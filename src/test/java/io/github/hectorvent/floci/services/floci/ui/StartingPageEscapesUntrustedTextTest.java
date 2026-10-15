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
 * Guards the landing page against HTML/script injection through server-derived error text.
 * {@code /_floci/ui/status} forwards {@code error} straight from the floci-ui sidecar's own
 * health JSON ({@link FlociUiManager}'s {@code unavailableMessage}) and from Docker daemon
 * exception messages, so both views of {@code index.html} must render that text as text, never
 * parse it as markup: the wait view through {@code textContent}, the dashboard through
 * {@code escapeHtml}.
 */
class StartingPageEscapesUntrustedTextTest {

    private static final Path LANDING_PAGE = Path.of("src", "main", "resources", "ui", "index.html");
    private static final Pattern ERROR_HTML_ASSIGNMENT = Pattern.compile("\\(\"launch-error\"\\)\\.innerHTML\\s*=");
    private static final Pattern ERROR_TEXT_ASSIGNMENT = Pattern.compile("\\(\"launch-error\"\\)\\.textContent\\s*=");
    private static final Pattern RAW_ERROR_CONCATENATION = Pattern.compile("\\+\\s*(?:ui|s)\\.error\\b");

    @Test
    void failRendersServerTextWithoutParsingItAsHtml() {
        String content = readLandingPage();

        assertFalse(ERROR_HTML_ASSIGNMENT.matcher(content).find(),
                "index.html must not assign the launch error through innerHTML");
        assertTrue(ERROR_TEXT_ASSIGNMENT.matcher(content).find(),
                "index.html must render the untrusted launch error with textContent");
    }

    @Test
    void dashboardEscapesServerTextBeforeConcatenatingItIntoHtml() {
        String content = readLandingPage();

        assertFalse(RAW_ERROR_CONCATENATION.matcher(content).find(),
                "index.html must not concatenate the status error into markup unescaped");
        assertTrue(content.contains("escapeHtml(ui.error)"),
                "index.html must escape the status error shown in the Runtime block");
    }

    private static String readLandingPage() {
        try {
            return Files.readString(LANDING_PAGE, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
