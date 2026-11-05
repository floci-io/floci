package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.RealElbV2DataPlaneProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.UpgradeRejectedException;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.PemKeyCertOptions;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Action;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancer;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Matcher;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetDescription;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetHealthDescription;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(RealElbV2DataPlaneProfile.class)
class ElbV2BackendTlsIntegrationTest {

    private final String name = "alb-tls-" + UUID.randomUUID().toString().substring(0, 8);
    private final List<Executable> cleanup = new ArrayList<>();
    private final AtomicInteger healthStatus = new AtomicInteger(200);
    private final AtomicBoolean encrypted = new AtomicBoolean();
    private final AtomicReference<String> received = new AtomicReference<>();
    private Ec2Client ec2;
    private ElasticLoadBalancingV2Client elb;
    private String vpc;
    private List<String> subnets;
    private String groupArn;
    private String dns;
    private int listenerPort;

    @Inject
    Vertx vertx;

    @Inject
    ElbV2HealthChecker healthChecker;

    @Inject
    ElbV2Service elbService;

    @TestHTTPResource("/")
    URI endpoint;

    @BeforeEach
    void createSdkNetwork() {
        ec2 = AwsTestClients.create(Ec2Client.builder(), "test", Region.EU_WEST_1, endpoint);
        cleanup.add(ec2::close);
        elb = AwsTestClients.create(ElasticLoadBalancingV2Client.builder(), "test", Region.EU_WEST_1, endpoint);
        cleanup.add(elb::close);
        vpc = ec2.createVpc(r -> r.cidrBlock("10.83.0.0/16")).vpc().vpcId();
        cleanup.add(() -> ec2.deleteVpc(r -> r.vpcId(vpc)));
        subnets = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            String cidr = "10.83." + index + ".0/24";
            String zone = "eu-west-1" + (char) ('a' + index);
            String subnet = ec2.createSubnet(r -> r.vpcId(vpc).cidrBlock(cidr)
                    .availabilityZone(zone)).subnet().subnetId();
            subnets.add(subnet);
            cleanup.add(() -> ec2.deleteSubnet(r -> r.subnetId(subnet)));
        }
    }

    @AfterEach
    void removeOnlyThisTestsResources() {
        assertAll(cleanup.reversed());
    }

    @Test
    void httpsTargetUsesTlsEvenWhenItsHealthCheckUsesHttp() throws Exception {
        HttpServer application = backend(true);
        HttpServer health = backend(false);
        createTarget("HTTPS", application.actualPort(), "HTTP", health.actualPort(), "200");
        awaitHealth("healthy", null);

        given().baseUri("http://127.0.0.1").port(listenerPort).urlEncodingEnabled(false)
                .header("Host", dns).header("Authorization", "native-client-signature")
                .contentType("application/octet-stream").body("native request".getBytes(StandardCharsets.UTF_8))
                .post("/packages/prepare?version=3%2E0").then().statusCode(200).body(equalTo("native request"));

        assertTrue(encrypted.get());
        assertEquals("POST /packages/prepare?version=3%2E0 native-client-signature", received.get());
    }

    @Test
    void httpTargetCanUseAnIndependentHttpsHealthEndpoint() throws Exception {
        HttpServer application = backend(false);
        HttpServer health = backend(true);
        createTarget("HTTP", application.actualPort(), "HTTPS", health.actualPort(), "200");
        awaitHealth("healthy", null);
        given().baseUri("http://127.0.0.1").port(listenerPort).header("Host", dns)
                .body("plain target".getBytes(StandardCharsets.UTF_8)).post("/echo")
                .then().statusCode(200).body(equalTo("plain target"));
        assertFalse(encrypted.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void healthProbeConnectsDirectlyDespiteJvmProxyConfiguration(boolean tls) throws Exception {
        HttpServer health = backend(tls);
        AtomicInteger proxied = new AtomicInteger();
        HttpServer proxy = vertx.createHttpServer().requestHandler(request -> {
            proxied.incrementAndGet();
            request.response().setStatusCode(503).end();
        }).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        cleanup.add(() -> proxy.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));
        ProxySelector previous = ProxySelector.getDefault();
        ProxySelector.setDefault(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                if (uri.getPort() == health.actualPort()) {
                    return List.of(new Proxy(Proxy.Type.HTTP,
                            new InetSocketAddress("127.0.0.1", proxy.actualPort())));
                }
                return previous == null ? List.of(Proxy.NO_PROXY) : previous.select(uri);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException failure) {
                fail("Health checks must connect directly to the target: " + uri, failure);
            }
        });
        try {
            createTarget("HTTP", health.actualPort(), tls ? "HTTPS" : "HTTP", health.actualPort(), "200");
            await().atMost(Duration.ofSeconds(5)).until(() -> proxied.get() > 0
                    || "healthy".equals(elb.describeTargetHealth(r -> r.targetGroupArn(groupArn))
                            .targetHealthDescriptions().getFirst().targetHealth().stateAsString()));
            assertEquals(0, proxied.get(), "The JVM proxy must not receive target health probes");
            awaitHealth("healthy", null);
        } finally {
            ProxySelector.setDefault(previous);
        }
    }

    @Test
    void httpsHealthRejectsAReachablePlaintextServer() throws Exception {
        HttpServer plain = backend(false);
        createTarget("HTTP", plain.actualPort(), "HTTPS", plain.actualPort(), "200");
        awaitHealth("unhealthy", "Target.FailedHealthChecks");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void httpsTargetNeverFallsBackToPlaintext(boolean websocket) throws Exception {
        HttpServer plain = backend(false);
        createTarget("HTTPS", plain.actualPort(), "HTTP", plain.actualPort(), "200");
        awaitHealth("healthy", null);

        assertForwardingFailure(websocket, 502);
        assertNull(received.get(), "The plaintext application must not receive the request");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unansweredBackendTlsHandshakeReturnsGatewayTimeout(boolean websocket) throws Exception {
        CompletableFuture<Duration> disconnected = new CompletableFuture<>();
        AtomicBoolean clientHello = new AtomicBoolean();
        NetServer stalled = vertx.createNetServer().connectHandler(socket -> {
            long accepted = System.nanoTime();
            socket.handler(buffer -> {
                if (buffer.length() > 0 && buffer.getUnsignedByte(0) == 22) {
                    clientHello.set(true);
                }
            });
            socket.closeHandler(ignored ->
                    disconnected.complete(Duration.ofNanos(System.nanoTime() - accepted)));
        }).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        cleanup.add(() -> stalled.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));
        HttpServer health = backend(false);
        createTarget("HTTPS", stalled.actualPort(), "HTTP", health.actualPort(), "200");
        awaitHealth("healthy", null);

        assertForwardingFailure(websocket, 504);
        assertTrue(clientHello.get(), "The backend must receive the real TLS ClientHello");
        Duration elapsed = disconnected.get(5, TimeUnit.SECONDS);
        assertTrue(elapsed.compareTo(Duration.ofSeconds(9)) >= 0
                        && elapsed.compareTo(Duration.ofSeconds(15)) < 0,
                "An ALB backend TLS handshake has a ten-second deadline: " + elapsed);
        assertNull(received.get(), "The native wait must end before an application request is sent");
    }

    @Test
    void httpsHealthPreservesCustomMatchersAndRecovers() throws Exception {
        HttpServer tls = backend(true);
        healthStatus.set(302);
        createTarget("HTTPS", tls.actualPort(), "HTTPS", tls.actualPort(), "302");
        awaitHealth("healthy", null, true);

        healthStatus.set(503);
        awaitHealth("unhealthy", "Target.ResponseCodeMismatch", true);

        elb.modifyTargetGroup(r -> r.targetGroupArn(groupArn)
                .matcher(Matcher.builder().httpCode("200-299").build()));
        healthStatus.set(204);
        awaitHealth("healthy", null, true);
    }

    @Test
    void changingHealthProtocolAndPortReplacesTheProbeSettings() throws Exception {
        HttpServer plain = backend(false);
        HttpServer tls = backend(true);
        createTarget("HTTP", plain.actualPort(), "HTTP", plain.actualPort(), "200");
        awaitHealth("healthy", null, true);

        elb.modifyTargetGroup(r -> r.targetGroupArn(groupArn).healthCheckProtocol("HTTPS"));
        awaitHealth("unhealthy", "Target.FailedHealthChecks", true);

        elb.modifyTargetGroup(r -> r.targetGroupArn(groupArn).healthCheckPort(String.valueOf(tls.actualPort())));
        awaitHealth("healthy", null, true);
    }

    @Test
    void anUnansweredTlsHandshakeExpiresAtTheConfiguredHealthTimeout() throws Exception {
        CompletableFuture<Duration> handshakeWait = new CompletableFuture<>();
        AtomicBoolean clientHelloReceived = new AtomicBoolean();
        NetServer stalled = vertx.createNetServer().connectHandler(socket -> {
            long accepted = System.nanoTime();
            socket.handler(buffer -> {
                if (buffer.length() > 0 && buffer.getUnsignedByte(0) == 22) {
                    clientHelloReceived.set(true);
                }
            });
            socket.closeHandler(ignored -> handshakeWait.complete(Duration.ofNanos(System.nanoTime() - accepted)));
        }).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        cleanup.add(() -> stalled.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));
        HttpServer application = backend(false);
        createTarget("HTTP", application.actualPort(), "HTTPS", stalled.actualPort(), "200");

        awaitHealth("unhealthy", "Target.Timeout");
        assertTrue(clientHelloReceived.get(), "The probe must start a real TLS handshake");
        Duration elapsed = handshakeWait.get(5, TimeUnit.SECONDS);
        assertTrue(elapsed.compareTo(Duration.ofMillis(1500)) >= 0
                && elapsed.compareTo(Duration.ofSeconds(4)) < 0,
                "The configured two-second timeout must bound the handshake: " + elapsed);
    }

    @Test
    void websocketUpgradeUsesTheTargetsHttpsProtocol() throws Exception {
        HttpServer application = backend(true);
        HttpServer health = backend(false);
        createTarget("HTTPS", application.actualPort(), "HTTP", health.actualPort(), "200");
        awaitHealth("healthy", null);
        HttpClient client = vertx.createHttpClient();
        cleanup.add(() -> client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));
        WebSocket socket = client.webSocket(new WebSocketConnectOptions().setHost("127.0.0.1")
                        .setPort(listenerPort).setURI("/socket.io/?transport=websocket").addHeader("Host", dns))
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        cleanup.add(() -> socket.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));
        CompletableFuture<String> reply = new CompletableFuture<>();
        socket.textMessageHandler(reply::complete);
        socket.writeTextMessage("native-frame").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals("echo:native-frame", reply.get(5, TimeUnit.SECONDS));
        assertTrue(encrypted.get(), "The WebSocket handshake must reach the backend over TLS");
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP", "HTTPS"})
    void slowResponseBytesCannotKeepAnUnhealthyTargetHealthy(String protocol) throws Exception {
        HttpServer healthy = backend("HTTPS".equals(protocol));
        createTarget(protocol, healthy.actualPort(), protocol, healthy.actualPort(), "200");
        awaitHealth("healthy", null);
        NetServer slow = slowPeer("HTTPS".equals(protocol), false);
        elb.modifyTargetGroup(r -> r.targetGroupArn(groupArn).healthCheckPort(String.valueOf(slow.actualPort())));

        awaitHealth("unhealthy", "Target.Timeout");
    }

    @Test
    void slowTlsRecordsCannotKeepAnUnhealthyTargetHealthy() throws Exception {
        HttpServer healthy = backend(false);
        createTarget("HTTP", healthy.actualPort(), "HTTP", healthy.actualPort(), "200");
        awaitHealth("healthy", null);
        NetServer slow = slowPeer(false, true);
        elb.modifyTargetGroup(r -> r.targetGroupArn(groupArn).healthCheckProtocol("HTTPS")
                .healthCheckPort(String.valueOf(slow.actualPort())));

        awaitHealth("unhealthy", "Target.Timeout");
    }

    private NetServer slowPeer(boolean tls, boolean handshake) throws Exception {
        NetServerOptions options = new NetServerOptions();
        if (tls) {
            CertificateGenerator.GeneratedCertificate certificate = new CertificateGenerator()
                    .generateSelfSignedCertificate("backend.example.test", List.of(), KeyAlgorithm.RSA_2048);
            options.setSsl(true).setPemKeyCertOptions(new PemKeyCertOptions()
                    .setCertValue(Buffer.buffer(certificate.certificatePem()))
                    .setKeyValue(Buffer.buffer(certificate.privateKeyPem())));
        }
        List<NetSocket> connections = new CopyOnWriteArrayList<>();
        List<Long> timers = new CopyOnWriteArrayList<>();
        List<Duration> closed = new CopyOnWriteArrayList<>();
        NetServer server = vertx.createNetServer(options).connectHandler(socket -> {
            connections.add(socket);
            long accepted = System.nanoTime();
            socket.handler(ignored -> {});
            socket.write(handshake ? Buffer.buffer(new byte[]{22, 3, 3, 0, 100})
                    : Buffer.buffer("HTTP/1.1 200 OK\r\nX-Incomplete: "));
            long timer = vertx.setPeriodic(250, ignored -> socket.write(Buffer.buffer(new byte[]{0})));
            timers.add(timer);
            socket.closeHandler(ignored -> {
                vertx.cancelTimer(timer);
                closed.add(Duration.ofNanos(System.nanoTime() - accepted));
            });
        }).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        cleanup.add(() -> {
            timers.forEach(vertx::cancelTimer);
            for (NetSocket socket : connections) {
                socket.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
            server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        });
        cleanup.add(() -> {
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertTrue(closed.size() >= 2,
                    "Both timed-out probe connections must be closed without fixture cleanup"));
            assertTrue(closed.stream().allMatch(wait -> wait.compareTo(Duration.ofSeconds(4)) < 0),
                    "Peer connections exceeded the configured health deadline: " + closed);
        });
        return server;
    }

    private void assertForwardingFailure(boolean websocket, int status) throws Exception {
        if (!websocket) {
            given().baseUri("http://127.0.0.1").port(listenerPort).header("Host", dns)
                    .body("must remain encrypted").post("/should-not-arrive")
                    .then().statusCode(status);
            return;
        }
        HttpClient client = vertx.createHttpClient();
        cleanup.add(() -> client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));
        ExecutionException failure = assertThrows(ExecutionException.class, () ->
                client.webSocket(new WebSocketConnectOptions().setHost("127.0.0.1")
                                .setPort(listenerPort).setURI("/socket.io/").addHeader("Host", dns))
                        .toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS));
        assertEquals(status, assertInstanceOf(UpgradeRejectedException.class, failure.getCause()).getStatus());
    }

    private HttpServer backend(boolean tls) throws Exception {
        HttpServerOptions options = new HttpServerOptions();
        if (tls) {
            CertificateGenerator.GeneratedCertificate certificate = new CertificateGenerator()
                    .generateSelfSignedCertificate("backend.example.test", List.of(), KeyAlgorithm.RSA_2048);
            options.setSsl(true).setPemKeyCertOptions(new PemKeyCertOptions()
                    .setCertValue(Buffer.buffer(certificate.certificatePem()))
                    .setKeyValue(Buffer.buffer(certificate.privateKeyPem())));
        }
        HttpServer backend = vertx.createHttpServer(options).requestHandler(request -> {
            if ("/health".equals(request.path())) {
                request.response().setStatusCode(healthStatus.get()).putHeader("Location", "/healthy").end();
                return;
            }
            encrypted.set(request.isSSL());
            received.set(request.method() + " " + request.uri() + " " + request.getHeader("Authorization"));
            if ("websocket".equalsIgnoreCase(request.getHeader("Upgrade"))) {
                request.toWebSocket().onSuccess(socket -> socket.textMessageHandler(
                        message -> socket.writeTextMessage("echo:" + message)));
            } else {
                request.body().onSuccess(body -> request.response().end(body));
            }
        }).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        cleanup.add(() -> backend.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));
        return backend;
    }

    private void createTarget(String protocol, int port, String healthProtocol, int healthPort, String matcher)
            throws Exception {
        LoadBalancer loadBalancer = elb.createLoadBalancer(r -> r.name(name).type("application")
                .subnets(subnets)).loadBalancers().getFirst();
        String arn = loadBalancer.loadBalancerArn();
        dns = loadBalancer.dnsName();
        cleanup.add(() -> elb.deleteLoadBalancer(r -> r.loadBalancerArn(arn)));
        groupArn = elb.createTargetGroup(r -> r.name(name).protocol(protocol).port(port).vpcId(vpc)
                        .targetType("ip").healthCheckProtocol(healthProtocol).healthCheckPort(String.valueOf(healthPort))
                        .healthCheckPath("/health").healthCheckIntervalSeconds(5).healthCheckTimeoutSeconds(2)
                        .healthyThresholdCount(2).unhealthyThresholdCount(2)
                        .matcher(Matcher.builder().httpCode(matcher).build()))
                .targetGroups().getFirst().targetGroupArn();
        cleanup.add(() -> elb.deleteTargetGroup(r -> r.targetGroupArn(groupArn)));
        try (ServerSocket reservation = new ServerSocket(0)) {
            listenerPort = reservation.getLocalPort();
        }
        String listener = elb.createListener(r -> r.loadBalancerArn(arn).protocol("HTTP").port(listenerPort)
                        .defaultActions(Action.builder().type("forward").targetGroupArn(groupArn).build()))
                .listeners().getFirst().listenerArn();
        cleanup.add(() -> elb.deleteListener(r -> r.listenerArn(listener)));
        elb.registerTargets(r -> r.targetGroupArn(groupArn)
                .targets(TargetDescription.builder().id("127.0.0.1").port(port).build()));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", listenerPort), 1000);
            }
        });
    }

    private void awaitHealth(String state, String reason) {
        awaitHealth(state, reason, false);
    }

    private void awaitHealth(String state, String reason, boolean triggerProbe) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            if (triggerProbe) {
                healthChecker.probeAll(groupArn, elbService.getTargetGroup(Region.EU_WEST_1.id(), groupArn));
            }
            List<TargetHealthDescription> targets = elb.describeTargetHealth(r -> r.targetGroupArn(groupArn))
                    .targetHealthDescriptions();
            assertEquals(1, targets.size());
            assertEquals(state, targets.getFirst().targetHealth().stateAsString());
            assertEquals(reason, targets.getFirst().targetHealth().reasonAsString());
        });
    }
}
