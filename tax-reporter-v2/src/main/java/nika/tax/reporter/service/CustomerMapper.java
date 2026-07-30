package nika.tax.reporter.service;

import nika.tax.reporter.dto.CustomerForm;
import nika.tax.reporter.postgres.domain.Customer;

public final class CustomerMapper {

    private CustomerMapper() {
    }

    public static CustomerForm toForm(Customer entity) {
        return CustomerForm.builder()
                .id(entity.getId())
                .clientName(entity.getClientName())
                .clientTin(entity.getClientTin())
                .clientId(entity.getClientId())
                .enabled(entity.getEnabled())
                .allocated(entity.getAllocated())
                .build();
    }

    public static void applyToEntity(CustomerForm form, Customer entity) {
        entity.setClientName(blankToNull(form.getClientName()));
        entity.setClientTin(blankToNull(form.getClientTin()));
        entity.setClientId(form.getClientId());
        entity.setEnabled(form.getEnabled() != null ? form.getEnabled() : Boolean.TRUE);
        // Explicit null-coalesce, not relying on the entity's @Builder.Default — that only
        // applies when constructed via .builder().build(), not via `new Customer()` as
        // CustomerService does; see the identical note on CustomerDepositService.create()
        // for the bug this exact omission caused there.
        entity.setAllocated(form.getAllocated() != null ? form.getAllocated() : Boolean.FALSE);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
