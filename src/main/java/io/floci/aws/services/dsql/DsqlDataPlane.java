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

    private final boolean mock;
    private final boolean memoryStorage;
    private final DsqlDatabases databases;
    private final DsqlEndpoint endpoint;
    private final Map<String, DsqlCluster> running = new ConcurrentHashMap<>();
    private final Set<String> wanted = ConcurrentHashMap.newKeySet();
    // Whether a cluster is still wanted and its route are changed together, so a deletion during a
    // start never leaves the route of a database already gone.
    private final Object routes = new Object();

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
        String arn = cluster.getArn();
        wanted.add(arn);
        Thread.ofVirtual().name("dsql-start-" + cluster.getIdentifier()).start(() -> {
            DsqlDatabases.Backend backend;
            try {
                backend = databases.start(cluster);
            } catch (Exception e) {
                LOG.warnv(e, "The database of DSQL cluster {0} did not start", cluster.getIdentifier());
                onReady.accept(false);
                return;
            }
            boolean deleted;
            boolean ready = false;
            synchronized (routes) {
                deleted = !wanted.contains(arn);
                if (!deleted) {
                    try {
                        endpoint.register(cluster, backend);
                        running.put(arn, cluster);
                        ready = true;
                    } catch (IOException e) {
                        LOG.warnv(e, "The DSQL endpoint cannot serve cluster {0}", cluster.getIdentifier());
                    }
                }
            }
            if (!ready) {
                // Deleted while it started, or unreachable: no database is left running for it.
                stop(cluster, deleted);
            }
            onReady.accept(ready);
        });
    }

    /**
     * Stops the cluster's database and drops its data, then runs {@code onGone}. When Docker cannot
     * remove them, the cluster stays as it is, and the next start of Floci finishes its deletion.
     */
    public void deprovision(DsqlCluster cluster, Runnable onGone) {
        if (mock) {
            onGone.run();
            return;
        }
        synchronized (routes) {
            wanted.remove(cluster.getArn());
            running.remove(cluster.getArn());
            endpoint.unregister(cluster);
        }
        Thread.ofVirtual().name("dsql-stop-" + cluster.getIdentifier()).start(() -> {
            if (stop(cluster, true)) {
                onGone.run();
            }
        });
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
     * in memory, where the clusters themselves do not survive.
     */
    void onStop(@Observes ShutdownEvent ignored) {
        if (mock) {
            return;
        }
        endpoint.stop();
        for (DsqlCluster cluster : running.values()) {
            stop(cluster, memoryStorage);
        }
        running.clear();
    }
}
