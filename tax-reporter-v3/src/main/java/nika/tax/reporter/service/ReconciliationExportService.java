package nika.tax.reporter.service;

import java.awt.Color;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
import org.springframework.stereotype.Service;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import lombok.RequiredArgsConstructor;
import nika.tax.reporter.postgres.domain.CardTransaction;
import nika.tax.reporter.postgres.domain.DepositCardTransaction;
import nika.tax.reporter.postgres.domain.Reconciliation;
import nika.tax.reporter.repository.CardTransactionRepository;
import nika.tax.reporter.repository.DepositCardTransactionRepository;

/**
 * Exports one reconciliation batch together with its full set of child transactions — not a
 * filtered list export like CardTransactionExportService/TaxReporterInvoiceExportService, this
 * is keyed by a single reconciliation id. Reuses
 * CardTransactionRepository.findByReconciliation_Id(id, Pageable) — the same paginated method
 * the reconciliation detail page itself uses — looping through pages internally rather than
 * loading the whole batch into memory at once, the same reason the list-export services do
 * this (a batch built from the bulk "reconcile all matching" action could hold up to
 * ReconciliationService.MAX_BULK_RECONCILE = 25,000 transactions).
 *
 * Column layout and styling deliberately mirror CardTransactionExportService's Excel/PDF output
 * for the transactions portion, including the same batched ebmNumber lookup (avoiding the same
 * N+1 lazy-loading risk on depositAllocations) — small private helpers are duplicated here
 * rather than extracted into a shared utility, matching how this project's other export
 * services already don't share this code either.
 */
@Service
@RequiredArgsConstructor
public class ReconciliationExportService {

    private static final int EXCEL_BATCH_SIZE = 1000;
    private static final int PDF_MAX_ROWS = 5000;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm");

    private static final String[] TX_HEADERS = {
        "Date / Time", "Client", "Client ID", "Card Number", "Plate Number",
        "POS Name", "POS Number", "Service", "SDC ID", "Stamp Number", "EBM Number", "Quantity", "Unit Price",
        "Total Amount", "Status", "Transaction GUID"
    };

    private final ReconciliationService reconciliationService;
    private final CardTransactionRepository cardTransactionRepository;
    private final DepositCardTransactionRepository depositCardTransactionRepository;

    public long countMatching(UUID reconciliationId) {
        return reconciliationService.childTransactionCount(reconciliationId);
    }

    // ---------------------------------------------------------------- Excel

    public void writeExcel(UUID reconciliationId, OutputStream out) throws IOException {
        Reconciliation batch = reconciliationService.getOrThrow(reconciliationId);
        long totalCount = reconciliationService.childTransactionCount(reconciliationId);
        BigDecimal total = reconciliationService.childTransactionTotal(reconciliationId);

        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {
            writeSummarySheet(workbook, batch, totalCount, total);
            writeTransactionsSheet(workbook, reconciliationId);
            workbook.write(out);
            workbook.dispose();
        }
    }

