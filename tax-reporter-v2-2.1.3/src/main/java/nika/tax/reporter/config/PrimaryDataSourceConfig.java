package nika.tax.reporter.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.zaxxer.hikari.HikariDataSource;

/**
 * Explicitly defines the primary (Postgres) DataSource bean — required because Spring Boot's
 * own DataSourceAutoConfiguration backs off entirely once any DataSource-typed bean exists
 * anywhere in the context (StampJdbcConfig defines one), a type-based @ConditionalOnMissingBean
 * check, not a name-based one. Full history of why this class exists at all is in its own
 * earlier commit — the short version: without it, an unreachable SQL Server could take down
 * the whole app even though Postgres was fine, because Spring Boot never created its own
 * primary datasource to fall back to.
 *
 * REBUILT after seeing the real app's actual property structure:
 * spring.datasource.postgres.jdbc-url/username/password/driver-class-name for connection
 * info, and a SEPARATE, NESTED spring.datasource.postgres.hikari.* for pool tuning (this
 * project's application.properties keeps that pool-tuning block in the shared base file,
 * since it applies across every profile; connection info lives in each application-{profile}
 * file). That nested "hikari." sub-key is structurally different from
 * spring.datasource.stamp.* (StampJdbcConfig), which is flat with no such nesting.
 *
 * This matters because a single @ConfigurationProperties("spring.datasource.postgres") bound
 * directly onto a raw HikariDataSource — the same simple pattern StampJdbcConfig correctly
 * uses for ITS flat properties — would NOT correctly bind the nested hikari.* properties here:
 * HikariDataSource has no property literally called "hikari", so Spring's binder would just
 * silently skip anything under that path, and the datasource would quietly fall back to
 * Hikari's own default pool size (10) instead of the tuned settings in application.properties
 * (max 20, min-idle 5, etc.) — no error, no warning, just wrong pool sizing in production.
 *
 * Fixed with two layered bindings on the SAME underlying HikariDataSource: the first binds
 * spring.datasource.postgres (connection info — jdbc-url/username/password/driver-class-name,
 * which happen to be real HikariDataSource property names, so this part binds correctly even
 * without any translation), the second binds spring.datasource.postgres.hikari (pool tuning)
 * onto that same instance as a distinct bean definition. Both @ConfigurationProperties
 * annotations apply to whatever the method returns, regardless of whether it constructs a new
 * object or (like the second method here) just returns an existing one passed in — the
 * binding is driven by the bean definition, not by what the method body does.
 *
 * @Primary is on the final dataSource bean only, not the intermediate one — dataSourceBase
 * stays reachable by explicit @Qualifier (required for the second method's own wiring) without
 * competing for unqualified DataSource injection, since @Primary already resolves that
 * ambiguity correctly (confirmed safe the same way stampDataSource's @Qualifier usages are —
 * see StampJdbcConfig's own history for why autowireCandidate=false was tried and reverted
 * instead of relying on @Primary alone).
 */
@Configuration
public class PrimaryDataSourceConfig {

    @Bean(name = "dataSourceBase")
    @ConfigurationProperties("spring.datasource.postgres")
    public HikariDataSource dataSourceBase() {
        return DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean(name = "dataSource")
    @Primary
    @ConfigurationProperties("spring.datasource.postgres.hikari")
    public DataSource dataSource(@Qualifier("dataSourceBase") HikariDataSource dataSourceBase) {
        return dataSourceBase;
    }
}
