package io.floci.aws.services.eks;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Generates iptables routing rules for link-local endpoints inside an EKS cluster container.
 * <p>
 * Programs DNAT rules in the node network namespace nat table so traffic originating
 * from the cluster pod CIDR directed at a link-local address (such as IMDS 169.254.169.254:80)
 * reaches Floci's node listener rather than being dropped by the Linux kernel or hijacked by CNI
 * hostPort ingress rules. The EKS Pod Identity address, 169.254.170.23:80, is sent straight to
 * Floci's credentials endpoint instead, by {@link #buildPodIdentityForwardCommand}.
 */
public final class EksPodNetworkRouting {

    public static final String DEFAULT_POD_CIDR = "10.42.0.0/16";
    public static final String CHAIN_NAME = "FLOCI-LINK-LOCAL";
    public static final LinkLocalEndpoint IMDS_ENDPOINT = new LinkLocalEndpoint("169.254.169.254", 80);
    public static final LinkLocalEndpoint POD_IDENTITY_ENDPOINT = new LinkLocalEndpoint("169.254.170.23", 80);
    public static final List<LinkLocalEndpoint> DEFAULT_ENDPOINTS = List.of(IMDS_ENDPOINT);
    private static final String HOSTS_FILE = "/etc/hosts";
    private static final Pattern IPV4_CIDR = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}/\\d{1,2}");
    /** A hostname or IPv4 address: nothing a shell or an iptables argument could read as more. */
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9._-]{0,251}[A-Za-z0-9])?");
    /** A path a single-quoted shell word can hold as it is. */
    private static final Pattern ABSOLUTE_PATH = Pattern.compile("/[A-Za-z0-9._/-]*");

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

        List<String> lines = iptablesLines(true);
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
     * own masquerade rule (flannel's) carries the answers back. Earlier rules for the address in
     * those two chains are removed first, as {@link #buildPodIdentityRemoveCommand} removes them,
     * so a Floci that comes back on another address leaves none behind. A rule an older Floci put in
     * {@link #CHAIN_NAME} for its relay stays, but the new first {@code PREROUTING} rule comes
     * before the jump to it.
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
        return buildPodIdentityForwardCommand(podCidr, flociHost, flociPort, HOSTS_FILE);
    }

    /** As above, reading host names from {@code hostsFile} rather than {@code /etc/hosts}. */
    static String[] buildPodIdentityForwardCommand(String podCidr, String flociHost, int flociPort, String hostsFile) {
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
        if (hostsFile == null || !ABSOLUTE_PATH.matcher(hostsFile).matches()) {
            throw new IllegalArgumentException("hostsFile must be an absolute path: " + hostsFile);
        }
        String address = POD_IDENTITY_ENDPOINT.ip();
        String match = "-d " + address + "/32 -p tcp --dport " + POD_IDENTITY_ENDPOINT.port();

        List<String> lines = iptablesLines(true);
        lines.add("HOST='" + flociHost + "'");
        lines.add("ADDR=");
        lines.add("case \"$HOST\" in");
        lines.add("  *[!0-9.]*)");
        lines.add("    ADDR=$(awk -v h=\"$HOST\" '!/^[[:space:]]*#/ { for (i = 2; i <= NF; i++) if ($i == h) { print $1; exit } }'"
                + " '" + hostsFile + "' 2>/dev/null || true)");
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
        addPodIdentityRuleRemoval(lines);
        lines.add("$IPTABLES -t nat -I PREROUTING 1 -s " + cidr + " " + match + " -j DNAT --to-destination \"$TARGET\"");
        lines.add("$IPTABLES -t nat -I OUTPUT 1 " + match + " -j DNAT --to-destination \"$TARGET\"");
        lines.add("for i in 1 2 3 4 5 6; do");
        lines.add("  " + podIdentityProbeCommand("http://" + address));
        lines.add("  sleep 1");
        lines.add("done");
        lines.add("echo \"nothing answers on " + address + " through $TARGET\" >&2");
        lines.add("exit 1");
        return new String[]{"sh", "-c", String.join("\n", lines)};
    }

    /**
     * Builds a POSIX sh command array that removes the rules {@link #buildPodIdentityForwardCommand}
     * adds, and any an earlier Floci added for the address, so that nothing answers on it, as on an
     * EKS node without the Pod Identity agent. A node without {@code iptables} has no such rules,
     * so the command succeeds there.
     *
     * @return command array suitable for container exec (["sh", "-c", "..."])
     */
    public static String[] buildPodIdentityRemoveCommand() {
        List<String> lines = iptablesLines(false);
        addPodIdentityRuleRemoval(lines);
        return new String[]{"sh", "-c", String.join("\n", lines)};
    }

    /**
     * Ready once the pod identity endpoint answers at all: without a pod's token Floci answers 400,
     * which still proves the path reaches it. Any HTTP proxy the image configures is bypassed, since
     * a proxy's own answer would pass the same check. Uses {@code curl} where there is one, else
     * {@code wget}, as in a stock {@code rancher/k3s} node, which has BusyBox's and no {@code curl}:
     * both print the status of an error answer, which is what is looked for.
     */
    static String podIdentityProbeCommand(String endpoint) {
        String url = endpoint + "/v1/credentials";
        return "if command -v curl >/dev/null 2>&1; then"
                + " [ \"$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' --max-time 1 " + url + ")\" != \"000\" ]"
                + " && exit 0;"
                + " else env -u http_proxy -u HTTP_PROXY wget -T 1 -O /dev/null " + url + " 2>&1"
                + " | grep -qE 'HTTP/1[.][01] [0-9]{3}|ERROR [0-9]{3}' && exit 0;"
                + " fi";
    }

    /**
     * The start of every command here: strict mode and {@code IPTABLES}, found on the path or
     * where a stock {@code rancher/k3s} image keeps it. Without one, the command fails when it
     * has rules to add, and ends successfully when it only removes them.
     */
    private static List<String> iptablesLines(boolean required) {
        List<String> lines = new ArrayList<>();
        lines.add("set -eu");
        lines.add("IPTABLES=$(command -v iptables 2>/dev/null || command -v /bin/aux/iptables 2>/dev/null || true)");
        lines.add("if [ -z \"$IPTABLES\" ]; then");
        if (required) {
            lines.add("  echo 'iptables not found in container' >&2");
            lines.add("  exit 1");
        } else {
            lines.add("  exit 0");
        }
        lines.add("fi");
        return lines;
    }

    /** Deletes each {@code PREROUTING} and {@code OUTPUT} rule for the Pod Identity address. */
    private static void addPodIdentityRuleRemoval(List<String> lines) {
        lines.add("for CHAIN in PREROUTING OUTPUT; do");
        lines.add("  $IPTABLES -t nat -S \"$CHAIN\" | grep -F -- '-d " + POD_IDENTITY_ENDPOINT.ip() + "/32 '"
                + " | grep -F -- '--dport " + POD_IDENTITY_ENDPOINT.port() + " '"
                + " | sed 's/^-A /-D /' | while read -r rule; do $IPTABLES -t nat $rule; done");
        lines.add("done");
    }
}
