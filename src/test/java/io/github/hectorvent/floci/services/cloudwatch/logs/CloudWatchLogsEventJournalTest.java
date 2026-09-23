package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Under {@code persistent} storage a PutLogEvents batch is appended to the events journal instead
 * of rewriting the whole events store on every call, while the definition stores keep their
 * rewritten-on-every-call file (issue #2500).
 */
class CloudWatchLogsEventJournalTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "eu-west-1";
    private static final String GROUP = "/journal";
    private static final String STREAM = "s";
    private static final int EVENTS_PER_BATCH = 10;

    @TempDir
    Path directory;

    @Test
    void putLogEventsUnderPersistentModeAppendsToTheJournalInsteadOfRewritingTheStore() throws IOException {
        Path journal = directory.resolve("cwlogs-events.wal");
        Path eventStore = directory.resolve("cwlogs-events.json");
        Path groupStore = directory.resolve("cwlogs-groups.json");

        StorageFactory first = newFactory();
        CloudWatchLogsService logs = newService(first);
        logs.createLogGroup(GROUP, null, null, REGION);
        logs.createLogStream(GROUP, STREAM, REGION);
        assertTrue(Files.readString(groupStore).contains(GROUP), "definition stores are still written on every call");

        Set<String> written = new HashSet<>();
        long journalSize = 0;
        for (int batch = 0; batch < 3; batch++) {
            List<Map<String, Object>> events = batch(batch);
            logs.putLogEvents(GROUP, STREAM, events, REGION);
            events.forEach(event -> written.add((String) event.get("message")));
            assertTrue(journalSize(journal) > journalSize, "every batch must append to the journal");
            journalSize = journalSize(journal);
            assertFalse(Files.exists(eventStore), "a batch must not rewrite the events store");
        }
        first.shutdownAll();

        assertTrue(Files.exists(eventStore), "a clean shutdown folds the journal into the events store");
        StorageFactory second = newFactory();
        CloudWatchLogsService reopened = newService(second);
        List<LogEvent> read = reopened.getLogEvents(GROUP, STREAM, null, null, 100, true, null, REGION).events();
        assertEquals(written, read.stream().map(LogEvent::getMessage).collect(Collectors.toSet()));
        second.shutdownAll();
    }

    private static long journalSize(Path journal) throws IOException {
        return Files.exists(journal) ? Files.size(journal) : 0L;
    }

    private static List<Map<String, Object>> batch(int batch) {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> events = new ArrayList<>();
        for (int i = 0; i < EVENTS_PER_BATCH; i++) {
            events.add(Map.of("timestamp", now + i, "message", "batch " + batch + " event " + i));
        }
        return events;
    }

    private CloudWatchLogsService newService(StorageFactory factory) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().cloudwatchlogs().maxEventsPerQuery()).thenReturn(10_000);
        when(config.services().cloudwatchlogs().maxStoredEvents()).thenReturn(10_000);
        return new CloudWatchLogsService(factory, config, new RegionResolver(REGION, ACCOUNT), null, null);
    }

    private StorageFactory newFactory() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        when(config.storage().wal().compactionIntervalMs()).thenReturn(3_600_000L);
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode(anyString())).thenReturn("persistent");
        when(access.storageFlushInterval(anyString())).thenReturn(3_600_000L);
        return new StorageFactory(config, access);
    }
}
