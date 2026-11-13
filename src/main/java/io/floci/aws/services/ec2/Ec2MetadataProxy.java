package io.floci.aws.services.ec2;

import com.github.dockerjava.api.model.ContainerNetwork;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shared commands and network resolution helpers for link-local metadata proxies.
 */
public final class Ec2MetadataProxy {

    private static final int EC2_IMDS_RELAY_PORT = 42169;

    /**
     * Run first in the shells that install and start these proxies and in the sshd install probe.
     * {@code docker exec} hands the shell the image's environment, so an application's library
     * search path or Python installation would otherwise be what the package manager, {@code socat}
     * and {@code curl} load. Only the helper shell changes; the container's configured environment
     * and its processes keep these variables.
     */
    static final String CLEAR_APPLICATION_PATHS = "unset LD_LIBRARY_PATH PYTHONPATH PYTHONHOME";

    private Ec2MetadataProxy() {}

    /**
     * Moves the exec'd shell, and everything it starts, out of the container's root cgroup into the
     * leaf {@code /sys/fs/cgroup/floci}. A {@code docker exec} lands in the root of the container's
     * cgroup namespace, and a systemd guest runs with a private one. cgroup v2 will not enable
     * controllers in a cgroup's {@code subtree_control} while that cgroup holds processes, so a
     * long-lived socat left in the root kept it empty, and kubelet with the cgroupfs driver then
     * failed to create {@code kubepods.slice} ("pids.max: no such file or directory").
     *
     * <p>Acts only when this shell sits at the root of a cgroup v2 namespace ({@code 0::/}) whose
     * cgroupfs is writable. A host-namespace container reports its full path, cgroup v1 reports one
     * line per hierarchy, and a read-only mount fails {@code -w} (access(2) reports EROFS, even for
     * root); all three run as before. Any {@code mkdir} or write that still fails is swallowed.
     * A plain directory rather than {@code systemd-run}, because the proxy starts while the guest's
     * systemd may still be booting, and systemd neither reaps nor moves a cgroup it did not create.
     */
    static final String LEAVE_ROOT_CGROUP =
            "if [ \"$(cat /proc/self/cgroup 2>/dev/null)\" = 0::/ ] && [ -w /sys/fs/cgroup/cgroup.procs ]; then"
                    + " { mkdir -p /sys/fs/cgroup/floci && echo $$ >/sys/fs/cgroup/floci/cgroup.procs; } 2>/dev/null || true;"
                    + " fi";

    public static String[] installCommand() {
        return installCommand(false);
    }

    static String[] installEc2Command() {
        return installCommand(true);
    }

    private static String[] installCommand(boolean requireIptables) {
        String iptablesProbe = requireIptables
                ? " && { command -v iptables >/dev/null 2>&1 || command -v nft >/dev/null 2>&1; }" : "";
        String iptablesPackage = requireIptables ? " iptables" : "";
        String dnfIptablesPackage = requireIptables ? " iptables-nft" : "";
        return new String[]{"sh", "-c", String.join("\n",
                "set -eu",
                LEAVE_ROOT_CGROUP,
                CLEAR_APPLICATION_PATHS,
                "if command -v ip >/dev/null 2>&1 && command -v socat >/dev/null 2>&1"
                        + " && command -v curl >/dev/null 2>&1" + iptablesProbe + "; then exit 0; fi",
                "if command -v apt-get >/dev/null 2>&1; then",
                "  apt-get update -qq >/dev/null",
                "  DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends"
                        + " iproute2 socat curl ca-certificates" + iptablesPackage + " >/dev/null",
                "elif command -v dnf >/dev/null 2>&1; then",
                // --allowerasing lets dnf swap the curl-minimal that
                // public.ecr.aws/amazonlinux/amazonlinux:2023 ships by default for the full
                // curl package this proxy needs. Without it, dnf aborts the whole transaction
                // on a curl/curl-minimal conflict and iproute+socat never install either, even
                // though neither of them conflicts with anything.
                "  dnf install -y --allowerasing iproute socat curl ca-certificates"
                        + dnfIptablesPackage + " >/dev/null",
                // Same gap as the sshd probe: Amazon Linux 2 has only yum, so on an instance
                // launched from ami-amazonlinux2 this chain reached its else branch and exited 1
                // with "No supported package manager found for IMDS proxy dependencies",
                // leaving the instance without a link-local IMDS endpoint.
                "elif command -v yum >/dev/null 2>&1; then",
                "  yum install -y iproute socat curl ca-certificates" + iptablesPackage + " >/dev/null",
                "elif command -v apk >/dev/null 2>&1; then",
                "  apk add --no-cache iproute2 socat curl ca-certificates" + iptablesPackage + " >/dev/null",
                "else",
                "  echo 'No supported package manager found for IMDS proxy dependencies' >&2",
                "  exit 1",
                "fi")};
    }

