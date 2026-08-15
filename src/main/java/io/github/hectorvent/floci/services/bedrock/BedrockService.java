package io.github.hectorvent.floci.services.bedrock;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.bedrock.model.Guardrail;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Amazon Bedrock control plane (signing name {@code bedrock}). Guardrails are
 * versioned: the working copy is {@code DRAFT} and {@code CreateGuardrailVersion}
 * snapshots it under the next numerical version. Every guardrail is READY as soon
 * as it is created, so {@code aws_bedrock_guardrail}'s status poll completes on its
 * first read.
 */
@ApplicationScoped
public class BedrockService {

    public static final String DRAFT_VERSION = "DRAFT";
    public static final String READY = "READY";

    private static final Logger LOG = Logger.getLogger(BedrockService.class);

    /**
     * Policy blocks arrive as {@code *Config} shapes on create/update and are read back
     * as their unsuffixed counterparts. The member structures are identical in the AWS
     * model; only these container keys are renamed.
     */
    private static final Map<String, String> POLICY_MEMBER_RENAMES = Map.of(
            "topicsConfig", "topics",
            "filtersConfig", "filters",
            "tierConfig", "tier",
            "wordsConfig", "words",
            "managedWordListsConfig", "managedWordLists",
            "piiEntitiesConfig", "piiEntities",
            "regexesConfig", "regexes");

