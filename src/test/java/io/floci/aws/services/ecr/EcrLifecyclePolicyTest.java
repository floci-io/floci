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

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EcrLifecyclePolicyTest {

    private static final String PREFIX = "Invalid parameter at 'LifecyclePolicyText' failed to satisfy constraint: '";
    private static final String PARSING_FAILURE =
            PREFIX + "Lifecycle policy parsing failure: Could not map policyString into LifecyclePolicy.'";
    private static final String EXPIRE = "{\"type\":\"expire\"}";
    private static final String TRANSITION = "{\"type\":\"transition\",\"targetStorageClass\":\"archive\"}";

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
                policy(rule(1, "{\"tagStatus\":\"any\",\"countType\":\"imageCountMoreThan\",\"countNumber\":1}",
                        EXPIRE)),
                policy(rule(1, tagged("[\"alpha*\"]", 1), EXPIRE),
                        rule(2, "{\"tagStatus\":\"untagged\",\"countType\":\"sinceImagePushed\",\"countUnit\":\"days\","
                                + "\"countNumber\":1}", EXPIRE),
                        rule(3, "{\"tagStatus\":\"any\",\"countType\":\"imageCountMoreThan\",\"countNumber\":1}",
                                EXPIRE)),
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

    @Test
    void enumValuesAreMatchedWithoutRegardToCase() {
        EcrLifecyclePolicy policy = EcrLifecyclePolicy.parse(policy(rule(1,
                "{\"tagStatus\":\"ANY\",\"storageClass\":\"Archive\",\"countType\":\"SINCEIMAGETRANSITIONED\","
                        + "\"countUnit\":\"DAYS\",\"countNumber\":365}", "{\"type\":\"EXPIRE\"}")));

        assertEquals(new Rule(1, TagStatus.ANY, List.of(), List.of(), StorageClass.ARCHIVE,
                CountType.SINCE_IMAGE_TRANSITIONED, 365, ActionType.EXPIRE), policy.rules().get(0));
    }

    @Test
    void anUntaggedRuleBeforeAnAnyRuleAndTwoAnyRulesAreTaken() {
        EcrLifecyclePolicy.parse(policy(
                rule(1, "{\"tagStatus\":\"untagged\",\"countType\":\"imageCountMoreThan\",\"countNumber\":2}", EXPIRE),
                rule(2, "{\"tagStatus\":\"any\",\"countType\":\"imageCountMoreThan\",\"countNumber\":2}", EXPIRE),
                rule(3, "{\"tagStatus\":\"any\",\"countType\":\"sinceImagePushed\",\"countUnit\":\"days\","
                        + "\"countNumber\":2}", EXPIRE)));
    }

    /**
     * Each storage class has its own rule set, so an archive rule selecting any image can come first, and a prefix
     * one storage class uses can be used again by the other.
     */
    @Test
    void theRulesOfEachStorageClassAreCheckedApart() {
        EcrLifecyclePolicy.parse(policy(
                rule(1, "{\"tagStatus\":\"tagged\",\"tagPrefixList\":[\"prod\"],\"storageClass\":\"archive\","
                        + "\"countType\":\"sinceImageTransitioned\",\"countUnit\":\"days\",\"countNumber\":90}",
                        EXPIRE),
                rule(2, "{\"tagStatus\":\"any\",\"storageClass\":\"archive\",\"countType\":\"sinceImageTransitioned\","
                        + "\"countUnit\":\"days\",\"countNumber\":365}", EXPIRE),
                rule(3, "{\"tagStatus\":\"tagged\",\"tagPrefixList\":[\"prod\",\"prod\"],"
                        + "\"countType\":\"imageCountMoreThan\",\"countNumber\":1}", EXPIRE),
                rule(4, "{\"tagStatus\":\"tagged\",\"tagPrefixList\":[\"beta\"],\"storageClass\":\"standard\","
                        + "\"countType\":\"imageCountMoreThan\",\"countNumber\":1}", EXPIRE),
                rule(5, "{\"tagStatus\":\"untagged\",\"storageClass\":\"standard\","
                        + "\"countType\":\"imageCountMoreThan\",\"countNumber\":1}", EXPIRE)));
    }

    @Test
    void emptyTagListsOnAnUntaggedRuleAreTakenAsLeftOut() {
        EcrLifecyclePolicy.parse(policy(rule(1, "{\"tagStatus\":\"untagged\",\"tagPrefixList\":[],"
                + "\"tagPatternList\":null,\"countType\":\"imageCountMoreThan\",\"countNumber\":1}", EXPIRE)));
    }

    /** The messages recorded from ECR for these documents. */
    static Stream<Arguments> recordedRejections() {
        return Stream.of(
                Arguments.of("{1:2}", PARSING_FAILURE),
                Arguments.of(policy(rule(1, "{\"tagStatus\":\"any\",\"countType\":\"imageCountMoreThan\","
                                + "\"countNumber\":0}", EXPIRE)),
                        PREFIX + "Lifecycle policy validation failure: numeric instance is lower than the required "
                                + "minimum (minimum: 1, found: 0)\n'"),
                Arguments.of(policy(rule(1, "{\"tagStatus\":\"tagged\",\"countType\":\"imageCountMoreThan\","
                                + "\"countNumber\":1}", EXPIRE)),
                        PREFIX + "Lifecycle policy validation failure: Must specify tagPrefixList or tagPatternList "
                                + "when tagStatus=TAGGED.'"));
    }

    @ParameterizedTest
    @MethodSource("recordedRejections")
    void aRejectionCarriesEcrsMessage(String policy, String message) {
        AwsException e = assertThrows(AwsException.class, () -> EcrLifecyclePolicy.parse(policy));

        assertEquals("InvalidParameterException", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
        assertEquals(message, e.getMessage());
    }

    @Test
    void aNegativeCountNumberIsQuotedAsTheValidatorFoundIt() {
        AwsException e = assertThrows(AwsException.class, () -> EcrLifecyclePolicy.parse(policy(rule(1,
                "{\"tagStatus\":\"any\",\"countType\":\"imageCountMoreThan\",\"countNumber\":-3}", EXPIRE))));

        assertEquals(PREFIX + "Lifecycle policy validation failure: numeric instance is lower than the required "
                + "minimum (minimum: 1, found: -3)\n'", e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "[]", "\"rules\"", "{\"rules\":[", "{\"rules\":[]} []", "{\"rules\":{}}",
            "{\"rules\":[1]}", "{\"rules\":[{\"rulePriority\":1,\"selection\":[],\"action\":{\"type\":\"expire\"}}]}",
            "{\"rules\":[{\"rulePriority\":1,\"selection\":{\"tagStatus\":\"tagged\",\"tagPrefixList\":\"prod\","
                    + "\"countType\":\"imageCountMoreThan\",\"countNumber\":1},\"action\":{\"type\":\"expire\"}}]}",
            "{\"rules\":[{\"rulePriority\":1,\"selection\":{\"tagStatus\":\"tagged\",\"tagPatternList\":[1],"
                    + "\"countType\":\"imageCountMoreThan\",\"countNumber\":1},\"action\":{\"type\":\"expire\"}}]}",
            "{\"rules\":[{\"rulePriority\":1,\"selection\":{\"tagStatus\":\"any\",\"countType\":\"imageCountMoreThan\","
                    + "\"countNumber\":1},\"action\":\"expire\"}]}"})
    void aDocumentThatIsNotALifecyclePolicyCannotBeMapped(String policy) {
        AwsException e = assertThrows(AwsException.class, () -> EcrLifecyclePolicy.parse(policy));

        assertEquals(PARSING_FAILURE, e.getMessage());
    }

    @Test
    void aMissingDocumentCannotBeMapped() {
        assertEquals(PARSING_FAILURE, assertThrows(AwsException.class, () -> EcrLifecyclePolicy.parse(null))
                .getMessage());
    }

    static Stream<Arguments> invalidRules() {
        String anyCount = "{\"tagStatus\":\"any\",\"countType\":\"imageCountMoreThan\",\"countNumber\":1}";
        return Stream.of(
                Arguments.of("{}", "A lifecycle policy must have at least one rule."),
                Arguments.of("{\"rules\":null}", "A lifecycle policy must have at least one rule."),
                Arguments.of("{\"rules\":[]}", "A lifecycle policy must have at least one rule."),
                Arguments.of(policy(rule(1, anyCount, EXPIRE), rule(1, anyCount, EXPIRE)),
                        "Each rule must have a unique rulePriority."),
                Arguments.of(policy("{\"selection\":" + anyCount + ",\"action\":" + EXPIRE + "}"),
                        "rulePriority must be a positive integer."),
                Arguments.of(policy(rule("\"1\"", anyCount, EXPIRE)), "rulePriority must be a positive integer."),
                Arguments.of(policy(rule("1.0", anyCount, EXPIRE)), "rulePriority must be a positive integer."),
                Arguments.of(policy(rule("0", anyCount, EXPIRE)), "rulePriority must be a positive integer."),
                Arguments.of(policy(rule("2147483648", anyCount, EXPIRE)), "rulePriority must be a positive integer."),
                Arguments.of(policy("{\"rulePriority\":1,\"action\":" + EXPIRE + "}"),
                        "Every rule must have a selection."),
                Arguments.of(policy("{\"rulePriority\":1,\"selection\":" + anyCount + "}"),
                        "Every rule must have an action."),
                Arguments.of(policy(rule(1, "{\"tagStatus\":\"some\",\"countType\":\"imageCountMoreThan\","
                        + "\"countNumber\":1}", EXPIRE)), "tagStatus must be tagged, untagged or any."),
                Arguments.of(policy(rule(1, "{\"countType\":\"imageCountMoreThan\",\"countNumber\":1}", EXPIRE)),
                        "tagStatus must be tagged, untagged or any."),
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
                Arguments.of(policy(rule(1, tagged("[\"*test*1*2*3\",\"test*1*2*3*4*5*6\"]", 1), EXPIRE)),
                        "A tag pattern can have at most 4 wildcards."),
                Arguments.of(policy(rule(1, "{\"tagStatus\":\"any\",\"countType\":\"imageCountLessThan\","
                                + "\"countNumber\":1}", EXPIRE)),
                        "countType must be imageCountMoreThan, sinceImagePushed, sinceImagePulled or "
                                + "sinceImageTransitioned."),
                Arguments.of(policy(rule(1, "{\"tagStatus\":\"any\",\"countType\":\"imageCountMoreThan\","
                                + "\"countUnit\":\"days\",\"countNumber\":1}", EXPIRE)),
                        "countUnit can only be used when countType is sinceImagePushed, sinceImagePulled or "
                                + "sinceImageTransitioned."),
                Arguments.of(policy(rule(1, "{\"tagStatus\":\"any\",\"countType\":\"sinceImagePushed\","
                                + "\"countNumber\":1}", EXPIRE)),
                        "countUnit must be days when countType is sinceImagePushed."),
                Arguments.of(policy(rule(1, "{\"tagStatus\":\"any\",\"countType\":\"sinceImagePulled\","
                                + "\"countUnit\":\"hours\",\"countNumber\":1}", EXPIRE)),
                        "countUnit must be days when countType is sinceImagePulled."),
                Arguments.of(policy(rule(1, "{\"tagStatus\":\"any\",\"countType\":\"imageCountMoreThan\"}", EXPIRE)),
                        "countNumber must be a positive integer."),
                Arguments.of(policy(rule(1, "{\"tagStatus\":\"any\",\"countType\":\"imageCountMoreThan\","
                        + "\"countNumber\":\"5\"}", EXPIRE)), "countNumber must be a positive integer."),
                Arguments.of(policy(rule(1, "{\"tagStatus\":\"any\",\"storageClass\":\"glacier\","
                                + "\"countType\":\"imageCountMoreThan\",\"countNumber\":1}", EXPIRE)),
                        "storageClass must be standard or archive."),
                Arguments.of(policy(rule(1, "{\"tagStatus\":\"any\",\"countType\":\"sinceImageTransitioned\","
                                + "\"countUnit\":\"days\",\"countNumber\":365}", EXPIRE)),
                        "storageClass must be archive when countType is sinceImageTransitioned."),
                Arguments.of(policy(rule(1, "{\"tagStatus\":\"any\",\"storageClass\":\"archive\","
                                + "\"countType\":\"sinceImagePushed\",\"countUnit\":\"days\",\"countNumber\":1}",
                                EXPIRE)),
                        "storageClass must be standard when countType is sinceImagePushed."),
                Arguments.of(policy(rule(1, anyCount, "{\"type\":\"delete\"}")),
                        "The action type must be expire or transition."),
                Arguments.of(policy(rule(1, anyCount, "{\"type\":\"transition\"}")),
                        "targetStorageClass must be archive when the action type is transition."),
                Arguments.of(policy(rule(1, anyCount, "{\"type\":\"transition\",\"targetStorageClass\":\"standard\"}")),
                        "targetStorageClass must be archive when the action type is transition."),
                Arguments.of(policy(rule(1, "{\"tagStatus\":\"any\",\"storageClass\":\"archive\","
                                + "\"countType\":\"sinceImageTransitioned\",\"countUnit\":\"days\","
                                + "\"countNumber\":365}", TRANSITION)),
                        "countType sinceImageTransitioned can only be used with the expire action."),
                Arguments.of(policy(rule(1, anyCount, EXPIRE), rule(2, tagged("[\"prod*\"]", 1), EXPIRE)),
                        "A rule with tagStatus=ANY must have the highest rulePriority."),
                Arguments.of(policy(
                                rule(1, "{\"tagStatus\":\"untagged\",\"countType\":\"imageCountMoreThan\","
                                        + "\"countNumber\":1}", EXPIRE),
                                rule(2, "{\"tagStatus\":\"untagged\",\"countType\":\"sinceImagePushed\","
                                        + "\"countUnit\":\"days\",\"countNumber\":1}", EXPIRE)),
                        "Only one rule selecting a storage class can select untagged images."),
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
                                        + "\"countType\":\"sinceImagePushed\",\"countUnit\":\"days\","
                                        + "\"countNumber\":1}", EXPIRE)),
                        "The rules selecting a storage class must have a unique set of tag prefixes."));
    }

    @ParameterizedTest
    @MethodSource("invalidRules")
    void aDocumentBreakingAPolicyRuleIsRefused(String policy, String reason) {
        AwsException e = assertThrows(AwsException.class, () -> EcrLifecyclePolicy.parse(policy));

        assertEquals("InvalidParameterException", e.getErrorCode());
        assertEquals(PREFIX + "Lifecycle policy validation failure: " + reason + "'", e.getMessage());
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
}
