package nika.tax.reporter.oracle.job;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import lombok.extern.slf4j.Slf4j;
import nika.tax.reporter.oracle.dto.CardTransactionDto;
import nika.tax.reporter.oracle.repository.OracleCardTransactionRepository;
import nika.tax.reporter.postgres.domain.CardTransaction;
import nika.tax.reporter.postgres.domain.Customer;
import nika.tax.reporter.postgres.domain.TerminalMachine;
import nika.tax.reporter.repository.CardTransactionRepository;
import nika.tax.reporter.repository.CustomerRepository;
import nika.tax.reporter.repository.TerminalMachineRepository;

/**
 * Pulls card transactions from Oracle (MAGICASH) into this app's own CardTransaction table.
 * Two fixes applied to the uploaded version, both matching this project's actual structure
 * rather than the real app's:
 *   - nika.tax.reporter.postgres.domain.v2.Customer -> nika.tax.reporter.postgres.domain.Customer
 *     (no .v2) — same fix already applied once before for a different file in this project.
 *   - nika.tax.reporter.postgres.repository.{TerminalMachineRepository,
 *     v2.CardTransactionRepositoryV2, v2.CustomerRepositoryV2} -> this project's actual
 *     nika.tax.reporter.repository.{TerminalMachineRepository, CardTransactionRepository,
 *     CustomerRepository} — no "V2" suffix and no "postgres." in the package here; this
 *     project's repositories all live directly under nika.tax.reporter.repository.
 *
 * AtomicBoolean guard added — not present in the uploaded version, same reasoning as every
 * other job in this project that's reachable from both a cron trigger and a manual "Run Now"
 * button (CustomerDepositMatchingService, PostgresJob): without it, an overlapping run could
 * fire while a previous one was still working through the same transaction list. Both
 * @Scheduled methods here share ONE guard, not two separate ones — they operate on the same
 * underlying data (card transactions) and could otherwise race on the same
 * check-then-insert pattern in handleCardTransactionWithMachineId, even though
 * getByTransactionGuid would still stop an actual duplicate row from landing.
 *
 * handleCardTransaction() is preserved even though it's dead code — nothing calls it, only
 * handleCardTransactionWithMachineId() is used by either @Scheduled method — kept as given
 * rather than removed, since it's not this project's place to delete logic from pasted
 * business code that might be a deliberate work-in-progress or reference implementation.
 *
 * UI trigger changed: the manual "Run Now" button now runs the MAINTENANCE-mode pass with
 * start/end dates the person picks in the UI, not the regular today's-date pull. That regular
 * pull (readCardTransaction, unrenamed — this class's naming was already correct) still runs
 * on its own schedule; it just no longer has a manual trigger of its own. maintenace.mode.enabled
 * is checked for the scheduled maintenance trigger only, not the manual one — see
 * OracleJdbcCustomerDepositJob's javadoc for the fuller reasoning, same decision applies here.
 */
@Configuration
@Slf4j
public class OracleJdbcCardTransactionJob {
	private static final DateTimeFormatter MAINTENANCE_DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

	private final OracleCardTransactionRepository oracleCardTransactionRepository;
	private final CardTransactionRepository cardTransactionRepository;
	private final TerminalMachineRepository terminalMachineRepository;
	private final CustomerRepository customerRepository;
	@Value("${maintenace.start.date}")
	private String startDate;
	@Value("${maintenace.end.date}")
	private String endDate;
	@Value("${maintenace.mode.enabled}")
	private boolean isMaintenanceMode;
	private final ZoneId zoneId;

	private final AtomicBoolean running = new AtomicBoolean(false);

	public OracleJdbcCardTransactionJob(OracleCardTransactionRepository oracleCardTransactionRepository,
			CardTransactionRepository cardTransactionRepository, ZoneId zoneId,
			TerminalMachineRepository terminalMachineRepository, CustomerRepository customerRepository) {
		this.oracleCardTransactionRepository = oracleCardTransactionRepository;
		this.cardTransactionRepository = cardTransactionRepository;
		this.terminalMachineRepository = terminalMachineRepository;
		this.customerRepository = customerRepository;
		this.zoneId = zoneId;
	}

