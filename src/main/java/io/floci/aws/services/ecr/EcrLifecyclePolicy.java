package io.floci.aws.services.ecr;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.floci.aws.core.common.AwsException;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * A lifecycle policy document as {@code PutLifecyclePolicy} takes it, checked in the stages ECR's refusals show: the
 * JSON is parsed, checked against a JSON schema whose validator's own text ECR quotes, mapped onto ECR's rule types,
 * and then checked against the rules the ECR user guide states. Values are matched exactly as the guide spells them.
 *
 * @see <a href="https://docs.aws.amazon.com/AmazonECR/latest/userguide/lifecycle_policy_parameters.html">Lifecycle
 *     policy properties</a>
 * @see <a href="https://docs.aws.amazon.com/AmazonECR/latest/userguide/LifecyclePolicies.html">Lifecycle policy
 *     evaluation rules</a>
 */
public record EcrLifecyclePolicy(List<Rule> rules) {

    /** Which images a rule's selection looks at by their tags. */
    public enum TagStatus {
        TAGGED("tagged"), UNTAGGED("untagged"), ANY("any");

        private final String wireName;

        TagStatus(String wireName) {
            this.wireName = wireName;
        }
    }

    /** The storage class a rule selects images of. */
    public enum StorageClass {
        STANDARD("standard"), ARCHIVE("archive");

        private final String wireName;

        StorageClass(String wireName) {
            this.wireName = wireName;
        }
    }

    /** What a rule counts against {@code countNumber}. */
    public enum CountType {
        IMAGE_COUNT_MORE_THAN("imageCountMoreThan"),
        SINCE_IMAGE_PUSHED("sinceImagePushed"),
        SINCE_IMAGE_PULLED("sinceImagePulled"),
        SINCE_IMAGE_TRANSITIONED("sinceImageTransitioned");

        private final String wireName;

