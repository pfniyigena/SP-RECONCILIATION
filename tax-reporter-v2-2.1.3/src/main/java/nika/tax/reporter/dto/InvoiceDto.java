package nika.tax.reporter.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Incoming payload for the invoice-ingestion API (POST /api/v1/invoices — see
 * InvoiceApiV1Controller). snake_case @JsonProperty names because this is what an external
 * system (EBM/fiscal device integration, going by the field names) actually posts; the rest of
 * this app uses camelCase Java conventions throughout, this DTO is the one deliberate exception
 * because it has to match what the external caller sends, not what's convenient internally.
 */
@Data
@ToString
@AllArgsConstructor
@NoArgsConstructor
public class InvoiceDto {
	@JsonProperty("registered_name")
	private String registeredName;
	@JsonProperty("registered_tin")
	private String registeredTin;
	@JsonProperty("sdc_id")
	private String sdcId;
	@JsonProperty("client_tin")
	private String clientTin;
	@JsonProperty("client_name")
	private String clientName;
	@JsonProperty("client_phone")
	private String clientPhone;
	@JsonProperty("receipt_number")
	private Long receiptNumber;
	@JsonProperty("stamp_date")
	private LocalDateTime stampDate;
	@JsonProperty("stamp_data")
	private String stampData;
	@JsonProperty("total_tax_amount")
	private BigDecimal totalTaxAmount;
	@JsonProperty("paid_amount")
	private BigDecimal paidAmount;
	@JsonProperty("total_amount")
	private BigDecimal totalAmount;
	@JsonProperty("transaction_type")
	private String transactionType;
	@JsonProperty("payment_mode")
	private String paymentMode;
	@JsonProperty("plate_number")
	private String plateNumber;
    @JsonProperty("erp_code" )
    private String erpCode;
}
