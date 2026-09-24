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
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = true)
@Entity
@Table(name = "card_transaction")
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CardTransaction extends AbstractEntity {
	private static final long serialVersionUID = 1L;
	@Column(name = "date_time_txs")
	private LocalDateTime dateTimeTransaction;
	@Column(name = "client_name",nullable = true)
	private String clientName;
	@Column(name = "card_number",nullable = true)
	private String cardNumber;
	@Column(name = "plate_number",nullable = true)
	private String plateNumber;
	@Column(name = "pos_name",nullable = true)
	private String posName;
	@Column(name = "pos_number")
	private Integer posNumber;
	@Column(name = "address_name",nullable = true)
	private String addressName;
	@Column(name = "address_description",nullable = true)
	private String addressDescription;
	@Column(name = "service_name",nullable = true)
	private String serviceName;
	@Column(name = "quantity")
	private BigDecimal quantity;
	@Column(name = "unit_price")
	private BigDecimal unitPrice;
	@Column(name = "total_amount")
	private BigDecimal totalAmount;
	@Column(name = "tag_number",nullable = true)
	private String tagNumber;
	@Column(name = "pos_id")
	private Integer posId;
	@Column(name = "service_id")
	private Integer serviceId;
	@Column(name = "client_id")
	private Integer clientId;
	@Column(name = "address_id")
	private Integer addressId;
	@Column(name = "trn_guid", unique = true)
	private String transactionGuid;
	@Column(name = "processed")
	@Builder.Default
	private Boolean processed = Boolean.FALSE;
	@Column(name = "success")
	@Builder.Default
	private Boolean success = Boolean.FALSE;
	/**
	 * Whether this transaction's totalAmount has been FULLY covered by one or more
	 * CustomerDeposits (see depositAllocations below). A transaction can be partially
	 * allocated (some deposits contributed, but not enough to cover the full amount) and
	 * still have allocated=false — CustomerDepositMatchingService re-derives how much is
	 * already allocated from depositAllocations rather than trusting this flag mid-run,
	 * this flag is the final "fully covered" outcome, not a running counter.
	 */
	@Column(name = "allocated")
	@Builder.Default
	private Boolean allocated = Boolean.FALSE;
	@ToString.Exclude
	@ManyToOne
	@JoinColumn(name = "machine_id")
	private TerminalMachine machine;
	@ToString.Exclude
	@ManyToOne
	@JoinColumn(name = "customer_id")
	private Customer customer;
	/**
	 * Null = not yet reconciled. Once set, CardTransactionSpecifications excludes
	 * this transaction from the normal ledger everywhere — it's only visible
	 * again via this Reconciliation's own detail page. Independent of deposit
	 * allocation below — a transaction can be both reconciled and allocated, or
	 * either on its own; the two features don't interact.
	 */
	@ToString.Exclude
	@ManyToOne
	@JoinColumn(name = "reconciliation_id")
	private Reconciliation reconciliation;
	/**
	 * A transaction's amount can be split across MORE THAN ONE CustomerDeposit — each
	 * DepositCardTransaction row records how much of THIS transaction came from ONE
	 * particular deposit. This replaces an earlier, wrong assumption in this project that
	 * a transaction matches exactly one deposit via a direct foreign key; the real app
	 * uses this join-entity model to support partial/split allocation instead.
	 */
	@ToString.Exclude
	@OneToMany(mappedBy = "transaction")
	@Builder.Default
	private List<DepositCardTransaction> depositAllocations = new ArrayList<>();
	@Column(name = "sdc_id")
	private String sdcId;
	@Column(name = "stamp_data")
	private String stampData;
	@Transient
	@Getter(AccessLevel.NONE)
	private String stampNumber;
	@Transient
	@Getter(AccessLevel.NONE)
	private String ebmNumber;

	public String getStampNumber() {
		if (this.stampData == null || this.stampData.isBlank()) {
			return null;
		}
		// split(",", 4): bounds parts.length to at most 4 rather than splitting on
		// every comma in the string, and preserves whatever follows the 3rd comma
		// as a single trailing segment in parts[3].
		String[] parts = this.stampData.split(",", 4);
		if (parts.length < 4) {
			return null; // malformed data — fewer than 4 comma-separated segments, no valid stamp number
		}
		return parts[0] + "/" + parts[1] + "/" + parts[3];
	}

	/**
	 * The stamp number of whichever deposit this transaction was FIRST allocated against
	 * — i.e. the EBM/fiscal stamp this transaction's spend is actually accounted under,
	 * which may differ from this transaction's own stampData (that's the POS/card-side
	 * stamp; this is the deposit/fiscal side). Guards against short/malformed deposit
	 * stampData the same way getStampNumber() does, rather than throwing.
	 */
	public String getEbmNumber() {
		for (DepositCardTransaction allocation : depositAllocations) {
			CustomerDeposit deposit = allocation.getDeposit();
			if (deposit != null && deposit.getStampNumber() != null) {
				this.ebmNumber = deposit.getStampNumber();
				return this.ebmNumber;
			}
		}
		this.ebmNumber = null;
		return null;
	}
}
