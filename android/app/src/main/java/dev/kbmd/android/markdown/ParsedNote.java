package dev.kbmd.android.markdown;

import java.util.List;
import java.util.Set;

public final class ParsedNote {

    public final List<WikiLink> links;
    public final Set<String> tags;

    public ParsedNote(List<WikiLink> links, Set<String> tags) {
        this.links = links;
        this.tags = tags;
    }
}
