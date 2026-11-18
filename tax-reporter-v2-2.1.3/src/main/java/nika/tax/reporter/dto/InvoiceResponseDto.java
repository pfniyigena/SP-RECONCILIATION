package nika.tax.reporter.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@ToString
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class InvoiceResponseDto {
    @JsonProperty("status")
    private String status;
    @JsonProperty("message")
    private String message;
}
