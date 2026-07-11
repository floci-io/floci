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
            default -> throw new AwsException("UnsupportedOperation",
                    "Operation " + action + " is not supported.", 400);
        };
    }

    private Response listResources(JsonNode request, String region) {
        String typeName = request.path("TypeName").asText(null);
        if (typeName == null || typeName.isBlank()) {
            throw new AwsException("ValidationException", "TypeName is required.", 400);
        }
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
