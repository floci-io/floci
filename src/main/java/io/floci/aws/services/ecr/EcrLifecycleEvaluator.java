package io.floci.aws.services.ecr;

import io.floci.aws.services.ecr.EcrLifecyclePolicy.ActionType;
import io.floci.aws.services.ecr.EcrLifecyclePolicy.Rule;
import io.floci.aws.services.ecr.EcrLifecyclePolicy.StorageClass;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Decides which images a lifecycle policy expires, by the evaluation rules of the ECR user guide: every rule selects
 * the images its tag filter matches and marks those its count rule finds, oldest first, and rules apply in
 * {@code rulePriority} order, a rule never marking an image a higher-priority rule selected.
 *
 * <p>Floci keeps every image in standard storage and records no pulls, so a rule selecting archived images selects
 * none, and {@code sinceImagePulled} and {@code transition} rules mark nothing for expiry while still keeping
 * lower-priority rules off the images they select.
 *
 * @see <a href="https://docs.aws.amazon.com/AmazonECR/latest/userguide/LifecyclePolicies.html">Lifecycle policy
 *     evaluation rules</a>
 */
final class EcrLifecycleEvaluator {

    /**
     * An image of the repository: its manifest digest, its tags, when it was pushed, and whether a manifest list the
     * repository holds names it, which keeps it from expiring.
     */
    record Image(String digest, List<String> tags, Instant pushedAt, boolean inManifestList) {}

    /** An image the policy expires, with the priority of the rule that marked it. */
    record Expiry(String digest, int rulePriority) {}

    /** Newest first, as {@code imageCountMoreThan} keeps the newest; the digest settles a tie. */
    private static final Comparator<Image> NEWEST_FIRST = Comparator.comparing(Image::pushedAt).reversed()
            .thenComparing(Image::digest);

    private EcrLifecycleEvaluator() {
    }

    static List<Expiry> expiries(EcrLifecyclePolicy policy, List<Image> images, Instant now) {
        List<Rule> rules = new ArrayList<>(policy.rules());
        rules.sort(Comparator.comparingInt(Rule::priority));
        Set<String> selectedByHigherRules = new HashSet<>();
        List<Expiry> expiries = new ArrayList<>();
        for (Rule rule : rules) {
            List<Image> selected = images.stream().filter(image -> selects(rule, image)).sorted(NEWEST_FIRST).toList();
            for (Image marked : marked(rule, selected, now)) {
                if (!selectedByHigherRules.contains(marked.digest()) && !marked.inManifestList()
                        && rule.action() == ActionType.EXPIRE) {
                    expiries.add(new Expiry(marked.digest(), rule.priority()));
                }
            }
            selected.forEach(image -> selectedByHigherRules.add(image.digest()));
        }
        return expiries;
    }

    private static boolean selects(Rule rule, Image image) {
        if (rule.storageClass() != StorageClass.STANDARD) {
            return false;
        }
        return switch (rule.tagStatus()) {
            case ANY -> true;
            case UNTAGGED -> image.tags().isEmpty();
            case TAGGED -> !image.tags().isEmpty() && (rule.tagPatterns().isEmpty()
                    ? rule.tagPrefixes().stream().allMatch(prefix -> image.tags().stream().anyMatch(
                            tag -> tag.startsWith(prefix)))
                    : rule.tagPatterns().stream().map(EcrLifecycleEvaluator::wildcard).allMatch(
                            pattern -> image.tags().stream().anyMatch(tag -> pattern.matcher(tag).matches())));
        };
    }

    /** The images, newest first, that the rule's count finds among those it selects. */
    private static List<Image> marked(Rule rule, List<Image> selected, Instant now) {
        return switch (rule.countType()) {
            case IMAGE_COUNT_MORE_THAN -> selected.subList(Math.min(rule.countNumber(), selected.size()),
                    selected.size());
            case SINCE_IMAGE_PUSHED -> {
                Instant cutoff = now.minus(Duration.ofDays(rule.countNumber()));
                yield selected.stream().filter(image -> image.pushedAt().isBefore(cutoff)).toList();
            }
            case SINCE_IMAGE_PULLED, SINCE_IMAGE_TRANSITIONED -> List.of();
        };
    }

    /** A tag pattern, where {@code *} matches any run of characters, none included. */
    private static Pattern wildcard(String pattern) {
        return Pattern.compile(Arrays.stream(pattern.split("\\*", -1)).map(Pattern::quote)
                .collect(Collectors.joining(".*")));
    }
}
