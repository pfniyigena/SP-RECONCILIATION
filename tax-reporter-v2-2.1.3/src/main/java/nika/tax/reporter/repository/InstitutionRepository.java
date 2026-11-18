package nika.tax.reporter.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import nika.tax.reporter.postgres.domain.Institution;

public interface InstitutionRepository
        extends JpaRepository<Institution, UUID>, JpaSpecificationExecutor<Institution> {

    /** Returns null if no match. tinNumber has no unique constraint on this entity, so this
     * throws IncorrectResultSizeDataAccessException if more than one institution shares a TIN. */
    Institution getByTinNumber(String tinNumber);
}
