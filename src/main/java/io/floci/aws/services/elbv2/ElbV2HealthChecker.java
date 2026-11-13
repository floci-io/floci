package io.floci.aws.services.elbv2;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.common.concurrent.SocketDeadline;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.elbv2.model.TargetDescription;
import io.floci.aws.services.elbv2.model.TargetGroup;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

@ApplicationScoped
public class ElbV2HealthChecker implements Resettable {

    private static final Logger LOG = Logger.getLogger(ElbV2HealthChecker.class);
    private static final int MAX_CONCURRENT_PROBES = 32;

    private final Vertx vertx;
    private final WorkerExecutor probeWorkers;
    private final EmulatorConfig config;
    private final Ec2Service ec2Service;
    private final SSLSocketFactory backendTls = backendTls();

    // tgArn → (targetKey → TargetState)
    private final Map<String, Map<String, TargetState>> states = new ConcurrentHashMap<>();
    // tgArn → timerId
    private final Map<String, Long> timers = new ConcurrentHashMap<>();
    private final Semaphore probeSlots = new Semaphore(MAX_CONCURRENT_PROBES);

    @Inject
    public ElbV2HealthChecker(Vertx vertx, EmulatorConfig config, Ec2Service ec2Service) {
        this.vertx = vertx;
        this.config = config;
        this.ec2Service = ec2Service;
        this.probeWorkers = vertx.createSharedWorkerExecutor("floci-elbv2-health-probes", MAX_CONCURRENT_PROBES);
    }

    public static int effectivePort(TargetDescription target, TargetGroup tg) {
        if (target.getPort() != null) {
            return target.getPort();
        }
        if (tg.getPort() != null) {
            return tg.getPort();
        }
        return 80;
    }

    /**
     * Whether {@code a} and {@code b} are the same registration in {@code tg}: the same Id on the same
     * effective port, which is how RegisterTargets and DeregisterTargets tell registrations apart.
     */
    public static boolean sameTarget(TargetDescription a, TargetDescription b, TargetGroup tg) {
        return Objects.equals(a.getId(), b.getId()) && effectivePort(a, tg) == effectivePort(b, tg);
    }

    public void startMonitoring(TargetGroup tg) {
        if (config.services().elbv2().mock()) {
            return;
        }
        if ("lambda".equals(tg.getTargetType())) {
            return;
        }
        String tgArn = tg.getTargetGroupArn();
        states.computeIfAbsent(tgArn, k -> new ConcurrentHashMap<>());

        long intervalMs = (tg.getHealthCheckIntervalSeconds() != null ? tg.getHealthCheckIntervalSeconds() : 30) * 1000L;
        long timerId = vertx.setPeriodic(intervalMs, id -> probeAll(tgArn, tg));
        timers.put(tgArn, timerId);
    }

    public void stopMonitoring(String tgArn) {
        Long timerId = timers.remove(tgArn);
        if (timerId != null) {
            vertx.cancelTimer(timerId);
        }
        states.remove(tgArn);
    }

    public void clear() {
        timers.values().forEach(vertx::cancelTimer);
        timers.clear();
        states.clear();
    }

    @PreDestroy
    void destroy() {
        clear();
        probeWorkers.close();
    }

    public void addTargets(String tgArn, List<TargetDescription> targets, TargetGroup tg) {
        if (config.services().elbv2().mock()) {
            return;
        }
        Map<String, TargetState> tgStates = states.computeIfAbsent(tgArn, k -> new ConcurrentHashMap<>());
        for (TargetDescription t : targets) {
            int port = effectivePort(t, tg);
            String key = stateKey(t.getId(), port);
            tgStates.putIfAbsent(key, new TargetState());
        }
        if (Boolean.TRUE.equals(tg.getHealthCheckEnabled())) {
            vertx.setTimer(1, ignored -> probeAll(tgArn, tg));
        }
    }

    public void removeTargets(String tgArn, List<TargetDescription> targets, TargetGroup tg) {
        Map<String, TargetState> tgStates = states.get(tgArn);
        if (tgStates == null) {
            return;
        }
        for (TargetDescription t : targets) {
            int port = effectivePort(t, tg);
            tgStates.remove(stateKey(t.getId(), port));
        }
    }

