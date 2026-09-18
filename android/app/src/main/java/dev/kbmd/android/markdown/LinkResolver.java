package dev.kbmd.android.markdown;

import java.util.Optional;

public interface LinkResolver {

    /** The vault path a link target points to, as seen from the note at {@code fromPath}. */
    Optional<String> resolve(String target, String fromPath);
}
