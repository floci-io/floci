package io.floci.aws.services.dsql;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.dsql.dataplane.DsqlDatabases;
import io.floci.aws.services.dsql.dataplane.DsqlEndpoint;
import io.floci.aws.services.dsql.model.DsqlCluster;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Runs each cluster's database and serves it on the DSQL endpoint. A cluster's database starts in
 * the background, and the cluster becomes ACTIVE once it answers, as on AWS. With
 * {@code floci.services.dsql.mock} on, a cluster has none and is ready at once, which is enough
 * for the control plane and CloudFormation.
 */
@ApplicationScoped
public class DsqlDataPlane {

    private static final Logger LOG = Logger.getLogger(DsqlDataPlane.class);
    /**
     * How long Floci's shutdown waits for the starts and deletions still running: longer than a
     * start waits for its database to answer.
     */
    static final Duration WORK_AT_SHUTDOWN = Duration.ofMinutes(3);

    private final boolean mock;
    private final boolean memoryStorage;
    private final DsqlDatabases databases;
    private final DsqlEndpoint endpoint;
    private final Map<String, DsqlCluster> running = new ConcurrentHashMap<>();
    private final Set<String> wanted = ConcurrentHashMap.newKeySet();
    // The starts and deletions still running, which a shutdown waits for.
    private final Set<Thread> working = ConcurrentHashMap.newKeySet();
    // Whether a cluster is still wanted and its route are changed together, so a deletion during a
    // start never leaves the route of a database already gone.
    private final Object routes = new Object();
    // Set under routes once Floci stops, after which no start registers a route.
    private boolean stopping;

    @Inject
    public DsqlDataPlane(EmulatorConfig config, DsqlDatabases databases, DsqlEndpoint endpoint) {
        this.mock = config.services().dsql().mock();
        this.memoryStorage = "memory".equals(config.storage().mode());
        this.databases = databases;
        this.endpoint = endpoint;
    }

    /** Starts the cluster's database, then reports whether it is ready to serve. */
    public void provision(DsqlCluster cluster, Consumer<Boolean> onReady) {
        if (mock) {
            onReady.accept(true);
            return;
        }
        wanted.add(cluster.getArn());
        inBackground("dsql-start-" + cluster.getIdentifier(), () -> start(cluster, onReady));
    }

    /** Runs {@code work} on a thread of its own, which a shutdown waits for. */
    private void inBackground(String name, Runnable work) {
        Thread thread = Thread.ofVirtual().name(name).unstarted(() -> {
            try {
                work.run();
            } finally {
                working.remove(Thread.currentThread());
            }
        });
        working.add(thread);
        thread.start();
    }

    private void start(DsqlCluster cluster, Consumer<Boolean> onReady) {
        String arn = cluster.getArn();
        DsqlDatabases.Backend backend;
        try {
            backend = databases.start(cluster);
        } catch (Exception e) {
            LOG.warnv(e, "The database of DSQL cluster {0} did not start", cluster.getIdentifier());
            onReady.accept(false);
            return;
        }
        boolean shutDown;
        boolean deleted;
        boolean ready = false;
        synchronized (routes) {
            shutDown = stopping;
            deleted = !wanted.contains(arn);
            if (!deleted && !shutDown) {
                try {
                    endpoint.register(cluster, backend);
                    running.put(arn, cluster);
                    ready = true;
                } catch (IOException e) {
                    LOG.warnv(e, "The DSQL endpoint cannot serve cluster {0}", cluster.getIdentifier());
                }
            }
        }
        if (shutDown) {
            // Floci stopped while it started: it goes as the running clusters went, and its status is
            // left as it was, so the next start of Floci starts it again.
            stopAtShutdown(cluster);
            return;
        }
        if (!ready) {
            // Deleted while it started, or unreachable: no database is left running for it.
            if (deleted) {
                drop(cluster);
            } else {
                stop(cluster, false);
            }
        }
        onReady.accept(ready);
    }

