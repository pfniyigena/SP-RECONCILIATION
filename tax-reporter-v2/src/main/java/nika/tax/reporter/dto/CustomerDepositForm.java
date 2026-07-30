package nika.tax.reporter.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerDepositForm {

    private UUID id;

    @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
    private LocalDateTime dateTimeTransaction;
    private String clientName;
    private String serviceName;
    private BigDecimal totalAmount;
    private Integer serviceId;
    private Integer clientId;
    private String transactionGuid;
    private String stampData;
    private String sapReference;

    @Builder.Default
    private Boolean processed = Boolean.FALSE;
}
