package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogEvent;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogGroup;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogStream;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.MetricFilter;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.SubscriptionFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class CloudWatchLogsServiceTest {

    private static final String REGION = "us-east-1";
    private static final String KEY_ARN =
            "arn:aws:kms:us-east-1:000000000000:key/1234abcd-12ab-34cd-56ef-1234567890ab";

    private CloudWatchLogsService service;

    @BeforeEach
    void setUp() {
        service = new CloudWatchLogsService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                10000,
                new RegionResolver("us-east-1", "000000000000")
        );
    }

    // ──────────────────────────── Log Groups ────────────────────────────

    @Test
    void createLogGroup() {
        service.createLogGroup("/app/logs", null, null, REGION);

        List<LogGroup> groups = service.describeLogGroups(null, REGION);
        assertEquals(1, groups.size());
        assertEquals("/app/logs", groups.getFirst().getLogGroupName());
        assertFalse(groups.getFirst().isDeletionProtectionEnabled());
    }

    @Test
    void createLogGroupWithDeletionProtectionEnabled() {
        service.createLogGroup("/app/protected", null, null, true, REGION);

        LogGroup group = service.describeLogGroups("/app/protected", REGION).getFirst();
        assertTrue(group.isDeletionProtectionEnabled());
    }

    @Test
    void createLogGroupDuplicateThrows() {
        service.createLogGroup("/app/logs", null, null, REGION);
        assertThrows(AwsException.class, () ->
                service.createLogGroup("/app/logs", null, null, REGION));
    }

    @Test
    void createLogGroupBlankNameThrows() {
        assertThrows(AwsException.class, () ->
                service.createLogGroup("", null, null, REGION));
    }

    @Test
    void explicitAccountLogWritesRemainIsolatedOutsideRequestContext() {
        InMemoryStorage<String, LogGroup> rawGroups = new InMemoryStorage<>();
        InMemoryStorage<String, LogStream> rawStreams = new InMemoryStorage<>();
        InMemoryStorage<String, LogEvent> rawEvents = new InMemoryStorage<>();
        CloudWatchLogsService accountService = new CloudWatchLogsService(
                new AccountAwareStorageBackend<>(rawGroups, null, "000000000000"),
                new AccountAwareStorageBackend<>(rawStreams, null, "000000000000"),
                new AccountAwareStorageBackend<>(rawEvents, null, "000000000000"),
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, "000000000000"),
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, "000000000000"),
                10_000, new RegionResolver(REGION, "000000000000"));
        String accountA = "111111111111";
        String accountB = "222222222222";

        for (String accountId : List.of(accountA, accountB)) {
            accountService.createLogGroupForAccount(
                    accountId, "/aws/rds/instance/db1/error", null, null, REGION);
            accountService.createLogStreamForAccount(
                    accountId, "/aws/rds/instance/db1/error", "stream", REGION);
            accountService.putLogEventsForAccount(
                    accountId, "/aws/rds/instance/db1/error", "stream",
                    List.of(Map.of("timestamp", 1L, "message", accountId)), REGION);
        }

        String groupKey = REGION + "::/aws/rds/instance/db1/error";
        String streamKey = groupKey + "::stream";
        assertTrue(rawGroups.get(accountA + "/" + groupKey).isPresent());
        assertTrue(rawGroups.get(accountB + "/" + groupKey).isPresent());
        assertTrue(rawStreams.get(accountA + "/" + streamKey).isPresent());
        assertTrue(rawStreams.get(accountB + "/" + streamKey).isPresent());
        assertEquals(1, rawEvents.keys().stream()
                .filter(key -> key.startsWith(accountA + "/" + streamKey + "::"))
                .count());
        assertEquals(1, rawEvents.keys().stream()
                .filter(key -> key.startsWith(accountB + "/" + streamKey + "::"))
                .count());
    }

    @Test
    void explicitAccountRetentionEvictionLeavesOtherAccountEventsUntouched() {
        InMemoryStorage<String, LogGroup> rawGroups = new InMemoryStorage<>();
        InMemoryStorage<String, LogStream> rawStreams = new InMemoryStorage<>();
        InMemoryStorage<String, LogEvent> rawEvents = new InMemoryStorage<>();
        String accountA = "111111111111";
        String accountB = "222222222222";
        CloudWatchLogsService accountService = new CloudWatchLogsService(
                new AccountAwareStorageBackend<>(rawGroups, null, accountB),
                new AccountAwareStorageBackend<>(rawStreams, null, accountB),
                new AccountAwareStorageBackend<>(rawEvents, null, accountB),
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, accountB),
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, accountB),
                10_000, new RegionResolver(REGION, accountB));
        long now = System.currentTimeMillis();
        long twoDaysAgo = now - 2 * 86_400_000L;

        accountService.createLogGroupForAccount(accountA, "/app/logs", 1, null, REGION);
        accountService.createLogStreamForAccount(accountA, "/app/logs", "stream", REGION);
        accountService.createLogGroupForAccount(accountB, "/app/logs", null, null, REGION);
        accountService.createLogStreamForAccount(accountB, "/app/logs", "stream", REGION);
        accountService.putLogEventsForAccount(
                accountB, "/app/logs", "stream", eventsAt(twoDaysAgo), REGION);
        accountService.putLogEventsForAccount(
                accountA, "/app/logs", "stream", eventsAt(twoDaysAgo, now), REGION);

        String eventPrefix = REGION + "::/app/logs::stream::";
        assertEquals(1, rawEvents.keys().stream()
                .filter(key -> key.startsWith(accountA + "/" + eventPrefix))
                .count());
        assertEquals(1, rawEvents.keys().stream()
                .filter(key -> key.startsWith(accountB + "/" + eventPrefix))
                .count());
    }

    @Test
    void explicitAccountCapacityEvictionDoesNotCountOtherAccountEvents() {
        InMemoryStorage<String, LogGroup> rawGroups = new InMemoryStorage<>();
        InMemoryStorage<String, LogStream> rawStreams = new InMemoryStorage<>();
        InMemoryStorage<String, LogEvent> rawEvents = new InMemoryStorage<>();
        String accountA = "111111111111";
        String accountB = "222222222222";
        CloudWatchLogsService accountService = new CloudWatchLogsService(
                new AccountAwareStorageBackend<>(rawGroups, null, accountB),
                new AccountAwareStorageBackend<>(rawStreams, null, accountB),
                new AccountAwareStorageBackend<>(rawEvents, null, accountB),
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, accountB),
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, accountB),
                10_000, 2, new RegionResolver(REGION, accountB));

        accountService.createLogGroupForAccount(accountA, "/app/logs", null, null, REGION);
        accountService.createLogStreamForAccount(accountA, "/app/logs", "stream", REGION);
        for (long timestamp : List.of(1L, 2L, 3L)) {
            LogEvent event = new LogEvent();
            event.setTimestamp(timestamp);
            event.setEventId("event-" + timestamp);
            rawEvents.put(accountB + "/event-" + timestamp, event);
        }

        accountService.putLogEventsForAccount(
                accountA, "/app/logs", "stream", eventsAt(4L), REGION);

        assertEquals(3, rawEvents.keys().stream()
                .filter(key -> key.startsWith(accountB + "/"))
                .count());
        assertEquals(1, rawEvents.keys().stream()
                .filter(key -> key.startsWith(accountA + "/"))
                .count());
    }

    @Test
    void deleteLogGroup() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.deleteLogGroup("/app/logs", REGION);

        assertTrue(service.describeLogGroups(null, REGION).isEmpty());
    }

    @Test
    void deleteLogGroupNotFoundThrows() {
        assertThrows(AwsException.class, () ->
                service.deleteLogGroup("/missing", REGION));
    }

    @Test
    void protectedLogGroupMustBeDisabledBeforeDeletion() {
        service.createLogGroup("/app/protected", null, null, true, REGION);

        AwsException error = assertThrows(AwsException.class,
                () -> service.deleteLogGroup("/app/protected", REGION));
        assertEquals("ValidationException", error.getErrorCode());
        assertTrue(service.logGroupExists("/app/protected", REGION));

        service.putLogGroupDeletionProtection("/app/protected", false, REGION);
        service.deleteLogGroup("/app/protected", REGION);

        assertFalse(service.logGroupExists("/app/protected", REGION));
    }

    @Test
    void putLogGroupDeletionProtectionRequiresExistingGroup() {
        AwsException error = assertThrows(AwsException.class,
                () -> service.putLogGroupDeletionProtection("/missing", true, REGION));

        assertEquals("ResourceNotFoundException", error.getErrorCode());
    }

    @Test
    void describeLogGroupsWithPrefix() {
        service.createLogGroup("/app/alpha", null, null, REGION);
        service.createLogGroup("/app/beta", null, null, REGION);
        service.createLogGroup("/other/logs", null, null, REGION);

        List<LogGroup> result = service.describeLogGroups("/app", REGION);
        assertEquals(2, result.size());
    }

    @Test
    void putAndDeleteRetentionPolicy() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.putRetentionPolicy("/app/logs", 30, REGION);

        LogGroup group = service.describeLogGroups("/app/logs", REGION).getFirst();
        assertEquals(30, group.getRetentionInDays());

        service.deleteRetentionPolicy("/app/logs", REGION);
        group = service.describeLogGroups("/app/logs", REGION).getFirst();
        assertNull(group.getRetentionInDays());
    }

    // ──────────────────────────── Stored event ceiling ────────────────────────────

    private static CloudWatchLogsService serviceWithStoredEventCeiling(int maxStoredEvents) {
        return new CloudWatchLogsService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                10000,
                maxStoredEvents,
                new RegionResolver("us-east-1", "000000000000")
        );
    }

    private static List<Map<String, Object>> eventsAt(long... timestamps) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (long timestamp : timestamps) {
            events.add(Map.of("timestamp", timestamp, "message", "event@" + timestamp));
        }
        return events;
    }

    private static List<Long> storedTimestamps(CloudWatchLogsService target, String group, String stream) {
        return target.getLogEvents(group, stream, null, null, 100, true, null, REGION).events().stream()
                .map(LogEvent::getTimestamp)
                .toList();
    }

    @Test
    void putLogEventsEvictsTheOldestEventsBeyondTheStoredEventCeiling() {
        CloudWatchLogsService capped = serviceWithStoredEventCeiling(3);
        capped.createLogGroup("/app/logs", null, null, REGION);
        capped.createLogStream("/app/logs", "stream-1", REGION);
        capped.createLogStream("/app/logs", "stream-2", REGION);

        capped.putLogEvents("/app/logs", "stream-1", eventsAt(1000, 2000, 3000), REGION);
        capped.putLogEvents("/app/logs", "stream-2", eventsAt(4000, 5000), REGION);

        assertEquals(List.of(3000L), storedTimestamps(capped, "/app/logs", "stream-1"),
                "the oldest events across every stream go first, the store stays within the ceiling");
        assertEquals(List.of(4000L, 5000L), storedTimestamps(capped, "/app/logs", "stream-2"));
    }

    @Test
    void putLogEventsKeepsEverythingWhileUnderTheStoredEventCeiling() {
        CloudWatchLogsService capped = serviceWithStoredEventCeiling(3);
        capped.createLogGroup("/app/logs", null, null, REGION);
        capped.createLogStream("/app/logs", "stream-1", REGION);

        capped.putLogEvents("/app/logs", "stream-1", eventsAt(1000, 2000, 3000), REGION);

        assertEquals(List.of(1000L, 2000L, 3000L), storedTimestamps(capped, "/app/logs", "stream-1"));
    }

    @Test
    void putLogEventsDropsEventsOlderThanTheGroupRetentionPolicy() {
        service.createLogGroup("/app/logs", 1, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);
        long now = System.currentTimeMillis();
        long twoDaysAgo = now - 2 * 86_400_000L;

        service.putLogEvents("/app/logs", "stream-1", eventsAt(twoDaysAgo, now), REGION);

        assertEquals(List.of(now), storedTimestamps(service, "/app/logs", "stream-1"),
                "events past the retention window are not kept on disk waiting for a background sweep");
    }

    @Test
    void putLogEventsAppliesRetentionToAGroupWhoseNameContainsTheKeySeparator() {
        // Right before the index too; keeps the index from losing it.
        service.createLogGroup("/app::logs", 1, null, REGION);
        service.createLogStream("/app::logs", "stream-1", REGION);
        service.createLogGroup("/app", null, null, REGION);
        service.createLogStream("/app", "stream-1", REGION);
        long now = System.currentTimeMillis();
        long twoDaysAgo = now - 2 * 86_400_000L;

        service.putLogEvents("/app", "stream-1", eventsAt(twoDaysAgo), REGION);
        service.putLogEvents("/app::logs", "stream-1", eventsAt(twoDaysAgo, now), REGION);

        assertEquals(List.of(now), storedTimestamps(service, "/app::logs", "stream-1"));
        assertEquals(List.of(twoDaysAgo), storedTimestamps(service, "/app", "stream-1"),
                "a group whose key shares the prefix keeps its own retention");
    }

    @Test
    void retentionOnAGroupLeavesAGroupNamedAfterItWithTheSeparatorAlone() {
        // "/app"'s keys are a string prefix of "/app::logs"'s, so a prefix match would take both.
        service.createLogGroup("/app", 1, null, REGION);
        service.createLogStream("/app", "stream-1", REGION);
        service.createLogGroup("/app::logs", null, null, REGION);
        service.createLogStream("/app::logs", "stream-1", REGION);
        long now = System.currentTimeMillis();
        long twoDaysAgo = now - 2 * 86_400_000L;

        service.putLogEvents("/app::logs", "stream-1", eventsAt(twoDaysAgo), REGION);
        service.putLogEvents("/app", "stream-1", eventsAt(twoDaysAgo, now), REGION);

        assertEquals(List.of(now), storedTimestamps(service, "/app", "stream-1"));
        assertEquals(List.of(twoDaysAgo), storedTimestamps(service, "/app::logs", "stream-1"),
                "a group without retention keeps its events whatever its name starts with");
    }

    @Test
    void retentionOnAGroupNamedWithTheSeparatorLeavesTheStreamItsNameSpellsAlone() {
        // Read from the store: getLogEvents matches "/app"'s "logs" by prefix and sees both.
        InMemoryStorage<String, LogEvent> events = new InMemoryStorage<>();
        CloudWatchLogsService target = serviceOver(events);
        target.createLogGroup("/app::logs", 1, null, REGION);
        target.createLogStream("/app::logs", "stream-1", REGION);
        target.createLogGroup("/app", null, null, REGION);
        target.createLogStream("/app", "logs", REGION);
        long now = System.currentTimeMillis();
        long twoDaysAgo = now - 2 * 86_400_000L;

        target.putLogEvents("/app", "logs", eventsAt(twoDaysAgo), REGION);
        target.putLogEvents("/app::logs", "stream-1", eventsAt(twoDaysAgo, now), REGION);

        assertEquals(List.of(now), storedIn(events, "/app::logs", "stream-1"));
        assertEquals(List.of(twoDaysAgo), storedIn(events, "/app", "logs"));
    }

    private static CloudWatchLogsService serviceOver(InMemoryStorage<String, LogEvent> events) {
        return new CloudWatchLogsService(new InMemoryStorage<>(), new InMemoryStorage<>(), events,
                new InMemoryStorage<>(), new InMemoryStorage<>(), 10000, 10000,
                new RegionResolver(REGION, "000000000000"));
    }

    /** The timestamps stored for exactly this group and stream, read off the store's keys. */
    private static List<Long> storedIn(InMemoryStorage<String, LogEvent> events, String group, String stream) {
        String prefix = REGION + "::" + group + "::" + stream + "::";
        return events.keys().stream()
                .filter(k -> LogEventIndex.isOfStream(k, prefix))
                .map(k -> events.get(k).orElseThrow().getTimestamp())
                .sorted()
                .toList();
    }

    @Test
    void deletingAStreamLeavesTheGroupItsNameSpellsAlone() {
        service.createLogGroup("/app", null, null, REGION);
        service.createLogStream("/app", "logs", REGION);
        service.createLogGroup("/app::logs", null, null, REGION);
        service.createLogStream("/app::logs", "stream-1", REGION);
        service.putLogEvents("/app::logs", "stream-1", eventsAt(1000), REGION);
        service.putLogEvents("/app", "logs", eventsAt(2000), REGION);

        service.deleteLogStream("/app", "logs", REGION);

        assertEquals(List.of(1000L), storedTimestamps(service, "/app::logs", "stream-1"));
    }

    @Test
    void aPutToAStreamOfAnotherGroupIsRefused() {
        // "/app"'s "logs::s" and "/app::logs"'s "s" share one stream key; only the first exists.
        InMemoryStorage<String, LogEvent> events = new InMemoryStorage<>();
        CloudWatchLogsService target = serviceOver(events);
        target.createLogGroup("/app", null, null, REGION);
        target.createLogStream("/app", "logs::s", REGION);
        target.createLogGroup("/app::logs", 1, null, REGION);
        long twoDaysAgo = System.currentTimeMillis() - 2 * 86_400_000L;
        target.putLogEvents("/app", "logs::s", eventsAt(twoDaysAgo), REGION);

        AwsException refused = assertThrows(AwsException.class,
                () -> target.putLogEvents("/app::logs", "s", eventsAt(twoDaysAgo), REGION));

        assertEquals("ResourceNotFoundException", refused.getErrorCode());
        assertEquals(List.of(twoDaysAgo), storedIn(events, "/app", "logs::s"),
                "\"/app::logs\"'s retention does not reach \"/app\"'s events");
    }

    @Test
    void deletingAStreamOfAnotherGroupIsRefused() {
        InMemoryStorage<String, LogEvent> events = new InMemoryStorage<>();
        CloudWatchLogsService target = serviceOver(events);
        target.createLogGroup("/app", null, null, REGION);
        target.createLogStream("/app", "logs::s", REGION);
        target.createLogGroup("/app::logs", null, null, REGION);
        target.putLogEvents("/app", "logs::s", eventsAt(1000), REGION);

        AwsException refused = assertThrows(AwsException.class,
                () -> target.deleteLogStream("/app::logs", "s", REGION));

        assertEquals("ResourceNotFoundException", refused.getErrorCode());
        assertEquals(List.of(1000L), storedIn(events, "/app", "logs::s"));
        assertEquals(List.of("logs::s"), target.describeLogStreams("/app", null, REGION).stream()
                .map(LogStream::getLogStreamName).toList());
    }

    @Test
    void describeLogStreamsListsOnlyTheGroupsOwnStreams() {
        service.createLogGroup("/app", null, null, REGION);
        service.createLogStream("/app", "a", REGION);
        service.createLogGroup("/app::logs", null, null, REGION);
        service.createLogStream("/app::logs", "s", REGION);

        assertEquals(List.of("a"), service.describeLogStreams("/app", null, REGION).stream()
                .map(LogStream::getLogStreamName).toList());
    }

    @Test
    void describeLogStreamsPagesOnlyTheGroupsOwnStreams() {
        // "/app::logs"'s "A" matches "/app"'s key prefix and sorts first by name.
        service.createLogGroup("/app", null, null, REGION);
        service.createLogStream("/app", "logsB", REGION);
        service.createLogStream("/app", "logsC", REGION);
        service.createLogGroup("/app::logs", null, null, REGION);
        service.createLogStream("/app::logs", "A", REGION);

        CloudWatchLogsService.DescribeLogStreamsResult first =
                service.describeLogStreams("/app", "logs", null, false, 1, null, REGION);
        CloudWatchLogsService.DescribeLogStreamsResult second =
                service.describeLogStreams("/app", "logs", null, false, 1, first.nextToken(), REGION);

        assertEquals(List.of("logsB"), first.logStreams().stream().map(LogStream::getLogStreamName).toList());
        assertEquals(List.of("logsC"), second.logStreams().stream().map(LogStream::getLogStreamName).toList());
        assertNull(second.nextToken());
    }

    @Test
    void aStreamRecordedWithoutItsGroupCountsAsTheCallers() {
        // Only a hand-built record lacks its group; it keeps working as it did before.
        InMemoryStorage<String, LogGroup> groups = new InMemoryStorage<>();
        InMemoryStorage<String, LogStream> streams = new InMemoryStorage<>();
        InMemoryStorage<String, LogEvent> events = new InMemoryStorage<>();
        CloudWatchLogsService before = new CloudWatchLogsService(groups, streams, events,
                new InMemoryStorage<>(), new InMemoryStorage<>(), 10000, 10000,
                new RegionResolver(REGION, "000000000000"));
        before.createLogGroup("/app", null, null, REGION);
        for (String name : List.of("old", "gone")) {
            LogStream bare = new LogStream();
            bare.setLogStreamName(name);
            streams.put(REGION + "::/app::" + name, bare);
        }
        assertEquals(List.of("gone", "old"), before.describeLogStreams("/app", null, REGION).stream()
                .map(LogStream::getLogStreamName).toList());
        long now = System.currentTimeMillis();
        long twoDaysAgo = now - 2 * 86_400_000L;
        before.putLogEvents("/app", "old", eventsAt(twoDaysAgo), REGION);
        before.putLogEvents("/app", "gone", eventsAt(now), REGION);

        CloudWatchLogsService after = new CloudWatchLogsService(groups, streams, events,
                new InMemoryStorage<>(), new InMemoryStorage<>(), 10000, 10000,
                new RegionResolver(REGION, "000000000000"));
        after.putRetentionPolicy("/app", 1, REGION);
        after.putLogEvents("/app", "old", eventsAt(now), REGION);
        assertEquals(List.of(now), storedIn(events, "/app", "old"), "the refill counts it as \"/app\"'s");

        after.deleteLogStream("/app", "gone", REGION);
        assertEquals(List.of(), storedIn(events, "/app", "gone"));
        after.deleteLogGroup("/app", REGION);
        assertTrue(streams.keys().isEmpty());
        assertTrue(events.keys().isEmpty());
    }

    @Test
    void deletingAStreamDropsItsIndexEntriesWhateverGroupTheyAreUnder() {
        // Without a recorded group the refill files "/app"'s "logs::s" under "/app::logs".
        InMemoryStorage<String, LogGroup> groups = new InMemoryStorage<>();
        InMemoryStorage<String, LogStream> streams = new InMemoryStorage<>();
        InMemoryStorage<String, LogEvent> events = new InMemoryStorage<>();
        CloudWatchLogsService before = new CloudWatchLogsService(groups, streams, events,
                new InMemoryStorage<>(), new InMemoryStorage<>(), 10000, 3,
                new RegionResolver(REGION, "000000000000"));
        before.createLogGroup("/app", null, null, REGION);
        before.createLogStream("/app", "kept", REGION);
        LogStream bare = new LogStream();
        bare.setLogStreamName("logs::s");
        streams.put(REGION + "::/app::logs::s", bare);
        before.putLogEvents("/app", "logs::s", eventsAt(8000, 9000), REGION);

        CloudWatchLogsService after = new CloudWatchLogsService(groups, streams, events,
                new InMemoryStorage<>(), new InMemoryStorage<>(), 10000, 3,
                new RegionResolver(REGION, "000000000000"));
        after.putLogEvents("/app", "kept", eventsAt(1000), REGION);
        after.deleteLogStream("/app", "logs::s", REGION);
        after.putLogEvents("/app", "kept", eventsAt(2000, 3000), REGION);

        assertEquals(List.of(1000L, 2000L, 3000L), storedIn(events, "/app", "kept"));
    }

    @Test
    void deletingAGroupLeavesTheGroupNamedAfterItWithTheSeparatorAlone() {
        InMemoryStorage<String, MetricFilter> metricFilters = new InMemoryStorage<>();
        InMemoryStorage<String, SubscriptionFilter> subscriptions = new InMemoryStorage<>();
        CloudWatchLogsService target = new CloudWatchLogsService(new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), subscriptions, metricFilters,
                10000, 10000, new RegionResolver(REGION, "000000000000"));
        // "/app::filter"'s subscription filter keys start with "/app"'s subscription prefix.
        for (String group : List.of("/app", "/app::logs", "/app::filter")) {
            target.createLogGroup(group, null, null, REGION);
            target.createLogStream(group, "stream-1", REGION);
            target.putLogEvents(group, "stream-1", eventsAt(1000), REGION);
            target.putSubscriptionFilter(group, "sub", "", "arn:aws:lambda:" + REGION + ":000000000000:function:f",
                    null, REGION);
            MetricFilter metric = new MetricFilter();
            metric.setFilterName("metric");
            metric.setLogGroupName(group);
            metricFilters.put(REGION + "::" + group + "::metric", metric);
        }

        target.deleteLogGroup("/app", REGION);

        assertEquals(List.of("stream-1"), target.describeLogStreams("/app::logs", null, REGION).stream()
                .map(LogStream::getLogStreamName).toList());
        assertEquals(List.of(1000L), storedTimestamps(target, "/app::logs", "stream-1"));
        assertTrue(metricFilters.get(REGION + "::/app::logs::metric").isPresent());
        assertFalse(metricFilters.get(REGION + "::/app::metric").isPresent());
        assertEquals(2, subscriptions.keys().size(), "only \"/app\"'s subscription filter went");
    }

    @Test
    void anIndexRefilledFromTheStoreStillTellsTheGroupsApart() {
        InMemoryStorage<String, LogGroup> groups = new InMemoryStorage<>();
        InMemoryStorage<String, LogStream> streams = new InMemoryStorage<>();
        InMemoryStorage<String, LogEvent> events = new InMemoryStorage<>();
        CloudWatchLogsService before = new CloudWatchLogsService(groups, streams, events,
                new InMemoryStorage<>(), new InMemoryStorage<>(), 10000, 10000,
                new RegionResolver(REGION, "000000000000"));
        before.createLogGroup("/app", null, null, REGION);
        before.createLogStream("/app", "logs", REGION);
        before.createLogStream("/app", "stream-1", REGION);
        before.createLogGroup("/app::logs", null, null, REGION);
        before.createLogStream("/app::logs", "stream-1", REGION);
        long now = System.currentTimeMillis();
        long twoDaysAgo = now - 2 * 86_400_000L;
        // Only the stream that exists tells "/app"'s "logs::s" from a "/app::logs" stream "s".
        before.createLogStream("/app", "logs::s", REGION);
        before.putLogEvents("/app", "logs", eventsAt(twoDaysAgo), REGION);
        before.putLogEvents("/app", "logs::s", eventsAt(twoDaysAgo), REGION);
        before.putLogEvents("/app::logs", "stream-1", eventsAt(twoDaysAgo), REGION);
        // Events whose stream is gone, as a put racing DeleteLogStream leaves them. Either key reads
        // as "/app" with a stream holding "::" or as "/app::logs" with one free of it.
        for (String stream : List.of("gone", "logs::gone")) {
            String group = stream.equals("gone") ? "/app::logs" : "/app";
            LogEvent orphan = new LogEvent();
            orphan.setEventId("orphan-" + stream);
            orphan.setTimestamp(twoDaysAgo);
            events.put(REGION + "::" + group + "::" + stream + "::" + String.format("%015d", twoDaysAgo)
                    + "::orphan-" + stream, orphan);
        }

        CloudWatchLogsService after = new CloudWatchLogsService(groups, streams, events,
                new InMemoryStorage<>(), new InMemoryStorage<>(), 10000, 10000,
                new RegionResolver(REGION, "000000000000"));
        after.putRetentionPolicy("/app", 1, REGION);
        after.putLogEvents("/app", "stream-1", eventsAt(now), REGION);

        assertEquals(List.of(), storedIn(events, "/app", "logs"));
        assertEquals(List.of(), storedIn(events, "/app", "logs::s"));
        assertEquals(List.of(twoDaysAgo), storedIn(events, "/app::logs", "stream-1"));
        // AWS deletes a stream's events with it, so the refill drops them rather than guess a group.
        assertEquals(List.of(), storedIn(events, "/app::logs", "gone"));
        assertEquals(List.of(), storedIn(events, "/app", "logs::gone"));
        assertEquals(2, events.keys().size(), "only \"/app::logs\"'s event and the new one are left");
    }

    @Test
    void aStreamDeletedAfterThePutChecksItLeavesNoEventsBehind() {
        // The stream is deleted between the put's first look at it and the put taking the lock.
        AtomicReference<Runnable> deleteLater = new AtomicReference<>();
        InMemoryStorage<String, LogStream> streams = new InMemoryStorage<>() {
            private boolean seen;

            @Override
            public Optional<LogStream> get(String key) {
                Optional<LogStream> stream = super.get(key);
                if (!seen && stream.isPresent() && stream.get().getLogStreamName().equals("doomed")) {
                    seen = true;
                    deleteLater.get().run();
                }
                return stream;
            }
        };
        InMemoryStorage<String, LogEvent> events = new InMemoryStorage<>();
        CloudWatchLogsService logs = new CloudWatchLogsService(new InMemoryStorage<>(), streams, events,
                new InMemoryStorage<>(), new InMemoryStorage<>(), 10000, 10000,
                new RegionResolver(REGION, "000000000000"));
        logs.createLogGroup("/app", null, null, REGION);
        logs.createLogStream("/app", "doomed", REGION);
        deleteLater.set(() -> logs.deleteLogStream("/app", "doomed", REGION));

        assertThrows(AwsException.class,
                () -> logs.putLogEvents("/app", "doomed", eventsAt(System.currentTimeMillis()), REGION));
        assertEquals(List.of(), List.copyOf(events.keys()));
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aStreamRecreatedWhileTheOldOneIsDeletedKeepsItsNewEvents() throws InterruptedException {
        // As the old stream's record goes, another caller recreates it and writes to it.
        AtomicReference<Thread> recreate = new AtomicReference<>();
        InMemoryStorage<String, LogStream> streams = new InMemoryStorage<>() {
            @Override
            public void delete(String key) {
                super.delete(key);
                Thread writer = recreate.getAndSet(null);
                if (writer == null) {
                    return;
                }
                startAndWaitUntilFinishedOrStuck(writer);
            }
        };
        InMemoryStorage<String, LogEvent> events = new InMemoryStorage<>();
        CloudWatchLogsService logs = new CloudWatchLogsService(new InMemoryStorage<>(), streams, events,
                new InMemoryStorage<>(), new InMemoryStorage<>(), 10000, 10000,
                new RegionResolver(REGION, "000000000000"));
        logs.createLogGroup("/app", null, null, REGION);
        logs.createLogStream("/app", "reused", REGION);
        long now = System.currentTimeMillis();
        logs.putLogEvents("/app", "reused", eventsAt(now - 1000), REGION);
        Thread writer = new Thread(() -> {
            logs.createLogStream("/app", "reused", REGION);
            logs.putLogEvents("/app", "reused", eventsAt(now), REGION);
        });
        recreate.set(writer);

        logs.deleteLogStream("/app", "reused", REGION);
        writer.join();

        assertEquals(List.of(now), storedTimestamps(logs, "/app", "reused"));
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aStreamDeletedAsThePutRecordsItsMetadataStaysDeleted() throws InterruptedException {
        // The delete arrives as the put writes the stream's metadata back.
        AtomicReference<Thread> deleter = new AtomicReference<>();
        InMemoryStorage<String, LogStream> streams = new InMemoryStorage<>() {
            @Override
            public void put(String key, LogStream value) {
                Thread delete = deleter.getAndSet(null);
                if (delete != null) {
                    startAndWaitUntilFinishedOrStuck(delete);
                }
                super.put(key, value);
            }
        };
        CloudWatchLogsService logs = new CloudWatchLogsService(new InMemoryStorage<>(), streams,
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), 10000, 10000,
                new RegionResolver(REGION, "000000000000"));
        logs.createLogGroup("/app", null, null, REGION);
        logs.createLogStream("/app", "doomed", REGION);
        Thread delete = new Thread(() -> logs.deleteLogStream("/app", "doomed", REGION));
        deleter.set(delete);

        logs.putLogEvents("/app", "doomed", eventsAt(System.currentTimeMillis()), REGION);
        delete.join();

        assertEquals(List.of(), logs.describeLogStreams("/app", null, REGION));
    }

    /** Runs the thread until it ends or waits on a lock the caller may hold. */
    private static void startAndWaitUntilFinishedOrStuck(Thread thread) {
        thread.start();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (thread.isAlive() && System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.BLOCKED || state == Thread.State.WAITING
                    || state == Thread.State.TIMED_WAITING) {
                return;
            }
            Thread.onSpinWait();
        }
    }

    @Test
    void putLogEventsLeavesOtherGroupsAloneWhenApplyingRetention() {
        service.createLogGroup("/short", 1, null, REGION);
        service.createLogStream("/short", "stream-1", REGION);
        service.createLogGroup("/forever", null, null, REGION);
        service.createLogStream("/forever", "stream-1", REGION);
        long now = System.currentTimeMillis();
        long twoDaysAgo = now - 2 * 86_400_000L;

        service.putLogEvents("/forever", "stream-1", eventsAt(twoDaysAgo), REGION);
        service.putLogEvents("/short", "stream-1", eventsAt(twoDaysAgo), REGION);

        assertEquals(List.of(twoDaysAgo), storedTimestamps(service, "/forever", "stream-1"));
        assertEquals(List.of(), storedTimestamps(service, "/short", "stream-1"));
    }

    @Test
    void putLogEventsAtTheCeilingDoesNotEnumerateTheStorePerCall() {
        CountingStorage<LogEvent> events = new CountingStorage<>();
        CloudWatchLogsService capped = new CloudWatchLogsService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), events,
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                10000, 100, new RegionResolver(REGION, "000000000000"));
        capped.createLogGroup("/app/logs", 30, null, REGION);
        capped.createLogStream("/app/logs", "stream-1", REGION);
        long now = System.currentTimeMillis();
        events.enumerations = 0;

        for (int i = 0; i < 1_000; i++) {
            capped.putLogEvents("/app/logs", "stream-1", eventsAt(now + i), REGION);
        }

        // Exactly once: the first call fills the index.
        int listed = events.enumerations;
        assertEquals(100, events.keys().size(), "the store stays at the ceiling");
        assertEquals(1, listed,
                "1,000 single-event calls listed the whole store " + listed
                        + " times; the index fills once and eviction should not grow with the store");
    }

    @Test
    void putLogEventsEvictsExactlyAfterAStreamIsDeleted() {
        CloudWatchLogsService capped = serviceWithStoredEventCeiling(3);
        capped.createLogGroup("/app/logs", null, null, REGION);
        capped.createLogStream("/app/logs", "kept", REGION);
        capped.createLogStream("/app/logs", "deleted", REGION);
        capped.putLogEvents("/app/logs", "kept", eventsAt(1000), REGION);
        capped.putLogEvents("/app/logs", "deleted", eventsAt(2000, 3000), REGION);

        capped.deleteLogStream("/app/logs", "deleted", REGION);
        capped.putLogEvents("/app/logs", "kept", eventsAt(4000), REGION);

        assertEquals(List.of(1000L, 4000L), storedTimestamps(capped, "/app/logs", "kept"),
                "the deleted stream's events no longer count toward the ceiling");
    }

    @Test
    void putLogEventsEvictsExactlyAfterAGroupIsDeleted() {
        CloudWatchLogsService capped = serviceWithStoredEventCeiling(3);
        capped.createLogGroup("/kept", null, null, REGION);
        capped.createLogStream("/kept", "stream-1", REGION);
        capped.createLogGroup("/deleted", null, null, REGION);
        capped.createLogStream("/deleted", "stream-1", REGION);
        capped.putLogEvents("/kept", "stream-1", eventsAt(1000), REGION);
        capped.putLogEvents("/deleted", "stream-1", eventsAt(2000, 3000), REGION);

        capped.deleteLogGroup("/deleted", REGION);
        capped.putLogEvents("/kept", "stream-1", eventsAt(4000), REGION);

        assertEquals(List.of(1000L, 4000L), storedTimestamps(capped, "/kept", "stream-1"));
    }

    @Test
    void putLogEventsCountsEventsStoredBeforeARestart() {
        InMemoryStorage<String, LogGroup> groups = new InMemoryStorage<>();
        InMemoryStorage<String, LogStream> streams = new InMemoryStorage<>();
        InMemoryStorage<String, LogEvent> events = new InMemoryStorage<>();
        CloudWatchLogsService before = new CloudWatchLogsService(groups, streams, events,
                new InMemoryStorage<>(), new InMemoryStorage<>(), 10000, 3,
                new RegionResolver(REGION, "000000000000"));
        before.createLogGroup("/app/logs", null, null, REGION);
        before.createLogStream("/app/logs", "stream-1", REGION);
        before.putLogEvents("/app/logs", "stream-1", eventsAt(1000, 2000, 3000), REGION);

        CloudWatchLogsService after = new CloudWatchLogsService(groups, streams, events,
                new InMemoryStorage<>(), new InMemoryStorage<>(), 10000, 3,
                new RegionResolver(REGION, "000000000000"));
        after.putLogEvents("/app/logs", "stream-1", eventsAt(4000), REGION);

        assertEquals(List.of(2000L, 3000L, 4000L), storedTimestamps(after, "/app/logs", "stream-1"));
    }

    @Test
    void putLogEventsDropsAnExpiredEventThatArrivesAfterNewerOnes() {
        service.createLogGroup("/app/logs", 1, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);
        long now = System.currentTimeMillis();

        service.putLogEvents("/app/logs", "stream-1", eventsAt(now), REGION);
        service.putLogEvents("/app/logs", "stream-1", eventsAt(now - 2 * 86_400_000L), REGION);

        assertEquals(List.of(now), storedTimestamps(service, "/app/logs", "stream-1"));
    }

    @Test
    void putLogEventsAfterAResetCountsOnlyWhatIsStoredSinceTheReset() {
        InMemoryStorage<String, LogGroup> groups = new InMemoryStorage<>();
        InMemoryStorage<String, LogStream> streams = new InMemoryStorage<>();
        InMemoryStorage<String, LogEvent> events = new InMemoryStorage<>();
        CloudWatchLogsService capped = new CloudWatchLogsService(groups, streams, events,
                new InMemoryStorage<>(), new InMemoryStorage<>(), 10000, 3,
                new RegionResolver(REGION, "000000000000"));
        capped.createLogGroup("/app/logs", null, null, REGION);
        capped.createLogStream("/app/logs", "stream-1", REGION);
        capped.putLogEvents("/app/logs", "stream-1", eventsAt(1000, 2000, 3000), REGION);

        groups.clear();
        streams.clear();
        events.clear();
        capped.clear();
        capped.createLogGroup("/app/logs", null, null, REGION);
        capped.createLogStream("/app/logs", "stream-1", REGION);
        capped.putLogEvents("/app/logs", "stream-1", eventsAt(500), REGION);

        assertEquals(List.of(500L), storedTimestamps(capped, "/app/logs", "stream-1"),
                "events wiped by the reset no longer count toward the ceiling");
    }

    @Test
    void eachAccountKeepsItsOwnCeiling() {
        InMemoryStorage<String, LogEvent> rawEvents = new InMemoryStorage<>();
        String accountA = "111111111111";
        String accountB = "222222222222";
        CloudWatchLogsService accountService = new CloudWatchLogsService(
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, accountB),
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, accountB),
                new AccountAwareStorageBackend<>(rawEvents, null, accountB),
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, accountB),
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, accountB),
                10_000, 2, new RegionResolver(REGION, accountB));
        for (String account : List.of(accountA, accountB)) {
            accountService.createLogGroupForAccount(account, "/app/logs", null, null, REGION);
            accountService.createLogStreamForAccount(account, "/app/logs", "stream", REGION);
        }

        accountService.putLogEventsForAccount(accountA, "/app/logs", "stream", eventsAt(1, 2, 3), REGION);
        accountService.putLogEventsForAccount(accountB, "/app/logs", "stream", eventsAt(4), REGION);
        accountService.putLogEventsForAccount(accountA, "/app/logs", "stream", eventsAt(5), REGION);

        assertEquals(List.of(3L, 5L), rawTimestamps(rawEvents, accountA),
                "account A drops its own oldest, whatever account B stores");
        assertEquals(List.of(4L), rawTimestamps(rawEvents, accountB));
    }

    private static List<Long> rawTimestamps(InMemoryStorage<String, LogEvent> rawEvents, String account) {
        return rawEvents.keys().stream()
                .filter(key -> key.startsWith(account + "/"))
                .map(key -> rawEvents.get(key).orElseThrow().getTimestamp())
                .sorted()
                .toList();
    }

    @Test
    void aStreamDeletedInTheRequestAccountLeavesThatAccountsCeilingExact() {
        String account = "222222222222";
        CloudWatchLogsService accountService = new CloudWatchLogsService(
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, account),
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, account),
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, account),
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, account),
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, account),
                10_000, 3, new RegionResolver(REGION, account));
        accountService.createLogGroupForAccount(account, "/app/logs", null, null, REGION);
        accountService.createLogStreamForAccount(account, "/app/logs", "kept", REGION);
        accountService.createLogStreamForAccount(account, "/app/logs", "deleted", REGION);
        accountService.putLogEventsForAccount(account, "/app/logs", "kept", eventsAt(1000), REGION);
        accountService.putLogEventsForAccount(account, "/app/logs", "deleted", eventsAt(2000, 3000), REGION);

        accountService.deleteLogStream("/app/logs", "deleted", REGION);
        accountService.putLogEventsForAccount(account, "/app/logs", "kept", eventsAt(4000), REGION);

        assertEquals(List.of(1000L, 4000L), storedTimestamps(accountService, "/app/logs", "kept"));
    }

    @Test
    // Unsynchronized writers can corrupt a TreeSet into a loop that never ends: fail, don't hang.
    @Timeout(60)
    void concurrentPutsAtTheCeilingKeepExactlyTheNewestEvents() throws InterruptedException {
        CeilingWatchStorage events = new CeilingWatchStorage();
        CloudWatchLogsService capped = new CloudWatchLogsService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), events,
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                10000, 100, new RegionResolver(REGION, "000000000000"));
        capped.createLogGroup("/app/logs", null, null, REGION);
        capped.createLogStream("/app/logs", "stream-1", REGION);
        int threads = 8;
        int perThread = 250;
        List<Thread> workers = new ArrayList<>();
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        for (int t = 0; t < threads; t++) {
            int base = t * perThread;
            Thread worker = new Thread(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        capped.putLogEvents("/app/logs", "stream-1", eventsAt(1000L + base + i), REGION);
                    }
                } catch (Throwable e) {
                    failures.add(e);
                }
            });
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join();
        }

        assertEquals(List.of(), failures);
        assertTrue(events.largestBeforeAWrite.get() <= 100,
                "a write began with " + events.largestBeforeAWrite.get() + " events stored, over the ceiling");
        List<Long> newest = new ArrayList<>();
        for (long ts = 1000L + threads * perThread - 100; ts < 1000L + threads * perThread; ts++) {
            newest.add(ts);
        }
        assertEquals(newest, storedTimestamps(capped, "/app/logs", "stream-1"));
    }

    @Test
    void aStoreWriteThatFailsPartWayIsCountedOnTheNextCall() {
        FailingOnceStorage events = new FailingOnceStorage();
        CloudWatchLogsService capped = new CloudWatchLogsService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), events,
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                10000, 3, new RegionResolver(REGION, "000000000000"));
        capped.createLogGroup("/app/logs", null, null, REGION);
        capped.createLogStream("/app/logs", "stream-1", REGION);
        capped.putLogEvents("/app/logs", "stream-1", eventsAt(1000, 2000), REGION);

        events.failNextPutAll = true;
        assertThrows(IllegalStateException.class,
                () -> capped.putLogEvents("/app/logs", "stream-1", eventsAt(3000, 4000), REGION));
        capped.putLogEvents("/app/logs", "stream-1", eventsAt(5000), REGION);

        assertEquals(List.of(3000L, 4000L, 5000L), storedTimestamps(capped, "/app/logs", "stream-1"),
                "events the failed call did store still count toward the ceiling");
    }

    @Test
    void anErrorAfterTheStoreWriteIsCountedOnTheNextCall() {
        FailingOnceStorage events = new FailingOnceStorage();
        CloudWatchLogsService capped = new CloudWatchLogsService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), events,
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                10000, 3, new RegionResolver(REGION, "000000000000"));
        capped.createLogGroup("/app/logs", null, null, REGION);
        capped.createLogStream("/app/logs", "stream-1", REGION);
        capped.putLogEvents("/app/logs", "stream-1", eventsAt(1000, 2000), REGION);

        events.errorNextPutAll = true;
        assertThrows(OutOfMemoryError.class,
                () -> capped.putLogEvents("/app/logs", "stream-1", eventsAt(3000, 4000), REGION));
        capped.putLogEvents("/app/logs", "stream-1", eventsAt(5000), REGION);

        assertEquals(List.of(3000L, 4000L, 5000L), storedTimestamps(capped, "/app/logs", "stream-1"),
                "an Error, not only a RuntimeException, leaves the index to be refilled");
    }

    @Test
    void anAccountKeepsOneIndexThroughAFailedWriteAndAReset() {
        // Writers queued on an index's monitor must never find a different index in the map,
        // or two writers would change one account's events under two locks.
        FailingOnceStorage events = new FailingOnceStorage();
        CloudWatchLogsService capped = new CloudWatchLogsService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), events,
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                10000, 3, new RegionResolver(REGION, "000000000000"));
        capped.createLogGroup("/app/logs", null, null, REGION);
        capped.createLogStream("/app/logs", "stream-1", REGION);
        capped.putLogEvents("/app/logs", "stream-1", eventsAt(1000), REGION);
        LogEventIndex index = capped.eventIndex("");

        events.failNextPutAll = true;
        assertThrows(IllegalStateException.class,
                () -> capped.putLogEvents("/app/logs", "stream-1", eventsAt(2000), REGION));
        assertSame(index, capped.eventIndex(""), "a failed write keeps the account's index");

        capped.beforeReset();
        capped.clear();
        capped.afterReset();
        assertSame(index, capped.eventIndex(""), "a reset keeps the account's index");
    }

    @Test
    void aResetReleasesTheIndexedEventsWithoutWaitingForTheNextWrite() {
        CloudWatchLogsService capped = serviceWithStoredEventCeiling(10);
        capped.createLogGroup("/app/logs", null, null, REGION);
        capped.createLogStream("/app/logs", "stream-1", REGION);
        capped.putLogEvents("/app/logs", "stream-1", eventsAt(1000, 2000, 3000), REGION);
        LogEventIndex index = capped.eventIndex("");
        assertEquals(3, index.size());

        capped.beforeReset();
        assertEquals(0, index.size(), "beforeReset() alone releases the entries");
        assertTrue(index.isStale(), "the next write still refills from the store");

        capped.putLogEvents("/app/logs", "stream-1", eventsAt(4000), REGION);
        capped.clear();
        assertEquals(0, index.size(), "clear() alone releases what was written during the reset");
        assertTrue(index.isStale());

        capped.putLogEvents("/app/logs", "stream-1", eventsAt(5000), REGION);
        capped.afterReset();
        assertEquals(0, index.size(), "afterReset() alone releases what was written before it");
        assertTrue(index.isStale());
    }

    @Test
    void putLogEventsDuringAResetDoNotSkewTheCeilingAfterIt() {
        InMemoryStorage<String, LogGroup> groups = new InMemoryStorage<>();
        InMemoryStorage<String, LogStream> streams = new InMemoryStorage<>();
        InMemoryStorage<String, LogEvent> events = new InMemoryStorage<>();
        CloudWatchLogsService capped = new CloudWatchLogsService(groups, streams, events,
                new InMemoryStorage<>(), new InMemoryStorage<>(), 10000, 3,
                new RegionResolver(REGION, "000000000000"));
        capped.createLogGroup("/app/logs", null, null, REGION);
        capped.createLogStream("/app/logs", "stream-1", REGION);
        capped.putLogEvents("/app/logs", "stream-1", eventsAt(1000, 2000, 3000), REGION);

        // The order EmulatorInfoController resets in: beforeReset, the storage wipe, clear,
        // afterReset. Calls land on both sides of the wipe, before the service is told.
        capped.beforeReset();
        capped.putLogEvents("/app/logs", "stream-1", eventsAt(4000), REGION);
        groups.clear();
        streams.clear();
        events.clear();
        capped.createLogGroup("/app/logs", null, null, REGION);
        capped.createLogStream("/app/logs", "stream-1", REGION);
        capped.putLogEvents("/app/logs", "stream-1", eventsAt(500), REGION);
        capped.clear();
        capped.afterReset();
        capped.putLogEvents("/app/logs", "stream-1", eventsAt(600, 700), REGION);

        assertEquals(List.of(500L, 600L, 700L), storedTimestamps(capped, "/app/logs", "stream-1"),
                "events wiped by the reset do not count toward the ceiling, before clear() or after");
    }

    @Test
    void aStoredEventWithoutAnIdDoesNotBreakIngest() {
        InMemoryStorage<String, LogEvent> events = new InMemoryStorage<>();
        CloudWatchLogsService capped = new CloudWatchLogsService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), events,
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                10000, 2, new RegionResolver(REGION, "000000000000"));
        capped.createLogGroup("/app/logs", null, null, REGION);
        capped.createLogStream("/app/logs", "stream-1", REGION);
        LogEvent legacy = new LogEvent();
        legacy.setTimestamp(1000);
        legacy.setMessage("legacy");
        events.put(REGION + "::/app/logs::stream-1::000000000001000::legacy", legacy);

        capped.putLogEvents("/app/logs", "stream-1", eventsAt(2000, 3000), REGION);

        assertEquals(List.of(2000L, 3000L), storedTimestamps(capped, "/app/logs", "stream-1"));
    }

    private static final class FailingOnceStorage extends InMemoryStorage<String, LogEvent> {
        boolean failNextPutAll;
        boolean errorNextPutAll;

        @Override
        public void putAll(Map<String, LogEvent> entries) {
            super.putAll(entries);
            if (failNextPutAll) {
                failNextPutAll = false;
                throw new IllegalStateException("journal write failed after the entries were stored");
            }
            if (errorNextPutAll) {
                errorNextPutAll = false;
                throw new OutOfMemoryError("ran out after the entries were stored");
            }
        }
    }

    private static final class CeilingWatchStorage extends InMemoryStorage<String, LogEvent> {
        final AtomicInteger largestBeforeAWrite = new AtomicInteger();

        @Override
        public void putAll(Map<String, LogEvent> entries) {
            largestBeforeAWrite.accumulateAndGet(keys().size(), Math::max);
            super.putAll(entries);
        }
    }

    private static final class CountingStorage<V> extends InMemoryStorage<String, V> {
        int enumerations;

        @Override
        public Set<String> keys() {
            enumerations++;
            return super.keys();
        }

        @Override
        public List<V> scan(Predicate<String> keyFilter) {
            enumerations++;
            return super.scan(keyFilter);
        }
    }

    // ──────────────────────────── KMS key association ────────────────────────────

    @Test
    void associateAndDisassociateKmsKey() {
        service.createLogGroup("/app/logs", null, null, REGION);
        assertNull(service.describeLogGroups("/app/logs", REGION).getFirst().getKmsKeyId(),
                "a freshly created log group has no CMK associated");

        service.associateKmsKey("/app/logs", KEY_ARN, REGION);
        assertEquals(KEY_ARN, service.describeLogGroups("/app/logs", REGION).getFirst().getKmsKeyId());

        service.disassociateKmsKey("/app/logs", REGION);
        assertNull(service.describeLogGroups("/app/logs", REGION).getFirst().getKmsKeyId());
    }

    @Test
    void associateKmsKeyReplacesAnExistingAssociation() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.associateKmsKey("/app/logs", KEY_ARN, REGION);

        String other = "arn:aws:kms:us-east-1:000000000000:key/99999999-9999-9999-9999-999999999999";
        service.associateKmsKey("/app/logs", other, REGION);

        assertEquals(other, service.describeLogGroups("/app/logs", REGION).getFirst().getKmsKeyId());
    }

    @Test
    void associateKmsKeyOnMissingGroupThrows() {
        assertThrows(AwsException.class, () -> service.associateKmsKey("/missing", KEY_ARN, REGION));
    }

    @Test
    void associateKmsKeyWithoutKeyIdThrows() {
        service.createLogGroup("/app/logs", null, null, REGION);
        assertThrows(AwsException.class, () -> service.associateKmsKey("/app/logs", "", REGION));
    }

    @Test
    void disassociateKmsKeyOnMissingGroupThrows() {
        assertThrows(AwsException.class, () -> service.disassociateKmsKey("/missing", REGION));
    }

    @Test
    void tagAndUntagLogGroup() {
        service.createLogGroup("/app/logs", null, Map.of("env", "prod"), REGION);
        service.tagLogGroup("/app/logs", Map.of("team", "platform"), REGION);

        Map<String, String> tags = service.listTagsLogGroup("/app/logs", REGION);
        assertEquals("prod", tags.get("env"));
        assertEquals("platform", tags.get("team"));

        service.untagLogGroup("/app/logs", List.of("env"), REGION);
        tags = service.listTagsLogGroup("/app/logs", REGION);
        assertFalse(tags.containsKey("env"));
    }

    // ──────────────────────────── Log Streams ────────────────────────────

    @Test
    void createLogStream() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);

        List<LogStream> streams = service.describeLogStreams("/app/logs", null, REGION);
        assertEquals(1, streams.size());
        assertEquals("stream-1", streams.getFirst().getLogStreamName());
    }

    @Test
    void createLogStreamForNonExistentGroupThrows() {
        assertThrows(AwsException.class, () ->
                service.createLogStream("/missing", "stream-1", REGION));
    }

    @Test
    void createLogStreamDuplicateThrows() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);
        assertThrows(AwsException.class, () ->
                service.createLogStream("/app/logs", "stream-1", REGION));
    }

    @Test
    void deleteLogStream() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);
        service.deleteLogStream("/app/logs", "stream-1", REGION);

        assertTrue(service.describeLogStreams("/app/logs", null, REGION).isEmpty());
    }

    @Test
    void describeLogStreamsOrdersByLastEventTimeDescendingWithLimit() {
        // The SDK idiom for "find the most recently active stream":
        // orderBy(LAST_EVENT_TIME).descending(true).limit(1). Alphabetical order is set up
        // to disagree with event recency so a name-sorted result would fail the assertion.
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "a-oldest", REGION);
        service.createLogStream("/app/logs", "b-newest", REGION);
        service.createLogStream("/app/logs", "c-middle", REGION);
        service.putLogEvents("/app/logs", "a-oldest", List.of(Map.of("timestamp", 1000L, "message", "old")), REGION);
        service.putLogEvents("/app/logs", "b-newest", List.of(Map.of("timestamp", 3000L, "message", "new")), REGION);
        service.putLogEvents("/app/logs", "c-middle", List.of(Map.of("timestamp", 2000L, "message", "mid")), REGION);

        CloudWatchLogsService.DescribeLogStreamsResult result = service.describeLogStreams("/app/logs", null, "LastEventTime", true, 1, null, REGION);

        assertEquals(1, result.logStreams().size());
        assertEquals("b-newest", result.logStreams().getFirst().getLogStreamName());
        assertNotNull(result.nextToken());
    }

    @Test
    void describeLogStreamsSortsStreamsWithoutEventsOldestByLastEventTime() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "z-empty", REGION);
        service.createLogStream("/app/logs", "a-active", REGION);
        service.putLogEvents("/app/logs", "a-active", List.of(Map.of("timestamp", 1000L, "message", "x")), REGION);

        CloudWatchLogsService.DescribeLogStreamsResult descending = service.describeLogStreams("/app/logs", null, "LastEventTime", true, 0, null, REGION);
        assertEquals(List.of("a-active", "z-empty"),
                descending.logStreams().stream().map(LogStream::getLogStreamName).toList());

        CloudWatchLogsService.DescribeLogStreamsResult ascending = service.describeLogStreams("/app/logs", null, "LastEventTime", false, 0, null, REGION);
        assertEquals(List.of("z-empty", "a-active"),
                ascending.logStreams().stream().map(LogStream::getLogStreamName).toList());
    }

    @Test
    void describeLogStreamsPaginatesWithNextToken() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);
        service.createLogStream("/app/logs", "stream-2", REGION);
        service.createLogStream("/app/logs", "stream-3", REGION);

        CloudWatchLogsService.DescribeLogStreamsResult page1 = service.describeLogStreams("/app/logs", null, null, false, 2, null, REGION);
        assertEquals(List.of("stream-1", "stream-2"),
                page1.logStreams().stream().map(LogStream::getLogStreamName).toList());
        assertNotNull(page1.nextToken());

        CloudWatchLogsService.DescribeLogStreamsResult page2 = service.describeLogStreams("/app/logs", null, null, false, 2, page1.nextToken(), REGION);
        assertEquals(List.of("stream-3"),
                page2.logStreams().stream().map(LogStream::getLogStreamName).toList());
        assertNull(page2.nextToken());
    }

    @Test
    void describeLogStreamsRejectsMalformedNextToken() {
        // A garbage token must fail loudly: silently restarting from the first page makes
        // custom pagination loops duplicate results or never progress.
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);

        AwsException e = assertThrows(AwsException.class, () ->
                service.describeLogStreams("/app/logs", null, null, false, 0, "not-a-token", REGION));
        assertEquals("InvalidParameterException", e.getErrorCode());
    }

    @Test
    void describeLogStreamsRejectsTokenFromDifferentOrdering() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);
        service.createLogStream("/app/logs", "stream-2", REGION);

        String nameOrderToken = service
                .describeLogStreams("/app/logs", null, null, false, 1, null, REGION)
                .nextToken();
        assertNotNull(nameOrderToken);

        AwsException e = assertThrows(AwsException.class, () ->
                service.describeLogStreams("/app/logs", null, "LastEventTime", true, 1, nameOrderToken, REGION));
        assertEquals("InvalidParameterException", e.getErrorCode());
    }

    @Test
    void describeLogStreamsPaginationDoesNotSkipAfterDeletionBetweenPages() {
        // A positional offset applied to the re-scanned collection would skip stream-3 here:
        // deleting already-returned stream-1 shifts everything left by one. The cursor keeps
        // the resume point anchored to the last returned stream instead.
        service.createLogGroup("/app/logs", null, null, REGION);
        for (int i = 1; i <= 4; i++) {
            service.createLogStream("/app/logs", "stream-" + i, REGION);
        }

        CloudWatchLogsService.DescribeLogStreamsResult page1 = service.describeLogStreams("/app/logs", null, null, false, 2, null, REGION);
        assertEquals(List.of("stream-1", "stream-2"),
                page1.logStreams().stream().map(LogStream::getLogStreamName).toList());

        service.deleteLogStream("/app/logs", "stream-1", REGION);

        CloudWatchLogsService.DescribeLogStreamsResult page2 = service.describeLogStreams("/app/logs", null, null, false, 2, page1.nextToken(), REGION);
        assertEquals(List.of("stream-3", "stream-4"),
                page2.logStreams().stream().map(LogStream::getLogStreamName).toList());
        assertNull(page2.nextToken());
    }

    @Test
    void describeLogStreamsLastEventTimePaginationDoesNotRepeatReorderedStreams() {
        // PutLogEvents to an already-returned stream between pages moves it even further
        // ahead in descending order. A positional offset would then re-serve the stream at
        // the boundary; the cursor never returns anything at-or-before the last seen key.
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "a", REGION);
        service.createLogStream("/app/logs", "b", REGION);
        service.createLogStream("/app/logs", "c", REGION);
        service.createLogStream("/app/logs", "d", REGION);
        service.putLogEvents("/app/logs", "a", List.of(Map.of("timestamp", 1000L, "message", "x")), REGION);
        service.putLogEvents("/app/logs", "b", List.of(Map.of("timestamp", 2000L, "message", "x")), REGION);
        service.putLogEvents("/app/logs", "c", List.of(Map.of("timestamp", 3000L, "message", "x")), REGION);
        service.putLogEvents("/app/logs", "d", List.of(Map.of("timestamp", 4000L, "message", "x")), REGION);

        CloudWatchLogsService.DescribeLogStreamsResult page1 = service.describeLogStreams("/app/logs", null, "LastEventTime", true, 2, null, REGION);
        assertEquals(List.of("d", "c"),
                page1.logStreams().stream().map(LogStream::getLogStreamName).toList());

        service.putLogEvents("/app/logs", "d", List.of(Map.of("timestamp", 5000L, "message", "x")), REGION);

        CloudWatchLogsService.DescribeLogStreamsResult page2 = service.describeLogStreams("/app/logs", null, "LastEventTime", true, 2, page1.nextToken(), REGION);
        assertEquals(List.of("b", "a"),
                page2.logStreams().stream().map(LogStream::getLogStreamName).toList());
        assertNull(page2.nextToken());
    }

    @Test
    void describeLogStreamsReturnsUnseenStreamThatReorderedAcrossThePageBoundary() {
        // An unreturned stream that receives a newer event between descending LastEventTime
        // pages sorts ahead of any saved sort-key cursor on the next request and would be
        // skipped forever. The snapshot freezes the ordering at page one, so the stream is
        // still returned in its original position.
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "a", REGION);
        service.createLogStream("/app/logs", "b", REGION);
        service.createLogStream("/app/logs", "c", REGION);
        service.createLogStream("/app/logs", "d", REGION);
        service.putLogEvents("/app/logs", "a", List.of(Map.of("timestamp", 1000L, "message", "x")), REGION);
        service.putLogEvents("/app/logs", "b", List.of(Map.of("timestamp", 2000L, "message", "x")), REGION);
        service.putLogEvents("/app/logs", "c", List.of(Map.of("timestamp", 3000L, "message", "x")), REGION);
        service.putLogEvents("/app/logs", "d", List.of(Map.of("timestamp", 4000L, "message", "x")), REGION);

        CloudWatchLogsService.DescribeLogStreamsResult page1 = service.describeLogStreams("/app/logs", null, "LastEventTime", true, 2, null, REGION);
        assertEquals(List.of("d", "c"),
                page1.logStreams().stream().map(LogStream::getLogStreamName).toList());

        // b was not returned yet; this would now sort it ahead of c, the last returned stream.
        service.putLogEvents("/app/logs", "b", List.of(Map.of("timestamp", 9000L, "message", "x")), REGION);

        CloudWatchLogsService.DescribeLogStreamsResult page2 = service.describeLogStreams("/app/logs", null, "LastEventTime", true, 2, page1.nextToken(), REGION);
        assertEquals(List.of("b", "a"),
                page2.logStreams().stream().map(LogStream::getLogStreamName).toList());
        assertNull(page2.nextToken());
        // Attributes are live even though the position is frozen.
        assertEquals(9000L, page2.logStreams().getFirst().getLastEventTimestamp());
    }

    @Test
    void describeLogStreamsRejectsLastEventTimeWithPrefix() {
        service.createLogGroup("/app/logs", null, null, REGION);
        AwsException e = assertThrows(AwsException.class, () ->
                service.describeLogStreams("/app/logs", "stream", "LastEventTime", true, 0, null, REGION));
        assertEquals("InvalidParameterException", e.getErrorCode());
    }

    @Test
    void describeLogStreamsRejectsUnknownOrderBy() {
        service.createLogGroup("/app/logs", null, null, REGION);
        AwsException e = assertThrows(AwsException.class, () ->
                service.describeLogStreams("/app/logs", null, "CreationTime", false, 0, null, REGION));
        assertEquals("InvalidParameterException", e.getErrorCode());
    }

    @Test
    void deleteLogGroupCascadesStreamsAndEvents() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);
        service.putLogEvents("/app/logs", "stream-1",
                List.of(Map.of("timestamp", System.currentTimeMillis(), "message", "hello")), REGION);

        service.deleteLogGroup("/app/logs", REGION);
        assertTrue(service.describeLogGroups(null, REGION).isEmpty());
    }

    // ──────────────────────────── Log Events ────────────────────────────

    @Test
    void putAndGetLogEvents() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);

        long now = System.currentTimeMillis();
        service.putLogEvents("/app/logs", "stream-1", List.of(
                Map.of("timestamp", now, "message", "first"),
                Map.of("timestamp", now + 1, "message", "second")
        ), REGION);

        CloudWatchLogsService.LogEventsResult result = service.getLogEvents(
                "/app/logs", "stream-1", null, null, 100, true, null, REGION);
        assertEquals(2, result.events().size());
        assertEquals("first", result.events().get(0).getMessage());
        assertEquals("second", result.events().get(1).getMessage());
    }

    @Test
    void getLogEventsPreservesIngestionOrderForSameTimestamp() {
        // Regression for issue #1584: events written in order within the same millisecond
        // must come back from GetLogEvents in ingestion order, not shuffled.
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);

        long ts = System.currentTimeMillis();
        List<Map<String, Object>> events = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            events.add(Map.of("timestamp", ts, "message", "SEQLINE-" + i));
        }
        service.putLogEvents("/app/logs", "stream-1", events, REGION);

        CloudWatchLogsService.LogEventsResult result = service.getLogEvents(
                "/app/logs", "stream-1", null, null, 100, true, null, REGION);

        assertEquals(10, result.events().size());
        for (int i = 0; i < 10; i++) {
            assertEquals("SEQLINE-" + i, result.events().get(i).getMessage(),
                    "event at index " + i + " out of ingestion order");
        }
    }

    @Test
    void filterLogEventsPreservesIngestionOrderForSameTimestamp() {
        // Same-timestamp ordering must also hold for FilterLogEvents.
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);

        long ts = System.currentTimeMillis();
        List<Map<String, Object>> events = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            events.add(Map.of("timestamp", ts, "message", "SEQLINE-" + i));
        }
        service.putLogEvents("/app/logs", "stream-1", events, REGION);

        CloudWatchLogsService.FilteredLogEventsResult result = service.filterLogEvents(
                "/app/logs", null, null, null, "SEQLINE", 100, null, REGION);

        assertEquals(10, result.events().size());
        for (int i = 0; i < 10; i++) {
            assertEquals("SEQLINE-" + i, result.events().get(i).event().getMessage(),
                    "event at index " + i + " out of ingestion order");
        }
    }

    @Test
    void getLogEventsWithTimeRange() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);

        long base = System.currentTimeMillis();
        service.putLogEvents("/app/logs", "stream-1", List.of(
                Map.of("timestamp", base, "message", "old"),
                Map.of("timestamp", base + 10000, "message", "new")
        ), REGION);

        CloudWatchLogsService.LogEventsResult result = service.getLogEvents(
                "/app/logs", "stream-1", base + 5000, null, 100, true, null, REGION);
        assertEquals(1, result.events().size());
        assertEquals("new", result.events().getFirst().getMessage());
    }

    @Test
    void filterLogEvents() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);

        long now = System.currentTimeMillis();
        service.putLogEvents("/app/logs", "stream-1", List.of(
                Map.of("timestamp", now, "message", "ERROR: something failed"),
                Map.of("timestamp", now + 1, "message", "INFO: all good"),
                Map.of("timestamp", now + 2, "message", "ERROR: another failure")
        ), REGION);

        CloudWatchLogsService.FilteredLogEventsResult result = service.filterLogEvents(
                "/app/logs", null, null, null, "ERROR", 100, null, REGION);
        assertEquals(2, result.events().size());
        assertTrue(result.events().stream().allMatch(f -> f.event().getMessage().contains("ERROR")));
    }

    @Test
    void filterLogEventsCarriesEmittingStreamPerEvent() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);
        service.createLogStream("/app/logs", "stream-2", REGION);

        long now = System.currentTimeMillis();
        service.putLogEvents("/app/logs", "stream-1",
                List.of(Map.of("timestamp", now, "message", "ERROR: from one")), REGION);
        service.putLogEvents("/app/logs", "stream-2",
                List.of(Map.of("timestamp", now + 1, "message", "ERROR: from two")), REGION);

        CloudWatchLogsService.FilteredLogEventsResult result = service.filterLogEvents(
                "/app/logs", null, null, null, "ERROR", 100, null, REGION);

        assertEquals(2, result.events().size());
        assertEquals("stream-1", result.events().get(0).logStreamName());
        assertEquals("stream-2", result.events().get(1).logStreamName());
    }

    @Test
    void filterLogEventsRestrictedToNamedStreamsSkipsTheRest() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);
        service.createLogStream("/app/logs", "stream-2", REGION);

        long now = System.currentTimeMillis();
        service.putLogEvents("/app/logs", "stream-1",
                List.of(Map.of("timestamp", now, "message", "keep me")), REGION);
        service.putLogEvents("/app/logs", "stream-2",
                List.of(Map.of("timestamp", now + 1, "message", "drop me")), REGION);

        CloudWatchLogsService.FilteredLogEventsResult result = service.filterLogEvents(
                "/app/logs", List.of("stream-2"), null, null, null, 100, null, REGION);

        assertEquals(1, result.events().size());
        assertEquals("stream-2", result.events().getFirst().logStreamName());
        assertEquals("drop me", result.events().getFirst().event().getMessage());
    }

    @Test
    void filterLogEventsDoesNotLeakEventsFromAPrefixSiblingGroup() {
        // "/app/logs" must not sweep up "/app/logs-archive": the key layout separates the group
        // from the stream with "::", so the group prefix has to be matched with it.
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);
        service.createLogGroup("/app/logs-archive", null, null, REGION);
        service.createLogStream("/app/logs-archive", "stream-1", REGION);

        long now = System.currentTimeMillis();
        service.putLogEvents("/app/logs", "stream-1",
                List.of(Map.of("timestamp", now, "message", "live")), REGION);
        service.putLogEvents("/app/logs-archive", "stream-1",
                List.of(Map.of("timestamp", now, "message", "archived")), REGION);

        CloudWatchLogsService.FilteredLogEventsResult result = service.filterLogEvents(
                "/app/logs", null, null, null, null, 100, null, REGION);

        assertEquals(1, result.events().size());
        assertEquals("live", result.events().getFirst().event().getMessage());
    }

    @Test
    void filterLogEventsRecoversStreamNamesHoldingAColon() {
        // AWS forbids ':' in a stream name but floci does not enforce that, so the stream a match
        // is attributed to must not depend on the name being AWS-legal.
        String awkward = "app:worker:1";
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", awkward, REGION);

        service.putLogEvents("/app/logs", awkward,
                List.of(Map.of("timestamp", System.currentTimeMillis(), "message", "hello")), REGION);

        CloudWatchLogsService.FilteredLogEventsResult result = service.filterLogEvents(
                "/app/logs", null, null, null, null, 100, null, REGION);

        assertEquals(1, result.events().size());
        assertEquals(awkward, result.events().getFirst().logStreamName());
    }

    @Test
    void filterLogEventsNoPattern() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);

        long now = System.currentTimeMillis();
        service.putLogEvents("/app/logs", "stream-1", List.of(
                Map.of("timestamp", now, "message", "msg1"),
                Map.of("timestamp", now + 1, "message", "msg2")
        ), REGION);

        CloudWatchLogsService.FilteredLogEventsResult result = service.filterLogEvents(
                "/app/logs", null, null, null, null, 100, null, REGION);
        assertEquals(2, result.events().size());
    }

    @Test
    void getStoredBytesForLogGroupSumsStreamBytesAndIgnoresOtherGroups() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);
        service.createLogStream("/app/logs", "stream-2", REGION);
        service.createLogGroup("/app/other", null, null, REGION);
        service.createLogStream("/app/other", "stream-1", REGION);

        long now = System.currentTimeMillis();
        service.putLogEvents("/app/logs", "stream-1",
                List.of(Map.of("timestamp", now, "message", "one")), REGION);
        service.putLogEvents("/app/logs", "stream-2",
                List.of(Map.of("timestamp", now + 1, "message", "two")), REGION);
        service.putLogEvents("/app/other", "stream-1",
                List.of(Map.of("timestamp", now + 2, "message", "other")), REGION);

        long expected = service.describeLogStreams("/app/logs", null, REGION).stream()
                .mapToLong(LogStream::getStoredBytes)
                .sum();
        assertTrue(expected > 0);
        assertEquals(expected, service.getStoredBytesForLogGroup("/app/logs", REGION));
        assertEquals(0, service.getStoredBytesForLogGroup("/app/empty", REGION));
    }

    @Test
    void putLogEventsUpdatesStreamMetadata() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);

        long now = System.currentTimeMillis();
        service.putLogEvents("/app/logs", "stream-1",
                List.of(Map.of("timestamp", now, "message", "test")), REGION);

        List<LogStream> streams = service.describeLogStreams("/app/logs", null, REGION);
        LogStream stream = streams.getFirst();
        assertEquals(now, stream.getFirstEventTimestamp());
        assertEquals(now, stream.getLastEventTimestamp());
        assertNotNull(stream.getLastIngestionTime());
    }

    @Test
    void putLogEventsStoresTheBatchWithOneBackendWrite() {
        String accountId = "111111111111";
        CountingStorageBackend<String, LogEvent> rawEvents = new CountingStorageBackend<>();
        CloudWatchLogsService batchService = new CloudWatchLogsService(
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, "000000000000"),
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, "000000000000"),
                new AccountAwareStorageBackend<>(rawEvents, null, "000000000000"),
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, "000000000000"),
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, "000000000000"),
                10_000,
                new RegionResolver(REGION, "000000000000"));
        batchService.createLogGroupForAccount(accountId, "/app/logs", null, null, REGION);
        batchService.createLogStreamForAccount(accountId, "/app/logs", "stream-1", REGION);

        batchService.putLogEventsForAccount(accountId, "/app/logs", "stream-1", List.of(
                Map.of("timestamp", 1_000L, "message", "first"),
                Map.of("timestamp", 2_000L, "message", "second"),
                Map.of("timestamp", 3_000L, "message", "third")), REGION);

        assertEquals(1, rawEvents.putAllCalls);
        assertEquals(0, rawEvents.putCalls);
        assertEquals(3, rawEvents.keys().size());
        assertTrue(rawEvents.keys().stream().allMatch(key -> key.startsWith(accountId + "/")));
    }

    private static final class CountingStorageBackend<K, V> extends InMemoryStorage<K, V> {
        private int putCalls;
        private int putAllCalls;

        @Override
        public void put(K key, V value) {
            putCalls++;
            super.put(key, value);
        }

        @Override
        public void putAll(Map<K, V> entries) {
            putAllCalls++;
            entries.forEach((key, value) -> super.put(key, value));
        }
    }

    @Test
    void maxEventsPerQueryIsRespected() {
        CloudWatchLogsService limitedService = new CloudWatchLogsService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                2,
                new RegionResolver("us-east-1", "000000000000")
        );

        limitedService.createLogGroup("/app/logs", null, null, REGION);
        limitedService.createLogStream("/app/logs", "stream-1", REGION);

        long now = System.currentTimeMillis();
        limitedService.putLogEvents("/app/logs", "stream-1", List.of(
                Map.of("timestamp", now, "message", "a"),
                Map.of("timestamp", now + 1, "message", "b"),
                Map.of("timestamp", now + 2, "message", "c")
        ), REGION);

        CloudWatchLogsService.LogEventsResult result = limitedService.getLogEvents(
                "/app/logs", "stream-1", null, null, 100, true, null, REGION);
        assertEquals(2, result.events().size());
    }

    // ──────────────────────────── GetLogEvents pagination (issue #90) ────────────────────────────

    private void putEvents(String group, String stream, long baseTs, int count) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            events.add(Map.of("timestamp", baseTs + i, "message", "msg-" + i));
        }
        service.putLogEvents(group, stream, events, REGION);
    }

    @Test
    void getLogEventsInitialTokensEncodePosition() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);
        putEvents("/app/logs", "stream-1", System.currentTimeMillis(), 5);

        CloudWatchLogsService.LogEventsResult result =
                service.getLogEvents("/app/logs", "stream-1", null, null, 100, true, null, REGION);

        assertEquals(5, result.events().size());
        assertEquals("f/5", result.nextForwardToken());
        assertEquals("b/0", result.nextBackwardToken());
    }

    @Test
    void getLogEventsForwardPaginationContinues() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);
        long base = System.currentTimeMillis();
        putEvents("/app/logs", "stream-1", base, 5);

        CloudWatchLogsService.LogEventsResult page1 =
                service.getLogEvents("/app/logs", "stream-1", null, null, 3, true, null, REGION);
        assertEquals(3, page1.events().size());
        assertEquals("msg-0", page1.events().get(0).getMessage());
        assertEquals("f/3", page1.nextForwardToken());

        CloudWatchLogsService.LogEventsResult page2 =
                service.getLogEvents("/app/logs", "stream-1", null, null, 3, true, page1.nextForwardToken(), REGION);
        assertEquals(2, page2.events().size());
        assertEquals("msg-3", page2.events().get(0).getMessage());
        assertEquals("f/5", page2.nextForwardToken());
    }

    @Test
    void getLogEventsAtEndOfStreamEchosToken() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);
        putEvents("/app/logs", "stream-1", System.currentTimeMillis(), 3);

        // Simulate the SDK sending back the last returned forward token
        CloudWatchLogsService.LogEventsResult atEnd =
                service.getLogEvents("/app/logs", "stream-1", null, null, 10, true, "f/3", REGION);

        assertEquals(0, atEnd.events().size());
        assertEquals("f/3", atEnd.nextForwardToken(), "token must echo back to signal end of stream");
    }

    @Test
    void getLogEventsPagesForwardWithAnUnboundedMaxEventsPerQuery() {
        CloudWatchLogsService unboundedService = new CloudWatchLogsService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                Integer.MAX_VALUE,
                new RegionResolver("us-east-1", "000000000000")
        );

        unboundedService.createLogGroup("/app/logs", null, null, REGION);
        unboundedService.createLogStream("/app/logs", "stream-1", REGION);
        long now = System.currentTimeMillis();
        unboundedService.putLogEvents("/app/logs", "stream-1", List.of(
                Map.of("timestamp", now, "message", "a"),
                Map.of("timestamp", now + 1, "message", "b"),
                Map.of("timestamp", now + 2, "message", "c")
        ), REGION);

        CloudWatchLogsService.LogEventsResult page =
                unboundedService.getLogEvents("/app/logs", "stream-1", null, null, 0, true, null, REGION);
        assertEquals(3, page.events().size());
        assertEquals("f/3", page.nextForwardToken());

        // GetLogEvents echoes its token at the end of the stream, so a paginator always
        // spends one more call on the token it was just handed.
        CloudWatchLogsService.LogEventsResult atEnd = unboundedService.getLogEvents(
                "/app/logs", "stream-1", null, null, 0, true, page.nextForwardToken(), REGION);
        assertEquals(0, atEnd.events().size());
        assertEquals("f/3", atEnd.nextForwardToken());
    }

    @Test
    void getLogEventsRejectsMalformedNextToken() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);
        putEvents("/app/logs", "stream-1", System.currentTimeMillis(), 3);

        AwsException exception = assertThrows(AwsException.class, () ->
                service.getLogEvents("/app/logs", "stream-1", null, null, 10, true, "f/not-a-token", REGION));

        assertEquals("InvalidParameterException", exception.getErrorCode());
        assertEquals(400, exception.getHttpStatus());
        assertEquals("The specified nextToken is invalid.", exception.getMessage());
    }

    @Test
    void getLogEventsRejectsNegativeNextToken() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);
        putEvents("/app/logs", "stream-1", System.currentTimeMillis(), 3);

        AwsException exception = assertThrows(AwsException.class, () ->
                service.getLogEvents("/app/logs", "stream-1", null, null, 10, true, "b/-1", REGION));

        assertEquals("InvalidParameterException", exception.getErrorCode());
        assertEquals(400, exception.getHttpStatus());
        assertEquals("The specified nextToken is invalid.", exception.getMessage());
    }

    @Test
    void getLogEventsRejectsOverflowNextToken() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);
        putEvents("/app/logs", "stream-1", System.currentTimeMillis(), 3);

        AwsException exception = assertThrows(AwsException.class, () ->
                service.getLogEvents("/app/logs", "stream-1", null, null, 10, true, "f/2147483648", REGION));

        assertEquals("InvalidParameterException", exception.getErrorCode());
        assertEquals(400, exception.getHttpStatus());
        assertEquals("The specified nextToken is invalid.", exception.getMessage());
    }

    @Test
    void getLogEventsRejectsUnrecognizedNextTokens() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);
        putEvents("/app/logs", "stream-1", System.currentTimeMillis(), 3);

        for (String token : List.of("", "x/1", "garbage")) {
            AwsException exception = assertThrows(AwsException.class, () ->
                    service.getLogEvents("/app/logs", "stream-1", null, null, 10, true, token, REGION));

            assertEquals("InvalidParameterException", exception.getErrorCode());
            assertEquals(400, exception.getHttpStatus());
            assertEquals("The specified nextToken is invalid.", exception.getMessage());
        }
    }

    @Test
    void getLogEventsStartFromTailWithNoToken() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);
        putEvents("/app/logs", "stream-1", System.currentTimeMillis(), 5);

        CloudWatchLogsService.LogEventsResult result =
                service.getLogEvents("/app/logs", "stream-1", null, null, 3, false, null, REGION);

        assertEquals(3, result.events().size());
        assertEquals("msg-2", result.events().get(0).getMessage());
        assertEquals("msg-4", result.events().get(2).getMessage());
        assertEquals("b/2", result.nextBackwardToken());
        assertEquals("f/5", result.nextForwardToken());
    }

    @Test
    void getLogEventsNarrowWindowPaginatesOnlyMatchingEventsAcrossManyOutOfWindowEvents() {
        // The window must be applied before pagination, not after: 6 in-window matches
        // surrounded by 200 out-of-window events must page exactly like a 6-event stream.
        // A refactor that paginates the full scan before filtering would return the wrong
        // messages here and drift the tokens off the match count.
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);
        long base = System.currentTimeMillis();
        putEvents("/app/logs", "stream-1", base, 200);

        long startTime = base + 97;
        long endTime = base + 102;

        CloudWatchLogsService.LogEventsResult page1 = service.getLogEvents(
                "/app/logs", "stream-1", startTime, endTime, 2, true, null, REGION);
        assertEquals(List.of("msg-97", "msg-98"),
                page1.events().stream().map(LogEvent::getMessage).toList());
        assertEquals("f/2", page1.nextForwardToken());

        CloudWatchLogsService.LogEventsResult page2 = service.getLogEvents(
                "/app/logs", "stream-1", startTime, endTime, 2, true, page1.nextForwardToken(), REGION);
        assertEquals(List.of("msg-99", "msg-100"),
                page2.events().stream().map(LogEvent::getMessage).toList());
        assertEquals("f/4", page2.nextForwardToken());

        CloudWatchLogsService.LogEventsResult page3 = service.getLogEvents(
                "/app/logs", "stream-1", startTime, endTime, 2, true, page2.nextForwardToken(), REGION);
        assertEquals(List.of("msg-101", "msg-102"),
                page3.events().stream().map(LogEvent::getMessage).toList());
        assertEquals("f/6", page3.nextForwardToken());

        CloudWatchLogsService.LogEventsResult atEnd = service.getLogEvents(
                "/app/logs", "stream-1", startTime, endTime, 2, true, page3.nextForwardToken(), REGION);
        assertEquals(0, atEnd.events().size());
        assertEquals("f/6", atEnd.nextForwardToken(), "token must echo back to signal end of the window");

        // startFromHead=false with no token must start from the tail of the *filtered*
        // window, not the tail of the full 200-event stream.
        CloudWatchLogsService.LogEventsResult tail = service.getLogEvents(
                "/app/logs", "stream-1", startTime, endTime, 2, false, null, REGION);
        assertEquals(List.of("msg-101", "msg-102"),
                tail.events().stream().map(LogEvent::getMessage).toList());
        assertEquals("b/4", tail.nextBackwardToken());
        assertEquals("f/6", tail.nextForwardToken());
    }

    @Test
    void filterLogEventsPagesForwardToTheNewestMatches() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);
        putEvents("/app/logs", "stream-1", System.currentTimeMillis(), 5);

        CloudWatchLogsService.FilteredLogEventsResult page1 = service.filterLogEvents(
                "/app/logs", null, null, null, null, 3, null, REGION);

        assertEquals(List.of("msg-0", "msg-1", "msg-2"),
                page1.events().stream().map(f -> f.event().getMessage()).toList());
        assertEquals("f/3", page1.nextToken());

        CloudWatchLogsService.FilteredLogEventsResult page2 = service.filterLogEvents(
                "/app/logs", null, null, null, null, 3, page1.nextToken(), REGION);

        // The newest matches were unreachable before: the cap kept the oldest slice and the token
        // carried no position, so this second page could never be requested.
        assertEquals(List.of("msg-3", "msg-4"),
                page2.events().stream().map(f -> f.event().getMessage()).toList());
        assertNull(page2.nextToken(), "a short final page must not advertise more results");
    }

    @Test
    void filterLogEventsOmitsTokenOnASingleFullPage() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);
        putEvents("/app/logs", "stream-1", System.currentTimeMillis(), 3);

        CloudWatchLogsService.FilteredLogEventsResult result = service.filterLogEvents(
                "/app/logs", null, null, null, null, 3, null, REGION);

        assertEquals(3, result.events().size());
        assertNull(result.nextToken(), "a page that exactly exhausts the matches is the last one");
    }

    @Test
    void filterLogEventsOmitsTokenOnAFullFinalPage() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);
        putEvents("/app/logs", "stream-1", System.currentTimeMillis(), 6);

        CloudWatchLogsService.FilteredLogEventsResult page1 = service.filterLogEvents(
                "/app/logs", null, null, null, null, 3, null, REGION);
        assertEquals(3, page1.events().size());
        assertEquals("f/3", page1.nextToken());

        CloudWatchLogsService.FilteredLogEventsResult page2 = service.filterLogEvents(
                "/app/logs", null, null, null, null, 3, page1.nextToken(), REGION);

        // Both pages are exactly full, so page size cannot distinguish "more to come" from
        // "finished". Only the position can, which is what makes this the case that pins the
        // emission condition.
        assertEquals(3, page2.events().size());
        assertEquals("msg-5", page2.events().get(2).event().getMessage());
        assertNull(page2.nextToken());
    }

    @Test
    void filterLogEventsEmptyGroupReturnsEmptyPageWithNoToken() {
        service.createLogGroup("/app/logs", null, null, REGION);

        CloudWatchLogsService.FilteredLogEventsResult result = service.filterLogEvents(
                "/app/logs", null, null, null, null, 10, null, REGION);

        assertTrue(result.events().isEmpty());
        assertNull(result.nextToken());
    }

    @Test
    void filterLogEventsAllMatchesExcludedReturnsEmptyPageWithNoToken() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);
        putEvents("/app/logs", "stream-1", System.currentTimeMillis(), 5);

        CloudWatchLogsService.FilteredLogEventsResult result = service.filterLogEvents(
                "/app/logs", null, null, null, "NOTHING-MATCHES-THIS", 3, null, REGION);

        assertTrue(result.events().isEmpty());
        assertNull(result.nextToken());
    }

    @Test
    void filterLogEventsTokenPastTheEndReturnsEmptyPageWithNoToken() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);
        putEvents("/app/logs", "stream-1", System.currentTimeMillis(), 3);

        CloudWatchLogsService.FilteredLogEventsResult result = service.filterLogEvents(
                "/app/logs", null, null, null, null, 10, "f/99", REGION);

        assertTrue(result.events().isEmpty());
        assertNull(result.nextToken());
    }

    @Test
    void filterLogEventsCursorAppliesAfterPatternAndTimeFilters() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);

        long now = System.currentTimeMillis();
        service.putLogEvents("/app/logs", "stream-1", List.of(
                Map.of("timestamp", now, "message", "ERROR: one"),
                Map.of("timestamp", now + 1, "message", "INFO: noise"),
                Map.of("timestamp", now + 2, "message", "ERROR: two"),
                Map.of("timestamp", now + 3, "message", "INFO: more noise"),
                Map.of("timestamp", now + 4, "message", "ERROR: three")
        ), REGION);

        CloudWatchLogsService.FilteredLogEventsResult page1 = service.filterLogEvents(
                "/app/logs", null, null, null, "ERROR", 2, null, REGION);
        assertEquals(2, page1.events().size());
        assertEquals("ERROR: one", page1.events().get(0).event().getMessage());
        assertEquals("ERROR: two", page1.events().get(1).event().getMessage());
        // Offset 2 indexes the three matches, not the five stored events. Indexing the raw scan
        // would land on "ERROR: two" here.
        assertEquals("f/2", page1.nextToken());

        CloudWatchLogsService.FilteredLogEventsResult page2 = service.filterLogEvents(
                "/app/logs", null, null, null, "ERROR", 2, page1.nextToken(), REGION);
        assertEquals(1, page2.events().size());
        assertEquals("ERROR: three", page2.events().get(0).event().getMessage());
        assertNull(page2.nextToken());
    }

    @Test
    void filterLogEventsNarrowWindowPaginatesOnlyMatchingEventsAcrossManyOutOfWindowEvents() {
        // Same guard as GetLogEvents: the window must be applied before pagination, so 6
        // in-window matches surrounded by 200 out-of-window events page like a 6-event scan,
        // not like the full one.
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);
        long base = System.currentTimeMillis();
        putEvents("/app/logs", "stream-1", base, 200);

        long startTime = base + 97;
        long endTime = base + 102;

        CloudWatchLogsService.FilteredLogEventsResult page1 = service.filterLogEvents(
                "/app/logs", null, startTime, endTime, null, 2, null, REGION);
        assertEquals(List.of("msg-97", "msg-98"),
                page1.events().stream().map(f -> f.event().getMessage()).toList());
        assertEquals("f/2", page1.nextToken());

        CloudWatchLogsService.FilteredLogEventsResult page2 = service.filterLogEvents(
                "/app/logs", null, startTime, endTime, null, 2, page1.nextToken(), REGION);
        assertEquals(List.of("msg-99", "msg-100"),
                page2.events().stream().map(f -> f.event().getMessage()).toList());
        assertEquals("f/4", page2.nextToken());

        CloudWatchLogsService.FilteredLogEventsResult page3 = service.filterLogEvents(
                "/app/logs", null, startTime, endTime, null, 2, page2.nextToken(), REGION);
        assertEquals(List.of("msg-101", "msg-102"),
                page3.events().stream().map(f -> f.event().getMessage()).toList());
        assertNull(page3.nextToken(), "a page that exactly exhausts the matches is the last one");
    }

    @Test
    void filterLogEventsPaginatesAcrossStreamsKeepingAttribution() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);
        service.createLogStream("/app/logs", "stream-2", REGION);

        long now = System.currentTimeMillis();
        service.putLogEvents("/app/logs", "stream-1", List.of(
                Map.of("timestamp", now, "message", "ERROR: a"),
                Map.of("timestamp", now + 2, "message", "ERROR: c")
        ), REGION);
        service.putLogEvents("/app/logs", "stream-2", List.of(
                Map.of("timestamp", now + 1, "message", "ERROR: b"),
                Map.of("timestamp", now + 3, "message", "ERROR: d")
        ), REGION);

        CloudWatchLogsService.FilteredLogEventsResult page1 = service.filterLogEvents(
                "/app/logs", null, null, null, "ERROR", 2, null, REGION);
        assertEquals(List.of("stream-1", "stream-2"),
                page1.events().stream().map(CloudWatchLogsService.FilteredEvent::logStreamName).toList());
        assertEquals("f/2", page1.nextToken());

        CloudWatchLogsService.FilteredLogEventsResult page2 = service.filterLogEvents(
                "/app/logs", null, null, null, "ERROR", 2, page1.nextToken(), REGION);
        assertEquals(List.of("stream-1", "stream-2"),
                page2.events().stream().map(CloudWatchLogsService.FilteredEvent::logStreamName).toList());
        assertEquals(List.of("ERROR: c", "ERROR: d"),
                page2.events().stream().map(f -> f.event().getMessage()).toList());
        assertNull(page2.nextToken());
    }

    @Test
    void filterLogEventsPaginatesWithinNamedStreamsOnly() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);
        service.createLogStream("/app/logs", "stream-2", REGION);

        long now = System.currentTimeMillis();
        service.putLogEvents("/app/logs", "stream-1", List.of(
                Map.of("timestamp", now, "message", "kept-0"),
                Map.of("timestamp", now + 2, "message", "kept-1"),
                Map.of("timestamp", now + 4, "message", "kept-2")
        ), REGION);
        service.putLogEvents("/app/logs", "stream-2", List.of(
                Map.of("timestamp", now + 1, "message", "excluded-0"),
                Map.of("timestamp", now + 3, "message", "excluded-1")
        ), REGION);

        CloudWatchLogsService.FilteredLogEventsResult page1 = service.filterLogEvents(
                "/app/logs", List.of("stream-1"), null, null, null, 2, null, REGION);
        assertEquals(List.of("kept-0", "kept-1"),
                page1.events().stream().map(f -> f.event().getMessage()).toList());
        // The excluded stream's events are dropped before the offset is computed, so the cursor
        // never has to skip over them.
        assertEquals("f/2", page1.nextToken());

        CloudWatchLogsService.FilteredLogEventsResult page2 = service.filterLogEvents(
                "/app/logs", List.of("stream-1"), null, null, null, 2, page1.nextToken(), REGION);
        assertEquals(List.of("kept-2"),
                page2.events().stream().map(f -> f.event().getMessage()).toList());
        assertNull(page2.nextToken());
    }

    @Test
    void filterLogEventsNeverEmitsACursorThatCannotAdvance() {
        CloudWatchLogsService capped = new CloudWatchLogsService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                0,
                new RegionResolver("us-east-1", "000000000000")
        );
        capped.createLogGroup("/app/logs", null, null, REGION);
        capped.createLogStream("/app/logs", "stream-1", REGION);
        capped.putLogEvents("/app/logs", "stream-1",
                List.of(Map.of("timestamp", System.currentTimeMillis(), "message", "msg")), REGION);

        CloudWatchLogsService.FilteredLogEventsResult result = capped.filterLogEvents(
                "/app/logs", null, null, null, null, 0, null, REGION);

        // A zero cap yields an empty page. Emitting a token here would point at the same offset
        // forever, so a paginating client would never terminate.
        assertTrue(result.events().isEmpty());
        assertNull(result.nextToken());
    }

    @Test
    void filterLogEventsRejectsMalformedNextToken() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);
        putEvents("/app/logs", "stream-1", System.currentTimeMillis(), 3);

        AwsException exception = assertThrows(AwsException.class, () ->
                service.filterLogEvents("/app/logs", null, null, null, null, 10, "f/not-a-token", REGION));

        assertEquals("InvalidParameterException", exception.getErrorCode());
        assertEquals(400, exception.getHttpStatus());
        assertEquals("The specified nextToken is invalid.", exception.getMessage());
    }

    @Test
    void filterLogEventsRejectsNegativeNextToken() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);
        putEvents("/app/logs", "stream-1", System.currentTimeMillis(), 3);

        AwsException exception = assertThrows(AwsException.class, () ->
                service.filterLogEvents("/app/logs", null, null, null, null, 10, "f/-1", REGION));

        assertEquals("InvalidParameterException", exception.getErrorCode());
        assertEquals(400, exception.getHttpStatus());
    }

    @Test
    void filterLogEventsRejectsOverflowNextToken() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);
        putEvents("/app/logs", "stream-1", System.currentTimeMillis(), 3);

        AwsException exception = assertThrows(AwsException.class, () ->
                service.filterLogEvents("/app/logs", null, null, null, null, 10, "f/2147483648", REGION));

        assertEquals("InvalidParameterException", exception.getErrorCode());
        assertEquals(400, exception.getHttpStatus());
    }

    @Test
    void filterLogEventsRejectsUnrecognizedNextTokens() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);
        putEvents("/app/logs", "stream-1", System.currentTimeMillis(), 3);

        // "b/0" is a GetLogEvents backward token; FilterLogEvents pages forward only, so it is
        // not a token this action can have issued.
        for (String token : List.of("", "b/0", "x/1", "garbage")) {
            AwsException exception = assertThrows(AwsException.class, () ->
                    service.filterLogEvents("/app/logs", null, null, null, null, 10, token, REGION));

            assertEquals("InvalidParameterException", exception.getErrorCode());
            assertEquals(400, exception.getHttpStatus());
            assertEquals("The specified nextToken is invalid.", exception.getMessage());
        }
    }

    // ──────────────────────────── Subscription Filters ────────────────────────────

    @Test
    void putSubscriptionFilter() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.putSubscriptionFilter("/app/logs", "my-filter", "ERROR", "arn:aws:lambda:us-east-1:000000000000:function:test", null, REGION);

        CloudWatchLogsService.DescribeSubscriptionFiltersResult result =
                service.describeSubscriptionFilters("/app/logs", null, null, 50, REGION);
        assertEquals(1, result.subscriptionFilters().size());
        SubscriptionFilter f = result.subscriptionFilters().getFirst();
        assertEquals("my-filter", f.getFilterName());
        assertEquals("/app/logs", f.getLogGroupName());
        assertEquals("ERROR", f.getFilterPattern());
        assertEquals("arn:aws:lambda:us-east-1:000000000000:function:test", f.getDestinationArn());
        assertEquals("ByLogStream", f.getDistribution());
        assertTrue(f.getCreationTime() > 0);
    }

    @Test
    void putSubscriptionFilterDefaultsDistribution() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.putSubscriptionFilter("/app/logs", "my-filter", "ERROR", "arn:aws:lambda:us-east-1:000000000000:function:test", null, REGION);

        SubscriptionFilter f = service.describeSubscriptionFilters("/app/logs", null, null, 50, REGION).subscriptionFilters().getFirst();
        assertEquals("ByLogStream", f.getDistribution());
    }

    @Test
    void putSubscriptionFilterWithoutLogGroupThrows() {
        assertThrows(AwsException.class, () ->
                service.putSubscriptionFilter("/missing", "my-filter", "ERROR", "arn:aws:lambda:us-east-1:000000000000:function:test", null, REGION));
    }

    @Test
    void putSubscriptionFilterUpsertsDuplicate() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.putSubscriptionFilter("/app/logs", "my-filter", "ERROR", "arn:aws:lambda:us-east-1:000000000000:function:test", null, REGION);
        // Upsert: calling with same name overwrites
        service.putSubscriptionFilter("/app/logs", "my-filter", "WARN", "arn:aws:lambda:us-east-1:000000000000:function:other", null, REGION);

        CloudWatchLogsService.DescribeSubscriptionFiltersResult result =
                service.describeSubscriptionFilters("/app/logs", null, null, 50, REGION);
        assertEquals(1, result.subscriptionFilters().size());
        assertEquals("WARN", result.subscriptionFilters().getFirst().getFilterPattern());
        assertEquals("arn:aws:lambda:us-east-1:000000000000:function:other", result.subscriptionFilters().getFirst().getDestinationArn());
    }

    @Test
    void describeSubscriptionFiltersWithPrefix() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.putSubscriptionFilter("/app/logs", "alpha-filter", "ERROR", "arn:aws:lambda:us-east-1:000000000000:function:a", null, REGION);
        service.putSubscriptionFilter("/app/logs", "beta-filter", "WARN", "arn:aws:lambda:us-east-1:000000000000:function:b", null, REGION);

        CloudWatchLogsService.DescribeSubscriptionFiltersResult result =
                service.describeSubscriptionFilters("/app/logs", "alpha", null, 50, REGION);
        assertEquals(1, result.subscriptionFilters().size());
        assertEquals("alpha-filter", result.subscriptionFilters().getFirst().getFilterName());
    }

    @Test
    void describeSubscriptionFiltersWithoutLogGroupThrows() {
        assertThrows(AwsException.class, () ->
                service.describeSubscriptionFilters("/missing", null, null, 50, REGION));
    }

    @Test
    void describeSubscriptionFiltersPagination() {
        service.createLogGroup("/app/logs", null, null, REGION);
        for (int i = 0; i < 5; i++) {
            service.putSubscriptionFilter("/app/logs", "filter-" + i, "ERROR", "arn:aws:lambda:us-east-1:000000000000:function:test", null, REGION);
        }

        CloudWatchLogsService.DescribeSubscriptionFiltersResult page1 =
                service.describeSubscriptionFilters("/app/logs", null, null, 2, REGION);
        assertEquals(2, page1.subscriptionFilters().size());
        assertNotNull(page1.nextToken());

        CloudWatchLogsService.DescribeSubscriptionFiltersResult page2 =
                service.describeSubscriptionFilters("/app/logs", null, page1.nextToken(), 2, REGION);
        assertEquals(2, page2.subscriptionFilters().size());
    }

    @Test
    void deleteSubscriptionFilter() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.putSubscriptionFilter("/app/logs", "my-filter", "ERROR", "arn:aws:lambda:us-east-1:000000000000:function:test", null, REGION);
        service.deleteSubscriptionFilter("/app/logs", "my-filter", REGION);

        CloudWatchLogsService.DescribeSubscriptionFiltersResult result =
                service.describeSubscriptionFilters("/app/logs", null, null, 50, REGION);
        assertTrue(result.subscriptionFilters().isEmpty());
    }

    @Test
    void deleteSubscriptionFilterNotFoundThrows() {
        service.createLogGroup("/app/logs", null, null, REGION);
        assertThrows(AwsException.class, () ->
                service.deleteSubscriptionFilter("/app/logs", "nonexistent", REGION));
    }

    @Test
    void getLogEventsBackwardPaginationEchosTokenAtStart() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);
        putEvents("/app/logs", "stream-1", System.currentTimeMillis(), 3);

        // b/0 means we are already at the start — echoed back
        CloudWatchLogsService.LogEventsResult atStart =
                service.getLogEvents("/app/logs", "stream-1", null, null, 10, true, "b/0", REGION);

        assertEquals(0, atStart.events().size());
        assertEquals("b/0", atStart.nextBackwardToken(), "token must echo back to signal start of stream");
    }
}
