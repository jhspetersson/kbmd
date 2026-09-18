package dev.kbmd.android.sync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import dev.kbmd.android.vault.VaultService;

/**
 * Two-way sync of the vault folder with a remote branch, without a local Git repository.
 * <p>
 * Every file is compared three ways by its Git blob id: the local file, the remote file, and what both were after
 * the previous sync. One-sided changes are copied across; remote deletions move the local file to the trash.
 * When both sides edited the same file, the local version stays in place and the remote one is saved next to it
 * as "name (remote conflict date)", so nothing is lost and no conflict markers end up in notes. That is the same
 * outcome the web app produces with a Git merge, and both can sync with the same repository.
 */
final class SyncEngine {

    /** Provider limits are around 100 MB per file; stay well below, base64 has to fit in memory. */
    static final long MAX_FILE_BYTES = 50L * 1024 * 1024;
    private static final Set<String> IGNORED_DIRS = new HashSet<>(Arrays.asList(".git", VaultService.META_DIR, VaultService.TRASH_DIR));
    private static final String GITIGNORE_ENTRIES = VaultService.META_DIR + "/\n" + VaultService.TRASH_DIR + "/\n";

    static final class Outcome {
        final List<String> steps = new ArrayList<>();
        final List<String> conflicts = new ArrayList<>();
        final List<String> skipped = new ArrayList<>();
    }

    private static final class LocalFile {
        final String sha;
        final long size;
        final long modified;

        LocalFile(String sha, long size, long modified) {
            this.sha = sha;
            this.size = size;
            this.modified = modified;
        }
    }

    private final VaultService vault;
    private final Path stateFile;
    /** Hashes of files that changed since the last sync, so the status poll does not hash them every time. */
    private final Map<String, LocalFile> hashCache = new ConcurrentHashMap<>();

    SyncEngine(VaultService vault, Path stateFile) {
        this.vault = vault;
        this.stateFile = stateFile;
    }

    /** Files that differ from the last synced state; -1 before the first sync. */
    int pendingChanges(SyncSettings settings) {
        SyncState state = SyncState.load(stateFile);
        if (state.head == null || !state.remoteKey.equals(settings.remoteKey())) {
            return -1;
        }
        try {
            Map<String, LocalFile> local = scan(state, new ArrayList<>());
            int pending = 0;
            Set<String> paths = new TreeSet<>(local.keySet());
            paths.addAll(state.files.keySet());
            for (String path : paths) {
                LocalFile file = local.get(path);
                SyncState.FileState base = state.files.get(path);
                if (!Objects.equals(file == null ? null : file.sha, base == null ? null : base.sha)) {
                    pending++;
                }
            }
            return pending;
        } catch (IOException e) {
            return -1;
        }
    }

