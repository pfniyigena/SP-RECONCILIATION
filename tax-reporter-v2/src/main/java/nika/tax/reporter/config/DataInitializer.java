package nika.tax.reporter.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import lombok.RequiredArgsConstructor;
import nika.tax.reporter.postgres.domain.AppUser;
import nika.tax.reporter.repository.AppUserRepository;
import nika.tax.reporter.repository.CustomerRepository;

/**
 * Creates a single default admin account the first time the app boots
 * against an empty app_user table, so there is a way to log in out of
 * the box. Change this password immediately in a real environment —
 * see application.yml (app.default-admin.password / DEFAULT_ADMIN_PASSWORD env var).
 */
@Configuration
@RequiredArgsConstructor
public class DataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    @Value("${app.default-admin.username}")
    private String defaultAdminUsername;

    @Value("${app.default-admin.password}")
    private String defaultAdminPassword;

    @Bean
    public CommandLineRunner seedDefaultAdmin(AppUserRepository appUserRepository, PasswordEncoder passwordEncoder) {
        return args -> {
            if (appUserRepository.count() == 0) {
                AppUser admin = AppUser.builder()
                        .username(defaultAdminUsername)
                        .password(passwordEncoder.encode(defaultAdminPassword))
                        .fullName("Administrator")
                        .role("ROLE_ADMIN")
                        .enabled(true)
                        .build();
                appUserRepository.save(admin);
                log.warn("No users found — created default admin account (username: '{}'). "
                        + "Change this password immediately.", defaultAdminUsername);
            }
        };
    }

    /**
     * Marks every customer accessible to at least one ROLE_CUSTOMER_SCOPED user as
     * allocated=true, on every startup, not just the first. Unlike seedDefaultAdmin above,
     * this isn't a one-time bootstrap concern, it's a standing reconciliation: it means a
     * customer granted to a Customer-Scoped user through any path — this app's own Users form,
     * a data import, a future integration — ends up correctly eligible for
     * CustomerDepositMatchingService after the next restart, without needing a manual admin
     * step to flip the flag every time that grant changes.
     *
     * Scoped to ROLE_CUSTOMER_SCOPED specifically, not "every customer" — see
     * CustomerRepository.markCustomerScopedAccessibleAsAllocated for why: it's the only role
     * with an explicit, computable "which customers" list under this app's current RBAC.
     * A customer not granted to any Customer-Scoped user is left alone here; Admin can still
     * flip its allocated flag by hand via the Customer form/list if it should be eligible
     * anyway despite not being scoped to anyone specifically.
     */
    @Bean
    public CommandLineRunner markCustomerScopedAccessibleCustomersAllocated(CustomerRepository customerRepository) {
        return args -> {
            int updated = customerRepository.markCustomerScopedAccessibleAsAllocated();
            if (updated > 0) {
                log.info("Startup: marked {} customer(s) as allocated=true (accessible by at least one ROLE_CUSTOMER_SCOPED user).", updated);
            }
        };
    }
}
