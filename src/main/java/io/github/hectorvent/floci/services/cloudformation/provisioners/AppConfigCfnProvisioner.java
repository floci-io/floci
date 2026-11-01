package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.appconfig.AppConfigService;
import io.github.hectorvent.floci.services.appconfig.model.Application;
import io.github.hectorvent.floci.services.appconfig.model.ConfigurationProfile;
import io.github.hectorvent.floci.services.appconfig.model.DeploymentStrategy;
import io.github.hectorvent.floci.services.appconfig.model.Environment;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provisions {@code AWS::AppConfig::Application}, {@code Environment}, {@code ConfigurationProfile}
 * and {@code DeploymentStrategy}. The physical id and {@code Ref} of each is the ID AppConfig gives
 * it, which is also its ID attribute for {@code Fn::GetAtt}: {@code ApplicationId},
 * {@code EnvironmentId}, {@code ConfigurationProfileId} and {@code Id}.
 *
 * <p>An update that keeps every create-only property changes the entity in place. Every mutable
 * property is written, so one the template drops is cleared or back at its default, and what the
 * update replaces is kept on the resource so a failed stack update can put it back; an
 * application's tags are driven to the template's set. An update that changes a create-only
 * property (the {@code ApplicationId} of an environment or profile, a profile's {@code LocationUri}
 * or {@code Type}, a strategy's {@code Name} or {@code ReplicateTo}) creates the new entity and
 * leaves the displaced one to the {@link ReplacementCleanup} record. Whether one changed is judged on
 * the resolved template values the entity was made from, as CloudFormation compares them.
 *
 * <p>Floci keeps AppConfig tags on applications only, so {@code Tags} on the other three types are
 * accepted and not stored, as Floci's AppConfig TagResource treats them. So are a profile's
 * {@code KmsKeyIdentifier}, {@code RetrievalRoleArn} and {@code Validators}, and the
 * {@code DeletionProtectionCheck} of an environment or profile, which Floci's create operations do
 * not keep either.
 */
@ApplicationScoped
public class AppConfigCfnProvisioner implements CfnResourceProvisioner {

    private static final String APPLICATION = "AWS::AppConfig::Application";
    private static final String ENVIRONMENT = "AWS::AppConfig::Environment";
    private static final String PROFILE = "AWS::AppConfig::ConfigurationProfile";
    private static final String STRATEGY = "AWS::AppConfig::DeploymentStrategy";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The registry schemas' length limits. */
    private static final int NAME_MAX_LENGTH = 64;
    private static final int PROFILE_NAME_MAX_LENGTH = 128;
    private static final int DESCRIPTION_MAX_LENGTH = 1024;
    private static final int LOCATION_URI_MAX_LENGTH = 2048;
    /** The range AppConfig takes for a strategy's duration and final bake time, in minutes. */
    private static final int MAX_MINUTES = 1440;
    /** The registry schemas' enums. */
    private static final List<String> DELETION_PROTECTION_CHECKS = List.of("ACCOUNT_DEFAULT", "APPLY", "BYPASS");
    private static final List<String> GROWTH_TYPES = List.of("EXPONENTIAL", "LINEAR");
    private static final List<String> REPLICATE_TO = List.of("NONE", "SSM_DOCUMENT");

    /**
     * The create-only properties as resolved when the entity was made, so an update can tell
     * whether one changed. Under the uses prefix, like the application ID below, so a rolled-back
     * replacement puts back the values the prior entity was made from.
     */
    private static final String CREATE_ONLY_ATTR = CfnRollback.USES_ATTR_PREFIX + "AppConfigCreateOnly";
    /**
     * The ID of the application an environment or profile belongs to: the stack deletes a leftover
     * environment or profile before an application by it.
     */
    private static final String APPLICATION_ATTR = CfnRollback.USES_ATTR_PREFIX + "AppConfigApplicationId";

    private final AppConfigService service;

    @Inject
    public AppConfigCfnProvisioner(AppConfigService service) {
        this.service = service;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(APPLICATION, ENVIRONMENT, PROFILE, STRATEGY);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        // A snapshot describes the update in flight; one an earlier update left behind is stale.
        r.getAttributes().remove(CfnRollback.APPCONFIG_UPDATE_SNAPSHOT_ATTR);
        if (ctx.isUpdate()) {
            // Rolling this update back undoes it alone; only ContinueUpdateRollback retries the
            // delete the last rollback could not make.
            ReplacementCleanup.endRollbackRetry(r);
        }
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        // A stack from before these types had a provisioner holds the dispatcher's stub here, which
        // names nothing, so the entity is created as on a first deploy.
        boolean updating = ctx.isUpdate() && !CfnResourceDispatcher.isStub(ctx.priorPhysicalId(), attributesBefore);
        switch (r.getResourceType()) {
            case APPLICATION -> provisionApplication(r, props, ctx, updating);
            case ENVIRONMENT -> provisionEnvironment(r, props, ctx, updating);
            case PROFILE -> provisionProfile(r, props, ctx, updating);
            case STRATEGY -> provisionStrategy(r, props, ctx, updating);
            default -> throw new IllegalArgumentException("Unsupported AppConfig resource type " + r.getResourceType());
        }
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    /**
     * An application has no create-only property, so an update always changes it in place. Its tags
     * are applied once it exists, and a create whose tagging fails deletes it again.
     */
    private void provisionApplication(StackResource r, JsonNode props, ProvisionContext ctx, boolean updating) {
        String name = requiredText(APPLICATION, props, "Name", NAME_MAX_LENGTH, ctx);
        String description = optionalText(APPLICATION, props, "Description", DESCRIPTION_MAX_LENGTH, ctx);
        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        if (updating) {
            String id = ctx.priorPhysicalId();
            snapshotBeforeUpdate(r, application(id));
            CfnRollback.guarded(r, ctx, false, () -> { }, () -> restoreSnapshot(r), () -> {
                service.updateApplication(id, name, description);
                service.setApplicationTags(id, tags);
            });
            return;
        }
        Map<String, Object> request = new HashMap<>();
        request.put("Name", name);
        request.put("Description", description);
        Application app = service.createApplication(request);
        r.setPhysicalId(app.getId());
        r.getAttributes().put("ApplicationId", app.getId());
        CfnRollback.guarded(r, ctx, true, () -> service.deleteApplicationById(app.getId()), () -> { }, () -> {
            if (!tags.isEmpty()) {
                service.setApplicationTags(app.getId(), tags);
            }
        });
    }

    private void provisionEnvironment(StackResource r, JsonNode props, ProvisionContext ctx, boolean updating) {
        String applicationId = requiredText(ENVIRONMENT, props, "ApplicationId", 0, ctx);
        String name = requiredText(ENVIRONMENT, props, "Name", NAME_MAX_LENGTH, ctx);
        String description = optionalText(ENVIRONMENT, props, "Description", DESCRIPTION_MAX_LENGTH, ctx);
        Object monitors = jsonValue(resolved(props, "Monitors", ctx));
        allowed(ENVIRONMENT, "DeletionProtectionCheck", text(props, "DeletionProtectionCheck", ctx),
                DELETION_PROTECTION_CHECKS);
        ObjectNode createOnly = MAPPER.createObjectNode().put("ApplicationId", applicationId);
        if (updating && madeFrom(r, createOnly)) {
            Environment current = service.findEnvironment(ctx.priorPhysicalId())
                    .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Environment not found", 404));
            snapshotBeforeUpdate(r, current);
            service.updateEnvironment(current.getApplicationId(), current.getId(), name, description, monitors);
            return;
        }
        Map<String, Object> request = new HashMap<>();
        request.put("Name", name);
        request.put("Description", description);
        request.put("Monitors", monitors);
        Environment env = service.createEnvironment(applicationId, request);
        r.setPhysicalId(env.getId());
        r.getAttributes().put("EnvironmentId", env.getId());
        r.getAttributes().put(APPLICATION_ATTR, env.getApplicationId());
        r.getAttributes().put(CREATE_ONLY_ATTR, createOnly.toString());
    }

    private void provisionProfile(StackResource r, JsonNode props, ProvisionContext ctx, boolean updating) {
        String applicationId = requiredText(PROFILE, props, "ApplicationId", 0, ctx);
        String name = requiredText(PROFILE, props, "Name", PROFILE_NAME_MAX_LENGTH, ctx);
        String locationUri = requiredText(PROFILE, props, "LocationUri", LOCATION_URI_MAX_LENGTH, ctx);
        String type = text(props, "Type", ctx);
        String description = optionalText(PROFILE, props, "Description", DESCRIPTION_MAX_LENGTH, ctx);
        allowed(PROFILE, "DeletionProtectionCheck", text(props, "DeletionProtectionCheck", ctx),
                DELETION_PROTECTION_CHECKS);
        ObjectNode createOnly = MAPPER.createObjectNode()
                .put("ApplicationId", applicationId)
                .put("LocationUri", locationUri)
                .put("Type", type);
        if (updating && madeFrom(r, createOnly)) {
            ConfigurationProfile current = service.findConfigurationProfile(ctx.priorPhysicalId())
                    .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                            "Configuration profile not found", 404));
            snapshotBeforeUpdate(r, current);
            service.updateConfigurationProfile(current.getApplicationId(), current.getId(), name, description);
            return;
        }
        Map<String, Object> request = new HashMap<>();
        request.put("Name", name);
        request.put("Description", description);
        request.put("LocationUri", locationUri);
        request.put("Type", type);
        ConfigurationProfile profile = service.createConfigurationProfile(applicationId, request);
        r.setPhysicalId(profile.getId());
        r.getAttributes().put("ConfigurationProfileId", profile.getId());
        r.getAttributes().put(APPLICATION_ATTR, profile.getApplicationId());
        r.getAttributes().put(CREATE_ONLY_ATTR, createOnly.toString());
    }

    /**
     * A strategy's {@code FinalBakeTimeInMinutes} and {@code GrowthType} take AppConfig's defaults,
     * 0 and {@code LINEAR}, when the template gives none.
     */
    private void provisionStrategy(StackResource r, JsonNode props, ProvisionContext ctx, boolean updating) {
        String name = requiredText(STRATEGY, props, "Name", NAME_MAX_LENGTH, ctx);
        String replicateTo = allowed(STRATEGY, "ReplicateTo", requiredText(STRATEGY, props, "ReplicateTo", 0, ctx),
                REPLICATE_TO);
        Integer duration = minutes(props, "DeploymentDurationInMinutes", ctx);
        if (duration == null) {
            throw new AwsException("ValidationError", STRATEGY + " requires DeploymentDurationInMinutes", 400);
        }
        Integer finalBake = minutes(props, "FinalBakeTimeInMinutes", ctx);
        int finalBakeMinutes = finalBake == null ? 0 : finalBake;
        float growthFactor = growthFactor(props, ctx);
        String growthType = allowed(STRATEGY, "GrowthType", text(props, "GrowthType", ctx), GROWTH_TYPES);
        String effectiveGrowthType = growthType == null ? "LINEAR" : growthType;
        String description = optionalText(STRATEGY, props, "Description", DESCRIPTION_MAX_LENGTH, ctx);
        ObjectNode createOnly = MAPPER.createObjectNode().put("Name", name).put("ReplicateTo", replicateTo);
        if (updating && madeFrom(r, createOnly)) {
            String id = ctx.priorPhysicalId();
            snapshotBeforeUpdate(r, service.getDeploymentStrategy(id));
            service.updateDeploymentStrategy(id, description, duration, finalBakeMinutes, growthFactor,
                    effectiveGrowthType);
            return;
        }
        Map<String, Object> request = new HashMap<>();
        request.put("Name", name);
        request.put("Description", description);
        request.put("DeploymentDurationInMinutes", duration);
        request.put("FinalBakeTimeInMinutes", finalBakeMinutes);
        request.put("GrowthFactor", growthFactor);
        request.put("GrowthType", effectiveGrowthType);
        request.put("ReplicateTo", replicateTo);
        DeploymentStrategy strategy = service.createDeploymentStrategy(request);
        r.setPhysicalId(strategy.getId());
        r.getAttributes().put("Id", strategy.getId());
        r.getAttributes().put(CREATE_ONLY_ATTR, createOnly.toString());
    }

    /** The application whose ID is exactly {@code id}: GetApplication also takes a name. */
    private Application application(String id) {
        return service.findApplication(id)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Application not found", 404));
    }

    /**
     * Whether the entity the resource holds was made from these create-only values. A resource
     * that recorded none, or none this can read, was not, so it is replaced rather than changed.
     */
    private static boolean madeFrom(StackResource r, ObjectNode createOnly) {
        String recorded = r.getAttributes().get(CREATE_ONLY_ATTR);
        if (recorded == null) {
            return false;
        }
        try {
            return MAPPER.readTree(recorded).equals(createOnly);
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    /** Keeps the entity as it is before an in-place update, so {@link #rollbackUpdate} can put it back. */
    private static void snapshotBeforeUpdate(StackResource r, Object current) {
        r.getAttributes().put(CfnRollback.APPCONFIG_UPDATE_SNAPSHOT_ATTR, MAPPER.valueToTree(current).toString());
    }

    /**
     * The resolved property, or null when it is absent or {@code AWS::NoValue}, directly or as the
     * {@code Fn::If} branch its condition chooses. Every other value is kept as resolved, so a blank
     * string a parameter supplies is a value, as the same string written literally is. The stack
     * engine already leaves such properties out before provisioning; omitting them here as well keeps
     * the same answer for callers that hand over properties directly.
     */
    private static JsonNode resolved(JsonNode props, String name, ProvisionContext ctx) {
        if (props == null || !props.has(name)) {
            return null;
        }
        JsonNode resolved = ctx.engine().resolveNodeOmittingNoValue(props.get(name));
        return resolved == null || resolved.isNull() || resolved.isMissingNode() ? null : resolved;
    }

    private static String text(JsonNode props, String name, ProvisionContext ctx) {
        JsonNode value = resolved(props, name, ctx);
        return value == null ? null : value.asText();
    }

    /** A required string; {@code maxLength} 0 means the schema sets no limit. */
    private static String requiredText(String type, JsonNode props, String name, int maxLength, ProvisionContext ctx) {
        String value = text(props, name, ctx);
        if (value == null || value.isEmpty()) {
            throw new AwsException("ValidationError", type + " requires " + name, 400);
        }
        return withinLength(type, name, value, maxLength);
    }

    private static String optionalText(String type, JsonNode props, String name, int maxLength, ProvisionContext ctx) {
        String value = text(props, name, ctx);
        return value == null ? null : withinLength(type, name, value, maxLength);
    }

    private static String withinLength(String type, String name, String value, int maxLength) {
        if (maxLength > 0 && value.length() > maxLength) {
            throw new AwsException("ValidationError",
                    type + " " + name + " must be at most " + maxLength + " characters", 400);
        }
        return value;
    }

    /** {@code value} when the schema allows it, or null when it is absent. */
    private static String allowed(String type, String name, String value, List<String> values) {
        if (value != null && !values.contains(value)) {
            throw new AwsException("ValidationError",
                    type + " " + name + " must be one of " + String.join(", ", values) + ", got '" + value + "'", 400);
        }
        return value;
    }

    /** A resolved list or object as the plain values a service request carries. */
    private static Object jsonValue(JsonNode value) {
        return value == null ? null : MAPPER.convertValue(value, Object.class);
    }

    /**
     * A whole number of minutes from 0 to 1440, as AppConfig takes it, or null when absent. The
     * schema types it as a number, and a parameter supplies it as a string.
     */
    private static Integer minutes(JsonNode props, String name, ProvisionContext ctx) {
        JsonNode value = resolved(props, name, ctx);
        if (value == null) {
            return null;
        }
        BigDecimal number = number(value);
        if (number == null || number.signum() < 0 || number.compareTo(BigDecimal.valueOf(MAX_MINUTES)) > 0
                || number.stripTrailingZeros().scale() > 0) {
            throw new AwsException("ValidationError", STRATEGY + " " + name
                    + " must be a whole number of minutes from 0 to " + MAX_MINUTES + ", got " + value.asText(), 400);
        }
        return number.intValueExact();
    }

    /** The percentage of targets per interval, from 1 to 100, as AppConfig takes it. */
    private static float growthFactor(JsonNode props, ProvisionContext ctx) {
        JsonNode value = resolved(props, "GrowthFactor", ctx);
        if (value == null) {
            throw new AwsException("ValidationError", STRATEGY + " requires GrowthFactor", 400);
        }
        BigDecimal number = number(value);
        if (number == null || number.compareTo(BigDecimal.ONE) < 0 || number.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new AwsException("ValidationError",
                    STRATEGY + " GrowthFactor must be a number from 1 to 100, got " + value.asText(), 400);
        }
        return number.floatValue();
    }

    private static BigDecimal number(JsonNode value) {
        if (value.isNumber()) {
            return value.decimalValue();
        }
        try {
            return new BigDecimal(value.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A stub left from before these types had a provisioner names nothing, so nothing is deleted. */
    @Override
    public void delete(StackResource resource, String region) {
        if (CfnResourceDispatcher.isStub(resource.getPhysicalId(), resource.getAttributes())) {
            return;
        }
        delete(resource.getResourceType(), resource.getPhysicalId(), region);
    }

    /**
     * Deletes the entity whose ID is exactly {@code physicalId}, so a name AppConfig would also take
     * never selects another one, and the displaced entity of a replacement is found by its ID alone.
     * An environment or profile is deleted without looking up its application, which the stack may
     * already have deleted: an update that moves them to a new application and removes the old one
     * deletes that application before the entities it displaced. One already gone counts as
     * deleted. An environment takes its
     * deployments with it, and a profile its hosted configuration versions.
     */
    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null || physicalId.isBlank()) {
            return;
        }
        switch (resourceType) {
            case APPLICATION -> service.deleteApplicationById(physicalId);
            case ENVIRONMENT -> service.deleteEnvironmentById(physicalId);
            case PROFILE -> service.deleteConfigurationProfileById(physicalId);
            case STRATEGY -> service.deleteDeploymentStrategy(physicalId);
            default -> throw new IllegalArgumentException("Unsupported AppConfig resource type " + resourceType);
        }
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
        resource.getAttributes().remove(CfnRollback.APPCONFIG_UPDATE_SNAPSHOT_ATTR);
        ReplacementCleanup.clear(resource);
    }

    /**
     * Keeps the snapshot of an in-place update whose own undo did not complete. The engine restores
     * the previous resource and reports the rollback failure once; with the snapshot of the state
     * the update started from, a ContinueUpdateRollback restores that state, instead of finding
     * nothing to undo or an older snapshot a committed update left on the previous resource.
     */
    @Override
    public void mergeFailedUpdateResourceTracking(StackResource previous, StackResource attempted) {
        String snapshot = attempted.getAttributes().get(CfnRollback.APPCONFIG_UPDATE_SNAPSHOT_ATTR);
        if (snapshot != null && attempted.getAttributes().containsKey(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR)) {
            previous.getAttributes().put(CfnRollback.APPCONFIG_UPDATE_SNAPSHOT_ATTR, snapshot);
        }
    }

    /**
     * A replacement is undone through the cleanup record and an in-place update from the snapshot
     * taken before it, which every mutable property is written back from. With neither on the
     * resource the provision failed before it changed anything, so there is nothing to undo.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (ReplacementCleanup.rollback(resource, this::delete)) {
            return true;
        }
        restoreSnapshot(resource);
        return true;
    }

    /**
     * Writes back what the snapshot holds, if the resource carries one. It is spent only once the
     * restore succeeded: a restore that throws leaves it in place for the next attempt instead of
     * reporting a rollback that never happened.
     */
    private void restoreSnapshot(StackResource resource) {
        JsonNode snapshot = CfnRollback.updateSnapshot(resource, CfnRollback.APPCONFIG_UPDATE_SNAPSHOT_ATTR);
        if (snapshot == null) {
            return;
        }
        try {
            restore(resource.getResourceType(), snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read the AppConfig update snapshot for "
                    + resource.getLogicalId(), e);
        }
        resource.getAttributes().remove(CfnRollback.APPCONFIG_UPDATE_SNAPSHOT_ATTR);
    }

    private void restore(String resourceType, JsonNode snapshot) throws JsonProcessingException {
        switch (resourceType) {
            case APPLICATION -> {
                Application prior = MAPPER.treeToValue(snapshot, Application.class);
                service.updateApplication(prior.getId(), prior.getName(), prior.getDescription());
                service.setApplicationTags(prior.getId(), prior.getTags());
            }
            case ENVIRONMENT -> {
                Environment prior = MAPPER.treeToValue(snapshot, Environment.class);
                service.updateEnvironment(prior.getApplicationId(), prior.getId(), prior.getName(),
                        prior.getDescription(), jsonValue(snapshot.get("Monitors")));
            }
            case PROFILE -> {
                ConfigurationProfile prior = MAPPER.treeToValue(snapshot, ConfigurationProfile.class);
                service.updateConfigurationProfile(prior.getApplicationId(), prior.getId(), prior.getName(),
                        prior.getDescription());
            }
            case STRATEGY -> {
                DeploymentStrategy prior = MAPPER.treeToValue(snapshot, DeploymentStrategy.class);
                service.updateDeploymentStrategy(prior.getId(), prior.getDescription(),
                        prior.getDeploymentDurationInMinutes(), prior.getFinalBakeTimeInMinutes(),
                        prior.getGrowthFactor(), prior.getGrowthType());
            }
            default -> throw new IllegalArgumentException("Unsupported AppConfig resource type " + resourceType);
        }
    }
}
