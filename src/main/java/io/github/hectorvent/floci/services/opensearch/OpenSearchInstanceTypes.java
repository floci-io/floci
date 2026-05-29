package io.github.hectorvent.floci.services.opensearch;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Catalog of AWS OpenSearch instance types and their per-family characteristics.
 * Drives {@code DescribeInstanceTypeDetails} / {@code DescribeInstanceTypeLimits}
 * so SDK clients see family-appropriate metadata instead of one canned response.
 *
 * <p>The single-node Docker container Floci boots is the same regardless of
 * instance type — that's a fundamental fidelity gap an emulator can't close
 * — but the metadata exposed via the management API needs to reflect reality
 * so introspection tools (Terraform, CDK, cost calculators) work correctly.
 *
 * <p>Notable family differences modeled here:
 * <ul>
 *   <li><b>{@code or1.*}</b> — OpenSearch-optimized, S3-backed segments. Only
 *       supports the {@code instance-store} storage type and AWS does not
 *       offer EBS attachment. Volume-size limits are correspondingly large.</li>
 *   <li><b>{@code i3.*}</b> — i3 generation has fixed local NVMe; EBS is
 *       attached but volume-size limits are smaller because the bulk of
 *       storage comes from the instance store.</li>
 *   <li>Everything else (m5/m6g/m7g, r5/r6g/r7g, c5/c6g/c7g, t3) — EBS-only,
 *       3584 GiB max gp2/gp3 volume.</li>
 * </ul>
 */
public final class OpenSearchInstanceTypes {

    /** Subset of {@code InstanceType.InstanceRole} values relevant for emulation. */
    public static final List<String> DATA_ROLE = List.of("Data");

    /**
     * Single per-instance-type entry. Family-level fields live here rather
     * than being recomputed at API call time so the metadata surface matches
     * AWS exactly for the cases where SDK clients introspect it.
     */
    public record InstanceTypeSpec(
            String instanceType,
            String storageType,         // ebs | instance-store | s3-backed
            int minVolumeSizeGib,
            int maxVolumeSizeGib,
            boolean ebsAttachable,
            boolean encryptionEnabled,
            boolean cognitoEnabled,
            boolean appLogsEnabled,
            boolean advancedSecurityEnabled,
            int minInstanceCount,
            int maxInstanceCount) {
    }

    private static final Map<String, InstanceTypeSpec> CATALOG = new LinkedHashMap<>();

    static {
        // EBS-backed general-purpose / compute / memory families. 3584 GiB max
        // mirrors AWS's published gp2/gp3 limit for OpenSearch domains.
        for (String type : List.of(
                "t3.small.search", "t3.medium.search",
                "m5.large.search", "m5.xlarge.search", "m5.2xlarge.search",
                "m6g.large.search", "m6g.xlarge.search", "m6g.2xlarge.search",
                "m7g.large.search", "m7g.xlarge.search", "m7g.2xlarge.search",
                "r5.large.search", "r5.xlarge.search", "r5.2xlarge.search",
                "r6g.large.search", "r6g.xlarge.search", "r6g.2xlarge.search",
                "r7g.large.search", "r7g.xlarge.search", "r7g.2xlarge.search",
                "c5.large.search", "c5.xlarge.search", "c5.2xlarge.search",
                "c6g.large.search", "c6g.xlarge.search", "c6g.2xlarge.search",
                "c7g.large.search", "c7g.xlarge.search", "c7g.2xlarge.search")) {
            CATALOG.put(type, new InstanceTypeSpec(
                    type, "ebs", 10, 3584, true,
                    true, true, true, true,
                    1, 80));
        }

        // i3.* — local NVMe instance store. EBS still attachable for cluster
        // metadata but the bulk of storage is the instance store; we expose
        // smaller EBS limits accordingly.
        for (String type : List.of(
                "i3.large.search", "i3.xlarge.search", "i3.2xlarge.search",
                "i3.4xlarge.search", "i3.8xlarge.search", "i3.16xlarge.search")) {
            CATALOG.put(type, new InstanceTypeSpec(
                    type, "instance-store", 10, 100, true,
                    true, true, true, true,
                    1, 40));
        }

        // or1.* — OpenSearch-optimized, S3-backed segments. EBS is NOT
        // attachable; storage scales with the instance class. Limits per
        // AWS's or1 launch announcement (Nov 2023) and subsequent expansion.
        CATALOG.put("or1.medium.search", new InstanceTypeSpec(
                "or1.medium.search", "s3-backed", 10, 8192, false,
                true, false, true, true, 1, 40));
        CATALOG.put("or1.large.search", new InstanceTypeSpec(
                "or1.large.search", "s3-backed", 10, 16384, false,
                true, false, true, true, 1, 40));
        CATALOG.put("or1.xlarge.search", new InstanceTypeSpec(
                "or1.xlarge.search", "s3-backed", 10, 24576, false,
                true, false, true, true, 1, 40));
        CATALOG.put("or1.2xlarge.search", new InstanceTypeSpec(
                "or1.2xlarge.search", "s3-backed", 10, 36864, false,
                true, false, true, true, 1, 40));
    }

    private OpenSearchInstanceTypes() {
    }

    /** Every supported instance type, in canonical (catalog) order. */
    public static List<String> listAll() {
        return List.copyOf(CATALOG.keySet());
    }

    /**
     * Look up the spec for {@code instanceType}. Unknown types return null —
     * callers should treat that as "AWS has it, we don't model it" and fall
     * back to a generic EBS spec rather than fail; AWS publishes new types
     * faster than emulators can keep up.
     */
    public static InstanceTypeSpec specOf(String instanceType) {
        return CATALOG.get(instanceType);
    }

    /**
     * Spec used when the requested type is not in the catalog. Generic EBS
     * defaults — keeps {@code DescribeInstanceTypeLimits} from 404'ing on
     * brand-new instance types AWS just announced.
     */
    public static InstanceTypeSpec genericFallback(String instanceType) {
        return new InstanceTypeSpec(
                instanceType, "ebs", 10, 3584, true,
                true, true, true, true, 1, 80);
    }
}
