package dev.kbmd.android.sync;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Small JSON-over-HTTPS client shared by the two providers. */
final class Http {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .build();

    private final Map<String, String> headers;

    Http(Map<String, String> headers) {
        this.headers = headers;
    }

    /** The provider answered with an error status. */
    static final class ApiException extends IOException {
        final int status;

        ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    static final class Reply {
        final byte[] body;
        final String nextPage;

        Reply(byte[] body, String nextPage) {
            this.body = body;
            this.nextPage = nextPage;
        }

        JSONObject object() throws IOException {
            try {
                return new JSONObject(new String(body, "UTF-8"));
            } catch (JSONException e) {
                throw new IOException("Unexpected answer from the server: " + e.getMessage());
            }
        }

        JSONArray array() throws IOException {
            try {
                return new JSONArray(new String(body, "UTF-8"));
            } catch (JSONException e) {
                throw new IOException("Unexpected answer from the server: " + e.getMessage());
            }
        }
    }

    Reply get(String url, String accept) throws IOException {
        return send("GET", url, null, accept);
    }

    Reply send(String method, String url, JSONObject body, String accept) throws IOException {
        Request.Builder request = new Request.Builder().url(url);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            request.header(header.getKey(), header.getValue());
        }
        if (accept != null) {
            request.header("Accept", accept);
        }
        request.method(method, body == null ? null : RequestBody.create(body.toString(), JSON));
        try (Response response = CLIENT.newCall(request.build()).execute()) {
            ResponseBody responseBody = response.body();
            byte[] bytes = responseBody == null ? new byte[0] : responseBody.bytes();
            if (!response.isSuccessful()) {
                throw new ApiException(response.code(), errorMessage(response.code(), bytes));
            }
            return new Reply(bytes, response.header("X-Next-Page"));
        }
    }

    private static String errorMessage(int status, byte[] body) {
        String detail = "";
        try {
            JSONObject json = new JSONObject(new String(body, "UTF-8"));
            Object message = json.has("message") ? json.get("message") : json.opt("error");
            detail = message == null ? "" : String.valueOf(message);
        } catch (JSONException | UnsupportedEncodingException e) {
            // not JSON: the status has to do
        }
        return "HTTP " + status + (detail.isEmpty() ? "" : ": " + detail);
    }

    /** Percent-encodes one path segment (or, with {@code keepSlashes}, a whole path). */
    static String encode(String value, boolean keepSlashes) {
        try {
            String encoded = URLEncoder.encode(value, "UTF-8").replace("+", "%20");
            return keepSlashes ? encoded.replace("%2F", "/") : encoded;
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
