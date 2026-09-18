package dev.kbmd.android.sync;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** GitLab (gitlab.com or self-hosted) through the repository API: tree listing, raw blobs and multi-file commits. */
final class GitLabRepo implements RemoteRepo {

    /** Commits carry their files as base64 JSON, so large syncs are split into several commits. */
    private static final int MAX_COMMIT_BYTES = 8 * 1024 * 1024;
    private static final int MAX_COMMIT_ACTIONS = 200;

    private final SyncSettings settings;
    private final Http http;
    private final String project;

    GitLabRepo(SyncSettings settings) {
        this.settings = settings;
        SyncSettings.Location location = settings.location();
        this.project = location.scheme + "://" + location.host + "/api/v4/projects/" + Http.encode(location.path, false);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("PRIVATE-TOKEN", settings.token);
        headers.put("User-Agent", "kbmd-android");
        this.http = new Http(headers);
    }

    @Override
    public String test() throws IOException {
        Snapshot snapshot = snapshot();
        if (snapshot.emptyRepository) {
            return "Connected. The repository is empty; the first sync will push the vault.";
        }
        return snapshot.head != null
                ? "Connected. Branch '" + settings.branch + "' found (" + snapshot.files.size() + " files)."
                : "Connected. Branch '" + settings.branch + "' does not exist yet and will be created from '" + snapshot.startBranch + "'.";
    }

    @Override
    public Snapshot snapshot() throws IOException {
        Snapshot snapshot = new Snapshot();
        JSONObject info = http.get(project, null).object();
        String defaultBranch = info.isNull("default_branch") ? null : info.optString("default_branch", null);
        if (info.optBoolean("empty_repo") || defaultBranch == null) {
            snapshot.emptyRepository = true;
            return snapshot;
        }
        snapshot.head = branchHead(settings.branch);
        if (snapshot.head == null) {
            snapshot.startBranch = defaultBranch;
            snapshot.startCommit = branchHead(defaultBranch);
            if (snapshot.startCommit == null) {
                snapshot.emptyRepository = true;
                return snapshot;
            }
        }
        String ref = snapshot.head != null ? snapshot.head : snapshot.startCommit;
        try {
            String page = "1";
            while (page != null && !page.isEmpty()) {
                Http.Reply reply = http.get(project + "/repository/tree?recursive=true&per_page=100&ref=" + ref + "&page=" + page, null);
                JSONArray entries = reply.array();
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject entry = entries.getJSONObject(i);
                    if ("blob".equals(entry.optString("type")) && !"120000".equals(entry.optString("mode"))) {
                        snapshot.files.put(entry.getString("path"), entry.getString("id"));
                    }
                }
                page = entries.length() == 0 ? null : reply.nextPage;
            }
        } catch (JSONException e) {
            throw new IOException("Unexpected answer from GitLab: " + e.getMessage());
        }
        return snapshot;
    }

    private String branchHead(String branch) throws IOException {
        try {
            return http.get(project + "/repository/branches/" + Http.encode(branch, false), null).object()
                    .getJSONObject("commit").getString("id");
        } catch (Http.ApiException e) {
            if (e.status == 404) {
                return null;
            }
            throw e;
        } catch (JSONException e) {
            throw new IOException("Unexpected answer from GitLab: " + e.getMessage());
        }
    }

    @Override
    public byte[] download(String path, String blobSha) throws IOException {
        return http.get(project + "/repository/blobs/" + blobSha + "/raw", null).body;
    }

    @Override
    public String push(Snapshot base, List<Upload> uploads, List<String> deletes, String message) throws IOException {
        try {
            // the commits API has no compare-and-swap: at least refuse to build on a head that moved meanwhile
            if (base.head != null && !base.head.equals(branchHead(settings.branch))) {
                throw new IOException("Push rejected: the remote changed during the sync. Sync again.");
            }
            // one entry per action: an upload, or null for a deletion (taken from deleted in order)
            List<Upload> pending = new ArrayList<>(uploads);
            List<Long> sizes = new ArrayList<>();
            for (Upload upload : pending) {
                sizes.add(upload.size());
            }
            List<String> deleted = new ArrayList<>();
            for (String path : deletes) {
                if (base.files.containsKey(path)) {
                    deleted.add(path);
                    pending.add(null);
                    sizes.add(0L);
                }
            }

            String head = base.head != null ? base.head : base.startCommit;
            boolean branchExists = base.head != null;
            int from = 0;
            int nextDelete = 0;
            while (from < pending.size()) {
                int to = from;
                long bytes = 0;
                while (to < pending.size() && to - from < MAX_COMMIT_ACTIONS && (to == from || bytes + sizes.get(to) <= MAX_COMMIT_BYTES)) {
                    bytes += sizes.get(to++);
                }
                // files are read one batch at a time, so a large vault does not have to fit in memory
                JSONArray actions = new JSONArray();
                for (int i = from; i < to; i++) {
                    Upload upload = pending.get(i);
                    if (upload == null) {
                        actions.put(new JSONObject().put("action", "delete").put("file_path", deleted.get(nextDelete++)));
                    } else {
                        actions.put(new JSONObject()
                                .put("action", base.files.containsKey(upload.path) ? "update" : "create")
                                .put("file_path", upload.path)
                                .put("encoding", "base64")
                                .put("content", Base64.getEncoder().encodeToString(upload.read())));
                    }
                }
                JSONObject commit = new JSONObject()
                        .put("branch", settings.branch)
                        .put("commit_message", message)
                        .put("author_name", settings.authorName)
                        .put("author_email", settings.authorEmail)
                        .put("actions", actions);
                if (!branchExists && base.startBranch != null) {
                    commit.put("start_branch", base.startBranch);
                }
                head = http.send("POST", project + "/repository/commits", commit, null).object().getString("id");
                branchExists = true;
                from = to;
            }
            return head;
        } catch (JSONException e) {
            throw new IOException("Unexpected answer from GitLab: " + e.getMessage());
        }
    }
}
