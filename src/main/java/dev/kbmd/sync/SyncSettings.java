package dev.kbmd.sync;

import java.util.Locale;

/**
 * @param provider        {@code github} or {@code gitlab}
 * @param remoteUrl       HTTPS clone URL, or the {@code owner/repo} shorthand
 * @param token           personal access token (GitHub: repo contents read/write; GitLab: write_repository)
 * @param autoSyncMinutes 0 disables automatic sync
 */
public record SyncSettings(String provider, String remoteUrl, String token, String branch,
                           String authorName, String authorEmail, int autoSyncMinutes) {

    public SyncSettings {
        provider = provider == null || provider.isBlank() ? "github" : provider.toLowerCase(Locale.ROOT);
        remoteUrl = remoteUrl == null ? "" : remoteUrl.strip();
        token = token == null ? "" : token.strip();
        branch = branch == null || branch.isBlank() ? "main" : branch.strip();
        authorName = authorName == null || authorName.isBlank() ? "kbmd" : authorName.strip();
        authorEmail = authorEmail == null || authorEmail.isBlank() ? "kbmd@localhost" : authorEmail.strip();
        autoSyncMinutes = Math.max(0, autoSyncMinutes);
    }

    public static SyncSettings empty() {
        return new SyncSettings(null, null, null, null, null, null, 0);
    }

    public boolean configured() {
        return !remoteUrl.isEmpty() && !token.isEmpty();
    }

    /** Expands {@code owner/repo} to a full URL on the provider's public host. */
    public String cloneUrl() {
        if (remoteUrl.contains("://")) {
            return remoteUrl;
        }
        String host = provider.equals("gitlab") ? "https://gitlab.com/" : "https://github.com/";
        String repo = remoteUrl.endsWith(".git") ? remoteUrl : remoteUrl + ".git";
        return host + repo;
    }

    /** Both providers accept the token as the HTTPS password; only the expected user name differs. */
    public String tokenUser() {
        return provider.equals("gitlab") ? "oauth2" : "x-access-token";
    }

    public SyncSettings withToken(String newToken) {
        return new SyncSettings(provider, remoteUrl, newToken, branch, authorName, authorEmail, autoSyncMinutes);
    }
}
