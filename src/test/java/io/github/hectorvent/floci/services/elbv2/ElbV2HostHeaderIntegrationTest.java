package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.testing.RealElbV2DataPlaneProfile;
import io.github.hectorvent.floci.testutil.FreePorts;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketConnectOptions;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Action;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancerAttribute;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetDescription;

import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(RealElbV2DataPlaneProfile.class)
class ElbV2HostHeaderIntegrationTest {

    private final List<Executable> cleanup = new ArrayList<>();
    private ElasticLoadBalancingV2Client elb;
    private String loadBalancerArn;
    private int listenerPort;

    @Inject
    Vertx vertx;

    @TestHTTPResource
    URI endpoint;

    @BeforeEach
    void createSdkListenerAndRealBackend() throws Exception {
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test"));
        Ec2Client ec2 = Ec2Client.builder().endpointOverride(endpoint).region(Region.EU_WEST_1)
                .credentialsProvider(credentials).build();
        cleanup.add(ec2::close);
        elb = ElasticLoadBalancingV2Client.builder().endpointOverride(endpoint).region(Region.EU_WEST_1)
                .credentialsProvider(credentials).build();
        cleanup.add(elb::close);
        String vpc = ec2.createVpc(r -> r.cidrBlock("10.84.0.0/16")).vpc().vpcId();
        cleanup.add(() -> ec2.deleteVpc(r -> r.vpcId(vpc)));
        List<String> subnets = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            String cidr = "10.84." + index + ".0/24";
            String zone = "eu-west-1" + (char) ('a' + index);
            String subnet = ec2.createSubnet(r -> r.vpcId(vpc).cidrBlock(cidr).availabilityZone(zone))
                    .subnet().subnetId();
            subnets.add(subnet);
            cleanup.add(() -> ec2.deleteSubnet(r -> r.subnetId(subnet)));
        }
        HttpServer backend = vertx.createHttpServer().requestHandler(request -> {
            String host = String.join("|", request.headers().getAll("Host"));
            if ("websocket".equalsIgnoreCase(request.getHeader("Upgrade"))) {
                request.toWebSocket().onSuccess(socket ->
                        socket.textMessageHandler(ignored -> socket.writeTextMessage(host)));
            } else {
                request.response().end(host);
            }
        }).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        cleanup.add(() -> backend.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));
        String name = "host-" + UUID.randomUUID().toString().substring(0, 8);
        loadBalancerArn = elb.createLoadBalancer(r -> r.name(name).type("application").subnets(subnets))
                .loadBalancers().getFirst().loadBalancerArn();
        cleanup.add(() -> elb.deleteLoadBalancer(r -> r.loadBalancerArn(loadBalancerArn)));
        String group = elb.createTargetGroup(r -> r.name(name).protocol("HTTP").port(backend.actualPort())
                        .vpcId(vpc).targetType("ip")).targetGroups().getFirst().targetGroupArn();
        cleanup.add(() -> elb.deleteTargetGroup(r -> r.targetGroupArn(group)));
        listenerPort = FreePorts.anyInterfacePort();
        String listener = elb.createListener(r -> r.loadBalancerArn(loadBalancerArn).protocol("HTTP")
                        .port(listenerPort).defaultActions(Action.builder().type("forward").targetGroupArn(group).build()))
                .listeners().getFirst().listenerArn();
        cleanup.add(() -> elb.deleteListener(r -> r.listenerArn(listener)));
        elb.registerTargets(r -> r.targetGroupArn(group)
                .targets(TargetDescription.builder().id("127.0.0.1").port(backend.actualPort()).build()));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            try (Socket socket = new Socket("127.0.0.1", listenerPort)) {
                assertTrue(socket.isConnected());
            }
        });
    }

    @AfterEach
    void removeOnlyThisTestsResources() {
        assertAll(cleanup.reversed());
    }

    @Test
    void defaultUsesClientHostAndAddsOnlyAMissingListenerPort() throws Exception {
        assertEquals("client.example.test:" + listenerPort, get("/headers", "client.example.test"));
        assertEquals("client.example.test:8443", get("/headers", "client.example.test:8443"));
    }

    @Test
    void defaultKeepsBracketedIpv6Authorities() throws Exception {
        assertEquals("[2001:db8::8]:" + listenerPort, get("/headers", "[2001:db8::8]"));
        assertEquals("[2001:db8::8]:8443", get("/headers", "[2001:db8::8]:8443"));
    }

    @Test
    void absoluteRequestTargetSuppliesTheAuthorityByDefault() throws Exception {
        assertEquals("absolute.example.test:" + listenerPort,
                get("http://absolute.example.test/headers", "client.example.test"));
        assertEquals("absolute.example.test:9443",
                get("http://absolute.example.test:9443/headers", "client.example.test:8443"));
    }

    @Test
    void preservationKeepsClientHeadersInsteadOfTheAbsoluteAuthority() throws Exception {
        preserve();
        assertEquals("client.example.test:8443",
                get("http://absolute.example.test/headers", "client.example.test:8443"));
        assertEquals("client.example.test|second.example.test:8443",
                get("/headers", "client.example.test", "second.example.test:8443"));
    }

    @Test
    void webSocketHandshakeUsesTheSameDefaultAndPreservationRules() throws Exception {
        assertEquals("client.example.test:" + listenerPort, webSocketHost());
        preserve();
        assertEquals("client.example.test", webSocketHost());
    }

    private void preserve() {
        elb.modifyLoadBalancerAttributes(r -> r.loadBalancerArn(loadBalancerArn).attributes(
                LoadBalancerAttribute.builder().key("routing.http.preserve_host_header.enabled").value("true").build()));
    }

    private String get(String target, String... hosts) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", listenerPort)) {
            socket.setSoTimeout(5_000);
            StringBuilder request = new StringBuilder("GET ").append(target).append(" HTTP/1.1\r\n");
            for (String host : hosts) {
                request.append("Host: ").append(host).append("\r\n");
            }
            request.append("Connection: close\r\n\r\n");
            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            assertTrue(response.startsWith("HTTP/1.1 200 "), response);
            return response.substring(response.indexOf("\r\n\r\n") + 4);
        }
    }

    private String webSocketHost() throws Exception {
        HttpClient client = vertx.createHttpClient();
        try {
            WebSocket socket = client.webSocket(new WebSocketConnectOptions()
                            .setHost("127.0.0.1").setPort(listenerPort).setURI("/ws")
                            .setHeaders(MultiMap.caseInsensitiveMultiMap().set("Host", "client.example.test")))
                    .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            try {
                CompletableFuture<String> received = new CompletableFuture<>();
                socket.textMessageHandler(received::complete);
                socket.writeTextMessage("report-host").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
                return received.get(5, TimeUnit.SECONDS);
            } finally {
                socket.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        } finally {
            client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }
}
