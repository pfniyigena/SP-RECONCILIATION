package nika.tax.reporter.postgres.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
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
@Table(name = "customer")
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Customer extends AbstractEntity {
	private static final long serialVersionUID = 1L;
	@Column(name = "client_name", unique = true)
	private String clientName;
	@Column(name = "client_tin")
	private String clientTin;
	@Column(name = "client_id")
	private Integer clientId;
	@Column(name = "enabled")
	@Builder.Default
	private Boolean enabled = Boolean.TRUE;
	/**
	 * Eligibility flag for CustomerDepositMatchingService — only customers with
	 * allocated=true are considered when the deposit-allocation job runs. Distinct from
	 * "enabled" (general account status); a customer can be enabled but not yet flagged
	 * for allocation, or vice versa.
	 */
	@Column(name = "allocated")
	@Builder.Default
	private Boolean allocated = Boolean.FALSE;
}
