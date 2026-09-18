package dev.kbmd.vault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import dev.kbmd.KbmdProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * File operations on the vault folder. Every path coming from a client goes through
 * {@link #resolve(String)}, which keeps it inside the vault and away from hidden folders.
 */
@Service
public class VaultService {

    public static final String TRASH_DIR = ".trash";
    public static final String META_DIR = ".kbmd";

    private static final Logger log = LoggerFactory.getLogger(VaultService.class);
    private static final Set<String> IMAGE_EXTENSIONS =
            Set.of("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "avif");

    private final Path root;
    /** {@code root} with symlinks and Windows 8.3 aliases resolved; paths are checked against this one too. */
    private final Path realRoot;
    private final String attachmentsDir;

    public VaultService(KbmdProperties properties) throws IOException {
        this.root = properties.vault().toAbsolutePath().normalize();
        this.attachmentsDir = properties.attachmentsDir();
        Files.createDirectories(root);
        this.realRoot = root.toRealPath();
        log.info("Vault: {}", root);
        seedIfEmpty();
    }

    public Path root() {
        return root;
    }

    public Path resolve(String relative) {
        if (relative == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Path is required");
        }
        String cleaned = relative.replace('\\', '/').strip();
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        Path resolved;
        try {
            resolved = root.resolve(cleaned).normalize();
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid path: " + relative);
        }
        checkInside(resolved, root, relative);
        // NTFS keeps 8.3 aliases (.kbmd -> KBMD~1) and symlinks may point anywhere: the name the file system
        // really uses has to pass the same checks
        Path real = realPath(resolved);
        if (!real.equals(resolved)) {
            checkInside(real, realRoot, relative);
        }
        return resolved;
    }

    private static void checkInside(Path path, Path base, String relative) {
        if (!path.startsWith(base)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Path escapes the vault: " + relative);
        }
        for (Path segment : base.relativize(path)) {
            if (segment.toString().startsWith(".")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Hidden paths are not accessible: " + relative);
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
                    .toList();
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
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Not a readable text file: " + path);
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
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such file: " + path);
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
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void create(String path, String content) {
        if (exists(path)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already exists: " + path);
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
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such entry: " + from);
        }
        if (source.equals(root)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot move the vault root");
        }
        if (target.startsWith(source) && !target.equals(source)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot move a folder into itself");
        }
        try {
            Files.createDirectories(target.getParent());
            if (Files.exists(target) && Files.isSameFile(source, target)) {
                // only the case changed ("Notes" -> "notes"): on Windows and macOS Files.move sees the same file
                // and does nothing, so go through a temporary name
                if (!source.toString().equals(target.toString())) {
                    Path temp = uniqueSibling(source.getParent(), "." + source.getFileName() + ".renaming");
                    Files.move(source, temp);
                    Files.move(temp, target);
                }
            } else {
                Files.move(source, target);
            }
        } catch (FileAlreadyExistsException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already exists: " + to);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Deleting moves the entry to the vault's .trash folder, so nothing is lost by a misclick. */
    public void trash(String path) {
        Path source = resolve(path);
        if (!Files.exists(source) || source.equals(root)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such entry: " + path);
        }
        try {
            Path trash = root.resolve(TRASH_DIR);
            Files.createDirectories(trash);
            Files.move(source, uniqueSibling(trash, source.getFileName().toString()), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Stores an uploaded file in the attachments folder (or {@code folder}) and returns its vault path. */
    public String upload(String originalName, String folder, InputStream data) {
        String name = sanitizeFileName(originalName);
        Path dir = resolve(folder == null || folder.isBlank() ? attachmentsDir : folder);
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
            return List.of();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(Files::isRegularFile).map(this::relativize).filter(VaultService::isImage).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void exportZip(OutputStream out) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (String path : listFiles()) {
                Path file = root.resolve(path);
                ZipEntry entry = new ZipEntry(path);
                entry.setLastModifiedTime(Files.getLastModifiedTime(file));
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

    private static Path uniqueSibling(Path dir, String name) {
        Path candidate = dir.resolve(name);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; Files.exists(candidate); i++) {
            candidate = dir.resolve(base + " " + i + ext);
        }
        return candidate;
    }

    private static String sanitizeFileName(String original) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[<>:\"|?*\\[\\]#^\\p{Cntrl}]", "_").strip();
        while (name.startsWith(".")) {
            name = name.substring(1);
        }
        return name.isEmpty() ? "upload" : name;
    }

    private void seedIfEmpty() throws IOException {
        try (Stream<Path> entries = Files.list(root)) {
            if (entries.findAny().isPresent()) {
                return;
            }
        }
        try (InputStream welcome = VaultService.class.getResourceAsStream("/seed/Welcome.md")) {
            if (welcome != null) {
                Files.copy(welcome, root.resolve("Welcome.md"));
            }
        }
    }

    public record TreeNode(String name, String path, String type, List<TreeNode> children) {
    }
}
