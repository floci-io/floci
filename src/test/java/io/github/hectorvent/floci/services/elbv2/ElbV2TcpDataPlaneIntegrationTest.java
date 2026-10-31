package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.core.common.Pem;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.testing.RealElbV2DataPlaneProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.PemKeyCertOptions;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Action;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ActionTypeEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.FixedResponseActionConfig;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Listener;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ListenerAttribute;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancer;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancerTypeEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ProtocolEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetDescription;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetHealthStateEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetTypeEnum;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

import java.io.IOException;
import java.net.BindException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(RealElbV2DataPlaneProfile.class)
class ElbV2TcpDataPlaneIntegrationTest {

    @Inject
    Vertx vertx;

    @TestHTTPResource
    URI endpoint;

    private ElasticLoadBalancingV2Client client;
    private NetServer backend;
    private HttpServer healthBackend;
    private String loadBalancerArn;
    private String targetGroupArn;
    private String listenerArn;
    private int listenerPort;

    @BeforeEach
    void createTcpListenerWithHealthyTarget() throws Exception {
        backend = vertx.createNetServer()
                .connectHandler(socket -> socket.handler(socket::write))
                .listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        healthBackend = vertx.createHttpServer()
                .requestHandler(request -> request.response().end("healthy"))
                .listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        client = ElasticLoadBalancingV2Client.builder()
                .endpointOverride(endpoint)
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();

        loadBalancerArn = client.createLoadBalancer(request -> request
                .name("tcp-regression-nlb")
                .type(LoadBalancerTypeEnum.NETWORK)
                .subnets(Ec2Service.defaultSubnetId("us-east-1", "a")))
                .loadBalancers().getFirst().loadBalancerArn();
        targetGroupArn = client.createTargetGroup(request -> request
                .name("tcp-regression-tg")
                .protocol(ProtocolEnum.TCP)
                .port(backend.actualPort())
                .vpcId(Ec2Service.defaultVpcId("us-east-1"))
                .targetType(TargetTypeEnum.IP)
                .healthCheckProtocol(ProtocolEnum.HTTP)
                .healthCheckPort(Integer.toString(healthBackend.actualPort()))
                .healthCheckIntervalSeconds(5)
                .healthyThresholdCount(2))
                .targetGroups().getFirst().targetGroupArn();
        client.registerTargets(request -> request.targetGroupArn(targetGroupArn)
                .targets(TargetDescription.builder().id("127.0.0.1").port(backend.actualPort()).build()));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertEquals(
                TargetHealthStateEnum.HEALTHY,
                client.describeTargetHealth(request -> request.targetGroupArn(targetGroupArn))
                        .targetHealthDescriptions().getFirst().targetHealth().state()));

