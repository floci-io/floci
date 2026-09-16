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
        return new String[]{"sh", "-c", String.join("\n",
                "set -eu",
                "ip addr show dev lo | grep -q '169.254.169.254/32' || ip addr add 169.254.169.254/32 dev lo",
                "if [ -f /tmp/floci-imds-proxy.pid ] && kill -0 \"$(cat /tmp/floci-imds-proxy.pid)\" 2>/dev/null; then",
                "  exit 0",
                "fi",
                "nohup socat TCP-LISTEN:80,bind=169.254.169.254,fork,reuseaddr TCP:" + flociHost + ":" + imdsPort + " >/tmp/floci-imds-proxy.log 2>&1 &",
                "echo $! > /tmp/floci-imds-proxy.pid",
                "for i in 1 2 3 4 5 6 7 8 9 10 11 12; do",
                "  curl -fsS --max-time 1 http://169.254.169.254/latest/meta-data/instance-id >/dev/null && exit 0",
                "  sleep 1",
                "done",
                "cat /tmp/floci-imds-proxy.log >&2 || true",
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
