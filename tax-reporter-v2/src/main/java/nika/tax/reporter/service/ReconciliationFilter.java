package nika.tax.reporter.service;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ReconciliationFilter {
    private String q; // matches against reference
    private LocalDate dateFrom;
    private LocalDate dateTo;
    /** Security-only, never set from request params — populated by the controller from
     * CustomerAccessScopeService when the current user is ROLE_CUSTOMER_SCOPED. Null means
     * unrestricted (Admin/Analyst). See ReconciliationSpecifications for what this actually
     * restricts: a reconciliation is only visible if EVERY transaction in it belongs to one of
     * these customers, not just some — a batch mixing a scoped customer's transactions with
     * anyone else's must stay invisible to that scoped user, not partially shown. */
    private Set<UUID> restrictToCustomerIds;
}
