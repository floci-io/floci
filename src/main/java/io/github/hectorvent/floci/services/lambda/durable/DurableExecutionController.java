package io.github.hectorvent.floci.services.lambda.durable;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.lambda.LambdaArnUtils;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableErrorObject;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableExecution;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableExecutionStatus;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableHistoryEvent;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Lambda durable execution APIs under the {@code /2025-12-01} version prefix. The literal
 * prefix keeps these routes off S3's bucket catch-all, as the other versioned Lambda controllers do.
 *
 * <p>An execution ARN holds {@code /}. The AWS SDKs send it percent-encoded as one path segment,
 * and the CLI and curl send it raw, so the ARN templates match greedily.
 */
@Path("/2025-12-01")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class DurableExecutionController {

    /** Valid Statuses filters for a state Floci never puts an execution in. */
    private static final Set<String> UNMODELLED_STATUSES = Set.of("PAUSED", "PAUSING", "DELETING");

    private final DurableExecutionService service;
    private final LambdaService lambdaService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public DurableExecutionController(DurableExecutionService service, LambdaService lambdaService,
                                      RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.service = service;
        this.lambdaService = lambdaService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/durable-executions/{arn: .+}/checkpoint")
    public Response checkpoint(@PathParam("arn") String arn, String body) {
        Map<String, Object> request = readObject(body);
        DurableExecutionService.CheckpointResult result = service.checkpoint(ownedArn(arn),
                stringMember(request, "CheckpointToken"), DurableWire.parseUpdates(request.get("Updates")));
        ObjectNode response = objectMapper.createObjectNode();
        if (result.checkpointToken() != null) {
            response.put("CheckpointToken", result.checkpointToken());
        }
        response.set("NewExecutionState", DurableWire.operations(result.newExecutionState(), null));
        return Response.ok(response).build();
    }

    @GET
    @Path("/durable-executions/{arn: .+}/state")
    public Response getState(@PathParam("arn") String arn,
                             @QueryParam("CheckpointToken") String checkpointToken,
                             @QueryParam("Marker") String marker,
                             @QueryParam("MaxItems") String maxItems) {
        DurableExecutionService.StatePage page = service.getState(ownedArn(arn), checkpointToken, marker,
                parseMaxItems(maxItems));
        return Response.ok(DurableWire.operations(page.operations(), page.nextMarker())).build();
    }

    @GET
    @Path("/durable-executions/{arn: .+}/history")
    public Response getHistory(@PathParam("arn") String arn,
                               @QueryParam("IncludeExecutionData") String includeExecutionData,
                               @QueryParam("Marker") String marker,
                               @QueryParam("MaxItems") String maxItems,
                               @QueryParam("ReverseOrder") String reverseOrder) {
        PaginatedResult<DurableHistoryEvent> page = service.history(ownedArn(arn), parseMaxItems(maxItems), marker,
                Boolean.parseBoolean(reverseOrder));
        boolean includeData = Boolean.parseBoolean(includeExecutionData);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode events = response.putArray("Events");
        for (DurableHistoryEvent event : page.items()) {
            events.add(DurableWire.historyEvent(event, includeData));
        }
        if (page.nextToken() != null) {
            response.put("NextMarker", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/durable-executions/{arn: .+}/stop")
    public Response stop(@PathParam("arn") String arn, String body) {
        DurableErrorObject error = body == null || body.isBlank() ? null : DurableWire.parseError(readObject(body));
        if (error != null && error.getErrorMessage() == null && error.getErrorType() == null
                && error.getErrorData() == null && error.getStackTrace() == null) {
            error = null;
        }
        DurableExecution execution = service.stop(ownedArn(arn), error);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("StopTimestamp", execution.getEndTimestamp() / 1000.0);
        return Response.ok(response).build();
    }

    /** IncludeExecutionData defaults to true here, unlike GetDurableExecutionHistory. */
    @GET
    @Path("/durable-executions/{arn: .+}")
    public Response getExecution(@PathParam("arn") String arn,
                                 @QueryParam("IncludeExecutionData") String includeExecutionData) {
        boolean includeData = includeExecutionData == null || Boolean.parseBoolean(includeExecutionData);
        return Response.ok(DurableWire.execution(service.get(ownedArn(arn)), includeData)).build();
    }

    @GET
    @Path("/functions/{functionName}/durable-executions")
    public Response listByFunction(@Context HttpHeaders headers,
                                   @PathParam("functionName") String functionName,
                                   @QueryParam("Qualifier") String qualifier,
                                   @QueryParam("DurableExecutionName") String durableExecutionName,
                                   @QueryParam("Statuses") List<String> statuses,
                                   @QueryParam("StartedAfter") String startedAfter,
                                   @QueryParam("StartedBefore") String startedBefore,
                                   @QueryParam("ReverseOrder") String reverseOrder,
                                   @QueryParam("Marker") String marker,
                                   @QueryParam("MaxItems") String maxItems) {
        String region = regionResolver.resolveRegion(headers);
        Set<DurableExecutionStatus> statusFilter = parseStatuses(statuses);
        boolean onlyUnmodelledStatuses = statuses != null && !statuses.isEmpty() && statusFilter.isEmpty();
        LambdaFunction fn = lambdaService.getFunction(region, functionName, qualifier);
        boolean qualified = (qualifier != null && !qualifier.isBlank())
                || LambdaArnUtils.resolve(functionName).qualifier() != null;
        DurableExecutionService.ListRequest request = new DurableExecutionService.ListRequest(fn.getAccountId(),
                region, fn.getFunctionName(), qualified ? fn.getVersion() : null, durableExecutionName, statusFilter,
                parseTimestamp(startedAfter, "startedAfter"), parseTimestamp(startedBefore, "startedBefore"),
                Boolean.parseBoolean(reverseOrder), parseMaxItems(maxItems), marker);
        PaginatedResult<DurableExecution> page = onlyUnmodelledStatuses
                ? new PaginatedResult<>(List.of(), null) : service.list(request);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode executions = response.putArray("DurableExecutions");
        for (DurableExecution execution : page.items()) {
            executions.add(DurableWire.executionSummary(execution));
        }
        if (page.nextToken() != null) {
            response.put("NextMarker", page.nextToken());
        }
        return Response.ok(response).build();
    }

    /** An execution of another account is not found, as AWS gives no cross-account access to executions. */
    private String ownedArn(String arn) {
        if (!DurableExecutionService.parseArn(arn).accountId().equals(regionResolver.getAccountId())) {
            throw new AwsException("ResourceNotFoundException", DurableExecutionService.NOT_FOUND, 404);
        }
        return arn;
    }

    private Map<String, Object> readObject(String body) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> request = objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});
            return request == null ? Map.of() : request;
        } catch (IOException e) {
            throw new AwsException("SerializationException", "Request body must be a JSON object", 400);
        }
    }

    private static String stringMember(Map<String, Object> request, String member) {
        Object value = request.get(member);
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        throw new AwsException("SerializationException", member + " must be a string", 400);
    }

    private static Integer parseMaxItems(String maxItems) {
        return Pagination.parseMaxResults(maxItems, "InvalidParameterValueException");
    }

    private static Set<DurableExecutionStatus> parseStatuses(List<String> statuses) {
        Set<DurableExecutionStatus> parsed = EnumSet.noneOf(DurableExecutionStatus.class);
        if (statuses == null) {
            return parsed;
        }
        for (String status : statuses) {
            if (UNMODELLED_STATUSES.contains(status)) {
                continue;
            }
            try {
                parsed.add(DurableExecutionStatus.valueOf(status));
            } catch (IllegalArgumentException e) {
                throw new AwsException("ValidationException",
                        "1 validation error detected: Value '" + statuses + "' at 'statuses' failed to satisfy "
                                + "constraint: Member must satisfy constraint: [Member must satisfy enum value set: "
                                + "[SUCCEEDED, TIMED_OUT, DELETING, STOPPED, PAUSED, PAUSING, FAILED, RUNNING], "
                                + "Member must not be null]", 400);
            }
        }
        return parsed;
    }

    private static Long parseTimestamp(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (DateTimeParseException expected) {
            // Not ISO 8601. Some SDKs send epoch seconds instead.
            double seconds;
            try {
                seconds = Double.parseDouble(value);
            } catch (NumberFormatException e) {
                throw new AwsException("InvalidParameterValueException", field + " must be a timestamp", 400);
            }
            if (!Double.isFinite(seconds)) {
                throw new AwsException("InvalidParameterValueException", field + " must be a timestamp", 400);
            }
            return (long) (seconds * 1000);
        }
    }
}
