package io.github.hectorvent.floci.services.dlm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.dlm.model.LifecyclePolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@ApplicationScoped
public class DlmService implements Resettable, TagHandler {

    private static final Logger LOG = Logger.getLogger(DlmService.class);
    private static final String SERVICE = "dlm";
    private static final Pattern DESCRIPTION = Pattern.compile("[0-9A-Za-z _-]{1,500}");
    private static final Pattern EXECUTION_ROLE_ARN = Pattern.compile(
            "arn:" + AwsArnUtils.PARTITION_REGEX + ":iam::[0-9]+:role/.+");
    private static final Pattern TAG_KEY = Pattern.compile("(?!aws:)[a-zA-Z+\\-=._:/]{1,128}");
    private static final Set<String> STATES = Set.of("ENABLED", "DISABLED");
    private static final Set<String> DEFAULT_POLICY_TYPES = Set.of("VOLUME", "INSTANCE");
    private static final Set<String> DEFAULT_POLICY_FILTERS = Set.of("VOLUME", "INSTANCE", "ALL");
    private static final Set<String> RESOURCE_TYPES = Set.of("VOLUME", "INSTANCE");
    private static final Set<String> POLICY_TYPES = Set.of(
            "EBS_SNAPSHOT_MANAGEMENT", "IMAGE_MANAGEMENT", "EVENT_BASED_POLICY");

    private final AccountAwareStorageBackend<LifecyclePolicy> policies;

    @Inject
    public DlmService(StorageFactory storageFactory) {
        this(storageFactory.create("dlm", "dlm-lifecycle-policies.json",
                new TypeReference<Map<String, LifecyclePolicy>>() {}));
    }

    DlmService(AccountAwareStorageBackend<LifecyclePolicy> policies) {
        this.policies = policies;
    }

    public LifecyclePolicy createLifecyclePolicy(JsonNode request, String region) {
        requireObject(request, "Request payload");
        String executionRoleArn = requiredText(request, "ExecutionRoleArn");
        validateExecutionRoleArn(executionRoleArn);
        String description = requiredText(request, "Description");
        validateDescription(description);
        String state = requiredText(request, "State");
        validateState(state);

        LifecyclePolicy policy = new LifecyclePolicy();
        String policyId = "policy-" + UUID.randomUUID().toString().replace("-", "");
        String now = Instant.now().toString();
        policy.setPolicyId(policyId);
        policy.setExecutionRoleArn(executionRoleArn);
        policy.setDescription(description);
        policy.setState(state);
        policy.setDateCreated(now);
        policy.setDateModified(now);
        policy.setPolicyArn(AwsArnUtils.Arn.of(
                SERVICE, region, policies.accountId(), "policy/" + policyId).toString());
        policy.setPolicyDetails(readPolicyDetails(request.get("PolicyDetails")));
        policy.setTags(readTags(request.get("Tags"), false));
        policy.setDefaultPolicyType(optionalEnum(request, "DefaultPolicy", DEFAULT_POLICY_TYPES));
        policy.setCreateInterval(optionalPositiveInteger(request, "CreateInterval"));
        policy.setRetainInterval(optionalPositiveInteger(request, "RetainInterval"));
        policy.setCopyTags(optionalBoolean(request, "CopyTags"));
        policy.setExtendDeletion(optionalBoolean(request, "ExtendDeletion"));
        policy.setCrossRegionCopyTargets(optionalArray(request, "CrossRegionCopyTargets", 3));
        policy.setExclusions(optionalObject(request, "Exclusions"));
        normalizeDefaultPolicyDetails(policy);

        policies.put(storageKey(region, policyId), policy);
        LOG.infov("Created DLM lifecycle policy: {0}", policyId);
        return policy;
    }

    public LifecyclePolicy getLifecyclePolicy(String policyId, String region) {
        validatePolicyId(policyId);
        return policies.get(storageKey(region, policyId))
                .orElseThrow(() -> notFound(policyId));
    }

