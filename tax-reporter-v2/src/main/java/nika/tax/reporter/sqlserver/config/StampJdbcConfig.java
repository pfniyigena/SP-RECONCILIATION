package nika.tax.reporter.sqlserver.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Secondary datasource: a read-only connection to the external SQL Server database holding
 * the Stamp table, used only by StampLookupService to resolve a CustomerDeposit's stampData
 * from its sapReference. Same shape as nika.tax.reporter.oracle.config.OracleJdbcConfig on
 * purpose — DataSourceBuilder + @ConfigurationProperties directly on the bean method, DataSource
 * as the declared type rather than a concrete Hikari type, no @Primary on either bean so
 * nothing else in the app can end up wired to this pool via unqualified injection.
 *
 * Moved from nika.tax.reporter.stamp.config to match the real app's package
 * (nika.tax.reporter.sqlserver.config) — same reasoning as every other real-file upload in
 * this project: match the actual structure once it's known, rather than keep an earlier guess.
 *
 * HISTORY worth keeping even though the file itself is simpler now — this class went through
 * two real, confirmed bugs earlier in this project that are worth remembering if this pattern
 * gets copied elsewhere (e.g. checking OracleJdbcConfig for the same two issues):
 *   1. Spring Boot's own primary-datasource auto-configuration is gated by
 *      @ConditionalOnMissingBean(DataSource.class) — a TYPE check, not a name check — so this
 *      bean's mere existence, regardless of name, made Spring Boot skip creating its own
 *      Postgres datasource entirely, and an unreachable SQL Server took the whole app down.
 *      Fixed by PrimaryDataSourceConfig explicitly defining the primary datasource, @Primary,
 *      alongside this one.
 *   2. @Bean(autowireCandidate = false) was tried as a belt-and-suspenders measure on the
 *      mistaken assumption that explicit @Qualifier references would be unaffected — they are
 *      NOT: @Autowired + @Qualifier goes through the same isAutowireCandidate() filtering that
 *      autowireCandidate=false gates. Only bean-factory-name lookups like @Resource(name=...)
 *      bypass that pipeline. Removed — @Primary on the real primary datasource is sufficient
 *      on its own and doesn't carry this side effect.
 *
 * IMPORTANT — same caveat applies here as to OracleJdbcConfig: because @ConfigurationProperties
 * is bound directly onto the object DataSourceBuilder returns (a HikariDataSource), binding
 * goes through plain reflection against Hikari's own setter names — it does NOT get
 * DataSourceBuilder's usual "url" -> vendor-specific-property translation, which only applies
 * to properties passed through the builder's own fluent methods (.url(...), .username(...)),
 * not to @ConfigurationProperties binding applied after the fact. HikariDataSource has
 * setJdbcUrl(), not setUrl() — so application.properties MUST use jdbc-url here, not url, or
 * that one property silently fails to bind with no error at startup.
 */
@Configuration
public class StampJdbcConfig {

    @Bean
    @ConfigurationProperties("spring.datasource.stamp")
    DataSource stampDataSource() {
        return DataSourceBuilder.create()
                .type(com.zaxxer.hikari.HikariDataSource.class)
                .build();
    }

    @Bean
    public JdbcTemplate stampJdbcTemplate(
            @Qualifier("stampDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
