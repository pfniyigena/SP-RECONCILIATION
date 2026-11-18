package nika.tax.reporter.postgres.domain;

import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@ToString(exclude = {"password", "accessibleCustomers"})
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = true)
@Entity
@Table(name = "app_user")
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AppUser extends AbstractEntity {

	private static final long serialVersionUID = 1L;

	@Column(name = "username", nullable = false, unique = true)
	private String username;

	@Column(name = "password", nullable = false)
	private String password;

	@Column(name = "full_name")
	private String fullName;

	/**
	 * Simple single-role model, e.g. "ROLE_ADMIN", "ROLE_ANALYST", or
	 * "ROLE_CUSTOMER_SCOPED". Only ROLE_CUSTOMER_SCOPED users are restricted by
	 * {@link #accessibleCustomers} — every other role ignores that set entirely.
	 */
	@Column(name = "role", nullable = false)
	private String role;

	@Column(name = "enabled")
	@Builder.Default
	private Boolean enabled = Boolean.TRUE;

	/**
	 * Which customers this user may see — only enforced when role is
	 * ROLE_CUSTOMER_SCOPED (see CardTransactionSpecifications /
	 * CardTransactionController). Empty/irrelevant for every other role,
	 * including Admin, which always sees everything regardless of this set.
	 */
	@ManyToMany(fetch = FetchType.LAZY)
	@JoinTable(
			name = "app_user_customer_access",
			joinColumns = @JoinColumn(name = "app_user_id"),
			inverseJoinColumns = @JoinColumn(name = "customer_id"))
	@Builder.Default
	private Set<Customer> accessibleCustomers = new HashSet<>();
}

