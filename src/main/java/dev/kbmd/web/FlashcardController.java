package dev.kbmd.web;

import java.nio.charset.StandardCharsets;
import java.util.List;

import dev.kbmd.flashcards.FlashcardService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/flashcards")
public class FlashcardController {

    private final FlashcardService flashcards;

    public FlashcardController(FlashcardService flashcards) {
        this.flashcards = flashcards;
    }

    @GetMapping("/decks")
    public List<FlashcardService.Deck> decks() {
        return flashcards.decks();
    }

    @GetMapping("/due")
    public List<FlashcardService.StudyCard> due(@RequestParam(required = false) String deck,
                                                @RequestParam(defaultValue = "0") int newLimit) {
        return flashcards.due(deck, newLimit);
    }

    @PostMapping("/review")
    public FlashcardService.CardState review(@RequestBody ReviewRequest request) {
        return flashcards.review(request.id(), request.rating());
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String deck) {
        String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        String name = (deck == null || deck.isBlank() ? "kbmd" : deck.replaceAll("[^\\w-]+", "_")) + "-anki.txt";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .body(flashcards.exportForAnki(deck, baseUrl).getBytes(StandardCharsets.UTF_8));
    }

    public record ReviewRequest(String id, FlashcardService.Rating rating) {
    }
}