    private void writeSummarySheet(SXSSFWorkbook workbook, Reconciliation batch, long totalCount, BigDecimal total) {
        Sheet sheet = workbook.createSheet("Summary");

        Font labelFont = workbook.createFont();
        labelFont.setBold(true);
        CellStyle labelStyle = workbook.createCellStyle();
        labelStyle.setFont(labelFont);

        CellStyle amountStyle = workbook.createCellStyle();
        amountStyle.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));

        int rowNum = 0;
        rowNum = addSummaryRow(sheet, rowNum, "Reconciliation date", batch.getReconciliationDate() != null ? batch.getReconciliationDate().toString() : "—", labelStyle);
        rowNum = addSummaryRow(sheet, rowNum, "Reference", batch.getReference() != null ? batch.getReference() : "—", labelStyle);
        rowNum = addSummaryRow(sheet, rowNum, "Created", batch.getCreatedAt() != null ? batch.getCreatedAt().format(DATE_FMT) : "—", labelStyle);
        rowNum = addSummaryRow(sheet, rowNum, "Transaction count", String.valueOf(totalCount), labelStyle);

        Row totalRow = sheet.createRow(rowNum);
        Cell totalLabel = totalRow.createCell(0);
        totalLabel.setCellValue("Total amount");
        totalLabel.setCellStyle(labelStyle);
        Cell totalValue = totalRow.createCell(1);
        totalValue.setCellValue(total != null ? total.doubleValue() : 0d);
        totalValue.setCellStyle(amountStyle);

        sheet.setColumnWidth(0, 24 * 256);
        sheet.setColumnWidth(1, 28 * 256);
    }

    private int addSummaryRow(Sheet sheet, int rowNum, String label, String value, CellStyle labelStyle) {
        Row row = sheet.createRow(rowNum);
        Cell labelCell = row.createCell(0);
        labelCell.setCellValue(label);
        labelCell.setCellStyle(labelStyle);
        row.createCell(1).setCellValue(value);
        return rowNum + 1;
    }

    private void writeTransactionsSheet(SXSSFWorkbook workbook, UUID reconciliationId) {
        Sheet sheet = workbook.createSheet("Transactions");

        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        CellStyle headerStyle = workbook.createCellStyle();
        headerStyle.setFillForegroundColor(IndexedColors.GREY_80_PERCENT.getIndex());
        headerStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
        headerStyle.setFont(headerFont);

        CellStyle amountStyle = workbook.createCellStyle();
        amountStyle.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));

        CellStyle dateStyle = workbook.createCellStyle();
        dateStyle.setDataFormat(workbook.createDataFormat().getFormat("dd mmm yyyy hh:mm"));

        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < TX_HEADERS.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(TX_HEADERS[i]);
            cell.setCellStyle(headerStyle);
        }

        int rowNum = 1;
        int pageIndex = 0;
        Page<CardTransaction> page;
        Sort sort = Sort.by(Sort.Direction.DESC, "dateTimeTransaction");
        do {
            page = cardTransactionRepository.findByReconciliation_Id(reconciliationId, PageRequest.of(pageIndex, EXCEL_BATCH_SIZE, sort));
            Map<UUID, String> ebmNumbers = buildEbmNumberMap(page.getContent());
            for (CardTransaction tx : page.getContent()) {
                Row row = sheet.createRow(rowNum++);
                writeTxRow(row, tx, amountStyle, dateStyle, ebmNumbers.get(tx.getId()));
            }
            pageIndex++;
        } while (page.hasNext());

        for (int i = 0; i < TX_HEADERS.length; i++) {
            sheet.setColumnWidth(i, 22 * 256);
        }
    }

    private void writeTxRow(Row row, CardTransaction tx, CellStyle amountStyle, CellStyle dateStyle, String ebmNumber) {
        int col = 0;
        if (tx.getDateTimeTransaction() != null) {
            Cell c = row.createCell(col);
            c.setCellValue(tx.getDateTimeTransaction());
            c.setCellStyle(dateStyle);
        }
        col++;
        setString(row, col++, tx.getClientName());
        setInt(row, col++, tx.getClientId());
        setString(row, col++, tx.getCardNumber());
        setString(row, col++, tx.getPlateNumber());
        setString(row, col++, tx.getPosName());
        setInt(row, col++, tx.getPosNumber());
        setString(row, col++, tx.getServiceName());
        setString(row, col++, tx.getSdcId());
        setString(row, col++, tx.getStampNumber());
        setString(row, col++, ebmNumber);
        setDecimal(row, col++, tx.getQuantity(), amountStyle);
        setDecimal(row, col++, tx.getUnitPrice(), amountStyle);
        setDecimal(row, col++, tx.getTotalAmount(), amountStyle);
        setString(row, col++, statusLabel(tx));
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

    // ------------------------------------------------------------------ PDF

    /** @return true if the batch was larger than {@link #PDF_MAX_ROWS} and had to be truncated. */
    public boolean writePdf(UUID reconciliationId, OutputStream out) throws IOException {
        Reconciliation batch = reconciliationService.getOrThrow(reconciliationId);
        long totalCount = reconciliationService.childTransactionCount(reconciliationId);
        BigDecimal total = reconciliationService.childTransactionTotal(reconciliationId);
        Page<CardTransaction> page = cardTransactionRepository.findByReconciliation_Id(reconciliationId,
                PageRequest.of(0, PDF_MAX_ROWS, Sort.by(Sort.Direction.DESC, "dateTimeTransaction")));
        boolean truncated = totalCount > PDF_MAX_ROWS;

        try {
            Document document = new Document(PageSize.A4.rotate(), 24, 24, 28, 28);
            PdfWriter.getInstance(document, out);
            document.open();

            com.lowagie.text.Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
            com.lowagie.text.Font metaFont = FontFactory.getFont(FontFactory.HELVETICA, 9);
            metaFont.setColor(new Color(90, 96, 112));

            String title = "Reconciliation"
                    + (batch.getReference() != null ? " — " + batch.getReference() : "")
                    + (batch.getReconciliationDate() != null ? " (" + batch.getReconciliationDate() + ")" : "");
            document.add(new Paragraph(title, titleFont));

            Paragraph meta = new Paragraph(
                    "Generated " + LocalDateTime.now().format(DATE_FMT)
                            + "  ·  " + page.getNumberOfElements() + " of " + totalCount + " transactions"
                            + "  ·  total " + (total != null ? String.format("%,.2f", total) : "0.00")
                            + (truncated ? "  ·  showing first " + PDF_MAX_ROWS + " rows (use Excel for the full set)" : ""),
                    metaFont);
            meta.setSpacingAfter(14);
            document.add(meta);

            String[] pdfHeaders = {"Date / Time", "Client", "Card Number", "Plate Number",
                    "POS Name", "Service", "SDC ID", "Stamp Number", "EBM Number", "Total Amount", "Status"};
            float[] widths = {2.0f, 1.8f, 1.5f, 1.3f, 1.8f, 1.6f, 1.4f, 1.6f, 1.6f, 1.5f, 1.1f};

            PdfPTable table = new PdfPTable(pdfHeaders.length);
            table.setWidthPercentage(100);
            table.setWidths(widths);

            com.lowagie.text.Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
            headerFont.setColor(Color.WHITE);
            for (String h : pdfHeaders) {
                PdfPCell cell = new PdfPCell(new Phrase(h, headerFont));
                cell.setBackgroundColor(new Color(28, 35, 51));
                cell.setPadding(5);
                cell.setHorizontalAlignment(Element.ALIGN_LEFT);
                table.addCell(cell);
            }

            com.lowagie.text.Font cellFont = FontFactory.getFont(FontFactory.HELVETICA, 8.5f);
            Map<UUID, String> ebmNumbers = buildEbmNumberMap(page.getContent());
            for (CardTransaction tx : page.getContent()) {
                addCell(table, tx.getDateTimeTransaction() != null ? tx.getDateTimeTransaction().format(DATE_FMT) : "—", cellFont);
                addCell(table, orDash(tx.getClientName()), cellFont);
                addCell(table, orDash(tx.getCardNumber()), cellFont);
                addCell(table, orDash(tx.getPlateNumber()), cellFont);
                addCell(table, orDash(tx.getPosName()), cellFont);
                addCell(table, orDash(tx.getServiceName()), cellFont);
                addCell(table, orDash(tx.getSdcId()), cellFont);
                addCell(table, orDash(tx.getStampNumber()), cellFont);
                addCell(table, orDash(ebmNumbers.get(tx.getId())), cellFont);
                addCell(table, tx.getTotalAmount() != null ? String.format("%,.2f", tx.getTotalAmount()) : "—", cellFont);
                addCell(table, statusLabel(tx), cellFont);
            }

            document.add(table);
            document.close();
        } catch (DocumentException e) {
            throw new IOException("Failed to generate PDF export", e);
        }

        return truncated;
    }

    private void addCell(PdfPTable table, String text, com.lowagie.text.Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text != null ? text : "", font));
        cell.setPadding(4);
        table.addCell(cell);
    }

    // ------------------------------------------------------------- shared

    /** Same batched lookup as CardTransactionExportService.buildEbmNumberMap — one query per
     * page rather than one per row, avoiding an N+1 lazy-load on depositAllocations. */
    private Map<UUID, String> buildEbmNumberMap(List<CardTransaction> transactions) {
        List<UUID> ids = transactions.stream().map(CardTransaction::getId).collect(Collectors.toList());
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<DepositCardTransaction> allocations = depositCardTransactionRepository.findByTransaction_IdIn(ids);
        Map<UUID, String> ebmNumbers = new HashMap<>();
        for (DepositCardTransaction allocation : allocations) {
            UUID txId = allocation.getTransaction().getId();
            if (ebmNumbers.containsKey(txId)) {
                continue;
            }
            String stampNumber = allocation.getDeposit() != null ? allocation.getDeposit().getStampNumber() : null;
            if (stampNumber != null) {
                ebmNumbers.put(txId, stampNumber);
            }
        }
        return ebmNumbers;
    }

    private String orDash(String value) {
        return (value == null || value.isBlank()) ? "—" : value;
    }

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
