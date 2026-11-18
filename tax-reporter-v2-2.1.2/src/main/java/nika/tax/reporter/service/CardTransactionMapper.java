package nika.tax.reporter.service;

import nika.tax.reporter.dto.CardTransactionForm;
import nika.tax.reporter.postgres.domain.CardTransaction;

public final class CardTransactionMapper {

    private CardTransactionMapper() {
    }

    public static CardTransactionForm toForm(CardTransaction entity) {
        return CardTransactionForm.builder()
                .id(entity.getId())
                .dateTimeTransaction(entity.getDateTimeTransaction())
                .clientName(entity.getClientName())
                .cardNumber(entity.getCardNumber())
                .plateNumber(entity.getPlateNumber())
                .posName(entity.getPosName())
                .posNumber(entity.getPosNumber())
                .addressName(entity.getAddressName())
                .addressDescription(entity.getAddressDescription())
                .serviceName(entity.getServiceName())
                .quantity(entity.getQuantity())
                .unitPrice(entity.getUnitPrice())
                .totalAmount(entity.getTotalAmount())
                .tagNumber(entity.getTagNumber())
                .posId(entity.getPosId())
                .serviceId(entity.getServiceId())
                .clientId(entity.getClientId())
                .addressId(entity.getAddressId())
                .sdcId(entity.getSdcId())
                .stampData(entity.getStampData())
                .transactionGuid(entity.getTransactionGuid())
                .processed(entity.getProcessed())
                .success(entity.getSuccess())
                .build();
    }

    /** Applies form values onto an existing (or new) entity instance in place. */
    public static void applyToEntity(CardTransactionForm form, CardTransaction entity) {
        entity.setDateTimeTransaction(form.getDateTimeTransaction());
        entity.setClientName(blankToNull(form.getClientName()));
        entity.setCardNumber(blankToNull(form.getCardNumber()));
        entity.setPlateNumber(blankToNull(form.getPlateNumber()));
        entity.setPosName(blankToNull(form.getPosName()));
        entity.setPosNumber(form.getPosNumber());
        entity.setAddressName(blankToNull(form.getAddressName()));
        entity.setAddressDescription(blankToNull(form.getAddressDescription()));
        entity.setServiceName(blankToNull(form.getServiceName()));
        entity.setQuantity(form.getQuantity());
        entity.setUnitPrice(form.getUnitPrice());
        entity.setTotalAmount(form.getTotalAmount());
        entity.setTagNumber(blankToNull(form.getTagNumber()));
        entity.setPosId(form.getPosId());
        entity.setServiceId(form.getServiceId());
        entity.setClientId(form.getClientId());
        entity.setAddressId(form.getAddressId());
        entity.setSdcId(blankToNull(form.getSdcId()));
        entity.setStampData(blankToNull(form.getStampData()));
        entity.setTransactionGuid(blankToNull(form.getTransactionGuid()));
        entity.setProcessed(form.getProcessed() != null ? form.getProcessed() : Boolean.FALSE);
        entity.setSuccess(form.getSuccess() != null ? form.getSuccess() : Boolean.FALSE);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
