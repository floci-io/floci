package io.floci.aws.services.ecr;

import io.floci.aws.services.ecr.EcrLifecycleEvaluator.Expiry;
import io.floci.aws.services.ecr.EcrLifecycleEvaluator.Image;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The worked examples of the ECR user guide's lifecycle policy examples page, each with the images it lists and the
 * result it states, then what Floci adds: manifest lists, and the rules it applies no action for.
 */
class EcrLifecycleEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-11-23T12:00:00Z");
    private static final String EXPIRE = "{\"type\":\"expire\"}";
    private static final String TRANSITION = "{\"type\":\"transition\",\"targetStorageClass\":\"archive\"}";

    @Test
    void multipleRulesLowerPriorityRuleSkipsAnImageAHigherOneSelected() {
        List<Image> images = List.of(image("A", 10, "beta-1", "prod-1"), image("B", 9, "beta-2", "prod-2"),
                image("C", 8, "beta-3"));

        assertEquals(List.of("A"), expired(policy(rule(1, tagged("prod*", 1), EXPIRE),
                rule(2, tagged("beta*", 1), EXPIRE)), images));
        assertEquals(List.of("B", "A"), expired(policy(rule(1, tagged("beta*", 1), EXPIRE),
                rule(2, tagged("prod*", 1), EXPIRE)), images));
    }

    @Test
    void multipleTagsInOneRuleSelectOnlyImagesMatchingEveryPattern() {
        List<Image> byAge = List.of(image("A", 12, "alpha-1"), image("B", 11, "beta-1"),
                image("C", 10, "alpha-2", "beta-2"), image("D", 4, "alpha-3"), image("E", 3, "beta-3"),
                image("F", 2, "alpha-4", "beta-4"));
        assertEquals(List.of("C"), expired(policy(rule(1, "{\"tagStatus\":\"tagged\",\"tagPatternList\":"
                + "[\"alpha*\",\"beta*\"],\"countType\":\"sinceImagePushed\",\"countNumber\":5,\"countUnit\":\"days\"}",
                EXPIRE)), byAge));

        List<Image> byCount = List.of(image("A", 10, "alpha-1", "beta-1", "gamma-1"),
                image("B", 9, "alpha-2", "beta-2"), image("C", 8, "alpha-3", "beta-3", "gamma-2"));
        assertEquals(List.of("B", "A"), expired(policy(rule(1, "{\"tagStatus\":\"tagged\",\"tagPatternList\":"
                + "[\"alpha*\",\"beta*\"],\"countType\":\"imageCountMoreThan\",\"countNumber\":1}", EXPIRE)), byCount));
    }

    @Test
    void anAnyRuleKeepsOnlyTheNewestImages() {
        List<Image> images = List.of(image("A", 4, "alpha-1"), image("B", 3, "beta-1"), image("C", 2),
                image("D", 1, "alpha-2"));

        assertEquals(List.of("C", "B", "A"), expired(policy(rule(1, any(1), EXPIRE)), images));
    }

    /** "Images A, B, and D are expired": the last rule can mark only D, the one image no earlier rule selected. */
    @Test
    void anAnyRuleAfterTaggedAndUntaggedRulesMarksOnlyWhatTheySkipped() {
        List<Image> images = List.of(image("A", Duration.ofDays(4), "alpha-1", "beta-1"),
                image("B", Duration.ofDays(3)), image("C", Duration.ofDays(2), "alpha-2"),
                image("D", Duration.ofDays(1), "git hash"), image("E", Duration.ofDays(1).minusMinutes(1)));

        assertEquals(List.of("A", "B", "D"), expired(policy(rule(1, tagged("alpha*", 1), EXPIRE),
                rule(2, "{\"tagStatus\":\"untagged\",\"countType\":\"sinceImagePushed\",\"countUnit\":\"days\","
                        + "\"countNumber\":1}", EXPIRE),
                rule(3, any(1), EXPIRE)), images));
    }

    @Test
    void anUntaggedRuleKeepsOneUntaggedImage() {
        List<Image> images = List.of(image("A", 3), image("B", 2), image("C", 1), image("T", 5, "latest"));

        assertEquals(List.of("B", "A"), expired(policy(rule(1, "{\"tagStatus\":\"untagged\","
                + "\"countType\":\"imageCountMoreThan\",\"countNumber\":1}", EXPIRE)), images));
    }

    @Test
    void aPrefixListSelectsImagesWithATagStartingWithEachPrefix() {
        List<Image> images = List.of(image("A", 5, "prod-1", "v1"), image("B", 4, "prod-2"), image("C", 3, "v2-prod"),
                image("D", 2, "prod-3", "v3"));

        assertEquals(List.of("A"), expired(policy(rule(1, "{\"tagStatus\":\"tagged\",\"tagPrefixList\":"
                + "[\"prod\",\"v\"],\"countType\":\"imageCountMoreThan\",\"countNumber\":1}", EXPIRE)), images));
    }

    @Test
    void aWildcardMatchesAnyRunOfCharactersIncludingNone() {
        List<Image> images = List.of(image("A", 5, "prod"), image("B", 4, "repo-production"), image("C", 3, "dev"),
                image("D", 2, "prod-team"));

        assertEquals(List.of("B", "A"), expired(policy(rule(1, tagged("*prod*", 1), EXPIRE)), images));
    }

    /** "Older than" the count: an image pushed exactly that many days ago stays. */
    @Test
    void sinceImagePushedExpiresOnlyImagesOlderThanTheCount() {
        List<Image> images = List.of(image("A", Duration.ofDays(7).plusSeconds(1)), image("B", Duration.ofDays(7)),
                image("C", Duration.ofDays(1)));

        assertEquals(List.of("A"), expired(policy(rule(1, "{\"tagStatus\":\"any\",\"countType\":\"sinceImagePushed\","
                + "\"countUnit\":\"days\",\"countNumber\":7}", EXPIRE)), images));
    }

    @Test
    void imagesPushedAtOnceAreKeptInDigestOrder() {
        List<Image> images = List.of(image("B", 1), image("A", 1), image("C", 1));

        assertEquals(List.of("C"), expired(policy(rule(1, any(2), EXPIRE)), images));
    }

    /**
     * "If an image is referenced by a manifest list, it cannot be expired or archived without the manifest list being
     * deleted or archived first."
     */
    @Test
    void anImageAManifestListNamesStillCountsButNeverExpires() {
        List<Image> images = List.of(new Image("child-1", List.of(), NOW.minus(Duration.ofDays(1)), true),
                new Image("child-2", List.of(), NOW.minus(Duration.ofDays(1)), true), image("orphan-1", 8),
                image("orphan-2", 7), image("index", 1, "v1"));

        assertEquals(List.of("orphan-2", "orphan-1"), expired(policy(rule(1, "{\"tagStatus\":\"untagged\","
                + "\"countType\":\"imageCountMoreThan\",\"countNumber\":1}", EXPIRE)), images));
    }

    /** Floci has no archive storage: a transition rule moves nothing, but lower rules still cannot take its images. */
    @Test
    void aTransitionRuleExpiresNothingAndKeepsLowerRulesOffWhatItSelects() {
        List<Image> images = List.of(image("A", 40, "prod-1"), image("B", 35, "dev-1"), image("C", 1, "prod-2"));

        assertEquals(List.of("B"), expired(policy(rule(1, "{\"tagStatus\":\"tagged\",\"tagPatternList\":[\"prod*\"],"
                        + "\"countType\":\"sinceImagePushed\",\"countUnit\":\"days\",\"countNumber\":30}", TRANSITION),
                rule(2, "{\"tagStatus\":\"any\",\"countType\":\"sinceImagePushed\",\"countUnit\":\"days\","
                        + "\"countNumber\":30}", EXPIRE)), images));
    }

    /** Floci records no pulls, so a pull-age rule marks nothing, yet keeps lower rules off what it selects. */
    @Test
    void aSinceImagePulledRuleExpiresNothingAndKeepsLowerRulesOffWhatItSelects() {
        List<Image> images = List.of(image("A", 100, "prod-1"), image("B", 100, "dev-1"));

        assertEquals(List.of("B"), expired(policy(rule(1, "{\"tagStatus\":\"tagged\",\"tagPatternList\":[\"prod*\"],"
                        + "\"countType\":\"sinceImagePulled\",\"countUnit\":\"days\",\"countNumber\":1}", EXPIRE),
                rule(2, "{\"tagStatus\":\"any\",\"countType\":\"sinceImagePushed\",\"countUnit\":\"days\","
                        + "\"countNumber\":1}", EXPIRE)), images));
    }

    /** Every image is in standard storage, so an archive rule selects none and keeps no later rule off them. */
    @Test
    void anArchiveRuleSelectsNoImage() {
        List<Image> images = List.of(image("A", 400), image("B", 500, "v1"));

        assertEquals(List.of("A", "B"), expired(policy(rule(1, "{\"tagStatus\":\"any\",\"storageClass\":\"archive\","
                        + "\"countType\":\"sinceImageTransitioned\",\"countUnit\":\"days\",\"countNumber\":365}",
                        EXPIRE),
                rule(2, "{\"tagStatus\":\"any\",\"countType\":\"sinceImagePushed\",\"countUnit\":\"days\","
                        + "\"countNumber\":30}", EXPIRE)), images));
    }

    @Test
    void aPatternMatchesTheWholeTag() {
        List<Image> images = List.of(image("A", 5, "preprod"), image("B", 4, "prod-1"), image("C", 3, "prod-2"));

        assertEquals(List.of("B"), expired(policy(rule(1, tagged("prod*", 1), EXPIRE)), images));
    }

    /** Rules apply in priority order whatever order the document lists them in. */
    @Test
    void anExpiryNamesTheRuleThatMarkedIt() {
        List<Image> images = List.of(image("A", 5, "prod-1"), image("B", 4, "prod-2"), image("C", 3),
                image("D", 10, "dev"));

        assertEquals(List.of(new Expiry("A", 4), new Expiry("D", 9)), EcrLifecycleEvaluator.expiries(
                EcrLifecyclePolicy.parse(policy(rule(9, any(2), EXPIRE), rule(4, tagged("prod*", 1), EXPIRE),
                        rule(6, "{\"tagStatus\":\"untagged\",\"countType\":\"sinceImagePushed\","
                                + "\"countUnit\":\"days\",\"countNumber\":30}", EXPIRE))), images, NOW));
    }

    private static List<String> expired(String policy, List<Image> images) {
        return EcrLifecycleEvaluator.expiries(EcrLifecyclePolicy.parse(policy), images, NOW).stream()
                .map(Expiry::digest).toList();
    }

    private static Image image(String digest, int pushedDaysAgo, String... tags) {
        return image(digest, Duration.ofDays(pushedDaysAgo), tags);
    }

    private static Image image(String digest, Duration pushedAgo, String... tags) {
        return new Image(digest, List.of(tags), NOW.minus(pushedAgo), false);
    }

    private static String policy(String... rules) {
        return "{\"rules\":[" + String.join(",", rules) + "]}";
    }

    private static String rule(int priority, String selection, String action) {
        return "{\"rulePriority\":" + priority + ",\"selection\":" + selection + ",\"action\":" + action + "}";
    }

    private static String tagged(String pattern, int count) {
        return "{\"tagStatus\":\"tagged\",\"tagPatternList\":[\"" + pattern
                + "\"],\"countType\":\"imageCountMoreThan\",\"countNumber\":" + count + "}";
    }

    private static String any(int count) {
        return "{\"tagStatus\":\"any\",\"countType\":\"imageCountMoreThan\",\"countNumber\":" + count + "}";
    }
}
