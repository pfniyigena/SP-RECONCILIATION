package nika.tax.reporter.config;

import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Needed by OracleJdbcCardTransactionJob/OracleJdbcCustomerDepositJob (constructor-injected
 * ZoneId, not a String property read directly) — no bean like this existed before those jobs
 * needed one.
 *
 * app.timezone only appears in the per-profile properties files (dev/oracle/sp), not the
 * shared base application.properties — so a plain build with no Maven profile active (this
 * project's "default" fallback, see pom.xml) would have no value for it at all. Falls back to
 * UTC rather than letting that be a hard startup failure; if "niwe" or another profile is
 * meant to set its own timezone, add app.timezone to its properties file the same way dev/
 * oracle/sp already do.
 */
@Configuration
public class TimeZoneConfig {

    @Bean
    public ZoneId zoneId(@Value("${app.timezone:UTC}") String timezone) {
        return ZoneId.of(timezone);
    }
}
