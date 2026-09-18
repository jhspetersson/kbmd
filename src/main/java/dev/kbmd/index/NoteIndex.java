package dev.kbmd.index;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
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

import dev.kbmd.markdown.LinkResolver;
import dev.kbmd.markdown.MarkdownService;
import dev.kbmd.markdown.ParsedNote;
import dev.kbmd.markdown.WikiLink;
import dev.kbmd.vault.VaultService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * In-memory view of the vault: link graph, tags, link resolution, and a Lucene full-text index.
 * Rebuilt from the files at startup; the files stay the only source of truth.
 */
@Service
public class NoteIndex implements LinkResolver {

    private static final Logger log = LoggerFactory.getLogger(NoteIndex.class);
    private static final Pattern QUERY_TOKEN = Pattern.compile("\"([^\"]+)\"|(\\S+)");
    private static final int MAX_RESULTS = 50;
    private static final int MAX_SNIPPETS = 3;

    private final VaultService vault;
    private final MarkdownService markdown;

    // replaced as a whole by rebuild(), so readers never see a half-built index
    private volatile Map<String, Entry> notes = new ConcurrentHashMap<>();
    private volatile Set<String> files = Set.of();
    private volatile Map<String, List<String>> filesByName = Map.of();

    private final Analyzer analyzer = new StandardAnalyzer();
    private final ByteBuffersDirectory directory = new ByteBuffersDirectory();
    private IndexWriter writer;
    private SearcherManager searchers;

    public NoteIndex(VaultService vault, MarkdownService markdown) {
        this.vault = vault;
        this.markdown = markdown;
    }

    @PostConstruct
    void open() throws IOException {
        writer = new IndexWriter(directory, new IndexWriterConfig(analyzer));
        searchers = new SearcherManager(writer, null);
        rebuild();
    }

    @PreDestroy
    void close() throws IOException {
        searchers.close();
        writer.close();
        directory.close();
    }

