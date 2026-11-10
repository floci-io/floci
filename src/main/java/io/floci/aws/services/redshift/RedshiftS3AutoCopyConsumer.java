package io.floci.aws.services.redshift;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.services.redshift.model.Integration;
import io.floci.aws.services.redshiftdata.RedshiftCopyJobExecutor;
import io.floci.aws.services.s3.model.S3ObjectUpdatedEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

@ApplicationScoped
public class RedshiftS3AutoCopyConsumer implements Resettable {
    private static final Logger LOG = Logger.getLogger(RedshiftS3AutoCopyConsumer.class);

    private final RedshiftService service;
    private final RedshiftCopyJobExecutor writer;
    private final Vertx vertx;
    private final long interval;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofVirtual()
            .name("redshift-s3-auto-copy-", 0).factory());
    private final AtomicBoolean pending = new AtomicBoolean();
    private long timer = -1;
    private volatile long generation;
    private volatile boolean quiesced;

    @Inject
    public RedshiftS3AutoCopyConsumer(RedshiftService service, RedshiftCopyJobExecutor writer,
                                     Vertx vertx, EmulatorConfig config) {
        this(service, writer, vertx, config.services().redshift().pollIntervalMs());
    }

    RedshiftS3AutoCopyConsumer(RedshiftService service, RedshiftCopyJobExecutor writer, Vertx vertx, long interval) {
        this.service = service;
        this.writer = writer;
        this.vertx = vertx;
        this.interval = interval;
    }

    void start(@Observes StartupEvent ignored) {
        // Reconcile after restart and retry files whose notifications arrived while a target was paused.
        timer = vertx.setPeriodic(interval, ignoredTimer -> schedule());
    }

    void updated(@Observes S3ObjectUpdatedEvent event) {
        if (service.listS3AutoCopyIntegrations().stream().anyMatch(integration -> "active".equals(integration.getStatus())
                && event.bucketName().equals(AwsArnUtils.parse(integration.getSourceArn()).resource()))) {
            schedule();
        }
    }

    private void schedule() {
        if (quiesced || executor.isShutdown() || !pending.compareAndSet(false, true)) {
            return;
        }
        long submitted = generation;
        try {
            executor.submit(() -> {
                try {
                    synchronized (this) {
                        if (!quiesced && submitted == generation) {
                            pollOnce();
                        }
                    }
                } finally {
                    pending.set(false);
                }
            });
        } catch (RejectedExecutionException expected) {
            pending.set(false);
            LOG.debugv(expected, "S3 auto-copy executor is shutting down");
        }
    }

    synchronized void pollOnce() {
        if (quiesced) {
            return;
        }
        for (Integration integration : service.listS3AutoCopyIntegrations()) {
            if (!"active".equals(integration.getStatus())) {
                continue;
            }
            try {
                scoped(integration, () -> writer.runAutomatic(integration));
            } catch (RuntimeException exception) {
                LOG.warnv(exception, "S3 auto-copy failed for integration {0}; will retry", integration.getIntegrationArn());
            }
        }
    }

    public synchronized void disable(Integration integration) {
        scoped(integration, () -> writer.disable(integration));
    }

    private static void scoped(Integration integration, Runnable action) {
        RequestScopes.callAs(integration.getAccountId(), AwsArnUtils.parse(integration.getIntegrationArn()).region(), () -> {
            action.run();
            return null;
        });
    }

    @Override
    public synchronized void clear() {
        generation++;
    }

    @Override
    public synchronized void beforeReset() {
        quiesced = true;
        generation++;
    }

    @Override
    public synchronized void afterReset() {
        generation++;
        quiesced = false;
    }

    @PreDestroy
    void close() {
        beforeReset();
        if (timer >= 0) {
            vertx.cancelTimer(timer);
        }
        executor.shutdownNow();
        clear();
    }
}
