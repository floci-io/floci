package io.floci.aws.services.cloudtrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CloudTrail's event history: the management events of each account and region, kept without a
 * trail, as {@code LookupEvents} reads them. AWS keeps 90 days; this keeps that window and at most
 * {@link #MAX_EVENTS_PER_REGION} events per account and region, in memory only.
 */
final class CloudTrailEventHistory {

    static final int MAX_EVENTS_PER_REGION = 10_000;
    static final Duration RETENTION = Duration.ofDays(90);

    /** One recorded event; {@code sequence} orders events recorded within the same instant. */
    record HistoryEvent(long sequence, Instant eventTime, ObjectNode record) {}

    /** The one lookup attribute a request may name: its key as AWS spells it, and the value to match. */
    record LookupAttribute(String key, String value) {}

    private final Map<String, Deque<HistoryEvent>> eventsByAccountAndRegion = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    void record(String accountId, String region, Instant eventTime, ObjectNode record) {
        HistoryEvent event = new HistoryEvent(sequence.incrementAndGet(), eventTime, record.deepCopy());
        eventsByAccountAndRegion.compute(key(accountId, region), (ignored, events) -> {
            Deque<HistoryEvent> updated = events == null ? new ArrayDeque<>() : events;
            updated.addLast(event);
            while (updated.size() > MAX_EVENTS_PER_REGION) {
                updated.removeFirst();
            }
            return updated;
        });
    }

    /** The events of an account and region inside the retention window that match, newest first. */
    List<HistoryEvent> lookup(String accountId, String region, LookupAttribute attribute,
                              Instant startTime, Instant endTime, Instant now) {
        Instant oldest = now.minus(RETENTION);
        List<HistoryEvent> matching = new ArrayList<>();
        eventsByAccountAndRegion.computeIfPresent(key(accountId, region), (ignored, events) -> {
            while (!events.isEmpty() && events.peekFirst().eventTime().isBefore(oldest)) {
                events.removeFirst();
            }
            events.descendingIterator().forEachRemaining(event -> {
                if (inRange(event.eventTime(), startTime, endTime) && matches(event.record(), attribute)) {
                    matching.add(event);
                }
            });
            return events.isEmpty() ? null : events;
        });
        return matching;
    }

    private static boolean inRange(Instant eventTime, Instant startTime, Instant endTime) {
        return (startTime == null || !eventTime.isBefore(startTime)) && (endTime == null || !eventTime.isAfter(endTime));
    }

    private static boolean matches(ObjectNode record, LookupAttribute attribute) {
        if (attribute == null) {
            return true;
        }
        JsonNode identity = record.path("userIdentity");
        String actual = switch (attribute.key()) {
            case "EventId" -> record.path("eventID").asText(null);
            case "EventName" -> record.path("eventName").asText(null);
            case "EventSource" -> record.path("eventSource").asText(null);
            case "ReadOnly" -> record.path("readOnly").asText(null);
            case "Username" -> identity.path("userName").asText(null);
            case "AccessKeyId" -> identity.path("accessKeyId").asText(null);
            // The recorded events name no resources, so a resource attribute matches none of them.
            default -> null;
        };
        return attribute.value().equals(actual);
    }

    private static String key(String accountId, String region) {
        return accountId + "::" + region;
    }
}
