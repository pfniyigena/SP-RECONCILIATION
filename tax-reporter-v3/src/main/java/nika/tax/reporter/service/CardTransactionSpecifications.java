package nika.tax.reporter.service;

import java.time.LocalDateTime;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import nika.tax.reporter.postgres.domain.CardTransaction;

public final class CardTransactionSpecifications {

    private CardTransactionSpecifications() {
    }

    public static Specification<CardTransaction> build(CardTransactionFilter filter) {
        // Unconditional: a reconciled transaction is "closed" and should never appear in the
        // normal ledger (list, export, customer-scoped views) regardless of what else is
        // filtered — it's only visible again via its Reconciliation's own detail page, which
        // queries CardTransactionRepository.findByReconciliation_Id directly, bypassing this
        // specification entirely on purpose.
        Specification<CardTransaction> spec = (root, query, cb) -> cb.isNull(root.get("reconciliation"));

        if (filter == null) {
            return spec;
        }

        if (StringUtils.hasText(filter.getQ())) {
            String like = "%" + filter.getQ().trim().toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(cb.coalesce(root.get("clientName"), "")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("cardNumber"), "")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("plateNumber"), "")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("posName"), "")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("transactionGuid"), "")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("sdcId"), "")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("stampData"), "")), like)));
        }

        if (StringUtils.hasText(filter.getStatus())) {
            switch (filter.getStatus().toUpperCase()) {
                case "PROCESSED" -> spec = spec.and((root, query, cb) ->
                        cb.and(cb.isTrue(root.get("processed")), cb.isTrue(root.get("success"))));
                case "FAILED" -> spec = spec.and((root, query, cb) ->
                        cb.and(cb.isTrue(root.get("processed")), cb.isFalse(root.get("success"))));
                case "PENDING" -> spec = spec.and((root, query, cb) ->
                        cb.isFalse(root.get("processed")));
                default -> {
                    // unrecognized value: ignore rather than fail the whole query
                }
            }
        }

        if (filter.getDateFrom() != null) {
            LocalDateTime from = filter.getDateFrom().atStartOfDay();
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("dateTimeTransaction"), from));
        }

        if (filter.getDateTo() != null) {
            LocalDateTime to = filter.getDateTo().plusDays(1).atStartOfDay();
            spec = spec.and((root, query, cb) -> cb.lessThan(root.get("dateTimeTransaction"), to));
        }

        if (filter.getMinAmount() != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("totalAmount"), filter.getMinAmount()));
        }

        if (filter.getMaxAmount() != null) {
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("totalAmount"), filter.getMaxAmount()));
        }

        if (filter.getMachineIds() != null && !filter.getMachineIds().isEmpty()) {
            spec = spec.and((root, query, cb) -> root.get("machine").get("id").in(filter.getMachineIds()));
        }

        if (filter.getCustomerIds() != null && !filter.getCustomerIds().isEmpty()) {
            spec = spec.and((root, query, cb) -> root.get("customer").get("id").in(filter.getCustomerIds()));
        }

        // Security-level scoping for ROLE_CUSTOMER_SCOPED users (see CustomerAccessScopeService).
        // ANDed on top of whatever customerIds the user picked above, so a scoped user's own
        // filter choices can only ever narrow further within their allowed set, never escape it.
        if (filter.getRestrictToCustomerIds() != null) {
            if (filter.getRestrictToCustomerIds().isEmpty()) {
                // Scoped, but nothing assigned yet — must see nothing, not everything.
                spec = spec.and((root, query, cb) -> cb.disjunction());
            } else {
                spec = spec.and((root, query, cb) -> root.get("customer").get("id").in(filter.getRestrictToCustomerIds()));
            }
        }

        return spec;
    }
}
