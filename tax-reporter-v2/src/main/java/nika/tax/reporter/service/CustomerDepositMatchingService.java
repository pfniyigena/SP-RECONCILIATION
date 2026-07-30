package nika.tax.reporter.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nika.tax.reporter.postgres.domain.CardTransaction;
import nika.tax.reporter.postgres.domain.CustomerDeposit;
import nika.tax.reporter.postgres.domain.DepositCardTransaction;
import nika.tax.reporter.repository.CardTransactionRepository;
import nika.tax.reporter.repository.CustomerDepositRepository;
import nika.tax.reporter.repository.DepositCardTransactionRepository;

/**
 * Matches CardTransactions to CustomerDeposits, per customer, FIFO on both sides — REWRITTEN
 * to match the real app's actual data model after seeing CustomerDeposit.currentBalance,
 * DepositCardTransaction, and the real allocateTransactionToCustomerDeposit() method. This
 * replaces an earlier version of this service built on a wrong assumption (a direct
 * CardTransaction -> CustomerDeposit foreign key, one deposit per transaction, no splitting).
 *
 * How allocation actually works here:
 *   - A transaction's amount CAN be split across more than one deposit if a single deposit's
 *     currentBalance isn't enough to cover it — each contributing deposit gets its own
 *     DepositCardTransaction row recording exactly how much it contributed.
 *   - CustomerDeposit.currentBalance is a live, denormalized running balance — decremented
 *     directly as allocations happen, not computed from a SUM over allocations. Reading "how
 *     much room is left in this deposit" is therefore O(1), not an aggregate query.
 *   - A transaction is marked allocated=true only once its FULL amount has been covered,
 *     possibly by several deposits together. If deposits run out before that happens, the
 *     transaction is left allocated=false and picked up again on a later run.
 *
 * THE BUG THIS FIXES: the real app's original allocateTransactionToCustomerDeposit(CardTransaction)
 * started `remaining` from cardTransaction.getTotalAmount() on every call — the full original
 * amount, with no memory of allocations from a previous, partial run. Re-running it on a
 * transaction that was only partially covered last time created a SECOND, overlapping set of
 * DepositCardTransaction records on top of the first, so the transaction ended up "allocated"
 * for more than its actual value, and deposit balances drained faster than they should have.
 * This version bulk-fetches each transaction's already-allocated total up front
 * (sumAllocatedAmountByTransactionForCustomer) and starts `remaining` from
 * totalAmount MINUS that, so resuming a partially-allocated transaction continues correctly.
 *
 * Idempotent / safe to re-run for the same reason: only ever touches transactions with
 * allocated=false, and always re-derives "already allocated" from DepositCardTransaction rather
 * than trusting any in-memory or stale assumption about where a previous run left off.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CustomerDepositMatchingService {

    private final CardTransactionRepository cardTransactionRepository;
    private final CustomerDepositRepository customerDepositRepository;
    private final DepositCardTransactionRepository depositCardTransactionRepository;

    /** Same reentrancy purpose as PostgresJob's matching guard — matters now that this is
     * reachable from both the Jobs page's "Run Now" button and the Deposits list's own
     * "Run Matching" button; without this, either could overlap a still-running previous call. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public record MatchingResult(int transactionsCompleted, int customersProcessed) {
    }

    /**
     * @return empty if a previous run was still in progress (skipped, not run again) — callers
     *         use this to show "already running" rather than a result implying nothing needed matching.
     */
    public Optional<MatchingResult> matchAll() {
        if (!running.compareAndSet(false, true)) {
            log.warn("Matching run requested, but a previous run is still in progress — skipping.");
            return Optional.empty();
        }
        try {
            return Optional.of(runMatchAll());
        } finally {
            running.set(false);
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * Scheduled equivalent of JobController.runDepositMatching() (the "Run Now" button) and
     * CustomerDepositController.runMatching() (the "Run Matching" button on the Deposits list)
     * — same matchAll() call, same reentrancy guard, same outcome, just triggered by a timer
     * instead of a click. Deliberately thin: matchAll() and runMatchAll() already log both
     * outcomes internally (transactions matched, or skipped because a previous run was still
     * in progress), so there's nothing this wrapper needs to do beyond calling it — no
     * duplicate logging, no separate result-message building the way the two button endpoints
     * do for their flash messages, since there's no request/response here to show one to.
     */
    @Scheduled(cron = "${deposit-matching.cron.expression}")
    public void scheduledMatchAll() {
        matchAll();
    }

    /**
     * Not @Transactional itself — called from matchAll() via self-invocation (this.runMatchAll()),
     * and Spring's proxy-based @Transactional does not apply to self-invoked calls within the
     * same class, so annotating this would be misleading rather than protective. Each customer's
     * own allocateForCustomer() is still consistent on its own; the run as a whole across many
     * customers is not one giant transaction, which is the right shape at this kind of scale anyway.
     */
    private MatchingResult runMatchAll() {
        List<UUID> customerIds = cardTransactionRepository.findDistinctCustomerIdsWithUnallocatedTransactions();
        int totalCompleted = 0;
        int customersProcessed = 0;

        for (UUID customerId : customerIds) {
            int completed = allocateForCustomer(customerId);
            if (completed > 0) {
                totalCompleted += completed;
                customersProcessed++;
            }
        }

        log.info("Deposit allocation complete: {} transactions fully allocated across {} customers.", totalCompleted, customersProcessed);
        return new MatchingResult(totalCompleted, customersProcessed);
    }

    /**
     * All allocation decisions for one customer happen in memory: deposits and their live
     * currentBalance are loaded once, already-allocated-per-transaction is loaded once (one
     * aggregate query, not one per transaction), and every split/decrement is worked out
     * against those in-memory values before anything is written. Only the final, already-decided
     * state gets saved — one batched saveAll() per entity type, not one save per transaction.
     *
     * @Transactional only actually takes effect if this is called from OUTSIDE this class,
     * through the Spring-managed proxy — its only caller today is runMatchAll(), which
     * self-invokes it, so for that call path this annotation has no effect (same self-invocation
     * limitation noted on runMatchAll() above). Left in place since this method is public and a
     * plausible future external caller (e.g. a per-customer "allocate just this one" action)
     * would get the real guarantee.
     *
     * @return how many transactions were newly and FULLY allocated (not just partially) in this run.
     */
    @Transactional
    public int allocateForCustomer(UUID customerId) {
        List<CustomerDeposit> deposits = customerDepositRepository.findAvailableDeposits(customerId, BigDecimal.ZERO);
        List<CardTransaction> unallocated = cardTransactionRepository
                .findByCustomer_IdAndAllocatedOrderByDateTimeTransactionAsc(customerId, Boolean.FALSE);

        if (deposits.isEmpty() || unallocated.isEmpty()) {
            return 0;
        }

        Map<UUID, BigDecimal> alreadyAllocated = depositCardTransactionRepository
                .sumAllocatedAmountByTransactionForCustomer(customerId)
                .stream()
                .collect(Collectors.toMap(
                        DepositCardTransactionRepository.TransactionAllocationRow::getTransactionId,
                        DepositCardTransactionRepository.TransactionAllocationRow::getAllocatedAmount));

        Iterator<CustomerDeposit> depositIterator = deposits.iterator();
        CustomerDeposit currentDeposit = depositIterator.hasNext() ? depositIterator.next() : null;

        List<DepositCardTransaction> newAllocations = new ArrayList<>();
        Set<CustomerDeposit> depositsTouched = new HashSet<>();
        List<CardTransaction> newlyCompleted = new ArrayList<>();
        int completedCount = 0;

        for (CardTransaction tx : unallocated) {
            BigDecimal already = alreadyAllocated.getOrDefault(tx.getId(), BigDecimal.ZERO);
            BigDecimal remaining = tx.getTotalAmount() != null
                    ? tx.getTotalAmount().subtract(already)
                    : BigDecimal.ZERO.subtract(already);

            while (remaining.compareTo(BigDecimal.ZERO) > 0 && currentDeposit != null) {
                BigDecimal available = currentDeposit.getCurrentBalance();

                if (available == null || available.compareTo(BigDecimal.ZERO) <= 0) {
                    currentDeposit = depositIterator.hasNext() ? depositIterator.next() : null;
                    continue;
                }

                BigDecimal allocate = available.min(remaining);

                newAllocations.add(DepositCardTransaction.builder()
                        .customer(tx.getCustomer())
                        .deposit(currentDeposit)
                        .transaction(tx)
                        .allocatedAmount(allocate)
                        .build());

                currentDeposit.setCurrentBalance(available.subtract(allocate));
                depositsTouched.add(currentDeposit);
                remaining = remaining.subtract(allocate);

                if (currentDeposit.getCurrentBalance().compareTo(BigDecimal.ZERO) <= 0) {
                    currentDeposit = depositIterator.hasNext() ? depositIterator.next() : null;
                }
            }

            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                tx.setAllocated(Boolean.TRUE);
                newlyCompleted.add(tx);
                completedCount++;
            } else {
                log.warn("Customer {} transaction {} still short {} after available deposits exhausted — will resume from this point next run.",
                        customerId, tx.getId(), remaining);
            }

            if (currentDeposit == null) {
                break; // this customer has no deposit balance left at all — remaining transactions stay unallocated this run
            }
        }

        if (!newAllocations.isEmpty()) {
            depositCardTransactionRepository.saveAll(newAllocations);
        }
        if (!depositsTouched.isEmpty()) {
            customerDepositRepository.saveAll(depositsTouched);
        }
        if (!newlyCompleted.isEmpty()) {
            cardTransactionRepository.saveAll(newlyCompleted);
        }

        return completedCount;
    }
}
