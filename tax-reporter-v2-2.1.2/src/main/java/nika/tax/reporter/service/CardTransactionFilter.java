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
public class CardTransactionFilter {
    private String q;
    private String status; // PROCESSED | FAILED | PENDING | null (all)
    private LocalDate dateFrom;
    private LocalDate dateTo;
    private BigDecimal minAmount;
    private BigDecimal maxAmount;
    private List<UUID> machineIds;
    private List<UUID> customerIds;

    /**
     * Security-level customer scoping for ROLE_CUSTOMER_SCOPED users — set by
     * the controller from the authenticated user's accessibleCustomers, never
     * from a request parameter. null = unrestricted (every other role).
     * Non-null but empty = restricted to zero customers (scoped user with
     * nothing assigned yet — must see nothing, not everything). Deliberately
     * separate from customerIds (the user's own filter picks) so this can't
     * be shown as a removable chip or overridden via the UI/URL — combining
     * both as separate AND'd predicates gives the correct intersection
     * automatically without any manual set-intersection logic.
     */
    private Set<UUID> restrictToCustomerIds;
}

