package io.github.hectorvent.floci.core.common;

import java.util.List;
import java.util.Locale;
import java.util.Set;

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

    private AwsRegions() {
    }
}
