package io.floci.aws.services.eks;

import io.floci.aws.services.ec2.Ec2MetadataProxy;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

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
    private static final Pattern IPV4_CIDR = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}/\\d{1,2}");
    /** A hostname or IPv4 address: nothing a shell or an iptables argument could read as more. */
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9._-]{0,251}[A-Za-z0-9])?");

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

    /**
     * Builds a POSIX sh command array that sends the EKS Pod Identity address
     * ({@code 169.254.170.23:80}) straight to Floci's credentials endpoint with NAT rules in the
     * node, so no relay process has to run there. The stock {@code rancher/k3s} image has
     * {@code iptables} (under {@code /bin/aux}) but neither {@code socat}, {@code curl} nor a
     * package manager to install them.
     *
     * <p>A {@code PREROUTING} rule takes the pod network's requests and an {@code OUTPUT} rule
     * those of the node itself, which is where a {@code hostNetwork} pod's come from. The node's
     * own masquerade rule (flannel's) carries the answers back. Earlier rules for the address are
     * removed first, so a Floci that comes back on another address leaves none behind, and a rule
     * an older Floci pointed at its relay is passed over.
     *
     * <p>iptables needs an address, so a {@code flociHost} name is resolved inside the node, as
     * its pods resolve it: from {@code /etc/hosts} (where {@code host.docker.internal} lives on
     * Linux), else through DNS. The command ends with a probe and fails if nothing answers.
     *
     * @param podCidr   pod network CIDR (defaults to {@link #DEFAULT_POD_CIDR} if null or blank)
     * @param flociHost the address or hostname the node reaches Floci on
     * @param flociPort Floci's port
     * @return command array suitable for container exec (["sh", "-c", "..."])
     */
    public static String[] buildPodIdentityForwardCommand(String podCidr, String flociHost, int flociPort) {
        String cidr = (podCidr != null && !podCidr.isBlank()) ? podCidr.trim() : DEFAULT_POD_CIDR;
        if (!IPV4_CIDR.matcher(cidr).matches()) {
            throw new IllegalArgumentException("podCidr must be an IPv4 CIDR: " + podCidr);
        }
        if (flociHost == null || !HOST.matcher(flociHost).matches()) {
            throw new IllegalArgumentException("flociHost must be an IPv4 address or a hostname: " + flociHost);
        }
        if (flociPort <= 0 || flociPort > 65535) {
            throw new IllegalArgumentException("flociPort must be between 1 and 65535");
        }
        String address = POD_IDENTITY_ENDPOINT.ip();
        int port = POD_IDENTITY_ENDPOINT.port();
        String match = "-d " + address + "/32 -p tcp --dport " + port;

        List<String> lines = new ArrayList<>();
        lines.add("set -eu");
        lines.add("IPTABLES=$(command -v iptables 2>/dev/null || command -v /bin/aux/iptables 2>/dev/null || true)");
        lines.add("if [ -z \"$IPTABLES\" ]; then");
        lines.add("  echo 'iptables not found in container' >&2");
        lines.add("  exit 1");
        lines.add("fi");
        lines.add("HOST='" + flociHost + "'");
        lines.add("ADDR=");
        lines.add("case \"$HOST\" in");
        lines.add("  *[!0-9.]*)");
        lines.add("    ADDR=$(awk -v h=\"$HOST\" '!/^[[:space:]]*#/ { for (i = 2; i <= NF; i++) if ($i == h) { print $1; exit } }'"
                + " /etc/hosts 2>/dev/null || true)");
        lines.add("    case \"$ADDR\" in");
        lines.add("      ''|*[!0-9.]*) ADDR=$(nslookup \"$HOST\" 2>/dev/null"
                + " | awk '/^Address/ && $NF ~ /^[0-9]+[.][0-9]+[.][0-9]+[.][0-9]+$/ { a = $NF } END { print a }' || true) ;;");
        lines.add("    esac ;;");
        lines.add("  *) ADDR=$HOST ;;");
        lines.add("esac");
        lines.add("if [ -z \"$ADDR\" ]; then");
        lines.add("  echo \"cannot resolve $HOST to an IPv4 address\" >&2");
        lines.add("  exit 1");
        lines.add("fi");
        lines.add("TARGET=\"$ADDR:" + flociPort + "\"");
        lines.add("for CHAIN in PREROUTING OUTPUT; do");
        lines.add("  $IPTABLES -t nat -S \"$CHAIN\" | grep -F -- '-d " + address + "/32 ' | grep -F -- '--dport " + port + " '"
                + " | sed 's/^-A /-D /' | while read -r rule; do $IPTABLES -t nat $rule; done");
        lines.add("done");
        lines.add("$IPTABLES -t nat -I PREROUTING 1 -s " + cidr + " " + match + " -j DNAT --to-destination \"$TARGET\"");
        lines.add("$IPTABLES -t nat -I OUTPUT 1 " + match + " -j DNAT --to-destination \"$TARGET\"");
        lines.add("for i in 1 2 3 4 5 6; do");
        lines.add("  " + Ec2MetadataProxy.podIdentityProbeCommand("http://" + address));
        lines.add("  sleep 1");
        lines.add("done");
        lines.add("echo \"nothing answers on " + address + " through $TARGET\" >&2");
        lines.add("exit 1");
        return new String[]{"sh", "-c", String.join("\n", lines)};
    }
}
