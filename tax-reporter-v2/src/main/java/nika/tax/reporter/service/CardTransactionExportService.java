package nika.tax.reporter.service;

import java.awt.Color;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;
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
public class CardTransactionExportService {

    /** Excel is streamed page-by-page so exporting the full filtered set doesn't blow up memory. */
    private static final int EXCEL_BATCH_SIZE = 1000;

    /** A PDF is meant to be read/printed, not a database dump — cap it to something sane. */
    private static final int PDF_MAX_ROWS = 5000;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm");

    private static final String[] HEADERS = {
        "Date / Time", "Client", "Client ID", "Card Number", "Plate Number",
        "POS Name", "POS Number", "Service", "SDC ID", "Stamp Number", "EBM Number", "Allocated Amount", "Allocated Quantity", "Quantity", "Unit Price",
        "Total Amount", "Status", "Transaction GUID"
    };

    private final CardTransactionRepository repository;
    private final DepositCardTransactionRepository depositCardTransactionRepository;

    /**
     * Bulk lookup, one query per page/batch rather than one per row — same reasoning as the
     * earlier ebmNumber-only version this replaces (avoids N+1 lazy-loading on
     * depositAllocations, since export queries don't go through
     * CardTransactionRepository.findWithAllocationsById's @EntityGraph).
     *
     * Returns EVERY allocation per transaction, not just the first — a transaction split
     * across multiple deposits now gets one export row per allocation (see writeExcel/
     * writePdf), each with that specific allocation's own ebmNumber and allocatedAmount,
     * rather than collapsing to a single row that could only ever show one deposit's stamp
     * number even when the transaction was actually funded by several.
     */
    private Map<UUID, List<DepositCardTransaction>> buildAllocationsMap(List<CardTransaction> transactions) {
        List<UUID> ids = transactions.stream().map(CardTransaction::getId).collect(Collectors.toList());
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<DepositCardTransaction> allocations = depositCardTransactionRepository.findByTransaction_IdIn(ids);
        return allocations.stream().collect(Collectors.groupingBy(a -> a.getTransaction().getId()));
    }

    // ---------------------------------------------------------------- Excel

    public long countMatching(CardTransactionFilter filter) {
        return repository.count(CardTransactionSpecifications.build(filter));
    }

