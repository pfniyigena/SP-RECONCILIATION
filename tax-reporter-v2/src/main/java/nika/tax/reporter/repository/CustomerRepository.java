package nika.tax.reporter.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import nika.tax.reporter.postgres.domain.Customer;

public interface CustomerRepository
        extends JpaRepository<Customer, UUID>, JpaSpecificationExecutor<Customer> {

    /**
     * Bulk-marks every not-yet-allocated customer that appears in at least one
     * ROLE_CUSTOMER_SCOPED user's accessibleCustomers grant as allocated=true, in one
     * statement — used on startup (see DataInitializer) rather than a findAll()+loop+save()
     * per customer, which would mean loading every Customer row into memory just to flip one
     * flag on each. At this app's stated scale (potentially millions of customers) that's the
     * difference between one UPDATE and millions of round trips.
     *
     * Scoped to ROLE_CUSTOMER_SCOPED specifically because that's the only role with an actual,
     * explicit "which customers" list (AppUser.accessibleCustomers) — ROLE_ANALYST has no
     * customer-scoping concept at all under this app's RBAC (it sees everyone, unrestricted),
     * so there's nothing meaningful to compute for "customers accessible by Analyst" beyond
     * "all of them", which doesn't actually encode a useful signal for this flag. A customer
     * not granted to any ROLE_CUSTOMER_SCOPED user is deliberately NOT auto-marked here —
     * Admin can still flip it manually via the Customer form/list, same as any other customer.
     *
     * @Transactional here on purpose — @Modifying queries require an active transaction, and
     * the intended caller (a CommandLineRunner at startup) isn't transactional by default;
     * annotating the repository method itself guarantees this works regardless of caller.
     *
     * @return how many rows were actually updated (customers that were false/null before).
     */
    @Transactional
    @Modifying
    @Query("update Customer c set c.allocated = true "
            + "where (c.allocated = false or c.allocated is null) "
            + "and c.id in (select cust.id from AppUser u join u.accessibleCustomers cust where u.role = 'ROLE_CUSTOMER_SCOPED')")
    int markCustomerScopedAccessibleAsAllocated();
}
