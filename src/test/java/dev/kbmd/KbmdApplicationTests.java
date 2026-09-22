package dev.kbmd;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import dev.kbmd.calendar.CalendarService;
import dev.kbmd.flashcards.FlashcardService;
import dev.kbmd.habits.HabitService;
import dev.kbmd.index.NoteIndex;
import dev.kbmd.markdown.MarkdownService;
import dev.kbmd.sync.SyncService;
import dev.kbmd.tasks.TaskService;
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
    HabitService habitService;
    @Autowired
    TaskService taskService;
    @Autowired
    CalendarService calendarService;
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

        assertThat(flashcards.decks()).containsExactly(new FlashcardService.Deck("spanish", 6, 6, 0, List.of("cards/Spanish.md")));
        List<FlashcardService.StudyCard> due = flashcards.due("spanish", 0);
        assertThat(due).hasSize(6);
        FlashcardService.StudyCard hola = due.stream().filter(card -> card.frontHtml().contains("hola")).findFirst().orElseThrow();
        assertThat(hola.frontHtml()).doesNotContain("flashcards");
        assertThat(due).anyMatch(card -> card.frontHtml().contains("[…]") && card.backHtml().contains("<strong>Madrid</strong>"));
        // the queue is shuffled: the same cards, not necessarily in note order
        assertThat(flashcards.due("spanish", 0)).extracting(FlashcardService.StudyCard::id).containsExactlyInAnyOrderElementsOf(due.stream().map(FlashcardService.StudyCard::id).toList());

        // a new card rated Hard comes back this session with its ease untouched; Again also dents the ease
        FlashcardService.CardState hard = flashcards.review(hola.id(), FlashcardService.Rating.HARD);
        assertThat(hard.interval()).isZero();
        assertThat(hard.ease()).isEqualTo(2.5);
        assertThat(flashcards.review(hola.id(), FlashcardService.Rating.AGAIN).ease()).isCloseTo(2.3, org.assertj.core.data.Offset.offset(1e-9));
        FlashcardService.CardState state = flashcards.review(hola.id(), FlashcardService.Rating.GOOD);
        assertThat(state.interval()).isEqualTo(1);
        // Hard on a review card always moves it forward, at least by a day
        assertThat(FlashcardService.schedule(state, FlashcardService.Rating.HARD, java.time.LocalDate.now()).interval()).isEqualTo(2);
        assertThat(FlashcardService.schedule(new FlashcardService.CardState("2026-01-01", 10, 2.5, 3, 0), FlashcardService.Rating.HARD, java.time.LocalDate.now()).interval()).isEqualTo(12);
        assertThat(flashcards.due("spanish", 0)).hasSize(5);
        FlashcardService.StudyCard other = due.stream().filter(card -> !card.id().equals(hola.id())).findFirst().orElseThrow();
        assertThat(flashcards.review(other.id(), FlashcardService.Rating.AGAIN).interval()).isZero();
        // the forgotten card is due again today and leads the queue; new cards follow, capped by the limit
        for (int attempt = 0; attempt < 5; attempt++) {
            List<FlashcardService.StudyCard> queue = flashcards.due("spanish", 0);
            assertThat(queue).hasSize(5);
            assertThat(queue.get(0).id()).isEqualTo(other.id());
            assertThat(queue.get(0).fresh()).isFalse();
            assertThat(queue.subList(1, 5)).allMatch(FlashcardService.StudyCard::fresh);
        }
        List<FlashcardService.StudyCard> limited = flashcards.due("spanish", 2);
        assertThat(limited).hasSize(3);
        assertThat(limited.get(0).id()).isEqualTo(other.id());
        assertThat(limited.subList(1, 3)).allMatch(FlashcardService.StudyCard::fresh);
        assertThat(flashcards.due("spanish", 10)).hasSize(5);
        assertThat(flashcards.exportForAnki("spanish", "http://localhost:8787")).contains("#deck column:3").contains("\tspanish\t");
    }

    @Test
    void tracksHabitsFromTaggedNotes() {
        notes.save("habits/Routine.md", """
                #habits

                - Exercise
                - [ ] Read 20 pages (3x/week)
                - **Meditate** (daily)
                - #habits
                ```
                - not a habit
                ```
                """);

        HabitService.Board board = habitService.board(7);
        assertThat(board.days()).hasSize(7).last().isEqualTo(java.time.LocalDate.now().toString());
        assertThat(board.habits()).extracting(HabitService.HabitView::name).containsExactly("Exercise", "Read 20 pages", "Meditate");
        assertThat(board.habits().get(1).weeklyTarget()).isEqualTo(3);
        assertThat(board.habits().get(2).weeklyTarget()).isEqualTo(7);

        String today = java.time.LocalDate.now().toString();
        String yesterday = java.time.LocalDate.now().minusDays(1).toString();
        assertThat(habitService.toggle("Exercise", yesterday)).isTrue();
        assertThat(habitService.toggle("Exercise", today)).isTrue();
        HabitService.HabitView exercise = habitService.board(7).habits().get(0);
        assertThat(exercise.done()).containsExactly(yesterday, today);
        assertThat(exercise.streak()).isEqualTo(2);
        assertThat(exercise.total()).isEqualTo(2);
        assertThat(habitService.toggle("Exercise", today)).isFalse();
        assertThat(habitService.board(7).habits().get(0).streak()).isEqualTo(1);
        assertThat(vault.root().resolve(".habits.json")).content().contains("\"Exercise\" : [ \"" + yesterday + "\" ]");

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> habitService.toggle("Exercise", java.time.LocalDate.now().plusDays(1).toString()));
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> habitService.toggle("Nope", today));
    }

    @Test
    void collectsTasksAndTogglesThemInPlace() {
        notes.save("tasks/Plan.md", "# Plan\n\n- [ ] Buy seeds 📅 2026-05-01 #garden\n- [x] Order soil\n\n```\n- [ ] not a task\n```\n> - [ ] quoted due:2026-06-01\n");

        List<TaskService.Task> found = taskService.tasks().stream().filter(t -> t.notePath().equals("tasks/Plan.md")).toList();
        assertThat(found).extracting(TaskService.Task::text).containsExactly("Buy seeds #garden", "Order soil", "quoted");
        assertThat(found.get(0).due()).isEqualTo("2026-05-01");
        assertThat(found.get(0).tags()).containsExactly("garden");
        assertThat(found.get(1).done()).isTrue();
        assertThat(found.get(2).due()).isEqualTo("2026-06-01");

        assertThat(taskService.toggle("tasks/Plan.md", 3)).isTrue();
        assertThat(taskService.toggle("tasks/Plan.md", 4)).isFalse();
        assertThat(vault.read("tasks/Plan.md")).contains("- [x] Buy seeds").contains("- [ ] Order soil");
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> taskService.toggle("tasks/Plan.md", 1));

        TaskService.Task added = taskService.add("Call the nursery", null);
        assertThat(added.notePath()).isEqualTo("Tasks.md");
        assertThat(vault.read("Tasks.md")).isEqualTo("# Tasks\n- [ ] Call the nursery\n");
        assertThat(taskService.add("Second", null).line()).isEqualTo(3);

        // reordering keeps a task's detail lines with it; moving to another note appends after that note's tasks
        notes.save("tasks/Order.md", "# Order\n\n- [ ] One\n  detail\n- [ ] Two\n- [ ] Three\n\nfooter\n");
        assertThat(taskService.moveTask("tasks/Order.md", 6, "tasks/Order.md", 3).line()).isEqualTo(3);
        assertThat(vault.read("tasks/Order.md")).isEqualTo("# Order\n\n- [ ] Three\n- [ ] One\n  detail\n- [ ] Two\n\nfooter\n");
        assertThat(taskService.moveTask("tasks/Order.md", 4, "tasks/Order.md", 0).line()).isEqualTo(5);
        assertThat(vault.read("tasks/Order.md")).isEqualTo("# Order\n\n- [ ] Three\n- [ ] Two\n- [ ] One\n  detail\n\nfooter\n");
        TaskService.Task moved = taskService.moveTask("tasks/Order.md", 5, "Tasks.md", 0);
        assertThat(moved.notePath()).isEqualTo("Tasks.md");
        assertThat(moved.line()).isEqualTo(4);
        assertThat(vault.read("tasks/Order.md")).isEqualTo("# Order\n\n- [ ] Three\n- [ ] Two\n\nfooter\n");
        assertThat(vault.read("Tasks.md")).isEqualTo("# Tasks\n- [ ] Call the nursery\n- [ ] Second\n- [ ] One\n  detail\n");
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> taskService.moveTask("tasks/Order.md", 1, "Tasks.md", 0));
    }

    @Test
    void kanbanBoardsAreEditedThroughTheNote() {
        notes.save("boards/Project.md", "#kanban\n\n## To do\n\n- [ ] Write spec\n  with a detail line\n- [ ] Review\n\n## Doing\n\n## Done\n\n- [x] Kick-off\n");

        TaskService.Board board = taskService.boards().get(0);
        assertThat(board.columns()).extracting(TaskService.Column::name).containsExactly("To do", "Doing", "Done");
        assertThat(board.columns().get(0).cards()).extracting(TaskService.Card::text).containsExactly("Write spec", "Review");

        // into the empty middle column: the detail line travels with the card
        board = taskService.move("boards/Project.md", 5, "Doing", 0);
        assertThat(board.columns().get(1).cards()).extracting(TaskService.Card::text).containsExactly("Write spec");
        assertThat(vault.read("boards/Project.md")).isEqualTo("#kanban\n\n## To do\n\n- [ ] Review\n\n## Doing\n\n- [ ] Write spec\n  with a detail line\n\n## Done\n\n- [x] Kick-off\n");

        // into Done: checked off; back out again: unchecked, placed first
        board = taskService.move("boards/Project.md", 5, "Done", 99);
        assertThat(board.columns().get(2).cards()).extracting(TaskService.Card::text).containsExactly("Kick-off", "Review");
        assertThat(board.columns().get(2).cards().get(1).done()).isTrue();
        board = taskService.move("boards/Project.md", board.columns().get(2).cards().get(1).line(), "To do", 0);
        assertThat(board.columns().get(0).cards().get(0).done()).isFalse();

        board = taskService.addCard("boards/Project.md", "Doing", "Ship it");
        assertThat(board.columns().get(1).cards()).extracting(TaskService.Card::text).containsExactly("Write spec", "Ship it");
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> taskService.move("boards/Project.md", 1, "Doing", 0));
    }

    @Test
    void calendarExpandsRulesFromNotes() {
        notes.save("cal/Events.md", """
                #calendar

                - 2026-09-25 14:30-15:00 Dentist
                - 2026-10-03..2026-10-05 Trip
                - every day 08:00 Standup until 2026-09-22
                - every Mon,Wed 07:00 Gym
                - every 2 weeks Tue Team sync from 2026-09-15
                - every month 1 Rent
                - every month last Invoices
                - every year 03-14 Pi day
                - birthday 1990-09-20 Mom
                - not an event
                """);
        notes.save("Daily/2026-09-21.md", "# 2026-09-21\n");
        notes.save("cal/Todo.md", "- [ ] Pay taxes due:2026-09-23\n- [x] Old 📅 2026-09-24\n");

        List<CalendarService.Occurrence> week = calendarService.occurrences(java.time.LocalDate.parse("2026-09-20"), java.time.LocalDate.parse("2026-09-26"));
        java.util.function.Function<String, List<String>> on = date -> week.stream().filter(o -> o.date().equals(date)).map(CalendarService.Occurrence::title).toList();
        assertThat(on.apply("2026-09-20")).containsExactlyInAnyOrder("Mom", "Standup");         // Sunday: birthday, daily rule
        assertThat(week.stream().filter(o -> o.title().equals("Mom")).findFirst().orElseThrow().detail()).isEqualTo("36");
        assertThat(on.apply("2026-09-21")).containsExactlyInAnyOrder("2026-09-21", "Gym", "Standup"); // Monday: daily note
        assertThat(on.apply("2026-09-22")).containsExactly("Standup");                          // Tuesday, one week after the 15th: 2-week rule off
        assertThat(on.apply("2026-09-23")).containsExactlyInAnyOrder("Gym", "Pay taxes");        // until stops the standup; open task with due date
        assertThat(on.apply("2026-09-24")).isEmpty();                                            // done task stays out
        assertThat(on.apply("2026-09-25")).containsExactly("Dentist");
        CalendarService.Occurrence dentist = week.stream().filter(o -> o.title().equals("Dentist")).findFirst().orElseThrow();
        assertThat(dentist.time()).isEqualTo("14:30");
        assertThat(dentist.endTime()).isEqualTo("15:00");
        assertThat(dentist.recurring()).isFalse();

        List<CalendarService.Occurrence> october = calendarService.occurrences(java.time.LocalDate.parse("2026-09-28"), java.time.LocalDate.parse("2026-10-31"));
        assertThat(october.stream().filter(o -> o.title().equals("Team sync")).map(CalendarService.Occurrence::date)).containsExactly("2026-09-29", "2026-10-13", "2026-10-27");
        assertThat(october.stream().filter(o -> o.title().equals("Rent")).map(CalendarService.Occurrence::date)).containsExactly("2026-10-01");
        assertThat(october.stream().filter(o -> o.title().equals("Invoices")).map(CalendarService.Occurrence::date)).containsExactly("2026-09-30", "2026-10-31");
        assertThat(october.stream().filter(o -> o.title().equals("Trip")).findFirst().orElseThrow().endDate()).isEqualTo("2026-10-05");
        assertThat(calendarService.occurrences(java.time.LocalDate.parse("2027-03-01"), java.time.LocalDate.parse("2027-03-31")))
                .extracting(CalendarService.Occurrence::title).contains("Pi day");

        String ics = calendarService.ics();
        assertThat(ics).contains("RRULE:FREQ=WEEKLY;BYDAY=MO,WE").contains("RRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=TU")
                .contains("RRULE:FREQ=MONTHLY;BYMONTHDAY=-1").contains("RRULE:FREQ=YEARLY;BYMONTH=9;BYMONTHDAY=20")
                .contains("RRULE:FREQ=DAILY;UNTIL=20260922").contains("DTSTART:20260925T143000").contains("DTEND;VALUE=DATE:20261006");

        assertThat(calendarService.add("2026-11-01 10:00", "Vet")).isEqualTo("2026-11-01 10:00 Vet");
        assertThat(vault.read("Calendar.md")).endsWith("#calendar\n\n- 2026-11-01 10:00 Vet\n");
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> calendarService.add("someday", "Vet"));
    }

    @Test
    void calendarAcceptsBirthdaysWithoutAYear() {
        notes.save("cal/Birthdays.md", """
                #calendar

                - birthday 05-15 Mary
                - birthday 15.05 Mark
                - birthday 15 May Mia
                - birthday May 15 Max
                - birthday May, 15 Moe
                - birthday 15.May Meg
                - birthday 15. May 10:00 Mel
                - birthday 29.02 Leap
                - birthday 15.05.1990 Dated
                - birthday May Nobody
                - birthday 32.05 Nobody
                """);
        List<CalendarService.Occurrence> may = calendarService.occurrences(java.time.LocalDate.parse("2027-05-01"), java.time.LocalDate.parse("2027-05-31"))
                .stream().filter(o -> o.notePath().equals("cal/Birthdays.md")).toList();
        assertThat(may).allSatisfy(o -> assertThat(o.date()).isEqualTo("2027-05-15"));
        assertThat(may).extracting(CalendarService.Occurrence::title).containsExactlyInAnyOrder("Mary", "Mark", "Mia", "Max", "Moe", "Meg", "Mel", "Dated");
        assertThat(may).allSatisfy(o -> assertThat(o.kind()).isEqualTo("birthday"));
        assertThat(may).filteredOn(o -> !o.title().equals("Dated")).allSatisfy(o -> assertThat(o.detail()).isNull());
        assertThat(may).filteredOn(o -> o.title().equals("Dated")).singleElement().extracting(CalendarService.Occurrence::detail).isEqualTo("37");
        assertThat(may).filteredOn(o -> o.title().equals("Mel")).singleElement().extracting(CalendarService.Occurrence::time).isEqualTo("10:00");
        assertThat(may).allSatisfy(o -> assertThat(o.rrule()).isEqualTo("FREQ=YEARLY;BYMONTH=5;BYMONTHDAY=15"));
        assertThat(calendarService.occurrences(java.time.LocalDate.parse("2028-02-01"), java.time.LocalDate.parse("2028-03-01")))
                .filteredOn(o -> o.title().equals("Leap")).extracting(CalendarService.Occurrence::date).containsExactly("2028-02-29");
        assertThat(calendarService.ics()).contains("DTSTART;VALUE=DATE:20000515");
        assertThat(calendarService.add("birthday 15 May", "Mona")).isEqualTo("birthday 15 May Mona");
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
