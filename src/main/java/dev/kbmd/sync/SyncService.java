package dev.kbmd.sync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.kbmd.index.NoteIndex;
import dev.kbmd.vault.VaultService;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Two-way sync of the vault with a GitHub or GitLab repository over HTTPS, authenticated with an access token.
 * The vault itself becomes a Git working tree: commit local changes, merge the remote branch, push.
 * When both sides edited the same file, the local version stays in place and the remote one is saved next
 * to it as "name (remote conflict date)", so nothing is lost and no conflict markers end up in notes.
 */
@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);
    private static final String GITIGNORE_ENTRIES = VaultService.META_DIR + "/\n" + VaultService.TRASH_DIR + "/\n";

    private final VaultService vault;
    private final NoteIndex index;
    private final JsonMapper json;
    private final Path settingsFile;
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile SyncSettings settings;
    private volatile SyncResult lastResult;
    private volatile Instant lastAttempt = Instant.now();

    public SyncService(VaultService vault, NoteIndex index, JsonMapper json) {
        this.vault = vault;
        this.index = index;
        this.json = json;
        this.settingsFile = vault.root().resolve(VaultService.META_DIR).resolve("sync.json");
        this.settings = load();
    }

    public SyncSettings settings() {
        return settings;
    }

    /** A blank token keeps the stored one, so the UI never needs to read it back. */
    public synchronized SyncSettings updateSettings(SyncSettings updated) {
        SyncSettings merged = updated.token().isEmpty() ? updated.withToken(settings.token()) : updated;
        try {
            Files.createDirectories(settingsFile.getParent());
            Files.writeString(settingsFile, json.writerWithDefaultPrettyPrinter().writeValueAsString(merged), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot store sync settings: " + e.getMessage());
        }
        settings = merged;
        return merged;
    }

    public SyncStatus status() {
        SyncSettings current = settings;
        int pending = -1;
        if (Files.isDirectory(vault.root().resolve(".git"))) {
            try (Git git = Git.open(vault.root().toFile())) {
                Status status = git.status().call();
                pending = status.getUncommittedChanges().size() + status.getUntracked().size();
            } catch (IOException | GitAPIException e) {
                log.debug("git status failed", e);
            }
        }
        return new SyncStatus(current.configured(), running.get(), pending, lastResult);
    }

    /** Checks that the repository is reachable with the stored token. */
    public SyncResult test() {
        SyncSettings current = requireConfigured();
        try {
            var refs = Git.lsRemoteRepository().setRemote(current.cloneUrl()).setHeads(true)
                    .setCredentialsProvider(credentials(current)).call();
            boolean hasBranch = refs.stream().anyMatch(ref -> ref.getName().equals(Constants.R_HEADS + current.branch()));
            return SyncResult.ok(refs.isEmpty()
                    ? "Connected. The repository is empty; the first sync will push the vault."
                    : hasBranch ? "Connected. Branch '" + current.branch() + "' found."
                    : "Connected. Branch '" + current.branch() + "' does not exist yet and will be created.", List.of());
        } catch (GitAPIException e) {
            return SyncResult.failed(describe(e));
        }
    }

    public SyncResult sync() {
        SyncSettings current = requireConfigured();
        if (!running.compareAndSet(false, true)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A sync is already running");
        }
        lastAttempt = Instant.now();
        SyncResult result;
        try {
            result = doSync(current);
        } catch (GitAPIException | IOException | RuntimeException e) {
            log.warn("Sync failed", e);
            result = SyncResult.failed(describe(e));
        } finally {
            running.set(false);
        }
        lastResult = result;
        index.rebuild();
        return result;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void autoSync() {
        SyncSettings current = settings;
        if (!current.configured() || current.autoSyncMinutes() <= 0 || running.get()) {
            return;
        }
        if (Duration.between(lastAttempt, Instant.now()).toMinutes() >= current.autoSyncMinutes()) {
            try {
                log.info("Auto sync: {}", sync().message());
            } catch (RuntimeException e) {
                log.warn("Auto sync skipped: {}", e.getMessage());
            }
        }
    }

    private SyncResult doSync(SyncSettings current) throws GitAPIException, IOException {
        CredentialsProvider credentials = credentials(current);
        String branchRef = Constants.R_HEADS + current.branch();
        String trackingRef = Constants.R_REMOTES + "origin/" + current.branch();
        List<String> conflicts = new ArrayList<>();
        List<String> steps = new ArrayList<>();

        try (Git git = openOrInit(current)) {
            Repository repository = git.getRepository();
            ensureGitignore();
            configureRemote(repository, current);

            if (repository.resolve(Constants.HEAD) == null) {
                // fresh repository: make sure the first commit lands on the configured branch
                repository.updateRef(Constants.HEAD).link(branchRef);
            }
            if (commitAll(git, current, "vault: " + timestamp())) {
                steps.add("committed local changes");
            }

            boolean remoteHasBranch = git.lsRemote().setRemote("origin").setHeads(true).setCredentialsProvider(credentials).call()
                    .stream().map(Ref::getName).anyMatch(branchRef::equals);
            if (remoteHasBranch) {
                git.fetch().setRemote("origin").setCredentialsProvider(credentials)
                        .setRefSpecs(new RefSpec("+" + Constants.R_HEADS + "*:" + Constants.R_REMOTES + "origin/*")).call();
                ObjectId remote = repository.resolve(trackingRef);
                ObjectId head = repository.resolve(Constants.HEAD);
                if (head == null) {
                    RefUpdate update = repository.updateRef(branchRef);
                    update.setNewObjectId(remote);
                    update.forceUpdate();
                    git.reset().setMode(ResetCommand.ResetType.HARD).call();
                    steps.add("downloaded the remote vault");
                } else {
                    merge(git, current, remote, conflicts, steps);
                }
            }

            if (repository.resolve(Constants.HEAD) == null) {
                return SyncResult.ok("Nothing to sync yet: the vault and the repository are both empty.", List.of());
            }
            push(git, current, credentials, steps);
        }

        String message = steps.isEmpty() ? "Already up to date." : "Synced: " + String.join(", ", steps) + ".";
        if (!conflicts.isEmpty()) {
            message += " " + conflicts.size() + " conflicting file(s): the remote versions were saved as separate copies.";
        }
        return SyncResult.ok(message, conflicts);
    }

    private void merge(Git git, SyncSettings current, ObjectId remote, List<String> conflicts, List<String> steps)
            throws GitAPIException, IOException {
        Repository repository = git.getRepository();
        RevCommit ours = repository.parseCommit(repository.resolve(Constants.HEAD));
        RevCommit theirs = repository.parseCommit(remote);

        MergeResult result = git.merge().include(theirs).setCommit(true)
                .setMessage("Merge remote changes").call();
        switch (result.getMergeStatus()) {
            case ALREADY_UP_TO_DATE -> {
            }
            case FAST_FORWARD, MERGED, MERGED_NOT_COMMITTED -> steps.add("merged remote changes");
            case CONFLICTING -> {
                for (String path : git.status().call().getConflicting()) {
                    resolveConflict(repository, path, ours, theirs);
                    conflicts.add(path);
                }
                commitAll(git, current, "Merge remote changes (kept both versions of conflicting files)");
                steps.add("merged remote changes");
            }
            default -> throw new IOException("Merge failed (" + result.getMergeStatus() + "): " + result);
        }
        if (result.getMergeStatus() == MergeResult.MergeStatus.MERGED_NOT_COMMITTED) {
            commitAll(git, current, "Merge remote changes");
        }
    }

    /** Keeps our version at {@code path} and stores theirs beside it; a one-sided delete keeps the surviving file. */
    private void resolveConflict(Repository repository, String path, RevCommit ours, RevCommit theirs) throws IOException {
        byte[] ourBytes = blob(repository, ours, path);
        byte[] theirBytes = blob(repository, theirs, path);
        Path file = vault.root().resolve(path);
        Files.createDirectories(file.getParent());
        if (ourBytes == null && theirBytes != null) {
            Files.write(file, theirBytes);
        } else if (ourBytes != null) {
            Files.write(file, ourBytes);
            if (theirBytes != null) {
                Files.write(conflictCopy(file), theirBytes);
            }
        }
    }

    private static byte[] blob(Repository repository, RevCommit commit, String path) throws IOException {
        try (TreeWalk walk = TreeWalk.forPath(repository, path, commit.getTree())) {
            return walk == null ? null : repository.open(walk.getObjectId(0)).getBytes();
        }
    }

    private static Path conflictCopy(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HHmmss"));
        return file.resolveSibling(base + " (remote conflict " + stamp + ")" + extension);
    }

    private void push(Git git, SyncSettings current, CredentialsProvider credentials, List<String> steps) throws GitAPIException, IOException {
        Repository repository = git.getRepository();
        ObjectId head = repository.resolve(Constants.HEAD);
        ObjectId tracking = repository.resolve(Constants.R_REMOTES + "origin/" + current.branch());
        if (head.equals(tracking)) {
            return;
        }
        Iterable<PushResult> results = git.push().setRemote("origin").setCredentialsProvider(credentials)
                .setRefSpecs(new RefSpec("HEAD:" + Constants.R_HEADS + current.branch())).call();
        for (PushResult result : results) {
            for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                switch (update.getStatus()) {
                    case OK, UP_TO_DATE -> {
                    }
                    case REJECTED_NONFASTFORWARD ->
                            throw new IOException("Push rejected: the remote changed during the sync. Sync again.");
                    default -> throw new IOException("Push failed: " + update.getStatus()
                            + (update.getMessage() == null ? "" : " - " + update.getMessage()));
                }
            }
        }
        steps.add("pushed to " + current.provider());
    }

    private boolean commitAll(Git git, SyncSettings current, String message) throws GitAPIException {
        git.add().addFilepattern(".").call();
        git.add().addFilepattern(".").setUpdate(true).call();
        Status status = git.status().call();
        boolean merging = git.getRepository().getRepositoryState() != org.eclipse.jgit.lib.RepositoryState.SAFE;
        if (status.isClean() && !merging) {
            return false;
        }
        PersonIdent author = new PersonIdent(current.authorName(), current.authorEmail());
        git.commit().setAuthor(author).setCommitter(author).setMessage(message).setSign(false).call();
        return true;
    }

    private Git openOrInit(SyncSettings current) throws IOException, GitAPIException {
        Path root = vault.root();
        if (Files.isDirectory(root.resolve(".git"))) {
            return Git.open(root.toFile());
        }
        return Git.init().setDirectory(root.toFile()).setInitialBranch(current.branch()).call();
    }

    private static void configureRemote(Repository repository, SyncSettings current) throws IOException {
        StoredConfig config = repository.getConfig();
        config.setString("remote", "origin", "url", current.cloneUrl());
        config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
        config.setBoolean("core", null, "autocrlf", false);
        config.save();
    }

    /** Tokens and the trash never leave this machine. */
    private void ensureGitignore() throws IOException {
        Path gitignore = vault.root().resolve(".gitignore");
        String existing = Files.exists(gitignore) ? Files.readString(gitignore, StandardCharsets.UTF_8) : "";
        Set<String> lines = Set.copyOf(existing.lines().map(String::strip).toList());
        StringBuilder missing = new StringBuilder();
        for (String entry : GITIGNORE_ENTRIES.split("\n")) {
            if (!lines.contains(entry)) {
                missing.append(entry).append('\n');
            }
        }
        if (!missing.isEmpty()) {
            String separator = existing.isEmpty() || existing.endsWith("\n") ? "" : "\n";
            Files.writeString(gitignore, existing + separator + missing, StandardCharsets.UTF_8);
        }
    }

    private SyncSettings requireConfigured() {
        SyncSettings current = settings;
        if (!current.configured()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Set the repository URL and access token first");
        }
        return current;
    }

    private static CredentialsProvider credentials(SyncSettings current) {
        return new UsernamePasswordCredentialsProvider(current.tokenUser(), current.token());
    }

    private static String describe(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String lower = message.toLowerCase();
        if (lower.contains("not authorized") || lower.contains("authentication")) {
            return "Authentication failed: check the access token and its repository permissions. (" + message + ")";
        }
        if (lower.contains("not found")) {
            return "Repository not found: check the URL, and that the token can access it. (" + message + ")";
        }
        return message;
    }

    private static String timestamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private SyncSettings load() {
        if (Files.isRegularFile(settingsFile)) {
            try {
                return json.readValue(Files.readString(settingsFile, StandardCharsets.UTF_8), SyncSettings.class);
            } catch (IOException | RuntimeException e) {
                log.warn("Ignoring unreadable {}: {}", settingsFile, e.getMessage());
            }
        }
        return SyncSettings.empty();
    }

    public record SyncResult(boolean ok, String message, List<String> conflicts, Instant time) {

        static SyncResult ok(String message, List<String> conflicts) {
            return new SyncResult(true, message, List.copyOf(conflicts), Instant.now());
        }

        static SyncResult failed(String message) {
            return new SyncResult(false, message, List.of(), Instant.now());
        }
    }

    public record SyncStatus(boolean configured, boolean running, int pendingChanges, SyncResult lastResult) {
    }
}
