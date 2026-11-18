package nika.tax.reporter.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TaxReporterInvoiceFilter {
    private String q;
    private String status; // PROCESSED | FAILED | PENDING | null (all)
    private LocalDate dateFrom;
    private LocalDate dateTo;
    private BigDecimal minAmount;
    private BigDecimal maxAmount;
    private String plateNumber;
    /** Non-null = restrict to invoices matched to these customers' card transactions (ROLE_CUSTOMER_SCOPED). Empty = nothing. */
    private Set<UUID> restrictToCustomerIds;
}
