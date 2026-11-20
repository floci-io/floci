package io.floci.aws.services.elbv2;

import com.sun.net.httpserver.HttpServer;
import io.floci.aws.core.common.Pem;
import io.floci.aws.services.acm.AcmService;
import io.floci.aws.services.acm.CertificateGenerator;
import io.floci.aws.services.acm.model.Certificate;
import io.floci.aws.services.acm.model.KeyAlgorithm;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.RdsMockProfile;
import io.floci.aws.testing.TlsTrust;
import io.floci.aws.testutil.FreePorts;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ProtocolEnum;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.floci.aws.testing.AlbFixtures.createListener;
import static io.floci.aws.testing.AlbFixtures.createLoadBalancer;
import static io.floci.aws.testing.AlbFixtures.deleteLoadBalancer;
import static io.floci.aws.testing.AlbFixtures.deleteTargetGroup;
import static io.floci.aws.testing.AlbFixtures.registerTarget;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(RdsMockProfile.class)
class ElbV2InitialHealthIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261025/us-east-1/elasticloadbalancing/aws4_request";

    @Inject
    AcmService acmService;

    @TestHTTPResource
    URI endpoint;

    @Test
    void registeredTargetBecomesHealthyAfterOneActualHttpProbe() throws Exception {
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
                Backend backend = backend("healthy", new CountDownLatch(0), workers)) {
            String groupArn = null;
            try {
                groupArn = createTargetGroup("initial-health-wire", backend.port(), "HealthyThresholdCount", 10);
                registerTarget(groupArn, "127.0.0.1", backend.port());
                String registeredGroupArn = groupArn;
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    assertEquals(List.of("healthy"), targetHealth(registeredGroupArn, "State"));
                    assertEquals(List.of(), targetHealth(registeredGroupArn, "Reason"));
                });
                assertEquals(1, backend.probes().get(), "Initial admission must not wait for the recovery threshold");
            } finally {
                deleteTargetGroup(groupArn);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = ProtocolEnum.class, names = {"HTTP", "HTTPS"})
    void initialTargetGetsNoRequestsWhileAnotherTargetIsHealthy(ProtocolEnum protocol) throws Exception {
        CountDownLatch releaseReplacementProbe = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
                Backend serving = backend("serving", new CountDownLatch(0), workers);
                Backend replacement = backend("replacement", releaseReplacementProbe, workers)) {
            String loadBalancerArn = null;
            String groupArn = null;
            Certificate certificate = null;
            try {
                int listenerPort = FreePorts.loopbackIpv4Port();
                loadBalancerArn = createLoadBalancer("initial-health-routing");
                groupArn = createTargetGroup("initial-health-routing", serving.port(), "UnhealthyThresholdCount", 10);
                registerTarget(groupArn, "127.0.0.1", serving.port());
                String listenerArn = createListener(loadBalancerArn, groupArn, listenerPort);
                String registeredGroupArn = groupArn;
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                        assertEquals(List.of("healthy"), targetHealth(registeredGroupArn, "State")));

                HttpClient.Builder transport = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(2));
                if (protocol == ProtocolEnum.HTTPS) {
                    CertificateGenerator.GeneratedCertificate material = new CertificateGenerator()
                            .generateSelfSignedCertificate("localhost", List.of(), KeyAlgorithm.RSA_2048);
                    certificate = acmService.importCertificate(material.certificatePem(), material.privateKeyPem(),
                            null, null, Map.of(), "us-east-1");
                    String certificateArn = certificate.getArn();
                    try (ElasticLoadBalancingV2Client client = AwsTestClients.create(
                            ElasticLoadBalancingV2Client.builder(), "test", Region.US_EAST_1, endpoint)) {
                        client.modifyListener(request -> request.listenerArn(listenerArn).protocol(protocol)
                                .sslPolicy("ELBSecurityPolicy-2016-08")
                                .certificates(value -> value.certificateArn(certificateArn)));
                    }
                    X509Certificate listenerCertificate = Pem.parseCertificate(certificate.getCertificateBody());
                    transport.sslContext(TlsTrust.trusting(listenerCertificate));
                }
                URI address = URI.create(protocol.toString().toLowerCase() + "://localhost:" + listenerPort + "/");
                try (HttpClient wire = transport.build()) {
                    await().atMost(Duration.ofSeconds(5)).ignoreException(IOException.class)
                            .untilAsserted(() -> assertEquals("serving", response(wire, address)));
                    registerTarget(groupArn, "127.0.0.1", replacement.port());
                    assertTrue(replacement.probeStarted().await(5, TimeUnit.SECONDS),
                            "The replacement must be held inside its first actual HTTP health probe");
                    assertThat(targetHealth(groupArn, "State"), containsInAnyOrder("healthy", "initial"));
                    for (int request = 0; request < 10; request++) {
                        assertEquals("serving", response(wire, address),
                                "Initial replacement must not receive traffic while another target is healthy");
                    }
                    assertEquals(0, replacement.requests().get());
                    assertThat(targetHealth(groupArn, "State"), containsInAnyOrder("healthy", "initial"));
                    releaseReplacementProbe.countDown();
                    await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                            assertEquals(List.of("healthy", "healthy"), targetHealth(registeredGroupArn, "State")));
                    Set<String> admittedResponses = new HashSet<>();
                    for (int request = 0; request < 10; request++) {
                        admittedResponses.add(response(wire, address));
                    }
                    assertEquals(Set.of("serving", "replacement"), admittedResponses);
                    assertEquals(1, replacement.probes().get(), "One successful probe must admit the replacement");
                }
            } finally {
                releaseReplacementProbe.countDown();
                String ownedBalancer = loadBalancerArn;
                String ownedGroup = groupArn;
                Certificate ownedCertificate = certificate;
                assertAll("Remove every initial-health fixture resource",
                        () -> deleteLoadBalancer(ownedBalancer),
                        () -> deleteTargetGroup(ownedGroup),
                        () -> {
                            if (ownedCertificate != null) {
                                acmService.deleteCertificate(ownedCertificate.getArn(), "us-east-1");
                            }
                        });
            }
        }
    }

    private record Backend(HttpServer server, AtomicInteger probes, AtomicInteger requests,
                           CountDownLatch probeStarted, CountDownLatch releaseProbe) implements AutoCloseable {
        int port() {
            return server.getAddress().getPort();
        }

        @Override
        public void close() {
            releaseProbe.countDown();
            server.stop(0);
        }
    }

    private static Backend backend(String body, CountDownLatch releaseProbe, ExecutorService workers) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger probes = new AtomicInteger();
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch probeStarted = new CountDownLatch(1);
        server.setExecutor(workers);
        server.createContext("/health", exchange -> {
            probes.incrementAndGet();
            probeStarted.countDown();
            try {
                if (!releaseProbe.await(60, TimeUnit.SECONDS)) {
                    exchange.close();
                    return;
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                exchange.close();
                throw new IOException("Interrupted while holding the target health probe", failure);
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        return new Backend(server, probes, requests, probeStarted, releaseProbe);
    }

    private static String response(HttpClient wire, URI address) throws IOException, InterruptedException {
        HttpResponse<String> response = wire.send(HttpRequest.newBuilder(address).timeout(Duration.ofSeconds(2))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return response.body();
    }

    /** An HTTP ip target group probed every 300 s, with {@code threshold} set to {@code value}. */
    private static String createTargetGroup(String name, int port, String threshold, int value) {
        return given()
                .header("Authorization", AUTH)
                .formParam("Action", "CreateTargetGroup")
                .formParam("Name", name)
                .formParam("Protocol", "HTTP")
                .formParam("Port", port)
                .formParam("VpcId", Ec2Service.defaultVpcId("us-east-1"))
                .formParam("TargetType", "ip")
                .formParam("HealthCheckPath", "/health")
                .formParam("HealthCheckIntervalSeconds", 300)
                .formParam("HealthCheckTimeoutSeconds", 120)
                .formParam(threshold, value)
                .when().post("/")
                .then().statusCode(200)
                .extract().path("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetGroupArn");
    }

    /** One {@code TargetHealth} field, such as {@code State} or {@code Reason}, for each registered target. */
    private static List<String> targetHealth(String groupArn, String field) {
        return given()
                .header("Authorization", AUTH)
                .formParam("Action", "DescribeTargetHealth")
                .formParam("TargetGroupArn", groupArn)
                .when().post("/")
                .then().statusCode(200)
                .extract().xmlPath().getList("DescribeTargetHealthResponse.DescribeTargetHealthResult"
                        + ".TargetHealthDescriptions.member.TargetHealth." + field);
    }
}
