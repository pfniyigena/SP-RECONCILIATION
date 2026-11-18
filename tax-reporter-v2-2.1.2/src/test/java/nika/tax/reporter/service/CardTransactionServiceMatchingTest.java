package nika.tax.reporter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import nika.tax.reporter.postgres.domain.CardTransaction;
import nika.tax.reporter.postgres.domain.TaxReporterInvoice;
import nika.tax.reporter.repository.CardTransactionRepository;
import nika.tax.reporter.repository.TaxReporterInvoiceRepository;

/** Pure unit tests (mocked repositories, no database or Spring context). */
class CardTransactionServiceMatchingTest {

    private CardTransactionRepository txRepo;
    private TaxReporterInvoiceRepository invoiceRepo;
    private CardTransactionService service;

    @BeforeEach
    void setUp() {
        txRepo = mock(CardTransactionRepository.class);
        invoiceRepo = mock(TaxReporterInvoiceRepository.class);
        service = new CardTransactionService(txRepo, invoiceRepo);
        ReflectionTestUtils.setField(service, "backDays", 1);
        ReflectionTestUtils.setField(service, "retryDays", 1);
        ReflectionTestUtils.setField(service, "matchingStartDate", "2026-06-11");
    }

    private static CardTransaction tx(LocalDateTime when, String amount, String plate) {
        CardTransaction t = new CardTransaction();
        t.setId(UUID.randomUUID());
        t.setSdcId("SDC1");
        t.setDateTimeTransaction(when);
        t.setTotalAmount(new BigDecimal(amount));
        t.setQuantity(BigDecimal.ONE);
        t.setUnitPrice(new BigDecimal(amount));
        t.setPlateNumber(plate);
        t.setSuccess(Boolean.FALSE);
        return t;
    }

    private static TaxReporterInvoice invoice(LocalDateTime stamp, String paid, String stampData) {
        TaxReporterInvoice i = new TaxReporterInvoice();
        i.setId(UUID.randomUUID());
        i.setStampDate(stamp);
        i.setPaidAmount(new BigDecimal(paid));
        i.setStampData(stampData);
        return i;
    }

    // ---- plate number extraction ----

    @Test
    void plateNumber_takesSegmentBeforePipe_andTrims() {
        assertEquals("RAB123C", service.getPlateNumberFromTransaction(tx(LocalDateTime.now(), "10", " RAB123C |driver")));
    }

    @Test
    void plateNumber_nullBlankOrEmptySegment_isNull() {
        assertNull(service.getPlateNumberFromTransaction(tx(LocalDateTime.now(), "10", null)));
        assertNull(service.getPlateNumberFromTransaction(tx(LocalDateTime.now(), "10", "   ")));
        assertNull(service.getPlateNumberFromTransaction(tx(LocalDateTime.now(), "10", "|only-suffix")));
    }

    // ---- amount tolerance ----

    @Test
    void amountsMatch_exact_andIntegerPart() {
        assertTrue(service.amountsMatch(new BigDecimal("1500.00"), new BigDecimal("1500.00"), new BigDecimal("1500")));
        assertTrue(service.amountsMatch(new BigDecimal("1500.40"), new BigDecimal("1500.40"), new BigDecimal("1500")));
    }

    @Test
    void amountsMatch_usesQuantityTimesUnitPriceToo() {
        assertTrue(service.amountsMatch(new BigDecimal("9999"), new BigDecimal("2000"), new BigDecimal("2000")));
    }

    @Test
    void amountsMatch_clearlyDifferent_isFalse() {
        assertFalse(service.amountsMatch(new BigDecimal("1000"), new BigDecimal("1000"), new BigDecimal("50000")));
    }

    // ---- matchAndProcessTransactions ----

    @Test
    void match_foundByAmountAndDate_marksProcessedAndSuccess() {
        CardTransaction t = tx(LocalDateTime.now().minusHours(2), "1500", null);
        TaxReporterInvoice inv = invoice(LocalDateTime.now().minusHours(2), "1500", "STAMP-1");
        when(txRepo.getBySdcIdAndProcessedAndDateTimeTransactionGreaterThanEqualOrderByDateTimeTransactionAsc(eq("SDC1"), eq(Boolean.FALSE), any())).thenReturn(List.of(t));
        when(invoiceRepo.findBySdcIdAndPaymentModeContainingIgnoreCaseAndProcessedOrderByStampDateAsc(anyString(), anyString(), any()))
                .thenReturn(List.of(inv));

        service.matchAndProcessTransactions("SDC1");

        verify(txRepo).updateMatchResult(t.getId(), Boolean.TRUE, Boolean.TRUE, "STAMP-1");
        verify(invoiceRepo).markProcessed(inv.getId());
    }

    @Test
    void noMatch_insideRetryWindow_staysUnprocessed() {
        CardTransaction t = tx(LocalDateTime.now().minusHours(2), "1500", null);
        when(txRepo.getBySdcIdAndProcessedAndDateTimeTransactionGreaterThanEqualOrderByDateTimeTransactionAsc(eq("SDC1"), eq(Boolean.FALSE), any())).thenReturn(List.of(t));
        when(invoiceRepo.findBySdcIdAndPaymentModeContainingIgnoreCaseAndProcessedOrderByStampDateAsc(anyString(), anyString(), any()))
                .thenReturn(List.of());

        service.matchAndProcessTransactions("SDC1");

        verify(txRepo, never()).updateMatchResult(any(), any(), any(), any());
    }

    @Test
    void noMatch_pastRetryWindow_isMarkedProcessedButNotSuccessful() {
        CardTransaction t = tx(LocalDateTime.now().minusDays(3), "1500", null);
        when(txRepo.getBySdcIdAndProcessedAndDateTimeTransactionGreaterThanEqualOrderByDateTimeTransactionAsc(eq("SDC1"), eq(Boolean.FALSE), any())).thenReturn(List.of(t));
        when(invoiceRepo.findBySdcIdAndPaymentModeContainingIgnoreCaseAndProcessedOrderByStampDateAsc(anyString(), anyString(), any()))
                .thenReturn(List.of());

        service.matchAndProcessTransactions("SDC1");

        verify(txRepo).updateMatchResult(eq(t.getId()), eq(Boolean.TRUE), eq(Boolean.FALSE), any());
    }

    @Test
    void oneFailingTransaction_doesNotStopTheRest() {
        CardTransaction bad = tx(LocalDateTime.now().minusHours(3), "1500", null);
        CardTransaction good = tx(LocalDateTime.now().minusHours(2), "800", null);
        TaxReporterInvoice inv = invoice(LocalDateTime.now().minusHours(2), "800", "STAMP-2");
        when(txRepo.getBySdcIdAndProcessedAndDateTimeTransactionGreaterThanEqualOrderByDateTimeTransactionAsc(eq("SDC1"), eq(Boolean.FALSE), any()))
                .thenReturn(List.of(bad, good));
        when(invoiceRepo.findBySdcIdAndPaymentModeContainingIgnoreCaseAndProcessedOrderByStampDateAsc(anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("boom")).thenReturn(List.of(inv));

        service.matchAndProcessTransactions("SDC1");

        verify(txRepo).updateMatchResult(good.getId(), Boolean.TRUE, Boolean.TRUE, "STAMP-2");
    }
}
