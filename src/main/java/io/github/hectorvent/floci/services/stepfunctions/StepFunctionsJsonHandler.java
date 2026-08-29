package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.stepfunctions.model.Activity;
import io.github.hectorvent.floci.services.stepfunctions.model.ActivityTask;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.MapRun;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class StepFunctionsJsonHandler {

    private final StepFunctionsService service;
    private final ObjectMapper objectMapper;

    @Inject
    public StepFunctionsJsonHandler(StepFunctionsService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "CreateStateMachine" -> handleCreateStateMachine(request, region);
            case "UpdateStateMachine" -> handleUpdateStateMachine(request);
            case "DescribeStateMachine" -> handleDescribeStateMachine(request);
            case "ListStateMachines" -> handleListStateMachines(request, region);
            case "DeleteStateMachine" -> handleDeleteStateMachine(request);
            case "PublishStateMachineVersion" -> handlePublishStateMachineVersion(request);
            case "ListStateMachineVersions" -> handleListStateMachineVersions(request);
            case "DeleteStateMachineVersion" -> handleDeleteStateMachineVersion(request);
            case "ValidateStateMachineDefinition" -> handleValidateStateMachineDefinition(request);
            case "StartExecution" -> handleStartExecution(request, region);
            case "StartSyncExecution" -> handleStartSyncExecution(request, region);
            case "DescribeExecution" -> handleDescribeExecution(request);
            case "ListExecutions" -> handleListExecutions(request);
            case "StopExecution" -> handleStopExecution(request);
            case "GetExecutionHistory" -> handleGetExecutionHistory(request);
            case "DescribeMapRun" -> handleDescribeMapRun(request);
            case "SendTaskSuccess" -> handleSendTaskSuccess(request);
            case "SendTaskFailure" -> handleSendTaskFailure(request);
            case "SendTaskHeartbeat" -> handleSendTaskHeartbeat(request);
            case "CreateActivity" -> handleCreateActivity(request, region);
            case "DeleteActivity" -> handleDeleteActivity(request);
            case "DescribeActivity" -> handleDescribeActivity(request);
            case "ListActivities" -> handleListActivities(request, region);
            case "GetActivityTask" -> handleGetActivityTask(request);
            case "ListTagsForResource" -> handleListTagsForResource(request);
            case "TagResource" -> handleTagResource(request);
            case "UntagResource" -> handleUntagResource(request);
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnsupportedOperation", "Operation " + action + " is not supported."))
                    .build();
        };
    }

    private Response handleCreateStateMachine(JsonNode request, String region) {
        boolean publish = parseOptionalBoolean(request, "publish", false);
        StepFunctionsService.CreateStateMachineResult result = service.createStateMachine(
                requiredText(request, "name"),
                requiredText(request, "definition"),
                requiredText(request, "roleArn"),
                optionalText(request, "type"),
                region,
                parseTagsArray(request.path("tags")),
                request.get("loggingConfiguration"),
                request.get("tracingConfiguration"),
                request.get("encryptionConfiguration"),
                publish,
                optionalText(request, "versionDescription")
        );
        StateMachine sm = result.stateMachine();
        ObjectNode response = objectMapper.createObjectNode();
        response.put("stateMachineArn", sm.getStateMachineArn());
        response.put("creationDate", sm.getCreationDate());
        if (result.version() != null) {
            response.put(
                    "stateMachineVersionArn",
                    result.version().getStateMachineVersionArn());
        } else {
            response.putNull("stateMachineVersionArn");
        }
        return Response.ok(response).build();
    }

    private Response handleUpdateStateMachine(JsonNode request) {
        String stateMachineArn = requiredText(request, "stateMachineArn");
        String definition = optionalText(request, "definition");
        String roleArn = optionalText(request, "roleArn");
        // AWS requires at least one updatable field; a bare stateMachineArn returns MissingRequiredParameter.
        if (definition == null && roleArn == null) {
            throw new AwsException("MissingRequiredParameter",
                    "Either the definition or the roleArn must be specified.", 400);
        }
        boolean publish = parseOptionalBoolean(request, "publish", false);
        StepFunctionsService.UpdateStateMachineResult result = service.updateStateMachine(
                stateMachineArn,
                new StepFunctionsService.UpdateStateMachineRequest(
                        definition,
                        roleArn,
                        request.get("loggingConfiguration"), request.has("loggingConfiguration"),
                        request.get("tracingConfiguration"), request.has("tracingConfiguration"),
                        request.get("encryptionConfiguration"), request.has("encryptionConfiguration"),
                        publish,
                        optionalText(request, "versionDescription")));
        StateMachine sm = result.stateMachine();
        ObjectNode response = objectMapper.createObjectNode();
        response.put("updateDate", sm.getUpdateDate());
        response.put("revisionId", sm.getRevisionId());
        if (result.version() != null) {
            response.put("stateMachineVersionArn", result.version().getStateMachineVersionArn());
        } else {
            response.putNull("stateMachineVersionArn");
        }
        return Response.ok(response).build();
    }

    private Response handlePublishStateMachineVersion(JsonNode request) {
        var version = service.publishStateMachineVersion(
                requiredText(request, "stateMachineArn"),
                optionalText(request, "revisionId"),
                optionalText(request, "description"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("stateMachineVersionArn", version.getStateMachineVersionArn());
        response.put("creationDate", version.getCreationDate());
        return Response.ok(response).build();
    }

    private Response handleListStateMachineVersions(JsonNode request) {
        var versions = service.listStateMachineVersions(request.path("stateMachineArn").asText());
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray("stateMachineVersions");
        for (var v : versions) {
            ObjectNode item = array.addObject();
            item.put("stateMachineVersionArn", v.getStateMachineVersionArn());
            item.put("creationDate", v.getCreationDate());
        }
        return Response.ok(response).build();
    }

    private Response handleDeleteStateMachineVersion(JsonNode request) {
        service.deleteStateMachineVersion(request.path("stateMachineVersionArn").asText());
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDescribeStateMachine(JsonNode request) {
        String includedData = optionalText(request, "includedData");
        if (includedData != null
                && !"ALL_DATA".equals(includedData)
                && !"METADATA_ONLY".equals(includedData)) {
            throw new AwsException(
                    "ValidationException",
                    "includedData must be ALL_DATA or METADATA_ONLY.", 400);
        }
        boolean metadataOnly = "METADATA_ONLY".equals(includedData);
        StateMachine sm = service.describeStateMachine(
                requiredText(request, "stateMachineArn"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("stateMachineArn", sm.getStateMachineArn());
        response.put("name", sm.getName());
        response.put("definition", metadataOnly ? "{}" : sm.getDefinition());
        response.put("roleArn", sm.getRoleArn());
        response.put("type", sm.getType());
        response.put("status", sm.getStatus());
        response.put("creationDate", sm.getCreationDate());
        if (sm.getDescription() != null) {
            response.put("description", sm.getDescription());
        }
        if (sm.getLoggingConfiguration() != null) {
            response.set("loggingConfiguration", sm.getLoggingConfiguration());
        }
        if (sm.getTracingConfiguration() != null) {
            response.set("tracingConfiguration", sm.getTracingConfiguration());
        }
        if (sm.getEncryptionConfiguration() != null) {
            response.set("encryptionConfiguration", sm.getEncryptionConfiguration());
        }
        if (sm.getRevisionId() != null) {
            response.put("revisionId", sm.getRevisionId());
        }
        return Response.ok(response).build();
    }

    private Response handleListStateMachines(JsonNode request, String region) {
        List<StateMachine> list = service.listStateMachines(region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray("stateMachines");
        for (StateMachine sm : list) {
            ObjectNode item = array.addObject();
            item.put("stateMachineArn", sm.getStateMachineArn());
            item.put("name", sm.getName());
            item.put("type", sm.getType());
            item.put("creationDate", sm.getCreationDate());
        }
        return Response.ok(response).build();
    }

    private Response handleDeleteStateMachine(JsonNode request) {
        service.deleteStateMachine(request.path("stateMachineArn").asText());
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleValidateStateMachineDefinition(JsonNode request) {
        String definition = request.path("definition").asText(null);
        String type = request.path("type").asText(null);
        String severity = request.path("severity").asText(null);
        Integer maxResults = parseOptionalInt(request, "maxResults");

        StepFunctionsService.ValidationResult result =
                service.validateStateMachineDefinition(definition, type, severity, maxResults);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("result", result.valid() ? "OK" : "FAIL");
        ArrayNode diags = response.putArray("diagnostics");
        for (StepFunctionsService.Diagnostic d : result.diagnostics()) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("severity", d.severity());
            node.put("code", d.code());
            node.put("message", d.message());
            if (d.location() != null) {
                node.put("location", d.location());
            }
            diags.add(node);
        }
        response.put("truncated", result.truncated());
        return Response.ok(response).build();
    }

    private Response handleStartExecution(JsonNode request, String region) {
        Execution exec = service.startExecution(
                request.path("stateMachineArn").asText(),
                request.path("name").asText(null),
                request.path("input").asText(null),
                region
        );
        ObjectNode response = objectMapper.createObjectNode();
        response.put("executionArn", exec.getExecutionArn());
        response.put("startDate", exec.getStartDate());
        return Response.ok(response).build();
    }

    private Response handleStartSyncExecution(JsonNode request, String region) {
        Execution exec = service.startSyncExecution(
                request.path("stateMachineArn").asText(),
                request.path("name").asText(null),
                request.path("input").asText(null),
                region
        );
        ObjectNode response = objectMapper.createObjectNode();
        response.put("executionArn", exec.getExecutionArn());
        response.put("stateMachineArn", exec.getStateMachineArn());
        response.put("name", exec.getName());
        response.put("status", exec.getStatus());
        response.put("startDate", exec.getStartDate());
        if (exec.getStopDate() != null) response.put("stopDate", exec.getStopDate());
        if (exec.getInput() != null) response.put("input", exec.getInput());
        if (exec.getOutput() != null) response.put("output", exec.getOutput());
        if (exec.getError() != null) response.put("error", exec.getError());
        if (exec.getCause() != null) response.put("cause", exec.getCause());
        return Response.ok(response).build();
    }

    private Response handleDescribeExecution(JsonNode request) {
        Execution exec = service.describeExecution(request.path("executionArn").asText());
        ObjectNode response = objectMapper.createObjectNode();
        response.put("executionArn", exec.getExecutionArn());
        response.put("stateMachineArn", exec.getStateMachineArn());
        response.put("name", exec.getName());
        response.put("status", exec.getStatus());
        response.put("startDate", exec.getStartDate());
        if (exec.getStopDate() != null) response.put("stopDate", exec.getStopDate());
        if (exec.getInput() != null) response.put("input", exec.getInput());
        if (exec.getOutput() != null) response.put("output", exec.getOutput());
        if (exec.getError() != null) response.put("error", exec.getError());
        if (exec.getCause() != null) response.put("cause", exec.getCause());
        return Response.ok(response).build();
    }

    private Response handleDescribeMapRun(JsonNode request) {
        MapRun mapRun = service.describeMapRun(requiredText(request, "mapRunArn"));
        return Response.ok(describeMapRunResponse(objectMapper, mapRun)).build();
    }

    /**
     * The wire response of {@code DescribeMapRun}, measured against us-east-1. The
     * {@code arn:aws:states:::aws-sdk:sfn:describeMapRun} Task integration renders the same node in
     * PascalCase, so this is the one place the response is described.
     *
     * <p>A retained run is one whose every item succeeded, because a Map fails on the first item
     * that fails. That fixes {@code status}, both zero tolerances and {@code redriveCount}:
     * {@code redriveDate} is absent until a run is redriven, and no run here ever is.
     */
    static ObjectNode describeMapRunResponse(ObjectMapper objectMapper, MapRun mapRun) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("mapRunArn", mapRun.getMapRunArn());
        response.put("executionArn", mapRun.getExecutionArn());
        response.put("status", "SUCCEEDED");
        response.put("startDate", mapRun.getStartDate());
        response.put("stopDate", mapRun.getStopDate());
        response.put("maxConcurrency", mapRun.getMaxConcurrency());
        response.put("toleratedFailurePercentage", 0.0);
        response.put("toleratedFailureCount", 0);
        putMapRunCounts(response.putObject("itemCounts"), mapRun.getItemCount());
        // One child execution per item: ItemBatcher is not applied, so no execution covers a batch.
        putMapRunCounts(response.putObject("executionCounts"), mapRun.getItemCount());
        response.put("redriveCount", 0);
        return response;
    }

    /** The ten counters of a run whose every item succeeded and was written to the result set. */
    private static void putMapRunCounts(ObjectNode counts, int items) {
        counts.put("pending", 0);
        counts.put("running", 0);
        counts.put("succeeded", items);
        counts.put("failed", 0);
        counts.put("timedOut", 0);
        counts.put("aborted", 0);
        counts.put("total", items);
        counts.put("resultsWritten", items);
        counts.put("failuresNotRedrivable", 0);
        counts.put("pendingRedrive", 0);
    }

    private Response handleListExecutions(JsonNode request) {
        List<Execution> list = service.listExecutions(request.path("stateMachineArn").asText());
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray("executions");
        for (Execution e : list) {
            ObjectNode item = array.addObject();
            item.put("executionArn", e.getExecutionArn());
            item.put("stateMachineArn", e.getStateMachineArn());
            item.put("name", e.getName());
            item.put("status", e.getStatus());
            item.put("startDate", e.getStartDate());
            if (e.getStopDate() != null) item.put("stopDate", e.getStopDate());
        }
        return Response.ok(response).build();
    }

    private Response handleStopExecution(JsonNode request) {
        service.stopExecution(
                request.path("executionArn").asText(),
                request.path("cause").asText(null),
                request.path("error").asText(null)
        );
        ObjectNode response = objectMapper.createObjectNode();
        response.put("stopDate", System.currentTimeMillis() / 1000.0);
        return Response.ok(response).build();
    }

    private Response handleGetExecutionHistory(JsonNode request) {
        var arn = request.path("executionArn").asText();
        var includeExecutionData = request.path("includeExecutionData").asBoolean(true);

        List<HistoryEvent> events = service.getExecutionHistory(arn);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray("events");
        for (HistoryEvent e : events) {
            ObjectNode item = array.addObject();
            item.put("id", e.getId());
            item.put("timestamp", e.getTimestamp());
            item.put("type", e.getType());
            if (e.getPreviousEventId() != null) item.put("previousEventId", e.getPreviousEventId());
            if (includeExecutionData && e.getDetails() != null) {
                item.set(historyEventDetailsField(e.getType()), objectMapper.valueToTree(e.getDetails()));
            }
        }
        return Response.ok(response).build();
    }

    static String historyEventDetailsField(String type) {
        if (type.endsWith("StateEntered")) {
            return "stateEnteredEventDetails";
        }
        if (type.endsWith("StateExited")) {
            return "stateExitedEventDetails";
        }
        return Character.toLowerCase(type.charAt(0)) + type.substring(1) + "EventDetails";
    }

    private Response handleSendTaskSuccess(JsonNode request) {
        service.sendTaskSuccess(request.path("taskToken").asText(), request.path("output").asText());
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleSendTaskFailure(JsonNode request) {
        service.sendTaskFailure(
                request.path("taskToken").asText(),
                request.path("cause").asText(null),
                request.path("error").asText(null)
        );
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleSendTaskHeartbeat(JsonNode request) {
        service.sendTaskHeartbeat(request.path("taskToken").asText());
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleCreateActivity(JsonNode request, String region) {
        Activity activity = service.createActivity(request.path("name").asText(), region, parseTagsArray(request.path("tags")));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("activityArn", activity.getActivityArn());
        response.put("creationDate", activity.getCreationDate());
        return Response.ok(response).build();
    }

    private Response handleDeleteActivity(JsonNode request) {
        service.deleteActivity(request.path("activityArn").asText());
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDescribeActivity(JsonNode request) {
        Activity activity = service.describeActivity(request.path("activityArn").asText());
        ObjectNode response = objectMapper.createObjectNode();
        response.put("activityArn", activity.getActivityArn());
        response.put("name", activity.getName());
        response.put("creationDate", activity.getCreationDate());
        return Response.ok(response).build();
    }

    private Response handleListActivities(JsonNode request, String region) {
        List<Activity> list = service.listActivities(region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray("activities");
        for (Activity a : list) {
            ObjectNode item = array.addObject();
            item.put("activityArn", a.getActivityArn());
            item.put("name", a.getName());
            item.put("creationDate", a.getCreationDate());
        }
        return Response.ok(response).build();
    }

    private Response handleGetActivityTask(JsonNode request) {
        String activityArn = request.path("activityArn").asText();
        String workerName = request.path("workerName").asText(null);
        ActivityTask task = service.getActivityTask(activityArn, workerName);
        ObjectNode response = objectMapper.createObjectNode();
        if (task != null) {
            response.put("taskToken", task.getTaskToken());
            response.put("input", task.getInput());
        }
        return Response.ok(response).build();
    }

    private Response handleListTagsForResource(JsonNode request) {
        java.util.Map<String, String> tags = service.listTags(request.path("resourceArn").asText());
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray("tags");
        tags.forEach((k, v) -> {
            ObjectNode entry = array.addObject();
            entry.put("key", k);
            entry.put("value", v);
        });
        return Response.ok(response).build();
    }

    private Response handleTagResource(JsonNode request) {
        String arn = request.path("resourceArn").asText();
        JsonNode tagsNode = request.path("tags");
        if (!tagsNode.isArray()) {
            return Response.status(400)
                    .entity(new AwsErrorResponse("ValidationException", "Parameter 'tags' must be a list"))
                    .build();
        }
        service.tagResource(arn, parseTagsArray(tagsNode));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleUntagResource(JsonNode request) {
        String arn = request.path("resourceArn").asText();
        JsonNode keysNode = request.path("tagKeys");
        if (!keysNode.isArray()) {
            return Response.status(400)
                    .entity(new AwsErrorResponse("ValidationException", "Parameter 'tagKeys' must be a list"))
                    .build();
        }
        java.util.List<String> tagKeys = new java.util.ArrayList<>();
        for (JsonNode key : keysNode) {
            tagKeys.add(key.asText());
        }
        service.untagResource(arn, tagKeys);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Map<String, String> parseTagsArray(JsonNode tagsNode) {
        Map<String, String> tags = new HashMap<>();
        if (tagsNode != null && tagsNode.isArray()) {
            for (JsonNode entry : tagsNode) {
                tags.put(entry.path("key").asText(), entry.path("value").asText());
            }
        }
        return tags;
    }

    private static String requiredText(JsonNode request, String fieldName) {
        JsonNode node = request.get(fieldName);
        if (node == null || node.isNull()) {
            throw new AwsException("MissingRequiredParameter", fieldName + " is required.", 400);
        }
        if (!node.isTextual()) {
            throw new AwsException("ValidationException", fieldName + " must be a string.", 400);
        }
        return node.asText();
    }

    private static String optionalText(JsonNode request, String fieldName) {
        JsonNode node = request.get(fieldName);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new AwsException("ValidationException", fieldName + " must be a string.", 400);
        }
        return node.asText();
    }

    private static boolean parseOptionalBoolean(
            JsonNode request, String fieldName, boolean defaultValue) {
        JsonNode node = request.get(fieldName);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.isBoolean()) {
            throw new AwsException("ValidationException", fieldName + " must be a boolean.", 400);
        }
        return node.asBoolean();
    }

    /**
     * Reads an optional integer field from the JSON request, rejecting wrong types
     * with a typed ValidationException. JsonNode.asInt() silently coerces strings,
     * fractional numbers, and explicit nulls to 0, which would let invalid payloads
     * slip through as 0 (and thus be treated as "use default" by the service).
     * Returns null when the field is absent or explicitly null in the JSON.
     */
    private static Integer parseOptionalInt(JsonNode request, String fieldName) {
        JsonNode node = request.get(fieldName);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber()) {
            throw new AwsException("ValidationException",
                    "Value '" + node + "' at '" + fieldName
                            + "' failed to satisfy constraint: Member must be an integer.", 400);
        }
        return node.intValue();
    }

}
