package io.github.hectorvent.floci.core.common;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Catalog of AWS regions the emulator advertises. Single source of truth for
 * every feature that enumerates regions (EC2 DescribeRegions, EKS ECR-mirror
 * hostnames, ...).
 */
public final class AwsRegions {

    public static final List<String> ALL = List.of(
            "us-east-1", "us-east-2", "us-west-1", "us-west-2",
            "eu-west-1", "eu-west-2", "eu-west-3", "eu-central-1",
            "ap-northeast-1", "ap-northeast-2", "ap-southeast-1", "ap-southeast-2",
            "ap-south-1", "sa-east-1", "ca-central-1");

    /**
     * Every published AWS region id, across the commercial, GovCloud and China partitions.
     *
     * <p>Distinct from {@link #ALL}, and deliberately a superset of it. {@code ALL} is what
     * this emulator <em>advertises</em> — what DescribeRegions returns. {@code KNOWN_IDS} is
     * for <em>recognising</em> a region id that a client wrote, most importantly inside a
     * hostname, where the alternative is a shape like {@code [a-z]{2}-[a-z-]+-\d+} that also
     * matches perfectly ordinary strings ({@code my-cd-1}, {@code eu-team-2}).
     *
     * <p>Sourced from the AWS General Reference endpoint tables. It needs an entry when AWS
     * launches a region; the cost of a missing one is documented at each call site, because it
     * differs per caller.
     */
    public static final Set<String> KNOWN_IDS = Set.of(
            "us-east-1", "us-east-2", "us-west-1", "us-west-2",
            "af-south-1",
            "ap-east-1", "ap-east-2",
            "ap-south-1", "ap-south-2",
            "ap-northeast-1", "ap-northeast-2", "ap-northeast-3",
            "ap-southeast-1", "ap-southeast-2", "ap-southeast-3", "ap-southeast-4",
            "ap-southeast-5", "ap-southeast-6", "ap-southeast-7",
            "ca-central-1", "ca-west-1",
            "eu-central-1", "eu-central-2",
            "eu-west-1", "eu-west-2", "eu-west-3",
            "eu-north-1", "eu-south-1", "eu-south-2",
            "il-central-1",
            "me-central-1", "me-south-1",
            "mx-central-1",
            "sa-east-1",
            "us-gov-east-1", "us-gov-west-1",
            "cn-north-1", "cn-northwest-1");

    /** True when {@code label} is exactly an AWS region id, case-insensitively. */
    public static boolean isRegionId(String label) {
        return label != null && KNOWN_IDS.contains(label.toLowerCase(Locale.ROOT));
    }

    /** The partition every region falls back to: the commercial one. */
    public static final String DEFAULT_PARTITION = "aws";

    /** The DNS suffix of the commercial partition, shared with {@code aws-us-gov}. */
    public static final String DEFAULT_DNS_SUFFIX = "amazonaws.com";

    private record PartitionRule(Pattern regionPattern, String partition, String dnsSuffix) {
    }

    /**
     * Region-to-partition rules, transcribed from the AWS SDKs' own public partition metadata
     * (botocore's {@code partitions.json}, MIT-licensed and openly published at
     * https://github.com/boto/botocore/blob/develop/botocore/data/partitions.json). The patterns
     * are that file's {@code regionRegex} values verbatim, so they stay comparable against it.
     *
     * <p>Only the non-commercial partitions need a rule: the commercial {@code aws} partition is
     * the fallback, and {@code aws-us-gov} shares its DNS suffix, which is why GovCloud carries a
     * rule for the partition id alone. The rules are mutually exclusive by construction (nothing
     * matching {@code us-gov-*}, {@code us-iso-*} or {@code us-isob-*} matches the commercial
     * shape, because {@code \w} does not cross a hyphen), so evaluation order is not load-bearing.
     *
     * <p>An unrecognised region resolves to {@code aws}. That is what the SDKs themselves do, and
     * it keeps a region AWS launches tomorrow working rather than failing closed.
     */
    private static final List<PartitionRule> PARTITION_RULES = List.of(
            new PartitionRule(Pattern.compile("^cn-\\w+-\\d+$"), "aws-cn", "amazonaws.com.cn"),
            new PartitionRule(Pattern.compile("^us-gov-\\w+-\\d+$"), "aws-us-gov", DEFAULT_DNS_SUFFIX),
            new PartitionRule(Pattern.compile("^us-iso-\\w+-\\d+$"), "aws-iso", "c2s.ic.gov"),
            new PartitionRule(Pattern.compile("^us-isob-\\w+-\\d+$"), "aws-iso-b", "sc2s.sgov.gov"),
            new PartitionRule(Pattern.compile("^eu-isoe-\\w+-\\d+$"), "aws-iso-e", "cloud.adc-e.uk"),
            new PartitionRule(Pattern.compile("^us-isof-\\w+-\\d+$"), "aws-iso-f", "csp.hci.ic.gov"),
            new PartitionRule(Pattern.compile("^eusc-de-\\w+-\\d+$"), "aws-eusc", "amazonaws.eu"));

    /**
     * The partition id a region belongs to: {@code aws}, {@code aws-us-gov}, {@code aws-cn}, or
     * one of the classified partitions. This is the second segment of every ARN minted for a
     * resource in that region.
     *
     * <p>A null, blank or unrecognised region gives {@code aws}. Blank is not an error case: many
     * ARNs are global and carry no region at all ({@code arn:aws:iam::…}), and those keep the
     * commercial partition because the resource's own region cannot say otherwise.
     */
    public static String partitionFor(String region) {
        PartitionRule rule = ruleFor(region);
        return rule == null ? DEFAULT_PARTITION : rule.partition();
    }

    /**
     * The DNS suffix endpoints in a region's partition are served under, e.g.
     * {@code amazonaws.com.cn} for {@code cn-north-1}. Same fallback rule as
     * {@link #partitionFor}.
     */
    public static String dnsSuffixFor(String region) {
        PartitionRule rule = ruleFor(region);
        return rule == null ? DEFAULT_DNS_SUFFIX : rule.dnsSuffix();
    }

    private static PartitionRule ruleFor(String region) {
        if (region == null || region.isBlank()) {
            return null;
        }
        String normalized = region.trim().toLowerCase(Locale.ROOT);
        for (PartitionRule rule : PARTITION_RULES) {
            if (rule.regionPattern().matcher(normalized).matches()) {
                return rule;
            }
        }
        return null;
    }

    private AwsRegions() {
    }
}
