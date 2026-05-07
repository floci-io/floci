package io.github.hectorvent.floci.services.ssm;

import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.services.ssm.model.Command;
import io.github.hectorvent.floci.services.ssm.model.CommandInvocation;
import io.github.hectorvent.floci.services.ssm.model.InstanceInformation;
import io.github.hectorvent.floci.services.ssm.model.Parameter;
import io.github.hectorvent.floci.services.ssm.model.ParameterHistory;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class SsmJsonHandler {

    private final SsmService ssmService;
    private final SsmCommandService commandService;
    private final ObjectMapper objectMapper;

    @Inject
    public SsmJsonHandler(SsmService ssmService, SsmCommandService commandService, ObjectMapper objectMapper) {
        this.ssmService = ssmService;
        this.commandService = commandService;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            // Parameter Store
            case "PutParameter" -> handlePutParameter(request, region);
            case "GetParameter" -> handleGetParameter(request, region);
            case "GetParameters" -> handleGetParameters(request, region);
            case "GetParametersByPath" -> handleGetParametersByPath(request, region);
            case "DeleteParameter" -> handleDeleteParameter(request, region);
            case "DeleteParameters" -> handleDeleteParameters(request, region);
            case "GetParameterHistory" -> handleGetParameterHistory(request, region);
            case "DescribeParameters" -> handleDescribeParameters(request, region);
            case "LabelParameterVersion" -> handleLabelParameterVersion(request, region);
            case "AddTagsToResource" -> handleAddTagsToResource(request, region);
            case "ListTagsForResource" -> handleListTagsForResource(request, region);
            case "RemoveTagsFromResource" -> handleRemoveTagsFromResource(request, region);
            // Run Command (public API)
            case "SendCommand" -> handleSendCommand(request, region);
            case "GetCommandInvocation" -> handleGetCommandInvocation(request, region);
            case "ListCommands" -> handleListCommands(request, region);
            case "ListCommandInvocations" -> handleListCommandInvocations(request, region);
            case "CancelCommand" -> handleCancelCommand(request, region);
            case "DescribeInstanceInformation" -> handleDescribeInstanceInformation(request, region);
            // Agent registration (internal, not in public SDK)
            case "UpdateInstanceInformation" -> handleUpdateInstanceInformation(request, region);
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnsupportedOperation", "Operation " + action + " is not supported."))
                    .build();
        };
    }

    @RegisterForReflection
    record PutParameterResponse(@JsonProperty("Version") long version) {
    }

    private Response handlePutParameter(JsonNode request, String region) {
        String name = request.path("Name").asText();
        String value = request.path("Value").asText();
        String type = request.path("Type").asText("String");
        String description = request.has("Description") ? request.path("Description").asText() : null;
        boolean overwrite = request.path("Overwrite").asBoolean(false);

        long version = ssmService.putParameter(name, value, type, description, overwrite, region);

        return Response.ok(new PutParameterResponse(version)).build();
    }

    private Response handleGetParameter(JsonNode request, String region) {
        String name = request.path("Name").asText();
        Parameter param = ssmService.getParameter(name, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("Parameter", parameterToNode(param));
        return Response.ok(response).build();
    }

    private Response handleGetParameters(JsonNode request, String region) {
        List<String> names = new ArrayList<>();
        request.path("Names").forEach(n -> names.add(n.asText()));

        List<Parameter> params = ssmService.getParameters(names, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode parametersArray = objectMapper.createArrayNode();
        for (Parameter p : params) {
            parametersArray.add(parameterToNode(p));
        }
        response.set("Parameters", parametersArray);
        response.set("InvalidParameters", objectMapper.createArrayNode());
        return Response.ok(response).build();
    }

    private Response handleGetParametersByPath(JsonNode request, String region) {
        String path = request.path("Path").asText();
        boolean recursive = request.path("Recursive").asBoolean(false);

        List<Parameter> params = ssmService.getParametersByPath(path, recursive, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode parametersArray = objectMapper.createArrayNode();
        for (Parameter p : params) {
            parametersArray.add(parameterToNode(p));
        }
        response.set("Parameters", parametersArray);
        return Response.ok(response).build();
    }

    private Response handleDeleteParameter(JsonNode request, String region) {
        String name = request.path("Name").asText();
        ssmService.deleteParameter(name, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDeleteParameters(JsonNode request, String region) {
        List<String> names = new ArrayList<>();
        request.path("Names").forEach(n -> names.add(n.asText()));

        List<String> deleted = ssmService.deleteParameters(names, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode deletedArray = objectMapper.createArrayNode();
        deleted.forEach(deletedArray::add);
        response.set("DeletedParameters", deletedArray);
        response.set("InvalidParameters", objectMapper.createArrayNode());
        return Response.ok(response).build();
    }

    private Response handleGetParameterHistory(JsonNode request, String region) {
        String name = request.path("Name").asText();
        List<ParameterHistory> history = ssmService.getParameterHistory(name, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode historyArray = objectMapper.createArrayNode();
        for (ParameterHistory h : history) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("Name", h.getName());
            node.put("Version", h.getVersion());
            node.put("Value", h.getValue());
            node.put("Type", h.getType());
            node.put("LastModifiedDate", h.getLastModifiedDate().toEpochMilli() / 1000.0);
            if (h.getDescription() != null) {
                node.put("Description", h.getDescription());
            }
            if (h.getLabels() != null && !h.getLabels().isEmpty()) {
                ArrayNode labelsArray = objectMapper.createArrayNode();
                h.getLabels().forEach(labelsArray::add);
                node.set("Labels", labelsArray);
            }
            historyArray.add(node);
        }
        response.set("Parameters", historyArray);
        return Response.ok(response).build();
    }

    private Response handleDescribeParameters(JsonNode request, String region) {
        List<String> nameFilters = new ArrayList<>();
        JsonNode filters = request.path("ParameterFilters");
        if (filters.isArray()) {
            for (JsonNode f : filters) {
                String key = f.path("Key").asText("");
                String option = f.path("Option").asText("Equals");
                if ("Name".equals(key) && "Equals".equals(option)) {
                    f.path("Values").forEach(v -> nameFilters.add(v.asText()));
                }
            }
        }
        List<Parameter> params = ssmService.describeParameters(nameFilters, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode parametersArray = objectMapper.createArrayNode();
        for (Parameter p : params) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("Name", p.getName());
            node.put("Type", p.getType());
            node.put("Version", p.getVersion());
            node.put("LastModifiedDate", p.getLastModifiedDate().toEpochMilli() / 1000.0);
            if (p.getDescription() != null) {
                node.put("Description", p.getDescription());
            }
            node.put("DataType", p.getDataType());
            parametersArray.add(node);
        }
        response.set("Parameters", parametersArray);
        return Response.ok(response).build();
    }

    private Response handleLabelParameterVersion(JsonNode request, String region) {
        String name = request.path("Name").asText();
        long parameterVersion = request.path("ParameterVersion").asLong();
        List<String> labels = new ArrayList<>();
        request.path("Labels").forEach(l -> labels.add(l.asText()));

        ssmService.labelParameterVersion(name, parameterVersion, labels, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("InvalidLabels", objectMapper.createArrayNode());
        response.put("ParameterVersion", parameterVersion);
        return Response.ok(response).build();
    }

    private Response handleAddTagsToResource(JsonNode request, String region) {
        String resourceId = request.path("ResourceId").asText();
        Map<String, String> tags = new HashMap<>();
        request.path("Tags").forEach(t ->
                tags.put(t.path("Key").asText(), t.path("Value").asText()));

        ssmService.addTagsToResource(resourceId, tags, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListTagsForResource(JsonNode request, String region) {
        String resourceId = request.path("ResourceId").asText();
        Map<String, String> tags = ssmService.listTagsForResource(resourceId, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode tagsArray = objectMapper.createArrayNode();
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            ObjectNode tagNode = objectMapper.createObjectNode();
            tagNode.put("Key", entry.getKey());
            tagNode.put("Value", entry.getValue());
            tagsArray.add(tagNode);
        }
        response.set("TagList", tagsArray);
        return Response.ok(response).build();
    }

    private Response handleRemoveTagsFromResource(JsonNode request, String region) {
        String resourceId = request.path("ResourceId").asText();
        List<String> tagKeys = new ArrayList<>();
        request.path("TagKeys").forEach(k -> tagKeys.add(k.asText()));

        ssmService.removeTagsFromResource(resourceId, tagKeys, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private ObjectNode parameterToNode(Parameter p) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", p.getName());
        node.put("Value", p.getValue());
        node.put("Type", p.getType());
        node.put("Version", p.getVersion());
        node.put("LastModifiedDate", p.getLastModifiedDate().toEpochMilli() / 1000.0);
        node.put("ARN", p.getArn());
        node.put("DataType", p.getDataType());
        return node;
    }

    // ── Agent registration ─────────────────────────────────────────────────

    private Response handleUpdateInstanceInformation(JsonNode request, String region) {
        commandService.updateInstanceInformation(request, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    // ── Run Command public API ─────────────────────────────────────────────

    private Response handleSendCommand(JsonNode request, String region) {
        Command command = commandService.sendCommand(request, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Command", commandToNode(command));
        return Response.ok(response).build();
    }

    private Response handleGetCommandInvocation(JsonNode request, String region) {
        String commandId = request.path("CommandId").asText();
        String instanceId = request.path("InstanceId").asText();
        CommandInvocation inv = commandService.getCommandInvocation(commandId, instanceId, region);
        return Response.ok(invocationToDetailNode(inv)).build();
    }

    private Response handleListCommands(JsonNode request, String region) {
        String commandId = request.has("CommandId") ? request.path("CommandId").asText() : null;
        String instanceId = request.has("InstanceId") ? request.path("InstanceId").asText() : null;
        List<Command> commands = commandService.listCommands(commandId, instanceId, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode commandsArray = objectMapper.createArrayNode();
        for (Command c : commands) {
            commandsArray.add(commandToNode(c));
        }
        response.set("Commands", commandsArray);
        return Response.ok(response).build();
    }

    private Response handleListCommandInvocations(JsonNode request, String region) {
        String commandId = request.has("CommandId") ? request.path("CommandId").asText() : null;
        String instanceId = request.has("InstanceId") ? request.path("InstanceId").asText() : null;
        List<CommandInvocation> invocations = commandService.listCommandInvocations(commandId, instanceId, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode invArray = objectMapper.createArrayNode();
        for (CommandInvocation inv : invocations) {
            invArray.add(invocationToNode(inv));
        }
        response.set("CommandInvocations", invArray);
        return Response.ok(response).build();
    }

    private Response handleCancelCommand(JsonNode request, String region) {
        String commandId = request.path("CommandId").asText();
        List<String> instanceIds = new ArrayList<>();
        request.path("InstanceIds").forEach(n -> instanceIds.add(n.asText()));
        commandService.cancelCommand(commandId, instanceIds, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDescribeInstanceInformation(JsonNode request, String region) {
        List<InstanceInformation> instances = commandService.describeInstanceInformation(region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = objectMapper.createArrayNode();
        for (InstanceInformation info : instances) {
            list.add(instanceInfoToNode(info));
        }
        response.set("InstanceInformationList", list);
        return Response.ok(response).build();
    }

    // ── Serialisation helpers ──────────────────────────────────────────────

    private ObjectNode commandToNode(Command c) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("CommandId", c.getCommandId());
        node.put("DocumentName", c.getDocumentName());
        if (c.getDocumentVersion() != null) node.put("DocumentVersion", c.getDocumentVersion());
        if (c.getComment() != null) node.put("Comment", c.getComment());
        if (c.getRequestedDateTime() != null) node.put("RequestedDateTime", c.getRequestedDateTime().toEpochMilli() / 1000.0);
        if (c.getExpiresAfter() != null) node.put("ExpiresAfter", c.getExpiresAfter().toEpochMilli() / 1000.0);
        node.put("Status", c.getStatus());
        node.put("StatusDetails", c.getStatusDetails());
        node.put("TargetCount", c.getTargetCount());
        node.put("CompletedCount", c.getCompletedCount());
        node.put("ErrorCount", c.getErrorCount());
        node.put("TimeoutSeconds", c.getTimeoutSeconds());
        if (c.getInstanceIds() != null) {
            ArrayNode ids = objectMapper.createArrayNode();
            c.getInstanceIds().forEach(ids::add);
            node.set("InstanceIds", ids);
        }
        if (c.getParameters() != null) {
            ObjectNode params = objectMapper.createObjectNode();
            c.getParameters().forEach((k, v) -> {
                ArrayNode arr = objectMapper.createArrayNode();
                v.forEach(arr::add);
                params.set(k, arr);
            });
            node.set("Parameters", params);
        }
        return node;
    }

    private ObjectNode invocationToNode(CommandInvocation inv) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("CommandId", inv.getCommandId());
        node.put("InstanceId", inv.getInstanceId());
        if (inv.getComment() != null) node.put("Comment", inv.getComment());
        node.put("DocumentName", inv.getDocumentName());
        if (inv.getDocumentVersion() != null) node.put("DocumentVersion", inv.getDocumentVersion());
        if (inv.getRequestedDateTime() != null) node.put("RequestedDateTime", inv.getRequestedDateTime().toEpochMilli() / 1000.0);
        node.put("Status", inv.getStatus());
        node.put("StatusDetails", inv.getStatusDetails());
        return node;
    }

    private ObjectNode invocationToDetailNode(CommandInvocation inv) {
        ObjectNode node = invocationToNode(inv);
        node.put("StandardOutputContent", inv.getStandardOutputContent() != null ? inv.getStandardOutputContent() : "");
        node.put("StandardErrorContent", inv.getStandardErrorContent() != null ? inv.getStandardErrorContent() : "");
        node.put("ResponseCode", inv.getResponseCode());
        if (inv.getExecutionStartDateTime() != null) {
            node.put("ExecutionStartDateTime", inv.getExecutionStartDateTime().toString());
        }
        if (inv.getExecutionEndDateTime() != null) {
            node.put("ExecutionEndDateTime", inv.getExecutionEndDateTime().toString());
        }
        return node;
    }

    private ObjectNode instanceInfoToNode(InstanceInformation info) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("InstanceId", info.getInstanceId());
        node.put("PingStatus", info.getPingStatus());
        node.put("AgentVersion", info.getAgentVersion());
        if (info.getPlatformType() != null) node.put("PlatformType", info.getPlatformType());
        if (info.getPlatformName() != null) node.put("PlatformName", info.getPlatformName());
        if (info.getPlatformVersion() != null) node.put("PlatformVersion", info.getPlatformVersion());
        if (info.getIpAddress() != null) node.put("IPAddress", info.getIpAddress());
        if (info.getComputerName() != null) node.put("ComputerName", info.getComputerName());
        node.put("ResourceType", info.getResourceType());
        if (info.getLastPingDateTime() != null) node.put("LastPingDateTime", info.getLastPingDateTime().toEpochMilli() / 1000.0);
        if (info.getRegistrationDate() != null) node.put("RegistrationDate", info.getRegistrationDate().toEpochMilli() / 1000.0);
        return node;
    }
}
