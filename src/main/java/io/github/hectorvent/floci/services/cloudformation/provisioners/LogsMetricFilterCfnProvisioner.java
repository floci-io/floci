package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackEvent;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsMetricFilterService;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsMetricFilterService.MutationOutcome;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsMetricFilterService.MutationResult;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.MetricFilter;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.MetricTransformation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static io.github.hectorvent.floci.services.cloudformation.provisioners.ProvisionContext.report;

/**
 * Metric-filter Ref and physical id are the name alone. Group identity is private metadata.
 * AWS replaces this type by deleting first, including with UpdateReplacePolicy Retain.
 * The complete prior definition survives until commit or successful rollback.
 */
@ApplicationScoped
public class LogsMetricFilterCfnProvisioner implements CfnResourceProvisioner {
    private static final String TYPE = "AWS::Logs::MetricFilter";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String GROUP_ATTR = "__FlociMetricFilterLogGroupName";
    private static final String NAME_MODE_ATTR = "__FlociMetricFilterNameMode";
    private static final String SNAPSHOT = CfnRollback.METRIC_FILTER_UPDATE_SNAPSHOT_ATTR;
    private static final String UNCERTAIN_ATTR = "__FlociMetricFilterOwnershipUncertain";
    private static final String ABSENT_ATTR = "__FlociMetricFilterBackingAbsent";
    private static final String STATE_ATTR = "__FlociMetricFilterState";
    private static final String REPLACEMENT_REASON = "Requested update requires the replacement of the existing resource; "
            + "deleting existing resource, then creating a new one.";
    private static final Set<String> UNITS = Set.of("Seconds", "Microseconds", "Milliseconds", "Bytes", "Kilobytes",
            "Megabytes", "Gigabytes", "Terabytes", "Bits", "Kilobits", "Megabits", "Gigabits", "Terabits", "Percent",
            "Count", "Bytes/Second", "Kilobytes/Second", "Megabytes/Second", "Gigabytes/Second", "Terabytes/Second",
            "Bits/Second", "Kilobits/Second", "Megabits/Second", "Gigabits/Second", "Terabits/Second", "Count/Second", "None");
    private final CloudWatchLogsMetricFilterService metricFilters;

    private enum Ownership { OWNED, UNOWNED, UNKNOWN }
    private enum Operation { CREATE, UPDATE, DELETE, OBSERVE }

    @Inject
    public LogsMetricFilterCfnProvisioner(CloudWatchLogsMetricFilterService metricFilters) {
        this.metricFilters = metricFilters;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        JsonNode resolved = pruneNoValue(ctx.engine().resolveNode(markNoValue(props)));
        MetricFilter definition = definition(resolved);
        String explicitName = string(resolved, "FilterName", false, 1, 512, "[^:*]+");
        Identity prior = ctx.isUpdate() ? identity(r, ctx.priorPhysicalId()) : null;
        String name = explicitName != null ? explicitName
                : prior != null && !"explicit".equals(r.getAttributes().get(NAME_MODE_ATTR))
                ? prior.name() : ctx.generatePhysicalName(r.getLogicalId(), 512, false);
        definition.setFilterName(name);
        Identity target = new Identity(definition.getLogGroupName(), name);
        ObjectNode snapshot = prior == null ? null : snapshot(r, prior, target, ctx.region());
        boolean replacement = snapshot != null && snapshot.path("replacement").asBoolean();
        ObjectNode state = snapshot == null ? state(target, Ownership.UNOWNED) : targetState(snapshot);
        if (replacement) {
            report(ctx.progress(), prior.name(), "DELETE_IN_PROGRESS", REPLACEMENT_REASON);
            try {
                mutate(r, priorState(snapshot), snapshot, Operation.DELETE, null, ctx.region());
            } catch (RuntimeException e) {
                report(ctx.progress(), prior.name(), "DELETE_FAILED", e.getMessage());
                throw e;
            }
            report(ctx.progress(), prior.name(), "DELETE_COMPLETE", null);
            setIdentity(r, target, explicitName != null);
            report(ctx.progress(), target.name(), "CREATE_IN_PROGRESS", null);
        }
        if (snapshot == null) {
            r.getAttributes().put(GROUP_ATTR, target.group());
            r.getAttributes().put(NAME_MODE_ATTR, explicitName != null ? "explicit" : "generated");
        }
        try {
            mutate(r, state, snapshot, prior == null || replacement ? Operation.CREATE : Operation.UPDATE,
                    definition, ctx.region());
        } catch (RuntimeException e) {
            if (replacement) {
                report(ctx.progress(), target.name(), "CREATE_FAILED", e.getMessage());
            }
            throw e;
        }
        setIdentity(r, target, explicitName != null);
        if (replacement) {
            report(ctx.progress(), target.name(), "CREATE_COMPLETE", null);
        }
    }

