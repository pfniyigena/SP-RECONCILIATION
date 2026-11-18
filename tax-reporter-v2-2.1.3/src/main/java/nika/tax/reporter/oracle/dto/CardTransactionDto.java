package nika.tax.reporter.oracle.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import nika.tax.reporter.postgres.domain.CardTransaction;
import nika.tax.reporter.postgres.domain.Customer;
import nika.tax.reporter.postgres.domain.CustomerDeposit;
import nika.tax.reporter.postgres.domain.TerminalMachine;

/**
 * Import fixed: the uploaded version imported nika.tax.reporter.postgres.domain.v2.Customer —
 * same issue already documented once before in this project's history for a different file.
 * This project's Customer lives at nika.tax.reporter.postgres.domain.Customer, no .v2. If your
 * real codebase does have a .v2 Customer, that's the one line to change back.
 *
 * Every other builder() call below (CardTransaction, CustomerDeposit, TerminalMachine) matches
 * this project's actual entity fields exactly as uploaded — no other changes were needed.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class CardTransactionDto {
	private String dateTimeTransaction;
	private String clientName;
	private String cardNumber;
	private String plateNumber;
	private String posName;
	private int posNumber;
	private String addressName;
	private String addressDescription;
	private String serviceName;
	private BigDecimal quantity;
	private BigDecimal unitPrice;
	private BigDecimal totalAmount;
	private String tagNumber;
	private int posId;
	private int serviceId;
	private int clientId;
	private int addressId;
	private String transactionGuid;
	private String sapReference;

	public CardTransaction toCardTransactionEntity() {
		String raw = this.dateTimeTransaction; // "01/07/2026 14:35:22"
		LocalDateTime rawDateTimeTrn = LocalDateTime.parse(raw, DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"));
		return CardTransaction.builder().dateTimeTransaction(rawDateTimeTrn).clientName(this.clientName)
				.cardNumber(this.cardNumber).plateNumber(this.plateNumber).posName(this.posName)
				.posNumber(this.posNumber).addressName(this.addressName).addressDescription(this.addressDescription)
				.serviceName(this.serviceName).quantity(this.quantity).unitPrice(this.unitPrice)
				.totalAmount(this.totalAmount).tagNumber(this.tagNumber).posId(this.posId).serviceId(this.serviceId)
				.clientId(this.clientId).addressId(this.addressId).transactionGuid(this.transactionGuid).build();
	}

	public CustomerDeposit toCustomerDepositEntity() {
		String raw = this.dateTimeTransaction; // "01/07/2026 14:35:22"
		LocalDateTime rawDateTimeTrn = LocalDateTime.parse(raw, DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"));
		return CustomerDeposit.builder().dateTimeTransaction(rawDateTimeTrn).clientName(this.clientName)
				.serviceName(this.serviceName).totalAmount(this.totalAmount).serviceId(this.serviceId)
				.clientId(this.clientId).transactionGuid(this.transactionGuid).sapReference(this.sapReference).build();
	}

	public TerminalMachine toTerminalMachineEntity() {
		return TerminalMachine.builder().posId(this.posId).posName(this.posName).posNumber(this.posNumber)
				.addressName(this.addressName).addressDescription(this.addressDescription).build();
	}

	public Customer toCustomerEntity() {
		return Customer.builder().clientId(this.clientId).clientName(this.clientName).build();
	}
}
