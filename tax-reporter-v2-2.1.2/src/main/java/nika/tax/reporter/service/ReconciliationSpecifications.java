package nika.tax.reporter.service;

import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import nika.tax.reporter.postgres.domain.CardTransaction;
import nika.tax.reporter.postgres.domain.Reconciliation;

public final class ReconciliationSpecifications {

    private ReconciliationSpecifications() {
    }

    public static Specification<Reconciliation> build(ReconciliationFilter filter) {
        Specification<Reconciliation> spec = Specification.allOf();

        if (filter == null) {
            return spec;
        }

        if (StringUtils.hasText(filter.getQ())) {
            String like = "%" + filter.getQ().trim().toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.like(cb.lower(cb.coalesce(root.get("reference"), "")), like));
        }

        if (filter.getDateFrom() != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("reconciliationDate"), filter.getDateFrom()));
        }

        if (filter.getDateTo() != null) {
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("reconciliationDate"), filter.getDateTo()));
        }

        if (filter.getRestrictToCustomerIds() != null) {
            var allowed = filter.getRestrictToCustomerIds();
            spec = spec.and((root, query, cb) -> {
                if (allowed.isEmpty()) {
                    return cb.disjunction(); // scoped to nothing — matches nothing, same as CardTransactionSpecifications
                }

                // A reconciliation is visible to a scoped user only if EVERY transaction in it
                // belongs to one of their allowed customers — not "at least one". A batch that
                // mixes a scoped customer's transactions with anyone else's must stay entirely
                // invisible, not partially shown (showing it would leak the existence of, and
                // the running total for, transactions belonging to customers this user has no
                // access to at all).
                Subquery<UUID> outOfScope = query.subquery(UUID.class);
                Root<CardTransaction> outOfScopeTx = outOfScope.from(CardTransaction.class);
                outOfScope.select(outOfScopeTx.get("id"))
                        .where(cb.and(
                                cb.equal(outOfScopeTx.get("reconciliation"), root),
                                cb.or(
                                        cb.isNull(outOfScopeTx.get("customer")),
                                        cb.not(outOfScopeTx.get("customer").get("id").in(allowed)))));

                // A reconciliation with zero transactions would vacuously pass the "no
                // out-of-scope transaction" check above with nothing to actually show — exclude
                // those too, though in practice every reconciliation is created with at least
                // one transaction already.
                Subquery<UUID> anyTransaction = query.subquery(UUID.class);
                Root<CardTransaction> anyTx = anyTransaction.from(CardTransaction.class);
                anyTransaction.select(anyTx.get("id"))
                        .where(cb.equal(anyTx.get("reconciliation"), root));

                return cb.and(cb.not(cb.exists(outOfScope)), cb.exists(anyTransaction));
            });
        }

        return spec;
    }
}
