package nika.tax.reporter.service;

import nika.tax.reporter.dto.TerminalMachineForm;
import nika.tax.reporter.postgres.domain.StampMachine;
import nika.tax.reporter.postgres.domain.TerminalMachine;

public final class TerminalMachineMapper {

    private TerminalMachineMapper() {
    }

    public static TerminalMachineForm toForm(TerminalMachine entity) {
        return TerminalMachineForm.builder()
                .id(entity.getId())
                .posName(entity.getPosName())
                .posId(entity.getPosId())
                .posNumber(entity.getPosNumber())
                .addressName(entity.getAddressName())
                .addressDescription(entity.getAddressDescription())
                .sdcId(entity.getSdcId())
                .location(entity.getLocation())
                .enabled(entity.getEnabled())
                .stampMachineId(entity.getStampMachine() != null ? entity.getStampMachine().getId() : null)
                .build();
    }

    public static void applyToEntity(TerminalMachineForm form, TerminalMachine entity, StampMachine stampMachine) {
        entity.setPosName(blankToNull(form.getPosName()));
        entity.setPosId(form.getPosId());
        entity.setPosNumber(form.getPosNumber());
        entity.setAddressName(blankToNull(form.getAddressName()));
        entity.setAddressDescription(blankToNull(form.getAddressDescription()));
        entity.setSdcId(blankToNull(form.getSdcId()));
        entity.setLocation(blankToNull(form.getLocation()));
        entity.setEnabled(form.getEnabled() != null ? form.getEnabled() : Boolean.TRUE);
        entity.setStampMachine(stampMachine);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
