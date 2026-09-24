package io.github.hectorvent.floci.core.common.docker;

import org.jboss.logging.Logger;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Lazily starts, health-checks, and reuses one container per arbitrary key. Pulled out of
 * {@code VerdaccioSidecarManager} (one Verdaccio container per CodeArtifact npm repository) since
 * that lifecycle logic (map of running containers, per-key start lock so concurrent first-use of
 * two different keys never blocks on each other, health poll, restart-on-unhealthy, stop-all on
 * shutdown) has nothing Verdaccio-specific in it; only how to actually build and start a container
 * for a given key does. A future per-repository sidecar (pypiserver, BaGet) can reuse this
 * directly instead of re-deriving the same map/lock/poll skeleton.
 *
 * <p>Callers own the health-check path and the actual container creation (image, config, env),
 * supplied per call since those are exactly the format-specific parts.
 */
public class PerKeyContainerPool {

    private static final Logger LOG = Logger.getLogger(PerKeyContainerPool.class);

    /** What a {@link Starter} hands back once its container is created and started. */
    public record StartedContainer(String containerId, String baseUrl) {}

    /** Builds and starts a fresh container for the key {@link #ensureReady} was called with. */
    @FunctionalInterface
    public interface Starter {
        StartedContainer start();
    }

    private final ContainerLifecycleManager lifecycleManager;
    private final String healthPath;
    private final ConcurrentHashMap<String, StartedContainer> containers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> startLocks = new ConcurrentHashMap<>();

    /**
     * @param healthPath path (e.g. {@code /-/ping}) appended to a container's base URL to probe
     *                   readiness; expected to return HTTP 200 once the container can serve
     */
    public PerKeyContainerPool(ContainerLifecycleManager lifecycleManager, String healthPath) {
        this.lifecycleManager = lifecycleManager;
        this.healthPath = healthPath;
    }

    /**
     * Base URL of a ready container for {@code key}, starting one via {@code starter} if this is
     * the first use or the previous container is no longer healthy. Concurrent calls for
     * different keys never block on each other; concurrent calls for the same key serialize so
     * only one container is ever started for it.
     */
    public String ensureReady(String key, Starter starter) {
        StartedContainer existing = containers.get(key);
        if (existing != null && SidecarHealthHelper.probeHealth(existing.baseUrl(), healthPath)) {
            return existing.baseUrl();
        }
        Object lock = startLocks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            existing = containers.get(key);
            if (existing != null && SidecarHealthHelper.probeHealth(existing.baseUrl(), healthPath)) {
                return existing.baseUrl();
            }
            if (existing != null) {
                LOG.warnv("Sidecar container for key {0} is no longer healthy; restarting it", key);
                lifecycleManager.stopAndRemove(existing.containerId(), null);
            }
            StartedContainer started = starter.start();
            // Tracked before the health wait, not after: a timeout below must not stop
            // stopContainer()/stopAll() from ever finding this already-running container again.
            containers.put(key, started);
            SidecarHealthHelper.waitForHealth(started.baseUrl(), healthPath);
            return started.baseUrl();
        }
    }

    /**
     * Stops and removes the container for one key, if one was ever started. No-op otherwise.
     * Shares {@link #ensureReady}'s per-key lock so a stop racing a concurrent first-use of the
     * same key serializes against it, instead of running between the moment that start commits to
     * a container and the moment it becomes visible here: without the shared lock, a stop that
     * finds nothing to remove could be immediately followed by that start's own container
     * registration, orphaning it since nothing calls stop for the key again.
     */
    public void stopContainer(String key) {
        if (key == null) {
            return;
        }
        Object lock = startLocks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            StartedContainer container = containers.remove(key);
            if (container != null) {
                lifecycleManager.stopAndRemove(container.containerId(), null);
            }
        }
    }

    /** Stops and removes every container this pool has started, then forgets them all. */
    public void stopAll() {
        if (containers.isEmpty()) {
            return;
        }
        LOG.infov("Stopping {0} pooled sidecar container(s)", containers.size());
        containers.forEach((key, container) -> {
            try {
                lifecycleManager.stopAndRemove(container.containerId(), null);
            } catch (Exception e) {
                LOG.debugv(e, "Failed to stop pooled sidecar container for key {0}", key);
            }
        });
        containers.clear();
    }
}
