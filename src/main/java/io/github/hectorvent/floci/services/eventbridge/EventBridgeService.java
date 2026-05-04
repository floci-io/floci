package io.github.hectorvent.floci.services.eventbridge;

import com.fasterxml.jackson.databind.node.ArrayNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.eventbridge.model.Archive;
import io.github.hectorvent.floci.services.eventbridge.model.ArchiveState;
import io.github.hectorvent.floci.services.eventbridge.model.ArchivedEvent;
import io.github.hectorvent.floci.services.eventbridge.model.EventBus;
import io.github.hectorvent.floci.services.eventbridge.model.Replay;
import io.github.hectorvent.floci.services.eventbridge.model.ReplayState;
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
    private final StorageBackend<String, Archive> archiveStore;
    private final StorageBackend<String, List<ArchivedEvent>> archivedEventStore;
    private final StorageBackend<String, Replay> replayStore;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    private final RuleScheduler ruleScheduler;
    private final EventBridgeInvoker invoker;
    private final ReplayDispatcher replayDispatcher;

    @Inject
    public EventBridgeService(StorageFactory storageFactory,
                              EmulatorConfig config,
                              RegionResolver regionResolver,
                              ObjectMapper objectMapper,
                              RuleScheduler ruleScheduler,
                              EventBridgeInvoker invoker,
                              ReplayDispatcher replayDispatcher) {
        this(
                storageFactory.create("eventbridge", "eventbridge-buses.json",
                        new TypeReference<Map<String, EventBus>>() {}),
                storageFactory.create("eventbridge", "eventbridge-rules.json",
                        new TypeReference<Map<String, Rule>>() {}),
                storageFactory.create("eventbridge", "eventbridge-targets.json",
                        new TypeReference<Map<String, List<Target>>>() {}),
                storageFactory.create("eventbridge", "eventbridge-archives.json",
                        new TypeReference<Map<String, Archive>>() {}),
                storageFactory.create("eventbridge", "eventbridge-archived-events.json",
                        new TypeReference<Map<String, List<ArchivedEvent>>>() {}),
                storageFactory.create("eventbridge", "eventbridge-replays.json",
                        new TypeReference<Map<String, Replay>>() {}),
                regionResolver, objectMapper, ruleScheduler, invoker, replayDispatcher
        );
    }

    EventBridgeService(StorageBackend<String, EventBus> busStore,
                       StorageBackend<String, Rule> ruleStore,
                       StorageBackend<String, List<Target>> targetStore,
                       StorageBackend<String, Archive> archiveStore,
                       StorageBackend<String, List<ArchivedEvent>> archivedEventStore,
                       StorageBackend<String, Replay> replayStore,
                       RegionResolver regionResolver,
                       ObjectMapper objectMapper,
                       RuleScheduler ruleScheduler,
                       EventBridgeInvoker invoker,
                       ReplayDispatcher replayDispatcher) {
        this.busStore = busStore;
        this.ruleStore = ruleStore;
        this.targetStore = targetStore;
        this.archiveStore = archiveStore;
        this.archivedEventStore = archivedEventStore;
        this.replayStore = replayStore;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
        this.ruleScheduler = ruleScheduler;
        this.invoker = invoker;
        this.replayDispatcher = replayDispatcher;
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
        if (resourceArn.contains("archive/")) {
            String archiveName = resourceArn.substring(resourceArn.lastIndexOf("archive/") + "archive/".length());
            String key = archiveKey(region, archiveName);
            return archiveStore.get(key)
                    .map(Archive::getTags)
                    .orElse(Map.of());
        }
        return Map.of();
    }

    public void tagResource(String resourceArn, Map<String, String> tags, String region) {
        if (resourceArn.contains("archive/")) {
            String archiveName = resourceArn.substring(resourceArn.lastIndexOf("archive/") + "archive/".length());
            String key = archiveKey(region, archiveName);
            Archive archive = archiveStore.get(key)
                    .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                            "Archive not found: " + archiveName, 404));
            archive.getTags().putAll(tags);
            archiveStore.put(key, archive);
            return;
        }
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
        if (resourceArn.contains("archive/")) {
            String archiveName = resourceArn.substring(resourceArn.lastIndexOf("archive/") + "archive/".length());
            String key = archiveKey(region, archiveName);
            Archive archive = archiveStore.get(key)
                    .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                            "Archive not found: " + archiveName, 404));
            tagKeys.forEach(archive.getTags()::remove);
            archiveStore.put(key, archive);
            return;
        }
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

            captureToArchives(entry, busStoreKey, eventId, region);

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

    // ──────────────────────────── Archives ────────────────────────────

    public Archive createArchive(String archiveName, String eventSourceArn, String description,
                                 String eventPattern, int retentionDays, String region) {
        if (archiveName == null || archiveName.isBlank()) {
            throw new AwsException("ValidationException", "ArchiveName is required.", 400);
        }
        if (eventSourceArn == null || eventSourceArn.isBlank()) {
            throw new AwsException("ValidationException", "EventSourceArn is required.", 400);
        }
        String key = archiveKey(region, archiveName);
        if (archiveStore.get(key).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsException",
                    "Archive already exists: " + archiveName, 400);
        }
        Archive archive = new Archive();
        archive.setArchiveName(archiveName);
        archive.setArchiveArn(regionResolver.buildArn("events", region, "archive/" + archiveName));
        archive.setEventSourceArn(eventSourceArn);
        archive.setDescription(description);
        archive.setEventPattern(eventPattern);
        archive.setRetentionDays(retentionDays);
        archive.setState(ArchiveState.ENABLED);
        archive.setCreationTime(Instant.now());
        archiveStore.put(key, archive);
        LOG.infov("Created archive: {0} for source {1}", archiveName, eventSourceArn);
        return archive;
    }

    public Archive describeArchive(String archiveName, String region) {
        return archiveStore.get(archiveKey(region, archiveName))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Archive not found: " + archiveName, 404));
    }

    public Archive updateArchive(String archiveName, String description,
                                 String eventPattern, int retentionDays, String region) {
        String key = archiveKey(region, archiveName);
        Archive archive = archiveStore.get(key)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Archive not found: " + archiveName, 404));
        if (description != null) {
            archive.setDescription(description);
        }
        archive.setEventPattern(eventPattern);
        archive.setRetentionDays(retentionDays);
        archiveStore.put(key, archive);
        return archive;
    }

    public void deleteArchive(String archiveName, String region) {
        String key = archiveKey(region, archiveName);
        archiveStore.get(key)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Archive not found: " + archiveName, 404));
        archiveStore.delete(key);
        archivedEventStore.delete(archivedEventKey(region, archiveName));
        LOG.infov("Deleted archive: {0}", archiveName);
    }

    public List<Archive> listArchives(String namePrefix, String eventSourceArn,
                                      ArchiveState state, String region) {
        String prefix = "archive:" + region + ":";
        return archiveStore.scan(k -> {
            if (!k.startsWith(prefix)) return false;
            Archive a = archiveStore.get(k).orElse(null);
            if (a == null) return false;
            if (namePrefix != null && !namePrefix.isBlank()
                    && !a.getArchiveName().startsWith(namePrefix)) {
                return false;
            }
            if (eventSourceArn != null && !eventSourceArn.isBlank()
                    && !eventSourceArn.equals(a.getEventSourceArn())) {
                return false;
            }
            if (state != null && state != a.getState()) {
                return false;
            }
            return true;
        });
    }

    private void captureToArchives(Map<String, Object> entry, String busStoreKey,
                                   String eventId, String region) {
        EventBus bus = busStore.get(busStoreKey).orElse(null);
        if (bus == null) {
            return;
        }
        String busArn = bus.getArn();
        String archivePrefix = "archive:" + region + ":";
        List<Archive> candidates = archiveStore.scan(k ->
                k.startsWith(archivePrefix)
                        && archiveStore.get(k).map(a ->
                        a.getState() == ArchiveState.ENABLED
                                && busArn.equals(a.getEventSourceArn())).orElse(false));

        for (Archive archive : candidates) {
            if (matchesPattern(entry, archive.getEventPattern())) {
                String evKey = archivedEventKey(region, archive.getArchiveName());
                List<ArchivedEvent> stored = new ArrayList<>(
                        archivedEventStore.get(evKey).orElse(new ArrayList<>()));
                ArchivedEvent ae = new ArchivedEvent(
                        eventId,
                        Instant.now(),
                        (String) entry.get("Source"),
                        (String) entry.get("DetailType"),
                        (String) entry.get("Detail"),
                        busArn
                );
                stored.add(ae);
                archivedEventStore.put(evKey, stored);
                archive.setEventCount(archive.getEventCount() + 1);
                archiveStore.put(archiveKey(region, archive.getArchiveName()), archive);
            }
        }
    }

    // ──────────────────────────── Replays ────────────────────────────

    public Replay startReplay(String replayName, String description, String eventSourceArn,
                              Instant eventStartTime, Instant eventEndTime,
                              String destinationArn, String region) {
        if (replayName == null || replayName.isBlank()) {
            throw new AwsException("ValidationException", "ReplayName is required.", 400);
        }
        String key = replayKey(region, replayName);
        if (replayStore.get(key).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsException",
                    "Replay already exists: " + replayName, 400);
        }

        // resolve archive
        String archiveName = archiveNameFromArn(eventSourceArn);
        Archive archive = archiveStore.get(archiveKey(region, archiveName))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Archive not found: " + archiveName, 404));

        List<ArchivedEvent> events = archivedEventStore
                .get(archivedEventKey(region, archiveName))
                .orElse(List.of());

        Replay replay = new Replay();
        replay.setReplayName(replayName);
        replay.setReplayArn(regionResolver.buildArn("events", region, "replay/" + replayName));
        replay.setDescription(description);
        replay.setEventSourceArn(eventSourceArn);
        replay.setDestinationArn(destinationArn);
        replay.setEventStartTime(eventStartTime);
        replay.setEventEndTime(eventEndTime);
        replay.setState(ReplayState.STARTING);
        replay.setReplayStartTime(Instant.now());
        replayStore.put(key, replay);

        replayDispatcher.dispatch(
                replay,
                events,
                entries -> putEvents(entries, region),
                (name, state) -> updateReplayState(name, state, region),
                time -> updateReplayLastReplayed(replayName, time, region)
        );

        LOG.infov("Started replay: {0} from archive {1}", replayName, archiveName);
        return replay;
    }

    public Replay describeReplay(String replayName, String region) {
        return replayStore.get(replayKey(region, replayName))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Replay not found: " + replayName, 404));
    }

    public Replay cancelReplay(String replayName, String region) {
        String key = replayKey(region, replayName);
        Replay replay = replayStore.get(key)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Replay not found: " + replayName, 404));
        if (replay.getState() != ReplayState.RUNNING && replay.getState() != ReplayState.STARTING) {
            throw new AwsException("IllegalStatusException",
                    "Replay is not in a cancellable state: " + replay.getState(), 400);
        }
        boolean signalled = replayDispatcher.requestCancel(replayName);
        if (!signalled) {
            // already completed between check and cancel
            replay = replayStore.get(key).orElse(replay);
        } else {
            replay.setState(ReplayState.CANCELLING);
            replay.setStateReason("Cancellation requested.");
            replayStore.put(key, replay);
        }
        return replay;
    }

    public List<Replay> listReplays(String namePrefix, String eventSourceArn,
                                    ReplayState state, String region) {
        String prefix = "replay:" + region + ":";
        return replayStore.scan(k -> {
            if (!k.startsWith(prefix)) return false;
            Replay r = replayStore.get(k).orElse(null);
            if (r == null) return false;
            if (namePrefix != null && !namePrefix.isBlank()
                    && !r.getReplayName().startsWith(namePrefix)) {
                return false;
            }
            if (eventSourceArn != null && !eventSourceArn.isBlank()
                    && !eventSourceArn.equals(r.getEventSourceArn())) {
                return false;
            }
            if (state != null && state != r.getState()) {
                return false;
            }
            return true;
        });
    }

    void updateReplayState(String replayName, ReplayState state, String region) {
        String key = replayKey(region, replayName);
        replayStore.get(key).ifPresent(r -> {
            r.setState(state);
            if (state == ReplayState.COMPLETED || state == ReplayState.CANCELLED
                    || state == ReplayState.FAILED) {
                r.setReplayEndTime(Instant.now());
            }
            replayStore.put(key, r);
            LOG.debugv("Replay {0} transitioned to {1}", replayName, state);
        });
    }

    void updateReplayLastReplayed(String replayName, Instant eventTime, String region) {
        String key = replayKey(region, replayName);
        replayStore.get(key).ifPresent(r -> {
            r.setEventLastReplayedTime(eventTime);
            replayStore.put(key, r);
        });
    }

    // ──────────────────────────── Storage key helpers ────────────────────────────

    private static String archiveKey(String region, String archiveName) {
        return "archive:" + region + ":" + archiveName;
    }

    private static String archivedEventKey(String region, String archiveName) {
        return "archivedEvents:" + region + ":" + archiveName;
    }

    private static String replayKey(String region, String replayName) {
        return "replay:" + region + ":" + replayName;
    }

    private static String archiveNameFromArn(String arn) {
        if (arn == null) return null;
        int idx = arn.lastIndexOf("archive/");
        return idx >= 0 ? arn.substring(idx + "archive/".length()) : arn;
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
