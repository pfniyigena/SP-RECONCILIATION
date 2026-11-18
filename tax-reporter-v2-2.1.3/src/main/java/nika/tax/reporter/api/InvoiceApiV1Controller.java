package nika.tax.reporter.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.extern.slf4j.Slf4j;
import nika.tax.reporter.dto.InvoiceDto;
import nika.tax.reporter.dto.InvoiceResponseDto;
import nika.tax.reporter.postgres.domain.TaxReporterInvoice;
import nika.tax.reporter.service.TaxReporterInvoiceService;

/**
 * Renamed and relocated from web.InvoiceIngestController — same endpoint
 * (POST /api/v1/invoices, still permitAll + CSRF-exempt in SecurityConfig, which matches on the
 * URL path rather than the controller's package or class name, so nothing there needed to change).
 *
 * Response contract changed from the previous version: always HTTP 200, with SUCCESS/FAILED
 * indicated in the response body's "status" field rather than the HTTP status code
 * (previously 201 on success, 422 on failure). Worth knowing if anything else is meant to call
 * this — a caller checking only the HTTP status code would see 200 either way now and would
 * need to inspect the body to tell success from failure.
 */
@RestController
@RequestMapping("/api/v1/invoices") // Version 1
@Slf4j
public class InvoiceApiV1Controller {
	private final TaxReporterInvoiceService invoiceService;

	public InvoiceApiV1Controller(TaxReporterInvoiceService invoiceService) {
		this.invoiceService = invoiceService;
	}

	@PostMapping
	public ResponseEntity<InvoiceResponseDto> createInvoice(@RequestBody InvoiceDto invoiceDto) {
		log.info("Saving----{}", invoiceDto);
		TaxReporterInvoice invoice = invoiceService.createInvoice(invoiceDto);
		if (invoice != null) {
			return ResponseEntity.ok(InvoiceResponseDto.builder().status("SUCCESS").build());
		} else {
			return ResponseEntity.ok(InvoiceResponseDto.builder().status("FAILED").build());
		}
	}
}
