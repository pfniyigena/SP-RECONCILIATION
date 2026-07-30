package nika.tax.reporter.web;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import nika.tax.reporter.export.ExportFormat;
import nika.tax.reporter.export.ExportJob;
import nika.tax.reporter.export.ExportJobService;
import nika.tax.reporter.export.ExportJobStatusResponse;
import nika.tax.reporter.export.ExportStatus;
import nika.tax.reporter.export.ExportTask;
import nika.tax.reporter.service.CardTransactionExportService;
import nika.tax.reporter.service.CardTransactionFilter;
import nika.tax.reporter.service.CustomerAccessScopeService;
import nika.tax.reporter.service.ReconciliationExportService;
import nika.tax.reporter.service.ReconciliationService;
import nika.tax.reporter.service.TaxReporterInvoiceExportService;
import nika.tax.reporter.service.TaxReporterInvoiceFilter;

/**
 * Entry point for all background exports in the app. Starting a job is
 * necessarily entity-specific (each has its own filter params), but polling
 * status and downloading the finished file are identical regardless of what's
 * being exported — those two live under the neutral /exports path and are
 * shared by every entity via ExportJobService/ExportTask.
 */
@RestController
@RequiredArgsConstructor
public class ExportJobController {

    private final ExportJobService exportJobService;
    private final CardTransactionExportService cardTransactionExportService;
    private final TaxReporterInvoiceExportService invoiceExportService;
    private final ReconciliationExportService reconciliationExportService;
    private final ReconciliationService reconciliationService;
    private final CustomerAccessScopeService customerAccessScopeService;

    @PostMapping("/transactions/export/{format}/jobs")
    public ResponseEntity<ExportJobStatusResponse> startTransactionsExport(
            @PathVariable String format,
            @RequestParam Optional<String> q,
            @RequestParam Optional<String> status,
            @RequestParam Optional<LocalDate> dateFrom,
            @RequestParam Optional<LocalDate> dateTo,
            @RequestParam Optional<BigDecimal> minAmount,
            @RequestParam Optional<BigDecimal> maxAmount,
            @RequestParam(name = "machineId", required = false) List<UUID> machineIds,
            @RequestParam(name = "customerId", required = false) List<UUID> customerIds,
            @RequestParam(defaultValue = "dateTimeTransaction,desc") String sort,
            Authentication authentication) {

        ExportFormat exportFormat = resolveFormat(format);
        if (exportFormat == null) {
            return ResponseEntity.badRequest().build();
        }

        CardTransactionFilter filter = CardTransactionFilter.builder()
                .q(q.orElse(null))
                .status(status.orElse(null))
                .dateFrom(dateFrom.orElse(null))
                .dateTo(dateTo.orElse(null))
                .minAmount(minAmount.orElse(null))
                .maxAmount(maxAmount.orElse(null))
                .machineIds(machineIds != null ? machineIds : Collections.emptyList())
                .customerIds(customerIds != null ? customerIds : Collections.emptyList())
                .build();

        // Same security-level scoping as the list page — an exported file is just
        // another way to read the data, so it gets the identical restriction.
        if (customerAccessScopeService.isScoped(authentication)) {
            filter.setRestrictToCustomerIds(customerAccessScopeService.accessibleCustomerIds(authentication));
        }

        Sort sortObj = parseSort(sort, "dateTimeTransaction");

        ExportTask task = new ExportTask() {
            @Override
            public long countMatching() {
                return cardTransactionExportService.countMatching(filter);
            }

            @Override
            public boolean writeTo(java.io.OutputStream out) throws IOException {
                if (exportFormat == ExportFormat.XLSX) {
                    cardTransactionExportService.writeExcel(filter, sortObj, out);
                    return false;
                }
                return cardTransactionExportService.writePdf(filter, sortObj, out);
            }
        };

        ExportJob job = exportJobService.submit("transactions", exportFormat, task);
        return ResponseEntity.ok(toResponse(job));
    }