    /**
     * Stops the cluster's database and drops its data, then runs {@code onGone}. The roles the
     * deletion drops are set on the cluster and handed to {@code persistPendingRoles} to be saved
     * before anything is dropped, so a deletion finished later drops the same ones. When they cannot
     * be found or removed, the cluster stays as it is, and the next start of Floci finishes its
     * deletion; unless the cluster {@code failed} and its backend lets that deletion finish anyway,
     * as AWS deletes a FAILED cluster, with a warning naming what may remain on the server.
     */
    public void deprovision(DsqlCluster cluster, boolean failed, Consumer<List<String>> persistPendingRoles,
                            Runnable onGone) {
        if (mock) {
            onGone.run();
            return;
        }
        synchronized (routes) {
            wanted.remove(cluster.getArn());
            running.remove(cluster.getArn());
            endpoint.unregister(cluster);
        }
        inBackground("dsql-stop-" + cluster.getIdentifier(), () -> {
            List<String> roles;
            try {
                roles = databases.rolesToDrop(cluster);
            } catch (SQLException | RuntimeException e) {
                LOG.warnv(e, "The roles of DSQL cluster {0} could not be listed", cluster.getIdentifier());
                if (failed && finishesAnyway(cluster)) {
                    onGone.run();
                }
                return;
            }
            if (!roles.isEmpty()) {
                cluster.setPendingBackendRoles(roles);
                persistPendingRoles.accept(roles);
            }
            if (stop(cluster, true) || (failed && finishesAnyway(cluster))) {
                onGone.run();
            }
        });
    }

    /** Whether a FAILED cluster's deletion finishes although its server was not cleaned up. */
    private boolean finishesAnyway(DsqlCluster cluster) {
        return databases.leftoversOfFailed(cluster).map(leftovers -> {
            LOG.warnv("DSQL cluster {0} failed and its server could not be cleaned up; it is deleted anyway,"
                    + " and its {1} may remain on the server", cluster.getIdentifier(), leftovers);
            return true;
        }).orElse(false);
    }

    /**
     * Drops the cluster's database and roles, best effort, for a cluster that goes without a deletion
     * of its own: one deleted while it started, or one of in-memory storage when Floci stops. With
     * its roles unlisted, the backend drops the ones it always knows.
     */
    private void drop(DsqlCluster cluster) {
        try {
            List<String> roles = databases.rolesToDrop(cluster);
            if (!roles.isEmpty()) {
                cluster.setPendingBackendRoles(roles);
            }
        } catch (SQLException | RuntimeException e) {
            LOG.warnv(e, "The roles of DSQL cluster {0} could not be listed", cluster.getIdentifier());
        }
        stop(cluster, true);
    }

    private boolean stop(DsqlCluster cluster, boolean dropData) {
        try {
            databases.stop(cluster, dropData);
            return true;
        } catch (RuntimeException e) {
            LOG.warnv(e, "The database of DSQL cluster {0} could not be removed", cluster.getIdentifier());
            return false;
        }
    }

    /**
     * Stops every database when Floci stops. Their data stays for the next start, unless storage is
     * in memory, where the clusters themselves do not survive. A cluster still starting is never
     * served once the endpoint has stopped; its start is waited for, and its database then goes as
     * the others do.
     */
    void onStop(@Observes ShutdownEvent ignored) {
        if (mock) {
            return;
        }
        synchronized (routes) {
            stopping = true;
        }
        endpoint.stop();
        for (DsqlCluster cluster : running.values()) {
            stopAtShutdown(cluster);
        }
        running.clear();
        awaitWork();
    }

    private void stopAtShutdown(DsqlCluster cluster) {
        if (memoryStorage) {
            drop(cluster);
        } else {
            stop(cluster, false);
        }
    }

    private void awaitWork() {
        Instant deadline = Instant.now().plus(WORK_AT_SHUTDOWN);
        for (Thread thread : List.copyOf(working)) {
            Duration left = Duration.between(Instant.now(), deadline);
            try {
                if (left.isNegative() || !thread.join(left)) {
                    LOG.warnv("{0} did not finish before Floci stopped; the database of its DSQL cluster may remain",
                            thread.getName());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
