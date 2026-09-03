package io.github.hectorvent.floci.services.elb;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.elb.model.ClassicHealthCheck;
import io.github.hectorvent.floci.services.elb.model.ClassicLoadBalancer;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Health checking for Classic (2012-06-01) load balancers.
 *
 * <p>Classic health is per instance on one load balancer, not per target on a target group, and
 * the check itself is described by a single {@code Target} string such as {@code HTTP:8080/} or
 * {@code TCP:80} rather than by separate protocol, port and path members — so this cannot reuse
 * {@code ElbV2HealthChecker}. The probing shape is deliberately the same: a Vert.x periodic timer
 * per load balancer, consecutive success/failure counters, and the configured thresholds.
 *
 * <p>With {@code floci.services.elb.mock=true} no probe is ever made and a registered instance is
 * reported {@code InService} straight away.
 */
@ApplicationScoped
public class ElbClassicHealthChecker implements Resettable {

    private static final Logger LOG = Logger.getLogger(ElbClassicHealthChecker.class);

    /** The health of one registered instance, in the {@code InstanceState} shape of the v1 model. */
    public record InstanceHealth(String state, String reasonCode, String description) {

        static InstanceHealth inService() {
            return new InstanceHealth("InService", "N/A", "N/A");
        }

        static InstanceHealth registering() {
            return new InstanceHealth("OutOfService", "ELB",
                    "Instance registration is still in progress.");
        }

        static InstanceHealth failedChecks() {
            return new InstanceHealth("OutOfService", "Instance",
                    "Instance has failed at least the UnhealthyThreshold number of health checks "
                            + "consecutively.");
        }
    }

    private final Vertx vertx;
    private final EmulatorConfig config;
    private final Ec2Service ec2Service;

    // "region|lbName" → instanceId → state
    private final Map<String, Map<String, InstanceState>> states = new ConcurrentHashMap<>();
    // "region|lbName" → Vert.x timer id
    private final Map<String, Long> timers = new ConcurrentHashMap<>();

    @Inject
    public ElbClassicHealthChecker(Vertx vertx, EmulatorConfig config, Ec2Service ec2Service) {
        this.vertx = vertx;
        this.config = config;
        this.ec2Service = ec2Service;
    }

    public static String key(String region, String loadBalancerName) {
        return region + "|" + loadBalancerName;
    }

    public void startMonitoring(ClassicLoadBalancer lb) {
        String k = key(lb.getRegion(), lb.getLoadBalancerName());
        states.computeIfAbsent(k, x -> new ConcurrentHashMap<>());
        if (mock() || vertx == null) {
            return;
        }
        stopTimer(k);
        int interval = lb.getHealthCheck() != null && lb.getHealthCheck().getInterval() != null
                ? lb.getHealthCheck().getInterval() : 30;
        timers.put(k, vertx.setPeriodic(interval * 1000L, id -> probeAll(lb)));
    }

    public void stopMonitoring(String region, String loadBalancerName) {
        String k = key(region, loadBalancerName);
        stopTimer(k);
        states.remove(k);
    }

    /** Re-arms the periodic timer after {@code ConfigureHealthCheck} changed the interval. */
    public void healthCheckChanged(ClassicLoadBalancer lb) {
        if (states.containsKey(key(lb.getRegion(), lb.getLoadBalancerName()))) {
            startMonitoring(lb);
        }
    }

    public void registerInstances(ClassicLoadBalancer lb, Collection<String> instanceIds) {
        Map<String, InstanceState> lbStates =
                states.computeIfAbsent(key(lb.getRegion(), lb.getLoadBalancerName()),
                        k -> new ConcurrentHashMap<>());
        for (String id : instanceIds) {
            lbStates.putIfAbsent(id, new InstanceState());
        }
        if (!mock() && vertx != null) {
            vertx.setTimer(1, ignored -> probeAll(lb));
        }
    }

    public void deregisterInstances(String region, String loadBalancerName, Collection<String> instanceIds) {
        Map<String, InstanceState> lbStates = states.get(key(region, loadBalancerName));
        if (lbStates != null) {
            instanceIds.forEach(lbStates::remove);
        }
    }

    /**
     * The health of one instance registered with a load balancer.
     *
     * <p>An instance with no probe result yet is {@code OutOfService}/{@code ELB}, which is what a
     * live Classic load balancer reports between {@code RegisterInstancesWithLoadBalancer} and the
     * first successful check.
     */
    public InstanceHealth getHealth(String region, String loadBalancerName, String instanceId) {
        if (mock()) {
            return InstanceHealth.inService();
        }
        Map<String, InstanceState> lbStates = states.get(key(region, loadBalancerName));
        InstanceState s = lbStates != null ? lbStates.get(instanceId) : null;
        if (s == null || s.health == null) {
            return InstanceHealth.registering();
        }
        return s.health;
    }

