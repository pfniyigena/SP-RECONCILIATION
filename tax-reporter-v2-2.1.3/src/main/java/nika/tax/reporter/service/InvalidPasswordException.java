package nika.tax.reporter.service;

/** Thrown when a submitted password fails the "required on create / min length if provided" rule. */
public class InvalidPasswordException extends RuntimeException {
    public InvalidPasswordException(String message) {
        super(message);
    }
}
