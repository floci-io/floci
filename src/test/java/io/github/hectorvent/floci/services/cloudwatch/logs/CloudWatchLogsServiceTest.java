package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogEvent;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogGroup;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogStream;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.SubscriptionFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CloudWatchLogsServiceTest {

    private static final String REGION = "us-east-1";

    private CloudWatchLogsService service;

    @BeforeEach
    void setUp() {
        service = new CloudWatchLogsService(
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

        var result = service.describeLogStreams("/app/logs", null, "LastEventTime", true, 1, null, REGION);

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

        var descending = service.describeLogStreams("/app/logs", null, "LastEventTime", true, 0, null, REGION);
        assertEquals(List.of("a-active", "z-empty"),
                descending.logStreams().stream().map(LogStream::getLogStreamName).toList());

        var ascending = service.describeLogStreams("/app/logs", null, "LastEventTime", false, 0, null, REGION);
        assertEquals(List.of("z-empty", "a-active"),
                ascending.logStreams().stream().map(LogStream::getLogStreamName).toList());
    }

    @Test
    void describeLogStreamsPaginatesWithNextToken() {
        service.createLogGroup("/app/logs", null, null, REGION);
        service.createLogStream("/app/logs", "stream-1", REGION);
        service.createLogStream("/app/logs", "stream-2", REGION);
        service.createLogStream("/app/logs", "stream-3", REGION);

        var page1 = service.describeLogStreams("/app/logs", null, null, false, 2, null, REGION);
        assertEquals(List.of("stream-1", "stream-2"),
                page1.logStreams().stream().map(LogStream::getLogStreamName).toList());
        assertNotNull(page1.nextToken());

        var page2 = service.describeLogStreams("/app/logs", null, null, false, 2, page1.nextToken(), REGION);
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

        var page1 = service.describeLogStreams("/app/logs", null, null, false, 2, null, REGION);
        assertEquals(List.of("stream-1", "stream-2"),
                page1.logStreams().stream().map(LogStream::getLogStreamName).toList());

        service.deleteLogStream("/app/logs", "stream-1", REGION);

        var page2 = service.describeLogStreams("/app/logs", null, null, false, 2, page1.nextToken(), REGION);
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

        var page1 = service.describeLogStreams("/app/logs", null, "LastEventTime", true, 2, null, REGION);
        assertEquals(List.of("d", "c"),
                page1.logStreams().stream().map(LogStream::getLogStreamName).toList());

        service.putLogEvents("/app/logs", "d", List.of(Map.of("timestamp", 5000L, "message", "x")), REGION);

        var page2 = service.describeLogStreams("/app/logs", null, "LastEventTime", true, 2, page1.nextToken(), REGION);
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

        var page1 = service.describeLogStreams("/app/logs", null, "LastEventTime", true, 2, null, REGION);
        assertEquals(List.of("d", "c"),
                page1.logStreams().stream().map(LogStream::getLogStreamName).toList());

        // b was not returned yet; this would now sort it ahead of c, the last returned stream.
        service.putLogEvents("/app/logs", "b", List.of(Map.of("timestamp", 9000L, "message", "x")), REGION);

        var page2 = service.describeLogStreams("/app/logs", null, "LastEventTime", true, 2, page1.nextToken(), REGION);
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
    void maxEventsPerQueryIsRespected() {
        CloudWatchLogsService limitedService = new CloudWatchLogsService(
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
