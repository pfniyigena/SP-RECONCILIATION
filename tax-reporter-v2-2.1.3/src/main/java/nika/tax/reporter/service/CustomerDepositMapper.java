package nika.tax.reporter.service;

import nika.tax.reporter.dto.CustomerDepositForm;
import nika.tax.reporter.postgres.domain.CustomerDeposit;

public final class CustomerDepositMapper {

    private CustomerDepositMapper() {
    }

    public static CustomerDepositForm toForm(CustomerDeposit entity) {
        return CustomerDepositForm.builder()
                .id(entity.getId())
                .dateTimeTransaction(entity.getDateTimeTransaction())
                .clientName(entity.getClientName())
                .serviceName(entity.getServiceName())
                .totalAmount(entity.getTotalAmount())
                .serviceId(entity.getServiceId())
                .clientId(entity.getClientId())
                .transactionGuid(entity.getTransactionGuid())
                .stampData(entity.getStampData())
                .sapReference(entity.getSapReference())
                .processed(entity.getProcessed())
                .build();
    }

    /** Does not touch customer — same as CardTransactionMapper, that relation isn't exposed on this form. */
    public static void applyToEntity(CustomerDepositForm form, CustomerDeposit entity) {
        entity.setDateTimeTransaction(form.getDateTimeTransaction());
        entity.setClientName(blankToNull(form.getClientName()));
        entity.setServiceName(blankToNull(form.getServiceName()));
        entity.setTotalAmount(form.getTotalAmount());
        entity.setServiceId(form.getServiceId());
        entity.setClientId(form.getClientId());
        entity.setTransactionGuid(blankToNull(form.getTransactionGuid()));
        entity.setStampData(blankToNull(form.getStampData()));
        entity.setSapReference(blankToNull(form.getSapReference()));
        entity.setProcessed(form.getProcessed() != null ? form.getProcessed() : Boolean.FALSE);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
