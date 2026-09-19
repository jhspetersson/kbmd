package dev.kbmd.android.flashcards;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import dev.kbmd.android.index.NoteIndex;
import dev.kbmd.android.markdown.MarkdownService;
import dev.kbmd.android.server.HttpError;
import dev.kbmd.android.vault.VaultService;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Anki-style flashcards. Cards are written inside notes tagged {@code #flashcards} (or {@code #flashcards/deck})
 * and scheduled with an SM-2 variant. Review history lives in {@code .flashcards.json} at the vault root: hidden
 * from the file tree, but included in sync so progress follows the vault. The file format is the web app's.
 */
public class FlashcardService {

    public static final String TAG = "flashcards";
    private static final String STATE_FILE = ".flashcards.json";
    private static final double DEFAULT_EASE = 2.5;
    private static final double MIN_EASE = 1.3;

    public enum Rating { AGAIN, HARD, GOOD, EASY }

    private final NoteIndex index;
    private final MarkdownService markdown;
    private final Path stateFile;

    public FlashcardService(VaultService vault, NoteIndex index, MarkdownService markdown) {
        this.index = index;
        this.markdown = markdown;
        this.stateFile = vault.root().resolve(STATE_FILE);
    }

    public synchronized List<Deck> decks() {
        LocalDate today = LocalDate.now();
        Map<String, int[]> counts = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, Set<String>> notes = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Card card : cards(loadStates())) {
            int[] count = counts.computeIfAbsent(card.deck, k -> new int[3]);
            notes.computeIfAbsent(card.deck, k -> new LinkedHashSet<>()).add(card.notePath);
            count[0]++;
            if (card.state == null) {
                count[1]++;
            } else if (!LocalDate.parse(card.state.due).isAfter(today)) {
                count[2]++;
            }
        }
        List<Deck> decks = new ArrayList<>();
        counts.forEach((name, count) -> decks.add(new Deck(name, count[0], count[1], count[2], new ArrayList<>(notes.get(name)))));
        return decks;
    }

    /** Cards to study now, due reviews and new cards shuffled together so neighbouring lines do not give each other away. */
    public synchronized List<StudyCard> due(String deck) {
        LocalDate today = LocalDate.now();
        List<StudyCard> queue = cards(loadStates()).stream()
                .filter(card -> deck == null || deck.trim().isEmpty() || card.deck.equalsIgnoreCase(deck))
                .filter(card -> card.state == null || !LocalDate.parse(card.state.due).isAfter(today))
                .map(this::toStudyCard)
                .collect(Collectors.toCollection(ArrayList::new));
        Collections.shuffle(queue);
        return queue;
    }

    public synchronized CardState review(String id, Rating rating) {
        Map<String, CardState> states = loadStates();
        if (cards(states).stream().noneMatch(card -> card.id.equals(id))) {
            throw HttpError.notFound("No such card (was the note edited?)");
        }
        CardState next = schedule(states.get(id), rating, LocalDate.now());
        states.put(id, next);
        saveStates(states);
        return next;
    }

    /**
     * Tab-separated file for Anki's File > Import. Uses Anki's header lines, so the deck and tag columns are
     * recognised automatically.
     */
    public synchronized String exportForAnki(String deck, String baseUrl) {
        StringBuilder out = new StringBuilder("#separator:tab\n#html:true\n#notetype:Basic\n#deck column:3\n#tags column:4\n");
        for (Card card : cards(Collections.emptyMap())) {
            if (deck != null && !deck.trim().isEmpty() && !card.deck.equalsIgnoreCase(deck)) {
                continue;
            }
            out.append(ankiField(render(card.front, card.notePath), baseUrl)).append('\t')
                    .append(ankiField(render(card.back, card.notePath), baseUrl)).append('\t')
                    .append(card.deck.replace("/", "::")).append('\t')
                    .append("kbmd").append('\n');
        }
        return out.toString();
    }

    static CardState schedule(CardState current, Rating rating, LocalDate today) {
        int interval = current == null ? 0 : current.interval;
        double ease = current == null ? DEFAULT_EASE : current.ease;
        int reps = current == null ? 0 : current.reps;
        int lapses = current == null ? 0 : current.lapses;
        switch (rating) {
            case AGAIN:
                ease -= 0.2;
                lapses += interval > 0 ? 1 : 0;
                interval = 0;
                break;
            case HARD:
                ease -= 0.15;
                interval = Math.max(1, (int) Math.round(interval * 1.2));
                break;
            case GOOD:
                interval = interval == 0 ? 1 : Math.max(interval + 1, (int) Math.round(interval * ease));
                break;
            case EASY:
                interval = interval == 0 ? 4 : Math.max(interval + 2, (int) Math.round(interval * ease * 1.3));
                ease += 0.15;
                break;
        }
        return new CardState(today.plusDays(interval).toString(), interval, Math.max(MIN_EASE, ease), reps + 1, lapses);
    }

    private List<Card> cards(Map<String, CardState> states) {
        Map<String, Card> cards = new LinkedHashMap<>();
        for (NoteIndex.TaggedNote note : index.notesTagged(TAG)) {
            // #flashcards/spanish/verbs -> deck "spanish/verbs"; plain #flashcards -> deck named after the note
            String deck = note.tags.stream().filter(tag -> tag.length() > TAG.length() + 1)
                    .map(tag -> tag.substring(TAG.length() + 1)).findFirst().orElse(note.title);
            for (FlashcardParser.ParsedCard parsed : FlashcardParser.parse(note.content)) {
                cards.putIfAbsent(parsed.id, new Card(parsed.id, deck, note.path, parsed.front, parsed.back,
                        parsed.line, states.get(parsed.id)));
            }
        }
        return new ArrayList<>(cards.values());
    }

    private StudyCard toStudyCard(Card card) {
        LocalDate today = LocalDate.now();
        Map<Rating, Integer> intervals = new LinkedHashMap<>();
        for (Rating rating : Rating.values()) {
            intervals.put(rating, schedule(card.state, rating, today).interval);
        }
        return new StudyCard(card.id, card.deck, card.notePath, card.line, card.state == null,
                render(card.front, card.notePath), render(card.back, card.notePath), intervals);
    }

    private String render(String text, String notePath) {
        // the deck tag is bookkeeping, not part of the question
        return markdown.render(text.replaceAll("(?i)(^|\\s)#" + TAG + "[\\w/-]*", "$1"), notePath, index);
    }

    private static String ankiField(String html, String baseUrl) {
        return html.replace("\"/api/", "\"" + baseUrl + "/api/").replace("\t", " ").replaceAll("\\R", " ").trim();
    }

    private Map<String, CardState> loadStates() {
        Map<String, CardState> states = new TreeMap<>();
        if (!Files.isRegularFile(stateFile)) {
            return states;
        }
        try {
            JSONObject json = new JSONObject(new String(Files.readAllBytes(stateFile), StandardCharsets.UTF_8));
            for (Iterator<String> ids = json.keys(); ids.hasNext(); ) {
                String id = ids.next();
                JSONObject state = json.getJSONObject(id);
                states.put(id, new CardState(state.getString("due"), state.getInt("interval"), state.getDouble("ease"),
                        state.optInt("reps"), state.optInt("lapses")));
            }
            return states;
        } catch (IOException | JSONException e) {
            throw new HttpError(500, "Cannot read " + STATE_FILE + ": " + e.getMessage());
        }
    }

    /** Sorted, one field per line and spaced like the web app writes it, so merges of review progress rarely conflict. */
    private void saveStates(Map<String, CardState> states) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, CardState> entry : new TreeMap<>(states).entrySet()) {
            CardState state = entry.getValue();
            out.append(first ? "\n" : ",\n")
                    .append("  ").append(JSONObject.quote(entry.getKey())).append(" : {\n")
                    .append("    \"due\" : ").append(JSONObject.quote(state.due)).append(",\n")
                    .append("    \"interval\" : ").append(state.interval).append(",\n")
                    .append("    \"ease\" : ").append(state.ease).append(",\n")
                    .append("    \"reps\" : ").append(state.reps).append(",\n")
                    .append("    \"lapses\" : ").append(state.lapses).append("\n")
                    .append("  }");
            first = false;
        }
        out.append(first ? " }" : "\n}");
        try {
            Files.write(stateFile, out.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new HttpError(500, "Cannot write " + STATE_FILE + ": " + e.getMessage());
        }
    }

    public static final class CardState {
        public final String due;
        public final int interval;
        public final double ease;
        public final int reps;
        public final int lapses;

        CardState(String due, int interval, double ease, int reps, int lapses) {
            this.due = due;
            this.interval = interval;
            this.ease = ease;
            this.reps = reps;
            this.lapses = lapses;
        }
    }

    private static final class Card {
        final String id;
        final String deck;
        final String notePath;
        final String front;
        final String back;
        final int line;
        final CardState state;

        Card(String id, String deck, String notePath, String front, String back, int line, CardState state) {
            this.id = id;
            this.deck = deck;
            this.notePath = notePath;
            this.front = front;
            this.back = back;
            this.line = line;
            this.state = state;
        }
    }

    public static final class Deck {
        public final String name;
        public final int total;
        public final int fresh;
        public final int due;
        /** the notes the cards come from, in vault order */
        public final List<String> notes;

        Deck(String name, int total, int fresh, int due, List<String> notes) {
            this.name = name;
            this.total = total;
            this.fresh = fresh;
            this.due = due;
            this.notes = notes;
        }
    }

    public static final class StudyCard {
        public final String id;
        public final String deck;
        public final String notePath;
        public final int line;
        public final boolean fresh;
        public final String frontHtml;
        public final String backHtml;
        public final Map<Rating, Integer> intervals;

        StudyCard(String id, String deck, String notePath, int line, boolean fresh, String frontHtml, String backHtml,
                  Map<Rating, Integer> intervals) {
            this.id = id;
            this.deck = deck;
            this.notePath = notePath;
            this.line = line;
            this.fresh = fresh;
            this.frontHtml = frontHtml;
            this.backHtml = backHtml;
            this.intervals = intervals;
        }
    }
}
