package dev.kbmd.markdown;

import java.util.List;
import java.util.Set;

public record ParsedNote(List<WikiLink> links, Set<String> tags) {
}
