package io.github.hectorvent.floci.core.common;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One AWS partition as AWS publishes it: {@code aws}, {@code aws-cn}, {@code aws-us-gov}, the
 * classified {@code aws-iso*} family and {@code aws-eusc}. Every field is vendored from botocore's
 * {@code partitions.json} and {@code endpoints.json} into {@code aws/partitions.json} by
 * {@code tools/aws/regen_partitions.py}; nothing here is hand-typed. See {@link AwsPartitions}
 * for the catalog and the region-to-partition lookup.
 *
 * @param id                   the partition id, the second segment of every ARN minted in it
 * @param name                 AWS's display name ({@code AWS Standard}, {@code AWS China}, ...)
 * @param dnsSuffix            the suffix every regional endpoint is served under
 * @param dualStackDnsSuffix   the suffix of the dual-stack endpoints
 * @param implicitGlobalRegion the region global services sign with when a client names none
 * @param regionRegex          botocore's shape of a region id in this partition, for regions
 *                             launched after the vendored data was generated
 * @param globalPseudoRegion   the {@code <partition>-global} label the SDKs accept as a region,
 *                             or {@code null} where the partition has none ({@code aws-eusc})
 * @param regions              the published regions, in botocore order
 * @param services             every service key {@code endpoints.json} lists for the partition
 * @param globalServices       services with a partition-wide endpoint, by service key
 * @param supportsDualStack    whether the partition publishes dual-stack endpoints
 * @param supportsFips         whether the partition publishes FIPS endpoints
 */
public record AwsPartition(
        String id,
        String name,
        String dnsSuffix,
        String dualStackDnsSuffix,
        String implicitGlobalRegion,
        Pattern regionRegex,
        String globalPseudoRegion,
        List<Region> regions,
        Set<String> services,
        Map<String, GlobalEndpoint> globalServices,
        boolean supportsDualStack,
        boolean supportsFips) {

    /**
     * A published region.
     *
     * @param id                the region id
     * @param description       AWS's display name, e.g. {@code US East (N. Virginia)}
     * @param optIn             true when an account must enable the region before using it, so
     *                          DescribeRegions omits it unless {@code AllRegions} is set
     * @param s3WebsiteDashForm true when the S3 website endpoint is the legacy
     *                          {@code s3-website-<region>} form rather than {@code s3-website.<region>}
     */
    public record Region(String id, String description, boolean optIn, boolean s3WebsiteDashForm) {
    }

    /**
     * A partition-wide service endpoint, e.g. IAM in {@code aws} at {@code iam.amazonaws.com}
     * signed with {@code us-east-1}. The signing region is the credential-scope region such a
     * client sends; it says which partition the request belongs to and nothing about which
     * region a regional resource should land in.
     *
     * @param regionalized true when the service also has per-region endpoints and the global
     *                     host is used only for a client that names no region (STS, S3)
     */
    public record GlobalEndpoint(String hostname, String signingRegion, boolean regionalized) {
    }

    public AwsPartition {
        regions = List.copyOf(regions);
        services = Set.copyOf(services);
        globalServices = Map.copyOf(globalServices);
    }

    /** True when {@code endpoints.json} lists {@code service} for this partition. */
    public boolean offers(String service) {
        return service != null && services.contains(service);
    }

    public Optional<GlobalEndpoint> globalEndpoint(String service) {
        return Optional.ofNullable(service == null ? null : globalServices.get(service));
    }

    /**
     * True only for the commercial partition: STS is regionalized everywhere, and the global
     * {@code sts.amazonaws.com} host exists in {@code aws} alone.
     */
    public boolean hasGlobalSts() {
        return globalServices.containsKey("sts");
    }

    public Optional<String> pseudoRegion() {
        return Optional.ofNullable(globalPseudoRegion);
    }

    /** True when {@code label} is this partition's {@code <partition>-global} pseudo-region. */
    public boolean isPseudoRegion(String label) {
        return globalPseudoRegion != null && label != null
                && globalPseudoRegion.equals(label.trim().toLowerCase(Locale.ROOT));
    }

    public List<String> regionIds() {
        return regions.stream().map(Region::id).toList();
    }

    public Optional<Region> region(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        return regions.stream().filter(region -> region.id().equals(normalized)).findFirst();
    }

    public boolean isPublishedRegion(String id) {
        return region(id).isPresent();
    }

    /** True when {@code id} has the shape botocore gives this partition's regions. */
    public boolean matchesRegionRegex(String id) {
        return id != null && regionRegex.matcher(id.trim().toLowerCase(Locale.ROOT)).matches();
    }

    /** The regional endpoint host, {@code <service>.<region>.<dnsSuffix>}. */
    public String regionalHostname(String service, String region) {
        return service + "." + region + "." + dnsSuffix;
    }
}
