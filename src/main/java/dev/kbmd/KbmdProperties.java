package dev.kbmd;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("kbmd")
public record KbmdProperties(Path vault, String attachmentsDir) {

    public KbmdProperties {
        if (vault == null) {
            vault = Path.of(System.getProperty("user.home"), "kbmd-vault");
        }
        if (attachmentsDir == null || attachmentsDir.isBlank()) {
            attachmentsDir = "attachments";
        }
    }
}
