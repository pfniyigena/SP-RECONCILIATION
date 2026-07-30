package nika.tax.reporter.postgres.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = true)
@Entity
@Table(name = "terminal_machine")
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TerminalMachine extends AbstractEntity {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	@Column(name = "pos_id")
	private Integer posId;
	@Column(name = "pos_name", nullable = true)
	private String posName;
	@Column(name = "pos_number")
	private Integer posNumber;
	@Column(name = "address_name", nullable = true)
	private String addressName;
	@Column(name = "address_description", nullable = true)
	private String addressDescription;
	@Column(name = "sdc_id")
	private String sdcId;
	@Column(name = "location")
	private String location;
	@Column(name = "enabled")
	@Builder.Default
	private Boolean enabled = Boolean.TRUE;
	@ToString.Exclude
	@ManyToOne
	@JoinColumn(name = "stamp_machine_id")
	private StampMachine stampMachine;
	@Transient
	private Long totalInvoices;
}
