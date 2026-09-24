package nika.tax.reporter.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import nika.tax.reporter.postgres.domain.CardTransaction;
import nika.tax.reporter.postgres.domain.Reconciliation;
import nika.tax.reporter.repository.CardTransactionRepository;
import nika.tax.reporter.repository.ReconciliationRepository;

@Service
@RequiredArgsConstructor
public class ReconciliationService {

    /**
     * Upper bound for a single "reconcile everything matching this filter" bulk action.
     * Not a hard technical limit — just a sane guardrail so a broad/empty filter can't
     * silently reconcile an enormous, unreviewed batch in one click. Narrow the filter
     * (e.g. by date range) to go above this in more than one pass.
     */
    private static final int MAX_BULK_RECONCILE = 25_000;

    private final ReconciliationRepository repository;
    private final CardTransactionRepository cardTransactionRepository;

    public Page<Reconciliation> search(ReconciliationFilter filter, Pageable pageable) {
        return repository.findAll(ReconciliationSpecifications.build(filter), pageable);
    }

    public Reconciliation getOrThrow(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No reconciliation found with id " + id));
    }

    public Page<CardTransaction> childTransactions(UUID reconciliationId, Pageable pageable) {
        return cardTransactionRepository.findByReconciliation_Id(reconciliationId, pageable);
    }

    /** See CardTransactionRepository.existsOutOfScopeTransactionInReconciliation — a batch-wide
     * check independent of pagination, not limited to whatever page of child transactions is
     * currently loaded. Short-circuits on an empty allowedCustomerIds without querying — an
     * empty "not in (...)" clause isn't something to rely on Hibernate handling consistently,
     * and the correct security answer for "zero accessible customers" is "nothing is
     * accessible" regardless of what the query would have returned. */
    public boolean hasOutOfScopeTransaction(UUID reconciliationId, Set<UUID> allowedCustomerIds) {
        if (allowedCustomerIds == null || allowedCustomerIds.isEmpty()) {
            return true;
        }
        return cardTransactionRepository.existsOutOfScopeTransactionInReconciliation(reconciliationId, allowedCustomerIds);
    }

    public long childTransactionCount(UUID reconciliationId) {
        return cardTransactionRepository.countByReconciliation_Id(reconciliationId);
    }

    public BigDecimal childTransactionTotal(UUID reconciliationId) {
        BigDecimal sum = cardTransactionRepository.sumTotalAmountByReconciliationId(reconciliationId);
        return sum != null ? sum : BigDecimal.ZERO;
    }

    /**
     * Creates the batch and links the given transactions to it in one go.
     *
     * @param restrictToCustomerIds security-only, null means unrestricted (Admin/Analyst) —
     *        when non-null (a ROLE_CUSTOMER_SCOPED caller), every selected transaction must
     *        belong to one of these customers or the whole reconcile is rejected. This is what
     *        stops a scoped user from reconciling — or even proving the existence of — a
     *        transaction outside their assigned customers by guessing/tampering with the
     *        selectedIds form field, since the list page's own filtering only governs what
     *        gets displayed as selectable, not what this endpoint would actually accept.
     * @throws ReconciliationException if the selection is empty, if any selected transaction
     *         is already part of a different reconciliation (defensive — stops a transaction
     *         silently being re-parented if two people act on overlapping selections at close
     *         to the same time), or if restrictToCustomerIds is set and any selected
     *         transaction falls outside it.
     */
    public Reconciliation reconcile(LocalDate date, String reference, List<UUID> transactionIds,
                                     Set<UUID> restrictToCustomerIds) {
        if (transactionIds == null || transactionIds.isEmpty()) {
            throw new ReconciliationException("Select at least one transaction to reconcile.");
        }
        if (date == null) {
            throw new ReconciliationException("Reconciliation date is required.");
        }

        List<CardTransaction> transactions = cardTransactionRepository.findAllById(transactionIds);
        if (transactions.size() != transactionIds.size()) {
            throw new ReconciliationException("One or more selected transactions could not be found — they may have been changed since you loaded this page. Refresh and try again.");
        }

        if (restrictToCustomerIds != null) {
            boolean anyOutOfScope = transactions.stream().anyMatch(t ->
                    t.getCustomer() == null || !restrictToCustomerIds.contains(t.getCustomer().getId()));
            if (anyOutOfScope) {
                throw new ReconciliationException("One or more selected transactions are outside your assigned customers.");
            }
        }

        List<CardTransaction> alreadyReconciled = transactions.stream()
                .filter(t -> t.getReconciliation() != null)
                .toList();
        if (!alreadyReconciled.isEmpty()) {
            throw new ReconciliationException(alreadyReconciled.size()
                    + " of the selected transactions are already part of another reconciliation — refresh the list and try again.");
        }

        Reconciliation batch = Reconciliation.builder()
                .reconciliationDate(date)
                .reference(StringUtils.hasText(reference) ? reference.trim() : null)
                .build();
        batch = repository.saveAndFlush(batch);

        for (CardTransaction tx : transactions) {
            tx.setReconciliation(batch);
        }
        cardTransactionRepository.saveAll(transactions);

        return batch;
    }

    /**
     * "Reconcile everything matching this filter" — the cross-page version of
     * {@link #reconcile}. Deliberately does NOT accept a list of IDs from the client;
     * it re-derives the matching set server-side from the same CardTransactionFilter/
     * CardTransactionSpecifications used by the ledger list itself (which already
     * excludes anything already reconciled), so this can never touch more than what
     * the person was actually looking at, and never depends on the browser having
     * submitted a huge list of individual transaction IDs.
     *
     * @throws ReconciliationException if nothing matches, or if the matching count
     *         exceeds {@link #MAX_BULK_RECONCILE}.
     */
    public Reconciliation reconcileMatching(LocalDate date, String reference, CardTransactionFilter filter) {
        if (date == null) {
            throw new ReconciliationException("Reconciliation date is required.");
        }

        var spec = CardTransactionSpecifications.build(filter);
        long matching = cardTransactionRepository.count(spec);

        if (matching == 0) {
            throw new ReconciliationException("No transactions match the current filter.");
        }
        if (matching > MAX_BULK_RECONCILE) {
            throw new ReconciliationException(matching + " transactions match your current filter, which is more than the "
                    + MAX_BULK_RECONCILE + " limit for one reconciliation. Narrow the filter (e.g. a shorter date range) and try again.");
        }

        List<CardTransaction> transactions = cardTransactionRepository.findAll(spec);

        Reconciliation batch = Reconciliation.builder()
                .reconciliationDate(date)
                .reference(StringUtils.hasText(reference) ? reference.trim() : null)
                .build();
        batch = repository.saveAndFlush(batch);

        for (CardTransaction tx : transactions) {
            tx.setReconciliation(batch);
        }
        cardTransactionRepository.saveAll(transactions);

        return batch;
    }
}
