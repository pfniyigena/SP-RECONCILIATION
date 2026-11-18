package nika.tax.reporter.config;

import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Supplies the "who" for AbstractEntity's @CreatedBy/@LastModifiedBy fields — without this
 * bean, @EnableJpaAuditing (on TaxReporterApplication) has no source for the current user, and
 * createdBy/updatedBy would silently stay at their "" default forever, no error anywhere to
 * explain why. Wasn't needed before this change since nothing used those annotations.
 *
 * Falls back to "system" for anything that saves outside an authenticated web request — the
 * startup CommandLineRunners in DataInitializer, CustomerDepositMatchingService's scheduled/
 * background allocation runs, and similar — rather than leaving createdBy/updatedBy blank or
 * throwing, which would break those save paths for a reason unrelated to what they're actually
 * doing.
 */
@Configuration
public class AuditorAwareConfig {

    @Bean
    public AuditorAware<String> auditorAware() {
        return () -> {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !authentication.isAuthenticated()
                    || "anonymousUser".equals(authentication.getPrincipal())) {
                return Optional.of("system");
            }
            return Optional.ofNullable(authentication.getName()).or(() -> Optional.of("system"));
        };
    }
}
