package dev.kbmd.android.sync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * What both sides looked like after the last successful sync: the common ancestor of the three-way comparison.
 * Size and modification time are remembered with each file, so unchanged files are not hashed again.
 */
final class SyncState {

    static final class FileState {
        final String sha;
        /** Size and time of the local file that had this content; -1 when the local file is known to differ. */
        final long size;
        final long modified;

        FileState(String sha, long size, long modified) {
            this.sha = sha;
            this.size = size;
            this.modified = modified;
        }
    }

    String remoteKey = "";
    String head;
    long savedAt;
    final Map<String, FileState> files = new HashMap<>();

    SyncState copy() {
        SyncState copy = new SyncState();
        copy.remoteKey = remoteKey;
        copy.head = head;
        copy.savedAt = savedAt;
        copy.files.putAll(files);
        return copy;
    }

    static SyncState load(Path file) {
        SyncState state = new SyncState();
        if (!Files.isRegularFile(file)) {
            return state;
        }
        try {
            JSONObject json = new JSONObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            state.remoteKey = json.optString("remote", "");
            state.head = json.isNull("head") ? null : json.optString("head", null);
            state.savedAt = json.optLong("savedAt");
            JSONObject files = json.optJSONObject("files");
            if (files != null) {
                for (Iterator<String> paths = files.keys(); paths.hasNext(); ) {
                    String path = paths.next();
                    JSONObject entry = files.getJSONObject(path);
                    state.files.put(path, new FileState(entry.getString("sha"), entry.optLong("size", -1), entry.optLong("modified", -1)));
                }
            }
        } catch (IOException | JSONException e) {
            return new SyncState(); // unreadable: behave like a first sync, which never loses data
        }
        return state;
    }

    void save(Path file) throws IOException {
        try {
            JSONObject entries = new JSONObject();
            for (Map.Entry<String, FileState> entry : files.entrySet()) {
                entries.put(entry.getKey(), new JSONObject().put("sha", entry.getValue().sha)
                        .put("size", entry.getValue().size).put("modified", entry.getValue().modified));
            }
            savedAt = System.currentTimeMillis();
            JSONObject json = new JSONObject().put("remote", remoteKey).put("head", head == null ? JSONObject.NULL : head)
                    .put("savedAt", savedAt).put("files", entries);
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temp, json.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }
}
