package io.github.hectorvent.floci.services.lambda.durable;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private static final int MAX_CALLBACK_PAYLOAD_BYTES = 1024 * 1024;

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
    public Response checkpoint(@Context HttpHeaders headers, @PathParam("arn") String arn, String body) {
        Map<String, Object> request = readObject(body);
        DurableExecutionService.CheckpointResult result = service.checkpoint(ownedArn(headers, arn),
                stringMember(request, "CheckpointToken"), stringMember(request, "ClientToken"),
                DurableWire.parseUpdates(request.get("Updates")));
        ObjectNode response = objectMapper.createObjectNode();
        if (result.checkpointToken() != null) {
            response.put("CheckpointToken", result.checkpointToken());
        }
        response.set("NewExecutionState", DurableWire.operations(result.newExecutionState(), null));
        return Response.ok(response).build();
    }

    @GET
    @Path("/durable-executions/{arn: .+}/state")
    public Response getState(@Context HttpHeaders headers, @PathParam("arn") String arn,
                             @QueryParam("CheckpointToken") String checkpointToken,
                             @QueryParam("Marker") String marker,
                             @QueryParam("MaxItems") String maxItems) {
        Integer pageSize = parseMaxItems(maxItems);
        requireValid(DurableExecutionService.maxItemsViolation(pageSize));
        DurableExecutionService.StatePage page = service.getState(ownedArn(headers, arn), checkpointToken, marker,
                pageSize);
        return Response.ok(DurableWire.operations(page.operations(), page.nextMarker())).build();
    }

    @GET
    @Path("/durable-executions/{arn: .+}/history")
    public Response getHistory(@Context HttpHeaders headers, @PathParam("arn") String arn,
                               @QueryParam("IncludeExecutionData") String includeExecutionData,
                               @QueryParam("Marker") String marker,
                               @QueryParam("MaxItems") String maxItems,
                               @QueryParam("ReverseOrder") String reverseOrder) {
        Integer pageSize = parseMaxItems(maxItems);
        requireValid(DurableExecutionService.maxItemsViolation(pageSize));
        PaginatedResult<DurableHistoryEvent> page = service.history(ownedArn(headers, arn), pageSize, marker,
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
    public Response stop(@Context HttpHeaders headers, @PathParam("arn") String arn, String body) {
        DurableErrorObject error = body == null || body.isBlank() ? null : DurableWire.parseError(readObject(body));
        if (error != null && error.getErrorMessage() == null && error.getErrorType() == null
                && error.getErrorData() == null && error.getStackTrace() == null) {
            error = null;
        }
        DurableExecution execution = service.stop(ownedArn(headers, arn), error);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("StopTimestamp", execution.getEndTimestamp() / 1000.0);
        return Response.ok(response).build();
    }

    /** IncludeExecutionData defaults to true here, unlike GetDurableExecutionHistory. */
    @GET
    @Path("/durable-executions/{arn: .+}")
    public Response getExecution(@Context HttpHeaders headers, @PathParam("arn") String arn,
                                 @QueryParam("IncludeExecutionData") String includeExecutionData) {
        boolean includeData = includeExecutionData == null || Boolean.parseBoolean(includeExecutionData);
        return Response.ok(DurableWire.execution(service.get(ownedArn(headers, arn)), includeData)).build();
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
        // AWS converts types first, then checks values, then the single-status rule, then the function.
        Long after = parseTimestamp(startedAfter);
        Long before = parseTimestamp(startedBefore);
        Integer pageSize = parseMaxItems(maxItems);
        requireValid(DurableExecutionService.maxItemsViolation(pageSize), statusesViolation(statuses));
        Set<DurableExecutionStatus> statusFilter = parseStatuses(statuses);
        LambdaFunction fn = lambdaService.getFunction(region, functionName, qualifier);
        boolean qualified = (qualifier != null && !qualifier.isBlank())
                || LambdaArnUtils.resolve(functionName).qualifier() != null;
        DurableExecutionService.ListRequest request = new DurableExecutionService.ListRequest(fn.getAccountId(),
                region, fn.getFunctionName(), qualified ? fn.getVersion() : null, durableExecutionName, statusFilter,
                after, before, Boolean.parseBoolean(reverseOrder), pageSize, marker);
        PaginatedResult<DurableExecution> page = service.list(request);
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

    @POST
    @Path("/durable-execution-callbacks/{callbackId: .+}/succeed")
    public Response callbackSucceed(@Context HttpHeaders headers, @PathParam("callbackId") String callbackId,
                                    byte[] body) {
        if (body != null && MAX_CALLBACK_PAYLOAD_BYTES < body.length) {
            throw new AwsException("ValidationException", "1 validation error detected: Value at 'result' failed to "
                    + "satisfy constraint: Member must have length less than or equal to " + MAX_CALLBACK_PAYLOAD_BYTES,
                    400);
        }
        String result = body == null || body.length == 0 ? null : new String(body, StandardCharsets.UTF_8);
        service.completeCallback(callbackId, regionResolver.getAccountId(), regionResolver.resolveRegion(headers), true,
                result, null);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    /**
     * Without a body the callback fails with no Error, and AWS does not invoke the function for it.
     * Any other body must be a JSON object.
     */
    @POST
    @Path("/durable-execution-callbacks/{callbackId: .+}/fail")
    public Response callbackFail(@Context HttpHeaders headers, @PathParam("callbackId") String callbackId,
                                 String body) {
        DurableErrorObject error = body == null || body.isEmpty() ? null : parseCallbackError(body);
        // AWS measures the error as compact JSON, whatever spacing the request used.
        if (error != null
                && MAX_CALLBACK_PAYLOAD_BYTES < DurableCheckpointApplier.utf8Length(DurableWire.error(error).toString())) {
            throw new AwsException("InvalidParameterValueException", "Error object size must be less than or equal to "
                    + MAX_CALLBACK_PAYLOAD_BYTES + " bytes.", 400);
        }
        service.completeCallback(callbackId, regionResolver.getAccountId(), regionResolver.resolveRegion(headers), false,
                null, error);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/durable-execution-callbacks/{callbackId: .+}/heartbeat")
    public Response callbackHeartbeat(@Context HttpHeaders headers, @PathParam("callbackId") String callbackId) {
        service.heartbeatCallback(callbackId, regionResolver.getAccountId(), regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    /**
     * An execution of another account is not found. One of another region reads as "Function not found",
     * as AWS answers for an execution ARN of another region.
     */
    private String ownedArn(HttpHeaders headers, String arn) {
        DurableExecutionService.ArnParts parts = DurableExecutionService.parseArn(arn);
        if (!parts.accountId().equals(regionResolver.getAccountId())) {
            throw new AwsException("ResourceNotFoundException", DurableExecutionService.NOT_FOUND, 404);
        }
        if (!parts.region().equals(regionResolver.resolveRegion(headers))) {
            throw new AwsException("ResourceNotFoundException", "Function not found", 404);
        }
        return arn;
    }

    /** AWS answers whitespace, null or broken JSON with a SerializationException that has no message. */
    private DurableErrorObject parseCallbackError(String body) {
        Map<String, Object> error;
        try {
            error = objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            error = null;
        }
        if (error == null) {
            throw new AwsException("SerializationException", null, 400);
        }
        return DurableWire.parseError(error);
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
        if (maxItems == null || maxItems.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(maxItems);
        } catch (NumberFormatException e) {
            throw new AwsException("SerializationException", "'" + maxItems + "' can not be converted to Integer", 400);
        }
    }

    private static void requireValid(String... violations) {
        List<String> found = Arrays.stream(violations).filter(Objects::nonNull).toList();
        if (!found.isEmpty()) {
            throw DurableExecutionService.validationError(found);
        }
    }

    private static String statusesViolation(List<String> statuses) {
        if (statuses == null) {
            return null;
        }
        for (String status : statuses) {
            if (!UNMODELLED_STATUSES.contains(status) && !isExecutionStatus(status)) {
                return "Value '" + statuses + "' at 'statuses' failed to satisfy constraint: Member must satisfy "
                        + "constraint: [Member must satisfy enum value set: [SUCCEEDED, TIMED_OUT, DELETING, STOPPED, "
                        + "PAUSED, PAUSING, FAILED, RUNNING], Member must not be null]";
            }
        }
        return null;
    }

    private static boolean isExecutionStatus(String status) {
        for (DurableExecutionStatus candidate : DurableExecutionStatus.values()) {
            if (candidate.name().equals(status)) {
                return true;
            }
        }
        return false;
    }

    /** Values are already valid. A status Floci never assigns matches nothing. */
    private static Set<DurableExecutionStatus> parseStatuses(List<String> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return null;
        }
        if (statuses.size() > 1) {
            throw new AwsException("InvalidParameterValueException", "Cannot filter by more than one status", 400);
        }
        String status = statuses.get(0);
        return UNMODELLED_STATUSES.contains(status) ? EnumSet.noneOf(DurableExecutionStatus.class)
                : EnumSet.of(DurableExecutionStatus.valueOf(status));
    }

    /** AWS takes ISO 8601, a plain date or epoch seconds. */
    private static Long parseTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (DateTimeParseException notInstant) {
            try {
                return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            } catch (DateTimeParseException notDate) {
                try {
                    double seconds = Double.parseDouble(value);
                    if (Double.isFinite(seconds)) {
                        return (long) (seconds * 1000);
                    }
                } catch (NumberFormatException notNumber) {
                    // Falls through to the error AWS gives for every unreadable timestamp.
                }
                throw new AwsException("SerializationException", "'" + value + "' can not be converted to Date", 400);
            }
        }
    }
}
