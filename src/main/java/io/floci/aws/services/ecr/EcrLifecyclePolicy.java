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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A lifecycle policy document as {@code PutLifecyclePolicy} takes it, checked against the rules the ECR user guide
 * gives for lifecycle policy properties and their evaluation. Enum values are matched without regard to case.
 *
 * @see <a href="https://docs.aws.amazon.com/AmazonECR/latest/userguide/lifecycle_policy_parameters.html">Lifecycle
 *     policy properties</a>
 */
public record EcrLifecyclePolicy(List<Rule> rules) {

    /** Which images a rule's selection looks at by their tags. */
    public enum TagStatus { TAGGED, UNTAGGED, ANY }

    /** The storage class a rule selects images of. */
    public enum StorageClass { STANDARD, ARCHIVE }

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

        boolean countsDays() {
            return this != IMAGE_COUNT_MORE_THAN;
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
    private static final int MAX_PATTERN_WILDCARDS = 4;

    /** Parses and checks {@code text}; a document ECR would refuse is refused with ECR's message form. */
    public static EcrLifecyclePolicy parse(String text) {
        JsonNode policy;
        try {
            policy = text == null ? null : SINGLE_JSON_VALUE.readTree(text);
        } catch (JsonProcessingException e) {
            throw parsingFailure();
        }
        if (policy == null || !policy.isObject()) {
            throw parsingFailure();
        }
        JsonNode rules = present(policy.get("rules"));
        if (rules != null && !rules.isArray()) {
            throw parsingFailure();
        }
        if (rules == null || rules.isEmpty()) {
            throw validationFailure("A lifecycle policy must have at least one rule.");
        }
        List<Rule> parsed = new ArrayList<>();
        Set<Integer> priorities = new HashSet<>();
        for (JsonNode rule : rules) {
            Rule next = rule(rule);
            if (!priorities.add(next.priority())) {
                throw validationFailure("Each rule must have a unique rulePriority.");
            }
            parsed.add(next);
        }
        checkRulesOfEachStorageClass(parsed);
        return new EcrLifecyclePolicy(List.copyOf(parsed));
    }

    private static Rule rule(JsonNode rule) {
        if (!rule.isObject()) {
            throw parsingFailure();
        }
        Integer priority = positiveInteger(rule.get("rulePriority"));
        if (priority == null) {
            throw validationFailure("rulePriority must be a positive integer.");
        }
        JsonNode selection = object(rule, "selection", "Every rule must have a selection.");
        TagStatus tagStatus = enumValue(selection.get("tagStatus"), TagStatus.class);
        if (tagStatus == null) {
            throw validationFailure("tagStatus must be tagged, untagged or any.");
        }
        List<String> patterns = strings(selection, "tagPatternList");
        List<String> prefixes = strings(selection, "tagPrefixList");
        checkTagFilters(tagStatus, patterns, prefixes);

        CountType countType = countType(selection.get("countType"));
        StorageClass storageClass = storageClass(selection.get("storageClass"), countType);
        checkCountUnit(present(selection.get("countUnit")), countType);
        int countNumber = countNumber(selection.get("countNumber"));

        JsonNode action = object(rule, "action", "Every rule must have an action.");
        ActionType actionType = enumValue(action.get("type"), ActionType.class);
        if (actionType == null) {
            throw validationFailure("The action type must be expire or transition.");
        }
        if (actionType == ActionType.TRANSITION) {
            if (enumValue(action.get("targetStorageClass"), StorageClass.class) != StorageClass.ARCHIVE) {
                throw validationFailure("targetStorageClass must be archive when the action type is transition.");
            }
            if (countType == CountType.SINCE_IMAGE_TRANSITIONED) {
                throw validationFailure("countType sinceImageTransitioned can only be used with the expire action.");
            }
        }
        return new Rule(priority, tagStatus, patterns, prefixes, storageClass, countType, countNumber, actionType);
    }

    private static void checkTagFilters(TagStatus tagStatus, List<String> patterns, List<String> prefixes) {
        if (tagStatus != TagStatus.TAGGED) {
            if (!patterns.isEmpty() || !prefixes.isEmpty()) {
                throw validationFailure("tagPrefixList and tagPatternList can only be used when tagStatus=TAGGED.");
            }
            return;
        }
        if (!patterns.isEmpty() && !prefixes.isEmpty()) {
            throw validationFailure("A rule can specify tagPrefixList or tagPatternList, but not both.");
        }
        if (patterns.isEmpty() && prefixes.isEmpty()) {
            throw validationFailure("Must specify tagPrefixList or tagPatternList when tagStatus=TAGGED.");
        }
        for (String pattern : patterns) {
            if (pattern.chars().filter(c -> c == '*').count() > MAX_PATTERN_WILDCARDS) {
                throw validationFailure("A tag pattern can have at most " + MAX_PATTERN_WILDCARDS + " wildcards.");
            }
        }
    }

    private static CountType countType(JsonNode node) {
        String value = node != null && node.isTextual() ? node.asText() : null;
        for (CountType countType : CountType.values()) {
            if (countType.wireName().equalsIgnoreCase(value)) {
                return countType;
            }
        }
        throw validationFailure(
                "countType must be imageCountMoreThan, sinceImagePushed, sinceImagePulled or sinceImageTransitioned.");
    }

    /** Archived images are selected only by age in the archive, and only that count selects them. */
    private static StorageClass storageClass(JsonNode node, CountType countType) {
        StorageClass storageClass = StorageClass.STANDARD;
        if (present(node) != null) {
            storageClass = enumValue(node, StorageClass.class);
            if (storageClass == null) {
                throw validationFailure("storageClass must be standard or archive.");
            }
        }
        boolean archive = countType == CountType.SINCE_IMAGE_TRANSITIONED;
        if (archive && storageClass != StorageClass.ARCHIVE) {
            throw validationFailure("storageClass must be archive when countType is sinceImageTransitioned.");
        }
        if (!archive && storageClass != StorageClass.STANDARD) {
            throw validationFailure("storageClass must be standard when countType is " + countType.wireName() + ".");
        }
        return storageClass;
    }

    private static void checkCountUnit(JsonNode countUnit, CountType countType) {
        if (!countType.countsDays()) {
            if (countUnit != null) {
                throw validationFailure("countUnit can only be used when countType is sinceImagePushed, "
                        + "sinceImagePulled or sinceImageTransitioned.");
            }
            return;
        }
        if (countUnit == null || !countUnit.isTextual() || !"days".equalsIgnoreCase(countUnit.asText())) {
            throw validationFailure("countUnit must be days when countType is " + countType.wireName() + ".");
        }
    }

    /** A countNumber below 1 is refused with the JSON schema validator's own text, as ECR quotes it. */
    private static int countNumber(JsonNode node) {
        if (node != null && node.isIntegralNumber() && node.canConvertToInt() && node.intValue() < 1) {
            throw validationFailure("numeric instance is lower than the required minimum (minimum: 1, found: "
                    + node.intValue() + ")\n");
        }
        Integer countNumber = positiveInteger(node);
        if (countNumber == null) {
            throw validationFailure("countNumber must be a positive integer.");
        }
        return countNumber;
    }

    /**
     * Checks across the rules selecting one storage class: a rule selecting any image has a higher rulePriority than
     * every rule that does not, at most one rule selects untagged images, and no tag prefix appears in two rules.
     */
    private static void checkRulesOfEachStorageClass(List<Rule> rules) {
        Map<StorageClass, List<Rule>> byStorageClass = new EnumMap<>(StorageClass.class);
        for (Rule rule : rules) {
            byStorageClass.computeIfAbsent(rule.storageClass(), ignored -> new ArrayList<>()).add(rule);
        }
        for (List<Rule> group : byStorageClass.values()) {
            int highestSelective = Integer.MIN_VALUE;
            int lowestAny = Integer.MAX_VALUE;
            int untagged = 0;
            Set<String> prefixes = new HashSet<>();
            for (Rule rule : group) {
                switch (rule.tagStatus()) {
                    case ANY -> lowestAny = Math.min(lowestAny, rule.priority());
                    case UNTAGGED -> {
                        highestSelective = Math.max(highestSelective, rule.priority());
                        untagged++;
                    }
                    case TAGGED -> {
                        highestSelective = Math.max(highestSelective, rule.priority());
                        for (String prefix : new LinkedHashSet<>(rule.tagPrefixes())) {
                            if (!prefixes.add(prefix)) {
                                throw validationFailure("The rules selecting a storage class must have a unique set "
                                        + "of tag prefixes.");
                            }
                        }
                    }
                }
            }
            if (lowestAny < highestSelective) {
                throw validationFailure("A rule with tagStatus=ANY must have the highest rulePriority.");
            }
            if (untagged > 1) {
                throw validationFailure("Only one rule selecting a storage class can select untagged images.");
            }
        }
    }

    /** A member that must be an object when given: one left out or null is refused, one of another type unmapped. */
    private static JsonNode object(JsonNode parent, String member, String missing) {
        JsonNode node = present(parent.get(member));
        if (node == null) {
            throw validationFailure(missing);
        }
        if (!node.isObject()) {
            throw parsingFailure();
        }
        return node;
    }

    /** A list of strings, empty when left out or null; anything but an array of strings cannot be mapped. */
    private static List<String> strings(JsonNode parent, String member) {
        JsonNode node = present(parent.get(member));
        if (node == null) {
            return List.of();
        }
        if (!node.isArray()) {
            throw parsingFailure();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isTextual()) {
                throw parsingFailure();
            }
            values.add(value.asText());
        }
        return List.copyOf(values);
    }

    private static <E extends Enum<E>> E enumValue(JsonNode node, Class<E> type) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        try {
            return Enum.valueOf(type, node.asText().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The value of an integer node of 1 or more that fits an int; null for anything else. */
    private static Integer positiveInteger(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < 1) {
            return null;
        }
        return node.intValue();
    }

    private static JsonNode present(JsonNode node) {
        return node == null || node.isNull() ? null : node;
    }

    private static AwsException parsingFailure() {
        return invalid("Lifecycle policy parsing failure: Could not map policyString into LifecyclePolicy.");
    }

    private static AwsException validationFailure(String reason) {
        return invalid("Lifecycle policy validation failure: " + reason);
    }

    private static AwsException invalid(String constraint) {
        return new AwsException("InvalidParameterException",
                "Invalid parameter at 'LifecyclePolicyText' failed to satisfy constraint: '" + constraint + "'", 400);
    }
}
