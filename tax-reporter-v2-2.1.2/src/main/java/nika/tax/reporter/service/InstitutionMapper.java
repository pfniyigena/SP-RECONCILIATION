package nika.tax.reporter.service;

import nika.tax.reporter.dto.InstitutionForm;
import nika.tax.reporter.postgres.domain.Institution;

public final class InstitutionMapper {

    private InstitutionMapper() {
    }

    public static InstitutionForm toForm(Institution entity) {
        return InstitutionForm.builder()
                .id(entity.getId())
                .name(entity.getName())
                .tinNumber(entity.getTinNumber())
                .enabled(entity.getEnabled())
                .build();
    }

    public static void applyToEntity(InstitutionForm form, Institution entity) {
        entity.setName(blankToNull(form.getName()));
        entity.setTinNumber(blankToNull(form.getTinNumber()));
        entity.setEnabled(form.getEnabled() != null ? form.getEnabled() : Boolean.TRUE);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
