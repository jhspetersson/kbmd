package dev.kbmd.android.server;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.kbmd.android.calendar.CalendarService;
import dev.kbmd.android.flashcards.FlashcardService;
import dev.kbmd.android.index.NoteIndex;
import dev.kbmd.android.sync.SyncSettings;
import dev.kbmd.android.tasks.TaskService;
import dev.kbmd.android.vault.VaultService;
import fi.iki.elonen.NanoHTTPD;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The web app's REST API, served to the WebView from inside the app on 127.0.0.1. Every request must carry the
 * session token (a cookie set by the activity), so other apps on the phone cannot read the vault through the port.
 */
public class ApiServer extends NanoHTTPD {

    public static final String SESSION_COOKIE = "kbmd_session";
    private static final long MAX_BODY_BYTES = 120L * 1024 * 1024;
    private static final Map<String, String> MIME_TYPES = new HashMap<>();

    static {
        String[][] types = {
                {"html", "text/html; charset=utf-8"}, {"js", "text/javascript; charset=utf-8"}, {"mjs", "text/javascript; charset=utf-8"},
                {"css", "text/css; charset=utf-8"}, {"json", "application/json"}, {"map", "application/json"},
                {"svg", "image/svg+xml"}, {"png", "image/png"}, {"jpg", "image/jpeg"}, {"jpeg", "image/jpeg"},
                {"gif", "image/gif"}, {"webp", "image/webp"}, {"avif", "image/avif"}, {"bmp", "image/bmp"}, {"ico", "image/x-icon"},
                {"woff", "font/woff"}, {"woff2", "font/woff2"}, {"ttf", "font/ttf"}, {"otf", "font/otf"},
                {"wasm", "application/wasm"}, {"txt", "text/plain; charset=utf-8"}, {"md", "text/markdown; charset=utf-8"},
                {"pdf", "application/pdf"}, {"mp3", "audio/mpeg"}, {"m4a", "audio/mp4"}, {"ogg", "audio/ogg"}, {"wav", "audio/wav"},
                {"mp4", "video/mp4"}, {"webm", "video/webm"}, {"mov", "video/quicktime"}, {"zip", "application/zip"},
                {"excalidraw", "application/json"}, {"csv", "text/csv; charset=utf-8"},
        };
        for (String[] type : types) {
            MIME_TYPES.put(type[0], type[1]);
        }
    }

    /** Where the built web UI comes from: the APK's assets on the phone, a folder in tests. */
    public interface WebAssets {
        /** @return null when there is no such file */
        InputStream open(String path) throws IOException;
    }

    private final Backend backend;
    private final WebAssets assets;
    private final String sessionToken;
    private final File tempDir;

    public ApiServer(int port, Backend backend, WebAssets assets, String sessionToken, File tempDir) {
        super("127.0.0.1", port);
        this.backend = backend;
        this.assets = assets;
        this.sessionToken = sessionToken;
        this.tempDir = tempDir;
    }

    public static String mimeType(String name) {
        int dot = name.lastIndexOf('.');
        String type = dot < 0 ? null : MIME_TYPES.get(name.substring(dot + 1).toLowerCase(Locale.ROOT));
        return type == null ? "application/octet-stream" : type;
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            if (!authorised(session)) {
                // nothing of the body is read for strangers; the connection is not kept alive either
                Response refused = error(403, "Forbidden");
                refused.addHeader("Connection", "close");
                return refused;
            }
            byte[] body = readBody(session);
            String uri = session.getUri();
            return uri.startsWith("/api/") ? api(session, uri.substring(4), body) : asset(uri);
        } catch (HttpError e) {
            return error(e.status, e.getMessage());
        } catch (JSONException e) {
            return error(400, "Malformed request: " + e.getMessage());
        } catch (OutOfMemoryError e) {
            return error(413, "Not enough memory for a request of this size");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return error(500, cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
        }
    }

    private boolean authorised(IHTTPSession session) {
        String cookie = session.getCookies().read(SESSION_COOKIE);
        String header = session.getHeaders().get("x-kbmd-session");
        return sessionToken.equals(cookie) || sessionToken.equals(header);
    }