    private static void setIdentity(StackResource r, Identity identity, boolean explicit) {
        r.setPhysicalId(identity.name());
        r.getAttributes().put(GROUP_ATTR, identity.group());
        r.getAttributes().put(NAME_MODE_ATTR, explicit ? "explicit" : "generated");
    }

    private record Identity(String group, String name) {}

    private static Identity identity(StackResource r, String physicalId) {
        String group = r.getAttributes().get(GROUP_ATTR);
        if (group == null) {
            throw new IllegalStateException("Missing metric filter group identity for " + r.getLogicalId());
        }
        return new Identity(group, physicalId);
    }

    private ObjectNode snapshot(StackResource r, Identity prior, Identity target, String region) {
        if (r.getAttributes().containsKey(SNAPSHOT)) {
            throw new IllegalStateException("Metric filter update still needs rollback or commit: " + r.getLogicalId());
        }
        ObjectNode priorState = currentState(r, prior);
        resolveUnknown(r, priorState, null, region);
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("region", region);
        snapshot.put("replacement", !prior.equals(target));
        snapshot.set("attributes", MAPPER.valueToTree(r.getAttributes()));
        if (ownership(priorState) == Ownership.UNOWNED) {
            snapshot.put("absent", true);
        } else {
            metricFilters.findMetricFilter(prior.group(), prior.name(), region).ifPresentOrElse(
                    filter -> snapshot.set("definition", MAPPER.valueToTree(filter)),
                    () -> {
                        snapshot.put("absent", true);
                        priorState.put("ownership", Ownership.UNOWNED.name());
                    });
        }
        snapshot.set("prior", priorState);
        if (!prior.equals(target)) {
            snapshot.set("target", state(target, Ownership.UNOWNED));
        }
        save(r, snapshot);
        return snapshot;
    }

    private static void save(StackResource r, ObjectNode snapshot) {
        r.getAttributes().put(SNAPSHOT, snapshot.toString());
    }

    @Override
    public void delete(StackResource resource, String region) {
        String raw = resource.getAttributes().get(SNAPSHOT);
        if (raw != null) {
            ObjectNode snapshot = object(raw);
            if (snapshot.path("replacement").asBoolean()) {
                mutate(resource, targetState(snapshot), snapshot, Operation.DELETE, null, region);
            }
            mutate(resource, priorState(snapshot), snapshot, Operation.DELETE, null, region);
        } else if (resource.getPhysicalId() != null) {
            mutate(resource, currentState(resource, identity(resource, resource.getPhysicalId())),
                    null, Operation.DELETE, null, region);
        }
    }

    private static ObjectNode state(Identity identity, Ownership ownership) {
        ObjectNode state = MAPPER.createObjectNode();
        state.put("group", identity.group());
        state.put("name", identity.name());
        state.put("ownership", ownership.name());
        state.put("operation", Operation.OBSERVE.name());
        state.put("outcome", ownership == Ownership.UNKNOWN
                ? MutationOutcome.UNKNOWN.name() : MutationOutcome.NOT_APPLIED.name());
        return state;
    }

