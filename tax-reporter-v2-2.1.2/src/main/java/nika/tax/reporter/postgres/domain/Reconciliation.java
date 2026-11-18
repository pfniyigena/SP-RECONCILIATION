package nika.tax.reporter.postgres.domain;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * A reconciliation batch — a set of CardTransactions marked as reconciled together
 * on a given date. CardTransaction holds the FK (reconciliation_id); once a
 * transaction is linked to one of these, CardTransactionSpecifications excludes
 * it from the normal transaction ledger everywhere (list, export, customer-scoped
 * views) — it's only visible again via this record's own detail page.
 */
@Data
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = true)
@Entity
@Table(name = "reconciliation")
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Reconciliation extends AbstractEntity {

	private static final long serialVersionUID = 1L;

	@Column(name = "reconciliation_date", nullable = false)
	private LocalDate reconciliationDate;

	/**
	 * Optional free-text label (e.g. "July batch 2" or a bank statement reference) —
	 * not asked for explicitly, added since a list of reconciliations with nothing
	 * but a date and nothing else to tell two same-day batches apart isn't very
	 * usable. Left nullable/optional so it's not a burden if unused.
	 */
	@Column(name = "reference")
	private String reference;
}
