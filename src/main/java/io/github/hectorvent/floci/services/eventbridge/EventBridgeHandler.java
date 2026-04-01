package io.github.hectorvent.floci.services.eventbridge;

import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsJson11Controller;
import io.github.hectorvent.floci.services.eventbridge.model.EventBus;
import io.github.hectorvent.floci.services.eventbridge.model.Rule;
import io.github.hectorvent.floci.services.eventbridge.model.RuleState;
import io.github.hectorvent.floci.services.eventbridge.model.InputTransformer;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * EventBridge JSON handler. Not a JAX-RS resource; dispatched from {@link AwsJson11Controller}.
 */
@ApplicationScoped
public class EventBridgeHandler {

    private static final Logger LOG = Logger.getLogger(EventBridgeHandler.class);

    private final EventBridgeService eventBridgeService;
    private final ObjectMapper objectMapper;

    @Inject
    public EventBridgeHandler(EventBridgeService eventBridgeService, ObjectMapper objectMapper) {
        this.eventBridgeService = eventBridgeService;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("EventBridge action: {0}", action);

        try {
            return switch (action) {
                case "CreateEventBus" -> handleCreateEventBus(request, region);
                case "DeleteEventBus" -> handleDeleteEventBus(request, region);
                case "DescribeEventBus" -> handleDescribeEventBus(request, region);
                case "ListEventBuses" -> handleListEventBuses(request, region);
                case "PutRule" -> handlePutRule(request, region);
                case "DeleteRule" -> handleDeleteRule(request, region);
                case "DescribeRule" -> handleDescribeRule(request, region);
                case "ListRules" -> handleListRules(request, region);
                case "EnableRule" -> handleEnableRule(request, region);
                case "DisableRule" -> handleDisableRule(request, region);
                case "PutTargets" -> handlePutTargets(request, region);
                case "RemoveTargets" -> handleRemoveTargets(request, region);
                case "ListTargetsByRule" -> handleListTargetsByRule(request, region);
                case "PutEvents" -> handlePutEvents(request, region);
                default -> Response.status(400)
                        .entity(new AwsErrorResponse("UnsupportedOperation", "Operation " + action + " is not supported."))
                        .build();
            };
        } catch (AwsException e) {
            return Response.status(e.getHttpStatus())
                    .entity(new AwsErrorResponse(e.getErrorCode(), e.getMessage()))
                    .build();
        } catch (Exception e) {
            LOG.errorv("EventBridge error processing action {0}: {1}", action, e.getMessage());
            return Response.status(500)
                    .entity(new AwsErrorResponse("InternalFailure", e.getMessage()))
                    .build();
        }
    }