    Outcome sync(RemoteRepo remote, SyncSettings settings) throws IOException {
        Outcome outcome = new Outcome();
        ensureGitignore();

        SyncState state = SyncState.load(stateFile);
        if (!state.remoteKey.equals(settings.remoteKey())) {
            state = new SyncState(); // another repository or branch: nothing in common yet
            state.remoteKey = settings.remoteKey();
        }
        // the remote first: an autosave during that round trip then still counts as a local change
        RemoteRepo.Snapshot snapshot = remote.snapshot();
        Map<String, LocalFile> local = scan(state, outcome.skipped);

        SyncState next = state.copy();
        List<String> uploads = new ArrayList<>();
        List<String> deletes = new ArrayList<>();
        int downloaded = 0;
        int removed = 0;

        Set<String> paths = new TreeSet<>(local.keySet());
        paths.addAll(snapshot.files.keySet());
        paths.addAll(state.files.keySet());
        for (String path : paths) {
            if (ignored(path) || outcome.skipped.contains(path)) {
                continue;
            }
            LocalFile file = local.get(path);
            String mine = file == null ? null : file.sha;
            String theirs = snapshot.files.get(path);
            String base = state.files.containsKey(path) ? state.files.get(path).sha : null;

            if (Objects.equals(mine, theirs)) {
                remember(next, path, file);
            } else if (Objects.equals(mine, base) || (mine == null && !Objects.equals(theirs, base))) {
                // only the remote side changed (or we deleted what they edited: the surviving file wins)
                if (theirs == null) {
                    Path target = vault.root().resolve(path);
                    if (Files.exists(target)) {
                        vault.moveToTrash(target);
                        removeEmptyParents(target);
                    }
                    next.files.remove(path);
                    removed++;
                } else if (fetch(remote, path, theirs, vault.root().resolve(path), outcome)) {
                    remember(next, path, stat(path, theirs));
                    downloaded++;
                }
            } else if (Objects.equals(theirs, base)) {
                // only the local side changed
                if (mine == null) {
                    deletes.add(path);
                } else {
                    uploads.add(path);
                }
            } else if (theirs == null) {
                uploads.add(path); // they deleted what we edited: the surviving file wins
            } else {
                // both sides edited the file: keep ours in place, theirs beside it
                Path copy = conflictCopy(vault.root().resolve(path));
                if (fetch(remote, path, theirs, copy, outcome)) {
                    outcome.conflicts.add(path);
                    uploads.add(path);
                    uploads.add(vault.root().relativize(copy).toString().replace('\\', '/'));
                    // their version has been taken in; from here on this is a plain local change, and that is
                    // recorded right away so a failure later in this sync cannot produce a second copy
                    next.files.put(path, new SyncState.FileState(theirs, -1, -1));
                    next.head = state.head;
                    next.save(stateFile);
                }
            }
        }
        if (downloaded > 0) {
            outcome.steps.add("downloaded " + downloaded + " file(s)");
        }
        if (removed > 0) {
            outcome.steps.add("moved " + removed + " remotely deleted file(s) to the trash");
        }

        // what came down is safe now, whatever happens to the upload
        next.head = snapshot.head != null ? snapshot.head : state.head;
        next.save(stateFile);

        if (!uploads.isEmpty() || !deletes.isEmpty()) {
            List<RemoteRepo.Upload> contents = new ArrayList<>();
            Map<String, LocalFile> pushed = new TreeMap<>();
            for (String path : uploads) {
                Path source = vault.root().resolve(path);
                contents.add(new RemoteRepo.Upload(path, source)); // read by the provider, one at a time
                pushed.put(path, new LocalFile(null, Files.size(source), Files.getLastModifiedTime(source).toMillis()));
            }
            next.head = remote.push(snapshot, contents, deletes, "vault: " + timestamp("yyyy-MM-dd HH:mm:ss") + " (phone)");
            for (RemoteRepo.Upload upload : contents) {
                // the id of what was actually sent; an edit in between is caught by the size/time check next time
                LocalFile stat = pushed.get(upload.path);
                String sha = upload.sha != null ? upload.sha : local.get(upload.path).sha;
                remember(next, upload.path, new LocalFile(sha, stat.size, stat.modified));
            }
            for (String path : deletes) {
                next.files.remove(path);
            }
            next.save(stateFile);
            outcome.steps.add("pushed " + (uploads.size() + deletes.size()) + " change(s) to " + settings.provider);
        } else if (next.head == null && snapshot.startCommit != null) {
            // nothing to push to a branch that does not exist yet: remember what it will start from
            next.head = snapshot.startCommit;
            next.save(stateFile);
        }
        hashCache.clear();
        return outcome;
    }

    private static void remember(SyncState state, String path, LocalFile file) {
        if (file == null) {
            state.files.remove(path);
        } else {
            state.files.put(path, new SyncState.FileState(file.sha, file.size, file.modified));
        }
    }

    private LocalFile stat(String path, String sha) throws IOException {
        Path file = vault.root().resolve(path);
        return new LocalFile(sha, Files.size(file), Files.getLastModifiedTime(file).toMillis());
    }

