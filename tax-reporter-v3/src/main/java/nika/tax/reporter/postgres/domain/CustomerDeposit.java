package nika.tax.reporter.postgres.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.*;

@Data
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = true)
@Entity
@Table(name = "customer_deposit")
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CustomerDeposit extends AbstractEntity {

	private static final long serialVersionUID = 1L;

	@Column(name = "date_time_txs")
	private LocalDateTime dateTimeTransaction;
	@Column(name = "client_name", nullable = true)
	private String clientName;
	@Column(name = "service_name", nullable = true)
	private String serviceName;
	@Column(name = "total_amount")
	@Builder.Default
	private BigDecimal totalAmount = BigDecimal.ZERO;
	/**
	 * The live, denormalized running balance — decremented directly as
	 * CustomerDepositMatchingService allocates transactions against this deposit, rather
	 * than being computed on demand from a SUM over allocations. Reading it is therefore
	 * O(1), not an aggregate query; correctness depends on every write path that touches
	 * allocations also updating this field in the same transaction (see
	 * CustomerDepositMatchingService — it's the only writer).
	 */
	@Column(name = "current_balance")
	@Builder.Default
	private BigDecimal currentBalance = BigDecimal.ZERO;
	@Column(name = "service_id")
	private Integer serviceId;
	@Column(name = "client_id")
	private Integer clientId;
	@Column(name = "trn_guid", unique = true)
	private String transactionGuid;
	@Column(name = "stamp_data")
	private String stampData;
	@Column(name = "sap_reference")
	private String sapReference;
	@Column(name = "processed")
	@Builder.Default
	private Boolean processed = Boolean.FALSE;
	@Transient
	@Getter(AccessLevel.NONE)
	private String stampNumber;
	@ToString.Exclude
	@ManyToOne
	@JoinColumn(name = "customer_id")
	private Customer customer;
	/**
	 * Every allocation record that has ever drawn against this deposit — replaces an
	 * earlier, wrong assumption in this project that a deposit relates to transactions
	 * directly; the real model routes through DepositCardTransaction so one deposit can
	 * fund pieces of many transactions, and one transaction can draw from many deposits.
	 */
	@ToString.Exclude
	@OneToMany(mappedBy = "deposit")
	@Builder.Default
	private List<DepositCardTransaction> allocations = new ArrayList<>();

	/**
	 * Reverted to the original, unguarded form at explicit request — this will throw
	 * ArrayIndexOutOfBoundsException on any stampData with fewer than 4 comma-separated
	 * segments. Called from CardTransaction.getEbmNumber(), the transaction/deposit view
	 * pages, and both Excel/PDF exports, so a malformed stampData anywhere in that chain will
	 * surface as a hard failure at whichever of those touches it, not a graceful null the way
	 * the guarded version (split(",", 4) + a length check) handled it.
	 */
	public String getStampNumber() {
		if (this.stampData != null && !this.stampData.isEmpty())
			this.stampNumber = this.stampData.split(",")[0] + "/" + this.stampData.split(",")[1] + "/"
					+ this.stampData.split(",")[3];
		return this.stampNumber;
	}
}
