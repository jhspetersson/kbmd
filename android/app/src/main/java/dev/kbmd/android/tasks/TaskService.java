package dev.kbmd.android.tasks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.kbmd.android.index.NoteIndex;
import dev.kbmd.android.server.HttpError;
import dev.kbmd.android.vault.NoteService;
import dev.kbmd.android.vault.VaultService;

/**
 * Tasks and kanban boards, both read straight from the notes and written back into them. Mirrors the web app:
 * a task is any {@code - [ ]} item ({@code 📅 2026-09-20} or {@code due:2026-09-20} sets a due date); a board
 * is a note tagged {@code #kanban} whose {@code ## headings} are columns and whose list items are cards.
 */
public class TaskService {

    public static final String KANBAN_TAG = "kanban";
    public static final String INBOX_NOTE = "Tasks.md";

    private static final Pattern TASK = Pattern.compile("^((?:\\s*>)*\\s*)(?:[-*+]|\\d+[.)])\\s+\\[([ xX])\\]\\s+(.*)$");
    private static final Pattern LIST_ITEM = Pattern.compile("^(\\s*)(?:[-*+]|\\d+[.)])\\s+(?:\\[([ xX])\\]\\s+)?(.*)$");
    private static final Pattern HEADING = Pattern.compile("^##\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern ANY_HEADING = Pattern.compile("^#{1,6}\\s.*");
    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~).*");
    private static final Pattern DUE = Pattern.compile("(?:📅|due:)\\s*(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern TAG = Pattern.compile("(?<![\\p{L}\\p{N}_&/#])#([\\p{L}_][\\p{L}\\p{N}_/-]*)");
    private static final Pattern DONE_COLUMN = Pattern.compile("(?i)^(done|completed?|finished)$");

    private final VaultService vault;
    private final NoteService notes;
    private final NoteIndex index;

    public TaskService(VaultService vault, NoteService notes, NoteIndex index) {
        this.vault = vault;
        this.notes = notes;
        this.index = index;
    }

    // ---------------------------------------------------------------- tasks

    public List<Task> tasks() {
        List<Task> tasks = new ArrayList<>();
        for (NoteIndex.TaggedNote note : index.allNotes()) {
            String[] lines = note.content.split("\\R", -1);
            boolean fenced = false;
            for (int i = 0; i < lines.length; i++) {
                if (FENCE.matcher(lines[i]).matches()) {
                    fenced = !fenced;
                    continue;
                }
                Matcher task = TASK.matcher(lines[i]);
                if (fenced || !task.matches()) {
                    continue;
                }
                tasks.add(describe(note.path, note.title, i + 1, task.group(2), task.group(3)));
            }
        }
        return tasks;
    }

    private static Task describe(String path, String title, int line, String mark, String raw) {
        Matcher due = DUE.matcher(raw);
        String dueDate = due.find() ? due.group(1) : null;
        Set<String> tags = new LinkedHashSet<>();
        Matcher tag = TAG.matcher(raw);
        while (tag.find()) {
            tags.add(tag.group(1).toLowerCase(Locale.ROOT));
        }
        String text = DUE.matcher(raw).replaceAll("").replaceAll("\\s+", " ").trim();
        return new Task(path, title, line, text, !mark.equals(" "), dueDate, new ArrayList<>(tags));
    }

    public synchronized boolean toggle(String path, int line) {
        String content = vault.read(path);
        String[] lines = content.split("\\R", -1);
        if (line < 1 || line > lines.length) {
            throw HttpError.notFound("No such line");
        }
        Matcher item = LIST_ITEM.matcher(lines[line - 1]);
        if (!item.matches() || item.group(2) == null) {
            throw HttpError.conflict("That line is no longer a task (was the note edited?)");
        }
        boolean done = item.group(2).equals(" ");
        lines[line - 1] = setDone(lines[line - 1], done);
        notes.save(path, String.join("\n", lines));
        return done;
    }

    public synchronized Task add(String text, String path) {
        String target = path == null || path.trim().isEmpty() ? INBOX_NOTE : path;
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty()) {
            throw HttpError.badRequest("The task is empty");
        }
        String content = vault.exists(target) ? vault.read(target) : "# Tasks\n";
        String separator = content.isEmpty() || content.endsWith("\n") ? "" : "\n";
        String updated = content + separator + "- [ ] " + clean + "\n";
        if (vault.exists(target)) {
            notes.save(target, updated);
        } else {
            notes.create(target, updated);
        }
        int line = updated.split("\\R", -1).length - 1;
        return describe(target, NoteIndex.title(target), line, " ", clean);
    }

    /**
     * Moves a task (its line and the indented lines under it) so that it sits before the task on {@code beforeLine}
     * of {@code targetPath}, or after that note's last task when {@code beforeLine} is 0. Returns the task where it
     * now is.
     */
    public synchronized Task moveTask(String path, int line, String targetPath, int beforeLine) {
        String target = targetPath == null || targetPath.trim().isEmpty() ? path : targetPath;
        List<String> source = new ArrayList<>(Arrays.asList(vault.read(path).split("\\R", -1)));
        if (line < 1 || line > source.size() || !TASK.matcher(source.get(line - 1)).matches()) {
            throw HttpError.conflict("That line is no longer a task (was the note edited?)");
        }
        int start = line - 1;
        List<String> block = new ArrayList<>(source.subList(start, blockEnd(source, start)));
        source.subList(start, start + block.size()).clear();

        boolean sameNote = target.equals(path);
        List<String> lines = sameNote ? source : new ArrayList<>(Arrays.asList(vault.read(target).split("\\R", -1)));
        int at;
        if (beforeLine > 0) {
            at = beforeLine - 1;
            if (sameNote && at > start) {
                at -= block.size(); // the lines above it moved up when the block came out
            }
            if (at < 0 || at >= lines.size() || !TASK.matcher(lines.get(at)).matches()) {
                throw HttpError.conflict("The target is no longer a task (was the note edited?)");
            }
        } else {
            at = afterLastTask(lines);
        }
        lines.addAll(at, block);
        if (!sameNote) {
            notes.save(path, String.join("\n", source));
        }
        notes.save(target, String.join("\n", lines));
        Matcher task = TASK.matcher(block.get(0));
        task.matches();
        return describe(target, NoteIndex.title(target), at + 1, task.group(2), task.group(3));
    }

    /** Where an appended task goes: after the note's last task and its detail lines, else at the very end. */
    private static int afterLastTask(List<String> lines) {
        boolean fenced = false;
        int last = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (FENCE.matcher(lines.get(i)).matches()) {
                fenced = !fenced;
            } else if (!fenced && TASK.matcher(lines.get(i)).matches()) {
                last = i;
            }
        }
        if (last >= 0) {
            return blockEnd(lines, last);
        }
        // before the empty element that stands for the note's final newline
        return !lines.isEmpty() && lines.get(lines.size() - 1).isEmpty() ? lines.size() - 1 : lines.size();
    }

    private static String setDone(String line, boolean done) {
        Matcher item = LIST_ITEM.matcher(line);
        if (!item.matches() || item.group(2) == null) {
            return line;
        }
        int box = line.indexOf('[', item.group(1).length());
        return line.substring(0, box + 1) + (done ? "x" : " ") + line.substring(box + 2);
    }

    // ---------------------------------------------------------------- kanban

    public List<Board> boards() {
        List<Board> boards = new ArrayList<>();
        for (NoteIndex.TaggedNote note : index.notesTagged(KANBAN_TAG)) {
            boards.add(parseBoard(note.path, note.title, note.content));
        }
        boards.sort(Comparator.comparing((Board b) -> b.title, String.CASE_INSENSITIVE_ORDER));
        return boards;
    }

    static Board parseBoard(String path, String title, String content) {
        String[] lines = content.split("\\R", -1);
        List<Column> columns = new ArrayList<>();
        List<Card> cards = null;
        String name = null;
        int columnLine = 0;
        boolean fenced = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (FENCE.matcher(line).matches()) {
                fenced = !fenced;
                continue;
            }
            if (fenced) {
                continue;
            }
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                if (name != null) {
                    columns.add(new Column(name, columnLine, cards));
                }
                name = heading.group(1);
                columnLine = i + 1;
                cards = new ArrayList<>();
                continue;
            }
            if (ANY_HEADING.matcher(line).matches()) {
                if (name != null) {
                    columns.add(new Column(name, columnLine, cards));
                    name = null;
                }
                continue;
            }
            Matcher item = LIST_ITEM.matcher(line);
            if (cards != null && item.matches() && item.group(1).isEmpty()) {
                String raw = item.group(3);
                Matcher due = DUE.matcher(raw);
                cards.add(new Card(i + 1, DUE.matcher(raw).replaceAll("").trim(), item.group(2) != null,
                        item.group(2) != null && !item.group(2).equals(" "), due.find() ? due.group(1) : null));
            }
        }
        if (name != null) {
            columns.add(new Column(name, columnLine, cards));
        }
        return new Board(path, title, columns);
    }

    public synchronized Board move(String path, int line, String column, int position) {
        String content = vault.read(path);
        Board board = parseBoard(path, NoteIndex.title(path), content);
        Column from = null;
        for (Column c : board.columns) {
            for (Card card : c.cards) {
                if (card.line == line) {
                    from = c;
                }
            }
        }
        if (from == null) {
            throw HttpError.conflict("That card is no longer on the board (was the note edited?)");
        }
        Column to = column(board, column);

        List<String> lines = new ArrayList<>(Arrays.asList(content.split("\\R", -1)));
        int start = line - 1;
        int end = blockEnd(lines, start);
        List<String> block = new ArrayList<>(lines.subList(start, end));
        lines.subList(start, end).clear();

        boolean toDone = DONE_COLUMN.matcher(to.name).matches();
        boolean fromDone = DONE_COLUMN.matcher(from.name).matches();
        if (toDone != fromDone) {
            block.set(0, setDone(block.get(0), toDone));
        }

        Board without = parseBoard(path, board.title, String.join("\n", lines));
        Column target = column(without, column);
        if (position < target.cards.size()) {
            lines.addAll(target.cards.get(Math.max(0, position)).line - 1, block);
        } else {
            appendToColumn(lines, target, block);
        }
        String updated = String.join("\n", lines);
        notes.save(path, updated);
        return parseBoard(path, board.title, updated);
    }

    public synchronized Board addCard(String path, String column, String text) {
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty()) {
            throw HttpError.badRequest("The card is empty");
        }
        String content = vault.read(path);
        Board board = parseBoard(path, NoteIndex.title(path), content);
        Column to = column(board, column);
        List<String> lines = new ArrayList<>(Arrays.asList(content.split("\\R", -1)));
        String card = "- [" + (DONE_COLUMN.matcher(to.name).matches() ? "x" : " ") + "] " + clean;
        appendToColumn(lines, to, new ArrayList<>(Collections.singletonList(card)));
        String updated = String.join("\n", lines);
        notes.save(path, updated);
        return parseBoard(path, board.title, updated);
    }

    private static Column column(Board board, String name) {
        for (Column c : board.columns) {
            if (c.name.equals(name)) {
                return c;
            }
        }
        throw HttpError.notFound("No column called " + name);
    }

    private static void appendToColumn(List<String> lines, Column column, List<String> block) {
        int at;
        if (column.cards.isEmpty()) {
            at = column.line;
            if (at < lines.size() && lines.get(at).trim().isEmpty()) {
                at++;
            } else {
                block.add(0, "");
            }
        } else {
            at = blockEnd(lines, column.cards.get(column.cards.size() - 1).line - 1);
        }
        if (at == lines.size() || !lines.get(at).trim().isEmpty()) {
            block.add(""); // a blank line before what follows, or the file's final newline
        }
        lines.addAll(at, block);
    }

    private static int blockEnd(List<String> lines, int start) {
        int end = start + 1;
        while (end < lines.size() && !lines.get(end).trim().isEmpty() && Character.isWhitespace(lines.get(end).charAt(0))) {
            end++;
        }
        return end;
    }

    public static final class Task {
        public final String notePath;
        public final String noteTitle;
        public final int line;
        public final String text;
        public final boolean done;
        public final String due;
        public final List<String> tags;

        Task(String notePath, String noteTitle, int line, String text, boolean done, String due, List<String> tags) {
            this.notePath = notePath;
            this.noteTitle = noteTitle;
            this.line = line;
            this.text = text;
            this.done = done;
            this.due = due;
            this.tags = tags;
        }
    }

    public static final class Card {
        public final int line;
        public final String text;
        public final boolean task;
        public final boolean done;
        public final String due;

        Card(int line, String text, boolean task, boolean done, String due) {
            this.line = line;
            this.text = text;
            this.task = task;
            this.done = done;
            this.due = due;
        }
    }

    public static final class Column {
        public final String name;
        public final int line;
        public final List<Card> cards;

        Column(String name, int line, List<Card> cards) {
            this.name = name;
            this.line = line;
            this.cards = cards;
        }
    }

    public static final class Board {
        public final String path;
        public final String title;
        public final List<Column> columns;

        Board(String path, String title, List<Column> columns) {
            this.path = path;
            this.title = title;
            this.columns = columns;
        }
    }
}
