package nika.tax.reporter.postgres.domain;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Common persistence fields shared by every entity. Matches the real app's AbstractEntity
 * exactly (previous version of this file was a simplified reference-project approximation —
 * missing createdBy/updatedBy auditing, optimistic-locking version, and @JsonIgnore on
 * internal/audit fields that shouldn't leak into any JSON serialization).
 *
 * createdBy/updatedBy require an AuditorAware<String> bean to actually get populated —
 * @EnableJpaAuditing alone (already present on TaxReporterApplication) isn't sufficient; without
 * an AuditorAware bean, Spring Data auditing has no source for "who" and these fields silently
 * stay at their default "" forever. See config.AuditorAwareConfig, added alongside this change —
 * it wasn't needed before since nothing used @CreatedBy/@LastModifiedBy until now.
 */
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@AllArgsConstructor
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AbstractEntity implements Serializable {

	private static final long serialVersionUID = 1L;

	@JsonIgnore
	@Id
	@GeneratedValue
	@UuidGenerator
	@Column(name = "id", nullable = false, unique = true, updatable = false)
	@EqualsAndHashCode.Include
	private UUID id;

	/**
	 * This field is used for auditory and logging purposes. It is populated by the
	 * system when an entity instance is created.
	 */
	@JsonIgnore
	@Column(name = "created_at")
	@CreatedDate
	protected LocalDateTime createdAt;

	@JsonIgnore
	@Column(name = "modified_at")
	@LastModifiedDate
	protected LocalDateTime modifiedAt;

	@CreatedBy
	@Basic(optional = true)
	@Column(name = "created_by")
	private String createdBy = "";

	@LastModifiedBy
	@Basic(optional = true)
	@Column(name = "updated_by")
	private String updatedBy = "";

	@JsonIgnore
	@Version
	public int version;

	/**
	 * This constructor is required by JPA. All subclasses of this class will
	 * inherit this constructor.
	 */
	protected AbstractEntity() {
		createdAt = LocalDateTime.now();
		modifiedAt = LocalDateTime.now();
	}
}
