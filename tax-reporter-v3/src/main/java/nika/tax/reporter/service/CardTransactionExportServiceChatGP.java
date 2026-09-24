package nika.tax.reporter.service;

import java.awt.Color;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import lombok.RequiredArgsConstructor;
import nika.tax.reporter.postgres.domain.CardTransaction;
import nika.tax.reporter.postgres.domain.DepositCardTransaction;
import nika.tax.reporter.repository.CardTransactionRepository;
import nika.tax.reporter.repository.DepositCardTransactionRepository;

@Service
@RequiredArgsConstructor
public class CardTransactionExportServiceChatGP {

	/**
	 * Excel is streamed page-by-page so exporting the full filtered set doesn't
	 * blow up memory.
	 */
	private static final int EXCEL_BATCH_SIZE = 1000;

	/**
	 * PDF is meant to be read/printed, not a database dump. This limits the number
	 * of CardTransactions loaded for a PDF.
	 *
	 * Note: one CardTransaction can produce multiple PDF rows when it has multiple
	 * deposit allocations.
	 */
	private static final int PDF_MAX_ROWS = 5000;

	private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm");

	private static final String[] HEADERS = { "Date / Time", "Client", "Client ID", "Card Number", "Plate Number",
			"POS Name", "POS Number", "Service", "SDC ID", "Stamp Number", "EBM Number", "Quantity", "Unit Price",
			"Total Amount", "Status", "Transaction GUID" };

	private final CardTransactionRepository repository;
	private final DepositCardTransactionRepository depositCardTransactionRepository;

	// ========================================================================
	// ALLOCATIONS / EBM NUMBERS
	// ========================================================================

	/**
	 * Loads all DepositCardTransaction records for the supplied transactions in one
	 * query/batch.
	 *
	 * A CardTransaction may have:
	 *
	 * Transaction A -> Allocation 1 -> EBM 1001 -> Allocation 2 -> EBM 1002 ->
	 * Allocation 3 -> EBM 1003
	 *
	 * The returned map therefore contains a LIST of EBM numbers per CardTransaction
	 * instead of only one EBM number.
	 *
	 * If a transaction has no allocations, it is not put into the map. The export
	 * methods handle that case by creating one row with an empty EBM number.
	 */
	private Map<UUID, List<String>> buildEbmNumberMap(List<CardTransaction> transactions) {

		List<UUID> ids = transactions.stream().map(CardTransaction::getId).filter(id -> id != null)
				.collect(Collectors.toList());

		if (ids.isEmpty()) {
			return Map.of();
		}

		List<DepositCardTransaction> allocations = depositCardTransactionRepository.findByTransaction_IdIn(ids);

		Map<UUID, List<String>> ebmNumbers = new HashMap<>();

		for (DepositCardTransaction allocation : allocations) {

			if (allocation == null || allocation.getTransaction() == null
					|| allocation.getTransaction().getId() == null) {
				continue;
			}

			UUID transactionId = allocation.getTransaction().getId();

			String ebmNumber = null;

			if (allocation.getDeposit() != null) {
				ebmNumber = allocation.getDeposit().getStampNumber();
			}

			/*
			 * Only add an EBM number when one actually exists.
			 */
			if (ebmNumber != null && !ebmNumber.isBlank()) {
				ebmNumbers.computeIfAbsent(transactionId, key -> new ArrayList<>()).add(ebmNumber);
			}
		}

		return ebmNumbers;
	}

	/**
	 * Returns the EBM numbers for a transaction.
	 *
	 * If there are no allocations, return a single null value.
	 *
	 * This is important because we still want a CardTransaction without allocations
	 * to appear in the export.
	 */
	private List<String> getEbmNumbers(CardTransaction transaction, Map<UUID, List<String>> ebmNumbers) {

		if (transaction == null || transaction.getId() == null) {
			return List.of((String) null);
		}

		List<String> numbers = ebmNumbers.get(transaction.getId());

		if (numbers == null || numbers.isEmpty()) {
			return List.of((String) null);
		}

		return numbers;
	}

	// ========================================================================
	// COUNT
	// ========================================================================

	public long countMatching(CardTransactionFilter filter) {
		return repository.count(CardTransactionSpecifications.build(filter));
	}

	// ========================================================================
	// EXCEL EXPORT
	// ========================================================================

	public void writeExcel(CardTransactionFilter filter, Sort sort, OutputStream out) throws IOException {

		Specification<CardTransaction> spec = CardTransactionSpecifications.build(filter);

		try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {

			Sheet sheet = workbook.createSheet("Transactions");

			// ------------------------------------------------------------
			// Header style
			// ------------------------------------------------------------

			Font headerFont = workbook.createFont();
			headerFont.setBold(true);
			headerFont.setColor(IndexedColors.WHITE.getIndex());

			CellStyle headerStyle = workbook.createCellStyle();
			headerStyle.setFillForegroundColor(IndexedColors.GREY_80_PERCENT.getIndex());
			headerStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
			headerStyle.setFont(headerFont);

			// ------------------------------------------------------------
			// Amount style
			// ------------------------------------------------------------

			CellStyle amountStyle = workbook.createCellStyle();
			amountStyle.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));

