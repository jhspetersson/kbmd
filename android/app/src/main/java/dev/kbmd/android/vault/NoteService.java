package dev.kbmd.android.vault;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.kbmd.android.index.NoteIndex;
import dev.kbmd.android.server.HttpError;

/** Vault mutations that also keep the index (and links in other notes) up to date. */
public class NoteService {

    private final VaultService vault;
    private final NoteIndex index;

    public NoteService(VaultService vault, NoteIndex index) {
        this.vault = vault;
        this.index = index;
    }

    public void save(String path, String content) {
        save(path, content, null);
    }

    /**
     * Saves unless the file changed since {@code baseModified} (the timestamp the editor loaded), which means a sync
     * or another app wrote it. Check and write happen under one lock, so two writers cannot both pass the check.
     */
    public synchronized void save(String path, String content, Long baseModified) {
        if (baseModified != null && vault.exists(path) && vault.lastModified(path) != baseModified) {
            throw HttpError.conflict("The file changed on disk since it was opened");
        }
        vault.write(path, content);
        index.update(path);
    }

    public synchronized void create(String path, String content) {
        vault.create(path, content);
        index.rebuild();
    }

    public void createFolder(String path) {
        vault.createFolder(path);
    }

    public void delete(String path) {
        vault.trash(path);
        index.rebuild();
    }

    public String upload(String fileName, String folder, InputStream data) {
        String path = vault.upload(fileName, folder, data);
        index.rebuild();
        return path;
    }

    /**
     * Moves or renames a file or folder and rewrites {@code [[wikilinks]]} in other notes that would
     * otherwise stop resolving to the moved files.
     *
     * @return number of notes whose links were updated
     */
    public int move(String from, String to) {
        String source = vault.relativize(vault.resolve(from));
        String target = vault.relativize(vault.resolve(to));

        Map<String, String> moved = new LinkedHashMap<>();
        if (vault.isDirectory(source)) {
            for (String file : index.allFiles()) {
                if (file.startsWith(source + "/")) {
                    moved.put(file, target + file.substring(source.length()));
                }
            }
        } else {
            moved.put(source, target);
        }
        // referencing note -> (link target as written -> file it pointed to)
        Map<String, Map<String, String>> references = new LinkedHashMap<>();
        for (String oldPath : moved.keySet()) {
            for (Map.Entry<String, Set<String>> reference : index.referencesTo(oldPath).entrySet()) {
                for (String raw : reference.getValue()) {
                    references.computeIfAbsent(reference.getKey(), k -> new LinkedHashMap<>()).put(raw, oldPath);
                }
            }
        }

        vault.move(source, target);
        index.rebuild();

        int updated = 0;
        for (Map.Entry<String, Map<String, String>> reference : references.entrySet()) {
            String note = moved.getOrDefault(reference.getKey(), reference.getKey());
            String content = vault.read(note);
            String rewritten = content;
            for (Map.Entry<String, String> link : reference.getValue().entrySet()) {
                String newPath = moved.get(link.getValue());
                if (index.resolve(link.getKey().trim(), note).filter(newPath::equals).isPresent()) {
                    continue; // still resolves, e.g. a bare name after moving to another folder
                }
                rewritten = rewriteLinks(rewritten, link.getKey(), linkTextFor(newPath, note));
            }
            if (!rewritten.equals(content)) {
                vault.write(note, rewritten);
                index.update(note);
                updated++;
            }
        }
        return updated;
    }

    /** The shortest way to write a link to {@code path}: the bare name when unambiguous, else the full path. */
    private String linkTextFor(String path, String fromNote) {
        String full = VaultService.isNote(path) ? path.substring(0, path.length() - 3) : path;
        String name = full.substring(full.lastIndexOf('/') + 1);
        return index.resolve(name, fromNote).filter(path::equals).isPresent() ? name : full;
    }

    private static String rewriteLinks(String content, String oldTarget, String newTarget) {
        Pattern pattern = Pattern.compile("\\[\\[" + Pattern.quote(oldTarget) + "(?=\\\\?[\\]|#])");
        return pattern.matcher(content).replaceAll(Matcher.quoteReplacement("[[" + newTarget));
    }
}
