package io.github.hectorvent.floci.services.rum;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.services.rum.model.AppMonitor;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory CloudWatch RUM app-monitor store. Enough for the CDK RUM AppMonitor custom resource
 * (createAppMonitor / updateAppMonitor / deleteAppMonitor) to succeed. Create is idempotent-adopt so a
 * re-applied or rolled-back-then-retried stack does not wedge on a name conflict (the emulator has no
 * async provider framework to reconcile).
 */
@ApplicationScoped
public class RumService implements Resettable {

    private final ConcurrentHashMap<String, AppMonitor> monitors = new ConcurrentHashMap<>();

    public AppMonitor createAppMonitor(String name, String domain) {
        if (name == null || name.isBlank()) {
            throw new AwsException("ValidationException", "Name is required.", 400);
        }
        return monitors.computeIfAbsent(name, n ->
                new AppMonitor(UUID.randomUUID().toString(), n, domain, "CREATED", Instant.now().getEpochSecond()));
    }

    public AppMonitor getAppMonitor(String name) {
        AppMonitor monitor = monitors.get(name);
        if (monitor == null) {
            throw new AwsException("ResourceNotFoundException", "App monitor " + name + " does not exist.", 404);
        }
        return monitor;
    }

    public void updateAppMonitor(String name, String domain) {
        monitors.compute(name, (key, current) -> {
            if (current == null) {
                throw new AwsException(
                        "ResourceNotFoundException", "App monitor " + name + " does not exist.", 404);
            }
            if (domain == null || domain.isBlank()) {
                return current;
            }
            return new AppMonitor(
                    current.getId(),
                    current.getName(),
                    domain,
                    current.getState(),
                    current.getCreated());
        });
    }

    public void deleteAppMonitor(String name) {
        if (monitors.remove(name) == null) {
            throw new AwsException("ResourceNotFoundException", "App monitor " + name + " does not exist.", 404);
        }
    }

    public List<AppMonitor> listAppMonitors() {
        return List.copyOf(monitors.values());
    }

    @Override
    public void clear() {
        monitors.clear();
    }
}
