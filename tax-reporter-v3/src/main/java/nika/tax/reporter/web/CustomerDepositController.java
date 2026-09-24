package nika.tax.reporter.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import nika.tax.reporter.dto.CustomerDepositForm;
import nika.tax.reporter.postgres.domain.Customer;
import nika.tax.reporter.postgres.domain.CustomerDeposit;
import nika.tax.reporter.repository.CustomerRepository;
import nika.tax.reporter.service.CustomerDepositFilter;
import nika.tax.reporter.service.CustomerDepositMapper;
import nika.tax.reporter.service.CustomerDepositMatchingService;
import nika.tax.reporter.service.CustomerDepositService;
import nika.tax.reporter.service.StampLookupException;

@Controller
@RequestMapping("/customer-deposits")
@RequiredArgsConstructor
public class CustomerDepositController {

    private final CustomerDepositService customerDepositService;
    private final CustomerRepository customerRepository;
    private final CustomerDepositMatchingService customerDepositMatchingService;

    @GetMapping
    public String list(
            @RequestParam Optional<String> q,
            @RequestParam Optional<String> status,
            @RequestParam Optional<LocalDate> dateFrom,
            @RequestParam Optional<LocalDate> dateTo,
            @RequestParam Optional<BigDecimal> minAmount,
            @RequestParam Optional<BigDecimal> maxAmount,
            @RequestParam(name = "customerId", required = false) List<UUID> customerIds,
            @PageableDefault(size = 50, sort = "dateTimeTransaction", direction = Sort.Direction.DESC) Pageable pageable,
            Model model) {

        CustomerDepositFilter filter = buildFilter(q, status, dateFrom, dateTo, minAmount, maxAmount, customerIds);

        Page<CustomerDeposit> deposits = customerDepositService.search(filter, pageable);
        List<Customer> customers = customerRepository.findAll(Sort.by("clientName"));

        boolean hasActiveFilters = filter.getQ() != null && !filter.getQ().isBlank()
                || filter.getStatus() != null && !filter.getStatus().isBlank()
                || filter.getDateFrom() != null
                || filter.getDateTo() != null
                || filter.getMinAmount() != null
                || filter.getMaxAmount() != null
                || filter.getCustomerIds() != null && !filter.getCustomerIds().isEmpty();

        String sortProperty = pageable.getSort().stream()
                .findFirst().map(Sort.Order::getProperty).orElse("dateTimeTransaction");
        String sortDirection = pageable.getSort().stream()
                .findFirst().map(o -> o.getDirection().name().toLowerCase()).orElse("desc");

        model.addAttribute("deposits", deposits);
        model.addAttribute("pageSubtotal", customerDepositService.pageSubtotal(deposits));
        model.addAttribute("filter", filter);
        model.addAttribute("customers", customers);
        model.addAttribute("hasActiveFilters", hasActiveFilters);
        model.addAttribute("sortProperty", sortProperty);
        model.addAttribute("sortDirection", sortDirection);
        model.addAttribute("matchedCounts", deposits.getContent().stream()
                .collect(Collectors.toMap(CustomerDeposit::getId,
                        d -> customerDepositService.matchedTransactionCount(d.getId()))));

        return "customer-deposits";
    }

    /**
     * "Run Matching" — a global action (not scoped to one deposit or one customer), runs
     * CustomerDepositMatchingService.matchAll() across every customer that currently has
     * unmatched transactions. Deliberately not tied to any single row/filter, unlike the
     * Reconciliation "select all matching" action — there's no meaningful way to scope this
     * to "what's currently on screen" the way a filtered list can be, since matching depends
     * on each customer's full deposit/transaction history, not just what's currently visible.
     */
    @PostMapping("/match")
    public String runMatching(RedirectAttributes redirectAttributes) {
        String message = customerDepositMatchingService.matchAll()
                .map(result -> result.transactionsCompleted() == 0
                        ? "No unmatched transactions found to match."
                        : result.transactionsCompleted() + " transaction(s) matched across "
                                + result.customersProcessed() + " customer(s).")
                .orElse("Matching is already running — try again shortly.");
        redirectAttributes.addFlashAttribute("flashMessage", message);
        return "redirect:/customer-deposits";
    }

    @GetMapping("/{id}")
    public String view(@PathVariable UUID id, Model model) {
        CustomerDeposit deposit = customerDepositService.getOrThrow(id);
        model.addAttribute("deposit", deposit);
        model.addAttribute("matchedAllocations", customerDepositService.matchedAllocations(id));
        model.addAttribute("allocatedAmount", customerDepositService.allocatedAmount(id));
        model.addAttribute("remainingBalance", customerDepositService.remainingBalance(deposit));
        return "customer-deposit-view";
    }

    /**
     * Manual one-click "fetch stamp data from SQL Server" action, mirroring the
     * "Mark as Processed" pattern already used on the Transactions detail page —
     * an explicit action a person triggers, not something run automatically on
     * every save (a slow/unreachable SQL Server shouldn't be able to block or
     * slow down normal create/edit of a deposit).
     */
    @PostMapping("/{id}/fetch-stamp-data")
    public String fetchStampData(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        try {
            String outcome = customerDepositService.fetchStampDataFromExternalSource(id);
            redirectAttributes.addFlashAttribute("flashMessage", outcome);
        } catch (StampLookupException e) {
            redirectAttributes.addFlashAttribute("flashMessage", e.getMessage());
        }
        return "redirect:/customer-deposits/" + id;
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable UUID id, Model model) {
        CustomerDeposit entity = customerDepositService.getOrThrow(id);
        addFormToModel(model, CustomerDepositMapper.toForm(entity));
        model.addAttribute("isEdit", true);
        return "customer-deposit-form";
    }

    @PostMapping("/{id}/edit")
    public String update(@PathVariable UUID id,
                          @Valid @ModelAttribute("form") CustomerDepositForm form,
                          BindingResult bindingResult,
                          Model model,
                          RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            form.setId(id);
            model.addAttribute("isEdit", true);
            return "customer-deposit-form";
        }
        customerDepositService.update(id, form);
        redirectAttributes.addFlashAttribute("flashMessage", "Customer deposit updated.");
        return "redirect:/customer-deposits/" + id;
    }

    private void addFormToModel(Model model, CustomerDepositForm form) {
        model.addAttribute("form", form);
        model.addAttribute(BindingResult.MODEL_KEY_PREFIX + "form", new BeanPropertyBindingResult(form, "form"));
    }

    private CustomerDepositFilter buildFilter(
            Optional<String> q, Optional<String> status,
            Optional<LocalDate> dateFrom, Optional<LocalDate> dateTo,
            Optional<BigDecimal> minAmount, Optional<BigDecimal> maxAmount,
            List<UUID> customerIds) {
        return CustomerDepositFilter.builder()
                .q(q.orElse(null))
                .status(status.orElse(null))
                .dateFrom(dateFrom.orElse(null))
                .dateTo(dateTo.orElse(null))
                .minAmount(minAmount.orElse(null))
                .maxAmount(maxAmount.orElse(null))
                .customerIds(customerIds != null ? customerIds : List.of())
                .build();
    }
}
