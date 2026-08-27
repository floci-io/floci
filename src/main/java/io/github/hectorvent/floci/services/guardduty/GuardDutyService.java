package io.github.hectorvent.floci.services.guardduty;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.guardduty.model.AdminAccount;
import io.github.hectorvent.floci.services.guardduty.model.Detector;
import io.github.hectorvent.floci.services.guardduty.model.DetectorAdditionalConfiguration;
import io.github.hectorvent.floci.services.guardduty.model.DetectorFeature;
import io.github.hectorvent.floci.services.guardduty.model.OrganizationAdditionalConfiguration;
import io.github.hectorvent.floci.services.guardduty.model.OrganizationConfiguration;
import io.github.hectorvent.floci.services.guardduty.model.OrganizationFeature;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * GuardDuty detector lifecycle and organization configuration backed by the configured
 * Floci storage mode.
 *
 * <p>Organization semantics are readback-only: Floci has no Organizations service, so
 * {@code adminAccountId} membership is not validated, delegated-administrator permissions are
 * not enforced, and member accounts are not fanned out. Organization configuration is stored
 * per calling account and echoed back as submitted.
 */
@ApplicationScoped
public class GuardDutyService {

    /**
     * The Terraform AWS provider matches this exact message to translate a
     * {@code BadRequestException} into resource removal from state.
     */
    static final String DETECTOR_NOT_FOUND_MESSAGE =
            "The request is rejected because the input detectorId is not owned by the current account.";

    /** Also matched verbatim by the Terraform AWS provider on delegated-admin delete. */
    static final String ADMIN_ALREADY_DISABLED_MESSAGE =
            "The request failed because the delegated administrator account has already been disabled "
                    + "and/or GuardDuty protection has been disabled.";

    private static final int DEFAULT_MAX_RESULTS = 50;
    private static final int MAX_RESULTS = 50;
    private static final String TOKEN_PREFIX = "guardduty:v1:";
    private static final Pattern ACCOUNT_ID_PATTERN = Pattern.compile("[0-9]{12}");
    private static final Set<String> FINDING_PUBLISHING_FREQUENCIES =
            Set.of("FIFTEEN_MINUTES", "ONE_HOUR", "SIX_HOURS");
    private static final Set<String> FEATURE_STATUSES = Set.of("ENABLED", "DISABLED");
    private static final Set<String> ORG_AUTO_ENABLE_VALUES = Set.of("NEW", "NONE", "ALL");
    private static final Set<String> DETECTOR_FEATURE_NAMES = Set.of(
            "S3_DATA_EVENTS", "EKS_AUDIT_LOGS", "EBS_MALWARE_PROTECTION", "RDS_LOGIN_EVENTS",
            "LAMBDA_NETWORK_LOGS", "EKS_RUNTIME_MONITORING", "RUNTIME_MONITORING",
            "AI_PROTECTION", "AI_ANALYST");
    private static final Set<String> ORG_FEATURE_NAMES = Set.of(
            "S3_DATA_EVENTS", "EKS_AUDIT_LOGS", "EBS_MALWARE_PROTECTION", "RDS_LOGIN_EVENTS",
            "LAMBDA_NETWORK_LOGS", "EKS_RUNTIME_MONITORING", "RUNTIME_MONITORING", "AI_PROTECTION");
    private static final Set<String> ADDITIONAL_CONFIGURATION_NAMES =
            Set.of("EKS_ADDON_MANAGEMENT", "ECS_FARGATE_AGENT_MANAGEMENT", "EC2_AGENT_MANAGEMENT");

    private final StorageBackend<String, Detector> detectorStore;
    private final StorageBackend<String, AdminAccount> adminAccountStore;

    @Inject
    public GuardDutyService(StorageFactory storageFactory) {
        this(storageFactory.create(
                        "guardduty",
                        "guardduty-detectors.json",
                        new TypeReference<Map<String, Detector>>() {
                        }),
                storageFactory.create(
                        "guardduty",
                        "guardduty-admin-accounts.json",
                        new TypeReference<Map<String, AdminAccount>>() {
                        }));
    }

    GuardDutyService(
            StorageBackend<String, Detector> detectorStore,
            StorageBackend<String, AdminAccount> adminAccountStore) {
        this.detectorStore = detectorStore;
        this.adminAccountStore = adminAccountStore;
    }

