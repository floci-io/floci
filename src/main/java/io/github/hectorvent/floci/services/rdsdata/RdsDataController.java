package io.github.hectorvent.floci.services.rdsdata;

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
public class RdsDataController {

    private static final Logger LOG = Logger.getLogger(RdsDataController.class);

    private final RdsDataService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public RdsDataController(RdsDataService service, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/Execute")
    public Response executeStatement(@Context HttpHeaders headers, String body) {
        return handle(headers, body, (request, region) -> Response.ok(service.executeStatement(request, region)).build());
    }

    @POST
    @Path("/ExecuteSql")
    public Response executeSql() {
        return error(400, "BadRequestException",
                "ExecuteSql is not supported by this local RDS Data API implementation.");
    }

    @POST
    @Path("/BeginTransaction")
    public Response beginTransaction(@Context HttpHeaders headers, String body) {
        return handle(headers, body, (request, region) -> Response.ok(service.beginTransaction(request, region)).build());
    }

    @POST
    @Path("/BatchExecute")
    public Response batchExecuteStatement() {
        return error(400, "BadRequestException",
                "BatchExecuteStatement is not supported by this local RDS Data API implementation.");
    }

    @POST
    @Path("/CommitTransaction")
    public Response commitTransaction(@Context HttpHeaders headers, String body) {
        return handle(headers, body, (request, region) -> Response.ok(service.commitTransaction(request)).build());
    }

    @POST
    @Path("/RollbackTransaction")
    public Response rollbackTransaction(@Context HttpHeaders headers, String body) {
        return handle(headers, body, (request, region) -> Response.ok(service.rollbackTransaction(request)).build());
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
            return error(400, "BadRequestException", "Malformed JSON request body.");
        } catch (Exception e) {
            LOG.error("Error processing RDS Data API request", e);
            return error(500, "InternalFailure", e.getMessage());
        }
    }

    private static Response error(int status, String code, String message) {
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .header("X-Amzn-Errortype", code)
                .header("x-amzn-query-error", code + ";" + (status < 500 ? "Sender" : "Receiver"))
                .entity(new AwsErrorResponse(code, message))
                .build();
    }

    @FunctionalInterface
    private interface Handler {
        Response handle(JsonNode request, String region);
    }
}
