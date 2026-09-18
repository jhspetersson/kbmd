package dev.kbmd.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.kbmd.index.NoteIndex;
import dev.kbmd.markdown.MarkdownService;
import dev.kbmd.vault.NoteService;
import dev.kbmd.vault.VaultService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api")
public class VaultController {

    private final VaultService vault;
    private final NoteService notes;
    private final NoteIndex index;
    private final MarkdownService markdown;

    public VaultController(VaultService vault, NoteService notes, NoteIndex index, MarkdownService markdown) {
        this.vault = vault;
        this.notes = notes;
        this.index = index;
        this.markdown = markdown;
    }

    @GetMapping("/tree")
    public VaultService.TreeNode tree() {
        return vault.tree();
    }

    @GetMapping("/notes")
    public NoteDocument read(@RequestParam String path) {
        return new NoteDocument(path, NoteIndex.title(path), vault.read(path), vault.lastModified(path));
    }

    @PutMapping("/notes")
    public NoteDocument save(@RequestParam String path, @RequestBody ContentRequest request) {
        // the editor sends the timestamp it loaded; a mismatch means a sync or another program changed the file
        notes.save(path, request.content() == null ? "" : request.content(), request.baseModified());
        return new NoteDocument(path, NoteIndex.title(path), null, vault.lastModified(path));
    }

    @PostMapping("/notes")
    @ResponseStatus(HttpStatus.CREATED)
    public NoteDocument create(@RequestBody CreateRequest request) {
        notes.create(request.path(), request.content());
        return read(request.path());
    }

    @GetMapping("/notes/names")
    public List<NoteIndex.NoteRef> names() {
        return index.noteRefs();
    }

    @PostMapping("/folders")
    @ResponseStatus(HttpStatus.CREATED)
    public void createFolder(@RequestBody CreateRequest request) {
        notes.createFolder(request.path());
    }

    @PostMapping("/move")
    public Map<String, Object> move(@RequestBody MoveRequest request) {
        return Map.of("updatedNotes", notes.move(request.from(), request.to()));
    }

    @DeleteMapping("/entries")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestParam String path) {
        notes.delete(path);
    }

    @PostMapping("/render")
    public Map<String, String> render(@RequestBody RenderRequest request) {
        String content = request.content() != null ? request.content() : vault.read(request.path());
        return Map.of("html", markdown.render(content, request.path(), index));
    }

    @GetMapping("/backlinks")
    public List<NoteIndex.Hit> backlinks(@RequestParam String path) {
        return index.backlinks(path);
    }

    @GetMapping("/graph")
    public NoteIndex.Graph graph() {
        return index.graph();
    }

    @GetMapping("/tags")
    public Map<String, Integer> tags() {
        return index.tags();
    }

    @GetMapping("/search")
    public NoteIndex.SearchResponse search(@RequestParam("q") String query) {
        return index.search(query);
    }

    /** Today's daily note, created on first use. */
    @PostMapping("/daily")
    public NoteDocument daily() {
        String path = "Daily/" + LocalDate.now() + ".md";
        if (!vault.exists(path)) {
            notes.create(path, "# " + LocalDate.now() + "\n\n");
        }
        return read(path);
    }

    @PostMapping(path = "/files/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<UploadedFile> upload(@RequestParam("file") List<MultipartFile> files,
                                     @RequestParam(required = false) String folder) throws IOException {
        List<UploadedFile> uploaded = new ArrayList<>();
        for (MultipartFile file : files) {
            try (InputStream data = file.getInputStream()) {
                String path = notes.upload(file.getOriginalFilename(), folder, data);
                String name = path.substring(path.lastIndexOf('/') + 1);
                boolean embed = VaultService.isImage(path) || VaultService.isDrawing(path);
                uploaded.add(new UploadedFile(path, name, (embed ? "!" : "") + "[[" + name + "]]"));
            }
        }
        return uploaded;
    }

    @GetMapping("/files/raw")
    public ResponseEntity<Resource> raw(@RequestParam String path) {
        Path file = vault.existingFile(path);
        MediaType type = MediaTypeFactory.getMediaType(file.getFileName().toString()).orElse(MediaType.APPLICATION_OCTET_STREAM);
        boolean inline = List.of("image", "audio", "video").contains(type.getType()) || MediaType.APPLICATION_PDF.equals(type);
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                        .filename(file.getFileName().toString(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                // uploaded files are untrusted: never let them run script on this origin
                .header("Content-Security-Policy", "sandbox")
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(file));
    }

    @GetMapping("/export")
    public ResponseEntity<StreamingResponseBody> export() {
        String name = vault.root().getFileName() + "-" + LocalDate.now() + ".zip";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .body(vault::exportZip);
    }

    public record NoteDocument(String path, String title, String content, long modified) {
    }

    public record ContentRequest(String content, Long baseModified) {
    }

    public record CreateRequest(String path, String content) {
    }

    public record MoveRequest(String from, String to) {
    }

    public record RenderRequest(String path, String content) {
    }

    public record UploadedFile(String path, String name, String markdown) {
    }
}
