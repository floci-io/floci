package io.github.hectorvent.floci.services.batch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class BatchController {

    private static final Logger LOG = Logger.getLogger(BatchController.class);

    private final BatchService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public BatchController(BatchService service, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/v1/createcomputeenvironment")
    public Response createComputeEnvironment(@Context HttpHeaders headers, String body) {
        return handle(headers, body, (request, region) ->
                Response.ok(service.createComputeEnvironment(request, region)).build());
    }

    @POST
    @Path("/v1/describecomputeenvironments")
    public Response describeComputeEnvironments(@Context HttpHeaders headers, String body) {
        return handle(headers, body, (request, region) ->
                Response.ok(service.describeComputeEnvironments(request)).build());
    }

    @POST
    @Path("/v1/createjobqueue")
    public Response createJobQueue(@Context HttpHeaders headers, String body) {
        return handle(headers, body, (request, region) ->
                Response.ok(service.createJobQueue(request, region)).build());
    }

    @POST
    @Path("/v1/describejobqueues")
    public Response describeJobQueues(@Context HttpHeaders headers, String body) {
        return handle(headers, body, (request, region) ->
                Response.ok(service.describeJobQueues(request)).build());
    }

    @POST
    @Path("/v1/registerjobdefinition")
    public Response registerJobDefinition(@Context HttpHeaders headers, String body) {
        return handle(headers, body, (request, region) ->
                Response.ok(service.registerJobDefinition(request, region)).build());
    }

    @POST
    @Path("/v1/deregisterjobdefinition")
    public Response deregisterJobDefinition(@Context HttpHeaders headers, String body) {
        return handle(headers, body, (request, region) ->
                Response.ok(service.deregisterJobDefinition(request)).build());
    }

    @POST
    @Path("/v1/describejobdefinitions")
    public Response describeJobDefinitions(@Context HttpHeaders headers, String body) {
        return handle(headers, body, (request, region) ->
                Response.ok(service.describeJobDefinitions(request)).build());
    }

    @POST
    @Path("/v1/submitjob")
    public Response submitJob(@Context HttpHeaders headers, String body) {
        return handle(headers, body, (request, region) ->
                Response.ok(service.submitJob(request, region)).build());
    }

    @POST
    @Path("/v1/describejobs")
    public Response describeJobs(@Context HttpHeaders headers, String body) {
        return handle(headers, body, (request, region) ->
                Response.ok(service.describeJobs(request)).build());
    }

    @POST
    @Path("/v1/listjobs")
    public Response listJobs(@Context HttpHeaders headers, String body) {
        return handle(headers, body, (request, region) ->
                Response.ok(service.listJobs(request)).build());
    }

    private Response handle(HttpHeaders headers, String body, Handler handler) {
        try {
            JsonNode request = body == null || body.isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(body);
            return handler.handle(request, regionResolver.resolveRegion(headers));
        } catch (AwsException e) {
            return error(e.getHttpStatus(), e.getErrorCode(), e.getMessage());
        } catch (JsonProcessingException e) {
            return error(400, "ClientException", "Malformed JSON request body.");
        } catch (Exception e) {
            LOG.error("Error processing AWS Batch request", e);
            return error(500, "ServerException", e.getMessage());
        }
    }

    private static Response error(int status, String code, String message) {
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .header("X-Amzn-Errortype", code)
                .entity(new AwsErrorResponse(code, message))
                .build();
    }

    @FunctionalInterface
    private interface Handler {
        Response handle(JsonNode request, String region);
    }
}
