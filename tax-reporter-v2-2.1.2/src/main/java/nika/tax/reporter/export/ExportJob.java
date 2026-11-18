package nika.tax.reporter.export;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Tracks one background export's progress. Deliberately a plain mutable object
 * rather than a JPA entity or a Lombok @Data class — it's held in an in-memory
 * map (see ExportJobService) for the lifetime of one export, not persisted.
 *
 * NOTE: this in-memory map means jobs don't survive an app restart and aren't
 * shared across instances if this app is ever scaled horizontally. Fine for a
 * single-instance deployment; if that changes, back this with Redis or a table
 * instead.
 */
public class ExportJob {

    private final String id;
    private final ExportFormat format;
    private final String kind;
    private final Instant createdAt = Instant.now();

    private volatile ExportStatus status = ExportStatus.QUEUED;
    private volatile long totalMatching = -1;
    private volatile boolean truncated = false;
    private volatile String errorMessage;
    private volatile Path filePath;
    private volatile Instant completedAt;

    public ExportJob(String id, ExportFormat format, String kind) {
        this.id = id;
        this.format = format;
        this.kind = kind;
    }

    public String getKind() {
        return kind;
    }

    public String getId() {
        return id;
    }

    public ExportFormat getFormat() {
        return format;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public ExportStatus getStatus() {
        return status;
    }

    public void setStatus(ExportStatus status) {
        this.status = status;
    }

    public long getTotalMatching() {
        return totalMatching;
    }

    public void setTotalMatching(long totalMatching) {
        this.totalMatching = totalMatching;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public void setTruncated(boolean truncated) {
        this.truncated = truncated;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Path getFilePath() {
        return filePath;
    }

    public void setFilePath(Path filePath) {
        this.filePath = filePath;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }
}
