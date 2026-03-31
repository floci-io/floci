package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.List;

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
            case "DescribeStateMachine" -> handleDescribeStateMachine(request);
            case "ListStateMachines" -> handleListStateMachines(request, region);
            case "DeleteStateMachine" -> handleDeleteStateMachine(request);
            case "StartExecution" -> handleStartExecution(request, region);
            case "DescribeExecution" -> handleDescribeExecution(request);
            case "ListExecutions" -> handleListExecutions(request);
            case "StopExecution" -> handleStopExecution(request);
            case "GetExecutionHistory" -> handleGetExecutionHistory(request);
            case "SendTaskSuccess" -> handleSendTaskSuccess(request);
            case "SendTaskFailure" -> handleSendTaskFailure(request);
            case "SendTaskHeartbeat" -> handleSendTaskHeartbeat(request);
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnsupportedOperation", "Operation " + action + " is not supported."))
                    .build();
        };
    }

    private Response handleCreateStateMachine(JsonNode request, String region) {
        StateMachine sm = service.createStateMachine(
                request.path("name").asText(),
                request.path("definition").asText(),
                request.path("roleArn").asText(),
                request.path("type").asText(null),
                region
        );
        ObjectNode response = objectMapper.createObjectNode();
        response.put("stateMachineArn", sm.getStateMachineArn());
        response.put("creationDate", sm.getCreationDate());
        return Response.ok(response).build();
    }

    private Response handleDescribeStateMachine(JsonNode request) {
        StateMachine sm = service.describeStateMachine(request.path("stateMachineArn").asText());
        ObjectNode response = objectMapper.createObjectNode();
        response.put("stateMachineArn", sm.getStateMachineArn());
        response.put("name", sm.getName());
        response.put("definition", sm.getDefinition());
        response.put("roleArn", sm.getRoleArn());
        response.put("type", sm.getType());
        response.put("status", sm.getStatus());
        response.put("creationDate", sm.getCreationDate());
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
        List<HistoryEvent> events = service.getExecutionHistory(request.path("executionArn").asText());
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray("events");
        for (HistoryEvent e : events) {
            ObjectNode item = array.addObject();
            item.put("id", e.getId());
            item.put("timestamp", e.getTimestamp());
            item.put("type", e.getType());
            if (e.getPreviousEventId() != null) item.put("previousEventId", e.getPreviousEventId());
            if (e.getDetails() != null) {
                item.set(e.getType() + "EventDetails", objectMapper.valueToTree(e.getDetails()));
            }
        }
        return Response.ok(response).build();
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

}