    public synchronized Detector createDetector(String region, String accountId, JsonNode request) {
        boolean enable = requireBoolean(request, "enable");
        String frequency = readFindingPublishingFrequency(request, "SIX_HOURS");
        List<DetectorFeature> features = readDetectorFeatures(request);
        Map<String, String> tags = readTags(request);

        if (!detectorStore.scan(key -> key.startsWith(region + "::")).isEmpty()) {
            throw badRequest("The request is rejected because a detector already exists for the current account.");
        }

        String now = isoTimestamp();
        Detector detector = new Detector(
                UUID.randomUUID().toString().replace("-", ""),
                enable ? "ENABLED" : "DISABLED",
                frequency,
                serviceRoleArn(accountId),
                now,
                now,
                tags,
                features,
                null);
        detectorStore.put(storageKey(region, detector.getId()), detector);
        return detector;
    }

    public Detector getDetector(String region, String detectorId) {
        return detectorStore.get(storageKey(region, detectorId)).orElseThrow(GuardDutyService::detectorNotFound);
    }

    public synchronized void updateDetector(String region, String detectorId, JsonNode request) {
        String key = storageKey(region, detectorId);
        Detector detector = detectorStore.get(key).orElseThrow(GuardDutyService::detectorNotFound);

        if (request.has("enable")) {
            detector.setStatus(requireBoolean(request, "enable") ? "ENABLED" : "DISABLED");
        }
        if (request.has("findingPublishingFrequency")) {
            detector.setFindingPublishingFrequency(readFindingPublishingFrequency(request, null));
        }
        if (request.has("features")) {
            detector.setFeatures(mergeDetectorFeatures(detector.getFeatures(), readDetectorFeatures(request)));
        }
        detector.setUpdatedAt(isoTimestamp());
        detectorStore.put(key, detector);
    }

    public synchronized void deleteDetector(String region, String detectorId) {
        String key = storageKey(region, detectorId);
        if (detectorStore.get(key).isEmpty()) {
            throw detectorNotFound();
        }
        detectorStore.delete(key);
    }

    public Page<String> listDetectorIds(String region, String maxResultsValue, String nextToken) {
        int maxResults = parseMaxResults(maxResultsValue);
        List<Detector> detectors = detectorStore.scan(key -> key.startsWith(region + "::"));
        detectors.sort(Comparator.comparing(Detector::getId));
        List<String> ids = detectors.stream().map(Detector::getId).toList();

        int offset = decodeOffset(nextToken, ids.size());
        int end = Math.min(offset + maxResults, ids.size());
        String responseToken = end < ids.size() ? encodeOffset(end) : null;
        return new Page<>(ids.subList(offset, end), responseToken);
    }

    public OrganizationConfiguration describeOrganizationConfiguration(String region, String detectorId) {
        Detector detector = getDetector(region, detectorId);
        OrganizationConfiguration configuration = detector.getOrganizationConfiguration();
        if (configuration == null) {
            return new OrganizationConfiguration(false, "NONE", List.of());
        }
        return configuration;
    }

    public synchronized void updateOrganizationConfiguration(String region, String detectorId, JsonNode request) {
        String key = storageKey(region, detectorId);
        Detector detector = detectorStore.get(key).orElseThrow(GuardDutyService::detectorNotFound);

        OrganizationConfiguration current = detector.getOrganizationConfiguration();
        boolean autoEnable = current != null && Boolean.TRUE.equals(current.getAutoEnable());
        String members = current == null ? "NONE" : current.getAutoEnableOrganizationMembers();
        List<OrganizationFeature> features = current == null ? List.of() : current.getFeatures();

        if (request.has("autoEnableOrganizationMembers")) {
            members = requireText(request, "autoEnableOrganizationMembers");
            if (!ORG_AUTO_ENABLE_VALUES.contains(members)) {
                throw badRequest("autoEnableOrganizationMembers must be one of NEW, ALL, or NONE.");
            }
            autoEnable = !"NONE".equals(members);
        } else if (request.has("autoEnable")) {
            autoEnable = requireBoolean(request, "autoEnable");
            members = autoEnable ? "NEW" : "NONE";
        }
        if (request.has("features")) {
            features = mergeOrganizationFeatures(features, readOrganizationFeatures(request));
        }

        detector.setOrganizationConfiguration(new OrganizationConfiguration(autoEnable, members, features));
        detectorStore.put(key, detector);
    }

