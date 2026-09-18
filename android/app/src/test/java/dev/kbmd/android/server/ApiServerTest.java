package dev.kbmd.android.server;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipInputStream;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Drives the API over real HTTP, the way the web UI does. */
public class ApiServerTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private ApiServer server;
    private String base;
    private Path vault;

    private static final class Reply {
        int status;
        byte[] body;
        HttpURLConnection connection;

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        JSONObject json() throws Exception {
            return new JSONObject(text());
        }
    }

    @Before
    public void start() throws Exception {
        vault = temp.newFolder("vault").toPath();
        Backend backend = new Backend(vault, temp.newFolder("private").toPath(), "# Welcome\n\nSee [[Second note]] #intro\n".getBytes(StandardCharsets.UTF_8));
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        server = new ApiServer(port, backend, path -> path.equals("index.html") ? new ByteArrayInputStream("<html>ui</html>".getBytes()) : null,
                "secret", temp.newFolder("cache"));
        server.start(5000, true);
        base = "http://127.0.0.1:" + port;
    }

    @After
    public void stop() {
        server.stop();
    }

    private Reply call(String method, String path, String contentType, byte[] body, String... headers) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection();
        connection.setRequestMethod(method);
        connection.setRequestProperty("Cookie", "other=1; " + ApiServer.SESSION_COOKIE + "=secret");
        for (int i = 0; i < headers.length; i += 2) {
            connection.setRequestProperty(headers[i], headers[i + 1]);
        }
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", contentType);
            connection.getOutputStream().write(body);
        }
        Reply reply = new Reply();
        reply.connection = connection;
        reply.status = connection.getResponseCode();
        try (InputStream in = reply.status < 400 ? connection.getInputStream() : connection.getErrorStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int count; in != null && (count = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, count);
            }
            reply.body = out.toByteArray();
        }
        return reply;
    }

    private Reply json(String method, String path, JSONObject body) throws Exception {
        return call(method, path, "application/json", body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String q(String value) throws IOException {
        return URLEncoder.encode(value, "UTF-8");
    }

    @Test
    public void requestsWithoutTheSessionAreRefused() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(base + "/api/tree").openConnection();
        assertEquals(403, connection.getResponseCode());
    }

    @Test
    public void servesTheUi() throws Exception {
        assertEquals("<html>ui</html>", call("GET", "/", null, null).text());
        assertEquals(404, call("GET", "/missing.js", null, null).status);
    }

    @Test
    public void notesRoundTripWithUnicode() throws Exception {
        String path = "Заметки/Привет мир.md";
        String content = "# Привет\n\nnaïve café ☕ [[Welcome]] #тег\n";
        assertEquals(201, json("POST", "/api/notes", new JSONObject().put("path", path).put("content", content)).status);
        assertEquals(409, json("POST", "/api/notes", new JSONObject().put("path", path)).status);

        JSONObject read = call("GET", "/api/notes?path=" + q(path), null, null).json();
        assertEquals(content, read.getString("content"));
        assertEquals("Привет мир", read.getString("title"));

        Reply stale = json("PUT", "/api/notes?path=" + q(path), new JSONObject().put("content", "x").put("baseModified", 1));
        assertEquals(409, stale.status);
        assertTrue(stale.json().getString("message").contains("changed on disk"));
        Reply saved = json("PUT", "/api/notes?path=" + q(path), new JSONObject().put("content", content + "more").put("baseModified", read.getLong("modified")));
        assertEquals(200, saved.status);
        assertEquals(content + "more", new String(Files.readAllBytes(vault.resolve(path)), StandardCharsets.UTF_8));

        JSONObject tags = call("GET", "/api/tags", null, null).json();
        assertEquals(1, tags.getInt("тег"));
        JSONArray backlinks = new JSONArray(call("GET", "/api/backlinks?path=Welcome.md", null, null).text());
        assertEquals(path, backlinks.getJSONObject(0).getString("path"));
    }

    @Test
    public void rendersSearchesAndBuildsTheGraph() throws Exception {
        String html = json("POST", "/api/render", new JSONObject().put("path", "Welcome.md").put("content", "[[Welcome|home]] and [[Nowhere]]\n\n- [ ] task\n\n```mermaid\ngraph TD\n```\n"))
                .json().getString("html");
        assertTrue(html, html.contains("data-path=\"Welcome.md\""));
        assertTrue(html, html.contains("unresolved"));
        assertTrue(html, html.contains("<pre class=\"mermaid\">"));
        assertTrue(html, html.contains("type=\"checkbox\""));
        assertTrue(html, html.contains("data-line=\"3\""));

        JSONObject found = call("GET", "/api/search?q=" + q("welc"), null, null).json();
        assertEquals("Welcome.md", found.getJSONArray("hits").getJSONObject(0).getString("path"));
        assertEquals(1, call("GET", "/api/search?q=" + q("tag:intro \"second note\""), null, null).json().getJSONArray("hits").length());
        assertEquals(0, call("GET", "/api/search?q=" + q("welcome absentword"), null, null).json().getJSONArray("hits").length());

        JSONObject graph = call("GET", "/api/graph", null, null).json();
        assertEquals(2, graph.getJSONArray("nodes").length());
        assertEquals("unresolved:second note", graph.getJSONArray("edges").getJSONObject(0).getString("target"));
    }

    @Test
    public void movingANoteRewritesLinks() throws Exception {
        json("POST", "/api/notes", new JSONObject().put("path", "Second note.md").put("content", "back to [[Welcome]]"));
        json("POST", "/api/notes", new JSONObject().put("path", "sub/Welcome.md").put("content", "namesake"));
        JSONObject moved = json("POST", "/api/move", new JSONObject().put("from", "Welcome.md").put("to", "archive/Start.md")).json();
        assertEquals(1, moved.getInt("updatedNotes"));
        assertEquals("back to [[Start]]", new String(Files.readAllBytes(vault.resolve("Second note.md")), StandardCharsets.UTF_8));

        // case-only rename of a folder: a no-op for Files.move on case-insensitive storage unless handled
        assertEquals(200, json("POST", "/api/move", new JSONObject().put("from", "archive").put("to", "Archive")).status);
        assertTrue(call("GET", "/api/tree", null, null).text().contains("\"Archive\""));
        assertFalse(call("GET", "/api/tree", null, null).text().contains("\"archive\""));
        assertEquals(200, json("POST", "/api/move", new JSONObject().put("from", "Archive").put("to", "archive")).status);

        assertEquals(204, call("DELETE", "/api/entries?path=" + q("archive/Start.md"), null, null).status);
        assertTrue(Files.exists(vault.resolve(".trash/Start.md")));
        assertEquals(400, call("GET", "/api/notes?path=" + q("../outside.md"), null, null).status);
        assertEquals(400, call("GET", "/api/notes?path=" + q(".trash/Start.md"), null, null).status);
    }

    @Test
    public void uploadsRawFilesAndExport() throws Exception {
        byte[] image = new byte[5000];
        for (int i = 0; i < image.length; i++) {
            image[i] = (byte) (i % 251);
        }
        String boundary = "----kbmdTestBoundary";
        ByteArrayOutputStream form = new ByteArrayOutputStream();
        form.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"фото 1.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        form.write(image);
        form.write(("\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"notes.txt\"\r\n\r\nplain\r\n").getBytes(StandardCharsets.UTF_8));
        form.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"folder\"\r\n\r\nmedia\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        JSONArray uploaded = new JSONArray(call("POST", "/api/files/upload", "multipart/form-data; boundary=" + boundary, form.toByteArray()).text());
        assertEquals("media/фото 1.png", uploaded.getJSONObject(0).getString("path"));
        assertEquals("![[фото 1.png]]", uploaded.getJSONObject(0).getString("markdown"));
        assertEquals("[[notes.txt]]", uploaded.getJSONObject(1).getString("markdown"));
        assertArrayEquals(image, Files.readAllBytes(vault.resolve("media/фото 1.png")));

        Reply raw = call("GET", "/api/files/raw?path=" + q("media/фото 1.png"), null, null);
        assertArrayEquals(image, raw.body);
        assertEquals("image/png", raw.connection.getContentType());
        Reply part = call("GET", "/api/files/raw?path=" + q("media/фото 1.png"), null, null, "Range", "bytes=100-199");
        assertEquals(206, part.status);
        assertEquals(100, part.body.length);
        assertEquals(image[100], part.body[0]);

        Reply export = call("GET", "/api/export", null, null);
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(export.body), StandardCharsets.UTF_8)) {
            while (zip.getNextEntry() != null) {
                entries++;
            }
        }
        assertEquals(3, entries);
    }

    @Test
    public void flashcardsAreScheduledAndStoredLikeTheWebApp() throws Exception {
        json("POST", "/api/notes", new JSONObject().put("path", "Cards.md").put("content", "#flashcards/geo\n\nCapital of France::Paris\n\nThe ==Seine== flows through Paris.\n"));
        JSONArray decks = new JSONArray(call("GET", "/api/flashcards/decks", null, null).text());
        assertEquals("geo", decks.getJSONObject(0).getString("name"));
        assertEquals(2, decks.getJSONObject(0).getInt("fresh"));

        JSONArray due = new JSONArray(call("GET", "/api/flashcards/due?deck=geo", null, null).text());
        JSONObject card = due.getJSONObject(0);
        assertTrue(card.getString("frontHtml").contains("Capital of France"));
        assertFalse(card.getString("frontHtml").contains("flashcards"));
        assertEquals(4, card.getJSONObject("intervals").getInt("EASY"));

        JSONObject state = json("POST", "/api/flashcards/review", new JSONObject().put("id", card.getString("id")).put("rating", "GOOD")).json();
        assertEquals(1, state.getInt("interval"));
        String stored = new String(Files.readAllBytes(vault.resolve(".flashcards.json")), StandardCharsets.UTF_8);
        assertTrue(stored, stored.contains("\"ease\" : 2.5"));
        assertEquals(1, new JSONArray(call("GET", "/api/flashcards/due", null, null).text()).length());
        assertTrue(call("GET", "/api/flashcards/export", null, null).text().startsWith("#separator:tab"));
    }

    @Test
    public void tracksHabitsLikeTheWebApp() throws Exception {
        json("POST", "/api/notes", new JSONObject().put("path", "Habits.md").put("content", "#habits\n\n- Exercise\n- Read (3x/week)\n"));
        JSONObject board = call("GET", "/api/habits?days=7", null, null).json();
        assertEquals(7, board.getJSONArray("days").length());
        JSONArray habits = board.getJSONArray("habits");
        assertEquals("Exercise", habits.getJSONObject(0).getString("name"));
        assertEquals(3, habits.getJSONObject(1).getInt("weeklyTarget"));

        String today = java.time.LocalDate.now().toString();
        assertTrue(json("POST", "/api/habits/toggle", new JSONObject().put("id", "Exercise").put("date", today)).json().getBoolean("done"));
        JSONObject exercise = call("GET", "/api/habits?days=7", null, null).json().getJSONArray("habits").getJSONObject(0);
        assertEquals(1, exercise.getInt("streak"));
        assertEquals(today, exercise.getJSONArray("done").getString(0));
        String stored = new String(Files.readAllBytes(vault.resolve(".habits.json")), StandardCharsets.UTF_8);
        assertEquals("{\n  \"Exercise\" : [ \"" + today + "\" ]\n}", stored);
        assertEquals(400, json("POST", "/api/habits/toggle", new JSONObject().put("id", "Exercise").put("date", "2999-01-01")).status);
        assertEquals(404, json("POST", "/api/habits/toggle", new JSONObject().put("id", "Nope").put("date", today)).status);
    }

    @Test
    public void tasksAndKanbanEditTheNotes() throws Exception {
        json("POST", "/api/notes", new JSONObject().put("path", "Plan.md").put("content", "- [ ] Buy seeds due:2026-05-01 #garden\n- [x] Order soil\n"));
        JSONArray tasks = new JSONArray(call("GET", "/api/tasks", null, null).text());
        assertEquals(2, tasks.length());
        assertEquals("Buy seeds #garden", tasks.getJSONObject(0).getString("text"));
        assertEquals("2026-05-01", tasks.getJSONObject(0).getString("due"));
        assertTrue(json("POST", "/api/tasks/toggle", new JSONObject().put("path", "Plan.md").put("line", 1)).json().getBoolean("done"));
        assertEquals("Tasks.md", json("POST", "/api/tasks", new JSONObject().put("text", "Call the nursery")).json().getString("notePath"));
        assertEquals("# Tasks\n- [ ] Call the nursery\n", new String(Files.readAllBytes(vault.resolve("Tasks.md")), StandardCharsets.UTF_8));

        json("POST", "/api/notes", new JSONObject().put("path", "Board.md").put("content", "#kanban\n\n## To do\n\n- [ ] Write spec\n  detail\n- [ ] Review\n\n## Done\n"));
        JSONArray boards = new JSONArray(call("GET", "/api/kanban", null, null).text());
        assertEquals(2, boards.getJSONObject(0).getJSONArray("columns").length());
        JSONObject moved = json("POST", "/api/kanban/move", new JSONObject().put("path", "Board.md").put("line", 5).put("column", "Done").put("position", 0)).json();
        JSONObject done = moved.getJSONArray("columns").getJSONObject(1).getJSONArray("cards").getJSONObject(0);
        assertEquals("Write spec", done.getString("text"));
        assertTrue(done.getBoolean("done"));
        assertEquals("#kanban\n\n## To do\n\n- [ ] Review\n\n## Done\n\n- [x] Write spec\n  detail\n", new String(Files.readAllBytes(vault.resolve("Board.md")), StandardCharsets.UTF_8));
        JSONObject added = json("POST", "/api/kanban/card", new JSONObject().put("path", "Board.md").put("column", "To do").put("text", "Ship")).json();
        assertEquals(2, added.getJSONArray("columns").getJSONObject(0).getJSONArray("cards").length());
    }

    @Test
    public void calendarExpandsRules() throws Exception {
        json("POST", "/api/notes", new JSONObject().put("path", "Events.md").put("content",
                "#calendar\n\n- 2026-09-25 14:30 Dentist\n- every Mon,Wed 07:00 Gym\n- every month last Invoices\n- birthday 1990-09-20 Mom\n- every 2 weeks Tue Sync from 2026-09-15\n"));
        JSONArray week = new JSONArray(call("GET", "/api/calendar?from=2026-09-20&to=2026-09-30", null, null).text());
        java.util.List<String> summary = new java.util.ArrayList<>();
        for (int i = 0; i < week.length(); i++) {
            summary.add(week.getJSONObject(i).getString("date") + " " + week.getJSONObject(i).getString("title"));
        }
        assertEquals(java.util.Arrays.asList("2026-09-20 Mom", "2026-09-21 Gym", "2026-09-23 Gym", "2026-09-25 Dentist", "2026-09-28 Gym",
                "2026-09-29 Sync", "2026-09-30 Invoices", "2026-09-30 Gym"), summary);
        assertEquals("36", week.getJSONObject(0).getString("detail"));
        assertEquals("14:30", week.getJSONObject(3).getString("time"));
        assertEquals("Calendar.md", json("POST", "/api/calendar/events", new JSONObject().put("spec", "2026-11-01 10:00").put("title", "Vet")).json().getString("notePath"));
        assertEquals(400, json("POST", "/api/calendar/events", new JSONObject().put("spec", "someday").put("title", "Vet")).status);
        String ics = call("GET", "/api/calendar/export", null, null).text();
        assertTrue(ics, ics.contains("RRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=TU") && ics.contains("RRULE:FREQ=MONTHLY;BYMONTHDAY=-1") && ics.contains("SUMMARY:Vet"));
    }

    @Test
    public void syncSettingsNeverReturnTheToken() throws Exception {
        JSONObject saved = json("PUT", "/api/sync/settings", new JSONObject().put("provider", "gitlab").put("remoteUrl", "me/notes")
                .put("token", "glpat-secret").put("branch", "").put("autoSyncMinutes", 15)).json();
        assertFalse(saved.has("token"));
        assertTrue(saved.getBoolean("tokenSet"));
        assertEquals("main", saved.getString("branch"));

        json("PUT", "/api/sync/settings", new JSONObject().put("provider", "gitlab").put("remoteUrl", "me/notes").put("token", ""));
        JSONObject status = call("GET", "/api/sync/status", null, null).json();
        assertTrue("a blank token keeps the stored one", status.getBoolean("configured"));
        assertEquals(-1, status.getInt("pendingChanges"));
        assertFalse("the token stays out of the vault", Files.exists(vault.resolve(".kbmd")));
    }
}
