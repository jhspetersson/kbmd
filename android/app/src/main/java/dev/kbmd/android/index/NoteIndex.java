package dev.kbmd.android.index;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import dev.kbmd.android.markdown.LinkResolver;
import dev.kbmd.android.markdown.MarkdownService;
import dev.kbmd.android.markdown.ParsedNote;
import dev.kbmd.android.markdown.WikiLink;
import dev.kbmd.android.vault.VaultService;

/**
 * In-memory view of the vault: link graph, tags, link resolution and full-text search.
 * Rebuilt from the files at startup; the files stay the only source of truth.
 * <p>
 * The web app searches with Lucene, which does not run on Android. A personal vault is small enough for a
 * plain token scan, which keeps the same query language: every word must match as a prefix,
 * {@code "quoted phrases"}, {@code tag:name} / {@code #name} and {@code path:folder}.
 */
public class NoteIndex implements LinkResolver {

    private static final Pattern QUERY_TOKEN = Pattern.compile("\"([^\"]+)\"|(\\S+)");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}_]+(?:['’][\\p{L}\\p{N}_]+)*");
    private static final int MAX_RESULTS = 50;
    private static final int MAX_SNIPPETS = 3;

    private final VaultService vault;
    private final MarkdownService markdown;

    // replaced as a whole by rebuild(), so readers never see a half-built index
    private volatile Map<String, Entry> notes = new ConcurrentHashMap<>();
    private volatile Set<String> files = Collections.emptySet();
    private volatile Map<String, List<String>> filesByName = Collections.emptyMap();

    public NoteIndex(VaultService vault, MarkdownService markdown) {
        this.vault = vault;
        this.markdown = markdown;
        rebuild();
    }

    /** Re-reads the whole vault. Called at startup and after anything that adds, moves or removes files. */
    public synchronized void rebuild() {
        List<String> all = vault.listFiles();
        Map<String, List<String>> byName = new HashMap<>();
        for (String path : all) {
            byName.computeIfAbsent(fileName(path).toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(path);
        }
        files = new HashSet<>(all);
        filesByName = byName;

        Map<String, Entry> rebuilt = new ConcurrentHashMap<>();
        for (String path : all) {
            if (VaultService.isNote(path)) {
                index(path, rebuilt);
            }
        }
        notes = rebuilt;
    }

    /** Re-indexes one note after its content changed. */
    public synchronized void update(String path) {
        if (!files.contains(path)) {
            rebuild();
            return;
        }
        if (VaultService.isNote(path)) {
            index(path, notes);
        }
    }

    private void index(String path, Map<String, Entry> into) {
        String content;
        try {
            content = vault.read(path);
        } catch (RuntimeException e) {
            return; // unreadable (binary) note: skipped
        }
        ParsedNote parsed = markdown.parse(content);
        into.put(path, new Entry(path, title(path), content, parsed, vault.lastModified(path)));
    }

    // ---------------------------------------------------------------- link resolution

    @Override
    public Optional<String> resolve(String target, String fromPath) {
        String wanted = target == null ? "" : target.replace('\\', '/').trim();
        while (wanted.startsWith("/")) {
            wanted = wanted.substring(1);
        }
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        Set<String> known = files;
        List<String> candidates = new ArrayList<>(Arrays.asList(wanted, wanted + ".md"));
        String folder = fromPath == null ? "" : parent(fromPath);
        if (!folder.isEmpty() || wanted.contains("..")) {
            String relative = normalize(folder.isEmpty() ? wanted : folder + "/" + wanted);
            if (relative != null) {
                candidates.add(relative);
                candidates.add(relative + ".md");
            }
        }
        for (String candidate : candidates) {
            if (known.contains(candidate)) {
                return Optional.of(candidate);
            }
        }

        // Obsidian-style: a bare name matches a file anywhere in the vault; shortest path wins
        String lower = wanted.toLowerCase(Locale.ROOT);
        String name = fileName(lower);
        Map<String, List<String>> byName = filesByName;
        List<String> named = new ArrayList<>(byName.getOrDefault(name, Collections.emptyList()));
        named.addAll(byName.getOrDefault(name + ".md", Collections.emptyList()));
        return named.stream()
                .filter(path -> {
                    String p = "/" + path.toLowerCase(Locale.ROOT);
                    return p.endsWith("/" + lower) || p.endsWith("/" + lower + ".md");
                })
                .min(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder()));
    }

    // ---------------------------------------------------------------- queries

    public List<NoteRef> noteRefs() {
        return notes.values().stream()
                .sorted(Comparator.comparingLong((Entry e) -> e.modified).reversed())
                .map(e -> new NoteRef(e.path, e.title))
                .collect(Collectors.toList());
    }

    public List<String> allFiles() {
        return files.stream().sorted().collect(Collectors.toList());
    }

    /** Notes that link to {@code path}, with the lines the links appear on. */
    public List<Hit> backlinks(String path) {
        List<Hit> hits = new ArrayList<>();
        for (Entry entry : notes.values()) {
            if (entry.path.equals(path)) {
                continue;
            }
            Set<String> targets = new LinkedHashSet<>();
            for (WikiLink link : entry.parsed.links) {
                if (resolve(link.target, entry.path).filter(path::equals).isPresent()) {
                    targets.add(link.raw);
                }
            }
            if (!targets.isEmpty()) {
                hits.add(new Hit(entry.path, entry.title, snippets(entry.content, targets)));
            }
        }
        hits.sort(Comparator.comparing((Hit h) -> h.title, String.CASE_INSENSITIVE_ORDER));
        return hits;
    }

    /** Which notes reference {@code path}, and how they spell the target. Used to rewrite links on rename. */
    public Map<String, Set<String>> referencesTo(String path) {
        Map<String, Set<String>> references = new LinkedHashMap<>();
        for (Entry entry : notes.values()) {
            for (WikiLink link : entry.parsed.links) {
                if (resolve(link.target, entry.path).filter(path::equals).isPresent()) {
                    references.computeIfAbsent(entry.path, k -> new LinkedHashSet<>()).add(link.raw);
                }
            }
        }
        return references;
    }

    public Graph graph() {
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        Set<GraphEdge> edges = new LinkedHashSet<>();
        for (Entry entry : notes.values()) {
            nodes.put(entry.path, new GraphNode(entry.path, entry.title, true, new ArrayList<>(entry.parsed.tags)));
        }
        for (Entry entry : notes.values()) {
            for (WikiLink link : entry.parsed.links) {
                if (link.target.isEmpty()) {
                    continue;
                }
                Optional<String> resolved = resolve(link.target, entry.path);
                String id;
                if (!resolved.isPresent()) {
                    id = "unresolved:" + link.target.toLowerCase(Locale.ROOT);
                    nodes.putIfAbsent(id, new GraphNode(id, fileName(link.target), false, Collections.emptyList()));
                } else if (notes.containsKey(resolved.get())) {
                    id = resolved.get();
                } else {
                    continue; // attachments stay out of the graph
                }
                if (!id.equals(entry.path)) {
                    edges.add(new GraphEdge(entry.path, id));
                }
            }
        }
        return new Graph(new ArrayList<>(nodes.values()), new ArrayList<>(edges));
    }

    /** Every note with its content, in path order. */
    public List<TaggedNote> allNotes() {
        return notes.values().stream()
                .sorted(Comparator.comparing((Entry e) -> e.path))
                .map(e -> new TaggedNote(e.path, e.title, e.content, new ArrayList<>(e.parsed.tags)))
                .collect(Collectors.toList());
    }

    /** Notes carrying {@code tag} or one of its nested tags ({@code tag/child}). */
    public List<TaggedNote> notesTagged(String tag) {
        List<TaggedNote> tagged = new ArrayList<>();
        for (Entry entry : notes.values()) {
            List<String> matching = entry.parsed.tags.stream()
                    .filter(t -> t.equals(tag) || t.startsWith(tag + "/")).collect(Collectors.toList());
            if (!matching.isEmpty()) {
                tagged.add(new TaggedNote(entry.path, entry.title, entry.content, matching));
            }
        }
        tagged.sort(Comparator.comparing((TaggedNote n) -> n.path));
        return tagged;
    }

    public Map<String, Integer> tags() {
        Map<String, Integer> counts = new TreeMap<>();
        for (Entry entry : notes.values()) {
            for (String tag : entry.parsed.tags) {
                counts.merge(tag, 1, Integer::sum);
            }
        }
        return counts;
    }

    // ---------------------------------------------------------------- search

    public SearchResponse search(String text) {
        List<String> highlight = new ArrayList<>();
        List<Clause> clauses = buildQuery(text == null ? "" : text, highlight);
        if (clauses.isEmpty()) {
            return new SearchResponse(Collections.emptyList(), Collections.emptyList());
        }
        List<Scored> scored = new ArrayList<>();
        for (Entry entry : notes.values()) {
            double total = 0;
            boolean all = true;
            for (Clause clause : clauses) {
                double score = clause.score(entry);
                if (score < 0) {
                    all = false;
                    break;
                }
                total += score;
            }
            if (all) {
                scored.add(new Scored(entry, total));
            }
        }
        scored.sort(Comparator.comparingDouble((Scored s) -> -s.score).thenComparing(s -> s.entry.path));
        List<Hit> hits = new ArrayList<>();
        for (Scored match : scored.subList(0, Math.min(MAX_RESULTS, scored.size()))) {
            hits.add(new Hit(match.entry.path, match.entry.title, snippets(match.entry.content, highlight)));
        }
        return new SearchResponse(hits, highlight);
    }

    private List<Clause> buildQuery(String text, List<String> highlight) {
        List<Clause> clauses = new ArrayList<>();
        Matcher matcher = QUERY_TOKEN.matcher(text);
        while (matcher.find()) {
            String phrase = matcher.group(1);
            String word = matcher.group(2);
            if (phrase != null) {
                List<String> terms = tokenize(phrase);
                if (terms.isEmpty()) {
                    continue;
                }
                clauses.add(entry -> {
                    double score = (containsPhrase(entry.titleTokens, terms) ? 4 : 0) + (containsPhrase(entry.contentTokens, terms) ? 1 : 0);
                    return score > 0 ? score : -1;
                });
                highlight.add(phrase.toLowerCase(Locale.ROOT));
            } else if (word.startsWith("#") || word.toLowerCase(Locale.ROOT).startsWith("tag:")) {
                String tag = word.replaceFirst("(?i)^tag:", "").replaceFirst("^#", "").toLowerCase(Locale.ROOT);
                if (!tag.isEmpty()) {
                    clauses.add(entry -> entry.parsed.tags.stream().anyMatch(t -> t.startsWith(tag)) ? 1 : -1);
                    highlight.add("#" + tag);
                }
            } else if (word.toLowerCase(Locale.ROOT).startsWith("path:")) {
                for (String term : tokenize(word.substring(5))) {
                    clauses.add(entry -> countPrefix(entry.pathTokens, term, false) > 0 ? 1 : -1);
                }
            } else {
                for (String term : tokenize(word)) {
                    clauses.add(entry -> {
                        int inContent = countPrefix(entry.contentTokens, term, false);
                        int exact = countPrefix(entry.contentTokens, term, true);
                        double score = (countPrefix(entry.titleTokens, term, false) > 0 ? 4 : 0)
                                + (countPrefix(entry.pathTokens, term, false) > 0 ? 2 : 0)
                                + (inContent > 0 ? 1 + Math.log(inContent) / 4 : 0)
                                + (exact > 0 ? 2 + Math.log(exact) / 4 : 0);
                        return score > 0 ? score : -1;
                    });
                    highlight.add(term);
                }
            }
        }
        return clauses;
    }

    /** Lower-cased words, split roughly the way Lucene's StandardAnalyzer does. */
    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = WORD.matcher(text);
        while (matcher.find()) {
            tokens.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        return tokens;
    }

    private static int countPrefix(String[] tokens, String term, boolean exact) {
        int count = 0;
        for (String token : tokens) {
            if (exact ? token.equals(term) : token.startsWith(term)) {
                count++;
            }
        }
        return count;
    }

    private static boolean containsPhrase(String[] tokens, List<String> phrase) {
        outer:
        for (int i = 0; i + phrase.size() <= tokens.length; i++) {
            for (int j = 0; j < phrase.size(); j++) {
                if (!tokens[i + j].equals(phrase.get(j))) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static List<Snippet> snippets(String content, Iterable<String> needles) {
        List<String> wanted = new ArrayList<>();
        needles.forEach(wanted::add);
        List<Snippet> snippets = new ArrayList<>();
        String[] lines = content.split("\\R", -1);
        for (int i = 0; i < lines.length && snippets.size() < MAX_SNIPPETS; i++) {
            for (String needle : wanted) {
                // matched on the line itself: lower-casing can change its length (e.g. Turkish İ)
                int at = indexOfIgnoreCase(lines[i], needle);
                if (at >= 0) {
                    int from = Math.max(0, at - 60);
                    int to = Math.min(lines[i].length(), at + needle.length() + 120);
                    snippets.add(new Snippet(i + 1, (from > 0 ? "…" : "") + lines[i].substring(from, Math.max(from, to)).trim()
                            + (to < lines[i].length() ? "…" : "")));
                    break;
                }
            }
        }
        return snippets;
    }

    private static int indexOfIgnoreCase(String text, String needle) {
        if (needle.isEmpty()) {
            return -1;
        }
        for (int i = 0; i + needle.length() <= text.length(); i++) {
            if (text.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- path helpers

    public static String title(String path) {
        String name = fileName(path);
        return VaultService.isNote(name) ? name.substring(0, name.length() - 3) : name;
    }

    private static String fileName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    /** Collapses {@code .} and {@code ..}; null when the path climbs out of the vault. */
    private static String normalize(String path) {
        Deque<String> parts = new ArrayDeque<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (parts.isEmpty()) {
                    return null;
                }
                parts.removeLast();
            } else {
                parts.addLast(part);
            }
        }
        return String.join("/", parts);
    }

    private interface Clause {
        /** Negative when the note does not match. */
        double score(Entry entry);
    }

    private static final class Scored {
        final Entry entry;
        final double score;

        Scored(Entry entry, double score) {
            this.entry = entry;
            this.score = score;
        }
    }

    private static final class Entry {
        final String path;
        final String title;
        final String content;
        final ParsedNote parsed;
        final long modified;
        final String[] titleTokens;
        final String[] pathTokens;
        final String[] contentTokens;

        Entry(String path, String title, String content, ParsedNote parsed, long modified) {
            this.path = path;
            this.title = title;
            this.content = content;
            this.parsed = parsed;
            this.modified = modified;
            this.titleTokens = tokenize(title).toArray(new String[0]);
            this.pathTokens = tokenize(path).toArray(new String[0]);
            this.contentTokens = tokenize(content).toArray(new String[0]);
        }
    }

    public static final class NoteRef {
        public final String path;
        public final String title;

        NoteRef(String path, String title) {
            this.path = path;
            this.title = title;
        }
    }

    public static final class TaggedNote {
        public final String path;
        public final String title;
        public final String content;
        public final List<String> tags;

        TaggedNote(String path, String title, String content, List<String> tags) {
            this.path = path;
            this.title = title;
            this.content = content;
            this.tags = tags;
        }
    }

    public static final class Snippet {
        public final int line;
        public final String text;

        Snippet(int line, String text) {
            this.line = line;
            this.text = text;
        }
    }

    public static final class Hit {
        public final String path;
        public final String title;
        public final List<Snippet> snippets;

        Hit(String path, String title, List<Snippet> snippets) {
            this.path = path;
            this.title = title;
            this.snippets = snippets;
        }
    }

    public static final class SearchResponse {
        public final List<Hit> hits;
        public final List<String> terms;

        SearchResponse(List<Hit> hits, List<String> terms) {
            this.hits = hits;
            this.terms = terms;
        }
    }

    public static final class GraphNode {
        public final String id;
        public final String label;
        public final boolean exists;
        public final List<String> tags;

        GraphNode(String id, String label, boolean exists, List<String> tags) {
            this.id = id;
            this.label = label;
            this.exists = exists;
            this.tags = tags;
        }
    }

    public static final class GraphEdge {
        public final String source;
        public final String target;

        GraphEdge(String source, String target) {
            this.source = source;
            this.target = target;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof GraphEdge && ((GraphEdge) other).source.equals(source) && ((GraphEdge) other).target.equals(target);
        }

        @Override
        public int hashCode() {
            return source.hashCode() * 31 + target.hashCode();
        }
    }

    public static final class Graph {
        public final List<GraphNode> nodes;
        public final List<GraphEdge> edges;

        Graph(List<GraphNode> nodes, List<GraphEdge> edges) {
            this.nodes = nodes;
            this.edges = edges;
        }
    }
}
