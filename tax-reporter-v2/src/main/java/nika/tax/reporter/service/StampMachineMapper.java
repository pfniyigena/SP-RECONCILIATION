package nika.tax.reporter.service;

import nika.tax.reporter.dto.StampMachineForm;
import nika.tax.reporter.postgres.domain.StampMachine;

public final class StampMachineMapper {

    private StampMachineMapper() {
    }

    public static StampMachineForm toForm(StampMachine entity) {
        return StampMachineForm.builder()
                .id(entity.getId())
                .serialNumber(entity.getSerialNumber())
                .model(entity.getModel())
                .sdcId(entity.getSdcId())
                .enabled(entity.getEnabled())
                .build();
    }

    public static void applyToEntity(StampMachineForm form, StampMachine entity) {
        entity.setSerialNumber(blankToNull(form.getSerialNumber()));
        entity.setModel(blankToNull(form.getModel()));
        entity.setSdcId(blankToNull(form.getSdcId()));
        entity.setEnabled(form.getEnabled() != null ? form.getEnabled() : Boolean.TRUE);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
