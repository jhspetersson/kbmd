package dev.kbmd.android.markdown;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.kbmd.android.vault.VaultService;
import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.front.matter.YamlFrontMatterExtension;
import org.commonmark.ext.front.matter.YamlFrontMatterVisitor;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.heading.anchor.HeadingAnchorExtension;
import org.commonmark.ext.task.list.items.TaskListItemMarker;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.node.SourceSpan;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.AttributeProvider;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.HtmlWriter;

/**
 * CommonMark + GFM, extended with Obsidian syntax: {@code [[wikilinks]]}, {@code ![[embeds]]}, {@code #tags},
 * plus {@code mermaid} and {@code gallery} fenced blocks. Mirrors the server-side renderer of the web app.
 */
public class MarkdownService {

    private static final String WIKI_SCHEME = "wikilink:";
    private static final String TAG_SCHEME = "tag:";

    private static final Pattern INLINE = Pattern.compile(
            "(!?)\\[\\[([^\\[\\]\\n]+?)]]|(?<![\\p{L}\\p{N}_&/#])#([\\p{L}_][\\p{L}\\p{N}_/-]*)");
    private static final Pattern HAS_SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:.*");
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile("!\\[([^\\]]*)]\\(([^)\\s]+)[^)]*\\)");

    private final List<Extension> extensions = Arrays.asList(
            TablesExtension.create(),
            StrikethroughExtension.create(),
            TaskListItemsExtension.create(),
            AutolinkExtension.create(),
            HeadingAnchorExtension.create(),
            YamlFrontMatterExtension.create());
    // block positions are kept so that a task checkbox knows the source line it toggles
    private final Parser parser = Parser.builder().extensions(extensions).includeSourceSpans(IncludeSourceSpans.BLOCKS).build();
    private final VaultService vault;

    public MarkdownService(VaultService vault) {
        this.vault = vault;
    }