    public void writeExcel(CardTransactionFilter filter, Sort sort, OutputStream out) throws IOException {
        Specification<CardTransaction> spec = CardTransactionSpecifications.build(filter);

        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {
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
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(HEADERS[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowNum = 1;
            int pageIndex = 0;
            Page<CardTransaction> page;
            do {
                page = repository.findAll(spec, PageRequest.of(pageIndex, EXCEL_BATCH_SIZE, sort));
                Map<UUID, List<DepositCardTransaction>> allocationsByTx = buildAllocationsMap(page.getContent());
                for (CardTransaction tx : page.getContent()) {
                    List<DepositCardTransaction> allocations = allocationsByTx.getOrDefault(tx.getId(), List.of());
                    if (allocations.isEmpty()) {
                        Row row = sheet.createRow(rowNum++);
                        writeRow(row, tx, amountStyle, dateStyle, null, null);
                    } else {
                        for (DepositCardTransaction allocation : allocations) {
                            Row row = sheet.createRow(rowNum++);
                            String ebmNumber = allocation.getDeposit() != null ? allocation.getDeposit().getStampNumber() : null;
                            writeRow(row, tx, amountStyle, dateStyle, ebmNumber, allocation.getAllocatedAmount());
                        }
                    }
                }
                pageIndex++;
            } while (page.hasNext());

            for (int i = 0; i < HEADERS.length; i++) {
                sheet.setColumnWidth(i, 22 * 256);
            }

            workbook.write(out);
            workbook.dispose(); // clean up SXSSF temp files
        }
    }

    private void writeRow(Row row, CardTransaction tx, CellStyle amountStyle, CellStyle dateStyle, String ebmNumber, BigDecimal allocatedAmount) {
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
        setDecimal(row, col++, allocatedAmount, amountStyle);
        setDecimal(row, col++, computeAllocatedQuantity(allocatedAmount, tx.getUnitPrice()), amountStyle);
        setDecimal(row, col++, tx.getQuantity(), amountStyle);
        setDecimal(row, col++, tx.getUnitPrice(), amountStyle);
        setDecimal(row, col++, tx.getTotalAmount(), amountStyle);
        setString(row, col++, statusLabel(tx));
        setString(row, col++, tx.getTransactionGuid());
    }

    /** allocatedAmount / CardTransaction.unitPrice — how much of the transaction's quantity
     * this one allocation actually covers, in the same units as tx.getQuantity() (e.g. liters,
     * if unitPrice is per-liter). Null whenever the division isn't meaningful: no allocation on
     * this row (allocatedAmount null), or unitPrice missing or zero — never throws
     * ArithmeticException for a divide-by-zero, which BigDecimal.divide would otherwise do. */
    private BigDecimal computeAllocatedQuantity(BigDecimal allocatedAmount, BigDecimal unitPrice) {
        if (allocatedAmount == null || unitPrice == null || unitPrice.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return allocatedAmount.divide(unitPrice, 4, RoundingMode.HALF_UP);
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

    /**
     * @return true if the matching result set was larger than {@link #PDF_MAX_ROWS} and had to be truncated.
     */
    public boolean writePdf(CardTransactionFilter filter, Sort sort, OutputStream out) throws IOException {
        Specification<CardTransaction> spec = CardTransactionSpecifications.build(filter);
        long totalMatching = repository.count(spec);
        Page<CardTransaction> page = repository.findAll(spec, PageRequest.of(0, PDF_MAX_ROWS, sort));
        boolean truncated = totalMatching > PDF_MAX_ROWS;

        try {
            Document document = new Document(PageSize.A4.rotate(), 24, 24, 28, 28);
            PdfWriter.getInstance(document, out);
            document.open();

            com.lowagie.text.Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
            com.lowagie.text.Font metaFont = FontFactory.getFont(FontFactory.HELVETICA, 9);
            metaFont.setColor(new Color(90, 96, 112));

            Paragraph title = new Paragraph("Transaction Ledger", titleFont);
            document.add(title);

            Paragraph meta = new Paragraph(
                    "Generated " + java.time.LocalDateTime.now().format(DATE_FMT)
                            + "  ·  " + page.getNumberOfElements() + " of " + totalMatching + " matching transactions"
                            + (truncated ? "  ·  showing first " + PDF_MAX_ROWS + " rows (narrow your filters or use the Excel export for the full set)" : ""),
                    metaFont);
            meta.setSpacingAfter(14);
            document.add(meta);

            String[] pdfHeaders = {"Date / Time", "Client", "Card Number", "Plate Number",
                    "POS Name", "Service", "SDC ID", "Stamp Number", "EBM Number", "Allocated Amount", "Allocated Qty", "Total Amount", "Status"};
            float[] widths = {2.0f, 1.8f, 1.5f, 1.3f, 1.8f, 1.6f, 1.4f, 1.6f, 1.6f, 1.5f, 1.3f, 1.5f, 1.1f};

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
            Map<UUID, List<DepositCardTransaction>> allocationsByTx = buildAllocationsMap(page.getContent());
            for (CardTransaction tx : page.getContent()) {
                List<DepositCardTransaction> allocations = allocationsByTx.getOrDefault(tx.getId(), List.of());
                if (allocations.isEmpty()) {
                    addTxRow(table, tx, null, null, cellFont);
                } else {
                    for (DepositCardTransaction allocation : allocations) {
                        String ebmNumber = allocation.getDeposit() != null ? allocation.getDeposit().getStampNumber() : null;
                        addTxRow(table, tx, ebmNumber, allocation.getAllocatedAmount(), cellFont);
                    }
                }
            }

            document.add(table);
            document.close();
        } catch (com.lowagie.text.DocumentException e) {
            throw new IOException("Failed to generate PDF export", e);
        }

        return truncated;
    }

    private void addTxRow(PdfPTable table, CardTransaction tx, String ebmNumber, BigDecimal allocatedAmount, com.lowagie.text.Font cellFont) {
        addCell(table, tx.getDateTimeTransaction() != null ? tx.getDateTimeTransaction().format(DATE_FMT) : "—", cellFont);
        addCell(table, orDash(tx.getClientName()), cellFont);
        addCell(table, orDash(tx.getCardNumber()), cellFont);
        addCell(table, orDash(tx.getPlateNumber()), cellFont);
        addCell(table, orDash(tx.getPosName()), cellFont);
        addCell(table, orDash(tx.getServiceName()), cellFont);
        addCell(table, orDash(tx.getSdcId()), cellFont);
        addCell(table, orDash(tx.getStampNumber()), cellFont);
        addCell(table, orDash(ebmNumber), cellFont);
        addCell(table, allocatedAmount != null ? String.format("%,.2f", allocatedAmount) : "—", cellFont);
        BigDecimal allocatedQuantity = computeAllocatedQuantity(allocatedAmount, tx.getUnitPrice());
        addCell(table, allocatedQuantity != null ? String.format("%,.4f", allocatedQuantity) : "—", cellFont);
        addCell(table, tx.getTotalAmount() != null ? String.format("%,.2f", tx.getTotalAmount()) : "—", cellFont);
        addCell(table, statusLabel(tx), cellFont);
    }

    private void addCell(PdfPTable table, String text, com.lowagie.text.Font font) {
        PdfPCell cell = new PdfPCell(new com.lowagie.text.Phrase(text != null ? text : "", font));
        cell.setPadding(4);
        table.addCell(cell);
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
