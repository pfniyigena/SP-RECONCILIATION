package nika.tax.reporter.postgres.domain;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * One allocation record: "this much of this CardTransaction's amount was covered by this
 * CustomerDeposit." A transaction can have several of these (split across multiple deposits
 * if one deposit's balance wasn't enough to cover it); a deposit can have many too (funding
 * pieces of many different transactions over its lifetime). CustomerDepositMatchingService is
 * the only writer of these — see its javadoc for the allocation algorithm and the
 * double-allocation bug this replaces.
 */
@Data
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = true)
@Entity
@Table(name = "deposit_card_transaction")
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class DepositCardTransaction extends AbstractEntity {
	private static final long serialVersionUID = 1L;

	@ToString.Exclude
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "deposit_id", nullable = false)
	private CustomerDeposit deposit;

	@ToString.Exclude
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "card_transaction_id", nullable = false)
	private CardTransaction transaction;

	@ToString.Exclude
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "customer_id", nullable = false)
	private Customer customer;

	@Column(name = "allocated_amount")
	private BigDecimal allocatedAmount;
}
