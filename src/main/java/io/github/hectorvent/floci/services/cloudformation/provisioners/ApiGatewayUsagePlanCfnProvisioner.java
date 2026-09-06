package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ReservedTags;
import io.github.hectorvent.floci.services.apigateway.ApiGatewayService;
import io.github.hectorvent.floci.services.apigateway.model.UsagePlan;
import io.github.hectorvent.floci.services.apigateway.model.UsagePlanKey;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::ApiGateway::UsagePlan} and
 * {@code AWS::ApiGateway::UsagePlanKey}, backed by {@link ApiGatewayService}.
 *
 * <p>A usage plan's {@code Ref} and {@code Fn::GetAtt Id} are the plan id. Every plan property is
 * mutable, so an update patches the plan in place, adding and removing API stages as the template
 * moves them. Throttle and Quota, on the plan and per stage, have no counterpart in
 * {@link UsagePlan} and are accepted without effect.
 *
 * <p>A usage plan key's {@code Ref} and {@code Fn::GetAtt Id} are {@code <keyId>:<usagePlanId>},
 * as on AWS. All three of its properties are createOnly, so a change to any of them is refused as a
 * replacement rather than re-associating a different key or plan under the old id.
 */
@ApplicationScoped
public class ApiGatewayUsagePlanCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(ApiGatewayUsagePlanCfnProvisioner.class);

    private static final String USAGE_PLAN = "AWS::ApiGateway::UsagePlan";
    private static final String USAGE_PLAN_KEY = "AWS::ApiGateway::UsagePlanKey";
    private static final String NOT_FOUND = "NotFoundException";
    private static final String KEY_ID_SEPARATOR = ":";
    /** The registry schema's only allowed KeyType. */
    private static final String API_KEY_TYPE = "API_KEY";
    /** Well under AWS's 1024-character name limit, and in line with the other generated names. */
    private static final int GENERATED_NAME_MAX = 128;

    private final ApiGatewayService apiGatewayService;

    @Inject
    public ApiGatewayUsagePlanCfnProvisioner(ApiGatewayService apiGatewayService) {
        this.apiGatewayService = apiGatewayService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(USAGE_PLAN, USAGE_PLAN_KEY);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case USAGE_PLAN -> provisionUsagePlan(r, props, ctx);
            case USAGE_PLAN_KEY -> provisionUsagePlanKey(r, props, ctx);
            default -> throw new IllegalStateException(
                    "ApiGatewayUsagePlanCfnProvisioner cannot handle " + r.getResourceType());
        }
    }

    // ──────────────────────────── UsagePlan ────────────────────────────

    private void provisionUsagePlan(StackResource r, JsonNode props, ProvisionContext ctx) {
        if (ctx.isUpdate()) {
            // An update re-invokes provision with the prior physical id. Creating unconditionally
            // would mint a second plan on every update and orphan the first, which then outlives
            // the stack since delete only knows the id recorded last.
            UsagePlan existing = findUsagePlan(ctx.region(), ctx.priorPhysicalId());
            if (existing != null) {
                updateUsagePlan(r, existing, props, ctx);
                return;
            }
        }
        String name = ctx.resolveOptional(props, "UsagePlanName");
        if (name == null || name.isBlank()) {
            name = ctx.generatePhysicalName(r.getLogicalId(), GENERATED_NAME_MAX, false);
        }
        Map<String, Object> request = new HashMap<>();
        request.put("name", name);
        request.put("description", ctx.resolveOptional(props, "Description"));
        List<Map<String, Object>> apiStages = new ArrayList<>();
        for (UsagePlan.ApiStage stage : apiStages(props, ctx)) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("apiId", stage.apiId());
            entry.put("stage", stage.stage());
            apiStages.add(entry);
        }
        request.put("apiStages", apiStages);
        request.put("tags", ctx.resolveTags(props, "Tags"));

        recordPlan(r, apiGatewayService.createUsagePlan(ctx.region(), request));
    }

    private void updateUsagePlan(StackResource r, UsagePlan existing, JsonNode props, ProvisionContext ctx) {
        List<Map<String, String>> patches = new ArrayList<>();
        // A template that omits UsagePlanName keeps whatever name the plan was created with.
        String name = ctx.resolveOptional(props, "UsagePlanName");
        if (name != null && !name.isBlank() && !name.equals(existing.getName())) {
            patches.add(patch("replace", "/name", name));
        }
        String description = ctx.resolveOptional(props, "Description");
        if (!Objects.equals(blankToNull(description), blankToNull(existing.getDescription()))) {
            // The service rejects a null patch value, so a dropped Description clears to "".
            patches.add(patch("replace", "/description", description == null ? "" : description));
        }
        // ApiStages is a set the template owns outright: stages it no longer lists come off the
        // plan, stages it adds go on. The service models that as add/remove of "apiId:stage".
        List<UsagePlan.ApiStage> desired = apiStages(props, ctx);
        List<UsagePlan.ApiStage> current = List.copyOf(existing.getApiStages());
        for (UsagePlan.ApiStage stage : current) {
            if (!desired.contains(stage)) {
                patches.add(patch("remove", "/apiStages", stage.apiId() + KEY_ID_SEPARATOR + stage.stage()));
            }
        }
        for (UsagePlan.ApiStage stage : desired) {
            if (!current.contains(stage)) {
                patches.add(patch("add", "/apiStages", stage.apiId() + KEY_ID_SEPARATOR + stage.stage()));
            }
        }
        UsagePlan plan = patches.isEmpty()
                ? existing
                : apiGatewayService.updateUsagePlan(ctx.region(), existing.getId(), patches);

        // The stored tags never include the reserved id-override keys (createUsagePlan strips them),
        // so the comparison ignores them too; the service strips them again on the way in.
        Map<String, String> desiredTags = ctx.resolveTags(props, "Tags");
        if (!ReservedTags.stripApiGatewayReservedTags(desiredTags).equals(plan.getTags())) {
            plan = apiGatewayService.replaceUsagePlanTags(ctx.region(), plan.getId(), desiredTags);
        }
        recordPlan(r, plan);
    }

    /** The template's ApiStages as (apiId, stage) pairs, in template order. */
    private static List<UsagePlan.ApiStage> apiStages(JsonNode props, ProvisionContext ctx) {
        List<UsagePlan.ApiStage> stages = new ArrayList<>();
        if (props == null || !props.has("ApiStages")) {
            return stages;
        }
        JsonNode resolved = ctx.engine().resolveNode(props.get("ApiStages"));
        if (resolved == null || resolved.isNull()) {
            return stages;
        }
        if (!resolved.isArray()) {
            throw new AwsException("ValidationError", "ApiStages of a usage plan must be a list.", 400);
        }
        for (JsonNode entry : resolved) {
            String apiId = blankToNull(ctx.engine().resolve(entry.path("ApiId")));
            String stage = blankToNull(ctx.engine().resolve(entry.path("Stage")));
            if (apiId == null || stage == null) {
                throw new AwsException("ValidationError",
                        "Every ApiStages entry of a usage plan needs both ApiId and Stage.", 400);
            }
            UsagePlan.ApiStage apiStage = new UsagePlan.ApiStage(apiId, stage);
            if (!stages.contains(apiStage)) {
                stages.add(apiStage);
            }
        }
        return stages;
    }

    private UsagePlan findUsagePlan(String region, String usagePlanId) {
        try {
            return apiGatewayService.getUsagePlan(region, usagePlanId);
        } catch (AwsException e) {
            if (!NOT_FOUND.equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("Usage plan {0} from the previous provision is gone, creating a new one", usagePlanId);
            return null;
        }
    }

    private static void recordPlan(StackResource r, UsagePlan plan) {
        r.setPhysicalId(plan.getId());
        r.getAttributes().put("Id", plan.getId());
    }

    // ──────────────────────────── UsagePlanKey ────────────────────────────

    private void provisionUsagePlanKey(StackResource r, JsonNode props, ProvisionContext ctx) {
        String usagePlanId = blankToNull(ctx.resolveOptional(props, "UsagePlanId"));
        String keyId = blankToNull(ctx.resolveOptional(props, "KeyId"));
        String keyType = blankToNull(ctx.resolveOptional(props, "KeyType"));
        if (usagePlanId == null || keyId == null) {
            throw new AwsException("ValidationError",
                    "AWS::ApiGateway::UsagePlanKey requires both UsagePlanId and KeyId.", 400);
        }
        if (!API_KEY_TYPE.equals(keyType)) {
            throw new AwsException("ValidationError",
                    "KeyType of a usage plan key must be " + API_KEY_TYPE + ".", 400);
        }

        String[] prior = ctx.isUpdate() ? splitKeyId(ctx.priorPhysicalId()) : null;
        if (prior != null) {
            // KeyId is createOnly and nothing legitimate changes it, so it is checked whether or not
            // the plan is still there. UsagePlanId is different: deleteUsagePlan leaves associations
            // behind and a plan recreated after an out-of-band delete has a new id, so a changed
            // plan id is a move only while the plan it moved off still exists.
            rejectIfChanged("KeyId", prior[0], keyId);
            UsagePlanKey existing = findUsagePlan(ctx.region(), prior[1]) == null
                    ? null
                    : findUsagePlanKey(ctx.region(), prior[1], prior[0]);
            if (existing != null) {
                rejectIfChanged("UsagePlanId", prior[1], usagePlanId);
                rejectIfChanged("KeyType", existing.getType(), keyType);
                recordKey(r, prior[0], prior[1]);
                return;
            }
        }
        Map<String, Object> request = new HashMap<>();
        request.put("keyId", keyId);
        request.put("keyType", keyType);
        apiGatewayService.createUsagePlanKey(ctx.region(), usagePlanId, request);
        recordKey(r, keyId, usagePlanId);
    }

    private UsagePlanKey findUsagePlanKey(String region, String usagePlanId, String keyId) {
        try {
            return apiGatewayService.getUsagePlanKey(region, usagePlanId, keyId);
        } catch (AwsException e) {
            if (!NOT_FOUND.equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("Usage plan key {0} on plan {1} from the previous provision is gone, creating it again",
                    keyId, usagePlanId);
            return null;
        }
    }

    /** AWS reports a usage plan key as {@code <keyId>:<usagePlanId>}; that is also what delete needs. */
    private static void recordKey(StackResource r, String keyId, String usagePlanId) {
        String id = keyId + KEY_ID_SEPARATOR + usagePlanId;
        r.setPhysicalId(id);
        r.getAttributes().put("Id", id);
    }

    /** {@code [keyId, usagePlanId]}, or null when the id is not in that shape. */
    private static String[] splitKeyId(String physicalId) {
        if (physicalId == null) {
            return null;
        }
        int separator = physicalId.indexOf(KEY_ID_SEPARATOR);
        if (separator <= 0 || separator == physicalId.length() - 1) {
            return null;
        }
        return new String[] {physicalId.substring(0, separator), physicalId.substring(separator + 1)};
    }

    // ──────────────────────────── shared ────────────────────────────

    private static void rejectIfChanged(String property, String existing, String requested) {
        if (!Objects.equals(blankToNull(existing), blankToNull(requested))) {
            throw new AwsException("ValidationError",
                    "Updating " + property + " requires resource replacement, which is not supported.", 400);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Map<String, String> patch(String op, String path, String value) {
        Map<String, String> patch = new HashMap<>();
        patch.put("op", op);
        patch.put("path", path);
        patch.put("value", value);
        return patch;
    }

    /** Without this the plan, or the key's membership in it, outlives the stack. */
    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null || physicalId.isBlank()) {
            return;
        }
        switch (resourceType) {
            case USAGE_PLAN -> CfnDeletes.safeDelete("usage plan", physicalId,
                    () -> apiGatewayService.deleteUsagePlan(region, physicalId), NOT_FOUND);
            case USAGE_PLAN_KEY -> {
                String[] parts = splitKeyId(physicalId);
                if (parts == null) {
                    LOG.warnv("Usage plan key {0} has no <keyId>:<usagePlanId> physical id, leaving it in place",
                            physicalId);
                    return;
                }
                CfnDeletes.safeDelete("usage plan key", physicalId,
                        () -> apiGatewayService.deleteUsagePlanKey(region, parts[1], parts[0]), NOT_FOUND);
            }
            default -> { }
        }
    }
}
