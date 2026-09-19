package dev.kbmd.calendar;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.kbmd.index.NoteIndex;
import dev.kbmd.tasks.TaskService;
import dev.kbmd.vault.NoteService;
import dev.kbmd.vault.VaultService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Calendar. Events are the list items of notes tagged {@code #calendar}, each starting with a date or a rule:
 * <pre>
 * - 2026-09-25 14:30 Dentist
 * - 2026-10-03..2026-10-05 Trip
 * - every day 08:00 Standup
 * - every Mon,Wed,Fri 07:00 Gym            (also: every week on Mon; every 2 weeks Tue from 2026-09-15)
 * - every month 1 Rent                     (also: every month last ...)
 * - every year 03-14 Pi day                (also: every year 1990-03-14 ...)
 * - birthday 1990-03-14 Mom                (yearly, with the age)
 * - birthday 15 May Mary                   (no age; also: 05-15, 15.05, 15.May, May 15, May, 15)
 * </pre>
 * {@code until YYYY-MM-DD} ends a rule. Daily notes and tasks with a due date appear in the calendar as well.
 */
@Service
public class CalendarService {

    public static final String TAG = "calendar";
    public static final String EVENTS_NOTE = "Calendar.md";
    private static final int MAX_DAYS = 400;

    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:[-*+]|\\d+[.)])\\s+(?:\\[[ xX]\\]\\s+)?(.+?)\\s*$");
    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~).*");
    private static final Pattern DATE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern DATE_RANGE = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})(?:\\.\\.|\\s*-\\s*|\\s+to\\s+)(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern TIME = Pattern.compile("^(\\d{1,2}:\\d{2})(?:\\s*-\\s*(\\d{1,2}:\\d{2}))?");
    private static final Pattern DAILY_NOTE = Pattern.compile("(?:^|/)(\\d{4}-\\d{2}-\\d{2})\\.md$");
    private static final Pattern MONTH_DAY_DIGITS = Pattern.compile("(\\d{1,2})-(\\d{1,2})");
    private static final Pattern DAY_MONTH_DIGITS = Pattern.compile("(\\d{1,2})\\.(\\d{1,2})(?:\\.(\\d{4}))?");
    private static final String DATE_SEPARATORS = "[.,/-]+";
    private static final Map<String, DayOfWeek> DAYS = Map.ofEntries(
            Map.entry("mon", DayOfWeek.MONDAY), Map.entry("monday", DayOfWeek.MONDAY),
            Map.entry("tue", DayOfWeek.TUESDAY), Map.entry("tues", DayOfWeek.TUESDAY), Map.entry("tuesday", DayOfWeek.TUESDAY),
            Map.entry("wed", DayOfWeek.WEDNESDAY), Map.entry("wednesday", DayOfWeek.WEDNESDAY),
            Map.entry("thu", DayOfWeek.THURSDAY), Map.entry("thur", DayOfWeek.THURSDAY), Map.entry("thurs", DayOfWeek.THURSDAY), Map.entry("thursday", DayOfWeek.THURSDAY),
            Map.entry("fri", DayOfWeek.FRIDAY), Map.entry("friday", DayOfWeek.FRIDAY),
            Map.entry("sat", DayOfWeek.SATURDAY), Map.entry("saturday", DayOfWeek.SATURDAY),
            Map.entry("sun", DayOfWeek.SUNDAY), Map.entry("sunday", DayOfWeek.SUNDAY));

    enum Repeat { NONE, DAILY, WEEKLY, MONTHLY, YEARLY }

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

    /** Everything happening between two dates (inclusive): events, daily notes and due tasks. */
    public List<Occurrence> occurrences(LocalDate from, LocalDate to) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > MAX_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ask for at most " + MAX_DAYS + " days");
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
            if (task.due() == null || task.done()) {
                continue;
            }
            LocalDate day = parseDate(task.due());
            if (day != null && !day.isBefore(from) && !day.isAfter(to)) {
                out.add(new Occurrence(day.toString(), null, null, null, task.text(), task.notePath(), task.line(), "task", false, null, null));
            }
        }
        out.sort(Comparator.comparing(Occurrence::date).thenComparing(o -> o.time() == null ? "" : o.time()).thenComparing(Occurrence::title));
        return out;
    }

    /** Appends an event line to the events note (created on first use) and returns it. */
    public synchronized String add(String spec, String title) {
        String cleanTitle = title == null ? "" : title.strip();
        String cleanSpec = spec == null ? "" : spec.strip();
        if (cleanTitle.isEmpty() || cleanSpec.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Date and title are required");
        }
        String line = cleanSpec + " " + cleanTitle;
        if (parseRule(line, EVENTS_NOTE, 0) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot understand the date: " + cleanSpec);
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

    /** All rules as an iCalendar file, recurrence included, for the phone's or the desktop's calendar app. */
    public String ics() {
        StringBuilder out = new StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//kbmd//EN\r\nCALSCALE:GREGORIAN\r\n");
        DateTimeFormatter stamp = DateTimeFormatter.ofPattern("yyyyMMdd");
        for (Rule rule : rules()) {
            out.append("BEGIN:VEVENT\r\n");
            out.append("UID:").append(uid(rule)).append("@kbmd\r\n");
            out.append("SUMMARY:").append(escape(rule.title())).append("\r\n");
            LocalDate start = rule.start();
            if (rule.time() != null) {
                String[] hm = rule.time().split(":");
                String startTime = String.format("%02d%02d00", Integer.parseInt(hm[0]), Integer.parseInt(hm[1]));
                out.append("DTSTART:").append(start.format(stamp)).append('T').append(startTime).append("\r\n");
                if (rule.endTime() != null) {
                    String[] eh = rule.endTime().split(":");
                    out.append("DTEND:").append(start.format(stamp)).append('T')
                            .append(String.format("%02d%02d00", Integer.parseInt(eh[0]), Integer.parseInt(eh[1]))).append("\r\n");
                }
            } else {
                out.append("DTSTART;VALUE=DATE:").append(start.format(stamp)).append("\r\n");
                LocalDate end = rule.repeat() == Repeat.NONE && rule.end() != null ? rule.end() : start;
                out.append("DTEND;VALUE=DATE:").append(end.plusDays(1).format(stamp)).append("\r\n");
            }
            String rrule = rrule(rule);
            if (rrule != null) {
                out.append("RRULE:").append(rrule).append("\r\n");
            }
            out.append("DESCRIPTION:").append(escape(rule.notePath() + " (line " + rule.line() + ")")).append("\r\n");
            out.append("END:VEVENT\r\n");
        }
        return out.append("END:VCALENDAR\r\n").toString();
    }

    /** The RRULE of a rule, or null when it does not repeat. */
    public static String rrule(Rule rule) {
        String until = rule.repeat() != Repeat.NONE && rule.end() != null ? ";UNTIL=" + rule.end().format(DateTimeFormatter.BASIC_ISO_DATE) : "";
        String interval = rule.interval() > 1 ? ";INTERVAL=" + rule.interval() : "";
        return switch (rule.repeat()) {
            case NONE -> null;
            case DAILY -> "FREQ=DAILY" + interval + until;
            case WEEKLY -> "FREQ=WEEKLY" + interval + ";BYDAY=" + String.join(",", rule.days().stream()
                    .map(d -> d.name().substring(0, 2)).toList()) + until;
            case MONTHLY -> "FREQ=MONTHLY" + interval + ";BYMONTHDAY=" + (rule.dayOfMonth() < 0 ? "-1" : rule.dayOfMonth()) + until;
            case YEARLY -> "FREQ=YEARLY" + interval + ";BYMONTH=" + rule.start().getMonthValue() + ";BYMONTHDAY=" + rule.start().getDayOfMonth() + until;
        };
    }

    // ---------------------------------------------------------------- rules

    List<Rule> rules() {
        List<Rule> rules = new ArrayList<>();
        for (NoteIndex.TaggedNote note : index.notesTagged(TAG)) {
            String[] lines = note.content().split("\\R", -1);
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
                Rule rule = parseRule(item.group(1), note.path(), i + 1);
                if (rule != null) {
                    rules.add(rule);
                }
            }
        }
        return rules;
    }

    /** Null when the line does not start with a date or a rule. */
    static Rule parseRule(String text, String notePath, int line) {
        String rest = text.strip();
        String lower = rest.toLowerCase(Locale.ROOT);
        Repeat repeat = Repeat.NONE;
        LocalDate start = null;
        LocalDate end = null;
        int interval = 1;
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        int dayOfMonth = 0;
        boolean birthday = false;
        boolean knownYear = true;
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
            List<String> words = new ArrayList<>(List.of(rest.split("\\s+")));
            int at = 1;
            if (lower.startsWith("birthday ")) {
                BirthDate born = parseBirthDate(words, 1);
                if (born == null) {
                    return null;
                }
                start = born.date();
                knownYear = born.knownYear();
                repeat = Repeat.YEARLY;
                birthday = true;
                at = 1 + born.words();
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
                    // without a year the rule has always applied: 2000 is a leap year, so 02-29 parses too
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

        // modifiers after the date part: a time, "from", "until"
        String time = null;
        String endTime = null;
        LocalDate from = null;
        String tail = rest.substring(consumed).strip();
        while (true) {
            Matcher t = TIME.matcher(tail);
            if (time == null && t.find()) {
                time = normalizeTime(t.group(1));
                endTime = t.group(2) == null ? null : normalizeTime(t.group(2));
                tail = tail.substring(t.end()).strip();
                continue;
            }
            Matcher m = Pattern.compile("^(from|until|till|to)\\s+(\\d{4}-\\d{2}-\\d{2})", Pattern.CASE_INSENSITIVE).matcher(tail);
            if (m.find() && parseDate(m.group(2)) != null) {
                if (m.group(1).equalsIgnoreCase("from")) {
                    from = parseDate(m.group(2));
                } else {
                    end = parseDate(m.group(2));
                }
                tail = tail.substring(m.end()).strip();
                continue;
            }
            break;
        }
        // "from" and "until" may also close the line, after the title
        Matcher trailing = Pattern.compile("\\s+(from|until|till)\\s+(\\d{4}-\\d{2}-\\d{2})$", Pattern.CASE_INSENSITIVE).matcher(tail);
        while (trailing.find() && parseDate(trailing.group(2)) != null) {
            if (trailing.group(1).equalsIgnoreCase("from")) {
                from = parseDate(trailing.group(2));
            } else {
                end = parseDate(trailing.group(2));
            }
            tail = tail.substring(0, trailing.start()).strip();
            trailing = Pattern.compile("\\s+(from|until|till)\\s+(\\d{4}-\\d{2}-\\d{2})$", Pattern.CASE_INSENSITIVE).matcher(tail);
        }
        String title = tail.strip();
        if (title.isEmpty()) {
            return null;
        }
        if (repeat == Repeat.DAILY || repeat == Repeat.WEEKLY || repeat == Repeat.MONTHLY) {
            // "from" anchors the rule (and the phase of "every 2 weeks"); without it the rule has always applied
            start = from != null ? from : LocalDate.of(2000, 1, 3);
            if (repeat == Repeat.WEEKLY && days.isEmpty()) {
                days = EnumSet.of((from != null ? from : LocalDate.now()).getDayOfWeek());
            }
        } else if (repeat == Repeat.YEARLY && from != null) {
            start = from;
        }
        return new Rule(repeat, start, end, interval, days, dayOfMonth, birthday, knownYear, time, endTime, title, notePath, line);
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
        LocalDate first = rule.start().isAfter(from) ? rule.start() : from;
        LocalDate last = rule.end() != null && rule.end().isBefore(to) ? rule.end() : to;
        switch (rule.repeat()) {
            case NONE -> {
                LocalDate end = rule.end() != null ? rule.end() : rule.start();
                if (!rule.start().isAfter(to) && !end.isBefore(from)) {
                    out.add(occurrence(rule, rule.start(), rule.end(), null));
                }
            }
            case DAILY -> {
                for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
                    if (ChronoUnit.DAYS.between(rule.start(), day) % rule.interval() == 0) {
                        out.add(occurrence(rule, day, null, null));
                    }
                }
            }
            case WEEKLY -> {
                LocalDate anchorWeek = rule.start().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
                    if (!rule.days().contains(day.getDayOfWeek())) {
                        continue;
                    }
                    long weeks = ChronoUnit.WEEKS.between(anchorWeek, day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
                    if (weeks % rule.interval() == 0) {
                        out.add(occurrence(rule, day, null, null));
                    }
                }
            }
            case MONTHLY -> {
                YearMonth month = YearMonth.from(first);
                YearMonth anchor = YearMonth.from(rule.start());
                for (; !month.atDay(1).isAfter(last); month = month.plusMonths(1)) {
                    if (ChronoUnit.MONTHS.between(anchor, month) % rule.interval() != 0) {
                        continue;
                    }
                    LocalDate day = rule.dayOfMonth() < 0 ? month.atEndOfMonth() : month.atDay(Math.min(rule.dayOfMonth(), month.lengthOfMonth()));
                    if (!day.isBefore(first) && !day.isAfter(last)) {
                        out.add(occurrence(rule, day, null, null));
                    }
                }
            }
            case YEARLY -> {
                for (int year = first.getYear(); year <= last.getYear(); year++) {
                    if ((year - rule.start().getYear()) % rule.interval() != 0) {
                        continue;
                    }
                    YearMonth month = YearMonth.of(year, rule.start().getMonth());
                    LocalDate day = month.atDay(Math.min(rule.start().getDayOfMonth(), month.lengthOfMonth()));
                    if (!day.isBefore(first) && !day.isAfter(last)) {
                        String detail = rule.birthday() && rule.knownYear() ? (year - rule.start().getYear()) + "" : null;
                        out.add(occurrence(rule, day, null, detail));
                    }
                }
            }
        }
    }

    private static Occurrence occurrence(Rule rule, LocalDate day, LocalDate endDay, String detail) {
        return new Occurrence(day.toString(), endDay == null ? null : endDay.toString(), rule.time(), rule.endTime(), rule.title(),
                rule.notePath(), rule.line(), rule.birthday() ? "birthday" : "event", rule.repeat() != Repeat.NONE, detail, rrule(rule));
    }

    /**
     * The date after {@code birthday}, starting at word {@code at}: a full date ({@code 1990-03-14}, {@code 14.03.1990})
     * or, when the year is unknown, a month and a day: {@code 03-14}, {@code 14.03}, {@code 14 March}, {@code March 14},
     * {@code March, 14}, {@code 14.Mar}. Null when the words do not start with a date.
     */
    static BirthDate parseBirthDate(List<String> words, int at) {
        if (words.size() <= at) {
            return null;
        }
        String first = words.get(at);
        LocalDate full = parseDate(first);
        if (full != null) {
            return new BirthDate(full, true, 1);
        }
        Matcher m = MONTH_DAY_DIGITS.matcher(first);
        if (m.matches()) {
            return birthDate(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), null, 1);
        }
        m = DAY_MONTH_DIGITS.matcher(first);
        if (m.matches()) {
            return birthDate(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)), m.group(3) == null ? null : Integer.valueOf(m.group(3)), 1);
        }
        // the month by name, in one word ("14.March") or two ("14 March", "March 14", "March, 14")
        List<String> parts = new ArrayList<>(Arrays.asList(first.split(DATE_SEPARATORS)));
        parts.removeIf(String::isEmpty);
        int taken = 1;
        if (parts.size() == 1 && words.size() > at + 1) {
            parts.addAll(Arrays.asList(words.get(at + 1).split(DATE_SEPARATORS)));
            parts.removeIf(String::isEmpty);
            taken = 2;
        }
        if (parts.size() != 2) {
            return null;
        }
        boolean dayFirst = parts.get(0).matches("\\d{1,2}");
        String day = parts.get(dayFirst ? 0 : 1);
        Month month = monthNamed(parts.get(dayFirst ? 1 : 0));
        if (month == null || !day.matches("\\d{1,2}")) {
            return null;
        }
        return birthDate(month.getValue(), Integer.parseInt(day), null, taken);
    }

    /** The English month whose name starts with {@code text} (at least three letters): March, mar, Sept. */
    private static Month monthNamed(String text) {
        String name = text.toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z]{3,}")) {
            return null;
        }
        for (Month month : Month.values()) {
            if (month.name().toLowerCase(Locale.ROOT).startsWith(name)) {
                return month;
            }
        }
        return null;
    }

    /** Null when the day does not exist. Without a year the date is placed in 2000, a leap year, so 02-29 is valid. */
    private static BirthDate birthDate(int month, int day, Integer year, int words) {
        try {
            return new BirthDate(LocalDate.of(year == null ? 2000 : year, month, day), year != null, words);
        } catch (DateTimeException e) {
            return null;
        }
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
        return h < 24 && m < 60 ? String.format("%02d:%02d", h, m) : null;
    }

    private static String uid(Rule rule) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest((rule.notePath() + "#" + rule.line() + "#" + rule.title()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n");
    }

    /**
     * @param start      first date (the birth date for birthdays; the anchor for weekly/monthly rules)
     * @param end        last date of a range, or the last date a rule applies (null: open-ended)
     * @param dayOfMonth for monthly rules, -1 meaning the last day
     * @param knownYear  false for a birthday given as a month and a day only: the age is unknown
     */
    public record Rule(Repeat repeat, LocalDate start, LocalDate end, int interval, Set<DayOfWeek> days, int dayOfMonth,
                       boolean birthday, boolean knownYear, String time, String endTime, String title, String notePath, int line) {
    }

    /** A parsed birth date and how many words it took; {@code knownYear} is false for a month and a day only. */
    record BirthDate(LocalDate date, boolean knownYear, int words) {
    }

    /**
     * {@code kind}: event, birthday, daily (a daily note) or task (a task with a due date). {@code detail}: the age for
     * birthdays. {@code rrule}: the iCalendar recurrence, for handing the event to another calendar.
     */
    public record Occurrence(String date, String endDate, String time, String endTime, String title, String notePath, int line,
                             String kind, boolean recurring, String detail, String rrule) {
    }
}
