package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.s3.PreSignedUrlGenerator;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.ses.model.EmailInsights;
import io.github.hectorvent.floci.services.ses.model.ExportJob;
import io.github.hectorvent.floci.services.ses.model.InsightsEvent;
import io.github.hectorvent.floci.services.ses.model.SentEmail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The parts of {@link SesExportJobService} the integration test cannot pin down: what happens when
 * a cancel lands in the window between the object being written and the job being marked complete.
 * The worker runs on a captured task here, so that window is reachable.
 */
class SesExportJobServiceTest {

    private static final String REGION = "eu-north-1";
    private static final String ACCOUNT = "000000000000";

    private final S3Service s3Service = mock(S3Service.class);
    private final PreSignedUrlGenerator presigner = mock(PreSignedUrlGenerator.class);
    private final SesSentEmailService sentEmailService =
            new SesSentEmailService(new InMemoryStorage<>());
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<Runnable> tasks = new ArrayList<>();

    private SesExportJobService service;

    @BeforeEach
    void setUp() {
        when(s3Service.bucketExists(anyString())).thenReturn(true);
        service = new SesExportJobService(new InMemoryStorage<>(), sentEmailService, s3Service,
                presigner, objectMapper, Clock.fixed(Instant.parse("2026-09-23T00:00:00Z"),
                        ZoneOffset.UTC),
                tasks::add, "http://localhost:4566");
    }

    private JsonNode insightsSource() {
        try {
            return objectMapper.readTree("""
                {"MessageInsightsDataSource": {"StartDate": 1758499200, "EndDate": 1758672000}}
                """);
        } catch (JsonProcessingException e) {
            throw new AssertionError(e);
        }
    }

    private ExportJob createJob() {
        return service.createExportJob(REGION, ACCOUNT, insightsSource(), destination(), () -> { });
    }

    private JsonNode destination() {
        return objectMapper.createObjectNode().put("DataFormat", ExportJob.FORMAT_CSV);
    }

    @Test
    void aCancelDuringTheWriteLeavesNoObjectBehind() {
        ExportJob job = createJob();
        // The cancel lands while the worker is inside putObject, which is the only window in which
        // an object can be written for a job that will never point at it.
        when(s3Service.putObject(anyString(), anyString(), any(), anyString(), any()))
                .thenAnswer(invocation -> {
                    service.cancelExportJob(REGION, job.getJobId());
                    return null;
                });

        tasks.forEach(Runnable::run);

        ExportJob stored = service.getExportJob(REGION, job.getJobId());
        assertEquals(ExportJob.STATUS_CANCELLED, stored.getJobStatus());
        assertNull(stored.getObjectKey(), "a cancelled job points at nothing");
        verify(s3Service).deleteObject(eq(SesExportJobService.exportBucket(ACCOUNT, REGION)), anyString());
    }

    @Test
    void anUninterruptedJobKeepsItsObject() {
        ExportJob job = createJob();

        tasks.forEach(Runnable::run);

        ExportJob stored = service.getExportJob(REGION, job.getJobId());
        assertEquals(ExportJob.STATUS_COMPLETED, stored.getJobStatus());
        verify(s3Service, never()).deleteObject(anyString(), anyString());
    }

    @Test
    void cancellingBeforeTheWorkerRunsSkipsTheWriteEntirely() {
        ExportJob job = createJob();
        service.cancelExportJob(REGION, job.getJobId());

        tasks.forEach(Runnable::run);

        assertEquals(ExportJob.STATUS_CANCELLED,
                service.getExportJob(REGION, job.getJobId()).getJobStatus());
        verify(s3Service, never()).putObject(anyString(), anyString(), any(), anyString(),
                any(Map.class));
    }

    @Test
    void aResetAbandonsAJobThatHasNotStartedWriting() {
        ExportJob job = createJob();

        service.beforeReset();
        tasks.forEach(Runnable::run);

        verify(s3Service, never()).putObject(anyString(), anyString(), any(), anyString(),
                any(Map.class));
        assertEquals(ExportJob.STATUS_PROCESSING,
                service.getExportJob(REGION, job.getJobId()).getJobStatus());
    }

    @Test
    void aResetWaitsForAWorkerAlreadyInsideItsWrite() throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        AtomicBoolean workerFinishedTheWrite = new AtomicBoolean();
        SesExportJobService threaded = new SesExportJobService(new InMemoryStorage<>(),
                sentEmailService, s3Service, presigner, objectMapper,
                Clock.fixed(Instant.parse("2026-09-23T00:00:00Z"), ZoneOffset.UTC),
                task -> new Thread(task).start(), "http://localhost:4566");
        when(s3Service.putObject(anyString(), anyString(), any(), anyString(), any()))
                .thenAnswer(invocation -> {
                    writing.countDown();
                    releaseWrite.await(5, TimeUnit.SECONDS);
                    workerFinishedTheWrite.set(true);
                    return null;
                });