    public synchronized void enableOrganizationAdminAccount(String region, JsonNode request) {
        String adminAccountId = readAdminAccountId(request);
        List<AdminAccount> existing = adminAccountStore.scan(key -> key.startsWith(region + "::"));
        if (!existing.isEmpty() && !existing.get(0).getAdminAccountId().equals(adminAccountId)) {
            throw badRequest("The request is rejected because the organization already has a "
                    + "delegated administrator account for GuardDuty.");
        }
        adminAccountStore.put(storageKey(region, adminAccountId), new AdminAccount(adminAccountId, "ENABLED"));
    }

    public synchronized void disableOrganizationAdminAccount(String region, JsonNode request) {
        String adminAccountId = readAdminAccountId(request);
        String key = storageKey(region, adminAccountId);
        if (adminAccountStore.get(key).isEmpty()) {
            throw badRequest(ADMIN_ALREADY_DISABLED_MESSAGE);
        }
        adminAccountStore.delete(key);
    }

    public Page<AdminAccount> listOrganizationAdminAccounts(
            String region, String maxResultsValue, String nextToken) {
        int maxResults = parseMaxResults(maxResultsValue);
        List<AdminAccount> accounts = adminAccountStore.scan(key -> key.startsWith(region + "::"));
        accounts.sort(Comparator.comparing(AdminAccount::getAdminAccountId));

        int offset = decodeOffset(nextToken, accounts.size());
        int end = Math.min(offset + maxResults, accounts.size());
        String responseToken = end < accounts.size() ? encodeOffset(end) : null;
        return new Page<>(accounts.subList(offset, end), responseToken);
    }

    public Map<String, String> listTags(String arn) {
        Detector detector = detectorFromArn(arn);
        return detector.getTags() == null ? Map.of() : detector.getTags();
    }

    public synchronized void tagResource(String arn, Map<String, String> tags) {
        DetectorRef ref = parseDetectorArn(arn);
        Detector detector = detectorStore.get(ref.key()).orElseThrow(GuardDutyService::detectorNotFound);
        Map<String, String> merged = new LinkedHashMap<>();
        if (detector.getTags() != null) {
            merged.putAll(detector.getTags());
        }
        merged.putAll(tags);
        detector.setTags(merged);
        detectorStore.put(ref.key(), detector);
    }

    public synchronized void untagResource(String arn, List<String> tagKeys) {
        DetectorRef ref = parseDetectorArn(arn);
        Detector detector = detectorStore.get(ref.key()).orElseThrow(GuardDutyService::detectorNotFound);
        if (detector.getTags() == null) {
            return;
        }
        Map<String, String> remaining = new LinkedHashMap<>(detector.getTags());
        tagKeys.forEach(remaining::remove);
        detector.setTags(remaining);
        detectorStore.put(ref.key(), detector);
    }

    private Detector detectorFromArn(String arn) {
        DetectorRef ref = parseDetectorArn(arn);
        return detectorStore.get(ref.key()).orElseThrow(GuardDutyService::detectorNotFound);
    }

