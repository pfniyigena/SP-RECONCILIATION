package nika.tax.reporter.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import nika.tax.reporter.dto.CardTransactionForm;
import nika.tax.reporter.postgres.domain.CardTransaction;
import nika.tax.reporter.postgres.domain.Customer;
import nika.tax.reporter.postgres.domain.TerminalMachine;
import nika.tax.reporter.repository.CustomerRepository;
import nika.tax.reporter.repository.TerminalMachineRepository;
import nika.tax.reporter.service.CardTransactionFilter;
import nika.tax.reporter.service.CardTransactionMapper;
import nika.tax.reporter.service.CardTransactionService;
import nika.tax.reporter.service.CustomerAccessScopeService;
import nika.tax.reporter.service.ReconciliationException;
import nika.tax.reporter.service.ReconciliationService;

@Controller
@RequestMapping("/transactions")
@RequiredArgsConstructor
public class CardTransactionController {

    private final CardTransactionService cardTransactionService;
    private final TerminalMachineRepository terminalMachineRepository;
    private final CustomerRepository customerRepository;
    private final CustomerAccessScopeService customerAccessScopeService;
    private final ReconciliationService reconciliationService;

    @GetMapping
    public String list(
            @RequestParam Optional<String> q,
            @RequestParam Optional<String> status,
            @RequestParam Optional<LocalDate> dateFrom,
            @RequestParam Optional<LocalDate> dateTo,
            @RequestParam Optional<BigDecimal> minAmount,
            @RequestParam Optional<BigDecimal> maxAmount,
            @RequestParam(name = "machineId", required = false) List<UUID> machineIds,
            @RequestParam(name = "customerId", required = false) List<UUID> customerIds,
            @PageableDefault(size = 50, sort = "dateTimeTransaction", direction = Sort.Direction.DESC) Pageable pageable,
            Authentication authentication,
            Model model) {

        CardTransactionFilter filter = buildFilter(q, status, dateFrom, dateTo, minAmount, maxAmount, machineIds, customerIds);

        List<Customer> customers = customerRepository.findAll(Sort.by("clientName"));

        if (customerAccessScopeService.isScoped(authentication)) {
            Set<UUID> allowed = customerAccessScopeService.accessibleCustomerIds(authentication);
            filter.setRestrictToCustomerIds(allowed);
            // Scoped users only ever see their own customers in the picker — this is UX,
            // not the actual security boundary (that's restrictToCustomerIds above, enforced
            // in the query itself regardless of what this list contains).
            customers = customers.stream().filter(c -> allowed.contains(c.getId())).collect(Collectors.toList());
        }

        Page<CardTransaction> transactions = cardTransactionService.search(filter, pageable);

        boolean hasActiveFilters = filter.getQ() != null && !filter.getQ().isBlank()
                || filter.getStatus() != null && !filter.getStatus().isBlank()
                || filter.getDateFrom() != null
                || filter.getDateTo() != null
                || filter.getMinAmount() != null
                || filter.getMaxAmount() != null
                || (filter.getMachineIds() != null && !filter.getMachineIds().isEmpty())
                || (filter.getCustomerIds() != null && !filter.getCustomerIds().isEmpty());

        String sortProperty = pageable.getSort().stream()
                .findFirst().map(Sort.Order::getProperty).orElse("dateTimeTransaction");
        String sortDirection = pageable.getSort().stream()
                .findFirst().map(o -> o.getDirection().name().toLowerCase()).orElse("desc");

        List<TerminalMachine> machines = terminalMachineRepository.findAll(Sort.by("posName"));

        model.addAttribute("transactions", transactions);
        model.addAttribute("pageSubtotal", cardTransactionService.pageSubtotal(transactions));
        model.addAttribute("filter", filter);
        model.addAttribute("hasActiveFilters", hasActiveFilters);
        model.addAttribute("sortProperty", sortProperty);
        model.addAttribute("sortDirection", sortDirection);
        model.addAttribute("machines", machines);
        model.addAttribute("customers", customers);
        model.addAttribute("selectedMachineLabel",
                selectedLabel(filter.getMachineIds(), machines, TerminalMachine::getId,
                        m -> m.getPosName() != null ? m.getPosName() : "Unnamed", "machine(s)"));
        model.addAttribute("selectedCustomerLabel",
                selectedLabel(filter.getCustomerIds(), customers, Customer::getId,
                        c -> c.getClientName() != null ? c.getClientName() : "Unnamed", "customer(s)"));

        return "transactions";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        CardTransactionForm form = CardTransactionForm.builder().build();
        addFormToModel(model, form);
        model.addAttribute("isEdit", false);
        return "transaction-form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") CardTransactionForm form,
                          BindingResult bindingResult,
                          Model model,
                          RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("isEdit", false);
            return "transaction-form";
        }
        CardTransaction saved = cardTransactionService.create(form);
        redirectAttributes.addFlashAttribute("flashMessage", "Transaction created.");
        return "redirect:/transactions/" + saved.getId();
    }

    @GetMapping("/{id}")
    public String view(@PathVariable UUID id, Authentication authentication, Model model) {
        CardTransaction entity = cardTransactionService.getOrThrow(id);
        assertAccessible(entity, authentication);
        model.addAttribute("tx", entity);
        return "transaction-view";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable UUID id, Authentication authentication, Model model) {
        CardTransaction entity = cardTransactionService.getOrThrow(id);
        assertAccessible(entity, authentication);
        CardTransactionForm form = CardTransactionMapper.toForm(entity);
        addFormToModel(model, form);
        model.addAttribute("isEdit", true);
        return "transaction-form";
    }

    @PostMapping("/{id}/edit")
    public String update(@PathVariable UUID id,
                          @Valid @ModelAttribute("form") CardTransactionForm form,
                          BindingResult bindingResult,
                          Authentication authentication,
                          Model model,
                          RedirectAttributes redirectAttributes) {
        assertAccessible(cardTransactionService.getOrThrow(id), authentication);
        if (bindingResult.hasErrors()) {
            form.setId(id);
            model.addAttribute("isEdit", true);
            return "transaction-form";
        }
        cardTransactionService.update(id, form);
        redirectAttributes.addFlashAttribute("flashMessage", "Transaction updated.");
        return "redirect:/transactions/" + id;
    }

    @PostMapping("/{id}/process")
    public String process(@PathVariable UUID id, Authentication authentication, RedirectAttributes redirectAttributes) {
        assertAccessible(cardTransactionService.getOrThrow(id), authentication);
        cardTransactionService.markProcessed(id);
        redirectAttributes.addFlashAttribute("flashMessage", "Transaction marked as processed.");
        return "redirect:/transactions/" + id;
    }

    /**
     * Bulk "mark selected transactions as reconciled" action from the ledger list page.
     * Now open to ROLE_CUSTOMER_SCOPED too (see SecurityConfig) — both paths below are scoped
     * to the caller's accessible customers when they're a scoped user, mirroring exactly how
     * list() already scopes the ledger view itself: the ID-list path validates every selected
     * transaction against ReconciliationService.reconcile's restrictToCustomerIds parameter,
     * and the reconcileAllMatching path scopes the filter itself before it ever reaches
     * reconcileMatching, so the underlying query can never return anything outside scope in
     * the first place.
     */
    @PostMapping("/reconcile")
    public String reconcile(@RequestParam(name = "selectedIds", required = false) List<UUID> selectedIds,
                             @RequestParam(name = "reconcileAllMatching", required = false, defaultValue = "false") boolean reconcileAllMatching,
                             @RequestParam LocalDate reconciliationDate,
                             @RequestParam Optional<String> reference,
                             @RequestParam Optional<String> q,
                             @RequestParam Optional<String> status,
                             @RequestParam Optional<LocalDate> dateFrom,
                             @RequestParam Optional<LocalDate> dateTo,
                             @RequestParam Optional<BigDecimal> minAmount,
                             @RequestParam Optional<BigDecimal> maxAmount,
                             @RequestParam(name = "machineId", required = false) List<UUID> machineIds,
                             @RequestParam(name = "customerId", required = false) List<UUID> customerIds,
                             Authentication authentication,
                             RedirectAttributes redirectAttributes) {
        Set<UUID> restrictToCustomerIds = customerAccessScopeService.isScoped(authentication)
                ? customerAccessScopeService.accessibleCustomerIds(authentication)
                : null;
        try {
            if (reconcileAllMatching) {
                // Cross-page path: re-derive the matching set server-side from the same
                // filter the ledger list itself would use — never trusts a client-submitted
                // ID list for this one, see ReconciliationService.reconcileMatching.
                CardTransactionFilter filter = buildFilter(q, status, dateFrom, dateTo, minAmount, maxAmount, machineIds, customerIds);
                filter.setRestrictToCustomerIds(restrictToCustomerIds);
                var batch = reconciliationService.reconcileMatching(reconciliationDate, reference.orElse(null), filter);
                redirectAttributes.addFlashAttribute("flashMessage", "All matching transactions reconciled.");
                return "redirect:/reconciliations/" + batch.getId();
            }

            var batch = reconciliationService.reconcile(reconciliationDate, reference.orElse(null), selectedIds, restrictToCustomerIds);
            redirectAttributes.addFlashAttribute("flashMessage",
                    (selectedIds != null ? selectedIds.size() : 0) + " transaction(s) reconciled.");
            return "redirect:/reconciliations/" + batch.getId();
        } catch (ReconciliationException e) {
            redirectAttributes.addFlashAttribute("flashMessage", e.getMessage());
            return "redirect:/transactions";
        }
    }

    /**
     * Blocks a ROLE_CUSTOMER_SCOPED user from reaching a transaction outside their
     * assigned customers via a direct/bookmarked/guessed URL — the list page's
     * filtering alone wouldn't stop that, since it only governs what the list
     * query returns, not what /transactions/{id} will load if asked directly.
     * A transaction with no customer at all is treated as inaccessible to scoped
     * users, the same safe-by-default choice as an empty accessibleCustomers set.
     */
    private void assertAccessible(CardTransaction entity, Authentication authentication) {
        if (!customerAccessScopeService.isScoped(authentication)) {
            return;
        }
        Set<UUID> allowed = customerAccessScopeService.accessibleCustomerIds(authentication);
        boolean ok = entity.getCustomer() != null && allowed.contains(entity.getCustomer().getId());
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No transaction found with id " + entity.getId());
        }
    }

    private void addFormToModel(Model model, CardTransactionForm form) {
        model.addAttribute("form", form);
        model.addAttribute(BindingResult.MODEL_KEY_PREFIX + "form", new BeanPropertyBindingResult(form, "form"));
    }

    /**
     * Plain-text summary for a multi-select filter chip, e.g. "Depot A, Depot B" or
     * "Depot A, Depot B, Depot C + 4 more". Shared by the machine and customer filters
     * so both chips behave identically.
     */
    private <T> String selectedLabel(List<UUID> selectedIds, List<T> allEntities,
                                      Function<T, UUID> idOf, Function<T, String> nameOf,
                                      String fallbackNoun) {
        if (selectedIds == null || selectedIds.isEmpty()) {
            return null;
        }
        List<String> names = allEntities.stream()
                .filter(e -> selectedIds.contains(idOf.apply(e)))
                .map(nameOf)
                .collect(Collectors.toList());
        if (names.isEmpty()) {
            return selectedIds.size() + " " + fallbackNoun;
        }
        if (names.size() <= 3) {
            return String.join(", ", names);
        }
        return String.join(", ", names.subList(0, 3)) + " + " + (names.size() - 3) + " more";
    }

    private CardTransactionFilter buildFilter(
            Optional<String> q, Optional<String> status,
            Optional<LocalDate> dateFrom, Optional<LocalDate> dateTo,
            Optional<BigDecimal> minAmount, Optional<BigDecimal> maxAmount,
            List<UUID> machineIds, List<UUID> customerIds) {
        return CardTransactionFilter.builder()
                .q(q.orElse(null))
                .status(status.orElse(null))
                .dateFrom(dateFrom.orElse(null))
                .dateTo(dateTo.orElse(null))
                .minAmount(minAmount.orElse(null))
                .maxAmount(maxAmount.orElse(null))
                .machineIds(machineIds != null ? machineIds : Collections.emptyList())
                .customerIds(customerIds != null ? customerIds : Collections.emptyList())
                .build();
    }
}
