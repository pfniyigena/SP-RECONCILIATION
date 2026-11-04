package nika.tax.reporter.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nika.tax.reporter.dto.CardTransactionForm;
import nika.tax.reporter.postgres.domain.CardTransaction;
import nika.tax.reporter.postgres.domain.TaxReporterInvoice;
import nika.tax.reporter.repository.CardTransactionRepository;
import nika.tax.reporter.repository.TaxReporterInvoiceRepository;

@Service
@RequiredArgsConstructor
@Slf4j
public class CardTransactionService {

    private final CardTransactionRepository repository;
    private final TaxReporterInvoiceRepository taxReporterInvoiceRepository;

    /** How many days before an invoice's stampDate a transaction can still count as a match —
     * covers a transaction posted a few days after the fiscal stamp was actually issued. Named
     * plainly for what it does; the pasted original called the resulting date
     * "invoiceDatePlusOne" while actually SUBTRACTING this value, which was backwards from what
     * the name said (a leftover from when this was hardcoded to 1 day, i.e. "minusDays(1)"). */
    @Value("${matching.back.days:2}")
    private int backDays;

    public Page<CardTransaction> search(CardTransactionFilter filter, Pageable pageable) {
        return repository.findAll(CardTransactionSpecifications.build(filter), pageable);
    }

    public CardTransaction getOrThrow(UUID id) {
        return repository.findWithAllocationsById(id)
                .orElseThrow(() -> new EntityNotFoundException("Transaction not found"));
    }

    public CardTransaction create(CardTransactionForm form) {
        CardTransaction entity = new CardTransaction();
        if (form.getTransactionGuid() == null || form.getTransactionGuid().isBlank()) {
            form.setTransactionGuid(UUID.randomUUID().toString());
        }
        CardTransactionMapper.applyToEntity(form, entity);
        return repository.save(entity);
    }

    public CardTransaction update(UUID id, CardTransactionForm form) {
        CardTransaction entity = getOrThrow(id);
        CardTransactionMapper.applyToEntity(form, entity);
        return repository.save(entity);
    }

    /**
     * Manual "Mark as Processed" trigger from the transaction detail page — now actually
     * attempts the same invoice matching matchAndProcessTransactions does for a whole SDC
     * (plate-number match first, tolerant date/amount match as fallback), for this one
     * transaction, rather than blindly forcing success=true without checking anything.
     * processed is still always set true regardless of whether a match was found — this is
     * still fundamentally a manual "stop retrying this one automatically" action; success only
     * reflects whether a real match was actually located. Structured identically to
     * matchAndProcessTransactions's own per-transaction logic (one reassigned stampData
     * variable, not two separate ones across duplicated branches) rather than just
     * functionally equivalent to it.
     */
    public CardTransaction markProcessed(UUID id) {
        CardTransaction transaction = getOrThrow(id);
        String stampData = processTransactionWithPlateNumber(transaction);
        if (stampData == null) {
            stampData = processTransaction(transaction);
        }
        repository.updateMatchResult(id, stampData != null ? Boolean.TRUE : transaction.getSuccess(),
                stampData != null ? stampData : transaction.getStampData());
        return getOrThrow(id);
    }

