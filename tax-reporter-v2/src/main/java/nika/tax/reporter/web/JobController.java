package nika.tax.reporter.web;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import lombok.RequiredArgsConstructor;
import nika.tax.reporter.job.PostgresJob;
import nika.tax.reporter.oracle.job.OracleJdbcCardTransactionJob;
import nika.tax.reporter.oracle.job.OracleJdbcCustomerDepositJob;
import nika.tax.reporter.service.CustomerDepositMatchingService;
import nika.tax.reporter.sqlserver.job.SqlServerJdbcCustomerDepositJob;

/**
 * Operational page for background jobs — deposit matching, card transaction/invoice matching
 * (PostgresJob), the two Oracle ingestion pulls (OracleJdbcCardTransactionJob,
 * OracleJdbcCustomerDepositJob), and now the SQL Server stamp lookup
 * (SqlServerJdbcCustomerDepositJob), built as a list from the start specifically so each new
 * job had an obvious place to go without restructuring anything.
 * Admin-only (see SecurityConfig) — running a bulk background job manually is an operational
 * action, not something every role should be able to trigger.
 */
@Controller
@RequestMapping("/jobs")
@RequiredArgsConstructor
public class JobController {

    private final CustomerDepositMatchingService customerDepositMatchingService;
    private final PostgresJob postgresJob;
    private final OracleJdbcCardTransactionJob oracleJdbcCardTransactionJob;
    private final OracleJdbcCustomerDepositJob oracleJdbcCustomerDepositJob;
    private final SqlServerJdbcCustomerDepositJob sqlServerJdbcCustomerDepositJob;

    /** HTML <input type="date"> submits/binds as ISO (yyyy-MM-dd) — this reformats to what
     * OracleCardTransactionRepository/OracleCustomerDepositRepository's SQL actually expects
     * (dd/MM/yyyy, used directly in TO_DATE(?, 'DD/MM/YYYY')), so the jobs themselves don't
     * need to know or care where their dd/MM/yyyy strings came from — config property or UI
     * form field, same contract either way. */
    private static final DateTimeFormatter ORACLE_DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @GetMapping
    public String list(Model model) {
        model.addAttribute("depositMatchingRunning", customerDepositMatchingService.isRunning());
        model.addAttribute("invoiceMatchingRunning", postgresJob.isRunning());
        model.addAttribute("invoiceMatchingEnabled", postgresJob.isMatchingModeEnabled());
        model.addAttribute("cardPullRunning", oracleJdbcCardTransactionJob.isRunning());
        model.addAttribute("depositPullRunning", oracleJdbcCustomerDepositJob.isRunning());
        model.addAttribute("stampLookupRunning", sqlServerJdbcCustomerDepositJob.isRunning());
        return "jobs";
    }

    @PostMapping("/deposit-matching/run")
    public String runDepositMatching(RedirectAttributes redirectAttributes) {
        String message = customerDepositMatchingService.matchAll()
                .map(result -> result.transactionsCompleted() == 0
                        ? "No unmatched transactions found to match."
                        : result.transactionsCompleted() + " transaction(s) matched across "
                                + result.customersProcessed() + " customer(s).")
                .orElse("Matching is already running — try again shortly.");
        redirectAttributes.addFlashAttribute("flashMessage", message);
        return "redirect:/jobs";
    }

    @PostMapping("/invoice-matching/run")
    public String runInvoiceMatching(RedirectAttributes redirectAttributes) {
        // Blocks until the whole run finishes — same as CustomerDepositMatchingService's "Run
        // Now" button, not the async job-polling pattern the Excel/PDF exports use. Internally
        // parallelized across SDCs (ForkJoinPool), but that's independent of, and doesn't
        // change, this request itself waiting for the full run to complete before redirecting.
        String message;
        if (!postgresJob.isMatchingModeEnabled()) {
            message = "Matching mode is disabled in configuration (matching.mode.enabled) — nothing was run.";
        } else {
            boolean started = postgresJob.runNow();
            message = started
                    ? "Matching run complete — see server logs for per-SDC results."
                    : "Matching is already running — try again shortly.";
        }
        redirectAttributes.addFlashAttribute("flashMessage", message);
        return "redirect:/jobs";
    }