			// ------------------------------------------------------------
			// Date style
			// ------------------------------------------------------------

			CellStyle dateStyle = workbook.createCellStyle();
			dateStyle.setDataFormat(workbook.createDataFormat().getFormat("dd mmm yyyy hh:mm"));

			// ------------------------------------------------------------
			// Header row
			// ------------------------------------------------------------

			Row headerRow = sheet.createRow(0);

			for (int i = 0; i < HEADERS.length; i++) {

				Cell cell = headerRow.createCell(i);
				cell.setCellValue(HEADERS[i]);
				cell.setCellStyle(headerStyle);
			}

			// ------------------------------------------------------------
			// Data
			// ------------------------------------------------------------

			int rowNum = 1;
			int pageIndex = 0;

			Page<CardTransaction> page;

			do {

				page = repository.findAll(spec, PageRequest.of(pageIndex, EXCEL_BATCH_SIZE, sort));

				List<CardTransaction> transactions = page.getContent();

				/*
				 * Load ALL allocations for this page in one query.
				 */
				Map<UUID, List<String>> ebmNumbers = buildEbmNumberMap(transactions);

				/*
				 * One CardTransaction can now produce multiple rows.
				 */
				for (CardTransaction tx : transactions) {

					List<String> transactionEbmNumbers = getEbmNumbers(tx, ebmNumbers);

					for (String ebmNumber : transactionEbmNumbers) {

						Row row = sheet.createRow(rowNum++);

						writeRow(row, tx, amountStyle, dateStyle, ebmNumber);
					}
				}

				pageIndex++;

			} while (page.hasNext());

			// ------------------------------------------------------------
			// Column widths
			// ------------------------------------------------------------

			for (int i = 0; i < HEADERS.length; i++) {
				sheet.setColumnWidth(i, 22 * 256);
			}

			// ------------------------------------------------------------
			// Write workbook
			// ------------------------------------------------------------

			workbook.write(out);

