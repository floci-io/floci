package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.CompositeAlarm;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

@ApplicationScoped
public class CloudWatchCompositeAlarmCfnProvisioner implements CfnResourceProvisioner {
    private static final String TYPE = "AWS::CloudWatch::CompositeAlarm";
    private static final String SNAPSHOT = "FlociCompositeAlarmSnapshot";
    private static final String TAG_KEYS = "FlociCompositeAlarmTagKeys";
    private static final String EXPLICIT_NAME = "FlociCompositeAlarmExplicitName";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final CloudWatchMetricsService service;

    public CloudWatchCompositeAlarmCfnProvisioner(CloudWatchMetricsService service) {
        this.service = service;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource resource, JsonNode props, ProvisionContext ctx) {
        for (String unsupported : List.of("ActionsSuppressor", "ActionsSuppressorWaitPeriod",
                "ActionsSuppressorExtensionPeriod")) {
            if (props.has(unsupported)) {
                throw new AwsException("ValidationError", "Composite action suppression is not supported", 400);
            }
        }
        if (resource.getAttributes().containsKey(SNAPSHOT)) {
            throw new AwsException("ResourceConflictException", "Alarm update cleanup is still pending", 400);
        }
        String explicit = ctx.resolveOptional(props, "AlarmName");
        boolean removedName = explicit == null && "true".equals(resource.getAttributes().get(EXPLICIT_NAME));
        String name = removedName ? ctx.generatePhysicalName(resource.getLogicalId(), 255, false)
                : ctx.stablePhysicalName(explicit, resource.getLogicalId(), 255, false);
        CompositeAlarm alarm = new CompositeAlarm();
        alarm.setAlarmName(name);
        alarm.setAlarmDescription(ctx.resolveOptional(props, "AlarmDescription"));
        alarm.setAlarmRule(ctx.resolveOptional(props, "AlarmRule"));
        alarm.setActionsEnabled(!"false".equals(ctx.resolveOptional(props, "ActionsEnabled")));
        alarm.setTags(ctx.resolveTags(props, "Tags"));
        actions(props, "AlarmActions", ctx, alarm.getAlarmActions());
        actions(props, "OKActions", ctx, alarm.getOkActions());
        actions(props, "InsufficientDataActions", ctx, alarm.getInsufficientDataActions());
        Map<String, String> desiredTags = Map.copyOf(alarm.getTags());
        Map<String, String> priorAttributes = Map.copyOf(resource.getAttributes());
        boolean inPlace = ctx.reusesPriorEntity(name);
        String updateSnapshot = null;
        if (inPlace) {
            ObjectNode snapshot = MAPPER.createObjectNode().put("region", ctx.region()).put("name", name);
            List<CompositeAlarm> previous = service.describeCompositeAlarms(List.of(name), null, ctx.region());
            if (!previous.isEmpty()) {
                snapshot.set("alarm", MAPPER.valueToTree(previous.getFirst()));
            }
            snapshot.set("attributes", MAPPER.valueToTree(priorAttributes));
            Set<String> changedKeys = new TreeSet<>(desiredTags.keySet());
            changedKeys.addAll(tagKeys(resource.getAttributes().get(TAG_KEYS)));
            snapshot.set("changedTagKeys", MAPPER.valueToTree(changedKeys));
            updateSnapshot = snapshot.toString();
        }
        service.putCompositeAlarm(alarm, ctx.region(), inPlace);
        if (inPlace) {
            resource.getAttributes().put(SNAPSHOT, updateSnapshot);
            reconcileTags(alarm.getAlarmArn(), tagKeys(priorAttributes.get(TAG_KEYS)), desiredTags, ctx.region());
        }
        resource.getAttributes().put(TAG_KEYS, MAPPER.valueToTree(desiredTags.keySet()).toString());
        resource.setPhysicalId(name);
        resource.getAttributes().put("Arn", alarm.getAlarmArn());
        resource.getAttributes().put(EXPLICIT_NAME, Boolean.toString(explicit != null));
        ReplacementCleanup.record(resource, ctx, priorAttributes);
    }

    private static void actions(JsonNode props, String field, ProvisionContext ctx, List<String> target) {
        JsonNode values = ctx.engine().resolveNode(props.path(field));
        if (values != null && values.isArray()) {
            values.forEach(value -> target.add(value.asText()));
        }
    }

    private static Set<String> tagKeys(String json) {
        Set<String> keys = new TreeSet<>();
        if (json != null) {
            try {
                MAPPER.readTree(json).forEach(value -> keys.add(value.asText()));
            } catch (JsonProcessingException error) {
                throw new IllegalStateException("Cannot read composite alarm tag ownership", error);
            }
        }
        return keys;
    }

    private void reconcileTags(String arn, Set<String> owned, Map<String, String> desired, String region) {
        List<String> removed = owned.stream().filter(key -> !desired.containsKey(key)).toList();
        if (!removed.isEmpty()) {
            service.untagResource(arn, removed, region);
        }
        if (!desired.isEmpty()) {
            service.tagResource(arn, desired, region);
        }
    }

    @Override
    public void delete(String type, String physicalId, String region) {
        service.deleteAlarms(List.of(physicalId), region);
    }

    @Override
    public boolean retainsFailedUpdateState(StackResource resource) {
        return resource.getAttributes().containsKey(SNAPSHOT);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (ReplacementCleanup.rollback(resource, this::delete)) {
            return true;
        }
        String raw = resource.getAttributes().get(SNAPSHOT);
        if (raw == null) {
            return false;
        }
        try {
            JsonNode snapshot = MAPPER.readTree(raw);
            String region = snapshot.path("region").asText();
            if (snapshot.has("alarm")) {
                CompositeAlarm original = MAPPER.treeToValue(snapshot.get("alarm"), CompositeAlarm.class);
                Map<String, String> originalTags = Map.copyOf(original.getTags());
                service.putCompositeAlarm(original, region);
                Set<String> changed = tagKeys(snapshot.path("changedTagKeys").toString());
                reconcileTags(original.getAlarmArn(), changed, originalTags.entrySet().stream()
                        .filter(entry -> changed.contains(entry.getKey()))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)), region);
            } else {
                delete(TYPE, snapshot.path("name").asText(), region);
            }
            resource.getAttributes().clear();
            snapshot.path("attributes").fields().forEachRemaining(entry ->
                    resource.getAttributes().put(entry.getKey(), entry.getValue().asText()));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot read composite alarm rollback state", error);
        }
        return true;
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return ReplacementCleanup.hasReplacement(resource);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return ReplacementCleanup.cleanupPhysicalId(resource);
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        if (resource.getAttributes().containsKey(SNAPSHOT) && !ReplacementCleanup.hasReplacement(resource)) {
            return new UpdateCleanupResult(true, true, null, 0, null);
        }
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(SNAPSHOT);
        ReplacementCleanup.clear(resource);
    }
}
