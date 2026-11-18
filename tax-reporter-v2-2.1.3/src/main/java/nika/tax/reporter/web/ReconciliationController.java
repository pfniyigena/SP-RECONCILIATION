package nika.tax.reporter.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import nika.tax.reporter.postgres.domain.CardTransaction;
import nika.tax.reporter.postgres.domain.Reconciliation;
import nika.tax.reporter.service.CustomerAccessScopeService;
import nika.tax.reporter.service.ReconciliationFilter;
import nika.tax.reporter.service.ReconciliationService;

@Controller
@RequestMapping("/reconciliations")
@RequiredArgsConstructor
public class ReconciliationController {

    private final ReconciliationService reconciliationService;
    private final CustomerAccessScopeService customerAccessScopeService;

    @GetMapping
    public String list(@RequestParam Optional<String> q,
                        @RequestParam Optional<LocalDate> dateFrom,
                        @RequestParam Optional<LocalDate> dateTo,
                        @PageableDefault(size = 25, sort = "reconciliationDate", direction = Sort.Direction.DESC) Pageable pageable,
                        Authentication authentication,
                        Model model) {
        ReconciliationFilter filter = ReconciliationFilter.builder()
                .q(q.orElse(null))
                .dateFrom(dateFrom.orElse(null))
                .dateTo(dateTo.orElse(null))
                .build();

        // Scoped users only ever see reconciliation batches composed ENTIRELY of their
        // assigned customers' transactions — see ReconciliationSpecifications for why "any"
        // isn't good enough here (a mixed batch must stay fully invisible, not partially shown).
        if (customerAccessScopeService.isScoped(authentication)) {
            filter.setRestrictToCustomerIds(customerAccessScopeService.accessibleCustomerIds(authentication));
        }

        Page<Reconciliation> reconciliations = reconciliationService.search(filter, pageable);

        model.addAttribute("reconciliations", reconciliations);
        model.addAttribute("filter", filter);
        // Per-row transaction count/total — computed here rather than embedded as a
        // derived property on the entity, since it depends on CardTransaction, not
        // anything Reconciliation itself owns.
        model.addAttribute("counts", reconciliations.getContent().stream()
                .collect(Collectors.toMap(Reconciliation::getId,
                        r -> reconciliationService.childTransactionCount(r.getId()))));
        model.addAttribute("totals", reconciliations.getContent().stream()
                .collect(Collectors.toMap(Reconciliation::getId,
                        r -> reconciliationService.childTransactionTotal(r.getId()))));

        return "reconciliations";
    }

    @GetMapping("/{id}")
    public String view(@PathVariable UUID id,
                        @PageableDefault(size = 50, sort = "dateTimeTransaction", direction = Sort.Direction.DESC) Pageable pageable,
                        Authentication authentication, Model model) {
        Reconciliation batch = reconciliationService.getOrThrow(id);

        // Same reasoning as CardTransactionController.assertAccessible — the list page's own
        // filtering only governs what's offered as clickable, not what this endpoint would
        // load if a scoped user navigated here directly (guessed/bookmarked URL). A batch with
        // even one transaction outside the caller's assigned customers is treated as if it
        // doesn't exist. This check is deliberately batch-wide (a dedicated query, see
        // ReconciliationService.hasOutOfScopeTransaction), NOT limited to whichever page of
        // child transactions ends up loaded below — checking only the current page would miss
        // an out-of-scope transaction sitting on some other page of the same batch.
        if (customerAccessScopeService.isScoped(authentication)) {
            Set<UUID> allowed = customerAccessScopeService.accessibleCustomerIds(authentication);
            if (reconciliationService.hasOutOfScopeTransaction(id, allowed)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No reconciliation found with id " + id);
            }
        }

        Page<CardTransaction> transactions = reconciliationService.childTransactions(id, pageable);

        // childTransactionTotal, not summed from the loaded page — this must reflect the
        // WHOLE batch's total regardless of which page is being viewed, the same reason the
        // scoping check above can't just look at the loaded page either. A page-limited sum
        // would silently become a page-subtotal instead of the batch's actual total the moment
        // this page has more than one page of transactions.
        BigDecimal total = reconciliationService.childTransactionTotal(id);

        model.addAttribute("batch", batch);
        model.addAttribute("transactions", transactions);
        model.addAttribute("total", total);
        return "reconciliation-view";
    }
}
