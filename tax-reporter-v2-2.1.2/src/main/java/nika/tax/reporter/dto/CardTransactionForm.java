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

/**
 * Form-backing object for the create/edit transaction screens. Kept separate
 * from the CardTransaction entity so the web layer can use HTML-form-friendly
 * types (e.g. datetime-local strings) and validation annotations without
 * touching the JPA entity itself.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CardTransactionForm {

    private UUID id;

    @NotNull(message = "Date/time is required")
    @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
    private LocalDateTime dateTimeTransaction;

    private String clientName;
    private String cardNumber;
    private String plateNumber;
    private String posName;
    private Integer posNumber;
    private String addressName;
    private String addressDescription;
    private String serviceName;

    private BigDecimal quantity;
    private BigDecimal unitPrice;

    @NotNull(message = "Total amount is required")
    private BigDecimal totalAmount;

    private String tagNumber;
    private Integer posId;
    private Integer serviceId;
    private Integer clientId;
    private Integer addressId;

    private String sdcId;
    private String stampData;

    private String transactionGuid;

    @Builder.Default
    private Boolean processed = Boolean.FALSE;

    @Builder.Default
    private Boolean success = Boolean.FALSE;
}