    public static String[] startCommand(String flociHost, int imdsPort) {
        return startCommand("imds", "169.254.169.254", 80, flociHost, imdsPort,
                imdsProbeCommand("http://169.254.169.254"));
    }

    static String[] startEc2Command(String flociHost, int imdsPort) {
        return startCommand("imds", "169.254.169.254", EC2_IMDS_RELAY_PORT, flociHost, imdsPort,
                imdsProbeCommand("http://169.254.169.254"), true);
    }

    /**
     * Ready once an IMDSv2 token reads the instance ID, which also holds on an instance whose
     * HttpTokens is required. A refused or empty token ends the attempt rather than falling back to
     * a tokenless read, which an empty token header would otherwise become. Both requests bypass
     * any HTTP proxy the image configures: the check is of the link-local path itself.
     */
    static String imdsProbeCommand(String endpoint) {
        return "token=$(curl -fsS --noproxy '*' --max-time 1 -X PUT "
                + "-H 'X-aws-ec2-metadata-token-ttl-seconds: 60' " + endpoint + "/latest/api/token)"
                + " && [ -n \"$token\" ]"
                + " && curl -fsS --noproxy '*' --max-time 1 -H \"X-aws-ec2-metadata-token: $token\" "
                + endpoint + "/latest/meta-data/instance-id >/dev/null && exit 0";
    }

    public static String[] podIdentityStartCommand(String flociHost, int flociPort) {
        return startCommand("pod-identity", "169.254.170.23", 80, flociHost, flociPort,
                podIdentityProbeCommand("http://169.254.170.23"));
    }

    /**
     * Ready once the relay answers at all: without a pod's token Floci answers 400, which still
     * proves socat relays. Any HTTP proxy the image configures is bypassed, since a proxy's own
     * answer would pass the same check.
     */
    static String podIdentityProbeCommand(String endpoint) {
        return "[ \"$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' --max-time 1 " + endpoint
                + "/v1/credentials)\" != \"000\" ] && exit 0";
    }

    public static String[] startCommand(String name, String bindIp, int bindPort,
                                        String targetHost, int targetPort, String probeCommand) {
        return startCommand(name, bindIp, bindPort, targetHost, targetPort, probeCommand, false);
    }

