package dev.kbmd.android.habits;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.kbmd.android.index.NoteIndex;
import dev.kbmd.android.server.HttpError;
import dev.kbmd.android.vault.VaultService;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Habit tracking. Habits are the list items of notes tagged {@code #habits}; a hint like {@code (3x/week)} sets a
 * weekly target, otherwise the habit is daily. Check-ins are kept in {@code .habits.json} at the vault root, in the
 * web app's format, so both apps share the same history through sync.
 */
public class HabitService {

    public static final String TAG = "habits";
    private static final String STATE_FILE = ".habits.json";
    private static final int MAX_DAYS = 90;

    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:[-*+]|\\d+[.)])\\s+(?:\\[[ xX]\\]\\s+)?(.+?)\\s*$");
    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~).*");
    private static final Pattern TARGET = Pattern.compile("\\s*\\((?:(\\d+)\\s*x?\\s*/\\s*(?:week|wk|w)|(daily)|(weekly))\\)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG_IN_TEXT = Pattern.compile("(?<![\\p{L}\\p{N}_&/#])#" + TAG + "(?:/[\\p{L}\\p{N}_/-]*)?");
    private static final Pattern BOLD = Pattern.compile("^\\*\\*(.+)\\*\\*$");

    private final NoteIndex index;
    private final Path stateFile;

    public HabitService(VaultService vault, NoteIndex index) {
        this.index = index;
        this.stateFile = vault.root().resolve(STATE_FILE);
    }

    /** The board as the UI expects it: the last {@code days} dates and every habit with its check-ins. */
    public synchronized JSONObject board(int days) throws JSONException {
        int span = Math.max(1, Math.min(MAX_DAYS, days));
        LocalDate today = LocalDate.now();
        List<String> dates = new ArrayList<>();
        for (int i = span - 1; i >= 0; i--) {
            dates.add(today.minusDays(i).toString());
        }
        String first = dates.get(0);
        String last = dates.get(dates.size() - 1);
        Map<String, Set<String>> state = loadState();
        JSONArray habits = new JSONArray();
        for (Habit habit : habits()) {
            Set<String> checks = state.containsKey(habit.id) ? state.get(habit.id) : new TreeSet<>();
            JSONArray done = new JSONArray();
            for (String date : checks) {
                if (date.compareTo(first) >= 0 && date.compareTo(last) <= 0) {
                    done.put(date);
                }
            }
            habits.put(new JSONObject()
                    .put("id", habit.id).put("name", habit.name).put("notePath", habit.notePath).put("line", habit.line)
                    .put("weeklyTarget", habit.weeklyTarget).put("done", done)
                    .put("streak", streak(checks, today)).put("week", thisWeek(checks, today)).put("total", checks.size()));
        }
        return new JSONObject().put("days", new JSONArray(dates)).put("habits", habits);
    }

    /** Flips the check-in of a habit on a date and returns the new state. */
    public synchronized boolean toggle(String id, String date) {
        LocalDate day;
        try {
            day = LocalDate.parse(date);
        } catch (RuntimeException e) {
            throw HttpError.badRequest("Bad date: " + date);
        }
        if (day.isAfter(LocalDate.now())) {
            throw HttpError.badRequest("That day has not come yet");
        }
        boolean known = false;
        for (Habit habit : habits()) {
            known |= habit.id.equals(id);
        }
        if (!known) {
            throw HttpError.notFound("No such habit (was the note edited?)");
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

    List<Habit> habits() {
        Map<String, Habit> habits = new LinkedHashMap<>();
        for (NoteIndex.TaggedNote note : index.notesTagged(TAG)) {
            for (Habit habit : parse(note.content, note.path)) {
                habits.putIfAbsent(habit.id, habit);
            }
        }
        return new ArrayList<>(habits.values());
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
            String text = TAG_IN_TEXT.matcher(item.group(1)).replaceAll("").trim();
            int target = 7;
            Matcher hint = TARGET.matcher(text);
            if (hint.find()) {
                target = hint.group(1) != null ? Math.max(1, Math.min(7, Integer.parseInt(hint.group(1)))) : hint.group(3) != null ? 1 : 7;
                text = text.substring(0, hint.start()).trim();
            }
            text = BOLD.matcher(text).replaceAll("$1").trim();
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
            JSONObject json = new JSONObject(new String(Files.readAllBytes(stateFile), StandardCharsets.UTF_8));
            for (Iterator<String> ids = json.keys(); ids.hasNext(); ) {
                String id = ids.next();
                JSONArray dates = json.getJSONArray(id);
                Set<String> checks = new TreeSet<>();
                for (int i = 0; i < dates.length(); i++) {
                    checks.add(dates.getString(i));
                }
                state.put(id, checks);
            }
            return state;
        } catch (IOException | JSONException e) {
            throw new HttpError(500, "Cannot read " + STATE_FILE + ": " + e.getMessage());
        }
    }

    /** Sorted and laid out like the web app writes it (one habit per line), so merges of check-ins rarely conflict. */
    private void saveState(Map<String, Set<String>> state) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Set<String>> entry : state.entrySet()) {
            out.append(first ? "\n" : ",\n").append("  ").append(JSONObject.quote(entry.getKey())).append(" : [ ");
            boolean firstDate = true;
            for (String date : entry.getValue()) {
                out.append(firstDate ? "" : ", ").append(JSONObject.quote(date));
                firstDate = false;
            }
            out.append(" ]");
            first = false;
        }
        out.append(first ? " }" : "\n}");
        try {
            Files.write(stateFile, out.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new HttpError(500, "Cannot write " + STATE_FILE + ": " + e.getMessage());
        }
    }

    static final class Habit {
        final String id;
        final String name;
        final String notePath;
        final int line;
        final int weeklyTarget;

        Habit(String id, String name, String notePath, int line, int weeklyTarget) {
            this.id = id;
            this.name = name;
            this.notePath = notePath;
            this.line = line;
            this.weeklyTarget = weeklyTarget;
        }
    }
}
