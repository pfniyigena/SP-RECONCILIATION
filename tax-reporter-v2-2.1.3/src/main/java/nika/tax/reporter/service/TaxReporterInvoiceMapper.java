package nika.tax.reporter.service;

import nika.tax.reporter.dto.TaxReporterInvoiceForm;
import nika.tax.reporter.postgres.domain.TaxReporterInvoice;

public final class TaxReporterInvoiceMapper {

    private TaxReporterInvoiceMapper() {
    }

    public static TaxReporterInvoiceForm toForm(TaxReporterInvoice entity) {
        return TaxReporterInvoiceForm.builder()
                .id(entity.getId())
                .registeredName(entity.getRegisteredName())
                .registeredTin(entity.getRegisteredTin())
                .sdcId(entity.getSdcId())
                .clientTin(entity.getClientTin())
                .clientName(entity.getClientName())
                .clientPhone(entity.getClientPhone())
                .receiptNumber(entity.getReceiptNumber())
                .stampDate(entity.getStampDate())
                .stampData(entity.getStampData())
                .totalTaxAmount(entity.getTotalTaxAmount())
                .totalAmount(entity.getTotalAmount())
                .paidAmount(entity.getPaidAmount())
                .transactionType(entity.getTransactionType())
                .paymentMode(entity.getPaymentMode())
                .plateNumber(entity.getPlateNumber())
                .erpCode(entity.getErpCode())
                .processed(entity.getProcessed())
                .failureReason(entity.getFailureReason())
                .build();
    }

    public static void applyToEntity(TaxReporterInvoiceForm form, TaxReporterInvoice entity) {
        entity.setRegisteredName(blankToNull(form.getRegisteredName()));
        entity.setRegisteredTin(blankToNull(form.getRegisteredTin()));
        entity.setSdcId(blankToNull(form.getSdcId()));
        entity.setClientTin(blankToNull(form.getClientTin()));
        entity.setClientName(blankToNull(form.getClientName()));
        entity.setClientPhone(blankToNull(form.getClientPhone()));
        entity.setReceiptNumber(form.getReceiptNumber());
        entity.setStampDate(form.getStampDate());
        entity.setStampData(blankToNull(form.getStampData()));
        entity.setTotalTaxAmount(form.getTotalTaxAmount());
        entity.setTotalAmount(form.getTotalAmount());
        entity.setPaidAmount(form.getPaidAmount());
        entity.setTransactionType(blankToNull(form.getTransactionType()));
        entity.setPaymentMode(blankToNull(form.getPaymentMode()));
        entity.setPlateNumber(blankToNull(form.getPlateNumber()));
        entity.setErpCode(blankToNull(form.getErpCode()));
        entity.setProcessed(form.getProcessed() != null ? form.getProcessed() : Boolean.FALSE);
        entity.setFailureReason(blankToNull(form.getFailureReason()));
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