        threaded.createExportJob(REGION, ACCOUNT, insightsSource(), destination(), () -> { });
        assertTrue(writing.await(5, TimeUnit.SECONDS), "the worker never reached its write");
        Thread reset = new Thread(threaded::beforeReset);
        reset.start();
        awaitParked(reset, "the reset waiting on the in-flight write");
        releaseWrite.countDown();
        reset.join(5_000);

        // join returns on timeout too, so without this the assertion below would hold even if
        // beforeReset never came back.
        assertFalse(reset.isAlive(), "the reset did not finish after the in-flight write");
        assertTrue(workerFinishedTheWrite.get(),
                "the reset returned before the in-flight write finished");
    }

    private void recordSend(Instant sentAt, String source) {
        SentEmail email = new SentEmail();
        email.setMessageId("m-" + sentAt.toEpochMilli() + "-" + source);
        email.setSource(source);
        email.setSentAt(sentAt);
        email.setInsights(List.of(new EmailInsights("to@example.com", "UNKNOWN_ISP",
                List.of(InsightsEvent.of(sentAt, "SEND")))));
        sentEmailService.record(REGION, email.getMessageId(), email);
    }

    private String runMetricsExport(String dimensions, Instant start, Instant end) throws Exception {
        JsonNode source = objectMapper.readTree("""
            {"MetricsDataSource": {"Dimensions": %s, "Namespace": "VDM",
                                   "Metrics": [{"Name": "SEND", "Aggregation": "VOLUME"}],
                                   "StartDate": %d, "EndDate": %d}}
            """.formatted(dimensions, start.getEpochSecond(), end.getEpochSecond()));
        service.createExportJob(REGION, ACCOUNT, source, destination(), () -> { });
        tasks.forEach(Runnable::run);
        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(s3Service).putObject(anyString(), anyString(), payload.capture(), anyString(), any());
        return new String(payload.getValue(), StandardCharsets.UTF_8);
    }

    @Test
    void anIdentityRowIsKeyedByTheBareAddressAndCountsItsSend() throws Exception {
        recordSend(Instant.parse("2026-09-23T10:00:00Z"), "\"Doe, Jane\" <jane@example.com>");

        String csv = runMetricsExport("{\"EMAIL_IDENTITY\": [\"*\"]}",
                Instant.parse("2026-09-23T00:00:00Z"), Instant.parse("2026-09-24T00:00:00Z"));

        // Keyed by the stored Source, the row is one the identity matching behind the counts then
        // refuses, so it comes back at zero, and a comma in the display name splits the CSV row.
        assertEquals("EMAIL_IDENTITY,SEND_VOLUME\njane@example.com,1.0000\n", csv);
    }

    @Test
    void aDimensionValueSeenOnlyOutsideTheWindowProducesNoRow() throws Exception {
        recordSend(Instant.parse("2026-09-23T10:00:00Z"), "inside@example.com");
        recordSend(Instant.parse("2026-09-20T10:00:00Z"), "outside@example.com");

        String csv = runMetricsExport("{\"EMAIL_IDENTITY\": [\"*\"]}",
                Instant.parse("2026-09-23T00:00:00Z"), Instant.parse("2026-09-24T00:00:00Z"));

        assertTrue(csv.contains("inside@example.com,1.0000"), csv);
        assertFalse(csv.contains("outside@example.com"), csv);
    }

    @Test
    void anInsightsWindowIncludesASendOnItsEndInstant() throws Exception {
        // The model calls MessageInsightsDataSource.EndDate inclusive, unlike the daily metrics
        // buckets, whose exclusive end the BatchGetMetricData probe confirmed.
        Instant sentAt = Instant.parse("2026-09-23T12:00:00Z");
        recordSend(sentAt, "edge@example.com");
        JsonNode source = objectMapper.readTree("""
            {"MessageInsightsDataSource": {"StartDate": %d, "EndDate": %d}}
            """.formatted(sentAt.minusSeconds(3600).getEpochSecond(), sentAt.getEpochSecond()));

        ExportJob job = service.createExportJob(REGION, ACCOUNT, source, destination(), () -> { });
        tasks.forEach(Runnable::run);

        assertEquals(1L, service.getExportJob(REGION, job.getJobId()).getProcessedRecordsCount());
    }

    @Test
    void aBucketCreatedByAConcurrentJobDoesNotFailTheExport() {
        when(s3Service.bucketExists(anyString())).thenReturn(false);
        when(s3Service.createBucket(anyString(), anyString())).thenThrow(new AwsException(
                "BucketAlreadyOwnedByYou",
                "Your previous request to create the named bucket succeeded and you already own it.",
                409));
        ExportJob job = createJob();

        tasks.forEach(Runnable::run);

        assertEquals(ExportJob.STATUS_COMPLETED,
                service.getExportJob(REGION, job.getJobId()).getJobStatus());
    }

    @Test
    void aCreateArrivingDuringAResetWaitsForItToFinish() throws Exception {
        service.beforeReset();
        AtomicBoolean admitted = new AtomicBoolean();
        Thread creator = new Thread(() -> {
            createJob();
            admitted.set(true);
        });

        creator.start();
        // Waiting for the thread to actually park proves the reset blocked it. A plain sleep would
        // pass while the thread was merely unscheduled, and stay green with the blocking removed.
        awaitParked(creator, "the create waiting on the reset");
        assertFalse(admitted.get(), "a create must not be admitted while the reset is in progress");
        service.afterReset();
        creator.join(5_000);

        assertTrue(admitted.get(), "afterReset must release the waiting create");
    }

    @Test
    void theExportBucketIsScopedToTheJobsAccount() {
        // An object write resolves its bucket across accounts under global-bucket-namespace, so a
        // shared name would put this account's export in whichever account created the bucket.
        createJob();

        tasks.forEach(Runnable::run);

        ArgumentCaptor<String> bucket = ArgumentCaptor.forClass(String.class);
        verify(s3Service).putObject(bucket.capture(), anyString(), any(), anyString(), any());
        assertEquals("floci-" + ACCOUNT + "-" + REGION + "-ses-export-files", bucket.getValue());
    }

    @Test
    void identitiesDifferingOnlyInCaseShareOneRow() throws Exception {
        // The aggregator matches identities without regard to case, so two rows would each count
        // both sends and the export would disagree with a BatchGetMetricData query.
        recordSend(Instant.parse("2026-09-23T10:00:00Z"), "a@example.com");
        recordSend(Instant.parse("2026-09-23T11:00:00Z"), "A@example.com");

        String csv = runMetricsExport("{\"EMAIL_IDENTITY\": [\"*\"]}",
                Instant.parse("2026-09-23T00:00:00Z"), Instant.parse("2026-09-24T00:00:00Z"));

        assertEquals("EMAIL_IDENTITY,SEND_VOLUME\na@example.com,2.0000\n", csv);
    }

    @Test
    void theConcurrentJobCeilingIsThePublishedQuota() {
        // SES documents 20 concurrent export jobs, the same figure it gives import jobs. The
        // workers are captured here, so every job stays PROCESSING and occupies a slot.
        for (int i = 0; i < SesExportJobService.MAX_CONCURRENT_JOBS; i++) {
            createJob();
        }

        AwsException thrown = assertThrows(AwsException.class, this::createJob);

        assertEquals("LimitExceededException", thrown.getErrorCode());
        assertEquals(20, SesExportJobService.MAX_CONCURRENT_JOBS);
    }

    /** Both waits here park in {@code Object.wait(timeout)}, so the state is timed, not WAITING. */
    private static void awaitParked(Thread thread, String what) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.TIMED_WAITING || state == Thread.State.WAITING) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError(what + " never parked, it was " + thread.getState());
    }

    @Test
    void recoverInterruptedJobsFailsAJobOfEveryAccount() {
        String otherAccount = "111122223333";
        AccountAwareStorageBackend<ExportJob> persisted =
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, ACCOUNT);
        ExportJob stuck = new ExportJob();
        stuck.setJobId("stuck");
        stuck.setRegion(REGION);
        stuck.setJobStatus(ExportJob.STATUS_PROCESSING);
        stuck.setCreatedTimestamp(Instant.parse("2026-09-23T00:00:00Z"));
        String key = "export-job::" + REGION + "::stuck";
        persisted.putForAccount(otherAccount, key, stuck);

        new SesExportJobService(persisted, sentEmailService, s3Service, presigner, objectMapper,
                Clock.fixed(Instant.parse("2026-09-23T00:00:00Z"), ZoneOffset.UTC), tasks::add,
                "http://localhost:4566");

        ExportJob recovered = persisted.getForAccount(otherAccount, key).orElseThrow();
        assertEquals(ExportJob.STATUS_FAILED, recovered.getJobStatus());
        assertEquals(SesExportJobService.RESTART_FAILURE_MESSAGE, recovered.getErrorMessage());
    }

    @Test
    void cancellingAFinishedJobIsRefused() {
        ExportJob job = createJob();
        tasks.forEach(Runnable::run);

        AwsException thrown = assertThrows(AwsException.class,
                () -> service.cancelExportJob(REGION, job.getJobId()));

        assertEquals("Cannot cancel an export job that has a <COMPLETED> status.",
                thrown.getMessage());
    }
}
