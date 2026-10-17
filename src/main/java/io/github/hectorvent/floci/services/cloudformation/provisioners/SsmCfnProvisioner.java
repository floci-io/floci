package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ssm.SsmService;
import io.github.hectorvent.floci.services.ssm.model.Parameter;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Provisions {@code AWS::SSM::Parameter}. The parameter name is the physical id and {@code Ref}.
 *
 * <p>A create, and the replacement a changed name makes, is one {@code PutParameter} that carries
 * the template's tags and does not overwrite, as AWS's handler sends it: a name that already exists
 * outside the stack fails the resource instead of being overwritten or adopted. An update that keeps
 * the name overwrites the parameter and drives the template's tags, after keeping what it changes on
 * the resource, so a failed stack update can put it back. A replacement leaves the displaced
 * parameter to the {@link ReplacementCleanup} record: deleted once the update commits, unless
 * {@code UpdateReplacePolicy} is {@code Retain}, and named again when the update rolls back.
 */
@ApplicationScoped
public class SsmCfnProvisioner implements CfnResourceProvisioner {

    private static final int PARAMETER_NAME_MAX_LENGTH = 2048;
    private static final String SSM_TEMPLATE_TAG_KEYS_ATTR = "FlociSsmTemplateTagKeys";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SsmService ssmService;

    public SsmCfnProvisioner(SsmService ssmService) {
        this.ssmService = ssmService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::SSM::Parameter");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        // A snapshot describes the update in flight; one an earlier update left behind is stale.
        r.getAttributes().remove(CfnRollback.SSM_PARAMETER_UPDATE_SNAPSHOT_ATTR);
        if (ctx.isUpdate()) {
            // Rolling this update back undoes it alone; only ContinueUpdateRollback retries the
            // delete the last rollback could not make.
            ReplacementCleanup.endRollbackRetry(r);
        }
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        String name = ctx.stablePhysicalName(ctx.resolveOptional(props, "Name"),
                r.getLogicalId(), PARAMETER_NAME_MAX_LENGTH, false);
        String value = ctx.resolveOptional(props, "Value");
        if (value == null) {
            value = "";
        }
        String type = ctx.resolveOptional(props, "Type");
        if (type == null) {
            type = "String";
        }
        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        SsmService.validateTagKeys(tags);
        // A dispatcher stub created nothing, so a parameter that takes over its id is a create.
        if (ctx.reusesPriorEntity(name) && !CfnResourceDispatcher.isStub(name, attributesBefore)) {
            snapshotBeforeUpdate(r, name, attributesBefore, tags.keySet(), ctx.region());
            ssmService.putParameter(name, value, type, null, true, ctx.region());
            reconcileTags(name, templateTagKeys(attributesBefore), tags, ctx.region());
        } else {
            ssmService.putParameter(name, value, type, null, false, tags, ctx.region());
        }
        r.setPhysicalId(name);
        r.getAttributes().put("Name", name);
        r.getAttributes().put("Type", type);
        r.getAttributes().put("Value", value);
        r.getAttributes().put("Arn", parameterArn(name, ctx));
        // Tag keys cannot contain a comma, so the sorted keys join losslessly.
        if (tags.isEmpty()) {
            r.getAttributes().remove(SSM_TEMPLATE_TAG_KEYS_ATTR);
        } else {
            r.getAttributes().put(SSM_TEMPLATE_TAG_KEYS_ATTR, String.join(",", new TreeSet<>(tags.keySet())));
        }
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    /**
     * Keeps what an update in place is about to change, so {@link #rollbackUpdate} can put it back:
     * the parameter's value and settings, the tags of the keys the previous template or this one
     * manages, and the resource's attributes. Reading it first also fails the update of a parameter
     * deleted outside the stack with {@code ParameterNotFound} before anything is written, as AWS's
     * handler checks that the parameter exists.
     */
    private void snapshotBeforeUpdate(StackResource r, String name, Map<String, String> attributesBefore,
                                      Set<String> desiredTagKeys, String region) {
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("region", region);
        snapshot.put("name", name);
        ObjectNode attributes = snapshot.putObject("attributes");
        attributesBefore.forEach((key, attribute) -> {
            if (!key.startsWith("__Floci") && attribute != null) {
                attributes.put(key, attribute);
            }
        });
        Parameter current = ssmService.getParameter(name, region);
        snapshot.put("value", current.getValue());
        snapshot.put("type", current.getType());
        snapshot.put("description", current.getDescription());
        snapshot.put("keyId", current.getKeyId());
        snapshot.put("allowedPattern", current.getAllowedPattern());
        snapshot.put("tier", current.getTier());
        if (current.getPolicies() != null) {
            snapshot.putArray("policies").addAll(current.getPolicies());
        }
        // A managed key the parameter did not carry is listed without a value, so the rollback removes it.
        Set<String> managedKeys = new TreeSet<>(templateTagKeys(attributesBefore));
        managedKeys.addAll(desiredTagKeys);
        Map<String, String> currentTags = current.getTags() == null ? Map.of() : current.getTags();
        ArrayNode tagKeys = snapshot.putArray("tagKeys");
        ObjectNode tags = snapshot.putObject("tags");
        for (String key : managedKeys) {
            tagKeys.add(key);
            if (currentTags.containsKey(key)) {
                tags.put(key, currentTags.get(key));
            }
        }
        r.getAttributes().put(CfnRollback.SSM_PARAMETER_UPDATE_SNAPSHOT_ATTR, snapshot.toString());
    }

    /**
     * Applies {@code desired} and removes the {@code managedKeys} it does not hold, so a tag added
     * outside the template stays, as in AWS. The overwriting put keeps the parameter's existing tags,
     * and a parameter provisioned before the keys were recorded has none to drop.
     */
    private void reconcileTags(String name, Collection<String> managedKeys, Map<String, String> desired,
                               String region) {
        List<String> dropped = managedKeys.stream()
                .filter(key -> !desired.containsKey(key))
                .toList();
        if (!dropped.isEmpty()) {
            ssmService.removeTagsFromResource(name, dropped, region);
        }
        if (!desired.isEmpty()) {
            ssmService.addTagsToResource(name, desired, region);
        }
    }

    /** The tag keys the template that provisioned the resource set. */
    private static List<String> templateTagKeys(Map<String, String> attributes) {
        String keys = attributes.get(SSM_TEMPLATE_TAG_KEYS_ATTR);
        return keys == null || keys.isEmpty() ? List.of() : Arrays.asList(keys.split(","));
    }

    /** AWS's form is {@code parameter/<name>} whether or not the name starts with a slash. */
    private static String parameterArn(String name, ProvisionContext ctx) {
        String path = name.startsWith("/") ? name : "/" + name;
        return AwsArnUtils.Arn.of("ssm", ctx.region(), ctx.accountId(), "parameter" + path).toString();
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        // ParameterNotFound is SSM's own code for a parameter that is already gone.
        CfnDeletes.safeDelete("SSM parameter", physicalId,
                () -> ssmService.deleteParameter(physicalId, region), "ParameterNotFound");
    }

    /**
     * An update in place that failed after its snapshot may already have changed the parameter, so
     * the engine keeps this attempt for {@link #rollbackUpdate} instead of putting the previous
     * resource back over it. A failure before the snapshot changed nothing and is left to the engine.
     */
    @Override
    public boolean retainsFailedUpdateState(StackResource resource) {
        return resource.getAttributes().containsKey(CfnRollback.SSM_PARAMETER_UPDATE_SNAPSHOT_ATTR);
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return ReplacementCleanup.hasReplacement(resource);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return ReplacementCleanup.cleanupPhysicalId(resource);
    }

    /**
     * The engine calls this for every resource once a stack update commits, but {@link #clearUpdate}
     * only after a replacement's cleanup, so an in-place update's snapshot is dropped here: the update
     * committed and nothing will roll it back.
     */
    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        resource.getAttributes().remove(CfnRollback.SSM_PARAMETER_UPDATE_SNAPSHOT_ATTR);
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(CfnRollback.SSM_PARAMETER_UPDATE_SNAPSHOT_ATTR);
        ReplacementCleanup.clear(resource);
    }

