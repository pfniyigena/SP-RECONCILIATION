package nika.tax.reporter.postgres.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/**
 * Matches the real app's TaxReporterInvoice exactly. Two things corrected here relative to the
 * version this project had before:
 *   - @Lob removed from failureReason. This project's own README documents the exact bug this
 *     causes on PostgreSQL ("lo_get(text) does not exist" / "text = bigint" errors, from
 *     Hibernate mapping @Lob String to the PostgreSQL OID large-object type instead of plain
 *     text) — that fix had been applied to CardTransaction/CustomerDeposit's analogous fields
 *     but never actually made it into this project's own TaxReporterInvoice, despite being
 *     documented as the correct fix. Applied now.
 *   - getStampNumber() reverted to its original unguarded form at explicit request — same as
 *     the earlier CustomerDeposit.getStampNumber() reversion. This will throw
 *     ArrayIndexOutOfBoundsException on any stampData with fewer than 2 comma-separated
 *     segments, rather than the guarded version's graceful null.
 */
@Data
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true,callSuper = true)
@Entity
@Table(name = "tax_reporter_invoice")
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TaxReporterInvoice extends AbstractEntity {
    /**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	@Column(name = "registered_name")
    private String registeredName;
    @Column(name = "registered_tin")
    private String registeredTin;
    @Column(name = "sdc_id")
    private String sdcId;
    @Column(name = "client_tin")
    private String clientTin;
    @Column(name = "client_name")
    private String clientName;
    @Column(name = "client_phone")
    private String clientPhone;
    @Column(name = "receipt_number")
    private Long receiptNumber;
    @Column(name = "stamp_date")
    private LocalDateTime stampDate;
    @Column(name = "stamp_data", unique = true)
    private String stampData;
    @Column(name = "total_tax_amount")
    private BigDecimal totalTaxAmount;
    @Column(name = "total_amount")
    private BigDecimal totalAmount;
    @Column(name = "paid_amount")
    private BigDecimal paidAmount;
    @Column(name = "transaction_type")
    private String transactionType;
    @Column(name = "payment_mode")
    private String paymentMode;
    @Column(name = "plate_number")
    private String plateNumber;
    @Column(name = "erp_code")
    private String erpCode;
	@Column(name = "processed")
	@Builder.Default
	private Boolean processed = Boolean.FALSE;
	@Column(name = "failure_reason",columnDefinition = "TEXT")
	private String failureReason;
    @Transient
    @Getter(AccessLevel.NONE)
    private String stampNumber;
    public String getStampNumber() {
        if (this.stampData != null && !this.stampData.isEmpty())
            this.stampNumber = this.stampData.split(",")[0] + "/" + this.stampData.split(",")[1];
        return this.stampNumber;
    }
}
