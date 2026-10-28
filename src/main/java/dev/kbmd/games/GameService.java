package dev.kbmd.games;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import dev.kbmd.vault.VaultService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Best scores of the built-in games, kept in {@code .games.json} at the vault root: hidden from the file tree,
 * included in sync, so every device shows the same records. One number per game; a time (Minesweeper) is a score
 * where lower is better.
 */
@Service
public class GameService {

    private static final String STATE_FILE = ".games.json";
    private static final Pattern GAME = Pattern.compile("[a-z0-9-]{1,40}");

    private final JsonMapper json;
    private final Path stateFile;

    public GameService(VaultService vault, JsonMapper json) {
        this.json = json;
        this.stateFile = vault.root().resolve(STATE_FILE);
    }

    public synchronized Map<String, Long> scores() {
        return loadState();
    }

    /** Keeps the score when it beats the stored one; returns the best after that, which may still be the old one. */
    public synchronized long record(String game, long score, boolean lowerIsBetter) {
        if (game == null || !GAME.matcher(game).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bad game name");
        }
        if (score < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bad score");
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
            state.putAll(json.readValue(Files.readString(stateFile, StandardCharsets.UTF_8), new TypeReference<Map<String, Long>>() {
            }));
            return state;
        } catch (IOException | RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot read " + STATE_FILE + ": " + e.getMessage());
        }
    }

    private void saveState(Map<String, Long> state) {
        try {
            // sorted and one game per line, so Git merges from two devices rarely conflict; LF like the phone writes it
            String text = json.writerWithDefaultPrettyPrinter().writeValueAsString(state).replace("\r\n", "\n");
            Files.writeString(stateFile, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot write " + STATE_FILE + ": " + e.getMessage());
        }
    }
}
