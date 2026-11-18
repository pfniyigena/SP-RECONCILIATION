package nika.tax.reporter.sqlserver.service;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import lombok.extern.slf4j.Slf4j;
import nika.tax.reporter.sqlserver.exception.StampLookupException;

/**
 * Looks up stamp data from the external SQL Server Stamp table by sapReference — used by
 * CustomerDepositService's "fetch stamp data" action, not called anywhere on the normal
 * request path, so a slow/unreachable SQL Server can only ever affect that one action, never
 * the rest of the app (which runs entirely against the primary PostgreSQL datasource).
 *
 * Moved from nika.tax.reporter.service to nika.tax.reporter.sqlserver.service to match the
 * real app's package, and STAMP_QUERY updated to the real table/column names
 * (dbo.Invoice_Table / RRAString / invoiceNumber) — an earlier version of this class had these
 * as a flagged, assumed placeholder (dbo.Stamp / stamp_data / sap_reference) since the real
 * schema wasn't known yet.
 *
 * One real bug fixed from the uploaded version: findStampDataBySapReference had two
 * back-to-back checks for the same condition —
 *   if (!StringUtils.hasText(sapReference) || !sapReference.matches("\\d+")) return Optional.empty();
 *   if (!sapReference.matches("\\d+")) return Optional.of("Invalid sapReference format: " + sapReference);
 * The second check is unreachable: by the time execution reaches it, the first check has
 * already returned for every case where the digits-only match fails, so
 * "!sapReference.matches(...)" can never be true there. Even if it HAD been reachable, the
 * behavior would still have been wrong — this method's contract is "the matching stamp data,
 * or empty if none," and returning an error-message string wrapped in Optional.of(...) means
 * the caller (SqlServerJdbcCustomerDepositJob) would have saved that literal error message
 * onto CustomerDeposit.stampData as if it were real data. Collapsed to the one correct check:
 * blank or non-digits both mean "can't look this up," which is Optional.empty(), full stop.
 *
 * Constructor is written by hand (no @RequiredArgsConstructor) specifically to attach
 * @Qualifier — Spring Boot auto-configures its own default JdbcTemplate wired to the PRIMARY
 * (Postgres) datasource via spring-boot-starter-jdbc (pulled in transitively by
 * spring-boot-starter-data-jpa), so without this qualifier there would be two JdbcTemplate
 * beans in the context and Spring would refuse to guess which one belongs here.
 */
@Service
@Slf4j
public class StampLookupService {

    private static final String STAMP_QUERY =
            "SELECT RRAString FROM dbo.Invoice_Table WHERE invoiceNumber = ?";

    private final JdbcTemplate stampJdbcTemplate;

    public StampLookupService(@Qualifier("stampJdbcTemplate") JdbcTemplate stampJdbcTemplate) {
        this.stampJdbcTemplate = stampJdbcTemplate;
    }

    /**
     * @return the matching stamp data, or empty if no row matches sapReference, or sapReference
     *         itself isn't a plausible lookup key (blank, or not digits-only) — all three are
     *         normal, expected outcomes, not errors.
     * @throws StampLookupException if the SQL Server connection/query itself fails (network,
     *         auth, unreachable server, etc.) — distinct from "not found" so the caller (and
     *         the UI) can tell "nothing to fetch yet" apart from "couldn't even check."
     */
    public Optional<String> findStampDataBySapReference(String sapReference) {

        if (!StringUtils.hasText(sapReference) || !sapReference.matches("\\d+")) {
            return Optional.empty();
        }

        try {
            List<String> results = stampJdbcTemplate.query(STAMP_QUERY,
                    (rs, rowNum) -> rs.getString("RRAString"),
                    sapReference.trim());
            return results.stream().findFirst();
        } catch (DataAccessException e) {
            log.error("Stamp lookup failed for sapReference={}", sapReference, e);
            throw new StampLookupException(
                    "Could not reach the Stamp database. Check the connection and try again.", e);
        }
    }
}
