package dev.kbmd.android.vault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import dev.kbmd.android.server.HttpError;

/**
 * File operations on the vault folder. Every path coming from the UI goes through
 * {@link #resolve(String)}, which keeps it inside the vault and away from hidden folders.
 */
public class VaultService {

    public static final String TRASH_DIR = ".trash";
    public static final String META_DIR = ".kbmd";

    private static final Set<String> IMAGE_EXTENSIONS =
            new HashSet<>(Arrays.asList("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "avif"));

    private final Path root;
    private final String attachmentsDir;

    public VaultService(Path vault, String attachmentsDir, byte[] welcomeNote) throws IOException {
        Files.createDirectories(vault);
        // the real path, so that paths compare equal even when the storage folder is reached through a symlink
        this.root = vault.toRealPath();
        this.attachmentsDir = attachmentsDir;
        seedIfEmpty(welcomeNote);
    }

    public Path root() {
        return root;
    }

    public Path resolve(String relative) {
        if (relative == null) {
            throw HttpError.badRequest("Path is required");
        }
        String cleaned = relative.replace('\\', '/').trim();
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        Path resolved;
        try {
            resolved = root.resolve(cleaned).normalize();
        } catch (RuntimeException e) {
            throw HttpError.badRequest("Invalid path: " + relative);
        }
        checkInside(resolved, relative);
        // a symlink (or, on other file systems, an alias) must not lead out of the vault or into a hidden folder
        Path real = realPath(resolved);
        if (!real.equals(resolved)) {
            checkInside(real, relative);
        }
        return resolved;
    }

    private void checkInside(Path path, String relative) {
        if (!path.startsWith(root)) {
            throw HttpError.badRequest("Path escapes the vault: " + relative);
        }
        for (Path segment : root.relativize(path)) {
            if (segment.toString().startsWith(".")) {
                throw HttpError.badRequest("Hidden paths are not accessible: " + relative);
            }
        }
    }

    /** The canonical form of {@code path}: its deepest existing ancestor resolved, the rest appended as given. */
    private static Path realPath(Path path) {
        Path existing = path;
        Path rest = null;
        while (existing != null && !Files.exists(existing)) {
            Path name = existing.getFileName();
            rest = rest == null ? name : name.resolve(rest);
            existing = existing.getParent();
        }
        if (existing == null) {
            return path;
        }
        try {
            Path real = existing.toRealPath();
            return rest == null ? real : real.resolve(rest);
        } catch (IOException e) {
            return path;
        }
    }

    public String relativize(Path absolute) {
        return root.relativize(absolute).toString().replace('\\', '/');
    }

    public static boolean isNote(String path) {
        return path.toLowerCase(Locale.ROOT).endsWith(".md");
    }

    public static boolean isDrawing(String path) {
        return path.toLowerCase(Locale.ROOT).endsWith(".excalidraw");
    }

    public static boolean isImage(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 && IMAGE_EXTENSIONS.contains(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** All visible files, as vault-relative paths with forward slashes. */
    public List<String> listFiles() {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(this::isVisible)
                    .map(this::relativize)
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public TreeNode tree() {
        return buildTree(root);
    }

    private TreeNode buildTree(Path dir) {
        List<TreeNode> children = new ArrayList<>();
        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted(Comparator
                            .comparing((Path p) -> !Files.isDirectory(p))
                            .thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .forEach(p -> {
                        if (Files.isDirectory(p)) {
                            children.add(buildTree(p));
                        } else {
                            String rel = relativize(p);
                            String type = isNote(rel) ? "note" : isDrawing(rel) ? "drawing" : isImage(rel) ? "image" : "file";
                            children.add(new TreeNode(p.getFileName().toString(), rel, type, null));
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String name = dir.equals(root) ? "" : dir.getFileName().toString();
        return new TreeNode(name, dir.equals(root) ? "" : relativize(dir), "folder", children);
    }

    public String read(String path) {
        Path file = existingFile(path);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(Files.readAllBytes(file))).toString();
        } catch (IOException e) {
            throw new HttpError(422, "Not a readable text file: " + path);
        }
    }

    public long lastModified(String path) {
        try {
            return Files.getLastModifiedTime(resolve(path)).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    public Path existingFile(String path) {
        Path file = resolve(path);
        if (!Files.isRegularFile(file)) {
            throw HttpError.notFound("No such file: " + path);
        }
        return file;
    }

    public boolean exists(String path) {
        return Files.exists(resolve(path));
    }

    public void write(String path, String content) {
        Path file = resolve(path);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void create(String path, String content) {
        if (exists(path)) {
            throw HttpError.conflict("Already exists: " + path);
        }
        write(path, content == null ? "" : content);
    }

    public void createFolder(String path) {
        try {
            Files.createDirectories(resolve(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean isDirectory(String path) {
        return Files.isDirectory(resolve(path));
    }

    public void move(String from, String to) {
        Path source = resolve(from);
        Path target = resolve(to);
        if (!Files.exists(source)) {
            throw HttpError.notFound("No such entry: " + from);
        }
        if (source.equals(root)) {
            throw HttpError.badRequest("Cannot move the vault root");
        }
        if (target.startsWith(source) && !target.equals(source)) {
            throw HttpError.badRequest("Cannot move a folder into itself");
        }
        try {
            Files.createDirectories(target.getParent());
            if (Files.exists(target) && Files.isSameFile(source, target)) {
                // only the case changed ("Notes" -> "notes"): phone storage is case-insensitive, so Files.move
                // sees the same file and does nothing; go through a temporary name
                if (!source.toString().equals(target.toString())) {
                    Path temp = uniqueSibling(source.getParent(), "." + source.getFileName() + ".renaming");
                    Files.move(source, temp);
                    Files.move(temp, target);
                }
            } else {
                Files.move(source, target);
            }
        } catch (FileAlreadyExistsException e) {
            throw HttpError.conflict("Already exists: " + to);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Deleting moves the entry to the vault's .trash folder, so nothing is lost by a mistap. */
    public void trash(String path) {
        Path source = resolve(path);
        if (!Files.exists(source) || source.equals(root)) {
            throw HttpError.notFound("No such entry: " + path);
        }
        moveToTrash(source);
    }

    /** Also used by sync, for files that were deleted on the remote side. */
    public void moveToTrash(Path source) {
        try {
            Path trash = root.resolve(TRASH_DIR);
            Files.createDirectories(trash);
            Files.move(source, uniqueSibling(trash, source.getFileName().toString()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Stores an uploaded file in the attachments folder (or {@code folder}) and returns its vault path. */
    public String upload(String originalName, String folder, InputStream data) {
        String name = sanitizeFileName(originalName);
        Path dir = resolve(folder == null || folder.trim().isEmpty() ? attachmentsDir : folder);
        try {
            Files.createDirectories(dir);
            Path target = uniqueSibling(dir, name);
            Files.copy(data, target);
            return relativize(target);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Image paths directly inside a folder, sorted by name. */
    public List<String> imagesIn(String folder) {
        Path dir = resolve(folder);
        if (!Files.isDirectory(dir)) {
            return Collections.emptyList();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(Files::isRegularFile).map(this::relativize).filter(VaultService::isImage)
                    .sorted().collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void exportZip(OutputStream out) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (String path : listFiles()) {
                Path file = root.resolve(path);
                ZipEntry entry = new ZipEntry(path);
                entry.setTime(Files.getLastModifiedTime(file).toMillis());
                zip.putNextEntry(entry);
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
    }

    private boolean isVisible(Path file) {
        for (Path segment : root.relativize(file)) {
            if (segment.toString().startsWith(".")) {
                return false;
            }
        }
        return true;
    }

    public static Path uniqueSibling(Path dir, String name) {
        Path candidate = dir.resolve(name);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; Files.exists(candidate); i++) {
            candidate = dir.resolve(base + " " + i + ext);
        }
        return candidate;
    }

    public static String sanitizeFileName(String original) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[<>:\"|?*\\[\\]#^\\p{Cntrl}]", "_").trim();
        while (name.startsWith(".")) {
            name = name.substring(1);
        }
        return name.isEmpty() ? "upload" : name;
    }

    private void seedIfEmpty(byte[] welcomeNote) throws IOException {
        try (Stream<Path> entries = Files.list(root)) {
            if (entries.findAny().isPresent()) {
                return;
            }
        }
        if (welcomeNote != null) {
            Files.write(root.resolve("Welcome.md"), welcomeNote);
        }
    }

    public static final class TreeNode {
        public final String name;
        public final String path;
        public final String type;
        public final List<TreeNode> children;

        public TreeNode(String name, String path, String type, List<TreeNode> children) {
            this.name = name;
            this.path = path;
            this.type = type;
            this.children = children;
        }
    }
}
