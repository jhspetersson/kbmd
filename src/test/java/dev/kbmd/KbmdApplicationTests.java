package dev.kbmd;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import dev.kbmd.flashcards.FlashcardService;
import dev.kbmd.index.NoteIndex;
import dev.kbmd.markdown.MarkdownService;
import dev.kbmd.sync.SyncService;
import dev.kbmd.sync.SyncSettings;
import dev.kbmd.vault.NoteService;
import dev.kbmd.vault.VaultService;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import dev.kbmd.web.LocalOriginFilter;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class KbmdApplicationTests {

    private static final Path TEMP = createTemp();

    @Autowired
    VaultService vault;
    @Autowired
    NoteService notes;
    @Autowired
    NoteIndex index;
    @Autowired
    MarkdownService markdown;
    @Autowired
    SyncService sync;
    @Autowired
    FlashcardService flashcards;
    @Autowired
    WebApplicationContext context;
    @Autowired
    LocalOriginFilter originFilter;
    MockMvc mvc;

    @org.junit.jupiter.api.BeforeEach
    void mockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(originFilter).build();
    }

    @DynamicPropertySource
    static void vaultLocation(DynamicPropertyRegistry registry) {
        registry.add("kbmd.vault", () -> TEMP.resolve("vault").toString());
    }

    private static Path createTemp() {
        try {
            return Files.createTempDirectory("kbmd-test");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void seedsWelcomeNoteIntoEmptyVault() {
        assertThat(vault.exists("Welcome.md")).isTrue();
        assertThat(index.tags()).containsKey("help");
    }

    @Test
    void rendersWikilinksTagsAndSpecialBlocks() {
        notes.save("render/Target.md", "# Target");
        String html = markdown.render("""
                See [[Target|the target]] and [[Missing]] #topic, but not `[[code]]`.

                ```mermaid
                graph TD; A-->B
                ```
                """, "render/Source.md", index);

        assertThat(html).contains("data-path=\"render/Target.md\"").contains(">the target</a>");
        assertThat(html).contains("wikilink unresolved");
        assertThat(html).contains("data-tag=\"topic\"");
        assertThat(html).contains("<code>[[code]]</code>");
        assertThat(html).contains("<pre class=\"mermaid\">");
    }

    @Test
    void taskCheckboxesCarryTheirSourceLine() {
        String html = markdown.render("intro\n\n> - [ ] quoted\n\n- [x] done\n- [ ] open\n", "tasks.md", index);

        assertThat(html).contains("disabled=\"\" checked=\"\" data-line=\"5\"")
                .contains("data-line=\"3\"").contains("data-line=\"6\"");
    }

    @Test
    void findsBacklinksAndSearchesByPrefixAndTag() {
        notes.save("search/Alpha.md", "Links to [[Beta]] about quantum entanglement #physics");
        notes.save("search/Beta.md", "# Beta\nNothing here");

        assertThat(index.backlinks("search/Beta.md")).extracting(NoteIndex.Hit::path).containsExactly("search/Alpha.md");
        assertThat(index.search("entangl").hits()).extracting(NoteIndex.Hit::path).containsExactly("search/Alpha.md");
        assertThat(index.search("tag:physics").hits()).extracting(NoteIndex.Hit::path).containsExactly("search/Alpha.md");
        assertThat(index.search("\"quantum entanglement\"").hits()).hasSize(1);
        assertThat(index.graph().edges()).contains(new NoteIndex.GraphEdge("search/Alpha.md", "search/Beta.md"));
    }

    @Test
    void renameRewritesLinksInOtherNotes() {
        notes.save("rename/Old name.md", "content");
        notes.save("rename/Referrer.md", "Go to [[Old name]] and [[Old name#Part|alias]].");

        int updated = notes.move("rename/Old name.md", "rename/New name.md");

        assertThat(updated).isEqualTo(1);
        assertThat(vault.read("rename/Referrer.md")).isEqualTo("Go to [[New name]] and [[New name#Part|alias]].");
    }

    @Test
    void renamesFolderWhenOnlyTheCaseChanges() {
        notes.save("casing/Inside.md", "content");

        notes.move("casing", "Casing");

        assertThat(vault.tree().children()).extracting(VaultService.TreeNode::name).contains("Casing").doesNotContain("casing");
        assertThat(index.allFiles()).contains("Casing/Inside.md").doesNotContain("casing/Inside.md");
        assertThat(vault.read("Casing/Inside.md")).isEqualTo("content");
        assertThat(vault.root().resolve(".casing.renaming")).doesNotExist();
    }

    @Test
    void findsFlashcardsAndSchedulesReviews() {
        notes.save("cards/Spanish.md", """
                #flashcards/spanish

                hola::hello
                gato:::cat
                not a card: `a::b`

                How do you say
                "thank you"?
                ?
                gracias

                The capital of Spain is ==Madrid== on the river ==Manzanares==.

                ```
                code::ignored
                ```
                """);
        notes.save("cards/Untagged.md", "ignored::because the note has no flashcards tag");

        assertThat(flashcards.decks()).containsExactly(new FlashcardService.Deck("spanish", 6, 6, 0));
        List<FlashcardService.StudyCard> due = flashcards.due("spanish");
        assertThat(due).hasSize(6);
        assertThat(due.get(0).frontHtml()).contains("hola").doesNotContain("flashcards");
        assertThat(due).anyMatch(card -> card.frontHtml().contains("[…]") && card.backHtml().contains("<strong>Madrid</strong>"));

        FlashcardService.CardState state = flashcards.review(due.get(0).id(), FlashcardService.Rating.GOOD);
        assertThat(state.interval()).isEqualTo(1);
        assertThat(flashcards.due("spanish")).hasSize(5);
        assertThat(flashcards.review(due.get(1).id(), FlashcardService.Rating.AGAIN).interval()).isZero();
        assertThat(flashcards.due("spanish")).hasSize(5);
        assertThat(flashcards.exportForAnki("spanish", "http://localhost:8787")).contains("#deck column:3").contains("\tspanish\t");
    }

    @Test
    void rejectsPathsOutsideTheVault() throws Exception {
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> vault.resolve("../outside.md"));
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> vault.resolve(".git/config"));

        // Windows keeps an 8.3 alias for every dotted name; it must not open a back door to hidden folders
        sync.updateSettings(new SyncSettings("github", "me/repo", "secret-token", null, null, null, 0));
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(vault.root().resolve("KBMD~1")), "no 8.3 names on this file system");
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> vault.resolve("KBMD~1/sync.json"));
        mvc.perform(get("/api/notes").param("path", "KBMD~1/sync.json")).andExpect(status().isBadRequest());
    }

    @Test
    void refusesCrossSiteWritesAndForeignHosts() throws Exception {
        mvc.perform(post("/api/daily").header("Host", "127.0.0.1:8787").header("Origin", "https://evil.example")).andExpect(status().isForbidden());
        mvc.perform(post("/api/daily").header("Host", "localhost:8787").header("Sec-Fetch-Site", "cross-site")).andExpect(status().isForbidden());
        mvc.perform(get("/api/tree").header("Host", "rebound.example")).andExpect(status().isForbidden());
        mvc.perform(get("/api/tree").header("Host", "localhost:8787").header("Origin", "https://evil.example")).andExpect(status().isOk());
        mvc.perform(post("/api/daily").header("Host", "127.0.0.1:8787").header("Origin", "http://localhost:5173").header("Sec-Fetch-Site", "same-origin")).andExpect(status().isOk());
        mvc.perform(post("/api/daily").header("Host", "[::1]:8787")).andExpect(status().isOk());
    }

    @Test
    void syncsBothWaysWithARemoteRepository() throws Exception {
        Path remote = TEMP.resolve("remote.git");
        Git.init().setBare(true).setDirectory(remote.toFile()).setInitialBranch("main").call().close();
        sync.updateSettings(new SyncSettings("github", remote.toUri().toString(), "test-token", "main", null, null, 0));

        notes.save("sync/Local.md", "from kbmd");
        assertThat(sync.sync().ok()).isTrue();

        // someone else edits the same repository
        Path other = TEMP.resolve("other-clone");
        try (Git clone = Git.cloneRepository().setURI(remote.toUri().toString()).setDirectory(other.toFile()).call()) {
            assertThat(other.resolve("sync/Local.md")).hasContent("from kbmd");
            assertThat(other.resolve(".kbmd")).doesNotExist();
            Files.writeString(other.resolve("sync/Remote.md"), "from elsewhere");
            Files.writeString(other.resolve("sync/Local.md"), "remote edit");
            clone.add().addFilepattern(".").call();
            clone.commit().setMessage("remote change").setSign(false).call();
            clone.push().call();
        }

        notes.save("sync/Local.md", "local edit");
        SyncService.SyncResult result = sync.sync();

        assertThat(result.ok()).as(result.message()).isTrue();
        assertThat(vault.read("sync/Remote.md")).isEqualTo("from elsewhere");
        assertThat(vault.read("sync/Local.md")).isEqualTo("local edit");
        assertThat(result.conflicts()).containsExactly("sync/Local.md");
        assertThat(index.allFiles()).anyMatch(path -> path.startsWith("sync/Local (remote conflict"));
    }
}
