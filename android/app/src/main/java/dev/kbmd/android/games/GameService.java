package dev.kbmd.android.games;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import dev.kbmd.android.server.HttpError;
import dev.kbmd.android.vault.VaultService;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Best scores of the built-in games, kept in {@code .games.json} at the vault root in the web app's format, so the
 * phone and the desktop show the same records through sync. A time (Minesweeper) is a score where lower is better.
 */
public class GameService {

    private static final String STATE_FILE = ".games.json";
    private static final Pattern GAME = Pattern.compile("[a-z0-9-]{1,40}");

    private final Path stateFile;

    public GameService(VaultService vault) {
        this.stateFile = vault.root().resolve(STATE_FILE);
    }

    public synchronized JSONObject scores() throws JSONException {
        JSONObject out = new JSONObject();
        for (Map.Entry<String, Long> entry : loadState().entrySet()) {
            out.put(entry.getKey(), entry.getValue());
        }
        return out;
    }

    /** Keeps the score when it beats the stored one; returns the best after that, which may still be the old one. */
    public synchronized long record(String game, long score, boolean lowerIsBetter) {
        if (game == null || !GAME.matcher(game).matches()) {
            throw HttpError.badRequest("Bad game name");
        }
        if (score < 0) {
            throw HttpError.badRequest("Bad score");
        }
        Map<String, Long> state = loadState();
        Long current = state.get(game);
        if (current != null && (lowerIsBetter ? score >= current : score <= current)) {
            return current;
        }
        state.put(game, score);
        saveState(state);
        return score;
    }

    private Map<String, Long> loadState() {
        Map<String, Long> state = new TreeMap<>();
        if (!Files.isRegularFile(stateFile)) {
            return state;
        }
        try {
            JSONObject json = new JSONObject(new String(Files.readAllBytes(stateFile), StandardCharsets.UTF_8));
            for (Iterator<String> games = json.keys(); games.hasNext(); ) {
                String game = games.next();
                state.put(game, json.getLong(game));
            }
            return state;
        } catch (IOException | JSONException e) {
            throw new HttpError(500, "Cannot read " + STATE_FILE + ": " + e.getMessage());
        }
    }

    /** Sorted and laid out like the web app writes it (one game per line), so merges from two devices rarely conflict. */
    private void saveState(Map<String, Long> state) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Long> entry : state.entrySet()) {
            out.append(first ? "\n" : ",\n").append("  ").append(JSONObject.quote(entry.getKey())).append(" : ").append(entry.getValue());
            first = false;
        }
        out.append(first ? " }" : "\n}");
        try {
            Files.write(stateFile, out.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new HttpError(500, "Cannot write " + STATE_FILE + ": " + e.getMessage());
        }
    }
}