        try (ServerSocket reservation = new ServerSocket(0)) {
            listenerPort = reservation.getLocalPort();
        }
        listenerArn = client.createListener(request -> request
                .loadBalancerArn(loadBalancerArn)
                .protocol(ProtocolEnum.TCP)
                .port(listenerPort)
                .defaultActions(Action.builder().type(ActionTypeEnum.FORWARD)
                        .targetGroupArn(targetGroupArn).build()))
                .listeners().getFirst().listenerArn();
        await().atMost(Duration.ofSeconds(5)).ignoreException(ConnectException.class).untilAsserted(() -> {
            try (Socket socket = connect(listenerPort)) {
                assertEquals(listenerPort, socket.getPort());
            }
        });
    }

    @AfterEach
    void cleanup() {
        assertAll("Clean up every TCP fixture resource even if another cleanup fails",
                () -> {
                    if (client != null && listenerArn != null) {
                        client.deleteListener(request -> request.listenerArn(listenerArn));
                    }
                },
                () -> {
                    if (client != null && loadBalancerArn != null) {
                        client.deleteLoadBalancer(request -> request.loadBalancerArn(loadBalancerArn));
                    }
                },
                () -> {
                    if (client != null && targetGroupArn != null) {
                        client.deleteTargetGroup(request -> request.targetGroupArn(targetGroupArn));
                    }
                },
                () -> {
                    if (backend != null) {
                        backend.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
                    }
                },
                () -> {
                    if (healthBackend != null) {
                        healthBackend.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
                    }
                },
                () -> {
                    if (client != null) {
                        client.close();
                    }
                });
    }

    @Test
    void tcpListenerRelaysBinaryBytesWithoutInterpretingHttp() throws Exception {
        // PostgreSQL SSLRequest: valid TCP payload, not an HTTP request.
        byte[] payload = {0, 0, 0, 8, 4, (byte) 0xd2, 0x16, 0x2f};
        try (Socket direct = connect(backend.actualPort())) {
            assertEcho(direct, payload);
        }
        try (Socket socket = connect(listenerPort)) {
            assertEcho(socket, payload);
            assertEcho(socket, new byte[]{(byte) 0xff, 0, 1, 2, 13, 10, (byte) 0x80, 42});
            byte[] larger = new byte[64 * 1024];
            for (int index = 0; index < larger.length; index++) {
                larger[index] = (byte) index;
            }
            assertEcho(socket, larger);
        }
    }

    @Test
    void tcpListenerPassesTlsThroughToTarget() throws Exception {
        CertificateGenerator.GeneratedCertificate certificate = new CertificateGenerator()
                .generateSelfSignedCertificate("localhost", List.of(), KeyAlgorithm.RSA_2048);
        replaceBackend(new NetServerOptions().setSsl(true).setPemKeyCertOptions(new PemKeyCertOptions()
                        .setKeyValue(Buffer.buffer(certificate.privateKeyPem()))
                        .setCertValue(Buffer.buffer(certificate.certificatePem()))),
                socket -> socket.handler(socket::write));
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("target", Pem.parseCertificate(certificate.certificatePem()));
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(trustStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", listenerPort), 2000);
            socket.setSoTimeout(5000);
            socket.startHandshake();
            assertEquals(Pem.parseCertificate(certificate.certificatePem()),
                    socket.getSession().getPeerCertificates()[0]);
            assertEcho(socket, new byte[]{0, 1, (byte) 0xff, 13, 10});
        }
    }

    @Test
    void tcpListenerRelaysServerGreetingBeforeClientSendsBytes() throws Exception {
        byte[] greeting = {0, 5, 42, (byte) 0xff};
        replaceBackend(new NetServerOptions(), socket -> {
            socket.write(Buffer.buffer(greeting));
            socket.handler(socket::write);
        });
        try (Socket socket = connect(listenerPort)) {
            assertArrayEquals(greeting, socket.getInputStream().readNBytes(greeting.length));
            assertEcho(socket, new byte[]{1, 2, 3});
        }
    }

    @Test
    void tcpListenerClosesConnectionWhenNoTargetsAreRegistered() throws Exception {
        client.deregisterTargets(request -> request.targetGroupArn(targetGroupArn)
                .targets(TargetDescription.builder().id("127.0.0.1").port(backend.actualPort()).build()));
        try (Socket socket = connect(listenerPort)) {
            assertEquals(-1, socket.getInputStream().read());
        }
    }

    @Test
    void tcpListenerClosesConnectionWhenTargetRefusesConnection() throws Exception {
        backend.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        backend = null;
        try (Socket socket = connect(listenerPort)) {
            assertEquals(-1, socket.getInputStream().read());
        }
    }

    @Test
    void modifyingTcpListenerPortReleasesOldPortAndServesNewPort() throws Exception {
        int oldPort = listenerPort;
        try (ServerSocket reservation = new ServerSocket(0)) {
            listenerPort = reservation.getLocalPort();
        }
        client.modifyListener(request -> request.listenerArn(listenerArn).port(listenerPort));
        await().atMost(Duration.ofSeconds(5)).ignoreException(ConnectException.class).untilAsserted(() -> {
            try (Socket socket = connect(listenerPort)) {
                assertEcho(socket, new byte[]{0, 7, (byte) 0xff});
            }
        });
        assertPortReleased(oldPort);
    }

    @Test
    void deletingTcpListenerReleasesItsPort() {
        client.deleteListener(request -> request.listenerArn(listenerArn));
        listenerArn = null;
        assertPortReleased(listenerPort);
    }

    @ParameterizedTest
    @EnumSource(value = ProtocolEnum.class, names = {"TCP", "HTTP"})
    void secondListenerOnTcpPortRegistersWithoutTakingThePort(ProtocolEnum protocol) throws Exception {
        String otherLbArn = client.createLoadBalancer(request -> request.name("tcp-regression-other")
                .type(protocol == ProtocolEnum.TCP ? LoadBalancerTypeEnum.NETWORK : LoadBalancerTypeEnum.APPLICATION)
                .subnets(Ec2Service.defaultSubnetId("us-east-1", "a"),
                        Ec2Service.defaultSubnetId("us-east-1", "b")))
                .loadBalancers().getFirst().loadBalancerArn();
        try {
            Action action = protocol == ProtocolEnum.TCP
                    ? Action.builder().type(ActionTypeEnum.FORWARD).targetGroupArn(targetGroupArn).build()
                    : Action.builder().type(ActionTypeEnum.FIXED_RESPONSE).fixedResponseConfig(
                            FixedResponseActionConfig.builder().statusCode("200").contentType("text/plain").build()).build();
            Listener created = client.createListener(request -> request.loadBalancerArn(otherLbArn)
                    .protocol(protocol).port(listenerPort).defaultActions(action))
                    .listeners().getFirst();
            assertEquals(otherLbArn, created.loadBalancerArn());
            List<Listener> described = client.describeListeners(request -> request.loadBalancerArn(otherLbArn)).listeners();
            assertEquals(1, described.size());
            assertEquals(created.listenerArn(), described.getFirst().listenerArn());
            try (Socket socket = connect(listenerPort)) {
                assertEcho(socket, new byte[]{0, 8, 13, 10});
            }
        } finally {
            client.deleteLoadBalancer(request -> request.loadBalancerArn(otherLbArn));
        }
    }

    @Test
    void parkedListenerServesThePortOnceTheTcpListenerIsDeleted() throws Exception {
        LoadBalancer other = client.createLoadBalancer(
                request -> request.name("tcp-regression-parked")
                        .type(LoadBalancerTypeEnum.APPLICATION)
                        .subnets(Ec2Service.defaultSubnetId("us-east-1", "a"),
                                Ec2Service.defaultSubnetId("us-east-1", "b")))
                .loadBalancers().getFirst();
        try {
            client.createListener(request -> request.loadBalancerArn(other.loadBalancerArn())
                    .protocol(ProtocolEnum.HTTP).port(listenerPort)
                    .defaultActions(Action.builder().type(ActionTypeEnum.FIXED_RESPONSE).fixedResponseConfig(
                            FixedResponseActionConfig.builder().statusCode("200").contentType("text/plain")
                                    .messageBody("parked").build()).build()));
            try (Socket socket = connect(listenerPort)) {
                assertEcho(socket, new byte[]{0, 9, 13, 10});
            }

            client.deleteListener(request -> request.listenerArn(listenerArn));
            listenerArn = null;

            await().atMost(Duration.ofSeconds(5)).ignoreExceptions().untilAsserted(() -> {
                try (Socket socket = connect(listenerPort)) {
                    socket.setSoTimeout(2000);
                    socket.getOutputStream().write(("GET / HTTP/1.1\r\nHost: " + other.dnsName()
                            + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                    assertTrue(response.startsWith("HTTP/1.1 200"), response);
                    assertTrue(response.endsWith("parked"), response);
                }
            });
        } finally {
            client.deleteLoadBalancer(request -> request.loadBalancerArn(other.loadBalancerArn()));
        }
    }

    @Test
    void tcpConnectionsUseRoundRobinAndKeepTheirSelectedTarget() throws Exception {
        NetServer second = vertx.createNetServer().connectHandler(socket -> socket.handler(buffer -> {
            byte[] bytes = buffer.getBytes();
            for (int index = 0; index < bytes.length; index++) {
                bytes[index]++;
            }
            socket.write(Buffer.buffer(bytes));
        })).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        try {
            client.registerTargets(request -> request.targetGroupArn(targetGroupArn).targets(TargetDescription.builder()
                    .id("127.0.0.1").port(second.actualPort()).build()));
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                List<TargetHealthStateEnum> states = client.describeTargetHealth(request -> request
                                .targetGroupArn(targetGroupArn)).targetHealthDescriptions().stream()
                        .map(description -> description.targetHealth().state()).toList();
                assertEquals(List.of(TargetHealthStateEnum.HEALTHY, TargetHealthStateEnum.HEALTHY), states);
            });
            Set<Integer> replies = new HashSet<>();
            for (int index = 0; index < 2; index++) {
                try (Socket socket = connect(listenerPort)) {
                    socket.getOutputStream().write(42);
                    int reply = socket.getInputStream().read();
                    replies.add(reply);
                    socket.getOutputStream().write(42);
                    assertEquals(reply, socket.getInputStream().read(), "A connection must keep its selected target");
                }
            }
            assertEquals(Set.of(42, 43), replies);
        } finally {
            assertAll("Deregister and close the second TCP target",
                    () -> client.deregisterTargets(request -> request.targetGroupArn(targetGroupArn)
                            .targets(TargetDescription.builder().id("127.0.0.1").port(second.actualPort()).build())),
                    () -> second.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void updatingForwardActionOnlyChangesNewTcpConnections() throws Exception {
        String emptyTgArn = client.createTargetGroup(request -> request.name("tcp-regression-empty")
                .protocol(ProtocolEnum.TCP).port(12345).vpcId(Ec2Service.defaultVpcId("us-east-1"))
                .targetType(TargetTypeEnum.IP)).targetGroups().getFirst().targetGroupArn();
        try {
            try (Socket established = connect(listenerPort)) {
                assertEcho(established, new byte[]{0, 1, 2});
                client.modifyListener(request -> request.listenerArn(listenerArn).defaultActions(Action.builder()
                        .type(ActionTypeEnum.FORWARD).targetGroupArn(emptyTgArn).build()));
                try (Socket next = connect(listenerPort)) {
                    assertEquals(-1, next.getInputStream().read());
                }
                assertEcho(established, new byte[]{3, 4, 5});
            }
        } finally {
            assertAll("Restore the listener and delete the temporary target group",
                    () -> client.modifyListener(request -> request.listenerArn(listenerArn).defaultActions(Action.builder()
                            .type(ActionTypeEnum.FORWARD).targetGroupArn(targetGroupArn).build())),
                    () -> client.deleteTargetGroup(request -> request.targetGroupArn(emptyTgArn)));
        }
    }

    @Test
    void idleTimeoutClosesTcpConnectionAfterConfiguredDuration() throws Exception {
        client.modifyListenerAttributes(request -> request.listenerArn(listenerArn)
                .attributes(ListenerAttribute.builder().key("tcp.idle_timeout.seconds").value("1").build()));
        try {
            try (Socket socket = connect(listenerPort)) {
                assertEcho(socket, new byte[]{1, 2, 3});
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    assertEquals(-1, socket.getInputStream().read(),
                            "Connection must be closed after idle timeout");
                });
            }
        } finally {
            client.modifyListenerAttributes(request -> request.listenerArn(listenerArn)
                    .attributes(ListenerAttribute.builder().key("tcp.idle_timeout.seconds").value("350").build()));
        }
    }

    private void replaceBackend(NetServerOptions options, Handler<NetSocket> handler) throws Exception {
        int port = backend.actualPort();
        backend.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        backend = vertx.createNetServer(options).connectHandler(handler).listen(port, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static Socket connect(int port) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2000);
            socket.setSoTimeout(3000);
            return socket;
        } catch (IOException failure) {
            try {
                socket.close();
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static void assertPortReleased(int port) {
        await().atMost(Duration.ofSeconds(5)).ignoreException(BindException.class).untilAsserted(() -> {
            assertThrows(ConnectException.class, () -> {
                try (Socket socket = connect(port)) {
                    assertEquals(port, socket.getPort());
                }
            });
            try (ServerSocket reservation = new ServerSocket()) {
                reservation.setReuseAddress(true);
                reservation.bind(new InetSocketAddress("0.0.0.0", port));
                assertEquals(port, reservation.getLocalPort());
            }
        });
    }

    private static void assertEcho(Socket socket, byte[] payload) throws IOException {
        socket.getOutputStream().write(payload);
        socket.getOutputStream().flush();
        assertArrayEquals(payload, socket.getInputStream().readNBytes(payload.length),
                "TCP traffic must reach the echo target unchanged, without an HTTP response");
    }
}
