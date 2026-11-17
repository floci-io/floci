package io.floci.aws.services.emrcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.eks.EksService;
import io.floci.aws.services.eks.model.AccessConfig;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.emrcontainers.EmrContainersService.ListQuery;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
}
