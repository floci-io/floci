package io.github.hectorvent.floci.core.common.port;

import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Hands out ports from a configured range for Lambda Runtime API servers.
 * Throws {@link IllegalStateException} when the range is exhausted; the
 * caller releases a port back to the pool via {@link #release(int)}.
 */
@ApplicationScoped
public class PortAllocator {

    private static final Logger LOG = Logger.getLogger(PortAllocator.class);
    private static final int PRESSURE_WARNING_PERCENT = 90;

    private final int basePort;
    private final int maxPort;
    private final int poolSize;
    private final int pressureWarningThreshold;
    private final Set<Integer> inUse = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean pressureWarningEmitted = new AtomicBoolean();
    private final Consumer<String> warningSink;

    @Inject
    public PortAllocator(EmulatorConfig config) {
        this(config.services().lambda().runtimeApiBasePort(),
                config.services().lambda().runtimeApiMaxPort());
    }

    PortAllocator(int basePort, int maxPort) {
        this(basePort, maxPort, LOG::warn);
    }

    PortAllocator(int basePort, int maxPort, Consumer<String> warningSink) {
        this.basePort = basePort;
        this.maxPort = maxPort;
        this.poolSize = maxPort - basePort + 1;
        this.pressureWarningThreshold = Math.max(1,
                (int) Math.ceil(poolSize * (PRESSURE_WARNING_PERCENT / 100.0)));
        this.warningSink = warningSink;
    }

    public int allocate() {
        for (int p = basePort; p <= maxPort; p++) {
            if (inUse.add(p)) {
                warnIfUnderPressure();
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
                        + basePort + "-" + maxPort + " (" + poolSize
                        + " ports, all in use). One port is held per running Lambda container, "
                        + "so this is the concurrent-execution ceiling. Widen it with "
                        + "floci.services.lambda.runtime-api-base-port / "
                        + "floci.services.lambda.runtime-api-max-port.");
    }

    public void release(int port) {
        if (!inUse.remove(port)) {
            return;
        }
        if (inUse.size() < pressureWarningThreshold) {
            pressureWarningEmitted.set(false);
        }
    }

    private void warnIfUnderPressure() {
        int allocated = inUse.size();
        if (allocated < pressureWarningThreshold || !pressureWarningEmitted.compareAndSet(false, true)) {
            return;
        }
        warningSink.accept(String.format(Locale.ROOT,
                "Lambda Runtime API port pool is %d%% allocated (%d/%d ports in use, range %d-%d). "
                        + "One port is held per running Lambda container. If this workload may grow, widen "
                        + "floci.services.lambda.runtime-api-base-port / floci.services.lambda.runtime-api-max-port "
                        + "before the pool is exhausted.",
                (allocated * 100) / poolSize, allocated, poolSize, basePort, maxPort));
    }
}
