package io.floci.aws.services.eks;

import com.sun.net.httpserver.HttpServer;
import io.floci.aws.testing.HelperShellFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EksPodNetworkRoutingTest {

    /** What the stand-in iptables is asked when it lists the rules for the address in each chain, and deletes them. */
    private static final List<String> EARLIER_RULES_REMOVED = List.of(
            "-t nat -S PREROUTING",
            "-t nat -D PREROUTING -s 10.42.0.0/16 -d 169.254.170.23/32 -p tcp -m tcp --dport 80 -j DNAT"
                    + " --to-destination 172.18.0.9:4566",
            "-t nat -S OUTPUT",
            "-t nat -D OUTPUT -d 169.254.170.23/32 -p tcp -m tcp --dport 80 -j DNAT --to-destination 172.18.0.9:4566",
            "-t nat -S FLOCI-LINK-LOCAL",
            "-t nat -D FLOCI-LINK-LOCAL -s 10.42.0.0/16 -d 169.254.170.23/32 -p tcp -m tcp --dport 80 -j DNAT"
                    + " --to-destination 169.254.170.23:80",
            "-t filter -S FORWARD");

    /** As {@link #EARLIER_RULES_REMOVED}, for IMDS. */
    private static final List<String> EARLIER_IMDS_RULES_REMOVED = List.of(
            "-t nat -S PREROUTING",
            "-t nat -D PREROUTING -s 10.42.0.0/16 -d 169.254.169.254/32 -p tcp -m tcp --dport 80 -j DNAT"
                    + " --to-destination 172.18.0.9:9169",
            "-t nat -S OUTPUT",
            "-t nat -D OUTPUT -d 169.254.169.254/32 -p tcp -m tcp --dport 80 -j DNAT --to-destination 172.18.0.9:9169",
            "-t nat -S FLOCI-LINK-LOCAL",
            "-t nat -D FLOCI-LINK-LOCAL -s 10.42.0.0/16 -d 169.254.169.254/32 -p tcp -m tcp --dport 80 -j DNAT"
                    + " --to-destination 169.254.169.254:80",
            "-t filter -S FORWARD",
            "-t filter -D FORWARD -s 10.42.0.0/16 -d 169.254.169.254/32 -p tcp -m tcp --dport 80 -j REJECT"
                    + " --reject-with tcp-reset");

    /** Where the relay an older Floci ran for the address kept its PID. */
    private static final Path RELAY_PID_FILE = Path.of("/tmp/floci-pod-identity-proxy.pid");
    /** As {@link #RELAY_PID_FILE}, for IMDS. */
    private static final Path IMDS_RELAY_PID_FILE = Path.of("/tmp/floci-imds-proxy.pid");

    @Test
    void podRulesTakeSeveralSourceRangesSeparatedByCommas() {
        String script = EksPodNetworkRouting.buildImdsForwardCommand("10.0.1.224/28,10.0.1.208/28", true,
                "10.0.0.5", 9169)[2];

        assertTrue(script.contains("-s 10.0.1.224/28,10.0.1.208/28 "), script);
        assertThrows(IllegalArgumentException.class, () ->
                EksPodNetworkRouting.buildImdsForwardCommand("10.0.1.224/28,bogus", true, "10.0.0.5", 9169));
        assertThrows(IllegalArgumentException.class, () ->
                EksPodNetworkRouting.buildImdsForwardCommand("10.0.1.224/28,", true, "10.0.0.5", 9169));
    }

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
     * Runs the forward script against stand-ins for a stock k3s node's tools ({@link #fakeStockNode}):
     * iptables records what it is asked and lists the rules an earlier Floci left for the address,
     * nslookup answers as BusyBox's does, and wget gets Floci's 400 for a request without a pod token.
     * There is no curl.
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

    @Test
    void imdsForwardRefusesABadHostOrPodCidr() {
        assertThrows(IllegalArgumentException.class,
                () -> EksPodNetworkRouting.buildImdsForwardCommand(null, true, "floci;reboot", 9169));
        assertThrows(IllegalArgumentException.class,
                () -> EksPodNetworkRouting.buildImdsForwardCommand("10.42.0.0/16; id", true, "floci", 9169));
        assertThrows(IllegalArgumentException.class,
                () -> EksPodNetworkRouting.buildImdsForwardCommand(null, true, "floci", 0));
    }

    /**
     * IMDS goes to Floci's metadata service as the Pod Identity address goes to its credentials endpoint, once the
     * rules an earlier Floci left for it are gone: from the node always, and from the pod network when asked. When not,
     * the pod network's requests are refused, rather than sent on toward the network the Docker host is on.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void imdsForwardReplacesEarlierRulesAndTakesThePodNetworkOnlyWhenAsked(boolean podNetwork, @TempDir Path dir)
            throws Exception {
        Path bin = fakeStockNode(dir);
        Path hosts = Files.writeString(dir.resolve("hosts"), "172.18.0.7 floci-under-test.internal\n");

        int exit = HelperShellFixture.run(EksPodNetworkRouting.buildImdsForwardCommand(null, podNetwork,
                        "floci-under-test.internal", 9169, hosts.toString()), dir,
                Map.of("PATH", bin.toString(), "http_proxy", "http://127.0.0.1:9"));

        assertEquals(0, exit, HelperShellFixture.output(dir));
        List<String> expected = new ArrayList<>(EARLIER_IMDS_RULES_REMOVED);
        if (podNetwork) {
            expected.add("-t nat -I PREROUTING 1 -s 10.42.0.0/16 -d 169.254.169.254/32 -p tcp --dport 80 -j DNAT"
                    + " --to-destination 172.18.0.7:9169");
        } else {
            expected.add("-t filter -I FORWARD 1 -s 10.42.0.0/16 -d 169.254.169.254/32 -p tcp --dport 80 -j REJECT"
                    + " --reject-with tcp-reset");
        }
        expected.add("-t nat -I OUTPUT 1 -d 169.254.169.254/32 -p tcp --dport 80 -j DNAT --to-destination"
                + " 172.18.0.7:9169");
        assertEquals(expected, Files.readAllLines(dir.resolve("iptables-calls")));
    }

    /**
     * The IMDS relay an older Floci ran on the node's loopback would keep the address local to the node, so the
     * forward stops it, while its PID file still names it, and takes its address off the loopback. The forward runs
     * traced, so the test sees the {@code kill} it sends.
     */
    @Test
    void imdsForwardRetiresTheRelayAnOlderFlociRan(@TempDir Path dir) throws Exception {
        Path bin = fakeStockNode(dir);
        HelperShellFixture.writeTool(bin, "ip", "echo \"$*\" >> '" + dir.resolve("ip-calls") + "'\n"
                + "if [ \"$*\" = 'addr show dev lo' ]; then\n"
                + "  echo '    inet 127.0.0.1/8 scope host lo'\n"
                + "  echo '    inet 169.254.169.254/32 scope global lo'\n"
                + "fi\n");
        Process relay = startFakeRelay(bin, "169.254.169.254");
        try {
            Files.writeString(IMDS_RELAY_PID_FILE, relay.pid() + "\n");

            int exit = HelperShellFixture.run(
                    traced(EksPodNetworkRouting.buildImdsForwardCommand(null, true, "172.18.0.7", 9169)), dir,
                    Map.of("PATH", bin.toString()));

            assertEquals(0, exit, HelperShellFixture.output(dir));
            assertEquals(List.of("kill " + relay.pid()), tracedKills(dir), HelperShellFixture.output(dir));
            assertTrue(relay.waitFor(10, TimeUnit.SECONDS), "the relay is still running");
            assertFalse(Files.exists(IMDS_RELAY_PID_FILE));
            assertEquals(List.of("addr show dev lo", "addr del 169.254.169.254/32 dev lo"),
                    calls(dir.resolve("ip-calls")));
        } finally {
            relay.destroyForcibly();
            Files.deleteIfExists(IMDS_RELAY_PID_FILE);
        }
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

    /**
     * An older Floci answered on the address with a socat relay on the node's loopback, which would answer again once
     * the rules in front of it went: the removal stops it, while its PID file still names it, and takes its address
     * off the loopback. The removal runs traced, so the test sees the {@code kill} it sends.
     */
    @Test
    void podIdentityRemovalRetiresTheRelayAnOlderFlociRan(@TempDir Path dir) throws Exception {
        Path bin = fakeStockNode(dir);
        HelperShellFixture.writeTool(bin, "ip", "echo \"$*\" >> '" + dir.resolve("ip-calls") + "'\n"
                + "if [ \"$*\" = 'addr show dev lo' ]; then\n"
                + "  echo '    inet 127.0.0.1/8 scope host lo'\n"
                + "  echo '    inet 169.254.170.23/32 scope global lo'\n"
                + "fi\n");
        Process relay = startFakeRelay(bin, "169.254.170.23");
        try {
            Files.writeString(RELAY_PID_FILE, relay.pid() + "\n");

            int exit = HelperShellFixture.run(traced(EksPodNetworkRouting.buildPodIdentityRemoveCommand()), dir,
                    Map.of("PATH", bin.toString()));

            assertEquals(0, exit, HelperShellFixture.output(dir));
            assertEquals(List.of("kill " + relay.pid()), tracedKills(dir), HelperShellFixture.output(dir));
            assertTrue(relay.waitFor(10, TimeUnit.SECONDS), "the relay is still running");
            assertFalse(Files.exists(RELAY_PID_FILE));
            assertEquals(List.of("addr show dev lo", "addr del 169.254.170.23/32 dev lo"),
                    calls(dir.resolve("ip-calls")));
        } finally {
            relay.destroyForcibly();
            Files.deleteIfExists(RELAY_PID_FILE);
        }
    }

    /**
     * A PID file that names another process, here a socat relaying IMDS, leaves that process running: the traced
     * removal sends it no {@code kill}.
     */
    @Test
    void podIdentityRemovalLeavesTheProcessAStalePidFileNames(@TempDir Path dir) throws Exception {
        Path bin = fakeStockNode(dir);
        Process imdsRelay = startFakeRelay(bin, "169.254.169.254");
        try {
            Files.writeString(RELAY_PID_FILE, imdsRelay.pid() + "\n");

            int exit = HelperShellFixture.run(traced(EksPodNetworkRouting.buildPodIdentityRemoveCommand()), dir,
                    Map.of("PATH", bin.toString()));

            assertEquals(0, exit, HelperShellFixture.output(dir));
            assertEquals(List.of(), tracedKills(dir), HelperShellFixture.output(dir));
            assertTrue(imdsRelay.isAlive(), "the IMDS relay was stopped");
            assertFalse(Files.exists(RELAY_PID_FILE));
        } finally {
            imdsRelay.destroyForcibly();
            Files.deleteIfExists(RELAY_PID_FILE);
        }
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
     * BusyBox's {@code wget} and no {@code curl}, with wget, reading the status from the status line
     * {@code -S} prints.
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
            String[] probe = probe(
                    EksPodNetworkRouting.podIdentityProbeCommand("http://127.0.0.1:" + floci.getAddress().getPort()));
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
     * The IMDS forward is ready once the node's instance id answers, or 401 for an instance that requires tokens:
     * Floci's 404, for a caller it doesn't know by its address, or another server's error leaves it waiting. With
     * curl, or with wget, whose status line {@code -S} prints: GNU's prints no status in its own message for a 401.
     */
    @ParameterizedTest
    @CsvSource({"curl, 200, 0", "curl, 401, 0", "curl, 404, 1", "curl, 500, 1",
            "wget, 200, 0", "wget, 401, 0", "wget, 404, 1", "wget, 500, 1"})
    void imdsProbeIsReadyOnceTheNodesInstanceIdAnswers(String client, int status, int expectedExit, @TempDir Path dir)
            throws Exception {
        Path bin = Files.createDirectory(dir.resolve("bin"));
        HelperShellFixture.linkHostTools(bin, List.of(client, "env", "grep"));
        List<String> asked = new CopyOnWriteArrayList<>();
        HttpServer imds = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            imds.createContext("/", exchange -> {
                asked.add(exchange.getRequestURI().getPath());
                byte[] body = "i-0123456789abcdef0".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            imds.start();

            int result = HelperShellFixture.run(
                    probe(EksPodNetworkRouting.imdsProbeCommand("http://127.0.0.1:" + imds.getAddress().getPort())),
                    dir, Map.of("PATH", bin.toString()));

            assertEquals(expectedExit, result, HelperShellFixture.output(dir));
            assertEquals(List.of("/latest/meta-data/instance-id"), asked);
        } finally {
            imds.stop(0);
        }
    }

    /**
     * A bin directory of stand-ins for a stock k3s node's tools, beside the host's awk, sed, grep,
     * env, sleep, cat, tr and rm. iptables records its arguments in {@code iptables-calls} and lists,
     * in {@code PREROUTING} and {@code OUTPUT}, the rules an earlier Floci on 172.18.0.9 left for the
     * Pod Identity address and for IMDS and one rule of another's, in {@code FORWARD} the rule an earlier
     * Floci refused the pod network's IMDS requests with and one of another's, and in
     * {@code FLOCI-LINK-LOCAL} the rules an older Floci sent each address to its relay with. nslookup
     * records its arguments in {@code nslookup-calls} and answers as BusyBox's. wget prints the status
     * line as BusyBox's does with {@code -S}: a 200 for the node's instance id from IMDS, and Floci's
     * 400 for anything else, as for a Pod Identity request without a pod token.
     */
    private static Path fakeStockNode(Path dir) throws Exception {
        Path bin = Files.createDirectory(dir.resolve("bin"));
        HelperShellFixture.linkHostTools(bin, List.of("awk", "sed", "grep", "env", "sleep", "cat", "tr", "rm"));
        HelperShellFixture.writeTool(bin, "iptables", "echo \"$*\" >> '" + dir.resolve("iptables-calls") + "'\n"
                + "if [ \"$*\" = '-t nat -S PREROUTING' ]; then\n"
                + "  echo '-P PREROUTING ACCEPT'\n"
                + "  echo '-A PREROUTING -s 10.42.0.0/16 -d 169.254.170.23/32 -p tcp -m tcp --dport 80 -j DNAT"
                + " --to-destination 172.18.0.9:4566'\n"
                + "  echo '-A PREROUTING -s 10.42.0.0/16 -d 169.254.169.254/32 -p tcp -m tcp --dport 80 -j DNAT"
                + " --to-destination 172.18.0.9:9169'\n"
                + "  echo '-A PREROUTING -j FLOCI-LINK-LOCAL'\n"
                + "fi\n"
                + "if [ \"$*\" = '-t nat -S OUTPUT' ]; then\n"
                + "  echo '-P OUTPUT ACCEPT'\n"
                + "  echo '-A OUTPUT -d 169.254.170.23/32 -p tcp -m tcp --dport 80 -j DNAT --to-destination 172.18.0.9:4566'\n"
                + "  echo '-A OUTPUT -d 169.254.169.254/32 -p tcp -m tcp --dport 80 -j DNAT --to-destination 172.18.0.9:9169'\n"
                + "  echo '-A OUTPUT -j KUBE-SERVICES'\n"
                + "fi\n"
                + "if [ \"$*\" = '-t filter -S FORWARD' ]; then\n"
                + "  echo '-P FORWARD ACCEPT'\n"
                + "  echo '-A FORWARD -s 10.42.0.0/16 -d 169.254.169.254/32 -p tcp -m tcp --dport 80 -j REJECT"
                + " --reject-with tcp-reset'\n"
                + "  echo '-A FORWARD -j KUBE-ROUTER-FORWARD'\n"
                + "fi\n"
                + "if [ \"$*\" = '-t nat -S FLOCI-LINK-LOCAL' ]; then\n"
                + "  echo '-N FLOCI-LINK-LOCAL'\n"
                + "  echo '-A FLOCI-LINK-LOCAL -s 10.42.0.0/16 -d 169.254.169.254/32 -p tcp -m tcp --dport 80 -j DNAT"
                + " --to-destination 169.254.169.254:80'\n"
                + "  echo '-A FLOCI-LINK-LOCAL -s 10.42.0.0/16 -d 169.254.170.23/32 -p tcp -m tcp --dport 80 -j DNAT"
                + " --to-destination 169.254.170.23:80'\n"
                + "fi\n");
        HelperShellFixture.writeTool(bin, "nslookup", "echo \"$*\" >> '" + dir.resolve("nslookup-calls") + "'\n"
                + "[ \"$1\" = floci-under-test.internal ] || exit 1\n"
                + "printf 'Server:\\t\\t127.0.0.11\\nAddress:\\t127.0.0.11:53\\n\\nNon-authoritative answer:\\n"
                + "Name:\\tfloci-under-test.internal\\nAddress: 172.18.0.2\\n'\n");
        HelperShellFixture.writeTool(bin, "wget", "[ -z \"${http_proxy:-}\" ] || exit 2\n"
                + "case \"$*\" in *' http://169.254.169.254/latest/meta-data/instance-id')\n"
                + "  echo '  HTTP/1.1 200 OK' >&2\n  exit 0 ;;\nesac\n"
                + "echo '  HTTP/1.1 400 Bad Request' >&2\n"
                + "echo 'wget: server returned error: HTTP/1.1 400 Bad Request' >&2\nexit 1\n");
        return bin;
    }

    /**
     * A process named socat, as the relay an older Floci ran is, with that relay's arguments for {@code bindIp}. It
     * ends on SIGTERM.
     */
    private static Process startFakeRelay(Path bin, String bindIp) throws Exception {
        Path relay = Files.createDirectories(bin.resolveSibling("relay")).resolve("socat");
        if (!Files.exists(relay)) {
            HelperShellFixture.writeTool(relay.getParent(), "socat",
                    "trap 'kill \"$child\" 2>/dev/null; exit 0' TERM\nsleep 60 &\nchild=$!\nwait \"$child\"\n");
        }
        return new ProcessBuilder(relay.toString(), "TCP-LISTEN:80,bind=" + bindIp + ",fork,reuseaddr",
                "TCP:172.18.0.9:4566").start();
    }

    /** A probe run on its own: it ends the shell once ready, and otherwise the shell fails. */
    private static String[] probe(String probeCommand) {
        return new String[]{"sh", "-c", probeCommand + "\nexit 1"};
    }

    /** {@code command}, an {@code sh -c} one, run with each command it runs printed to its output ({@code sh -x}). */
    private static String[] traced(String[] command) {
        List<String> arguments = new ArrayList<>(List.of(command));
        arguments.add(1, "-x");
        return arguments.toArray(String[]::new);
    }

    /** The {@code kill} commands a {@link #traced} shell ran, as its trace prints them: {@code kill <pid>}. */
    private static List<String> tracedKills(Path dir) throws Exception {
        return HelperShellFixture.output(dir).lines()
                .filter(line -> line.matches("\\++ kill .*"))
                .map(line -> line.substring(line.indexOf(' ') + 1))
                .toList();
    }

    private static List<String> calls(Path record) throws Exception {
        return Files.exists(record) ? Files.readAllLines(record) : List.of();
    }
}
