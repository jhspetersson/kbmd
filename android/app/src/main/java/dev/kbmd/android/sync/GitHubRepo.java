package dev.kbmd.android.sync;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * GitHub through the Git Database API: blobs, trees, commits and refs. A sync becomes one commit, however many
 * files it touches. Works with github.com and with GitHub Enterprise ({@code https://host/api/v3}).
 */
final class GitHubRepo implements RemoteRepo {

    private static final String JSON_TYPE = "application/vnd.github+json";
    private static final String RAW_TYPE = "application/vnd.github.raw+json";

    private final SyncSettings settings;
    private final Http http;
    private final String repo;

    GitHubRepo(SyncSettings settings) {
        this.settings = settings;
        SyncSettings.Location location = settings.location();
        String api = location.host.equals("github.com") || location.host.equals("www.github.com")
                ? "https://api.github.com"
                : location.scheme + "://" + location.host + "/api/v3";
        this.repo = api + "/repos/" + Http.encode(location.path, true);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + settings.token);
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        headers.put("User-Agent", "kbmd-android");
        this.http = new Http(headers);
    }

    @Override
    public String test() throws IOException {
        JSONObject info = http.get(repo, JSON_TYPE).object();
        JSONObject permissions = info.optJSONObject("permissions");
        if (permissions != null && !permissions.optBoolean("push", true)) {
            return "Connected, but the token may only read this repository: syncing needs \"Contents: Read and write\".";
        }
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
        String commit = branchHead(settings.branch, snapshot);
        if (snapshot.emptyRepository) {
            return snapshot;
        }
        if (commit == null) {
            // the branch is missing: it will start from the default branch
            snapshot.startBranch = http.get(repo, JSON_TYPE).object().optString("default_branch", "main");
            snapshot.startCommit = branchHead(snapshot.startBranch, snapshot);
            if (snapshot.startCommit == null) {
                snapshot.emptyRepository = true;
                return snapshot;
            }
        } else {
            snapshot.head = commit;
        }
        try {
            String treeOf = commit != null ? commit : snapshot.startCommit;
            snapshot.tree = http.get(repo + "/git/commits/" + treeOf, JSON_TYPE).object().getJSONObject("tree").getString("sha");
            JSONObject tree = http.get(repo + "/git/trees/" + snapshot.tree + "?recursive=1", JSON_TYPE).object();
            if (tree.optBoolean("truncated")) {
                throw new IOException("The repository has too many files for GitHub's tree API.");
            }
            JSONArray entries = tree.getJSONArray("tree");
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                if ("blob".equals(entry.optString("type")) && !"120000".equals(entry.optString("mode"))) {
                    snapshot.files.put(entry.getString("path"), entry.getString("sha"));
                    snapshot.modes.put(entry.getString("path"), entry.optString("mode", "100644"));
                }
            }
        } catch (JSONException e) {
            throw new IOException("Unexpected answer from GitHub: " + e.getMessage());
        }
        return snapshot;
    }

    /** Head commit of a branch; null when it does not exist. Flags an empty repository on the snapshot. */
    private String branchHead(String branch, Snapshot snapshot) throws IOException {
        try {
            JSONObject ref = http.get(repo + "/git/ref/heads/" + Http.encode(branch, true), JSON_TYPE).object();
            return ref.getJSONObject("object").getString("sha");
        } catch (Http.ApiException e) {
            if (e.status == 409) {
                snapshot.emptyRepository = true;
                return null;
            }
            if (e.status == 404) {
                http.get(repo, JSON_TYPE); // tells a missing branch from a missing repository
                return null;
            }
            throw e;
        } catch (JSONException e) {
            throw new IOException("Unexpected answer from GitHub: " + e.getMessage());
        }
    }

    @Override
    public byte[] download(String path, String blobSha) throws IOException {
        return http.get(repo + "/git/blobs/" + blobSha, RAW_TYPE).body;
    }

    @Override
    public String push(Snapshot base, List<Upload> uploads, List<String> deletes, String message) throws IOException {
        try {
            if (base.emptyRepository) {
                base = initialise(uploads);
            }
            String parent = base.head != null ? base.head : base.startCommit;

            JSONArray tree = new JSONArray();
            for (Upload upload : uploads) {
                byte[] content = upload.read();
                if (upload.sha.equals(base.files.get(upload.path))) {
                    continue; // the file that initialised an empty repository
                }
                JSONObject blob = new JSONObject();
                blob.put("content", Base64.getEncoder().encodeToString(content));
                blob.put("encoding", "base64");
                String sha = http.send("POST", repo + "/git/blobs", blob, JSON_TYPE).object().getString("sha");
                tree.put(new JSONObject().put("path", upload.path).put("type", "blob").put("sha", sha)
                        .put("mode", base.modes.containsKey(upload.path) ? base.modes.get(upload.path) : "100644"));
            }
            for (String path : deletes) {
                if (base.files.containsKey(path)) {
                    tree.put(new JSONObject().put("path", path).put("type", "blob").put("sha", JSONObject.NULL)
                            .put("mode", base.modes.containsKey(path) ? base.modes.get(path) : "100644"));
                }
            }
            if (tree.length() == 0) {
                return parent;
            }

            JSONObject newTree = new JSONObject().put("tree", tree);
            if (base.tree != null) {
                newTree.put("base_tree", base.tree);
            }
            String treeSha = http.send("POST", repo + "/git/trees", newTree, JSON_TYPE).object().getString("sha");

            JSONObject author = new JSONObject().put("name", settings.authorName).put("email", settings.authorEmail)
                    .put("date", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
            JSONObject commit = new JSONObject().put("message", message).put("tree", treeSha).put("author", author);
            commit.put("parents", parent == null ? new JSONArray() : new JSONArray().put(parent));
            String commitSha = http.send("POST", repo + "/git/commits", commit, JSON_TYPE).object().getString("sha");

            if (base.head == null) {
                http.send("POST", repo + "/git/refs",
                        new JSONObject().put("ref", "refs/heads/" + settings.branch).put("sha", commitSha), JSON_TYPE);
            } else {
                try {
                    http.send("PATCH", repo + "/git/refs/heads/" + Http.encode(settings.branch, true),
                            new JSONObject().put("sha", commitSha).put("force", false), JSON_TYPE);
                } catch (Http.ApiException e) {
                    if (e.status == 422) {
                        throw new IOException("Push rejected: the remote changed during the sync. Sync again.");
                    }
                    throw e;
                }
            }
            return commitSha;
        } catch (JSONException e) {
            throw new IOException("Unexpected answer from GitHub: " + e.getMessage());
        }
    }

    /**
     * The Git Database API refuses to work on a repository without commits, so the first file goes through
     * the Contents API, which creates the initial commit.
     */
    private Snapshot initialise(List<Upload> uploads) throws IOException, JSONException {
        if (uploads.isEmpty()) {
            throw new IOException("Nothing to push to the empty repository.");
        }
        Upload first = uploads.get(0);
        for (Upload upload : uploads) {
            if (upload.path.equals(".gitignore")) {
                first = upload;
            }
        }
        JSONObject body = new JSONObject()
                .put("message", "Initial commit")
                .put("content", Base64.getEncoder().encodeToString(first.read()))
                .put("branch", settings.branch)
                .put("committer", new JSONObject().put("name", settings.authorName).put("email", settings.authorEmail));
        http.send("PUT", repo + "/contents/" + Http.encode(first.path, true), body, JSON_TYPE);
        Snapshot created = snapshot();
        if (created.emptyRepository) {
            throw new IOException("GitHub did not create the first commit. Add a README to the repository and sync again.");
        }
        return created;
    }
}