    @PostMapping("/card-pull/run")
    public String runCardPull(@RequestParam LocalDate startDate,
                               @RequestParam LocalDate endDate,
                               RedirectAttributes redirectAttributes) {
        String rangeError = validateRange(startDate, endDate);
        if (rangeError != null) {
            redirectAttributes.addFlashAttribute("flashMessage", rangeError);
            return "redirect:/jobs";
        }

        // Same blocking-request caveat as invoice-matching above — this waits for the whole
        // Oracle pull to finish before redirecting, it doesn't hand off to a background task.
        // Runs the maintenance-mode pass with the dates entered here, not the regular
        // today's-date pull — see OracleJdbcCardTransactionJob's javadoc for why the manual
        // trigger was changed to this.
        try {
            boolean started = oracleJdbcCardTransactionJob.runMaintenanceNow(
                    startDate.format(ORACLE_DATE_FORMAT), endDate.format(ORACLE_DATE_FORMAT));
            redirectAttributes.addFlashAttribute("flashMessage", started
                    ? "Card transaction pull complete for " + startDate + " to " + endDate + " — see server logs for results."
                    : "Card transaction pull is already running — try again shortly.");
        } catch (IllegalArgumentException e) {
            // Safety net, not the primary path — validateRange above already rejects this
            // before we get here in normal use. Catches the case where the job's own
            // validation and this controller's ever drift apart, rather than surfacing a
            // raw exception if that happens.
            redirectAttributes.addFlashAttribute("flashMessage", e.getMessage());
        }
        return "redirect:/jobs";
    }

    @PostMapping("/deposit-pull/run")
    public String runDepositPull(@RequestParam LocalDate startDate,
                                  @RequestParam LocalDate endDate,
                                  RedirectAttributes redirectAttributes) {
        String rangeError = validateRange(startDate, endDate);
        if (rangeError != null) {
            redirectAttributes.addFlashAttribute("flashMessage", rangeError);
            return "redirect:/jobs";
        }

        try {
            boolean started = oracleJdbcCustomerDepositJob.runMaintenanceNow(
                    startDate.format(ORACLE_DATE_FORMAT), endDate.format(ORACLE_DATE_FORMAT));
            redirectAttributes.addFlashAttribute("flashMessage", started
                    ? "Deposit pull complete for " + startDate + " to " + endDate + " — see server logs for results."
                    : "Deposit pull is already running — try again shortly.");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("flashMessage", e.getMessage());
        }
        return "redirect:/jobs";
    }

    /**
     * Shared by both Oracle maintenance-pull endpoints. Rejects here, before either job is
     * even called, so an invalid range never touches the AtomicBoolean guard or Oracle at all
     * — the job's own runMaintenanceNow also validates independently (see its javadoc), since
     * it's a public method other callers besides this form could reach, but this earlier
     * check is what actually gives the person a clear message instead of a stack trace.
     *
     * @return an error message if the range is invalid, or null if it's fine to proceed.
     */
    private String validateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate.isAfter(endDate)) {
            return "Invalid date range: " + startDate + " is after " + endDate + " — the start date must be on or before the end date.";
        }
        return null;
    }

    @PostMapping("/stamp-lookup/run")
    public String runStampLookup(RedirectAttributes redirectAttributes) {
        boolean started = sqlServerJdbcCustomerDepositJob.runNow();
        redirectAttributes.addFlashAttribute("flashMessage", started
                ? "Stamp lookup complete — see server logs for results."
                : "Stamp lookup is already running — try again shortly.");
        return "redirect:/jobs";
    }
}
