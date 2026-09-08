package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
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
 * <p>Description, Enabled and Tags update in place. Name, Value and GenerateDistinctId are
 * createOnly in the registry schema: AWS replaces the key for a change to any of them, and this
 * provisioner has no replacement path, so such a change is reported rather than applied to a key
 * whose value callers already hold. CustomerId and the deprecated StageKeys have no counterpart in
 * {@link ApiKey} and are accepted without effect.
 */
@ApplicationScoped
public class ApiGatewayApiKeyCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::ApiGateway::ApiKey";
    private static final String NOT_FOUND = "NotFoundException";
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
        // createApiKey gives a key with GenerateDistinctId=false its value as its id, so whether the
        // two differ records which mode the key was created in.
        boolean requestedDistinct = Boolean.TRUE.equals(resolveBoolean(props, "GenerateDistinctId", ctx));
        boolean existingDistinct = !Objects.equals(existing.getId(), existing.getValue());
        if (requestedDistinct != existingDistinct) {
            throw replacementNotSupported("GenerateDistinctId");
        }

        List<Map<String, String>> patches = new ArrayList<>();
        String description = ctx.resolveOptional(props, "Description");
        if (!Objects.equals(blankToNull(description), blankToNull(existing.getDescription()))) {
            patches.add(patch("/description", description));
        }
        // Enabled defaults to true when the template omits it, as in AWS.
        Boolean enabled = resolveBoolean(props, "Enabled", ctx);
        boolean desiredEnabled = enabled == null || enabled;
        if (desiredEnabled != existing.isEnabled()) {
            patches.add(patch("/enabled", Boolean.toString(desiredEnabled)));
        }
        ApiKey key = patches.isEmpty()
                ? existing
                : apiGatewayService.updateApiKey(ctx.region(), existing.getId(), patches);

        Map<String, String> desiredTags = ctx.resolveTags(props, "Tags");
        if (!desiredTags.equals(key.getTags())) {
            key = apiGatewayService.replaceApiKeyTags(ctx.region(), key.getId(), desiredTags);
        }
        record(r, key);
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
}
