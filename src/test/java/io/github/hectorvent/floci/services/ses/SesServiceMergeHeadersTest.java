package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.services.ses.model.MessageHeader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link SesService#mergeHeaders(List, List)} — the per-entry header merge used by
 * V2 {@code SendBulkEmail} (DefaultContent.Template.Headers + per-entry
 * ReplacementHeaders). AWS replaces by header name: a per-entry header with the same Name
 * as a default header overrides the default value.
 */
class SesServiceMergeHeadersTest {

    @Test
    void mergeHeaders_nullsAndEmptiesReturnEmpty() {
        assertTrue(SesService.mergeHeaders(null, null).isEmpty());
        assertTrue(SesService.mergeHeaders(List.of(), null).isEmpty());
        assertTrue(SesService.mergeHeaders(null, List.of()).isEmpty());
        assertTrue(SesService.mergeHeaders(List.of(), List.of()).isEmpty());
    }

    @Test
    void mergeHeaders_replacementOverridesByName() {
        List<MessageHeader> defaults = List.of(
                new MessageHeader("X-Mailer", "floci-default"),
                new MessageHeader("List-Unsubscribe", "<mailto:default@example.com>"));
        List<MessageHeader> replacement = List.of(
                new MessageHeader("X-Mailer", "floci-override"));

        List<MessageHeader> merged = SesService.mergeHeaders(defaults, replacement);

        assertEquals(2, merged.size());
        assertEquals("floci-override", findValue(merged, "X-Mailer"));
        assertEquals("<mailto:default@example.com>", findValue(merged, "List-Unsubscribe"));
    }

    @Test
    void mergeHeaders_caseInsensitiveOverrideKeepsReplacementCasing() {
        // RFC 5322 makes header field names case-insensitive, so a per-entry
        // lowercase "x-mailer" should override a default "X-Mailer".
        List<MessageHeader> defaults = List.of(new MessageHeader("X-Mailer", "floci-default"));
        List<MessageHeader> replacement = List.of(new MessageHeader("x-mailer", "floci-override"));

        List<MessageHeader> merged = SesService.mergeHeaders(defaults, replacement);

        assertEquals(1, merged.size(), "case differences should not produce duplicate entries");
        assertEquals("x-mailer", merged.get(0).name(), "replacement's casing wins on override");
        assertEquals("floci-override", merged.get(0).value());
    }

    @Test
    void mergeHeaders_replacementOnlyNamesAreAdded() {
        List<MessageHeader> defaults = List.of(new MessageHeader("X-Mailer", "floci"));
        List<MessageHeader> replacement = List.of(new MessageHeader("X-Custom", "v1"));

        List<MessageHeader> merged = SesService.mergeHeaders(defaults, replacement);

        assertEquals(2, merged.size());
        assertEquals("floci", findValue(merged, "X-Mailer"));
        assertEquals("v1", findValue(merged, "X-Custom"));
    }

    private static String findValue(List<MessageHeader> headers, String name) {
        return headers.stream().filter(h -> name.equals(h.name())).findFirst()
                .orElseThrow().value();
    }
}
