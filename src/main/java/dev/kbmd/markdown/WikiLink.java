package dev.kbmd.markdown;

/**
 * An Obsidian-style link: {@code [[target#anchor|alias]]}, or an embed when prefixed with {@code !}.
 *
 * @param raw the target exactly as written (before the {@code #} or {@code |}), used when rewriting links on rename
 */
public record WikiLink(String raw, String target, String anchor, String alias, boolean embed) {

    public String display() {
        if (alias != null && !alias.isBlank()) {
            return alias;
        }
        String name = target.substring(target.lastIndexOf('/') + 1);
        return anchor == null ? name : name.isEmpty() ? anchor : name + " > " + anchor;
    }
}
