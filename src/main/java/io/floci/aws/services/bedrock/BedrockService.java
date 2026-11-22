package io.floci.aws.services.bedrock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.SmithyValidationErrors;
import io.floci.aws.core.resource.ExplorerResource;
import io.floci.aws.core.resource.ResourceProvider;
import io.floci.aws.core.resource.SupportedResourceType;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.bedrock.model.ApplicationInferenceProfile;
import io.floci.aws.services.bedrock.model.Guardrail;
import io.floci.aws.services.kms.KmsService;
import io.floci.aws.services.kms.model.KmsKey;
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
import java.util.Optional;
import java.util.Set;
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
public class BedrockService implements ResourceProvider {

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
    // Wider than the published ModelModality enum: AWS also accepts SPEECH and VIDEO here.
    private static final List<String> OUTPUT_MODALITIES = List.of("TEXT", "IMAGE", "EMBEDDING", "SPEECH", "VIDEO");
    private static final List<String> INFERENCE_TYPES = List.of("ON_DEMAND", "PROVISIONED");
    private static final List<String> CUSTOMIZATION_TYPES = List.of("FINE_TUNING", "CONTINUED_PRE_TRAINING", "DISTILLATION");
    private static final String FOUNDATION_MODEL_PREFIX = "foundation-model/";
    private static final String INFERENCE_PROFILE_PREFIX = "inference-profile/";
    private static final String APPLICATION_INFERENCE_PROFILE_PREFIX = "application-inference-profile/";
    private static final List<String> INFERENCE_PROFILE_TYPES = List.of("SYSTEM_DEFINED", "APPLICATION");
    private static final Pattern APPLICATION_PROFILE_NAME_PATTERN =
            Pattern.compile("([0-9a-zA-Z][ _-]?)+");
    private static final Pattern APPLICATION_PROFILE_DESCRIPTION_PATTERN =
            Pattern.compile("([0-9a-zA-Z:.][ _-]?)+");
    private static final Pattern IDEMPOTENCY_TOKEN_PATTERN =
            Pattern.compile("[a-zA-Z0-9]([-a-zA-Z0-9]{0,254}[a-zA-Z0-9])?");

    /** {@code TagList} from the AWS model: at most 200 items on one request. */
    private static final int TAG_LIST_MAX = 200;

    /**
     * {@code TooManyTagsException} documents a limit of 50 tags per resource, counted over the
     * tags already on the resource together with the tags carried by the current request.
     */
    private static final int TAGS_PER_RESOURCE_MAX = 50;

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

    /** {@code ResourcePolicyResourceArn} from the AWS model: min 1, max 255. */
    private static final int RESOURCE_POLICY_ARN_MAX = 255;

    /** {@code ResourcePolicyDocument} from the AWS model: min 1, max 20480. */
    private static final int RESOURCE_POLICY_MAX = 20480;

    /** {@code GuardrailCrossRegionGuardrailProfileId} from the AWS model, e.g. {@code us.guardrail.v1:0}. */
    private static final Pattern GUARDRAIL_PROFILE_ID_PATTERN =
            Pattern.compile("[a-z0-9-]+[.]guardrail[.]v[0-9:]+");

    private static final String GUARDRAIL_RESOURCE_PREFIX = "guardrail/";
    private static final String GUARDRAIL_PROFILE_RESOURCE_PREFIX = "guardrail-profile/";

    /**
     * The actions the Bedrock user guide lets a guardrail resource-based policy grant. A guardrail
     * profile's policy may grant only {@code bedrock:ApplyGuardrail}.
     */
    private static final Set<String> GUARDRAIL_POLICY_ACTIONS =
            Set.of("bedrock:ApplyGuardrail", "bedrock:GetGuardrail");
    private static final Set<String> GUARDRAIL_PROFILE_POLICY_ACTIONS = Set.of("bedrock:ApplyGuardrail");

    /** What {@code GetResourcePolicy} answers for a guardrail profile holding no policy under the called Region. */
    private static final String EMPTY_POLICY = "{}";

    private final StorageBackend<String, Guardrail> guardrails;
    /**
     * Guardrail-profile policies, keyed by the Region whose endpoint stored them and the profile ARN. A
     * system-defined profile has one ARN per destination Region, and AWS keeps each policy under the
     * Region that received {@code PutResourcePolicy}, so one ARN can hold a different policy per Region.
     */
    private final StorageBackend<String, String> guardrailProfilePolicies;
    private final StorageBackend<String, ApplicationInferenceProfile> applicationInferenceProfiles;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    private final KmsService kmsService;

    @Inject
    public BedrockService(StorageFactory storageFactory, RegionResolver regionResolver,
                          ObjectMapper objectMapper, KmsService kmsService) {
        this(storageFactory.create("bedrock", "bedrock-guardrails.json",
                        new TypeReference<Map<String, Guardrail>>() {}),
                storageFactory.create("bedrock", "bedrock-guardrail-profile-policies.json",
                        new TypeReference<Map<String, String>>() {}),
                storageFactory.create("bedrock", "bedrock-application-inference-profiles.json",
                        new TypeReference<Map<String, ApplicationInferenceProfile>>() {}),
                regionResolver, objectMapper, kmsService);
    }

