package nika.tax.reporter.sqlserver.exception;

/** Thrown when the SQL Server Stamp lookup fails at the connection/query level — distinct from
 * a normal "no matching row" result, which is represented as Optional.empty(), not an exception. */
public class StampLookupException extends RuntimeException {
    private static final long serialVersionUID = 1L;

	public StampLookupException(String message, Throwable cause) {
        super(message, cause);
    }
}