    /**
     * A replacement is undone through the cleanup record, and an update in place from the snapshot
     * taken before it. Like AWS's rollback, which runs its update handler, the restore needs the
     * parameter to exist: one deleted outside the stack fails it with {@code ParameterNotFound}. The
     * value and settings go back with one overwrite, which SSM keeps as a new version, unless the
     * parameter already holds them; the managed tags go back to the values they had, and the resource
     * gets back the attributes it had. The snapshot is spent only once the restore succeeded, so a
     * restore that throws leaves it for ContinueUpdateRollback to retry. With neither on the resource
     * there is nothing to restore from.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (ReplacementCleanup.rollback(resource, this::delete)) {
            return true;
        }
        String raw = resource.getAttributes().get(CfnRollback.SSM_PARAMETER_UPDATE_SNAPSHOT_ATTR);
        if (raw == null) {
            return false;
        }
        JsonNode snapshot;
        try {
            snapshot = MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read the SSM parameter update snapshot for "
                    + resource.getLogicalId(), e);
        }
        String region = snapshot.path("region").asText();
        String name = snapshot.path("name").asText();
        restore(ssmService.getParameter(name, region), snapshot, name, region);
        resource.getAttributes().keySet().removeIf(key -> !key.startsWith("__Floci"));
        snapshot.path("attributes").properties()
                .forEach(attribute -> resource.getAttributes().put(attribute.getKey(), attribute.getValue().asText()));
        resource.getAttributes().remove(CfnRollback.SSM_PARAMETER_UPDATE_SNAPSHOT_ATTR);
        return true;
    }

    /** Puts the snapshot's value, settings and managed tags back on the parameter. */
    private void restore(Parameter current, JsonNode snapshot, String name, String region) {
        String value = snapshot.path("value").asText();
        String type = snapshot.path("type").textValue();
        String description = snapshot.path("description").textValue();
        String keyId = snapshot.path("keyId").textValue();
        String allowedPattern = snapshot.path("allowedPattern").textValue();
        String tier = snapshot.path("tier").textValue();
        List<JsonNode> policies = null;
        if (snapshot.has("policies")) {
            policies = new ArrayList<>();
            snapshot.path("policies").forEach(policies::add);
        }
        boolean unchanged = value.equals(current.getValue()) && Objects.equals(type, current.getType())
                && Objects.equals(description, current.getDescription())
                && Objects.equals(keyId, current.getKeyId())
                && Objects.equals(allowedPattern, current.getAllowedPattern())
                && Objects.equals(tier, current.getTier())
                && Objects.equals(policies, current.getPolicies());
        if (!unchanged) {
            ssmService.putParameter(name, value, type, description, true, null, keyId, allowedPattern, tier,
                    policies, region);
        }
        List<String> managedKeys = new ArrayList<>();
        snapshot.path("tagKeys").forEach(key -> managedKeys.add(key.asText()));
        Map<String, String> tags = new HashMap<>();
        snapshot.path("tags").properties().forEach(tag -> tags.put(tag.getKey(), tag.getValue().asText()));
        reconcileTags(name, managedKeys, tags, region);
    }
}
