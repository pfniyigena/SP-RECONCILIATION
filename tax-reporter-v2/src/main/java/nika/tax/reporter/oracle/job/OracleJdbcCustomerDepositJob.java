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
import nika.tax.reporter.oracle.dto.CustomerDepositDto;
import nika.tax.reporter.oracle.repository.OracleCustomerDepositRepository;
import nika.tax.reporter.postgres.domain.Customer;
import nika.tax.reporter.postgres.domain.CustomerDeposit;
import nika.tax.reporter.repository.CustomerDepositRepository;
import nika.tax.reporter.repository.CustomerRepository;

/**
 * Pulls customer deposits from Oracle (MAGICASH) into this app's own CustomerDeposit table.
 *
 * Two methods renamed at request — both had names copy-pasted from the sibling
 * OracleJdbcCardTransactionJob ("CardTransaction" in a class that's actually about
 * CustomerDeposit): readCardTransaction -> readCustomerDeposit,
 * readCardTransactionInMaintenanceMode -> readCustomerDepositInMaintenanceMode. Behavior
 * unchanged by the rename itself.
 *
 * UI trigger changed: the manual "Run Now" button now runs the MAINTENANCE-mode pass
 * (readCustomerDepositInMaintenanceMode's underlying logic) with start/end dates the person
 * picks in the UI, not the regular today's-date pull. readCustomerDeposit() is UNCHANGED and
 * still runs on its own schedule (deposit.pull.cron.expression) — it just no longer has a
 * manual trigger of its own, since the request was to replace the manual entry point, not add
 * a second one alongside it.
 *
 * isMaintenanceMode (maintenace.mode.enabled) is checked for the SCHEDULED maintenance trigger
 * only, not the manual one — a person explicitly picking dates and clicking "Run Now" is
 * already an intentional, deliberate action; gating that behind a config flag meant for "should
 * this run automatically" would just be confusing (click a button with real dates entered, get
 * told nothing happened because of an unrelated setting). This is a real behavior decision, not
 * implied by the rename/wiring request — flagged here rather than silently applied.
 */
@Configuration
@Slf4j
public class OracleJdbcCustomerDepositJob {
	private static final DateTimeFormatter MAINTENANCE_DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

	private final OracleCustomerDepositRepository oracleCustomerDepositRepository;
	private final CustomerDepositRepository customerDepositRepository;
	private final CustomerRepository customerRepository;
	@Value("${maintenace.start.date}")
	private String startDate;
	@Value("${maintenace.end.date}")
	private String endDate;
	@Value("${maintenace.mode.enabled}")
	private boolean isMaintenanceMode;
	private final ZoneId zoneId;

	private final AtomicBoolean running = new AtomicBoolean(false);

	public OracleJdbcCustomerDepositJob(OracleCustomerDepositRepository oracleCustomerDepositRepository,
			CustomerDepositRepository customerDepositRepository, ZoneId zoneId,
			CustomerRepository customerRepository) {
		this.oracleCustomerDepositRepository = oracleCustomerDepositRepository;
		this.customerDepositRepository = customerDepositRepository;
		this.customerRepository = customerRepository;
		this.zoneId = zoneId;
	}

	@Scheduled(cron = "${deposit.pull.cron.expression}")
	void readCustomerDeposit() {
		if (!running.compareAndSet(false, true)) {
			log.warn("Deposit pull requested, but a previous run is still in progress — skipping this trigger.");
			return;
		}
		try {
			runReadCustomerDeposit();
		} finally {
			running.set(false);
		}
	}

	private void runReadCustomerDeposit() {
		LocalDate date = LocalDate.now(zoneId);
		LocalDateTime start = date.atStartOfDay();
		LocalDateTime end = date.plusDays(1).atStartOfDay();
		log.info("READING DEPOSIT TRANSACTIONS FROM ORACLE DATABASE BETWEEN {} AND {}", start, end);
		var transactions = oracleCustomerDepositRepository.findTodayTransaction(start, end);
		log.info("Found {} deposit transactions from Oracle database between {} and {}", transactions.size(), start, end);

		try {
			for (CustomerDepositDto transaction : transactions) {
				handleCardTransactionWithMachineId(transaction);
			}

		} catch (Exception e) {
			log.error("Error reading deposit transactions from Oracle database", e);
		}
		log.info(
				"=======END IF READING DEPOSIT TRANSACTION FROM ORACLE DATABASE BETWEEN {} AND {} FOR CARD TRANSACTIONS:{} ",
				start, end, transactions.size());

	}

	public boolean isRunning() {
		return running.get();
	}

	@Scheduled(cron = "${deposit.maintenance.cron.expression}")
	void readCustomerDepositInMaintenanceMode() {
		if (!running.compareAndSet(false, true)) {
			log.warn("Deposit maintenance pull requested, but a previous run is still in progress — skipping this trigger.");
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
	 * maintenace.mode.enabled (see class javadoc for why).
	 *
	 * @param startDate  dd/MM/yyyy, matching what OracleCustomerDepositRepository's SQL expects
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
		log.info("READING DEPOSIT TRANSACTION FROM ORACLE DATABASE IN MAINTENANCE MODE BETWEEN {} AND {}", startDate,
				endDate);
		var transactions = oracleCustomerDepositRepository.findAllForMaintenace(startDate, endDate);
		try {
			for (CustomerDepositDto transaction : transactions) {
				handleCardTransactionWithMachineId(transaction);
			}

		} catch (Exception e) {
			log.error("Error reading card transactions from Oracle database", e);
		}
		log.info(
				"========END OF READING DEPOSIT TRANSACTION FROM ORACLE DATABASE IN MAINTENANCE MODE BETWEEN {} AND {} FOR CARD TRANSACTIONS:{}",
				startDate, endDate, transactions.size());
	}

	void handleCardTransactionWithMachineId(CustomerDepositDto transaction) {
		log.info("Handling card transaction: {}", transaction);
		Customer customer = customerRepository.getByClientId(transaction.getClientId()).orElseGet(() -> {
			log.warn("Customer  not found for posId: {}, posName: {}", transaction.getClientId(),
					transaction.getClientName());

			Customer newCustomer = transaction.toCustomerEntity();
			return customerRepository.save(newCustomer);
		});

		customerDepositRepository.getByTransactionGuid(transaction.getTransactionGuid()).ifPresentOrElse(
				existingTransaction -> log.info("Card transaction already exists: {}", existingTransaction), () -> {
					CustomerDeposit newTransaction = transaction.toCustomerDepositEntity();

					newTransaction.setCustomer(customer); // if relationship exists

					newTransaction = customerDepositRepository.save(newTransaction);
					log.info("Saved new deposit transaction: {}", newTransaction);
				});
	}
}
