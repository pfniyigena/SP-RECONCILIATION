package nika.tax.reporter.config;

import java.sql.Connection;

import javax.sql.DataSource;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health = the PRIMARY Postgres database answers. Oracle and SQL Server are deliberately NOT part of
 * the health status (Boot's generic "db" indicator is switched off in application.properties):
 * they are secondary, optional integrations whose outage the app already tolerates, so they must not
 * turn the whole service DOWN for a load balancer or monitor. Injects the single @Primary DataSource.
 */
@Component("primaryDb")
public class PrimaryDbHealthIndicator implements HealthIndicator {

    private final DataSource primaryDataSource;

    public PrimaryDbHealthIndicator(DataSource primaryDataSource) {
        this.primaryDataSource = primaryDataSource;
    }

    @Override
    public Health health() {
        try (Connection connection = primaryDataSource.getConnection()) {
            return connection.isValid(2) ? Health.up().build() : Health.down().build();
        } catch (Exception e) {
            return Health.down().withDetail("error", e.getClass().getSimpleName()).build();
        }
    }
}
