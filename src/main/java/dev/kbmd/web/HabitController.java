package dev.kbmd.web;

import java.util.Map;

import dev.kbmd.habits.HabitService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/habits")
public class HabitController {

    private final HabitService habits;

    public HabitController(HabitService habits) {
        this.habits = habits;
    }

    @GetMapping
    public HabitService.Board board(@RequestParam(defaultValue = "14") int days) {
        return habits.board(days);
    }

    @PostMapping("/toggle")
    public Map<String, Boolean> toggle(@RequestBody ToggleRequest request) {
        return Map.of("done", habits.toggle(request.id(), request.date()));
    }

    public record ToggleRequest(String id, String date) {
    }
}
