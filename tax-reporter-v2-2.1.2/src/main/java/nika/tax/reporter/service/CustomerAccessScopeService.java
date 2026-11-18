package nika.tax.reporter.service;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import nika.tax.reporter.postgres.domain.AppUser;
import nika.tax.reporter.repository.AppUserRepository;

/**
 * Resolves which customers the currently authenticated user is allowed to see.
 *
 * Only ROLE_CUSTOMER_SCOPED is restricted — every other role (Admin, Analyst,
 * anything else) is unrestricted regardless of what's in accessibleCustomers,
 * so this feature is purely additive and can't accidentally narrow access for
 * existing users on existing roles.
 */
@Service
@RequiredArgsConstructor
public class CustomerAccessScopeService {

    public static final String ROLE_CUSTOMER_SCOPED = "ROLE_CUSTOMER_SCOPED";

    private final AppUserRepository appUserRepository;

    /**
     * @return the set of customer IDs this user is restricted to, or empty if
     *         the user is NOT scoped (i.e. has unrestricted access). Callers
     *         must check {@link #isScoped(Authentication)} first — an empty
     *         set from this method means "not scoped" (see everything), not
     *         "scoped to zero customers".
     *
     * @Transactional: accessibleCustomers is a LAZY collection — without an
     * open transaction spanning both the repository lookup and the collection
     * access below, this throws LazyInitializationException, since the entity
     * would otherwise already be detached by the time .getAccessibleCustomers()
     * runs.
     */
    @Transactional(readOnly = true)
    public Set<UUID> accessibleCustomerIds(Authentication authentication) {
        if (!isScoped(authentication)) {
            return Set.of();
        }
        return appUserRepository.findByUsername(authentication.getName())
                .map(AppUser::getAccessibleCustomers)
                .orElse(Set.of())
                .stream()
                .map(c -> c.getId())
                .collect(Collectors.toSet());
    }

    public boolean isScoped(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> ROLE_CUSTOMER_SCOPED.equals(a.getAuthority()));
    }
}

