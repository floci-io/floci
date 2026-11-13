package io.floci.aws.services.eks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class EksPodNetworkRoutingTest {

    @Test
    void linkLocalEndpointValidatesInput() {
        assertThrows(NullPointerException.class, () -> new LinkLocalEndpoint(null, 80));
        assertThrows(IllegalArgumentException.class, () -> new LinkLocalEndpoint("", 80));
        assertThrows(IllegalArgumentException.class, () -> new LinkLocalEndpoint("   ", 80));
        assertThrows(IllegalArgumentException.class, () -> new LinkLocalEndpoint("invalid-ip", 80));
        assertThrows(IllegalArgumentException.class, () -> new LinkLocalEndpoint("169.254.169.256", 80));
        assertThrows(IllegalArgumentException.class, () -> new LinkLocalEndpoint("-1.0.0.0", 80));
        assertThrows(IllegalArgumentException.class, () -> new LinkLocalEndpoint("169.254.169", 80));
        assertThrows(IllegalArgumentException.class, () -> new LinkLocalEndpoint("169.254.169.254.1", 80));
        assertThrows(IllegalArgumentException.class, () -> new LinkLocalEndpoint("169.254.01.1", 80));
        assertThrows(IllegalArgumentException.class, () -> new LinkLocalEndpoint("; rm -rf / ;", 80));
        assertThrows(IllegalArgumentException.class, () -> new LinkLocalEndpoint("169.254.169.254", 0));
        assertThrows(IllegalArgumentException.class, () -> new LinkLocalEndpoint("169.254.169.254", -1));
        assertThrows(IllegalArgumentException.class, () -> new LinkLocalEndpoint("169.254.169.254", 65536));

        LinkLocalEndpoint ep = new LinkLocalEndpoint("169.254.169.254", 80);
        assertEquals("169.254.169.254", ep.ip());
        assertEquals(80, ep.port());
    }

    @Test
    void buildRoutingCommandRejectsNullOrEmptyEndpoints() {
        assertThrows(IllegalArgumentException.class, () ->
                EksPodNetworkRouting.buildRoutingCommand(null, null));
        assertThrows(IllegalArgumentException.class, () ->
                EksPodNetworkRouting.buildRoutingCommand("10.42.0.0/16", List.of()));
    }

    @Test
    void buildRoutingCommandUsesDefaultPodCidrWhenNullOrBlank() {
        String[] cmdNull = EksPodNetworkRouting.buildRoutingCommand(null, EksPodNetworkRouting.DEFAULT_ENDPOINTS);
        assertEquals(3, cmdNull.length);
        assertEquals("sh", cmdNull[0]);
        assertEquals("-c", cmdNull[1]);
        assertTrue(cmdNull[2].contains(EksPodNetworkRouting.DEFAULT_POD_CIDR));

        String[] cmdBlank = EksPodNetworkRouting.buildRoutingCommand("   ", EksPodNetworkRouting.DEFAULT_ENDPOINTS);
        assertTrue(cmdBlank[2].contains(EksPodNetworkRouting.DEFAULT_POD_CIDR));
    }

    @Test
    void buildRoutingCommandGeneratesIdempotentChainAndRules() {
        String[] cmd = EksPodNetworkRouting.buildRoutingCommand("10.42.0.0/16", List.of(
                new LinkLocalEndpoint("169.254.169.254", 80),
                new LinkLocalEndpoint("169.254.170.23", 80)
        ));

        String script = cmd[2];
        assertNotNull(script);

        // Finds iptables binary across standard and k3s aux locations
        assertTrue(script.contains("IPTABLES=$(command -v iptables 2>/dev/null || command -v /bin/aux/iptables 2>/dev/null || true)"));
        assertTrue(script.contains("iptables not found in container"));

        // Creates custom chain idempotently
        assertTrue(script.contains("$IPTABLES -t nat -N FLOCI-LINK-LOCAL 2>/dev/null || true"));

        // Inserts jump in PREROUTING only if not already present
        assertTrue(script.contains("$IPTABLES -t nat -C PREROUTING -j FLOCI-LINK-LOCAL 2>/dev/null || $IPTABLES -t nat -I PREROUTING 1 -j FLOCI-LINK-LOCAL"));

        // Adds rule for IMDS endpoint with check before append
        String imdsCheck = "$IPTABLES -t nat -C FLOCI-LINK-LOCAL -s 10.42.0.0/16 -d 169.254.169.254 -p tcp --dport 80 -j DNAT --to-destination 169.254.169.254:80 2>/dev/null";
        String imdsAdd = "$IPTABLES -t nat -A FLOCI-LINK-LOCAL -s 10.42.0.0/16 -d 169.254.169.254 -p tcp --dport 80 -j DNAT --to-destination 169.254.169.254:80";
        assertTrue(script.contains(imdsCheck + " || " + imdsAdd));

        // Adds rule for Pod Identity endpoint with check before append
        String podIdCheck = "$IPTABLES -t nat -C FLOCI-LINK-LOCAL -s 10.42.0.0/16 -d 169.254.170.23 -p tcp --dport 80 -j DNAT --to-destination 169.254.170.23:80 2>/dev/null";
        String podIdAdd = "$IPTABLES -t nat -A FLOCI-LINK-LOCAL -s 10.42.0.0/16 -d 169.254.170.23 -p tcp --dport 80 -j DNAT --to-destination 169.254.170.23:80";
        assertTrue(script.contains(podIdCheck + " || " + podIdAdd));
    }

    @Test
    void buildRoutingCommandSupportsCustomCidr() {
        String customCidr = "192.168.0.0/16";
        String[] cmd = EksPodNetworkRouting.buildRoutingCommand(customCidr, EksPodNetworkRouting.DEFAULT_ENDPOINTS);
        String script = cmd[2];

        assertTrue(script.contains("-s 192.168.0.0/16 -d 169.254.169.254"));
    }

    @Test
    void podIdentityForwardSendsPodAndNodeRequestsToFloci() {
        String[] cmd = EksPodNetworkRouting.buildPodIdentityForwardCommand(null, "172.18.0.2", 4566);
        assertEquals(3, cmd.length);
        assertEquals("sh", cmd[0]);
        assertEquals("-c", cmd[1]);
        String script = cmd[2];

        assertTrue(script.contains("HOST='172.18.0.2'"), script);
        assertTrue(script.contains("TARGET=\"$ADDR:4566\""), script);
        assertTrue(script.contains("$IPTABLES -t nat -I PREROUTING 1 -s 10.42.0.0/16 -d 169.254.170.23/32 -p tcp --dport 80"
                + " -j DNAT --to-destination \"$TARGET\""), script);
        assertTrue(script.contains("$IPTABLES -t nat -I OUTPUT 1 -d 169.254.170.23/32 -p tcp --dport 80"
                + " -j DNAT --to-destination \"$TARGET\""), script);
        assertFalse(script.contains("socat"), script);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "floci host", "floci;reboot", "$(id)", "floci`id`", "-floci", "floci-", "a'b"})
    void podIdentityForwardRefusesAHostTheShellCouldReadAsMore(String host) {
        assertThrows(IllegalArgumentException.class,
                () -> EksPodNetworkRouting.buildPodIdentityForwardCommand(null, host, 4566));
    }

    @Test
    void podIdentityForwardRefusesABadPodCidrOrPort() {
        assertThrows(IllegalArgumentException.class,
                () -> EksPodNetworkRouting.buildPodIdentityForwardCommand("10.42.0.0", "floci", 4566));
        assertThrows(IllegalArgumentException.class,
                () -> EksPodNetworkRouting.buildPodIdentityForwardCommand("10.42.0.0/16; id", "floci", 4566));
        assertThrows(IllegalArgumentException.class,
                () -> EksPodNetworkRouting.buildPodIdentityForwardCommand(null, null, 4566));
        assertThrows(IllegalArgumentException.class,
                () -> EksPodNetworkRouting.buildPodIdentityForwardCommand(null, "floci", 0));
        assertThrows(IllegalArgumentException.class,
                () -> EksPodNetworkRouting.buildPodIdentityForwardCommand(null, "floci", 65536));
    }

    /**
     * Runs the forward script against stand-ins for a stock k3s node's tools: iptables records what
     * it is asked and lists a rule an earlier Floci left for the address, nslookup answers as
     * BusyBox's does, and wget gets Floci's 400 for a request without a pod token. There is no curl.
     */
    @Test
    void podIdentityForwardResolvesTheHostReplacesAnEarlierRuleAndProbes(@TempDir Path dir) throws Exception {
        Path bin = Files.createDirectory(dir.resolve("bin"));
        for (String tool : List.of("awk", "sed", "grep", "env", "sleep", "cat")) {
            Path found = onPath(tool);
            assumeTrue(found != null, tool + " is not installed on this host");
            Files.createSymbolicLink(bin.resolve(tool), found);
        }
        Path record = dir.resolve("iptables-calls");
        tool(bin, "iptables", "echo \"$*\" >> '" + record + "'\n"
                + "if [ \"$*\" = '-t nat -S PREROUTING' ]; then\n"
                + "  echo '-P PREROUTING ACCEPT'\n"
                + "  echo '-A PREROUTING -s 10.42.0.0/16 -d 169.254.170.23/32 -p tcp -m tcp --dport 80 -j DNAT"
                + " --to-destination 172.18.0.9:4566'\n"
                + "  echo '-A PREROUTING -j FLOCI-LINK-LOCAL'\n"
                + "fi\n");
        tool(bin, "nslookup", "[ \"$1\" = floci-under-test.internal ] || exit 1\n"
                + "printf 'Server:\\t\\t127.0.0.11\\nAddress:\\t127.0.0.11:53\\n\\nNon-authoritative answer:\\n"
                + "Name:\\tfloci-under-test.internal\\nAddress: 172.18.0.2\\n'\n");
        tool(bin, "wget", "[ -z \"${http_proxy:-}\" ] || exit 2\n"
                + "echo 'wget: server returned error: HTTP/1.1 400 Bad Request' >&2\nexit 1\n");

        String[] cmd = EksPodNetworkRouting.buildPodIdentityForwardCommand(null, "floci-under-test.internal", 4566);
        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", cmd[2]).redirectErrorStream(true);
        builder.environment().put("PATH", bin.toString());
        builder.environment().put("http_proxy", "http://127.0.0.1:9");
        Process process = builder.start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "the forward script exceeded its bound");
        String output = new String(process.getInputStream().readAllBytes());

        assertEquals(0, process.exitValue(), output);
        assertEquals(List.of(
                "-t nat -S PREROUTING",
                "-t nat -D PREROUTING -s 10.42.0.0/16 -d 169.254.170.23/32 -p tcp -m tcp --dport 80 -j DNAT"
                        + " --to-destination 172.18.0.9:4566",
                "-t nat -S OUTPUT",
                "-t nat -I PREROUTING 1 -s 10.42.0.0/16 -d 169.254.170.23/32 -p tcp --dport 80 -j DNAT"
                        + " --to-destination 172.18.0.2:4566",
                "-t nat -I OUTPUT 1 -d 169.254.170.23/32 -p tcp --dport 80 -j DNAT --to-destination 172.18.0.2:4566"),
                Files.readAllLines(record));
    }

    private static void tool(Path bin, String name, String script) throws IOException {
        Path tool = bin.resolve(name);
        Files.writeString(tool, "#!/bin/sh\n" + script);
        assertTrue(tool.toFile().setExecutable(true));
    }

    private static Path onPath(String tool) {
        for (String entry : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            Path candidate = Path.of(entry, tool);
            if (!entry.isEmpty() && Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath();
            }
        }
        return null;
    }
}
