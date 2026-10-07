package io.github.hectorvent.floci.core.common;

import java.util.UUID;

/**
 * The CloudFront distribution AWS puts in front of a custom domain, an edge-optimized API Gateway
 * domain or an AppSync custom domain: a distribution domain name on the configured CloudFront
 * suffix, and the CloudFront hosted zone of the region's partition, which a Route 53 alias record
 * targets. The zone is null in a partition without CloudFront.
 *
 * @param domainName   the distribution domain name, {@code d<13 hex>.<suffix>}
 * @param hostedZoneId the partition's CloudFront hosted zone, or null where there is none
 */
public record CloudFrontEdgeDomain(String domainName, String hostedZoneId) {

    public static CloudFrontEdgeDomain create(String region, String domainSuffix) {
        String distributionId = "d" + UUID.randomUUID().toString().replace("-", "").substring(0, 13);
        String hostedZoneId = AwsRegionFacts.cloudFrontHostedZoneId(
                AwsPartitions.forRegionOrCommercial(region).id()).orElse(null);
        return new CloudFrontEdgeDomain(distributionId + "." + domainSuffix, hostedZoneId);
    }
}
