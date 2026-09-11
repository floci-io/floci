package io.github.hectorvent.floci.services.bedrock;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.bedrock.model.Guardrail;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.kms.model.KmsKey;
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
import java.util.regex.Pattern;

/**
 * Amazon Bedrock control plane (signing name {@code bedrock}). Guardrails are
 * versioned: the working copy is {@code DRAFT} and {@code CreateGuardrailVersion}
 * snapshots it under the next numerical version. Every guardrail is READY as soon
 * as it is created, so {@code aws_bedrock_guardrail}'s status poll completes on its
 * first read.
 *
 * <p>Model invocation is a separate service, {@code bedrock-runtime}, which shares
 * this signing name but serves the {@code /model/...} routes.
 */
@ApplicationScoped
public class BedrockService {

    public static final String DRAFT_VERSION = "DRAFT";
    public static final String READY = "READY";

    private static final Logger LOG = Logger.getLogger(BedrockService.class);

    /** {@code GuardrailName} from the AWS model. */
    private static final Pattern NAME_PATTERN = Pattern.compile("[0-9a-zA-Z\\-_]{1,50}");

    /** {@code GuardrailNumericalVersion} from the AWS model. */
    private static final Pattern NUMERICAL_VERSION_PATTERN = Pattern.compile("[1-9][0-9]{0,7}");

    /** {@code GuardrailDescription} from the AWS model: min 1, max 200. */
    private static final int DESCRIPTION_MAX = 200;

    /** {@code GuardrailBlockedMessaging} from the AWS model: min 1, max 500. */
    private static final int BLOCKED_MESSAGING_MAX = 500;

    /** {@code MaxResults} on {@code ListGuardrails} is capped at 1000 by the AWS model. */
    private static final int MAX_PAGE = 1000;

