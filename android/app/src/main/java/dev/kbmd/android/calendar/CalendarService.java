package dev.kbmd.android.calendar;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.kbmd.android.index.NoteIndex;
import dev.kbmd.android.server.HttpError;
import dev.kbmd.android.tasks.TaskService;
import dev.kbmd.android.vault.NoteService;
import dev.kbmd.android.vault.VaultService;

/**
 * Calendar, mirroring the web app: events are the list items of notes tagged {@code #calendar}, starting with a
 * date ({@code 2026-09-25 14:30 Dentist}, {@code 2026-10-03..2026-10-05 Trip}) or a rule ({@code every day 08:00},
 * {@code every Mon,Wed}, {@code every 2 weeks Tue ... from 2026-09-15}, {@code every month 1}, {@code every month last},
 * {@code every year 03-14}, {@code birthday 1990-03-14 Mom}); {@code until YYYY-MM-DD} ends a rule. Daily notes and
 * tasks with a due date appear as well.
 */
public class CalendarService {

    public static final String TAG = "calendar";
    public static final String EVENTS_NOTE = "Calendar.md";
    private static final int MAX_DAYS = 400;

    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:[-*+]|\\d+[.)])\\s+(?:\\[[ xX]\\]\\s+)?(.+?)\\s*$");
    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~).*");
    private static final Pattern DATE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern DATE_RANGE = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})(?:\\.\\.|\\s*-\\s*|\\s+to\\s+)(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern TIME = Pattern.compile("^(\\d{1,2}:\\d{2})(?:\\s*-\\s*(\\d{1,2}:\\d{2}))?");
    private static final Pattern MODIFIER = Pattern.compile("^(from|until|till|to)\\s+(\\d{4}-\\d{2}-\\d{2})", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING = Pattern.compile("\\s+(from|until|till)\\s+(\\d{4}-\\d{2}-\\d{2})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DAILY_NOTE = Pattern.compile("(?:^|/)(\\d{4}-\\d{2}-\\d{2})\\.md$");
    private static final Map<String, DayOfWeek> DAYS = new HashMap<>();

    static {
        String[][] names = {
                {"mon", "monday"}, {"tue", "tues", "tuesday"}, {"wed", "wednesday"}, {"thu", "thur", "thurs", "thursday"},
                {"fri", "friday"}, {"sat", "saturday"}, {"sun", "sunday"}};
        DayOfWeek[] days = DayOfWeek.values();
        for (int i = 0; i < names.length; i++) {
            for (String name : names[i]) {
                DAYS.put(name, days[i]);
            }
        }
    }

    public enum Repeat { NONE, DAILY, WEEKLY, MONTHLY, YEARLY }

    private final VaultService vault;
    private final NoteService notes;
    private final NoteIndex index;
    private final TaskService tasks;

    public CalendarService(VaultService vault, NoteService notes, NoteIndex index, TaskService tasks) {
        this.vault = vault;
        this.notes = notes;
        this.index = index;
        this.tasks = tasks;
    }

    public List<Occurrence> occurrences(LocalDate from, LocalDate to) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > MAX_DAYS) {
            throw HttpError.badRequest("Ask for at most " + MAX_DAYS + " days");
        }
        List<Occurrence> out = new ArrayList<>();
        for (Rule rule : rules()) {
            expand(rule, from, to, out);
        }
        for (String path : index.allFiles()) {
            Matcher daily = DAILY_NOTE.matcher(path);
            if (daily.find()) {
                LocalDate day = parseDate(daily.group(1));
                if (day != null && !day.isBefore(from) && !day.isAfter(to)) {
                    out.add(new Occurrence(day.toString(), null, null, null, NoteIndex.title(path), path, 0, "daily", false, null, null));
                }
            }
        }
        for (TaskService.Task task : tasks.tasks()) {
            if (task.due == null || task.done) {
                continue;
            }
            LocalDate day = parseDate(task.due);
            if (day != null && !day.isBefore(from) && !day.isAfter(to)) {
                out.add(new Occurrence(day.toString(), null, null, null, task.text, task.notePath, task.line, "task", false, null, null));
            }
        }
        out.sort(Comparator.comparing((Occurrence o) -> o.date).thenComparing(o -> o.time == null ? "" : o.time).thenComparing(o -> o.title));
        return out;
    }

    public synchronized String add(String spec, String title) {
        String cleanTitle = title == null ? "" : title.trim();
        String cleanSpec = spec == null ? "" : spec.trim();
        if (cleanTitle.isEmpty() || cleanSpec.isEmpty()) {
            throw HttpError.badRequest("Date and title are required");
        }
        String line = cleanSpec + " " + cleanTitle;
        if (parseRule(line, EVENTS_NOTE, 0) == null) {
            throw HttpError.badRequest("Cannot understand the date: " + cleanSpec);
        }
        String content = vault.exists(EVENTS_NOTE) ? vault.read(EVENTS_NOTE) : "# Calendar\n\n#calendar\n\n";
        String separator = content.isEmpty() || content.endsWith("\n") ? "" : "\n";
        String updated = content + separator + "- " + line + "\n";
        if (vault.exists(EVENTS_NOTE)) {
            notes.save(EVENTS_NOTE, updated);
        } else {
            notes.create(EVENTS_NOTE, updated);
        }
        return line;
    }

    public String ics() {
        StringBuilder out = new StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//kbmd//EN\r\nCALSCALE:GREGORIAN\r\n");
        DateTimeFormatter stamp = DateTimeFormatter.ofPattern("yyyyMMdd");
        for (Rule rule : rules()) {
            out.append("BEGIN:VEVENT\r\n");
            out.append("UID:").append(uid(rule)).append("@kbmd\r\n");
            out.append("SUMMARY:").append(escape(rule.title)).append("\r\n");
            LocalDate start = rule.start;
            if (rule.time != null) {
                out.append("DTSTART:").append(start.format(stamp)).append('T').append(rule.time.replace(":", "")).append("00\r\n");
                if (rule.endTime != null) {
                    out.append("DTEND:").append(start.format(stamp)).append('T').append(rule.endTime.replace(":", "")).append("00\r\n");
                }
            } else {
                out.append("DTSTART;VALUE=DATE:").append(start.format(stamp)).append("\r\n");
                LocalDate end = rule.repeat == Repeat.NONE && rule.end != null ? rule.end : start;
                out.append("DTEND;VALUE=DATE:").append(end.plusDays(1).format(stamp)).append("\r\n");
            }
            String rrule = rrule(rule);
            if (rrule != null) {
                out.append("RRULE:").append(rrule).append("\r\n");
            }
            out.append("DESCRIPTION:").append(escape(rule.notePath + " (line " + rule.line + ")")).append("\r\n");
            out.append("END:VEVENT\r\n");
        }
        return out.append("END:VCALENDAR\r\n").toString();
    }

    public static String rrule(Rule rule) {
        String until = rule.repeat != Repeat.NONE && rule.end != null ? ";UNTIL=" + rule.end.format(DateTimeFormatter.BASIC_ISO_DATE) : "";
        String interval = rule.interval > 1 ? ";INTERVAL=" + rule.interval : "";
        switch (rule.repeat) {
            case DAILY:
                return "FREQ=DAILY" + interval + until;
            case WEEKLY: {
                List<String> days = new ArrayList<>();
                for (DayOfWeek day : rule.days) {
                    days.add(day.name().substring(0, 2));
                }
                return "FREQ=WEEKLY" + interval + ";BYDAY=" + String.join(",", days) + until;
            }
            case MONTHLY:
                return "FREQ=MONTHLY" + interval + ";BYMONTHDAY=" + (rule.dayOfMonth < 0 ? "-1" : String.valueOf(rule.dayOfMonth)) + until;
            case YEARLY:
                return "FREQ=YEARLY" + interval + ";BYMONTH=" + rule.start.getMonthValue() + ";BYMONTHDAY=" + rule.start.getDayOfMonth() + until;
            default:
                return null;
        }
    }

    // ---------------------------------------------------------------- rules

    List<Rule> rules() {
        List<Rule> rules = new ArrayList<>();
        for (NoteIndex.TaggedNote note : index.notesTagged(TAG)) {
            String[] lines = note.content.split("\\R", -1);
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
                Rule rule = parseRule(item.group(1), note.path, i + 1);
                if (rule != null) {
                    rules.add(rule);
                }
            }
        }
        return rules;
    }

    static Rule parseRule(String text, String notePath, int line) {
        String rest = text.trim();
        String lower = rest.toLowerCase(Locale.ROOT);
        Repeat repeat = Repeat.NONE;
        LocalDate start = null;
        LocalDate end = null;
        int interval = 1;
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        int dayOfMonth = 0;
        boolean birthday = false;
        int consumed;

        Matcher range = DATE_RANGE.matcher(rest);
        if (range.find()) {
            start = parseDate(range.group(1));
            end = parseDate(range.group(2));
            if (start == null || end == null || end.isBefore(start)) {
                return null;
            }
            consumed = range.end();
        } else if (lower.startsWith("birthday ") || lower.startsWith("every ")) {
            List<String> words = new ArrayList<>(Arrays.asList(rest.split("\\s+")));
            int at = 1;
            if (lower.startsWith("birthday ")) {
                if (words.size() < 2 || (start = parseDate(words.get(1))) == null) {
                    return null;
                }
                repeat = Repeat.YEARLY;
                birthday = true;
                at = 2;
            } else {
                if (words.size() > at && words.get(at).matches("\\d+")) {
                    interval = Math.max(1, Integer.parseInt(words.get(at++)));
                }
                if (words.size() <= at) {
                    return null;
                }
                String unit = words.get(at).toLowerCase(Locale.ROOT);
                Set<DayOfWeek> listed = dayList(unit);
                if (unit.matches("days?")) {
                    repeat = Repeat.DAILY;
                    at++;
                } else if (unit.matches("weekdays?")) {
                    repeat = Repeat.WEEKLY;
                    days = EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
                    at++;
                } else if (unit.matches("weekends?")) {
                    repeat = Repeat.WEEKLY;
                    days = EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
                    at++;
                } else if (unit.matches("weeks?")) {
                    repeat = Repeat.WEEKLY;
                    at++;
                    if (words.size() > at && words.get(at).equalsIgnoreCase("on")) {
                        at++;
                    }
                    if (words.size() > at) {
                        Set<DayOfWeek> named = dayList(words.get(at));
                        if (named != null) {
                            days = named;
                            at++;
                        }
                    }
                } else if (listed != null) {
                    repeat = Repeat.WEEKLY;
                    days = listed;
                    at++;
                } else if (unit.matches("months?")) {
                    repeat = Repeat.MONTHLY;
                    at++;
                    if (words.size() > at && words.get(at).equalsIgnoreCase("on")) {
                        at++;
                    }
                    if (words.size() <= at) {
                        return null;
                    }
                    String day = words.get(at).toLowerCase(Locale.ROOT).replaceAll("(?<=\\d)(st|nd|rd|th)$", "");
                    if (day.equals("last")) {
                        dayOfMonth = -1;
                    } else if (day.matches("\\d{1,2}") && Integer.parseInt(day) >= 1 && Integer.parseInt(day) <= 31) {
                        dayOfMonth = Integer.parseInt(day);
                    } else {
                        return null;
                    }
                    at++;
                } else if (unit.matches("years?")) {
                    repeat = Repeat.YEARLY;
                    at++;
                    if (words.size() > at && words.get(at).equalsIgnoreCase("on")) {
                        at++;
                    }
                    if (words.size() <= at) {
                        return null;
                    }
                    String when = words.get(at);
                    start = when.matches("\\d{2}-\\d{2}") ? parseDate("2000-" + when) : parseDate(when);
                    if (start == null) {
                        return null;
                    }
                    at++;
                } else {
                    return null;
                }
            }
            consumed = 0;
            for (int i = 0; i < at; i++) {
                consumed = rest.indexOf(words.get(i), consumed) + words.get(i).length();
            }
        } else {
            Matcher single = DATE.matcher(rest);
            if (!single.lookingAt() || (start = parseDate(single.group(1))) == null) {
                return null;
            }
            consumed = single.end();
        }

        String time = null;
        String endTime = null;
        LocalDate from = null;
        String tail = rest.substring(consumed).trim();
        while (true) {
            Matcher t = TIME.matcher(tail);
            if (time == null && t.find()) {
                time = normalizeTime(t.group(1));
                endTime = t.group(2) == null ? null : normalizeTime(t.group(2));
                tail = tail.substring(t.end()).trim();
                continue;
            }
            Matcher m = MODIFIER.matcher(tail);
            if (m.find() && parseDate(m.group(2)) != null) {
                if (m.group(1).equalsIgnoreCase("from")) {
                    from = parseDate(m.group(2));
                } else {
                    end = parseDate(m.group(2));
                }
                tail = tail.substring(m.end()).trim();
                continue;
            }
            break;
        }
        Matcher trailing = TRAILING.matcher(tail);
        while (trailing.find() && parseDate(trailing.group(2)) != null) {
            if (trailing.group(1).equalsIgnoreCase("from")) {
                from = parseDate(trailing.group(2));
            } else {
                end = parseDate(trailing.group(2));
            }
            tail = tail.substring(0, trailing.start()).trim();
            trailing = TRAILING.matcher(tail);
        }
        String title = tail.trim();
        if (title.isEmpty()) {
            return null;
        }
        if (repeat == Repeat.DAILY || repeat == Repeat.WEEKLY || repeat == Repeat.MONTHLY) {
            start = from != null ? from : LocalDate.of(2000, 1, 3);
            if (repeat == Repeat.WEEKLY && days.isEmpty()) {
                days = EnumSet.of((from != null ? from : LocalDate.now()).getDayOfWeek());
            }
        } else if (repeat == Repeat.YEARLY && from != null) {
            start = from;
        }
        return new Rule(repeat, start, end, interval, days, dayOfMonth, birthday, time, endTime, title, notePath, line);
    }

    private static Set<DayOfWeek> dayList(String word) {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String part : word.toLowerCase(Locale.ROOT).split("[,/+&]")) {
            DayOfWeek day = DAYS.get(part.replaceAll("\\.$", ""));
            if (day == null) {
                return null;
            }
            days.add(day);
        }
        return days.isEmpty() ? null : days;
    }

    private static void expand(Rule rule, LocalDate from, LocalDate to, List<Occurrence> out) {
        LocalDate first = rule.start.isAfter(from) ? rule.start : from;
        LocalDate last = rule.end != null && rule.end.isBefore(to) ? rule.end : to;
        switch (rule.repeat) {
            case NONE: {
                LocalDate end = rule.end != null ? rule.end : rule.start;
                if (!rule.start.isAfter(to) && !end.isBefore(from)) {
                    out.add(occurrence(rule, rule.start, rule.end, null));
                }
                break;
            }
            case DAILY:
                for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
                    if (ChronoUnit.DAYS.between(rule.start, day) % rule.interval == 0) {
                        out.add(occurrence(rule, day, null, null));
                    }
                }
                break;
            case WEEKLY: {
                LocalDate anchorWeek = rule.start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
                    if (!rule.days.contains(day.getDayOfWeek())) {
                        continue;
                    }
                    long weeks = ChronoUnit.WEEKS.between(anchorWeek, day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
                    if (weeks % rule.interval == 0) {
                        out.add(occurrence(rule, day, null, null));
                    }
                }
                break;
            }
            case MONTHLY: {
                YearMonth month = YearMonth.from(first);
                YearMonth anchor = YearMonth.from(rule.start);
                for (; !month.atDay(1).isAfter(last); month = month.plusMonths(1)) {
                    if (ChronoUnit.MONTHS.between(anchor, month) % rule.interval != 0) {
                        continue;
                    }
                    LocalDate day = rule.dayOfMonth < 0 ? month.atEndOfMonth() : month.atDay(Math.min(rule.dayOfMonth, month.lengthOfMonth()));
                    if (!day.isBefore(first) && !day.isAfter(last)) {
                        out.add(occurrence(rule, day, null, null));
                    }
                }
                break;
            }
            case YEARLY:
                for (int year = first.getYear(); year <= last.getYear(); year++) {
                    if ((year - rule.start.getYear()) % rule.interval != 0) {
                        continue;
                    }
                    YearMonth month = YearMonth.of(year, rule.start.getMonth());
                    LocalDate day = month.atDay(Math.min(rule.start.getDayOfMonth(), month.lengthOfMonth()));
                    if (!day.isBefore(first) && !day.isAfter(last)) {
                        out.add(occurrence(rule, day, null, rule.birthday ? String.valueOf(year - rule.start.getYear()) : null));
                    }
                }
                break;
        }
    }

    private static Occurrence occurrence(Rule rule, LocalDate day, LocalDate endDay, String detail) {
        return new Occurrence(day.toString(), endDay == null ? null : endDay.toString(), rule.time, rule.endTime, rule.title,
                rule.notePath, rule.line, rule.birthday ? "birthday" : "event", rule.repeat != Repeat.NONE, detail, rrule(rule));
    }

    static LocalDate parseDate(String text) {
        try {
            return LocalDate.parse(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String normalizeTime(String time) {
        String[] hm = time.split(":");
        int h = Integer.parseInt(hm[0]);
        int m = Integer.parseInt(hm[1]);
        return h < 24 && m < 60 ? String.format(Locale.ROOT, "%02d:%02d", h, m) : null;
    }

    private static String uid(Rule rule) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest((rule.notePath + "#" + rule.line + "#" + rule.title).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                hex.append(String.format(Locale.ROOT, "%02x", digest[i] & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n");
    }

    public static final class Rule {
        public final Repeat repeat;
        public final LocalDate start;
        public final LocalDate end;
        public final int interval;
        public final Set<DayOfWeek> days;
        public final int dayOfMonth;
        public final boolean birthday;
        public final String time;
        public final String endTime;
        public final String title;
        public final String notePath;
        public final int line;

        Rule(Repeat repeat, LocalDate start, LocalDate end, int interval, Set<DayOfWeek> days, int dayOfMonth,
             boolean birthday, String time, String endTime, String title, String notePath, int line) {
            this.repeat = repeat;
            this.start = start;
            this.end = end;
            this.interval = interval;
            this.days = days;
            this.dayOfMonth = dayOfMonth;
            this.birthday = birthday;
            this.time = time;
            this.endTime = endTime;
            this.title = title;
            this.notePath = notePath;
            this.line = line;
        }
    }

    public static final class Occurrence {
        public final String date;
        public final String endDate;
        public final String time;
        public final String endTime;
        public final String title;
        public final String notePath;
        public final int line;
        public final String kind;
        public final boolean recurring;
        public final String detail;
        public final String rrule;

        Occurrence(String date, String endDate, String time, String endTime, String title, String notePath, int line,
                   String kind, boolean recurring, String detail, String rrule) {
            this.rrule = rrule;
            this.date = date;
            this.endDate = endDate;
            this.time = time;
            this.endTime = endTime;
            this.title = title;
            this.notePath = notePath;
            this.line = line;
            this.kind = kind;
            this.recurring = recurring;
            this.detail = detail;
        }
    }
}
