package io.floci.aws.services.emr;

import com.github.dockerjava.api.DockerClient;
import com.sun.net.httpserver.HttpServer;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerExecStubs;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.services.ec2.Ec2MetadataServer;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.lambda.launcher.BootstrapProcess;
import io.floci.aws.testutil.FreePorts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A cluster's instance metadata relay, and the link-local addresses its containers are given. */
class EmrMetadataRelaysTest {

    private static final String NETWORK = "floci-emr-j-1-hdfs";
    private static final String IMAGE = "sha256:image";

    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final DockerClient docker = mock(DockerClient.class);
    private final ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
    private final ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
    private final ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
    private final Ec2MetadataServer server = mock(Ec2MetadataServer.class);
    private final EmrMetadataRelays relays = new EmrMetadataRelays(containerBuilder, lifecycle, server, config);
    private final String relayName = ContainerStorageHelper.dockerName(config, EmrMetadataRelays.name("j-1"));

    @BeforeEach
    void docker() {
        when(lifecycle.getDockerClient()).thenReturn(docker);
        when(containerBuilder.newContainer(IMAGE)).thenReturn(spec);
        when(spec.build()).thenReturn(new ContainerSpec(IMAGE));
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("relay-1", Map.of()));
        when(lifecycle.resolveContainerNetworkIp("relay-1", NETWORK)).thenReturn("172.20.0.3");
        when(config.services().ec2().imdsPort()).thenReturn(9169);
        ContainerExecStubs.completeEveryExec(docker, "relay-1", 0L, "", "");
    }

    @Test
    void aClustersRelayHoldsTheMetadataAddressOnItsNetworkAndIsTrustedOnceItListens() {
        relays.start("j-1", "123456789012", "us-east-1", NETWORK, IMAGE, "floci");

        verify(spec).withLinkLocalIp("169.254.169.254");
        verify(spec).withDockerNetwork(Optional.of(NETWORK));
        verify(spec).withUser("root");
        verify(spec).withEntrypoint(List.of("python3", "-c", EmrMetadataRelays.RELAY_SCRIPT, "floci", "9169"));
        verify(server).trustForwarder("172.20.0.3");
        assertTrue(relays.serves("j-1"));
    }

    @Test
    void aRelayThatStoppedIsTrustedNoMoreBeforeItGivesWayToANewOne() {
        relays.start("j-1", "123456789012", "us-east-1", NETWORK, IMAGE, "floci");
        when(lifecycle.createAndStart(any()))
                .thenReturn(new ContainerLifecycleManager.ContainerInfo("relay-2", Map.of()));
        when(lifecycle.resolveContainerNetworkIp("relay-2", NETWORK)).thenReturn("172.20.0.4");
        ContainerExecStubs.completeEveryExec(docker, "relay-2", 0L, "", "");

        relays.start("j-1", "123456789012", "us-east-1", NETWORK, IMAGE, "floci");

        // Docker may give the stopped one's address to another container once it has gone.
        InOrder order = inOrder(server, lifecycle);
        order.verify(server).distrustForwarder("172.20.0.3");
        order.verify(lifecycle).removeIfExistsStrict(relayName);
        order.verify(server).trustForwarder("172.20.0.4");
    }

    @Test
    void aRelayDockerKeepsIsTrustedNoMoreAndTheNextStopRemovesIt() {
        relays.start("j-1", "123456789012", "us-east-1", NETWORK, IMAGE, "floci");
        doThrow(new IllegalStateException("Docker kept it")).doNothing()
                .when(lifecycle).removeIfExistsStrict(relayName);

        assertFalse(relays.stop("j-1"));

        verify(server).distrustForwarder("172.20.0.3");
        assertFalse(relays.serves("j-1"));

        relays.stopManagedContainers();

        // Once as it started, then as its cluster ended, and again as Floci stopped.
        verify(lifecycle, times(3)).removeIfExistsStrict(relayName);
    }

    @Test
    void eachContainerGetsAnAddressOfItsOwnWhichTheServerAnswersAsItsInstance() {
        Instance primary = instance("i-00000000000000001");
        Instance core = instance("i-00000000000000002");

        String bootstrap = relays.give("j-1", primary);
        String step = relays.give("j-1", primary);
        String node = relays.give("j-1", core);

        assertEquals(3, Set.of(bootstrap, step, node).size());
        assertTrue(Set.of(bootstrap, step, node).stream().allMatch(address -> address.startsWith("169.254.")));
        assertTrue(Set.of(bootstrap, step, node).stream().noneMatch("169.254.169.254"::equals));
        verify(server).reconcileClusterNodeAddresses(Set.of(bootstrap, step), primary);
        verify(server).reconcileClusterNodeAddresses(Set.of(node), core);
    }

    @Test
    void anInstanceIsForgottenWithTheLastAddressItWasGiven() {
        Instance primary = instance("i-00000000000000001");
        String bootstrap = relays.give("j-1", primary);
        String step = relays.give("j-1", primary);

        relays.release(bootstrap);

        verify(server).reconcileClusterNodeAddresses(Set.of(step), primary);
        verify(server, never()).unregisterInstance(primary);

        relays.release(step);

        verify(server).unregisterInstance(primary);
        // A released address is given again.
        assertEquals(bootstrap, relays.give("j-1", instance("i-00000000000000003")));
    }

    @Test
    void aClustersEndRemovesItsRelayAndEveryAddressGivenOnItsNetwork() {
        relays.start("j-1", "123456789012", "us-east-1", NETWORK, IMAGE, "floci");
        Instance primary = instance("i-00000000000000001");
        Instance other = instance("i-00000000000000001");
        relays.give("j-1", primary);
        String kept = relays.give("j-2", other);

        assertTrue(relays.stop("j-1"));

        verify(server).distrustForwarder("172.20.0.3");
        // Once as it started, which a container of its name left would have kept from it, and once as it went.
        verify(lifecycle, times(2)).removeIfExistsStrict(relayName);
        verify(server).unregisterInstance(primary);
        verify(server, never()).unregisterInstance(other);
        assertNotEquals(kept, relays.give("j-2", instance("i-00000000000000002")));
    }

    @Test
    void theRelayNamesItsCallerInPlaceOfAnyItClaimsAndPassesTheAnswerBack() throws Exception {
        boolean python;
        try {
            python = BootstrapProcess.run(new ProcessBuilder("python3", "--version")).exit() == 0;
        } catch (IOException e) {
            python = false;
        }
        assumeTrue(python, "the host has no python3");
        List<String> seen = new CopyOnWriteArrayList<>();
        HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/", exchange -> {
            seen.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " "
                    + exchange.getRequestHeaders().getFirst("X-Forwarded-For") + " "
                    + exchange.getRequestHeaders().getFirst("X-aws-ec2-metadata-token-ttl-seconds"));
            byte[] body = "i-00000000000000001".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("X-aws-ec2-metadata-token-ttl-seconds", "60");
            if ("HEAD".equals(exchange.getRequestMethod())) {
                // The length of the body a GET would have, which a HEAD's answer gives.
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(body.length));
            }
            exchange.sendResponseHeaders(200, "HEAD".equals(exchange.getRequestMethod()) ? -1 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                if (!"HEAD".equals(exchange.getRequestMethod())) {
                    out.write(body);
                }
            }
        });
        target.start();
        int port = FreePorts.loopbackIpv4Port();
        String script = EmrMetadataRelays.RELAY_SCRIPT.replace("Server(('169.254.169.254', 80)",
                "Server(('127.0.0.1', " + port + ")");
        Process relay = new ProcessBuilder("python3", "-I", "-c", script, "127.0.0.1",
                String.valueOf(target.getAddress().getPort())).redirectErrorStream(true).start();
        try (HttpClient client = HttpClient.newHttpClient()) {
            awaitListening(port, relay);
            String base = "http://127.0.0.1:" + port;

            HttpResponse<String> got = client.send(HttpRequest.newBuilder(URI.create(base + "/latest/meta-data/instance-id"))
                    .header("X-Forwarded-For", "203.0.113.9").timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> token = client.send(HttpRequest.newBuilder(URI.create(base + "/latest/api/token"))
                    .header("X-aws-ec2-metadata-token-ttl-seconds", "60").timeout(Duration.ofSeconds(5))
                    .PUT(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> head = client.send(HttpRequest.newBuilder(URI.create(base + "/latest/meta-data/"))
                    .timeout(Duration.ofSeconds(5)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, got.statusCode());
            assertEquals("i-00000000000000001", got.body());
            assertEquals("60", token.headers().firstValue("X-aws-ec2-metadata-token-ttl-seconds").orElseThrow());
            assertEquals(200, head.statusCode());
            assertEquals("", head.body());
            assertEquals("19", head.headers().firstValue("Content-Length").orElseThrow());
            assertEquals(List.of("GET /latest/meta-data/instance-id 127.0.0.1 null",
                    "PUT /latest/api/token 127.0.0.1 60", "HEAD /latest/meta-data/ 127.0.0.1 null"), seen);
        } finally {
            relay.destroyForcibly();
            target.stop(0);
        }
    }

    // Until the relay takes a connection on port, or has exited, within ten seconds.
    private static void awaitListening(int port, Process relay) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline && relay.isAlive()) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return;
            } catch (IOException notYet) {
                // Not listening yet: Python is starting.
                Thread.onSpinWait();
            }
        }
        throw new AssertionError("The relay did not listen: "
                + new String(relay.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static Instance instance(String id) {
        Instance instance = new Instance();
        instance.setInstanceId(id);
        return instance;
    }
}
