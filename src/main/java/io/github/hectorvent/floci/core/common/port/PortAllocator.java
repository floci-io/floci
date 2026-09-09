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
        // This exception usually reaches an operator second-hand — a custom resource reports
        // FAILED and CloudFormation rolls the stack back, so what they see is a CFN error and
        // this text buried in floci's own log. It has to carry its own diagnosis: which pool
        // ran dry, how wide it was, and the property that widens it. Otherwise the only way to
        // learn the knob exists is to find this class in the source (issue #2206).
        throw new IllegalStateException(
                "Lambda Runtime API port pool exhausted: no free ports in range "
                        + basePort + "-" + maxPort + " (" + (maxPort - basePort + 1)
                        + " ports, all in use). One port is held per running Lambda container, "
                        + "so this is the concurrent-execution ceiling. Widen it with "
                        + "floci.services.lambda.runtime-api-base-port / "
                        + "floci.services.lambda.runtime-api-max-port.");
    }

    public void release(int port) {
        inUse.remove(port);
    }
}