    private final StorageBackend<String, Guardrail> guardrails;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public BedrockService(StorageFactory storageFactory, RegionResolver regionResolver,
                          ObjectMapper objectMapper) {
        this.guardrails = storageFactory.create("bedrock", "bedrock-guardrails.json",
                new TypeReference<Map<String, Guardrail>>() {});
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public Guardrail createGuardrail(JsonNode request, String region) {
        String name = requiredText(request, "name");
        String blockedInputMessaging = requiredText(request, "blockedInputMessaging");
        String blockedOutputsMessaging = requiredText(request, "blockedOutputsMessaging");

        for (Guardrail existing : draftsIn(region)) {
            if (name.equals(existing.getName())) {
                throw new AwsException("ConflictException",
                        "Guardrail with name " + name + " already exists.", 400);
            }
        }

        String guardrailId = newGuardrailId();
        Instant now = Instant.now();

        Guardrail guardrail = new Guardrail();
        guardrail.setGuardrailId(guardrailId);
        guardrail.setGuardrailArn(regionResolver.buildArn("bedrock", region, "guardrail/" + guardrailId));
        guardrail.setName(name);
        guardrail.setDescription(textOrNull(request, "description"));
        guardrail.setVersion(DRAFT_VERSION);
        guardrail.setBlockedInputMessaging(blockedInputMessaging);
        guardrail.setBlockedOutputsMessaging(blockedOutputsMessaging);
        guardrail.setKmsKeyArn(textOrNull(request, "kmsKeyId"));
        guardrail.setCreatedAt(now);
        guardrail.setUpdatedAt(now);
        guardrail.setTags(parseTagList(request.get("tags")));
        guardrail.setAccountId(regionResolver.getAccountId());
        applyPolicies(guardrail, request, region);

        guardrails.put(storageKey(region, guardrailId, DRAFT_VERSION), guardrail);
        LOG.infov("Created Bedrock guardrail: {0}", guardrailId);
        return guardrail;
    }

    public Guardrail getGuardrail(String identifier, String version, String region) {
        String guardrailId = resolveGuardrailId(identifier);
        String effectiveVersion = (version == null || version.isBlank()) ? DRAFT_VERSION : version;
        return guardrails.get(storageKey(region, guardrailId, effectiveVersion))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Guardrail " + guardrailId + " version " + effectiveVersion + " does not exist.", 404));
    }

    public Guardrail updateGuardrail(String identifier, JsonNode request, String region) {
        String name = requiredText(request, "name");
        String blockedInputMessaging = requiredText(request, "blockedInputMessaging");
        String blockedOutputsMessaging = requiredText(request, "blockedOutputsMessaging");

        Guardrail guardrail = getGuardrail(identifier, DRAFT_VERSION, region);
        guardrail.setName(name);
        guardrail.setDescription(textOrNull(request, "description"));
        guardrail.setBlockedInputMessaging(blockedInputMessaging);
        guardrail.setBlockedOutputsMessaging(blockedOutputsMessaging);
        guardrail.setKmsKeyArn(textOrNull(request, "kmsKeyId"));
        guardrail.setUpdatedAt(Instant.now());
        applyPolicies(guardrail, request, region);

        guardrails.put(storageKey(region, guardrail.getGuardrailId(), DRAFT_VERSION), guardrail);
        LOG.infov("Updated Bedrock guardrail: {0}", guardrail.getGuardrailId());
        return guardrail;
    }

    public void deleteGuardrail(String identifier, String version, String region) {
        String guardrailId = resolveGuardrailId(identifier);
        if (version != null && !version.isBlank()) {
            getGuardrail(guardrailId, version, region);
            guardrails.delete(storageKey(region, guardrailId, version));
            LOG.infov("Deleted Bedrock guardrail {0} version {1}", guardrailId, version);
            return;
        }
        List<Guardrail> versions = versionsOf(guardrailId, region);
        if (versions.isEmpty()) {
            throw new AwsException("ResourceNotFoundException",
                    "Guardrail " + guardrailId + " does not exist.", 404);
        }
        for (Guardrail guardrail : versions) {
            guardrails.delete(storageKey(region, guardrailId, guardrail.getVersion()));
        }
        LOG.infov("Deleted Bedrock guardrail: {0}", guardrailId);
    }

    /**
     * With no identifier, lists the DRAFT of every guardrail. With an identifier,
     * lists every version of that guardrail — the behaviour the AWS model documents.
     */
    public List<Guardrail> listGuardrails(String identifier, String region) {
        if (identifier == null || identifier.isBlank()) {
            return draftsIn(region);
        }
        String guardrailId = resolveGuardrailId(identifier);
        List<Guardrail> versions = versionsOf(guardrailId, region);
        if (versions.isEmpty()) {
            throw new AwsException("ResourceNotFoundException",
                    "Guardrail " + guardrailId + " does not exist.", 404);
        }
        return versions;
    }

    public Guardrail createGuardrailVersion(String identifier, String description, String region) {
        Guardrail draft = getGuardrail(identifier, DRAFT_VERSION, region);
        String guardrailId = draft.getGuardrailId();
        String version = String.valueOf(nextVersionNumber(guardrailId, region));

        Guardrail snapshot = copyOf(draft);
        snapshot.setVersion(version);
        if (description != null) {
            snapshot.setDescription(description);
        }
        snapshot.setUpdatedAt(Instant.now());

        guardrails.put(storageKey(region, guardrailId, version), snapshot);
        LOG.infov("Created Bedrock guardrail {0} version {1}", guardrailId, version);
        return snapshot;
    }

    // ──────────────────────────── Tags ────────────────────────────

    public Map<String, String> listTags(String resourceArn, String region) {
        Guardrail guardrail = findByArn(resourceArn, region);
        return guardrail.getTags() != null ? guardrail.getTags() : Map.of();
    }

    public void tagResource(String resourceArn, Map<String, String> tags, String region) {
        Guardrail guardrail = findByArn(resourceArn, region);
        if (guardrail.getTags() == null) {
            guardrail.setTags(new HashMap<>());
        }
        guardrail.getTags().putAll(tags);
        guardrails.put(storageKey(region, guardrail.getGuardrailId(), DRAFT_VERSION), guardrail);
    }

    public void untagResource(String resourceArn, List<String> tagKeys, String region) {
        Guardrail guardrail = findByArn(resourceArn, region);
        if (guardrail.getTags() != null && tagKeys != null) {
            tagKeys.forEach(guardrail.getTags()::remove);
        }
        guardrails.put(storageKey(region, guardrail.getGuardrailId(), DRAFT_VERSION), guardrail);
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private void applyPolicies(Guardrail guardrail, JsonNode request, String region) {
        guardrail.setTopicPolicy(renamePolicy(request.get("topicPolicyConfig")));
        guardrail.setContentPolicy(renamePolicy(request.get("contentPolicyConfig")));
        guardrail.setWordPolicy(renamePolicy(request.get("wordPolicyConfig")));
        guardrail.setSensitiveInformationPolicy(renamePolicy(request.get("sensitiveInformationPolicyConfig")));
        guardrail.setContextualGroundingPolicy(renamePolicy(request.get("contextualGroundingPolicyConfig")));
        guardrail.setAutomatedReasoningPolicy(renamePolicy(request.get("automatedReasoningPolicyConfig")));
        guardrail.setCrossRegionDetails(crossRegionDetails(request.get("crossRegionConfig"), region));
    }

    private JsonNode renamePolicy(JsonNode policyConfig) {
        if (policyConfig == null || !policyConfig.isObject()) {
            return null;
        }
        ObjectNode policy = objectMapper.createObjectNode();
        policyConfig.fields().forEachRemaining(entry ->
                policy.set(POLICY_MEMBER_RENAMES.getOrDefault(entry.getKey(), entry.getKey()), entry.getValue()));
        return policy;
    }

    private JsonNode crossRegionDetails(JsonNode crossRegionConfig, String region) {
        if (crossRegionConfig == null || !crossRegionConfig.isObject()) {
            return null;
        }
        JsonNode identifier = crossRegionConfig.get("guardrailProfileIdentifier");
        if (identifier == null || identifier.isNull()) {
            return null;
        }
        String value = identifier.asText();
        String profileId = value.startsWith("arn:")
                ? value.substring(value.lastIndexOf('/') + 1)
                : value;
        ObjectNode details = objectMapper.createObjectNode();
        details.put("guardrailProfileId", profileId);
        details.put("guardrailProfileArn",
                regionResolver.buildArn("bedrock", region, "guardrail-profile/" + profileId));
        return details;
    }

    private Guardrail copyOf(Guardrail source) {
        Guardrail copy = new Guardrail();
        copy.setGuardrailId(source.getGuardrailId());
        copy.setGuardrailArn(source.getGuardrailArn());
        copy.setName(source.getName());
        copy.setDescription(source.getDescription());
        copy.setBlockedInputMessaging(source.getBlockedInputMessaging());
        copy.setBlockedOutputsMessaging(source.getBlockedOutputsMessaging());
        copy.setKmsKeyArn(source.getKmsKeyArn());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        copy.setTags(source.getTags() != null ? new HashMap<>(source.getTags()) : new HashMap<>());
        copy.setAccountId(source.getAccountId());
        copy.setTopicPolicy(source.getTopicPolicy());
        copy.setContentPolicy(source.getContentPolicy());
        copy.setWordPolicy(source.getWordPolicy());
        copy.setSensitiveInformationPolicy(source.getSensitiveInformationPolicy());
        copy.setContextualGroundingPolicy(source.getContextualGroundingPolicy());
        copy.setAutomatedReasoningPolicy(source.getAutomatedReasoningPolicy());
        copy.setCrossRegionDetails(source.getCrossRegionDetails());
        return copy;
    }

    private int nextVersionNumber(String guardrailId, String region) {
        int highest = 0;
        for (Guardrail guardrail : versionsOf(guardrailId, region)) {
            if (DRAFT_VERSION.equals(guardrail.getVersion())) {
                continue;
            }
            try {
                highest = Math.max(highest, Integer.parseInt(guardrail.getVersion()));
            } catch (NumberFormatException e) {
                LOG.debugv("Ignoring non-numerical guardrail version {0} on {1}: {2}",
                        guardrail.getVersion(), guardrailId, e.getMessage());
            }
        }
        return highest + 1;
    }

    private List<Guardrail> versionsOf(String guardrailId, String region) {
        String prefix = region + "::" + guardrailId + "::";
        List<Guardrail> versions = new ArrayList<>(guardrails.scan(key -> key.startsWith(prefix)));
        versions.sort(Comparator.comparing(Guardrail::getVersion));
        return versions;
    }

    private List<Guardrail> draftsIn(String region) {
        String prefix = region + "::";
        String suffix = "::" + DRAFT_VERSION;
        return guardrails.scan(key -> key.startsWith(prefix) && key.endsWith(suffix));
    }

    private Guardrail findByArn(String resourceArn, String region) {
        if (resourceArn == null || resourceArn.isBlank()) {
            throw new AwsException("ValidationException", "resourceARN is required", 400);
        }
        String guardrailId = resolveGuardrailId(resourceArn);
        return guardrails.get(storageKey(region, guardrailId, DRAFT_VERSION))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Resource " + resourceArn + " does not exist.", 404));
    }

    private String resolveGuardrailId(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new AwsException("ValidationException", "guardrailIdentifier is required", 400);
        }
        if (!identifier.startsWith("arn:")) {
            return identifier;
        }
        String resource;
        try {
            resource = AwsArnUtils.parse(identifier).resource();
        } catch (IllegalArgumentException e) {
            throw new AwsException("ValidationException",
                    "Invalid guardrail identifier: " + identifier, 400);
        }
        int slash = resource.indexOf('/');
        if (slash < 0 || slash == resource.length() - 1) {
            throw new AwsException("ValidationException",
                    "Invalid guardrail identifier: " + identifier, 400);
        }
        return resource.substring(slash + 1);
    }

    private String storageKey(String region, String guardrailId, String version) {
        return region + "::" + guardrailId + "::" + version;
    }

    private String newGuardrailId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12).toLowerCase(Locale.ROOT);
    }

    private String requiredText(JsonNode request, String field) {
        String value = textOrNull(request, field);
        if (value == null || value.isBlank()) {
            throw new AwsException("ValidationException", field + " is required", 400);
        }
        return value;
    }

    private String textOrNull(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    private Map<String, String> parseTagList(JsonNode tagsNode) {
        Map<String, String> tags = new HashMap<>();
        if (tagsNode != null && tagsNode.isArray()) {
            for (JsonNode tag : tagsNode) {
                JsonNode key = tag.get("key");
                JsonNode value = tag.get("value");
                if (key != null && !key.isNull() && value != null && !value.isNull()) {
                    tags.put(key.asText(), value.asText());
                }
            }
        }
        return tags;
    }
}