    private ObjectNode currentState(StackResource resource, Identity identity) {
        String raw = resource.getAttributes().get(STATE_ATTR);
        if (raw != null) {
            ObjectNode state = object(raw);
            if (!identity.equals(address(state))) {
                throw new IllegalStateException("Metric filter state does not match its physical identity");
            }
            return state;
        }
        if ("true".equals(resource.getAttributes().get(UNCERTAIN_ATTR))) {
            return state(identity, Ownership.UNKNOWN);
        }
        if ("true".equals(resource.getAttributes().get(ABSENT_ATTR))) {
            return state(identity, Ownership.UNOWNED);
        }
        boolean completed = "CREATE_COMPLETE".equals(resource.getStatus()) || "UPDATE_COMPLETE".equals(resource.getStatus());
        return state(identity, completed ? Ownership.OWNED : Ownership.UNKNOWN);
    }

    private static Ownership ownership(ObjectNode state) {
        return Ownership.valueOf(state.path("ownership").asText());
    }

    private static Identity address(ObjectNode state) {
        return new Identity(state.path("group").asText(), state.path("name").asText());
    }

    private static ObjectNode priorState(ObjectNode snapshot) {
        return (ObjectNode) snapshot.get("prior");
    }

    private static ObjectNode targetState(ObjectNode snapshot) {
        return snapshot.path("replacement").asBoolean() ? (ObjectNode) snapshot.get("target") : priorState(snapshot);
    }

    private static ObjectNode object(String raw) {
        try {
            if (MAPPER.readTree(raw) instanceof ObjectNode object) {
                return object;
            }
            throw new IllegalStateException("Metric filter mutation metadata must be an object");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid metric filter mutation metadata", e);
        }
    }

    private static void saveTracking(StackResource resource, ObjectNode state, ObjectNode snapshot) {
        if (snapshot == null) {
            resource.getAttributes().put(STATE_ATTR, state.toString());
        } else {
            save(resource, snapshot);
        }
    }

    private void resolveUnknown(StackResource resource, ObjectNode state, ObjectNode snapshot, String region) {
        if (ownership(state) != Ownership.UNKNOWN) {
            return;
        }
        saveTracking(resource, state, snapshot);
        Identity identity = address(state);
        if (!metricFilters.confirmMetricFilterAbsent(identity.group(), identity.name(), region, () -> {
            state.put("ownership", Ownership.UNOWNED.name());
            state.put("operation", Operation.OBSERVE.name());
            state.put("outcome", MutationOutcome.NOT_APPLIED.name());
            saveTracking(resource, state, snapshot);
        })) {
            throw new IllegalStateException("Metric filter write outcome is uncertain for "
                    + identity.group() + "/" + identity.name()
                    + "; refusing to delete or adopt a filter without confirmed ownership");
        }
    }

    private void mutate(StackResource resource, ObjectNode state, ObjectNode snapshot, Operation requested,
                         MetricFilter definition, String region) {
        resolveUnknown(resource, state, snapshot, region);
        Ownership before = ownership(state);
        if (requested == Operation.DELETE && before == Ownership.UNOWNED) {
            return;
        }
        Operation operation = requested == Operation.UPDATE && before == Ownership.UNOWNED ? Operation.CREATE : requested;
        Identity identity = address(state);
        boolean firstCreate = snapshot == null && operation == Operation.CREATE && resource.getPhysicalId() == null;
        state.put("ownership", Ownership.UNKNOWN.name());
        state.put("operation", operation.name());
        state.put("outcome", MutationOutcome.UNKNOWN.name());
        saveTracking(resource, state, snapshot);
        if (firstCreate) {
            resource.setPhysicalId(identity.name());
            resource.getAttributes().put(CfnRollback.ROLLBACK_OWNED_ATTR, "true");
        }
        Consumer<MutationResult> outcome = result -> {
            Ownership after;
            if (result.outcome() == MutationOutcome.UNKNOWN) {
                after = Ownership.UNKNOWN;
            } else if (operation == Operation.CREATE) {
                after = result.outcome() == MutationOutcome.APPLIED ? Ownership.OWNED : Ownership.UNOWNED;
            } else {
                after = result.present() == null ? before : result.present() ? Ownership.OWNED : Ownership.UNOWNED;
            }
            state.put("ownership", after.name());
            state.put("outcome", result.outcome().name());
            saveTracking(resource, state, snapshot);
            if (snapshot == null) {
                if (firstCreate && after == Ownership.UNOWNED) {
                    resource.setPhysicalId(null);
                    resource.getAttributes().remove(CfnRollback.ROLLBACK_OWNED_ATTR);
                }
                finishState(resource, state);
            }
        };
        switch (operation) {
            case CREATE -> metricFilters.createMetricFilter(definition, region, outcome);
            case UPDATE -> metricFilters.updateMetricFilter(definition, region, outcome);
            case DELETE -> metricFilters.deleteMetricFilter(identity.group(), identity.name(), region, outcome);
            case OBSERVE -> throw new IllegalStateException("Observation is not a mutation");
        }
    }

