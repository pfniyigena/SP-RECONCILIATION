package nika.tax.reporter.repository;

import java.math.BigDecimal;
import java.util.List;
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
}