    public String getState(String tgArn, String targetId, int port) {
        return getHealth(tgArn, targetId, port).state();
    }

    public TargetHealthStatus getHealth(String tgArn, String targetId, int port) {
        Map<String, TargetState> tgStates = states.get(tgArn);
        if (tgStates == null) {
            return TargetHealthStatus.registrationInProgress();
        }
        TargetState s = tgStates.get(stateKey(targetId, port));
        if (s == null) {
            return TargetHealthStatus.registrationInProgress();
        }
        synchronized (s) {
            return new TargetHealthStatus(s.status, s.reason, s.description);
        }
    }

    public boolean isHealthy(String tgArn, TargetDescription target, int port) {
        String state = getState(tgArn, target.getId(), port);
        return "healthy".equals(state) || "initial".equals(state);
    }

    void probeAll(String tgArn, TargetGroup tg) {
        Map<String, TargetState> tgStates = states.get(tgArn);
        if (tgStates == null || !Boolean.TRUE.equals(tg.getHealthCheckEnabled())) {
            return;
        }
        for (Map.Entry<String, TargetState> entry : tgStates.entrySet()) {
            String key = entry.getKey();
            TargetState state = entry.getValue();
            String[] parts = key.split(":", 2);
            if (parts.length != 2) {
                continue;
            }
            String targetId = parts[0];
            int port;
            try {
                port = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                continue;
            }
            String protocol = tg.getHealthCheckProtocol();
            String path = tg.getHealthCheckPath() != null ? tg.getHealthCheckPath() : "/";
            String matcher = tg.getMatcher() != null ? tg.getMatcher() : "200";
            int timeout = tg.getHealthCheckTimeoutSeconds() != null ? tg.getHealthCheckTimeoutSeconds() : 5;
            int healthyThreshold = tg.getHealthyThresholdCount() != null ? tg.getHealthyThresholdCount() : 5;
            int unhealthyThreshold = tg.getUnhealthyThresholdCount() != null ? tg.getUnhealthyThresholdCount() : 2;
            int probePort = healthCheckPort(tg, port);

            synchronized (state) {
                if (state.inFlight || state.workerRunning) {
                    continue;
                }
                state.inFlight = true;
            }
            try {
                probeWorkers.<Integer>executeBlocking(() -> {
                    synchronized (state) {
                        if (states.get(tgArn) != tgStates || tgStates.get(key) != state
                                || !probeSlots.tryAcquire()) {
                            return null;
                        }
                        state.workerRunning = true;
                    }
                    return SocketDeadline.call(timeout, () -> {
                        String host = ElbV2TargetResolver.resolveHost(ec2Service, tg, targetId);
                        return probeOnce(host, probePort, path, timeout, protocol);
                    }, () -> {
                        synchronized (state) {
                            state.workerRunning = false;
                            probeSlots.release();
                        }
                    });
                }, false).onComplete(outcome -> {
                    synchronized (state) {
                        try {
                            if (states.get(tgArn) != tgStates || tgStates.get(key) != state) {
                                return;
                            }
                            if (outcome.failed()) {
                                state.consecutiveSuccesses = 0;
                                state.consecutiveFailures++;
                                if (state.consecutiveFailures >= unhealthyThreshold) {
                                    state.status = "unhealthy";
                                    if (isTimeout(outcome.cause())) {
                                        state.reason = "Target.Timeout";
                                        state.description = "Request timed out";
                                    } else {
                                        state.reason = "Target.FailedHealthChecks";
                                        state.description = "Health checks failed";
                                    }
                                }
                                LOG.debugv("Health check failed for {0}:{1} - {2}",
                                        targetId, port, outcome.cause().getMessage());
                            } else if (outcome.result() != null) {
                                int statusCode = outcome.result();
                                if (matchesStatusCode(statusCode, matcher)) {
                                    state.consecutiveFailures = 0;
                                    state.consecutiveSuccesses++;
                                    boolean initialApplicationTarget = "initial".equals(state.status)
                                            && ("HTTP".equals(tg.getProtocol()) || "HTTPS".equals(tg.getProtocol())
                                            || "lambda".equals(tg.getTargetType()));
                                    if (initialApplicationTarget || state.consecutiveSuccesses >= healthyThreshold) {
                                        state.status = "healthy";
                                        state.reason = null;
                                        state.description = null;
                                    } else if ("initial".equals(state.status)) {
                                        state.reason = "Elb.InitialHealthChecking";
                                        state.description = "Initial health checks in progress";
                                    }
                                } else {
                                    state.consecutiveSuccesses = 0;
                                    state.consecutiveFailures++;
                                    if (state.consecutiveFailures >= unhealthyThreshold) {
                                        state.status = "unhealthy";
                                        state.reason = "Target.ResponseCodeMismatch";
                                        state.description = "Health checks failed with these codes: [" + statusCode + "]";
                                    }
                                }
                            }
                        } finally {
                            state.inFlight = false;
                        }
                    }
                });
            } catch (RuntimeException | Error error) {
                synchronized (state) {
                    state.inFlight = false;
                }
                throw error;
            }
        }
    }

