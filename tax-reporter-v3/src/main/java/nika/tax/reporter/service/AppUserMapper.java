package nika.tax.reporter.service;

import java.util.List;
import java.util.stream.Collectors;

import nika.tax.reporter.dto.AppUserForm;
import nika.tax.reporter.postgres.domain.AppUser;
import nika.tax.reporter.postgres.domain.Customer;

public final class AppUserMapper {

    private AppUserMapper() {
    }

    /** Password is intentionally left blank on the form — never send a hash (or anything) back to the browser. */
    public static AppUserForm toForm(AppUser entity) {
        return AppUserForm.builder()
                .id(entity.getId())
                .username(entity.getUsername())
                .fullName(entity.getFullName())
                .role(entity.getRole())
                .enabled(entity.getEnabled())
                .accessibleCustomerIds(entity.getAccessibleCustomers() == null
                        ? List.of()
                        : entity.getAccessibleCustomers().stream().map(Customer::getId).collect(Collectors.toList()))
                .build();
    }

    /**
     * Does not touch password (AppUserService handles hashing/leaving it unchanged)
     * or accessibleCustomers (needs repository lookups to resolve IDs to entities —
     * done in AppUserService, which has repository access; this mapper doesn't).
     */
    public static void applyToEntity(AppUserForm form, AppUser entity) {
        entity.setUsername(form.getUsername() != null ? form.getUsername().trim() : null);
        entity.setFullName(blankToNull(form.getFullName()));
        entity.setRole(form.getRole());
        entity.setEnabled(form.getEnabled() != null ? form.getEnabled() : Boolean.TRUE);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}