    private static String[] startCommand(String name, String bindIp, int bindPort,
                                         String targetHost, int targetPort, String probeCommand,
                                         boolean routeImdsPort) {
        String legacyPidFile = "/tmp/floci-" + name + "-proxy.pid";
        String pidFile = routeImdsPort ? "/tmp/floci-" + name + "-proxy-" + bindPort + ".pid" : legacyPidFile;
        String logFile = "/tmp/floci-" + name + "-proxy.log";
        String listener = "TCP-LISTEN:" + bindPort + ",bind=" + bindIp + ",";
        String ruleMatch = "-d " + bindIp + "/32 -p tcp --dport 80 -j DNAT --to-destination "
                + bindIp + ":" + bindPort;
        String nftRuleMarker = "floci-imds-relay-" + bindPort;
        List<String> lines = new ArrayList<>(List.of(
                "set -eu",
                LEAVE_ROOT_CGROUP,
                CLEAR_APPLICATION_PATHS,
                "ip addr show dev lo | grep -q '" + bindIp + "/32' || ip addr add " + bindIp + "/32 dev lo"));

        // The writable-layer PID file survives a container restart, so verify the process name and
        // listener arguments before reusing it or retiring a pre-upgrade TCP/80 listener.
        if (routeImdsPort) {
            lines.add("legacy_pid=");
            lines.add(socatPidCheck(legacyPidFile, "TCP-LISTEN:80,bind=" + bindIp + ","));
            lines.add("  legacy_pid=$pid");
            lines.add("fi");
            lines.add("proxy_running=0");
            lines.add("new_pid=");
            lines.add("rule_added=0");
            lines.add("rule_backend=");
            lines.add("cleanup_new_proxy() {");
            lines.add("  if [ -n \"$new_pid\" ]; then");
            lines.add("    if [ \"$(cat \"/proc/$new_pid/comm\" 2>/dev/null)\" = socat ]"
                    + " && tr '\\0' ' ' 2>/dev/null <\"/proc/$new_pid/cmdline\""
                    + " | grep -q '" + listener + "'; then");
            lines.add("      kill \"$new_pid\" 2>/dev/null || true");
            lines.add("    fi");
            lines.add("    if [ \"$(cat " + pidFile + " 2>/dev/null)\" = \"$new_pid\" ]; then");
            lines.add("      rm -f " + pidFile);
            lines.add("    fi");
            lines.add("  fi");
            lines.add("  if [ \"$rule_added\" -eq 1 ]; then");
            lines.add("    if [ \"$rule_backend\" = nft ]; then");
            lines.add("      nft delete table ip floci_imds 2>/dev/null || true");
            lines.add("    else");
            lines.add("      iptables -t nat -D OUTPUT " + ruleMatch + " 2>/dev/null || true");
            lines.add("    fi");
            lines.add("  fi");
            lines.add("}");
        }
        lines.add(socatPidCheck(pidFile, listener));
        if (routeImdsPort) {
            lines.add("  proxy_running=1");
            lines.add("fi");
            lines.add("if [ \"$proxy_running\" -eq 0 ]; then");
        } else {
            lines.add("  exit 0");
            lines.add("fi");
        }
        lines.add((routeImdsPort ? "  " : "") + "nohup socat " + listener + "fork,reuseaddr TCP:"
                + targetHost + ":" + targetPort + " >" + logFile + " 2>&1 &");
        if (routeImdsPort) {
            lines.add("  new_pid=$!");
            lines.add("  echo \"$new_pid\" > " + pidFile);
        } else {
            lines.add("echo $! > " + pidFile);
        }
        if (routeImdsPort) {
            lines.add("fi");
            lines.add("if command -v nft >/dev/null 2>&1"
                    + " && nft list chain ip floci_imds output 2>/dev/null"
                    + " | grep -F 'comment \"" + nftRuleMarker + "\"'"
                    + " | grep -Fq '" + bindIp + ":" + bindPort + "'; then");
            lines.add("  rule_backend=nft");
            lines.add("elif command -v iptables >/dev/null 2>&1"
                    + " && ! { command -v nft >/dev/null 2>&1"
                    + " && nft list table ip floci_imds >/dev/null 2>&1; }; then");
            lines.add("  rule_backend=iptables");
            lines.add("  if iptables -t nat -C OUTPUT " + ruleMatch + " 2>/dev/null; then");
            lines.add("    :");
            lines.add("  elif iptables -t nat -I OUTPUT 1 " + ruleMatch + "; then");
            lines.add("    rule_added=1");
            lines.add("  else");
            lines.add("    cleanup_new_proxy");
            lines.add("    exit 1");
            lines.add("  fi");
            lines.add("elif command -v nft >/dev/null 2>&1; then");
            lines.add("  rule_backend=nft");
            lines.add("  if nft list table ip floci_imds >/dev/null 2>&1; then");
            lines.add("    if ! nft delete table ip floci_imds; then");
            lines.add("      cleanup_new_proxy");
            lines.add("      exit 1");
            lines.add("    fi");
            lines.add("  fi");
            lines.add("  if printf '%s\\n' 'add table ip floci_imds'"
                    + " 'add chain ip floci_imds output { type nat hook output priority -100; policy accept; }'"
                    + " 'add rule ip floci_imds output ip daddr " + bindIp + " tcp dport 80 dnat to "
                    + bindIp + ":" + bindPort + " comment \"" + nftRuleMarker + "\"' | nft -f -; then");
            lines.add("    rule_added=1");
            lines.add("  else");
            lines.add("    cleanup_new_proxy");
            lines.add("    exit 1");
            lines.add("  fi");
            lines.add("else");
            lines.add("  cleanup_new_proxy");
            lines.add("  exit 1");
            lines.add("fi");
        }
        lines.add("for i in 1 2 3 4 5 6 7 8 9 10 11 12; do");
        if (routeImdsPort) {
            lines.add("  if (" + probeCommand + "); then");
            lines.add("    if [ -n \"$legacy_pid\" ]"
                    + " && [ \"$(cat \"/proc/$legacy_pid/comm\" 2>/dev/null)\" = socat ]"
                    + " && tr '\\0' ' ' 2>/dev/null <\"/proc/$legacy_pid/cmdline\""
                    + " | grep -q 'TCP-LISTEN:80,bind=" + bindIp + ",'; then");
            lines.add("      kill \"$legacy_pid\" 2>/dev/null || true");
            lines.add("      if [ \"$(cat " + legacyPidFile + " 2>/dev/null)\" = \"$legacy_pid\" ]; then");
            lines.add("        rm -f " + legacyPidFile);
            lines.add("      fi");
            lines.add("    fi");
            lines.add("    exit 0");
            lines.add("  fi");
        } else {
            lines.add("  " + probeCommand);
        }
        lines.add("  sleep 1");
        lines.add("done");
        if (routeImdsPort) {
            lines.add("cleanup_new_proxy");
        }
        lines.add("cat " + logFile + " >&2 || true");
        lines.add("exit 1");
        return new String[]{"sh", "-c", String.join("\n", lines)};
    }

