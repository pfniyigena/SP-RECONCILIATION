package nika.tax.reporter.oracle.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.zaxxer.hikari.HikariDataSource;

/**
 * Secondary datasource: Oracle (the MAGICASH source system, going by the connection details in
 * application-{profile}.properties). This is the FIRST real implementation of this class in
 * this project — it had only ever been discussed/referenced before (the "OracleJdbcConfig"
 * named in StampJdbcConfig's own comments), never actually built, because nothing had shown me
 * its real property structure until now.
 *
 * Two-step binding, not the single-prefix pattern StampJdbcConfig uses for its flat
 * spring.datasource.stamp.* — Oracle's real properties are structured differently:
 * spring.datasource.oracle.jdbc-url/username/password/driver-class-name for connection info
 * (in each application-{profile}.properties), and a SEPARATE, NESTED
 * spring.datasource.oracle.hikari.* for pool tuning (in the shared base application.properties,
 * applying across every profile). A single @ConfigurationProperties("spring.datasource.oracle")
 * bound directly onto a raw HikariDataSource — the simple pattern that correctly handles
 * stamp's flat structure — would silently fail to bind anything under that nested "hikari."
 * path, since HikariDataSource has no property literally called "hikari"; the pool would just
 * quietly fall back to Hikari's own default sizing instead of the tuned settings in
 * application.properties. Same reasoning as PrimaryDataSourceConfig, which needed the identical
 * fix for Postgres's equally-nested structure — see that class for the fuller explanation.
 *
 * No @Primary needed here (unlike PrimaryDataSourceConfig) — this is a genuine secondary
 * datasource, never meant to be the one Spring Boot's JPA autoconfiguration or anything else
 * picks by default; only reachable via the explicit @Qualifier below.
 *
 * Not wired to anything yet: nothing in this reference project actually queries Oracle —
 * the cards.pull.cron.expression / deposit.pull.cron.expression properties hint at an
 * ingestion job that would use this connection, but that job isn't implemented here. This
 * class makes the real property structure valid and the datasource itself connectable; the
 * actual "pull from MAGICASH" logic is a separate feature that wasn't part of this request.
 */
@Configuration
public class OracleJdbcConfig {

    @Bean(name = "oracleDataSourceBase")
    @ConfigurationProperties("spring.datasource.oracle")
    public HikariDataSource oracleDataSourceBase() {
        return DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean(name = "oracleDataSource")
    @ConfigurationProperties("spring.datasource.oracle.hikari")
    public DataSource oracleDataSource(@Qualifier("oracleDataSourceBase") HikariDataSource oracleDataSourceBase) {
        return oracleDataSourceBase;
    }

    @Bean(name = "oracleJdbcTemplate")
    public JdbcTemplate oracleJdbcTemplate(@Qualifier("oracleDataSource") DataSource oracleDataSource) {
        return new JdbcTemplate(oracleDataSource);
    }
}