    private static void finishState(StackResource resource, ObjectNode state) {
        resource.getAttributes().put(STATE_ATTR, state.toString());
        resource.getAttributes().remove(UNCERTAIN_ATTR);
        if (ownership(state) == Ownership.OWNED) {
            resource.getAttributes().remove(ABSENT_ATTR);
        } else if (ownership(state) == Ownership.UNOWNED) {
            resource.getAttributes().put(ABSENT_ATTR, "true");
        }
    }

    @Override
    public void clearUpdate(StackResource resource) {
        String raw = resource.getAttributes().get(SNAPSHOT);
        if (raw == null) {
            return;
        }
        ObjectNode snapshot = object(raw);
        if (!canCommit(snapshot)) {
            throw new IllegalStateException("Cannot commit unconfirmed metric filter mutations");
        }
        finishState(resource, targetState(snapshot));
        resource.getAttributes().remove(SNAPSHOT);
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        // DeleteStack also invokes this hook, including after a failed rollback.
        String raw = resource.getAttributes().get(SNAPSHOT);
        if ("UPDATE_COMPLETE".equals(resource.getStatus()) && raw != null
                && canCommit(object(raw))) {
            clearUpdate(resource);
        }
        return UpdateCleanupResult.notApplicable();
    }

    private static boolean canCommit(ObjectNode snapshot) {
        return ownership(targetState(snapshot)) == Ownership.OWNED
                && (!snapshot.path("replacement").asBoolean() || ownership(priorState(snapshot)) == Ownership.UNOWNED);
    }

