package nika.tax.reporter.export;

public record ExportJobStatusResponse(
        String jobId,
        String status,
        long totalMatching,
        boolean truncated,
        String errorMessage) {
}
