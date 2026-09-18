package dev.kbmd.android.sync;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One branch of a repository on GitHub or GitLab, reached through the provider's REST API. */
public interface RemoteRepo {

    /** Checks that the repository is reachable with the token; returns a sentence for the user. */
    String test() throws IOException;

    Snapshot snapshot() throws IOException;

    byte[] download(String path, String blobSha) throws IOException;

    /**
     * Commits the changes on top of {@code base} and returns the new head commit.
     *
     * @throws IOException when the branch moved since {@code base} was taken, among other things
     */
    String push(Snapshot base, List<Upload> uploads, List<String> deletes, String message) throws IOException;

    /** The remote branch at one moment: its head commit and the Git blob id of every file. */
    final class Snapshot {
        /** Null when the branch does not exist yet. */
        public String head;
        public String tree;
        public boolean emptyRepository;
        /** When the branch is missing in a repository that has others: the default branch it will start from. */
        public String startBranch;
        public String startCommit;
        public final Map<String, String> files = new HashMap<>();
        public final Map<String, String> modes = new HashMap<>();
    }

    /** A file to push. Its bytes are read when the provider asks for them, so a large sync never sits in memory at once. */
    final class Upload {
        public final String path;
        private final java.nio.file.Path file;
        private final byte[] bytes;
        /** Blob id of what was last read; null until then. */
        public volatile String sha;

        public Upload(String path, java.nio.file.Path file) {
            this.path = path;
            this.file = file;
            this.bytes = null;
        }

        public Upload(String path, byte[] content) {
            this.path = path;
            this.file = null;
            this.bytes = content;
        }

        public byte[] read() throws IOException {
            byte[] content = bytes != null ? bytes : java.nio.file.Files.readAllBytes(file);
            sha = GitHash.blob(content);
            return content;
        }

        public long size() throws IOException {
            return bytes != null ? bytes.length : java.nio.file.Files.size(file);
        }
    }
}
