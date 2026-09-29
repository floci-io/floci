package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The per-region and per-partition constants AWS publishes only as prose tables, vendored in
 * {@code aws/region-facts.json} by {@code tools/aws/regen_region_facts.py} from the Terraform
 * provider and the CDK: load balancer and S3 website hosted zone ids, the CloudFront hosted
 * zone per partition, the SAML sign-on URL per partition and the VPC endpoint service-name
 * prefixes. Every accessor is {@link Optional}: the ISO and EUSC partitions have no published
 * hosted zones, and a service that needs one there has nothing faithful to answer.
 */
public final class AwsRegionFacts {

    static final String RESOURCE_NAME = "aws/region-facts.json";
    private static final String DEFAULT_VPC_ENDPOINT_PREFIX = "com.amazonaws";

    private static final class Holder {
        static final JsonNode FACTS = load();
    }

    private AwsRegionFacts() {
    }

    /** Classic load balancers share the Application load balancer hosted zone. */
    public static Optional<String> classicElbHostedZoneId(String region) {
        return regionText(region, "classicElbHostedZoneId");
    }

    public static Optional<String> albHostedZoneId(String region) {
        return regionText(region, "albHostedZoneId");
    }

    /** Network load balancers have their own hosted zone in every region, distinct from the ALB one. */
    public static Optional<String> nlbHostedZoneId(String region) {
        return regionText(region, "nlbHostedZoneId");
    }

    public static Optional<String> s3WebsiteHostedZoneId(String region) {
        return regionText(region, "s3WebsiteHostedZoneId");
    }

    /** Published for {@code aws} and {@code aws-cn} only, where CloudFront exists. */
    public static Optional<String> cloudFrontHostedZoneId(String partition) {
        return partitionText(partition, "cloudfrontHostedZoneId");
    }

    /** The console's SAML endpoint; absent for {@code aws-iso-e}, {@code aws-iso-f} and {@code aws-eusc}. */
    public static Optional<String> samlSignOnUrl(String partition) {
        return partitionText(partition, "samlSignOnUrl");
    }

    /** {@code com.amazonaws.vpce}, {@code cn.com.amazonaws.vpce}, ...: the reversed DNS suffix plus {@code .vpce}. */
    public static Optional<String> vpcEndpointServiceNamePrefix(String partition) {
        return partitionText(partition, "vpcEndpointServiceNamePrefix");
    }

    /**
     * The AWS-owned VPC endpoint service name for {@code service} in {@code region}, e.g.
     * {@code com.amazonaws.us-east-1.s3} or {@code cn.com.amazonaws.cn-north-1.s3}. The reversed
     * prefix applies only to the (region, service) pairs the CDK lists; every other pair keeps
     * {@code com.amazonaws}, GovCloud included. China's {@code transcribe} also carries a
     * trailing {@code .cn}.
     */
    public static String vpcEndpointServiceName(String region, String service) {
        JsonNode entry = regionNode(region);
        String prefix = DEFAULT_VPC_ENDPOINT_PREFIX;
        String suffix = "";
        if (entry != null) {
            if (contains(entry.path("vpcEndpointPrefixServices"), service)) {
                prefix = entry.path("vpcEndpointExceptionPrefix").asText(DEFAULT_VPC_ENDPOINT_PREFIX);
            }
            if (contains(entry.path("vpcEndpointCnSuffixServices"), service)) {
                suffix = ".cn";
            }
        }
        return prefix + "." + region + "." + service + suffix;
    }

    /** The services whose endpoint name in {@code region} uses the reversed-suffix prefix. */
    public static Set<String> vpcEndpointPrefixServices(String region) {
        Set<String> services = new LinkedHashSet<>();
        JsonNode entry = regionNode(region);
        if (entry != null) {
            entry.path("vpcEndpointPrefixServices").forEach(node -> services.add(node.asText()));
        }
        return services;
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode node : array) {
            if (node.asText().equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static Optional<String> regionText(String region, String field) {
        JsonNode entry = regionNode(region);
        return entry == null || !entry.hasNonNull(field) ? Optional.empty() : Optional.of(entry.get(field).asText());
    }

    private static Optional<String> partitionText(String partition, String field) {
        if (partition == null) {
            return Optional.empty();
        }
        JsonNode entry = Holder.FACTS.path("partitions").get(partition.trim().toLowerCase(Locale.ROOT));
        return entry == null || !entry.hasNonNull(field) ? Optional.empty() : Optional.of(entry.get(field).asText());
    }

    private static JsonNode regionNode(String region) {
        if (region == null || region.isBlank()) {
            return null;
        }
        return Holder.FACTS.path("regions").get(region.trim().toLowerCase(Locale.ROOT));
    }

    private static JsonNode load() {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(RESOURCE_NAME)) {
            if (in == null) {
                throw new IllegalStateException("Region facts resource not found: " + RESOURCE_NAME);
            }
            JsonNode root = new ObjectMapper().readTree(in);
            if (!root.path("regions").isObject() || !root.path("partitions").isObject()) {
                throw new IllegalStateException(RESOURCE_NAME + " lacks the regions or partitions object");
            }
            return root;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read the region facts " + RESOURCE_NAME, e);
        }
    }
}
