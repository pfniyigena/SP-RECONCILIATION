package nika.tax.reporter.job;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Remembers the last run of each background job (when it started/finished, whether it threw)
 * so the Jobs page can show "last run" without anyone grepping logs. In memory only: it is
 * empty after an app restart, and per instance. Static on purpose - the jobs have hand-written
 * constructors, and this keeps every job's wiring unchanged.
 */
public final class JobRunTracker {

    public record JobRun(LocalDateTime started, LocalDateTime finished, boolean success, String message) {
        public long seconds() {
            return java.time.Duration.between(started, finished).toSeconds();
        }
    }

    private static final Map<String, JobRun> LAST = new ConcurrentHashMap<>();

    private JobRunTracker() {
    }

    /** Runs the job, records the outcome, and rethrows any exception unchanged. */
    public static void run(String name, Runnable job) {
        call(name, () -> {
            job.run();
            return null;
        });
    }

    public static <T> T call(String name, Supplier<T> job) {
        LocalDateTime started = LocalDateTime.now();
        try {
            T result = job.get();
            LAST.put(name, new JobRun(started, LocalDateTime.now(), true, "Completed"));
            return result;
        } catch (RuntimeException e) {
            LAST.put(name, new JobRun(started, LocalDateTime.now(), false,
                    e.getClass().getSimpleName() + ": " + e.getMessage()));
            throw e;
        }
    }

    public static Map<String, JobRun> snapshot() {
        return Map.copyOf(LAST);
    }
}
