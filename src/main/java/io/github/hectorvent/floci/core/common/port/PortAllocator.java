package io.github.hectorvent.floci.core.common.port;

import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hands out ports from a configured range for Lambda Runtime API servers.
 * Throws {@link IllegalStateException} when the range is exhausted; the
 * caller releases a port back to the pool via {@link #release(int)}.
 */
@ApplicationScoped
public class PortAllocator {

    private final int basePort;
    private final int maxPort;
    private final Set<Integer> inUse = ConcurrentHashMap.newKeySet();

    @Inject
    public PortAllocator(EmulatorConfig config) {
        this(config.services().lambda().runtimeApiBasePort(),
                config.services().lambda().runtimeApiMaxPort());
    }

    PortAllocator(int basePort, int maxPort) {
        this.basePort = basePort;
        this.maxPort = maxPort;
    }

    public int allocate() {
        for (int p = basePort; p <= maxPort; p++) {
            if (inUse.add(p)) {
                return p;
            }
        }
        throw new IllegalStateException(
                "No free ports in range " + basePort + "-" + maxPort);
    }

    public void release(int port) {
        inUse.remove(port);
    }
}
