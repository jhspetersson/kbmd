package dev.kbmd.web;

import java.util.List;
import java.util.Map;

import dev.kbmd.tasks.TaskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class TaskController {

    private final TaskService tasks;

    public TaskController(TaskService tasks) {
        this.tasks = tasks;
    }

    @GetMapping("/tasks")
    public List<TaskService.Task> tasks() {
        return tasks.tasks();
    }

    @PostMapping("/tasks")
    public TaskService.Task add(@RequestBody AddTaskRequest request) {
        return tasks.add(request.text(), request.path());
    }

    @PostMapping("/tasks/toggle")
    public Map<String, Boolean> toggle(@RequestBody LineRequest request) {
        return Map.of("done", tasks.toggle(request.path(), request.line()));
    }

    @GetMapping("/kanban")
    public List<TaskService.Board> boards() {
        return tasks.boards();
    }

    @PostMapping("/kanban/move")
    public TaskService.Board move(@RequestBody MoveCardRequest request) {
        return tasks.move(request.path(), request.line(), request.column(), request.position());
    }

    @PostMapping("/kanban/card")
    public TaskService.Board addCard(@RequestBody AddCardRequest request) {
        return tasks.addCard(request.path(), request.column(), request.text());
    }

    public record AddTaskRequest(String text, String path) {
    }

    public record LineRequest(String path, int line) {
    }

    public record MoveCardRequest(String path, int line, String column, int position) {
    }

    public record AddCardRequest(String path, String column, String text) {
    }
}
