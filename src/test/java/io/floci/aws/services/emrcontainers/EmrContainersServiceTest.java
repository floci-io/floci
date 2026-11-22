package io.floci.aws.services.emrcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.eks.EksService;
import io.floci.aws.services.eks.model.AccessConfig;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.emrcontainers.EmrContainersService.ListQuery;
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
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

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
                "registry.internal/emr-on-eks/spark/%s", "floci-net");
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
    void immediateModeNeverStartsAContainer() {
        EmrContainersService service = service(0);
        String clusterId = virtualCluster(service, EKS_CLUSTER);
        String id = startJobRun(service, clusterId).get("id").textValue();

        assertEquals("COMPLETED", state(jobRun(service, clusterId, id)));
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