    @PostMapping("/invoices/export/{format}/jobs")
    public ResponseEntity<ExportJobStatusResponse> startInvoicesExport(
            @PathVariable String format,
            @RequestParam Optional<String> q,
            @RequestParam Optional<String> status,
            @RequestParam Optional<LocalDate> dateFrom,
            @RequestParam Optional<LocalDate> dateTo,
            @RequestParam Optional<BigDecimal> minAmount,
            @RequestParam Optional<BigDecimal> maxAmount,
            @RequestParam Optional<String> plateNumber,
            @RequestParam(defaultValue = "stampDate,desc") String sort) {

        ExportFormat exportFormat = resolveFormat(format);
        if (exportFormat == null) {
            return ResponseEntity.badRequest().build();
        }

        TaxReporterInvoiceFilter filter = TaxReporterInvoiceFilter.builder()
                .q(q.orElse(null))
                .status(status.orElse(null))
                .dateFrom(dateFrom.orElse(null))
                .dateTo(dateTo.orElse(null))
                .minAmount(minAmount.orElse(null))
                .maxAmount(maxAmount.orElse(null))
                .plateNumber(plateNumber.orElse(null))
                .build();
        Sort sortObj = parseSort(sort, "stampDate");

        ExportTask task = new ExportTask() {
            @Override
            public long countMatching() {
                return invoiceExportService.countMatching(filter);
            }

            @Override
            public boolean writeTo(java.io.OutputStream out) throws IOException {
                if (exportFormat == ExportFormat.XLSX) {
                    invoiceExportService.writeExcel(filter, sortObj, out);
                    return false;
                }
                return invoiceExportService.writePdf(filter, sortObj, out);
            }
        };

        ExportJob job = exportJobService.submit("invoices", exportFormat, task);
        return ResponseEntity.ok(toResponse(job));
    }

    @PostMapping("/reconciliations/{id}/export/{format}/jobs")
    public ResponseEntity<ExportJobStatusResponse> startReconciliationExport(
            @PathVariable UUID id,
            @PathVariable String format,
            Authentication authentication) {

        ExportFormat exportFormat = resolveFormat(format);
        if (exportFormat == null) {
            return ResponseEntity.badRequest().build();
        }

        // Same accessibility gate as ReconciliationController.view() — an export is just
        // another way to read this batch's data, so a scoped user must not be able to reach
        // a mixed/out-of-scope batch through this endpoint just because the view page's own
        // guard doesn't apply here. Batch-wide, not page-limited (see
        // ReconciliationService.hasOutOfScopeTransaction for why that distinction matters).
        if (customerAccessScopeService.isScoped(authentication)) {
            var allowed = customerAccessScopeService.accessibleCustomerIds(authentication);
            if (reconciliationService.hasOutOfScopeTransaction(id, allowed)) {
                return ResponseEntity.notFound().build();
            }
        }

        ExportTask task = new ExportTask() {
            @Override
            public long countMatching() {
                return reconciliationExportService.countMatching(id);
            }

            @Override
            public boolean writeTo(java.io.OutputStream out) throws IOException {
                if (exportFormat == ExportFormat.XLSX) {
                    reconciliationExportService.writeExcel(id, out);
                    return false;
                }
                return reconciliationExportService.writePdf(id, out);
            }
        };

        ExportJob job = exportJobService.submit("reconciliation", exportFormat, task);
        return ResponseEntity.ok(toResponse(job));
    }

    @GetMapping("/exports/jobs/{jobId}")
    public ResponseEntity<ExportJobStatusResponse> status(@PathVariable String jobId) {
        return exportJobService.get(jobId)
                .map(job -> ResponseEntity.ok(toResponse(job)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/exports/jobs/{jobId}/download")
    public void download(@PathVariable String jobId, HttpServletResponse response) throws IOException {
        ExportJob job = exportJobService.get(jobId).orElse(null);

        if (job == null || job.getStatus() != ExportStatus.COMPLETED || job.getFilePath() == null) {
            response.sendError(HttpStatus.NOT_FOUND.value(), "Export not ready or not found");
            return;
        }

        boolean isPdf = job.getFormat() == ExportFormat.PDF;
        String extension = isPdf ? "pdf" : "xlsx";
        String contentType = isPdf
                ? MediaType.APPLICATION_PDF_VALUE
                : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

        response.setContentType(contentType);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + job.getKind() + "." + extension + "\"");

        Files.copy(job.getFilePath(), response.getOutputStream());
    }

    private ExportFormat resolveFormat(String format) {
        if ("xlsx".equalsIgnoreCase(format)) {
            return ExportFormat.XLSX;
        }
        if ("pdf".equalsIgnoreCase(format)) {
            return ExportFormat.PDF;
        }
        return null;
    }

    private Sort parseSort(String sort, String defaultProperty) {
        String[] parts = sort.split(",");
        String property = parts.length > 0 && !parts[0].isBlank() ? parts[0] : defaultProperty;
        Sort.Direction direction = (parts.length > 1 && "asc".equalsIgnoreCase(parts[1]))
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;
        return Sort.by(direction, property);
    }

    private ExportJobStatusResponse toResponse(ExportJob job) {
        return new ExportJobStatusResponse(
                job.getId(),
                job.getStatus().name(),
                job.getTotalMatching(),
                job.isTruncated(),
                job.getErrorMessage());
    }
}
