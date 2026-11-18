package nika.tax.reporter.service;

/** Thrown when a bulk "reconcile these transactions" request fails validation. */
public class ReconciliationException extends RuntimeException {
    public ReconciliationException(String message) {
        super(message);
    }
}
