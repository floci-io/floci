package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.model.ContainerNetwork;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ec2MetadataProxyTest {

    @Test
    void installCommandContainsSupportedPackageManagers() {
        String[] command = Ec2MetadataProxy.installCommand();
        assertEquals(3, command.length);
        assertEquals("sh", command[0]);
        assertEquals("-c", command[1]);

        String script = command[2];
        assertTrue(script.contains("command -v ip >/dev/null 2>&1 && command -v socat >/dev/null 2>&1 && command -v curl >/dev/null 2>&1; then exit 0; fi"));
        assertTrue(script.contains("apt-get install -y --no-install-recommends iproute2 socat curl ca-certificates"));
        assertTrue(script.contains("dnf install -y --allowerasing iproute socat curl ca-certificates"));
        assertTrue(script.contains("yum install -y iproute socat curl ca-certificates"));
        assertTrue(script.contains("apk add --no-cache iproute2 socat curl ca-certificates"));
    }

    @Test
    void startCommandAttachesAddressIdempotentlyAndTargetsHostAndPort() {
        String[] command = Ec2MetadataProxy.startCommand("10.0.0.1", 9169);
        assertEquals(3, command.length);
        assertEquals("sh", command[0]);
        assertEquals("-c", command[1]);

        String script = command[2];
        // Attaches address if missing
        assertTrue(script.contains("ip addr show dev lo | grep -q '169.254.169.254/32' || ip addr add 169.254.169.254/32 dev lo"));
        // Idempotent when pid file exists and process is alive
        assertTrue(script.contains("if [ -f /tmp/floci-imds-proxy.pid ] && kill -0 \"$(cat /tmp/floci-imds-proxy.pid)\" 2>/dev/null; then\n  exit 0\nfi"));
        // Targets configured Floci host and IMDS port
        assertTrue(script.contains("nohup socat TCP-LISTEN:80,bind=169.254.169.254,fork,reuseaddr TCP:10.0.0.1:9169 >/tmp/floci-imds-proxy.log 2>&1 &"));
        // Verifies via link-local curl
        assertTrue(script.contains("curl -fsS --max-time 1 http://169.254.169.254/latest/meta-data/instance-id >/dev/null && exit 0"));
    }

    @Test
    void preferredMetadataSourceIpPrefersConfiguredNetworkOverBridge() {
        ContainerNetwork bridge = new ContainerNetwork();
        bridge.withIpv4Address("172.17.0.2");
        ContainerNetwork vpc = new ContainerNetwork();
        vpc.withIpv4Address("10.0.0.2");

        Optional<String> ip = Ec2MetadataProxy.preferredMetadataSourceIp(Map.of("bridge", bridge, "custom-net", vpc));
        assertTrue(ip.isPresent());
        assertEquals("10.0.0.2", ip.get());
    }

    @Test
    void preferredMetadataSourceIpFallsBackToBridge() {
        ContainerNetwork bridge = new ContainerNetwork();
        bridge.withIpv4Address("172.17.0.2");

        Optional<String> ip = Ec2MetadataProxy.preferredMetadataSourceIp(Map.of("bridge", bridge));
        assertTrue(ip.isPresent());
        assertEquals("172.17.0.2", ip.get());
    }

    @Test
    void preferredMetadataSourceIpReturnsEmptyWhenNoNetworks() {
        assertTrue(Ec2MetadataProxy.preferredMetadataSourceIp(null).isEmpty());
        assertTrue(Ec2MetadataProxy.preferredMetadataSourceIp(Map.of()).isEmpty());
    }
}
