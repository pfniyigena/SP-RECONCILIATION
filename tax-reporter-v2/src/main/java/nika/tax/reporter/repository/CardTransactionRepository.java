package nika.tax.reporter.repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import nika.tax.reporter.postgres.domain.CardTransaction;

public interface CardTransactionRepository
        extends JpaRepository<CardTransaction, UUID>, JpaSpecificationExecutor<CardTransaction> {

    /** Bypasses the "exclude reconciled" default in CardTransactionSpecifications on purpose —
     * this is specifically for the Reconciliation detail page, the one place reconciled
     * transactions are meant to still be visible. Paginated — a batch can have far more
     * transactions than are reasonable to render on one page. */
    Page<CardTransaction> findByReconciliation_Id(UUID reconciliationId, Pageable pageable);

    long countByReconciliation_Id(UUID reconciliationId);

    @Query("select coalesce(sum(t.totalAmount), 0) from CardTransaction t where t.reconciliation.id = :id")
    BigDecimal sumTotalAmountByReconciliationId(@Param("id") UUID reconciliationId);

    /**
     * Cheap existence check covering the WHOLE batch, independent of pagination — used by
     * ReconciliationController.view()'s accessibility check. Deliberately not "does the
     * current page contain an out-of-scope transaction": once that page is paginated, checking
     * only the loaded page would miss an out-of-scope transaction sitting on some OTHER page of
     * the same batch, silently letting a mixed batch through as long as whichever page loaded
     * first happened to look clean. This queries across every transaction in the batch, not
     * just whatever's currently displayed.
     */
    @Query("select case when count(t) > 0 then true else false end from CardTransaction t "
            + "where t.reconciliation.id = :reconciliationId "
            + "and (t.customer is null or t.customer.id not in :allowedCustomerIds)")
    boolean existsOutOfScopeTransactionInReconciliation(@Param("reconciliationId") UUID reconciliationId,
                                                          @Param("allowedCustomerIds") Collection<UUID> allowedCustomerIds);

    /**
     * Eagerly fetches depositAllocations and each allocation's deposit in the same query —
     * used by the transaction detail page, which shows the matched-deposit list (getEbmNumber()
     * also walks this same collection). Without this, that collection is LAZY by default
     * (@OneToMany has no fetch type override on CardTransaction, so it defaults to LAZY),
     * meaning accessing it from the view after the request's transaction/session has closed
     * would throw LazyInitializationException — same class of bug documented earlier in this
     * project for AppUser.accessibleCustomers, fixed the same way (a JOIN FETCH-equivalent,
     * here via @EntityGraph instead of JPQL, but same effect).
     */
    @EntityGraph(attributePaths = {
            "depositAllocations",
            "depositAllocations.deposit"
    })
    Optional<CardTransaction> findWithAllocationsById(UUID id);

    // ---- CustomerDeposit matching (CustomerDepositMatchingService) ----
    //
    // Keyed off the `allocated` flag now, not a direct customerDeposit relation — CardTransaction
    // no longer has one; allocation is tracked via DepositCardTransaction (see that entity and
    // DepositCardTransactionRepository), which supports a transaction being split across more
    // than one deposit. `allocated` means "fully covered", not "has at least one allocation".

    /** Unallocated transactions for one customer, oldest first — the FIFO input queue for matching. */
    /** Unprocessed transactions for one SDC (stamp machine), oldest first — the input queue
     * for CardTransactionService.matchAndProcessTransactions. */
    List<CardTransaction> getBySdcIdAndProcessedOrderByDateTimeTransactionAsc(String sdcId, Boolean processed);

    List<CardTransaction> findByCustomer_IdAndAllocatedOrderByDateTimeTransactionAsc(UUID customerId, Boolean allocated);

    /** Distinct customers with at least one unallocated, customer-linked transaction — drives
     * CustomerDepositMatchingService.matchAll(), which only bothers running the per-customer
     * algorithm for customers that actually have something left to allocate. */
    @Query("select distinct t.customer.id from CardTransaction t where t.allocated = false and t.customer is not null")
    List<UUID> findDistinctCustomerIdsWithUnallocatedTransactions();

    /** Used by the Oracle ingestion jobs to check whether a transaction has already been
     * pulled in, before inserting a duplicate. Unlike CustomerDeposit.transactionGuid, this
     * entity's transactionGuid has no unique constraint — so this can throw
     * IncorrectResultSizeDataAccessException if two rows ever share one. Flagged, not silently
     * assumed safe. */
    Optional<CardTransaction> getByTransactionGuid(String transactionGuid);
}
