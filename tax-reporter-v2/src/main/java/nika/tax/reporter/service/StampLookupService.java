package nika.tax.reporter.service;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * Looks up stamp_data from the external SQL Server Stamp table by sapReference — used by
 * CustomerDepositService's "fetch stamp data" action, not called anywhere on the normal
 * request path, so a slow/unreachable SQL Server can only ever affect that one action, never
 * the rest of the app (which runs entirely against the primary PostgreSQL datasource).
 *
 * ASSUMPTIONS, flagged because I don't have your actual SQL Server schema: table dbo.Stamp,
 * column sap_reference (matches CustomerDeposit.sapReference), column stamp_data (matches
 * CustomerDeposit.stampData). Adjust STAMP_QUERY below to your real table/column names —
 * everything else in this class works regardless of what that SQL actually says.
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
            "SELECT stamp_data FROM dbo.Stamp WHERE sap_reference = ?";

    private final JdbcTemplate stampJdbcTemplate;

    public StampLookupService(@Qualifier("stampJdbcTemplate") JdbcTemplate stampJdbcTemplate) {
        this.stampJdbcTemplate = stampJdbcTemplate;
    }

    /**
     * @return the matching stamp_data, or empty if no row matches sapReference — this is a
     *         normal, expected outcome (not every deposit has a stamp yet), not an error.
     * @throws StampLookupException if the SQL Server connection/query itself fails (network,
     *         auth, unreachable server, etc.) — distinct from "not found" so the caller (and
     *         the UI) can tell "nothing to fetch yet" apart from "couldn't even check."
     */
    public Optional<String> findStampDataBySapReference(String sapReference) {
        if (!StringUtils.hasText(sapReference)) {
            return Optional.empty();
        }
        try {
            List<String> results = stampJdbcTemplate.query(STAMP_QUERY,
                    (rs, rowNum) -> rs.getString("stamp_data"),
                    sapReference.trim());
            return results.stream().findFirst();
        } catch (DataAccessException e) {
            log.error("Stamp lookup failed for sapReference={}", sapReference, e);
            throw new StampLookupException(
                    "Could not reach the Stamp database. Check the connection and try again.", e);
        }
    }
}
