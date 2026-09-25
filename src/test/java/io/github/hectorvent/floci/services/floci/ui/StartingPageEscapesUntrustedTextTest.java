package io.github.hectorvent.floci.services.floci.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the startup interstitial against HTML/script injection through server-derived error
 * text. {@code /_floci/ui/status} forwards {@code error} straight from the floci-ui sidecar's own
 * health JSON ({@link FlociUiManager}'s {@code unavailableMessage}) and from Docker daemon
 * exception messages, so the polling script in {@code starting.html} must render that text as
 * text, never parse it as markup.
 */
class StartingPageEscapesUntrustedTextTest {

    private static final Path STARTING_PAGE = Path.of("src", "main", "resources", "ui", "starting.html");

    @Test
    void failRendersServerTextWithoutParsingItAsHtml() {
        String content = readStartingPage();

        assertFalse(content.contains("innerHTML"),
                "starting.html must not assign server-derived text through innerHTML");
        assertTrue(content.contains("msg.textContent = text;"),
                "starting.html must render the untrusted status text with textContent");
    }

    private static String readStartingPage() {
        try {
            return Files.readString(STARTING_PAGE, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
