package dev.kbmd.android.flashcards;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds flashcards in a note. The syntax follows the Obsidian spaced-repetition conventions:
 * <pre>
 * Question::Answer             one card
 * Term:::Definition            two cards, one in each direction
 *
 * A longer question            multi-line card: "?" separates the sides ("??" also adds the reverse);
 * ?                            the card ends at the next blank line
 * Its answer
 *
 * The ==mitochondria== is ...  cloze: one card per highlighted part
 * </pre>
 * Fenced code is ignored. Card ids are computed exactly as in the web app, so review progress syncs between both.
 */
final class FlashcardParser {

    static final class ParsedCard {
        final String id;
        final String front;
        final String back;
        final int line;

        ParsedCard(String id, String front, String back, int line) {
            this.id = id;
            this.front = front;
            this.back = back;
            this.line = line;
        }
    }

    private static final Pattern SINGLE_LINE = Pattern.compile("^(.+?)\\s*(:::|::)\\s*(.+)$");
    private static final Pattern LIST_MARKER = Pattern.compile("^\\s*(?:[-*+]|\\d+[.)])\\s+(?:\\[[ xX]\\]\\s+)?");
    private static final Pattern CLOZE = Pattern.compile("==(.+?)==");
    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~).*");
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`]*`");

    private FlashcardParser() {
    }

    static List<ParsedCard> parse(String content) {
        List<ParsedCard> cards = new ArrayList<>();
        String[] lines = content.split("\\R", -1);
        List<String> block = new ArrayList<>();
        int blockStart = 0;
        boolean fenced = false;
        boolean blockHasCode = false;
        for (int i = 0; i <= lines.length; i++) {
            String line = i < lines.length ? lines[i] : "";
            boolean fence = i < lines.length && FENCE.matcher(line).matches();
            if (fence) {
                fenced = !fenced;
                blockHasCode = true;
            }
            // a fenced block may contain blank lines and still belongs to the surrounding card
            if (i < lines.length && (fenced || fence || !line.trim().isEmpty())) {
                if (block.isEmpty()) {
                    blockStart = i;
                }
                block.add(line);
                continue;
            }
            if (!block.isEmpty()) {
                parseBlock(block, blockStart + 1, blockHasCode, cards);
                block.clear();
                blockHasCode = false;
            }
        }
        return cards;
    }

    private static void parseBlock(List<String> block, int firstLine, boolean hasCode, List<ParsedCard> cards) {
        int separator = -1;
        for (int i = 0; i < block.size() && separator < 0; i++) {
            String stripped = strip(block.get(i));
            if (FENCE.matcher(block.get(i)).matches()) {
                break; // a "?" inside code is not a separator
            }
            if (stripped.equals("?") || stripped.equals("??")) {
                separator = i;
            }
        }
        if (separator > 0 && separator < block.size() - 1) {
            String front = strip(String.join("\n", block.subList(0, separator)));
            String back = strip(String.join("\n", block.subList(separator + 1, block.size())));
            add(cards, front, back, firstLine);
            if (strip(block.get(separator)).equals("??")) {
                add(cards, back, front, firstLine);
            }
            return;
        }
        if (hasCode) {
            return;
        }

        int before = cards.size();
        for (int i = 0; i < block.size(); i++) {
            String line = LIST_MARKER.matcher(block.get(i)).replaceFirst("");
            Matcher matcher = SINGLE_LINE.matcher(withoutInlineCode(line));
            if (!matcher.matches()) {
                continue;
            }
            // positions are the same in the masked and the original line
            String front = strip(line.substring(0, matcher.end(1)));
            String back = strip(line.substring(matcher.start(3)));
            add(cards, front, back, firstLine + i);
            if (matcher.group(2).equals(":::")) {
                add(cards, back, front, firstLine + i);
            }
        }
        if (cards.size() > before) {
            return;
        }

        String text = String.join("\n", block);
        Matcher cloze = CLOZE.matcher(text);
        int index = 0;
        while (cloze.find()) {
            String front = text.substring(0, cloze.start()) + "**[…]**" + text.substring(cloze.end());
            String back = text.substring(0, cloze.start()) + "**" + cloze.group(1) + "**" + text.substring(cloze.end());
            // the hidden part is in the id, so every cloze of a paragraph is its own card
            cards.add(new ParsedCard(hash(text + " " + index++), unhighlight(front), unhighlight(back), firstLine));
        }
    }

    private static void add(List<ParsedCard> cards, String front, String back, int line) {
        if (!front.isEmpty() && !back.isEmpty()) {
            cards.add(new ParsedCard(hash(front), front, back, line));
        }
    }

    private static String unhighlight(String text) {
        return CLOZE.matcher(text).replaceAll("$1");
    }

    /** Masks `inline code` with filler of the same length, so a "::" inside it does not split the line. */
    private static String withoutInlineCode(String line) {
        StringBuilder masked = new StringBuilder(line);
        Matcher code = INLINE_CODE.matcher(line);
        while (code.find()) {
            for (int i = code.start(); i < code.end(); i++) {
                masked.setCharAt(i, 'x');
            }
        }
        return masked.toString();
    }

    /** Cards are identified by their question, so they keep their schedule when the note moves or the answer is edited. */
    private static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(strip(text).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 10; i++) {
                hex.append(String.format("%02x", digest[i] & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * {@code String.strip()} of newer Java versions: removes Unicode whitespace, where {@code trim()} removes
     * everything up to U+0020. Ids must match the web app's, so the difference matters here.
     */
    static String strip(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }
}
