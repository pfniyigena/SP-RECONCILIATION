package nika.tax.reporter.service;

import java.awt.Color;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;

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
import nika.tax.reporter.postgres.domain.TaxReporterInvoice;
import nika.tax.reporter.repository.TaxReporterInvoiceRepository;

@Service
@RequiredArgsConstructor
public class TaxReporterInvoiceExportService {

    private static final int EXCEL_BATCH_SIZE = 1000;
    private static final int PDF_MAX_ROWS = 5000;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm");

    private static final String[] HEADERS = {
        "Stamp Date", "Registered Name", "Registered TIN", "SDC ID", "Client TIN", "Client Name",
        "Client Phone", "Receipt Number", "Stamp Number", "Total Tax Amount", "Total Amount",
        "Paid Amount", "Transaction Type", "Payment Mode", "Plate Number", "ERP Code", "Status", "Failure Reason"
    };

    private final TaxReporterInvoiceRepository repository;

    public long countMatching(TaxReporterInvoiceFilter filter) {
        return repository.count(TaxReporterInvoiceSpecifications.build(filter));
    }

    // ---------------------------------------------------------------- Excel

    public void writeExcel(TaxReporterInvoiceFilter filter, Sort sort, OutputStream out) throws IOException {
        Specification<TaxReporterInvoice> spec = TaxReporterInvoiceSpecifications.build(filter);

        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {
            Sheet sheet = workbook.createSheet("Invoices");

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
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(HEADERS[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowNum = 1;
            int pageIndex = 0;
            Page<TaxReporterInvoice> page;
            do {
                page = repository.findAll(spec, PageRequest.of(pageIndex, EXCEL_BATCH_SIZE, sort));
                for (TaxReporterInvoice inv : page.getContent()) {
                    Row row = sheet.createRow(rowNum++);
                    writeRow(row, inv, amountStyle, dateStyle);
                }
                pageIndex++;
            } while (page.hasNext());

            for (int i = 0; i < HEADERS.length; i++) {
                sheet.setColumnWidth(i, 22 * 256);
            }

            workbook.write(out);
            workbook.dispose();
        }
    }

    private void writeRow(Row row, TaxReporterInvoice inv, CellStyle amountStyle, CellStyle dateStyle) {
        int col = 0;

        if (inv.getStampDate() != null) {
            Cell c = row.createCell(col);
            c.setCellValue(inv.getStampDate());
            c.setCellStyle(dateStyle);
        }
        col++;
        setString(row, col++, inv.getRegisteredName());
        setString(row, col++, inv.getRegisteredTin());
        setString(row, col++, inv.getSdcId());
        setString(row, col++, inv.getClientTin());
        setString(row, col++, inv.getClientName());
        setString(row, col++, inv.getClientPhone());
        setLong(row, col++, inv.getReceiptNumber());
        setString(row, col++, inv.getStampNumber());
        setDecimal(row, col++, inv.getTotalTaxAmount(), amountStyle);
        setDecimal(row, col++, inv.getTotalAmount(), amountStyle);
        setDecimal(row, col++, inv.getPaidAmount(), amountStyle);
        setString(row, col++, inv.getTransactionType());
        setString(row, col++, inv.getPaymentMode());
        setString(row, col++, inv.getPlateNumber());
        setString(row, col++, inv.getErpCode());
        setString(row, col++, statusLabel(inv));
        setString(row, col++, inv.getFailureReason());
    }

    private void setString(Row row, int col, String value) {
        if (value != null) {
            row.createCell(col).setCellValue(value);
        }
    }

    private void setLong(Row row, int col, Long value) {
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

    public boolean writePdf(TaxReporterInvoiceFilter filter, Sort sort, OutputStream out) throws IOException {
        Specification<TaxReporterInvoice> spec = TaxReporterInvoiceSpecifications.build(filter);
        long totalMatching = repository.count(spec);
        Page<TaxReporterInvoice> page = repository.findAll(spec, PageRequest.of(0, PDF_MAX_ROWS, sort));
        boolean truncated = totalMatching > PDF_MAX_ROWS;

        try {
            Document document = new Document(PageSize.A4.rotate(), 24, 24, 28, 28);
            PdfWriter.getInstance(document, out);
            document.open();

            com.lowagie.text.Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
            com.lowagie.text.Font metaFont = FontFactory.getFont(FontFactory.HELVETICA, 9);
            metaFont.setColor(new Color(90, 96, 112));

            document.add(new Paragraph("Invoice Ledger", titleFont));

            Paragraph meta = new Paragraph(
                    "Generated " + java.time.LocalDateTime.now().format(DATE_FMT)
                            + "  ·  " + page.getNumberOfElements() + " of " + totalMatching + " matching invoices"
                            + (truncated ? "  ·  showing first " + PDF_MAX_ROWS + " rows (narrow your filters or use the Excel export for the full set)" : ""),
                    metaFont);
            meta.setSpacingAfter(14);
            document.add(meta);

            String[] pdfHeaders = {"Stamp Date", "Plate Number", "Client Name", "Client TIN",
                    "Receipt #", "Stamp Number", "Total Amount", "Payment Mode", "Status"};
            float[] widths = {1.9f, 1.9f, 1.9f, 1.4f, 1.2f, 1.6f, 1.4f, 1.4f, 1.2f};

            PdfPTable table = new PdfPTable(pdfHeaders.length);
            table.setWidthPercentage(100);
            table.setWidths(widths);

            com.lowagie.text.Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
            headerFont.setColor(Color.WHITE);
            for (String h : pdfHeaders) {
                PdfPCell cell = new PdfPCell(new com.lowagie.text.Phrase(h, headerFont));
                cell.setBackgroundColor(new Color(28, 35, 51));
                cell.setPadding(5);
                cell.setHorizontalAlignment(Element.ALIGN_LEFT);
                table.addCell(cell);
            }

            com.lowagie.text.Font cellFont = FontFactory.getFont(FontFactory.HELVETICA, 8.5f);
            for (TaxReporterInvoice inv : page.getContent()) {
                addCell(table, inv.getStampDate() != null ? inv.getStampDate().format(DATE_FMT) : "—", cellFont);
                addCell(table, orDash(inv.getPlateNumber()), cellFont);
                addCell(table, orDash(inv.getClientName()), cellFont);
                addCell(table, orDash(inv.getClientTin()), cellFont);
                addCell(table, inv.getReceiptNumber() != null ? inv.getReceiptNumber().toString() : "—", cellFont);
                addCell(table, orDash(inv.getStampNumber()), cellFont);
                addCell(table, inv.getTotalAmount() != null ? String.format("%,.2f", inv.getTotalAmount()) : "—", cellFont);
                addCell(table, orDash(inv.getPaymentMode()), cellFont);
                addCell(table, statusLabel(inv), cellFont);
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
        return (value == null || value.isBlank()) ? "—" : value;
    }

    private String statusLabel(TaxReporterInvoice inv) {
        if (inv.getFailureReason() != null && !inv.getFailureReason().isBlank()) {
            return "Failed";
        }
        if (Boolean.TRUE.equals(inv.getProcessed())) {
            return "Processed";
        }
        return "Pending";
    }
}
