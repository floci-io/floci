package io.floci.aws.services.codeartifact;

import com.sun.net.httpserver.HttpServer;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.PerKeyContainerPool;
import io.floci.aws.testing.ParallelLocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
class SidecarRepositoryLeaseTest {

    @Test
    void unhealthyReplacementPreservesBothAdmittedUses() throws IOException {
        verifyRetirement(false);
    }

    @Test
    void resetProtectsRetiredAndCurrentContainersUntilUsesFinish() throws IOException {
        verifyRetirement(true);
    }

    private void verifyRetirement(boolean reset) throws IOException {
        AtomicBoolean unhealthy = new AtomicBoolean();
        HttpServer previous = server(unhealthy);
        HttpServer replacement = server(new AtomicBoolean());
        try {
            ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
            PerKeyContainerPool pool = new PerKeyContainerPool(lifecycle, "/health");
            SidecarRepositoryLease lease = new SidecarRepositoryLease("test", pool);
            lease.registerRepository("repository");
            lease.beginUse("repository");
            pool.ensureReady("repository", () -> new PerKeyContainerPool.StartedContainer("previous", url(previous)));
            unhealthy.set(true);
            lease.beginUse("repository");
            assertEquals(url(replacement), pool.ensureReady("repository",
                    () -> new PerKeyContainerPool.StartedContainer("replacement", url(replacement))));
            if (reset) {
                lease.stopManagedContainers();
                assertThrows(IllegalStateException.class, () -> lease.beginUse("repository"));
            }
            verifyNoInteractions(lifecycle);
            lease.endUse("repository");
            verifyNoInteractions(lifecycle);
            lease.endUse("repository");
            verify(lifecycle).stopAndRemoveStrict("previous", null);
            if (reset) {
                verify(lifecycle).stopAndRemoveStrict("replacement", null);
            }
            verifyNoMoreInteractions(lifecycle);
        } finally {
            previous.stop(0);
            replacement.stop(0);
        }
    }

    private HttpServer server(AtomicBoolean unhealthy) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(unhealthy.get() ? 503 : 200, -1);
            }
        });
        server.start();
        return server;
    }

    private String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
