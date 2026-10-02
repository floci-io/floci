package io.github.hectorvent.floci.core.common.docker;

import org.jboss.logging.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
     * Bumped by every {@link #stopAll()}. {@link #ensureReady} reads it before and after starting
     * a container: {@code stopAll()} takes no per-key lock (it has no single key to take), so it
     * cannot see a container that only starts registering itself after {@code stopAll()} already
     * iterated the map. A changed generation is how a start still notices that a teardown happened
     * while it was building, so it can stop what it just started instead of leaving it running with
     * nothing left able to ever stop it again.
     */
    private final AtomicLong generation = new AtomicLong();

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
            long startedGeneration = generation.get();
            StartedContainer started = starter.start();
            // Registered before checking the generation, not after: checking first and only then
            // registering leaves a gap between the two where a concurrent stopAll() can run and
            // see nothing (this container isn't in the map yet) without the generation bump alone
            // catching it either, since that only tells this call a reset happened, not whether
            // stopAll()'s own sweep already passed the point where it could have found this
            // container. Registering first means stopAll() either sees it in the map and stops it,
            // or runs entirely before this put and gets caught by the generation check below;
            // there is no window where neither happens.
            containers.put(key, started);
            if (generation.get() != startedGeneration) {
                // A stopAll() ran while this container was being built and may have already swept
                // past this key (its forEach found nothing, since the put above hadn't happened
                // yet) before clearing the map out from under this registration. Remove and stop
                // it directly rather than trust the map state, which that same stopAll() may have
                // already cleared.
                LOG.warnv("Sidecar pool was reset while starting a container for key {0}; stopping it", key);
                containers.remove(key);
                lifecycleManager.stopAndRemove(started.containerId(), null);
                throw new IllegalStateException("Sidecar pool was reset while starting a container for key " + key);
            }
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
        generation.incrementAndGet();
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