	@Scheduled(cron = "${cards.pull.cron.expression}")
	void readCardTransaction() {
		if (!running.compareAndSet(false, true)) {
			log.warn("Card transaction pull requested, but a previous run is still in progress — skipping this trigger.");
			return;
		}
		try {
			runReadCardTransaction();
		} finally {
			running.set(false);
		}
	}

	private void runReadCardTransaction() {
		LocalDate date = LocalDate.now(zoneId);
		LocalDateTime start = date.atStartOfDay();
		LocalDateTime end = date.plusDays(1).atStartOfDay();
		log.info("READING CARD TRANSACTION FROM ORACLE DATABASE BETWEEN {} AND {}", start, end);
		var transactions = oracleCardTransactionRepository.findTodayTransaction(start, end);
		log.info("Found {} card transactions from Oracle database between {} and {}", transactions.size(), start, end);

		try {
			for (CardTransactionDto transaction : transactions) {
				handleCardTransactionWithMachineId(transaction);
			}

		} catch (Exception e) {
			log.error("Error reading card transactions from Oracle database", e);
		}
		log.info(
				"=======END IF READING CARD TRANSACTION FROM ORACLE DATABASE BETWEEN {} AND {} FOR CARD TRANSACTIONS:{} ",
				start, end, transactions.size());

	}

	public boolean isRunning() {
		return running.get();
	}

	@Scheduled(cron = "${cards.maintenance.cron.expression}")
	void readCardTransactionInMaintenanceMode() {
		if (!running.compareAndSet(false, true)) {
			log.warn("Card transaction maintenance pull requested, but a previous run is still in progress — skipping this trigger.");
			return;
		}
		try {
			if (!isMaintenanceMode) {
				log.info("Maintenance mode is disabled. Skipping card transaction reading.");
				return;
			}
			runMaintenanceMode(startDate, endDate);
		} finally {
			running.set(false);
		}
	}

	/**
	 * Entry point for the manual "Run Now" button on the Jobs page — runs the maintenance-mode
	 * pull with the start/end dates the person entered, NOT the configured
	 * maintenace.start.date/maintenace.end.date defaults, and NOT gated by
	 * maintenace.mode.enabled — a person explicitly picking dates and clicking "Run Now" is
	 * already a deliberate action; gating that behind a config flag meant for "should this run
	 * automatically" would just be confusing. Same behavior decision as
	 * OracleJdbcCustomerDepositJob.runMaintenanceNow, flagged there in more detail.
	 *
	 * Replaces the previous runNow(), which triggered the regular (today's date range) pull —
	 * that pull still runs on its own schedule (cards.pull.cron.expression), it just no longer
	 * has a manual trigger of its own, since the request was to replace the manual entry
	 * point, not add a second one alongside it.
	 *
	 * @param startDate  dd/MM/yyyy, matching what OracleCardTransactionRepository's SQL expects
	 * @param endDate    dd/MM/yyyy
	 * @return true if the run actually started, false if a previous run was still in progress.
	 * @throws IllegalArgumentException if either date fails to parse as dd/MM/yyyy, or if
	 *         startDate is after endDate. Checked here too, not just in JobController —
	 *         this is a public method, and the UI form isn't the only conceivable caller.
	 *         Deliberately checked BEFORE acquiring the running guard, so a rejected request
	 *         never marks the job as running and never blocks a legitimate one that follows it.
	 */
	public boolean runMaintenanceNow(String startDate, String endDate) {
		validateRange(startDate, endDate);
		if (!running.compareAndSet(false, true)) {
			return false;
		}
		try {
			runMaintenanceMode(startDate, endDate);
		} finally {
			running.set(false);
		}
		return true;
	}

