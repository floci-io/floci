package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.redshift.RedshiftService;
import io.github.hectorvent.floci.services.redshift.model.EventSubscription;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class RedshiftEventSubscriptionCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::Redshift::EventSubscription";
    private static final String UPDATE_SNAPSHOT = CfnRollback.USES_ATTR_PREFIX + "RedshiftEventSubscriptionUpdate";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final RedshiftService redshift;

    @Inject
    public RedshiftEventSubscriptionCfnProvisioner(RedshiftService redshift) {
        this.redshift = redshift;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource resource, JsonNode props, ProvisionContext ctx) {
        String name = ctx.resolveOptional(props, "SubscriptionName");
        if (name == null || name.isBlank()) {
            throw new AwsException("ValidationError", "SubscriptionName is required.", 400);
        }
        String topic = ctx.resolveOptional(props, "SnsTopicArn");
        String type = ctx.resolveOptional(props, "SourceType");
        List<String> ids = ctx.resolveStringList(props, "SourceIds");
        List<String> categories = ctx.resolveStringList(props, "EventCategories");
        String severity = ctx.resolveOptional(props, "Severity");
        String enabledValue = ctx.resolveOptional(props, "Enabled");
        Boolean enabled = enabledValue == null ? true : Boolean.valueOf(enabledValue);
        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        Map<String, String> attributesBefore = Map.copyOf(resource.getAttributes());
        if (ctx.reusesPriorEntity(name)) {
            EventSubscription prior = redshift.describeEventSubscriptions(name, null, null, null, null).items().getFirst();
            resource.getAttributes().put(UPDATE_SNAPSHOT, snapshot(prior, attributesBefore).toString());
        }
        EventSubscription subscription = ctx.reusesPriorEntity(name)
                ? redshift.reconcileEventSubscription(name, topic, type, ids, categories, severity, enabled, tags)
                : redshift.createEventSubscription(name, topic, type, ids, categories, severity, enabled, tags);
        resource.setPhysicalId(subscription.getCustSubscriptionId());
        resource.getAttributes().put("CustomerAwsId", subscription.getCustomerAwsId());
        resource.getAttributes().put("CustSubscriptionId", subscription.getCustSubscriptionId());
        resource.getAttributes().put("Status", subscription.getStatus());
        resource.getAttributes().put("SubscriptionCreationTime", subscription.getSubscriptionCreationTime().toString());
        resource.getAttributes().put("SourceIdsList", array(subscription.getSourceIdsList()).toString());
        resource.getAttributes().put("EventCategoriesList", array(subscription.getEventCategoriesList()).toString());
        if (ctx.isUpdate()) {
            ReplacementCleanup.record(resource, ctx, attributesBefore);
        }
    }

    @Override
    public Map<String, Set<String>> jsonAttributes() {
        return Map.of(TYPE, Set.of("SourceIdsList", "EventCategoriesList"));
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        CfnDeletes.safeDelete("Redshift event subscription", physicalId,
                () -> redshift.deleteEventSubscription(physicalId), "SubscriptionNotFound");
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
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(UPDATE_SNAPSHOT);
        ReplacementCleanup.clear(resource);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (ReplacementCleanup.rollback(resource, this::delete)) {
            return true;
        }
        String raw = resource.getAttributes().get(UPDATE_SNAPSHOT);
        if (raw == null) {
            return false;
        }
        try {
            JsonNode snapshot = MAPPER.readTree(raw);
            Map<String, String> tags = new LinkedHashMap<>();
            snapshot.path("tags").fields().forEachRemaining(entry -> tags.put(entry.getKey(), entry.getValue().asText()));
            redshift.restoreEventSubscription(resource.getPhysicalId(), snapshot.path("topic").asText(),
                    snapshot.path("type").asText(null), readStrings(snapshot.path("ids")),
                    readStrings(snapshot.path("categories")), snapshot.path("severity").asText(null),
                    snapshot.path("enabled").asBoolean(), tags, snapshot.path("status").asText());
            resource.getAttributes().clear();
            snapshot.path("attributes").fields().forEachRemaining(entry ->
                    resource.getAttributes().put(entry.getKey(), entry.getValue().asText()));
            return true;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid Redshift event subscription update snapshot", e);
        }
    }

    private static ObjectNode snapshot(EventSubscription subscription, Map<String, String> attributes) {
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("topic", subscription.getSnsTopicArn());
        snapshot.put("type", subscription.getSourceType());
        snapshot.put("severity", subscription.getSeverity());
        snapshot.put("enabled", subscription.isEnabled());
        snapshot.put("status", subscription.getStatus());
        snapshot.set("ids", array(subscription.getSourceIdsList()));
        snapshot.set("categories", array(subscription.getEventCategoriesList()));
        ObjectNode tags = snapshot.putObject("tags");
        subscription.getTags().forEach(tags::put);
        snapshot.set("attributes", MAPPER.valueToTree(attributes));
        return snapshot;
    }

    private static List<String> readStrings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(member -> values.add(member.asText()));
        return values;
    }

    private static ArrayNode array(List<String> values) {
        ArrayNode array = MAPPER.createArrayNode();
        values.forEach(array::add);
        return array;
    }

}
