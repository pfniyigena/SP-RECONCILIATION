package nika.tax.reporter.job;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import nika.tax.reporter.repository.StampMachineRepository;
import nika.tax.reporter.service.CardTransactionService;

/**
 * Matches CardTransactions to TaxReporterInvoices per SDC (stamp machine), in parallel across
 * SDCs. Ported from a real PostgresJob that, as pasted, would not have compiled — its
 * constructor took stampMachineRepository/cardTransactionService/customerRepository/
 * customerDepositService as parameters but only ever assigned the first two, and the class body
 * referenced cardTransactionRepository, taxReporterInvoiceRepository, cardTransactionServiceV2,
 * and backDays without any of them being declared as fields anywhere. The actual matching logic
 * (CardTransactionService.matchAndProcessTransactions/processTransaction/isWithinMagnitude) is
 * preserved exactly; what's rebuilt here is the wiring around it.
 *
 * Two deliberate departures from the pasted version, both explained where they matter more —
 * see CardTransactionService for the resetProcessedTransactions() omission, and below for the
 * AtomicBoolean guard this job didn't originally have.
 */
@Component
@Slf4j
public class PostgresJob {

    private static final int MATCHING_PARALLELISM = 8; // tune against Hikari pool size

    /** Global kill switch — checked on both the scheduled trigger and the manual "Run Now"
     * button, not just the cron path, since a person explicitly disabling matching probably
     * means "don't run this at all right now," not "don't run it automatically, but manual
     * triggers are still fine." */
    @Value("${matching.mode.enabled:false}")
    private boolean isMatchingMode;

    private final StampMachineRepository stampMachineRepository;
    private final CardTransactionService cardTransactionService;

    /** Not present in the pasted original, added here for the same reason
     * CustomerDepositMatchingService has one: this job is now reachable from both the cron
     * trigger and a manual "Run Now" button on the Jobs page, and without an explicit guard
     * the two could overlap and run the same parallel SDC sweep twice at once. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public PostgresJob(StampMachineRepository stampMachineRepository, CardTransactionService cardTransactionService) {
        this.stampMachineRepository = stampMachineRepository;
        this.cardTransactionService = cardTransactionService;
    }

    @Scheduled(cron = "${matching.mode.cron.expression}")
    public void matchingCardWithInvoices() {
        if (!running.compareAndSet(false, true)) {
            log.warn("Matching run requested, but a previous run is still in progress — skipping this trigger.");
            return;
        }
        try {
            runMatching();
        } finally {
            running.set(false);
        }
    }

    /**
     * Entry point for the manual "Run Now" button on the Jobs page — same guard, same logic,
     * same code path as the scheduled trigger.
     *
     * @return true if the run actually started, false if a previous run was still in progress.
     *         Does not distinguish "ran but did nothing because matching mode is disabled" —
     *         check {@link #isMatchingModeEnabled()} separately for that, so the Jobs page can
     *         show a specific "matching is disabled in configuration" message rather than
     *         implying a real run happened.
     */
    public boolean runNow() {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        try {
            runMatching();
        } finally {
            running.set(false);
        }
        return true;
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean isMatchingModeEnabled() {
        return isMatchingMode;
    }

    private void runMatching() {
        if (!isMatchingMode) {
            log.debug("Matching mode is disabled. Skipping this run.");
            return;
        }

        List<String> sdcIds = stampMachineRepository.findAllEnabledSdcIds();
        log.info("Matching run starting: {} enabled stamp machines.", sdcIds.size());

        AtomicInteger failures = new AtomicInteger();
        ForkJoinPool pool = new ForkJoinPool(MATCHING_PARALLELISM);
        try {
            pool.submit(() -> sdcIds.parallelStream().forEach(sdcId -> {
                try {
                    cardTransactionService.matchAndProcessTransactions(sdcId);
                    log.info("Matching completed for SDC {}", sdcId);
                } catch (Exception e) {
                    failures.incrementAndGet();
                    log.error("Matching failed for SDC {}", sdcId, e);
                }
            })).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Matching run interrupted", e);
        } catch (ExecutionException e) {
            log.error("Matching run failed unexpectedly", e.getCause());
        } finally {
            pool.shutdown();
        }

        log.info("Matching run complete: {} machines processed, {} failures.", sdcIds.size(), failures.get());
    }
}