    BedrockService(StorageBackend<String, Guardrail> guardrails,
                   StorageBackend<String, String> guardrailProfilePolicies,
                   StorageBackend<String, ApplicationInferenceProfile> applicationInferenceProfiles,
                   RegionResolver regionResolver,
                   ObjectMapper objectMapper, KmsService kmsService) {
        this.guardrails = guardrails;
        this.guardrailProfilePolicies = guardrailProfilePolicies;
        this.applicationInferenceProfiles = applicationInferenceProfiles;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
        this.kmsService = kmsService;
    }

    public synchronized Guardrail createGuardrail(JsonNode request, String region) {
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
        String guardrailArn = regionResolver.buildArn("bedrock", region, "guardrail/" + guardrailId);
        Map<String, String> tags = parseTagList(request.get("tags"));
        if (tags.size() > TAGS_PER_RESOURCE_MAX) {
            throw tooManyTags(guardrailArn, tags.size());
        }
        Instant now = Instant.now();

        Guardrail guardrail = new Guardrail();
        guardrail.setGuardrailId(guardrailId);
        guardrail.setGuardrailArn(guardrailArn);
        guardrail.setName(name);
        guardrail.setDescription(description);
        guardrail.setVersion(DRAFT_VERSION);
        guardrail.setBlockedInputMessaging(blockedInputMessaging);
        guardrail.setBlockedOutputsMessaging(blockedOutputsMessaging);
        guardrail.setKmsKeyArn(resolveKmsKeyArn(textOrNull(request, "kmsKeyId"), region));
        guardrail.setCreatedAt(now);
        guardrail.setUpdatedAt(now);
        guardrail.setTags(tags);
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

    public synchronized Guardrail updateGuardrail(String identifier, JsonNode request, String region) {
        String name = requiredName(request);
        String description = boundedDescription(request);
        String blockedInputMessaging = requiredBlockedMessaging(request, "blockedInputMessaging");
        String blockedOutputsMessaging = requiredBlockedMessaging(request, "blockedOutputsMessaging");

        Guardrail guardrail = getGuardrail(identifier, DRAFT_VERSION, region);
        // getGuardrail returns the stored draft, so the key must resolve before any setter runs.
        String kmsKeyArn = resolveKmsKeyArn(textOrNull(request, "kmsKeyId"), region);
        guardrail.setName(name);
        guardrail.setDescription(description);
        guardrail.setBlockedInputMessaging(blockedInputMessaging);
        guardrail.setBlockedOutputsMessaging(blockedOutputsMessaging);
        guardrail.setKmsKeyArn(kmsKeyArn);
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
    public synchronized void deleteGuardrail(String identifier, String version, String region) {
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

    // Foundation models and inference profiles

    public List<BedrockModelCatalog.FoundationModel> listFoundationModels(String byProvider, String byOutputModality,
                                                                         String byInferenceType,
                                                                         String byCustomizationType, String region) {
        requireOneOf("byOutputModality", byOutputModality, OUTPUT_MODALITIES);
        requireOneOf("byInferenceType", byInferenceType, INFERENCE_TYPES);
        requireOneOf("byCustomizationType", byCustomizationType, CUSTOMIZATION_TYPES);
        if (byCustomizationType != null || "PROVISIONED".equals(byInferenceType)) {
            // The catalog holds no customization or provisioned-throughput support.
            return List.of();
        }
        return BedrockModelCatalog.modelsIn(region).stream()
                .filter(model -> byProvider == null || model.providerName().equalsIgnoreCase(byProvider))
                .filter(model -> byOutputModality == null || model.outputModalities().contains(byOutputModality))
                .filter(model -> byInferenceType == null || model.servedInRegion(region))
                .toList();
    }

    public BedrockModelCatalog.FoundationModel getFoundationModel(String modelIdentifier, String region) {
        String modelId = foundationModelIdOf(modelIdentifier, region);
        Optional<BedrockModelCatalog.FoundationModel> served = BedrockModelCatalog.modelsIn(region).stream()
                .filter(model -> model.modelId().equals(modelId))
                .findFirst();
        if (served.isPresent()) {
            return served.get();
        }
        if (BedrockModelCatalog.MODELS.stream().anyMatch(model -> model.modelId().equals(modelId))) {
            throw new AwsException("ValidationException",
                    "The provided model identifier is not valid in this Region.", 400);
        }
        throw invalidModelIdentifier();
    }

    public record InferenceProfileView(String inferenceProfileId, String inferenceProfileArn,
                                       String inferenceProfileName, String description, List<String> models,
                                       Instant createdAt, Instant updatedAt, String type) {
    }

    public synchronized ApplicationInferenceProfile createApplicationInferenceProfile(
            String name, String description, String copyFrom, Map<String, String> tags, String region) {
        return createApplicationInferenceProfile(name, description, copyFrom, tags, null, region);
    }

    public synchronized ApplicationInferenceProfile createApplicationInferenceProfile(
            String name, String description, String copyFrom, Map<String, String> tags,
            String clientRequestToken, String region) {
        if (clientRequestToken != null) {
            if (clientRequestToken.length() > 256 || !IDEMPOTENCY_TOKEN_PATTERN.matcher(clientRequestToken).matches()) {
                throw new AwsException("ValidationException", "clientRequestToken is invalid.", 400);
            }
            Optional<ApplicationInferenceProfile> previous = applicationsIn(region).stream()
                    .filter(profile -> clientRequestToken.equals(profile.getClientRequestToken()))
                    .findFirst();
            if (previous.isPresent()) {
                return previous.get();
            }
        }
        if (name == null || name.length() > 64 || !APPLICATION_PROFILE_NAME_PATTERN.matcher(name).matches()) {
            throw new AwsException("ValidationException", "inferenceProfileName is invalid.", 400);
        }
        if (description != null && (description.length() > 200
                || !APPLICATION_PROFILE_DESCRIPTION_PATTERN.matcher(description).matches())) {
            throw new AwsException("ValidationException", "description is invalid.", 400);
        }
        List<String> models = modelsFromSource(copyFrom, region);
        if (applicationsIn(region).stream().anyMatch(profile -> name.equals(profile.getInferenceProfileName()))) {
            throw new AwsException("ConflictException", "Inference profile " + name + " already exists.", 400);
        }

        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String arn = regionResolver.buildArn("bedrock", region, APPLICATION_INFERENCE_PROFILE_PREFIX + id);
        Map<String, String> initialTags = tags == null ? Map.of() : Map.copyOf(tags);
        if (initialTags.size() > TAGS_PER_RESOURCE_MAX) {
            throw tooManyTags(arn, initialTags.size());
        }
        Instant now = Instant.now();
        ApplicationInferenceProfile profile = new ApplicationInferenceProfile();
        profile.setInferenceProfileId(id);
        profile.setInferenceProfileArn(arn);
        profile.setInferenceProfileName(name);
        profile.setDescription(description);
        profile.setModels(models);
        profile.setCreatedAt(now);
        profile.setUpdatedAt(now);
        profile.setTags(initialTags);
        profile.setAccountId(regionResolver.getAccountId());
        profile.setRegion(region);
        profile.setClientRequestToken(clientRequestToken);
        applicationInferenceProfiles.put(applicationProfileKey(region, id), profile);
        LOG.infov("Created Bedrock application inference profile: {0}", id);
        return profile;
    }

    public ApplicationInferenceProfile getApplicationInferenceProfile(String identifier, String region) {
        String id = applicationProfileIdOf(identifier, region);
        return applicationInferenceProfiles.get(applicationProfileKey(region, id))
                .orElseThrow(() -> profileNotFound(identifier));
    }

    public synchronized void deleteApplicationInferenceProfile(String identifier, String region) {
        ApplicationInferenceProfile profile = getApplicationInferenceProfile(identifier, region);
        applicationInferenceProfiles.delete(applicationProfileKey(region, profile.getInferenceProfileId()));
        LOG.infov("Deleted Bedrock application inference profile: {0}", profile.getInferenceProfileId());
    }

    public PaginatedResult<InferenceProfileView> listInferenceProfiles(String typeEquals,
                                                                       Integer maxResults,
                                                                       String nextToken, String region) {
        requireOneOf("typeEquals", typeEquals, INFERENCE_PROFILE_TYPES);
        List<InferenceProfileView> profiles = new ArrayList<>();
        if (!"APPLICATION".equals(typeEquals)) {
            BedrockModelCatalog.profilesFrom(region).stream()
                    .map(profile -> systemProfileView(profile, region)).forEach(profiles::add);
        }
        if (!"SYSTEM_DEFINED".equals(typeEquals)) {
            applicationsIn(region).stream().map(BedrockService::applicationProfileView).forEach(profiles::add);
        }
        String scope = "bedrock-inference-profiles:" + regionResolver.getAccountId() + ":" + region + ":"
                + (typeEquals == null ? "ALL" : typeEquals);
        return Pagination.paginate(profiles, InferenceProfileView::inferenceProfileId,
                maxResults, nextToken, MAX_PAGE, MAX_PAGE, 2048, "ValidationException", scope, true);
    }

    public InferenceProfileView getInferenceProfile(String inferenceProfileIdentifier, String region) {
        AwsArnUtils.Arn arn = arnOf(inferenceProfileIdentifier);
        if (arn != null && arn.resource().startsWith(APPLICATION_INFERENCE_PROFILE_PREFIX)) {
            return applicationProfileView(getApplicationInferenceProfile(inferenceProfileIdentifier, region));
        }
        if (arn == null) {
            Optional<ApplicationInferenceProfile> application = applicationInferenceProfiles.get(
                    applicationProfileKey(region, inferenceProfileIdentifier));
            if (application.isPresent()) {
                return applicationProfileView(application.get());
            }
        }
        String profileId = inferenceProfileIdOf(inferenceProfileIdentifier, region);
        return BedrockModelCatalog.profilesFrom(region).stream()
                .filter(profile -> profile.inferenceProfileId().equals(profileId))
                .map(profile -> systemProfileView(profile, region))
                .findFirst()
                .orElseThrow(() -> profileNotFound(inferenceProfileIdentifier));
    }

    private List<String> modelsFromSource(String copyFrom, String region) {
        AwsArnUtils.Arn arn = arnOf(copyFrom);
        if (arn == null || !isBedrockArnIn(arn, region)) {
            throw new AwsException("ValidationException", "modelSource.copyFrom must be a Bedrock ARN in this Region.", 400);
        }
        if (arn.resource().startsWith(FOUNDATION_MODEL_PREFIX) && arn.accountId().isEmpty()) {
            String modelId = arn.resource().substring(FOUNDATION_MODEL_PREFIX.length());
            getFoundationModel(copyFrom, region);
            if (BedrockModelCatalog.MODELS.stream().noneMatch(model -> model.modelId().equals(modelId)
                    && model.servedInRegion(region))) {
                throw new AwsException("ValidationException", "The foundation model is not served in this Region.", 400);
            }
            return List.of(copyFrom);
        }
        if (arn.resource().startsWith(INFERENCE_PROFILE_PREFIX)
                && arn.accountId().equals(regionResolver.getAccountId())) {
            String profileId = arn.resource().substring(INFERENCE_PROFILE_PREFIX.length());
            BedrockModelCatalog.InferenceProfile profile = BedrockModelCatalog.profilesFrom(region).stream()
                    .filter(candidate -> candidate.inferenceProfileId().equals(profileId))
                    .findFirst().orElseThrow(() -> profileNotFound(copyFrom));
            return systemProfileModelArns(profile, region);
        }
        throw new AwsException("ValidationException", "modelSource.copyFrom must name a foundation model "
                + "or system-defined inference profile.", 400);
    }

    private InferenceProfileView systemProfileView(BedrockModelCatalog.InferenceProfile profile, String region) {
        return new InferenceProfileView(profile.inferenceProfileId(), regionResolver.buildArn("bedrock", region,
                INFERENCE_PROFILE_PREFIX + profile.inferenceProfileId()), profile.inferenceProfileName(),
                null, systemProfileModelArns(profile, region), profile.createdAt(), profile.createdAt(),
                "SYSTEM_DEFINED");
    }

    private List<String> systemProfileModelArns(BedrockModelCatalog.InferenceProfile profile, String region) {
        return profile.routes().get(region).stream()
                .map(destination -> foundationModelArn(profile.modelId(), destination)).toList();
    }

    public String foundationModelArn(String modelId, String region) {
        return regionResolver.buildArn("bedrock", region, "", FOUNDATION_MODEL_PREFIX + modelId);
    }

    private static InferenceProfileView applicationProfileView(ApplicationInferenceProfile profile) {
        return new InferenceProfileView(profile.getInferenceProfileId(), profile.getInferenceProfileArn(),
                profile.getInferenceProfileName(), profile.getDescription(), profile.getModels(),
                profile.getCreatedAt(), profile.getUpdatedAt(), "APPLICATION");
    }

    private List<ApplicationInferenceProfile> applicationsIn(String region) {
        String prefix = regionResolver.getAccountId() + "::" + region + "::";
        return applicationInferenceProfiles.scan(key -> key.startsWith(prefix));
    }

    private String applicationProfileKey(String region, String id) {
        return regionResolver.getAccountId() + "::" + region + "::" + id;
    }

    private String applicationProfileIdOf(String identifier, String region) {
        AwsArnUtils.Arn arn = arnOf(identifier);
        if (arn == null) {
            return identifier;
        }
        if (!"bedrock".equals(arn.service()) || arn.accountId().isEmpty()) {
            throw new AwsException("ValidationException", "The provided inference profile identifier is invalid.", 400);
        }
        if (!arn.accountId().equals(regionResolver.getAccountId())) {
            throw new AwsException("AccessDeniedException",
                    "You don't have access to the inference profile " + identifier + ".", 403);
        }
        if (!isBedrockArnIn(arn, region) || !arn.resource().startsWith(APPLICATION_INFERENCE_PROFILE_PREFIX)) {
            throw profileNotFound(identifier);
        }
        return arn.resource().substring(APPLICATION_INFERENCE_PROFILE_PREFIX.length());
    }

    private static AwsException profileNotFound(String identifier) {
        return new AwsException("ResourceNotFoundException",
                "The inference profile " + identifier + " was not found.", 404);
    }

    /**
     * The model id an identifier names. AWS answers every identifier it cannot resolve to a model,
     * an unknown id or an ARN of another Region, service or account, with a validation error.
     */
    private String foundationModelIdOf(String identifier, String region) {
        AwsArnUtils.Arn arn = arnOf(identifier);
        if (arn == null) {
            return identifier;
        }
        if (!isBedrockArnIn(arn, region) || !arn.accountId().isEmpty()
                || !arn.resource().startsWith(FOUNDATION_MODEL_PREFIX)) {
            throw invalidModelIdentifier();
        }
        return arn.resource().substring(FOUNDATION_MODEL_PREFIX.length());
    }

    /**
     * The profile id an identifier names. As AWS does, an ARN of another service or without an
     * account is invalid, one of another account is denied, and one of another Region names nothing.
     */
    private String inferenceProfileIdOf(String identifier, String region) {
        AwsArnUtils.Arn arn = arnOf(identifier);
        if (arn == null) {
            return identifier;
        }
        if (!"bedrock".equals(arn.service()) || arn.accountId().isEmpty()) {
            throw new AwsException("ValidationException",
                    "The provided inference profile identifier " + identifier + " is invalid.", 400);
        }
        if (!arn.accountId().equals(regionResolver.getAccountId())) {
            throw new AwsException("AccessDeniedException",
                    "You don't have access to the inference profile " + identifier + ".", 403);
        }
        if (!isBedrockArnIn(arn, region) || !arn.resource().startsWith(INFERENCE_PROFILE_PREFIX)) {
            throw new AwsException("ResourceNotFoundException", "Resource " + identifier + " does not exist.", 404);
        }
        return arn.resource().substring(INFERENCE_PROFILE_PREFIX.length());
    }

    /** The ARN an identifier is, or {@code null} for a plain id. */
    private static AwsArnUtils.Arn arnOf(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new AwsException("ValidationException", "The identifier is required.", 400);
        }
        if (!AwsArnUtils.looksLikeArn(identifier)) {
            return null;
        }
        try {
            return AwsArnUtils.parse(identifier);
        } catch (IllegalArgumentException e) {
            throw new AwsException("ValidationException", "The identifier " + identifier + " is not a valid ARN.", 400);
        }
    }

    private boolean isBedrockArnIn(AwsArnUtils.Arn arn, String region) {
        return "bedrock".equals(arn.service()) && region.equals(arn.region())
                && regionResolver.partitionForRegion(region).equals(arn.partition());
    }

    private static AwsException invalidModelIdentifier() {
        return new AwsException("ValidationException", "The provided model identifier is invalid.", 400);
    }

    private static void requireOneOf(String parameter, String value, List<String> allowed) {
        if (value != null && !allowed.contains(value)) {
            throw SmithyValidationErrors.validationException(SmithyValidationErrors.notInEnum(value, parameter, allowed));
        }
    }

    public synchronized Guardrail createGuardrailVersion(String identifier, String description, String region) {
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

    // Resource policies

    /**
     * The policy attached to a guardrail, or null when it holds none. A guardrail profile that holds
     * none under {@code region} reads as the empty policy {@code {}}.
     */
    public String getResourcePolicy(String resourceArn, String region) {
        requireResourcePolicyArn(resourceArn);
        if (isGuardrailProfileArn(resourceArn)) {
            requireOwnGuardrailProfile(resourceArn);
            return guardrailProfilePolicies.get(profilePolicyKey(region, resourceArn)).orElse(EMPTY_POLICY);
        }
        return findGuardrailForPolicy(resourceArn, region).getResourcePolicy();
    }

    public synchronized void putResourcePolicy(String resourceArn, String policy, String region) {
        requireResourcePolicyArn(resourceArn);
        if (policy == null || policy.isEmpty() || policy.length() > RESOURCE_POLICY_MAX) {
            throw new AwsException("ValidationException",
                    "resourcePolicy must be between 1 and " + RESOURCE_POLICY_MAX + " characters.", 400);
        }
        boolean profile = isGuardrailProfileArn(resourceArn);
        validateResourcePolicy(policy, resourceArn,
                profile ? GUARDRAIL_PROFILE_POLICY_ACTIONS : GUARDRAIL_POLICY_ACTIONS);
        if (profile) {
            requireOwnGuardrailProfile(resourceArn);
            guardrailProfilePolicies.put(profilePolicyKey(region, resourceArn), policy);
            return;
        }
        Guardrail guardrail = findGuardrailForPolicy(resourceArn, region);
        guardrail.setResourcePolicy(policy);
        guardrails.put(storageKey(region, guardrail.getGuardrailId(), DRAFT_VERSION), guardrail);
        LOG.infov("Put resource policy on Bedrock guardrail: {0}", guardrail.getGuardrailId());
    }

    public synchronized void deleteResourcePolicy(String resourceArn, String region) {
        requireResourcePolicyArn(resourceArn);
        if (isGuardrailProfileArn(resourceArn)) {
            requireOwnGuardrailProfile(resourceArn);
            guardrailProfilePolicies.delete(profilePolicyKey(region, resourceArn));
            return;
        }
        Guardrail guardrail = findGuardrailForPolicy(resourceArn, region);
        guardrail.setResourcePolicy(null);
        guardrails.put(storageKey(region, guardrail.getGuardrailId(), DRAFT_VERSION), guardrail);
        LOG.infov("Deleted resource policy of Bedrock guardrail: {0}", guardrail.getGuardrailId());
    }

    // Tags

    public Map<String, String> listTags(String resourceArn, String region) {
        if (isApplicationProfileResource(resourceArn, region)) {
            ApplicationInferenceProfile profile = getApplicationInferenceProfile(resourceArn, region);
            return profile.getTags() != null ? Map.copyOf(profile.getTags()) : Map.of();
        }
        Guardrail guardrail = findByArn(resourceArn, region);
        return guardrail.getTags() != null ? guardrail.getTags() : Map.of();
    }

    /**
     * The 50 tag limit applies to the resource, not to the request, so it is checked against the
     * total the resource is left holding once the incoming tags are merged in. A tag that replaces
     * the value of a key already present does not add to that total.
     */
    public synchronized void tagResource(String resourceArn, Map<String, String> tags, String region) {
        if (isApplicationProfileResource(resourceArn, region)) {
            ApplicationInferenceProfile profile = getApplicationInferenceProfile(resourceArn, region);
            Map<String, String> merged = new HashMap<>(profile.getTags() != null ? profile.getTags() : Map.of());
            merged.putAll(tags);
            if (merged.size() > TAGS_PER_RESOURCE_MAX) {
                throw tooManyTags(profile.getInferenceProfileArn(), merged.size());
            }
            applicationInferenceProfiles.put(applicationProfileKey(region, profile.getInferenceProfileId()),
                    applicationProfileWithTags(profile, merged));
            return;
        }
        Guardrail guardrail = findByArn(resourceArn, region);
        Map<String, String> merged = guardrail.getTags() == null
                ? new HashMap<>()
                : new HashMap<>(guardrail.getTags());
        merged.putAll(tags);
        if (merged.size() > TAGS_PER_RESOURCE_MAX) {
            throw tooManyTags(guardrail.getGuardrailArn(), merged.size());
        }
        guardrail.setTags(merged);
        guardrails.put(storageKey(region, guardrail.getGuardrailId(), DRAFT_VERSION), guardrail);
    }

    public synchronized void untagResource(String resourceArn, List<String> tagKeys, String region) {
        if (isApplicationProfileResource(resourceArn, region)) {
            ApplicationInferenceProfile profile = getApplicationInferenceProfile(resourceArn, region);
            Map<String, String> remaining = new HashMap<>(profile.getTags() != null ? profile.getTags() : Map.of());
            if (tagKeys != null) {
                tagKeys.forEach(remaining::remove);
            }
            applicationInferenceProfiles.put(applicationProfileKey(region, profile.getInferenceProfileId()),
                    applicationProfileWithTags(profile, remaining));
            return;
        }
        Guardrail guardrail = findByArn(resourceArn, region);
        Map<String, String> remaining = new HashMap<>(guardrail.getTags() != null ? guardrail.getTags() : Map.of());
        if (tagKeys != null) {
            tagKeys.forEach(remaining::remove);
        }
        guardrail.setTags(remaining);
        guardrails.put(storageKey(region, guardrail.getGuardrailId(), DRAFT_VERSION), guardrail);
    }

    /**
     * The tags of the guardrail or application inference profile whose ARN is {@code arn} in {@code account}, read in
     * place under the key the tag handlers read, in the ARN's Region, and matched on the whole ARN it records; none
     * when there is no such resource. Tag calls give the resource a new map, so the read is of one whole set.
     */
    Map<String, String> tagsInAccount(String account, String arn) {
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String resource = parsed.resource();
        Optional<Map<String, String>> tags = Optional.empty();
        if (resource.startsWith(GUARDRAIL_RESOURCE_PREFIX)) {
            tags = AccountAwareStorageBackend.readInAccount(guardrails, account,
                            storageKey(parsed.region(), resource.substring(GUARDRAIL_RESOURCE_PREFIX.length()), DRAFT_VERSION))
                    .filter(guardrail -> arn.equals(guardrail.getGuardrailArn())).map(Guardrail::getTags);
        } else if (resource.startsWith(APPLICATION_INFERENCE_PROFILE_PREFIX)) {
            tags = AccountAwareStorageBackend.readInAccount(applicationInferenceProfiles, account,
                            applicationProfileKey(parsed.region(),
                                    resource.substring(APPLICATION_INFERENCE_PROFILE_PREFIX.length())))
                    .filter(profile -> arn.equals(profile.getInferenceProfileArn()))
                    .map(ApplicationInferenceProfile::getTags);
        }
        return tags.orElse(Map.of());
    }

    // Resource discovery (Resource Groups Tagging API, Resource Explorer)

    @Override
    public List<ExplorerResource> getResources() {
        List<ExplorerResource> resources = new ArrayList<>();
        for (String key : guardrails.keys()) {
            // Versions share the draft's ARN, and tags live on the draft, so only the draft is listed.
            if (!key.endsWith("::" + DRAFT_VERSION)) {
                continue;
            }
            guardrails.get(key).ifPresent(guardrail -> resources.add(new ExplorerResource(
                    guardrail.getGuardrailArn(), "bedrock:guardrail", "bedrock",
                    key.substring(0, key.indexOf("::")), guardrail.getAccountId(),
                    guardrail.getCreatedAt() != null ? guardrail.getCreatedAt() : Instant.now(),
                    guardrail.getTags() != null ? guardrail.getTags() : Map.of())));
        }
        for (String key : applicationInferenceProfiles.keys()) {
            applicationInferenceProfiles.get(key).ifPresent(profile -> resources.add(new ExplorerResource(
                    profile.getInferenceProfileArn(), "bedrock:application-inference-profile", "bedrock",
                    profile.getRegion(), profile.getAccountId(), profile.getCreatedAt(),
                    profile.getTags() != null ? profile.getTags() : Map.of())));
        }
        return resources;
    }

    @Override
    public Set<SupportedResourceType> getSupportedResourceTypes() {
        return Set.of(new SupportedResourceType("bedrock:guardrail", "bedrock", true),
                new SupportedResourceType("bedrock:application-inference-profile", "bedrock", true));
    }

    private boolean isApplicationProfileResource(String identifier, String region) {
        if (identifier == null) {
            return false;
        }
        if (!AwsArnUtils.looksLikeArn(identifier)) {
            return applicationInferenceProfiles.get(applicationProfileKey(region, identifier)).isPresent();
        }
        try {
            return AwsArnUtils.parse(identifier).resource().startsWith(APPLICATION_INFERENCE_PROFILE_PREFIX);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static ApplicationInferenceProfile applicationProfileWithTags(ApplicationInferenceProfile source,
                                                                          Map<String, String> tags) {
        ApplicationInferenceProfile updated = new ApplicationInferenceProfile();
        updated.setInferenceProfileId(source.getInferenceProfileId());
        updated.setInferenceProfileArn(source.getInferenceProfileArn());
        updated.setInferenceProfileName(source.getInferenceProfileName());
        updated.setDescription(source.getDescription());
        updated.setModels(source.getModels());
        updated.setCreatedAt(source.getCreatedAt());
        updated.setUpdatedAt(source.getUpdatedAt());
        updated.setTags(Map.copyOf(tags));
        updated.setAccountId(source.getAccountId());
        updated.setRegion(source.getRegion());
        updated.setClientRequestToken(source.getClientRequestToken());
        return updated;
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
        String profileId = AwsArnUtils.looksLikeArn(value)
                ? value.substring(value.lastIndexOf('/') + 1)
                : value;
        ObjectNode details = objectMapper.createObjectNode();
        details.put("guardrailProfileId", profileId);
        details.put("guardrailProfileArn",
                regionResolver.buildArn("bedrock", region, GUARDRAIL_PROFILE_RESOURCE_PREFIX + profileId));
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
                .filter(guardrail -> !AwsArnUtils.looksLikeArn(resourceArn) || resourceArn.equals(guardrail.getGuardrailArn()))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Resource " + resourceArn + " does not exist.", 404));
    }

    private static void requireResourcePolicyArn(String resourceArn) {
        if (resourceArn == null || resourceArn.isEmpty() || resourceArn.length() > RESOURCE_POLICY_ARN_MAX) {
            throw new AwsException("ValidationException",
                    "resourceArn must be between 1 and " + RESOURCE_POLICY_ARN_MAX + " characters.", 400);
        }
    }

    /** The guardrail a resource policy names, found by its exact ARN only, never by a bare id. */
    private Guardrail findGuardrailForPolicy(String resourceArn, String region) {
        if (!AwsArnUtils.isArnFor(resourceArn, "bedrock")
                || !AwsArnUtils.parse(resourceArn).resource().startsWith(GUARDRAIL_RESOURCE_PREFIX)) {
            throw new AwsException("ResourceNotFoundException", "Resource " + resourceArn + " does not exist.", 404);
        }
        return findByArn(resourceArn, region);
    }

    private static boolean isGuardrailProfileArn(String resourceArn) {
        return AwsArnUtils.isArnFor(resourceArn, "bedrock")
                && AwsArnUtils.parse(resourceArn).resource().startsWith(GUARDRAIL_PROFILE_RESOURCE_PREFIX);
    }

    /**
     * A guardrail profile is system-defined, so any well-formed profile ARN in the caller's own account and
     * partition names one. Whether its Region is a destination of the calling Region is not checked.
     */
    private void requireOwnGuardrailProfile(String resourceArn) {
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(resourceArn);
        String profileId = parsed.resource().substring(GUARDRAIL_PROFILE_RESOURCE_PREFIX.length());
        if (parsed.region().isEmpty() || !GUARDRAIL_PROFILE_ID_PATTERN.matcher(profileId).matches()
                || !parsed.partition().equals(regionResolver.getPartition())
                || !parsed.partition().equals(regionResolver.partitionForRegion(parsed.region()))
                || !parsed.accountId().equals(regionResolver.getAccountId())) {
            throw new AwsException("ResourceNotFoundException", "Resource " + resourceArn + " does not exist.", 404);
        }
    }

    private static String profilePolicyKey(String region, String resourceArn) {
        return region + "::" + resourceArn;
    }

    /**
     * Holds a policy to the restricted language the Bedrock user guide gives guardrail resource-based
     * policies: Allow statements on the resource the policy is attached to, granting only the supported
     * actions, either to the account's root or to {@code *} under an {@code aws:PrincipalOrgID} condition,
     * with no condition but that one and {@code aws:PrincipalOrgPaths}.
     */
    private void validateResourcePolicy(String policy, String resourceArn, Set<String> allowedActions) {
        JsonNode document;
        try {
            document = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(policy);
        } catch (JsonProcessingException e) {
            throw invalidResourcePolicy("resourcePolicy is not a JSON policy document.");
        }
        if (document == null || !document.isObject()) {
            throw invalidResourcePolicy("resourcePolicy is not a JSON policy document.");
        }
        List<JsonNode> statements = elements(document.path("Statement"));
        if (statements.isEmpty()) {
            throw invalidResourcePolicy("resourcePolicy has no Statement.");
        }
        String accountRoot = regionResolver.buildGlobalArn("iam", "root");
        for (JsonNode statement : statements) {
            if (!"Allow".equals(statement.path("Effect").asText())) {
                throw invalidResourcePolicy("Every statement must have Effect Allow.");
            }
            List<JsonNode> resources = elements(statement.path("Resource"));
            if (resources.isEmpty()
                    || resources.stream().anyMatch(resource -> !resourceArn.equals(resource.asText()))) {
                throw invalidResourcePolicy("Every statement's Resource must be " + resourceArn + ".");
            }
            List<JsonNode> actions = elements(statement.path("Action"));
            if (actions.isEmpty()
                    || actions.stream().anyMatch(action -> !allowedActions.contains(action.asText()))) {
                throw invalidResourcePolicy("Every statement's Action must be among "
                        + String.join(", ", allowedActions.stream().sorted().toList()) + ".");
            }
            validatePrincipalAndCondition(statement.path("Principal"), statement.path("Condition"), accountRoot);
        }
    }

    private static void validatePrincipalAndCondition(JsonNode principal, JsonNode condition, String accountRoot) {
        if (principal.isTextual() && "*".equals(principal.asText())) {
            List<String> organizations = strings(condition.path("StringEquals").path("aws:PrincipalOrgID"));
            if (organizations == null || organizations.size() != 1 || organizations.getFirst().isBlank()
                    || organizations.getFirst().contains("*")) {
                throw invalidResourcePolicy("A statement with Principal * needs a StringEquals condition on "
                        + "aws:PrincipalOrgID naming one organization.");
            }
        } else {
            List<String> roots = principal.isObject() && principal.size() == 1 ? strings(principal.path("AWS")) : null;
            if (roots == null || roots.stream().anyMatch(root -> !accountRoot.equals(root))) {
                throw invalidResourcePolicy("Principal must be * or {\"AWS\": \"" + accountRoot + "\"}.");
            }
        }
        if (condition.isMissingNode()) {
            return;
        }
        if (!condition.isObject()) {
            throw invalidResourcePolicy("Condition must be an object.");
        }
        for (Map.Entry<String, JsonNode> operator : condition.properties()) {
            String key = switch (operator.getKey()) {
                case "StringEquals" -> "aws:PrincipalOrgID";
                case "ForAnyValue:StringLike" -> "aws:PrincipalOrgPaths";
                default -> null;
            };
            if (key == null || !operator.getValue().isObject() || operator.getValue().size() != 1
                    || strings(operator.getValue().path(key)) == null) {
                throw invalidResourcePolicy("The only supported conditions are StringEquals on aws:PrincipalOrgID "
                        + "and ForAnyValue:StringLike on aws:PrincipalOrgPaths.");
            }
        }
    }

    /** A string or a non-empty array of strings, as a list; null for any other value, or none. */
    private static List<String> strings(JsonNode node) {
        if (node.isTextual()) {
            return List.of(node.asText());
        }
        if (!node.isArray() || node.isEmpty()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isTextual()) {
                return null;
            }
            values.add(value.asText());
        }
        return values;
    }

    /** A policy element that may be one value or an array of them; empty when absent. */
    private static List<JsonNode> elements(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            return List.of(node);
        }
        List<JsonNode> values = new ArrayList<>();
        node.forEach(values::add);
        return values;
    }

    private static AwsException invalidResourcePolicy(String reason) {
        return new AwsException("ValidationException", "Invalid resource policy: " + reason, 400);
    }

    private String resolveGuardrailId(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new AwsException("ValidationException", "guardrailIdentifier is required", 400);
        }
        if (!AwsArnUtils.looksLikeArn(identifier)) {
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

    /**
     * Reads a {@code TagList}. Shared with the controller so that {@code CreateGuardrail} and
     * {@code TagResource}, which both declare that shape, apply the same item cap.
     */
    static Map<String, String> parseTagList(JsonNode tagsNode) {
        Map<String, String> tags = new HashMap<>();
        if (tagsNode == null || !tagsNode.isArray()) {
            return tags;
        }
        validateTagListSize(tagsNode, "ValidationException", "tags");
        for (JsonNode tag : tagsNode) {
            JsonNode key = tag.get("key");
            JsonNode value = tag.get("value");
            if (key != null && !key.isNull() && value != null && !value.isNull()) {
                tags.put(key.asText(), value.asText());
            }
        }
        return tags;
    }

    public static void validateTagListSize(JsonNode tagsNode, String errorCode, String propertyName) {
        if (tagsNode.size() > TAG_LIST_MAX) {
            throw new AwsException(errorCode,
                    propertyName + " must have at most " + TAG_LIST_MAX + " items.", 400);
        }
    }

    private static AwsException tooManyTags(String resourceArn, int total) {
        return new AwsException("TooManyTagsException",
                "Resource " + resourceArn + " would hold " + total + " tags, over the limit of "
                        + TAGS_PER_RESOURCE_MAX + " tags per resource.",
                400, Map.<String, Object>of("resourceName", resourceArn));
    }
}
