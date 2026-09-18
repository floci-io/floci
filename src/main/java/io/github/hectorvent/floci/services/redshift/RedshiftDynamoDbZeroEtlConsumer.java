package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbStreamService;
import io.github.hectorvent.floci.services.dynamodb.model.DynamoDbStreamRecord;
import io.github.hectorvent.floci.services.redshift.model.Integration;
import io.github.hectorvent.floci.services.redshiftdata.RedshiftZeroEtlWriter;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.annotation.PreDestroy;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class RedshiftDynamoDbZeroEtlConsumer {

    private static final Logger LOG = Logger.getLogger(RedshiftDynamoDbZeroEtlConsumer.class);
    private static final int BATCH_SIZE = 100;
    private static final long POLL_INTERVAL_SECONDS = 1;

    private final DynamoDbStreamService streamService;
    private final RedshiftService redshiftService;
    private final RedshiftZeroEtlWriter writer;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "redshift-zero-etl");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, ScheduledFuture<?>> pollers = new ConcurrentHashMap<>();

    @Inject
    public RedshiftDynamoDbZeroEtlConsumer(DynamoDbStreamService streamService,
                                           RedshiftService redshiftService,
                                           RedshiftZeroEtlWriter writer) {
        this.streamService = streamService;
        this.redshiftService = redshiftService;
        this.writer = writer;
    }

    void onStart(@Observes StartupEvent event) {
        startPersistedIntegrations();
    }

    public void startPersistedIntegrations() {
        for (Integration integration : redshiftService.listDynamoDbZeroEtlIntegrations()) {
            if (integration.isPollingEnabled()) {
                startPolling(integration);
            }
        }
    }

    public void startPolling(Integration integration) {
        stopPolling(integration.getIntegrationArn());
        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
                () -> pollSafely(integration), 0, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
        pollers.put(integration.getIntegrationArn(), future);
    }

    public void stopPolling(String integrationArn) {
        ScheduledFuture<?> future = pollers.remove(integrationArn);
        if (future != null) {
            future.cancel(false);
        }
    }

    void pollOnce(Integration integration) {
        writer.createLandingTable(integration.getTargetClusterIdentifier(), integration.getLandingTableName());
        String iteratorType = integration.getCheckpointSequenceNumber() == null
                ? "TRIM_HORIZON" : "AFTER_SEQUENCE_NUMBER";
        String iterator = streamService.getShardIterator(integration.getSourceStreamArn(),
                DynamoDbStreamService.SHARD_ID, iteratorType, integration.getCheckpointSequenceNumber());
        DynamoDbStreamService.GetRecordsResult result = streamService.getRecords(iterator, BATCH_SIZE);
        if (result.records().isEmpty()) {
            return;
        }
        String sequence = writer.writeBatch(integration.getTargetClusterIdentifier(),
                integration.getLandingTableName(), result.records());
        redshiftService.updateIntegrationRuntime(integration.getIntegrationArn(), sequence, true, null);
        integration.setCheckpointSequenceNumber(sequence);
    }

    public void reset() {
        for (String integrationArn : pollers.keySet()) {
            stopPolling(integrationArn);
        }
        scheduler.shutdownNow();
    }

    @PreDestroy
    void onStop() {
        reset();
    }

    private void pollSafely(Integration integration) {
        try {
            pollOnce(integration);
        } catch (Exception e) {
            LOG.warnv(e, "Zero-ETL polling failed for integration {0}", integration.getIntegrationArn());
            if (e instanceof AwsException awsException
                    && "TrimmedDataAccessException".equals(awsException.getErrorCode())) {
                integration.setCheckpointSequenceNumber(null);
            }
            try {
                redshiftService.updateIntegrationRuntime(integration.getIntegrationArn(),
                        integration.getCheckpointSequenceNumber(), false, e.getMessage());
            } catch (Exception updateError) {
                LOG.warnv(updateError, "Could not persist zero-ETL failure for integration {0}",
                        integration.getIntegrationArn());
            }
        }
    }
}
