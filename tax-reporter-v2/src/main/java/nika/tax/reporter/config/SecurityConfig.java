package nika.tax.reporter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider(UserDetailsService userDetailsService,
                                                              PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/access-denied", "/css/**", "/js/**", "/webjars/**").permitAll()
                .requestMatchers("/api/v1/invoices/**").permitAll()
                // Invoices has no customer relation to scope by, so ROLE_CUSTOMER_SCOPED
                // deliberately doesn't get it — its whole purpose is "restricted to certain
                // customers", and there's nothing to restrict here, so it's excluded rather
                // than granted unrestricted access to a screen the role wasn't asked for.
                .requestMatchers("/invoices/**").hasAnyRole("ADMIN", "ANALYST")
                // Customer Deposits: same treatment as Invoices — Admin/Analyst only.
                // ROLE_CUSTOMER_SCOPED doesn't get this even though CustomerDeposit does
                // have a customer relation, since per-customer scoping for this entity
                // wasn't part of what was asked for when this feature was added; extending
                // ROLE_CUSTOMER_SCOPED here would need the same deliberate treatment
                // CardTransaction got (query-level + direct-URL guards), not just a URL rule.
                .requestMatchers("/customer-deposits/**").hasAnyRole("ADMIN", "ANALYST")
                // Reconciliation: now open to ROLE_CUSTOMER_SCOPED too — scoping is enforced at
                // the query/validation level instead (ReconciliationSpecifications restricts the
                // list/detail view to batches composed ENTIRELY of the caller's assigned
                // customers, ReconciliationService.reconcile validates every selected transaction
                // against the same restriction on create, and the reconcileAllMatching path scopes
                // the underlying filter before the query ever runs) — this URL rule is no longer
                // the actual security boundary for customer isolation, just for keeping the whole
                // area away from unauthenticated/unrelated roles. Must come before the broader
                // /transactions/** rule below — Spring evaluates matchers in declaration order and
                // uses the first pattern match, so the more specific rule has to be listed first.
                .requestMatchers("/transactions/reconcile", "/reconciliations/**").hasAnyRole("ADMIN", "ANALYST", "CUSTOMER_SCOPED")
                // Transactions/Exports: Admins and Analysts see everything; ROLE_CUSTOMER_SCOPED
                // is admitted here too, but restricted to their assigned customers inside
                // CardTransactionController/ExportJobController — not at this URL-pattern level,
                // since the restriction is row-level (which customers), not screen-level.
                .requestMatchers("/", "/transactions/**", "/exports/**").hasAnyRole("ADMIN", "ANALYST", "CUSTOMER_SCOPED")
                // Self-service account page: any authenticated user, regardless of role —
                // there's no reason to change-your-own-password behind a role check, and
                // using .authenticated() rather than enumerating roles here means this
                // doesn't need touching again if a new role is ever added later.
                .requestMatchers("/profile/**").authenticated()
                // Everything else (Machines, Customers, Stamp Machines, Users, Reports, Maintenance,
                // Settings, Reconciliation, Dashboard) is Admin-only.
                .anyRequest().hasRole("ADMIN"))
            .exceptionHandling(ex -> ex.accessDeniedPage("/access-denied"))
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/v1/invoices/**"))
            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .defaultSuccessUrl("/transactions", true)
                .failureUrl("/login?error")
                .permitAll())
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout")
                .permitAll());

        return http.build();
    }
}