    /** Re-reads the whole vault. Called at startup and after anything that adds, moves or removes files. */
    public synchronized void rebuild() {
        long start = System.nanoTime();
        List<String> all = vault.listFiles();
        Map<String, List<String>> byName = new HashMap<>();
        for (String path : all) {
            byName.computeIfAbsent(fileName(path).toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(path);
        }
        files = new HashSet<>(all);
        filesByName = byName;

        Map<String, Entry> rebuilt = new ConcurrentHashMap<>();
        try {
            writer.deleteAll();
            for (String path : all) {
                if (VaultService.isNote(path)) {
                    index(path, rebuilt);
                }
            }
            writer.commit();
            searchers.maybeRefresh();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        notes = rebuilt;
        log.info("Indexed {} notes ({} files) in {} ms", rebuilt.size(), all.size(), (System.nanoTime() - start) / 1_000_000);
    }

    /** Re-indexes one note after its content changed. */
    public synchronized void update(String path) {
        if (!files.contains(path)) {
            rebuild();
            return;
        }
        if (!VaultService.isNote(path)) {
            return;
        }
        try {
            index(path, notes);
            writer.commit();
            searchers.maybeRefresh();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void index(String path, Map<String, Entry> into) throws IOException {
        String content;
        try {
            content = vault.read(path);
        } catch (RuntimeException e) {
            log.warn("Skipping unreadable note {}: {}", path, e.getMessage());
            return;
        }
        ParsedNote parsed = markdown.parse(content);
        Entry entry = new Entry(path, title(path), content, parsed, vault.lastModified(path));
        into.put(path, entry);

        Document doc = new Document();
        doc.add(new StringField("path", path, Field.Store.YES));
        doc.add(new TextField("pathText", path, Field.Store.NO));
        doc.add(new TextField("title", entry.title(), Field.Store.NO));
        doc.add(new TextField("content", content, Field.Store.NO));
        for (String tag : parsed.tags()) {
            doc.add(new StringField("tag", tag, Field.Store.NO));
        }
        writer.updateDocument(new Term("path", path), doc);
    }

    // ---------------------------------------------------------------- link resolution

    @Override
    public Optional<String> resolve(String target, String fromPath) {
        String wanted = target == null ? "" : target.replace('\\', '/').strip();
        while (wanted.startsWith("/")) {
            wanted = wanted.substring(1);
        }
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        Set<String> known = files;
        List<String> candidates = new ArrayList<>(List.of(wanted, wanted + ".md"));
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
        List<String> named = new ArrayList<>(filesByName.getOrDefault(name, List.of()));
        named.addAll(filesByName.getOrDefault(name + ".md", List.of()));
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
                .sorted(Comparator.comparingLong(Entry::modified).reversed())
                .map(e -> new NoteRef(e.path(), e.title()))
                .toList();
    }

    public List<String> allFiles() {
        return files.stream().sorted().toList();
    }

    /** Notes that link to {@code path}, with the lines the links appear on. */
    public List<Hit> backlinks(String path) {
        List<Hit> hits = new ArrayList<>();
        for (Entry entry : notes.values()) {
            if (entry.path().equals(path)) {
                continue;
            }
            Set<String> targets = new LinkedHashSet<>();
            for (WikiLink link : entry.parsed().links()) {
                if (resolve(link.target(), entry.path()).filter(path::equals).isPresent()) {
                    targets.add(link.raw());
                }
            }
            if (!targets.isEmpty()) {
                hits.add(new Hit(entry.path(), entry.title(), snippets(entry.content(), targets)));
            }
        }
        hits.sort(Comparator.comparing(Hit::title, String.CASE_INSENSITIVE_ORDER));
        return hits;
    }

    /** Which notes reference {@code path}, and how they spell the target. Used to rewrite links on rename. */
    public Map<String, Set<String>> referencesTo(String path) {
        Map<String, Set<String>> references = new LinkedHashMap<>();
        for (Entry entry : notes.values()) {
            for (WikiLink link : entry.parsed().links()) {
                if (resolve(link.target(), entry.path()).filter(path::equals).isPresent()) {
                    references.computeIfAbsent(entry.path(), k -> new LinkedHashSet<>()).add(link.raw());
                }
            }
        }
        return references;
    }

    public Graph graph() {
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        Set<GraphEdge> edges = new LinkedHashSet<>();
        for (Entry entry : notes.values()) {
            nodes.put(entry.path(), new GraphNode(entry.path(), entry.title(), true, List.copyOf(entry.parsed().tags())));
        }
        for (Entry entry : notes.values()) {
            for (WikiLink link : entry.parsed().links()) {
                if (link.target().isEmpty()) {
                    continue;
                }
                Optional<String> resolved = resolve(link.target(), entry.path());
                String id;
                if (resolved.isEmpty()) {
                    id = "unresolved:" + link.target().toLowerCase(Locale.ROOT);
                    nodes.putIfAbsent(id, new GraphNode(id, fileName(link.target()), false, List.of()));
                } else if (notes.containsKey(resolved.get())) {
                    id = resolved.get();
                } else {
                    continue; // attachments stay out of the graph
                }
                if (!id.equals(entry.path())) {
                    edges.add(new GraphEdge(entry.path(), id));
                }
            }
        }
        return new Graph(List.copyOf(nodes.values()), List.copyOf(edges));
    }

    /** Notes carrying {@code tag} or one of its nested tags ({@code tag/child}). */
    public List<TaggedNote> notesTagged(String tag) {
        List<TaggedNote> tagged = new ArrayList<>();
        for (Entry entry : notes.values()) {
            List<String> matching = entry.parsed().tags().stream()
                    .filter(t -> t.equals(tag) || t.startsWith(tag + "/")).toList();
            if (!matching.isEmpty()) {
                tagged.add(new TaggedNote(entry.path(), entry.title(), entry.content(), matching));
            }
        }
        tagged.sort(Comparator.comparing(TaggedNote::path));
        return tagged;
    }

    public Map<String, Integer> tags() {
        Map<String, Integer> counts = new TreeMap<>();
        for (Entry entry : notes.values()) {
            for (String tag : entry.parsed().tags()) {
                counts.merge(tag, 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * Full-text search. Every word must match (as a prefix, so it works while typing); {@code "quoted phrases"},
     * {@code tag:name} / {@code #name} and {@code path:folder} are supported.
     */
    public SearchResponse search(String text) {
        List<String> highlight = new ArrayList<>();
        Query query = buildQuery(text == null ? "" : text, highlight);
        if (query == null) {
            return new SearchResponse(List.of(), List.of());
        }
        try {
            searchers.maybeRefresh();
            IndexSearcher searcher = searchers.acquire();
            try {
                List<Hit> hits = new ArrayList<>();
                for (ScoreDoc scoreDoc : searcher.search(query, MAX_RESULTS).scoreDocs) {
                    String path = searcher.storedFields().document(scoreDoc.doc).get("path");
                    Entry entry = notes.get(path);
                    if (entry != null) {
                        hits.add(new Hit(path, entry.title(), snippets(entry.content(), highlight)));
                    }
                }
                return new SearchResponse(hits, highlight);
            } finally {
                searchers.release(searcher);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Query buildQuery(String text, List<String> highlight) {
        BooleanQuery.Builder all = new BooleanQuery.Builder();
        boolean any = false;
        Matcher matcher = QUERY_TOKEN.matcher(text);
        while (matcher.find()) {
            String phrase = matcher.group(1);
            String word = matcher.group(2);
            if (phrase != null) {
                List<String> terms = analyze(phrase);
                if (terms.isEmpty()) {
                    continue;
                }
                BooleanQuery.Builder either = new BooleanQuery.Builder();
                either.add(new BoostQuery(new PhraseQuery("title", terms.toArray(String[]::new)), 4f), Occur.SHOULD);
                either.add(new PhraseQuery("content", terms.toArray(String[]::new)), Occur.SHOULD);
                all.add(either.build(), Occur.MUST);
                highlight.add(phrase.toLowerCase(Locale.ROOT));
                any = true;
            } else if (word.startsWith("#") || word.toLowerCase(Locale.ROOT).startsWith("tag:")) {
                String tag = word.replaceFirst("(?i)^tag:", "").replaceFirst("^#", "").toLowerCase(Locale.ROOT);
                if (!tag.isEmpty()) {
                    all.add(new PrefixQuery(new Term("tag", tag)), Occur.MUST);
                    highlight.add("#" + tag);
                    any = true;
                }
            } else if (word.toLowerCase(Locale.ROOT).startsWith("path:")) {
                for (String term : analyze(word.substring(5))) {
                    all.add(new PrefixQuery(new Term("pathText", term)), Occur.MUST);
                    any = true;
                }
            } else {
                for (String term : analyze(word)) {
                    BooleanQuery.Builder either = new BooleanQuery.Builder();
                    either.add(new BoostQuery(new PrefixQuery(new Term("title", term)), 4f), Occur.SHOULD);
                    either.add(new BoostQuery(new PrefixQuery(new Term("pathText", term)), 2f), Occur.SHOULD);
                    either.add(new PrefixQuery(new Term("content", term)), Occur.SHOULD);
                    either.add(new BoostQuery(new TermQuery(new Term("content", term)), 2f), Occur.SHOULD);
                    all.add(either.build(), Occur.MUST);
                    highlight.add(term);
                    any = true;
                }
            }
        }
        if (!any) {
            return null;
        }
        all.add(new MatchAllDocsQuery(), Occur.FILTER);
        return all.build();
    }

    private List<String> analyze(String text) {
        List<String> terms = new ArrayList<>();
        try (TokenStream stream = analyzer.tokenStream("content", text)) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                terms.add(term.toString());
            }
            stream.end();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return terms;
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
                    snippets.add(new Snippet(i + 1, (from > 0 ? "…" : "") + lines[i].substring(from, to).strip()
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

    private record Entry(String path, String title, String content, ParsedNote parsed, long modified) {
    }

    public record NoteRef(String path, String title) {
    }

    public record TaggedNote(String path, String title, String content, List<String> tags) {
    }

    public record Snippet(int line, String text) {
    }

    public record Hit(String path, String title, List<Snippet> snippets) {
    }

    public record SearchResponse(List<Hit> hits, List<String> terms) {
    }

    public record GraphNode(String id, String label, boolean exists, List<String> tags) {
    }

    public record GraphEdge(String source, String target) {
    }

    public record Graph(List<GraphNode> nodes, List<GraphEdge> edges) {
    }
}
