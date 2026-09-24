package nika.tax.reporter.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import nika.tax.reporter.postgres.domain.CustomerDeposit;

public interface CustomerDepositRepository
        extends JpaRepository<CustomerDeposit, UUID>, JpaSpecificationExecutor<CustomerDeposit> {

    /** A customer's deposits, oldest first — used for display (list/detail pages), not matching
     * (see findAvailableDeposits below for that — this one doesn't filter out exhausted deposits). */
    List<CustomerDeposit> findByCustomer_IdOrderByDateTimeTransactionAsc(UUID customerId);

    /** The FIFO input queue for CustomerDepositMatchingService: only deposits with balance
     * left (currentBalance > minBalance), oldest first, so an exhausted deposit is never
     * revisited. minBalance is normally BigDecimal.ZERO — kept as a parameter (matching the
     * real app's method signature) rather than hardcoded, in case a minimum-usable-balance
     * threshold above zero is ever wanted without changing the query itself. */
    @Query("select d from CustomerDeposit d where d.customer.id = :customerId and d.currentBalance > :minBalance "
            + "order by d.dateTimeTransaction asc")
    List<CustomerDeposit> findAvailableDeposits(@Param("customerId") UUID customerId, @Param("minBalance") BigDecimal minBalance);

    /** Used by the Oracle ingestion job (OracleJdbcCustomerDepositJob) to check whether a
     * deposit has already been pulled in, before inserting a duplicate. transactionGuid is
     * unique = true on this entity, so this is guaranteed 0 or 1 results — safely, unlike
     * CardTransaction's own transactionGuid, which has no such constraint. */
    Optional<CustomerDeposit> getByTransactionGuid(String transactionGuid);

    /** The input queue for SqlServerJdbcCustomerDepositJob: every deposit that hasn't had its
     * stampData resolved yet (either never looked up, or looked up and found nothing so far —
     * this doesn't distinguish the two, so a deposit with no matching stamp yet in the
     * external Stamp table gets retried on every run of that job, not skipped after one miss).
     * No pagination/oldest-first ordering here, unlike the deposit-matching queue above —
     * kept simple to match what this job actually needs, revisit if this list ever grows large
     * enough that loading it all at once becomes a real concern. */
    @Query("select d from CustomerDeposit d where d.stampData is null or d.stampData = ''")
    List<CustomerDeposit> findNullOrEmpty();
}
