package io.github.hectorvent.floci.services.sagemaker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerEntities.EndpointConfigResource;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerEntities.EndpointResource;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerEntities.ModelResource;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerEntities.TrainingJobResource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class SageMakerListSortServiceTest {
    private static final String REGION = "us-east-1";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void listEndpointsHonorsDescendingNameSort() throws Exception {
        InMemoryStorage<String, EndpointResource> endpoints = new InMemoryStorage<>();
        endpoints.put(REGION + "::a-endpoint", endpoint("a-endpoint"));
        endpoints.put(REGION + "::z-endpoint", endpoint("z-endpoint"));

        JsonNode response = service(new InMemoryStorage<>(), endpoints, new InMemoryStorage<>())
                .listEndpoints(mapper.readTree("{\"SortBy\":\"Name\",\"SortOrder\":\"Descending\"}"), REGION);

        assertEquals(List.of("z-endpoint", "a-endpoint"), names(response, "Endpoints", "EndpointName"));
    }

    @Test
    void listEndpointConfigsHonorsDescendingNameSort() throws Exception {
        InMemoryStorage<String, EndpointConfigResource> configs = new InMemoryStorage<>();
        configs.put(REGION + "::a-config", endpointConfig("a-config"));
        configs.put(REGION + "::z-config", endpointConfig("z-config"));

        JsonNode response = service(configs, new InMemoryStorage<>(), new InMemoryStorage<>())
                .listEndpointConfigs(mapper.readTree("{\"SortBy\":\"Name\",\"SortOrder\":\"Descending\"}"), REGION);

        assertEquals(List.of("z-config", "a-config"), names(response, "EndpointConfigs", "EndpointConfigName"));
    }

    @Test
    void listTrainingJobsHonorsDescendingNameSort() throws Exception {
        InMemoryStorage<String, TrainingJobResource> jobs = new InMemoryStorage<>();
        jobs.put(REGION + "::a-job", trainingJob("a-job"));
        jobs.put(REGION + "::z-job", trainingJob("z-job"));

        JsonNode response = service(new InMemoryStorage<>(), new InMemoryStorage<>(), jobs)
                .listTrainingJobs(mapper.readTree("{\"SortBy\":\"Name\",\"SortOrder\":\"Descending\"}"), REGION);

        assertEquals(List.of("z-job", "a-job"), names(response, "TrainingJobSummaries", "TrainingJobName"));
    }

    @Test
    void listDefaultsToModeledCreationTimeOrder() throws Exception {
        InMemoryStorage<String, EndpointConfigResource> configs = new InMemoryStorage<>();
        EndpointConfigResource oldConfig = endpointConfig("z-config");
        oldConfig.creationTime = 1000;
        EndpointConfigResource newConfig = endpointConfig("a-config");
        newConfig.creationTime = 3000;
        configs.put(REGION + "::z-config", oldConfig);
        configs.put(REGION + "::a-config", newConfig);

        InMemoryStorage<String, EndpointResource> endpoints = new InMemoryStorage<>();
        EndpointResource oldEndpoint = endpoint("z-endpoint");
        oldEndpoint.creationTime = 1000;
        EndpointResource newEndpoint = endpoint("a-endpoint");
        newEndpoint.creationTime = 3000;
        endpoints.put(REGION + "::z-endpoint", oldEndpoint);
        endpoints.put(REGION + "::a-endpoint", newEndpoint);

        InMemoryStorage<String, TrainingJobResource> jobs = new InMemoryStorage<>();
        TrainingJobResource oldJob = trainingJob("z-job");
        oldJob.creationTime = 1000;
        TrainingJobResource newJob = trainingJob("a-job");
        newJob.creationTime = 3000;
        jobs.put(REGION + "::z-job", oldJob);
        jobs.put(REGION + "::a-job", newJob);

        SageMakerService service = service(configs, endpoints, jobs);
        JsonNode emptyRequest = mapper.readTree("{}");
        assertEquals(List.of("a-config", "z-config"),
                names(service.listEndpointConfigs(emptyRequest, REGION), "EndpointConfigs", "EndpointConfigName"));
        assertEquals(List.of("a-endpoint", "z-endpoint"),
                names(service.listEndpoints(emptyRequest, REGION), "Endpoints", "EndpointName"));
        assertEquals(List.of("z-job", "a-job"),
                names(service.listTrainingJobs(emptyRequest, REGION), "TrainingJobSummaries", "TrainingJobName"));
    }

    @Test
    void listStatusSortUsesStatusThenName() throws Exception {
        InMemoryStorage<String, EndpointResource> endpoints = new InMemoryStorage<>();
        EndpointResource creating = endpoint("z-endpoint");
        creating.endpointStatus = "Creating";
        EndpointResource serving = endpoint("a-endpoint");
        serving.endpointStatus = "InService";
        endpoints.put(REGION + "::z-endpoint", creating);
        endpoints.put(REGION + "::a-endpoint", serving);

        InMemoryStorage<String, TrainingJobResource> jobs = new InMemoryStorage<>();
        TrainingJobResource completed = trainingJob("z-job");
        TrainingJobResource running = trainingJob("a-job");
        running.trainingJobStatus = "InProgress";
        jobs.put(REGION + "::z-job", completed);
        jobs.put(REGION + "::a-job", running);

        SageMakerService service = service(new InMemoryStorage<>(), endpoints, jobs);
        JsonNode request = mapper.readTree("{\"SortBy\":\"Status\",\"SortOrder\":\"Ascending\"}");
        assertEquals(List.of("z-endpoint", "a-endpoint"),
                names(service.listEndpoints(request, REGION), "Endpoints", "EndpointName"));
        assertEquals(List.of("z-job", "a-job"),
                names(service.listTrainingJobs(request, REGION), "TrainingJobSummaries", "TrainingJobName"));
    }

    @Test
    void listTrainingJobsUsesSortedStatusForCursorWhenJobChangesBeforeRendering() throws Exception {
        InMemoryStorage<String, TrainingJobResource> jobs = new InMemoryStorage<>();
        TrainingJobResource first = trainingJob("b-job");
        first.trainingJobStatus = "InProgress";
        TrainingJobResource second = trainingJob("a-job");
        second.trainingJobStatus = "InProgress";
        jobs.put(REGION + "::b-job", first);
        jobs.put(REGION + "::a-job", second);
        SageMakerService service = service(new InMemoryStorage<>(), new InMemoryStorage<>(), jobs,
                mapperMutatingBeforeRender(() -> first.trainingJobStatus = "Completed"));
        ObjectNode request = mapper.createObjectNode()
                .put("SortBy", "Status").put("SortOrder", "Descending").put("MaxResults", 1);

        JsonNode firstPage = service.listTrainingJobs(request, REGION);
        assertEquals(List.of("b-job"), names(firstPage, "TrainingJobSummaries", "TrainingJobName"));
        assertEquals("InProgress", firstPage.path("TrainingJobSummaries").get(0).path("TrainingJobStatus").asText());
        assertEquals("Completed", first.trainingJobStatus);

        JsonNode secondPage = service.listTrainingJobs(request.deepCopy()
                .put("NextToken", firstPage.path("NextToken").asText()), REGION);
        assertEquals(List.of("a-job"), names(secondPage, "TrainingJobSummaries", "TrainingJobName"));
    }

    @Test
    void listEndpointsUsesSortedStatusForCursorWhenEndpointChangesBeforeRendering() throws Exception {
        InMemoryStorage<String, EndpointResource> endpoints = new InMemoryStorage<>();
        EndpointResource first = endpoint("b-endpoint");
        EndpointResource second = endpoint("a-endpoint");
        endpoints.put(REGION + "::b-endpoint", first);
        endpoints.put(REGION + "::a-endpoint", second);
        SageMakerService service = service(new InMemoryStorage<>(), endpoints, new InMemoryStorage<>(),
                mapperMutatingBeforeRender(() -> first.endpointStatus = "Creating"));
        ObjectNode request = mapper.createObjectNode()
                .put("SortBy", "Status").put("SortOrder", "Descending").put("MaxResults", 1);

        JsonNode firstPage = service.listEndpoints(request, REGION);
        assertEquals(List.of("b-endpoint"), names(firstPage, "Endpoints", "EndpointName"));
        assertEquals("InService", firstPage.path("Endpoints").get(0).path("EndpointStatus").asText());
        assertEquals("Creating", first.endpointStatus);

        JsonNode secondPage = service.listEndpoints(request.deepCopy()
                .put("NextToken", firstPage.path("NextToken").asText()), REGION);
        assertEquals(List.of("a-endpoint"), names(secondPage, "Endpoints", "EndpointName"));
    }

    @Test
    void listCreationTimeCursorResumesAfterAnchorDeletion() throws Exception {
        InMemoryStorage<String, EndpointConfigResource> configs = new InMemoryStorage<>();
        for (String name : List.of("a", "m", "z")) {
            EndpointConfigResource config = endpointConfig(name);
            config.creationTime = name.equals("z") ? 3000 : 2000;
            configs.put(REGION + "::" + name, config);
        }
        SageMakerService service = service(configs, new InMemoryStorage<>(), new InMemoryStorage<>());
        JsonNode first = service.listEndpointConfigs(mapper.readTree("{\"MaxResults\":1}"), REGION);
        assertEquals(List.of("z"), names(first, "EndpointConfigs", "EndpointConfigName"));
        String cursor = first.path("NextToken").asText();
        configs.delete(REGION + "::z");

        JsonNode second = service.listEndpointConfigs(mapper.createObjectNode()
                .put("MaxResults", 1).put("NextToken", cursor), REGION);
        assertEquals(List.of("m"), names(second, "EndpointConfigs", "EndpointConfigName"));
        JsonNode third = service.listEndpointConfigs(mapper.createObjectNode()
                .put("MaxResults", 1).put("NextToken", second.path("NextToken").asText()), REGION);
        assertEquals(List.of("a"), names(third, "EndpointConfigs", "EndpointConfigName"));

        AwsException changedOrder = assertThrows(AwsException.class, () -> service.listEndpointConfigs(
                mapper.createObjectNode().put("SortOrder", "Ascending").put("NextToken", cursor), REGION));
        assertEquals("ValidationException", changedOrder.getErrorCode());
    }

    @Test
    void listEndpointAndTrainingCursorsResumeAfterAnchorDeletion() throws Exception {
        InMemoryStorage<String, EndpointResource> endpoints = new InMemoryStorage<>();
        EndpointResource olderEndpoint = endpoint("a-endpoint");
        olderEndpoint.creationTime = 1000;
        EndpointResource newerEndpoint = endpoint("z-endpoint");
        newerEndpoint.creationTime = 3000;
        endpoints.put(REGION + "::a-endpoint", olderEndpoint);
        endpoints.put(REGION + "::z-endpoint", newerEndpoint);

        InMemoryStorage<String, TrainingJobResource> jobs = new InMemoryStorage<>();
        TrainingJobResource olderJob = trainingJob("a-job");
        olderJob.creationTime = 1000;
        TrainingJobResource newerJob = trainingJob("z-job");
        newerJob.creationTime = 3000;
        jobs.put(REGION + "::a-job", olderJob);
        jobs.put(REGION + "::z-job", newerJob);

        SageMakerService service = service(new InMemoryStorage<>(), endpoints, jobs);
        JsonNode endpointFirst = service.listEndpoints(mapper.readTree("{\"MaxResults\":1}"), REGION);
        assertEquals(List.of("z-endpoint"), names(endpointFirst, "Endpoints", "EndpointName"));
        endpoints.delete(REGION + "::z-endpoint");
        JsonNode endpointNext = service.listEndpoints(mapper.createObjectNode()
                .put("MaxResults", 1).put("NextToken", endpointFirst.path("NextToken").asText()), REGION);
        assertEquals(List.of("a-endpoint"), names(endpointNext, "Endpoints", "EndpointName"));

        JsonNode jobFirst = service.listTrainingJobs(mapper.readTree("{\"MaxResults\":1}"), REGION);
        assertEquals(List.of("a-job"), names(jobFirst, "TrainingJobSummaries", "TrainingJobName"));
        jobs.delete(REGION + "::a-job");
        JsonNode jobNext = service.listTrainingJobs(mapper.createObjectNode()
                .put("MaxResults", 1).put("NextToken", jobFirst.path("NextToken").asText()), REGION);
        assertEquals(List.of("z-job"), names(jobNext, "TrainingJobSummaries", "TrainingJobName"));
        jobs.delete(REGION + "::z-job");

        EndpointResource firstStatusEndpoint = endpoint("a-status");
        firstStatusEndpoint.endpointStatus = "Creating";
        EndpointResource nextStatusEndpoint = endpoint("z-status");
        nextStatusEndpoint.endpointStatus = "Creating";
        endpoints.put(REGION + "::a-status", firstStatusEndpoint);
        endpoints.put(REGION + "::z-status", nextStatusEndpoint);
        JsonNode statusEndpointFirst = service.listEndpoints(mapper.readTree(
                "{\"MaxResults\":1,\"SortBy\":\"Status\",\"SortOrder\":\"Ascending\"}"), REGION);
        assertEquals(List.of("a-status"), names(statusEndpointFirst, "Endpoints", "EndpointName"));
        endpoints.delete(REGION + "::a-status");
        JsonNode statusEndpointNext = service.listEndpoints(mapper.createObjectNode()
                .put("MaxResults", 1).put("SortBy", "Status").put("SortOrder", "Ascending")
                .put("NextToken", statusEndpointFirst.path("NextToken").asText()), REGION);
        assertEquals(List.of("z-status"), names(statusEndpointNext, "Endpoints", "EndpointName"));

        TrainingJobResource firstStatusJob = trainingJob("a-status-job");
        firstStatusJob.trainingJobStatus = "Completed";
        TrainingJobResource nextStatusJob = trainingJob("z-status-job");
        nextStatusJob.trainingJobStatus = "Completed";
        jobs.put(REGION + "::a-status-job", firstStatusJob);
        jobs.put(REGION + "::z-status-job", nextStatusJob);
        JsonNode statusJobFirst = service.listTrainingJobs(mapper.readTree(
                "{\"MaxResults\":1,\"SortBy\":\"Status\",\"SortOrder\":\"Ascending\"}"), REGION);
        assertEquals(List.of("a-status-job"), names(statusJobFirst, "TrainingJobSummaries", "TrainingJobName"));
        jobs.delete(REGION + "::a-status-job");
        JsonNode statusJobNext = service.listTrainingJobs(mapper.createObjectNode()
                .put("MaxResults", 1).put("SortBy", "Status").put("SortOrder", "Ascending")
                .put("NextToken", statusJobFirst.path("NextToken").asText()), REGION);
        assertEquals(List.of("z-status-job"), names(statusJobNext, "TrainingJobSummaries", "TrainingJobName"));
    }

    @Test
    void listOpaqueCursorsRejectAnotherRegionOrOperation() throws Exception {
        InMemoryStorage<String, EndpointConfigResource> configs = new InMemoryStorage<>();
        EndpointConfigResource older = endpointConfig("older");
        older.creationTime = 1000;
        EndpointConfigResource newer = endpointConfig("newer");
        newer.creationTime = 3000;
        configs.put(REGION + "::older", older);
        configs.put(REGION + "::newer", newer);

        InMemoryStorage<String, EndpointResource> endpoints = new InMemoryStorage<>();
        endpoints.put(REGION + "::endpoint", endpoint("endpoint"));
        SageMakerService service = service(configs, endpoints, new InMemoryStorage<>());
        JsonNode first = service.listEndpointConfigs(mapper.createObjectNode().put("MaxResults", 1), REGION);
        String cursor = first.path("NextToken").asText();
        JsonNode next = mapper.createObjectNode().put("NextToken", cursor);

        AwsException otherRegion = assertThrows(AwsException.class,
                () -> service.listEndpointConfigs(next, "us-west-2"));
        assertEquals("ValidationException", otherRegion.getErrorCode());
        AwsException otherOperation = assertThrows(AwsException.class,
                () -> service.listEndpoints(next, REGION));
        assertEquals("ValidationException", otherOperation.getErrorCode());
    }

    @Test
    void listTrainingCursorRejectsChangedFilters() throws Exception {
        InMemoryStorage<String, TrainingJobResource> jobs = new InMemoryStorage<>();
        TrainingJobResource firstJob = trainingJob("alpha-first");
        firstJob.creationTime = 1000;
        TrainingJobResource secondJob = trainingJob("alpha-second");
        secondJob.creationTime = 3000;
        jobs.put(REGION + "::alpha-first", firstJob);
        jobs.put(REGION + "::alpha-second", secondJob);
        SageMakerService service = service(new InMemoryStorage<>(), new InMemoryStorage<>(), jobs);
        JsonNode first = service.listTrainingJobs(mapper.createObjectNode()
                .put("NameContains", "alpha").put("StatusEquals", "Completed").put("MaxResults", 1), REGION);
        String cursor = first.path("NextToken").asText();
        JsonNode sameFilters = service.listTrainingJobs(mapper.createObjectNode()
                .put("NameContains", "alpha").put("StatusEquals", "Completed").put("NextToken", cursor), REGION);
        assertEquals(List.of("alpha-second"), names(sameFilters, "TrainingJobSummaries", "TrainingJobName"));

        AwsException changedName = assertThrows(AwsException.class, () -> service.listTrainingJobs(
                mapper.createObjectNode().put("NameContains", "beta").put("StatusEquals", "Completed")
                        .put("NextToken", cursor), REGION));
        assertEquals("ValidationException", changedName.getErrorCode());
        AwsException changedStatus = assertThrows(AwsException.class, () -> service.listTrainingJobs(
                mapper.createObjectNode().put("NameContains", "alpha").put("StatusEquals", "Failed")
                        .put("NextToken", cursor), REGION));
        assertEquals("ValidationException", changedStatus.getErrorCode());
    }

    @Test
    void listRejectsUnmodeledSortValues() throws Exception {
        SageMakerService service = service(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>());
        AwsException invalidConfigKey = assertThrows(AwsException.class, () -> service.listEndpointConfigs(
                mapper.readTree("{\"SortBy\":\"Status\"}"), REGION));
        assertEquals("ValidationException", invalidConfigKey.getErrorCode());
        AwsException invalidOrder = assertThrows(AwsException.class, () -> service.listEndpoints(
                mapper.readTree("{\"SortOrder\":\"Newest\"}"), REGION));
        assertEquals("ValidationException", invalidOrder.getErrorCode());
    }

    @Test
    void listOperationsReportBothInvalidSortValues() throws Exception {
        SageMakerService service = service(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>());
        JsonNode request = mapper.readTree("{\"SortBy\":\"BadBy\",\"SortOrder\":\"BadOrder\"}");
        String sortOrderError = "Value 'BadOrder' at 'sortOrder' failed to satisfy constraint: "
                + "Member must satisfy enum value set: [Ascending, Descending]";

        String twoValueSortByError = "Value 'BadBy' at 'sortBy' failed to satisfy constraint: "
                + "Member must satisfy enum value set: [Name, CreationTime, Status]";
        String twoValueMessage = "2 validation errors detected: " + twoValueSortByError + "; " + sortOrderError;
        for (Supplier<ObjectNode> operation : List.of(
                (Supplier<ObjectNode>) () -> service.listEndpoints(request, REGION),
                () -> service.listTrainingJobs(request, REGION))) {
            AwsException error = assertThrows(AwsException.class, operation::get);
            assertEquals("ValidationException", error.getErrorCode());
            assertEquals(twoValueMessage, error.getMessage());
        }

        String modelSortByError = "Value 'BadBy' at 'sortBy' failed to satisfy constraint: "
                + "Member must satisfy enum value set: [Name, CreationTime]";
        String modelMessage = "2 validation errors detected: " + modelSortByError + "; " + sortOrderError;
        for (Supplier<ObjectNode> operation : List.of(
                (Supplier<ObjectNode>) () -> service.listModels(request, REGION),
                () -> service.listEndpointConfigs(request, REGION))) {
            AwsException error = assertThrows(AwsException.class, operation::get);
            assertEquals("ValidationException", error.getErrorCode());
            assertEquals(modelMessage, error.getMessage());
        }
    }

    private SageMakerService service(InMemoryStorage<String, EndpointConfigResource> configs,
                                     InMemoryStorage<String, EndpointResource> endpoints,
                                     InMemoryStorage<String, TrainingJobResource> jobs) {
        return service(configs, endpoints, jobs, mapper);
    }

    private SageMakerService service(InMemoryStorage<String, EndpointConfigResource> configs,
                                     InMemoryStorage<String, EndpointResource> endpoints,
                                     InMemoryStorage<String, TrainingJobResource> jobs,
                                     ObjectMapper serviceMapper) {
        return new SageMakerService(new InMemoryStorage<String, ModelResource>(), configs, endpoints, jobs,
                new RegionResolver(REGION, "000000000000"), serviceMapper,
                mock(SageMakerEndpointManager.class), mock(SageMakerTrainingRunner.class));
    }

    private static ObjectMapper mapperMutatingBeforeRender(Runnable mutation) {
        return new ObjectMapper() {
            private boolean mutated;

            @Override
            public ObjectNode createObjectNode() {
                if (!mutated) {
                    mutated = true;
                    mutation.run();
                }
                return super.createObjectNode();
            }
        };
    }

    private static EndpointResource endpoint(String name) {
        EndpointResource endpoint = new EndpointResource();
        endpoint.endpointName = name;
        endpoint.endpointArn = "arn:aws:sagemaker:us-east-1:000000000000:endpoint/" + name;
        endpoint.endpointStatus = "InService";
        return endpoint;
    }

    private static EndpointConfigResource endpointConfig(String name) {
        EndpointConfigResource config = new EndpointConfigResource();
        config.endpointConfigName = name;
        config.endpointConfigArn = "arn:aws:sagemaker:us-east-1:000000000000:endpoint-config/" + name;
        return config;
    }

    private static TrainingJobResource trainingJob(String name) {
        TrainingJobResource job = new TrainingJobResource();
        job.trainingJobName = name;
        job.trainingJobArn = "arn:aws:sagemaker:us-east-1:000000000000:training-job/" + name;
        job.trainingJobStatus = "Completed";
        return job;
    }

    private static List<String> names(JsonNode response, String itemsField, String nameField) {
        List<String> names = new ArrayList<>();
        response.path(itemsField).forEach(item -> names.add(item.path(nameField).asText()));
        return names;
    }
}
