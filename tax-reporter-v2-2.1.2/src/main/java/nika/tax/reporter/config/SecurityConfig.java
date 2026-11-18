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
                // Health check for monitoring: status only, no details (see application.properties).
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                // Add/edit/update actions across every entity: ADMIN only. Must come before the
                // broader per-entity rules below — Spring evaluates matchers in declaration
                // order and uses the first match, so these more-specific write-action rules
                // have to be listed first or the broader view-level rules would win instead.
                // Scoped to the actual write endpoints (edit forms, "Mark as Processed",
                // "fetch stamp data", the global deposit-matching run) — NOT reconciliation or
                // the customer-scoped bulk "reconcile all matching" path, which got its own
                // deliberate ROLE_CUSTOMER_SCOPED treatment earlier and isn't being undone here;
                // reconciling isn't a per-record edit the way these are.
                .requestMatchers("/transactions/*/edit", "/transactions/*/process").hasRole("ADMIN")
                .requestMatchers("/invoices/*/edit", "/invoices/*/process").hasRole("ADMIN")
                .requestMatchers("/customer-deposits/*/edit", "/customer-deposits/*/fetch-stamp-data", "/customer-deposits/match").hasRole("ADMIN")
                // Invoices - open to ROLE_CUSTOMER_SCOPED for viewing. Invoices have no customer link
                // of their own, so a scoped user is restricted in the query itself to invoices whose
                // stampData was matched onto one of their customers' card transactions (see
                // TaxReporterInvoiceSpecifications and InvoiceController.assertAccessible).
                .requestMatchers("/invoices/**").hasAnyRole("ADMIN", "ANALYST", "CUSTOMER_SCOPED")
                // Customer Deposits — now open to ROLE_CUSTOMER_SCOPED too, WITH real
                // query-level scoping this time (CustomerDepositSpecifications restricts the
                // list/detail view to the caller's assigned customers, same restrictToCustomerIds
                // pattern as CardTransaction, plus a direct-URL guard on the detail view) —
                // this entity does have a customer relation, so unlike Invoices above, proper
                // scoping was actually possible here.
                .requestMatchers("/customer-deposits/**").hasAnyRole("ADMIN", "ANALYST", "CUSTOMER_SCOPED")
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