    /**
     * Policy blocks arrive as {@code *Config} shapes on create and update, and are read back
     * as their unsuffixed counterparts. The member structures are identical in the AWS
     * model, only these container keys are renamed.
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
    private final KmsService kmsService;

    @Inject
    public BedrockService(StorageFactory storageFactory, RegionResolver regionResolver,
                          ObjectMapper objectMapper, KmsService kmsService) {
        this(storageFactory.create("bedrock", "bedrock-guardrails.json",
                new TypeReference<Map<String, Guardrail>>() {}), regionResolver, objectMapper, kmsService);
    }

    BedrockService(StorageBackend<String, Guardrail> guardrails, RegionResolver regionResolver,
                   ObjectMapper objectMapper, KmsService kmsService) {
        this.guardrails = guardrails;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
        this.kmsService = kmsService;
    }

    public Guardrail createGuardrail(JsonNode request, String region) {
        String name = requiredName(request);
        String description = boundedDescription(request);
        String blockedInputMessaging = requiredBlockedMessaging(request, "blockedInputMessaging");
        String blockedOutputsMessaging = requiredBlockedMessaging(request, "blockedOutputsMessaging");

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
        guardrail.setDescription(description);
        guardrail.setVersion(DRAFT_VERSION);
        guardrail.setBlockedInputMessaging(blockedInputMessaging);
        guardrail.setBlockedOutputsMessaging(blockedOutputsMessaging);
        guardrail.setKmsKeyArn(resolveKmsKeyArn(textOrNull(request, "kmsKeyId"), region));
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
        String name = requiredName(request);
        String description = boundedDescription(request);
        String blockedInputMessaging = requiredBlockedMessaging(request, "blockedInputMessaging");
        String blockedOutputsMessaging = requiredBlockedMessaging(request, "blockedOutputsMessaging");

        Guardrail guardrail = getGuardrail(identifier, DRAFT_VERSION, region);
        guardrail.setName(name);
        guardrail.setDescription(description);
        guardrail.setBlockedInputMessaging(blockedInputMessaging);
        guardrail.setBlockedOutputsMessaging(blockedOutputsMessaging);
        guardrail.setKmsKeyArn(resolveKmsKeyArn(textOrNull(request, "kmsKeyId"), region));
        guardrail.setUpdatedAt(Instant.now());
        applyPolicies(guardrail, request, region);

        guardrails.put(storageKey(region, guardrail.getGuardrailId(), DRAFT_VERSION), guardrail);
        LOG.infov("Updated Bedrock guardrail: {0}", guardrail.getGuardrailId());
        return guardrail;
    }

    /**
     * Deletes one numerical version, or the whole guardrail when no version is given.
     * {@code guardrailVersion} is a {@code GuardrailNumericalVersion} in the AWS model,
     * so DRAFT cannot be deleted on its own.
     */
    public void deleteGuardrail(String identifier, String version, String region) {
        String guardrailId = resolveGuardrailId(identifier);
        if (version != null && !version.isBlank()) {
            if (!NUMERICAL_VERSION_PATTERN.matcher(version).matches()) {
                throw new AwsException("ValidationException",
                        "guardrailVersion must be a numerical version.", 400);
            }
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
     * lists every version of that guardrail, which is what the AWS model documents.
     */
    public PaginatedResult<Guardrail> listGuardrails(String identifier, Integer maxResults,
                                                     String nextToken, String region) {
        List<Guardrail> matches;
        if (identifier == null || identifier.isBlank()) {
            matches = draftsIn(region);
        } else {
            String guardrailId = resolveGuardrailId(identifier);
            matches = versionsOf(guardrailId, region);
            if (matches.isEmpty()) {
                throw new AwsException("ResourceNotFoundException",
                        "Guardrail " + guardrailId + " does not exist.", 404);
            }
        }
        return Pagination.paginate(matches, BedrockService::listCursor, maxResults, nextToken,
                MAX_PAGE, "ValidationException");
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

    // Tags

    public Map<String, String> listTags(String resourceArn, String region) {
        Guardrail guardrail = findByArn(resourceArn, region);
        return guardrail.getTags() != null ? guardrail.getTags() : Map.of();
    }

    public synchronized void tagResource(String resourceArn, Map<String, String> tags, String region) {
        Guardrail guardrail = findByArn(resourceArn, region);
        if (guardrail.getTags() == null) {
            guardrail.setTags(new HashMap<>());
        }
        guardrail.getTags().putAll(tags);
        guardrails.put(storageKey(region, guardrail.getGuardrailId(), DRAFT_VERSION), guardrail);
    }

    public synchronized void untagResource(String resourceArn, List<String> tagKeys, String region) {
        Guardrail guardrail = findByArn(resourceArn, region);
        if (guardrail.getTags() != null && tagKeys != null) {
            tagKeys.forEach(guardrail.getTags()::remove);
        }
        guardrails.put(storageKey(region, guardrail.getGuardrailId(), DRAFT_VERSION), guardrail);
    }

    // Helpers

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
        policyConfig.properties().forEach(entry ->
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

    /**
     * Pagination cursor. DRAFT sorts after the numerical versions of the same guardrail
     * because the padded numbers never reach the letter range.
     */
    private static String listCursor(Guardrail guardrail) {
        String version = guardrail.getVersion();
        String sortableVersion = NUMERICAL_VERSION_PATTERN.matcher(version).matches()
                ? String.format(Locale.ROOT, "%08d", Integer.parseInt(version))
                : version;
        return guardrail.getGuardrailId() + "::" + sortableVersion;
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

    private String requiredName(JsonNode request) {
        String name = requiredText(request, "name");
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new AwsException("ValidationException",
                    "name must match [0-9a-zA-Z-_]+ and be at most 50 characters.", 400);
        }
        return name;
    }

    /**
     * {@code description} is optional, but the AWS model gives it a 1 to 200 character
     * {@code GuardrailDescription} shape, so an over-long or empty value is rejected.
     */
    private String boundedDescription(JsonNode request) {
        String description = textOrNull(request, "description");
        if (description == null) {
            return null;
        }
        return bounded(description, "description", DESCRIPTION_MAX);
    }

    private String requiredBlockedMessaging(JsonNode request, String field) {
        return bounded(requiredText(request, field), field, BLOCKED_MESSAGING_MAX);
    }

    private String bounded(String value, String field, int max) {
        if (value.isEmpty() || value.length() > max) {
            throw new AwsException("ValidationException",
                    field + " must be between 1 and " + max + " characters.", 400);
        }
        return value;
    }

    /**
     * {@code kmsKeyId} on the request is a {@code KmsKeyId}: a key id, a key ARN, an alias name
     * or an alias ARN. {@code kmsKeyArn} on the read shapes is a {@code KmsKeyArn}, which is only
     * ever the full key ARN, so every accepted form resolves through KMS to that one shape.
     */
    private String resolveKmsKeyArn(String kmsKeyId, String region) {
        if (kmsKeyId == null || kmsKeyId.isBlank()) {
            return null;
        }
        if (kmsService == null) {
            throw new IllegalStateException("BedrockService was built without a KmsService; "
                    + "a kmsKeyId cannot be resolved");
        }
        KmsKey key;
        try {
            key = kmsService.describeKey(kmsKeyId, region);
        } catch (AwsException e) {
            LOG.debugv("Rejecting Bedrock guardrail kmsKeyId {0}: {1}", kmsKeyId, e.getMessage());
            throw kmsKeyNotUsable(kmsKeyId);
        }
        if (!key.isEnabled() || "PendingDeletion".equals(key.getKeyState())) {
            throw kmsKeyNotUsable(kmsKeyId);
        }
        return key.getArn();
    }

    private static AwsException kmsKeyNotUsable(String kmsKeyId) {
        return new AwsException("ValidationException", "The KMS key " + kmsKeyId
                + " does not exist, is not enabled, or cannot be used.", 400);
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
