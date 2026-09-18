package dev.kbmd.android.server;

import java.io.IOException;
import java.nio.file.Path;

import dev.kbmd.android.flashcards.FlashcardService;
import dev.kbmd.android.habits.HabitService;
import dev.kbmd.android.index.NoteIndex;
import dev.kbmd.android.markdown.MarkdownService;
import dev.kbmd.android.sync.SyncService;
import dev.kbmd.android.tasks.TaskService;
import dev.kbmd.android.vault.NoteService;
import dev.kbmd.android.vault.VaultService;

/** The services of the web app's backend, wired by hand. */
public final class Backend {

    public final VaultService vault;
    public final MarkdownService markdown;
    public final NoteIndex index;
    public final NoteService notes;
    public final FlashcardService flashcards;
    public final HabitService habits;
    public final TaskService tasks;
    public final SyncService sync;

    /**
     * @param vaultDir   the folder with the notes
     * @param privateDir app-private storage for the sync token and state
     */
    public Backend(Path vaultDir, Path privateDir, byte[] welcomeNote) throws IOException {
        vault = new VaultService(vaultDir, "attachments", welcomeNote);
        markdown = new MarkdownService(vault);
        index = new NoteIndex(vault, markdown);
        notes = new NoteService(vault, index);
        flashcards = new FlashcardService(vault, index, markdown);
        habits = new HabitService(vault, index);
        tasks = new TaskService(vault, notes, index);
        sync = new SyncService(vault, index, privateDir);
    }
}
