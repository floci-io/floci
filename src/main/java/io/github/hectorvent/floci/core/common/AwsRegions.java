package io.github.hectorvent.floci.core.common;

import java.util.List;
import java.util.Set;

/**
 * Region facade over the vendored partition catalog ({@link AwsPartitions}). Single source of
 * truth for every feature that enumerates or classifies regions: EC2 DescribeRegions, the ECR
 * mirror hostnames EKS nodes get, TLS certificate SANs, hostname parsing, and the partition
 * segment of every ARN.
 */
public final class AwsRegions {

    /**
     * Every published AWS region id, across all partitions.
     *
     * <p>Distinct from {@link #advertised(String)}, and deliberately a superset of every advertised
     * list: {@code advertised} is what this emulator <em>advertises</em> in one partition, while
     * {@code KNOWN_IDS} is for <em>recognising</em> a region id that a client wrote, most
     * importantly inside a hostname, where the alternative is a shape like
     * {@code [a-z]{2}-[a-z-]+-\d+} that also matches perfectly ordinary strings ({@code my-cd-1},
     * {@code eu-team-2}).
     *
     * <p>It follows botocore's published lists ({@code make aws-data-sync}); the cost of a
     * region newer than the vendored data is documented at each call site, because it differs
     * per caller.
     */
    public static final Set<String> KNOWN_IDS = AwsPartitions.allRegionIds();

    /** The partition every unrecognised region falls back to: the commercial one. */
    public static final String DEFAULT_PARTITION = AwsPartitions.COMMERCIAL_ID;

    /** The DNS suffix of the commercial partition, shared with {@code aws-us-gov}. */
    public static final String DEFAULT_DNS_SUFFIX = "amazonaws.com"; // partition-literal: the commercial suffix, by definition

    /**
     * The regions a partition publishes, in botocore order, e.g. the 34 commercial regions for
     * {@code aws} or {@code cn-north-1} and {@code cn-northwest-1} for {@code aws-cn}.
     *
     * @throws IllegalArgumentException when {@code partitionId} names no published partition
     */
    public static List<String> advertised(String partitionId) {
        return AwsPartitions.byId(partitionId).regionIds();
    }

    /** True when {@code label} is exactly a published AWS region id, case-insensitively. */
    public static boolean isRegionId(String label) {
        return AwsPartitions.isPublishedRegion(label);
    }

    /**
     * The partition id a region belongs to: {@code aws}, {@code aws-us-gov}, {@code aws-cn}, or
     * one of the classified partitions. This is the second segment of every ARN minted for a
     * resource in that region. A {@code <partition>-global} pseudo-region resolves to its
     * partition.
     *
     * <p>A null, blank or unrecognised region gives {@code aws}, which is what the AWS SDKs do
     * with a region they do not know. Blank is the common case for a global-service ARN
     * ({@code arn:aws:iam::…}); callers that know the deployment's partition should mint those
     * through {@link RegionResolver#buildGlobalArn} instead of relying on this fallback.
     */
    public static String partitionFor(String region) {
        return AwsPartitions.forRegionOrCommercial(region).id();
    }

    /**
     * The DNS suffix endpoints in a region's partition are served under, e.g.
     * {@code amazonaws.com.cn} for {@code cn-north-1}. Same fallback rule as
     * {@link #partitionFor}.
     */
    public static String dnsSuffixFor(String region) {
        return AwsPartitions.forRegionOrCommercial(region).dnsSuffix();
    }

    /** The private EC2 DNS domain shared by instances, network interfaces, ECS and EKS. */
    public static String ec2PrivateDnsDomain(String region) {
        String effectiveRegion = region == null || region.isBlank() ? "us-east-1" : region; // partition-literal: legacy private DNS domain
        return "us-east-1".equals(effectiveRegion) // partition-literal: ec2.internal is specific to us-east-1
                ? "ec2.internal" : effectiveRegion + ".compute.internal";
    }

    /** The private DNS name for an IPv4 EC2 address in the request's region. */
    public static String ec2PrivateIpDnsName(String address, String region) {
        return "ip-" + address.replace('.', '-') + "." + ec2PrivateDnsDomain(region);
    }

    private AwsRegions() {
    }
}
