package io.floci.aws.services.ecr;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.ecr.EcrLifecyclePolicy.ActionType;
import io.floci.aws.services.ecr.EcrLifecyclePolicy.CountType;
import io.floci.aws.services.ecr.EcrLifecyclePolicy.Rule;
import io.floci.aws.services.ecr.EcrLifecyclePolicy.StorageClass;
import io.floci.aws.services.ecr.EcrLifecyclePolicy.TagStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EcrLifecyclePolicyTest {

    private static final String PREFIX = "Invalid parameter at 'LifecyclePolicyText' failed to satisfy constraint: '";
    private static final String PARSING_FAILURE =
            PREFIX + "Lifecycle policy parsing failure: Could not map policyString into LifecyclePolicy.'";
    private static final String VALIDATION = PREFIX + "Lifecycle policy validation failure: ";
    private static final String EXPIRE = "{\"type\":\"expire\"}";
    private static final String TRANSITION = "{\"type\":\"transition\",\"targetStorageClass\":\"archive\"}";
    private static final String ANY_COUNT = "{\"tagStatus\":\"any\",\"countType\":\"imageCountMoreThan\","
            + "\"countNumber\":1}";
    private static final String NO_SELECTION_MATCHED =
            "instance failed to match exactly one schema (matched 0 out of 4)";
    private static final String NO_ACTION_MATCHED = "instance failed to match exactly one schema (matched 0 out of 2)";

    /** The policies of the ECR user guide's lifecycle policy examples page, each of which ECR takes. */
    static Stream<String> userGuideExamples() {
        return Stream.of(
                policy(rule(1, "{\"tagStatus\":\"tagged\",\"tagPatternList\":[\"prod*\"],"
                        + "\"countType\":\"sinceImagePushed\",\"countUnit\":\"days\",\"countNumber\":14}", EXPIRE)),
                policy(rule(1, "{\"tagStatus\":\"any\",\"countType\":\"sinceImagePulled\",\"countUnit\":\"days\","
                        + "\"countNumber\":90}", TRANSITION)),
                policy(rule(1, "{\"tagStatus\":\"any\",\"storageClass\":\"archive\","
                        + "\"countType\":\"sinceImageTransitioned\",\"countUnit\":\"days\",\"countNumber\":365}",
                        EXPIRE)),
                policy(rule(1, "{\"tagStatus\":\"untagged\",\"countType\":\"imageCountMoreThan\",\"countNumber\":1}",
                        EXPIRE)),
                policy(rule(1, tagged("[\"prod*\"]", 1), EXPIRE), rule(2, tagged("[\"beta*\"]", 1), EXPIRE)),
                policy(rule(1, tagged("[\"beta*\"]", 1), EXPIRE), rule(2, tagged("[\"prod*\"]", 1), EXPIRE)),
                policy(rule(1, "{\"tagStatus\":\"tagged\",\"tagPatternList\":[\"alpha*\",\"beta*\"],"
                        + "\"countType\":\"sinceImagePushed\",\"countNumber\":5,\"countUnit\":\"days\"}", EXPIRE)),
                policy(rule(1, tagged("[\"alpha*\",\"beta*\"]", 1), EXPIRE)),
                policy(rule(1, ANY_COUNT, EXPIRE)),
                policy(rule(1, tagged("[\"alpha*\"]", 1), EXPIRE),
                        rule(2, "{\"tagStatus\":\"untagged\",\"countType\":\"sinceImagePushed\",\"countUnit\":\"days\","
                                + "\"countNumber\":1}", EXPIRE),
                        rule(3, ANY_COUNT, EXPIRE)),
                policy(rule(1, "{\"tagStatus\":\"tagged\",\"tagPatternList\":[\"prod*\"],"
                        + "\"countType\":\"sinceImagePushed\",\"countUnit\":\"days\",\"countNumber\":30}", TRANSITION)),
                policy(rule(1, "{\"tagStatus\":\"any\",\"countType\":\"sinceImagePushed\",\"countUnit\":\"days\","
                                + "\"countNumber\":30}", TRANSITION),
                        rule(2, "{\"tagStatus\":\"any\",\"storageClass\":\"archive\","
                                + "\"countType\":\"sinceImageTransitioned\",\"countUnit\":\"days\","
                                + "\"countNumber\":365}", EXPIRE)));
    }

    @ParameterizedTest
    @MethodSource("userGuideExamples")
    void everyUserGuideExampleIsTaken(String policy) {
        EcrLifecyclePolicy.parse(policy);
    }

    @Test
    void aRuleIsReadWithItsDefaults() {
        EcrLifecyclePolicy policy = EcrLifecyclePolicy.parse(policy(
                rule(3, "{\"tagStatus\":\"tagged\",\"tagPrefixList\":[\"prod\",\"v\"],"
                        + "\"countType\":\"sinceImagePushed\",\"countUnit\":\"days\",\"countNumber\":7}", EXPIRE),
                rule(9, "{\"tagStatus\":\"any\",\"countType\":\"imageCountMoreThan\",\"countNumber\":2}", TRANSITION)));

        assertEquals(List.of(
                new Rule(3, TagStatus.TAGGED, List.of(), List.of("prod", "v"), StorageClass.STANDARD,
                        CountType.SINCE_IMAGE_PUSHED, 7, ActionType.EXPIRE),
                new Rule(9, TagStatus.ANY, List.of(), List.of(), StorageClass.STANDARD,
                        CountType.IMAGE_COUNT_MORE_THAN, 2, ActionType.TRANSITION)), policy.rules());
    }

    /**
     * Each storage class has its own rule set: an archive rule selecting any image can come before the standard
     * rules, a prefix one storage class uses can be used by the other, and each can have one untagged rule.
     */
    @Test
    void theRulesOfEachStorageClassAreCheckedApart() {
        EcrLifecyclePolicy.parse(policy(
                rule(1, "{\"tagStatus\":\"tagged\",\"tagPrefixList\":[\"prod\"],\"storageClass\":\"archive\","
                        + "\"countType\":\"sinceImageTransitioned\",\"countUnit\":\"days\",\"countNumber\":90}",
                        EXPIRE),
                rule(2, "{\"tagStatus\":\"untagged\",\"storageClass\":\"archive\","
                        + "\"countType\":\"sinceImageTransitioned\",\"countUnit\":\"days\",\"countNumber\":90}",
                        EXPIRE),
                rule(3, "{\"tagStatus\":\"any\",\"storageClass\":\"archive\","
                        + "\"countType\":\"sinceImageTransitioned\",\"countUnit\":\"days\",\"countNumber\":365}",
                        EXPIRE),
                rule(4, "{\"tagStatus\":\"tagged\",\"tagPrefixList\":[\"prod\"],"
                        + "\"countType\":\"imageCountMoreThan\",\"countNumber\":1}", EXPIRE),
                rule(5, "{\"tagStatus\":\"untagged\",\"storageClass\":\"standard\","
                        + "\"countType\":\"imageCountMoreThan\",\"countNumber\":1}", EXPIRE)));
    }

    /** A prefix repeated within one rule's own list counts once. */
    @Test
    void aPrefixRepeatedWithinOneRuleIsTaken() {
        EcrLifecyclePolicy.parse(policy(
                rule(1, "{\"tagStatus\":\"tagged\",\"tagPrefixList\":[\"prod\",\"prod\"],"
                        + "\"countType\":\"imageCountMoreThan\",\"countNumber\":1}", EXPIRE),
                rule(2, "{\"tagStatus\":\"tagged\",\"tagPrefixList\":[\"beta\"],"
                        + "\"countType\":\"imageCountMoreThan\",\"countNumber\":1}", EXPIRE)));
    }

    @Test
    void anEmptyTagListOnAnUntaggedRuleIsTakenAsLeftOut() {
        EcrLifecyclePolicy.parse(policy(rule(1, "{\"tagStatus\":\"untagged\",\"tagPrefixList\":[],"
                + "\"countType\":\"imageCountMoreThan\",\"countNumber\":1}", EXPIRE)));
    }

    @Test
    void fiftyRulesAndTenPrefixesAreTaken() {
        String[] rules = new String[50];
        for (int i = 0; i < 49; i++) {
            rules[i] = rule(i + 1, tagged("[\"v" + i + "*\"]", 1), EXPIRE);
        }
        rules[49] = rule(50, "{\"tagStatus\":\"tagged\",\"tagPrefixList\":[" + prefixes(10)
                + "],\"countType\":\"imageCountMoreThan\",\"countNumber\":1}", EXPIRE);

        EcrLifecyclePolicy.parse(policy(rules));
    }

    /**
     * The messages ECR answered for these documents, from recordings, from measurements against ECR and from
     * refusals users quoted.
     */
    static Stream<Arguments> refusalsEcrAnswered() {
        String[] fiftyOne = Collections.nCopies(51, rule(1, ANY_COUNT, EXPIRE)).toArray(String[]::new);
        return Stream.of(
                Arguments.of("{1:2}", PARSING_FAILURE),
                Arguments.of(policy(rule(1, "{\"tagStatus\":\"any\",\"countType\":\"imageCountMoreThan\","
                                + "\"countNumber\":0}", EXPIRE)),
                        VALIDATION + "numeric instance is lower than the required minimum (minimum: 1, found: 0)\n'"),
                Arguments.of(policy(rule(1, "{\"tagStatus\":\"tagged\",\"countType\":\"imageCountMoreThan\","
                                + "\"countNumber\":1}", EXPIRE)),
                        VALIDATION + "Must specify tagPrefixList or tagPatternList when tagStatus=TAGGED.'"),
                Arguments.of(policy(rule(1, "{\"tagStatus\":\"any\",\"countType\":\"notARealCountType\","
                                + "\"countNumber\":1}", EXPIRE)),
                        VALIDATION + NO_SELECTION_MATCHED + "\n'"),
                Arguments.of(policy(rule(1, "{\"tagStatus\":\"any\",\"countType\":\"imageCountMoreThan\","
                                + "\"countUnit\":\"days\",\"countNumber\":10}", EXPIRE)),
                        VALIDATION + NO_SELECTION_MATCHED + "\n'"),
                Arguments.of(policy(rule(1, ANY_COUNT, "{\"type\":\"expire\",\"targetStorageClass\":null}")),
                        VALIDATION + NO_ACTION_MATCHED + "\n'"),
                Arguments.of(policy(rule(1, ANY_COUNT, "{\"type\":\"transition\"}")), PARSING_FAILURE),
                Arguments.of(policy(rule(1, "{\"tagStatus\":\"tagged\",\"tagPrefixList\":\"prod\","
                                + "\"countType\":\"imageCountMoreThan\",\"countNumber\":1}", EXPIRE)),
                        VALIDATION + "instance type (string) does not match any allowed primitive type "
                                + "(allowed: [\"array\"])\n'"),
                Arguments.of(policy(rule(1, "{\"tagStatus\":\"untagged\",\"tagPrefixList\":null,"
                                + "\"countType\":\"imageCountMoreThan\",\"countNumber\":1}", EXPIRE)),
                        VALIDATION + "instance type (null) does not match any allowed primitive type "
                                + "(allowed: [\"array\"])\n'"),
                Arguments.of(policy(rule(1, "{\"tagStatus\":\"any\",\"countType\":\"imageCountMoreThan\","
                                + "\"countNumber\":\"5\"}", EXPIRE)),
                        VALIDATION + "instance type (string) does not match any allowed primitive type "
                                + "(allowed: [\"integer\"])\n'"),
                Arguments.of(policy(rule(1, ANY_COUNT, EXPIRE), rule(1, tagged("[\"prod*\"]", 1), EXPIRE)),
                        VALIDATION + "Duplicate priority in multiple rules'"),
                Arguments.of(policy(rule(1, ANY_COUNT, EXPIRE), rule(2, "{\"tagStatus\":\"untagged\","
                                + "\"countType\":\"imageCountMoreThan\",\"countNumber\":1}", EXPIRE)),
                        VALIDATION + "Rule for tagStatus=ANY must have the lowest priority per storage class.'"),
                Arguments.of(policy(
                                rule(1, "{\"tagStatus\":\"untagged\",\"countType\":\"imageCountMoreThan\","
                                        + "\"countNumber\":1}", EXPIRE),
                                rule(2, "{\"tagStatus\":\"untagged\",\"countType\":\"sinceImagePushed\","
                                        + "\"countUnit\":\"days\",\"countNumber\":1}", EXPIRE)),
                        VALIDATION + "Only one rule can select Untagged images per storage class.'"),
                Arguments.of(policy(rule(1, tagged("[\"a*b*b*b*b*\"]", 1), EXPIRE)),
                        VALIDATION + "Invalid tag pattern provided. Total number of * must not exceed 4'"),
                Arguments.of(policy(fiftyOne),
                        VALIDATION + "array is too long: must have at most 50 elements but instance has 51 "
                                + "elements\n'"),
                Arguments.of(policy(rule(1, "{\"tagStatus\":\"tagged\",\"tagPrefixList\":[" + prefixes(16)
                                + "],\"countType\":\"imageCountMoreThan\",\"countNumber\":1}", EXPIRE)),
                        VALIDATION + "array is too long: must have at most 10 elements but instance has 16 "
                                + "elements\n'"));
    }

    @ParameterizedTest
    @MethodSource("refusalsEcrAnswered")
    void aRefusalCarriesEcrsMessage(String policy, String message) {
        AwsException e = assertThrows(AwsException.class, () -> EcrLifecyclePolicy.parse(policy));

        assertEquals("InvalidParameterException", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
        assertEquals(message, e.getMessage());
    }

    /**
     * Refusals no ECR answer shows, worded as the same JSON schema validator words them: its type, required member,
     * minimum and array length checks, and the alternatives a selection or an action must match one of.
     */
    static Stream<Arguments> schemaRefusals() {
        return Stream.of(
                Arguments.of("[]", "instance type (array) does not match any allowed primitive type "
                        + "(allowed: [\"object\"])"),
                Arguments.of("{}", "object has missing required properties ([\"rules\"])"),
                Arguments.of("{\"rules\":{}}", "instance type (object) does not match any allowed primitive type "
                        + "(allowed: [\"array\"])"),
                Arguments.of("{\"rules\":[]}", "array is too short: must have at least 1 elements but instance has 0 "
                        + "elements"),
                Arguments.of("{\"rules\":[1]}", "instance type (integer) does not match any allowed primitive type "
                        + "(allowed: [\"object\"])"),
                Arguments.of(policy("{\"rulePriority\":1}"),
                        "object has missing required properties ([\"action\",\"selection\"])"),
                Arguments.of(policy(rule("0", ANY_COUNT, EXPIRE)),
                        "numeric instance is lower than the required minimum (minimum: 1, found: 0)"),
                Arguments.of(policy(rule("1.0", ANY_COUNT, EXPIRE)),
                        "instance type (number) does not match any allowed primitive type (allowed: [\"integer\"])"),
                Arguments.of(policy(rule("\"1\"", ANY_COUNT, EXPIRE)),
                        "instance type (string) does not match any allowed primitive type (allowed: [\"integer\"])"),
                Arguments.of(policy(rule("null", ANY_COUNT, EXPIRE)),
                        "instance type (null) does not match any allowed primitive type (allowed: [\"integer\"])"),
                Arguments.of(policy(rule(1, "[]", EXPIRE)),
                        "instance type (array) does not match any allowed primitive type (allowed: [\"object\"])"),
                Arguments.of(policy(rule(1, "{\"countType\":\"imageCountMoreThan\",\"countNumber\":1}", EXPIRE)),
                        "object has missing required properties ([\"tagStatus\"])"),
                Arguments.of(policy(rule(1, "{\"tagStatus\":\"tagged\",\"tagPatternList\":[1],"
                                + "\"countType\":\"imageCountMoreThan\",\"countNumber\":1}", EXPIRE)),
                        "instance type (integer) does not match any allowed primitive type (allowed: [\"string\"])"),
                Arguments.of(policy(rule(1, "{\"tagStatus\":\"any\",\"countType\":\"IMAGECOUNTMORETHAN\","
                        + "\"countNumber\":1}", EXPIRE)), NO_SELECTION_MATCHED),
                Arguments.of(policy(rule(1, "{\"tagStatus\":\"any\",\"countType\":\"sinceImagePushed\","
                        + "\"countNumber\":1}", EXPIRE)), NO_SELECTION_MATCHED),
                Arguments.of(policy(rule(1, "{\"tagStatus\":\"any\",\"countType\":\"sinceImagePushed\","
                        + "\"countUnit\":\"Days\",\"countNumber\":1}", EXPIRE)), NO_SELECTION_MATCHED),
                Arguments.of(policy(rule(1, "{\"tagStatus\":\"tagged\",\"tagPrefixlist\":[\"prod\"],"
                        + "\"countType\":\"imageCountMoreThan\",\"countNumber\":1}", EXPIRE)), NO_SELECTION_MATCHED),
                Arguments.of(policy(rule(1, ANY_COUNT, "\"expire\"")),
                        "instance type (string) does not match any allowed primitive type (allowed: [\"object\"])"),
                Arguments.of(policy(rule(1, ANY_COUNT, "{\"type\":\"EXPIRE\"}")), NO_ACTION_MATCHED),
                Arguments.of(policy(rule(1, ANY_COUNT, "{\"type\":\"delete\"}")), NO_ACTION_MATCHED),
                Arguments.of(policy(rule(1, ANY_COUNT,
                        "{\"type\":\"transition\",\"targetStorageClass\":\"standard\"}")), NO_ACTION_MATCHED));
    }

    @ParameterizedTest
    @MethodSource("schemaRefusals")
    void aDocumentTheSchemaRefusesIsRefusedWithTheValidatorsText(String policy, String report) {
        AwsException e = assertThrows(AwsException.class, () -> EcrLifecyclePolicy.parse(policy));

        assertEquals(VALIDATION + report + "\n'", e.getMessage());
    }

    /** What passes the schema but fits none of ECR's rule types: a value it does not name, or a number too large. */
    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "{\"rules\":[", "{\"rules\":[]} []",
            "{\"rules\":[{\"rulePriority\":2147483648,\"selection\":" + ANY_COUNT + ",\"action\":" + EXPIRE + "}]}",
            "{\"rules\":[{\"rulePriority\":1,\"selection\":{\"tagStatus\":\"any\",\"countType\":\"imageCountMoreThan\","
                    + "\"countNumber\":2147483648},\"action\":" + EXPIRE + "}]}",
            "{\"rules\":[{\"rulePriority\":1,\"selection\":{\"tagStatus\":\"TAGGED\",\"tagPrefixList\":[\"prod\"],"
                    + "\"countType\":\"imageCountMoreThan\",\"countNumber\":1},\"action\":" + EXPIRE + "}]}",
            "{\"rules\":[{\"rulePriority\":1,\"selection\":{\"tagStatus\":\"some\","
                    + "\"countType\":\"imageCountMoreThan\",\"countNumber\":1},\"action\":" + EXPIRE + "}]}",
            "{\"rules\":[{\"rulePriority\":1,\"selection\":{\"tagStatus\":\"any\",\"storageClass\":\"glacier\","
                    + "\"countType\":\"imageCountMoreThan\",\"countNumber\":1},\"action\":" + EXPIRE + "}]}"})
    void aDocumentEcrCannotMapIsRefused(String policy) {
        AwsException e = assertThrows(AwsException.class, () -> EcrLifecyclePolicy.parse(policy));

        assertEquals(PARSING_FAILURE, e.getMessage());
    }

    /** The model bounds the text to 100 to 30720 characters, checked before the document, with the member's name. */
    @Test
    void aDocumentOutsideTheModelsLengthIsRefusedBeforeItIsRead() {
        AwsException tooShort = assertThrows(AwsException.class,
                () -> EcrService.validateLifecyclePolicyDocument("{\"rules\":[]}"));
        String padded = policy(rule(1, ANY_COUNT, EXPIRE)) + " ".repeat(30720);
        AwsException tooLong = assertThrows(AwsException.class,
                () -> EcrService.validateLifecyclePolicyDocument(padded));

        assertEquals("Invalid parameter at 'lifecyclePolicyText' failed to satisfy constraint: 'Member must have "
                + "length greater than or equal to 100'", tooShort.getMessage());
        assertEquals("Invalid parameter at 'lifecyclePolicyText' failed to satisfy constraint: 'Member must have "
                + "length less than or equal to 30720'", tooLong.getMessage());
        EcrService.validateLifecyclePolicyDocument(policy(rule(1, ANY_COUNT, EXPIRE)));
    }

    @Test
    void aMissingDocumentCannotBeMapped() {
        assertEquals(PARSING_FAILURE, assertThrows(AwsException.class, () -> EcrLifecyclePolicy.parse(null))
                .getMessage());
    }

    /** The rules of the user guide that no ECR answer shows the wording of, in Floci's own sentences. */
    static Stream<Arguments> ruleRefusals() {
        String archive = "\"storageClass\":\"archive\",\"countType\":\"sinceImageTransitioned\",\"countUnit\":\"days\"";
        return Stream.of(
                Arguments.of(policy(rule(1, "{\"tagStatus\":\"untagged\",\"tagPrefixList\":[\"prod\"],"
                                + "\"countType\":\"imageCountMoreThan\",\"countNumber\":1}", EXPIRE)),
                        "tagPrefixList and tagPatternList can only be used when tagStatus=TAGGED."),
                Arguments.of(policy(rule(1, "{\"tagStatus\":\"any\",\"tagPatternList\":[\"prod*\"],"
                                + "\"countType\":\"imageCountMoreThan\",\"countNumber\":1}", EXPIRE)),
                        "tagPrefixList and tagPatternList can only be used when tagStatus=TAGGED."),
                Arguments.of(policy(rule(1, "{\"tagStatus\":\"tagged\",\"tagPrefixList\":[\"prod\"],"
                                + "\"tagPatternList\":[\"prod*\"],\"countType\":\"imageCountMoreThan\","
                                + "\"countNumber\":1}", EXPIRE)),
                        "A rule can specify tagPrefixList or tagPatternList, but not both."),
                Arguments.of(policy(rule(1, "{\"tagStatus\":\"tagged\",\"tagPrefixList\":[],"
                                + "\"countType\":\"imageCountMoreThan\",\"countNumber\":1}", EXPIRE)),
                        "Must specify tagPrefixList or tagPatternList when tagStatus=TAGGED."),
                Arguments.of(policy(rule(1, "{\"tagStatus\":\"any\",\"countType\":\"sinceImageTransitioned\","
                                + "\"countUnit\":\"days\",\"countNumber\":365}", EXPIRE)),
                        "storageClass must be archive when countType is sinceImageTransitioned."),
                Arguments.of(policy(rule(1, "{\"tagStatus\":\"any\",\"storageClass\":\"archive\","
                                + "\"countType\":\"sinceImagePushed\",\"countUnit\":\"days\",\"countNumber\":1}",
                                EXPIRE)),
                        "storageClass must be standard when countType is sinceImagePushed."),
                Arguments.of(policy(rule(1, "{\"tagStatus\":\"any\"," + archive + ",\"countNumber\":89}", EXPIRE)),
                        "countNumber must be at least 90 when countType is sinceImageTransitioned."),
                Arguments.of(policy(rule(1, "{\"tagStatus\":\"any\"," + archive + ",\"countNumber\":365}",
                                TRANSITION)),
                        "countType sinceImageTransitioned can only be used with the expire action."),
                Arguments.of(policy(rule(1, ANY_COUNT, EXPIRE), rule(2, "{\"tagStatus\":\"any\","
                                + "\"countType\":\"sinceImagePushed\",\"countUnit\":\"days\",\"countNumber\":1}",
                                EXPIRE)),
                        "Rule for tagStatus=ANY must have the lowest priority per storage class."),
                Arguments.of(policy(
                                rule(1, "{\"tagStatus\":\"tagged\",\"tagPrefixList\":[\"prod\",\"v1\"],"
                                        + "\"countType\":\"imageCountMoreThan\",\"countNumber\":1}", EXPIRE),
                                rule(2, "{\"tagStatus\":\"tagged\",\"tagPrefixList\":[\"v1\",\"prod\"],"
                                        + "\"countType\":\"sinceImagePushed\",\"countUnit\":\"days\","
                                        + "\"countNumber\":1}", EXPIRE)),
                        "The rules selecting a storage class must have a unique set of tag prefixes."),
                Arguments.of(policy(
                                rule(1, "{\"tagStatus\":\"tagged\",\"tagPrefixList\":[\"prod\",\"beta\"],"
                                        + "\"countType\":\"imageCountMoreThan\",\"countNumber\":1}", EXPIRE),
                                rule(2, "{\"tagStatus\":\"tagged\",\"tagPrefixList\":[\"prod\",\"qa\"],"
                                        + "\"countType\":\"imageCountMoreThan\",\"countNumber\":1}", EXPIRE)),
                        "The rules selecting a storage class must have a unique set of tag prefixes."));
    }

    @ParameterizedTest
    @MethodSource("ruleRefusals")
    void aDocumentBreakingAUserGuideRuleIsRefused(String policy, String reason) {
        AwsException e = assertThrows(AwsException.class, () -> EcrLifecyclePolicy.parse(policy));

        assertEquals("InvalidParameterException", e.getErrorCode());
        assertEquals(VALIDATION + reason + "'", e.getMessage());
    }

    private static String policy(String... rules) {
        return "{\"rules\":[" + String.join(",", rules) + "]}";
    }

    private static String rule(int priority, String selection, String action) {
        return rule(String.valueOf(priority), selection, action);
    }

    private static String rule(String priority, String selection, String action) {
        return "{\"rulePriority\":" + priority + ",\"description\":\"rule\",\"selection\":" + selection
                + ",\"action\":" + action + "}";
    }

    private static String tagged(String patterns, int count) {
        return "{\"tagStatus\":\"tagged\",\"tagPatternList\":" + patterns
                + ",\"countType\":\"imageCountMoreThan\",\"countNumber\":" + count + "}";
    }

    private static String prefixes(int count) {
        StringBuilder prefixes = new StringBuilder();
        for (int i = 0; i < count; i++) {
            prefixes.append(i == 0 ? "" : ",").append("\"").append(Integer.toHexString(i)).append("\"");
        }
        return prefixes.toString();
    }
}
