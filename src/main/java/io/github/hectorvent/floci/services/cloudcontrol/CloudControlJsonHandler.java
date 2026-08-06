package io.github.hectorvent.floci.services.cloudcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
public class CloudControlJsonHandler {

    private final CloudControlService service;
    private final ObjectMapper mapper;

    @Inject
    public CloudControlJsonHandler(CloudControlService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "ListResources" -> listResources(request, region);
            case "GetResource" -> getResource(request, region);
            case "CreateResource" -> progressResponse(
                    service.createResource(region, required(request, "TypeName"),
                            request.path("DesiredState").asText(null)));
            case "DeleteResource" -> progressResponse(
                    service.deleteResource(region, required(request, "TypeName"), required(request, "Identifier")));
            case "GetResourceRequestStatus" -> progressResponse(
                    service.requestStatus(required(request, "RequestToken")));
            default -> throw new AwsException("UnsupportedOperation",
                    "Operation " + action + " is not supported.", 400);
        };
    }

    private Response getResource(JsonNode request, String region) {
        String typeName = required(request, "TypeName");
        String identifier = required(request, "Identifier");
        CloudControlService.ResourceDescription resource = service.getResource(region, typeName, identifier);
        ObjectNode response = mapper.createObjectNode();
        response.put("TypeName", typeName);
        ObjectNode desc = response.putObject("ResourceDescription");
        desc.put("Identifier", resource.identifier());
        desc.put("Properties", resource.properties());
        return Response.ok(response).build();
    }

    private Response progressResponse(CloudControlService.ProgressEvent event) {
        ObjectNode response = mapper.createObjectNode();
        ObjectNode pe = response.putObject("ProgressEvent");
        pe.put("TypeName", event.typeName());
        pe.put("Identifier", event.identifier());
        pe.put("RequestToken", event.requestToken());
        pe.put("Operation", event.operation());
        pe.put("OperationStatus", event.operationStatus());
        if (event.statusMessage() != null) {
            pe.put("StatusMessage", event.statusMessage());
        }
        if (event.resourceModel() != null) {
            pe.put("ResourceModel", event.resourceModel());
        }
        return Response.ok(response).build();
    }

    /**
     * InvalidRequestException is the code Cloud Control declares for a malformed request, so it is
     * what an SDK maps onto a typed exception. ValidationException is not in the service model.
     */
    private String required(JsonNode request, String field) {
        String value = request.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new AwsException("InvalidRequestException", field + " is required.", 400);
        }
        return value;
    }

    private Response listResources(JsonNode request, String region) {
        String typeName = required(request, "TypeName");
        ObjectNode response = mapper.createObjectNode();
        response.put("TypeName", typeName);
        ArrayNode resources = response.putArray("ResourceDescriptions");
        for (CloudControlService.ResourceDescription resource : service.listResources(region, typeName)) {
            ObjectNode node = mapper.createObjectNode();
            node.put("Identifier", resource.identifier());
            node.put("Properties", resource.properties());
            resources.add(node);
        }
        return Response.ok(response).build();
    }
}
