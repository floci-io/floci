package io.github.hectorvent.floci.services.sagemaker;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
public class SageMakerJsonHandler {
    private final SageMakerService service;

    @Inject
    public SageMakerJsonHandler(SageMakerService service) {
        this.service = service;
    }

    public Response handle(String action, JsonNode request, String region) {
        Object entity = switch (action) {
            case "CreateModel" -> service.createModel(request, region);
            case "DescribeModel" -> service.describeModel(request);
            case "DeleteModel" -> service.deleteModel(request);
            case "ListModels" -> service.listModels(request);
            case "CreateEndpointConfig" -> service.createEndpointConfig(request, region);
            case "DescribeEndpointConfig" -> service.describeEndpointConfig(request);
            case "DeleteEndpointConfig" -> service.deleteEndpointConfig(request);
            case "ListEndpointConfigs" -> service.listEndpointConfigs(request);
            case "CreateEndpoint" -> service.createEndpoint(request, region);
            case "DescribeEndpoint" -> service.describeEndpoint(request);
            case "DeleteEndpoint" -> service.deleteEndpoint(request);
            case "ListEndpoints" -> service.listEndpoints(request);
            case "UpdateEndpoint" -> service.updateEndpoint(request);
            case "CreateTrainingJob" -> service.createTrainingJob(request, region);
            case "DescribeTrainingJob" -> service.describeTrainingJob(request);
            case "ListTrainingJobs" -> service.listTrainingJobs(request);
            case "StopTrainingJob" -> service.stopTrainingJob(request);
            case "AddTags" -> service.addTags(request);
            case "ListTags" -> service.listTags(request);
            case "DeleteTags" -> service.deleteTags(request);
            default -> throw SageMakerService.validation("Action " + action + " is not supported");
        };
        return Response.ok(entity).build();
    }
}
