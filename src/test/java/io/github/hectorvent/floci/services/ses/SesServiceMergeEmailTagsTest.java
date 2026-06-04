package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.services.ses.model.MessageTag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link SesService#mergeEmailTags(List, List)} — the per-entry tag merge used by
 * V2 {@code SendBulkEmail} (DefaultEmailTags + per-entry ReplacementTags) and the V1 Query
 * equivalent (DefaultTags + Destinations.member.N.ReplacementTags). AWS replaces by tag
 * name: a per-entry tag with the same name as a default tag overrides the default value.
 */
class SesServiceMergeEmailTagsTest {

    @Test
    void mergeEmailTags_nullsAndEmptiesReturnEmpty() {
        assertTrue(SesService.mergeEmailTags(null, null).isEmpty());
        assertTrue(SesService.mergeEmailTags(List.of(), null).isEmpty());
        assertTrue(SesService.mergeEmailTags(null, List.of()).isEmpty());
        assertTrue(SesService.mergeEmailTags(List.of(), List.of()).isEmpty());
    }

    @Test
    void mergeEmailTags_replacementOverridesByName() {
        List<MessageTag> defaults = List.of(
                new MessageTag("env", "prod"),
                new MessageTag("campaign", "launch"));
        List<MessageTag> replacement = List.of(new MessageTag("campaign", "vip"));

        List<MessageTag> merged = SesService.mergeEmailTags(defaults, replacement);

        assertEquals(2, merged.size());
        assertEquals("prod", findValue(merged, "env"));
        assertEquals("vip", findValue(merged, "campaign"));
    }

    @Test
    void mergeEmailTags_replacementOnlyNamesAreAdded() {
        List<MessageTag> defaults = List.of(new MessageTag("env", "prod"));
        List<MessageTag> replacement = List.of(new MessageTag("locale", "ja-JP"));

        List<MessageTag> merged = SesService.mergeEmailTags(defaults, replacement);

        assertEquals(2, merged.size());
        assertEquals("prod", findValue(merged, "env"));
        assertEquals("ja-JP", findValue(merged, "locale"));
    }

    private static String findValue(List<MessageTag> tags, String name) {
        return tags.stream().filter(t -> name.equals(t.name())).findFirst()
                .orElseThrow().value();
    }
}
