package nika.tax.reporter.export;

import java.io.IOException;
import java.io.OutputStream;

/**
 * What one background export job actually does, independent of which entity
 * it's exporting. Implementations are small lambdas built per-request in the
 * controller that knows the specific filter/entity — ExportJobService itself
 * never needs to know about CardTransaction, TaxReporterInvoice, or anything else.
 */
public interface ExportTask {

    /** Fast COUNT query, used to show progress while the job runs. */
    long countMatching();

    /** Writes the export to `out`. @return true if the output was truncated (e.g. a PDF row cap). */
    boolean writeTo(OutputStream out) throws IOException;
}
