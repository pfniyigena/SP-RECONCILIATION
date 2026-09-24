package nika.tax.reporter.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import nika.tax.reporter.postgres.domain.TaxReporterInvoice;

public interface TaxReporterInvoiceRepository
        extends JpaRepository<TaxReporterInvoice, UUID>, JpaSpecificationExecutor<TaxReporterInvoice> {

    /** Returns null if no match. stampData is unique on this entity, so this is guaranteed 0 or 1 results. */
    TaxReporterInvoice getByStampData(String stampData);

    /** Unprocessed card-paid invoices for one SDC, oldest first — the candidate pool
     * CardTransactionService.processTransaction searches for a matching invoice. */
    List<TaxReporterInvoice> findBySdcIdAndPaymentModeContainingIgnoreCaseAndProcessedOrderByStampDateAsc(
            String sdcId, String paymentMode, Boolean processed);

    /** Candidate pool for CardTransactionService.processTransactionWithPlateNumber — same SDC,
     * same plate number, exact (floor-rounded) paid amount, card payment, stamped sometime
     * during the transaction's own calendar day. "StampDateLessThan" both bounds this and the
     * GreaterThanEqual one, not "DateTrn" — TaxReporterInvoice has no dateTrn property; that
     * was in the version this was ported from and would have failed at application startup
     * with PropertyReferenceException (Spring Data can't derive a query against a property
     * that doesn't exist), not just at call time. stampDate is what start/end actually bound
     * against on the caller's side, so it's the field the derived query needs to reference
     * for both ends of the range. */
    List<TaxReporterInvoice> findBySdcIdAndPlateNumberContainingIgnoreCaseAndPaidAmountAndPaymentModeContainingIgnoreCaseAndStampDateGreaterThanEqualAndStampDateLessThanOrderByStampDateAsc(
            String sdcId, String plateNumber, BigDecimal paidAmount, String paymentMode,
            LocalDateTime start, LocalDateTime end);
}
