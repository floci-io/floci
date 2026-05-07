package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.elbv2.model.TargetDescription;
import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ElbV2HealthChecker {

    private static final Logger LOG = Logger.getLogger(ElbV2HealthChecker.class);

    private final Vertx vertx;
    private final EmulatorConfig config;

    // tgArn → (targetKey → TargetState)
    private final Map<String, Map<String, TargetState>> states = new ConcurrentHashMap<>();
    // tgArn → timerId
    private final Map<String, Long> timers = new ConcurrentHashMap<>();

    @Inject
    public ElbV2HealthChecker(Vertx vertx, EmulatorConfig config) {
        this.vertx = vertx;
        this.config = config;
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
        Map<String, TargetState> tgStates = states.get(tgArn);
        if (tgStates == null) {
            return "initial";
        }
        TargetState s = tgStates.get(stateKey(targetId, port));
        if (s == null) {
            return "initial";
        }
        return s.status;
    }

    public boolean isHealthy(String tgArn, TargetDescription target, int port) {
        String state = getState(tgArn, target.getId(), port);
        return "healthy".equals(state) || "initial".equals(state);
    }

    private void probeAll(String tgArn, TargetGroup tg) {
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
            String host = parts[0];
            int port;
            try {
                port = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                continue;
            }
            String path = tg.getHealthCheckPath() != null ? tg.getHealthCheckPath() : "/";
            String matcher = tg.getMatcher() != null ? tg.getMatcher() : "200";
            int timeout = tg.getHealthCheckTimeoutSeconds() != null ? tg.getHealthCheckTimeoutSeconds() : 5;
            int healthyThreshold = tg.getHealthyThresholdCount() != null ? tg.getHealthyThresholdCount() : 5;
            int unhealthyThreshold = tg.getUnhealthyThresholdCount() != null ? tg.getUnhealthyThresholdCount() : 2;

            vertx.executeBlocking(() -> {
                return probe(host, port, path, timeout);
            }).onSuccess(statusCode -> {
                boolean success = matchesStatusCode(statusCode, matcher);
                if (success) {
                    state.consecutiveFailures = 0;
                    state.consecutiveSuccesses++;
                    if (state.consecutiveSuccesses >= healthyThreshold) {
                        state.status = "healthy";
                    }
                } else {
                    state.consecutiveSuccesses = 0;
                    state.consecutiveFailures++;
                    if (state.consecutiveFailures >= unhealthyThreshold) {
                        state.status = "unhealthy";
                    }
                }
            }).onFailure(err -> {
                state.consecutiveSuccesses = 0;
                state.consecutiveFailures++;
                if (state.consecutiveFailures >= unhealthyThreshold) {
                    state.status = "unhealthy";
                }
                LOG.debugv("Health check failed for {0}:{1} - {2}", host, port, err.getMessage());
            });
        }
    }

    private int probe(String host, int port, String path, int timeoutSeconds) throws IOException {
        URL url = new URL("http", host, port, path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
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

    private static String stateKey(String targetId, int port) {
        return targetId + ":" + port;
    }

    private static class TargetState {
        volatile String status = "initial";
        volatile int consecutiveSuccesses = 0;
        volatile int consecutiveFailures = 0;
    }
}