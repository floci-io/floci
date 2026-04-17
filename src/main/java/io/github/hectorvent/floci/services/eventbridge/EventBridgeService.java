package io.github.hectorvent.floci.services.eventbridge;

import com.fasterxml.jackson.databind.node.ArrayNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.eventbridge.model.EventBus;
import io.github.hectorvent.floci.services.eventbridge.model.Rule;
import io.github.hectorvent.floci.services.eventbridge.model.RuleState;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class EventBridgeService {

    private static final Logger LOG = Logger.getLogger(EventBridgeService.class);

    private final StorageBackend<String, EventBus> busStore;
    private final StorageBackend<String, Rule> ruleStore;
    private final StorageBackend<String, List<Target>> targetStore;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    private final RuleScheduler ruleScheduler;
    private final EventBridgeInvoker invoker;

    @Inject
    public EventBridgeService(StorageFactory storageFactory,
                              EmulatorConfig config,
                              RegionResolver regionResolver,
                              ObjectMapper objectMapper,
                              RuleScheduler ruleScheduler,
                              EventBridgeInvoker invoker) {
        this(
                storageFactory.create("eventbridge", "eventbridge-buses.json",
                        new TypeReference<Map<String, EventBus>>() {}),
                storageFactory.create("eventbridge", "eventbridge-rules.json",
                        new TypeReference<Map<String, Rule>>() {}),
                storageFactory.create("eventbridge", "eventbridge-targets.json",
                        new TypeReference<Map<String, List<Target>>>() {}),
                regionResolver, objectMapper, ruleScheduler, invoker
        );
    }

    EventBridgeService(StorageBackend<String, EventBus> busStore,
                       StorageBackend<String, Rule> ruleStore,
                       StorageBackend<String, List<Target>> targetStore,
                       RegionResolver regionResolver,
                       ObjectMapper objectMapper,
                       RuleScheduler ruleScheduler,
                       EventBridgeInvoker invoker) {
        this.busStore = busStore;
        this.ruleStore = ruleStore;
        this.targetStore = targetStore;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
        this.ruleScheduler = ruleScheduler;
        this.invoker = invoker;
    }

    @PostConstruct
    void init() {
        if (ruleScheduler != null) {
            ruleStore.keys().forEach(key -> {
                ruleStore.get(key).ifPresent(this::startSchedulerIfNeeded);
            });
            LOG.infov("EventBridge initialized, {0} scheduler(s) restored", ruleScheduler.getActiveSchedulerCount());
        }
    }

    // ──────────────────────────── Event Buses ────────────────────────────

    public EventBus getOrCreateDefaultBus(String region) {
        String key = busKey(region, "default");
        return busStore.get(key).orElseGet(() -> {
            EventBus bus = new EventBus(
                    "default",
                    regionResolver.buildArn("events", region, "event-bus/default"),
                    null,
                    Instant.now()
            );
            busStore.put(key, bus);
            return bus;
        });
    }

    public EventBus createEventBus(String name, String description,
                                   Map<String, String> tags, String region) {
        if (name == null || name.isBlank()) {
            throw new AwsException("ValidationException", "EventBus name is required.", 400);
        }
        String key = busKey(region, name);
        if (busStore.get(key).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsException",
                    "EventBus already exists: " + name, 400);
        }
        EventBus bus = new EventBus(
                name,
                regionResolver.buildArn("events", region, "event-bus/" + name),
                description,
                Instant.now()
        );
        if (tags != null) {
            bus.getTags().putAll(tags);
        }
        busStore.put(key, bus);
        LOG.infov("Created event bus: {0} in region {1}", name, region);
        return bus;
    }

    public void deleteEventBus(String name, String region) {
        if ("default".equals(name)) {
            throw new AwsException("ValidationException", "Cannot delete the default event bus.", 400);
        }
        String key = busKey(region, name);
        busStore.get(key)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "EventBus not found: " + name, 404));
        String rulePrefix = ruleKeyPrefix(region, name);
        boolean hasRules = ruleStore.keys().stream().anyMatch(k -> k.startsWith(rulePrefix));
        if (hasRules) {
            throw new AwsException("ValidationException",
                    "Cannot delete event bus with existing rules: " + name, 400);
        }
        busStore.delete(key);
        LOG.infov("Deleted event bus: {0}", name);
    }

    public EventBus describeEventBus(String name, String region) {
        String effectiveName = name == null || name.isBlank() ? "default" : name;
        if ("default".equals(effectiveName)) {
            return getOrCreateDefaultBus(region);
        }
        return busStore.get(busKey(region, effectiveName))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "EventBus not found: " + effectiveName, 404));
    }

    public List<EventBus> listEventBuses(String namePrefix, String region) {
        getOrCreateDefaultBus(region);
        String storagePrefix = "bus:" + region + ":";
        List<EventBus> result = busStore.scan(k -> {
            if (!k.startsWith(storagePrefix)) return false;
            if (namePrefix == null || namePrefix.isBlank()) return true;
            String busName = k.substring(storagePrefix.length());
            return busName.startsWith(namePrefix);
        });
        return result;
    }

    // ──────────────────────────── Rules ────────────────────────────

    public Rule putRule(String name, String busName, String eventPattern,
                        String scheduleExpression, RuleState state, String description,
                        String roleArn, Map<String, String> tags, String region) {
        String effectiveBus = resolvedBusName(busName);
        ensureBusExists(effectiveBus, region);

        String key = ruleKey(region, effectiveBus, name);
        Rule rule = ruleStore.get(key).orElse(new Rule());
        rule.setName(name);
        rule.setArn(buildRuleArn(region, effectiveBus, name));
        rule.setEventBusName(effectiveBus);
        rule.setEventPattern(eventPattern);
        rule.setScheduleExpression(scheduleExpression);
        rule.setState(state != null ? state : RuleState.ENABLED);
        rule.setDescription(description);
        rule.setRoleArn(roleArn);
        if (tags != null) {
            rule.getTags().putAll(tags);
        }
        if (rule.getCreatedAt() == null) {
            rule.setCreatedAt(Instant.now());
        }
        ruleStore.put(key, rule);

        if (ruleScheduler != null) {
            ruleScheduler.stopScheduler(rule.getArn());
            startSchedulerIfNeeded(rule);
        }

        LOG.infov("Put rule: {0} on bus {1}", name, effectiveBus);
        return rule;
    }

    public void deleteRule(String name, String busName, String region) {
        String effectiveBus = resolvedBusName(busName);
        String key = ruleKey(region, effectiveBus, name);
        Rule rule = ruleStore.get(key)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Rule not found: " + name, 404));
        List<Target> targets = targetStore.get(key).orElse(List.of());
        if (!targets.isEmpty()) {
            throw new AwsException("ValidationException",
                    "Rule still has targets. Remove targets before deleting the rule.", 400);
        }

        if (ruleScheduler != null) {
            ruleScheduler.stopScheduler(rule.getArn());
        }

        ruleStore.delete(key);
        LOG.infov("Deleted rule: {0}", name);
    }

    public Rule describeRule(String name, String busName, String region) {
        String effectiveBus = resolvedBusName(busName);
        return ruleStore.get(ruleKey(region, effectiveBus, name))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Rule not found: " + name, 404));
    }

    public List<Rule> listRules(String busName, String namePrefix, String region) {
        String effectiveBus = resolvedBusName(busName);
        String prefix = ruleKeyPrefix(region, effectiveBus);
        return ruleStore.scan(k -> {
            if (!k.startsWith(prefix)) return false;
            if (namePrefix == null || namePrefix.isBlank()) return true;
            String ruleName = k.substring(prefix.length());
            return ruleName.startsWith(namePrefix);
        });
    }

    public void enableRule(String name, String busName, String region) {
        String effectiveBus = resolvedBusName(busName);
        String key = ruleKey(region, effectiveBus, name);
        Rule rule = ruleStore.get(key)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Rule not found: " + name, 404));
        rule.setState(RuleState.ENABLED);
        ruleStore.put(key, rule);
        startSchedulerIfNeeded(rule);
    }

    public void disableRule(String name, String busName, String region) {
        String effectiveBus = resolvedBusName(busName);
        String key = ruleKey(region, effectiveBus, name);
        Rule rule = ruleStore.get(key)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Rule not found: " + name, 404));
        rule.setState(RuleState.DISABLED);
        ruleStore.put(key, rule);

        if (ruleScheduler != null) {
            ruleScheduler.stopScheduler(rule.getArn());
        }
    }

    // ──────────────────────────── Targets ────────────────────────────

    public int putTargets(String ruleName, String busName, List<Target> newTargets, String region) {
        String effectiveBus = resolvedBusName(busName);
        String key = ruleKey(region, effectiveBus, ruleName);
        ruleStore.get(key)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Rule not found: " + ruleName, 404));
        List<Target> existing = new ArrayList<>(targetStore.get(key).orElse(new ArrayList<>()));
        for (Target newTarget : newTargets) {
            existing.removeIf(t -> t.getId().equals(newTarget.getId()));
            existing.add(newTarget);
        }
        targetStore.put(key, existing);
        LOG.infov("Put {0} targets on rule {1}", newTargets.size(), ruleName);
        return 0;
    }

    public record RemoveTargetsResult(int successfulCount, int failedCount) {}

    public RemoveTargetsResult removeTargets(String ruleName, String busName,
                                             List<String> ids, String region) {
        String effectiveBus = resolvedBusName(busName);
        String key = ruleKey(region, effectiveBus, ruleName);
        List<Target> existing = new ArrayList<>(targetStore.get(key).orElse(new ArrayList<>()));
        int removed = 0;
        for (String id : ids) {
            if (existing.removeIf(t -> t.getId().equals(id))) {
                removed++;
            }
        }
        targetStore.put(key, existing);
        return new RemoveTargetsResult(removed, ids.size() - removed);
    }

    public List<Target> listTargetsByRule(String ruleName, String busName, String region) {
        String effectiveBus = resolvedBusName(busName);
        String key = ruleKey(region, effectiveBus, ruleName);
        ruleStore.get(key)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Rule not found: " + ruleName, 404));
        return targetStore.get(key).orElse(List.of());
    }

    // ──────────────────────────── Tags ────────────────────────────

    public Map<String, String> listTagsForResource(String resourceArn, String region) {
        // Check if it's an event bus ARN (contains "event-bus/")
        if (resourceArn.contains("event-bus/")) {
            String busName = resourceArn.substring(resourceArn.lastIndexOf("event-bus/") + "event-bus/".length());
            String key = busKey(region, busName);
            return busStore.get(key)
                    .map(EventBus::getTags)
                    .orElse(Map.of());
        }
        // Check if it's a rule ARN (contains "rule/")
        if (resourceArn.contains("rule/")) {
            String afterRule = resourceArn.substring(resourceArn.lastIndexOf("rule/") + "rule/".length());
            String busName;
            String ruleName;
            if (afterRule.contains("/")) {
                // Custom bus: rule/{busName}/{ruleName}
                int slashIdx = afterRule.indexOf('/');
                busName = afterRule.substring(0, slashIdx);
                ruleName = afterRule.substring(slashIdx + 1);
            } else {
                // Default bus: rule/{ruleName}
                busName = "default";
                ruleName = afterRule;
            }
            String key = ruleKey(region, busName, ruleName);
            return ruleStore.get(key)
                    .map(Rule::getTags)
                    .orElse(Map.of());
        }
        return Map.of();
    }

    public void tagResource(String resourceArn, Map<String, String> tags, String region) {
        if (resourceArn.contains("event-bus/")) {
            String busName = resourceArn.substring(resourceArn.lastIndexOf("event-bus/") + "event-bus/".length());
            String key = busKey(region, busName);
            EventBus bus = busStore.get(key)
                    .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                            "Resource not found: " + resourceArn, 404));
            bus.getTags().putAll(tags);
            busStore.put(key, bus);
            return;
        }
        if (resourceArn.contains("rule/")) {
            String afterRule = resourceArn.substring(resourceArn.lastIndexOf("rule/") + "rule/".length());
            String busName;
            String ruleName;
            if (afterRule.contains("/")) {
                int slashIdx = afterRule.indexOf('/');
                busName = afterRule.substring(0, slashIdx);
                ruleName = afterRule.substring(slashIdx + 1);
            } else {
                busName = "default";
                ruleName = afterRule;
            }
            String key = ruleKey(region, busName, ruleName);
            Rule rule = ruleStore.get(key)
                    .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                            "Resource not found: " + resourceArn, 404));
            rule.getTags().putAll(tags);
            ruleStore.put(key, rule);
            return;
        }
        throw new AwsException("ResourceNotFoundException", "Resource not found: " + resourceArn, 404);
    }

    public void untagResource(String resourceArn, List<String> tagKeys, String region) {
        if (resourceArn.contains("event-bus/")) {
            String busName = resourceArn.substring(resourceArn.lastIndexOf("event-bus/") + "event-bus/".length());
            String key = busKey(region, busName);
            EventBus bus = busStore.get(key)
                    .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                            "Resource not found: " + resourceArn, 404));
            tagKeys.forEach(bus.getTags()::remove);
            busStore.put(key, bus);
            return;
        }
        if (resourceArn.contains("rule/")) {
            String afterRule = resourceArn.substring(resourceArn.lastIndexOf("rule/") + "rule/".length());
            String busName;
            String ruleName;
            if (afterRule.contains("/")) {
                int slashIdx = afterRule.indexOf('/');
                busName = afterRule.substring(0, slashIdx);
                ruleName = afterRule.substring(slashIdx + 1);
            } else {
                busName = "default";
                ruleName = afterRule;
            }
            String key = ruleKey(region, busName, ruleName);
            Rule rule = ruleStore.get(key)
                    .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                            "Resource not found: " + resourceArn, 404));
            tagKeys.forEach(rule.getTags()::remove);
            ruleStore.put(key, rule);
            return;
        }
        throw new AwsException("ResourceNotFoundException", "Resource not found: " + resourceArn, 404);
    }

    // ──────────────────────────── Permissions ────────────────────────────

    public void putPermission(String busName, String action, String principal,
                              String statementId, String conditionJson, String policyJson, String region) {
        String effectiveBus = resolvedBusName(busName);
        if ("default".equals(effectiveBus)) {
            getOrCreateDefaultBus(region);
        }
        String key = busKey(region, effectiveBus);
        EventBus bus = busStore.get(key)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "EventBus not found: " + effectiveBus, 404));

        try {
            if (policyJson != null && !policyJson.isBlank()) {
                bus.setPolicy(policyJson);
            } else {
                String currentPolicy = bus.getPolicy();
                ObjectNode policy;
                if (currentPolicy != null && !currentPolicy.isBlank()) {
                    policy = (ObjectNode) objectMapper.readTree(currentPolicy);
                } else {
                    policy = objectMapper.createObjectNode();
                    policy.put("Version", "2012-10-17");
                    policy.putArray("Statement");
                }

                ArrayNode statements = (ArrayNode) policy.get("Statement");
                for (int i = 0; i < statements.size(); i++) {
                    if (statementId.equals(statements.get(i).path("Sid").asText(null))) {
                        statements.remove(i);
                        break;
                    }
                }

                ObjectNode statement = objectMapper.createObjectNode();
                statement.put("Sid", statementId);
                statement.put("Effect", "Allow");
                statement.put("Principal", principal != null ? principal : "*");
                statement.put("Action", action != null ? action : "events:PutEvents");
                statement.put("Resource", bus.getArn());
                if (conditionJson != null && !conditionJson.isBlank()) {
                    statement.set("Condition", objectMapper.readTree(conditionJson));
                }
                statements.add(statement);
                bus.setPolicy(objectMapper.writeValueAsString(policy));
            }
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InternalException", "Failed to process permission policy: " + e.getMessage(), 500);
        }

        busStore.put(key, bus);
        LOG.infov("Put permission on bus {0}, statement {1}", effectiveBus, statementId);
    }

    public void removePermission(String busName, String statementId, boolean removeAll, String region) {
        String effectiveBus = resolvedBusName(busName);
        if ("default".equals(effectiveBus)) {
            getOrCreateDefaultBus(region);
        }
        String key = busKey(region, effectiveBus);
        EventBus bus = busStore.get(key)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "EventBus not found: " + effectiveBus, 404));

        if (removeAll) {
            bus.setPolicy(null);
        } else {
            if (statementId == null || statementId.isBlank()) {
                throw new AwsException("ValidationException", "StatementId is required.", 400);
            }
            try {
                String currentPolicy = bus.getPolicy();
                if (currentPolicy == null || currentPolicy.isBlank()) {
                    throw new AwsException("ResourceNotFoundException",
                            "Statement not found: " + statementId, 400);
                }
                ObjectNode policy = (ObjectNode) objectMapper.readTree(currentPolicy);
                ArrayNode statements = (ArrayNode) policy.get("Statement");
                boolean found = false;
                for (int i = 0; i < statements.size(); i++) {
                    if (statementId.equals(statements.get(i).path("Sid").asText(null))) {
                        statements.remove(i);
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    throw new AwsException("ResourceNotFoundException",
                            "Statement not found: " + statementId, 400);
                }
                if (statements.isEmpty()) {
                    bus.setPolicy(null);
                } else {
                    bus.setPolicy(objectMapper.writeValueAsString(policy));
                }
            } catch (AwsException e) {
                throw e;
            } catch (Exception e) {
                throw new AwsException("InternalException", "Failed to process permission policy: " + e.getMessage(), 500);
            }
        }

        busStore.put(key, bus);
        LOG.infov("Removed permission from bus {0}, statement {1}, removeAll {2}", effectiveBus, statementId, removeAll);
    }

    // ──────────────────────────── PutEvents ────────────────────────────

    public record PutEventsResult(int failedCount, List<Map<String, String>> entries) {}

    public PutEventsResult putEvents(List<Map<String, Object>> entries, String region) {
        int failed = 0;
        List<Map<String, String>> resultEntries = new ArrayList<>();

        for (Map<String, Object> entry : entries) {
            String eventBusNameRaw = (String) entry.get("EventBusName");
            String effectiveBus = resolvedBusName(eventBusNameRaw);
            String busStoreKey = busKey(region, effectiveBus);

            if ("default".equals(effectiveBus)) {
                getOrCreateDefaultBus(region);
            } else if (busStore.get(busStoreKey).isEmpty()) {
                failed++;
                Map<String, String> errorEntry = new HashMap<>();
                errorEntry.put("ErrorCode", "InvalidArgument");
                errorEntry.put("ErrorMessage", "EventBus not found: " + effectiveBus);
                resultEntries.add(errorEntry);
                continue;
            }

            String eventId = UUID.randomUUID().toString();
            String rulePrefix = ruleKeyPrefix(region, effectiveBus);
            List<Rule> matchedRules = ruleStore.scan(k ->
                    k.startsWith(rulePrefix) && isRuleEnabled(k));

            for (Rule rule : matchedRules) {
                if (matchesPattern(entry, rule.getEventPattern())) {
                    String ruleKey = ruleKey(region, effectiveBus, rule.getName());
                    List<Target> targets = targetStore.get(ruleKey).orElse(List.of());
                    String eventJson = buildEventEnvelope(entry, effectiveBus, eventId);
                    for (Target target : targets) {
                        invoker.invokeTarget(target, eventJson, region);
                    }
                }
            }

            Map<String, String> successEntry = new HashMap<>();
            successEntry.put("EventId", eventId);
            resultEntries.add(successEntry);
        }

        return new PutEventsResult(failed, resultEntries);
    }

    // ──────────────────────────── Pattern Matching ────────────────────────────

    boolean matchesPattern(Map<String, Object> event, String eventPattern) {
        if (eventPattern == null || eventPattern.isBlank()) {
            return true;
        }
        try {
            JsonNode pattern = objectMapper.readTree(eventPattern);
            JsonNode sourceField = pattern.get("source");
            if (sourceField != null && sourceField.isArray()) {
                String eventSource = (String) event.get("Source");
                if (!matchesArrayField(sourceField, eventSource)) {
                    return false;
                }
            }
            JsonNode detailTypeField = pattern.get("detail-type");
            if (detailTypeField != null && detailTypeField.isArray()) {
                String eventDetailType = (String) event.get("DetailType");
                if (!matchesArrayField(detailTypeField, eventDetailType)) {
                    return false;
                }
            }
            JsonNode detailPattern = pattern.get("detail");
            if (detailPattern != null && detailPattern.isObject()) {
                Object eventDetail = event.get("Detail");
                String detailStr = eventDetail instanceof String s ? s : null;
                if (detailStr == null) {
                    return false;
                }
                JsonNode detailNode = objectMapper.readTree(detailStr);
                var fields = detailPattern.fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    JsonNode expected = field.getValue();
                    JsonNode actual = detailNode.get(field.getKey());
                    String actualStr = actual != null ? actual.asText(null) : null;
                    if (expected.isArray() && !matchesArrayField(expected, actualStr)) {
                        return false;
                    }
                }
            }
            JsonNode resourcesPattern = pattern.get("resources");
            if (resourcesPattern != null && resourcesPattern.isArray()) {
                var resources = ((ArrayNode) event.get("Resources")).elements();
                while (resources.hasNext()) {
                    var resource = resources.next().asText(null);
                    if (matchesArrayField(resourcesPattern, resource)) {
                        return true;
                    }
                }
                return false;
            }
            return true;
        } catch (Exception e) {
            LOG.warnv("Failed to parse event pattern: {0}", e.getMessage());
            return false;
        }
    }

    private boolean matchesArrayField(JsonNode arrayNode, String value) {
        for (JsonNode element : arrayNode) {
            if (matchesSingleElement(element, value)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesSingleElement(JsonNode element, String value) {
        // Exact string match
        if (element.isTextual()) {
            return value != null && value.equals(element.asText());
        }
        // Null literal match
        if (element.isNull()) {
            return value == null;
        }
        // Content filter object
        if (element.isObject()) {
            if (element.has("prefix")) {
                return value != null && value.startsWith(element.get("prefix").asText());
            }
            if (element.has("suffix")) {
                return value != null && value.endsWith(element.get("suffix").asText());
            }
            if (element.has("equals-ignore-case")) {
                return value != null && value.equalsIgnoreCase(element.get("equals-ignore-case").asText());
            }
            if (element.has("anything-but")) {
                JsonNode anythingBut = element.get("anything-but");
                if (anythingBut.isArray()) {
                    for (JsonNode v : anythingBut) {
                        if (v.isTextual() && v.asText().equals(value)) return false;
                    }
                    return value != null;
                }
                if (anythingBut.isObject() && anythingBut.has("prefix")) {
                    return value != null && !value.startsWith(anythingBut.get("prefix").asText());
                }
            }
            if (element.has("exists")) {
                boolean shouldExist = element.get("exists").asBoolean();
                return shouldExist ? (value != null) : (value == null);
            }
        }
        return false;
    }

    // ──────────────────────────── Target Routing ────────────────────────────


    private String buildEventEnvelope(Map<String, Object> entry, String busName, String eventId) {
        try {
            String source = (String) entry.getOrDefault("Source", "");
            String detailType = (String) entry.getOrDefault("DetailType", "");
            String detail = (String) entry.getOrDefault("Detail", "{}");
            ArrayNode resources = (ArrayNode) entry.getOrDefault("Resources", objectMapper.createArrayNode());
            ObjectNode node = objectMapper.createObjectNode();
            node.put("version", "0");
            node.put("id", eventId);
            node.put("source", source);
            node.put("detail-type", detailType);
            node.put("account", regionResolver.getAccountId());
            node.put("time", Instant.now().toString());
            node.put("region", regionResolver.getDefaultRegion());
            node.putArray("resources").addAll(resources);
            node.set("detail", objectMapper.readTree(detail));
            node.put("event-bus-name", busName);
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{}";
        }
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private boolean isRuleEnabled(String ruleStoreKey) {
        return ruleStore.get(ruleStoreKey)
                .map(r -> r.getState() == RuleState.ENABLED)
                .orElse(false);
    }

    private void ensureBusExists(String busName, String region) {
        if ("default".equals(busName)) {
            getOrCreateDefaultBus(region);
            return;
        }
        busStore.get(busKey(region, busName))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "EventBus not found: " + busName, 404));
    }

    private static String resolvedBusName(String busName) {
        return (busName == null || busName.isBlank()) ? "default" : busName;
    }

    private static String busKey(String region, String name) {
        return "bus:" + region + ":" + name;
    }

    private static String ruleKeyPrefix(String region, String busName) {
        return "rule:" + region + ":" + busName + "/";
    }

    private static String ruleKey(String region, String busName, String ruleName) {
        return ruleKeyPrefix(region, busName) + ruleName;
    }

    private String buildRuleArn(String region, String busName, String ruleName) {
        if ("default".equals(busName)) {
            return regionResolver.buildArn("events", region, "rule/" + ruleName);
        }
        return regionResolver.buildArn("events", region, "rule/" + busName + "/" + ruleName);
    }

    private void startSchedulerIfNeeded(Rule rule) {
        if (ruleScheduler != null
                && rule.getState() == RuleState.ENABLED
                && rule.getScheduleExpression() != null
                && !rule.getScheduleExpression().isBlank()) {
            String region = rule.getRegion() != null ? rule.getRegion() : "us-east-1";
            String key = ruleKey(region, rule.getEventBusName(), rule.getName());
            ruleScheduler.startScheduler(
                rule.getArn(),
                rule.getScheduleExpression(),
                () -> {
                    Rule r = ruleStore.get(key).orElse(null);
                    List<Target> t = targetStore.get(key).orElse(List.of());
                    return new RuleScheduler.ScheduleData(r, t);
                }
            );
        }
    }

}
