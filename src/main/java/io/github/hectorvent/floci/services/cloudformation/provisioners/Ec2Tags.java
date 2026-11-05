package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Tag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Tag reconciliation shared by the EC2 provisioners ({@code Ec2InstanceCfnProvisioner},
 * {@code Ec2SecurityGroupCfnProvisioner}, {@code Ec2VpcCfnProvisioner} and
 * {@code Ec2FlowLogCfnProvisioner}). They drive it from the desired tags resolved via
 * {@link ProvisionContext#resolveTags}; the reconcile itself is identical: an update changes only
 * the keys the stack owns, recorded by {@link #recordKeys}, and keeps a tag added outside the stack,
 * as AWS does.
 */
final class Ec2Tags {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Ec2Tags() {
    }

    static List<Tag> validatedTags(Map<String, String> desired) {
        List<Tag> tags = desired.entrySet().stream()
                .map(entry -> new Tag(entry.getKey(), entry.getValue())).toList();
        Ec2Service.validateUserTagKeys(tags);
        return tags;
    }

    /**
     * The user tags EC2 currently holds for the resource, keyed by tag key in DescribeTags order.
     * Reserved {@code aws:} keys are never part of the user tag set, so neither
     * {@link #reconcileOwned} nor a rollback snapshot taken from this map can try to write one.
     */
    static Map<String, String> current(Ec2Service ec2Service, String region, String resourceId) {
        Map<String, String> current = new LinkedHashMap<>();
        for (Map<String, String> entry : ec2Service.describeTags(region, Map.of("resource-id", List.of(resourceId)))) {
            if (!Ec2Service.isAwsTagKey(entry.get("key"))) {
                current.put(entry.get("key"), entry.get("value"));
            }
        }
        return current;
    }

    /**
     * Brings the keys a template owns to {@code desired} and leaves every other tag alone, as AWS
     * keeps a tag added outside the stack: removes the {@code owned} keys {@code desired} no longer
     * declares, then creates or overwrites the declared ones. {@code owned} is the key set the
     * last deployment wrote, or the keys an update touched when a rollback restores them. Returns
     * whether any of those tags were anything other than {@code desired} before.
     */
    static boolean reconcileOwned(Ec2Service ec2Service, String region, String resourceId, Set<String> owned,
                                  Map<String, String> desired) {
        List<Tag> add = validatedTags(desired);
        Map<String, String> current = current(ec2Service, region, resourceId);
        boolean changed = changesOwnedTags(owned, desired, current);
        List<Tag> remove = new ArrayList<>();
        for (String key : owned) {
            if (!desired.containsKey(key) && current.containsKey(key)) {
                remove.add(new Tag(key, current.get(key)));
            }
        }
        if (!remove.isEmpty()) {
            ec2Service.deleteTags(region, List.of(resourceId), remove);
        }
        if (!add.isEmpty()) {
            ec2Service.createTags(region, List.of(resourceId), add);
        }
        return changed;
    }

    /**
     * The keys the last deployment recorded in {@code recordAttr}. A resource provisioned before
     * that was recorded falls back to the previous template's keys, resolved as well as they still
     * can be. Recorded rather than re-resolved, because a key taken from a parameter resolves to the
     * new value on the next update.
     */
    static Set<String> ownedKeys(StackResource r, String recordAttr, ProvisionContext ctx) {
        String recorded = r.getAttributes().get(recordAttr);
        if (recorded == null) {
            return ctx.resolveTags(ctx.priorProperties(), "Tags").keySet();
        }
        Set<String> keys = new LinkedHashSet<>();
        try {
            MAPPER.readTree(recorded).forEach(key -> keys.add(key.asText()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable tag keys on " + r.getLogicalId(), e);
        }
        return keys;
    }

    /**
     * Records the keys this deployment resolved and wrote, as a JSON array: the keys the stack owns.
     * An empty array is recorded too: re-resolving the previous template could read keys this
     * deployment never wrote (an {@code Fn::If} over the next update's conditions), so only a
     * resource provisioned before records were kept falls back to that.
     */
    static void recordKeys(StackResource r, String recordAttr, Map<String, String> desired) {
        ArrayNode keys = MAPPER.createArrayNode();
        desired.keySet().forEach(keys::add);
        r.getAttributes().put(recordAttr, keys.toString());
    }

    /**
     * Whether an update to {@code desired} changes a tag or the owned key set. A change of ownership
     * alone (taking over a key that already holds the declared value) still needs the record put
     * back, so it counts as a change too.
     */
    static boolean changesOwned(Set<String> owned, Map<String, String> desired, Map<String, String> current) {
        return !owned.equals(desired.keySet()) || changesOwnedTags(owned, desired, current);
    }

    private static boolean changesOwnedTags(Set<String> owned, Map<String, String> desired,
                                            Map<String, String> current) {
        return touched(owned, desired).stream().anyMatch(key -> desired.containsKey(key)
                ? !current.containsKey(key) || !Objects.equals(desired.get(key), current.get(key))
                : current.containsKey(key));
    }

    /**
     * Records in {@code snapshot} what {@link #restoreOwned} needs: the keys an update touches (the
     * owned ones and the declared ones), the values they had, and the ownership record the update
     * replaces. Tags outside the touched set are never written, so they are not recorded either.
     */
    static void snapshotOwned(ObjectNode snapshot, Set<String> owned, Map<String, String> desired,
                              Map<String, String> current, String priorRecord) {
        Set<String> touched = touched(owned, desired);
        touched.forEach(snapshot.putArray("touched")::add);
        ObjectNode tags = snapshot.putObject("tags");
        touched.stream().filter(current::containsKey).forEach(key -> tags.put(key, current.get(key)));
        snapshot.put("tagKeys", priorRecord);
    }

    /**
     * Puts the touched keys back from a {@link #snapshotOwned} snapshot: a key the resource had gets
     * its value again, and a key the update added is removed. The ownership record goes back too.
     */
    static void restoreOwned(Ec2Service ec2Service, String region, String resourceId, StackResource r,
                             String recordAttr, JsonNode snapshot) {
        Set<String> touched = new LinkedHashSet<>();
        snapshot.path("touched").forEach(key -> touched.add(key.asText()));
        Map<String, String> priorTags = new LinkedHashMap<>();
        // EC2 accepts a tag with no value; asText() would turn the saved null into the text "null".
        snapshot.path("tags").properties().forEach(f -> priorTags.put(f.getKey(),
                f.getValue().isNull() ? null : f.getValue().asText()));
        reconcileOwned(ec2Service, region, resourceId, touched, priorTags);
        restoreRecord(r, recordAttr, snapshot);
    }

    /** Puts back the ownership record a snapshot carries in {@code tagKeys}, or its absence. */
    static void restoreRecord(StackResource r, String recordAttr, JsonNode snapshot) {
        String tagKeys = CfnRollback.snapshotText(snapshot, "tagKeys");
        if (tagKeys == null) {
            r.getAttributes().remove(recordAttr);
        } else {
            r.getAttributes().put(recordAttr, tagKeys);
        }
    }

    private static Set<String> touched(Set<String> owned, Map<String, String> desired) {
        Set<String> touched = new LinkedHashSet<>(owned);
        touched.addAll(desired.keySet());
        return touched;
    }
}
