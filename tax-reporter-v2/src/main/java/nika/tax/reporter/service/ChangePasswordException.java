package nika.tax.reporter.service;

/** Thrown when a self-service password change fails validation, carrying which form field it belongs to. */
public class ChangePasswordException extends RuntimeException {

    private final String field;

    public ChangePasswordException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
