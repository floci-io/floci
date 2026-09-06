package io.github.hectorvent.floci.services.controltower;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.services.controltower.model.EnabledControl;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ControlTowerControlController {
    private final ControlTowerControlService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;
    private final RequestContext requestContext;

    @Inject
    public ControlTowerControlController(ControlTowerControlService service, ObjectMapper objectMapper,
                                         RegionResolver regionResolver, RequestContext requestContext) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
        this.requestContext = requestContext;
    }

    @POST @Path("/enable-control")
    public Response enable(@Context HttpHeaders headers, String body) {
        var result = service.enable(account(), region(headers), parse(body));
        return Response.ok(objectMapper.createObjectNode().put("arn", result.arn())
                .put("operationIdentifier", result.operationIdentifier())).build();
    }

    @POST @Path("/list-enabled-controls")
    public Response list(@Context HttpHeaders headers, String body) {
        var result = service.list(region(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        var array = response.putArray("enabledControls");
        result.controls().forEach(control -> array.add(summary(control)));
        if (result.nextToken() != null) response.put("nextToken", result.nextToken());
        return Response.ok(response).build();
    }

    @POST @Path("/get-enabled-control")
    public Response get(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        EnabledControl control = service.get(region(headers), requireText(request, "enabledControlIdentifier"));
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode details = summary(control);
        if (control.getParameters() != null) details.set("parameters", control.getParameters());
        response.set("enabledControlDetails", details);
        return Response.ok(response).build();
    }

    @POST @Path("/update-enabled-control")
    public Response update(@Context HttpHeaders headers, String body) {
        String operationId = service.update(account(), region(headers), parse(body));
        return Response.ok(objectMapper.createObjectNode().put("operationIdentifier", operationId)).build();
    }

    @POST @Path("/reset-enabled-control")
    public Response reset(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        String operationId = service.reset(account(), region(headers), requireText(request, "enabledControlIdentifier"));
        return Response.ok(objectMapper.createObjectNode().put("operationIdentifier", operationId)).build();
    }

    @POST @Path("/get-control-operation")
    public Response operation(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        var operation = service.operation(account(), region(headers), requireText(request, "operationIdentifier"));
        ObjectNode node = objectMapper.createObjectNode();
        node.put("operationIdentifier", operation.operationIdentifier());
        node.put("operationType", operation.operationType());
        node.put("status", operation.status());
        node.put("controlIdentifier", operation.controlIdentifier());
        node.put("enabledControlIdentifier", operation.enabledControlIdentifier());
        node.put("targetIdentifier", operation.targetIdentifier());
        String now = Instant.now().toString();
        node.put("startTime", now);
        node.put("endTime", now);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("controlOperation", node);
        return Response.ok(response).build();
    }

    private ObjectNode summary(EnabledControl control) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", control.getArn());
        node.put("controlIdentifier", control.getControlIdentifier());
        node.put("targetIdentifier", control.getTargetIdentifier());
        ObjectNode status = node.putObject("statusSummary");
        status.put("status", control.getStatus());
        status.put("lastOperationIdentifier", control.getLastOperationIdentifier());
        ObjectNode drift = node.putObject("driftStatusSummary");
        drift.put("driftStatus", control.getDriftStatus());
        return node;
    }

    private JsonNode parse(String body) {
        try {
            JsonNode request = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
            if (request == null || !request.isObject()) throw validation("Request body must be a JSON object.");
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw validation("Request body is not valid JSON.");
        }
    }

    private static String requireText(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || !value.isTextual()) throw validation(field + " must be a string.");
        return value.asText();
    }

    private String region(HttpHeaders headers) { return regionResolver.resolveRegion(headers); }
    private String account() { return requestContext.getAccountId(); }
    private static AwsException validation(String message) { return new AwsException("ValidationException", message, 400); }
}