    /** Extracts links and tags for the index. */
    public ParsedNote parse(String content) {
        Node document = parser.parse(content);
        InlineProcessor processor = new InlineProcessor();
        document.accept(processor);

        YamlFrontMatterVisitor frontMatter = new YamlFrontMatterVisitor();
        document.accept(frontMatter);
        Set<String> tags = new LinkedHashSet<>(processor.tags);
        for (String key : new String[] {"tags", "tag"}) {
            List<String> values = frontMatter.getData().get(key);
            if (values == null) {
                continue;
            }
            for (String value : values) {
                for (String tag : value.replaceAll("[\\[\\]\"'#]", "").split("[,\\s]+")) {
                    if (!tag.trim().isEmpty()) {
                        tags.add(tag.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return new ParsedNote(new ArrayList<>(processor.links), tags);
    }

    public String render(String content, String notePath, LinkResolver resolver) {
        Node document = parser.parse(content);
        InlineProcessor processor = new InlineProcessor();
        document.accept(processor);

        AttributeProvider attributes = (node, tagName, attrs) -> decorate(node, attrs, processor.meta, notePath, resolver);
        HtmlRenderer renderer = HtmlRenderer.builder()
                .extensions(extensions)
                .attributeProviderFactory(context -> attributes)
                .nodeRendererFactory(context -> new FencedBlockRenderer(context, notePath, resolver))
                .build();
        return renderer.render(document);
    }

    public static String rawUrl(String path) {
        try {
            return "/api/files/raw?path=" + URLEncoder.encode(path, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void decorate(Node node, Map<String, String> attrs, Map<Node, WikiLink> meta, String notePath, LinkResolver resolver) {
        if (node instanceof Link) {
            Link link = (Link) node;
            String destination = link.getDestination();
            WikiLink wiki = meta.get(node);
            if (destination.startsWith(TAG_SCHEME)) {
                attrs.put("href", "#");
                attrs.put("class", "tag");
                attrs.put("data-tag", destination.substring(TAG_SCHEME.length()));
            } else if (wiki != null) {
                decorateNoteLink(attrs, wiki, resolver.resolve(wiki.target, notePath), notePath);
            } else if (destination.startsWith("#")) {
                attrs.put("class", "anchor-link");
            } else if (HAS_SCHEME.matcher(destination).matches()) {
                attrs.put("target", "_blank");
                attrs.put("rel", "noopener noreferrer");
                attrs.put("class", "external-link");
            } else {
                WikiLink plain = fromMarkdownDestination(destination);
                Optional<String> resolved = resolver.resolve(plain.target, notePath);
                if (resolved.isPresent() && !VaultService.isNote(resolved.get()) && !VaultService.isDrawing(resolved.get())) {
                    attrs.put("href", rawUrl(resolved.get()));
                    attrs.put("target", "_blank");
                } else {
                    decorateNoteLink(attrs, plain, resolved, notePath);
                }
            }
        } else if (node instanceof Image) {
            Image image = (Image) node;
            WikiLink wiki = meta.get(node);
            String destination = image.getDestination();
            if (wiki != null) {
                attrs.put("src", resolver.resolve(wiki.target, notePath).map(MarkdownService::rawUrl).orElse(""));
                if (wiki.alias != null && wiki.alias.matches("\\d+(x\\d+)?")) {
                    attrs.put("width", wiki.alias.split("x")[0]);
                }
            } else if (!HAS_SCHEME.matcher(destination).matches() && !destination.startsWith("/")) {
                resolver.resolve(decode(destination), notePath).ifPresent(path -> attrs.put("src", rawUrl(path)));
            }
            attrs.put("loading", "lazy");
        } else if (node instanceof TaskListItemMarker) {
            Optional<Integer> line = sourceLine(node);
            if (line.isPresent()) {
                attrs.put("data-line", String.valueOf(line.get()));
            }
        }
    }

    /** 1-based line of the nearest block around {@code node}, e.g. the list item holding a task checkbox. */
    private static Optional<Integer> sourceLine(Node node) {
        for (Node block = node; block != null; block = block.getParent()) {
            List<SourceSpan> spans = block.getSourceSpans();
            if (!spans.isEmpty()) {
                return Optional.of(spans.get(0).getLineIndex() + 1);
            }
        }
        return Optional.empty();
    }

    private void decorateNoteLink(Map<String, String> attrs, WikiLink wiki, Optional<String> resolved, String notePath) {
        boolean sameNote = wiki.target.isEmpty();
        boolean drawing = wiki.embed && VaultService.isDrawing(wiki.target);
        String css = drawing ? "wikilink drawing-embed" : wiki.embed ? "wikilink embed" : "wikilink";
        attrs.put("href", "#");
        attrs.put("class", resolved.isPresent() || sameNote ? css : css + " unresolved");
        attrs.put("data-target", wiki.target);
        attrs.put("data-path", sameNote ? notePath : resolved.orElse(""));
        if (wiki.anchor != null) {
            attrs.put("data-anchor", wiki.anchor);
        }
    }

    private static WikiLink fromMarkdownDestination(String destination) {
        String decoded = decode(destination);
        int hash = decoded.indexOf('#');
        String target = hash >= 0 ? decoded.substring(0, hash) : decoded;
        String anchor = hash >= 0 ? decoded.substring(hash + 1) : null;
        return new WikiLink(destination, target, anchor, null, false);
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value.replace("+", "%2B"), "UTF-8");
        } catch (IllegalArgumentException | UnsupportedEncodingException e) {
            return value;
        }
    }

    static WikiLink parseWikiLink(String inner, boolean embed) {
        int pipe = inner.indexOf('|');
        String raw = pipe >= 0 ? inner.substring(0, pipe) : inner;
        String alias = pipe >= 0 ? inner.substring(pipe + 1).trim() : null;
        // inside tables the pipe has to be written as \|
        String reference = raw.endsWith("\\") ? raw.substring(0, raw.length() - 1) : raw;
        int hash = reference.indexOf('#');
        String target = (hash >= 0 ? reference.substring(0, hash) : reference).trim();
        String anchor = hash >= 0 ? reference.substring(hash + 1).trim() : null;
        String rawTarget = hash >= 0 ? reference.substring(0, hash) : reference;
        return new WikiLink(rawTarget, target, anchor, alias, embed);
    }

    /** Replaces wikilinks, embeds and tags found in text nodes with link/image nodes, collecting them on the way. */
    private static final class InlineProcessor extends AbstractVisitor {

        final List<WikiLink> links = new ArrayList<>();
        final Set<String> tags = new LinkedHashSet<>();
        final Map<Node, WikiLink> meta = new IdentityHashMap<>();

        @Override
        public void visit(Link link) {
            String destination = link.getDestination();
            if (!destination.isEmpty() && !destination.startsWith("#") && !HAS_SCHEME.matcher(destination).matches()) {
                links.add(fromMarkdownDestination(destination));
            }
            // link labels are left alone
        }

        @Override
        public void visit(Image image) {
            String destination = image.getDestination();
            if (!destination.isEmpty() && !HAS_SCHEME.matcher(destination).matches()) {
                WikiLink link = fromMarkdownDestination(destination);
                links.add(new WikiLink(link.raw, link.target, null, null, true));
            }
        }

        @Override
        public void visit(Text text) {
            String literal = text.getLiteral();
            Matcher matcher = INLINE.matcher(literal);
            Node cursor = text;
            int last = 0;
            while (matcher.find()) {
                if (matcher.start() > last) {
                    cursor = append(cursor, new Text(literal.substring(last, matcher.start())));
                }
                cursor = append(cursor, matcher.group(3) != null
                        ? tagNode(matcher.group(3))
                        : wikiNode(parseWikiLink(matcher.group(2), !matcher.group(1).isEmpty())));
                last = matcher.end();
            }
            if (cursor != text) {
                if (last < literal.length()) {
                    append(cursor, new Text(literal.substring(last)));
                }
                text.unlink();
            }
        }

        private Node tagNode(String tag) {
            tags.add(tag.toLowerCase(Locale.ROOT));
            Link link = new Link(TAG_SCHEME + tag.toLowerCase(Locale.ROOT), null);
            link.appendChild(new Text("#" + tag));
            return link;
        }

        private Node wikiNode(WikiLink wiki) {
            links.add(wiki);
            Node node = wiki.embed && VaultService.isImage(wiki.target)
                    ? new Image(WIKI_SCHEME + wiki.target, null)
                    : new Link(WIKI_SCHEME + wiki.target, null);
            // for images the alias is a size (![[pic.png|300]]), not a label
            node.appendChild(new Text(node instanceof Image ? wiki.target.substring(wiki.target.lastIndexOf('/') + 1) : wiki.display()));
            meta.put(node, wiki);
            return node;
        }

        private static Node append(Node after, Node node) {
            after.insertAfter(node);
            return node;
        }
    }

    /** Fenced code blocks, with {@code mermaid} and {@code gallery} handled specially. */
    private final class FencedBlockRenderer implements NodeRenderer {

        private final HtmlWriter html;
        private final String notePath;
        private final LinkResolver resolver;

        FencedBlockRenderer(HtmlNodeRendererContext context, String notePath, LinkResolver resolver) {
            this.html = context.getWriter();
            this.notePath = notePath;
            this.resolver = resolver;
        }

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            return Collections.singleton(FencedCodeBlock.class);
        }

        @Override
        public void render(Node node) {
            FencedCodeBlock block = (FencedCodeBlock) node;
            String info = block.getInfo() == null ? "" : block.getInfo().trim();
            String language = info.isEmpty() ? "" : info.split("\\s+")[0].toLowerCase(Locale.ROOT);
            html.line();
            if (language.equals("mermaid")) {
                html.tag("pre", Collections.singletonMap("class", "mermaid"));
                html.text(block.getLiteral());
                html.tag("/pre");
            } else if (language.equals("gallery")) {
                renderGallery(block.getLiteral());
            } else {
                html.tag("pre");
                html.tag("code", language.isEmpty()
                        ? Collections.<String, String>emptyMap()
                        : Collections.singletonMap("class", "language-" + language));
                html.text(block.getLiteral());
                html.tag("/code");
                html.tag("/pre");
            }
            html.line();
        }

        /**
         * One entry per line: an image ({@code pic.png}, {@code ![[pic.png]]}, {@code ![alt](pic.png)}, a URL)
         * or a folder, which expands to every image in it. {@code | caption} may follow an image.
         */
        private void renderGallery(String body) {
            Map<String, String> images = new LinkedHashMap<>();
            for (String line : body.split("\\R")) {
                String entry = line.trim();
                if (entry.isEmpty()) {
                    continue;
                }
                String caption = "";
                Matcher markdown = MARKDOWN_IMAGE.matcher(entry);
                if (markdown.matches()) {
                    caption = markdown.group(1);
                    entry = decode(markdown.group(2));
                } else {
                    entry = entry.replaceAll("^!?\\[\\[|]]$", "");
                    int pipe = entry.indexOf('|');
                    if (pipe >= 0) {
                        caption = entry.substring(pipe + 1).trim();
                        entry = entry.substring(0, pipe).trim();
                    }
                }
                if (HAS_SCHEME.matcher(entry).matches()) {
                    images.put(entry, caption);
                    continue;
                }
                List<String> folderImages = folderImages(entry);
                if (!folderImages.isEmpty()) {
                    for (String path : folderImages) {
                        if (!images.containsKey(rawUrl(path))) {
                            images.put(rawUrl(path), fileLabel(path));
                        }
                    }
                } else {
                    Optional<String> resolved = resolver.resolve(entry, notePath).filter(VaultService::isImage);
                    if (resolved.isPresent()) {
                        images.put(rawUrl(resolved.get()), caption);
                    }
                }
            }

            html.tag("div", Collections.singletonMap("class", "gallery"));
            if (images.isEmpty()) {
                html.tag("p", Collections.singletonMap("class", "gallery-empty"));
                html.text("Empty gallery: list image files or a folder, one per line.");
                html.tag("/p");
            }
            for (Map.Entry<String, String> image : images.entrySet()) {
                html.tag("figure");
                Map<String, String> attrs = new LinkedHashMap<>();
                attrs.put("src", image.getKey());
                attrs.put("alt", image.getValue());
                attrs.put("loading", "lazy");
                html.tag("img", attrs, true);
                if (!image.getValue().trim().isEmpty()) {
                    html.tag("figcaption");
                    html.text(image.getValue());
                    html.tag("/figcaption");
                }
                html.tag("/figure");
            }
            html.tag("/div");
        }

        private List<String> folderImages(String folder) {
            try {
                return vault.imagesIn(folder);
            } catch (RuntimeException e) {
                return Collections.emptyList();
            }
        }

        private String fileLabel(String path) {
            String name = path.substring(path.lastIndexOf('/') + 1);
            int dot = name.lastIndexOf('.');
            return dot > 0 ? name.substring(0, dot) : name;
        }
    }
}
