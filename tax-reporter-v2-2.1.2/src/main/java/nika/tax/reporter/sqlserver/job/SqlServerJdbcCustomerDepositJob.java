package nika.tax.reporter.sqlserver.job;

import nika.tax.reporter.job.JobRunTracker;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import nika.tax.reporter.sqlserver.exception.StampLookupException;
import org.springframework.scheduling.annotation.Scheduled;

import lombok.extern.slf4j.Slf4j;
import nika.tax.reporter.postgres.domain.CustomerDeposit;
import nika.tax.reporter.repository.CustomerDepositRepository;
import nika.tax.reporter.sqlserver.service.StampLookupService;

/**
 * Resolves stampData for CustomerDeposits that don't have it yet, by looking each one up
 * against the external SQL Server Stamp table via sapReference (StampLookupService).
 *
 * One fix needed to compile, same pattern as every other uploaded job in this project: the
 * uploaded version imported nika.tax.reporter.postgres.repository.v2.CustomerDepositRepositoryV2,
 * which doesn't exist here. Corrected to this project's actual
 * nika.tax.reporter.repository.CustomerDepositRepository, no "V2" suffix, no "postgres."
 * prefix — every repository in this project lives directly under nika.tax.reporter.repository.
 * Field/parameter renamed from customerDepositRepositoryV2 to customerDepositRepository for
 * consistency now that the type itself doesn't say "V2" either.
 *
 * AtomicBoolean guard added — not present in the uploaded version, same reasoning as every
 * other job in this project reachable from both a cron trigger and a manual "Run Now" button.
 * Only one @Scheduled method here, so this is a simpler case than the Oracle jobs (which share
 * one guard across two methods) — just the one method and the manual trigger sharing it.
 */
@Configuration
@Slf4j
public class SqlServerJdbcCustomerDepositJob {
	private final CustomerDepositRepository customerDepositRepository;
	private final StampLookupService stampLookupService;

	@Value("${stamp.lookup.max-attempts:72}")
	private int maxAttempts;

	private final AtomicBoolean running = new AtomicBoolean(false);

	public SqlServerJdbcCustomerDepositJob(CustomerDepositRepository customerDepositRepository,
			StampLookupService stampLookupService) {
		this.customerDepositRepository = customerDepositRepository;
		this.stampLookupService = stampLookupService;
	}

	@Scheduled(cron = "${stamp.pull.cron.expression}")
	void readCardTransaction() {
		if (!running.compareAndSet(false, true)) {
			log.warn("Stamp lookup pull requested, but a previous run is still in progress — skipping this trigger.");
			return;
		}
		try {
			JobRunTracker.run("stamp-lookup", this::runReadCardTransaction);
		} finally {
			running.set(false);
		}
	}

	private void runReadCardTransaction() {
		var transactions = customerDepositRepository.findStampPending(maxAttempts);
		log.info("Stamp lookup: {} deposits pending (max {} attempts each)", transactions.size(), maxAttempts);

		for (CustomerDeposit transaction : transactions) {
			try {
				handleCardTransactionWithMachineId(transaction);
			} catch (StampLookupException e) {
				// SQL Server unreachable: stop this run WITHOUT counting an attempt against any deposit.
				log.error("Stamp database unreachable - ending this run: {}", e.getMessage());
				return;
			} catch (Exception e) {
				log.error("Stamp lookup failed for deposit {} - continuing with the next one", transaction.getId(), e);
			}
		}

	}

	/**
	 * Entry point for the manual "Run Now" button on the Jobs page — same guard, same logic,
	 * same code path as the scheduled trigger.
	 *
	 * @return true if the run actually started, false if a previous run was still in progress.
	 */
	public boolean runNow() {
		if (!running.compareAndSet(false, true)) {
			return false;
		}
		try {
			JobRunTracker.run("stamp-lookup", this::runReadCardTransaction);
		} finally {
			running.set(false);
		}
		return true;
	}

	public boolean isRunning() {
		return running.get();
	}

	void handleCardTransactionWithMachineId(CustomerDeposit transaction) {
		 Optional<String> stampData = stampLookupService.findStampDataBySapReference(transaction.getSapReference());
	        if (stampData.isEmpty()) {
	            customerDepositRepository.incrementStampAttempts(transaction.getId());
	            return;
	        }
	        // Targeted update, not save(): the deposit-allocation job changes currentBalance on the same rows.
	        customerDepositRepository.updateStampData(transaction.getId(), stampData.get());
	}
}
