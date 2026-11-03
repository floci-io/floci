package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.model.ContainerNetwork;

import java.util.Map;
import java.util.Optional;

/**
 * Shared commands and network resolution helpers for installing and starting
 * the link-local IMDS proxy (169.254.169.254:80) inside containers.
 */
public final class Ec2MetadataProxy {

    /**
     * Run first in the shells that install and start these proxies and in the sshd install probe.
     * {@code docker exec} hands the shell the image's environment, so an application's library
     * search path or Python installation would otherwise be what the package manager, {@code socat}
     * and {@code curl} load. Only the helper shell changes; the container's configured environment
     * and its processes keep these variables.
     */
    static final String CLEAR_APPLICATION_PATHS = "unset LD_LIBRARY_PATH PYTHONPATH PYTHONHOME";

    private Ec2MetadataProxy() {}

    public static String[] installCommand() {
        return new String[]{"sh", "-c", String.join("\n",
                "set -eu",
                CLEAR_APPLICATION_PATHS,
                "if command -v ip >/dev/null 2>&1 && command -v socat >/dev/null 2>&1 && command -v curl >/dev/null 2>&1; then exit 0; fi",
                "if command -v apt-get >/dev/null 2>&1; then",
                "  apt-get update -qq >/dev/null",
                "  DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends iproute2 socat curl ca-certificates >/dev/null",
                "elif command -v dnf >/dev/null 2>&1; then",
                // --allowerasing lets dnf swap the curl-minimal that
                // public.ecr.aws/amazonlinux/amazonlinux:2023 ships by default for the full
                // curl package this proxy needs. Without it, dnf aborts the whole transaction
                // on a curl/curl-minimal conflict and iproute+socat never install either, even
                // though neither of them conflicts with anything.
                "  dnf install -y --allowerasing iproute socat curl ca-certificates >/dev/null",
                // Same gap as the sshd probe: Amazon Linux 2 has only yum, so on an instance
                // launched from ami-amazonlinux2 this chain reached its else branch and exited 1
                // with "No supported package manager found for IMDS proxy dependencies",
                // leaving the instance without a link-local IMDS endpoint.
                "elif command -v yum >/dev/null 2>&1; then",
                "  yum install -y iproute socat curl ca-certificates >/dev/null",
                "elif command -v apk >/dev/null 2>&1; then",
                "  apk add --no-cache iproute2 socat curl ca-certificates >/dev/null",
                "else",
                "  echo 'No supported package manager found for IMDS proxy dependencies' >&2",
                "  exit 1",
                "fi")};
    }

    public static String[] startCommand(String flociHost, int imdsPort) {
        return startCommand("imds", "169.254.169.254", 80, flociHost, imdsPort,
                imdsProbeCommand("http://169.254.169.254"));
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
        String pidFile = "/tmp/floci-" + name + "-proxy.pid";
        String logFile = "/tmp/floci-" + name + "-proxy.log";
        return new String[]{"sh", "-c", String.join("\n",
                "set -eu",
                CLEAR_APPLICATION_PATHS,
                "ip addr show dev lo | grep -q '" + bindIp + "/32' || ip addr add " + bindIp + "/32 dev lo",
                // The pid file is on the container's writable layer, so it outlives a restart while
                // the PID namespace does not: only a pid that is still this socat listener counts.
                // The command name matters as well as the arguments: the shell running this script
                // carries the listener string in its own command line.
                "if [ -f " + pidFile + " ] && pid=\"$(cat " + pidFile + ")\""
                        + " && [ \"$(cat \"/proc/$pid/comm\" 2>/dev/null)\" = socat ]"
                        + " && tr '\\0' ' ' 2>/dev/null <\"/proc/$pid/cmdline\""
                        + " | grep -q 'TCP-LISTEN:" + bindPort + ",bind=" + bindIp + ",'; then",
                "  exit 0",
                "fi",
                "nohup socat TCP-LISTEN:" + bindPort + ",bind=" + bindIp + ",fork,reuseaddr TCP:"
                        + targetHost + ":" + targetPort + " >" + logFile + " 2>&1 &",
                "echo $! > " + pidFile,
                "for i in 1 2 3 4 5 6 7 8 9 10 11 12; do",
                "  " + probeCommand,
                "  sleep 1",
                "done",
                "cat " + logFile + " >&2 || true",
                "exit 1")};
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
