package io.github.hectorvent.floci.services.ec2;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The EC2 resource-id prefix vocabulary: which AWS resource type an id like {@code vol-0abc}
 * belongs to, and how that type appears in an ARN.
 *
 * <p>EC2 API models carry ids, not ARNs — real AWS does the same, and floci's models mirror it.
 * Anything that has to name an EC2 resource in ARN form (Resource Groups Tagging's
 * {@code GetResources}, {@code DescribeTags}' {@code resourceType} column) has to go from the id
 * back to the type, so the mapping lives here once rather than being restated per call site.
 *
 * <p>Type strings are the ones AWS uses in EC2 ARNs
 * ({@code arn:aws:ec2:<region>:<account>:<type>/<id>}), not the {@code AWS::EC2::*}
 * CloudFormation spellings.
 */
public final class Ec2ResourceIds {

    private Ec2ResourceIds() {}

    /**
     * Id prefix to ARN resource type. Iteration order is longest prefix first so that
     * {@code tgw-attach-} is matched before {@code tgw-} and {@code vpce-svc-} before
     * {@code vpce-}.
     */
    private static final Map<String, String> TYPE_BY_PREFIX = buildPrefixMap();

    /**
     * Types whose ARN omits the account segment. AWS documents images and snapshots as
     * {@code arn:aws:ec2:<region>::image/<id>} — the sharing model makes them account-less.
     */
    private static final java.util.Set<String> ACCOUNTLESS_TYPES =
            java.util.Set.of("image", "snapshot");

    private static Map<String, String> buildPrefixMap() {
        // Declared shortest-to-longest for readability; sorted below.
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("i-", "instance");
        raw.put("h-", "dedicated-host");
        raw.put("fl-", "vpc-flow-log");
        raw.put("pl-", "prefix-list");
        raw.put("sg-", "security-group");
        raw.put("lt-", "launch-template");
        raw.put("pg-", "placement-group");
        raw.put("cr-", "capacity-reservation");
        raw.put("vol-", "volume");
        raw.put("ami-", "image");
        raw.put("aki-", "image");
        raw.put("ari-", "image");
        raw.put("vpc-", "vpc");
        raw.put("eni-", "network-interface");
        raw.put("rtb-", "route-table");
        raw.put("acl-", "network-acl");
        raw.put("igw-", "internet-gateway");
        raw.put("nat-", "natgateway");
        raw.put("sgr-", "security-group-rule");
        raw.put("sir-", "spot-instances-request");
        raw.put("cgw-", "customer-gateway");
        raw.put("vgw-", "vpn-gateway");
        raw.put("vpn-", "vpn-connection");
        raw.put("pcx-", "vpc-peering-connection");
        raw.put("tgw-", "transit-gateway");
        raw.put("key-", "key-pair");
        raw.put("snap-", "snapshot");
        raw.put("dopt-", "dhcp-options");
        raw.put("eigw-", "egress-only-internet-gateway");
        raw.put("ipam-", "ipam");
        raw.put("vpce-", "vpc-endpoint");
        raw.put("fleet-", "fleet");
        raw.put("subnet-", "subnet");
        raw.put("eipalloc-", "elastic-ip");
        raw.put("vpce-svc-", "vpc-endpoint-service");
        raw.put("tgw-rtb-", "transit-gateway-route-table");
        raw.put("tgw-attach-", "transit-gateway-attachment");

        Map<String, String> sorted = new LinkedHashMap<>();
        raw.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()))
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        // Collections.unmodifiableMap, not Map.copyOf: the longest-prefix-first iteration order
        // is the matching rule, and Map.copyOf does not preserve it.
        return java.util.Collections.unmodifiableMap(sorted);
    }

    /**
     * The ARN resource type for an EC2 resource id, or {@code "unknown"} when the prefix is not
     * one this vocabulary knows. {@code "unknown"} is what {@code DescribeTags} has always
     * reported for an unrecognised id, so callers that surface it keep that behaviour.
     */
    public static String resourceType(String resourceId) {
        if (resourceId == null) {
            return "unknown";
        }
        for (Map.Entry<String, String> entry : TYPE_BY_PREFIX.entrySet()) {
            if (resourceId.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "unknown";
    }

    /**
     * The ARN for an EC2 resource id, or {@code null} when the id's prefix is unrecognised —
     * a guessed ARN is worse than none, because callers join on it.
     */
    public static String arn(String region, String accountId, String resourceId) {
        String type = resourceType(resourceId);
        if ("unknown".equals(type)) {
            return null;
        }
        String account = ACCOUNTLESS_TYPES.contains(type) || accountId == null ? "" : accountId;
        return "arn:aws:ec2:" + (region == null ? "" : region) + ":" + account + ":" + type + "/" + resourceId;
    }
}
