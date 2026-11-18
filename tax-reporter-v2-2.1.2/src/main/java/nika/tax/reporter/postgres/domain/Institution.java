package nika.tax.reporter.postgres.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * totalInvoices is left unpopulated for now, deliberately — same as the existing
 * TerminalMachine.totalInvoices, which is also currently dead weight. Computing it for real
 * would need Institution -> StampMachine -> TaxReporterInvoice, and this project's StampMachine
 * doesn't have an institution relation yet (it still has serialNumber/model/enabled, not the
 * real app's sdcId/location/institution/totalInvoices shape) — that's the "Institution +
 * StampMachine/TerminalMachine (org structure)" work explicitly deferred earlier in this
 * project in favor of the CustomerDeposit/CardTransaction/DepositCardTransaction realignment.
 * Adding a fabricated computation here now would just be guessing at a relationship that
 * doesn't exist in this project yet.
 */
@Data
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true,callSuper=true)
@Entity
@Table(name = "institution")
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Institution extends AbstractEntity{
    /**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	@Column(name = "name")
    private String name;
    @Column(name = "tin_number",nullable = false)
    private String tinNumber;
    @Column(name="enabled" )
    @Builder.Default
    private Boolean enabled=Boolean.TRUE;
    @Transient
    private Long totalInvoices;
}
