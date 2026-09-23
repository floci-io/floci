package io.github.hectorvent.floci.services.eks;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates iptables routing rules for link-local endpoints inside an EKS cluster container.
 * <p>
 * Programs DNAT rules in the node network namespace nat table so traffic originating
 * from the cluster pod CIDR directed at link-local addresses (such as IMDS 169.254.169.254:80
 * and EKS Pod Identity 169.254.170.23:80) reaches Floci's node listener rather than being
 * dropped by the Linux kernel or hijacked by CNI hostPort ingress rules.
 */
public final class EksPodNetworkRouting {

    public static final String DEFAULT_POD_CIDR = "10.42.0.0/16";
    public static final String CHAIN_NAME = "FLOCI-LINK-LOCAL";
    public static final LinkLocalEndpoint IMDS_ENDPOINT = new LinkLocalEndpoint("169.254.169.254", 80);
    public static final LinkLocalEndpoint POD_IDENTITY_ENDPOINT = new LinkLocalEndpoint("169.254.170.23", 80);
    public static final List<LinkLocalEndpoint> DEFAULT_ENDPOINTS = List.of(IMDS_ENDPOINT);

    private EksPodNetworkRouting() {}

    /**
     * Builds a POSIX sh command array that idempotently programs iptables DNAT rules
     * in the node container nat table for the specified link-local endpoints.
     *
     * @param podCidr pod network CIDR (defaults to {@link #DEFAULT_POD_CIDR} if null or blank)
     * @param endpoints link-local endpoints to route from the pod network
     * @return command array suitable for container exec (["sh", "-c", "..."])
     */
    public static String[] buildRoutingCommand(String podCidr, List<LinkLocalEndpoint> endpoints) {
        String cidr = (podCidr != null && !podCidr.isBlank()) ? podCidr : DEFAULT_POD_CIDR;
        if (endpoints == null || endpoints.isEmpty()) {
            throw new IllegalArgumentException("endpoints cannot be null or empty");
        }

        List<String> lines = new ArrayList<>();
        lines.add("set -eu");
        lines.add("IPTABLES=$(command -v iptables 2>/dev/null || command -v /bin/aux/iptables 2>/dev/null || true)");
        lines.add("if [ -z \"$IPTABLES\" ]; then");
        lines.add("  echo 'iptables not found in container' >&2");
        lines.add("  exit 1");
        lines.add("fi");
        lines.add("$IPTABLES -t nat -N " + CHAIN_NAME + " 2>/dev/null || true");
        lines.add("$IPTABLES -t nat -C PREROUTING -j " + CHAIN_NAME + " 2>/dev/null || $IPTABLES -t nat -I PREROUTING 1 -j " + CHAIN_NAME);

        for (LinkLocalEndpoint ep : endpoints) {
            String check = String.format(
                    "$IPTABLES -t nat -C %s -s %s -d %s -p tcp --dport %d -j DNAT --to-destination %s:%d 2>/dev/null",
                    CHAIN_NAME, cidr, ep.ip(), ep.port(), ep.ip(), ep.port());
            String add = String.format(
                    "$IPTABLES -t nat -A %s -s %s -d %s -p tcp --dport %d -j DNAT --to-destination %s:%d",
                    CHAIN_NAME, cidr, ep.ip(), ep.port(), ep.ip(), ep.port());
            lines.add(check + " || " + add);
        }

        return new String[]{"sh", "-c", String.join("\n", lines)};
    }
}
