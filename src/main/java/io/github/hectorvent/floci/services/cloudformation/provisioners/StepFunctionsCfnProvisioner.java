package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationYamlParser;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.github.hectorvent.floci.services.stepfunctions.StepFunctionsService;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Provisions {@code AWS::StepFunctions::StateMachine}.
 *
 * <p>Extracted from {@code CloudFormationResourceProvisioner}. This type carried the last
 * fall-through arm in that class's five update-cleanup hooks, so moving it here reduces every
 * one of them to plain registry delegation: a replacement's bookkeeping, its retry budget and
 * its rollback now live beside the code that creates the replacement.
 *
 * <p>A state machine replaces rather than updates in place when its name changes, when the name
 * stops being generated and becomes explicit (or the reverse), or when its type changes, since
 * {@code StateMachineName} and {@code StateMachineType} are create-only in the registry schema.
 * The name mode is remembered on the resource because a generated name is otherwise
 * indistinguishable from an explicit one on the next update.
 */
@ApplicationScoped
public class StepFunctionsCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::StepFunctions::StateMachine";

    private static final Logger LOG = Logger.getLogger(StepFunctionsCfnProvisioner.class);

    private static final String SFN_NAME_MODE_ATTR = "FlociStepFunctionsNameMode";
    private static final String SFN_UPDATE_SNAPSHOT_ATTR = "__FlociStepFunctionsUpdateSnapshot";
    private static final String NAME_MODE_EXPLICIT = "explicit";
    private static final String NAME_MODE_GENERATED = "generated";
    private static final int STEP_FUNCTIONS_NAME_MAX_LENGTH = 80;
    /** Mirrors the suffix ProvisionContext.generatePhysicalName appends, so a generated name is recognisable. */
    private static final int GENERATED_NAME_SUFFIX_LENGTH = 12;

    /** Matches the non-ARN stub physical id the dispatcher's default arm writes, {@code <logicalId>-<8 hex>}. */
    private static final Pattern STUB_PHYSICAL_ID = Pattern.compile("^[A-Za-z0-9]+-[0-9a-f]{8}$");

    private final StepFunctionsService stepFunctionsService;
    private final S3Service s3Service;
    private final ObjectMapper objectMapper;

    @Inject
    public StepFunctionsCfnProvisioner(StepFunctionsService stepFunctionsService, S3Service s3Service,
                                       ObjectMapper objectMapper) {
        this.stepFunctionsService = stepFunctionsService;
        this.s3Service = s3Service;
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        requireSupported(r.getResourceType());
        provisionStateMachine(r, props, ctx);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        requireSupported(resourceType);
        stepFunctionsService.deleteStateMachine(physicalId);
    }

    private static void requireSupported(String resourceType) {
        if (!TYPE.equals(resourceType)) {
            throw new IllegalStateException(
                    "StepFunctionsCfnProvisioner received an unsupported type: " + resourceType);
        }
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        String rawSnapshot = resource.getAttributes().get(SFN_UPDATE_SNAPSHOT_ATTR);
        if (rawSnapshot == null) {
            return UpdateCleanupResult.notApplicable();
        }
        try {
            JsonNode snapshot = objectMapper.readTree(rawSnapshot);
            String previousArn = snapshot.path("physicalId").asText(null);
            if (!snapshot.path("replacement").asBoolean(false)
                    || previousArn == null
                    || Objects.equals(previousArn, resource.getPhysicalId())) {
                return new UpdateCleanupResult(true, true, previousArn, 0, null);
            }
            if ("Retain".equals(resource.getUpdateReplacePolicy())) {
                return new UpdateCleanupResult(true, true, previousArn, 0, null);
            }

            int attempts = snapshot.path("cleanupAttempts").asInt(0);
            String failureReason = snapshot.path("cleanupFailureReason").asText(null);
            if (attempts >= 3) {
                return new UpdateCleanupResult(
                        true, false, previousArn, attempts, failureReason);
            }

            try {
                String previousRevisionId = snapshot.path("stateMachine")
                        .path("revisionId")
                        .asText(null);
                StateMachine cleanupTarget = findStateMachine(previousArn);
                if (cleanupTarget != null
                        && !stepFunctionsService.deleteStateMachineIfRevisionMatches(
                                previousArn, previousRevisionId)) {
                    throw new IllegalStateException(
                            "The old state machine no longer matches the replacement snapshot");
                }
                return new UpdateCleanupResult(
                        true, true, previousArn, attempts, null);
            } catch (Exception e) {
                attempts++;
                ((ObjectNode) snapshot).put("cleanupAttempts", attempts);
                ((ObjectNode) snapshot).put("cleanupFailureReason", e.getMessage());
                resource.getAttributes().put(
                        SFN_UPDATE_SNAPSHOT_ATTR, snapshot.toString());
                return new UpdateCleanupResult(
                        true, false, previousArn, attempts, e.getMessage());
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not finalize Step Functions state machine "
                            + resource.getLogicalId(), e);
        }
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        if ("Retain".equals(resource.getUpdateReplacePolicy())) {
            return null;
        }
        String rawSnapshot = resource.getAttributes().get(SFN_UPDATE_SNAPSHOT_ATTR);
        if (rawSnapshot == null) {
            return null;
        }
        try {
            JsonNode snapshot = objectMapper.readTree(rawSnapshot);
            if (!snapshot.path("replacement").asBoolean(false)) {
                return null;
            }
            return snapshot.path("physicalId").asText(null);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not read Step Functions cleanup metadata for "
                            + resource.getLogicalId(), e);
        }
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        String rawSnapshot = resource.getAttributes().get(SFN_UPDATE_SNAPSHOT_ATTR);
        if (rawSnapshot == null) {
            return false;
        }
        try {
            return objectMapper.readTree(rawSnapshot).path("replacement").asBoolean(false);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not read Step Functions update metadata for "
                            + resource.getLogicalId(), e);
        }
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(SFN_UPDATE_SNAPSHOT_ATTR);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        String rawSnapshot = resource.getAttributes().get(SFN_UPDATE_SNAPSHOT_ATTR);
        if (rawSnapshot == null) {
            return false;
        }
        try {
            JsonNode snapshot = objectMapper.readTree(rawSnapshot);
            String previousArn = snapshot.path("physicalId").asText(null);
            String replacementArn = snapshot.path("replacementArn").asText(
                    resource.getPhysicalId());
            String replacementRevisionId = snapshot.path("replacementRevisionId")
                    .asText(null);
            if (snapshot.path("replacement").asBoolean(false)
                    && replacementArn != null
                    && !Objects.equals(previousArn, replacementArn)) {
                stepFunctionsService.deleteStateMachineIfRevisionMatches(
                        replacementArn, replacementRevisionId);
            }

            StateMachine previous = objectMapper.treeToValue(
                    snapshot.path("stateMachine"), StateMachine.class);
            StateMachine current = findStateMachine(previousArn);
            String restoredRevisionId = null;
            if (current == null) {
                throw new IllegalStateException(
                        "The original state machine no longer exists: " + previousArn);
            }
            if (!snapshot.path("replacement").asBoolean(false)) {
                if (!stateMachineConfigurationMatches(
                        current,
                        previous.getDefinition(),
                        previous.getRoleArn(),
                        previous.getLoggingConfiguration(),
                        previous.getTracingConfiguration(),
                        previous.getEncryptionConfiguration())) {
                    stepFunctionsService.updateStateMachine(
                            previousArn,
                            new StepFunctionsService.UpdateStateMachineRequest(
                                    previous.getDefinition(),
                                    previous.getRoleArn(),
                                    previous.getLoggingConfiguration(), true,
                                    previous.getTracingConfiguration(), true,
                                    previous.getEncryptionConfiguration(), true,
                                    false,
                                    null));
                }
                StateMachine restored = stepFunctionsService.describeStateMachine(previousArn);
                restoredRevisionId = restored.getRevisionId();
                if (!Objects.equals(restored.getTags(), previous.getTags())) {
                    stepFunctionsService.replaceStateMachineTags(
                            previousArn, previous.getTags());
                }
            }

            resource.setPhysicalId(previousArn);
            resource.getAttributes().clear();
            JsonNode previousAttributes = snapshot.path("attributes");
            previousAttributes.fields().forEachRemaining(entry ->
                    resource.getAttributes().put(entry.getKey(), entry.getValue().asText()));
            if (restoredRevisionId != null) {
                resource.getAttributes().put(
                        "StateMachineRevisionId", restoredRevisionId);
            }
            resource.setStatus("UPDATE_COMPLETE");
            resource.setStatusReason(null);
            return true;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not roll back Step Functions state machine "
                            + resource.getLogicalId(), e);
        }
    }

    private void provisionStateMachine(StackResource r, JsonNode props, ProvisionContext ctx) {
        CloudFormationTemplateEngine engine = ctx.engine();
        String region = ctx.region();
        String accountId = ctx.accountId();
        String explicitName = ctx.resolveOptional(props, "StateMachineName");
        boolean hasExplicitName = explicitName != null && !explicitName.isBlank();
        String roleArn = ctx.resolveOptional(props, "RoleArn");
        if (roleArn == null || roleArn.isBlank()) {
            throw new AwsException("ValidationError", "RoleArn is required for a state machine", 400);
        }
        String type = resolveOrDefault(props, "StateMachineType", ctx, "STANDARD");
        Map<String, String> tags = parseCfnTags(props != null ? props.get("Tags") : null, engine);
        String definition = resolveStateMachineDefinition(props, ctx);
        JsonNode loggingConfiguration = resolveStateMachineLoggingConfiguration(props, engine);
        JsonNode tracingConfiguration = resolveStateMachineTracingConfiguration(props, engine);
        JsonNode encryptionConfiguration = resolveStateMachineEncryptionConfiguration(props, engine);

        StateMachine existing = findExistingOrStubbedStateMachine(r.getPhysicalId());
        String desiredNameMode = hasExplicitName ? NAME_MODE_EXPLICIT : NAME_MODE_GENERATED;
        String previousNameMode = r.getAttributes().get(SFN_NAME_MODE_ATTR);
        if (existing != null && previousNameMode == null) {
            previousNameMode = inferStepFunctionsNameMode(existing, ctx.stackName(), r.getLogicalId());
        }
        boolean nameModeReplacement = existing != null
                && !Objects.equals(previousNameMode, desiredNameMode);
        boolean typeReplacement = existing != null && !Objects.equals(existing.getType(), type);

        String name;
        if (hasExplicitName) {
            name = explicitName;
        } else if (existing != null && !nameModeReplacement && !typeReplacement) {
            name = existing.getName();
        } else {
            name = ctx.generatePhysicalName(r.getLogicalId(), STEP_FUNCTIONS_NAME_MAX_LENGTH, false);
        }
        String desiredArn = AwsArnUtils.Arn.of(
                "states", region, accountId, "stateMachine:" + name).toString();

        boolean nameReplacement = existing != null && !Objects.equals(existing.getName(), name);
        boolean replacement = nameReplacement || nameModeReplacement || typeReplacement;
        if (replacement && Objects.equals(existing.getName(), name)) {
            throw new AwsException("ValidationError",
                    "Cannot replace state machine " + existing.getName()
                            + " without a new StateMachineName", 400);
        }

        boolean configurationChanged = existing != null
                && !stateMachineConfigurationMatches(
                        existing,
                        definition,
                        roleArn,
                        loggingConfiguration,
                        tracingConfiguration,
                        encryptionConfiguration);
        boolean tagsChanged = existing != null && !Objects.equals(existing.getTags(), tags);

        StateMachine sm;
        if (existing == null) {
            sm = stepFunctionsService.createStateMachine(
                    name, definition, roleArn, type, region, tags,
                    loggingConfiguration, tracingConfiguration, encryptionConfiguration);
        } else if (!replacement && !configurationChanged && !tagsChanged) {
            sm = existing;
        } else {
            String replacementRevisionId = replacement
                    ? UUID.randomUUID().toString()
                    : null;
            beginStepFunctionsUpdate(
                    r,
                    existing,
                    replacement,
                    replacement ? desiredArn : null,
                    replacementRevisionId);
            if (replacement) {
                sm = stepFunctionsService.createStateMachineWithRevisionId(
                        name, definition, roleArn, type, region, tags,
                        loggingConfiguration,
                        tracingConfiguration,
                        encryptionConfiguration,
                        replacementRevisionId);
                markStepFunctionsReplacementCreated(r, sm);
            } else {
                sm = existing;
                if (configurationChanged) {
                    sm = stepFunctionsService.updateStateMachine(
                            existing.getStateMachineArn(),
                            new StepFunctionsService.UpdateStateMachineRequest(
                                    definition,
                                    roleArn,
                                    loggingConfiguration, true,
                                    tracingConfiguration, true,
                                    encryptionConfiguration, true,
                                    false,
                                    null)).stateMachine();
                }
                if (tagsChanged) {
                    stepFunctionsService.replaceStateMachineTags(sm.getStateMachineArn(), tags);
                    sm = stepFunctionsService.describeStateMachine(sm.getStateMachineArn());
                }
            }
        }

        r.setPhysicalId(sm.getStateMachineArn());
        r.getAttributes().put("Arn", sm.getStateMachineArn());
        r.getAttributes().put("Name", sm.getName());
        r.getAttributes().put("StateMachineRevisionId", sm.getRevisionId());
        r.getAttributes().put(SFN_NAME_MODE_ATTR, desiredNameMode);
    }


    /**
     * Looks up a state machine by ARN, treating {@code StateMachineDoesNotExist} as "not found".
     * An {@code InvalidArn} error is not swallowed here: every caller of this method reads an ARN
     * this floci instance itself recorded (a cleanup or rollback snapshot), so a malformed value
     * reaching {@code describeStateMachine} is a real anomaly, not a legitimate "not found" case.
     */
    private StateMachine findStateMachine(String stateMachineArn) {
        if (stateMachineArn == null || stateMachineArn.isBlank()) {
            return null;
        }
        try {
            return stepFunctionsService.describeStateMachine(stateMachineArn);
        } catch (AwsException e) {
            if ("StateMachineDoesNotExist".equals(e.getErrorCode())) {
                return null;
            }
            throw e;
        }
    }

    /**
     * Same as {@link #findStateMachine}, but additionally treats {@code InvalidArn} as "not found"
     * when {@code physicalId} matches the stub shape a pre-SAM-expansion floci build wrote
     * ({@code <logicalId>-<8 hex>}, produced at :523). A stack whose
     * {@code AWS::Serverless::StateMachine} resource was provisioned before floci expanded SAM
     * types carries that stub value into its next update; without this, {@code describeStateMachine}
     * throws {@code InvalidArn} for it and the update fails instead of creating the real resource.
     * Used only at the provisioning entry point, where that upgrade path is legitimate: unlike
     * {@link #findStateMachine}'s other callers, a stub here is expected, not an anomaly.
     */
    private StateMachine findExistingOrStubbedStateMachine(String physicalId) {
        if (physicalId == null || physicalId.isBlank()) {
            return null;
        }
        try {
            return stepFunctionsService.describeStateMachine(physicalId);
        } catch (AwsException e) {
            if ("StateMachineDoesNotExist".equals(e.getErrorCode())
                    || ("InvalidArn".equals(e.getErrorCode()) && isStubPhysicalId(physicalId))) {
                return null;
            }
            throw e;
        }
    }
    private static boolean isStubPhysicalId(String physicalId) {
        return STUB_PHYSICAL_ID.matcher(physicalId).matches();
    }
    private String inferStepFunctionsNameMode(
            StateMachine existing, String stackName, String logicalId) {
        String base = stackName + "-" + logicalId;
        int keep = STEP_FUNCTIONS_NAME_MAX_LENGTH - GENERATED_NAME_SUFFIX_LENGTH - 1;
        String prefix = base.substring(0, Math.min(base.length(), keep));
        while (prefix.endsWith("-")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        String generatedPrefix = prefix.isEmpty() ? "" : prefix + "-";
        String name = existing.getName();
        if (name == null || !name.startsWith(generatedPrefix)) {
            return NAME_MODE_EXPLICIT;
        }
        String suffix = name.substring(generatedPrefix.length());
        boolean generatedSuffix = suffix.length() == GENERATED_NAME_SUFFIX_LENGTH
                && suffix.chars().allMatch(c -> c >= '0' && c <= '9' || c >= 'a' && c <= 'f');
        return generatedSuffix
                ? NAME_MODE_GENERATED
                : NAME_MODE_EXPLICIT;
    }
    private boolean stateMachineConfigurationMatches(
            StateMachine existing,
            String definition,
            String roleArn,
            JsonNode loggingConfiguration,
            JsonNode tracingConfiguration,
            JsonNode encryptionConfiguration) {
        return Objects.equals(existing.getDefinition(), definition)
                && Objects.equals(existing.getRoleArn(), roleArn)
                && Objects.equals(
                        effectiveLoggingConfiguration(existing.getLoggingConfiguration()),
                        loggingConfiguration)
                && Objects.equals(
                        effectiveTracingConfiguration(existing.getTracingConfiguration()),
                        tracingConfiguration)
                && Objects.equals(
                        effectiveEncryptionConfiguration(existing.getEncryptionConfiguration()),
                        encryptionConfiguration);
    }
    private JsonNode effectiveLoggingConfiguration(JsonNode configuration) {
        return configuration != null ? configuration : defaultStateMachineLoggingConfiguration();
    }
    private JsonNode effectiveTracingConfiguration(JsonNode configuration) {
        return configuration != null ? configuration : defaultStateMachineTracingConfiguration();
    }
    private JsonNode effectiveEncryptionConfiguration(JsonNode configuration) {
        return configuration != null ? configuration : defaultStateMachineEncryptionConfiguration();
    }
    private String resolveStateMachineDefinition(JsonNode props, ProvisionContext ctx) {
        CloudFormationTemplateEngine engine = ctx.engine();
        if (props == null) {
            throw new AwsException(
                    "ValidationError",
                    "A state machine definition is required",
                    400);
        }

        boolean hasDefinitionString =
                props.has("DefinitionString")
                        && !props.get("DefinitionString").isNull();
        boolean hasDefinition =
                props.has("Definition")
                        && !props.get("Definition").isNull();
        boolean hasS3Location =
                props.has("DefinitionS3Location")
                        && !props.get("DefinitionS3Location").isNull();
        int sourceCount = (hasDefinitionString ? 1 : 0)
                + (hasDefinition ? 1 : 0)
                + (hasS3Location ? 1 : 0);
        if (sourceCount != 1) {
            throw new AwsException(
                    "ValidationError",
                    "Specify exactly one of Definition, DefinitionString, or DefinitionS3Location",
                    400);
        }

        boolean definitionFromS3 = false;
        String definition;
        if (hasDefinitionString) {
            definition = ctx.resolveOptional(props, "DefinitionString");
        } else if (hasDefinition) {
            definition = engine.resolveJsonAttribute(props.get("Definition"));
        } else {
            JsonNode location = engine.resolveNode(props.get("DefinitionS3Location"));
            String bucket = location.path("Bucket").asText(null);
            String key = location.path("Key").asText(null);
            String version = location.path("Version").asText(null);
            if (bucket == null || bucket.isBlank() || key == null || key.isBlank()) {
                throw new AwsException(
                        "ValidationError",
                        "DefinitionS3Location requires Bucket and Key",
                        400);
            }
            S3Object object = s3Service.getObject(bucket, key, version);
            definition = new String(object.getData(), StandardCharsets.UTF_8);
            definitionFromS3 = true;
        }

        JsonNode subsNode = props.get("DefinitionSubstitutions");
        if (subsNode != null && !subsNode.isNull()) {
            JsonNode resolvedSubs = engine.resolveNode(subsNode);
            Iterator<Map.Entry<String, JsonNode>> entries = resolvedSubs.fields();
            while (entries.hasNext()) {
                Map.Entry<String, JsonNode> entry = entries.next();
                String placeholder = "${" + entry.getKey() + "}";
                String value = entry.getValue().isTextual()
                        ? entry.getValue().asText()
                        : entry.getValue().toString();
                definition = definition.replace(placeholder, value);
            }
        }

        if (definitionFromS3) {
            try {
                definition = new CloudFormationYamlParser(objectMapper)
                        .parse(definition)
                        .toString();
            } catch (Exception e) {
                throw new AwsException(
                        "ValidationError",
                        "DefinitionS3Location contains invalid JSON or YAML: "
                                + e.getMessage(),
                        400);
            }
        }

        return definition;
    }
    private JsonNode resolveStateMachineLoggingConfiguration(
            JsonNode props, CloudFormationTemplateEngine engine) {
        if (props == null || !props.has("LoggingConfiguration")
                || props.get("LoggingConfiguration").isNull()) {
            return defaultStateMachineLoggingConfiguration();
        }
        JsonNode source = engine.resolveNode(props.get("LoggingConfiguration"));
        if (!source.isObject()) {
            throw new AwsException("ValidationError",
                    "LoggingConfiguration must be an object", 400);
        }

        ObjectNode result = objectMapper.createObjectNode();
        JsonNode level = source.get("Level");
        if (level != null && !level.isTextual()) {
            throw new AwsException("ValidationError",
                    "LoggingConfiguration.Level must be a string", 400);
        }
        result.put("level", level != null ? level.asText() : "OFF");

        JsonNode includeExecutionData = source.get("IncludeExecutionData");
        if (includeExecutionData != null && !includeExecutionData.isBoolean()) {
            throw new AwsException("ValidationError",
                    "LoggingConfiguration.IncludeExecutionData must be a boolean", 400);
        }
        result.put("includeExecutionData",
                includeExecutionData != null && includeExecutionData.asBoolean());

        ArrayNode destinations = result.putArray("destinations");
        JsonNode sourceDestinations = source.get("Destinations");
        if (sourceDestinations != null) {
            if (!sourceDestinations.isArray()) {
                throw new AwsException("ValidationError",
                        "LoggingConfiguration.Destinations must be an array", 400);
            }
            for (JsonNode destination : sourceDestinations) {
                JsonNode logGroupArn = destination.path("CloudWatchLogsLogGroup").get("LogGroupArn");
                if (logGroupArn == null || !logGroupArn.isTextual()) {
                    throw new AwsException("ValidationError",
                            "LoggingConfiguration destination LogGroupArn must be a string", 400);
                }
                destinations.addObject()
                        .putObject("cloudWatchLogsLogGroup")
                        .put("logGroupArn", logGroupArn.asText());
            }
        }
        return result;
    }
    private ObjectNode defaultStateMachineLoggingConfiguration() {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("level", "OFF");
        result.put("includeExecutionData", false);
        result.putArray("destinations");
        return result;
    }
    private JsonNode resolveStateMachineTracingConfiguration(
            JsonNode props, CloudFormationTemplateEngine engine) {
        if (props == null || !props.has("TracingConfiguration")
                || props.get("TracingConfiguration").isNull()) {
            return defaultStateMachineTracingConfiguration();
        }
        JsonNode source = engine.resolveNode(props.get("TracingConfiguration"));
        if (!source.isObject()) {
            throw new AwsException("ValidationError",
                    "TracingConfiguration must be an object", 400);
        }
        JsonNode enabled = source.get("Enabled");
        if (enabled != null && !enabled.isBoolean()) {
            throw new AwsException("ValidationError",
                    "TracingConfiguration.Enabled must be a boolean", 400);
        }
        return objectMapper.createObjectNode()
                .put("enabled", enabled != null && enabled.asBoolean());
    }
    private ObjectNode defaultStateMachineTracingConfiguration() {
        return objectMapper.createObjectNode().put("enabled", false);
    }
    private JsonNode resolveStateMachineEncryptionConfiguration(
            JsonNode props, CloudFormationTemplateEngine engine) {
        if (props == null || !props.has("EncryptionConfiguration")
                || props.get("EncryptionConfiguration").isNull()) {
            return defaultStateMachineEncryptionConfiguration();
        }
        JsonNode source = engine.resolveNode(props.get("EncryptionConfiguration"));
        if (!source.isObject()) {
            throw new AwsException("ValidationError",
                    "EncryptionConfiguration must be an object", 400);
        }

        JsonNode type = source.get("Type");
        if (type == null || !type.isTextual() || type.asText().isBlank()) {
            throw new AwsException("ValidationError",
                    "EncryptionConfiguration.Type is required and must be a string", 400);
        }
        ObjectNode result = objectMapper.createObjectNode().put("type", type.asText());

        JsonNode keyId = source.get("KmsKeyId");
        if (keyId != null) {
            if (!keyId.isTextual()) {
                throw new AwsException("ValidationError",
                        "EncryptionConfiguration.KmsKeyId must be a string", 400);
            }
            result.put("kmsKeyId", keyId.asText());
        }

        JsonNode reusePeriod = source.get("KmsDataKeyReusePeriodSeconds");
        if (reusePeriod != null) {
            if (!reusePeriod.isIntegralNumber()) {
                throw new AwsException("ValidationError",
                        "EncryptionConfiguration.KmsDataKeyReusePeriodSeconds must be an integer", 400);
            }
            result.put("kmsDataKeyReusePeriodSeconds", reusePeriod.intValue());
        }
        return result;
    }
    private ObjectNode defaultStateMachineEncryptionConfiguration() {
        return objectMapper.createObjectNode().put("type", "AWS_OWNED_KEY");
    }
    private void beginStepFunctionsUpdate(
            StackResource resource,
            StateMachine existing,
            boolean replacement,
            String replacementArn,
            String replacementRevisionId) {
        if (resource.getAttributes().containsKey(SFN_UPDATE_SNAPSHOT_ATTR)) {
            return;
        }
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("physicalId", resource.getPhysicalId());
        snapshot.put("replacement", replacement);
        if (replacementArn != null) {
            snapshot.put("replacementArn", replacementArn);
        }
        if (replacementRevisionId != null) {
            snapshot.put("replacementRevisionId", replacementRevisionId);
        }
        snapshot.put("replacementCreated", false);
        snapshot.put("cleanupAttempts", 0);
        snapshot.set("stateMachine", objectMapper.valueToTree(existing));
        ObjectNode attributes = snapshot.putObject("attributes");
        resource.getAttributes().forEach(attributes::put);
        resource.getAttributes().put(SFN_UPDATE_SNAPSHOT_ATTR, snapshot.toString());
    }
    private void markStepFunctionsReplacementCreated(
            StackResource resource, StateMachine replacement) {
        String rawSnapshot = resource.getAttributes().get(SFN_UPDATE_SNAPSHOT_ATTR);
        if (rawSnapshot == null) {
            throw new IllegalStateException(
                    "Step Functions replacement metadata is missing for "
                            + resource.getLogicalId());
        }
        try {
            ObjectNode snapshot = (ObjectNode) objectMapper.readTree(rawSnapshot);
            snapshot.put("replacementCreated", true);
            snapshot.put("replacementArn", replacement.getStateMachineArn());
            snapshot.put("replacementRevisionId", replacement.getRevisionId());
            resource.getAttributes().put(
                    SFN_UPDATE_SNAPSHOT_ATTR, snapshot.toString());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not record Step Functions replacement ownership for "
                            + resource.getLogicalId(), e);
        }
    }
    private String resolveOrDefault(JsonNode props, String name,
                                    ProvisionContext ctx, String defaultValue) {
        String value = ctx.resolveOptional(props, name);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }
    private Map<String, String> parseCfnTags(JsonNode tagsNode, CloudFormationTemplateEngine engine) {
        tagsNode = engine.resolveNode(tagsNode);
        Map<String, String> out = new HashMap<>();
        if (tagsNode == null || tagsNode.isNull() || !tagsNode.isArray()) {
            return out;
        }
        for (JsonNode entry : tagsNode) {
            JsonNode resolved = engine.resolveNode(entry);
            String key = resolved.path("Key").asText(null);
            String value = resolved.path("Value").asText("");
            if (key != null) {
                out.put(key, value);
            }
        }
        return out;
    }
}
