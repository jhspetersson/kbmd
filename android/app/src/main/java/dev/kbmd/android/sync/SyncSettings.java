package dev.kbmd.android.sync;

import java.util.Locale;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Same fields as the web app's sync settings: provider ({@code github} or {@code gitlab}), repository
 * ({@code owner/repo} or an HTTPS URL), access token, branch, commit author and the auto sync interval (0 = off).
 */
public final class SyncSettings {

    public final String provider;
    public final String remoteUrl;
    public final String token;
    public final String branch;
    public final String authorName;
    public final String authorEmail;
    public final int autoSyncMinutes;

    public SyncSettings(String provider, String remoteUrl, String token, String branch,
                        String authorName, String authorEmail, int autoSyncMinutes) {
        this.provider = blank(provider) ? "github" : provider.trim().toLowerCase(Locale.ROOT);
        this.remoteUrl = remoteUrl == null ? "" : remoteUrl.trim();
        this.token = token == null ? "" : token.trim();
        this.branch = blank(branch) ? "main" : branch.trim();
        this.authorName = blank(authorName) ? "kbmd" : authorName.trim();
        this.authorEmail = blank(authorEmail) ? "kbmd@localhost" : authorEmail.trim();
        this.autoSyncMinutes = Math.max(0, autoSyncMinutes);
    }

    public static SyncSettings empty() {
        return new SyncSettings(null, null, null, null, null, null, 0);
    }

    public static SyncSettings fromJson(JSONObject json) {
        return new SyncSettings(text(json, "provider"), text(json, "remoteUrl"), text(json, "token"), text(json, "branch"),
                text(json, "authorName"), text(json, "authorEmail"), json.optInt("autoSyncMinutes", 0));
    }

    /** {@code withToken} is false for the copy shown to the UI: the token itself is never sent back. */
    public JSONObject toJson(boolean withToken) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("provider", provider);
        json.put("remoteUrl", remoteUrl);
        if (withToken) {
            json.put("token", token);
        } else {
            json.put("tokenSet", !token.isEmpty());
        }
        json.put("branch", branch);
        json.put("authorName", authorName);
        json.put("authorEmail", authorEmail);
        json.put("autoSyncMinutes", autoSyncMinutes);
        return json;
    }

    public boolean configured() {
        return !remoteUrl.isEmpty() && !token.isEmpty();
    }

    public boolean gitlab() {
        return provider.equals("gitlab");
    }

    public SyncSettings withToken(String newToken) {
        return new SyncSettings(provider, remoteUrl, newToken, branch, authorName, authorEmail, autoSyncMinutes);
    }

    /** Identifies the remote side; when it changes, what was synced before no longer says anything. */
    public String remoteKey() {
        Location location = location();
        return provider + "|" + location.host + "|" + location.path.toLowerCase(Locale.ROOT) + "|" + branch;
    }

    /** Splits {@code owner/repo}, {@code host/owner/repo} or a clone URL into the server and the repository path. */
    public Location location() {
        String url = remoteUrl;
        String scheme = "https";
        int schemeEnd = url.indexOf("://");
        String host = null;
        if (schemeEnd > 0) {
            scheme = url.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
            url = url.substring(schemeEnd + 3);
            int at = url.indexOf('@');
            int slash = url.indexOf('/');
            if (at >= 0 && (slash < 0 || at < slash)) {
                url = url.substring(at + 1); // credentials in a pasted clone URL are not used
            }
        }
        String first = url.contains("/") ? url.substring(0, url.indexOf('/')) : url;
        if (schemeEnd > 0 || (first.contains(".") && url.indexOf('/') != url.lastIndexOf('/'))) {
            host = first.toLowerCase(Locale.ROOT);
            url = url.contains("/") ? url.substring(url.indexOf('/') + 1) : "";
        }
        String path = url.replaceAll("[?#].*$", "").replaceAll("/+$", "").replaceAll("\\.git$", "").replaceAll("^/+", "");
        if (host == null) {
            host = gitlab() ? "gitlab.com" : "github.com";
        }
        return new Location(scheme, host, path);
    }

    public static final class Location {
        public final String scheme;
        public final String host;
        public final String path;

        Location(String scheme, String host, String path) {
            this.scheme = scheme;
            this.host = host;
            this.path = path;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String text(JSONObject json, String key) {
        return json.isNull(key) ? null : json.optString(key, null);
    }
}
