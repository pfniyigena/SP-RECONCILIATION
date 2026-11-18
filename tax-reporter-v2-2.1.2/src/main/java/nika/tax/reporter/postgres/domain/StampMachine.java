package nika.tax.reporter.postgres.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * PLACEHOLDER — TerminalMachine.stampMachine references this entity, but its real
 * field definitions weren't provided. This stub exists only so the project compiles
 * and the terminal_machine -> stamp_machine relationship resolves.
 *
 * Replace the fields below with your actual StampMachine definition (e.g. serial
 * number, fiscal device / SDC identifiers, certification details — whatever your
 * RRA/EBM integration actually tracks) and this class becomes a drop-in swap since
 * nothing else in the app depends on its internals yet.
 *
 * sdcId and institution added as minimal, targeted additions — sdcId so
 * StampMachineRepository.getBySdcId (a Spring Data derived query) has an actual
 * property to derive against, institution because TaxReporterInvoiceService's
 * invoice-ingestion path (validStampMachine) constructs a StampMachine with one.
 * Without either, those would have failed — sdcId at application startup
 * (PropertyReferenceException, since a derived query can't be built against a
 * nonexistent property), institution at compile time. This is still NOT the full
 * StampMachine rebuild (location, totalInvoices) deferred earlier in this project —
 * just the two fields actually required by what's been asked for so far.
 */
@Data
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = true)
@Entity
@Table(name = "stamp_machine")
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class StampMachine extends AbstractEntity {

	private static final long serialVersionUID = 1L;

	@Column(name = "serial_number", unique = true)
	private String serialNumber;

	@Column(name = "model")
	private String model;

	@Column(name = "sdc_id", unique = true)
	private String sdcId;

	@Column(name = "enabled")
	@Builder.Default
	private Boolean enabled = Boolean.TRUE;

	@ToString.Exclude
	@ManyToOne
	@JoinColumn(name = "institution_id")
	private Institution institution;
}
