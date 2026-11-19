package io.floci.aws.services.elbv2;

import com.sun.net.httpserver.HttpExchange;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Action;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ActionTypeEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.CreateListenerRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancerTypeEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ProtocolEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetDescription;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetHealthStateEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetTypeEnum;

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

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(RdsMockProfile.class)
class ElbV2MixedTargetAdmissionIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String OLD_DOCUMENT = """
            {
              "hits": {
                "hits": [
                  { "_id": "old" }
                ]
              }
            }
            """;
    private static final String REPLACEMENT_DOCUMENT = """
            {
              "hits": {
                "hits": [
                  { "_id": "replacement" }
                ]
              }
            }
            """;

    @Inject
    AcmService acmService;

    @TestHTTPResource
    URI endpoint;

    @ParameterizedTest
    @EnumSource(value = ProtocolEnum.class, names = {"HTTP", "HTTPS"})
    void documentRequestsUseOnlyAdmittedTargetsUntilTheReplacementProbeCompletes(ProtocolEnum protocol)
            throws Exception {
        CountDownLatch replacementProbeStarted = new CountDownLatch(1);
        CountDownLatch releaseReplacementProbe = new CountDownLatch(1);
        AtomicInteger replacementRequests = new AtomicInteger();
        String balancer = null;
        String group = null;
        Certificate certificate = null;
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
                Backend oldBackend = backend(OLD_DOCUMENT, null, null, new AtomicInteger(), workers);
                Backend replacement = backend(REPLACEMENT_DOCUMENT, replacementProbeStarted,
                        releaseReplacementProbe, replacementRequests, workers);
                ElasticLoadBalancingV2Client client = AwsTestClients.create(
                ElasticLoadBalancingV2Client.builder(), "test", Region.US_EAST_1, endpoint)) {
            try {
                balancer = client.createLoadBalancer(request -> request.name("mixed-admission")
                        .type(LoadBalancerTypeEnum.APPLICATION)
                        .subnets(Ec2Service.defaultSubnetId(REGION, "a"), Ec2Service.defaultSubnetId(REGION, "b")))
                        .loadBalancers().getFirst().loadBalancerArn();
                group = client.createTargetGroup(request -> request.name("mixed-admission-targets")
                        .protocol(ProtocolEnum.HTTP).port(oldBackend.server().getAddress().getPort())
                        .targetType(TargetTypeEnum.IP).vpcId(Ec2Service.defaultVpcId(REGION))
                        .healthCheckPath("/health").healthCheckIntervalSeconds(300)
                        .healthCheckTimeoutSeconds(120).healthyThresholdCount(10))
                        .targetGroups().getFirst().targetGroupArn();
                String targetGroup = group;
                TargetDescription oldTarget = target(oldBackend);
                TargetDescription newTarget = target(replacement);
                client.registerTargets(request -> request.targetGroupArn(targetGroup).targets(oldTarget));
                awaitHealth(client, targetGroup, oldTarget, TargetHealthStateEnum.HEALTHY);

                int port = FreePorts.loopbackIpv4Port();
                CreateListenerRequest.Builder listener = CreateListenerRequest.builder()
                        .loadBalancerArn(balancer).protocol(protocol).port(port)
                        .defaultActions(Action.builder().type(ActionTypeEnum.FORWARD)
                                .targetGroupArn(targetGroup).build());
                HttpClient.Builder transport = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(2));
                if (protocol == ProtocolEnum.HTTPS) {
                    CertificateGenerator.GeneratedCertificate material = new CertificateGenerator()
                            .generateSelfSignedCertificate("localhost", List.of(), KeyAlgorithm.RSA_2048);
                    certificate = acmService.importCertificate(material.certificatePem(), material.privateKeyPem(),
                            null, null, Map.of(), REGION);
                    String certificateArn = certificate.getArn();
                    listener.certificates(value -> value.certificateArn(certificateArn));
                    X509Certificate listenerCertificate = Pem.parseCertificate(certificate.getCertificateBody());
                    transport.sslContext(TlsTrust.trusting(listenerCertificate));
                }
                client.createListener(listener.build());
                URI search = URI.create(protocol.toString().toLowerCase() + "://localhost:" + port + "/docs/_search");
                try (HttpClient wire = transport.build()) {
                    await().atMost(Duration.ofSeconds(5)).pollDelay(Duration.ZERO)
                            .pollInterval(Duration.ofMillis(10)).ignoreException(IOException.class)
                            .untilAsserted(() -> assertEquals(OLD_DOCUMENT, documentRequest(wire, search)));
                    client.registerTargets(request -> request.targetGroupArn(targetGroup).targets(newTarget));
                    assertTrue(replacementProbeStarted.await(5, TimeUnit.SECONDS),
                            "The replacement must be held inside its first actual HTTP health probe");
                    assertHealth(client, targetGroup, newTarget, TargetHealthStateEnum.INITIAL);
                    assertHealth(client, targetGroup, oldTarget, TargetHealthStateEnum.HEALTHY);
                    for (int request = 0; request < 10; request++) {
                        assertEquals(OLD_DOCUMENT, documentRequest(wire, search),
                                "Initial replacement must not receive document traffic while an old target is healthy");
                    }
                    assertEquals(0, replacementRequests.get(), "No request may reach the initial replacement");
                    assertHealth(client, targetGroup, newTarget, TargetHealthStateEnum.INITIAL);
                    releaseReplacementProbe.countDown();
                    awaitHealth(client, targetGroup, newTarget, TargetHealthStateEnum.HEALTHY);
                    Set<String> admittedResponses = new HashSet<>();
                    for (int request = 0; request < 10; request++) {
                        admittedResponses.add(documentRequest(wire, search));
                    }
                    assertEquals(Set.of(OLD_DOCUMENT, REPLACEMENT_DOCUMENT), admittedResponses);
                    assertTrue(replacementRequests.get() > 0,
                            "The replacement must participate after its real health result is published");
                }
            } finally {
                releaseReplacementProbe.countDown();
                String ownedBalancer = balancer;
                String ownedGroup = group;
                Certificate ownedCertificate = certificate;
                assertAll("Remove every mixed-target fixture resource",
                        () -> {
                            if (ownedBalancer != null) {
                                client.deleteLoadBalancer(request -> request.loadBalancerArn(ownedBalancer));
                            }
                        },
                        () -> {
                            if (ownedGroup != null) {
                                client.deleteTargetGroup(request -> request.targetGroupArn(ownedGroup));
                            }
                        },
                        () -> {
                            if (ownedCertificate != null) {
                                acmService.deleteCertificate(ownedCertificate.getArn(), REGION);
                            }
                        });
            }
        } finally {
            releaseReplacementProbe.countDown();
        }
    }

    private record Backend(HttpServer server, CountDownLatch releaseProbe) implements AutoCloseable {
        @Override
        public void close() {
            if (releaseProbe != null) {
                releaseProbe.countDown();
            }
            server.stop(0);
        }
    }

    private static Backend backend(String document, CountDownLatch probeStarted, CountDownLatch releaseProbe,
                                   AtomicInteger requests, ExecutorService workers) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(workers);
        server.createContext("/health", exchange -> {
            if (probeStarted != null) {
                probeStarted.countDown();
                try {
                    if (!releaseProbe.await(60, TimeUnit.SECONDS)) {
                        exchange.close();
                        return;
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    exchange.close();
                    throw new IOException("Interrupted while holding the replacement health probe", failure);
                }
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/docs/_search", exchange -> {
            requests.incrementAndGet();
            respond(exchange, document);
        });
        server.start();
        return new Backend(server, releaseProbe);
    }

    private static void respond(HttpExchange exchange, String document) throws IOException {
        byte[] response = document.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(response);
        }
    }

    private static TargetDescription target(Backend backend) {
        return TargetDescription.builder().id("127.0.0.1").port(backend.server().getAddress().getPort()).build();
    }

    private static void awaitHealth(ElasticLoadBalancingV2Client client, String group, TargetDescription target,
                                    TargetHealthStateEnum state) {
        await().atMost(Duration.ofSeconds(5)).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10))
                .untilAsserted(() -> assertHealth(client, group, target, state));
    }

    private static void assertHealth(ElasticLoadBalancingV2Client client, String group, TargetDescription target,
                                     TargetHealthStateEnum state) {
        assertEquals(state, client.describeTargetHealth(request -> request.targetGroupArn(group).targets(target))
                .targetHealthDescriptions().getFirst().targetHealth().state());
    }

    private static String documentRequest(HttpClient wire, URI search) throws IOException, InterruptedException {
        HttpResponse<String> response = wire.send(HttpRequest.newBuilder(search).timeout(Duration.ofSeconds(2))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return response.body();
    }
}
