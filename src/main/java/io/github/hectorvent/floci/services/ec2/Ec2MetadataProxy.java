package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.model.ContainerNetwork;

import java.util.Map;
import java.util.Optional;

/**
 * Shared commands and network resolution helpers for installing and starting
 * the link-local IMDS proxy (169.254.169.254:80) inside containers.
 */
public final class Ec2MetadataProxy {

    private Ec2MetadataProxy() {}

    public static String[] installCommand() {
        return new String[]{"sh", "-c", String.join("\n",
                "set -eu",
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
                "curl -fsS --max-time 1 http://169.254.169.254/latest/meta-data/instance-id >/dev/null && exit 0");
    }

    public static String[] podIdentityStartCommand(String flociHost, int flociPort) {
        return startCommand("pod-identity", "169.254.170.23", 80, flociHost, flociPort,
                "[ \"$(curl -s -o /dev/null -w '%{http_code}' --max-time 1 http://169.254.170.23/v1/credentials)\" != \"000\" ] && exit 0");
    }

    public static String[] startCommand(String name, String bindIp, int bindPort,
                                        String targetHost, int targetPort, String probeCommand) {
        String pidFile = "/tmp/floci-" + name + "-proxy.pid";
        String logFile = "/tmp/floci-" + name + "-proxy.log";
        return new String[]{"sh", "-c", String.join("\n",
                "set -eu",
                "ip addr show dev lo | grep -q '" + bindIp + "/32' || ip addr add " + bindIp + "/32 dev lo",
                "if [ -f " + pidFile + " ] && kill -0 \"$(cat " + pidFile + ")\" 2>/dev/null; then",
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