    // ---------------------------------------------------------------- API

    private Response api(IHTTPSession session, String route, byte[] body) throws Exception {
        String key = session.getMethod().name() + " " + route;
        VaultService vault = backend.vault;
        NoteIndex index = backend.index;
        switch (key) {
            case "GET /tree":
                return json(200, tree(vault.tree()));
            case "GET /notes":
                return json(200, document(param(session, "path"), true));
            case "PUT /notes": {
                String path = param(session, "path");
                JSONObject request = jsonBody(body);
                // the editor sends the timestamp it loaded; a mismatch means a sync or another app changed the file
                backend.notes.save(path, request.isNull("content") ? "" : request.getString("content"),
                        request.isNull("baseModified") ? null : request.getLong("baseModified"));
                return json(200, document(path, false));
            }
            case "POST /notes": {
                JSONObject request = jsonBody(body);
                String path = requiredText(request, "path");
                backend.notes.create(path, request.isNull("content") ? "" : request.getString("content"));
                return json(201, document(path, true));
            }
            case "GET /notes/names": {
                JSONArray names = new JSONArray();
                for (NoteIndex.NoteRef ref : index.noteRefs()) {
                    names.put(new JSONObject().put("path", ref.path).put("title", ref.title));
                }
                return json(200, names);
            }
            case "POST /folders":
                backend.notes.createFolder(requiredText(jsonBody(body), "path"));
                return empty(201);
            case "POST /move": {
                JSONObject request = jsonBody(body);
                return json(200, new JSONObject().put("updatedNotes", backend.notes.move(requiredText(request, "from"), requiredText(request, "to"))));
            }
            case "DELETE /entries":
                backend.notes.delete(param(session, "path"));
                return empty(204);
            case "POST /render": {
                JSONObject request = jsonBody(body);
                String path = request.isNull("path") ? null : request.getString("path");
                String content = request.isNull("content") ? vault.read(path) : request.getString("content");
                return json(200, new JSONObject().put("html", backend.markdown.render(content, path, index)));
            }
            case "GET /backlinks":
                return json(200, hits(index.backlinks(param(session, "path"))));
            case "GET /graph":
                return json(200, graph(index.graph()));
            case "GET /tags": {
                JSONObject tags = new JSONObject();
                for (Map.Entry<String, Integer> tag : index.tags().entrySet()) {
                    tags.put(tag.getKey(), tag.getValue());
                }
                return json(200, tags);
            }
            case "GET /search": {
                NoteIndex.SearchResponse found = index.search(optionalParam(session, "q"));
                return json(200, new JSONObject().put("hits", hits(found.hits)).put("terms", new JSONArray(found.terms)));
            }
            case "POST /daily": {
                // today's daily note, created on first use
                String path = "Daily/" + LocalDate.now() + ".md";
                if (!vault.exists(path)) {
                    backend.notes.create(path, "# " + LocalDate.now() + "\n\n");
                }
                return json(200, document(path, true));
            }
            case "POST /files/upload":
                return json(200, upload(session, body));
            case "GET /files/raw":
                return raw(session, vault.existingFile(param(session, "path")));
            case "GET /export":
                return export();
            case "GET /flashcards/decks": {
                JSONArray decks = new JSONArray();
                for (FlashcardService.Deck deck : backend.flashcards.decks()) {
                    decks.put(new JSONObject().put("name", deck.name).put("total", deck.total).put("fresh", deck.fresh).put("due", deck.due)
                            .put("notes", new JSONArray(deck.notes)));
                }
                return json(200, decks);
            }
            case "GET /flashcards/due": {
                JSONArray cards = new JSONArray();
                for (FlashcardService.StudyCard card : backend.flashcards.due(optionalParam(session, "deck"))) {
                    JSONObject intervals = new JSONObject();
                    for (Map.Entry<FlashcardService.Rating, Integer> interval : card.intervals.entrySet()) {
                        intervals.put(interval.getKey().name(), interval.getValue());
                    }
                    cards.put(new JSONObject().put("id", card.id).put("deck", card.deck).put("notePath", card.notePath)
                            .put("line", card.line).put("fresh", card.fresh).put("frontHtml", card.frontHtml)
                            .put("backHtml", card.backHtml).put("intervals", intervals));
                }
                return json(200, cards);
            }
            case "POST /flashcards/review": {
                JSONObject request = jsonBody(body);
                FlashcardService.Rating rating;
                try {
                    rating = FlashcardService.Rating.valueOf(requiredText(request, "rating"));
                } catch (IllegalArgumentException e) {
                    throw HttpError.badRequest("Unknown rating");
                }
                FlashcardService.CardState state = backend.flashcards.review(requiredText(request, "id"), rating);
                return json(200, new JSONObject().put("due", state.due).put("interval", state.interval)
                        .put("ease", state.ease).put("reps", state.reps).put("lapses", state.lapses));
            }
            case "GET /flashcards/export": {
                String deck = optionalParam(session, "deck");
                String name = (deck == null || deck.trim().isEmpty() ? "kbmd" : deck.replaceAll("[^\\w-]+", "_")) + "-anki.txt";
                String host = session.getHeaders().get("host");
                byte[] text = backend.flashcards.exportForAnki(deck, "http://" + (host == null ? "127.0.0.1" : host)).getBytes(StandardCharsets.UTF_8);
                Response response = newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", new ByteArrayInputStream(text), text.length);
                response.addHeader("Content-Disposition", "attachment; filename=\"" + name + "\"");
                return response;
            }
            case "GET /tasks": {
                JSONArray tasks = new JSONArray();
                for (TaskService.Task task : backend.tasks.tasks()) {
                    tasks.put(task(task));
                }
                return json(200, tasks);
            }
            case "POST /tasks": {
                JSONObject request = jsonBody(body);
                return json(200, task(backend.tasks.add(requiredText(request, "text"), request.isNull("path") ? null : request.getString("path"))));
            }
            case "POST /tasks/toggle": {
                JSONObject request = jsonBody(body);
                return json(200, new JSONObject().put("done", backend.tasks.toggle(requiredText(request, "path"), request.getInt("line"))));
            }
            case "GET /kanban": {
                JSONArray boards = new JSONArray();
                for (TaskService.Board board : backend.tasks.boards()) {
                    boards.put(board(board));
                }
                return json(200, boards);
            }
            case "POST /kanban/move": {
                JSONObject request = jsonBody(body);
                return json(200, board(backend.tasks.move(requiredText(request, "path"), request.getInt("line"),
                        requiredText(request, "column"), request.optInt("position", Integer.MAX_VALUE))));
            }
            case "POST /kanban/card": {
                JSONObject request = jsonBody(body);
                return json(200, board(backend.tasks.addCard(requiredText(request, "path"), requiredText(request, "column"), requiredText(request, "text"))));
            }
            case "GET /calendar": {
                java.time.LocalDate from;
                java.time.LocalDate to;
                try {
                    from = java.time.LocalDate.parse(param(session, "from"));
                    to = java.time.LocalDate.parse(param(session, "to"));
                } catch (java.time.format.DateTimeParseException e) {
                    throw HttpError.badRequest("Dates must be YYYY-MM-DD");
                }
                JSONArray occurrences = new JSONArray();
                for (CalendarService.Occurrence o : backend.calendar.occurrences(from, to)) {
                    occurrences.put(new JSONObject().put("date", o.date).put("endDate", o.endDate == null ? JSONObject.NULL : o.endDate)
                            .put("time", o.time == null ? JSONObject.NULL : o.time).put("endTime", o.endTime == null ? JSONObject.NULL : o.endTime)
                            .put("title", o.title).put("notePath", o.notePath).put("line", o.line).put("kind", o.kind)
                            .put("recurring", o.recurring).put("detail", o.detail == null ? JSONObject.NULL : o.detail)
                            .put("rrule", o.rrule == null ? JSONObject.NULL : o.rrule));
                }
                return json(200, occurrences);
            }
            case "POST /calendar/events": {
                JSONObject request = jsonBody(body);
                return json(200, new JSONObject().put("line", backend.calendar.add(requiredText(request, "spec"), requiredText(request, "title")))
                        .put("notePath", CalendarService.EVENTS_NOTE));
            }
            case "GET /calendar/export": {
                byte[] text = backend.calendar.ics().getBytes(StandardCharsets.UTF_8);
                Response response = newFixedLengthResponse(Response.Status.OK, "text/calendar; charset=utf-8", new ByteArrayInputStream(text), text.length);
                response.addHeader("Content-Disposition", "attachment; filename=\"kbmd-calendar.ics\"");
                return response;
            }
            case "GET /habits": {
                String days = optionalParam(session, "days");
                int span = 14;
                try {
                    span = days == null ? 14 : Integer.parseInt(days.trim());
                } catch (NumberFormatException e) {
                    throw HttpError.badRequest("Bad number of days");
                }
                return json(200, backend.habits.board(span));
            }
            case "POST /habits/toggle": {
                JSONObject request = jsonBody(body);
                return json(200, new JSONObject().put("done", backend.habits.toggle(requiredText(request, "id"), requiredText(request, "date"))));
            }
            case "GET /sync/settings":
                return json(200, backend.sync.settings().toJson(false));
            case "PUT /sync/settings":
                return json(200, backend.sync.updateSettings(SyncSettings.fromJson(jsonBody(body))).toJson(false));
            case "GET /sync/status":
                return json(200, backend.sync.status());
            case "POST /sync/test":
                return json(200, backend.sync.test().toJson());
            case "POST /sync/run":
                return json(200, backend.sync.sync().toJson());
            default:
                throw HttpError.notFound("No such endpoint: " + key);
        }
    }

