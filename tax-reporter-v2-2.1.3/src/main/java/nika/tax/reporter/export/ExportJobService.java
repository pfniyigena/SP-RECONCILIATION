package nika.tax.reporter.export;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

/**
 * Exports used to stream the generated file directly over the request that
 * asked for it. For a large filtered set that download could take minutes,
 * and the whole thing depended on one uninterrupted client connection — a
 * laptop going to sleep, wifi dropping, or a tab closing killed the export
 * entirely with no way to resume (this is what ERR_NETWORK_IO_SUSPENDED means:
 * the OS suspended the network stack mid-transfer).
 *
 * Now export generation runs here, on a background thread, decoupled from any
 * particular HTTP connection. The browser starts a job, polls its status, and
 * downloads the finished file once ready — a short request instead of one
 * long fragile one. If the client disconnects mid-generation, the job keeps
 * running; the person can just re-poll or come back later within the file's
 * retention window.
 *
 * This service is deliberately domain-agnostic — it knows nothing about
 * CardTransaction, TaxReporterInvoice, or any other entity. Each controller
 * builds a small ExportTask (count + write) for whatever it's exporting and
 * hands it to submit(); the job-tracking/threading/cleanup machinery below is
 * shared by every exportable entity in the app.
 */
@Service
public class ExportJobService {

    private static final Logger log = LoggerFactory.getLogger(ExportJobService.class);

    /** How long a completed (or abandoned) job's temp file is kept before cleanup. */
    private static final long RETENTION_MINUTES = 30;

    private final Map<String, ExportJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public ExportJob submit(String kind, ExportFormat format, ExportTask task) {
        ExportJob job = new ExportJob(UUID.randomUUID().toString(), format, kind);
        jobs.put(job.getId(), job);
        executor.submit(() -> run(job, task));
        return job;
    }

    public Optional<ExportJob> get(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    private void run(ExportJob job, ExportTask task) {
        job.setStatus(ExportStatus.RUNNING);
        try {
            job.setTotalMatching(task.countMatching());

            Path tempFile = Files.createTempFile("tax-reporter-export-", "." + job.getFormat().name().toLowerCase());
            try (OutputStream out = Files.newOutputStream(tempFile)) {
                boolean truncated = task.writeTo(out);
                job.setTruncated(truncated);
            }

            job.setFilePath(tempFile);
            job.setStatus(ExportStatus.COMPLETED);
            job.setCompletedAt(Instant.now());
        } catch (Exception e) {
            log.error("Export job {} failed", job.getId(), e);
            job.setStatus(ExportStatus.FAILED);
            job.setErrorMessage(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedRate = 5 * 60 * 1000)
    public void cleanupOldJobs() {
        Instant cutoff = Instant.now().minus(RETENTION_MINUTES, ChronoUnit.MINUTES);
        jobs.entrySet().removeIf(entry -> {
            ExportJob job = entry.getValue();
            boolean expired = job.getCreatedAt().isBefore(cutoff);
            if (expired) {
                deleteFileQuietly(job.getFilePath());
            }
            return expired;
        });
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private void deleteFileQuietly(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                log.warn("Could not delete temp export file {}", path, e);
            }
        }
    }
}