        CountType(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    /** What a rule does to the images it selects. */
    public enum ActionType { EXPIRE, TRANSITION }

    /**
     * One rule. {@code tagPatterns} and {@code tagPrefixes} are empty unless the rule selects tagged images, and at
     * most one of them is not.
     */
    public record Rule(int priority, TagStatus tagStatus, List<String> tagPatterns, List<String> tagPrefixes,
                       StorageClass storageClass, CountType countType, int countNumber, ActionType action) {}

    private static final ObjectReader SINGLE_JSON_VALUE =
            new ObjectMapper().reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final int MAX_RULES = 50;
    private static final int MAX_TAG_PREFIXES = 10;
    private static final int MAX_PATTERN_WILDCARDS = 4;
    /** "Images must be in archive storage for a minimum of 90 days before deletion." */
    private static final int MIN_ARCHIVE_DAYS = 90;
    private static final Set<String> SELECTION_MEMBERS = Set.of("tagStatus", "tagPatternList", "tagPrefixList",
            "storageClass", "countType", "countUnit", "countNumber");
    /** The alternatives of the selection and action schemas, which ECR counts in its oneOf message. */
    private static final int SELECTION_SCHEMAS = CountType.values().length;
    private static final int ACTION_SCHEMAS = ActionType.values().length;

    /** Parses and checks {@code text}; a document ECR would refuse is refused with ECR's message. */
    public static EcrLifecyclePolicy parse(String text) {
        JsonNode policy;
        try {
            policy = text == null ? null : SINGLE_JSON_VALUE.readTree(text);
        } catch (JsonProcessingException e) {
            throw parsingFailure();
        }
        if (policy == null || policy.isMissingNode()) {
            throw parsingFailure();
        }
        checkSchema(policy);
        List<Rule> rules = new ArrayList<>();
        for (JsonNode rule : policy.get("rules")) {
            rules.add(map(rule));
        }
        checkRules(rules);
        return new EcrLifecyclePolicy(List.copyOf(rules));
    }

    // ------------------------------------------------------------
    // The JSON schema: ECR quotes its validator, newline included
    // ------------------------------------------------------------

    private static void checkSchema(JsonNode policy) {
        requireType(policy, "object");
        requireMembers(policy, "rules");
        JsonNode rules = policy.get("rules");
        requireType(rules, "array");
        if (rules.isEmpty()) {
            throw schemaFailure("array is too short: must have at least 1 elements but instance has 0 elements");
        }
        requireAtMost(rules, MAX_RULES);
        for (JsonNode rule : rules) {
            requireType(rule, "object");
            requireMembers(rule, "action", "rulePriority", "selection");
            requirePositiveInteger(rule.get("rulePriority"));
            requireType(rule.get("selection"), "object");
            checkSelectionSchema(rule.get("selection"));
            requireType(rule.get("action"), "object");
            checkActionSchema(rule.get("action"));
        }
    }

    private static void checkSelectionSchema(JsonNode selection) {
        requireMembers(selection, "countNumber", "countType", "tagStatus");
        for (String list : List.of("tagPatternList", "tagPrefixList")) {
            JsonNode values = selection.get(list);
            if (values != null) {
                requireType(values, "array");
                values.forEach(value -> requireType(value, "string"));
            }
        }
        if (selection.has("tagPrefixList")) {
            requireAtMost(selection.get("tagPrefixList"), MAX_TAG_PREFIXES);
        }
        requirePositiveInteger(selection.get("countNumber"));
        if (!matchesACountSchema(selection)) {
            throw schemaFailure(noSchemaMatched(SELECTION_SCHEMAS));
        }
    }

    /**
     * Each count type has a schema of its own: {@code imageCountMoreThan} takes no {@code countUnit}, the others take
     * {@code days}, and none takes a member the guide does not list.
     */
    private static boolean matchesACountSchema(JsonNode selection) {
        for (Iterator<String> members = selection.fieldNames(); members.hasNext(); ) {
            if (!SELECTION_MEMBERS.contains(members.next())) {
                return false;
            }
        }
        CountType countType = wireValue(selection.get("countType"), CountType.values(), CountType::wireName);
        if (countType == null) {
            return false;
        }
        JsonNode countUnit = selection.get("countUnit");
        return countType == CountType.IMAGE_COUNT_MORE_THAN
                ? countUnit == null
                : countUnit != null && countUnit.isTextual() && countUnit.asText().equals("days");
    }

    /** An expire action carries only its type; a transition can also carry where it moves images to. */
    private static void checkActionSchema(JsonNode action) {
        String type = action.path("type").isTextual() ? action.get("type").asText() : "";
        JsonNode target = action.get("targetStorageClass");
        boolean matched = switch (type) {
            case "expire" -> action.size() == 1;
            case "transition" -> action.size() == 1 || action.size() == 2 && target != null && target.isTextual()
                    && target.asText().equals(StorageClass.ARCHIVE.wireName);
            default -> false;
        };
        if (!matched) {
            throw schemaFailure(noSchemaMatched(ACTION_SCHEMAS));
        }
    }

    private static void requireMembers(JsonNode object, String... members) {
        Set<String> missing = new TreeSet<>();
        for (String member : members) {
            if (!object.has(member)) {
                missing.add(member);
            }
        }
        if (!missing.isEmpty()) {
            throw schemaFailure("object has missing required properties ([\"" + String.join("\",\"", missing)
                    + "\"])");
        }
    }

    private static void requireType(JsonNode node, String type) {
        if (!typeOf(node).equals(type)) {
            throw schemaFailure("instance type (" + typeOf(node)
                    + ") does not match any allowed primitive type (allowed: [\"" + type + "\"])");
        }
    }

    /** An integer of at least 1; one too large for ECR's rule types passes the schema and fails the mapping. */
    private static void requirePositiveInteger(JsonNode node) {
        requireType(node, "integer");
        if (node.bigIntegerValue().signum() < 1) {
            throw schemaFailure("numeric instance is lower than the required minimum (minimum: 1, found: "
                    + node.asText() + ")");
        }
    }

    private static void requireAtMost(JsonNode array, int max) {
        if (array.size() > max) {
            throw schemaFailure("array is too long: must have at most " + max + " elements but instance has "
                    + array.size() + " elements");
        }
    }

    private static String typeOf(JsonNode node) {
        return switch (node.getNodeType()) {
            case ARRAY -> "array";
            case BOOLEAN -> "boolean";
            case NULL -> "null";
            case NUMBER -> node.isIntegralNumber() ? "integer" : "number";
            case OBJECT -> "object";
            case STRING -> "string";
            default -> "unknown";
        };
    }

    private static String noSchemaMatched(int schemas) {
        return "instance failed to match exactly one schema (matched 0 out of " + schemas + ")";
    }

    // ------------------------------------------------------------
    // Mapping onto ECR's rule types: what does not fit is unmapped
    // ------------------------------------------------------------

    private static Rule map(JsonNode rule) {
        JsonNode selection = rule.get("selection");
        JsonNode action = rule.get("action");
        TagStatus tagStatus = wireValue(selection.get("tagStatus"), TagStatus.values(), status -> status.wireName);
        StorageClass storageClass = selection.has("storageClass")
                ? wireValue(selection.get("storageClass"), StorageClass.values(), storage -> storage.wireName)
                : StorageClass.STANDARD;
        boolean transition = action.get("type").asText().equals("transition");
        if (tagStatus == null || storageClass == null || !rule.get("rulePriority").canConvertToInt()
                || !selection.get("countNumber").canConvertToInt()
                || transition && !action.has("targetStorageClass")) {
            throw parsingFailure();
        }
        return new Rule(rule.get("rulePriority").intValue(), tagStatus, strings(selection.get("tagPatternList")),
                strings(selection.get("tagPrefixList")), storageClass,
                wireValue(selection.get("countType"), CountType.values(), CountType::wireName),
                selection.get("countNumber").intValue(), transition ? ActionType.TRANSITION : ActionType.EXPIRE);
    }

    private static List<String> strings(JsonNode values) {
        List<String> strings = new ArrayList<>();
        if (values != null) {
            values.forEach(value -> strings.add(value.asText()));
        }
        return List.copyOf(strings);
    }

    private static <E> E wireValue(JsonNode node, E[] values, Function<E, String> wireName) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        for (E value : values) {
            if (wireName.apply(value).equals(node.asText())) {
                return value;
            }
        }
        return null;
    }

