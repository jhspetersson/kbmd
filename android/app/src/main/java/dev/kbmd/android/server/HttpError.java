package dev.kbmd.android.server;

/** A failure that maps to an HTTP status; the message is shown to the user by the web UI. */
public class HttpError extends RuntimeException {

    public final int status;

    public HttpError(int status, String message) {
        super(message);
        this.status = status;
    }

    public static HttpError badRequest(String message) {
        return new HttpError(400, message);
    }

    public static HttpError notFound(String message) {
        return new HttpError(404, message);
    }

    public static HttpError conflict(String message) {
        return new HttpError(409, message);
    }
}
