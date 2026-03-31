package io.github.hectorvent.floci.core.common.port;

import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe sequential port dispenser for Lambda Runtime API servers.
 */
@ApplicationScoped
public class PortAllocator {

    private final AtomicInteger counter;
    private final int basePort;
    private final int range;

    @Inject
    public PortAllocator(EmulatorConfig config) {
        this.basePort = config.services().lambda().runtimeApiBasePort();
        int maxPort = config.services().lambda().runtimeApiMaxPort();
        this.range = maxPort - basePort + 1;
        this.counter = new AtomicInteger(0);
    }

    PortAllocator(int basePort, int maxPort) {
        this.basePort = basePort;
        this.range = maxPort - basePort + 1;
        this.counter = new AtomicInteger(0);
    }

    public int allocate() {
        int offset = counter.getAndIncrement();
        return basePort + (Math.abs(offset) % range);
    }
}