    private JSONObject document(String path, boolean withContent) throws JSONException {
        return new JSONObject()
                .put("path", path)
                .put("title", NoteIndex.title(path))
                .put("content", withContent ? backend.vault.read(path) : JSONObject.NULL)
                .put("modified", backend.vault.lastModified(path));
    }

    private static JSONObject task(TaskService.Task task) throws JSONException {
        return new JSONObject().put("notePath", task.notePath).put("noteTitle", task.noteTitle).put("line", task.line)
                .put("text", task.text).put("done", task.done).put("due", task.due == null ? JSONObject.NULL : task.due)
                .put("tags", new JSONArray(task.tags));
    }

    private static JSONObject board(TaskService.Board board) throws JSONException {
        JSONArray columns = new JSONArray();
        for (TaskService.Column column : board.columns) {
            JSONArray cards = new JSONArray();
            for (TaskService.Card card : column.cards) {
                cards.put(new JSONObject().put("line", card.line).put("text", card.text).put("task", card.task)
                        .put("done", card.done).put("due", card.due == null ? JSONObject.NULL : card.due));
            }
            columns.put(new JSONObject().put("name", column.name).put("line", column.line).put("cards", cards));
        }
        return new JSONObject().put("path", board.path).put("title", board.title).put("columns", columns);
    }

