package io.github.hectorvent.floci.services.secretsmanager;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Fires rotations when a secret's rotation schedule comes due.
 *
 * <p>Without this, {@code RotationRules} were stored and echoed back but never acted on: only an
 * explicit RotateSecret call ever rotated anything, so {@code AutomaticallyAfterDays} and
 * {@code ScheduleExpression} described a schedule that could not happen.
 *
 * <p>A single background thread ticks on a fixed interval and rotates every secret whose
 * {@code NextRotationDate} has passed. {@link SecretsManagerService#rotateSecret} advances that
 * date as it dispatches, so a secret is claimed on the tick that picks it up and the rotation's
 * own success or failure does not decide whether the sweep retries it immediately.
 */
@ApplicationScoped
public class SecretRotationScheduler {

    private static final Logger LOG = Logger.getLogger(SecretRotationScheduler.class);

    private final SecretsManagerService service;
    private final long tickIntervalSeconds;
    private final boolean enabled;
    private final ScheduledExecutorService executor;

    @Inject
    public SecretRotationScheduler(SecretsManagerService service, EmulatorConfig config) {
        this.service = service;
        this.tickIntervalSeconds = config.services().secretsmanager().rotationTickSeconds();
        this.enabled = config.services().secretsmanager().enabled()
                && config.services().secretsmanager().scheduledRotationEnabled();
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "secretsmanager-rotation-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /** Test seam: a scheduler that never starts its thread, so {@link #tick} drives it directly. */
    SecretRotationScheduler(SecretsManagerService service) {
        this.service = service;
        this.tickIntervalSeconds = 60;
        this.enabled = false;
        this.executor = null;
    }

    void onStart(@Observes StartupEvent ignored) {
        if (!enabled) {
            LOG.debug("Secrets Manager scheduled rotation disabled by configuration");
            return;
        }
        executor.scheduleAtFixedRate(this::tickSafely, tickIntervalSeconds, tickIntervalSeconds,
                TimeUnit.SECONDS);
        LOG.infov("Secrets Manager rotation scheduler started (tick every {0}s)", tickIntervalSeconds);
    }

    void onStop(@Observes ShutdownEvent ignored) {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    void tickSafely() {
        try {
            tick(Instant.now());
        } catch (Throwable t) {
            LOG.warnv("Secrets Manager rotation sweep failed: {0}", t.getMessage());
        }
    }

    void tick(Instant now) {
        for (SecretsManagerService.RegionalSecret entry : service.listAllSecrets()) {
            if (!isDue(entry.secret(), now)) {
                continue;
            }
            try {
                // Addressed by ARN, not name: the ARN names the owning account, and this thread
                // has no request to resolve one from. A null Lambda ARN reuses the one already
                // configured on the secret.
                service.rotateSecret(entry.secret().getArn(), UUID.randomUUID().toString(),
                        null, null, true, entry.region());
                LOG.infov("Triggered scheduled rotation for secret {0} in {1}",
                        entry.secret().getName(), entry.region());
            } catch (Exception e) {
                // One secret mid-rotation, or missing its Lambda, must not stop the sweep.
                LOG.debugv("Skipping scheduled rotation for {0}: {1}",
                        entry.secret().getName(), e.getMessage());
            }
        }
    }

    private boolean isDue(Secret secret, Instant now) {
        if (!secret.isRotationEnabled() || secret.getNextRotationDate() == null) {
            return false;
        }
        // A replica is read-only, a deleted secret is not rotatable, and a secret another AWS
        // service owns is rotated by that service rather than through a rotation Lambda.
        if (secret.isReplica() || secret.getDeletedDate() != null || secret.getOwningService() != null) {
            return false;
        }
        return !secret.getNextRotationDate().isAfter(now);
    }
}