    private static DetectorRef parseDetectorArn(String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw badRequest("The request is rejected because an invalid resource ARN is specified: " + arn);
        }
        String prefix = "detector/";
        String resource = parsed.resource();
        if (!resource.startsWith(prefix) || resource.length() == prefix.length()) {
            throw badRequest("The request is rejected because an invalid resource ARN is specified: " + arn);
        }
        return new DetectorRef(parsed.region(), resource.substring(prefix.length()));
    }

    private static List<DetectorFeature> mergeDetectorFeatures(
            List<DetectorFeature> current, List<DetectorFeature> submitted) {
        List<DetectorFeature> merged = current == null ? new ArrayList<>() : new ArrayList<>(current);
        for (DetectorFeature update : submitted) {
            DetectorFeature existing = merged.stream()
                    .filter(feature -> feature.getName().equals(update.getName()))
                    .findFirst()
                    .orElse(null);
            if (existing == null) {
                merged.add(update);
            } else {
                existing.setStatus(update.getStatus());
                existing.setUpdatedAt(update.getUpdatedAt());
                if (update.getAdditionalConfiguration() != null) {
                    existing.setAdditionalConfiguration(update.getAdditionalConfiguration());
                }
            }
        }
        return merged;
    }

    private static List<OrganizationFeature> mergeOrganizationFeatures(
            List<OrganizationFeature> current, List<OrganizationFeature> submitted) {
        List<OrganizationFeature> merged = current == null ? new ArrayList<>() : new ArrayList<>(current);
        for (OrganizationFeature update : submitted) {
            OrganizationFeature existing = merged.stream()
                    .filter(feature -> feature.getName().equals(update.getName()))
                    .findFirst()
                    .orElse(null);
            if (existing == null) {
                merged.add(update);
            } else {
                existing.setAutoEnable(update.getAutoEnable());
                if (update.getAdditionalConfiguration() != null) {
                    existing.setAdditionalConfiguration(update.getAdditionalConfiguration());
                }
            }
        }
        return merged;
    }

    private static List<DetectorFeature> readDetectorFeatures(JsonNode request) {
        if (!request.has("features")) {
            return null;
        }
        JsonNode featuresNode = request.get("features");
        if (!featuresNode.isArray()) {
            throw badRequest("features must be an array.");
        }
        long now = Instant.now().getEpochSecond();
        List<DetectorFeature> features = new ArrayList<>(featuresNode.size());
        for (JsonNode featureNode : featuresNode) {
            requireObject(featureNode, "features member");
            String name = requireText(featureNode, "name");
            if (!DETECTOR_FEATURE_NAMES.contains(name)) {
                throw badRequest("features contains an unsupported feature name: " + name);
            }
            String status = requireText(featureNode, "status");
            if (!FEATURE_STATUSES.contains(status)) {
                throw badRequest("features contains an invalid status: " + status);
            }
            features.add(new DetectorFeature(
                    name, status, now, readDetectorAdditionalConfiguration(featureNode, now)));
        }
        return features;
    }

    private static List<DetectorAdditionalConfiguration> readDetectorAdditionalConfiguration(
            JsonNode featureNode, long now) {
        if (!featureNode.has("additionalConfiguration")) {
            return null;
        }
        JsonNode configurationNode = featureNode.get("additionalConfiguration");
        if (!configurationNode.isArray()) {
            throw badRequest("additionalConfiguration must be an array.");
        }
        List<DetectorAdditionalConfiguration> configurations = new ArrayList<>(configurationNode.size());
        for (JsonNode node : configurationNode) {
            requireObject(node, "additionalConfiguration member");
            String name = requireText(node, "name");
            if (!ADDITIONAL_CONFIGURATION_NAMES.contains(name)) {
                throw badRequest("additionalConfiguration contains an unsupported name: " + name);
            }
            String status = requireText(node, "status");
            if (!FEATURE_STATUSES.contains(status)) {
                throw badRequest("additionalConfiguration contains an invalid status: " + status);
            }
            configurations.add(new DetectorAdditionalConfiguration(name, status, now));
        }
        return configurations;
    }

    private static List<OrganizationFeature> readOrganizationFeatures(JsonNode request) {
        JsonNode featuresNode = request.get("features");
        if (!featuresNode.isArray()) {
            throw badRequest("features must be an array.");
        }
        List<OrganizationFeature> features = new ArrayList<>(featuresNode.size());
        for (JsonNode featureNode : featuresNode) {
            requireObject(featureNode, "features member");
            String name = requireText(featureNode, "name");
            if (!ORG_FEATURE_NAMES.contains(name)) {
                throw badRequest("features contains an unsupported feature name: " + name);
            }
            String autoEnable = requireText(featureNode, "autoEnable");
            if (!ORG_AUTO_ENABLE_VALUES.contains(autoEnable)) {
                throw badRequest("features contains an invalid autoEnable value: " + autoEnable);
            }
            features.add(new OrganizationFeature(
                    name, autoEnable, readOrganizationAdditionalConfiguration(featureNode)));
        }
        return features;
    }

    private static List<OrganizationAdditionalConfiguration> readOrganizationAdditionalConfiguration(
            JsonNode featureNode) {
        if (!featureNode.has("additionalConfiguration")) {
            return null;
        }
        JsonNode configurationNode = featureNode.get("additionalConfiguration");
        if (!configurationNode.isArray()) {
            throw badRequest("additionalConfiguration must be an array.");
        }
        List<OrganizationAdditionalConfiguration> configurations = new ArrayList<>(configurationNode.size());
        for (JsonNode node : configurationNode) {
            requireObject(node, "additionalConfiguration member");
            String name = requireText(node, "name");
            if (!ADDITIONAL_CONFIGURATION_NAMES.contains(name)) {
                throw badRequest("additionalConfiguration contains an unsupported name: " + name);
            }
            String autoEnable = requireText(node, "autoEnable");
            if (!ORG_AUTO_ENABLE_VALUES.contains(autoEnable)) {
                throw badRequest("additionalConfiguration contains an invalid autoEnable value: " + autoEnable);
            }
            configurations.add(new OrganizationAdditionalConfiguration(name, autoEnable));
        }
        return configurations;
    }

    private static String readFindingPublishingFrequency(JsonNode request, String defaultValue) {
        if (!request.has("findingPublishingFrequency")) {
            return defaultValue;
        }
        String frequency = requireText(request, "findingPublishingFrequency");
        if (!FINDING_PUBLISHING_FREQUENCIES.contains(frequency)) {
            throw badRequest("findingPublishingFrequency must be one of FIFTEEN_MINUTES, ONE_HOUR, or SIX_HOURS.");
        }
        return frequency;
    }

    private static Map<String, String> readTags(JsonNode request) {
        if (!request.has("tags")) {
            return null;
        }
        JsonNode tagsNode = request.get("tags");
        if (!tagsNode.isObject() || tagsNode.size() > 50) {
            throw badRequest("tags must be an object with at most 50 entries.");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        tagsNode.fields().forEachRemaining(entry -> {
            JsonNode valueNode = entry.getValue();
            if (!valueNode.isTextual()) {
                throw badRequest("tags contains a non-string value.");
            }
            tags.put(entry.getKey(), valueNode.textValue());
        });
        return tags;
    }

    private static String readAdminAccountId(JsonNode request) {
        String adminAccountId = requireText(request, "adminAccountId");
        if (!ACCOUNT_ID_PATTERN.matcher(adminAccountId).matches()) {
            throw badRequest("adminAccountId must be a 12-digit account ID.");
        }
        return adminAccountId;
    }

    private static String storageKey(String region, String id) {
        return region + "::" + id;
    }

    private static String serviceRoleArn(String accountId) {
        return "arn:aws:iam::" + accountId
                + ":role/aws-service-role/guardduty.amazonaws.com/AWSServiceRoleForAmazonGuardDuty";
    }

    private static String isoTimestamp() {
        return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString();
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw badRequest(field + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual()) {
            throw badRequest(field + " must be a string.");
        }
        return value.textValue();
    }

    private static boolean requireBoolean(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isBoolean()) {
            throw badRequest(field + " must be a boolean.");
        }
        return value.booleanValue();
    }

    private static int parseMaxResults(String value) {
        if (value == null) {
            return DEFAULT_MAX_RESULTS;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > MAX_RESULTS) {
                throw badRequest("maxResults must be between 1 and " + MAX_RESULTS + ".");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw badRequest("maxResults must be an integer between 1 and " + MAX_RESULTS + ".");
        }
    }

    private static int decodeOffset(String token, int resultSize) {
        if (token == null) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            if (!decoded.startsWith(TOKEN_PREFIX)) {
                throw badRequest("nextToken is invalid.");
            }
            int offset = Integer.parseInt(decoded.substring(TOKEN_PREFIX.length()));
            if (offset < 1 || offset >= resultSize) {
                throw badRequest("nextToken is invalid.");
            }
            return offset;
        } catch (IllegalArgumentException e) {
            throw badRequest("nextToken is invalid.");
        }
    }

    private static String encodeOffset(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((TOKEN_PREFIX + offset).getBytes(StandardCharsets.UTF_8));
    }

    private static AwsException detectorNotFound() {
        return badRequest(DETECTOR_NOT_FOUND_MESSAGE);
    }

    private static AwsException badRequest(String message) {
        return new AwsException("BadRequestException", message, 400);
    }

    public record Page<T>(List<T> items, String nextToken) {
        public Page {
            items = List.copyOf(items);
        }
    }

    private record DetectorRef(String region, String detectorId) {
        String key() {
            return storageKey(region, detectorId);
        }
    }
}