			/*
			 * Clean up SXSSF temporary files.
			 */
			workbook.dispose();
		}
	}

	/**
	 * Writes one Excel row.
	 *
	 * The same CardTransaction information is repeated when the transaction has
	 * multiple allocations, but the EBM Number changes for each row.
	 */
	private void writeRow(Row row, CardTransaction tx, CellStyle amountStyle, CellStyle dateStyle, String ebmNumber) {

		int col = 0;

		// ------------------------------------------------------------
		// Date / Time
		// ------------------------------------------------------------

		if (tx.getDateTimeTransaction() != null) {

			Cell c = row.createCell(col);

			c.setCellValue(tx.getDateTimeTransaction());

			c.setCellStyle(dateStyle);
		}

		col++;

		// ------------------------------------------------------------
		// Transaction information
		// ------------------------------------------------------------

		setString(row, col++, tx.getClientName());

		setInt(row, col++, tx.getClientId());

		setString(row, col++, tx.getCardNumber());

		setString(row, col++, tx.getPlateNumber());

		setString(row, col++, tx.getPosName());

		setInt(row, col++, tx.getPosNumber());

		setString(row, col++, tx.getServiceName());

		setString(row, col++, tx.getSdcId());

		setString(row, col++, tx.getStampNumber());

		// ------------------------------------------------------------
		// Allocation-specific EBM number
		// ------------------------------------------------------------

		setString(row, col++, ebmNumber);

		// ------------------------------------------------------------
		// Amounts
		// ------------------------------------------------------------

		setDecimal(row, col++, tx.getQuantity(), amountStyle);

		setDecimal(row, col++, tx.getUnitPrice(), amountStyle);

		setDecimal(row, col++, tx.getTotalAmount(), amountStyle);

		// ------------------------------------------------------------
		// Status
		// ------------------------------------------------------------

		setString(row, col++, statusLabel(tx));

		// ------------------------------------------------------------
		// Transaction GUID
		// ------------------------------------------------------------

		setString(row, col++, tx.getTransactionGuid());
	}

	private void setString(Row row, int col, String value) {

		if (value != null) {
			row.createCell(col).setCellValue(value);
		}
	}

	private void setInt(Row row, int col, Integer value) {

		if (value != null) {
			row.createCell(col).setCellValue(value);
		}
	}

	private void setDecimal(Row row, int col, BigDecimal value, CellStyle style) {

		if (value != null) {

			Cell cell = row.createCell(col);

			cell.setCellValue(value.doubleValue());

			cell.setCellStyle(style);
		}
	}

	// ========================================================================
	// PDF EXPORT
	// ========================================================================

	/**
	 * Writes the PDF export.
	 *
	 * PDF_MAX_ROWS limits the number of CardTransactions loaded. A transaction with
	 * multiple allocations can therefore generate multiple PDF rows.
	 *
	 * @return true when the matching CardTransaction set was larger than
	 *         PDF_MAX_ROWS and was therefore truncated.
	 */
	public boolean writePdf(CardTransactionFilter filter, Sort sort, OutputStream out) throws IOException {

		Specification<CardTransaction> spec = CardTransactionSpecifications.build(filter);

		long totalMatching = repository.count(spec);

		/*
		 * Load only the allowed number of CardTransactions.
		 */
		Page<CardTransaction> page = repository.findAll(spec, PageRequest.of(0, PDF_MAX_ROWS, sort));

		boolean truncated = totalMatching > PDF_MAX_ROWS;

		try {

			Document document = new Document(PageSize.A4.rotate(), 24, 24, 28, 28);

			PdfWriter.getInstance(document, out);

			document.open();

			// ------------------------------------------------------------
			// Title
			// ------------------------------------------------------------

			com.lowagie.text.Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);

			com.lowagie.text.Font metaFont = FontFactory.getFont(FontFactory.HELVETICA, 9);

			metaFont.setColor(new Color(90, 96, 112));

			Paragraph title = new Paragraph("Transaction Ledger", titleFont);

			document.add(title);

			// ------------------------------------------------------------
			// Metadata
			// ------------------------------------------------------------

			Paragraph meta = new Paragraph("Generated " + LocalDateTime.now().format(DATE_FMT) + "  ·  "
					+ page.getNumberOfElements() + " of " + totalMatching + " matching transactions"
					+ (truncated
							? "  ·  showing first " + PDF_MAX_ROWS + " transactions " + "(narrow your filters or "
									+ "use the Excel export for " + "the full set)"
							: ""),
					metaFont);

			meta.setSpacingAfter(14);

			document.add(meta);

			// ------------------------------------------------------------
			// PDF headers
			// ------------------------------------------------------------

			String[] pdfHeaders = { "Date / Time", "Client", "Card Number", "Plate Number", "POS Name", "Service",
					"SDC ID", "Stamp Number", "EBM Number", "Total Amount", "Status" };

			float[] widths = { 2.0f, 1.8f, 1.5f, 1.3f, 1.8f, 1.6f, 1.4f, 1.6f, 1.6f, 1.5f, 1.1f };

			PdfPTable table = new PdfPTable(pdfHeaders.length);

			table.setWidthPercentage(100);

			table.setWidths(widths);

			// ------------------------------------------------------------
			// Header style
			// ------------------------------------------------------------

			com.lowagie.text.Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);

			headerFont.setColor(Color.WHITE);

			for (String header : pdfHeaders) {

				PdfPCell cell = new PdfPCell(new com.lowagie.text.Phrase(header, headerFont));

				cell.setBackgroundColor(new Color(28, 35, 51));

				cell.setPadding(5);

				cell.setHorizontalAlignment(Element.ALIGN_LEFT);

				table.addCell(cell);
			}

			// ------------------------------------------------------------
			// Data
			// ------------------------------------------------------------

			com.lowagie.text.Font cellFont = FontFactory.getFont(FontFactory.HELVETICA, 8.5f);

			/*
			 * Load all allocations for the CardTransactions in this PDF page using one
			 * query.
			 */
			Map<UUID, List<String>> ebmNumbers = buildEbmNumberMap(page.getContent());

			/*
			 * One CardTransaction can now create multiple PDF rows.
			 */
			for (CardTransaction tx : page.getContent()) {

				List<String> transactionEbmNumbers = getEbmNumbers(tx, ebmNumbers);

				for (String ebmNumber : transactionEbmNumbers) {

					addCell(table,
							tx.getDateTimeTransaction() != null ? tx.getDateTimeTransaction().format(DATE_FMT) : "—",
							cellFont);

					addCell(table, orDash(tx.getClientName()), cellFont);

					addCell(table, orDash(tx.getCardNumber()), cellFont);

					addCell(table, orDash(tx.getPlateNumber()), cellFont);

					addCell(table, orDash(tx.getPosName()), cellFont);

					addCell(table, orDash(tx.getServiceName()), cellFont);

					addCell(table, orDash(tx.getSdcId()), cellFont);

					addCell(table, orDash(tx.getStampNumber()), cellFont);

					/*
					 * Allocation-specific EBM number.
					 */
					addCell(table, orDash(ebmNumber), cellFont);

					addCell(table, tx.getTotalAmount() != null ? String.format("%,.2f", tx.getTotalAmount()) : "—",
							cellFont);

					addCell(table, statusLabel(tx), cellFont);
				}
			}

			document.add(table);

			document.close();

		} catch (com.lowagie.text.DocumentException e) {

			throw new IOException("Failed to generate PDF export", e);
		}

		return truncated;
	}

	private void addCell(PdfPTable table, String text, com.lowagie.text.Font font) {

		PdfPCell cell = new PdfPCell(new com.lowagie.text.Phrase(text != null ? text : "", font));

		cell.setPadding(4);

		table.addCell(cell);
	}

	private String orDash(String value) {

		return value == null || value.isBlank() ? "—" : value;
	}

	// ========================================================================
	// STATUS
	// ========================================================================

	private String statusLabel(CardTransaction tx) {

		if (Boolean.TRUE.equals(tx.getProcessed()) && !Boolean.TRUE.equals(tx.getSuccess())) {

			return "Failed";
		}

		if (Boolean.TRUE.equals(tx.getProcessed())) {
			return "Processed";
		}

		return "Pending";
	}
}