    @Override
    public void clear() {
        if (vertx != null) {
            timers.values().forEach(vertx::cancelTimer);
        }
        timers.clear();
        states.clear();
    }

    private boolean mock() {
        return config != null && config.services().elb().mock();
    }

    private void stopTimer(String k) {
        Long timerId = timers.remove(k);
        if (timerId != null && vertx != null) {
            vertx.cancelTimer(timerId);
        }
    }

    private void probeAll(ClassicLoadBalancer lb) {
        Map<String, InstanceState> lbStates = states.get(key(lb.getRegion(), lb.getLoadBalancerName()));
        if (lbStates == null || lbStates.isEmpty()) {
            return;
        }
        ClassicHealthCheck hc = lb.getHealthCheck() != null ? lb.getHealthCheck() : ClassicHealthCheck.defaults();
        Target target = Target.parse(hc.getTarget());
        if (target == null) {
            return;
        }
        int timeout = hc.getTimeout() != null ? hc.getTimeout() : 5;
        int healthyThreshold = hc.getHealthyThreshold() != null ? hc.getHealthyThreshold() : 10;
        int unhealthyThreshold = hc.getUnhealthyThreshold() != null ? hc.getUnhealthyThreshold() : 2;

        for (Map.Entry<String, InstanceState> entry : lbStates.entrySet()) {
            String instanceId = entry.getKey();
            InstanceState state = entry.getValue();
            String host = resolveHost(instanceId);
            vertx.executeBlocking(() -> probe(host, target, timeout))
                    .onSuccess(ok -> {
                        if (Boolean.TRUE.equals(ok)) {
                            state.consecutiveFailures = 0;
                            state.consecutiveSuccesses++;
                            if (state.consecutiveSuccesses >= healthyThreshold) {
                                state.health = InstanceHealth.inService();
                            }
                        } else {
                            recordFailure(state, unhealthyThreshold);
                        }
                    })
                    .onFailure(err -> {
                        recordFailure(state, unhealthyThreshold);
                        LOG.debugv("Classic ELB health check failed for {0} ({1}): {2}",
                                instanceId, host, err.getMessage());
                    });
        }
    }

    private static void recordFailure(InstanceState state, int unhealthyThreshold) {
        state.consecutiveSuccesses = 0;
        state.consecutiveFailures++;
        if (state.consecutiveFailures >= unhealthyThreshold) {
            state.health = InstanceHealth.failedChecks();
        }
    }

    private String resolveHost(String instanceId) {
        if (ec2Service == null) {
            return instanceId;
        }
        Instance instance = ec2Service.findInstanceById(instanceId);
        if (instance == null) {
            return instanceId;
        }
        String bridgeIp = instance.getContainerBridgeIp();
        return bridgeIp != null && !bridgeIp.isBlank() ? bridgeIp : instanceId;
    }

    private static boolean probe(String host, Target target, int timeoutSeconds) throws IOException {
        int timeoutMs = timeoutSeconds * 1000;
        if (target.path() == null) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, target.port()), timeoutMs);
                return true;
            }
        }
        URL url = new URL(target.scheme(), host, target.port(), target.path());
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setInstanceFollowRedirects(false);
        try {
            int code = conn.getResponseCode();
            // Classic ELB treats any 2xx or 3xx as a healthy HTTP check.
            return code >= 200 && code < 400;
        } finally {
            conn.disconnect();
        }
    }

    /**
     * A parsed {@code HealthCheck.Target} — {@code HTTP:8080/health}, {@code TCP:80}, and so on.
     * {@code path} is null for the connection-only protocols, which is what selects a TCP probe.
     */
    record Target(String scheme, int port, String path) {

        static Target parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            int colon = raw.indexOf(':');
            if (colon < 0) {
                return null;
            }
            String protocol = raw.substring(0, colon).toUpperCase();
            String rest = raw.substring(colon + 1);
            int slash = rest.indexOf('/');
            String portPart = slash < 0 ? rest : rest.substring(0, slash);
            String path = slash < 0 ? null : rest.substring(slash);
            int port;
            try {
                port = Integer.parseInt(portPart.trim());
            } catch (NumberFormatException e) {
                return null;
            }
            return switch (protocol) {
                case "HTTP" -> new Target("http", port, path != null ? path : "/");
                // Floci does not terminate TLS for Classic health checks; an HTTPS target is
                // probed over plain HTTP against the same port rather than reported unknown.
                case "HTTPS" -> new Target("http", port, path != null ? path : "/");
                case "TCP", "SSL" -> new Target(null, port, null);
                default -> null;
            };
        }
    }

    private static final class InstanceState {
        volatile InstanceHealth health;
        volatile int consecutiveSuccesses;
        volatile int consecutiveFailures;
    }
}
