package nika.tax.reporter.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import nika.tax.reporter.postgres.domain.TaxReporterInvoice;

public interface TaxReporterInvoiceRepository
        extends JpaRepository<TaxReporterInvoice, UUID>, JpaSpecificationExecutor<TaxReporterInvoice> {

    /** Returns null if no match. stampData is unique on this entity, so this is guaranteed 0 or 1 results. */
    TaxReporterInvoice getByStampData(String stampData);
}
