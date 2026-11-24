package io.floci.aws.services.sagemaker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.sagemaker.model.SageMakerEntities.EndpointConfigResource;
import io.floci.aws.services.sagemaker.model.SageMakerEntities.EndpointResource;
import io.floci.aws.services.sagemaker.model.SageMakerEntities.ModelResource;
import io.floci.aws.services.sagemaker.model.SageMakerEntities.TrainingJobResource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SageMakerServiceTest {
    private static final String DEFAULT_ACCOUNT = "000000000000";
    private static final String OTHER_ACCOUNT = "111111111111";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void modelCrudAndDuplicateValidation() throws Exception {
        SageMakerService service = service();
        service.createModel(mapper.readTree("""
                {"ModelName":"m1","PrimaryContainer":{"Image":"busybox:stable"},"ExecutionRoleArn":"arn:aws:iam::000000000000:role/r"}
                """), "us-east-1");
        assertEquals("m1", service.describeModel(mapper.readTree("{\"ModelName\":\"m1\"}"), "us-east-1").path("ModelName").asText());
        AwsException duplicate = assertThrows(AwsException.class, () -> service.createModel(mapper.readTree("""
                {"ModelName":"m1","PrimaryContainer":{"Image":"busybox:stable"}}
                """), "us-east-1"));
        assertEquals("ValidationException", duplicate.getErrorCode());
        service.deleteModel(mapper.readTree("{\"ModelName\":\"m1\"}"), "us-east-1");
        AwsException missing = assertThrows(AwsException.class,
                () -> service.describeModel(mapper.readTree("{\"ModelName\":\"m1\"}"), "us-east-1"));
        assertEquals("ValidationException", missing.getErrorCode());
        AwsException deleteMissing = assertThrows(AwsException.class,
                () -> service.deleteModel(mapper.readTree("{\"ModelName\":\"m1\"}"), "us-east-1"));
        assertEquals("ValidationException", deleteMissing.getErrorCode());
    }

    @Test
    void modelIsScopedByRegion() throws Exception {
        SageMakerService service = service();
        service.createModel(mapper.readTree("""
                {"ModelName":"m1","PrimaryContainer":{"Image":"busybox:stable"}}
                """), "us-east-1");
        AwsException missingInOtherRegion = assertThrows(AwsException.class,
                () -> service.describeModel(mapper.readTree("{\"ModelName\":\"m1\"}"), "us-west-2"));
        assertEquals("ValidationException", missingInOtherRegion.getErrorCode());
        // Same name, different region: not a collision, since the ARNs are regional.
        service.createModel(mapper.readTree("""
                {"ModelName":"m1","PrimaryContainer":{"Image":"busybox:stable"}}
                """), "us-west-2");
        assertEquals(1, service.listModels(mapper.readTree("{}"), "us-east-1").path("Models").size());
        assertEquals(1, service.listModels(mapper.readTree("{}"), "us-west-2").path("Models").size());
    }

    @Test
    void createModelRejectsEmptyContainers() throws Exception {
        SageMakerService service = service();
        AwsException empty = assertThrows(AwsException.class, () -> service.createModel(mapper.readTree("""
                {"ModelName":"m1","Containers":[]}
                """), "us-east-1"));
        assertEquals("ValidationException", empty.getErrorCode());
    }

    @Test
    void listModelsValidatesMaxResultsAndPaginates() throws Exception {
        SageMakerService service = service();
        for (String name : List.of("a", "b", "c")) {
            service.createModel(mapper.readTree(
                    "{\"ModelName\":\"" + name + "\",\"PrimaryContainer\":{\"Image\":\"busybox:stable\"}}"), "us-east-1");
        }
        AwsException badMax = assertThrows(AwsException.class,
                () -> service.listModels(mapper.readTree("{\"MaxResults\":0}"), "us-east-1"));
        assertEquals("ValidationException", badMax.getErrorCode());

        JsonNode firstPage = service.listModels(mapper.readTree("{\"MaxResults\":2,\"SortBy\":\"Name\",\"SortOrder\":\"Ascending\"}"), "us-east-1");
        assertEquals(2, firstPage.path("Models").size());
        assertEquals("a", firstPage.path("Models").get(0).path("ModelName").asText());
        String nextToken = firstPage.path("NextToken").asText();
        assertEquals("b", nextToken);

        JsonNode secondPage = service.listModels(
                mapper.readTree("{\"MaxResults\":2,\"SortBy\":\"Name\",\"SortOrder\":\"Ascending\",\"NextToken\":\"" + nextToken + "\"}"), "us-east-1");
        assertEquals(1, secondPage.path("Models").size());
        assertEquals("c", secondPage.path("Models").get(0).path("ModelName").asText());
        assertTrue(secondPage.path("NextToken").isMissingNode());
    }

    @Test
    void listModelsSortsByRequestedKeyAndResumesInDescendingOrder() throws Exception {
        InMemoryStorage<String, ModelResource> models = new InMemoryStorage<>();
        for (String name : List.of("a", "m", "z")) {
            ModelResource model = new ModelResource();
            model.modelName = name;
            model.creationTime = switch (name) {
                case "a" -> 2000L;
                case "m" -> 1000L;
                default -> 3000L;
            };
            models.put("us-east-1::" + name, model);
        }
        SageMakerService service = new SageMakerService(models,
                new InMemoryStorage<String, EndpointConfigResource>(),
                new InMemoryStorage<String, EndpointResource>(),
                new InMemoryStorage<String, TrainingJobResource>(),
                new RegionResolver("us-east-1", "000000000000"), mapper,
                mock(SageMakerEndpointManager.class), mock(SageMakerTrainingRunner.class));

        JsonNode defaultOrder = service.listModels(mapper.readTree("{}"), "us-east-1");
        assertEquals(List.of("z", "a", "m"), modelNames(defaultOrder));
        JsonNode newestPage = service.listModels(mapper.readTree("{\"MaxResults\":2}"), "us-east-1");
        assertEquals(List.of("z", "a"), modelNames(newestPage));
        String creationCursor = newestPage.path("NextToken").asText();
        JsonNode olderPage = service.listModels(
                mapper.createObjectNode().put("MaxResults", 2).put("NextToken", creationCursor), "us-east-1");
        assertEquals(List.of("m"), modelNames(olderPage));
        JsonNode oldestFirst = service.listModels(mapper.readTree("{\"SortBy\":\"CreationTime\",\"SortOrder\":\"Ascending\"}"), "us-east-1");
        assertEquals(List.of("m", "a", "z"), modelNames(oldestFirst));

        JsonNode firstPage = service.listModels(mapper.readTree("{\"SortBy\":\"Name\",\"SortOrder\":\"Descending\",\"MaxResults\":2}"), "us-east-1");
        assertEquals(List.of("z", "m"), modelNames(firstPage));
        JsonNode secondPage = service.listModels(mapper.readTree("{\"SortBy\":\"Name\",\"SortOrder\":\"Descending\",\"MaxResults\":2,\"NextToken\":\"m\"}"), "us-east-1");
        assertEquals(List.of("a"), modelNames(secondPage));
        assertTrue(secondPage.path("NextToken").isMissingNode());

        models.delete("us-east-1::m");
        JsonNode afterCursorDeletion = service.listModels(mapper.readTree("{\"SortBy\":\"Name\",\"SortOrder\":\"Descending\",\"MaxResults\":2,\"NextToken\":\"m\"}"), "us-east-1");
        assertEquals(List.of("a"), modelNames(afterCursorDeletion));
    }

    @Test
    void listModelsCreationTimeCursorResumesEqualTimeTiesAfterDeletion() throws Exception {
        InMemoryStorage<String, ModelResource> models = new InMemoryStorage<>();
        for (String name : List.of("a", "m", "z")) {
            ModelResource model = new ModelResource();
            model.modelName = name;
            model.creationTime = name.equals("z") ? 3000L : 2000L;
            models.put("us-east-1::" + name, model);
        }
        SageMakerService service = new SageMakerService(models,
                new InMemoryStorage<String, EndpointConfigResource>(),
                new InMemoryStorage<String, EndpointResource>(),
                new InMemoryStorage<String, TrainingJobResource>(),
                new RegionResolver("us-east-1", "000000000000"), mapper,
                mock(SageMakerEndpointManager.class), mock(SageMakerTrainingRunner.class));

        JsonNode firstAscending = service.listModels(
                mapper.createObjectNode().put("MaxResults", 1).put("SortOrder", "Ascending"), "us-east-1");
        assertEquals(List.of("a"), modelNames(firstAscending));
        models.delete("us-east-1::a");
        JsonNode nextAscending = service.listModels(mapper.createObjectNode()
                .put("MaxResults", 1).put("SortOrder", "Ascending")
                .put("NextToken", firstAscending.path("NextToken").asText()), "us-east-1");
        assertEquals(List.of("m"), modelNames(nextAscending));

        JsonNode firstDescending = service.listModels(mapper.createObjectNode().put("MaxResults", 1), "us-east-1");
        assertEquals(List.of("z"), modelNames(firstDescending));
        models.delete("us-east-1::z");
        JsonNode nextDescending = service.listModels(mapper.createObjectNode()
                .put("MaxResults", 1).put("NextToken", firstDescending.path("NextToken").asText()), "us-east-1");
        assertEquals(List.of("m"), modelNames(nextDescending));
    }

    @Test
    void listModelsCreationTimeCursorRejectsChangedRequestContext() throws Exception {
        SageMakerService service = service();
        for (String name : List.of("a", "b")) {
            service.createModel(mapper.readTree(
                    "{\"ModelName\":\"" + name + "\",\"PrimaryContainer\":{\"Image\":\"busybox:stable\"}}"), "us-east-1");
        }
        JsonNode first = service.listModels(mapper.createObjectNode().put("MaxResults", 1), "us-east-1");
        String token = first.path("NextToken").asText();

        AwsException changedRegion = assertThrows(AwsException.class, () -> service.listModels(
                mapper.createObjectNode().put("MaxResults", 1).put("NextToken", token), "us-west-2"));
        assertEquals("ValidationException", changedRegion.getErrorCode());
        AwsException changedOrder = assertThrows(AwsException.class, () -> service.listModels(
                mapper.createObjectNode().put("MaxResults", 1).put("SortOrder", "Ascending")
                        .put("NextToken", token), "us-east-1"));
        assertEquals("ValidationException", changedOrder.getErrorCode());
        AwsException changedFilter = assertThrows(AwsException.class, () -> service.listModels(
                mapper.createObjectNode().put("MaxResults", 1).put("NameContains", "b")
                        .put("NextToken", token), "us-east-1"));
        assertEquals("ValidationException", changedFilter.getErrorCode());
        AwsException invalidToken = assertThrows(AwsException.class, () -> service.listModels(
                mapper.createObjectNode().put("NextToken", "invalid%"), "us-east-1"));
        assertEquals("ValidationException", invalidToken.getErrorCode());
    }

    private static List<String> modelNames(JsonNode response) {
        List<String> names = new ArrayList<>();
        response.path("Models").forEach(model -> names.add(model.path("ModelName").asText()));
        return names;
    }

    @Test
    void listEndpointConfigsStillPaginatesByName() throws Exception {
        InMemoryStorage<String, EndpointConfigResource> configs = new InMemoryStorage<>();
        for (String name : List.of("a", "b")) {
            EndpointConfigResource config = new EndpointConfigResource();
            config.endpointConfigName = name;
            configs.put("us-east-1::" + name, config);
        }
        SageMakerService service = new SageMakerService(new InMemoryStorage<String, ModelResource>(), configs,
                new InMemoryStorage<String, EndpointResource>(),
                new InMemoryStorage<String, TrainingJobResource>(),
                new RegionResolver("us-east-1", "000000000000"), mapper,
                mock(SageMakerEndpointManager.class), mock(SageMakerTrainingRunner.class));

        JsonNode firstPage = service.listEndpointConfigs(mapper.readTree("{\"MaxResults\":1,\"SortBy\":\"Name\",\"SortOrder\":\"Ascending\"}"), "us-east-1");
        assertEquals("a", firstPage.path("EndpointConfigs").get(0).path("EndpointConfigName").asText());
        assertEquals("a", firstPage.path("NextToken").asText());
        configs.delete("us-east-1::a");
        JsonNode secondPage = service.listEndpointConfigs(mapper.readTree("{\"MaxResults\":1,\"SortBy\":\"Name\",\"SortOrder\":\"Ascending\",\"NextToken\":\"a\"}"), "us-east-1");
        assertEquals("b", secondPage.path("EndpointConfigs").get(0).path("EndpointConfigName").asText());
        assertTrue(secondPage.path("NextToken").isMissingNode());
    }

    @Test
    void deleteEndpointConfigRequiresExistingConfig() throws Exception {
        SageMakerService service = service();
        AwsException missing = assertThrows(AwsException.class,
                () -> service.deleteEndpointConfig(mapper.readTree("{\"EndpointConfigName\":\"missing\"}"), "us-east-1"));
        assertEquals("ValidationException", missing.getErrorCode());
    }

    @Test
    void endpointConfigRequiresExistingModel() throws Exception {
        SageMakerService service = service();
        AwsException missing = assertThrows(AwsException.class, () -> service.createEndpointConfig(mapper.readTree("""
                {"EndpointConfigName":"cfg","ProductionVariants":[{"VariantName":"AllTraffic","ModelName":"missing","InitialInstanceCount":1,"InstanceType":"ml.t2.medium"}]}
                """), "us-east-1"));
        assertEquals("ValidationException", missing.getErrorCode());
    }

    @Test
    void tagsRoundTrip() throws Exception {
        SageMakerService service = service();
        String arn = service.createModel(mapper.readTree("""
                {"ModelName":"tagged","PrimaryContainer":{"Image":"busybox:stable"},"Tags":[{"Key":"a","Value":"b"}]}
                """), "us-east-1").path("ModelArn").asText();
        service.addTags(mapper.readTree("{\"ResourceArn\":\"" + arn + "\",\"Tags\":[{\"Key\":\"c\",\"Value\":\"d\"}]}"));
        assertEquals(2, service.listTags(mapper.readTree("{\"ResourceArn\":\"" + arn + "\"}")).path("Tags").size());
        service.deleteTags(mapper.readTree("{\"ResourceArn\":\"" + arn + "\",\"TagKeys\":[\"a\"]}"));
        assertEquals("c", service.listTags(mapper.readTree("{\"ResourceArn\":\"" + arn + "\"}")).path("Tags").get(0).path("Key").asText());
    }

    @Test
    void tagsWithoutAKeyAreSkippedInsteadOfStoredWithANullKey() throws Exception {
        SageMakerService service = service();
        String arn = service.createModel(mapper.readTree("""
                {"ModelName":"m1","PrimaryContainer":{"Image":"busybox:stable"},"Tags":[{"Value":"orphan"},{"Key":"a","Value":"b"}]}
                """), "us-east-1").path("ModelArn").asText();
        JsonNode tags = service.listTags(mapper.readTree("{\"ResourceArn\":\"" + arn + "\"}")).path("Tags");
        assertEquals(1, tags.size());
        assertEquals("a", tags.get(0).path("Key").asText());
    }

    @Test
    void trainingJobListingFiltersByNameAndStatus() throws Exception {
        SageMakerService service = service();
        for (String name : List.of("job-a", "job-b", "other")) {
            service.createTrainingJob(mapper.readTree("""
                    {"TrainingJobName":"%s",
                     "AlgorithmSpecification":{"TrainingImage":"busybox:stable"},
                     "OutputDataConfig":{"S3OutputPath":"s3://bucket/out"}}
                    """.formatted(name)), "us-east-1");
        }
        JsonNode filtered = service.listTrainingJobs(mapper.readTree("{\"NameContains\":\"job-\"}"), "us-east-1");
        assertEquals(2, filtered.path("TrainingJobSummaries").size());

        JsonNode byStatus = service.listTrainingJobs(mapper.readTree("{\"StatusEquals\":\"Completed\"}"), "us-east-1");
        assertEquals(0, byStatus.path("TrainingJobSummaries").size());
    }


    @Test
    void s3UriParsingCoversPrefixesAndValidation() {
        S3Uri object = S3Uri.parse("s3://bucket/path/to/object");
        assertEquals("bucket", object.bucket());
        assertEquals("path/to/object", object.key());
        S3Uri bucketOnly = S3Uri.parse("s3://bucket");
        assertEquals("bucket", bucketOnly.bucket());
        assertEquals("", bucketOnly.key());
        assertThrows(IllegalArgumentException.class, () -> S3Uri.parse("http://bucket/key"));
    }

    @Test
    void trainingRunUpdatesApplyOnlyWhileTheirJobIsStillStored() throws Exception {
        InMemoryStorage<String, TrainingJobResource> jobs = new InMemoryStorage<>();
        SageMakerService service = service(jobs);
        String request = """
                {"TrainingJobName":"reset-job",
                 "AlgorithmSpecification":{"TrainingImage":"busybox:stable"},
                 "OutputDataConfig":{"S3OutputPath":"s3://bucket/out"}}
                """;
        service.createTrainingJob(mapper.readTree(request), "us-east-1");
        TrainingJobResource run = jobs.scan(key -> true).get(0);

        run.trainingJobStatus = "Completed";
        assertTrue(service.updateTrainingJob(run));
        assertEquals("Completed", describeStatus(service, "reset-job"));

        // A state reset wipes the store while the run is still finishing.
        jobs.clear();
        run.trainingJobStatus = "Failed";
        assertFalse(service.updateTrainingJob(run));
        assertTrue(jobs.scan(key -> true).isEmpty());
        assertThrows(AwsException.class, () -> describeStatus(service, "reset-job"));

        // A same-named job created after the reset is not overwritten by the old run.
        service.createTrainingJob(mapper.readTree(request), "us-east-1");
        TrainingJobResource recreated = jobs.scan(key -> true).get(0);
        run.creationTime = recreated.creationTime - 1;
        assertFalse(service.updateTrainingJob(run));
        assertEquals("InProgress", describeStatus(service, "reset-job"));
    }

    @Test
    void endpointWorkerStartsTheModelOfTheEndpointsOwnAccount() {
        WorkerFixture fixture = workerFixture();
        assertEquals("image-of-account-111",
                fixture.service().modelForEndpoint(endpoint("Creating")).primaryContainer.get("Image"));
    }

    @Test
    void endpointWorkerFindsAConfigThatOnlyItsOwnAccountHas() {
        WorkerFixture fixture = workerFixture();
        EndpointResource endpoint = endpoint("Creating");
        endpoint.endpointConfigName = "solo-cfg";
        assertEquals("image-of-account-111",
                fixture.service().modelForEndpoint(endpoint).primaryContainer.get("Image"));
    }

    @Test
    void endpointWorkerSavesItsOutcomeInTheEndpointsOwnAccount() {
        WorkerFixture fixture = workerFixture();
        assertTrue(fixture.service().finalizeEndpointStart(endpoint("InService")));
        assertEquals("InService",
                fixture.endpoints().getForAccount(OTHER_ACCOUNT, "us-east-1::demo-ep").orElseThrow().endpointStatus);
    }

    @Test
    void trainingWorkerSavesItsOutcomeInTheJobsOwnAccount() {
        WorkerFixture fixture = workerFixture();
        assertTrue(fixture.service().updateTrainingJob(trainingJob("Completed")));
        assertEquals("Completed",
                fixture.jobs().getForAccount(OTHER_ACCOUNT, "us-east-1::demo-job").orElseThrow().trainingJobStatus);
    }

    /**
     * #5664: shutdown stops every endpoint's container, so after a restart an endpoint that was running or still
     * starting goes back to Creating and starts again, written in its own account; a failed one stays as it is, and
     * one a crash left deleting is deleted.
     */
    @Test
    void restoringStartsEveryRunningOrStartingEndpointAgainInItsOwnAccount() {
        InMemoryStorage<String, EndpointResource> raw = spy(new InMemoryStorage<String, EndpointResource>());
        AccountAwareStorageBackend<EndpointResource> endpoints =
                new AccountAwareStorageBackend<>(raw, null, DEFAULT_ACCOUNT);
        SageMakerEndpointManager manager = mock(SageMakerEndpointManager.class);
        SageMakerService service = restoringService(endpoints, manager);
        EndpointResource running = endpoint("InService", "running");
        running.containerId = "stopped-on-shutdown";
        running.invokeHost = "172.17.0.9";
        running.invokePort = 32768;
        running.generation = 3;
        // A record saved before endpoints carried their account: the account it is stored under owns it.
        running.accountId = null;
        EndpointResource starting = endpoint("Creating", "starting");
        starting.accountId = DEFAULT_ACCOUNT;
        EndpointResource failed = endpoint("Failed", "failed");
        failed.failureReason = "Container did not pass /ping health check";
        endpoints.putForAccount(OTHER_ACCOUNT, "us-east-1::running", running);
        endpoints.putForAccount(DEFAULT_ACCOUNT, "us-east-1::starting", starting);
        endpoints.putForAccount(OTHER_ACCOUNT, "us-east-1::failed", failed);
        endpoints.putForAccount(OTHER_ACCOUNT, "us-east-1::deleting", endpoint("Deleting", "deleting"));
        clearInvocations(raw);

        service.restorePersistedRuntime();

        EndpointResource restored = endpoints.getForAccount(OTHER_ACCOUNT, "us-east-1::running").orElseThrow();
        assertEquals("Creating", restored.endpointStatus);
        assertNull(restored.containerId);
        assertNull(restored.invokeHost);
        assertEquals(0, restored.invokePort);
        assertEquals(4, restored.generation);
        verify(raw).put(OTHER_ACCOUNT + "/us-east-1::running", restored);
        assertEquals("Creating",
                endpoints.getForAccount(DEFAULT_ACCOUNT, "us-east-1::starting").orElseThrow().endpointStatus);
        verify(raw).put(eq(DEFAULT_ACCOUNT + "/us-east-1::starting"), any());
        EndpointResource stillFailed = endpoints.getForAccount(OTHER_ACCOUNT, "us-east-1::failed").orElseThrow();
        assertEquals("Failed", stillFailed.endpointStatus);
        assertEquals("Container did not pass /ping health check", stillFailed.failureReason);
        assertTrue(endpoints.getForAccount(OTHER_ACCOUNT, "us-east-1::deleting").isEmpty());

        ArgumentCaptor<EndpointResource> started = ArgumentCaptor.forClass(EndpointResource.class);
        verify(manager, times(2)).startEndpointAsync(started.capture(), eq(service));
        assertEquals(Map.of("running", OTHER_ACCOUNT, "starting", DEFAULT_ACCOUNT), started.getAllValues().stream()
                .collect(Collectors.toMap(endpoint -> endpoint.endpointName, endpoint -> endpoint.accountId)));
        verify(manager).stopEndpoint(argThat(endpoint -> "deleting".equals(endpoint.endpointName)));
    }

    /** One endpoint that cannot be restored leaves the others to restore, and its failure is thrown at the end. */
    @Test
    void anEndpointThatCannotBeRestoredLeavesTheOthersToRestore() {
        AccountAwareStorageBackend<EndpointResource> endpoints =
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, DEFAULT_ACCOUNT);
        SageMakerEndpointManager manager = mock(SageMakerEndpointManager.class);
        SageMakerService service = restoringService(endpoints, manager);
        endpoints.putForAccount(OTHER_ACCOUNT, "us-east-1::first", endpoint("InService", "first"));
        endpoints.putForAccount(OTHER_ACCOUNT, "us-east-1::second", endpoint("InService", "second"));
        IllegalStateException cause = new IllegalStateException("worker pool shut down");
        doThrow(cause).when(manager)
                .startEndpointAsync(argThat(endpoint -> "first".equals(endpoint.endpointName)), any());

        IllegalStateException thrown = assertThrows(IllegalStateException.class, service::restorePersistedRuntime);

        assertEquals("Could not restore SageMaker endpoint first of account " + OTHER_ACCOUNT, thrown.getMessage());
        assertSame(cause, thrown.getCause());
        verify(manager).startEndpointAsync(argThat(endpoint -> "second".equals(endpoint.endpointName)), eq(service));
    }

    /**
     * A start worker runs with a copy of the endpoint, so an UpdateEndpoint while a restored endpoint is starting
     * supersedes the restore's start: its result is discarded, not published over the update.
     */
    @Test
    void anUpdateSupersedesTheStartOfARestoredEndpoint() throws Exception {
        AccountAwareStorageBackend<EndpointResource> endpoints =
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, DEFAULT_ACCOUNT);
        InMemoryStorage<String, EndpointConfigResource> configs = new InMemoryStorage<>();
        configs.put("us-east-1::demo-cfg", endpointConfig("demo-cfg", "demo"));
        SageMakerEndpointManager manager = mock(SageMakerEndpointManager.class);
        SageMakerService service = new SageMakerService(new InMemoryStorage<String, ModelResource>(), configs,
                endpoints, new InMemoryStorage<String, TrainingJobResource>(),
                new RegionResolver("us-east-1", DEFAULT_ACCOUNT), mapper, manager, mock(SageMakerTrainingRunner.class));
        EndpointResource stored = endpoint("InService");
        stored.accountId = DEFAULT_ACCOUNT;
        endpoints.putForAccount(DEFAULT_ACCOUNT, "us-east-1::demo-ep", stored);
        service.restorePersistedRuntime();
        ArgumentCaptor<EndpointResource> restoreRun = ArgumentCaptor.forClass(EndpointResource.class);
        verify(manager).startEndpointAsync(restoreRun.capture(), eq(service));

        service.updateEndpoint(mapper.readTree("""
                {"EndpointName":"demo-ep","EndpointConfigName":"demo-cfg"}"""), "us-east-1");
        restoreRun.getValue().endpointStatus = "InService";
        restoreRun.getValue().invokeHost = "172.17.0.9";

        assertFalse(service.finalizeEndpointStart(restoreRun.getValue()));
        EndpointResource current = endpoints.getForAccount(DEFAULT_ACCOUNT, "us-east-1::demo-ep").orElseThrow();
        assertEquals("Creating", current.endpointStatus);
        assertNull(current.invokeHost);
    }

    /** A start publishes its own fields onto the stored endpoint, keeping what was written to it meanwhile. */
    @Test
    void aStartsResultKeepsTagsAddedWhileItRan() {
        WorkerFixture fixture = workerFixture();
        fixture.endpoints().getForAccount(OTHER_ACCOUNT, "us-east-1::demo-ep").orElseThrow().tags.put("team", "ml");
        EndpointResource run = endpoint("InService");
        run.invokeHost = "172.17.0.9";
        run.invokePort = 32768;

        assertTrue(fixture.service().finalizeEndpointStart(run));

        EndpointResource current = fixture.endpoints().getForAccount(OTHER_ACCOUNT, "us-east-1::demo-ep").orElseThrow();
        assertEquals("InService", current.endpointStatus);
        assertEquals("172.17.0.9", current.invokeHost);
        assertEquals(32768, current.invokePort);
        assertEquals(Map.of("team", "ml"), current.tags);
    }

    /**
     * A record from before stores were account-scoped is moved into the default account, not copied there: a second
     * restart restores it once, and a deleting one is gone for good.
     */
    @Test
    void aRecordFromBeforeAccountScopedStoresIsMovedNotCopied() {
        InMemoryStorage<String, EndpointResource> raw = new InMemoryStorage<>();
        AccountAwareStorageBackend<EndpointResource> endpoints =
                new AccountAwareStorageBackend<>(raw, null, DEFAULT_ACCOUNT);
        SageMakerEndpointManager manager = mock(SageMakerEndpointManager.class);
        SageMakerService service = restoringService(endpoints, manager);
        EndpointResource legacy = endpoint("InService", "legacy");
        legacy.accountId = null;
        raw.put("us-east-1::legacy", legacy);
        EndpointResource gone = endpoint("Deleting", "gone");
        gone.accountId = null;
        raw.put("us-east-1::gone", gone);

        service.restorePersistedRuntime();
        service.restorePersistedRuntime();

        assertEquals(Set.of(DEFAULT_ACCOUNT + "/us-east-1::legacy"), raw.keys());
        verify(manager, times(2)).startEndpointAsync(argThat(endpoint -> "legacy".equals(endpoint.endpointName)),
                eq(service));
        assertEquals(3, endpoints.getForAccount(DEFAULT_ACCOUNT, "us-east-1::legacy").orElseThrow().generation);
    }

    private SageMakerService restoringService(AccountAwareStorageBackend<EndpointResource> endpoints,
                                              SageMakerEndpointManager manager) {
        return new SageMakerService(new InMemoryStorage<String, ModelResource>(),
                new InMemoryStorage<String, EndpointConfigResource>(), endpoints,
                new InMemoryStorage<String, TrainingJobResource>(), new RegionResolver("us-east-1", DEFAULT_ACCOUNT),
                mapper, manager, mock(SageMakerTrainingRunner.class));
    }

    private static EndpointResource endpoint(String status, String name) {
        EndpointResource endpoint = endpoint(status);
        endpoint.endpointName = name;
        return endpoint;
    }

    private record WorkerFixture(SageMakerService service,
                                 AccountAwareStorageBackend<EndpointResource> endpoints,
                                 AccountAwareStorageBackend<TrainingJobResource> jobs) {
    }

    /**
     * #8373: the endpoint and training workers call back on their own threads, outside any request,
     * where an account-aware store reads the default account. Stores with no request context stand
     * in for that thread. Account 111 owns the resources; the default account holds a same-named
     * endpoint config and model with a different image, and nothing named {@code solo-cfg}.
     */
    private WorkerFixture workerFixture() {
        AccountAwareStorageBackend<ModelResource> models =
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, DEFAULT_ACCOUNT);
        AccountAwareStorageBackend<EndpointConfigResource> configs =
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, DEFAULT_ACCOUNT);
        AccountAwareStorageBackend<EndpointResource> endpoints =
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, DEFAULT_ACCOUNT);
        AccountAwareStorageBackend<TrainingJobResource> jobs =
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, DEFAULT_ACCOUNT);
        SageMakerService service = new SageMakerService(models, configs, endpoints, jobs,
                new RegionResolver("us-east-1", DEFAULT_ACCOUNT), mapper,
                mock(SageMakerEndpointManager.class), mock(SageMakerTrainingRunner.class));

        models.putForAccount(DEFAULT_ACCOUNT, "us-east-1::demo", model("demo", "image-of-default-account"));
        configs.putForAccount(DEFAULT_ACCOUNT, "us-east-1::demo-cfg", endpointConfig("demo-cfg", "demo"));
        models.putForAccount(OTHER_ACCOUNT, "us-east-1::demo", model("demo", "image-of-account-111"));
        configs.putForAccount(OTHER_ACCOUNT, "us-east-1::demo-cfg", endpointConfig("demo-cfg", "demo"));
        models.putForAccount(OTHER_ACCOUNT, "us-east-1::solo", model("solo", "image-of-account-111"));
        configs.putForAccount(OTHER_ACCOUNT, "us-east-1::solo-cfg", endpointConfig("solo-cfg", "solo"));
        endpoints.putForAccount(OTHER_ACCOUNT, "us-east-1::demo-ep", endpoint("Creating"));
        jobs.putForAccount(OTHER_ACCOUNT, "us-east-1::demo-job", trainingJob("InProgress"));
        return new WorkerFixture(service, endpoints, jobs);
    }

    private static ModelResource model(String name, String image) {
        ModelResource model = new ModelResource();
        model.modelName = name;
        model.region = "us-east-1";
        model.primaryContainer.put("Image", image);
        return model;
    }

    private static EndpointConfigResource endpointConfig(String name, String modelName) {
        EndpointConfigResource config = new EndpointConfigResource();
        config.endpointConfigName = name;
        config.region = "us-east-1";
        config.productionVariants.add(new HashMap<>(Map.of("VariantName", "v", "ModelName", modelName)));
        return config;
    }

    /** A fresh object each call, so a check of the stored record proves a write rather than a shared reference. */
    private static EndpointResource endpoint(String status) {
        EndpointResource endpoint = new EndpointResource();
        endpoint.endpointName = "demo-ep";
        endpoint.endpointConfigName = "demo-cfg";
        endpoint.endpointStatus = status;
        endpoint.region = "us-east-1";
        endpoint.accountId = OTHER_ACCOUNT;
        endpoint.generation = 1;
        return endpoint;
    }

    private static TrainingJobResource trainingJob(String status) {
        TrainingJobResource job = new TrainingJobResource();
        job.trainingJobName = "demo-job";
        job.trainingJobArn = "arn:aws:sagemaker:us-east-1:" + OTHER_ACCOUNT + ":training-job/demo-job";
        job.trainingJobStatus = status;
        job.creationTime = 1000L;
        job.region = "us-east-1";
        job.accountId = OTHER_ACCOUNT;
        return job;
    }

    private String describeStatus(SageMakerService service, String name) throws Exception {
        return service.describeTrainingJob(mapper.readTree("{\"TrainingJobName\":\"" + name + "\"}"), "us-east-1")
                .path("TrainingJobStatus").asText();
    }

    private SageMakerService service() {
        return service(new InMemoryStorage<String, TrainingJobResource>());
    }

    private SageMakerService service(InMemoryStorage<String, TrainingJobResource> trainingJobs) {
        return new SageMakerService(new InMemoryStorage<String, ModelResource>(),
                new InMemoryStorage<String, EndpointConfigResource>(),
                new InMemoryStorage<String, EndpointResource>(),
                trainingJobs,
                new RegionResolver("us-east-1", "000000000000"), mapper,
                mock(SageMakerEndpointManager.class), mock(SageMakerTrainingRunner.class));
    }
}