	private static void validateRange(String startDate, String endDate) {
		LocalDate start;
		LocalDate end;
		try {
			start = LocalDate.parse(startDate, MAINTENANCE_DATE_FORMAT);
			end = LocalDate.parse(endDate, MAINTENANCE_DATE_FORMAT);
		} catch (DateTimeParseException e) {
			throw new IllegalArgumentException(
					"startDate/endDate must be in dd/MM/yyyy format — got \"" + startDate + "\" / \"" + endDate + "\".", e);
		}
		if (start.isAfter(end)) {
			throw new IllegalArgumentException(
					"Invalid date range: startDate " + startDate + " is after endDate " + endDate + ".");
		}
	}

	private void runMaintenanceMode(String startDate, String endDate) {
		log.info("READING CARD TRANSACTION FROM ORACLE DATABASE IN MAINTENANCE MODE BETWEEN {} AND {}", startDate,
				endDate);
		var transactions = oracleCardTransactionRepository.findAllForMaintenace(startDate, endDate);
		try {
			for (CardTransactionDto transaction : transactions) {
				handleCardTransactionWithMachineId(transaction);
			}

		} catch (Exception e) {
			log.error("Error reading card transactions from Oracle database", e);
		}
		log.info(
				"========END OF READING CARD TRANSACTION FROM ORACLE DATABASE IN MAINTENANCE MODE BETWEEN {} AND {} FOR CARD TRANSACTIONS:{}",
				startDate, endDate, transactions.size());
	}

	void handleCardTransaction(CardTransactionDto transaction) {
		log.info("Handling card transaction: {}", transaction);

		terminalMachineRepository.getByPosNumber(transaction.getPosNumber())
				.ifPresentOrElse(terminalMachine -> log.info("Found terminal machine: {}", terminalMachine), () -> {
					log.warn("Terminal machine not found for posId: {},posName:{}", transaction.getPosNumber(),
							transaction.getPosName());
					terminalMachineRepository.save(transaction.toTerminalMachineEntity());
				});

		cardTransactionRepository.getByTransactionGuid(transaction.getTransactionGuid()).ifPresentOrElse(
				existingTransaction -> log.info("Card transaction already exists: {}", existingTransaction), () -> {
					var newTransaction = cardTransactionRepository.save(transaction.toCardTransactionEntity());
					log.info("Saved new card transaction: {}", newTransaction);
				});

	}

	void handleCardTransactionWithMachineId(CardTransactionDto transaction) {
		log.info("Handling card transaction: {}", transaction);
		Customer customer = customerRepository.getByClientId(transaction.getClientId()).orElseGet(() -> {
			log.warn("Customer  not found for posId: {}, posName: {}", transaction.getPosNumber(),
					transaction.getPosName());

			Customer newCustomer = transaction.toCustomerEntity();
			return customerRepository.save(newCustomer);
		});
		TerminalMachine terminalMachine = terminalMachineRepository.getByPosNumber(transaction.getPosNumber())
				.orElseGet(() -> {
					log.warn("Terminal machine not found for posId: {}, posName: {}", transaction.getPosNumber(),
							transaction.getPosName());

					TerminalMachine newTerminal = transaction.toTerminalMachineEntity();
					return terminalMachineRepository.save(newTerminal);
				});

		log.info("Using terminal machine: {}", terminalMachine);
		cardTransactionRepository.getByTransactionGuid(transaction.getTransactionGuid()).ifPresentOrElse(
				existingTransaction -> log.info("Card transaction already exists: {}", existingTransaction), () -> {
					CardTransaction newTransaction = transaction.toCardTransactionEntity();
					newTransaction.setMachine(terminalMachine); // if relationship exists
					newTransaction.setCustomer(customer); // if relationship exists
					newTransaction.setSdcId(terminalMachine.getSdcId()); // set sdcId from terminal machine
					newTransaction = cardTransactionRepository.save(newTransaction);
					log.info("Saved new card transaction: {}", newTransaction);
				});
	}
}
