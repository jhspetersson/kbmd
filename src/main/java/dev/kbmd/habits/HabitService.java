package dev.kbmd.habits;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.kbmd.index.NoteIndex;
import dev.kbmd.vault.VaultService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Habit tracking. Habits are the list items of notes tagged {@code #habits}; a hint like {@code (3x/week)} sets a
 * weekly target, otherwise the habit is daily. Check-ins are kept in {@code .habits.json} at the vault root: hidden
 * from the file tree, included in sync, and keyed by the habit's name so the file stays readable.
 */
@Service
public class HabitService {

    public static final String TAG = "habits";
    private static final String STATE_FILE = ".habits.json";
    private static final int MAX_DAYS = 90;

    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:[-*+]|\\d+[.)])\\s+(?:\\[[ xX]]\\s+)?(.+?)\\s*$");
    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~).*");
    /** "(3x/week)", "(3/week)", "(daily)", "(weekly)" at the end of the item. */
    private static final Pattern TARGET = Pattern.compile("\\s*\\((?:(\\d+)\\s*x?\\s*/\\s*(?:week|wk|w)|(daily)|(weekly))\\)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG_IN_TEXT = Pattern.compile("(?<![\\p{L}\\p{N}_&/#])#" + TAG + "(?:/[\\p{L}\\p{N}_/-]*)?");

    private final NoteIndex index;
    private final JsonMapper json;
    private final Path stateFile;

    public HabitService(VaultService vault, NoteIndex index, JsonMapper json) {
        this.index = index;
        this.json = json;
        this.stateFile = vault.root().resolve(STATE_FILE);
    }

    /** The habits with their check-ins over the last {@code days} days (today included). */
    public synchronized Board board(int days) {
        int span = Math.max(1, Math.min(MAX_DAYS, days));
        LocalDate today = LocalDate.now();
        List<String> dates = new ArrayList<>();
        for (int i = span - 1; i >= 0; i--) {
            dates.add(today.minusDays(i).toString());
        }
        Map<String, Set<String>> done = loadState();
        List<HabitView> views = new ArrayList<>();
        for (Habit habit : habits()) {
            Set<String> checks = done.getOrDefault(habit.id(), new TreeSet<>());
            List<String> inRange = checks.stream().filter(d -> d.compareTo(dates.get(0)) >= 0 && d.compareTo(dates.get(dates.size() - 1)) <= 0).toList();
            views.add(new HabitView(habit.id(), habit.name(), habit.notePath(), habit.line(), habit.weeklyTarget(),
                    inRange, streak(checks, today), thisWeek(checks, today), checks.size()));
        }
        return new Board(dates, views);
    }

    /** Flips the check-in of a habit on a date and returns the new state. */
    public synchronized boolean toggle(String id, String date) {
        LocalDate day;
        try {
            day = LocalDate.parse(date);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bad date: " + date);
        }
        if (day.isAfter(LocalDate.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That day has not come yet");
        }
        if (habits().stream().noneMatch(habit -> habit.id().equals(id))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such habit (was the note edited?)");
        }
        Map<String, Set<String>> state = loadState();
        Set<String> checks = state.computeIfAbsent(id, k -> new TreeSet<>());
        boolean nowDone = !checks.remove(day.toString());
        if (nowDone) {
            checks.add(day.toString());
        }
        if (checks.isEmpty()) {
            state.remove(id);
        }
        saveState(state);
        return nowDone;
    }

    /** Habits found in the notes, in note order; the first occurrence of a name wins. */
    List<Habit> habits() {
        Map<String, Habit> habits = new LinkedHashMap<>();
        for (NoteIndex.TaggedNote note : index.notesTagged(TAG)) {
            for (Habit habit : parse(note.content(), note.path())) {
                habits.putIfAbsent(habit.id(), habit);
            }
        }
        return List.copyOf(habits.values());
    }

    static List<Habit> parse(String content, String notePath) {
        List<Habit> habits = new ArrayList<>();
        String[] lines = content.split("\\R", -1);
        boolean fenced = false;
        for (int i = 0; i < lines.length; i++) {
            if (FENCE.matcher(lines[i]).matches()) {
                fenced = !fenced;
                continue;
            }
            Matcher item = LIST_ITEM.matcher(lines[i]);
            if (fenced || !item.matches()) {
                continue;
            }
            String text = TAG_IN_TEXT.matcher(item.group(1)).replaceAll("").strip();
            int target = 7;
            Matcher hint = TARGET.matcher(text);
            if (hint.find()) {
                target = hint.group(1) != null ? Math.max(1, Math.min(7, Integer.parseInt(hint.group(1)))) : hint.group(3) != null ? 1 : 7;
                text = text.substring(0, hint.start()).strip();
            }
            text = text.replaceAll("^\\*\\*(.+)\\*\\*$", "$1").strip();
            if (!text.isEmpty()) {
                habits.add(new Habit(text, text, notePath, i + 1, target));
            }
        }
        return habits;
    }

    /** Consecutive days checked, counting back from today (or from yesterday, while today is still open). */
    static int streak(Set<String> checks, LocalDate today) {
        LocalDate day = checks.contains(today.toString()) ? today : today.minusDays(1);
        int streak = 0;
        while (checks.contains(day.toString())) {
            streak++;
            day = day.minusDays(1);
        }
        return streak;
    }

    /** Check-ins in the current week (Monday to Sunday). */
    static int thisWeek(Set<String> checks, LocalDate today) {
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        int count = 0;
        for (LocalDate day = monday; !day.isAfter(today); day = day.plusDays(1)) {
            if (checks.contains(day.toString())) {
                count++;
            }
        }
        return count;
    }

    private Map<String, Set<String>> loadState() {
        Map<String, Set<String>> state = new TreeMap<>();
        if (!Files.isRegularFile(stateFile)) {
            return state;
        }
        try {
            Map<String, List<String>> stored = json.readValue(Files.readString(stateFile, StandardCharsets.UTF_8),
                    new TypeReference<Map<String, List<String>>>() {
                    });
            stored.forEach((id, dates) -> state.put(id, new TreeSet<>(dates)));
            return state;
        } catch (IOException | RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot read " + STATE_FILE + ": " + e.getMessage());
        }
    }

    private void saveState(Map<String, Set<String>> state) {
        try {
            // sorted and pretty-printed, so Git merges of check-ins rarely conflict
            Files.writeString(stateFile, json.writerWithDefaultPrettyPrinter().writeValueAsString(state), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot write " + STATE_FILE + ": " + e.getMessage());
        }
    }

    record Habit(String id, String name, String notePath, int line, int weeklyTarget) {
    }

    /**
     * @param done   dates within the board's range that are checked
     * @param streak consecutive days up to today
     * @param week   check-ins this week, to compare with {@code weeklyTarget}
     * @param total  check-ins ever
     */
    public record HabitView(String id, String name, String notePath, int line, int weeklyTarget,
                            List<String> done, int streak, int week, int total) {
    }

    public record Board(List<String> days, List<HabitView> habits) {
    }
}
