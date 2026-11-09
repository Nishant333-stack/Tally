package dev.nishant.tally.http;

/** An error that maps directly to an HTTP status and a stable machine-readable code. */
public class ApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public final int status;
    public final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException badRequest(String msg) { return new ApiException(400, "invalid_request", msg); }
    public static ApiException notFound(String what) { return new ApiException(404, "not_found", what + " not found"); }
}
