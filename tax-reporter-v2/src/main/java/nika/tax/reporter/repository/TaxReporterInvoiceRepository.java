package nika.tax.reporter.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     * for both ends of the range.
     *
     * Explicit @Query, not a derived method name, so plate numbers can be compared with ALL
     * spaces stripped — Spring Data's method-name derivation has no keyword for that (only
     * things like Containing/IgnoreCase; there's no "IgnoreWhitespace"). replace(x, ' ', '')
     * is applied to BOTH sides: the stored i.plateNumber (in case the source data has
     * inconsistent internal spacing — "RAD 123 X" vs "RAD123X" — not just leading/trailing,
     * which a plain TRIM() wouldn't touch) and the incoming :plateNumber parameter itself,
     * so this works correctly regardless of whether the caller already cleaned its value.
     * That matters here specifically because CardTransactionService's own
     * getPlateNumberFromTransaction() only calls .trim() (leading/trailing only) on the
     * segment before "|" — it does NOT strip internal spaces — so without this fix on the
     * query side, a transaction's plate number with an internal space would still fail to
     * match an invoice's differently-spaced value even after that extraction step. */
    @Query("select i from TaxReporterInvoice i "
            + "where i.sdcId = :sdcId "
            + "and lower(replace(i.plateNumber, ' ', '')) like lower(concat('%', replace(:plateNumber, ' ', ''), '%')) "
            + "and i.paidAmount = :paidAmount "
            + "and lower(i.paymentMode) like lower(concat('%', :paymentMode, '%')) "
            + "and i.stampDate >= :start and i.stampDate < :end "
            + "order by i.stampDate asc")
    List<TaxReporterInvoice> findBySdcIdAndPlateNumberContainingIgnoreCaseAndPaidAmountAndPaymentModeContainingIgnoreCaseAndStampDateGreaterThanEqualAndStampDateLessThanOrderByStampDateAsc(
            @Param("sdcId") String sdcId, @Param("plateNumber") String plateNumber,
            @Param("paidAmount") BigDecimal paidAmount, @Param("paymentMode") String paymentMode,
            @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}
