package io.github.hectorvent.floci.services.rum;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.rum.model.AppMonitor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RumServiceTest {

    private final RumService service = new RumService();

    @Test
    void updateAppMonitorAtomicallyReplacesTheStoredSnapshot() {
        AppMonitor original = service.createAppMonitor("monitor", "old.example.com");

        service.updateAppMonitor("monitor", "new.example.com");

        AppMonitor updated = service.getAppMonitor("monitor");
        assertNotSame(original, updated);
        assertEquals("old.example.com", original.getDomain());
        assertEquals("new.example.com", updated.getDomain());
        assertEquals(original.getId(), updated.getId());
        assertEquals(original.getName(), updated.getName());
        assertEquals(original.getState(), updated.getState());
        assertEquals(original.getCreated(), updated.getCreated());
    }

    @Test
    void updateAppMonitorWithBlankDomainLeavesTheSnapshotUnchanged() {
        AppMonitor original = service.createAppMonitor("monitor", "old.example.com");

        service.updateAppMonitor("monitor", "  ");

        assertSame(original, service.getAppMonitor("monitor"));
        assertEquals("old.example.com", original.getDomain());
    }

    @Test
    void updateAppMonitorRejectsMissingMonitor() {
        AwsException error = assertThrows(
                AwsException.class,
                () -> service.updateAppMonitor("missing", "new.example.com"));

        assertEquals("ResourceNotFoundException", error.getErrorCode());
        assertEquals(404, error.getHttpStatus());
    }
}
