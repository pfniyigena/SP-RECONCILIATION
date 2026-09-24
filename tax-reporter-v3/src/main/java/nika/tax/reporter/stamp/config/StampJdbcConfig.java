package nika.tax.reporter.stamp.config;

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
 * as the declared type rather than a concrete Hikari type.
 *
 * HISTORY, because this took two corrections to get right and both are worth understanding:
 *
 * 1. Original version relied on "no @Primary on either bean" to keep this datasource out of
 *    the way. Wrong — Spring Boot's JPA autoconfiguration ended up wiring THIS bean in as the
 *    primary datasource for Hibernate, so an unreachable SQL Server took the whole app down.
 *    Root cause turned out to be one level deeper: Spring Boot's own primary-datasource
 *    auto-configuration is gated by @ConditionalOnMissingBean(DataSource.class) — a TYPE check,
 *    not a name check — so the mere existence of THIS bean (regardless of its name) made Spring
 *    Boot skip creating its own Postgres datasource entirely. Real fix: PrimaryDataSourceConfig
 *    now explicitly defines that bean, @Primary, alongside this one.
 *
 * 2. Second correction, after the first one: this class was also given
 *    @Bean(autowireCandidate = false) as a belt-and-suspenders measure, on the mistaken claim
 *    that explicit @Qualifier("stampDataSource")/@Qualifier("stampJdbcTemplate") references
 *    would be unaffected. That claim was WRONG and broke StampLookupService's own startup —
 *    @Autowired + @Qualifier still goes through the same isAutowireCandidate() filtering that
 *    autowireCandidate=false gates; only bean-factory-name lookups like @Resource(name=...),
 *    which bypass that pipeline entirely, are actually unaffected by it. @Qualifier is not one
 *    of those. Removed here — @Primary on the real datasource (PrimaryDataSourceConfig) is
 *    sufficient on its own to win any ambiguous/unqualified resolution, and doesn't carry this
 *    side effect, so there was never a need for both.
 *
 * IMPORTANT — same caveat applies here as to OracleJdbcConfig: because @ConfigurationProperties
 * is bound directly onto the object DataSourceBuilder returns (a HikariDataSource), binding
 * goes through plain reflection against Hikari's own setter names — it does NOT get
 * DataSourceBuilder's usual "url" -> vendor-specific-property translation, which only applies
 * to properties passed through the builder's own fluent methods (.url(...), .username(...)),
 * not to @ConfigurationProperties binding applied after the fact. HikariDataSource has
 * setJdbcUrl(), not setUrl() — so application.yml MUST use jdbc-url here, not url, or that one
 * property silently fails to bind with no error at startup. Worth double-checking the existing
 * spring.datasource.oracle.* properties use jdbc-url too, for the same reason — and worth
 * checking OracleJdbcConfig for the SAME two issues this class went through: does a
 * PrimaryDataSourceConfig-equivalent exist for the real app's actual primary datasource, and
 * is anything there using autowireCandidate=false in a way that would break its own
 * @Qualifier-based injection points the same way this did.
 */
@Configuration
public class StampJdbcConfig {

    @Bean(name = "stampDataSource")
    @ConfigurationProperties("spring.datasource.stamp")
    public DataSource stampDataSource() {
        return DataSourceBuilder.create()
                .type(com.zaxxer.hikari.HikariDataSource.class)
                .build();
    }

    @Bean(name = "stampJdbcTemplate")
    public JdbcTemplate stampJdbcTemplate(
            @Qualifier("stampDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
