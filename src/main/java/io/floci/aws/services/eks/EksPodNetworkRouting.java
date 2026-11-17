package io.floci.aws.services.eks;

import io.floci.aws.services.ec2.Ec2MetadataProxy;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Generates the iptables rules that answer a link-local address inside an EKS cluster container: IMDS,
 * {@code 169.254.169.254:80} ({@link #buildImdsForwardCommand}), and the EKS Pod Identity address,
 * {@code 169.254.170.23:80} ({@link #buildPodIdentityForwardCommand}). NAT rules in the node send the address
 * straight to Floci, so no relay process runs there and nothing has to be installed, which a stock
 * {@code rancher/k3s} image, with {@code iptables} but no package manager, could not do.
 */
public final class EksPodNetworkRouting {

    public static final String DEFAULT_POD_CIDR = "10.42.0.0/16";
    public static final String CHAIN_NAME = "FLOCI-LINK-LOCAL";
    public static final LinkLocalEndpoint IMDS_ENDPOINT = new LinkLocalEndpoint("169.254.169.254", 80);
    public static final LinkLocalEndpoint POD_IDENTITY_ENDPOINT = new LinkLocalEndpoint("169.254.170.23", 80);
    /** The names the relays an older Floci ran for the addresses went by ({@link Ec2MetadataProxy#stopRelayLines}). */
    private static final String IMDS_RELAY = "imds";
    private static final String POD_IDENTITY_RELAY = "pod-identity";
    private static final String HOSTS_FILE = "/etc/hosts";
    private static final Pattern IPV4_CIDR = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}/\\d{1,2}");
    /** A hostname or IPv4 address: nothing a shell or an iptables argument could read as more. */
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9._-]{0,251}[A-Za-z0-9])?");
    /** A path a single-quoted shell word can hold as it is. */
    private static final Pattern ABSOLUTE_PATH = Pattern.compile("/[A-Za-z0-9._/-]*");

    private EksPodNetworkRouting() {}

    /**
     * Builds a POSIX sh command array that sends IMDS ({@code 169.254.169.254:80}) straight to Floci's metadata
     * service with NAT rules in the node, as {@link #buildPodIdentityForwardCommand} sends the Pod Identity address.
     * An {@code OUTPUT} rule takes the node's own requests, which is where a {@code hostNetwork} pod's come from, and
     * with {@code podNetwork} a {@code PREROUTING} rule takes the pod network's too; without it, ordinary pods reach
     * no IMDS, as before. The rules and the relay an older Floci set up for the address go first.
     *
     * @param podCidr    pod network CIDR (defaults to {@link #DEFAULT_POD_CIDR} if null or blank)
     * @param podNetwork whether pods outside the node's network namespace reach IMDS
     * @param flociHost  the address or hostname the node reaches Floci on
     * @param imdsPort   the port of Floci's metadata service
     * @return command array suitable for container exec (["sh", "-c", "..."])
     */
    public static String[] buildImdsForwardCommand(String podCidr, boolean podNetwork, String flociHost, int imdsPort) {
        return buildImdsForwardCommand(podCidr, podNetwork, flociHost, imdsPort, HOSTS_FILE);
    }

    /** As above, reading host names from {@code hostsFile} rather than {@code /etc/hosts}. */
    static String[] buildImdsForwardCommand(String podCidr, boolean podNetwork, String flociHost, int imdsPort,
                                            String hostsFile) {
        return buildForwardCommand(IMDS_ENDPOINT, IMDS_RELAY, podNetwork ? podCidrOf(podCidr) : null, flociHost, imdsPort,
                hostsFile, answerProbeCommand("http://" + IMDS_ENDPOINT.ip() + "/latest/meta-data/"));
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
     * own masquerade rule (flannel's) carries the answers back. What an earlier Floci set up for the
     * address is removed first, as {@link #buildPodIdentityRemoveCommand} removes it, so a Floci that
     * comes back on another address leaves no rule behind, and a node an older Floci gave a relay ends
     * as a new node does.
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
        return buildForwardCommand(POD_IDENTITY_ENDPOINT, POD_IDENTITY_RELAY, podCidrOf(podCidr), flociHost, flociPort,
                hostsFile, podIdentityProbeCommand("http://" + POD_IDENTITY_ENDPOINT.ip()));
    }

    /** {@code podCidr}, or {@link #DEFAULT_POD_CIDR} if null or blank, which must be an IPv4 CIDR. */
    private static String podCidrOf(String podCidr) {
        String cidr = (podCidr != null && !podCidr.isBlank()) ? podCidr.trim() : DEFAULT_POD_CIDR;
        if (!IPV4_CIDR.matcher(cidr).matches()) {
            throw new IllegalArgumentException("podCidr must be an IPv4 CIDR: " + podCidr);
        }
        return cidr;
    }

    /**
     * The forward of {@code endpoint} to {@code flociHost:flociPort}: the {@code flociHost} name resolved inside the
     * node, the rules and the relay named {@code relay} an earlier Floci set up for the address removed, a
     * {@code PREROUTING} rule for {@code podCidr} unless it is null and an {@code OUTPUT} rule added, then
     * {@code probe} until it ends the command, failing if nothing answers.
     */
    private static String[] buildForwardCommand(LinkLocalEndpoint endpoint, String relay, String podCidr,
                                                String flociHost, int flociPort, String hostsFile, String probe) {
        if (flociHost == null || !HOST.matcher(flociHost).matches()) {
            throw new IllegalArgumentException("flociHost must be an IPv4 address or a hostname: " + flociHost);
        }
        if (flociPort <= 0 || flociPort > 65535) {
            throw new IllegalArgumentException("flociPort must be between 1 and 65535");
        }
        if (hostsFile == null || !ABSOLUTE_PATH.matcher(hostsFile).matches()) {
            throw new IllegalArgumentException("hostsFile must be an absolute path: " + hostsFile);
        }
        String address = endpoint.ip();
        String match = "-d " + address + "/32 -p tcp --dport " + endpoint.port();

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
        addRuleRemoval(lines, endpoint, relay);
        if (podCidr != null) {
            lines.add("$IPTABLES -t nat -I PREROUTING 1 -s " + podCidr + " " + match
                    + " -j DNAT --to-destination \"$TARGET\"");
        }
        lines.add("$IPTABLES -t nat -I OUTPUT 1 " + match + " -j DNAT --to-destination \"$TARGET\"");
        lines.add("for i in 1 2 3 4 5 6; do");
        lines.add("  " + probe);
        lines.add("  sleep 1");
        lines.add("done");
        lines.add("echo \"nothing answers on " + address + " through $TARGET\" >&2");
        lines.add("exit 1");
        return new String[]{"sh", "-c", String.join("\n", lines)};
    }

    /**
     * Builds a POSIX sh command array that removes the rules {@link #buildPodIdentityForwardCommand}
     * adds, any an earlier Floci added for the address, and the relay an older Floci ran for it, so
     * that nothing answers on it, as on an EKS node without the Pod Identity agent. A node without
     * {@code iptables} has no such rules, so the command succeeds there.
     *
     * @return command array suitable for container exec (["sh", "-c", "..."])
     */
    public static String[] buildPodIdentityRemoveCommand() {
        List<String> lines = iptablesLines(false);
        addRuleRemoval(lines, POD_IDENTITY_ENDPOINT, POD_IDENTITY_RELAY);
        return new String[]{"sh", "-c", String.join("\n", lines)};
    }

    /** Ready once the pod identity endpoint answers at all: without a pod's token Floci answers 400. */
    static String podIdentityProbeCommand(String endpoint) {
        return answerProbeCommand(endpoint + "/v1/credentials");
    }

    /**
     * Ready once {@code url} answers at all, whatever the status, which still proves the path reaches Floci. Any HTTP
     * proxy the image configures is bypassed, since a proxy's own answer would pass the same check. Uses {@code curl}
     * where there is one, else {@code wget}, as in a stock {@code rancher/k3s} node, which has BusyBox's and no
     * {@code curl}: both print the status of an error answer, which is what is looked for.
     */
    static String answerProbeCommand(String url) {
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

    /**
     * Deletes each rule for {@code endpoint}'s address in {@code PREROUTING} and {@code OUTPUT}, and in
     * {@link #CHAIN_NAME}, where an older Floci sent it to the relay it ran in the node, then retires that relay, named
     * {@code relay}. The relay would otherwise answer again once the rules in front of it go.
     */
    private static void addRuleRemoval(List<String> lines, LinkLocalEndpoint endpoint, String relay) {
        lines.add("for CHAIN in PREROUTING OUTPUT " + CHAIN_NAME + "; do");
        lines.add("  $IPTABLES -t nat -S \"$CHAIN\" 2>/dev/null | grep -F -- '-d " + endpoint.ip() + "/32 '"
                + " | grep -F -- '--dport " + endpoint.port() + " '"
                + " | sed 's/^-A /-D /' | while read -r rule; do $IPTABLES -t nat $rule; done");
        lines.add("done");
        lines.addAll(Ec2MetadataProxy.stopRelayLines(relay, endpoint.ip()));
    }
}
