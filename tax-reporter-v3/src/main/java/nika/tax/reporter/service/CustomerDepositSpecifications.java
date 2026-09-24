package nika.tax.reporter.service;

import java.time.LocalDateTime;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import nika.tax.reporter.postgres.domain.CustomerDeposit;

public final class CustomerDepositSpecifications {

    private CustomerDepositSpecifications() {
    }

    public static Specification<CustomerDeposit> build(CustomerDepositFilter filter) {
        Specification<CustomerDeposit> spec = Specification.allOf();

        if (filter == null) {
            return spec;
        }

        if (StringUtils.hasText(filter.getQ())) {
            String like = "%" + filter.getQ().trim().toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(cb.coalesce(root.get("clientName"), "")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("serviceName"), "")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("transactionGuid"), "")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("stampData"), "")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("sapReference"), "")), like)));
        }

        if (StringUtils.hasText(filter.getStatus())) {
            switch (filter.getStatus().toUpperCase()) {
                case "PROCESSED" -> spec = spec.and((root, query, cb) -> cb.isTrue(root.get("processed")));
                case "PENDING" -> spec = spec.and((root, query, cb) -> cb.isFalse(root.get("processed")));
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

        if (filter.getCustomerIds() != null && !filter.getCustomerIds().isEmpty()) {
            spec = spec.and((root, query, cb) -> root.get("customer").get("id").in(filter.getCustomerIds()));
        }

        return spec;
    }
}
