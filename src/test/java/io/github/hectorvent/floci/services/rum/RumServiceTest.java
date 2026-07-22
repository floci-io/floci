package io.github.hectorvent.floci.services.rum;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.services.rum.model.AppMonitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RumServiceTest {

    private static final String REGION = "us-east-1";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RumService service = new RumService(new InMemoryStorage<>());

    @Test
    void updateAppMonitorAtomicallyReplacesTheStoredSnapshot() throws Exception {
        AppMonitor original = service.createAppMonitor(REGION, request("""
                {"Name":"monitor","Domain":"old.example.com","CwLogEnabled":false}
                """));

        service.updateAppMonitor(REGION, "monitor", request("""
                {
                  "Domain":"new.example.com",
                  "AppMonitorConfiguration":{"AllowCookies":true,"SessionSampleRate":0.5},
                  "CwLogEnabled":true,
                  "CustomEvents":{"Status":"ENABLED"}
                }
                """));

        AppMonitor updated = service.getAppMonitor(REGION, "monitor");
        assertNotSame(original, updated);
        assertEquals("old.example.com", original.getDomain());
        assertEquals("new.example.com", updated.getDomain());
        assertEquals(original.getId(), updated.getId());
        assertEquals(original.getName(), updated.getName());
        assertEquals(original.getState(), updated.getState());
        assertEquals("Web", updated.getPlatform());
        assertEquals(original.getCreated(), updated.getCreated());
        assertEquals(19, updated.getLastModified().length());
        assertTrue(updated.getAppMonitorConfiguration().path("AllowCookies").booleanValue());
        assertTrue(updated.getDataStorage().path("CwLog").path("CwLogEnabled").booleanValue());
        assertEquals("ENABLED", updated.getCustomEvents().path("Status").textValue());
    }

    @Test
    void emptyUpdateLeavesTheStoredSnapshotUnchanged() throws Exception {
        AppMonitor original = service.createAppMonitor(REGION, request("""
                {"Name":"monitor","Domain":"old.example.com"}
                """));

        service.updateAppMonitor(REGION, "monitor", request("{}"));

        assertEquals(original, service.getAppMonitor(REGION, "monitor"));
    }

    @Test
    void updateAppMonitorRejectsBlankDomain() throws Exception {
        service.createAppMonitor(REGION, request("""
                {"Name":"monitor","Domain":"old.example.com"}
                """));

        AwsException error = assertThrows(
                AwsException.class,
                () -> service.updateAppMonitor(REGION, "monitor", request("{\"Domain\":\"  \"}")));

        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void updateAppMonitorRejectsMissingMonitor() throws Exception {
        AwsException error = assertThrows(
                AwsException.class,
                () -> service.updateAppMonitor(REGION, "missing", request("{\"Domain\":\"new.example.com\"}")));

        assertEquals("ResourceNotFoundException", error.getErrorCode());
        assertEquals(404, error.getHttpStatus());
        assertEquals("missing", error.getExtendedData().get("resourceName"));
    }

    @Test
    void createAppMonitorRejectsDuplicateWithoutChangingOriginal() throws Exception {
        AppMonitor original = service.createAppMonitor(REGION, request("""
                {"Name":"monitor","Domain":"old.example.com"}
                """));

        AwsException error = assertThrows(
                AwsException.class,
                () -> service.createAppMonitor(REGION, request("""
                        {"Name":"monitor","Domain":"new.example.com"}
                        """)));

        assertEquals("ConflictException", error.getErrorCode());
        assertEquals(409, error.getHttpStatus());
        assertEquals("old.example.com", service.getAppMonitor(REGION, "monitor").getDomain());
        assertEquals(original.getId(), service.getAppMonitor(REGION, "monitor").getId());
    }

    @Test
    void listAppMonitorsPaginatesInNameOrder() throws Exception {
        for (String name : List.of("monitor-c", "monitor-a", "monitor-b")) {
            service.createAppMonitor(REGION, request("""
                    {"Name":"%s","Domain":"example.com"}
                    """.formatted(name)));
        }

        RumService.Page first = service.listAppMonitors(REGION, "2", null);
        RumService.Page second = service.listAppMonitors(REGION, "2", first.nextToken());

        assertEquals(List.of("monitor-a", "monitor-b"),
                first.monitors().stream().map(AppMonitor::getName).toList());
        assertEquals(List.of("monitor-c"), second.monitors().stream().map(AppMonitor::getName).toList());
        assertFalse(first.nextToken().isBlank());
        assertEquals(null, second.nextToken());
    }

    @Test
    void listAppMonitorsRejectsInvalidLimitsAndTokens() {
        for (String limit : List.of("0", "101", "not-a-number")) {
            AwsException error = assertThrows(
                    AwsException.class, () -> service.listAppMonitors(REGION, limit, null));
            assertEquals("ValidationException", error.getErrorCode());
        }
        AwsException tokenError = assertThrows(
                AwsException.class, () -> service.listAppMonitors(REGION, null, "not-a-token"));
        assertEquals("ValidationException", tokenError.getErrorCode());
    }

    @Test
    void createAppMonitorSupportsDomainListAndDefaultsLoggingOff() throws Exception {
        AppMonitor monitor = service.createAppMonitor(REGION, request("""
                {"Name":"monitor","DomainList":["example.com","localhost"]}
                """));

        assertEquals(List.of("example.com", "localhost"), monitor.getDomainList());
        assertEquals(null, monitor.getDomain());
        assertFalse(monitor.getDataStorage().path("CwLog").path("CwLogEnabled").booleanValue());
        assertEquals(19, monitor.getCreated().length());
        assertEquals(19, monitor.getLastModified().length());
        assertEquals("Web", monitor.getPlatform());
    }

    @Test
    void appMonitorConfigurationCanBeReloadedFromPersistentStorage(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("rum.json");
        var firstStore = new PersistentStorage<String, AppMonitor>(
                file, new TypeReference<Map<String, AppMonitor>>() {
                });
        firstStore.load();
        RumService firstService = new RumService(firstStore);
        AppMonitor created = firstService.createAppMonitor(REGION, request("""
                {
                  "Name":"persistent-monitor",
                  "Domain":"example.com",
                  "AppMonitorConfiguration":{"AllowCookies":true},
                  "CwLogEnabled":true,
                  "Tags":{"Owner":"floci"}
                }
                """));

        var reloadedStore = new PersistentStorage<String, AppMonitor>(
                file, new TypeReference<Map<String, AppMonitor>>() {
                });
        reloadedStore.load();
        AppMonitor reloaded = new RumService(reloadedStore).getAppMonitor(REGION, "persistent-monitor");

        assertEquals(created.getId(), reloaded.getId());
        assertEquals(created.getCreated(), reloaded.getCreated());
        assertEquals("Web", reloaded.getPlatform());
        assertEquals("example.com", reloaded.getDomain());
        assertTrue(reloaded.getAppMonitorConfiguration().path("AllowCookies").booleanValue());
        assertTrue(reloaded.getDataStorage().path("CwLog").path("CwLogEnabled").booleanValue());
        assertEquals("floci", reloaded.getTags().get("Owner"));
    }

    private JsonNode request(String json) throws Exception {
        return objectMapper.readTree(json);
    }
}
