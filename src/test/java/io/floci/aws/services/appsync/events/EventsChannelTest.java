package io.floci.aws.services.appsync.events;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventsChannelTest {

    @Test
    void aChannelNormalisesItsSlashesAndNamesItsNamespace() {
        EventsChannel channel = EventsChannel.forPublish("/appservices-v7-6-0/protocol/123456789012/run-1/");

        assertEquals("/appservices-v7-6-0/protocol/123456789012/run-1", channel.path());
        assertEquals("appservices-v7-6-0", channel.namespace());
        assertEquals(List.of("appservices-v7-6-0", "protocol", "123456789012", "run-1"), channel.segments());
        assertEquals("/ns/a", EventsChannel.forPublish("ns/a").path());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/", "/a/b/c/d/e/f", "/ns//a", "/ns/-a", "/ns/a-", "/ns/a_b", "/ns/a b", "/ns/*"})
    void aPublishChannelOutsideTheGrammarIsRefused(String path) {
        assertThrows(IllegalArgumentException.class, () -> EventsChannel.forPublish(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/*", "/ns/*/a", "/ns/a*"})
    void aWildcardOnlyEndsASubscriptionBelowItsNamespace(String path) {
        assertThrows(IllegalArgumentException.class, () -> EventsChannel.forSubscribe(path));
    }

    @Test
    void aSegmentIsUpToFiftyCharacters() {
        String fifty = "a".repeat(50);
        assertEquals(fifty, EventsChannel.forPublish("/ns/" + fifty).segments().get(1));
        assertThrows(IllegalArgumentException.class, () -> EventsChannel.forPublish("/ns/" + fifty + "a"));
    }

    @ParameterizedTest
    @CsvSource({
            "/ns/a,       /ns/a,     true",
            "/ns/a,       /ns/a/b,   false",
            "/ns/a,       /ns/b,     false",
            "/ns/*,       /ns/a,     true",
            "/ns/*,       /ns/a/b/c, true",
            "/ns/*,       /ns,       false",
            "/ns/a/*,     /ns/a/b,   true",
            "/ns/a/*,     /ns/b/c,   false",
            "/ns/*,       /other/a,  false"})
    void aSubscriptionMatchesItsChannelOrEverythingBelowItsWildcard(String subscription, String published,
                                                                     boolean matches) {
        boolean actual = EventsChannel.forSubscribe(subscription).matches(EventsChannel.forPublish(published));
        assertEquals(matches, actual, subscription + " vs " + published);
    }

    @Test
    void aWildcardSubscriptionSaysSo() {
        assertTrue(EventsChannel.forSubscribe("/ns/*").wildcard());
        assertFalse(EventsChannel.forSubscribe("/ns/a").wildcard());
    }
}
