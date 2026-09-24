package nika.tax.reporter.config;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.zaxxer.hikari.HikariDataSource;

/**
 * Explicitly defines the primary (Postgres) DataSource bean. This became necessary the moment
 * StampJdbcConfig (and, in the real app, OracleJdbcConfig) defined their own secondary
 * DataSource beans — Spring Boot's own DataSourceAutoConfiguration is guarded by
 * @ConditionalOnMissingBean(DataSource.class), a TYPE-based check, not a name-based one. Once
 * ANY bean of type DataSource exists anywhere in the context, Spring Boot sees that condition
 * as already satisfied and skips creating its own primary datasource entirely, regardless of
 * what the existing bean is actually named or what it's meant to be used for.
 *
 * This is what caused two failures in sequence, not one:
 *   1. With only stampDataSource present and no primary bean ever created, Hibernate had
 *      exactly one DataSource to use and used it — so a downed SQL Server took the whole app
 *      down, even though Postgres itself was fine the whole time.
 *   2. After stampDataSource was excluded from autowiring (@Bean(autowireCandidate = false)),
 *      there were then ZERO DataSource beans available for anything — "No qualifying bean of
 *      type DataSource" — because the real fix was never applied: the primary bean still
 *      didn't exist.
 *
 * Uses the DataSourceProperties intermediary (not a raw @ConfigurationProperties binding
 * directly onto HikariDataSource, the way the secondary datasources are built) specifically
 * because application.yml's spring.datasource.* already uses `url`, not `jdbc-url` — Spring's
 * own DataSourceProperties/initializeDataSourceBuilder() correctly translates that to whatever
 * the target implementation actually needs (HikariDataSource.setJdbcUrl()); binding directly
 * onto a raw HikariDataSource does not get that translation and would require renaming the
 * existing, already-working `url` property to `jdbc-url` — a needless breaking change here.
 *
 * @Primary on both beans below: belt-and-suspenders alongside stampDataSource's
 * autowireCandidate=false — either mechanism alone would have been enough, but having both
 * means this can't silently regress if one of the two is ever changed without the other.
 */
@Configuration
public class PrimaryDataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "dataSource")
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }
}