    // ------------------------------------------------------------
    // The user guide's rules, in ECR's sentences where known
    // ------------------------------------------------------------

    private static void checkRules(List<Rule> rules) {
        Set<Integer> priorities = new HashSet<>();
        for (Rule rule : rules) {
            if (!priorities.add(rule.priority())) {
                throw validationFailure("Duplicate priority in multiple rules");
            }
        }
        for (Rule rule : rules) {
            checkTagFilters(rule);
            checkStorageClass(rule);
            if (rule.countType() == CountType.SINCE_IMAGE_TRANSITIONED && rule.action() == ActionType.TRANSITION) {
                throw validationFailure("countType sinceImageTransitioned can only be used with the expire action.");
            }
        }
        checkRulesOfEachStorageClass(rules);
    }

    private static void checkTagFilters(Rule rule) {
        if (rule.tagStatus() != TagStatus.TAGGED) {
            if (!rule.tagPatterns().isEmpty() || !rule.tagPrefixes().isEmpty()) {
                throw validationFailure("tagPrefixList and tagPatternList can only be used when tagStatus=TAGGED.");
            }
            return;
        }
        if (!rule.tagPatterns().isEmpty() && !rule.tagPrefixes().isEmpty()) {
            throw validationFailure("A rule can specify tagPrefixList or tagPatternList, but not both.");
        }
        if (rule.tagPatterns().isEmpty() && rule.tagPrefixes().isEmpty()) {
            throw validationFailure("Must specify tagPrefixList or tagPatternList when tagStatus=TAGGED.");
        }
        for (String pattern : rule.tagPatterns()) {
            if (pattern.chars().filter(c -> c == '*').count() > MAX_PATTERN_WILDCARDS) {
                throw validationFailure("Invalid tag pattern provided. Total number of * must not exceed "
                        + MAX_PATTERN_WILDCARDS);
            }
        }
    }

    /** Archived images are selected only by age in the archive, and only after their minimum stay there. */
    private static void checkStorageClass(Rule rule) {
        boolean archive = rule.countType() == CountType.SINCE_IMAGE_TRANSITIONED;
        if (archive && rule.storageClass() != StorageClass.ARCHIVE) {
            throw validationFailure("storageClass must be archive when countType is sinceImageTransitioned.");
        }
        if (!archive && rule.storageClass() != StorageClass.STANDARD) {
            throw validationFailure("storageClass must be standard when countType is "
                    + rule.countType().wireName() + ".");
        }
        if (archive && rule.countNumber() < MIN_ARCHIVE_DAYS) {
            throw validationFailure("countNumber must be at least " + MIN_ARCHIVE_DAYS
                    + " when countType is sinceImageTransitioned.");
        }
    }

    /**
     * Checks across the rules selecting one storage class: a rule selecting any image has the highest rulePriority of
     * them all, so only one can, at most one rule selects untagged images, and no tag prefix appears in two rules.
     */
    private static void checkRulesOfEachStorageClass(List<Rule> rules) {
        Map<StorageClass, List<Rule>> byStorageClass = new EnumMap<>(StorageClass.class);
        for (Rule rule : rules) {
            byStorageClass.computeIfAbsent(rule.storageClass(), ignored -> new ArrayList<>()).add(rule);
        }
        for (List<Rule> group : byStorageClass.values()) {
            int highest = Integer.MIN_VALUE;
            int lowestAny = Integer.MAX_VALUE;
            int untagged = 0;
            Set<String> prefixes = new HashSet<>();
            for (Rule rule : group) {
                highest = Math.max(highest, rule.priority());
                switch (rule.tagStatus()) {
                    case ANY -> lowestAny = Math.min(lowestAny, rule.priority());
                    case UNTAGGED -> untagged++;
                    case TAGGED -> {
                        for (String prefix : new LinkedHashSet<>(rule.tagPrefixes())) {
                            if (!prefixes.add(prefix)) {
                                throw validationFailure("The rules selecting a storage class must have a unique set "
                                        + "of tag prefixes.");
                            }
                        }
                    }
                }
            }
            if (lowestAny < highest) {
                throw validationFailure("Rule for tagStatus=ANY must have the lowest priority per storage class.");
            }
            if (untagged > 1) {
                throw validationFailure("Only one rule can select Untagged images per storage class.");
            }
        }
    }

    // ------------------------------------------------------------
    // ECR's message forms
    // ------------------------------------------------------------

    private static AwsException parsingFailure() {
        return invalid("Lifecycle policy parsing failure: Could not map policyString into LifecyclePolicy.");
    }

    /** The validator's own text, which ECR quotes with the newline the validator ends it with. */
    private static AwsException schemaFailure(String report) {
        return validationFailure(report + "\n");
    }

    private static AwsException validationFailure(String reason) {
        return invalid("Lifecycle policy validation failure: " + reason);
    }

    private static AwsException invalid(String constraint) {
        return new AwsException("InvalidParameterException",
                "Invalid parameter at 'LifecyclePolicyText' failed to satisfy constraint: '" + constraint + "'", 400);
    }
}
