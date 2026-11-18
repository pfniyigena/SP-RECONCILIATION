package nika.tax.reporter.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import nika.tax.reporter.postgres.domain.DepositCardTransaction;

public interface DepositCardTransactionRepository extends JpaRepository<DepositCardTransaction, UUID> {

    List<DepositCardTransaction> findByDeposit_Id(UUID depositId);

    long countByDeposit_Id(UUID depositId);

    List<DepositCardTransaction> findByTransaction_Id(UUID transactionId);

    /**
     * Bulk variant of findByTransaction_Id, for building an ebmNumber lookup map across a
     * whole page of transactions in one query — used by CardTransactionExportService instead
     * of calling CardTransaction.getEbmNumber() per row, which would lazy-load
     * depositAllocations one row at a time (N+1) since export queries don't go through
     * CardTransactionRepository.findWithAllocationsById's @EntityGraph.
     *
     * @EntityGraph added after a real LazyInitializationException: deposit is a LAZY @ManyToOne
     * on DepositCardTransaction, and CardTransactionExportService.writeExcel/writePdf run
     * asynchronously on ExportJobService's background thread pool — by the time
     * buildEbmNumberMap called allocation.getDeposit().getStampNumber(), this query's own
     * (short-lived, auto-closing) session had already ended, so the lazy proxy had nothing
     * left to resolve against ("no Session"). Fetching deposit eagerly in the same query means
     * it arrives as fully-loaded data rather than a proxy, so it stays usable regardless of
     * session lifetime or which thread eventually reads it — sidesteps needing to reason about
     * transaction boundaries across a background-thread export entirely, rather than trying to
     * keep a session open for the whole (potentially slow) streamed write.
     */
    @EntityGraph(attributePaths = {"deposit"})
    List<DepositCardTransaction> findByTransaction_IdIn(List<UUID> transactionIds);

    /**
     * Bulk-fetch, per customer, how much of EACH of their transactions is already allocated —
     * one query covering every transaction for this customer, not one query per transaction.
     * This is what CustomerDepositMatchingService uses to resume a partially-allocated
     * transaction correctly on a later run instead of restarting from its full amount (the
     * double-allocation bug this whole rework exists to fix — see that service's javadoc).
     */
    @Query("select a.transaction.id as transactionId, coalesce(sum(a.allocatedAmount), 0) as allocatedAmount "
            + "from DepositCardTransaction a where a.customer.id = :customerId "
            + "group by a.transaction.id")
    List<TransactionAllocationRow> sumAllocatedAmountByTransactionForCustomer(@Param("customerId") UUID customerId);

    interface TransactionAllocationRow {
        UUID getTransactionId();
        BigDecimal getAllocatedAmount();
    }
}
