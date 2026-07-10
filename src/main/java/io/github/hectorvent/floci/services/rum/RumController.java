package io.github.hectorvent.floci.services.rum;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.rum.model.AppMonitor;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * CloudWatch RUM (Smithy restJson1) — minimal app-monitor lifecycle.
 *
 * <p>Exists so the CDK RUM AppMonitor custom resource ({@code Custom::AWS} / AwsCustomResource) —
 * which calls {@code rum:createAppMonitor}/{@code updateAppMonitor}/{@code deleteAppMonitor} — gets a
 * JSON response. Without a {@code rum} service these requests fell through to the S3 catch-all
 * ({@code @Path("/{bucket}")}) and returned an XML error, which the restJson1 SDK could not parse.
 *
 * <p>The literal {@code /appmonitor} paths take JAX-RS precedence over S3's {@code /{bucket}} and
 * {@code /{bucket}/{key}} template routes, so these routes win with no extra routing wiring.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RumController {

    private final RumService service;
    private final ObjectMapper objectMapper;

    @Inject
    public RumController(RumService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new AwsException("ValidationException", "Request body is not valid JSON.", 400);
        }
    }

    @POST
    @Path("/appmonitor")
    public Response createAppMonitor(String body) {
        JsonNode request = parse(body);
        AppMonitor monitor = service.createAppMonitor(
                request.path("Name").asText(null), request.path("Domain").asText(null));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Id", monitor.getId());
        return Response.ok(response).build();
    }

    @GET
    @Path("/appmonitor/{name}")
    public Response getAppMonitor(@PathParam("name") String name) {
        AppMonitor monitor = service.getAppMonitor(name);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("AppMonitor", objectMapper.valueToTree(monitor));
        return Response.ok(response).build();
    }

    @PATCH
    @Path("/appmonitor/{name}")
    public Response updateAppMonitor(@PathParam("name") String name, String body) {
        JsonNode request = parse(body);
        service.updateAppMonitor(name, request.path("Domain").asText(null));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @DELETE
    @Path("/appmonitor/{name}")
    public Response deleteAppMonitor(@PathParam("name") String name) {
        service.deleteAppMonitor(name);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/appmonitors")
    public Response listAppMonitors(String body) {
        ObjectNode response = objectMapper.createObjectNode();
        var summaries = response.putArray("AppMonitorSummaries");
        for (AppMonitor monitor : service.listAppMonitors()) {
            summaries.addPOJO(monitor);
        }
        return Response.ok(response).build();
    }
}
