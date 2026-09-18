package dev.kbmd.web;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import dev.kbmd.calendar.CalendarService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/calendar")
public class CalendarController {

    private final CalendarService calendar;

    public CalendarController(CalendarService calendar) {
        this.calendar = calendar;
    }

    @GetMapping
    public List<CalendarService.Occurrence> occurrences(@RequestParam String from, @RequestParam String to) {
        try {
            return calendar.occurrences(LocalDate.parse(from), LocalDate.parse(to));
        } catch (java.time.format.DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dates must be YYYY-MM-DD");
        }
    }

    @PostMapping("/events")
    public Map<String, String> add(@RequestBody AddEventRequest request) {
        return Map.of("line", calendar.add(request.spec(), request.title()), "notePath", CalendarService.EVENTS_NOTE);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/calendar; charset=utf-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("kbmd-calendar.ics").build().toString())
                .body(calendar.ics().getBytes(StandardCharsets.UTF_8));
    }

    public record AddEventRequest(String spec, String title) {
    }
}
