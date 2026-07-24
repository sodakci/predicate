package history.query;

/** Raised when a structured predicate query cannot be parsed or evaluated. */
public final class QueryException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public QueryException(String message) {
        super(message);
    }

    public QueryException(String message, Throwable cause) {
        super(message, cause);
    }
}
