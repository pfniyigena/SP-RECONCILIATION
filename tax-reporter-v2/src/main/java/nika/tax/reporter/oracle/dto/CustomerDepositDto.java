package nika.tax.reporter.oracle.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import nika.tax.reporter.postgres.domain.Customer;
import nika.tax.reporter.postgres.domain.CustomerDeposit;

/** Same import fix as CardTransactionDto — nika.tax.reporter.postgres.domain.v2.Customer
 * corrected to nika.tax.reporter.postgres.domain.Customer (no .v2), matching this project's
 * actual package. Every builder() call below matches this project's actual entity fields
 * exactly as uploaded. */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class CustomerDepositDto {
	private String dateTimeTransaction;
	private String clientName;
	private String serviceName;
	private BigDecimal totalAmount;
	private int serviceId;
	private int clientId;
	private String transactionGuid;
	private String sapReference;
	public CustomerDeposit toCustomerDepositEntity() {
		String raw = this.dateTimeTransaction; // "01/07/2026 14:35:22"
		LocalDateTime rawDateTimeTrn = LocalDateTime.parse(raw, DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"));
		return CustomerDeposit.builder().dateTimeTransaction(rawDateTimeTrn).clientName(this.clientName)
				.serviceName(this.serviceName).totalAmount(this.totalAmount).currentBalance(this.totalAmount)
				.serviceId(this.serviceId)
				.clientId(this.clientId).transactionGuid(this.transactionGuid).sapReference(this.sapReference).build();
	}
	
	public Customer toCustomerEntity() {
		return Customer.builder().clientId(this.clientId).clientName(this.clientName).build();
	}
}
