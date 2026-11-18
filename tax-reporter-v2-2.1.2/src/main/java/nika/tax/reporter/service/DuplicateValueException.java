package nika.tax.reporter.service;

/** Thrown when a save fails a unique-constraint check (e.g. a duplicate customer name). */
public class DuplicateValueException extends RuntimeException {

    /** Which form field this should be attached to, if the entity has more than one unique
     * constraint and the caller needs to tell them apart (see StampMachineService, which has
     * two: serialNumber and sdcId). Null for entities with just one, where the catching
     * controller already knows which field to highlight without needing this. */
    private final String field;

    public DuplicateValueException(String message) {
        super(message);
        this.field = null;
    }

    public DuplicateValueException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
