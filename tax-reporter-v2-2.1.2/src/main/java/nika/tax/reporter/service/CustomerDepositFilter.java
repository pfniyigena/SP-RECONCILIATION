package nika.tax.reporter.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CustomerDepositFilter {
    private String q;
    private String status; // PROCESSED | PENDING | null (all) — only two states: no success/failure split like CardTransaction
    private LocalDate dateFrom;
    private LocalDate dateTo;
    private BigDecimal minAmount;
    private BigDecimal maxAmount;
    private List<UUID> customerIds;
    /** Security-only, never set from request params — populated by the controller from
     * CustomerAccessScopeService when the current user is ROLE_CUSTOMER_SCOPED. Null means
     * unrestricted (Admin/Analyst). Same pattern as CardTransactionFilter's own field. */
    private Set<UUID> restrictToCustomerIds;
}
