package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ReservedTags;
import io.github.hectorvent.floci.services.eks.model.EksClusterRuntimeConfig;
import io.github.hectorvent.floci.services.eks.model.KubernetesNetworkConfig;

import java.util.Map;
import java.util.Set;

/**
 * Resolves Floci's input-only EKS runtime tags and validates the k3s network contract.
 * EksService consumes this at creation and EksClusterManager uses the persisted result at runtime.
 */
final class EksRuntimeConfig {

    private static final String DEFAULT_POD_IPV4_CIDR = "10.42.0.0/16";

    private static final Set<String> SUPPORTED_TAGS = Set.of(
            ReservedTags.EKS_IMAGE_KEY,
            ReservedTags.EKS_NODE_IPV4_ADDRESS_KEY,
            ReservedTags.EKS_POD_IPV4_CIDR_KEY);

    private EksRuntimeConfig() {
    }

    static EksClusterRuntimeConfig fromCreateTags(Map<String, String> tags) {
        rejectUnknownReservedTags(tags);
        return new EksClusterRuntimeConfig(
                tagValue(tags, ReservedTags.EKS_IMAGE_KEY),
                tagValue(tags, ReservedTags.EKS_NODE_IPV4_ADDRESS_KEY),
                tagValue(tags, ReservedTags.EKS_POD_IPV4_CIDR_KEY));
    }

    static void validate(EksClusterRuntimeConfig runtime, KubernetesNetworkConfig networkConfig) {
        if (networkConfig == null) {
            throw invalid("kubernetesNetworkConfig is required");
        }
        if (networkConfig.getIpFamily() != null && !"ipv4".equals(networkConfig.getIpFamily())) {
            throw invalid("kubernetesNetworkConfig.ipFamily must be ipv4");
        }

        String serviceCidr = networkConfig.getServiceIpv4Cidr();
        Ipv4Cidr service = Ipv4Cidr.parse("kubernetesNetworkConfig.serviceIpv4Cidr", serviceCidr);
        String podCidr = podIpv4Cidr(runtime);
        Ipv4Cidr pod = Ipv4Cidr.parse(ReservedTags.EKS_POD_IPV4_CIDR_KEY, podCidr);
        if (service.overlaps(pod)) {
            throw invalid("kubernetesNetworkConfig.serviceIpv4Cidr must not overlap "
                    + ReservedTags.EKS_POD_IPV4_CIDR_KEY);
        }

        if (runtime.getNodeIpv4Address() != null) {
            long nodeAddress = parseIpv4Address(ReservedTags.EKS_NODE_IPV4_ADDRESS_KEY,
                    runtime.getNodeIpv4Address());
            if (service.contains(nodeAddress) || pod.contains(nodeAddress)) {
                throw invalid(ReservedTags.EKS_NODE_IPV4_ADDRESS_KEY
                        + " must not be in the service or pod CIDR");
            }
        }
    }

    private static void rejectUnknownReservedTags(Map<String, String> tags) {
        if (tags == null) {
            return;
        }
        for (String key : tags.keySet()) {
            if (key != null && key.startsWith(ReservedTags.RESERVED_PREFIX)
                    && !SUPPORTED_TAGS.contains(key)) {
                throw invalid(key + " is an unknown Reserved Tag.");
            }
        }
    }

    static String podIpv4Cidr(EksClusterRuntimeConfig runtime) {
        return runtime.getPodIpv4Cidr() != null
                ? runtime.getPodIpv4Cidr()
                : DEFAULT_POD_IPV4_CIDR;
    }

    private static String tagValue(Map<String, String> tags, String key) {
        if (tags == null || !tags.containsKey(key)) {
            return null;
        }
        String value = tags.get(key);
        if (value == null || value.isBlank()) {
            throw invalid(key + " must not be blank");
        }
        if (!value.equals(value.trim()) || value.chars().anyMatch(Character::isWhitespace)) {
            throw invalid(key + " must not contain whitespace");
        }
        return value;
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }

    private static long parseIpv4Address(String field, String value) {
        if (value == null) {
            throw invalid(field + " must be an IPv4 address");
        }
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            throw invalid(field + " must be an IPv4 address");
        }
        long address = 0;
        for (String octet : octets) {
            if (octet.isEmpty() || !octet.chars().allMatch(Character::isDigit)) {
                throw invalid(field + " must be an IPv4 address");
            }
            int number;
            try {
                number = Integer.parseInt(octet);
            } catch (NumberFormatException e) {
                throw invalid(field + " must be an IPv4 address");
            }
            if (number > 255) {
                throw invalid(field + " must be an IPv4 address");
            }
            address = (address << 8) | number;
        }
        return address;
    }

    private record Ipv4Cidr(long networkAddress, int prefixLength) {

        static Ipv4Cidr parse(String field, String value) {
            if (value == null) {
                throw invalid(field + " must be an IPv4 CIDR");
            }
            String[] parts = value.split("/", -1);
            if (parts.length != 2) {
                throw invalid(field + " must be an IPv4 CIDR");
            }
            long address = parseIpv4Address(field, parts[0]);
            int prefixLength;
            try {
                prefixLength = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                throw invalid(field + " must be an IPv4 CIDR");
            }
            if (prefixLength < 0 || prefixLength > 32) {
                throw invalid(field + " must be an IPv4 CIDR");
            }
            long mask = mask(prefixLength);
            if ((address & mask) != address) {
                throw invalid(field + " must use its network address");
            }
            return new Ipv4Cidr(address, prefixLength);
        }

        boolean overlaps(Ipv4Cidr other) {
            long sharedMask = mask(Math.min(prefixLength, other.prefixLength));
            return (networkAddress & sharedMask) == (other.networkAddress & sharedMask);
        }

        boolean contains(long address) {
            return (address & mask(prefixLength)) == networkAddress;
        }

        private static long mask(int prefixLength) {
            if (prefixLength == 0) {
                return 0;
            }
            return (0xffffffffL << (32 - prefixLength)) & 0xffffffffL;
        }
    }
}