    public List<LifecyclePolicy> getLifecyclePolicies(String region,
                                                       List<String> policyIds,
                                                       String state,
                                                       List<String> resourceTypes,
                                                       List<String> targetTags,
                                                       List<String> tagsToAdd,
                                                       String defaultPolicyType) {
        Set<String> ids = normalizedQueryValues(policyIds);
        Set<String> requestedResourceTypes = normalizedQueryValues(resourceTypes);
        Set<String> requestedTargetTags = normalizedQueryValues(targetTags);
        Set<String> requestedTagsToAdd = normalizedQueryValues(tagsToAdd);
        if (state != null) {
            validateGettableState(state);
        }
        requestedResourceTypes.forEach(value -> validateEnum("resourceTypes", value, RESOURCE_TYPES));
        if (requestedResourceTypes.size() > 1) {
            throw invalidRequest("resourceTypes must contain exactly one value.");
        }
        if (defaultPolicyType != null) {
            validateEnum("defaultPolicyType", defaultPolicyType, DEFAULT_POLICY_FILTERS);
        }
        ids.forEach(DlmService::validatePolicyId);

        return policies.scan(key -> key.startsWith(region + "::")).stream()
                .filter(policy -> ids.isEmpty() || ids.contains(policy.getPolicyId()))
                .filter(policy -> state == null || state.equals(policy.getState()))
                .filter(policy -> requestedResourceTypes.isEmpty()
                        || !disjoint(requestedResourceTypes, policyResourceTypes(policy)))
                .filter(policy -> requestedTargetTags.isEmpty()
                        || policyTargetTags(policy).containsAll(requestedTargetTags))
                .filter(policy -> requestedTagsToAdd.isEmpty()
                        || policyTagsToAdd(policy).containsAll(requestedTagsToAdd))
                .filter(policy -> matchesDefaultPolicyType(policy, defaultPolicyType))
                .sorted((left, right) -> left.getPolicyId().compareTo(right.getPolicyId()))
                .toList();
    }

    public LifecyclePolicy updateLifecyclePolicy(String policyId, JsonNode request, String region) {
        requireObject(request, "Request payload");
        LifecyclePolicy current = getLifecyclePolicy(policyId, region);
        LifecyclePolicy policy = new LifecyclePolicy(current);
        boolean changed = false;

        if (request.has("ExecutionRoleArn")) {
            String value = requiredText(request, "ExecutionRoleArn");
            validateExecutionRoleArn(value);
            policy.setExecutionRoleArn(value);
            changed = true;
        }
        if (request.has("Description")) {
            String value = requiredText(request, "Description");
            validateDescription(value);
            policy.setDescription(value);
            changed = true;
        }
        if (request.has("State")) {
            String value = requiredText(request, "State");
            validateState(value);
            policy.setState(value);
            changed = true;
        }
        if (request.has("PolicyDetails")) {
            JsonNode details = readPolicyDetails(request.get("PolicyDetails"));
            validateImmutablePolicyDetails(current.getPolicyDetails(), details);
            policy.setPolicyDetails(details);
            changed = true;
        }
        if (request.has("CreateInterval")) {
            policy.setCreateInterval(optionalPositiveInteger(request, "CreateInterval"));
            changed = true;
        }
        if (request.has("RetainInterval")) {
            policy.setRetainInterval(optionalPositiveInteger(request, "RetainInterval"));
            changed = true;
        }
        if (request.has("CopyTags")) {
            policy.setCopyTags(optionalBoolean(request, "CopyTags"));
            changed = true;
        }
        if (request.has("ExtendDeletion")) {
            policy.setExtendDeletion(optionalBoolean(request, "ExtendDeletion"));
            changed = true;
        }
        if (request.has("CrossRegionCopyTargets")) {
            policy.setCrossRegionCopyTargets(optionalArray(request, "CrossRegionCopyTargets", 3));
            changed = true;
        }
        if (request.has("Exclusions")) {
            policy.setExclusions(optionalObject(request, "Exclusions"));
            changed = true;
        }
        if (changed) {
            normalizeDefaultPolicyDetails(policy);
            policy.setDateModified(Instant.now().toString());
            policies.put(storageKey(region, policyId), policy);
        }
        return changed ? policy : current;
    }

