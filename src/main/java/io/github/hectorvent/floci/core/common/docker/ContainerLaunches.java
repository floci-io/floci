package io.github.hectorvent.floci.core.common.docker;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The containers of the runs a component has in progress, by run id, so a stop, a reset or a shutdown can
 * end them wherever they are: before the container is up, the run reads that it was asked to stop once it
 * is; after, the container is removed. Whoever claims a run's container removes it, and no one else.
 */
public final class ContainerLaunches {

    private final ConcurrentHashMap<String, Launch> launches = new ConcurrentHashMap<>();

    /** A run of {@code id} is starting: its launch, which {@link #end} ends. */
    public Launch begin(String id) {
        Launch launch = new Launch();
        launches.put(id, launch);
        return launch;
    }

    /** The launch of the run of {@code id} in progress, or null when there is none. */
    public Launch get(String id) {
        return launches.get(id);
    }

    /** The run is over: its container, for the caller to remove, unless someone has claimed it already. */
    public String end(String id, Launch launch) {
        launches.remove(id, launch);
        return launch.claim();
    }

    /** Stops every run in progress, as {@link Launch#stopNow} does, so a reset or a shutdown orphans none. */
    public void stopAll(ContainerLifecycleManager lifecycleManager, int stopTimeoutSeconds) {
        for (Launch launch : launches.values()) {
            launch.stopNow(lifecycleManager, stopTimeoutSeconds);
        }
    }

    /** A run in progress: its container, once up and until claimed, and whether it was asked to stop. */
    public static final class Launch {

        private final AtomicReference<String> container = new AtomicReference<>();
        private volatile boolean stopRequested;

        /** The run's container is up. */
        public void started(String containerId) {
            container.set(containerId);
        }

        /** The run's container, or null before it is up or once claimed. */
        public String container() {
            return container.get();
        }

        /** Takes the run's container, to remove it: null when it is not up, or someone else took it. */
        public String claim() {
            return container.getAndSet(null);
        }

        public boolean stopRequested() {
            return stopRequested;
        }

        /** Asks the run to stop, which it reads itself; nothing is removed. */
        public void requestStop() {
            stopRequested = true;
        }

        /** Asks the run to stop, and removes its container now when it is up and no one has claimed it. */
        public void stopNow(ContainerLifecycleManager lifecycleManager, int stopTimeoutSeconds) {
            stopRequested = true;
            String containerId = claim();
            if (containerId != null) {
                lifecycleManager.stopAndRemove(containerId, null, stopTimeoutSeconds);
            }
        }
    }
}
