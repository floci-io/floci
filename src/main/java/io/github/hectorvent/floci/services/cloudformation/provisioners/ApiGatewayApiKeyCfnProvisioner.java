package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.apigateway.ApiGatewayService;
import io.github.hectorvent.floci.services.apigateway.model.ApiKey;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::ApiGateway::ApiKey}, backed by
 * {@link ApiGatewayService}. {@code Ref} and {@code Fn::GetAtt [Key, APIKeyId]} both yield the key
 * id, matching AWS.
 *
 * <p>CustomerId, Description, Enabled and Tags update in place, and the values the key had are kept
 * on the resource so a failed stack update can put all four back. Name, Value and
 * GenerateDistinctId are createOnly in the registry schema: AWS replaces the key for a change to
 * any of them, and this provisioner has no replacement path, so such a change is reported rather
 * than applied to a key whose value callers already hold. The deprecated StageKeys property is
 * accepted without effect.
 */
@ApplicationScoped
public class ApiGatewayApiKeyCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::ApiGateway::ApiKey";
    private static final String NOT_FOUND = "NotFoundException";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Well under AWS's 1024-character name limit, and in line with the other generated names. */
    private static final int GENERATED_NAME_MAX = 128;

    private final ApiGatewayService apiGatewayService;

    @Inject
    public ApiGatewayApiKeyCfnProvisioner(ApiGatewayService apiGatewayService) {
        this.apiGatewayService = apiGatewayService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        // A snapshot describes the update in flight; one an earlier update left behind is stale.
        r.getAttributes().remove(CfnRollback.API_KEY_UPDATE_SNAPSHOT_ATTR);
        if (ctx.isUpdate()) {
            // An update re-invokes provision with the prior physical id. Creating unconditionally
            // would mint a second key on every update and orphan the first, which then outlives the
            // stack since delete only knows the id recorded last.
            ApiKey existing = apiGatewayService.findApiKey(ctx.region(), ctx.priorPhysicalId()).orElse(null);
            if (existing != null) {
                update(r, existing, props, ctx);
                return;
            }
        }
        create(r, props, ctx);
    }

    private void create(StackResource r, JsonNode props, ProvisionContext ctx) {
        String name = ctx.resolveOptional(props, "Name");
        if (name == null || name.isBlank()) {
            name = ctx.generatePhysicalName(r.getLogicalId(), GENERATED_NAME_MAX, false);
        }
        Map<String, Object> request = new HashMap<>();
        request.put("name", name);
        request.put("description", ctx.resolveOptional(props, "Description"));
        String customerId = ctx.resolveOptional(props, "CustomerId");
        if (customerId != null && !customerId.isBlank()) {
            request.put("customerId", customerId);
        }
        Boolean enabled = resolveBoolean(props, "Enabled", ctx);
        if (enabled != null) {
            request.put("enabled", enabled);
        }
        Boolean generateDistinctId = resolveBoolean(props, "GenerateDistinctId", ctx);
        if (generateDistinctId != null) {
            request.put("generateDistinctId", generateDistinctId);
        }
        String value = ctx.resolveOptional(props, "Value");
        if (value != null && !value.isBlank()) {
            request.put("value", value);
        }
        request.put("tags", ctx.resolveTags(props, "Tags"));

        record(r, apiGatewayService.createApiKey(ctx.region(), request));
    }

    private void update(StackResource r, ApiKey existing, JsonNode props, ProvisionContext ctx) {
        // A template that omits Name keeps whatever name the key was created with, generated or
        // not; only an explicit, different Name is a rename.
        String name = ctx.resolveOptional(props, "Name");
        if (name != null && !name.isBlank()) {
            rejectIfChanged("Name", existing.getName(), name);
        }
        String value = ctx.resolveOptional(props, "Value");
        if (value != null && !value.isBlank()) {
            rejectIfChanged("Value", existing.getValue(), value);
        }
        Boolean requestedDistinct = resolveBoolean(props, "GenerateDistinctId", ctx);
        if (requestedDistinct != null) {
            boolean existingDistinct = !Objects.equals(existing.getId(), existing.getValue());
            if (requestedDistinct != existingDistinct) {
                throw replacementNotSupported("GenerateDistinctId");
            }
        }

        List<Map<String, String>> patches = new ArrayList<>();
        String customerId = ctx.resolveOptional(props, "CustomerId");
        if (!Objects.equals(blankToNull(customerId), blankToNull(existing.getCustomerId()))) {
            patches.add(patch("/customerId", customerId));
        }
        String description = ctx.resolveOptional(props, "Description");
        if (!Objects.equals(blankToNull(description), blankToNull(existing.getDescription()))) {
            patches.add(patch("/description", description));
        }
        // Enabled defaults to false when the template omits it, as in the AWS registry schema.
        Boolean enabled = resolveBoolean(props, "Enabled", ctx);
        boolean desiredEnabled = enabled != null && enabled;
        if (desiredEnabled != existing.isEnabled()) {
            patches.add(patch("/enabled", Boolean.toString(desiredEnabled)));
        }
        Map<String, String> desiredTags = ctx.resolveTags(props, "Tags");
        boolean tagsChanged = !desiredTags.equals(existing.getTags());
        if (!patches.isEmpty() || tagsChanged) {
            snapshotBeforeUpdate(r, existing, ctx.region());
        }

        // Tags go first: the service refuses a tag change that adds a reserved tag key, and refusing
        // it before the patch leaves the key exactly as the stack last committed it.
        ApiKey key = existing;
        if (tagsChanged) {
            key = apiGatewayService.replaceApiKeyTags(ctx.region(), existing.getId(), desiredTags);
        }
        if (!patches.isEmpty()) {
            key = apiGatewayService.updateApiKey(ctx.region(), existing.getId(), patches);
        }
        record(r, key);
    }

    /**
     * Keeps the customer id, description, enabled flag and tags the key has before an in-place
     * update changes them, so {@link #rollbackUpdate} can put them back. Written as text before the
     * first mutating call, because the service updates the stored key object in place.
     */
    private static void snapshotBeforeUpdate(StackResource r, ApiKey existing, String region) {
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("region", region);
        snapshot.put("id", existing.getId());
        snapshot.put("customerId", existing.getCustomerId());
        snapshot.put("description", existing.getDescription());
        snapshot.put("enabled", existing.isEnabled());
        ObjectNode tags = snapshot.putObject("tags");
        existing.getTags().forEach(tags::put);
        r.getAttributes().put(CfnRollback.API_KEY_UPDATE_SNAPSHOT_ATTR, snapshot.toString());
    }

    private static void record(StackResource r, ApiKey key) {
        r.setPhysicalId(key.getId());
        r.getAttributes().put("APIKeyId", key.getId());
    }

    private static Boolean resolveBoolean(JsonNode props, String name, ProvisionContext ctx) {
        String resolved = ctx.resolveOptional(props, name);
        return resolved == null || resolved.isBlank() ? null : Boolean.parseBoolean(resolved);
    }

    private static void rejectIfChanged(String property, String existing, String requested) {
        if (!Objects.equals(blankToNull(existing), blankToNull(requested))) {
            throw replacementNotSupported(property);
        }
    }

    private static AwsException replacementNotSupported(String property) {
        return new AwsException("ValidationError",
                "Updating " + property + " requires resource replacement, which is not supported.", 400);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** A null value clears the field, the way a template that drops Description reads on AWS. */
    private static Map<String, String> patch(String path, String value) {
        Map<String, String> op = new HashMap<>();
        op.put("op", "replace");
        op.put("path", path);
        op.put("value", value);
        return op;
    }

    /** Without this the key outlives its stack and keeps authenticating requests. */
    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null || physicalId.isBlank()) {
            return;
        }
        CfnDeletes.safeDelete("API key", physicalId,
                () -> apiGatewayService.deleteApiKey(region, physicalId), NOT_FOUND);
    }

    /**
     * Puts the customer id, description, enabled flag and tags back from the snapshot the update
     * took. With no snapshot on the resource the update changed nothing, since the snapshot is
     * taken before every mutating call, so there is nothing to undo. A key deleted since the update
     * fails the restore with NotFoundException, and the stack reports the rollback as failed.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        // The snapshot is spent only once the restore succeeded: a restore that throws leaves it in
        // place for the next attempt instead of reporting a rollback that never happened.
        String raw = resource.getAttributes().get(CfnRollback.API_KEY_UPDATE_SNAPSHOT_ATTR);
        if (raw == null) {
            return true;
        }
        JsonNode snapshot;
        try {
            snapshot = MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read the API key update snapshot for "
                    + resource.getLogicalId(), e);
        }
        String region = snapshot.path("region").asText();
        String id = snapshot.path("id").asText();
        Map<String, String> tags = new HashMap<>();
        snapshot.path("tags").fields().forEachRemaining(tag -> tags.put(tag.getKey(), tag.getValue().asText()));
        apiGatewayService.replaceApiKeyTags(region, id, tags);
        apiGatewayService.updateApiKey(region, id, List.of(
                patch("/customerId", snapshotText(snapshot, "customerId")),
                patch("/description", snapshotText(snapshot, "description")),
                patch("/enabled", Boolean.toString(snapshot.path("enabled").asBoolean()))));
        resource.getAttributes().remove(CfnRollback.API_KEY_UPDATE_SNAPSHOT_ATTR);
        return true;
    }

    /** A snapshotted string, or null when the key carried none. */
    private static String snapshotText(JsonNode snapshot, String field) {
        JsonNode value = snapshot.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