    /**
     * Sums totalAmount for whatever page of results is currently displayed.
     * NOTE: this is a page-level subtotal only (cheap, no extra query) — it is
     * NOT a grand total across all matching rows, which would need a separate
     * aggregate query (see CardTransactionRepository if you want to add one
     * with @Query("select sum(t.totalAmount) from CardTransaction t where ...")).
     */
    public BigDecimal pageSubtotal(Page<CardTransaction> page) {
        return page.getContent().stream()
                .map(CardTransaction::getTotalAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ---- Card transaction / invoice matching (PostgresJob) ----
    //
    // Ported from the real app's PostgresJob.matchAndProcessTransactions/processTransaction/
    // isWithinMagnitude, moved here (called from the job, not living in it) so the job class
    // itself stays orchestration-only — paginate/parallelize/guard, same separation as
    // CustomerDepositMatchingService vs. its own job. Business logic (the actual matching
    // rules) is preserved exactly as given, redundant-looking tolerance checks included —
    // these read like tolerance rules accumulated from real production discrepancies between
    // totalAmount and quantity*unitPrice, and between different rounding conventions, not
    // something to "clean up" without knowing why each one was added.

    /** Matches every unprocessed transaction for one SDC against that SDC's unprocessed
     * card-paid invoices, oldest-first on both sides. A transaction with no match found stays
     * unprocessed... no wait, this always marks processed=true regardless of match outcome,
     * matching the original exactly: match found sets stampData+success+processed, no match
     * still sets processed=true (just not success) — meaning a transaction is only ever
     * retried by matchAndProcessTransactions if something ELSE resets processed back to
     * false first. The pasted original called
     * cardTransactionServiceV2.resetProcessedTransactions() at the top of every scheduled run
     * for this — deliberately NOT ported here: that method's own implementation wasn't
     * provided, it ran unconditionally even when matching mode was disabled (a bug already
     * documented once before in this project's history), and inventing behavior for an
     * undefined method risks doing the wrong thing under a name that sounds authoritative.
     * If transactions need to be retried after a failed match, that reset needs a real,
     * intentional definition (reset everything? only ones with no possible remaining
     * candidates? on every run or only when explicitly triggered?) before it belongs here. */
    public void matchAndProcessTransactions(String sdcId) {
        List<CardTransaction> unprocessedTransactions = repository
                .getBySdcIdAndProcessedOrderByDateTimeTransactionAsc(sdcId, Boolean.FALSE);
        for (CardTransaction transaction : unprocessedTransactions) {
            String stampData = processTransactionWithPlateNumber(transaction);
            if (stampData == null) {
                // fallback only when the precise plate-number match missed (see history in README)
                stampData = processTransaction(transaction);
            }
            // Targeted update, not repository.save(transaction): this loop holds a copy loaded
            // before the (slow) invoice matching, and other writers (deposit allocation,
            // reconciliation) may have changed the row meanwhile.
            repository.updateMatchResult(transaction.getId(),
                    stampData != null ? Boolean.TRUE : transaction.getSuccess(),
                    stampData != null ? stampData : transaction.getStampData());
        }
    }

    /**
     * Precise match: same SDC, same plate number, exact paid amount (floor-rounded to match
     * transaction.totalAmount's precision), CARD payment, invoice stamped sometime during the
     * transaction's own calendar day. Tried before the looser processTransaction() below,
     * which only matches on amount tolerance and a same-or-adjacent-day window without plate
     * number — this is the more specific signal when a plate number is actually available.
     */
    private String processTransactionWithPlateNumber(CardTransaction transaction) {
        // Guard added after actually tracing the null path, not assumed safe. Without this,
        // a null plate number goes straight into the query as :plateNumber. What happens next
        // depends on whether Hibernate translates JPQL's concat() to the standard "||"
        // operator (NULL poisons the result, row excluded — safe, but only as a side effect)
        // or to Postgres's native concat() function (non-standard: treats NULL as an empty
        // string, so concat('%', NULL, '%') becomes the wildcard '%%', which matches every
        // invoice with ANY plate number at all — silently wrong matches for a transaction
        // that has none, not a crash but arguably worse). Rather than depend on which of
        // those this ORM/DB combination actually does, skip the query entirely when there's
        // nothing to search for — falls through to the caller's processTransaction()
        // fallback, same as if this method had genuinely found no match.
        String plateNumber = getPlateNumberFromTransaction(transaction);
        if (plateNumber == null) {
            return null;
        }
        // Same reasoning as the plateNumber guard above, for the other two fields this method
        // depends on: CardTransactionForm requires both via @NotNull, but that only covers
        // manual entry through the web form — the entity's own @Column annotations have no
        // nullable=false, and the Oracle ingestion path (CardTransactionDto.
        // toCardTransactionEntity()) builds the entity directly, bypassing the form and its
        // validation entirely. If source data is ever incomplete, .toLocalDate() or
        // .setScale(...) below would throw NPE rather than this method just reporting "no
        // match" the way it does for every other unmatchable case.
        if (transaction.getDateTimeTransaction() == null || transaction.getTotalAmount() == null) {
            return null;
        }

        LocalDateTime start = transaction.getDateTimeTransaction().toLocalDate().atStartOfDay();
        LocalDateTime end = transaction.getDateTimeTransaction().plusDays(1).toLocalDate().atStartOfDay();
        BigDecimal min = transaction.getTotalAmount().setScale(0, RoundingMode.FLOOR);
        List<TaxReporterInvoice> matchingInvoices = taxReporterInvoiceRepository
                .findBySdcIdAndPlateNumberContainingIgnoreCaseAndPaidAmountAndPaymentModeContainingIgnoreCaseAndStampDateGreaterThanEqualAndStampDateLessThanOrderByStampDateAsc(
                        transaction.getSdcId(), plateNumber, min, "CARD", start, end);
        for (TaxReporterInvoice invoice : matchingInvoices) {
            invoice.setProcessed(Boolean.TRUE);
            taxReporterInvoiceRepository.save(invoice);
            return invoice.getStampData();
        }
        return null;
    }

    private String processTransaction(CardTransaction transaction) {
        // Same reasoning as processTransactionWithPlateNumber's guard: CardTransactionForm
        // requires these via @NotNull, but only for manual entry — the Oracle ingestion path
        // bypasses the form entirely.
        if (transaction.getDateTimeTransaction() == null || transaction.getTotalAmount() == null) {
            return null;
        }

        List<TaxReporterInvoice> matchingInvoices = taxReporterInvoiceRepository
                .findBySdcIdAndPaymentModeContainingIgnoreCaseAndProcessedOrderByStampDateAsc(transaction.getSdcId(),
                        "CARD", Boolean.FALSE);
        for (TaxReporterInvoice invoice : matchingInvoices) {
            // Neither branch below can meaningfully evaluate without these — skip straight to
            // the next candidate invoice rather than let a null stampDate/paidAmount throw
            // partway through (LocalDateTime.minusDays and BigDecimal.compareTo both require
            // a non-null receiver/argument; there's no query-level filter excluding invoices
            // missing either field from this candidate list).
            if (invoice.getStampDate() == null || invoice.getPaidAmount() == null) {
                continue;
            }

            LocalDateTime transactionDate = transaction.getDateTimeTransaction();
            LocalDateTime invoiceDate = invoice.getStampDate();
            LocalDateTime invoiceDateMinusBackDays = invoice.getStampDate().minusDays(backDays);
            BigDecimal transactionAmount = transaction.getTotalAmount();
            // quantity/unitPrice have no @NotNull on the form at all (unlike
            // dateTimeTransaction/totalAmount above) — reachable via ordinary manual entry,
            // not just Oracle ingestion. null here means "this alternate amount check doesn't
            // apply to this transaction", not a reason to abort the whole match attempt —
            // transactionAmount (the primary totalAmount) is still valid and still checked.
            BigDecimal transactionAmount2 = (transaction.getQuantity() != null && transaction.getUnitPrice() != null)
                    ? transaction.getQuantity().multiply(transaction.getUnitPrice())
                    : null;
            BigDecimal invoiceAmount = invoice.getPaidAmount();

            if (transactionDate.toLocalDate().equals(invoiceDate.toLocalDate())) {

                if (amountsMatch(transactionAmount, transactionAmount2, invoiceAmount)) {
                    log.info(
                            "Matched transaction {} with invoice {} on transaction amount:{} on date:{} based on amount and date",
                            transaction.getId(), invoice.getId(), transaction.getTotalAmount(),
                            transaction.getDateTimeTransaction());
                    invoice.setProcessed(Boolean.TRUE);
                    taxReporterInvoiceRepository.save(invoice);
                    return invoice.getStampData();
                }

            } else if (transactionDate.toLocalDate().equals(invoiceDateMinusBackDays.toLocalDate())) {

                if (amountsMatch(transactionAmount, transactionAmount2, invoiceAmount)) {
                    log.info(
                            "Matched transaction {} with invoice {} on transaction amount:{} on date:{} based on amount and date",
                            transaction.getId(), invoice.getId(), transaction.getTotalAmount(),
                            transaction.getDateTimeTransaction());
                    invoice.setProcessed(Boolean.TRUE);
                    taxReporterInvoiceRepository.save(invoice);
                    return invoice.getStampData();
                }

            } else {
                log.info(
                        "No match for transaction {} with invoice {} on transaction amount:{} on date:{} of SDC ID:{}, Station:{}",
                        transaction.getId(), invoice.getId(), transaction.getTotalAmount(),
                        transaction.getDateTimeTransaction(), transaction.getSdcId(), transaction.getPosName());
            }
        }
        return null;
    }

    /** Every tolerance check the original tried, preserved as-is: exact match, integer-part
     * match, floor-rounded match, half-up-rounded match — each checked against BOTH
     * totalAmount and the independently-computed quantity*unitPrice — plus the magnitude
     * fallback below. Extracted into one method only to de-duplicate the two identical
     * if-conditions in processTransaction (same-day vs. backdated-day branches used to repeat
     * this whole expression); the actual comparisons are unchanged.
     *
     * transactionAmount2 may be null (quantity or unitPrice missing on the transaction — see
     * the caller) — every check involving it is skipped in that case rather than thrown,
     * falling back to just the transactionAmount-based checks and the magnitude fallback.
     * transactionAmount/invoiceAmount are guaranteed non-null by the caller's own guards
     * before this method is ever reached, so those aren't re-checked here. */
    private boolean amountsMatch(BigDecimal transactionAmount, BigDecimal transactionAmount2, BigDecimal invoiceAmount) {
        boolean transactionAmount2Matches = transactionAmount2 != null && (
                (transactionAmount2.compareTo(invoiceAmount) == 0)
                || (transactionAmount2.toBigInteger().compareTo(invoiceAmount.toBigInteger()) == 0)
                || (transactionAmount2.setScale(0, RoundingMode.DOWN)
                        .compareTo(invoiceAmount.setScale(0, RoundingMode.DOWN)) == 0)
                || (transactionAmount2.setScale(0, RoundingMode.HALF_UP)
                        .compareTo(invoiceAmount.setScale(0, RoundingMode.HALF_UP)) == 0));

        return (transactionAmount.compareTo(invoiceAmount) == 0)
                || transactionAmount2Matches
                || (transactionAmount.toBigInteger().compareTo(invoiceAmount.toBigInteger()) == 0)
                || (transactionAmount.setScale(0, RoundingMode.DOWN)
                        .compareTo(invoiceAmount.setScale(0, RoundingMode.DOWN)) == 0)
                || (transactionAmount.setScale(0, RoundingMode.HALF_UP)
                        .compareTo(invoiceAmount.setScale(0, RoundingMode.HALF_UP)) == 0)
                || isWithinMagnitude(transactionAmount, invoiceAmount);
    }

    /** True if the absolute difference between a and b has fewer digits than the smaller of
     * the two — a rough "these are probably the same value with rounding/conversion noise,
     * not two genuinely different amounts" heuristic. Preserved exactly as given; unusual but
     * clearly deliberate, not something to second-guess without knowing what discrepancies it
     * was written to absorb. */
    private boolean isWithinMagnitude(BigDecimal a, BigDecimal b) {
        BigDecimal difference = a.subtract(b).abs();
        BigDecimal smaller = a.min(b).abs();

        int differenceDigits = difference.stripTrailingZeros().precision() - difference.stripTrailingZeros().scale();
        int smallerDigits = smaller.stripTrailingZeros().precision() - smaller.stripTrailingZeros().scale();

        return differenceDigits < smallerDigits;
    }

    /** CardTransaction.plateNumber can hold more than one plate separated by "|" — only the
     * first is used for matching. Returns null (rather than empty string) if there's nothing
     * usable, so callers can treat "no plate number" uniformly regardless of whether the
     * source value was null, blank, or just an empty segment before the separator. */
    private String getPlateNumberFromTransaction(CardTransaction transaction) {
        String value = transaction.getPlateNumber();
        if (value == null || value.isBlank()) {
            return null;
        }

        int separatorIndex = value.indexOf("|");
        String plateNumber = (separatorIndex >= 0 ? value.substring(0, separatorIndex) : value).trim();

        return plateNumber.isEmpty() ? null : plateNumber;
    }
}

