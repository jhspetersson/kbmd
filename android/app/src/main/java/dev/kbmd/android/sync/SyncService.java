package dev.kbmd.android.sync;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.kbmd.android.index.NoteIndex;
import dev.kbmd.android.server.HttpError;
import dev.kbmd.android.vault.VaultService;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Sync with a GitHub or GitLab repository through the provider's REST API, authenticated with an access token.
 * Settings (including the token) and the sync state live in the app's private storage, outside the vault.
 */
public class SyncService {

    private final NoteIndex index;
    private final SyncEngine engine;
    private final Path settingsFile;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "kbmd-auto-sync");
        thread.setDaemon(true);
        return thread;
    });

    private volatile SyncSettings settings;
    private volatile SyncResult lastResult;
    private volatile long lastAttempt = System.currentTimeMillis();

    public SyncService(VaultService vault, NoteIndex index, Path privateDir) {
        this.index = index;
        this.settingsFile = privateDir.resolve("sync.json");
        this.engine = new SyncEngine(vault, privateDir.resolve("sync-state.json"));
        this.settings = load();
        scheduler.scheduleWithFixedDelay(this::autoSync, 60, 60, TimeUnit.SECONDS);
    }

    public SyncSettings settings() {
        return settings;
    }

    /** A blank token keeps the stored one, so the UI never needs to read it back. */
    public synchronized SyncSettings updateSettings(SyncSettings updated) {
        SyncSettings merged = updated.token.isEmpty() ? updated.withToken(settings.token) : updated;
        try {
            Files.createDirectories(settingsFile.getParent());
            Files.write(settingsFile, merged.toJson(true).toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            throw new HttpError(500, "Cannot store sync settings: " + e.getMessage());
        }
        settings = merged;
        return merged;
    }

    public JSONObject status() throws JSONException {
        SyncSettings current = settings;
        SyncResult result = lastResult;
        return new JSONObject()
                .put("configured", current.configured())
                .put("running", running.get())
                .put("pendingChanges", current.configured() && !running.get() ? engine.pendingChanges(current) : -1)
                .put("lastResult", result == null ? JSONObject.NULL : result.toJson());
    }

    public boolean hasPendingChanges() {
        SyncSettings current = settings;
        return current.configured() && engine.pendingChanges(current) != 0;
    }

    /** Checks that the repository is reachable with the stored token. */
    public SyncResult test() {
        SyncSettings current = requireConfigured();
        try {
            return SyncResult.ok(remote(current).test(), new ArrayList<>());
        } catch (IOException | RuntimeException e) {
            return SyncResult.failed(describe(e));
        }
    }

    public SyncResult sync() {
        SyncSettings current = requireConfigured();
        if (!running.compareAndSet(false, true)) {
            throw HttpError.conflict("A sync is already running");
        }
        lastAttempt = System.currentTimeMillis();
        SyncResult result;
        try {
            SyncEngine.Outcome outcome = engine.sync(remote(current), current);
            String message = outcome.steps.isEmpty() ? "Already up to date." : "Synced: " + String.join(", ", outcome.steps) + ".";
            if (!outcome.conflicts.isEmpty()) {
                message += " " + outcome.conflicts.size() + " conflicting file(s): the remote versions were saved as separate copies.";
            }
            if (!outcome.skipped.isEmpty()) {
                message += " Not synced (over 50 MB, or a name this device cannot store): " + String.join(", ", outcome.skipped) + ".";
            }
            result = SyncResult.ok(message, outcome.conflicts);
        } catch (IOException | RuntimeException e) {
            result = SyncResult.failed(describe(e));
        } finally {
            running.set(false);
        }
        lastResult = result;
        index.rebuild();
        return result;
    }

    /** Runs a sync when automatic sync is on and the interval has passed. */
    private void autoSync() {
        SyncSettings current = settings;
        if (!current.configured() || current.autoSyncMinutes <= 0 || running.get()) {
            return;
        }
        if (System.currentTimeMillis() - lastAttempt >= current.autoSyncMinutes * 60_000L) {
            try {
                sync();
            } catch (RuntimeException e) {
                // a manual sync got there first
            }
        }
    }

    /** For the moment the app goes to the background: push what was just written, if automatic sync is on. */
    public void syncInBackgroundIfDue() {
        SyncSettings current = settings;
        if (!current.configured() || current.autoSyncMinutes <= 0 || running.get()) {
            return;
        }
        // a moment later, so the editor's last save has reached the disk
        scheduler.schedule(() -> {
            try {
                if (!running.get() && hasPendingChanges()) {
                    sync();
                }
            } catch (RuntimeException e) {
                // the next sync will catch up
            }
        }, 2, TimeUnit.SECONDS);
    }

    private static RemoteRepo remote(SyncSettings settings) {
        if (settings.location().path.isEmpty() || !settings.location().path.contains("/")) {
            throw HttpError.badRequest("The repository should look like owner/repo or https://host/owner/repo");
        }
        return settings.gitlab() ? new GitLabRepo(settings) : new GitHubRepo(settings);
    }

    private SyncSettings requireConfigured() {
        SyncSettings current = settings;
        if (!current.configured()) {
            throw HttpError.badRequest("Set the repository URL and access token first");
        }
        return current;
    }

    private static String describe(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (e instanceof UnknownHostException) {
            return "No connection: cannot reach " + message + ". Check the network and the repository URL.";
        }
        if (e instanceof SocketTimeoutException) {
            return "The server did not answer in time. Check the network and try again.";
        }
        if (e instanceof Http.ApiException) {
            int status = ((Http.ApiException) e).status;
            if (status == 401) {
                return "Authentication failed: check the access token. (" + message + ")";
            }
            if (status == 403) {
                return "Not allowed: check the token's repository permissions (it must be able to write contents). (" + message + ")";
            }
            if (status == 404) {
                return "Repository not found: check the URL, and that the token can access it. (" + message + ")";
            }
        }
        return message;
    }

    private SyncSettings load() {
        if (Files.isRegularFile(settingsFile)) {
            try {
                return SyncSettings.fromJson(new JSONObject(new String(Files.readAllBytes(settingsFile), StandardCharsets.UTF_8)));
            } catch (IOException | JSONException e) {
                // unreadable settings: start over
            }
        }
        return SyncSettings.empty();
    }

    public static final class SyncResult {
        public final boolean ok;
        public final String message;
        public final List<String> conflicts;
        public final Instant time;

        private SyncResult(boolean ok, String message, List<String> conflicts) {
            this.ok = ok;
            this.message = message;
            this.conflicts = conflicts;
            this.time = Instant.now();
        }

        static SyncResult ok(String message, List<String> conflicts) {
            return new SyncResult(true, message, new ArrayList<>(conflicts));
        }

        static SyncResult failed(String message) {
            return new SyncResult(false, message, new ArrayList<>());
        }

        public JSONObject toJson() throws JSONException {
            return new JSONObject().put("ok", ok).put("message", message)
                    .put("conflicts", new JSONArray(conflicts)).put("time", time.toString());
        }
    }
}
