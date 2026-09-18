package dev.kbmd.web;

import dev.kbmd.sync.SyncService;
import dev.kbmd.sync.SyncSettings;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sync")
public class SyncController {

    private final SyncService sync;

    public SyncController(SyncService sync) {
        this.sync = sync;
    }

    @GetMapping("/settings")
    public SettingsView settings() {
        return SettingsView.of(sync.settings());
    }

    @PutMapping("/settings")
    public SettingsView update(@RequestBody SyncSettings settings) {
        return SettingsView.of(sync.updateSettings(settings));
    }

    @GetMapping("/status")
    public SyncService.SyncStatus status() {
        return sync.status();
    }

    @PostMapping("/test")
    public SyncService.SyncResult test() {
        return sync.test();
    }

    @PostMapping("/run")
    public SyncService.SyncResult run() {
        return sync.sync();
    }

    /** Settings as shown to the browser: the token itself is never sent back. */
    public record SettingsView(String provider, String remoteUrl, boolean tokenSet, String branch,
                               String authorName, String authorEmail, int autoSyncMinutes) {

        static SettingsView of(SyncSettings s) {
            return new SettingsView(s.provider(), s.remoteUrl(), !s.token().isEmpty(), s.branch(),
                    s.authorName(), s.authorEmail(), s.autoSyncMinutes());
        }
    }
}