    private static JSONObject tree(VaultService.TreeNode node) throws JSONException {
        JSONObject json = new JSONObject().put("name", node.name).put("path", node.path).put("type", node.type);
        if (node.children == null) {
            return json.put("children", JSONObject.NULL);
        }
        JSONArray children = new JSONArray();
        for (VaultService.TreeNode child : node.children) {
            children.put(tree(child));
        }
        return json.put("children", children);
    }

    private static JSONArray hits(List<NoteIndex.Hit> hits) throws JSONException {
        JSONArray array = new JSONArray();
        for (NoteIndex.Hit hit : hits) {
            JSONArray snippets = new JSONArray();
            for (NoteIndex.Snippet snippet : hit.snippets) {
                snippets.put(new JSONObject().put("line", snippet.line).put("text", snippet.text));
            }
            array.put(new JSONObject().put("path", hit.path).put("title", hit.title).put("snippets", snippets));
        }
        return array;
    }

    private static JSONObject graph(NoteIndex.Graph graph) throws JSONException {
        JSONArray nodes = new JSONArray();
        for (NoteIndex.GraphNode node : graph.nodes) {
            nodes.put(new JSONObject().put("id", node.id).put("label", node.label).put("exists", node.exists).put("tags", new JSONArray(node.tags)));
        }
        JSONArray edges = new JSONArray();
        for (NoteIndex.GraphEdge edge : graph.edges) {
            edges.put(new JSONObject().put("source", edge.source).put("target", edge.target));
        }
        return new JSONObject().put("nodes", nodes).put("edges", edges);
    }

