package io.floci.aws.services.emrcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.SparkConnectTargets;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.eks.EksService;
import io.floci.aws.services.eks.model.AccessConfig;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.emrcontainers.EmrContainersService.ListQuery;
import io.floci.aws.services.emrserverless.SessionServers;
import io.floci.aws.services.emrserverless.SparkJobRunner;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmrContainersServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String EKS_CLUSTER = "analytics";
    private static final String ACCESS_ENTRY_CLUSTER = "managed-access";
    private static final String ROLE = "arn:aws:iam::000000000000:role/emr-job";
    private static final String RELEASE = "emr-7.5.0-latest";

    private final ObjectMapper mapper = new ObjectMapper();
    private MutableClock clock;
    private EksService eks;
    // The retries' backoffs a docker service waits for, which the test ends when it chooses.
    private final QueuedDelays delays = new QueuedDelays();

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        eks = mock(EksService.class);
        when(eks.findAuthenticationCluster(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(eks.findAuthenticationCluster(ACCOUNT_ID, REGION, EKS_CLUSTER)).thenReturn(Optional.of(new Cluster()));
        Cluster withAccessEntries = new Cluster();
        withAccessEntries.setAccessConfig(new AccessConfig("API_AND_CONFIG_MAP", false));
        when(eks.findAuthenticationCluster(ACCOUNT_ID, REGION, ACCESS_ENTRY_CLUSTER))
                .thenReturn(Optional.of(withAccessEntries));
    }

    private EmrContainersService service(int jobRunDurationSeconds) {
        return service(jobRunDurationSeconds, REGION, List.of(AccountAwareStorageBackend.inMemory(ACCOUNT_ID),
                AccountAwareStorageBackend.inMemory(ACCOUNT_ID), AccountAwareStorageBackend.inMemory(ACCOUNT_ID),
                AccountAwareStorageBackend.inMemory(ACCOUNT_ID), AccountAwareStorageBackend.inMemory(ACCOUNT_ID)));
    }

    // A service serving requests of region over the given virtual cluster, job run, endpoint, template and security
    // configuration stores, so two services of different regions can share them.
    private EmrContainersService service(int jobRunDurationSeconds, String region,
                                         List<AccountAwareStorageBackend<ObjectNode>> stores) {
        IamService iam = mock(IamService.class);
        when(iam.resolveCallerArn(eq(null))).thenReturn(Optional.empty());
        return new EmrContainersService(stores.get(0), stores.get(1), stores.get(2), stores.get(3), stores.get(4),
                mapper, new RegionResolver(region, ACCOUNT_ID), eks, iam, null, clock, jobRunDurationSeconds);
    }

    private ObjectNode json(String text) {
        try {
            return (ObjectNode) mapper.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(text, e);
        }
    }

    private String virtualCluster(EmrContainersService service, String eksCluster) {
        return service.createVirtualCluster(json("""
                {"name": "vc", "clientToken": "%s",
                 "containerProvider": {"type": "EKS", "id": "%s", "info": {"eksInfo": {"namespace": "spark"}}}}
                """.formatted(UUID.randomUUID(), eksCluster))).get("id").textValue();
    }

    private ObjectNode startJobRun(EmrContainersService service, String virtualClusterId) {
        return service.startJobRun(virtualClusterId, json("""
                {"name": "pi", "clientToken": "%s", "executionRoleArn": "%s", "releaseLabel": "%s",
                 "jobDriver": {"sparkSubmitJobDriver": {"entryPoint": "s3://bucket/pi.py"}}}
                """.formatted(UUID.randomUUID(), ROLE, RELEASE)));
    }

    private JsonNode jobRun(EmrContainersService service, String virtualClusterId, String id) {
        return service.describeJobRun(virtualClusterId, id).get("jobRun");
    }

    @Test
    void virtualClusterIsRunningWithAnArnAndIdOfAwsShape() {
        EmrContainersService service = service(0);
        String id = virtualCluster(service, EKS_CLUSTER);

        JsonNode cluster = service.describeVirtualCluster(id).get("virtualCluster");
        assertEquals(EmrContainersService.VIRTUAL_CLUSTER_ID_LENGTH, id.length());
        assertTrue(id.matches("[0-9a-z]+"));
        assertEquals("arn:aws:emr-containers:us-east-1:000000000000:/virtualclusters/" + id,
                cluster.get("arn").textValue());
        assertEquals("RUNNING", cluster.get("state").textValue());
        assertEquals("2026-01-01T00:00:00Z", cluster.get("createdAt").textValue());
        assertEquals("spark", cluster.at("/containerProvider/info/eksInfo/namespace").textValue());
        assertFalse(cluster.has("_clientToken"), "Floci's own members never leave the service");
    }

    @Test
    void sameClientTokenAnswersTheSameVirtualCluster() {
        EmrContainersService service = service(0);
        String body = """
                {"name": "vc", "clientToken": "once",
                 "containerProvider": {"type": "EKS", "id": "analytics", "info": {"eksInfo": {"namespace": "a"}}}}
                """;

        String first = service.createVirtualCluster(json(body)).get("id").textValue();
        String second = service.createVirtualCluster(json(body)).get("id").textValue();

        assertEquals(first, second);
    }

    @Test
    void jobRunCompletesAsSoonAsSubmittedByDefault() {
        EmrContainersService service = service(0);
        String clusterId = virtualCluster(service, EKS_CLUSTER);

        ObjectNode started = startJobRun(service, clusterId);
        JsonNode run = jobRun(service, clusterId, started.get("id").textValue());

        assertEquals(EmrContainersService.JOB_RUN_ID_LENGTH, started.get("id").textValue().length());
        assertEquals("COMPLETED", run.get("state").textValue());
        assertEquals(run.get("createdAt"), run.get("finishedAt"));
        assertEquals("arn:aws:iam::000000000000:root", run.get("createdBy").textValue());
        assertEquals(RELEASE, run.get("releaseLabel").textValue());
    }

    @Test
    void jobRunStaysRunningForTheConfiguredDurationThenCompletes() {
        EmrContainersService service = service(60);
        String clusterId = virtualCluster(service, EKS_CLUSTER);
        String id = startJobRun(service, clusterId).get("id").textValue();

        assertEquals("RUNNING", jobRun(service, clusterId, id).get("state").textValue());
        clock.advance(Duration.ofSeconds(59));
        assertEquals("RUNNING", jobRun(service, clusterId, id).get("state").textValue());
        clock.advance(Duration.ofSeconds(1));

        JsonNode run = jobRun(service, clusterId, id);
        assertEquals("COMPLETED", run.get("state").textValue());
        assertEquals("2026-01-01T00:01:00Z", run.get("finishedAt").textValue());
    }

    @Test
    void jobRunOnAClusterEksDoesNotHaveFailsAsAwsDocuments() {
        EmrContainersService service = service(0);
        String clusterId = virtualCluster(service, "gone");

        JsonNode run = jobRun(service, clusterId, startJobRun(service, clusterId).get("id").textValue());

        assertEquals("FAILED", run.get("state").textValue());
        assertEquals("Cluster gone does not exist.", run.get("stateDetails").textValue());
        assertEquals("CLUSTER_UNAVAILABLE", run.get("failureReason").textValue());
    }

    @Test
    void runningJobRunIsCancelledOnceAndThenRefused() {
        EmrContainersService service = service(600);
        String clusterId = virtualCluster(service, EKS_CLUSTER);
        String id = startJobRun(service, clusterId).get("id").textValue();

        service.cancelJobRun(clusterId, id);
        assertEquals("CANCELLED", jobRun(service, clusterId, id).get("state").textValue());

        AwsException again = assertThrows(AwsException.class, () -> service.cancelJobRun(clusterId, id));
        assertEquals("ValidationException", again.getErrorCode());
        clock.advance(Duration.ofHours(1));
        assertEquals("CANCELLED", jobRun(service, clusterId, id).get("state").textValue(),
                "a cancelled run never completes when its duration passes");
    }

    @Test
    void jobRunOnUnknownOrTerminatedVirtualClusterIsRefusedWithAwsWording() {
        EmrContainersService service = service(0);
        AwsException unknown = assertThrows(AwsException.class, () -> startJobRun(service, "nosuchcluster"));
        assertEquals("ResourceNotFoundException", unknown.getErrorCode());
        assertEquals(400, unknown.getHttpStatus());
        assertEquals("Virtual cluster nosuchcluster doesn't exist.", unknown.getMessage());

        String clusterId = virtualCluster(service, EKS_CLUSTER);
        service.deleteVirtualCluster(clusterId);
        AwsException terminated = assertThrows(AwsException.class, () -> startJobRun(service, clusterId));
        assertEquals("ValidationException", terminated.getErrorCode());
        assertEquals("Virtual cluster state TERMINATED is not valid to create resource JobRun.",
                terminated.getMessage());
    }

    @Test
    void jobTemplateParametersAreSubstitutedWithDefaultsAndTypes() {
        EmrContainersService service = service(0);
        String clusterId = virtualCluster(service, EKS_CLUSTER);
        String templateId = service.createJobTemplate(json("""
                {"name": "t", "clientToken": "%s", "jobTemplateData": {
                   "executionRoleArn": "%s", "releaseLabel": "%s",
                   "jobDriver": {"sparkSubmitJobDriver": {"entryPoint": "${EntryPoint}",
                     "sparkSubmitParameters":
                       "--class ${MainClass} --conf spark.executor.instances=${Executors} ${Kept}"}},
                   "parameterConfiguration": {
                     "EntryPoint": {"type": "STRING"},
                     "MainClass": {"type": "STRING", "defaultValue": "Main"},
                     "Executors": {"type": "NUMBER", "defaultValue": "2"}},
                   "jobTags": {"team": "data", "tier": "batch"}}}
                """.formatted(UUID.randomUUID(), ROLE, RELEASE))).get("id").textValue();

        String id = service.startJobRun(clusterId, json("""
                {"clientToken": "%s", "jobTemplateId": "%s",
                 "jobTemplateParameters": {"EntryPoint": "s3://bucket/app.jar", "Executors": "4"},
                 "tags": {"tier": "adhoc"}}
                """.formatted(UUID.randomUUID(), templateId))).get("id").textValue();
        JsonNode run = jobRun(service, clusterId, id);

        assertEquals("s3://bucket/app.jar", run.at("/jobDriver/sparkSubmitJobDriver/entryPoint").textValue());
        assertEquals("--class Main --conf spark.executor.instances=4 ${Kept}",
                run.at("/jobDriver/sparkSubmitJobDriver/sparkSubmitParameters").textValue());
        assertEquals(ROLE, run.get("executionRoleArn").textValue());
        assertEquals(Map.of("team", "data", "tier", "adhoc"), mapper.convertValue(run.get("tags"), Map.class));

        AwsException missing = assertThrows(AwsException.class, () -> service.startJobRun(clusterId, json("""
                {"clientToken": "%s", "jobTemplateId": "%s"}
                """.formatted(UUID.randomUUID(), templateId))));
        assertEquals("ValidationException", missing.getErrorCode());
        assertTrue(missing.getMessage().contains("EntryPoint"), missing.getMessage());

        AwsException notNumber = assertThrows(AwsException.class, () -> service.startJobRun(clusterId, json("""
                {"clientToken": "%s", "jobTemplateId": "%s",
                 "jobTemplateParameters": {"EntryPoint": "s3://bucket/app.jar", "Executors": "many"}}
                """.formatted(UUID.randomUUID(), templateId))));
        assertTrue(notNumber.getMessage().contains("Executors"), notNumber.getMessage());
    }

    @Test
    void jobRunWithoutTemplateNeedsItsRoleReleaseAndDriver() {
        EmrContainersService service = service(0);
        String clusterId = virtualCluster(service, EKS_CLUSTER);

        AwsException error = assertThrows(AwsException.class, () -> service.startJobRun(clusterId,
                json("{\"clientToken\": \"t\", \"releaseLabel\": \"emr-7.5.0-latest\"}")));

        assertEquals("ValidationException", error.getErrorCode());
        assertTrue(error.getMessage().startsWith("2 validation errors detected: "), error.getMessage());
    }

    @Test
    void schedulerStatusCountsRunningJobRunsAndAnEmptyConfigurationClearsIt() {
        EmrContainersService service = service(600);
        String clusterId = virtualCluster(service, EKS_CLUSTER);
        startJobRun(service, clusterId);
        startJobRun(service, clusterId);
        service.updateVirtualCluster(clusterId, json("""
                {"clientToken": "u1", "schedulerConfiguration": {"maxConcurrentJobRuns": 5, "maxInQueueJobRuns": 100}}
                """));

        JsonNode status = service.describeVirtualCluster(clusterId).at("/virtualCluster/schedulerStatus");
        assertEquals(2, status.get("currentConcurrentJobRuns").intValue());
        assertEquals(0, status.get("currentInQueueJobRuns").intValue());

        service.updateVirtualCluster(clusterId, json("{\"clientToken\": \"u2\", \"schedulerConfiguration\": {}}"));
        JsonNode cleared = service.describeVirtualCluster(clusterId).get("virtualCluster");
        assertFalse(cleared.has("schedulerConfiguration"));
        assertFalse(cleared.has("schedulerStatus"));
    }

    // A virtual cluster on EKS_CLUSTER with these concurrent job limits.
    private String limitedVirtualCluster(EmrContainersService service, String schedulerConfiguration) {
        return service.createVirtualCluster(json("""
                {"name": "vc", "clientToken": "%s", "schedulerConfiguration": %s,
                 "containerProvider": {"type": "EKS", "id": "%s", "info": {"eksInfo": {"namespace": "spark"}}}}
                """.formatted(UUID.randomUUID(), schedulerConfiguration, EKS_CLUSTER))).get("id").textValue();
    }

    private JsonNode schedulerStatus(EmrContainersService service, String clusterId) {
        return service.describeVirtualCluster(clusterId).at("/virtualCluster/schedulerStatus");
    }

    @Test
    void aRunBeyondTheRunningLimitWaitsPendingAndStartsWhenASlotFreesUp() {
        EmrContainersService service = service(600);
        String clusterId = limitedVirtualCluster(service, "{\"maxConcurrentJobRuns\": 1}");
        String first = startJobRun(service, clusterId).get("id").textValue();
        clock.advance(Duration.ofSeconds(1));
        String second = startJobRun(service, clusterId).get("id").textValue();

        assertEquals("RUNNING", jobRun(service, clusterId, first).get("state").textValue());
        assertEquals("PENDING", jobRun(service, clusterId, second).get("state").textValue());
        assertEquals(1, schedulerStatus(service, clusterId).get("currentConcurrentJobRuns").intValue());
        assertEquals(1, schedulerStatus(service, clusterId).get("currentInQueueJobRuns").intValue());

        // Read only after both have ended: the second started the moment the first's slot freed up.
        clock.advance(Duration.ofHours(1));
        assertEquals("2026-01-01T00:10:00Z", jobRun(service, clusterId, first).get("finishedAt").textValue());
        JsonNode run = jobRun(service, clusterId, second);
        assertEquals("COMPLETED", run.get("state").textValue());
        assertEquals("2026-01-01T00:20:00Z", run.get("finishedAt").textValue());
        assertFalse(run.has("_queuePosition"), "Floci's own members never leave the service");
        assertEquals(0, schedulerStatus(service, clusterId).get("currentInQueueJobRuns").intValue());
    }

    @Test
    void aFullQueueRefusesTheRunAndARunQueuedThirtyMinutesFails() {
        EmrContainersService service = service(3600);
        String clusterId = limitedVirtualCluster(service, "{\"maxConcurrentJobRuns\": 1, \"maxInQueueJobRuns\": 100}");
        startJobRun(service, clusterId);
        List<String> queued = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            queued.add(startJobRun(service, clusterId).get("id").textValue());
        }

        AwsException full = assertThrows(AwsException.class, () -> startJobRun(service, clusterId));
        assertEquals("ValidationException", full.getErrorCode());
        assertEquals(400, full.getHttpStatus());
        assertEquals(100, schedulerStatus(service, clusterId).get("currentInQueueJobRuns").intValue());

        clock.advance(Duration.ofMinutes(30));
        JsonNode timedOut = jobRun(service, clusterId, queued.get(0));
        assertEquals("FAILED", timedOut.get("state").textValue());
        assertEquals("2026-01-01T00:30:00Z", timedOut.get("finishedAt").textValue());
        assertTrue(timedOut.get("stateDetails").textValue().contains("30 minutes"), timedOut.toString());
        assertEquals(0, schedulerStatus(service, clusterId).get("currentInQueueJobRuns").intValue());
        String next = startJobRun(service, clusterId).get("id").textValue();
        assertEquals("PENDING", jobRun(service, clusterId, next).get("state").textValue(),
                "the first run still holds the one running slot");
    }

    @Test
    void aCancelledRunOrARaisedLimitStartsTheQueuedRunsInOrderAtOnce() {
        EmrContainersService service = service(600);
        String clusterId = limitedVirtualCluster(service, "{\"maxConcurrentJobRuns\": 1}");
        String first = startJobRun(service, clusterId).get("id").textValue();
        String second = startJobRun(service, clusterId).get("id").textValue();
        String third = startJobRun(service, clusterId).get("id").textValue();

        clock.advance(Duration.ofMinutes(1));
        service.cancelJobRun(clusterId, first);
        assertEquals("RUNNING", jobRun(service, clusterId, second).get("state").textValue());
        assertEquals("PENDING", jobRun(service, clusterId, third).get("state").textValue());

        clock.advance(Duration.ofMinutes(1));
        JsonNode raised = service.updateVirtualCluster(clusterId,
                json("{\"clientToken\": \"u\", \"schedulerConfiguration\": {\"maxConcurrentJobRuns\": 2}}"));
        assertEquals(2, raised.at("/virtualCluster/schedulerStatus/currentConcurrentJobRuns").intValue());
        clock.advance(Duration.ofHours(1));
        assertEquals("2026-01-01T00:11:00Z", jobRun(service, clusterId, second).get("finishedAt").textValue());
        assertEquals("2026-01-01T00:12:00Z", jobRun(service, clusterId, third).get("finishedAt").textValue());
    }

    @Test
    void withoutLimitsAThousandRunsRunAtOnce() {
        EmrContainersService service = service(600);
        String clusterId = virtualCluster(service, EKS_CLUSTER);
        for (int i = 0; i < 1000; i++) {
            startJobRun(service, clusterId);
        }
        String beyond = startJobRun(service, clusterId).get("id").textValue();

        assertEquals("PENDING", jobRun(service, clusterId, beyond).get("state").textValue());
        clock.advance(Duration.ofHours(1));
        assertEquals("2026-01-01T00:20:00Z", jobRun(service, clusterId, beyond).get("finishedAt").textValue());
    }

    @Test
    void runsThatCompleteAndStartTogetherAreStoredInOneWrite() {
        CountingStorage runs = new CountingStorage();
        EmrContainersService service = service(600, REGION, List.of(AccountAwareStorageBackend.inMemory(ACCOUNT_ID),
                new AccountAwareStorageBackend<>(runs, null, ACCOUNT_ID),
                AccountAwareStorageBackend.inMemory(ACCOUNT_ID), AccountAwareStorageBackend.inMemory(ACCOUNT_ID),
                AccountAwareStorageBackend.inMemory(ACCOUNT_ID)));
        String clusterId = limitedVirtualCluster(service, "{\"maxConcurrentJobRuns\": 5}");
        for (int i = 0; i < 10; i++) {
            startJobRun(service, clusterId);
        }

        clock.advance(Duration.ofHours(1));
        int before = runs.writes;
        service.describeVirtualCluster(clusterId);
        assertEquals(before + 1, runs.writes, "five runs completed, five started and completed");
        List<String> states = service.listJobRuns(clusterId, ListQuery.of(null, null, null, null, null), null)
                .findValuesAsText("state");
        assertEquals(List.of("COMPLETED"), states.stream().distinct().toList());
        assertEquals(before + 1, runs.writes, "a read with nothing to settle writes nothing");
    }

    // An in-memory store that counts its writes, a batch as one.
    private static final class CountingStorage extends InMemoryStorage<String, ObjectNode> {
        private int writes;
        private boolean inBatch;

        @Override
        public synchronized void put(String key, ObjectNode value) {
            if (!inBatch) {
                writes++;
            }
            super.put(key, value);
        }

        @Override
        public synchronized void putAll(Map<String, ObjectNode> entries) {
            writes++;
            inBatch = true;
            try {
                super.putAll(entries);
            } finally {
                inBatch = false;
            }
        }
    }

    /**
     * IAM's lookup reads the tags of the virtual cluster, job run or job template the ARN names, as the tag calls
     * leave them, and nothing for another account's, another Region's, an unknown or another service's ARN.
     */
    @Test
    void storedTagsAreTheTagsOfTheResourceTheArnNames() {
        // A job run that takes an hour, so it is still running when it is tagged.
        EmrContainersService service = service(3600);
        String clusterId = virtualCluster(service, EKS_CLUSTER);
        String clusterArn = service.describeVirtualCluster(clusterId).at("/virtualCluster/arn").textValue();
        service.tagResource(clusterArn, Map.of("team", "data", "env", "dev"));
        service.untagResource(clusterArn, List.of("env"));
        String runArn = startJobRun(service, clusterId).get("arn").textValue();
        service.tagResource(runArn, Map.of("team", "ops"));
        String templateArn = service.createJobTemplate(json("""
                {"name": "tagged", "clientToken": "%s", "tags": {"team": "web"}, "jobTemplateData": {
                   "executionRoleArn": "%s", "releaseLabel": "%s",
                   "jobDriver": {"sparkSubmitJobDriver": {"entryPoint": "s3://bucket/pi.py"}}}}
                """.formatted(UUID.randomUUID(), ROLE, RELEASE))).get("arn").textValue();

        assertEquals("emr-containers", service.namespace());
        assertEquals(Map.of("team", "data"), service.storedTags(clusterArn));
        assertEquals(Map.of("team", "ops"), service.storedTags(runArn));
        assertEquals(Map.of("team", "web"), service.storedTags(templateArn));
        assertTrue(service.storedTags(clusterArn.replace(ACCOUNT_ID, "111122223333")).isEmpty());
        assertTrue(service.storedTags(clusterArn.replace(REGION, "us-west-2")).isEmpty());
        assertTrue(service.storedTags(clusterArn.replace(clusterId, "0000000000000000000000000")).isEmpty());
        assertTrue(service.storedTags("arn:aws:emr-serverless:us-east-1:000000000000:/applications/abc").isEmpty());
    }

    /** A virtual cluster stored before storage was account-aware is read in place: the lookup migrates nothing. */
    @Test
    void storedTagsReadAPreAccountAwareVirtualClusterInPlace() {
        InMemoryStorage<String, ObjectNode> raw = new InMemoryStorage<>();
        EmrContainersService service = service(0, REGION, List.of(new AccountAwareStorageBackend<>(raw, null,
                ACCOUNT_ID), AccountAwareStorageBackend.inMemory(ACCOUNT_ID), AccountAwareStorageBackend.inMemory(
                ACCOUNT_ID), AccountAwareStorageBackend.inMemory(ACCOUNT_ID), AccountAwareStorageBackend.inMemory(
                ACCOUNT_ID)));
        String arn = "arn:aws:emr-containers:us-east-1:000000000000:/virtualclusters/legacy0000000000000000000";
        raw.put(arn, json("{\"arn\": \"" + arn + "\", \"tags\": {\"team\": \"data\"}}"));

        assertEquals(Map.of("team", "data"), service.storedTags(arn));
        assertEquals(Set.of(arn), raw.keys());
    }

    @Test
    void terminatingAVirtualClusterClearsItsTagsAndRefusesNewOnes() {
        EmrContainersService service = service(0);
        String clusterId = virtualCluster(service, EKS_CLUSTER);
        String arn = service.describeVirtualCluster(clusterId).at("/virtualCluster/arn").textValue();
        service.tagResource(arn, Map.of("team", "data"));
        assertEquals(Map.of("team", "data"), service.listTags(arn));

        service.deleteVirtualCluster(clusterId);

        assertEquals(Map.of(), service.listTags(arn));
        AwsException error = assertThrows(AwsException.class, () -> service.tagResource(arn, Map.of("a", "b")));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals("TERMINATED", service.describeVirtualCluster(clusterId).at("/virtualCluster/state").textValue());
    }

    @Test
    void aResourceOfAnotherRegionIsNotFoundByTheTaggingOperations() {
        List<AccountAwareStorageBackend<ObjectNode>> stores = List.of(AccountAwareStorageBackend.inMemory(ACCOUNT_ID),
                AccountAwareStorageBackend.inMemory(ACCOUNT_ID), AccountAwareStorageBackend.inMemory(ACCOUNT_ID),
                AccountAwareStorageBackend.inMemory(ACCOUNT_ID), AccountAwareStorageBackend.inMemory(ACCOUNT_ID));
        EmrContainersService east = service(0, REGION, stores);
        EmrContainersService west = service(0, "eu-west-1", stores);
        String clusterId = virtualCluster(east, EKS_CLUSTER);
        String arn = east.describeVirtualCluster(clusterId).at("/virtualCluster/arn").textValue();

        for (Runnable call : List.<Runnable>of(() -> west.listTags(arn), () -> west.tagResource(arn, Map.of("a", "b")),
                () -> west.untagResource(arn, List.of("team")))) {
            AwsException error = assertThrows(AwsException.class, call::run);
            assertEquals("ResourceNotFoundException", error.getErrorCode());
        }
        assertEquals(Map.of(), east.listTags(arn));
    }

    @Test
    void aJobRunsTemplateAndRequestTagsTogetherKeepTheTagLimit() {
        EmrContainersService service = service(0);
        String clusterId = virtualCluster(service, EKS_CLUSTER);
        ObjectNode template = json("""
                {"name": "t", "clientToken": "c", "jobTemplateData": {"executionRoleArn": "%s",
                 "releaseLabel": "%s", "jobDriver": {"sparkSqlJobDriver": {"entryPoint": "s3://b/q.sql"}}}}
                """.formatted(ROLE, RELEASE));
        ObjectNode jobTags = ((ObjectNode) template.get("jobTemplateData")).putObject("jobTags");
        ObjectNode request = json("{\"clientToken\": \"r\"}");
        ObjectNode requestTags = request.putObject("tags");
        for (int i = 0; i < 30; i++) {
            jobTags.put("template" + i, "v");
            requestTags.put("request" + i, "v");
        }
        request.put("jobTemplateId", service.createJobTemplate(template).get("id").textValue());

        AwsException error = assertThrows(AwsException.class, () -> service.startJobRun(clusterId, request));

        assertEquals("ValidationException", error.getErrorCode());
    }

    @Test
    void theAccessEntryFilterKeepsTheVirtualClustersOfEksClustersThatAdmitAccessEntries() {
        EmrContainersService service = service(0);
        String plain = virtualCluster(service, EKS_CLUSTER);
        String integrated = virtualCluster(service, ACCESS_ENTRY_CLUSTER);
        ListQuery all = ListQuery.of(null, null, null, null, null);

        JsonNode withEntries = service.listVirtualClusters(all, null, null, true).get("virtualClusters");
        JsonNode without = service.listVirtualClusters(all, null, null, false).get("virtualClusters");

        assertEquals(List.of(integrated), ids(withEntries));
        assertEquals(List.of(plain), ids(without));
        assertEquals(2, service.listVirtualClusters(all, null, null, null).get("virtualClusters").size());
    }

    private static List<String> ids(JsonNode items) {
        List<String> ids = new ArrayList<>();
        items.forEach(item -> ids.add(item.get("id").textValue()));
        return ids;
    }

    @Test
    void jobTemplateArnIsOutsideTheTaggingOperationsModel() {
        EmrContainersService service = service(0);
        String arn = service.createJobTemplate(json("""
                {"name": "t", "clientToken": "c", "jobTemplateData": {"executionRoleArn": "%s",
                 "releaseLabel": "%s", "jobDriver": {"sparkSqlJobDriver": {"entryPoint": "s3://b/q.sql"}}}}
                """.formatted(ROLE, RELEASE))).get("arn").textValue();

        AwsException error = assertThrows(AwsException.class, () -> service.tagResource(arn, Map.of("a", "b")));

        assertEquals("ValidationException", error.getErrorCode());
    }

    @Test
    void listFiltersByStateAndPagesById() {
        EmrContainersService service = service(0);
        String running = virtualCluster(service, EKS_CLUSTER);
        String terminated = virtualCluster(service, EKS_CLUSTER);
        service.deleteVirtualCluster(terminated);

        JsonNode onlyRunning = service.listVirtualClusters(
                ListQuery.of(null, null, List.of("RUNNING"), null, null), null, null, null).get("virtualClusters");
        assertEquals(1, onlyRunning.size());
        assertEquals(running, onlyRunning.get(0).get("id").textValue());

        ObjectNode first = service.listVirtualClusters(ListQuery.of(null, null, null, "1", null), null, null, null);
        assertEquals(1, first.get("virtualClusters").size());
        ObjectNode second = service.listVirtualClusters(
                ListQuery.of(null, null, null, "1", first.get("nextToken").textValue()), null, null, null);
        assertEquals(1, second.get("virtualClusters").size());
        assertFalse(second.has("nextToken"));
        assertTrue(first.at("/virtualClusters/0/id").textValue()
                .compareTo(second.at("/virtualClusters/0/id").textValue()) < 0);
    }

    @Test
    void createdAfterAndBeforeBoundTheList() {
        EmrContainersService service = service(0);
        virtualCluster(service, EKS_CLUSTER);
        clock.advance(Duration.ofHours(1));
        String later = virtualCluster(service, EKS_CLUSTER);
        Instant between = Instant.parse("2026-01-01T00:30:00Z");

        JsonNode after = service.listVirtualClusters(ListQuery.of(between.toString(), null, null, null, null),
                null, null, null).get("virtualClusters");
        JsonNode before = service.listVirtualClusters(ListQuery.of(null, between.toString(), null, null, null),
                null, null, null).get("virtualClusters");

        assertEquals(1, after.size());
        assertEquals(later, after.get(0).get("id").textValue());
        assertEquals(1, before.size());
    }

    @Test
    void managedEndpointIssuesTokensWithinTheDocumentedDuration() {
        EmrContainersService service = service(0);
        String clusterId = virtualCluster(service, EKS_CLUSTER);
        String endpointId = service.createManagedEndpoint(clusterId, json("""
                {"name": "studio", "type": "JUPYTER_ENTERPRISE_GATEWAY", "releaseLabel": "%s",
                 "executionRoleArn": "%s", "clientToken": "e1"}
                """.formatted(RELEASE, ROLE))).get("id").textValue();
        assertEquals(EmrContainersService.ENDPOINT_ID_LENGTH, endpointId.length());

        ObjectNode credentials = service.getManagedEndpointSessionCredentials(clusterId, endpointId,
                json("{\"executionRoleArn\": \"%s\", \"credentialType\": \"TOKEN\"}".formatted(ROLE)));
        assertFalse(credentials.at("/credentials/token").textValue().isEmpty());
        assertEquals(credentials.get("credentials"), credentials.get("endpointCredentials"));
        assertEquals("2026-01-01T00:15:00Z", credentials.get("expiresAt").textValue(),
                "the API reference: the default duration is 15 minutes");

        AwsException tooLong = assertThrows(AwsException.class, () -> service.getManagedEndpointSessionCredentials(
                clusterId, endpointId, json("""
                        {"executionRoleArn": "%s", "credentialType": "TOKEN", "durationInSeconds": 43201}
                        """.formatted(ROLE))));
        assertEquals("ValidationException", tooLong.getErrorCode());

        service.deleteManagedEndpoint(clusterId, endpointId);
        assertEquals("TERMINATED",
                service.describeManagedEndpoint(clusterId, endpointId).at("/endpoint/state").textValue());
        assertThrows(AwsException.class, () -> service.getManagedEndpointSessionCredentials(clusterId, endpointId,
                json("{\"executionRoleArn\": \"%s\", \"credentialType\": \"TOKEN\"}".formatted(ROLE))));
    }

    @Test
    void virtualClusterNamingAnUnknownSecurityConfigurationIsRefused() {
        EmrContainersService service = service(0);
        String body = """
                {"name": "vc", "clientToken": "%s", "securityConfigurationId": "%s",
                 "containerProvider": {"type": "EKS", "id": "analytics"}}
                """;

        AwsException error = assertThrows(AwsException.class,
                () -> service.createVirtualCluster(json(body.formatted("a", "nosuchconfig"))));
        assertEquals("ResourceNotFoundException", error.getErrorCode());

        String configurationId = service.createSecurityConfiguration(json("""
                {"name": "sc", "clientToken": "s1", "securityConfigurationData": {}}
                """)).get("id").textValue();
        String clusterId = service.createVirtualCluster(json(body.formatted("b", configurationId)))
                .get("id").textValue();
        assertEquals(configurationId, service.describeVirtualCluster(clusterId)
                .at("/virtualCluster/securityConfigurationId").textValue());
    }

    @Test
    void modelConstraintsAreCheckedBeforeAnythingIsStored() {
        EmrContainersService service = service(0);

        AwsException badName = assertThrows(AwsException.class, () -> service.createVirtualCluster(json("""
                {"name": "bad name", "clientToken": "c", "containerProvider": {"type": "EKS", "id": "analytics"}}
                """)));
        AwsException badType = assertThrows(AwsException.class, () -> service.createVirtualCluster(json("""
                {"name": "vc", "clientToken": "c", "containerProvider": {"type": "ECS", "id": "analytics"}}
                """)));
        AwsException badId = assertThrows(AwsException.class, () -> service.describeVirtualCluster("UPPER"));

        assertEquals("ValidationException", badName.getErrorCode());
        assertEquals("ValidationException", badType.getErrorCode());
        assertEquals("ValidationException", badId.getErrorCode());
        assertEquals(0, service.listVirtualClusters(ListQuery.of(null, null, null, null, null), null, null, null)
                .get("virtualClusters").size());
    }

    @Test
    void clearRemovesEveryResource() {
        EmrContainersService service = service(0);
        String clusterId = virtualCluster(service, EKS_CLUSTER);
        startJobRun(service, clusterId);

        service.clear();

        assertThrows(AwsException.class, () -> service.describeVirtualCluster(clusterId));
    }

    // A docker runner mode service whose run threads the test runs, by runAll(), over these stores.
    private EmrContainersService dockerService(FakeRunner runner, QueuedExecutor executor,
                                               List<AccountAwareStorageBackend<ObjectNode>> stores) {
        IamService iam = mock(IamService.class);
        when(iam.resolveCallerArn(eq(null))).thenReturn(Optional.empty());
        return new EmrContainersService(stores.get(0), stores.get(1), stores.get(2), stores.get(3), stores.get(4),
                mapper, new RegionResolver(REGION, ACCOUNT_ID), eks, iam, null, clock, 0, runner, executor,
                "registry.internal/emr-on-eks/spark/%s", "floci-net", delays, null);
    }

    private EmrContainersService dockerService(FakeRunner runner, QueuedExecutor executor) {
        return dockerService(runner, executor, stores());
    }

    private static List<AccountAwareStorageBackend<ObjectNode>> stores() {
        return List.of(AccountAwareStorageBackend.inMemory(ACCOUNT_ID), AccountAwareStorageBackend.inMemory(ACCOUNT_ID),
                AccountAwareStorageBackend.inMemory(ACCOUNT_ID), AccountAwareStorageBackend.inMemory(ACCOUNT_ID),
                AccountAwareStorageBackend.inMemory(ACCOUNT_ID));
    }

    private static String state(JsonNode run) {
        return run.get("state").textValue();
    }

    @Test
    void dockerRunIsSubmittedThenRunningWhileItsDriverRunsThenCompleted() {
        FakeRunner runner = new FakeRunner();
        QueuedExecutor executor = new QueuedExecutor();
        EmrContainersService service = dockerService(runner, executor);
        String clusterId = virtualCluster(service, EKS_CLUSTER);
        String id = startJobRun(service, clusterId).get("id").textValue();

        assertEquals("SUBMITTED", state(jobRun(service, clusterId, id)), "submitted while its container starts");
        List<String> whileRunning = new ArrayList<>();
        runner.whileRunning = () -> whileRunning.add(state(jobRun(service, clusterId, id)));
        clock.advance(Duration.ofSeconds(42));
        executor.runAll();

        assertEquals(List.of("RUNNING"), whileRunning);
        JsonNode run = jobRun(service, clusterId, id);
        assertEquals("COMPLETED", state(run));
        assertEquals("2026-01-01T00:00:42Z", run.get("finishedAt").textValue());
        assertFalse(run.has("failureReason"));
        SparkJobRunner.Job job = runner.jobs.getFirst();
        assertEquals(id, job.runId());
        assertEquals("registry.internal/emr-on-eks/spark/emr-7.5.0:latest", job.imageTemplate());
        assertEquals("floci-net", job.dockerNetwork());
        assertEquals(ROLE, job.executionRole());
        assertEquals(List.of("s3://bucket/pi.py"), job.application());
    }

    @Test
    void dockerRunWhoseDriverExitsWithAnErrorFailsAsAUserError() {
        FakeRunner runner = new FakeRunner();
        runner.outcome = SparkJobRunner.Outcome.exitedWith(1);
        QueuedExecutor executor = new QueuedExecutor();
        EmrContainersService service = dockerService(runner, executor);
        String clusterId = virtualCluster(service, EKS_CLUSTER);
        String id = startJobRun(service, clusterId).get("id").textValue();

        executor.runAll();

        JsonNode run = jobRun(service, clusterId, id);
        assertEquals("FAILED", state(run));
        assertEquals("USER_ERROR", run.get("failureReason").textValue());
        assertEquals("Jobrun failed. Main Spark container terminated with errors. Please refer logs uploaded to "
                + "S3/CloudWatch based on your monitoring configuration.", run.get("stateDetails").textValue());
    }

    @Test
    void dockerRunWhoseDriverCannotStartFailsAsAnInternalError() {
        FakeRunner runner = new FakeRunner();
        runner.outcome = SparkJobRunner.Outcome.couldNotStart("Could not pull image");
        QueuedExecutor executor = new QueuedExecutor();
        EmrContainersService service = dockerService(runner, executor);
        String clusterId = virtualCluster(service, EKS_CLUSTER);
        String id = startJobRun(service, clusterId).get("id").textValue();

        executor.runAll();

        JsonNode run = jobRun(service, clusterId, id);
        assertEquals("FAILED", state(run));
        assertEquals("INTERNAL_ERROR", run.get("failureReason").textValue());
        assertEquals("JobRun submission failed due to an internal error. Floci could not start its driver: "
                + "Could not pull image", run.get("stateDetails").textValue());
    }

    @Test
    void cancellingARunningDockerRunStopsItsDriverThenCancelsIt() {
        FakeRunner runner = new FakeRunner();
        QueuedExecutor executor = new QueuedExecutor();
        EmrContainersService service = dockerService(runner, executor);
        String clusterId = virtualCluster(service, EKS_CLUSTER);
        String id = startJobRun(service, clusterId).get("id").textValue();
        List<String> afterCancel = new ArrayList<>();
        runner.whileRunning = () -> {
            service.cancelJobRun(clusterId, id);
            afterCancel.add(state(jobRun(service, clusterId, id)));
            // The stop the cancel handed off, and the cancel again, which changes nothing.
            executor.runAll();
            service.cancelJobRun(clusterId, id);
            afterCancel.add(state(jobRun(service, clusterId, id)));
        };

        executor.runAll();

        assertEquals(List.of("CANCEL_PENDING", "CANCEL_PENDING"), afterCancel);
        assertEquals(Set.of(id), runner.stopped);
        assertEquals("CANCELLED", state(jobRun(service, clusterId, id)));
        AwsException again = assertThrows(AwsException.class, () -> service.cancelJobRun(clusterId, id));
        assertEquals("ValidationException", again.getErrorCode());
    }

    @Test
    void dockerRunCancelledBeforeItsDriverStartsNeverStartsIt() {
        FakeRunner runner = new FakeRunner();
        QueuedExecutor executor = new QueuedExecutor();
        EmrContainersService service = dockerService(runner, executor);
        String clusterId = virtualCluster(service, EKS_CLUSTER);
        String id = startJobRun(service, clusterId).get("id").textValue();

        service.cancelJobRun(clusterId, id);
        executor.runAll();

        assertTrue(runner.driversStarted.isEmpty());
        assertEquals("CANCELLED", state(jobRun(service, clusterId, id)));
    }

    @Test
    void dockerRunHoldsItsRunningSlotFromSubmissionUntilItsDriverEnds() {
        FakeRunner runner = new FakeRunner();
        QueuedExecutor executor = new QueuedExecutor();
        EmrContainersService service = dockerService(runner, executor);
        String clusterId = limitedVirtualCluster(service, "{\"maxConcurrentJobRuns\": 1}");
        String first = startJobRun(service, clusterId).get("id").textValue();
        String second = startJobRun(service, clusterId).get("id").textValue();

        assertEquals("SUBMITTED", state(jobRun(service, clusterId, first)));
        assertEquals("PENDING", state(jobRun(service, clusterId, second)), "the submitted run holds the only slot");
        assertEquals(1, executor.queued(), "only the first run's container starts");

        executor.runNext();

        assertEquals("COMPLETED", state(jobRun(service, clusterId, first)));
        assertEquals("SUBMITTED", state(jobRun(service, clusterId, second)), "the freed slot goes to the queue");
        executor.runAll();
        assertEquals("COMPLETED", state(jobRun(service, clusterId, second)));
        assertEquals(List.of(first, second), runner.driversStarted);
    }

    @Test
    void cancellingAQueuedDockerRunCancelsItAtOnce() {
        FakeRunner runner = new FakeRunner();
        QueuedExecutor executor = new QueuedExecutor();
        EmrContainersService service = dockerService(runner, executor);
        String clusterId = limitedVirtualCluster(service, "{\"maxConcurrentJobRuns\": 1}");
        startJobRun(service, clusterId);
        String queued = startJobRun(service, clusterId).get("id").textValue();

        service.cancelJobRun(clusterId, queued);

        assertEquals("CANCELLED", state(jobRun(service, clusterId, queued)));
        assertTrue(runner.stopped.isEmpty());
    }

    @Test
    void dockerRunAnEarlierProcessLeftRunningHasFailed() {
        List<AccountAwareStorageBackend<ObjectNode>> stores = stores();
        EmrContainersService earlier = dockerService(new FakeRunner(), new QueuedExecutor(), stores);
        String clusterId = virtualCluster(earlier, EKS_CLUSTER);
        String id = startJobRun(earlier, clusterId).get("id").textValue();

        FakeRunner runner = new FakeRunner();
        QueuedExecutor executor = new QueuedExecutor();
        EmrContainersService restarted = dockerService(runner, executor, stores);
        JsonNode run = jobRun(restarted, clusterId, id);

        assertEquals("FAILED", state(run));
        assertEquals("INTERNAL_ERROR", run.get("failureReason").textValue());
        assertEquals("Trouble monitoring your JobRun due to an internal error", run.get("stateDetails").textValue());
        assertTrue(runner.leftoversRemoved.isEmpty(), "removed off the request's thread");
        executor.runAll();
        assertEquals(List.of(id), runner.leftoversRemoved, "a driver the startup's cleanup left goes");
    }

    @Test
    void dockerRunsThreadFromBeforeAResetWritesNothingBack() {
        FakeRunner runner = new FakeRunner();
        QueuedExecutor executor = new QueuedExecutor();
        EmrContainersService service = dockerService(runner, executor);
        String clusterId = virtualCluster(service, EKS_CLUSTER);
        startJobRun(service, clusterId);

        service.beforeReset();
        service.clear();
        String after = virtualCluster(service, EKS_CLUSTER);
        executor.runAll();

        assertEquals(1, runner.jobs.size(), "its thread still ran");
        assertTrue(service.listJobRuns(after, ListQuery.of(null, null, null, null, null), null)
                .get("jobRuns").isEmpty());
        assertThrows(AwsException.class, () -> service.describeVirtualCluster(clusterId));
    }

    @Test
    void dockerRunWhoseJobDriverNamesNoEntryPointFailsItsSubmission() {
        FakeRunner runner = new FakeRunner();
        QueuedExecutor executor = new QueuedExecutor();
        EmrContainersService service = dockerService(runner, executor);
        String clusterId = virtualCluster(service, EKS_CLUSTER);

        String id = service.startJobRun(clusterId, json("""
                {"clientToken": "sql", "executionRoleArn": "%s", "releaseLabel": "%s",
                 "jobDriver": {"sparkSqlJobDriver": {"sparkSqlParameters": "--conf a=b"}}}
                """.formatted(ROLE, RELEASE))).get("id").textValue();

        JsonNode run = jobRun(service, clusterId, id);
        assertEquals("FAILED", state(run));
        assertEquals("VALIDATION_ERROR", run.get("failureReason").textValue());
        assertEquals("JobRun submission failed.", run.get("stateDetails").textValue(),
                "kept FAILED, as AWS's common errors page gives a run whose parameters are not valid, not refused");
        assertEquals(0, executor.queued(), "no container starts");
    }

    @Test
    void dockerRunWhoseContainerStartsCountsAsConcurrentNotQueued() {
        FakeRunner runner = new FakeRunner();
        QueuedExecutor executor = new QueuedExecutor();
        EmrContainersService service = dockerService(runner, executor);
        // The model's least queue depth.
        String clusterId = limitedVirtualCluster(service,
                "{\"maxConcurrentJobRuns\": 1, \"maxInQueueJobRuns\": 100}");
        String first = startJobRun(service, clusterId).get("id").textValue();

        assertEquals(1, schedulerStatus(service, clusterId).get("currentConcurrentJobRuns").intValue());
        assertEquals(0, schedulerStatus(service, clusterId).get("currentInQueueJobRuns").intValue());
        String last = null;
        for (int i = 0; i < 100; i++) {
            last = startJobRun(service, clusterId).get("id").textValue();
        }
        assertEquals("PENDING", state(jobRun(service, clusterId, last)), "the queue's hundredth place is free");
        assertEquals(100, schedulerStatus(service, clusterId).get("currentInQueueJobRuns").intValue());
        AwsException full = assertThrows(AwsException.class, () -> startJobRun(service, clusterId));
        assertEquals("ValidationException", full.getErrorCode());

        service.cancelJobRun(clusterId, first);
        assertEquals("CANCEL_PENDING", state(jobRun(service, clusterId, first)));
        assertEquals(1, schedulerStatus(service, clusterId).get("currentConcurrentJobRuns").intValue(),
                "its container still runs");
    }

    @Test
    void dockerRunThatCannotBeHandedToAThreadFailsAndFreesItsSlot() {
        FakeRunner runner = new FakeRunner();
        QueuedExecutor executor = new QueuedExecutor();
        EmrContainersService service = dockerService(runner, executor);
        String clusterId = limitedVirtualCluster(service, "{\"maxConcurrentJobRuns\": 1}");
        String first = startJobRun(service, clusterId).get("id").textValue();
        String second = startJobRun(service, clusterId).get("id").textValue();
        String third = startJobRun(service, clusterId).get("id").textValue();

        executor.rejecting = true;
        executor.runNext();

        assertEquals("COMPLETED", state(jobRun(service, clusterId, first)));
        for (String refused : List.of(second, third)) {
            JsonNode run = jobRun(service, clusterId, refused);
            assertEquals("FAILED", state(run), "its slot then went to the next");
            assertEquals("INTERNAL_ERROR", run.get("failureReason").textValue());
            assertEquals("JobRun submission failed due to an internal error. Floci could not start its driver: "
                    + "shutting down", run.get("stateDetails").textValue());
        }
    }

    @Test
    void dockerRunsDriverLogsGoUnderASparkApplicationIdOfItsOwn() {
        FakeRunner runner = new FakeRunner();
        QueuedExecutor executor = new QueuedExecutor();
        EmrContainersService service = dockerService(runner, executor);
        String clusterId = virtualCluster(service, EKS_CLUSTER);
        String id = service.startJobRun(clusterId, json("""
                {"clientToken": "logs", "executionRoleArn": "%s", "releaseLabel": "%s",
                 "jobDriver": {"sparkSubmitJobDriver": {"entryPoint": "s3://bucket/pi.py"}},
                 "configurationOverrides": {"monitoringConfiguration": {
                   "s3MonitoringConfiguration": {"logUri": "s3://logs"},
                   "cloudWatchMonitoringConfiguration": {"logGroupName": "jobs", "logStreamNamePrefix": "p"}}}}
                """.formatted(ROLE, RELEASE))).get("id").textValue();

        executor.runAll();

        SparkJobRunner.Job job = runner.jobs.getFirst();
        String driver = clusterId + "/jobs/" + id + "/containers/spark-[0-9a-f]{32}/spark-" + id + "-driver/";
        assertTrue(job.logFolder().matches("s3://logs/" + driver), job.logFolder());
        assertEquals("jobs", job.cloudWatchLogs().logGroup());
        assertTrue(job.cloudWatchLogs().stdoutStream().matches("p/" + driver + "stdout"));
        assertEquals(job.cloudWatchLogs().stdoutStream().replace("/stdout", "/stderr"),
                job.cloudWatchLogs().stderrStream());
        assertTrue(job.logFolder().endsWith(job.cloudWatchLogs().stdoutStream().substring(2).replace("stdout", "")),
                "the same Spark application id names both");
    }

    private String startRetriedJobRun(EmrContainersService service, String clusterId, String retryPolicy) {
        return service.startJobRun(clusterId, json("""
                {"clientToken": "%s", "executionRoleArn": "%s", "releaseLabel": "%s",
                 "jobDriver": {"sparkSubmitJobDriver": {"entryPoint": "s3://bucket/pi.py"}},
                 "retryPolicyConfiguration": %s,
                 "configurationOverrides": {"monitoringConfiguration": {
                   "s3MonitoringConfiguration": {"logUri": "s3://logs"}}}}
                """.formatted(UUID.randomUUID(), ROLE, RELEASE, retryPolicy))).get("id").textValue();
    }

    @Test
    void dockerRunWhoseDriverFailsIsRetriedAfterAnExponentialBackoffUntilItsAttemptsRunOut() {
        FakeRunner runner = new FakeRunner();
        runner.outcome = SparkJobRunner.Outcome.exitedWith(1);
        QueuedExecutor executor = new QueuedExecutor();
        EmrContainersService service = dockerService(runner, executor);
        String clusterId = virtualCluster(service, EKS_CLUSTER);
        String id = startRetriedJobRun(service, clusterId, "{\"maxAttempts\": 3}");

        executor.runAll();

        JsonNode waiting = jobRun(service, clusterId, id);
        assertEquals("SUBMITTED", state(waiting), "between its attempts");
        assertEquals(1, waiting.at("/retryPolicyExecution/currentAttemptCount").intValue());
        assertEquals(List.of(Duration.ofSeconds(10)), delays.delays);

        delays.runAll();
        executor.runAll();
        assertEquals(2, jobRun(service, clusterId, id).at("/retryPolicyExecution/currentAttemptCount").intValue());
        assertEquals(List.of(Duration.ofSeconds(10), Duration.ofSeconds(20)), delays.delays);
        delays.runAll();
        executor.runAll();

        JsonNode run = jobRun(service, clusterId, id);
        assertEquals("FAILED", state(run));
        assertEquals("USER_ERROR", run.get("failureReason").textValue());
        assertEquals(3, run.at("/retryPolicyExecution/currentAttemptCount").intValue());
        assertEquals(3, run.at("/retryPolicyConfiguration/maxAttempts").intValue());
        assertEquals(3, runner.jobs.size(), "a driver for each attempt");
        assertEquals(2, delays.delays.size(), "no backoff after the last");
        List<String> folders = runner.jobs.stream().map(SparkJobRunner.Job::logFolder).toList();
        for (String folder : folders) {
            assertTrue(folder.matches("s3://logs/" + clusterId + "/jobs/" + id
                    + "/containers/spark-[0-9a-f]{32}/spark-" + id + "-driver-[0-9a-z]{5}/"), folder);
        }
        assertEquals(3, Set.copyOf(folders).size(), "each attempt's driver pod has a suffix of its own");
        assertEquals(1, folders.stream().map(folder -> folder.split("/")[7]).distinct().count(),
                "a retry restarts the driver of the same Spark application");
    }

    @Test
    void dockerRunStoredWithoutItsApplicationIdKeepsTheOneItsFirstAttemptGets() {
        List<AccountAwareStorageBackend<ObjectNode>> stores = stores();
        FakeRunner runner = new FakeRunner();
        runner.outcome = SparkJobRunner.Outcome.exitedWith(1);
        QueuedExecutor executor = new QueuedExecutor();
        EmrContainersService service = dockerService(runner, executor, stores);
        String clusterId = limitedVirtualCluster(service, "{\"maxConcurrentJobRuns\": 1}");
        startJobRun(service, clusterId);
        String id = startRetriedJobRun(service, clusterId, "{\"maxAttempts\": 2}");
        // Queued as a run stored before runs kept their application's id was.
        String arn = jobRun(service, clusterId, id).get("arn").textValue();
        ObjectNode stored = stores.get(1).get(arn).orElseThrow().deepCopy();
        stored.remove("_sparkApplicationId");
        stores.get(1).put(arn, stored);

        executor.runAll();
        delays.runAll();
        executor.runAll();

        List<SparkJobRunner.Job> attempts = runner.jobs.stream().filter(job -> job.runId().equals(id)).toList();
        assertEquals(2, attempts.size());
        assertEquals(attempts.get(0).logFolder().split("/")[7], attempts.get(1).logFolder().split("/")[7]);
        assertEquals("FAILED", state(jobRun(service, clusterId, id)));
    }

    @Test
    void dockerRunAnEarlierProcessLeftBetweenItsAttemptsWaitsOutItsBackoffAndRetries() {
        List<AccountAwareStorageBackend<ObjectNode>> stores = stores();
        FakeRunner failing = new FakeRunner();
        failing.outcome = SparkJobRunner.Outcome.exitedWith(1);
        QueuedExecutor earlierExecutor = new QueuedExecutor();
        EmrContainersService earlier = dockerService(failing, earlierExecutor, stores);
        String clusterId = virtualCluster(earlier, EKS_CLUSTER);
        String id = startRetriedJobRun(earlier, clusterId, "{\"maxAttempts\": 2}");
        earlierExecutor.runAll();
        assertEquals(List.of(Duration.ofSeconds(10)), delays.delays);
        // The earlier process stops 4 seconds into the backoff, its timer with it.
        delays.forget();
        clock.advance(Duration.ofSeconds(4));

        FakeRunner runner = new FakeRunner();
        QueuedExecutor executor = new QueuedExecutor();
        EmrContainersService restarted = dockerService(runner, executor, stores);
        JsonNode waiting = jobRun(restarted, clusterId, id);

        assertEquals("SUBMITTED", state(waiting), "not a run left running");
        assertEquals(1, waiting.at("/retryPolicyExecution/currentAttemptCount").intValue());
        assertEquals(List.of(Duration.ofSeconds(6)), delays.delays, "the rest of its backoff");
        delays.runAll();
        executor.runAll();
        JsonNode run = jobRun(restarted, clusterId, id);
        assertEquals("COMPLETED", state(run));
        assertEquals(2, run.at("/retryPolicyExecution/currentAttemptCount").intValue());
        assertEquals(1, runner.jobs.size());
        assertTrue(runner.leftoversRemoved.isEmpty());
        assertEquals(failing.jobs.get(0).logFolder().split("/")[7], runner.jobs.get(0).logFolder().split("/")[7],
                "the same Spark application");
    }

    @Test
    void dockerRunWhoseRetriedDriverSucceedsCompletes() {
        FakeRunner runner = new FakeRunner();
        runner.outcome = SparkJobRunner.Outcome.exitedWith(1);
        QueuedExecutor executor = new QueuedExecutor();
        EmrContainersService service = dockerService(runner, executor);
        String clusterId = virtualCluster(service, EKS_CLUSTER);
        String id = startRetriedJobRun(service, clusterId, "{\"maxAttempts\": 5}");
        executor.runAll();

        runner.outcome = SparkJobRunner.Outcome.exitedWith(0);
        delays.runAll();
        executor.runAll();

        JsonNode run = jobRun(service, clusterId, id);
        assertEquals("COMPLETED", state(run));
        assertEquals(2, run.at("/retryPolicyExecution/currentAttemptCount").intValue());
    }

    @Test
    void dockerRunCancelledBetweenItsAttemptsIsCancelledAtOnce() {
        FakeRunner runner = new FakeRunner();
        runner.outcome = SparkJobRunner.Outcome.exitedWith(1);
        QueuedExecutor executor = new QueuedExecutor();
        EmrContainersService service = dockerService(runner, executor);
        String clusterId = limitedVirtualCluster(service, "{\"maxConcurrentJobRuns\": 1}");
        String id = startRetriedJobRun(service, clusterId, "{\"maxAttempts\": 2}");
        String queued = startJobRun(service, clusterId).get("id").textValue();
        executor.runAll();
        assertEquals("PENDING", state(jobRun(service, clusterId, queued)), "the retried run keeps its slot");

        service.cancelJobRun(clusterId, id);

        assertEquals("CANCELLED", state(jobRun(service, clusterId, id)));
        assertEquals("SUBMITTED", state(jobRun(service, clusterId, queued)), "the freed slot goes to the queue");
        delays.runAll();
        executor.runAll();
        assertEquals("CANCELLED", state(jobRun(service, clusterId, id)), "its backoff's end starts nothing");
        assertEquals(1, runner.jobs.stream().filter(job -> job.runId().equals(id)).count());
    }

    @Test
    void retryPolicyOfOneAttemptIsNotShownAndAnInvalidOneIsRefused() {
        EmrContainersService service = service(0);
        String clusterId = virtualCluster(service, EKS_CLUSTER);

        JsonNode disabled = jobRun(service, clusterId, startRetriedJobRun(service, clusterId, "{\"maxAttempts\": 1}"));
        JsonNode enabled = jobRun(service, clusterId, startRetriedJobRun(service, clusterId, "{\"maxAttempts\": 2}"));

        assertFalse(disabled.has("retryPolicyConfiguration"), "not visible when retry policy is disabled");
        assertFalse(disabled.has("retryPolicyExecution"));
        assertEquals(2, enabled.at("/retryPolicyConfiguration/maxAttempts").intValue());
        assertEquals(1, enabled.at("/retryPolicyExecution/currentAttemptCount").intValue());
        for (String invalid : List.of("0", "-1")) {
            AwsException refused = assertThrows(AwsException.class,
                    () -> startRetriedJobRun(service, clusterId, "{\"maxAttempts\": " + invalid + "}"), invalid);
            assertEquals("ValidationException", refused.getErrorCode());
            assertEquals("Retry policy configuration's parameter value of maxAttempts is invalid",
                    refused.getMessage());
        }
        // Not a 32-bit integer at all, which the model's JavaInteger refuses first.
        for (String invalid : List.of("2147483648", "\"2\"")) {
            AwsException refused = assertThrows(AwsException.class,
                    () -> startRetriedJobRun(service, clusterId, "{\"maxAttempts\": " + invalid + "}"), invalid);
            assertEquals("ValidationException", refused.getErrorCode());
        }
    }

    @Test
    void retryBackoffDoublesFromTenSecondsToSixMinutes() {
        assertEquals(Duration.ofSeconds(10), EmrContainersService.retryDelay(1));
        assertEquals(Duration.ofSeconds(20), EmrContainersService.retryDelay(2));
        assertEquals(Duration.ofSeconds(40), EmrContainersService.retryDelay(3));
        assertEquals(Duration.ofSeconds(320), EmrContainersService.retryDelay(6));
        assertEquals(Duration.ofMinutes(6), EmrContainersService.retryDelay(7));
        assertEquals(Duration.ofMinutes(6), EmrContainersService.retryDelay(Integer.MAX_VALUE));
    }

    @Test
    void monitoringDestinationWithoutItsLogGroupOrUriIsRefusedFromATemplateAsFromARequest() {
        EmrContainersService service = service(0);
        String clusterId = virtualCluster(service, EKS_CLUSTER);
        // The template's parametric model lets the destination leave its log group out.
        String templateId = service.createJobTemplate(json("""
                {"name": "t", "clientToken": "%s", "jobTemplateData": {
                   "executionRoleArn": "%s", "releaseLabel": "%s",
                   "jobDriver": {"sparkSubmitJobDriver": {"entryPoint": "s3://bucket/pi.py"}},
                   "configurationOverrides": {"monitoringConfiguration": {
                     "cloudWatchMonitoringConfiguration": {"logStreamNamePrefix": "p"}}}}}
                """.formatted(UUID.randomUUID(), ROLE, RELEASE))).get("id").textValue();

        AwsException fromTemplate = assertThrows(AwsException.class, () -> service.startJobRun(clusterId, json("""
                {"clientToken": "%s", "jobTemplateId": "%s"}
                """.formatted(UUID.randomUUID(), templateId))));
        AwsException fromRequest = assertThrows(AwsException.class, () -> service.startJobRun(clusterId, json("""
                {"clientToken": "%s", "executionRoleArn": "%s", "releaseLabel": "%s",
                 "jobDriver": {"sparkSubmitJobDriver": {"entryPoint": "s3://bucket/pi.py"}},
                 "configurationOverrides": {"monitoringConfiguration": {"s3MonitoringConfiguration": {}}}}
                """.formatted(UUID.randomUUID(), ROLE, RELEASE))));

        assertEquals("ValidationException", fromTemplate.getErrorCode());
        assertTrue(fromTemplate.getMessage().contains(
                "monitoringConfiguration.cloudWatchMonitoringConfiguration.logGroupName"), fromTemplate.getMessage());
        assertEquals("ValidationException", fromRequest.getErrorCode());
        assertTrue(fromRequest.getMessage().contains("logUri"), fromRequest.getMessage());
        assertTrue(service.listJobRuns(clusterId, ListQuery.of(null, null, null, null, null), null)
                .get("jobRuns").isEmpty(), "neither run is stored");
    }

    @Test
    void immediateModeNeverStartsAContainer() {
        EmrContainersService service = service(0);
        String clusterId = virtualCluster(service, EKS_CLUSTER);
        String id = startJobRun(service, clusterId).get("id").textValue();

        assertEquals("COMPLETED", state(jobRun(service, clusterId, id)));
    }

    /** Waits each backoff until the test ends them all, on the test's thread. */
    private static final class QueuedDelays implements EmrContainersService.Delays {
        final List<Duration> delays = new ArrayList<>();
        private final Deque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void after(Duration delay, Runnable task) {
            delays.add(delay);
            tasks.add(task);
        }

        void runAll() {
            while (!tasks.isEmpty()) {
                tasks.removeFirst().run();
            }
        }

        // The backoffs waited for so far go, as a process's timer goes with it.
        void forget() {
            delays.clear();
            tasks.clear();
        }
    }

    /** Runs what the service hands its run threads only when the test says so, on the test's thread. */
    private static final class QueuedExecutor implements Executor {
        private final Deque<Runnable> tasks = new ArrayDeque<>();
        // Refuses every task from then on, as an executor that is shut down does.
        boolean rejecting;

        @Override
        public void execute(Runnable task) {
            if (rejecting) {
                throw new RejectedExecutionException("shutting down");
            }
            tasks.add(task);
        }

        int queued() {
            return tasks.size();
        }

        void runNext() {
            tasks.removeFirst().run();
        }

        void runAll() {
            while (!tasks.isEmpty()) {
                runNext();
            }
        }
    }

    // ── Managed endpoints' gateways ───────────────────────────────────────────────

    // Why the gateways' probe finds none answering, until a test sets it to null.
    private final AtomicReference<String> gatewayUnanswered = new AtomicReference<>();

    // A docker service whose endpoints' gateways run on threads of their own, Floci at http://floci.test:4566/.
    private EmrContainersService gatewayService(GatewayRunner runner,
                                                List<AccountAwareStorageBackend<ObjectNode>> stores) {
        IamService iam = mock(IamService.class);
        when(iam.resolveCallerArn(eq(null))).thenReturn(Optional.empty());
        // A pool, as the service's own is, so that its shutdown interrupts the gateways' threads.
        SessionServers servers = new SessionServers(EmrContainersGateways.KIND, runner,
                Executors.newCachedThreadPool(Thread.ofPlatform().daemon().factory()), null,
                (host, port) -> gatewayUnanswered.get(), clock);
        return new EmrContainersService(stores.get(0), stores.get(1), stores.get(2), stores.get(3), stores.get(4),
                mapper, new RegionResolver(REGION, ACCOUNT_ID), eks, iam, null, clock, 0, runner, Runnable::run,
                null, "floci-net", delays, new EmrContainersService.EndpointGateways(servers,
                        "registry.internal/emr-on-eks/notebook-spark/%s", "http://floci.test:4566/"));
    }

    private String gatewayEndpoint(EmrContainersService service, String clusterId) {
        return service.createManagedEndpoint(clusterId, json("""
                {"name": "studio", "type": "JUPYTER_ENTERPRISE_GATEWAY", "releaseLabel": "%s",
                 "executionRoleArn": "%s", "clientToken": "%s",
                 "configurationOverrides": {"applicationConfiguration": [{"classification": "spark-defaults",
                   "properties": {"spark.sql.shuffle.partitions": "4", "spark.master": "k8s://https://eks.test:443",
                     "spark.submit.deployMode": "cluster",
                     "spark.kubernetes.driverEnv.SPARK_DIST_CLASSPATH": "/opt/extra/*"}},
                  {"classification": "jeg-config", "properties": {
                     "RemoteMappingKernelManager.cull_idle_timeout": "3600",
                     "RemoteMappingKernelManager.cull_interval": "60",
                     "RemoteMappingKernelManager.cull_connected": "true"}}]}}
                """.formatted(RELEASE, ROLE, UUID.randomUUID()))).get("id").textValue();
    }

    private static JsonNode endpoint(EmrContainersService service, String clusterId, String endpointId) {
        return service.describeManagedEndpoint(clusterId, endpointId).get("endpoint");
    }

    // The endpoint once it is in that state, which a gateway's thread puts it in.
    private static JsonNode awaitEndpoint(EmrContainersService service, String clusterId, String endpointId,
                                          String state) {
        return await("endpoint " + endpointId + " " + state).atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(10))
                .until(() -> endpoint(service, clusterId, endpointId), endpoint -> state.equals(state(endpoint)));
    }

    private String sessionToken(EmrContainersService service, String clusterId, String endpointId) {
        return service.getManagedEndpointSessionCredentials(clusterId, endpointId,
                json("{\"executionRoleArn\": \"%s\", \"credentialType\": \"TOKEN\"}".formatted(ROLE)))
                .at("/credentials/token").textValue();
    }

    @Test
    void dockerEndpointRunsItsGatewayAndIsActiveWithItsServerUrlOnceTheGatewayAnswers() {
        GatewayRunner runner = new GatewayRunner();
        gatewayUnanswered.set("not yet");
        EmrContainersService service = gatewayService(runner, stores());
        String clusterId = virtualCluster(service, EKS_CLUSTER);
        String endpointId = gatewayEndpoint(service, clusterId);

        JsonNode creating = endpoint(service, clusterId, endpointId);
        assertEquals("CREATING", state(creating), "Initially, your endpoint is in the CREATING state");
        assertFalse(creating.has("serverUrl"));
        SparkJobRunner.Job job = runner.awaitJob(endpointId);
        assertEquals("registry.internal/emr-on-eks/notebook-spark/emr-7.5.0:latest", job.imageTemplate());
        assertEquals(EmrContainersGateways.PORT, job.listenPort());
        assertEquals(ROLE, job.executionRole());
        assertEquals("floci-net", job.dockerNetwork());
        assertEquals("4", job.sparkDefaults().get("spark.sql.shuffle.partitions"));
        assertEquals("false", job.sparkDefaults().get(EmrContainersGateways.PYSPARK_VIRTUALENV),
                "a kernel in Spark's local mode runs on the image's Python");
        assertFalse(job.sparkDefaults().containsKey("spark.master"), "the runner's local mode stands");
        assertEquals("client", job.sparkDefaults().get("spark.submit.deployMode"));
        assertEquals("/opt/extra/*:" + EmrContainersGateways.AWS_SDK,
                job.environment().get(EmrContainersGateways.CLASS_PATH_VARIABLE),
                "a kernel's driver keeps the endpoint's class path and gains S3A's AWS SDK");
        assertEquals(EmrContainersGateways.PROGRAM, job.environment().get(EmrContainersGateways.PROGRAM_VARIABLE));
        assertTrue(job.command().get(2).endsWith("exec python3 /tmp/floci-gateway/gateway.py \"$@\""),
                job.command().get(2));
        // The guide's "Jupyter Enterprise Gateway (JEG) configuration options" allow-lists the first two.
        assertEquals(List.of("gateway", "--RemoteMappingKernelManager.cull_idle_timeout=3600",
                "--RemoteMappingKernelManager.cull_interval=60"), job.command().subList(3, job.command().size()));

        clock.advance(Duration.ofMinutes(3).plusSeconds(15));
        gatewayUnanswered.set(null);
        JsonNode active = awaitEndpoint(service, clusterId, endpointId, "ACTIVE");

        assertEquals("http://floci.test:4566/_aws/emr-containers/endpoints/" + endpointId,
                active.get("serverUrl").textValue());
        assertEquals("Endpoint created successfully. It took 3 Minutes 15 Seconds",
                active.get("stateDetails").textValue());
        runner.stop(endpointId);
    }

    @Test
    void sessionTokenReachesItsOwnEndpointsGatewayUntilItExpires() {
        GatewayRunner runner = new GatewayRunner();
        EmrContainersService service = gatewayService(runner, stores());
        String clusterId = virtualCluster(service, EKS_CLUSTER);
        String endpointId = gatewayEndpoint(service, clusterId);
        String otherId = gatewayEndpoint(service, clusterId);
        awaitEndpoint(service, clusterId, endpointId, "ACTIVE");
        awaitEndpoint(service, clusterId, otherId, "ACTIVE");
        String token = sessionToken(service, clusterId, endpointId);

        SparkConnectTargets.Route route = service.gatewayRoute(endpointId, token).orElseThrow();
        assertEquals("127.0.0.1", route.host());
        assertEquals(GatewayRunner.PORT, route.port());
        route.ended().run();
        assertTrue(service.gatewayRoute(otherId, token).isEmpty(), "another endpoint's token is not its");
        assertTrue(service.gatewayRoute(endpointId, "not-a-token").isEmpty());
        assertTrue(service.gatewayRoute(endpointId, null).isEmpty());

        clock.advance(Duration.ofMinutes(15));
        SparkConnectTargets.Route expired = service.gatewayRoute(endpointId, token).orElseThrow();
        assertEquals(SparkConnectTargets.Route.UNAUTHENTICATED, expired.status());
        assertEquals("The authentication token has expired. Call GetManagedEndpointSessionCredentials for a new "
                + "one.", expired.refusal());
        runner.stop(endpointId);
        runner.stop(otherId);
    }

    @Test
    void corsPreflightReachesAnActiveEndpointsGatewayWithoutAToken() {
        GatewayRunner runner = new GatewayRunner();
        EmrContainersService service = gatewayService(runner, stores());
        String clusterId = virtualCluster(service, EKS_CLUSTER);
        String endpointId = gatewayEndpoint(service, clusterId);
        awaitEndpoint(service, clusterId, endpointId, "ACTIVE");

        SparkConnectTargets.Route route = service.gatewayPreflightRoute(endpointId).orElseThrow();
        assertEquals("127.0.0.1", route.host());
        assertEquals(GatewayRunner.PORT, route.port());
        route.ended().run();
        assertTrue(service.gatewayPreflightRoute("ep0000000000000").isEmpty(), "no gateway runs for it");
        runner.stop(endpointId);
    }

    @Test
    void deletedDockerEndpointStopsItsGatewayAndItsTokensGo() {
        GatewayRunner runner = new GatewayRunner();
        EmrContainersService service = gatewayService(runner, stores());
        String clusterId = virtualCluster(service, EKS_CLUSTER);
        String endpointId = gatewayEndpoint(service, clusterId);
        awaitEndpoint(service, clusterId, endpointId, "ACTIVE");
        String token = sessionToken(service, clusterId, endpointId);

        service.deleteManagedEndpoint(clusterId, endpointId);
        JsonNode terminated = awaitEndpoint(service, clusterId, endpointId, "TERMINATED");

        assertTrue(runner.stopped.contains(endpointId));
        assertFalse(terminated.has("serverUrl"));
        assertTrue(service.gatewayRoute(endpointId, token).isEmpty());
    }

    @Test
    void dockerEndpointWhoseGatewayExitsIsTerminatedWithErrors() {
        GatewayRunner runner = new GatewayRunner();
        EmrContainersService service = gatewayService(runner, stores());
        String clusterId = virtualCluster(service, EKS_CLUSTER);
        String endpointId = gatewayEndpoint(service, clusterId);
        awaitEndpoint(service, clusterId, endpointId, "ACTIVE");

        runner.end(endpointId, SparkJobRunner.Outcome.exitedWith(1));
        JsonNode failed = awaitEndpoint(service, clusterId, endpointId, "TERMINATED_WITH_ERRORS");

        assertEquals("INTERNAL_ERROR", failed.get("failureReason").textValue());
        assertEquals("The endpoint's Jupyter Enterprise Gateway exited with code 1.",
                failed.get("stateDetails").textValue());
        assertFalse(failed.has("serverUrl"));
    }

    @Test
    void dockerEndpointsGatewayStoppedWithFlociIsTerminatedWithErrors() {
        GatewayRunner runner = new GatewayRunner();
        EmrContainersService service = gatewayService(runner, stores());
        String clusterId = virtualCluster(service, EKS_CLUSTER);
        String endpointId = gatewayEndpoint(service, clusterId);
        awaitEndpoint(service, clusterId, endpointId, "ACTIVE");

        service.shutdown();
        JsonNode lost = awaitEndpoint(service, clusterId, endpointId, "TERMINATED_WITH_ERRORS");

        assertEquals("INTERNAL_ERROR", lost.get("failureReason").textValue());
        assertEquals("The endpoint's Jupyter Enterprise Gateway stopped with Floci.",
                lost.get("stateDetails").textValue());
        assertFalse(lost.has("serverUrl"));
        assertTrue(runner.stopped.isEmpty(), "nothing deleted it");
    }

    @Test
    void dockerEndpointAnEarlierProcessRanIsTerminatedWithErrors() {
        List<AccountAwareStorageBackend<ObjectNode>> stores = stores();
        GatewayRunner earlierRunner = new GatewayRunner();
        EmrContainersService earlier = gatewayService(earlierRunner, stores);
        String clusterId = virtualCluster(earlier, EKS_CLUSTER);
        String endpointId = gatewayEndpoint(earlier, clusterId);
        awaitEndpoint(earlier, clusterId, endpointId, "ACTIVE");

        EmrContainersService restarted = gatewayService(new GatewayRunner(), stores);
        JsonNode lost = endpoint(restarted, clusterId, endpointId);

        assertEquals("TERMINATED_WITH_ERRORS", state(lost));
        assertEquals("INTERNAL_ERROR", lost.get("failureReason").textValue());
        assertEquals("The endpoint's Jupyter Enterprise Gateway stopped with Floci.",
                lost.get("stateDetails").textValue());
        earlierRunner.stop(endpointId);
    }

    /**
     * A runner whose gateways listen at once on 127.0.0.1:{@value #PORT} and run until they are stopped, interrupted,
     * or ended as a test says.
     */
    private static final class GatewayRunner implements SparkJobRunner {
        static final int PORT = 18888;
        final List<Job> jobs = new CopyOnWriteArrayList<>();
        final Set<String> stopped = ConcurrentHashMap.newKeySet();
        private final Map<String, CompletableFuture<Outcome>> ends = new ConcurrentHashMap<>();

        @Override
        public Outcome run(Job job, Progress progress) {
            jobs.add(job);
            progress.started();
            progress.listening("127.0.0.1", PORT);
            try {
                return end(job.runId()).get();
            } catch (InterruptedException e) {
                // As the Docker runner answers when Floci's shutdown interrupts it.
                return Outcome.halted();
            } catch (ExecutionException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void stop(String runId) {
            stopped.add(runId);
            end(runId, Outcome.halted());
        }

        @Override
        public void removeLeftoverDrivers(String service) {
        }

        void end(String runId, Outcome outcome) {
            end(runId).complete(outcome);
        }

        Job awaitJob(String runId) {
            return await("a gateway started for " + runId).atMost(Duration.ofSeconds(10))
                    .pollInterval(Duration.ofMillis(10))
                    .until(() -> jobs.stream().filter(started -> started.runId().equals(runId)).findFirst(),
                            Optional::isPresent)
                    .get();
        }

        private CompletableFuture<Outcome> end(String runId) {
            return ends.computeIfAbsent(runId, id -> new CompletableFuture<>());
        }
    }

    /** A runner that starts the driver at once, runs {@code whileRunning}, and ends as {@code outcome} says. */
    private static final class FakeRunner implements SparkJobRunner {
        final List<Job> jobs = new ArrayList<>();
        final List<String> driversStarted = new ArrayList<>();
        final Set<String> stopped = new HashSet<>();
        final List<String> leftoversRemoved = new ArrayList<>();
        Outcome outcome = Outcome.exitedWith(0);
        Runnable whileRunning = () -> { };

        @Override
        public Outcome run(Job job, Progress progress) {
            jobs.add(job);
            if (progress.cancelled()) {
                return Outcome.halted();
            }
            driversStarted.add(job.runId());
            progress.started();
            whileRunning.run();
            return stopped.contains(job.runId()) ? Outcome.halted() : outcome;
        }

        @Override
        public void stop(String runId) {
            stopped.add(runId);
        }

        @Override
        public void removeLeftoverDrivers(String service) {
        }

        @Override
        public void removeLeftoverDriver(String service, String runId) {
            leftoversRemoved.add(runId);
        }
    }
}
