package io.github.hectorvent.floci.services.firehose;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.S3Destination;
import io.github.hectorvent.floci.services.firehose.model.Record;
import io.github.hectorvent.floci.services.firehose.model.RedshiftDestination;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FirehoseRedshiftServiceTest {

    private static final String STREAM = "rs-delivery";
    private static final String BACKUP_ENABLED = ", \"S3BackupMode\": \"Enabled\", \"S3BackupConfiguration\": "
            + "{\"RoleARN\": \"arn:aws:iam::000000000000:role/firehose\", \"BucketARN\": \"arn:aws:s3:::bk\"}";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private FirehoseService service;
    private S3Service s3Service;
    private FirehoseRedshiftDelivery delivery;
    private MutableClock clock;
    private StorageFactory storageFactory;
    private RegionResolver regionResolver;
    private EmulatorConfig config;

    @BeforeEach
    void setUp() {
        storageFactory = Mockito.mock(StorageFactory.class);
        Map<String, AccountAwareStorageBackend<?>> backends = new HashMap<>();
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(invocation -> backends.computeIfAbsent(
                        invocation.getArgument(0) + "/" + invocation.getArgument(1),
                        k -> AccountAwareStorageBackend.inMemory("000000000000")));
        s3Service = Mockito.mock(S3Service.class);
        delivery = Mockito.mock(FirehoseRedshiftDelivery.class);
        clock = new MutableClock();

        EmulatorConfig.FirehoseServiceConfig firehoseCfg = mock(EmulatorConfig.FirehoseServiceConfig.class);
        when(firehoseCfg.enabled()).thenReturn(true);
        when(firehoseCfg.tickIntervalSeconds()).thenReturn(10L);
        when(firehoseCfg.flushRecordCount()).thenReturn(0);
        EmulatorConfig.ServicesConfig servicesCfg = mock(EmulatorConfig.ServicesConfig.class);
        when(servicesCfg.firehose()).thenReturn(firehoseCfg);
        config = mock(EmulatorConfig.class);
        when(config.services()).thenReturn(servicesCfg);
        regionResolver = new RegionResolver("us-east-1", "000000000000");

        service = new FirehoseService(storageFactory, s3Service, new KinesisService(storageFactory, regionResolver),
                regionResolver, clock, config, mock(FirehoseParquetConverter.class),
                mock(FirehoseLambdaTransformer.class), delivery, Runnable::run);
    }

    private RedshiftDestination destination(String extraMembers) throws Exception {
        return mapper.readValue("""
                {"RoleARN": "arn:aws:iam::000000000000:role/firehose",
                 "ClusterJDBCURL": "jdbc:redshift://c1.abc.us-east-1.redshift.amazonaws.com:5439/dev",
                 "Username": "admin", "Password": "Secret123",
                 "CopyCommand": {"DataTableName": "events"},
                 "S3Configuration": {"RoleARN": "arn:aws:iam::000000000000:role/firehose", "BucketARN": "arn:aws:s3:::stage"}
                 %s}
                """.formatted(extraMembers), RedshiftDestination.class);
    }

    private void createStream(String extraMembers) throws Exception {
        service.createDeliveryStream("us-east-1", STREAM, null, destination(extraMembers), List.of(), null, null);
        service.putRecord(STREAM, new Record("{\"id\":1}".getBytes(StandardCharsets.UTF_8)));
    }

    private S3Destination describedBackup(String streamName) {
        return service.describeDeliveryStream(streamName).getDestinations().get(0)
                .getRedshiftDestinationDescription().getS3Backup();
    }

    private void copyFails() {
        doThrow(new IllegalStateException("relation does not exist"))
                .when(delivery).copy(anyString(), anyString(), any(), anyString(), anyString());
    }

    private void verifyStagedTimes(int times) {
        verify(s3Service, times(times)).putObject(eq("stage"), anyString(), any(byte[].class), anyString(),
                anyMap(), any());
    }

    /** AWS retries a failed Redshift load every 5 minutes, so 301 seconds is always past it. */
    private void pastBackoff() {
        clock.advance(Duration.ofSeconds(301));
    }

    @Test
    void successfulCopyConsumesTheBatchOnce() throws Exception {
        createStream("");

        service.flush(STREAM);
        service.flush(STREAM);
        pastBackoff();
        service.retryPendingRedshiftCopies();

        verify(delivery, times(1)).copy(eq("000000000000"), eq("us-east-1"), any(), eq("stage"), anyString());
        verifyStagedTimes(1);
    }

    @Test
    void failedCopyIsLoggedAndNotRestagedOrRebuffered() throws Exception {
        createStream("");
        copyFails();

        service.flush(STREAM);
        service.flush(STREAM);

        verify(delivery, times(1)).copy(anyString(), anyString(), any(), eq("stage"), anyString());
        verifyStagedTimes(1);
    }

    @Test
    void deliveryUnavailableLeavesStagedObjectInS3() throws Exception {
        FirehoseService serviceWithoutDelivery = new FirehoseService(storageFactory, s3Service,
                new KinesisService(storageFactory, regionResolver), regionResolver, clock, config,
                mock(FirehoseParquetConverter.class), mock(FirehoseLambdaTransformer.class), null, Runnable::run);
        RedshiftDestination destination = mapper.readValue("""
                {"RoleARN": "arn:aws:iam::000000000000:role/firehose",
                 "ClusterJDBCURL": "jdbc:redshift://c1.abc.us-east-1.redshift.amazonaws.com:5439/dev",
                 "Username": "admin", "Password": "Secret123",
                 "CopyCommand": {"DataTableName": "events"},
                 "S3Configuration": {"RoleARN": "arn:aws:iam::000000000000:role/firehose", "BucketARN": "arn:aws:s3:::stage"}}
                """, RedshiftDestination.class);
        serviceWithoutDelivery.createDeliveryStream("us-east-1", "fh-no-delivery", null, destination, List.of(), null, null);
        serviceWithoutDelivery.putRecord("fh-no-delivery", new Record("{\"id\":1}".getBytes(StandardCharsets.UTF_8)));

        serviceWithoutDelivery.flush("fh-no-delivery");

        verifyStagedTimes(1);
        verify(delivery, never()).copy(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void backupEnabledWritesTheBatchToTheBackupBucket() throws Exception {
        createStream(BACKUP_ENABLED);

        service.flush(STREAM);

        verifyStagedTimes(1);
        verify(s3Service, times(1)).putObject(eq("bk"), anyString(), any(byte[].class), anyString(), anyMap(), any());
        verify(delivery, times(1)).copy(anyString(), anyString(), any(), eq("stage"), anyString());
    }

    @Test
    void aRedshiftBackupGetsTheLoggingDefaultsOnCreate() throws Exception {
        createStream(BACKUP_ENABLED);
        service.createDeliveryStream("us-east-1", "rs-logging", null, destination(
                ", \"S3BackupMode\": \"Enabled\", \"S3BackupConfiguration\": "
                        + "{\"RoleARN\": \"arn:aws:iam::000000000000:role/firehose\", \"BucketARN\": \"arn:aws:s3:::bk\","
                        + " \"CloudWatchLoggingOptions\": {\"LogGroupName\": \"g\"}}"),
                List.of(), null, null);

        S3Destination omitted = describedBackup(STREAM);
        assertEquals(false, omitted.getCloudWatchLoggingOptions().getEnabled());
        assertEquals("UNCOMPRESSED", omitted.getCompressionFormat());

        S3Destination withoutEnabled = describedBackup("rs-logging");
        assertEquals(false, withoutEnabled.getCloudWatchLoggingOptions().getEnabled());
        assertEquals("g", withoutEnabled.getCloudWatchLoggingOptions().getLogGroupName());
        assertEquals("UNCOMPRESSED", withoutEnabled.getCompressionFormat());
    }

    @Test
    void backupObjectUsesTheBackupConfigurationPrefix() throws Exception {
        createStream(", \"S3BackupMode\": \"Enabled\", \"S3BackupConfiguration\": "
                + "{\"RoleARN\": \"arn:aws:iam::000000000000:role/firehose\", \"BucketARN\": \"arn:aws:s3:::bk\","
                + " \"Prefix\": \"backup/\"}");

        service.flush(STREAM);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(s3Service).putObject(eq("bk"), keys.capture(), any(byte[].class), anyString(), anyMap(), any());
        assertTrue(keys.getValue().startsWith("backup/"), keys.getValue());
    }

    @Test
    void aFailingBackupWriteStillLoadsTheStagedBatchAndDoesNotRestageIt() throws Exception {
        createStream(BACKUP_ENABLED);
        doThrow(new IllegalStateException("backup bucket unavailable")).when(s3Service)
                .putObject(eq("bk"), anyString(), any(byte[].class), anyString(), anyMap(), any());

        service.flush(STREAM);
        service.flush(STREAM);

        verifyStagedTimes(1);
        verify(delivery, times(1)).copy(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void aFailingPrimaryWriteWritesNoBackupSoNothingIsDelivered() throws Exception {
        createStream(BACKUP_ENABLED);
        doThrow(new IllegalStateException("s3 unavailable")).when(s3Service)
                .putObject(eq("stage"), anyString(), any(byte[].class), anyString(), anyMap(), any());

        service.flush(STREAM);

        verify(s3Service, never()).putObject(eq("bk"), anyString(), any(byte[].class), anyString(), anyMap(), any());
        verify(delivery, never()).copy(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void rejectedExecutionDoesNotRestoreRecordsToBuffer() throws Exception {
        Executor rejectingExecutor = r -> {
            throw new RejectedExecutionException("executor shut down");
        };
        FirehoseService rejectingService = new FirehoseService(storageFactory, s3Service,
                new KinesisService(storageFactory, regionResolver), regionResolver, clock, config,
                mock(FirehoseParquetConverter.class), mock(FirehoseLambdaTransformer.class), delivery, rejectingExecutor);

        RedshiftDestination destination = mapper.readValue("""
                {"RoleARN": "arn:aws:iam::000000000000:role/firehose",
                 "ClusterJDBCURL": "jdbc:redshift://c1.abc.us-east-1.redshift.amazonaws.com:5439/dev",
                 "Username": "admin", "Password": "Secret123",
                 "CopyCommand": {"DataTableName": "events"},
                 "S3Configuration": {"RoleARN": "arn:aws:iam::000000000000:role/firehose", "BucketARN": "arn:aws:s3:::stage"}}
                """, RedshiftDestination.class);
        rejectingService.createDeliveryStream("us-east-1", "fh-rejected", null, destination, List.of(), null, null);
        rejectingService.putRecord("fh-rejected", new Record("{\"id\":1}".getBytes(StandardCharsets.UTF_8)));

        rejectingService.flush("fh-rejected");
        rejectingService.flush("fh-rejected");

        verify(s3Service, times(1)).putObject(eq("stage"), anyString(), any(byte[].class), anyString(),
                anyMap(), any());
    }

    @Test
    void onPreShutdownFlushesBuffersAndRunsCopyBeforeShuttingDown() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        FirehoseService shutdownService = new FirehoseService(storageFactory, s3Service,
                new KinesisService(storageFactory, regionResolver), regionResolver, clock, config,
                mock(FirehoseParquetConverter.class), mock(FirehoseLambdaTransformer.class), delivery, executor);

        RedshiftDestination destination = mapper.readValue("""
                {"RoleARN": "arn:aws:iam::000000000000:role/firehose",
                 "ClusterJDBCURL": "jdbc:redshift://c1.abc.us-east-1.redshift.amazonaws.com:5439/dev",
                 "Username": "admin", "Password": "Secret123",
                 "CopyCommand": {"DataTableName": "events"},
                 "S3Configuration": {"RoleARN": "arn:aws:iam::000000000000:role/firehose", "BucketARN": "arn:aws:s3:::stage"}}
                """, RedshiftDestination.class);
        shutdownService.createDeliveryStream("us-east-1", "fh-shutdown", null, destination, List.of(), null, null);
        shutdownService.putRecord("fh-shutdown", new Record("{\"id\":1}".getBytes(StandardCharsets.UTF_8)));

        shutdownService.onPreShutdown(null);

        verify(s3Service, times(1)).putObject(eq("stage"), anyString(), any(byte[].class), anyString(),
                anyMap(), any());
        verify(delivery, times(1)).copy(eq("000000000000"), eq("us-east-1"), any(), eq("stage"), anyString());
        assertTrue(executor.isTerminated());
    }

    @Test
    void onPreShutdownDropsQueuedTasksWhenTerminationTimesOut() throws Exception {
        ExecutorService mockExecutor = mock(ExecutorService.class);
        FirehoseService shutdownService = new FirehoseService(storageFactory, s3Service,
                new KinesisService(storageFactory, regionResolver), regionResolver, clock, config,
                mock(FirehoseParquetConverter.class), mock(FirehoseLambdaTransformer.class), delivery, mockExecutor);

        RedshiftDestination destination = mapper.readValue("""
                {"RoleARN": "arn:aws:iam::000000000000:role/firehose",
                 "ClusterJDBCURL": "jdbc:redshift://c1.abc.us-east-1.redshift.amazonaws.com:5439/dev",
                 "Username": "admin", "Password": "Secret123",
                 "CopyCommand": {"DataTableName": "events"},
                 "S3Configuration": {"RoleARN": "arn:aws:iam::000000000000:role/firehose", "BucketARN": "arn:aws:s3:::stage"}}
                """, RedshiftDestination.class);
        shutdownService.createDeliveryStream("us-east-1", "fh-timeout", null, destination, List.of(), null, null);
        shutdownService.putRecord("fh-timeout", new Record("{\"id\":1}".getBytes(StandardCharsets.UTF_8)));

        when(mockExecutor.awaitTermination(anyLong(), any())).thenReturn(false);
        when(mockExecutor.shutdownNow()).thenReturn(List.of(() -> {}));

        shutdownService.onPreShutdown(null);

        InOrder inOrder = Mockito.inOrder(mockExecutor);
        inOrder.verify(mockExecutor).execute(any());
        inOrder.verify(mockExecutor).shutdown();
        inOrder.verify(mockExecutor).awaitTermination(5, TimeUnit.SECONDS);
        inOrder.verify(mockExecutor).shutdownNow();
    }

    @Test
    void failedCopyIsRetriedAgainstTheSameStagedObjectWithoutRestaging() throws Exception {
        createStream("");
        copyFails();

        service.flush(STREAM);
        pastBackoff();
        service.retryPendingRedshiftCopies();
        pastBackoff();
        service.retryPendingRedshiftCopies();

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(delivery, times(3)).copy(anyString(), anyString(), any(), eq("stage"), keys.capture());
        assertEquals(1, new HashSet<>(keys.getAllValues()).size(), "every attempt must use the one staged key");
        verifyStagedTimes(1);
    }

    @Test
    void failedCopyIsNotRetriedBeforeTheBackoffHasElapsed() throws Exception {
        createStream("");
        copyFails();

        service.flush(STREAM);
        service.retryPendingRedshiftCopies();
        service.retryPendingRedshiftCopies();

        verify(delivery, times(1)).copy(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void theSchedulerTickRetriesPendingCopies() throws Exception {
        createStream("");
        copyFails();
        service.flush(STREAM);
        pastBackoff();

        service.tickSafely();

        verify(delivery, times(2)).copy(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void recoveredCopyStopsBeingRetried() throws Exception {
        createStream("");
        copyFails();
        service.flush(STREAM);
        Mockito.reset(delivery);

        pastBackoff();
        service.retryPendingRedshiftCopies();
        pastBackoff();
        service.retryPendingRedshiftCopies();

        verify(delivery, times(1)).copy(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void failedCopyIsDroppedAfterTheRetryWindowButTheStagedObjectStays() throws Exception {
        createStream(", \"RetryOptions\": {\"DurationInSeconds\": 350}");
        copyFails();

        service.flush(STREAM);
        pastBackoff();
        service.retryPendingRedshiftCopies();
        pastBackoff();
        service.retryPendingRedshiftCopies();

        verify(delivery, times(2)).copy(anyString(), anyString(), any(), anyString(), anyString());
        verifyStagedTimes(1);
    }

    @Test
    void retryWindowShorterThanBackoffExpiresWithoutRetrying() throws Exception {
        createStream(", \"RetryOptions\": {\"DurationInSeconds\": 10}");
        copyFails();

        service.flush(STREAM);
        pastBackoff();
        service.retryPendingRedshiftCopies();

        verify(delivery, times(1)).copy(anyString(), anyString(), any(), anyString(), anyString());
        verifyStagedTimes(1);
    }

    @Test
    void slowFailureMeasuresNextAttemptFromFailureTime() throws Exception {
        createStream("");
        Mockito.doAnswer(invocation -> {
            clock.advance(Duration.ofSeconds(150));
            throw new IllegalStateException("slow connection failure");
        }).when(delivery).copy(anyString(), anyString(), any(), anyString(), anyString());

        service.flush(STREAM);
        clock.advance(Duration.ofSeconds(151));
        service.retryPendingRedshiftCopies();
        verify(delivery, times(1)).copy(anyString(), anyString(), any(), anyString(), anyString());

        clock.advance(Duration.ofSeconds(150));
        service.retryPendingRedshiftCopies();
        verify(delivery, times(2)).copy(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void firstAttemptTakingLongerThanRetryWindowDoesNotRetry() throws Exception {
        createStream(", \"RetryOptions\": {\"DurationInSeconds\": 100}");
        Mockito.doAnswer(invocation -> {
            clock.advance(Duration.ofSeconds(150));
            throw new IllegalStateException("long initial attempt failed");
        }).when(delivery).copy(anyString(), anyString(), any(), anyString(), anyString());

        service.flush(STREAM);
        pastBackoff();
        service.retryPendingRedshiftCopies();

        verify(delivery, times(1)).copy(anyString(), anyString(), any(), anyString(), anyString());
        verifyStagedTimes(1);
    }

    @Test
    void zeroRetryDurationMeansASingleAttempt() throws Exception {
        createStream(", \"RetryOptions\": {\"DurationInSeconds\": 0}");
        copyFails();

        service.flush(STREAM);
        pastBackoff();
        service.retryPendingRedshiftCopies();

        verify(delivery, times(1)).copy(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void anUnexpectedFailureDoesNotEscapeAndIsRetried() throws Exception {
        createStream("");
        doThrow(new NullPointerException("unexpected internal bug")).when(delivery)
                .copy(anyString(), anyString(), any(), anyString(), anyString());

        service.flush(STREAM);
        pastBackoff();
        service.retryPendingRedshiftCopies();

        verify(delivery, times(2)).copy(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void aNewerBatchWaitsBehindAnOlderFailingOne() throws Exception {
        createStream("");
        copyFails();
        service.flush(STREAM);
        service.putRecord(STREAM, new Record("{\"id\":2}".getBytes(StandardCharsets.UTF_8)));

        service.flush(STREAM);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(delivery, times(1)).copy(anyString(), anyString(), any(), eq("stage"), keys.capture());
        verifyStagedTimes(2);
    }

    @Test
    void deletingTheStreamDiscardsPendingCopies() throws Exception {
        createStream("");
        copyFails();
        service.flush(STREAM);

        service.deleteDeliveryStream(STREAM);
        pastBackoff();
        service.retryPendingRedshiftCopies();

        verify(delivery, times(1)).copy(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void deletingStreamWhileDrainIteratesStopsFurtherCopies() throws Exception {
        createStream("");
        copyFails();
        service.flush(STREAM);
        service.putRecord(STREAM, new Record("{\"id\":2}".getBytes(StandardCharsets.UTF_8)));
        service.flush(STREAM);

        Mockito.reset(delivery);
        Mockito.doAnswer(invocation -> {
            service.deleteDeliveryStream(STREAM);
            return null;
        }).when(delivery).copy(anyString(), anyString(), any(), anyString(), anyString());

        pastBackoff();
        service.retryPendingRedshiftCopies();

        verify(delivery, times(1)).copy(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void retryPendingRedshiftCopiesDoesNotRunWhenHeadIsNotDue() throws Exception {
        createStream("");
        copyFails();

        service.flush(STREAM);
        for (int i = 0; i < 5; i++) {
            service.retryPendingRedshiftCopies();
        }

        verify(delivery, times(1)).copy(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void pendingCopiesAreDiscardedIfDestinationIsNoLongerRedshift() throws Exception {
        createStream("");
        copyFails();
        service.flush(STREAM);

        service.describeDeliveryStream(STREAM).getDestinations().get(0).setRedshiftDestinationDescription(null);

        pastBackoff();
        service.retryPendingRedshiftCopies();

        verify(delivery, times(1)).copy(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void backupEnabledWritesTheBatchToTheBackupBucketOnlyOnce() throws Exception {
        createStream(BACKUP_ENABLED);
        copyFails();

        service.flush(STREAM);
        pastBackoff();
        service.retryPendingRedshiftCopies();

        verifyStagedTimes(1);
        verify(s3Service, times(1)).putObject(eq("bk"), anyString(), any(byte[].class), anyString(), anyMap(), any());
    }
}