    public void deleteLifecyclePolicy(String policyId, String region) {
        getLifecyclePolicy(policyId, region);
        policies.delete(storageKey(region, policyId));
        LOG.infov("Deleted DLM lifecycle policy: {0}", policyId);
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public String tagsBodyKey() {
        return "Tags";
    }

    @Override
    public boolean strictTagValidation() {
        return true;
    }

    @Override
    public String tagValidationErrorCode() {
        return "InvalidRequestException";
    }

    @Override
    public int tagResourceSuccessStatus() {
        return 200;
    }

    @Override
    public int untagResourceSuccessStatus() {
        return 200;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(policyForArn(region, arn).getTags());
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        validateTags(tags, true);
        LifecyclePolicy policy = policyForArn(region, arn);
        Map<String, String> updated = new LinkedHashMap<>(policy.getTags());
        updated.putAll(tags);
        policy.setTags(updated);
        policies.put(storageKey(region, policy.getPolicyId()), policy);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        if (tagKeys == null || tagKeys.isEmpty()) {
            throw invalidRequest("TagKeys must contain at least one key.");
        }
        tagKeys.forEach(DlmService::validateTagKey);
        LifecyclePolicy policy = policyForArn(region, arn);
        Map<String, String> updated = new LinkedHashMap<>(policy.getTags());
        tagKeys.forEach(updated::remove);
        policy.setTags(updated);
        policies.put(storageKey(region, policy.getPolicyId()), policy);
    }

    @Override
    public void clear() {
        policies.clear();
    }

    private LifecyclePolicy policyForArn(String region, String arn) {
        return policies.scan(key -> key.startsWith(region + "::")).stream()
                .filter(policy -> policy.getPolicyArn().equals(arn))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Lifecycle policy " + arn + " was not found.", 404));
    }

    private static JsonNode readPolicyDetails(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        requireObject(node, "PolicyDetails");
        String policyType = optionalText(node, "PolicyType");
        if (policyType != null) {
            validateEnum("PolicyDetails.PolicyType", policyType, POLICY_TYPES);
        }
        JsonNode resourceTypes = node.get("ResourceTypes");
        if (resourceTypes != null && !resourceTypes.isNull()) {
            if (!resourceTypes.isArray() || resourceTypes.size() != 1
                    || !resourceTypes.get(0).isTextual()) {
                throw invalidRequest("PolicyDetails.ResourceTypes must contain exactly one string value.");
            }
            validateEnum("PolicyDetails.ResourceTypes", resourceTypes.get(0).textValue(), RESOURCE_TYPES);
        }
        return node.deepCopy();
    }

    private static void validateImmutablePolicyDetails(JsonNode existing, JsonNode updated) {
        if (existing == null || updated == null) {
            return;
        }
        for (String field : List.of("PolicyType", "ResourceTypes", "ResourceType")) {
            JsonNode before = existing.get(field);
            JsonNode after = updated.get(field);
            if (before != null && after != null && !before.equals(after)) {
                throw invalidRequest("PolicyDetails." + field + " cannot be changed.");
            }
        }
    }

    private static void normalizeDefaultPolicyDetails(LifecyclePolicy policy) {
        if (policy.getDefaultPolicyType() == null) {
            return;
        }
        ObjectNode details = policy.getPolicyDetails() == null
                ? JsonNodeFactory.instance.objectNode()
                : (ObjectNode) policy.getPolicyDetails().deepCopy();
        details.putIfAbsent("PolicyLanguage", JsonNodeFactory.instance.textNode("SIMPLIFIED"));
        details.putIfAbsent("ResourceType", JsonNodeFactory.instance.textNode(policy.getDefaultPolicyType()));
        putIfPresent(details, "CreateInterval", policy.getCreateInterval());
        putIfPresent(details, "RetainInterval", policy.getRetainInterval());
        putIfPresent(details, "CopyTags", policy.getCopyTags());
        putIfPresent(details, "ExtendDeletion", policy.getExtendDeletion());
        setIfPresent(details, "CrossRegionCopyTargets", policy.getCrossRegionCopyTargets());
        setIfPresent(details, "Exclusions", policy.getExclusions());
        policy.setPolicyDetails(details);
    }

    private static void putIfPresent(ObjectNode node, String field, Object value) {
        if (value instanceof Integer integer) {
            node.put(field, integer);
        } else if (value instanceof Boolean bool) {
            node.put(field, bool);
        }
    }

    private static void setIfPresent(ObjectNode node, String field, JsonNode value) {
        if (value != null) {
            node.set(field, value.deepCopy());
        }
    }

    private static Map<String, String> readTags(JsonNode node, boolean required) {
        if (node == null || node.isNull()) {
            if (required) {
                throw invalidRequest("Tags is required.");
            }
            return new LinkedHashMap<>();
        }
        if (!node.isObject()) {
            throw invalidRequest("Tags must be a map of string keys to string values.");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) {
                throw invalidRequest("Tag values must be strings.");
            }
            tags.put(entry.getKey(), entry.getValue().textValue());
        });
        validateTags(tags, required);
        return tags;
    }

    private static void validateTags(Map<String, String> tags, boolean requireOne) {
        if (tags == null || (requireOne && tags.isEmpty())) {
            throw invalidRequest("Tags must contain at least one entry.");
        }
        if (tags.size() > 200) {
            throw invalidRequest("Tags must contain no more than 200 entries.");
        }
        tags.forEach((key, value) -> {
            validateTagKey(key);
            if (value == null || value.length() > 256) {
                throw invalidRequest("Tag values must contain no more than 256 characters.");
            }
        });
    }

    private static void validateTagKey(String key) {
        if (key == null || !TAG_KEY.matcher(key).matches()) {
            throw invalidRequest("Tag keys must be 1-128 valid characters and must not start with aws:.");
        }
    }

    private static JsonNode optionalObject(JsonNode request, String field) {
        JsonNode node = request.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        requireObject(node, field);
        return node.deepCopy();
    }

    private static JsonNode optionalArray(JsonNode request, String field, int maxSize) {
        JsonNode node = request.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray() || node.size() > maxSize) {
            throw invalidRequest(field + " must be an array with no more than " + maxSize + " entries.");
        }
        return node.deepCopy();
    }

    private static Integer optionalPositiveInteger(JsonNode request, String field) {
        JsonNode node = request.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < 1) {
            throw invalidRequest(field + " must be a positive integer.");
        }
        return node.intValue();
    }

    private static Boolean optionalBoolean(JsonNode request, String field) {
        JsonNode node = request.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isBoolean()) {
            throw invalidRequest(field + " must be a boolean.");
        }
        return node.booleanValue();
    }

    private static String optionalEnum(JsonNode request, String field, Set<String> values) {
        String value = optionalText(request, field);
        if (value != null) {
            validateEnum(field, value, values);
        }
        return value;
    }

    private static String requiredText(JsonNode request, String field) {
        String value = optionalText(request, field);
        if (value == null) {
            throw invalidRequest(field + " is required.");
        }
        return value;
    }

    private static String optionalText(JsonNode request, String field) {
        JsonNode node = request == null ? null : request.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw invalidRequest(field + " must be a string.");
        }
        return node.textValue();
    }

    private static void validateDescription(String description) {
        if (!DESCRIPTION.matcher(description).matches()) {
            throw invalidRequest("Description must be 1-500 letters, digits, spaces, underscores, or hyphens.");
        }
    }

    private static void validateExecutionRoleArn(String executionRoleArn) {
        if (executionRoleArn.length() > 2048 || !EXECUTION_ROLE_ARN.matcher(executionRoleArn).matches()) {
            throw invalidRequest("ExecutionRoleArn must be a valid IAM role ARN.");
        }
    }

    private static void validateState(String state) {
        validateEnum("State", state, STATES);
    }

    private static void validateGettableState(String state) {
        if (!Set.of("ENABLED", "DISABLED", "ERROR").contains(state)) {
            throw invalidRequest("state contains an invalid value.");
        }
    }

    private static void validateEnum(String field, String value, Set<String> allowed) {
        if (!allowed.contains(value)) {
            throw invalidRequest(field + " contains an invalid value: " + value + ".");
        }
    }

    private static void validatePolicyId(String policyId) {
        if (policyId == null || policyId.length() > 64 || !policyId.matches("policy-[a-f0-9]+")) {
            throw invalidRequest("PolicyId must match policy-[a-f0-9]+.");
        }
    }

    private static void requireObject(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            throw invalidRequest(field + " must be a JSON object.");
        }
    }

    private static boolean matchesDefaultPolicyType(LifecyclePolicy policy, String filter) {
        if (filter == null) {
            return true;
        }
        if ("ALL".equals(filter)) {
            return policy.getDefaultPolicyType() != null;
        }
        return filter.equals(policy.getDefaultPolicyType());
    }

    private static Set<String> policyResourceTypes(LifecyclePolicy policy) {
        JsonNode details = policy.getPolicyDetails();
        Set<String> values = new LinkedHashSet<>();
        if (details != null) {
            JsonNode list = details.get("ResourceTypes");
            if (list != null && list.isArray()) {
                list.forEach(node -> {
                    if (node.isTextual()) {
                        values.add(node.textValue());
                    }
                });
            }
            JsonNode single = details.get("ResourceType");
            if (single != null && single.isTextual()) {
                values.add(single.textValue());
            }
        }
        if (policy.getDefaultPolicyType() != null) {
            values.add(policy.getDefaultPolicyType());
        }
        return values;
    }

    private static Set<String> policyTargetTags(LifecyclePolicy policy) {
        return tagPairs(policy.getPolicyDetails(), "TargetTags");
    }

    private static Set<String> policyTagsToAdd(LifecyclePolicy policy) {
        Set<String> values = new LinkedHashSet<>();
        JsonNode details = policy.getPolicyDetails();
        JsonNode schedules = details == null ? null : details.get("Schedules");
        if (schedules != null && schedules.isArray()) {
            schedules.forEach(schedule -> values.addAll(tagPairs(schedule, "TagsToAdd")));
        }
        return values;
    }

    private static Set<String> tagPairs(JsonNode parent, String field) {
        Set<String> values = new LinkedHashSet<>();
        JsonNode tags = parent == null ? null : parent.get(field);
        if (tags != null && tags.isArray()) {
            for (JsonNode tag : tags) {
                JsonNode key = tag.get("Key");
                JsonNode value = tag.get("Value");
                if (key != null && key.isTextual() && value != null && value.isTextual()) {
                    values.add(key.textValue() + "=" + value.textValue());
                }
            }
        }
        return values;
    }

    private static Set<String> normalizedQueryValues(List<String> values) {
        Set<String> normalized = new LinkedHashSet<>();
        if (values == null) {
            return normalized;
        }
        for (String value : values) {
            if (value == null) {
                continue;
            }
            for (String part : value.split(",")) {
                if (!part.isBlank()) {
                    normalized.add(part);
                }
            }
        }
        return normalized;
    }

    private static boolean disjoint(Set<String> left, Set<String> right) {
        for (String value : left) {
            if (right.contains(value)) {
                return false;
            }
        }
        return true;
    }

    private static String storageKey(String region, String policyId) {
        return region + "::" + policyId;
    }

    private static AwsException notFound(String policyId) {
        return new AwsException("ResourceNotFoundException",
                "Lifecycle policy " + policyId + " was not found.", 404);
    }

    private static AwsException invalidRequest(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }
}