    /** Downloads into {@code target}; a file this device cannot store is reported and left for the next sync. */
    private boolean fetch(RemoteRepo remote, String path, String sha, Path target, Outcome outcome) throws IOException {
        byte[] content = remote.download(path, sha);
        try {
            if (!target.normalize().startsWith(vault.root())) {
                throw new IOException("outside the vault");
            }
            Files.createDirectories(target.getParent());
            // written next to the target and moved into place: a process killed mid-write leaves no truncated note
            Path temp = target.resolveSibling("." + target.getFileName() + ".part");
            Files.write(temp, content);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException | UnsupportedOperationException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            outcome.skipped.add(path);
            return false;
        }
    }

    /** Every file that takes part in the sync, keyed by vault path. Hidden files do, the trash and app data do not. */
    private Map<String, LocalFile> scan(SyncState state, List<String> skipped) throws IOException {
        Map<String, LocalFile> local = new TreeMap<>();
        Path root = vault.root();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return !dir.equals(root) && IGNORED_DIRS.contains(dir.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                String path = root.relativize(file).toString().replace('\\', '/');
                if (attrs.size() > MAX_FILE_BYTES) {
                    skipped.add(path);
                    return FileVisitResult.CONTINUE;
                }
                local.put(path, hash(file, path, attrs.size(), attrs.lastModifiedTime().toMillis(), state));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });
        hashCache.keySet().retainAll(local.keySet());
        return local;
    }

    private LocalFile hash(Path file, String path, long size, long modified, SyncState state) throws IOException {
        SyncState.FileState known = state.files.get(path);
        // a file written in the same moment as the state cannot be told apart by its timestamp
        if (known != null && known.size == size && known.modified == modified && modified < state.savedAt - 2000) {
            return new LocalFile(known.sha, size, modified);
        }
        LocalFile cached = hashCache.get(path);
        if (cached != null && cached.size == size && cached.modified == modified && modified < System.currentTimeMillis() - 2000) {
            return cached;
        }
        LocalFile hashed = new LocalFile(GitHash.blob(Files.readAllBytes(file)), size, modified);
        hashCache.put(path, hashed);
        return hashed;
    }

    private static boolean ignored(String path) {
        for (String segment : path.split("/")) {
            if (IGNORED_DIRS.contains(segment)) {
                return true;
            }
        }
        return false;
    }

    private void removeEmptyParents(Path file) {
        Path dir = file.getParent();
        try {
            while (dir != null && !dir.equals(vault.root()) && dir.startsWith(vault.root())) {
                try (java.util.stream.Stream<Path> entries = Files.list(dir)) {
                    if (entries.findAny().isPresent()) {
                        return;
                    }
                }
                Files.delete(dir);
                dir = dir.getParent();
            }
        } catch (IOException e) {
            // an empty folder left behind is harmless
        }
    }

    private static Path conflictCopy(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        return VaultService.uniqueSibling(file.getParent(), base + " (remote conflict " + timestamp("yyyy-MM-dd HHmmss") + ")" + extension);
    }

    /** The same entries the web app writes, so a desktop clone of the repository ignores the same folders. */
    private void ensureGitignore() throws IOException {
        Path gitignore = vault.root().resolve(".gitignore");
        String existing = Files.exists(gitignore) ? new String(Files.readAllBytes(gitignore), StandardCharsets.UTF_8) : "";
        Set<String> lines = new HashSet<>();
        for (String line : existing.split("\\R")) {
            lines.add(line.trim());
        }
        StringBuilder missing = new StringBuilder();
        for (String entry : GITIGNORE_ENTRIES.split("\n")) {
            if (!lines.contains(entry)) {
                missing.append(entry).append('\n');
            }
        }
        if (missing.length() > 0) {
            String separator = existing.isEmpty() || existing.endsWith("\n") ? "" : "\n";
            Files.write(gitignore, (existing + separator + missing).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String timestamp(String pattern) {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern(pattern));
    }
}
