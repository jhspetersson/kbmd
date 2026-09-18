package dev.kbmd.markdown;

import java.util.Optional;

@FunctionalInterface
public interface LinkResolver {

    /** Resolves a link target written in the note at {@code fromPath} to a vault-relative file path. */
    Optional<String> resolve(String target, String fromPath);
}
