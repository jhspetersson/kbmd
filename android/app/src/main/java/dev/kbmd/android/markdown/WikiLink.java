package dev.kbmd.android.markdown;

/**
 * An Obsidian-style link: {@code [[target#anchor|alias]]}, or an embed when prefixed with {@code !}.
 * {@code raw} is the target exactly as written (before the {@code #} or {@code |}), used when rewriting links on rename.
 */
public final class WikiLink {

    public final String raw;
    public final String target;
    public final String anchor;
    public final String alias;
    public final boolean embed;

    public WikiLink(String raw, String target, String anchor, String alias, boolean embed) {
        this.raw = raw;
        this.target = target;
        this.anchor = anchor;
        this.alias = alias;
        this.embed = embed;
    }

    public String display() {
        if (alias != null && !alias.trim().isEmpty()) {
            return alias;
        }
        String name = target.substring(target.lastIndexOf('/') + 1);
        return anchor == null ? name : name.isEmpty() ? anchor : name + " > " + anchor;
    }
}
