package io.floci.aws.services.eks;

import com.sun.net.httpserver.HttpServer;
import io.floci.aws.testing.HelperShellFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EksPodNetworkRoutingTest {

    /** What the stand-in iptables is asked when it lists a rule for the address in each chain. */
    private static final List<String> EARLIER_RULES_REMOVED = List.of(
            "-t nat -S PREROUTING",
            "-t nat -D PREROUTING -s 10.42.0.0/16 -d 169.254.170.23/32 -p tcp -m tcp --dport 80 -j DNAT"
                    + " --to-destination 172.18.0.9:4566",
            "-t nat -S OUTPUT",
            "-t nat -D OUTPUT -d 169.254.170.23/32 -p tcp -m tcp --dport 80 -j DNAT --to-destination 172.18.0.9:4566");

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

    @ParameterizedTest
    @ValueSource(strings = {"", "floci host", "floci;reboot", "$(id)", "floci`id`", "-floci", "floci-", "a'b"})
    void podIdentityForwardRefusesAHostTheShellCouldReadAsMore(String host) {
        assertThrows(IllegalArgumentException.class,
                () -> EksPodNetworkRouting.buildPodIdentityForwardCommand(null, host, 4566));
    }

    @Test
    void podIdentityForwardRefusesABadPodCidrPortOrHostsFile() {
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
        assertThrows(IllegalArgumentException.class,
                () -> EksPodNetworkRouting.buildPodIdentityForwardCommand(null, "floci", 4566, "hosts"));
        assertThrows(IllegalArgumentException.class,
                () -> EksPodNetworkRouting.buildPodIdentityForwardCommand(null, "floci", 4566, "/etc/ho'sts"));
    }

    /**
     * Runs the forward script against stand-ins for a stock k3s node's tools: iptables records what
     * it is asked and lists the rules an earlier Floci left for the address, nslookup answers as
     * BusyBox's does, and wget gets Floci's 400 for a request without a pod token. There is no curl.
     * The host is resolved from the hosts file first, through DNS when that has no entry, and an
     * address is used as it is.
     */
    @ParameterizedTest
    @CsvSource({
            "floci-under-test.internal, '', 172.18.0.2, true",
            "floci-under-test.internal, '172.18.0.7 other-name floci-under-test.internal', 172.18.0.7, false",
            "172.18.0.5, '', 172.18.0.5, false"})
    void podIdentityForwardResolvesTheHostReplacesEarlierRulesAndProbes(String host, String hostsEntry,
                                                                        String address, boolean looksUp,
                                                                        @TempDir Path dir) throws Exception {
        Path bin = fakeStockNode(dir);
        Path hosts = Files.writeString(dir.resolve("hosts"), "# 172.18.0.8 floci-under-test.internal\n" + hostsEntry + "\n");

        int exit = HelperShellFixture.run(
                EksPodNetworkRouting.buildPodIdentityForwardCommand(null, host, 4566, hosts.toString()), dir,
                Map.of("PATH", bin.toString(), "http_proxy", "http://127.0.0.1:9"));

        assertEquals(0, exit, HelperShellFixture.output(dir));
        List<String> expected = new ArrayList<>(EARLIER_RULES_REMOVED);
        expected.add("-t nat -I PREROUTING 1 -s 10.42.0.0/16 -d 169.254.170.23/32 -p tcp --dport 80 -j DNAT"
                + " --to-destination " + address + ":4566");
        expected.add("-t nat -I OUTPUT 1 -d 169.254.170.23/32 -p tcp --dport 80 -j DNAT --to-destination "
                + address + ":4566");
        assertEquals(expected, Files.readAllLines(dir.resolve("iptables-calls")));
        assertEquals(looksUp ? List.of(host) : List.of(), calls(dir.resolve("nslookup-calls")));
    }

    /** Without the add-on nothing answers on the address: every rule for it goes, the others stay. */
    @Test
    void podIdentityRemovalDeletesEveryRuleForTheAddressAndAddsNone(@TempDir Path dir) throws Exception {
        Path bin = fakeStockNode(dir);

        int exit = HelperShellFixture.run(EksPodNetworkRouting.buildPodIdentityRemoveCommand(), dir,
                Map.of("PATH", bin.toString()));

        assertEquals(0, exit, HelperShellFixture.output(dir));
        assertEquals(EARLIER_RULES_REMOVED, Files.readAllLines(dir.resolve("iptables-calls")));
    }

    /** A node without iptables has no rules to remove, but cannot be given any. */
    @Test
    void withoutIptablesRemovalSucceedsAndTheForwardFails(@TempDir Path dir) throws Exception {
        Path bin = Files.createDirectory(dir.resolve("bin"));
        HelperShellFixture.linkHostTools(bin, List.of("awk", "sed", "grep"));
        Map<String, String> environment = Map.of("PATH", bin.toString());

        assertEquals(0, HelperShellFixture.run(EksPodNetworkRouting.buildPodIdentityRemoveCommand(), dir, environment),
                HelperShellFixture.output(dir));
        assertEquals(1, HelperShellFixture.run(
                EksPodNetworkRouting.buildPodIdentityForwardCommand(null, "172.18.0.2", 4566), dir, environment));
        assertEquals("iptables not found in container\n", HelperShellFixture.output(dir));
    }

    /**
     * Floci answers 400 without a pod token, which still proves the path reaches it. A proxy that
     * answered in its place would look the same to the status check, so the probe must not go
     * through one, whether it asks with curl or, on a stock {@code rancher/k3s} node, which has
     * BusyBox's {@code wget} and no {@code curl}, with wget, reading the status from the error it
     * prints.
     */
    @ParameterizedTest
    @ValueSource(strings = {"curl", "wget"})
    void podIdentityProbeReachesFlociPastTheImagesHttpProxy(String client, @TempDir Path dir) throws Exception {
        Path bin = Files.createDirectory(dir.resolve("bin"));
        HelperShellFixture.linkHostTools(bin, List.of(client, "env", "grep"));
        List<String> proxied = new CopyOnWriteArrayList<>();
        AtomicInteger answered = new AtomicInteger();
        HttpServer proxy = null;
        HttpServer floci = null;
        try {
            proxy = HelperShellFixture.failingProxy(proxied);
            floci = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            floci.createContext("/v1/credentials", exchange -> {
                answered.incrementAndGet();
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
            });
            floci.start();
            String[] probe = {"sh", "-c",
                    EksPodNetworkRouting.podIdentityProbeCommand("http://127.0.0.1:" + floci.getAddress().getPort())};
            Map<String, String> environment = new HashMap<>(HelperShellFixture.proxyEnvironment(proxy));
            environment.put("PATH", bin.toString());

            int result = HelperShellFixture.run(probe, dir, environment);

            assertEquals(0, result, HelperShellFixture.output(dir));
            assertEquals(1, answered.get(), HelperShellFixture.output(dir));
            assertEquals(List.of(), proxied);
        } finally {
            if (floci != null) {
                floci.stop(0);
            }
            if (proxy != null) {
                proxy.stop(0);
            }
        }
    }

    /**
     * A bin directory of stand-ins for a stock k3s node's tools, beside the host's awk, sed, grep,
     * env, sleep and cat. iptables records its arguments in {@code iptables-calls} and lists, in
     * each chain, a rule an earlier Floci on 172.18.0.9 left for the address and one rule of
     * another's. nslookup records its arguments in {@code nslookup-calls} and answers as BusyBox's.
     */
    private static Path fakeStockNode(Path dir) throws Exception {
        Path bin = Files.createDirectory(dir.resolve("bin"));
        HelperShellFixture.linkHostTools(bin, List.of("awk", "sed", "grep", "env", "sleep", "cat"));
        HelperShellFixture.writeTool(bin, "iptables", "echo \"$*\" >> '" + dir.resolve("iptables-calls") + "'\n"
                + "if [ \"$*\" = '-t nat -S PREROUTING' ]; then\n"
                + "  echo '-P PREROUTING ACCEPT'\n"
                + "  echo '-A PREROUTING -s 10.42.0.0/16 -d 169.254.170.23/32 -p tcp -m tcp --dport 80 -j DNAT"
                + " --to-destination 172.18.0.9:4566'\n"
                + "  echo '-A PREROUTING -j FLOCI-LINK-LOCAL'\n"
                + "fi\n"
                + "if [ \"$*\" = '-t nat -S OUTPUT' ]; then\n"
                + "  echo '-P OUTPUT ACCEPT'\n"
                + "  echo '-A OUTPUT -d 169.254.170.23/32 -p tcp -m tcp --dport 80 -j DNAT --to-destination 172.18.0.9:4566'\n"
                + "  echo '-A OUTPUT -j KUBE-SERVICES'\n"
                + "fi\n");
        HelperShellFixture.writeTool(bin, "nslookup", "echo \"$*\" >> '" + dir.resolve("nslookup-calls") + "'\n"
                + "[ \"$1\" = floci-under-test.internal ] || exit 1\n"
                + "printf 'Server:\\t\\t127.0.0.11\\nAddress:\\t127.0.0.11:53\\n\\nNon-authoritative answer:\\n"
                + "Name:\\tfloci-under-test.internal\\nAddress: 172.18.0.2\\n'\n");
        HelperShellFixture.writeTool(bin, "wget", "[ -z \"${http_proxy:-}\" ] || exit 2\n"
                + "echo 'wget: server returned error: HTTP/1.1 400 Bad Request' >&2\nexit 1\n");
        return bin;
    }

    private static List<String> calls(Path record) throws Exception {
        return Files.exists(record) ? Files.readAllLines(record) : List.of();
    }
}
