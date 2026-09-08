package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.lambda.LambdaArnUtils;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.FunctionEventInvokeConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Provisions {@code AWS::Lambda::EventInvokeConfig}, the asynchronous invocation settings of one
 * function version or alias.
 *
 * <p>The physical id is the registry schema's composite primary identifier, {@code FunctionName}
 * and {@code Qualifier} joined by a pipe, which is what a real stack shows and all that
 * {@link #delete(String, String, String)} receives. Neither part can contain a pipe.
 *
 * <p>Both identifier parts are create-only. An update that still names the same function version
 * or alias, whether by the same text or by another form of the function name (a short name and
 * the function's ARN address one configuration), applies the template's settings through the
 * merge-style update call, as the AWS handler does, so a setting the template drops keeps its
 * stored value. The settings the configuration had are kept on the resource so a failed stack
 * update can put them back. An update that names another function or qualifier creates the
 * configuration for the new target and leaves the displaced one to the {@link ReplacementCleanup}
 * record, which deletes it once the stack update commits or puts it back on rollback.
 *
 * <p>The schema declares no read-only properties, so there is nothing for {@code Fn::GetAtt}.
 */
@ApplicationScoped
public class LambdaEventInvokeConfigCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::Lambda::EventInvokeConfig";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MIN_RETRY_ATTEMPTS = 0;
    private static final int MAX_RETRY_ATTEMPTS = 2;
    private static final int MIN_EVENT_AGE_SECONDS = 60;
    private static final int MAX_EVENT_AGE_SECONDS = 21600;

    private final LambdaService lambdaService;

    @Inject
    public LambdaEventInvokeConfigCfnProvisioner(LambdaService lambdaService) {
        this.lambdaService = lambdaService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        // A snapshot describes the update in flight; one an earlier update left behind is stale.
        r.getAttributes().remove(CfnRollback.EVENT_INVOKE_CONFIG_SNAPSHOT_ATTR);
        Map<String, String> attributesBefore = Map.copyOf(r.getAttributes());
        String functionName = required(ctx.resolveOptional(props, "FunctionName"), "FunctionName");
        String qualifier = required(ctx.resolveOptional(props, "Qualifier"), "Qualifier");
        Map<String, Object> settings = settings(props, ctx);

        if (ctx.isUpdate() && namesTheSameTarget(ctx.priorPhysicalId(), functionName, qualifier)) {
            snapshotBeforeUpdate(r, functionName, qualifier, ctx.region());
            lambdaService.updateEventInvokeConfig(ctx.region(), functionName, qualifier, settings);
            // The prior id stays, so a function renamed to another form of the same name is not
            // recorded as a replacement whose cleanup would delete the one configuration.
            r.setPhysicalId(ctx.priorPhysicalId());
        } else {
            lambdaService.putEventInvokeConfig(ctx.region(), functionName, qualifier, settings);
            r.setPhysicalId(functionName + "|" + qualifier);
        }
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    /**
     * Whether the prior physical id addresses the configuration the template now names. The
     * service keys a configuration by the function's ARN and the qualifier, so a short name and the
     * function's ARN are the same target, and treating them as a replacement would delete that one
     * configuration through the old name once the update committed.
     */
    private static boolean namesTheSameTarget(String priorPhysicalId, String functionName, String qualifier) {
        int separator = priorPhysicalId.lastIndexOf('|');
        if (separator <= 0 || !qualifier.equals(priorPhysicalId.substring(separator + 1))) {
            return false;
        }
        String priorFunction = priorPhysicalId.substring(0, separator);
        if (priorFunction.equals(functionName)) {
            return true;
        }
        try {
            LambdaArnUtils.ResolvedFunctionRef prior = LambdaArnUtils.resolve(priorFunction);
            LambdaArnUtils.ResolvedFunctionRef current = LambdaArnUtils.resolve(functionName);
            return prior.name().equals(current.name())
                    && (prior.region() == null || current.region() == null
                        || prior.region().equals(current.region()));
        } catch (AwsException malformed) {
            return false;
        }
    }

    /**
     * The settings the template names, in the request shape the Lambda service reads. Only named
     * settings are sent: on a create the service stores nothing for the rest, and on an in-place
     * update the merge-style call leaves them as they are. {@code DestinationConfig} is resolved
     * before it is looked at, so a conditional value that resolves to {@code AWS::NoValue} is
     * omitted rather than sent as an empty destination set that would clear the stored ones.
     */
    private Map<String, Object> settings(JsonNode props, ProvisionContext ctx) {
        Map<String, Object> settings = new LinkedHashMap<>();
        Integer retryAttempts = integerInRange(ctx.resolveOptional(props, "MaximumRetryAttempts"),
                "MaximumRetryAttempts", MIN_RETRY_ATTEMPTS, MAX_RETRY_ATTEMPTS);
        if (retryAttempts != null) {
            settings.put("MaximumRetryAttempts", retryAttempts);
        }
        Integer eventAge = integerInRange(ctx.resolveOptional(props, "MaximumEventAgeInSeconds"),
                "MaximumEventAgeInSeconds", MIN_EVENT_AGE_SECONDS, MAX_EVENT_AGE_SECONDS);
        if (eventAge != null) {
            settings.put("MaximumEventAgeInSeconds", eventAge);
        }
        JsonNode destinations = props == null || !props.hasNonNull("DestinationConfig")
                ? null
                : ctx.engine().resolveNode(props.get("DestinationConfig"));
        if (destinations != null && destinations.isObject()) {
            settings.put("DestinationConfig", destinationConfig(destinations));
        }
        return settings;
    }

    private Map<String, Object> destinationConfig(JsonNode resolved) {
        Map<String, Object> config = new LinkedHashMap<>();
        for (String side : new String[] {"OnSuccess", "OnFailure"}) {
            JsonNode destination = resolved.get(side);
            if (destination == null || !destination.isObject()) {
                continue;
            }
            JsonNode arn = destination.get("Destination");
            if (arn == null || arn.isNull() || !arn.isValueNode()) {
                throw new AwsException("ValidationError",
                        TYPE + " DestinationConfig." + side + " requires Destination", 400);
            }
            config.put(side, Map.of("Destination", arn.asText()));
        }
        return config;
    }

    /**
     * Checks a numeric property against the bounds the registry schema declares. Real
     * CloudFormation validates the template against the schema before the handler runs, so an
     * out-of-range value fails the resource here rather than being stored.
     */
    private static Integer integerInRange(String value, String property, int min, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new AwsException("ValidationError",
                    TYPE + " " + property + " must be an integer, got '" + value + "'", 400);
        }
        if (parsed < min || parsed > max) {
            throw new AwsException("ValidationError",
                    TYPE + " " + property + " must be between " + min + " and " + max + ", got " + parsed, 400);
        }
        return parsed;
    }

    private static String required(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new AwsException("ValidationError", TYPE + " requires " + property, 400);
        }
        return value;
    }

    /**
     * Keeps the settings the configuration has before an in-place update changes them, in the
     * request shape a put takes, so {@link #rollbackUpdate} can put every one of them back. A
     * configuration that is already gone leaves no snapshot; the update that follows fails on the
     * same absence and changes nothing.
     */
    private void snapshotBeforeUpdate(StackResource r, String functionName, String qualifier, String region) {
        FunctionEventInvokeConfig current;
        try {
            current = lambdaService.getEventInvokeConfig(region, functionName, qualifier);
        } catch (AwsException e) {
            if ("ResourceNotFoundException".equals(e.getErrorCode())) {
                return;
            }
            throw e;
        }
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("region", region);
        snapshot.put("functionName", functionName);
        snapshot.put("qualifier", qualifier);
        ObjectNode settings = snapshot.putObject("settings");
        if (current.getMaximumRetryAttempts() != null) {
            settings.put("MaximumRetryAttempts", current.getMaximumRetryAttempts());
        }
        if (current.getMaximumEventAgeInSeconds() != null) {
            settings.put("MaximumEventAgeInSeconds", current.getMaximumEventAgeInSeconds());
        }
        FunctionEventInvokeConfig.DestinationConfig destinations = current.getDestinationConfig();
        if (destinations != null) {
            ObjectNode config = settings.putObject("DestinationConfig");
            if (destinations.getOnSuccess() != null && destinations.getOnSuccess().getDestination() != null) {
                config.putObject("OnSuccess").put("Destination", destinations.getOnSuccess().getDestination());
            }
            if (destinations.getOnFailure() != null && destinations.getOnFailure().getDestination() != null) {
                config.putObject("OnFailure").put("Destination", destinations.getOnFailure().getDestination());
            }
        }
        r.getAttributes().put(CfnRollback.EVENT_INVOKE_CONFIG_SNAPSHOT_ATTR, snapshot.toString());
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null) {
            return;
        }
        int separator = physicalId.lastIndexOf('|');
        if (separator <= 0) {
            return;
        }
        String functionName = physicalId.substring(0, separator);
        String qualifier = physicalId.substring(separator + 1);
        // The service answers ResourceNotFoundException both for a missing configuration and
        // for a function that is already gone; either way there is nothing left to delete.
        CfnDeletes.safeDelete("Lambda event invoke config", physicalId,
                () -> lambdaService.deleteEventInvokeConfig(region, functionName, qualifier),
                "ResourceNotFoundException");
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
        resource.getAttributes().remove(CfnRollback.EVENT_INVOKE_CONFIG_SNAPSHOT_ATTR);
        ReplacementCleanup.clear(resource);
    }

    /**
     * A replacement is undone through the cleanup record and an in-place update from the snapshot
     * taken before it, put back in full so a setting the update changed or cleared is as it was.
     * With neither on the resource the provision failed before it changed anything, since every
     * mutation is preceded by the snapshot or followed by the record, so there is nothing to undo.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (ReplacementCleanup.rollback(resource, this::delete)) {
            return true;
        }
        // The snapshot is spent only once the restore succeeded: a restore that throws leaves it in
        // place for the next attempt instead of reporting a rollback that never happened.
        String raw = resource.getAttributes().get(CfnRollback.EVENT_INVOKE_CONFIG_SNAPSHOT_ATTR);
        if (raw == null) {
            return true;
        }
        JsonNode snapshot;
        try {
            snapshot = MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read the event invoke config snapshot for "
                    + resource.getLogicalId(), e);
        }
        lambdaService.putEventInvokeConfig(snapshot.path("region").asText(),
                snapshot.path("functionName").asText(), snapshot.path("qualifier").asText(),
                settingsFrom(snapshot.path("settings")));
        resource.getAttributes().remove(CfnRollback.EVENT_INVOKE_CONFIG_SNAPSHOT_ATTR);
        return true;
    }

    private static Map<String, Object> settingsFrom(JsonNode settings) {
        Map<String, Object> request = new LinkedHashMap<>();
        if (settings.hasNonNull("MaximumRetryAttempts")) {
            request.put("MaximumRetryAttempts", settings.get("MaximumRetryAttempts").asInt());
        }
        if (settings.hasNonNull("MaximumEventAgeInSeconds")) {
            request.put("MaximumEventAgeInSeconds", settings.get("MaximumEventAgeInSeconds").asInt());
        }
        JsonNode destinations = settings.get("DestinationConfig");
        if (destinations != null && destinations.isObject()) {
            Map<String, Object> config = new LinkedHashMap<>();
            for (String side : new String[] {"OnSuccess", "OnFailure"}) {
                String destination = destinations.path(side).path("Destination").asText(null);
                if (destination != null) {
                    config.put(side, Map.of("Destination", destination));
                }
            }
            request.put("DestinationConfig", config);
        }
        return request;
    }
}
