package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbStreamService;
import io.github.hectorvent.floci.services.dynamodb.model.DynamoDbStreamRecord;
import io.github.hectorvent.floci.services.redshift.model.Integration;
import io.github.hectorvent.floci.services.redshiftdata.RedshiftZeroEtlWriter;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@ApplicationScoped
public class RedshiftDynamoDbZeroEtlConsumer {

    private static final Logger LOG = Logger.getLogger(RedshiftDynamoDbZeroEtlConsumer.class);
    private static final int BATCH_SIZE = 100;

    private final Vertx vertx;
    private final DynamoDbStreamService streamService;
    private final RedshiftService redshiftService;
    private final RedshiftZeroEtlWriter writer;
    private final long pollIntervalMs;
    private final ConcurrentHashMap<String, Long> timerIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> activePolls = new ConcurrentHashMap<>();
    private final ExecutorService pollExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "redshift-zero-etl");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public RedshiftDynamoDbZeroEtlConsumer(Vertx vertx,
                                           DynamoDbStreamService streamService,
                                           RedshiftService redshiftService,
                                           RedshiftZeroEtlWriter writer,
                                           EmulatorConfig config) {
        this(vertx, streamService, redshiftService, writer, config.services().redshift().pollIntervalMs());
    }

    RedshiftDynamoDbZeroEtlConsumer(DynamoDbStreamService streamService,
                                    RedshiftService redshiftService,
                                    RedshiftZeroEtlWriter writer) {
        this(null, streamService, redshiftService, writer, 1000);
    }

    private RedshiftDynamoDbZeroEtlConsumer(Vertx vertx,
                                            DynamoDbStreamService streamService,
                                            RedshiftService redshiftService,
                                            RedshiftZeroEtlWriter writer,
                                            long pollIntervalMs) {
        this.vertx = vertx;
        this.streamService = streamService;
        this.redshiftService = redshiftService;
        this.writer = writer;
        this.pollIntervalMs = pollIntervalMs;
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
        if (vertx == null || timerIds.containsKey(integration.getIntegrationArn())) {
            return;
        }
        long timerId = vertx.setPeriodic(pollIntervalMs, ignored -> pollAnd(integration));
        timerIds.put(integration.getIntegrationArn(), timerId);
    }

    public void stopPolling(String integrationArn) {
        Long timerId = timerIds.remove(integrationArn);
        if (timerId != null) {
            vertx.cancelTimer(timerId);
        }
        activePolls.remove(integrationArn);
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
        redshiftService.updateIntegrationRuntime(integration.getAccountId(), integration.getIntegrationArn(),
                sequence, true, null);
        integration.setCheckpointSequenceNumber(sequence);
    }

    public void reset() {
        for (String integrationArn : timerIds.keySet()) {
            stopPolling(integrationArn);
        }
        activePolls.clear();
    }

    @PreDestroy
    void onStop() {
        reset();
        pollExecutor.shutdownNow();
    }

    private void pollAnd(Integration integration) {
        String integrationArn = integration.getIntegrationArn();
        if (activePolls.putIfAbsent(integrationArn, Boolean.TRUE) != null) {
            return;
        }
        pollExecutor.submit(() -> {
            try {
                pollSafely(integration);
            } finally {
                activePolls.remove(integrationArn);
            }
        });
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
                redshiftService.updateIntegrationRuntime(integration.getAccountId(), integration.getIntegrationArn(),
                        integration.getCheckpointSequenceNumber(), false, e.getMessage());
            } catch (Exception updateError) {
                LOG.warnv(updateError, "Could not persist zero-ETL failure for integration {0}",
                        integration.getIntegrationArn());
            }
        }
    }
}