    private static String socatPidCheck(String pidFile, String listener) {
        return "if [ -f " + pidFile + " ] && pid=\"$(cat " + pidFile + ")\""
                + " && [ \"$(cat \"/proc/$pid/comm\" 2>/dev/null)\" = socat ]"
                + " && tr '\\0' ' ' 2>/dev/null <\"/proc/$pid/cmdline\""
                + " | grep -q '" + listener + "'; then";
    }

    public static Optional<String> preferredMetadataSourceIp(Map<String, ContainerNetwork> networks) {
        if (networks == null || networks.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> configuredNetworkIp = networks.entrySet().stream()
                .filter(entry -> !"bridge".equals(entry.getKey()))
                .map(Map.Entry::getValue)
                .map(ContainerNetwork::getIpAddress)
                .filter(ip -> ip != null && !ip.isBlank())
                .findFirst();
        if (configuredNetworkIp.isPresent()) {
            return configuredNetworkIp;
        }
        ContainerNetwork bridge = networks.get("bridge");
        if (bridge != null && bridge.getIpAddress() != null && !bridge.getIpAddress().isBlank()) {
            return Optional.of(bridge.getIpAddress());
        }
        return networks.values().stream()
                .map(ContainerNetwork::getIpAddress)
                .filter(ip -> ip != null && !ip.isBlank())
                .findFirst();
    }
}