    private JSONArray upload(IHTTPSession session, byte[] body) throws JSONException {
        List<Multipart.Part> parts = Multipart.parse(session.getHeaders().get("content-type"), body);
        String folder = null;
        for (Multipart.Part part : parts) {
            if (part.name.equals("folder") && part.fileName == null) {
                folder = part.text();
            }
        }
        JSONArray uploaded = new JSONArray();
        for (Multipart.Part part : parts) {
            if (!part.name.equals("file") || part.fileName == null) {
                continue;
            }
            String path = backend.notes.upload(part.fileName, folder, new ByteArrayInputStream(part.body, part.start, part.length));
            uploaded.put(uploadedFile(path));
        }
        if (uploaded.length() == 0) {
            throw HttpError.badRequest("No file in the request");
        }
        return uploaded;
    }

    /** The answer for one stored file, with the markdown that embeds or links it. */
    public static JSONObject uploadedFile(String path) throws JSONException {
        String name = path.substring(path.lastIndexOf('/') + 1);
        boolean embed = VaultService.isImage(path) || VaultService.isDrawing(path);
        return new JSONObject().put("path", path).put("name", name).put("markdown", (embed ? "!" : "") + "[[" + name + "]]");
    }

    private Response raw(IHTTPSession session, Path file) throws IOException {
        String name = file.getFileName().toString();
        String type = mimeType(name);
        boolean inline = type.startsWith("image/") || type.startsWith("audio/") || type.startsWith("video/") || type.equals("application/pdf");
        long size = Files.size(file);

        Response response;
        String range = session.getHeaders().get("range");
        long[] bounds = parseRange(range, size);
        if (bounds != null) {
            // media elements seek with range requests
            InputStream in = new FileInputStream(file.toFile());
            long skipped = 0;
            while (skipped < bounds[0]) {
                long step = in.skip(bounds[0] - skipped);
                if (step <= 0) {
                    break;
                }
                skipped += step;
            }
            long length = bounds[1] - bounds[0] + 1;
            response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, type, in, length);
            response.addHeader("Content-Range", "bytes " + bounds[0] + "-" + bounds[1] + "/" + size);
        } else {
            response = newFixedLengthResponse(Response.Status.OK, type, new FileInputStream(file.toFile()), size);
        }
        response.addHeader("Accept-Ranges", "bytes");
        response.addHeader("Content-Disposition", (inline ? "inline" : "attachment") + "; filename*=UTF-8''" + urlEncode(name));
        // uploaded files are untrusted: never let them run script on this origin
        response.addHeader("Content-Security-Policy", "sandbox");
        response.addHeader("X-Content-Type-Options", "nosniff");
        response.addHeader("Cache-Control", "no-cache");
        return response;
    }

    private static long[] parseRange(String header, long size) {
        if (header == null || !header.startsWith("bytes=") || header.contains(",") || size == 0) {
            return null;
        }
        try {
            String[] parts = header.substring(6).trim().split("-", -1);
            long from;
            long to;
            if (parts[0].isEmpty()) {
                from = Math.max(0, size - Long.parseLong(parts[1]));
                to = size - 1;
            } else {
                from = Long.parseLong(parts[0]);
                to = parts.length < 2 || parts[1].isEmpty() ? size - 1 : Math.min(size - 1, Long.parseLong(parts[1]));
            }
            return from > to || from >= size ? null : new long[] {from, to};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Response export() throws IOException {
        File zip = File.createTempFile("export", ".zip", tempDir);
        try (OutputStream out = new FileOutputStream(zip)) {
            backend.vault.exportZip(out);
        }
        InputStream in = new FileInputStream(zip) {
            @Override
            public void close() throws IOException {
                super.close();
                zip.delete();
            }
        };
        Response response = newFixedLengthResponse(Response.Status.OK, "application/zip", in, zip.length());
        response.addHeader("Content-Disposition", "attachment; filename=\"kbmd-vault-" + LocalDate.now() + ".zip\"");
        return response;
    }

    // ---------------------------------------------------------------- web UI

    private Response asset(String uri) throws IOException {
        String path = uri.equals("/") ? "index.html" : uri.substring(1);
        if (path.contains("..")) {
            throw HttpError.badRequest("Invalid path");
        }
        InputStream in = assets.open(path);
        if (in == null && !path.substring(path.lastIndexOf('/') + 1).contains(".")) {
            path = "index.html"; // client-side routes
            in = assets.open(path);
        }
        if (in == null) {
            throw HttpError.notFound("Not found: " + uri);
        }
        Response response = newChunkedResponse(Response.Status.OK, mimeType(path), in);
        response.addHeader("Cache-Control", path.startsWith("assets/") || path.startsWith("fonts/") ? "public, max-age=31536000, immutable" : "no-cache");
        return response;
    }

    // ---------------------------------------------------------------- plumbing

    private static byte[] readBody(IHTTPSession session) throws IOException {
        String header = session.getHeaders().get("content-length");
        if (header == null) {
            return new byte[0];
        }
        long length;
        try {
            length = Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            throw HttpError.badRequest("Bad Content-Length");
        }
        if (length > MAX_BODY_BYTES) {
            throw new HttpError(413, "The upload is too large (limit: 120 MB)");
        }
        byte[] body = new byte[(int) length];
        InputStream in = session.getInputStream();
        int read = 0;
        while (read < body.length) {
            int count = in.read(body, read, body.length - read);
            if (count < 0) {
                throw HttpError.badRequest("Incomplete request body");
            }
            read += count;
        }
        return body;
    }

    private static JSONObject jsonBody(byte[] body) throws JSONException {
        return body.length == 0 ? new JSONObject() : new JSONObject(new String(body, StandardCharsets.UTF_8));
    }

    private static String requiredText(JSONObject json, String key) {
        if (json.isNull(key)) {
            throw HttpError.badRequest("'" + key + "' is required");
        }
        return json.optString(key);
    }

    private static String param(IHTTPSession session, String name) {
        String value = optionalParam(session, name);
        if (value == null) {
            throw HttpError.badRequest("Parameter '" + name + "' is required");
        }
        return value;
    }

    private static String optionalParam(IHTTPSession session, String name) {
        List<String> values = session.getParameters().get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Response json(int status, Object json) {
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        Response response = newFixedLengthResponse(new Status(status), "application/json; charset=utf-8", new ByteArrayInputStream(bytes), bytes.length);
        response.addHeader("Cache-Control", "no-store");
        return response;
    }

    private static Response empty(int status) {
        return newFixedLengthResponse(new Status(status), "text/plain", new ByteArrayInputStream(new byte[0]), 0);
    }

    private static Response error(int status, String message) {
        try {
            return json(status, new JSONObject().put("status", status).put("message", message == null ? "Error" : message));
        } catch (JSONException e) {
            return empty(status);
        }
    }

    /** NanoHTTPD's own status enum lacks a few codes (422 among them). */
    private static final class Status implements Response.IStatus {
        private final int code;

        Status(int code) {
            this.code = code;
        }

        @Override
        public int getRequestStatus() {
            return code;
        }

        @Override
        public String getDescription() {
            return code + " " + (code < 300 ? "OK" : code < 500 ? "Client Error" : "Server Error");
        }
    }
}
