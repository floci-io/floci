package io.floci.aws.services.emr;

import io.floci.aws.core.common.AppUiRoute;
import io.floci.aws.core.common.AppUiRoutes;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * A cluster's Trino as clients outside the cluster reach it, through Floci, on a host of the cluster's own,
 * {@code <cluster id>.}{@value EmrTrino#HOST_LABEL}{@code .<Floci's host and port>} ({@link EmrTrino#address}), as
 * EMR's clients reach Trino on the primary node: no AWS API gives an address for it. The host takes no credentials, as
 * Floci's Trino has no authentication, so whoever reaches Floci's port reaches the cluster's Trino. Trino names a
 * query's next pages by the host it was asked on, which the request keeps, and by the scheme it was reached over,
 * which goes on as {@code X-Forwarded-Proto}. A cluster whose Trino is not running yet has its primary node started,
 * as an application UI link does, and the request answered with a {@code 503}, which Trino's clients try again on.
 */
@ApplicationScoped
public class EmrTrinoRoutes implements AppUiRoutes {

    // No source gives these, as no AWS API serves Trino; they are Floci's.
    static final String NO_SUCH_CLUSTER = "No EMR cluster %s runs Floci's Trino.";
    static final String CLUSTER_ENDED = "EMR cluster %s is no longer running, and its Trino is there only while it is"
            + " RUNNING or WAITING.";
    static final String NOT_READY = "The Trino of EMR cluster %s is starting. Try again in a moment.";
    // How long a port found for a cluster's Trino is used before it is asked for again, as for the application UIs.
    private static final Duration ENDPOINT_RECHECK = Duration.ofSeconds(10);
    // A cluster's id, as a host name holds it, without case.
    private static final Pattern CLUSTER_ID = Pattern.compile("j-[a-z0-9]+");

    private final EmrService clusters;
    private final Clock clock;
    // Where Floci found each cluster's Trino, and when, by account and cluster.
    private final Map<String, Found> endpoints = new ConcurrentHashMap<>();

    @Inject
    public EmrTrinoRoutes(EmrService clusters) {
        this(clusters, Clock.systemUTC());
    }

    EmrTrinoRoutes(EmrService clusters, Clock clock) {
        this.clusters = clusters;
        this.clock = clock;
    }

    private record Found(ContainerLifecycleManager.EndpointInfo endpoint, Instant at) {
    }

    @Override
    public String hostLabel() {
        return EmrTrino.HOST_LABEL;
    }

    @Override
    public String service() {
        return "emr";
    }

    /**
     * The route of a request whose {@code Host} is {@code hostHeader}, over {@code scheme}: to the Trino of the
     * cluster the host names, once it answers queries; null where the host is not one of a cluster's Trino.
     */
    @Override
    public AppUiRoute route(String hostHeader, String scheme) {
        String host = hostHeader.toLowerCase(Locale.ROOT);
        int dot = host.indexOf('.');
        if (dot <= 0 || !host.startsWith(EmrTrino.HOST_LABEL + ".", dot + 1)
                || !CLUSTER_ID.matcher(host.substring(0, dot)).matches()) {
            return null;
        }
        // A cluster's id is j- and capitals and digits, which the host holds in lower case.
        String clusterId = "j-" + host.substring(2, dot).toUpperCase(Locale.ROOT);
        EmrService.TrinoCluster cluster = clusters.trinoCluster(clusterId).orElse(null);
        if (cluster == null) {
            return AppUiRoute.refused(404, String.format(NO_SUCH_CLUSTER, clusterId));
        }
        String key = cluster.accountId() + "/" + cluster.clusterId();
        if (!EmrClusterLifecycle.RUNNING.equals(cluster.state())
                && !EmrClusterLifecycle.WAITING.equals(cluster.state())) {
            endpoints.remove(key);
            return EmrClusterLifecycle.isTerminated(cluster.state())
                    || EmrClusterLifecycle.TERMINATING.equals(cluster.state())
                    ? AppUiRoute.refused(404, String.format(CLUSTER_ENDED, cluster.clusterId()))
                    : AppUiRoute.refused(503, String.format(NOT_READY, cluster.clusterId()));
        }
        Instant now = clock.instant();
        Found found = endpoints.get(key);
        if (found == null || !now.isBefore(found.at().plus(ENDPOINT_RECHECK))) {
            ContainerLifecycleManager.EndpointInfo endpoint = RequestScopes.callAs(cluster.accountId(),
                    cluster.region(), () -> clusters.trinoInterface(cluster.clusterId(), cluster.port()))
                    .orElse(null);
            if (endpoint == null) {
                endpoints.remove(key);
                RequestScopes.runAs(cluster.accountId(), cluster.region(),
                        () -> clusters.startPrimaryNode(cluster.clusterId()));
                return AppUiRoute.refused(503, String.format(NOT_READY, cluster.clusterId()));
            }
            found = new Found(endpoint, now);
            endpoints.put(key, found);
        }
        Found routed = found;
        return new AppUiRoute(found.endpoint().host(), found.endpoint().port(), Map.of(),
                () -> endpoints.remove(key, routed), 0, null, 503, String.format(NOT_READY, cluster.clusterId()))
                .withHeaders(Map.of("X-Forwarded-Proto", scheme));
    }
}