    @Override
    public boolean hasPendingRollbackCleanup(StackResource resource) {
        return resource.getAttributes().containsKey(SNAPSHOT)
                || resource.getAttributes().containsKey(STATE_ATTR)
                || resource.getAttributes().containsKey(UNCERTAIN_ATTR)
                || resource.getAttributes().containsKey(GROUP_ATTR)
                || resource.getAttributes().containsKey(NAME_MODE_ATTR)
                || "true".equals(resource.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR));
    }

    @Override
    public boolean retainsFailedUpdateState(StackResource resource) {
        return resource.getAttributes().containsKey(SNAPSHOT);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        return rollbackUpdate(resource, event -> {});
    }

    @Override
    public boolean rollbackUpdate(StackResource resource, Consumer<StackEvent> progress) {
        String raw = resource.getAttributes().get(SNAPSHOT);
        if (raw == null) {
            return true;
        }
        try {
            ObjectNode snapshot = object(raw);
            String region = snapshot.path("region").asText();
            boolean replacement = snapshot.path("replacement").asBoolean();
            ObjectNode priorState = priorState(snapshot);
            ObjectNode targetState = targetState(snapshot);
            Identity prior = address(priorState);
            Identity target = address(targetState);
            if (replacement) {
                report(progress, target.name(), "UPDATE_IN_PROGRESS", "Resource update rolled back");
            }
            if (replacement || snapshot.path("absent").asBoolean()) {
                resolveUnknown(resource, targetState, snapshot, region);
                boolean announceDelete = replacement && (ownership(targetState) == Ownership.OWNED
                        || metricFilters.findMetricFilter(target.group(), target.name(), region).isEmpty());
                if (announceDelete) {
                    report(progress, target.name(), "DELETE_IN_PROGRESS", null);
                }
                mutate(resource, targetState, snapshot, Operation.DELETE, null, region);
                if (announceDelete) {
                    report(progress, target.name(), "DELETE_COMPLETE", null);
                }
            }
            if (!snapshot.path("absent").asBoolean()) {
                if (replacement) {
                    report(progress, target.name(), "CREATE_IN_PROGRESS", null);
                }
                MetricFilter definition = MAPPER.treeToValue(snapshot.get("definition"), MetricFilter.class);
                mutate(resource, priorState, snapshot, Operation.UPDATE, definition, region);
                if (replacement) {
                    report(progress, prior.name(), "CREATE_COMPLETE", null);
                }
            }

            Map<String, String> attributes = new HashMap<>();
            snapshot.path("attributes").fields().forEachRemaining(e -> attributes.put(e.getKey(), e.getValue().asText()));
            resource.setAttributes(attributes);
            resource.setPhysicalId(prior.name());
            resource.getAttributes().put(GROUP_ATTR, prior.group());
            finishState(resource, priorState);
            return true;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid metric filter rollback snapshot for " + resource.getLogicalId(), e);
        }
    }

    // Preserve NoValue through intrinsic resolution without confusing it with a valid empty
    // FilterPattern. The shared engine otherwise renders both as an empty string.
    private static JsonNode markNoValue(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            if ("AWS::NoValue".equals(node.path("Ref").asText())) {
                return MissingNode.getInstance();
            }
            ObjectNode result = MAPPER.createObjectNode();
            node.fields().forEachRemaining(e -> result.set(e.getKey(), markNoValue(e.getValue())));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = MAPPER.createArrayNode();
            node.forEach(item -> result.add(markNoValue(item)));
            return result;
        }
        return node;
    }

    private static JsonNode pruneNoValue(JsonNode node) {
        if (node != null && node.isObject()) {
            ObjectNode result = MAPPER.createObjectNode();
            node.fields().forEachRemaining(e -> {
                if (!e.getValue().isMissingNode()) {
                    result.set(e.getKey(), pruneNoValue(e.getValue()));
                }
            });
            return result;
        }
        if (node != null && node.isArray()) {
            ArrayNode result = MAPPER.createArrayNode();
            node.forEach(item -> {
                if (!item.isMissingNode()) {
                    result.add(pruneNoValue(item));
                }
            });
            return result;
        }
        return node;
    }

    private static MetricFilter definition(JsonNode props) {
        if (props == null || !props.isObject()) {
            throw invalid("Properties must be an object");
        }
        onlyMembers(props, Set.of("LogGroupName", "FilterName", "FilterPattern", "MetricTransformations",
                "ApplyOnTransformedLogs", "FieldSelectionCriteria", "EmitSystemFieldDimensions"));
        MetricFilter filter = new MetricFilter();
        filter.setLogGroupName(string(props, "LogGroupName", true, 1, 512, "[.\\-_/#A-Za-z0-9]+"));
        filter.setFilterPattern(string(props, "FilterPattern", true, 0, 1024, null));
        JsonNode transformations = props.get("MetricTransformations");
        if (transformations == null || !transformations.isArray() || transformations.size() != 1
                || !transformations.get(0).isObject()) {
            throw invalid("MetricTransformations must contain exactly one object");
        }
        filter.setMetricTransformations(List.of(transformation(transformations.get(0))));
        JsonNode transformed = props.get("ApplyOnTransformedLogs");
        if (transformed != null) {
            if (!transformed.isBoolean() && (!transformed.isTextual()
                    || !Set.of("true", "false").contains(transformed.asText()))) {
                throw invalid("ApplyOnTransformedLogs must be a boolean");
            }
            filter.setApplyOnTransformedLogs(Boolean.parseBoolean(transformed.asText()));
        }
        filter.setFieldSelectionCriteria(string(props, "FieldSelectionCriteria", false, 0, 2000, null));
        JsonNode fields = props.get("EmitSystemFieldDimensions");
        if (fields != null) {
            if (!fields.isArray()) {
                throw invalid("EmitSystemFieldDimensions must be an array");
            }
            List<String> values = new ArrayList<>();
            for (JsonNode field : fields) {
                if (!field.isTextual()) {
                    throw invalid("EmitSystemFieldDimensions entries must be strings");
                }
                values.add(field.textValue());
            }
            filter.setEmitSystemFieldDimensions(values);
        }
        return filter;
    }

    private static MetricTransformation transformation(JsonNode node) {
        onlyMembers(node, Set.of("MetricName", "MetricNamespace", "MetricValue", "DefaultValue", "Dimensions", "Unit"));
        MetricTransformation t = new MetricTransformation();
        t.setMetricName(string(node, "MetricName", true, 1, 255, "[\\x00-\\x7F&&[^:*$]]+"));
        t.setMetricNamespace(string(node, "MetricNamespace", true, 1, 256, "[0-9a-zA-Z.\\-_/#]+"));
        t.setMetricValue(string(node, "MetricValue", true, 1, 100, ".+"));
        JsonNode defaultValue = node.get("DefaultValue");
        if (defaultValue != null && !defaultValue.isNull()) {
            try {
                if ((!defaultValue.isNumber() && !defaultValue.isTextual())
                        || !defaultValue.asText().matches("[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?")) {
                    throw invalid("DefaultValue must be a finite number");
                }
                double value = Double.parseDouble(defaultValue.asText());
                if (!Double.isFinite(value)) {
                    throw invalid("DefaultValue must be a finite number");
                }
                t.setDefaultValue(value);
            } catch (NumberFormatException e) {
                throw invalid("DefaultValue must be a finite number");
            }
        }
        JsonNode dimensions = node.get("Dimensions");
        if (dimensions != null) {
            if (!dimensions.isArray() || dimensions.isEmpty() || dimensions.size() > 3) {
                throw invalid("Dimensions must be an array of one to three Key/Value objects");
            }
            Map<String, String> byKey = new LinkedHashMap<>();
            for (JsonNode dimension : dimensions) {
                onlyMembers(dimension, Set.of("Key", "Value"));
                String key = string(dimension, "Key", true, 1, 255, "[\\x00-\\x7F]+");
                String value = string(dimension, "Value", true, 1, 255, null);
                if (key.isBlank() || key.startsWith(":") || byKey.putIfAbsent(key, value) != null) {
                    throw invalid("Dimensions need unique ASCII keys, nonblank and not starting with ':'");
                }
            }
            t.setDimensions(byKey);
        }
        t.setUnit(string(node, "Unit", false, 1, 64, null));
        if (t.getUnit() != null && !UNITS.contains(t.getUnit())) {
            throw invalid("Unit must be a CloudWatch metric unit");
        }
        return t;
    }

    private static void onlyMembers(JsonNode node, Set<String> members) {
        node.fieldNames().forEachRemaining(member -> {
            if (!members.contains(member)) {
                throw invalid("Unknown property " + member);
            }
        });
    }

    private static String string(JsonNode node, String member, boolean required, int min, int max, String pattern) {
        JsonNode value = node == null ? null : node.get(member);
        if (value == null && !required) {
            return null;
        }
        if (value == null || !value.isTextual() || value.textValue().length() < min
                || value.textValue().length() > max
                || pattern != null && !value.textValue().matches(pattern)) {
            throw invalid(member + " must be a string of length " + min + " to " + max
                    + (pattern == null ? "" : " matching " + pattern));
        }
        return value.textValue();
    }

    private static AwsException invalid(String message) {
        return new AwsException("ValidationError", TYPE + " " + message, 400);
    }
}