    private Response handleCreateEventBus(JsonNode request, String region) {
        String name = request.path("Name").asText(null);
        String description = request.path("Description").asText(null);
        Map<String, String> tags = parseTagsArray(request.path("Tags"));
        EventBus bus = eventBridgeService.createEventBus(name, description, tags, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("EventBusArn", bus.getArn());
        return Response.ok(response).build();
    }

    private Response handleDeleteEventBus(JsonNode request, String region) {
        String name = request.path("Name").asText(null);
        eventBridgeService.deleteEventBus(name, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDescribeEventBus(JsonNode request, String region) {
        String name = request.path("Name").asText(null);
        EventBus bus = eventBridgeService.describeEventBus(name, region);
        return Response.ok(buildBusNode(bus)).build();
    }

    private Response handleListEventBuses(JsonNode request, String region) {
        String namePrefix = request.path("NamePrefix").asText(null);
        List<EventBus> buses = eventBridgeService.listEventBuses(namePrefix, region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode busesArray = response.putArray("EventBuses");
        for (EventBus bus : buses) {
            busesArray.add(buildBusNode(bus));
        }
        return Response.ok(response).build();
    }

    private Response handlePutRule(JsonNode request, String region) {
        String name = request.path("Name").asText(null);
        String busName = request.path("EventBusName").asText(null);
        String eventPattern = request.path("EventPattern").asText(null);
        String scheduleExpression = request.path("ScheduleExpression").asText(null);
        RuleState state = parseRuleState(request.path("State").asText(null));
        String description = request.path("Description").asText(null);
        String roleArn = request.path("RoleArn").asText(null);
        Map<String, String> tags = parseTagsArray(request.path("Tags"));
        Rule rule = eventBridgeService.putRule(name, busName, eventPattern, scheduleExpression,
                state, description, roleArn, tags, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("RuleArn", rule.getArn());
        return Response.ok(response).build();
    }

    private Response handleDeleteRule(JsonNode request, String region) {
        String name = request.path("Name").asText(null);
        String busName = request.path("EventBusName").asText(null);
        eventBridgeService.deleteRule(name, busName, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDescribeRule(JsonNode request, String region) {
        String name = request.path("Name").asText(null);
        String busName = request.path("EventBusName").asText(null);
        Rule rule = eventBridgeService.describeRule(name, busName, region);
        return Response.ok(buildRuleNode(rule)).build();
    }

    private Response handleListRules(JsonNode request, String region) {
        String busName = request.path("EventBusName").asText(null);
        String namePrefix = request.path("NamePrefix").asText(null);
        List<Rule> rules = eventBridgeService.listRules(busName, namePrefix, region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode rulesArray = response.putArray("Rules");
        for (Rule rule : rules) {
            rulesArray.add(buildRuleNode(rule));
        }
        return Response.ok(response).build();
    }

    private Response handleEnableRule(JsonNode request, String region) {
        String name = request.path("Name").asText(null);
        String busName = request.path("EventBusName").asText(null);
        eventBridgeService.enableRule(name, busName, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDisableRule(JsonNode request, String region) {
        String name = request.path("Name").asText(null);
        String busName = request.path("EventBusName").asText(null);
        eventBridgeService.disableRule(name, busName, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handlePutTargets(JsonNode request, String region) {
        String ruleName = request.path("Rule").asText(null);
        String busName = request.path("EventBusName").asText(null);
        List<Target> targets = new ArrayList<>();
        JsonNode targetsNode = request.path("Targets");
        if (targetsNode.isArray()) {
            for (JsonNode t : targetsNode) {
                String input = t.path("Input").asText("");
                String inputPath = t.path("InputPath").asText("");
                Target target = new Target(
                        t.path("Id").asText(null),
                        t.path("Arn").asText(null),
                        input.isEmpty() ? null : input,
                        inputPath.isEmpty() ? null : inputPath
                );
                JsonNode transformerNode = t.path("InputTransformer");
                if (!transformerNode.isMissingNode() && transformerNode.isObject()) {
                    Map<String, String> pathsMap = new HashMap<>();
                    JsonNode pathsNode = transformerNode.path("InputPathsMap");
                    if (pathsNode.isObject()) {
                        pathsNode.fields().forEachRemaining(e -> pathsMap.put(e.getKey(), e.getValue().asText()));
                    }
                    String template = transformerNode.path("InputTemplate").asText(null);
                    target.setInputTransformer(new InputTransformer(pathsMap, template));
                }
                targets.add(target);
            }
        }
        int failed = eventBridgeService.putTargets(ruleName, busName, targets, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("FailedEntryCount", failed);
        response.putArray("FailedEntries");
        return Response.ok(response).build();
    }

    private Response handleRemoveTargets(JsonNode request, String region) {
        String ruleName = request.path("Rule").asText(null);
        String busName = request.path("EventBusName").asText(null);
        List<String> ids = new ArrayList<>();
        JsonNode idsNode = request.path("Ids");
        if (idsNode.isArray()) {
            for (JsonNode id : idsNode) {
                ids.add(id.asText());
            }
        }
        EventBridgeService.RemoveTargetsResult result =
                eventBridgeService.removeTargets(ruleName, busName, ids, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("SuccessfulEntryCount", result.successfulCount());
        response.put("FailedEntryCount", result.failedCount());
        response.putArray("SuccessfulEntries");
        response.putArray("FailedEntries");
        return Response.ok(response).build();
    }

    private Response handleListTargetsByRule(JsonNode request, String region) {
        String ruleName = request.path("Rule").asText(null);
        String busName = request.path("EventBusName").asText(null);
        List<Target> targets = eventBridgeService.listTargetsByRule(ruleName, busName, region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode targetsArray = response.putArray("Targets");
        for (Target t : targets) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("Id", t.getId());
            node.put("Arn", t.getArn());
            if (t.getInput() != null) {
                node.put("Input", t.getInput());
            }
            if (t.getInputPath() != null) {
                node.put("InputPath", t.getInputPath());
            }
            if (t.getInputTransformer() != null) {
                ObjectNode transformerNode = node.putObject("InputTransformer");
                ObjectNode pathsNode = transformerNode.putObject("InputPathsMap");
                t.getInputTransformer().getInputPathsMap().forEach(pathsNode::put);
                if (t.getInputTransformer().getInputTemplate() != null) {
                    transformerNode.put("InputTemplate", t.getInputTransformer().getInputTemplate());
                }
            }
            targetsArray.add(node);
        }
        return Response.ok(response).build();
    }

    private Response handlePutEvents(JsonNode request, String region) {
        List<Map<String, Object>> entries = new ArrayList<>();
        JsonNode entriesNode = request.path("Entries");
        if (entriesNode.isArray()) {
            for (JsonNode entryNode : entriesNode) {
                Map<String, Object> entry = new HashMap<>();
                if (!entryNode.path("EventBusName").isMissingNode()) {
                    entry.put("EventBusName", entryNode.path("EventBusName").asText(null));
                }
                if (!entryNode.path("Source").isMissingNode()) {
                    entry.put("Source", entryNode.path("Source").asText(null));
                }
                if (!entryNode.path("DetailType").isMissingNode()) {
                    entry.put("DetailType", entryNode.path("DetailType").asText(null));
                }
                if (!entryNode.path("Detail").isMissingNode()) {
                    entry.put("Detail", entryNode.path("Detail").asText(null));
                }
                entries.add(entry);
            }
        }
        EventBridgeService.PutEventsResult result = eventBridgeService.putEvents(entries, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("FailedEntryCount", result.failedCount());
        ArrayNode resultEntries = response.putArray("Entries");
        for (Map<String, String> entry : result.entries()) {
            ObjectNode node = objectMapper.createObjectNode();
            entry.forEach(node::put);
            resultEntries.add(node);
        }
        return Response.ok(response).build();
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private ObjectNode buildBusNode(EventBus bus) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", bus.getName());
        node.put("Arn", bus.getArn());
        if (bus.getDescription() != null) {
            node.put("Description", bus.getDescription());
        }
        if (bus.getCreatedTime() != null) {
            node.put("CreationTime", bus.getCreatedTime().getEpochSecond());
        }
        return node;
    }

    private ObjectNode buildRuleNode(Rule rule) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", rule.getName());
        node.put("Arn", rule.getArn());
        node.put("EventBusName", rule.getEventBusName());
        node.put("State", rule.getState().name());
        if (rule.getEventPattern() != null) {
            node.put("EventPattern", rule.getEventPattern());
        }
        if (rule.getScheduleExpression() != null) {
            node.put("ScheduleExpression", rule.getScheduleExpression());
        }
        if (rule.getDescription() != null) {
            node.put("Description", rule.getDescription());
        }
        if (rule.getRoleArn() != null) {
            node.put("RoleArn", rule.getRoleArn());
        }
        return node;
    }

    private RuleState parseRuleState(String state) {
        if (state == null || state.isBlank()) {
            return RuleState.ENABLED;
        }
        return switch (state.toUpperCase()) {
            case "DISABLED" -> RuleState.DISABLED;
            default -> RuleState.ENABLED;
        };
    }

    private Map<String, String> parseTagsArray(JsonNode tagsNode) {
        Map<String, String> tags = new HashMap<>();
        if (tagsNode != null && tagsNode.isArray()) {
            for (JsonNode tag : tagsNode) {
                String key = tag.path("Key").asText(null);
                String value = tag.path("Value").asText(null);
                if (key != null) {
                    tags.put(key, value);
                }
            }
        }
        return tags;
    }
}
