package dev.kbmd.android.sync;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import dev.kbmd.android.vault.VaultService;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SyncEngineTest {

    /** A branch held in memory, behaving like the providers: content addressed by Git blob id. */
    private static final class FakeRemote implements RemoteRepo {
        final Map<String, byte[]> files = new HashMap<>();
        int commits;
        boolean failPush;

        @Override
        public String test() {
            return "ok";
        }

        @Override
        public Snapshot snapshot() {
            Snapshot snapshot = new Snapshot();
            snapshot.emptyRepository = commits == 0;
            snapshot.head = commits == 0 ? null : "commit" + commits;
            files.forEach((path, content) -> snapshot.files.put(path, GitHash.blob(content)));
            return snapshot;
        }

        @Override
        public byte[] download(String path, String blobSha) {
            assertEquals(blobSha, GitHash.blob(files.get(path)));
            return files.get(path);
        }

        @Override
        public String push(Snapshot base, List<Upload> uploads, List<String> deletes, String message) throws IOException {
            if (failPush) {
                throw new IOException("Push rejected");
            }
            for (Upload upload : uploads) {
                files.put(upload.path, upload.read());
            }
            deletes.forEach(files::remove);
            return "commit" + ++commits;
        }

        void commit(String path, String content) {
            if (content == null) {
                files.remove(path);
            } else {
                files.put(path, content.getBytes(StandardCharsets.UTF_8));
            }
            commits++;
        }

        String text(String path) {
            return files.containsKey(path) ? new String(files.get(path), StandardCharsets.UTF_8) : null;
        }
    }

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private final SyncSettings settings = new SyncSettings("github", "me/notes", "token", "main", null, null, 0);
    private Path root;
    private Path stateFile;
    private SyncEngine engine;
    private FakeRemote remote;

    @Before
    public void setUp() throws IOException {
        VaultService vault = new VaultService(temp.newFolder("vault").toPath(), "attachments", null);
        root = vault.root();
        stateFile = temp.getRoot().toPath().resolve("private/sync-state.json");
        engine = new SyncEngine(vault, stateFile);
        remote = new FakeRemote();
    }

    private void write(String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        // as if written a while ago: the engine re-hashes files that are as new as its state
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() - 60_000));
    }

    private String read(String path) throws IOException {
        return new String(Files.readAllBytes(root.resolve(path)), StandardCharsets.UTF_8);
    }

    private SyncEngine.Outcome sync() throws IOException {
        return engine.sync(remote, settings);
    }

    @Test
    public void blobIdsAreGitIds() {
        // git hash-object of an empty file and of "hello\n"
        assertEquals("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", GitHash.blob(new byte[0]));
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", GitHash.blob("hello\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void firstSyncPushesTheVaultButNotTrashOrAppData() throws IOException {
        write("Note.md", "hello");
        write("folder/Other.md", "other");
        write(".flashcards.json", "{ }");
        write(".trash/Old.md", "old");
        write(".kbmd/sync.json", "secret");

        sync();

        assertEquals("hello", remote.text("Note.md"));
        assertEquals("other", remote.text("folder/Other.md"));
        assertEquals("{ }", remote.text(".flashcards.json"));
        assertTrue(remote.text(".gitignore").contains(".trash/"));
        assertFalse(remote.files.containsKey(".trash/Old.md"));
        assertFalse(remote.files.containsKey(".kbmd/sync.json"));
        assertEquals(1, remote.commits);
        assertEquals(0, engine.pendingChanges(settings));

        assertTrue(sync().steps.isEmpty());
        assertEquals("nothing to do means no commit", 1, remote.commits);
    }

    @Test
    public void firstSyncDownloadsAnExistingRepository() throws IOException {
        remote.commit("Remote.md", "from the desktop");
        remote.commit("pics/a.png", "png");

        sync();

        assertEquals("from the desktop", read("Remote.md"));
        assertEquals("png", read("pics/a.png"));
    }

    @Test
    public void changesTravelBothWays() throws IOException {
        write("Mine.md", "v1");
        sync();

        remote.commit("Theirs.md", "new on remote");
        remote.commit("Mine.md", "v2 from remote");
        sync();
        assertEquals("new on remote", read("Theirs.md"));
        assertEquals("v2 from remote", read("Mine.md"));

        write("Mine.md", "v3 from phone");
        assertEquals(1, engine.pendingChanges(settings));
        sync();
        assertEquals("v3 from phone", remote.text("Mine.md"));
        assertEquals(0, engine.pendingChanges(settings));
    }

    @Test
    public void deletionsTravelBothWays() throws IOException {
        write("Local.md", "a");
        write("dir/Remote.md", "b");
        sync();

        Files.delete(root.resolve("Local.md"));
        remote.commit("dir/Remote.md", null);
        sync();

        assertFalse(remote.files.containsKey("Local.md"));
        assertFalse(Files.exists(root.resolve("dir/Remote.md")));
        assertFalse("empty folders go too", Files.exists(root.resolve("dir")));
        try (Stream<Path> trash = Files.list(root.resolve(".trash"))) {
            assertEquals("remote deletions land in the trash", 1, trash.count());
        }
    }

    @Test
    public void conflictKeepsBothVersions() throws IOException {
        write("Note.md", "base");
        sync();

        write("Note.md", "phone edit");
        remote.commit("Note.md", "desktop edit");
        SyncEngine.Outcome outcome = sync();

        assertEquals(List.of("Note.md"), outcome.conflicts);
        assertEquals("phone edit", read("Note.md"));
        assertEquals("phone edit", remote.text("Note.md"));
        List<String> copies = remote.files.keySet().stream().filter(p -> p.contains("remote conflict")).collect(Collectors.toList());
        assertEquals(1, copies.size());
        assertEquals("desktop edit", remote.text(copies.get(0)));
        assertEquals("desktop edit", read(copies.get(0)));

        assertTrue("settled: nothing more to do", sync().steps.isEmpty());
    }

    @Test
    public void conflictIsNotRepeatedWhenThePushFails() throws IOException {
        write("Note.md", "base");
        sync();
        write("Note.md", "phone edit");
        remote.commit("Note.md", "desktop edit");

        remote.failPush = true;
        try {
            sync();
            fail("push should have failed");
        } catch (IOException expected) {
            // the conflict copy is on the phone already
        }
        remote.failPush = false;
        SyncEngine.Outcome retry = sync();

        assertTrue(retry.conflicts.isEmpty());
        assertEquals("phone edit", remote.text("Note.md"));
        assertEquals(1, remote.files.keySet().stream().filter(p -> p.contains("remote conflict")).count());
    }

    @Test
    public void anEditSurvivesADeleteOnTheOtherSide() throws IOException {
        write("A.md", "a");
        write("B.md", "b");
        sync();

        Files.delete(root.resolve("A.md"));
        remote.commit("A.md", "edited remotely");
        write("B.md", "edited locally");
        remote.commit("B.md", null);
        sync();

        assertEquals("edited remotely", read("A.md"));
        assertEquals("edited locally", remote.text("B.md"));
    }

    @Test
    public void sameChangeOnBothSidesIsNoConflict() throws IOException {
        write("Note.md", "base");
        sync();
        write("Note.md", "same");
        remote.commit("Note.md", "same");

        SyncEngine.Outcome outcome = sync();

        assertTrue(outcome.conflicts.isEmpty());
        assertEquals(0, engine.pendingChanges(settings));
    }

    @Test
    public void anotherRepositoryStartsFromScratch() throws IOException {
        write("Note.md", "mine");
        sync();

        FakeRemote other = new FakeRemote();
        other.commit("Else.md", "theirs");
        SyncSettings moved = new SyncSettings("gitlab", "me/other", "token", "main", null, null, 0);
        assertEquals(-1, engine.pendingChanges(moved));
        engine.sync(other, moved);

        assertEquals("nothing is deleted because of the old state", "mine", read("Note.md"));
        assertEquals("mine", other.text("Note.md"));
        assertEquals("theirs", read("Else.md"));
    }

    @Test
    public void binaryContentIsUntouched() throws IOException {
        byte[] bytes = new byte[2048];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i * 31);
        }
        remote.files.put("attachments/pic.png", bytes);
        remote.commits++;
        sync();
        assertArrayEquals(bytes, Files.readAllBytes(root.resolve("attachments/pic.png")));
    }

    @Test
    public void repositoryLocations() {
        SyncSettings.Location shorthand = new SyncSettings("github", "me/notes.git", "t", null, null, null, 0).location();
        assertEquals("github.com", shorthand.host);
        assertEquals("me/notes", shorthand.path);

        SyncSettings.Location url = new SyncSettings("gitlab", "https://oauth2:x@git.example.org/group/sub/notes.git/", "t", null, null, null, 0).location();
        assertEquals("git.example.org", url.host);
        assertEquals("group/sub/notes", url.path);

        SyncSettings.Location hostFirst = new SyncSettings("gitlab", "gitlab.com/group/notes", "t", null, null, null, 0).location();
        assertEquals("gitlab.com", hostFirst.host);
        assertEquals("group/notes", hostFirst.path);
    }
}
