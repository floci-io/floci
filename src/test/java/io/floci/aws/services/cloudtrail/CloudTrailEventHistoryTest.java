package io.floci.aws.services.cloudtrail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CloudTrailEventHistoryTest {

    private static final String ACCOUNT = "111122223333";
    private static final String REGION = "us-east-1";
    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CloudTrailEventHistory history = new CloudTrailEventHistory();

    @Test
    void eventsOlderThanNinetyDaysAreNotReturned() {
        history.record(ACCOUNT, REGION, NOW.minus(Duration.ofDays(90)).minusSeconds(1), event("expired"));
        history.record(ACCOUNT, REGION, NOW.minus(Duration.ofDays(90)), event("oldest-kept"));

        List<CloudTrailEventHistory.HistoryEvent> events = history.lookup(ACCOUNT, REGION, null, null, null, NOW);

        assertEquals(List.of("oldest-kept"), names(events));
    }

    @Test
    void anAccountAndRegionKeepsItsTenThousandNewestEvents() {
        for (int i = 0; i <= 10_000; i++) {
            history.record(ACCOUNT, REGION, NOW, event("event-" + i));
        }

        List<CloudTrailEventHistory.HistoryEvent> events = history.lookup(ACCOUNT, REGION, null, null, null, NOW);

        assertEquals(10_000, events.size());
        assertEquals("event-10000", events.getFirst().record().path("eventName").asText());
        assertEquals("event-1", events.getLast().record().path("eventName").asText());
    }

    @Test
    void historyIsKeptPerAccountAndRegion() {
        history.record(ACCOUNT, REGION, NOW, event("here"));
        history.record("444455556666", REGION, NOW, event("other-account"));
        history.record(ACCOUNT, "eu-west-1", NOW, event("other-region"));

        assertEquals(List.of("here"), names(history.lookup(ACCOUNT, REGION, null, null, null, NOW)));
    }

    private static ObjectNode event(String name) {
        ObjectNode record = MAPPER.createObjectNode();
        record.put("eventName", name);
        return record;
    }

    private static List<String> names(List<CloudTrailEventHistory.HistoryEvent> events) {
        return events.stream().map(event -> event.record().path("eventName").asText()).toList();
    }
}
