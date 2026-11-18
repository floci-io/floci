package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.bedrock.BedrockService;
import io.floci.aws.services.bedrock.BedrockTagValidator;
import io.floci.aws.services.bedrock.model.ApplicationInferenceProfile;
import io.floci.aws.services.cloudformation.model.StackResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Provisions an application inference profile through the Bedrock control plane. */
@ApplicationScoped
public class BedrockCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::Bedrock::ApplicationInferenceProfile";
    private static final String NOT_FOUND = "ResourceNotFoundException";
    private static final String PRIOR_TAGS = "__FlociBedrockProfilePriorTags";
    private static final String MANAGED_TAG_KEYS = "__FlociBedrockProfileManagedTagKeys";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BedrockService bedrockService;

    @Inject
    public BedrockCfnProvisioner(BedrockService bedrockService) {
        this.bedrockService = bedrockService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public Map<String, Set<String>> jsonAttributes() {
        return Map.of(TYPE, Set.of("Models"));
    }

    @Override
    public void provision(StackResource resource, JsonNode properties, ProvisionContext ctx) {
        Map<String, String> attributesBefore = Map.copyOf(resource.getAttributes());
        String name = required(ctx.resolveOptional(properties, "InferenceProfileName"), "InferenceProfileName");
        String description = ctx.resolveOptional(properties, "Description");
        String source = modelSource(properties, ctx);
        Map<String, String> tags = tags(properties, ctx);
        Set<String> previouslyManagedTagKeys = managedTagKeys(attributesBefore);

        ApplicationInferenceProfile existing = ctx.isUpdate()
                && !CfnResourceDispatcher.isStub(ctx.priorPhysicalId(), attributesBefore)
                ? bedrockService.getApplicationInferenceProfile(ctx.priorPhysicalId(), ctx.region()) : null;
        if (existing != null && !ctx.changes(TYPE, properties).requiresReplacement()) {
            resource.getAttributes().remove(PRIOR_TAGS);
            Map<String, String> existingTags = existing.getTags() == null ? Map.of() : existing.getTags();
            if (tagStateChanged(existingTags, previouslyManagedTagKeys, tags)) {
                ObjectNode snapshot = MAPPER.createObjectNode();
                snapshot.put("region", ctx.region());
                snapshot.put("managedTagKeys", attributesBefore.get(MANAGED_TAG_KEYS));
                Set<String> touchedKeys = new HashSet<>(previouslyManagedTagKeys);
                touchedKeys.addAll(tags.keySet());
                CfnRollback.putTagSnapshot(snapshot, touchedKeys, existingTags);
                resource.getAttributes().put(PRIOR_TAGS, snapshot.toString());
                CfnRollback.guarded(resource, ctx, false, () -> { }, () -> restoreTags(resource), () -> {
                    reconcileTags(existing.getInferenceProfileArn(), previouslyManagedTagKeys, tags, ctx.region());
                    publish(resource, bedrockService.getApplicationInferenceProfile(
                            existing.getInferenceProfileId(), ctx.region()));
                });
            } else {
                publish(resource, bedrockService.getApplicationInferenceProfile(existing.getInferenceProfileId(),
                        ctx.region()));
            }
        } else {
            ApplicationInferenceProfile created = bedrockService.createApplicationInferenceProfile(name,
                    description, source, tags, ctx.region());
            publish(resource, created);
        }
        recordManagedTagKeys(resource, tags.keySet());
        ReplacementCleanup.record(resource, ctx, attributesBefore);
    }

    private static String modelSource(JsonNode properties, ProvisionContext ctx) {
        JsonNode source = properties == null ? null : properties.get("ModelSource");
        String copyFrom = source == null ? null : ctx.engine().resolve(source.path("CopyFrom"));
        return required(copyFrom, "ModelSource.CopyFrom");
    }

    private static Map<String, String> tags(JsonNode properties, ProvisionContext ctx) {
        if (properties == null || !properties.has("Tags")) {
            return Map.of();
        }
        JsonNode resolved = ctx.engine().resolveNode(properties.get("Tags"));
        if (resolved == null || !resolved.isArray()) {
            throw new AwsException("ValidationError", TYPE + " Tags must be an array.", 400);
        }
        BedrockService.validateTagListSize(resolved, "ValidationError", TYPE + " Tags");
        BedrockTagValidator.validate(resolved, "Key", "Value", "ValidationError",
                TYPE + " Tags");
        Map<String, String> tags = new LinkedHashMap<>();
        for (JsonNode tag : resolved) {
            tags.put(tag.path("Key").textValue(), tag.path("Value").textValue());
        }
        return tags;
    }

    private static String required(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new AwsException("ValidationError", TYPE + " requires " + property, 400);
        }
        return value;
    }

    private static void publish(StackResource resource, ApplicationInferenceProfile profile) {
        String id = profile.getInferenceProfileId();
        resource.setPhysicalId(id);
        resource.getAttributes().put("InferenceProfileId", id);
        resource.getAttributes().put("InferenceProfileArn", profile.getInferenceProfileArn());
        resource.getAttributes().put("InferenceProfileIdentifier", id);
        resource.getAttributes().put("Status", "ACTIVE");
        resource.getAttributes().put("Type", "APPLICATION");
        resource.getAttributes().put("CreatedAt", profile.getCreatedAt().toString());
        resource.getAttributes().put("UpdatedAt", profile.getUpdatedAt().toString());
        ArrayNode models = MAPPER.createArrayNode();
        for (String modelArn : profile.getModels()) {
            models.addObject().put("ModelArn", modelArn);
        }
        resource.getAttributes().put("Models", models.toString());
    }

    private static boolean tagStateChanged(Map<String, String> current, Set<String> previouslyManaged,
                                           Map<String, String> desired) {
        return !previouslyManaged.equals(desired.keySet()) || previouslyManaged.stream()
                .anyMatch(key -> !Objects.equals(current.get(key), desired.get(key)));
    }

    private void reconcileTags(String arn, Set<String> managedKeys, Map<String, String> desired, String region) {
        CfnRollback.reconcileTags(managedKeys, desired,
                keys -> bedrockService.untagResource(arn, keys, region),
                tags -> bedrockService.tagResource(arn, tags, region));
    }

    private void restoreTags(StackResource resource) {
        JsonNode snapshot = CfnRollback.updateSnapshot(resource, PRIOR_TAGS);
        if (snapshot == null) {
            return;
        }
        String region = CfnRollback.snapshotText(snapshot, "region");
        List<String> managedKeys = CfnRollback.snapshotTagKeys(snapshot);
        Map<String, String> tags = CfnRollback.snapshotTags(snapshot);
        ApplicationInferenceProfile profile = bedrockService.getApplicationInferenceProfile(
                resource.getPhysicalId(), region);
        CfnRollback.reconcileTags(managedKeys, tags,
                keys -> bedrockService.untagResource(profile.getInferenceProfileArn(), keys, region),
                desired -> bedrockService.tagResource(profile.getInferenceProfileArn(), desired, region));
        restoreManagedTagKeys(resource, CfnRollback.snapshotText(snapshot, "managedTagKeys"));
        resource.getAttributes().remove(PRIOR_TAGS);
    }

    private static Set<String> managedTagKeys(Map<String, String> attributes) {
        String raw = attributes.get(MANAGED_TAG_KEYS);
        if (raw == null) {
            return Set.of();
        }
        try {
            JsonNode keys = MAPPER.readTree(raw);
            if (keys == null || !keys.isArray()) {
                throw new IllegalArgumentException("managed tag keys are not an array");
            }
            Set<String> result = new HashSet<>();
            for (JsonNode key : keys) {
                if (!key.isTextual()) {
                    throw new IllegalArgumentException("managed tag key is not a string");
                }
                result.add(key.textValue());
            }
            return result;
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new AwsException("InternalFailure", "Invalid Bedrock profile managed-tag metadata: "
                    + e.getMessage(), 500);
        }
    }

    private static void recordManagedTagKeys(StackResource resource, Set<String> keys) {
        if (keys.isEmpty()) {
            resource.getAttributes().remove(MANAGED_TAG_KEYS);
            return;
        }
        try {
            resource.getAttributes().put(MANAGED_TAG_KEYS, MAPPER.writeValueAsString(new TreeSet<>(keys)));
        } catch (JsonProcessingException e) {
            throw new AwsException("InternalFailure", "Failed to store Bedrock profile managed-tag metadata: "
                    + e.getMessage(), 500);
        }
    }

    private static void restoreManagedTagKeys(StackResource resource, String keys) {
        if (keys == null) {
            resource.getAttributes().remove(MANAGED_TAG_KEYS);
        } else {
            resource.getAttributes().put(MANAGED_TAG_KEYS, keys);
        }
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (ReplacementCleanup.rollback(resource, this::delete)) {
            resource.getAttributes().remove(PRIOR_TAGS);
            return true;
        }
        restoreTags(resource);
        return true;
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return ReplacementCleanup.hasReplacement(resource);
    }

    @Override
    public void mergeFailedUpdateResourceTracking(StackResource previous, StackResource attempted) {
        String priorTags = attempted.getAttributes().get(PRIOR_TAGS);
        if (priorTags != null) {
            previous.getAttributes().put(PRIOR_TAGS, priorTags);
        }
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return ReplacementCleanup.cleanupPhysicalId(resource);
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        resource.getAttributes().remove(PRIOR_TAGS);
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(PRIOR_TAGS);
        ReplacementCleanup.clear(resource);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null || physicalId.isBlank()) {
            return;
        }
        CfnDeletes.safeDelete("Bedrock application inference profile", physicalId,
                () -> bedrockService.deleteApplicationInferenceProfile(physicalId, region), NOT_FOUND);
    }
}
