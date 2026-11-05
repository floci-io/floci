package io.github.hectorvent.floci.services.codeartifact;

import io.github.hectorvent.floci.core.common.docker.PerKeyContainerPool;
import org.jboss.logging.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps a repository's sidecar alive until every admitted use has completed. */
final class SidecarRepositoryLease {

    private static final Logger LOG = Logger.getLogger(SidecarRepositoryLease.class);

    private final String sidecarName;
    private final PerKeyContainerPool pool;
    private final ConcurrentHashMap<String, UseState> uses = new ConcurrentHashMap<>();

    private static final class UseState {
        private int active;
        private boolean releasing;
        private boolean stopping;
        private boolean stopped;
    }

    SidecarRepositoryLease(String sidecarName, PerKeyContainerPool pool) {
        this.sidecarName = sidecarName;
        this.pool = pool;
    }

    void registerRepository(String repositoryId) {
        uses.putIfAbsent(repositoryId, new UseState());
    }

    void beginUse(String repositoryId) {
        UseState use = uses.get(repositoryId);
        if (use == null) {
            throw new IllegalStateException("No " + sidecarName + " repository lease for " + repositoryId);
        }
        synchronized (use) {
            if (use.releasing) {
                throw new IllegalStateException(sidecarName + " sidecar is being released for repository "
                        + repositoryId);
            }
            use.active++;
        }
    }

    void endUse(String repositoryId) {
        UseState use = uses.get(repositoryId);
        if (use == null) {
            throw new IllegalStateException("No " + sidecarName + " sidecar use for repository " + repositoryId);
        }
        synchronized (use) {
            if (use.active == 0) {
                throw new IllegalStateException("No active " + sidecarName + " sidecar use for repository "
                        + repositoryId);
            }
            use.active--;
        }
        try {
            stopIfUnused(repositoryId, use);
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not stop {0} sidecar for repository {1} after its last use; "
                    + "teardown can retry cleanup.", sidecarName, repositoryId);
        }
    }

    void release(String repositoryId) {
        UseState use = uses.computeIfAbsent(repositoryId, key -> new UseState());
        synchronized (use) {
            use.releasing = true;
        }
        stopIfUnused(repositoryId, use);
    }

    private void stopIfUnused(String repositoryId, UseState use) {
        synchronized (use) {
            if (!use.releasing || use.active != 0 || use.stopping || use.stopped) {
                return;
            }
            use.stopping = true;
        }
        boolean stopped = false;
        try {
            if (pool.isTracked(repositoryId)) {
                pool.stopContainerStrict(repositoryId);
            }
            stopped = true;
        } finally {
            synchronized (use) {
                use.stopped = stopped;
                use.stopping = false;
                if (stopped) {
                    uses.remove(repositoryId, use);
                }
            }
        }
    }

    void stopManagedContainers() {
        RuntimeException[] failure = new RuntimeException[1];
        uses.forEach((id, use) -> {
            synchronized (use) {
                use.releasing = true;
            }
            try {
                stopIfUnused(id, use);
            } catch (RuntimeException e) {
                if (failure[0] == null) {
                    failure[0] = e;
                } else {
                    failure[0].addSuppressed(e);
                }
            }
        });
        try {
            pool.stopAllStrictExcept(Set.copyOf(uses.keySet()));
        } catch (RuntimeException e) {
            if (failure[0] == null) {
                failure[0] = e;
            } else {
                failure[0].addSuppressed(e);
            }
        }
        if (failure[0] != null) {
            throw failure[0];
        }
    }
}
