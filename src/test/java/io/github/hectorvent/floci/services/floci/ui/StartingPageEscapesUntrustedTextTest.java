package io.github.hectorvent.floci.services.floci.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

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
    private static final Pattern MSG_HTML_ASSIGNMENT = Pattern.compile("\\bmsg\\.innerHTML\\s*=");
    private static final Pattern MSG_TEXT_ASSIGNMENT = Pattern.compile("\\bmsg\\.textContent\\s*=");

    @Test
    void failRendersServerTextWithoutParsingItAsHtml() {
        String content = readStartingPage();

        assertFalse(MSG_HTML_ASSIGNMENT.matcher(content).find(),
                "starting.html must not assign the status message through innerHTML");
        assertTrue(MSG_TEXT_ASSIGNMENT.matcher(content).find(),
                "starting.html must render the untrusted status message with textContent");
    }

    private static String readStartingPage() {
        try {
            return Files.readString(STARTING_PAGE, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
