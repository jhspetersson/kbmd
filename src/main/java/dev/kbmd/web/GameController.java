package dev.kbmd.web;

import java.util.Map;

import dev.kbmd.games.GameService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/games")
public class GameController {

    private final GameService games;

    public GameController(GameService games) {
        this.games = games;
    }

    @GetMapping("/scores")
    public Map<String, Long> scores() {
        return games.scores();
    }

    @PostMapping("/score")
    public Map<String, Long> record(@RequestBody ScoreRequest request) {
        return Map.of("best", games.record(request.game(), request.score(), request.lowerIsBetter()));
    }

    public record ScoreRequest(String game, long score, boolean lowerIsBetter) {
    }
}
