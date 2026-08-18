package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.apigateway.ApiGatewayService;
import io.github.hectorvent.floci.services.apigateway.model.ApiKey;
import io.github.hectorvent.floci.services.apigateway.model.UsagePlan;
import io.github.hectorvent.floci.services.apigateway.model.UsagePlanKey;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::ApiGateway::ApiKey}, {@code AWS::ApiGateway::UsagePlan}
 * and {@code AWS::ApiGateway::UsagePlanKey} (aws-bench gap batch, issue #17).
 */
@ApplicationScoped
public class ApiGatewayCfnProvisioner implements CfnResourceProvisioner {

    private final ApiGatewayService apiGatewayService;

    @Inject
    public ApiGatewayCfnProvisioner(ApiGatewayService apiGatewayService) {
        this.apiGatewayService = apiGatewayService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::ApiGateway::ApiKey", "AWS::ApiGateway::UsagePlan", "AWS::ApiGateway::UsagePlanKey");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case "AWS::ApiGateway::ApiKey" -> provisionApiKey(r, props, ctx);
            case "AWS::ApiGateway::UsagePlan" -> provisionUsagePlan(r, props, ctx);
            case "AWS::ApiGateway::UsagePlanKey" -> provisionUsagePlanKey(r, props, ctx);
            default -> throw new IllegalStateException("ApiGatewayCfnProvisioner cannot handle " + r.getResourceType());
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        switch (resourceType) {
            case "AWS::ApiGateway::ApiKey" -> apiGatewayService.deleteApiKey(region, physicalId);
            case "AWS::ApiGateway::UsagePlan" -> apiGatewayService.deleteUsagePlan(region, physicalId);
            case "AWS::ApiGateway::UsagePlanKey" -> deleteUsagePlanKeyFromAnyPlan(region, physicalId);
            default -> { }
        }
    }

    // ──────────────────────────── ApiKey ────────────────────────────

    private void provisionApiKey(StackResource r, JsonNode props, ProvisionContext ctx) {
        if (r.getPhysicalId() != null) {
            // ApiGatewayService#createApiKey mints a fresh random id on every call unless an
            // explicit Value is supplied, so re-creating on UpdateStack would orphan the previous
            // key (issue #16's bug). Reconcile the mutable fields in place instead.
            List<Map<String, String>> patches = new ArrayList<>();
            String name = ctx.resolveOptional(props, "Name");
            if (name != null) {
                patches.add(patch("/name", name));
            }
            String description = ctx.resolveOptional(props, "Description");
            if (description != null) {
                patches.add(patch("/description", description));
            }
            String enabled = props != null && props.has("Enabled") ? ctx.engine().resolve(props.get("Enabled")) : null;
            if (enabled != null) {
                patches.add(patch("/enabled", enabled));
            }
            ApiKey key = apiGatewayService.updateApiKey(ctx.region(), r.getPhysicalId(), patches);
            setApiKeyAttributes(r, key);
            return;
        }

        String name = ctx.resolveOptional(props, "Name");
        if (name == null || name.isBlank()) {
            name = ctx.generatePhysicalName(r.getLogicalId(), 128, false);
        }
        Map<String, Object> req = new HashMap<>();
        req.put("name", name);
        req.put("description", ctx.resolveOptional(props, "Description"));
        if (props != null && props.has("Enabled")) {
            req.put("enabled", Boolean.parseBoolean(ctx.engine().resolve(props.get("Enabled"))));
        }
        if (props != null && props.has("GenerateDistinctId")) {
            req.put("generateDistinctId", Boolean.parseBoolean(ctx.engine().resolve(props.get("GenerateDistinctId"))));
        }
        String value = ctx.resolveOptional(props, "Value");
        if (value != null && !value.isBlank()) {
            req.put("value", value);
        }
        req.put("tags", parseTagList(props, "Tags", ctx));

        ApiKey key = apiGatewayService.createApiKey(ctx.region(), req);
        setApiKeyAttributes(r, key);
    }

    private void setApiKeyAttributes(StackResource r, ApiKey key) {
        r.setPhysicalId(key.getId());
        // AWS::ApiGateway::ApiKey's only documented Fn::GetAtt attribute (AWS public schema);
        // no local registry schema is present under local/aws/cfn-resource-schemas.
        r.getAttributes().put("APIKeyId", key.getId());
    }

    // ──────────────────────────── UsagePlan ────────────────────────────

    private void provisionUsagePlan(StackResource r, JsonNode props, ProvisionContext ctx) {
        if (r.getPhysicalId() != null) {
            // ApiGatewayService has no update path for usage plans and createUsagePlan mints a
            // fresh random id every call, so re-creating on UpdateStack would orphan the previous
            // plan. Treat the plan as immutable after creation; Fn::GetAtt attributes are already
            // populated from the initial provision.
            return;
        }

        String name = ctx.resolveOptional(props, "UsagePlanName");
        Map<String, Object> req = new HashMap<>();
        req.put("name", name);
        req.put("description", ctx.resolveOptional(props, "Description"));
        req.put("tags", parseTagList(props, "Tags", ctx));

        List<Map<String, Object>> apiStages = new ArrayList<>();
        if (props != null && props.has("ApiStages") && props.get("ApiStages").isArray()) {
            for (JsonNode stage : props.get("ApiStages")) {
                JsonNode resolved = ctx.engine().resolveNode(stage);
                Map<String, Object> s = new HashMap<>();
                s.put("apiId", resolved.path("ApiId").asText(null));
                s.put("stage", resolved.path("Stage").asText(null));
                apiStages.add(s);
            }
        }
        req.put("apiStages", apiStages);

        UsagePlan plan = apiGatewayService.createUsagePlan(ctx.region(), req);
        r.setPhysicalId(plan.getId());
        // AWS::ApiGateway::UsagePlan's only documented Fn::GetAtt attribute (AWS public schema);
        // no local registry schema is present under local/aws/cfn-resource-schemas.
        r.getAttributes().put("Id", plan.getId());
    }

    // ──────────────────────────── UsagePlanKey ────────────────────────────

    private void provisionUsagePlanKey(StackResource r, JsonNode props, ProvisionContext ctx) {
        String usagePlanId = ctx.resolveOptional(props, "UsagePlanId");
        String keyId = ctx.resolveOptional(props, "KeyId");
        String keyType = ctx.resolveOptional(props, "KeyType");

        Map<String, Object> req = new HashMap<>();
        req.put("keyId", keyId);
        req.put("keyType", keyType);

        // createUsagePlanKey is keyed by (usagePlanId, keyId) from the template's own properties
        // (not randomly generated), so re-provisioning the same declared pair on UpdateStack is a
        // harmless overwrite rather than a duplicate.
        UsagePlanKey key = apiGatewayService.createUsagePlanKey(ctx.region(), usagePlanId, req);
        r.setPhysicalId(key.getId());
        // AWS::ApiGateway::UsagePlanKey's only documented Fn::GetAtt attribute (AWS public schema);
        // no local registry schema is present under local/aws/cfn-resource-schemas.
        r.getAttributes().put("Id", key.getId());
    }

    /**
     * The delete-by-type-and-physical-id path (see {@link CfnResourceProvisioner}) has no access to
     * the UsagePlanId the key was associated under, so the association is looked up across every
     * usage plan in the region and removed wherever it is found (mirrors
     * {@code ApiGatewayService#deleteApiKey}, which does the same when a key itself is deleted).
     */
    private void deleteUsagePlanKeyFromAnyPlan(String region, String keyId) {
        for (UsagePlan plan : apiGatewayService.getUsagePlans(region)) {
            try {
                apiGatewayService.deleteUsagePlanKey(region, plan.getId(), keyId);
            } catch (AwsException notAssociatedWithThisPlan) {
                // expected: the key is only associated with one (or zero) of the plans scanned
            }
        }
    }

    private static Map<String, String> patch(String path, String value) {
        Map<String, String> op = new HashMap<>();
        op.put("op", "replace");
        op.put("path", path);
        op.put("value", value);
        return op;
    }

    private Map<String, String> parseTagList(JsonNode props, String field, ProvisionContext ctx) {
        Map<String, String> tags = new HashMap<>();
        if (props == null || !props.has(field) || !props.get(field).isArray()) {
            return tags;
        }
        for (JsonNode tag : props.get(field)) {
            JsonNode resolved = ctx.engine().resolveNode(tag);
            String key = resolved.path("Key").asText(null);
            String value = resolved.path("Value").asText("");
            if (key != null) {
                tags.put(key, value);
            }
        }
        return tags;
    }
}
