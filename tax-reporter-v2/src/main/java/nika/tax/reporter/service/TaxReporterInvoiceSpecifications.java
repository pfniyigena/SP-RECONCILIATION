package nika.tax.reporter.service;

import java.time.LocalDateTime;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import nika.tax.reporter.postgres.domain.TaxReporterInvoice;

public final class TaxReporterInvoiceSpecifications {

    private TaxReporterInvoiceSpecifications() {
    }

    public static Specification<TaxReporterInvoice> build(TaxReporterInvoiceFilter filter) {
        Specification<TaxReporterInvoice> spec = Specification.allOf();

        if (filter == null) {
            return spec;
        }

        if (StringUtils.hasText(filter.getQ())) {
            String like = "%" + filter.getQ().trim().toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(cb.coalesce(root.get("registeredName"), "")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("registeredTin"), "")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("sdcId"), "")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("clientTin"), "")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("clientName"), "")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("clientPhone"), "")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("stampData"), "")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("plateNumber"), "")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("erpCode"), "")), like),
                    cb.like(cb.function("str", String.class, root.get("receiptNumber")), like)));
        }

        if (StringUtils.hasText(filter.getStatus())) {
            switch (filter.getStatus().toUpperCase()) {
                case "FAILED" -> spec = spec.and((root, query, cb) ->
                        cb.and(cb.isNotNull(root.get("failureReason")), cb.notEqual(root.get("failureReason"), "")));
                case "PROCESSED" -> spec = spec.and((root, query, cb) ->
                        cb.and(cb.isTrue(root.get("processed")),
                                cb.or(cb.isNull(root.get("failureReason")), cb.equal(root.get("failureReason"), ""))));
                case "PENDING" -> spec = spec.and((root, query, cb) ->
                        cb.and(cb.isFalse(root.get("processed")),
                                cb.or(cb.isNull(root.get("failureReason")), cb.equal(root.get("failureReason"), ""))));
                default -> {
                    // unrecognized value: ignore rather than fail the whole query
                }
            }
        }

        if (filter.getDateFrom() != null) {
            LocalDateTime from = filter.getDateFrom().atStartOfDay();
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("stampDate"), from));
        }

        if (filter.getDateTo() != null) {
            LocalDateTime to = filter.getDateTo().plusDays(1).atStartOfDay();
            spec = spec.and((root, query, cb) -> cb.lessThan(root.get("stampDate"), to));
        }

        if (filter.getMinAmount() != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("totalAmount"), filter.getMinAmount()));
        }

        if (filter.getMaxAmount() != null) {
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("totalAmount"), filter.getMaxAmount()));
        }

        if (StringUtils.hasText(filter.getPlateNumber())) {
            String like = "%" + filter.getPlateNumber().trim().toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.like(cb.lower(cb.coalesce(root.get("plateNumber"), "")), like));
        }

        return spec;
    }
}