    private int probeOnce(String host, int port, String path, int timeoutSeconds, String protocol) throws IOException {
        String scheme = "HTTPS".equals(protocol) ? "https" : "http";
        URL url = new URL(scheme, ElbV2TargetResolver.resolveCheckedAddress(host), port, path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
        if (conn instanceof HttpsURLConnection https) {
            https.setSSLSocketFactory(backendTls);
            https.setHostnameVerifier((hostname, session) -> true);
        }
        conn.setConnectTimeout(timeoutSeconds * 1000);
        conn.setReadTimeout(timeoutSeconds * 1000);
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(false);
        try {
            conn.connect();
            return conn.getResponseCode();
        } finally {
            conn.disconnect();
        }
    }

    private static SSLSocketFactory backendTls() {
        try {
            // ALB encrypts target connections without validating target certificates.
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, InsecureTrustManagerFactory.INSTANCE.getTrustManagers(), null);
            return context.getSocketFactory();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot initialize ELBv2 backend TLS", e);
        }
    }

    /**
     * AWS probes {@code HealthCheckPort} when it is a number and the target's own port when it is
     * {@code traffic-port} or unset. Health state stays keyed by the traffic port either way.
     * A stored value that is not a valid port, which the API accepted before it validated the
     * field, keeps probing the traffic port as before.
     */
    static int healthCheckPort(TargetGroup tg, int trafficPort) {
        String configured = tg.getHealthCheckPort();
        if (configured == null || configured.isBlank() || "traffic-port".equals(configured)) {
            return trafficPort;
        }
        Integer port = parsePort(configured);
        if (port == null) {
            LOG.debugv("Target group {0} has invalid HealthCheckPort {1}; probing traffic port {2}",
                    tg.getTargetGroupArn(), configured, trafficPort);
            return trafficPort;
        }
        return port;
    }

    /** Returns the port number {@code value} names, or null when it is not a port from 1 to 65535. */
    static Integer parsePort(String value) {
        try {
            int port = Integer.parseInt(value.trim());
            return port >= 1 && port <= 65535 ? port : null;
        } catch (NumberFormatException ignored) {
            // Not a number: the caller treats it the same as an out-of-range port.
            return null;
        }
    }

    static boolean matchesStatusCode(int code, String matcher) {
        String codeStr = String.valueOf(code);
        if (matcher.contains("-")) {
            String[] range = matcher.split("-", 2);
            try {
                int low = Integer.parseInt(range[0].trim());
                int high = Integer.parseInt(range[1].trim());
                return code >= low && code <= high;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return Arrays.stream(matcher.split(","))
                .map(String::trim)
                .anyMatch(codeStr::equals);
    }

    private static boolean isTimeout(Throwable err) {
        Throwable current = err;
        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String stateKey(String targetId, int port) {
        return targetId + ":" + port;
    }

    public record TargetHealthStatus(String state, String reason, String description) {
        private static TargetHealthStatus registrationInProgress() {
            return new TargetHealthStatus(
                    "initial",
                    "Elb.RegistrationInProgress",
                    "Target registration is in progress");
        }
    }

    private static class TargetState {
        boolean inFlight;
        boolean workerRunning;
        volatile String status = "initial";
        volatile String reason = "Elb.RegistrationInProgress";
        volatile String description = "Target registration is in progress";
        volatile int consecutiveSuccesses = 0;
        volatile int consecutiveFailures = 0;
    }
}
