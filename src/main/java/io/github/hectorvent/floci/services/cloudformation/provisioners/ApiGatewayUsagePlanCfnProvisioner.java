package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ReservedTags;
import io.github.hectorvent.floci.services.apigateway.ApiGatewayService;
import io.github.hectorvent.floci.services.apigateway.UsagePlanLimits;
import io.github.hectorvent.floci.services.apigateway.model.UsagePlan;
import io.github.hectorvent.floci.services.apigateway.model.UsagePlanKey;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::ApiGateway::UsagePlan} and
 * {@code AWS::ApiGateway::UsagePlanKey}, backed by {@link ApiGatewayService}.
 *
 * <p>A usage plan's {@code Ref} and {@code Fn::GetAtt Id} are the plan id. Every plan property is
 * mutable, so an update patches the name and description in place and replaces the API stages,
 * throttle and quota together when any of them changes. The values the plan had are kept on the
 * resource so a failed stack update can put them all back. Throttle and quota are stored and
 * reported, not enforced.
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
    private static final ObjectMapper MAPPER = new ObjectMapper();
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
        // A snapshot describes the update in flight; one an earlier update left behind is stale.
        r.getAttributes().remove(CfnRollback.USAGE_PLAN_UPDATE_SNAPSHOT_ATTR);
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
        Map<String, Object> request = limitsRequest(props, ctx);
        request.put("name", name);
        request.put("description", ctx.resolveOptional(props, "Description"));
        request.put("tags", ctx.resolveTags(props, "Tags"));

        recordPlan(r, apiGatewayService.createUsagePlan(ctx.region(), request));
        if (ctx.isUpdate()) {
            CfnRollback.recordCreatedByUpdate(r, ctx, CfnRollback.USAGE_PLAN_UPDATE_SNAPSHOT_ATTR);
        }
    }

    private void updateUsagePlan(StackResource r, UsagePlan existing, JsonNode props, ProvisionContext ctx) {
        // Validated before anything changes, so a template the service would refuse leaves the plan as it was.
        UsagePlan.Limits desiredLimits = UsagePlanLimits.fromRequest(limitsRequest(props, ctx));
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
        // ApiStages, Throttle and Quota belong to the template outright, so what it no longer lists comes
        // off the plan. They are replaced together, and only when one of them changed.
        boolean limitsChanged = !desiredLimits.equals(existing.limits());
        // The stored tags never include the reserved id-override keys (createUsagePlan strips them),
        // so the comparison ignores them too; the service strips them again on the way in.
        Map<String, String> desiredTags = ctx.resolveTags(props, "Tags");
        boolean tagsChanged = !ReservedTags.stripApiGatewayReservedTags(desiredTags).equals(existing.getTags());
        if (!patches.isEmpty() || limitsChanged || tagsChanged) {
            r.getAttributes().put(CfnRollback.USAGE_PLAN_UPDATE_SNAPSHOT_ATTR, apiGatewayService.readUsagePlan(
                    ctx.region(), existing.getId(), plan -> snapshot(plan, ctx.region())));
        }

        CfnRollback.guarded(r, ctx, false, () -> { }, () -> rollbackUpdate(r), () -> {
            if (!patches.isEmpty()) {
                apiGatewayService.updateUsagePlan(ctx.region(), existing.getId(), patches);
            }
            if (limitsChanged) {
                apiGatewayService.replaceUsagePlanLimits(ctx.region(), existing.getId(), desiredLimits);
            }
            if (tagsChanged) {
                apiGatewayService.replaceUsagePlanTags(ctx.region(), existing.getId(), desiredTags);
            }
        });
        recordPlan(r, existing);
    }

    /**
     * The name, description, stages with their method throttles, throttle, quota and tags the plan
     * has before an in-place update changes them, so {@link #rollbackUpdate} can put them back.
     * Written as text before the first mutating call, because the service updates the stored plan in
     * place, and built inside {@link ApiGatewayService#readUsagePlan} so a concurrent patch cannot
     * land halfway through it.
     */
    private static String snapshot(UsagePlan plan, String region) {
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("region", region);
        snapshot.put("id", plan.getId());
        snapshot.put("name", plan.getName());
        snapshot.put("description", plan.getDescription());
        snapshot.set("limits", MAPPER.valueToTree(plan.limits()));
        ObjectNode tags = snapshot.putObject("tags");
        plan.getTags().forEach(tags::put);
        return snapshot.toString();
    }

    /**
     * The template's ApiStages, Throttle and Quota in CreateUsagePlan's request shape, which
     * {@link UsagePlanLimits#fromRequest} validates for create and update alike.
     */
    private static Map<String, Object> limitsRequest(JsonNode props, ProvisionContext ctx) {
        Map<String, Object> request = new HashMap<>();
        request.put("apiStages", apiStages(props, ctx));
        JsonNode throttle = resolved(props, "Throttle", ctx);
        if (throttle != null) {
            CfnPropertyTypes.requireType("Throttle", throttle, JsonNodeType.OBJECT);
            request.put("throttle", throttle(throttle, ctx));
        }
        JsonNode quota = resolved(props, "Quota", ctx);
        if (quota != null) {
            CfnPropertyTypes.requireType("Quota", quota, JsonNodeType.OBJECT);
            Map<String, Object> settings = new HashMap<>();
            putResolved(settings, "limit", quota.path("Limit"), ctx);
            putResolved(settings, "offset", quota.path("Offset"), ctx);
            putResolved(settings, "period", quota.path("Period"), ctx);
            request.put("quota", settings);
        }
        return request;
    }

    /** The template's ApiStages in template order, each with its per-method Throttle. */
    private static List<Map<String, Object>> apiStages(JsonNode props, ProvisionContext ctx) {
        List<Map<String, Object>> stages = new ArrayList<>();
        JsonNode resolved = resolved(props, "ApiStages", ctx);
        if (resolved == null) {
            return stages;
        }
        CfnPropertyTypes.requireType("ApiStages", resolved, JsonNodeType.ARRAY);
        for (int i = 0; i < resolved.size(); i++) {
            JsonNode entry = resolved.get(i);
            // Template validation names a list entry by its index.
            CfnPropertyTypes.requireType(String.valueOf(i), entry, JsonNodeType.OBJECT);
            String apiId = blankToNull(ctx.engine().resolve(entry.path("ApiId")));
            String stage = blankToNull(ctx.engine().resolve(entry.path("Stage")));
            if (apiId == null || stage == null) {
                throw new AwsException("ValidationError",
                        "Every ApiStages entry of a usage plan needs both ApiId and Stage.", 400);
            }
            Map<String, Object> apiStage = new HashMap<>();
            apiStage.put("apiId", apiId);
            apiStage.put("stage", stage);
            JsonNode methods = entry.path("Throttle");
            if (!methods.isMissingNode() && !methods.isNull()) {
                CfnPropertyTypes.requireType("Throttle", methods, JsonNodeType.OBJECT);
                Map<String, Object> throttles = new LinkedHashMap<>();
                for (Map.Entry<String, JsonNode> method : methods.properties()) {
                    CfnPropertyTypes.requireType(method.getKey(), method.getValue(), JsonNodeType.OBJECT);
                    throttles.put(method.getKey(), throttle(method.getValue(), ctx));
                }
                apiStage.put("throttle", throttles);
            }
            stages.add(apiStage);
        }
        return stages;
    }

    private static Map<String, Object> throttle(JsonNode settings, ProvisionContext ctx) {
        Map<String, Object> throttle = new HashMap<>();
        putResolved(throttle, "burstLimit", settings.path("BurstLimit"), ctx);
        putResolved(throttle, "rateLimit", settings.path("RateLimit"), ctx);
        return throttle;
    }

    /**
     * {@code props[name]} with its intrinsics resolved, or null when the template leaves it out,
     * including through {@code AWS::NoValue}.
     */
    private static JsonNode resolved(JsonNode props, String name, ProvisionContext ctx) {
        if (props == null || !props.has(name)) {
            return null;
        }
        JsonNode node = ctx.engine().resolveNodeOmittingNoValue(props.get(name));
        return node == null || node.isNull() || node.isMissingNode() ? null : node;
    }

    /**
     * A member the template gives is passed on as it resolves, blank included, so the service refuses an
     * invalid one instead of reading it as absent; only a missing or null member is left out.
     */
    private static void putResolved(Map<String, Object> target, String key, JsonNode value, ProvisionContext ctx) {
        if (!value.isMissingNode() && !value.isNull()) {
            target.put(key, ctx.engine().resolve(value));
        }
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

    /**
     * An update that failed after its snapshot may already have changed the plan, and its own undo
     * may have failed too, so the engine keeps this attempt for {@link #rollbackUpdate} instead of
     * putting the previous resource back over it and losing the snapshot. A failure before the
     * snapshot changed nothing and is left to the engine.
     */
    @Override
    public boolean retainsFailedUpdateState(StackResource resource) {
        return resource.getAttributes().containsKey(CfnRollback.USAGE_PLAN_UPDATE_SNAPSHOT_ATTR);
    }

    /**
     * The engine calls this for every resource once a stack update commits, so the snapshot is
     * dropped here: the update committed and nothing will roll it back, and a snapshot left behind
     * would make a later failure keep its attempt and restore stale values.
     */
    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        resource.getAttributes().remove(CfnRollback.USAGE_PLAN_UPDATE_SNAPSHOT_ATTR);
        return UpdateCleanupResult.notApplicable();
    }

    /**
     * Undoes a usage plan update from the snapshot on the resource, and is also the in-place undo
     * {@code updateUsagePlan} hands to {@link CfnRollback#guarded}. A plan the update created is
     * removed by {@link CfnRollback#undoCreatedByUpdate}. Otherwise the name, description, stages,
     * throttle, quota and tags are put back with one restore, which runs under the service's patch
     * lock and is written whether or not the plan already matches the snapshot: a comparison read
     * outside that lock could mix two revisions of the plan. With no snapshot on the resource the
     * update changed nothing, since every mutating call is preceded by the snapshot or followed by
     * the creation record, so there is nothing to undo. A plan deleted since the update fails the
     * rollback with NotFoundException, and the stack reports the rollback as failed. A usage plan
     * key keeps the default, which answers false.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (!USAGE_PLAN.equals(resource.getResourceType())) {
            return false;
        }
        // The snapshot is spent only once the restore succeeded: a restore that throws leaves it in
        // place for the next attempt instead of reporting a rollback that never happened.
        JsonNode snapshot = CfnRollback.updateSnapshot(resource, CfnRollback.USAGE_PLAN_UPDATE_SNAPSHOT_ATTR);
        if (snapshot == null) {
            return true;
        }
        if (CfnRollback.undoCreatedByUpdate(resource, snapshot, CfnRollback.USAGE_PLAN_UPDATE_SNAPSHOT_ATTR, "Id",
                (createdId, region) -> delete(USAGE_PLAN, createdId, region))) {
            return true;
        }
        String region = snapshot.path("region").asText();
        String id = snapshot.path("id").asText();
        String name = CfnRollback.snapshotText(snapshot, "name");
        String description = CfnRollback.snapshotText(snapshot, "description");
        UsagePlan.Limits limits = MAPPER.convertValue(snapshot.path("limits"), UsagePlan.Limits.class);
        Map<String, String> tags = new HashMap<>();
        snapshot.path("tags").properties().forEach(tag -> tags.put(tag.getKey(), tag.getValue().asText()));
        apiGatewayService.restoreUsagePlan(region, id, name, description, limits, tags);
        resource.getAttributes().remove(CfnRollback.USAGE_PLAN_UPDATE_SNAPSHOT_ATTR);
        return true;
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
