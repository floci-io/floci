package io.floci.aws.services.redshift;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.SweepScheduler;
import io.floci.aws.core.common.SweepWorker;
import io.floci.aws.services.redshift.model.Integration;
import io.floci.aws.services.redshiftdata.RedshiftCopyJobExecutor;
import io.floci.aws.services.s3.model.S3ObjectUpdatedEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

@ApplicationScoped
public class RedshiftS3AutoCopyConsumer extends SweepScheduler {
    private static final Logger LOG = Logger.getLogger(RedshiftS3AutoCopyConsumer.class);

    private static final class WorkQueue {
        private final Set<String> pendingBuckets = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean reconcileAll = new AtomicBoolean(true);
    }

    private final RedshiftService service;
    private final RedshiftCopyJobExecutor writer;
    private final WorkQueue work;

    @Inject
    public RedshiftS3AutoCopyConsumer(RedshiftService service, RedshiftCopyJobExecutor writer, EmulatorConfig config) {
        this(service, writer, config.services().redshift().enabled(),
                Math.max(1L, TimeUnit.MILLISECONDS.toSeconds(config.services().redshift().pollIntervalMs())));
    }

    RedshiftS3AutoCopyConsumer(RedshiftService service, RedshiftCopyJobExecutor writer,
                               boolean enabled, long intervalSeconds) {
        this(service, writer, new WorkQueue(), enabled, intervalSeconds);
    }

    private RedshiftS3AutoCopyConsumer(RedshiftService service, RedshiftCopyJobExecutor writer,
                                       WorkQueue work, boolean enabled, long intervalSeconds) {
        super(new SweepWorker("redshift-s3-auto-copy", enabled, intervalSeconds, true,
                ignored -> sweep(work, service, writer)));
        this.service = service;
        this.writer = writer;
        this.work = work;
    }

    RedshiftS3AutoCopyConsumer() {
        this.service = null;
        this.writer = null;
        this.work = null;
    }

    void updated(@Observes S3ObjectUpdatedEvent event) {
        work.pendingBuckets.add(event.bucketName());
    }

    void markReconcile() {
        work.reconcileAll.set(true);
    }

    void pollOnce() {
        tick(Instant.now());
    }

    private static void sweep(WorkQueue work, RedshiftService service, RedshiftCopyJobExecutor writer) {
        boolean fullReconcile = work.reconcileAll.getAndSet(false);
        Set<String> buckets = new HashSet<>(work.pendingBuckets);
        work.pendingBuckets.removeAll(buckets);
        if (!fullReconcile && buckets.isEmpty()) {
            return;
        }
        for (Integration integration : service.listS3AutoCopyIntegrations()) {
            if (!"active".equals(integration.getStatus())) {
                continue;
            }
            String sourceBucket = AwsArnUtils.parse(integration.getSourceArn()).resource();
            if (!fullReconcile && !buckets.contains(sourceBucket)) {
                continue;
            }
            try {
                if (scoped(integration, () -> writer.runAutomatic(integration))) {
                    work.pendingBuckets.add(sourceBucket);
                }
            } catch (RuntimeException exception) {
                work.pendingBuckets.add(sourceBucket);
                LOG.warnv(exception, "S3 auto-copy failed for integration {0}; will retry",
                        integration.getIntegrationArn());
            }
        }
    }

    public void disable(Integration integration) {
        scoped(integration, () -> {
            writer.disable(integration);
            return null;
        });
    }

    private static <T> T scoped(Integration integration, Supplier<T> action) {
        return RequestScopes.callAs(integration.getAccountId(),
                AwsArnUtils.parse(integration.getIntegrationArn()).region(), action);
    }

    @Override
    public void clear() {
        work.pendingBuckets.clear();
        work.reconcileAll.set(true);
    }
}
