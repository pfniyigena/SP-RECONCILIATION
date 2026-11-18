package nika.tax.reporter.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaxReporterInvoiceForm {

    private UUID id;

    private String registeredName;
    private String registeredTin;
    private String sdcId;
    private String clientTin;
    private String clientName;
    private String clientPhone;
    private Long receiptNumber;

    @NotNull(message = "Stamp date is required")
    @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
    private LocalDateTime stampDate;

    private String stampData;

    private BigDecimal totalTaxAmount;

    @NotNull(message = "Total amount is required")
    private BigDecimal totalAmount;

    private BigDecimal paidAmount;
    private String transactionType;
    private String paymentMode;
    private String plateNumber;
    private String erpCode;

    @Builder.Default
    private Boolean processed = Boolean.FALSE;

    private String failureReason;
}
