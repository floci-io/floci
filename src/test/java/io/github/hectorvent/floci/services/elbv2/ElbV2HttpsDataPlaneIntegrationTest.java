package io.github.hectorvent.floci.services.elbv2;

import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.CertificateReferenceResolver;
import io.github.hectorvent.floci.core.common.CertificateReferenceResolver.CertificateMaterial;
import io.github.hectorvent.floci.services.acm.AcmService;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.Certificate;
import io.github.hectorvent.floci.services.acm.model.CertificateOptions;
import io.github.hectorvent.floci.services.acm.model.CertificateStatus;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.services.acm.model.ValidationMethod;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.testing.InlineMocks;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

@QuarkusTest
@TestProfile(ElbV2HttpsDataPlaneIntegrationTest.RealElbV2DataPlaneProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElbV2HttpsDataPlaneIntegrationTest {

    public static final class RealElbV2DataPlaneProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.elbv2.mock", "false");
        }
    }

    private static final String REGION = "us-east-1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260720/us-east-1/elasticloadbalancing/aws4_request";
    private static final String SHARED_CERTIFICATE_HOST = "shared.elb.localhost.localstack.cloud";
    private static final int HTTPS_PORT = 7794;
    private static final int HTTP_PORT = 7795;

    private static String firstLbArn;
    private static String firstTargetGroupArn;
    private static String firstListenerArn;
    private static String secondLbArn;
    private static String secondListenerArn;
    private static String httpLbArn;
    private static String httpListenerArn;
    private static HttpServer targetServer;

    @Inject
    AcmService acmService;

    @Inject
    ElbV2Service elbV2Service;

    @Inject
    ElbV2DataPlane dataPlane;

    @BeforeAll
    static void startTargetServer() throws IOException {
        RestAssuredJsonUtils.configureAwsContentTypes();
        targetServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        targetServer.createContext("/", exchange -> {
            byte[] response = "target".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        targetServer.start();
    }

    @AfterAll
    static void stopTargetServer() {
        if (targetServer != null) {
            targetServer.stop(0);
        }
    }

    @Test
    @Order(-8)
    void localRequestsPreserveHostAndVerifiedSniWithoutPublicDns() throws Exception {
        String host = "loopback.elb.invalid";
        LoadBalancerRef loadBalancer = null;
        Certificate certificate = null;
        try {
            loadBalancer = createLoadBalancer("tls-local-transport");
            CertificateGenerator.GeneratedCertificate pair = new CertificateGenerator().generateSelfSignedCertificate(
                    host, List.of(), KeyAlgorithm.RSA_2048);
            certificate = acmService.importCertificate(pair.certificatePem(), pair.privateKeyPem(),
                    null, null, Map.of(), REGION);
            String https = createFixedResponseListener(loadBalancer.arn(), "HTTPS", HTTPS_PORT,
                    certificate.getArn(), "fallback");
            String http = createFixedResponseListener(loadBalancer.arn(), "HTTP", HTTP_PORT, null, "fallback");
            for (String listener : List.of(https, http)) {
                given().header("Authorization", AUTH)
                        .formParam("Action", "CreateRule").formParam("ListenerArn", listener)
                        .formParam("Priority", "1")
                        .formParam("Conditions.member.1.Field", "host-header")
                        .formParam("Conditions.member.1.HostHeaderConfig.Values.member.1", host)
                        .formParam("Actions.member.1.Type", "fixed-response")
                        .formParam("Actions.member.1.FixedResponseConfig.StatusCode", "200")
                        .formParam("Actions.member.1.FixedResponseConfig.ContentType", "text/plain")
                        .formParam("Actions.member.1.FixedResponseConfig.MessageBody", "local-only")
                        .when().post("/").then().statusCode(200);
            }
            awaitPlainResponse(host, HTTP_PORT, "local-only");
            awaitHttpsMaterial(host, 200, "local-only", certificate, certificate);
            Certificate expected = certificate;
            assertThrows(SSLHandshakeException.class,
                    () -> httpsRequest("other.elb.invalid", HTTPS_PORT, expected));
        } finally {
            LoadBalancerRef ownedLoadBalancer = loadBalancer;
            Certificate ownedCertificate = certificate;
            assertAll("Delete the local transport fixture after any failure",
                    () -> {
                        if (ownedLoadBalancer != null) {
                            given().header("Authorization", AUTH).formParam("Action", "DeleteLoadBalancer")
                                    .formParam("LoadBalancerArn", ownedLoadBalancer.arn())
                                    .when().post("/").then().statusCode(200);
                        }
                    },
                    () -> {
                        if (ownedCertificate != null) {
                            acmService.deleteCertificate(ownedCertificate.getArn(), REGION);
                        }
                    });
        }
    }

    @Test
    @Order(-7)
    void acmAssociationsSurviveReimportUntilTheLastListenerAndPersistedConsumerRelease() throws Exception {
        LoadBalancerRef loadBalancer = createLoadBalancer("acm-associations");
        Certificate certificate = requestCertificate(loadBalancer.dnsName());
        String persistedConsumer = "arn:aws:cognito-idp:us-east-1:000000000000:userpool/persisted-consumer";
        boolean persisted = false;
        try {
            createFixedResponseListener(loadBalancer.arn(), "HTTPS", HTTPS_PORT, certificate.getArn(), "first");
            String second = createFixedResponseListener(loadBalancer.arn(), "HTTPS", HTTP_PORT, certificate.getArn(), "second");
            acmService.addInUseBy(certificate.getArn(), persistedConsumer, REGION);
            persisted = true;
            assertAcmConsumers(certificate.getArn(), List.of(loadBalancer.arn(), persistedConsumer));
            assertEquals(List.of(persistedConsumer), acmService.getCertificate(certificate.getArn(), REGION).getInUseBy(),
                    "computed load-balancer references must not be stored in ACM");
            assertAcmDeleteInUse(certificate.getArn());
            CertificateGenerator.GeneratedCertificate replacement = new CertificateGenerator().generateSelfSignedCertificate(
                    loadBalancer.dnsName(), List.of(), KeyAlgorithm.RSA_2048);
            Certificate current = acmService.importCertificate(replacement.certificatePem(), replacement.privateKeyPem(),
                    null, certificate.getArn(), Map.of(), REGION);
            assertAcmConsumers(current.getArn(), List.of(loadBalancer.arn(), persistedConsumer));
            assertEquals(List.of(persistedConsumer), current.getInUseBy());
            awaitHttpsMaterial(loadBalancer.dnsName(), 200, "first", current, certificate, current);
            HttpsResult secondWire = awaitHttpsAtPort(loadBalancer.dnsName(), HTTP_PORT, current);
            assertEquals("second", secondWire.body());
            assertPeerCertificate(secondWire.session(), current, loadBalancer.dnsName());
            given().header("Authorization", AUTH).formParam("Action", "DeleteListener")
                    .formParam("ListenerArn", second).when().post("/").then().statusCode(200);
            assertAcmConsumers(current.getArn(), List.of(loadBalancer.arn(), persistedConsumer));
            assertAcmDeleteInUse(current.getArn());
            given().header("Authorization", AUTH).formParam("Action", "DeleteLoadBalancer")
                    .formParam("LoadBalancerArn", loadBalancer.arn()).when().post("/").then().statusCode(200);
            assertAcmConsumers(current.getArn(), List.of(persistedConsumer));
            assertAcmDeleteInUse(current.getArn());
            acmService.removeInUseBy(current.getArn(), persistedConsumer, REGION);
            persisted = false;
            assertAcmConsumers(current.getArn(), List.of());
            acmRequest()
                    .header("Authorization", accountAuthorization("test", "acm"))
                    .header("X-Amz-Target", "CertificateManager.DeleteCertificate")
                    .body(Map.of("CertificateArn", current.getArn())).when().post("/").then().statusCode(200);
            AwsException missing = assertThrows(AwsException.class, () -> acmService.getCertificate(current.getArn(), REGION));
            assertEquals("ResourceNotFoundException", missing.getErrorCode());
        } finally {
            boolean releasePersisted = persisted;
            assertAll("Clean every association fixture resource",
                    () -> given().header("Authorization", AUTH).formParam("Action", "DeleteLoadBalancer")
                            .formParam("LoadBalancerArn", loadBalancer.arn()).when().post("/").then().statusCode(200),
                    () -> { if (releasePersisted) { acmService.removeInUseBy(certificate.getArn(), persistedConsumer, REGION); } },
                    () -> {
                        try {
                            acmService.deleteCertificate(certificate.getArn(), REGION);
                        } catch (AwsException failure) {
                            if (!"ResourceNotFoundException".equals(failure.getErrorCode())) { throw failure; }
                        }
                    });
        }
    }

    private static RequestSpecification acmRequest() {
        return given().contentType("application/x-amz-json-1.1");
    }

    private static void assertAcmDeleteInUse(String arn) {
        acmRequest()
                .header("Authorization", accountAuthorization("test", "acm"))
                .header("X-Amz-Target", "CertificateManager.DeleteCertificate")
                .body(Map.of("CertificateArn", arn)).when().post("/").then().statusCode(400)
                .body("__type", equalTo("ResourceInUseException"));
    }

    private static void assertAcmConsumers(String arn, List<String> expected) {
        List<String> actual = acmRequest()
                .header("Authorization", accountAuthorization("test", "acm"))
                .header("X-Amz-Target", "CertificateManager.DescribeCertificate")
                .body(Map.of("CertificateArn", arn)).when().post("/").then().statusCode(200)
                .extract().path("Certificate.InUseBy");
        assertEquals(expected.stream().sorted().toList(), actual.stream().sorted().toList());
        List<Map<String, Object>> summaries = acmRequest()
                .header("Authorization", accountAuthorization("test", "acm"))
                .header("X-Amz-Target", "CertificateManager.ListCertificates").body(Map.of())
                .when().post("/").then().statusCode(200).extract().path("CertificateSummaryList");
        Map<String, Object> summary = summaries.stream().filter(row -> arn.equals(row.get("CertificateArn")))
                .findFirst().orElseThrow();
        assertEquals(!expected.isEmpty(), summary.get("InUse"));
    }

    @Test
    @Order(-6)
    void controlPublicationDoesNotBlockCommittedTraffic() throws Exception {
        LoadBalancerRef first = createLoadBalancer("publication-traffic");
        LoadBalancerRef unrelated = createLoadBalancer("publication-other");
        ElbV2Service service = ClientProxy.unwrap(elbV2Service);
        ElbV2DataPlane original = service.dataPlane;
        ElbV2DataPlane delegating = mock(ElbV2DataPlane.class, delegatesTo(original));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            String listener = createFixedResponseListener(first.arn(), "HTTP", HTTP_PORT, null, "old");
            createFixedResponseListener(unrelated.arn(), "HTTP", HTTPS_PORT, null, "unrelated");
            assertPlainResponse(first.dnsName(), HTTP_PORT, "old");
            assertPlainResponse(unrelated.dnsName(), HTTPS_PORT, "unrelated");
            doAnswer(call -> original.updateListener(call.getArgument(0), () -> {
                entered.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                return call.getArgument(1, BooleanSupplier.class).getAsBoolean();
            })).when(delegating).updateListener(eq(HTTP_PORT), any());
            service.dataPlane = delegating;
            CompletableFuture<Response> changing = CompletableFuture.supplyAsync(() -> given()
                    .header("Authorization", AUTH).formParam("Action", "ModifyListener")
                    .formParam("ListenerArn", listener).formParam("DefaultActions.member.1.Type", "fixed-response")
                    .formParam("DefaultActions.member.1.FixedResponseConfig.StatusCode", "200")
                    .formParam("DefaultActions.member.1.FixedResponseConfig.MessageBody", "new")
                    .when().post("/"), worker);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertAll("Traffic must progress while control publication waits",
                    () -> assertPlainResponse(first.dnsName(), HTTP_PORT, "old"),
                    () -> assertPlainResponse(unrelated.dnsName(), HTTPS_PORT, "unrelated"));
            release.countDown();
            assertEquals(200, changing.get(10, TimeUnit.SECONDS).statusCode());
            awaitPlainResponse(first.dnsName(), HTTP_PORT, "new");
        } finally {
            release.countDown();
            worker.shutdown();
            try {
                assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
            } finally {
                worker.shutdownNow();
                service.dataPlane = original;
                assertAll("Delete both traffic fixtures",
                        () -> deleteLoadBalancer(first.arn()),
                        () -> deleteLoadBalancer(unrelated.arn()));
            }
        }
    }

    @Test
    @Order(-5)
    void missingHttpsCertificateDoesNotPublishAListenerOrPreventAValidRetry() throws Exception {
        LoadBalancerRef loadBalancer = createLoadBalancer("https-missing-create");
        Certificate certificate = requestCertificate(loadBalancer.dnsName());
        try {
            List<String> rulesBefore = given().header("Authorization", AUTH).formParam("Action", "DescribeRules")
                    .when().post("/").then().statusCode(200).extract().xmlPath()
                    .getList("DescribeRulesResponse.DescribeRulesResult.Rules.member.RuleArn");
            Response rejected = given().header("Authorization", AUTH)
                    .formParam("Action", "CreateListener").formParam("LoadBalancerArn", loadBalancer.arn())
                    .formParam("Protocol", "HTTPS").formParam("Port", HTTPS_PORT)
                    .formParam("Tags.member.1.Key", "missing-certificate")
                    .formParam("Tags.member.1.Value", "must-not-publish")
                    .formParam("DefaultActions.member.1.Type", "fixed-response")
                    .formParam("DefaultActions.member.1.FixedResponseConfig.StatusCode", "200")
                    .formParam("DefaultActions.member.1.FixedResponseConfig.MessageBody", "invalid")
                    .when().post("/");
            List<String> published = given().header("Authorization", AUTH)
                    .formParam("Action", "DescribeListeners").formParam("LoadBalancerArn", loadBalancer.arn())
                    .when().post("/").then().statusCode(200).extract().xmlPath()
                    .getList("DescribeListenersResponse.DescribeListenersResult.Listeners.member.ListenerArn");
            assertAll("Refuse before publishing listener, default rule, or tags",
                    () -> assertEquals(400, rejected.statusCode()),
                    () -> assertTrue(published.isEmpty(), "failed create must not leave a visible listener"),
                    () -> assertEquals(rulesBefore, given().header("Authorization", AUTH)
                            .formParam("Action", "DescribeRules").when().post("/").then().statusCode(200)
                            .extract().xmlPath().getList("DescribeRulesResponse.DescribeRulesResult.Rules.member.RuleArn")),
                    () -> {
                        for (String listener : published) {
                            assertTrue(given().header("Authorization", AUTH).formParam("Action", "DescribeRules")
                                    .formParam("ListenerArn", listener).when().post("/").then().statusCode(200)
                                    .extract().xmlPath().getList("DescribeRulesResponse.DescribeRulesResult.Rules.member").isEmpty(),
                                    "failed create must not leave its default rule");
                        }
                    },
                    () -> {
                        for (String listener : published) {
                            assertTrue(given().header("Authorization", AUTH).formParam("Action", "DescribeTags")
                                    .formParam("ResourceArns.member.1", listener).when().post("/").then().statusCode(200)
                                    .extract().xmlPath().getList("DescribeTagsResponse.DescribeTagsResult.TagDescriptions.member.Tags.member").isEmpty(),
                                    "failed create must not leave its tags");
                        }
                    });
            createFixedResponseListener(loadBalancer.arn(), "HTTPS", HTTPS_PORT, certificate.getArn(), "retry");
            HttpsResult retried = awaitHttps(loadBalancer.dnsName(), certificate);
            assertEquals(200, retried.statusCode());
            assertEquals("retry", retried.body());
            assertPeerCertificate(retried.session(), certificate, loadBalancer.dnsName());
        } finally {
            cleanupMissingCertificateFixture(loadBalancer, certificate);
        }
    }

    @Test
    @Order(-4)
    void missingHttpsCertificatePreservesTheHttpListenerAndItsDefaultRule() throws Exception {
        LoadBalancerRef loadBalancer = createLoadBalancer("https-missing-modify");
        Certificate certificate = requestCertificate(loadBalancer.dnsName());
        String listener = null;
        try {
            listener = createFixedResponseListener(loadBalancer.arn(), "HTTP", HTTPS_PORT, null, "original");
            awaitPlainResponse(loadBalancer.dnsName(), HTTPS_PORT, "original");
            Response rejected = given().header("Authorization", AUTH)
                    .formParam("Action", "ModifyListener").formParam("ListenerArn", listener)
                    .formParam("Protocol", "HTTPS").formParam("SslPolicy", "must-not-publish")
                    .formParam("DefaultActions.member.1.Type", "fixed-response")
                    .formParam("DefaultActions.member.1.FixedResponseConfig.StatusCode", "200")
                    .formParam("DefaultActions.member.1.FixedResponseConfig.MessageBody", "invalid")
                    .when().post("/");
            Response described = given().header("Authorization", AUTH)
                    .formParam("Action", "DescribeListeners").formParam("ListenerArns.member.1", listener)
                    .when().post("/").then().statusCode(200).extract().response();
            String result = "DescribeListenersResponse.DescribeListenersResult.Listeners.member.";
            String ownedListener = listener;
            assertAll("Refused conversion must preserve control and data planes",
                    () -> assertEquals(400, rejected.statusCode()),
                    () -> assertEquals("HTTP", described.path(result + "Protocol")),
                    () -> assertEquals(String.valueOf(HTTPS_PORT), described.path(result + "Port")),
                    () -> assertEquals("", described.path(result + "SslPolicy")),
                    () -> assertTrue(described.xmlPath().getList(result + "Certificates.member").isEmpty()),
                    () -> assertEquals("original", described.path(result
                            + "DefaultActions.member.FixedResponseConfig.MessageBody")),
                    () -> assertEquals("original", given().header("Authorization", AUTH)
                            .formParam("Action", "DescribeRules").formParam("ListenerArn", ownedListener)
                            .when().post("/").then().statusCode(200).extract()
                            .path("DescribeRulesResponse.DescribeRulesResult.Rules.member.Actions.member.FixedResponseConfig.MessageBody")),
                    () -> assertPlainResponse(loadBalancer.dnsName(), HTTPS_PORT, "original"));
            given().header("Authorization", AUTH).formParam("Action", "ModifyListener")
                    .formParam("ListenerArn", listener).formParam("Protocol", "HTTPS")
                    .formParam("Certificates.member.1.CertificateArn", certificate.getArn())
                    .when().post("/").then().statusCode(200);
            HttpsResult retried = awaitHttps(loadBalancer.dnsName(), certificate);
            assertEquals("original", retried.body());
            assertPeerCertificate(retried.session(), certificate, loadBalancer.dnsName());
        } finally {
            cleanupMissingCertificateFixture(loadBalancer, certificate);
        }
    }

    @Test
    @Order(-3)
    void createCannotPublishAfterAConflictingProtocolWinsThePort() throws Exception {
        LoadBalancerRef first = createLoadBalancer("protocol-create-first");
        LoadBalancerRef winner = createLoadBalancer("protocol-create-winner");
        Certificate certificate = requestCertificate(winner.dnsName());
        ElbV2Service service = ClientProxy.unwrap(elbV2Service);
        ElbV2DataPlane original = service.dataPlane;
        CountDownLatch preflight = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> attemptedArn = new AtomicReference<>();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CompletableFuture<Response> creation = null;
        try {
            service.dataPlane = preflightBarrier(original, "HTTP", preflight, release, attemptedArn);
            creation = CompletableFuture.supplyAsync(() -> given().header("Authorization", AUTH)
                    .formParam("Action", "CreateListener").formParam("LoadBalancerArn", first.arn())
                    .formParam("Protocol", "HTTP").formParam("Port", HTTPS_PORT)
                    .formParam("Tags.member.1.Key", "protocol-loser").formParam("Tags.member.1.Value", "absent")
                    .formParam("DefaultActions.member.1.Type", "fixed-response")
                    .formParam("DefaultActions.member.1.FixedResponseConfig.StatusCode", "200")
                    .formParam("DefaultActions.member.1.FixedResponseConfig.MessageBody", "loser")
                    .when().post("/"), worker);
            assertTrue(preflight.await(5, TimeUnit.SECONDS), "first preflight did not complete");
            createFixedResponseListener(first.arn(), "HTTP", HTTP_PORT, null, "unrelated");
            awaitPlainResponse(first.dnsName(), HTTP_PORT, "unrelated");
            String winnerArn = createFixedResponseListener(winner.arn(), "HTTPS", HTTPS_PORT,
                    certificate.getArn(), "winner");
            assertEquals("winner", awaitHttps(winner.dnsName(), certificate).body());
            release.countDown();
            Response rejected = creation.get(10, TimeUnit.SECONDS);
            List<String> actualPorts = given().header("Authorization", AUTH)
                    .formParam("Action", "DescribeListeners").formParam("LoadBalancerArn", first.arn())
                    .when().post("/").then().statusCode(200).extract().xmlPath()
                    .getList("DescribeListenersResponse.DescribeListenersResult.Listeners.member.Port");
            assertAll("A stale successful preflight must not publish the losing request",
                    () -> assertEquals(400, rejected.statusCode()),
                    () -> assertEquals(List.of(String.valueOf(HTTP_PORT)), actualPorts),
                    () -> assertTrue(given().header("Authorization", AUTH).formParam("Action", "DescribeRules")
                            .formParam("ListenerArn", attemptedArn.get()).when().post("/").then().statusCode(200)
                            .extract().xmlPath().getList("DescribeRulesResponse.DescribeRulesResult.Rules.member").isEmpty()),
                    () -> assertTrue(given().header("Authorization", AUTH).formParam("Action", "DescribeTags")
                            .formParam("ResourceArns.member.1", attemptedArn.get()).when().post("/").then().statusCode(200)
                            .extract().xmlPath().getList("DescribeTagsResponse.DescribeTagsResult.TagDescriptions.member.Tags.member").isEmpty()),
                    () -> assertEquals("winner", awaitHttps(winner.dnsName(), certificate).body()),
                    () -> assertPlainResponse(first.dnsName(), HTTP_PORT, "unrelated"));
            given().header("Authorization", AUTH).formParam("Action", "DeleteListener")
                    .formParam("ListenerArn", winnerArn).when().post("/").then().statusCode(200);
            awaitListenerPortClosed();
            createFixedResponseListener(first.arn(), "HTTP", HTTPS_PORT, null, "retry");
            awaitPlainResponse(first.dnsName(), HTTPS_PORT, "retry");
        } finally {
            release.countDown();
            worker.shutdownNow();
            try {
                assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS), "creation worker did not stop");
            } finally {
                service.dataPlane = original;
                cleanupProtocolFixtures(first, winner, certificate);
            }
        }
    }

    @Test
    @Order(-2)
    void modifyCannotPublishAfterAConflictingProtocolWinsTheDestinationPort() throws Exception {
        LoadBalancerRef first = createLoadBalancer("protocol-modify-first");
        LoadBalancerRef winner = createLoadBalancer("protocol-modify-winner");
        Certificate certificate = requestCertificate(first.dnsName());
        ElbV2Service service = ClientProxy.unwrap(elbV2Service);
        ElbV2DataPlane original = service.dataPlane;
        CountDownLatch preflight = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> attemptedArn = new AtomicReference<>();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            String firstArn = createFixedResponseListener(first.arn(), "HTTP", HTTP_PORT, null, "original");
            awaitPlainResponse(first.dnsName(), HTTP_PORT, "original");
            service.dataPlane = preflightBarrier(original, "HTTPS", preflight, release, attemptedArn);
            CompletableFuture<Response> modification = CompletableFuture.supplyAsync(() ->
                    given().header("Authorization", AUTH).formParam("Action", "ModifyListener")
                            .formParam("ListenerArn", firstArn).formParam("Protocol", "HTTPS")
                            .formParam("Port", HTTPS_PORT).formParam("SslPolicy", "must-not-publish")
                            .formParam("Certificates.member.1.CertificateArn", certificate.getArn())
                            .formParam("DefaultActions.member.1.Type", "fixed-response")
                            .formParam("DefaultActions.member.1.FixedResponseConfig.StatusCode", "200")
                            .formParam("DefaultActions.member.1.FixedResponseConfig.MessageBody", "invalid")
                            .when().post("/"), worker);
            assertTrue(preflight.await(5, TimeUnit.SECONDS), "modify preflight did not complete");
            assertPlainResponse(first.dnsName(), HTTP_PORT, "original");
            String winnerArn = createFixedResponseListener(winner.arn(), "HTTP", HTTPS_PORT, null, "winner");
            awaitPlainResponse(winner.dnsName(), HTTPS_PORT, "winner");
            release.countDown();
            Response rejected = modification.get(10, TimeUnit.SECONDS);
            Response described = given().header("Authorization", AUTH).formParam("Action", "DescribeListeners")
                    .formParam("ListenerArns.member.1", firstArn).when().post("/")
                    .then().statusCode(200).extract().response();
            String result = "DescribeListenersResponse.DescribeListenersResult.Listeners.member.";
            assertAll("Refused destination must preserve the original listener and the competing winner",
                    () -> assertEquals(400, rejected.statusCode()),
                    () -> assertEquals("HTTP", described.path(result + "Protocol")),
                    () -> assertEquals(String.valueOf(HTTP_PORT), described.path(result + "Port")),
                    () -> assertEquals("", described.path(result + "SslPolicy")),
                    () -> assertTrue(described.xmlPath().getList(result + "Certificates.member").isEmpty()),
                    () -> assertEquals("original", described.path(result
                            + "DefaultActions.member.FixedResponseConfig.MessageBody")),
                    () -> assertPlainResponse(first.dnsName(), HTTP_PORT, "original"),
                    () -> assertPlainResponse(winner.dnsName(), HTTPS_PORT, "winner"));
            given().header("Authorization", AUTH).formParam("Action", "DeleteListener")
                    .formParam("ListenerArn", winnerArn).when().post("/").then().statusCode(200);
            awaitListenerPortClosed();
            given().header("Authorization", AUTH).formParam("Action", "ModifyListener")
                    .formParam("ListenerArn", firstArn).formParam("Protocol", "HTTPS").formParam("Port", HTTPS_PORT)
                    .formParam("Certificates.member.1.CertificateArn", certificate.getArn())
                    .when().post("/").then().statusCode(200);
            assertEquals("original", awaitHttps(first.dnsName(), certificate).body());
        } finally {
            release.countDown();
            worker.shutdownNow();
            try {
                assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS), "modification worker did not stop");
            } finally {
                service.dataPlane = original;
                cleanupProtocolFixtures(first, winner, certificate);
            }
        }
    }

    @Test
    @Order(-1)
    void portOnlyChangesReconcileCertificateChangesDuringMaterialPreparation() throws Exception {
        LoadBalancerRef loadBalancer = createLoadBalancer("tls-material-retry");
        LoadBalancerRef unrelated = createLoadBalancer("tls-material-progress");
        ElbV2DataPlane runtime = ClientProxy.unwrap(dataPlane);
        CertificateReferenceResolver original = runtime.certificateResolver;
        ExecutorService worker = Executors.newSingleThreadExecutor();
        AtomicReference<CountDownLatch> entered = new AtomicReference<>(new CountDownLatch(1));
        AtomicReference<CountDownLatch> release = new AtomicReference<>(new CountDownLatch(1));
        AtomicReference<String> pausedCertificate = new AtomicReference<>();
        AtomicBoolean claimedPause = new AtomicBoolean();
        AtomicBoolean refuseUnchanged = new AtomicBoolean(true);
        AtomicBoolean iamPresent = new AtomicBoolean();
        AtomicInteger iamReads = new AtomicInteger();
        String iamName = "tls-material-" + Long.toUnsignedString(System.nanoTime(), 36);
        Certificate first = null;
        Certificate added = null;
        try {
            first = requestCertificate(loadBalancer.dnsName());
            String addedHost = "later-material.elb.localhost.localstack.cloud";
            added = requestCertificate(addedHost);
            CertificateGenerator.GeneratedCertificate pair = new CertificateGenerator().generateSelfSignedCertificate(
                    "additional-material.example.test", List.of(), KeyAlgorithm.RSA_2048);
            String iamArn = uploadIamCertificate("test", iamName, pair);
            iamPresent.set(true);
            String listener = createFixedResponseListener(loadBalancer.arn(), "HTTPS", HTTPS_PORT, first.getArn(), "original");
            given().header("Authorization", AUTH).formParam("Action", "AddListenerCertificates")
                    .formParam("ListenerArn", listener).formParam("Certificates.member.1.CertificateArn", iamArn)
                    .when().post("/").then().statusCode(200);
            assertEquals("original", awaitHttps(loadBalancer.dnsName(), first).body());
            CertificateReferenceResolver wrapped = mock(CertificateReferenceResolver.class, delegatesTo(original));
            doAnswer(call -> {
                String certificate = call.getArgument(2);
                if (iamArn.equals(certificate)) {
                    iamReads.incrementAndGet();
                    if (refuseUnchanged.compareAndSet(true, false)) {
                        throw new AwsException("CertificateNotFound", "The specified certificate does not exist.", 400);
                    }
                }
                if (certificate.equals(pausedCertificate.get()) && claimedPause.compareAndSet(false, true)) {
                    entered.get().countDown();
                    assertTrue(release.get().await(30, TimeUnit.SECONDS), "material preparation was not released");
                }
                return original.publishedMaterial(call.getArgument(0), call.getArgument(1), certificate);
            }).when(wrapped).publishedMaterial(anyString(), anyString(), anyString());
            runtime.certificateResolver = wrapped;
            given().header("Authorization", AUTH).formParam("Action", "ModifyListener")
                    .formParam("ListenerArn", listener).formParam("Port", HTTP_PORT)
                    .when().post("/").then().statusCode(400)
                    .body("ErrorResponse.Error.Code", equalTo("CertificateNotFound"));
            assertEquals(1, iamReads.get(), "an unchanged snapshot must preserve its preparation error");
            given().header("Authorization", AUTH).formParam("Action", "DescribeListeners")
                    .formParam("ListenerArns.member.1", listener).when().post("/").then().statusCode(200)
                    .body("DescribeListenersResponse.DescribeListenersResult.Listeners.member.Port",
                            equalTo(String.valueOf(HTTPS_PORT)));
            pausedCertificate.set(iamArn);
            CompletableFuture<Response> moving = CompletableFuture.supplyAsync(() -> given().header("Authorization", AUTH)
                    .formParam("Action", "ModifyListener").formParam("ListenerArn", listener)
                    .formParam("Port", HTTP_PORT).when().post("/"), worker);
            assertTrue(entered.get().await(5, TimeUnit.SECONDS));
            given().header("Authorization", AUTH).formParam("Action", "RemoveListenerCertificates")
                    .formParam("ListenerArn", listener).formParam("Certificates.member.1.CertificateArn", iamArn)
                    .when().post("/").then().statusCode(200);
            given().header("Authorization", accountAuthorization("test", "iam"))
                    .formParam("Action", "DeleteServerCertificate").formParam("ServerCertificateName", iamName)
                    .when().post("/").then().statusCode(200);
            iamPresent.set(false);
            String progress = createFixedResponseListener(unrelated.arn(), "HTTP", HTTP_PORT, null, "progress");
            awaitPlainResponse(unrelated.dnsName(), HTTP_PORT, "progress");
            assertEquals("original", awaitHttps(loadBalancer.dnsName(), first).body());
            given().header("Authorization", AUTH).formParam("Action", "DeleteListener")
                    .formParam("ListenerArn", progress).when().post("/").then().statusCode(200);
            awaitListenerPortClosed(HTTP_PORT);
            release.get().countDown();
            assertEquals(200, moving.get(10, TimeUnit.SECONDS).statusCode());
            assertEquals(2, iamReads.get(), "retry must discard the removed, deleted certificate");
            HttpsResult moved = awaitHttpsAtPort(loadBalancer.dnsName(), HTTP_PORT, first);
            assertEquals("original", moved.body());
            assertPeerCertificate(moved.session(), first, loadBalancer.dnsName());
            given().header("Authorization", AUTH).formParam("Action", "DescribeListenerCertificates")
                    .formParam("ListenerArn", listener).when().post("/").then().statusCode(200)
                    .body("DescribeListenerCertificatesResponse.DescribeListenerCertificatesResult.Certificates.member.CertificateArn",
                            equalTo(first.getArn()));
            entered.set(new CountDownLatch(1));
            release.set(new CountDownLatch(1));
            claimedPause.set(false);
            pausedCertificate.set(first.getArn());
            CompletableFuture<Response> movingBack = CompletableFuture.supplyAsync(() -> given().header("Authorization", AUTH)
                    .formParam("Action", "ModifyListener").formParam("ListenerArn", listener)
                    .formParam("Port", HTTPS_PORT).when().post("/"), worker);
            assertTrue(entered.get().await(5, TimeUnit.SECONDS));
            given().header("Authorization", AUTH).formParam("Action", "AddListenerCertificates")
                    .formParam("ListenerArn", listener).formParam("Certificates.member.1.CertificateArn", added.getArn())
                    .when().post("/").then().statusCode(200);
            release.get().countDown();
            assertEquals(200, movingBack.get(10, TimeUnit.SECONDS).statusCode());
            given().header("Authorization", AUTH).formParam("Action", "DescribeListenerCertificates")
                    .formParam("ListenerArn", listener).when().post("/").then().statusCode(200)
                    .body("DescribeListenerCertificatesResponse.DescribeListenerCertificatesResult.Certificates.member.collect { it.CertificateArn.text() }",
                            equalTo(List.of(first.getArn(), added.getArn())));
            HttpsResult finalResult = awaitHttps(loadBalancer.dnsName(), first);
            assertEquals("original", finalResult.body());
            assertPeerCertificate(finalResult.session(), first, loadBalancer.dnsName());
            HttpsResult addedResult = awaitHttps(addedHost, added);
            assertEquals(200, addedResult.statusCode());
            assertEquals("original", addedResult.body());
            assertPeerCertificate(addedResult.session(), added, addedHost);
        } finally {
            release.get().countDown();
            worker.shutdown();
            try {
                assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS), "material worker did not stop");
            } finally {
                worker.shutdownNow();
                runtime.certificateResolver = original;
                Certificate ownedFirst = first;
                Certificate ownedAdded = added;
                assertAll("Clean all material-retry resources after success or failure",
                        () -> given().header("Authorization", AUTH).formParam("Action", "DeleteLoadBalancer")
                                .formParam("LoadBalancerArn", loadBalancer.arn()).when().post("/").then().statusCode(200),
                        () -> given().header("Authorization", AUTH).formParam("Action", "DeleteLoadBalancer")
                                .formParam("LoadBalancerArn", unrelated.arn()).when().post("/").then().statusCode(200),
                        () -> awaitListenerPortClosed(HTTPS_PORT),
                        () -> awaitListenerPortClosed(HTTP_PORT),
                        () -> {
                            if (iamPresent.get()) {
                                given().header("Authorization", accountAuthorization("test", "iam"))
                                        .formParam("Action", "DeleteServerCertificate").formParam("ServerCertificateName", iamName)
                                        .when().post("/").then().statusCode(200);
                            }
                        },
                        () -> { if (ownedFirst != null) { acmService.deleteCertificate(ownedFirst.getArn(), REGION); } },
                        () -> { if (ownedAdded != null) { acmService.deleteCertificate(ownedAdded.getArn(), REGION); } });
            }
        }
    }

    private static ElbV2DataPlane preflightBarrier(ElbV2DataPlane original, String protocol,
                                                  CountDownLatch preflight, CountDownLatch release,
                                                  AtomicReference<String> attemptedArn) {
        ElbV2DataPlane wrapped = mock(ElbV2DataPlane.class, delegatesTo(original));
        doAnswer(invocation -> {
            String arn = invocation.getArgument(0);
            int port = invocation.getArgument(1);
            String actualProtocol = invocation.getArgument(2);
            original.requireCompatibleListenerProtocol(arn, port, actualProtocol);
            if (port == HTTPS_PORT && protocol.equals(actualProtocol) && attemptedArn.compareAndSet(null, arn)) {
                preflight.countDown();
                assertTrue(release.await(30, TimeUnit.SECONDS), "preflight was not released");
            }
            return null;
        }).when(wrapped).requireCompatibleListenerProtocol(anyString(), anyInt(), anyString());
        return wrapped;
    }

    private void cleanupProtocolFixtures(LoadBalancerRef first, LoadBalancerRef winner, Certificate certificate) {
        assertAll("Remove both protocol-race fixtures even when the loser was published",
                () -> given().header("Authorization", AUTH).formParam("Action", "DeleteLoadBalancer")
                        .formParam("LoadBalancerArn", first.arn()).when().post("/").then().statusCode(200),
                () -> given().header("Authorization", AUTH).formParam("Action", "DeleteLoadBalancer")
                        .formParam("LoadBalancerArn", winner.arn()).when().post("/").then().statusCode(200),
                ElbV2HttpsDataPlaneIntegrationTest::awaitListenerPortClosed,
                () -> acmService.deleteCertificate(certificate.getArn(), REGION));
    }

    private void cleanupMissingCertificateFixture(LoadBalancerRef loadBalancer, Certificate certificate) {
        assertAll("Clean up both successful and partially published listener requests",
                () -> {
                    List<String> listeners = given().header("Authorization", AUTH)
                            .formParam("Action", "DescribeListeners").formParam("LoadBalancerArn", loadBalancer.arn())
                            .when().post("/").then().statusCode(200).extract().xmlPath()
                            .getList("DescribeListenersResponse.DescribeListenersResult.Listeners.member.ListenerArn");
                    for (String listener : listeners) {
                        given().header("Authorization", AUTH).formParam("Action", "DeleteListener")
                                .formParam("ListenerArn", listener).when().post("/").then().statusCode(200);
                    }
                    awaitListenerPortClosed();
                },
                () -> given().header("Authorization", AUTH).formParam("Action", "DeleteLoadBalancer")
                        .formParam("LoadBalancerArn", loadBalancer.arn()).when().post("/").then().statusCode(200),
                () -> acmService.deleteCertificate(certificate.getArn(), REGION));
    }

    private static void assertPlainResponse(String host, int port, String body) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2000);
            socket.setSoTimeout(2000);
            HttpResult response = request(socket, host);
            assertEquals(200, response.statusCode());
            assertEquals(body, response.body());
        }
    }

    private static void awaitPlainResponse(String host, int port, String body) {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                assertPlainResponse(host, port, body));
    }

    @Test
    @Order(1)
    void terminatesTlsWithSniSelectedAcmCertificatesAndKeepsHttpListenersPlain() throws Exception {
        LoadBalancerRef first = createLoadBalancer("https-first");
        firstLbArn = first.arn();
        Certificate firstCertificate = requestCertificate(first.dnsName());
        firstTargetGroupArn = createHealthyTargetGroup();
        firstListenerArn = createForwardListener(
                first.arn(), HTTPS_PORT, firstCertificate.getArn(), firstTargetGroupArn);

        describeHttpsListener(firstListenerArn, firstCertificate.getArn());
        awaitTargetHealthy(firstTargetGroupArn);
        HttpsResult firstResult = awaitHttps(first.dnsName(), firstCertificate);
        assertEquals(200, firstResult.statusCode());
        assertEquals("target", firstResult.body());
        assertPeerCertificate(firstResult.session(), firstCertificate, first.dnsName());

        LoadBalancerRef second = createLoadBalancer("https-second");
        secondLbArn = second.arn();
        Certificate secondCertificate = requestCertificate(second.dnsName());
        secondListenerArn = createFixedResponseListener(
                second.arn(), "HTTPS", HTTPS_PORT, secondCertificate.getArn(), "second");

        HttpsResult refreshedFirstResult = awaitHttps(first.dnsName(), firstCertificate);
        assertEquals("target", refreshedFirstResult.body());
        assertPeerCertificate(refreshedFirstResult.session(), firstCertificate, first.dnsName());

        HttpsResult secondResult = awaitHttps(second.dnsName(), secondCertificate);
        assertEquals("second", secondResult.body());
        assertPeerCertificate(secondResult.session(), secondCertificate, second.dnsName());
        HttpsResult ambiguous = awaitHttps(SHARED_CERTIFICATE_HOST, firstCertificate, secondCertificate);
        assertEquals(502, ambiguous.statusCode());
        assertEquals("No listener for host", ambiguous.body());

        LoadBalancerRef http = createLoadBalancer("http-control");
        httpLbArn = http.arn();
        httpListenerArn = createFixedResponseListener(http.arn(), "HTTP", HTTP_PORT, null, "plain");
        assertPlainResponse(http.dnsName(), HTTP_PORT, "plain");

        deleteListener(firstListenerArn);
        firstListenerArn = null;
        assertEquals("second", awaitHttps(second.dnsName(), secondCertificate).body());
        assertEquals("second", awaitHttps(SHARED_CERTIFICATE_HOST, secondCertificate).body());

        deleteListener(secondListenerArn);
        secondListenerArn = null;
        awaitListenerPortClosed();
    }

    @Test
    @Order(2)
    void iamServerCertificateTerminatesTls() throws Exception {
        LoadBalancerRef loadBalancer = createLoadBalancer("https-iam");
        String name = "elb-tls-iam-" + Long.toString(System.nanoTime(), 36);
        String listenerArn = null;
        boolean uploaded = false;
        CertificateGenerator.GeneratedCertificate pair = new CertificateGenerator().generateSelfSignedCertificate(
                loadBalancer.dnsName(), List.of(), KeyAlgorithm.RSA_2048);
        try {
            String certificateArn = given()
                    .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260720/us-east-1/iam/aws4_request")
                    .formParam("Action", "UploadServerCertificate")
                    .formParam("ServerCertificateName", name)
                    .formParam("CertificateBody", pair.certificatePem())
                    .formParam("PrivateKey", pair.privateKeyPem())
                .when().post("/").then().statusCode(200)
                    .extract().path("UploadServerCertificateResponse.UploadServerCertificateResult"
                            + ".ServerCertificateMetadata.Arn");
            uploaded = true;
            listenerArn = createFixedResponseListener(loadBalancer.arn(), "HTTPS", HTTPS_PORT, certificateArn, "iam");
            Certificate expected = new Certificate();
            expected.setCertificateBody(pair.certificatePem());
            HttpsResult result = awaitHttps(loadBalancer.dnsName(), expected);
            assertEquals(200, result.statusCode());
            assertEquals("iam", result.body());
            assertPeerCertificate(result.session(), expected, loadBalancer.dnsName());
        } finally {
            try {
                deleteListener(listenerArn);
                deleteLoadBalancer(loadBalancer.arn());
            } finally {
                if (uploaded) {
                    given()
                            .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260720/us-east-1/iam/aws4_request")
                            .formParam("Action", "DeleteServerCertificate")
                            .formParam("ServerCertificateName", name)
                        .when().post("/").then().statusCode(200);
                }
            }
        }
    }

    @Test
    @Order(3)
    void rejectedMixedProtocolChangePreservesBothHttpListeners() throws Exception {
        LoadBalancerRef first = createLoadBalancer("mixed-before-first");
        LoadBalancerRef second = createLoadBalancer("mixed-before-second");
        Certificate certificate = requestCertificate(first.dnsName());
        String firstListener = null;
        String secondListener = null;
        try {
            firstListener = createFixedResponseListener(first.arn(), "HTTP", HTTP_PORT, null, "first");
            secondListener = createFixedResponseListener(second.arn(), "HTTP", HTTP_PORT, null, "second");
            assertPlainResponse(first.dnsName(), HTTP_PORT, "first");
            assertPlainResponse(second.dnsName(), HTTP_PORT, "second");
            Response rejected = given().header("Authorization", AUTH)
                    .formParam("Action", "ModifyListener").formParam("ListenerArn", firstListener)
                    .formParam("Protocol", "HTTPS")
                    .formParam("Certificates.member.1.CertificateArn", certificate.getArn())
                    .when().post("/");
            assertTrue(rejected.statusCode() >= 400);
            given().header("Authorization", AUTH).formParam("Action", "DescribeListeners")
                    .formParam("ListenerArns.member.1", firstListener).when().post("/")
                    .then().statusCode(200)
                    .body("DescribeListenersResponse.DescribeListenersResult.Listeners.member.Protocol", equalTo("HTTP"));
            assertPlainResponse(first.dnsName(), HTTP_PORT, "first");
            assertPlainResponse(second.dnsName(), HTTP_PORT, "second");
        } finally {
            deleteListener(firstListener);
            deleteListener(secondListener);
            deleteLoadBalancer(first.arn());
            deleteLoadBalancer(second.arn());
            acmService.deleteCertificate(certificate.getArn(), REGION);
        }
    }

    @Test
    @Order(4)
    void rejectedDefaultCertificateRemovalPreservesTlsListener() throws Exception {
        LoadBalancerRef loadBalancer = createLoadBalancer("remove-default-before");
        Certificate certificate = requestCertificate(loadBalancer.dnsName());
        String listener = null;
        try {
            listener = createFixedResponseListener(loadBalancer.arn(), "HTTPS", HTTPS_PORT,
                    certificate.getArn(), "retained");
            HttpsResult before = awaitHttps(loadBalancer.dnsName(), certificate);
            assertEquals("retained", before.body());
            assertPeerCertificate(before.session(), certificate, loadBalancer.dnsName());
            Response rejected = given().header("Authorization", AUTH)
                    .formParam("Action", "RemoveListenerCertificates").formParam("ListenerArn", listener)
                    .formParam("Certificates.member.1.CertificateArn", certificate.getArn())
                    .when().post("/");
            assertEquals(400, rejected.statusCode());
            assertEquals("OperationNotPermitted", rejected.xmlPath().getString("ErrorResponse.Error.Code"));
            given().header("Authorization", AUTH).formParam("Action", "DescribeListenerCertificates")
                    .formParam("ListenerArn", listener).when().post("/").then().statusCode(200)
                    .body("DescribeListenerCertificatesResponse.DescribeListenerCertificatesResult.Certificates.member.CertificateArn",
                            equalTo(certificate.getArn()));
            HttpsResult after = awaitHttps(loadBalancer.dnsName(), certificate);
            assertEquals("retained", after.body());
            assertPeerCertificate(after.session(), certificate, loadBalancer.dnsName());
        } finally {
            deleteListener(listener);
            deleteLoadBalancer(loadBalancer.arn());
            acmService.deleteCertificate(certificate.getArn(), REGION);
        }
    }

    @Test
    @Order(5)
    void rejectedMixedProtocolCreationLeavesNoListenerAndCanRetry() throws Exception {
        LoadBalancerRef first = null;
        LoadBalancerRef second = null;
        Certificate certificate = null;
        String firstListener = null;
        String retryListener = null;
        try {
            first = createLoadBalancer("create-mixed-first");
            second = createLoadBalancer("create-mixed-second");
            certificate = requestCertificate(second.dnsName());
            firstListener = createFixedResponseListener(first.arn(), "HTTP", HTTP_PORT, null, "first");
            assertPlainResponse(first.dnsName(), HTTP_PORT, "first");
            Response refused = given().header("Authorization", AUTH)
                    .formParam("Action", "CreateListener").formParam("LoadBalancerArn", second.arn())
                    .formParam("Protocol", "HTTPS").formParam("Port", HTTP_PORT)
                    .formParam("Certificates.member.1.CertificateArn", certificate.getArn())
                    .formParam("DefaultActions.member.1.Type", "fixed-response")
                    .formParam("DefaultActions.member.1.FixedResponseConfig.StatusCode", "200")
                    .formParam("DefaultActions.member.1.FixedResponseConfig.MessageBody", "second")
                    .when().post("/");
            assertTrue(refused.statusCode() >= 400);
            List<String> remaining = given().header("Authorization", AUTH)
                    .formParam("Action", "DescribeListeners").formParam("LoadBalancerArn", second.arn())
                    .when().post("/").then().statusCode(200).extract().xmlPath()
                    .getList("DescribeListenersResponse.DescribeListenersResult.Listeners.member.ListenerArn");
            assertTrue(remaining.isEmpty());
            assertPlainResponse(first.dnsName(), HTTP_PORT, "first");
            retryListener = createFixedResponseListener(second.arn(), "HTTPS", HTTPS_PORT,
                    certificate.getArn(), "retry");
            HttpsResult retry = awaitHttps(second.dnsName(), certificate);
            assertEquals("retry", retry.body());
            assertPeerCertificate(retry.session(), certificate, second.dnsName());
        } finally {
            deleteListener(firstListener);
            deleteListener(retryListener);
            if (first != null) {
                deleteLoadBalancer(first.arn());
            }
            if (second != null) {
                deleteLoadBalancer(second.arn());
            }
            if (certificate != null) {
                acmService.deleteCertificate(certificate.getArn(), REGION);
            }
        }
    }

    @Test
    @Order(6)
    void reimportedCertificateUsesOneSnapshotAcrossSharedPortListeners() throws Exception {
        LoadBalancerRef first = null;
        LoadBalancerRef second = null;
        Certificate current = null;
        String firstListener = null;
        String secondListener = null;
        try {
            first = createLoadBalancer("same-material-first");
            second = createLoadBalancer("same-material-second");
            CertificateGenerator generator = new CertificateGenerator();
            CertificateGenerator.GeneratedCertificate original = generator.generateSelfSignedCertificate(
                    first.dnsName(), List.of(), KeyAlgorithm.RSA_2048);
            current = acmService.importCertificate(original.certificatePem(), original.privateKeyPem(),
                    null, null, Map.of(), REGION);
            String arn = current.getArn();
            Certificate originalTrusted = current;
            firstListener = createFixedResponseListener(first.arn(), "HTTPS", HTTPS_PORT, arn, "first");
            HttpsResult initial = awaitHttps(first.dnsName(), current);
            assertEquals("first", initial.body());
            assertPeerCertificate(initial.session(), current, first.dnsName());
            CertificateGenerator.GeneratedCertificate replacement = generator.generateSelfSignedCertificate(
                    first.dnsName(), List.of(second.dnsName(), SHARED_CERTIFICATE_HOST), KeyAlgorithm.RSA_2048);
            current = acmService.importCertificate(replacement.certificatePem(), replacement.privateKeyPem(),
                    null, arn, Map.of(), REGION);
            assertEquals(arn, current.getArn());
            // A fresh handshake must see replacement material before another ELB publication.
            awaitHttpsMaterial(first.dnsName(), 200, "first", current, current, originalTrusted);
            secondListener = createFixedResponseListener(second.arn(), "HTTPS", HTTPS_PORT, arn, "second");
            awaitHttpsMaterial(first.dnsName(), 200, "first", current, current, originalTrusted);
            HttpsResult shared = awaitHttps(SHARED_CERTIFICATE_HOST, current);
            assertEquals(502, shared.statusCode());
            assertEquals("No listener for host", shared.body());
            assertPeerCertificate(shared.session(), current, SHARED_CERTIFICATE_HOST);
            HttpsResult firstResult = awaitHttps(first.dnsName(), current);
            assertEquals("first", firstResult.body());
            assertPeerCertificate(firstResult.session(), current, first.dnsName());
            HttpsResult secondResult = awaitHttps(second.dnsName(), current);
            assertEquals("second", secondResult.body());
            assertPeerCertificate(secondResult.session(), current, second.dnsName());
            deleteListener(secondListener);
            secondListener = null;
            awaitHttpsMaterial(SHARED_CERTIFICATE_HOST, 200, "first", current, current, originalTrusted);
        } finally {
            deleteListener(firstListener);
            deleteListener(secondListener);
            if (first != null) {
                deleteLoadBalancer(first.arn());
            }
            if (second != null) {
                deleteLoadBalancer(second.arn());
            }
            if (current != null) {
                acmService.deleteCertificate(current.getArn(), REGION);
            }
        }
    }

    @Test
    @Order(7)
    void cnOnlyAdditionalCertificateRoutesAfterASanCertificate() throws Exception {
        LoadBalancerRef loadBalancer = createLoadBalancer("https-cn-only");
        Certificate sanCertificate = null;
        Certificate cnCertificate = null;
        Certificate unrelatedCertificate = null;
        LoadBalancerRef unrelated = null;
        String unrelatedListener = null;
        String listenerArn = null;
        String cnHost = "cn-only.elb.localhost.localstack.cloud";
        try {
            CertificateGenerator generator = new CertificateGenerator();
            CertificateGenerator.GeneratedCertificate san = generator.generateSelfSignedCertificate(
                    loadBalancer.dnsName(), List.of(), KeyAlgorithm.RSA_2048);
            CertificateGenerator.GeneratedCertificate cn = generator.generateCaCertificate(cnHost);
            assertNull(parseCertificate(cn.certificatePem()).getSubjectAlternativeNames());
            assertTrue(parseCertificate(san.certificatePem()).getSubjectAlternativeNames().stream()
                    .anyMatch(name -> loadBalancer.dnsName().equals(name.get(1))));
            sanCertificate = acmService.importCertificate(san.certificatePem(), san.privateKeyPem(),
                    null, null, Map.of(), REGION);
            cnCertificate = acmService.importCertificate(cn.certificatePem(), cn.privateKeyPem(),
                    null, null, Map.of(), REGION);
            listenerArn = createFixedResponseListener(loadBalancer.arn(), "HTTPS", HTTPS_PORT,
                    sanCertificate.getArn(), "cn-route");
            HttpsResult original = awaitHttps(loadBalancer.dnsName(), sanCertificate);
            assertEquals(200, original.statusCode());
            assertEquals("cn-route", original.body());
            assertPeerCertificate(original.session(), sanCertificate, loadBalancer.dnsName());
            unrelated = createLoadBalancer("https-cn-control");
            unrelatedCertificate = requestCertificate(unrelated.dnsName());
            unrelatedListener = createFixedResponseListener(unrelated.arn(), "HTTPS", HTTPS_PORT,
                    unrelatedCertificate.getArn(), "unrelated-route");
            HttpsResult unrelatedBefore = awaitHttps(unrelated.dnsName(), unrelatedCertificate);
            assertEquals(200, unrelatedBefore.statusCode());
            assertEquals("unrelated-route", unrelatedBefore.body());
            assertPeerCertificate(unrelatedBefore.session(), unrelatedCertificate, unrelated.dnsName());
            given().header("Authorization", AUTH)
                    .formParam("Action", "AddListenerCertificates").formParam("ListenerArn", listenerArn)
                    .formParam("Certificates.member.1.CertificateArn", cnCertificate.getArn())
                    .when().post("/").then().statusCode(200);

            HttpsResult additional = awaitHttps(cnHost, cnCertificate);
            assertEquals(200, additional.statusCode());
            assertEquals("cn-route", additional.body());
            X509Certificate peer = assertInstanceOf(X509Certificate.class,
                    additional.session().getPeerCertificates()[0]);
            assertEquals(fingerprint(parseCertificate(cnCertificate.getCertificateBody())), fingerprint(peer));
            assertNull(peer.getSubjectAlternativeNames());
            HttpsResult retained = awaitHttps(loadBalancer.dnsName(), sanCertificate);
            assertEquals(200, retained.statusCode());
            assertEquals("cn-route", retained.body());
            assertPeerCertificate(retained.session(), sanCertificate, loadBalancer.dnsName());
            HttpsResult unrelatedAfter = awaitHttps(unrelated.dnsName(), unrelatedCertificate);
            assertEquals(200, unrelatedAfter.statusCode());
            assertEquals("unrelated-route", unrelatedAfter.body());
            assertPeerCertificate(unrelatedAfter.session(), unrelatedCertificate, unrelated.dnsName());
        } finally {
            deleteListener(unrelatedListener);
            deleteListener(listenerArn);
            if (unrelated != null) {
                deleteLoadBalancer(unrelated.arn());
            }
            if (unrelatedCertificate != null) {
                acmService.deleteCertificate(unrelatedCertificate.getArn(), REGION);
            }
            deleteLoadBalancer(loadBalancer.arn());
            if (sanCertificate != null) {
                acmService.deleteCertificate(sanCertificate.getArn(), REGION);
            }
            if (cnCertificate != null) {
                acmService.deleteCertificate(cnCertificate.getArn(), REGION);
            }
        }
    }

    @Test
    @Order(8)
    void anEstablishedTlsConnectionSurvivesCertificateReplacement() throws Exception {
        LoadBalancerRef loadBalancer = createLoadBalancer("https-keepalive");
        Certificate original = null;
        Certificate replacement = null;
        String listener = null;
        try {
            original = requestCertificate(loadBalancer.dnsName());
            replacement = requestCertificate(loadBalancer.dnsName());
            assertNotEquals(fingerprint(parseCertificate(original.getCertificateBody())),
                    fingerprint(parseCertificate(replacement.getCertificateBody())));
            listener = createFixedResponseListener(loadBalancer.arn(), "HTTPS", HTTPS_PORT,
                    original.getArn(), "before");
            HttpsResult before = awaitHttps(loadBalancer.dnsName(), original);
            assertEquals(200, before.statusCode());
            assertEquals("before", before.body());
            assertPeerCertificate(before.session(), original, loadBalancer.dnsName());
            try (SSLSocket socket = openTlsConnection(loadBalancer.dnsName(), HTTPS_PORT,
                    original, replacement)) {
                assertPeerCertificate(socket.getSession(), original, loadBalancer.dnsName());
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.UTF_8));
                assertPersistentResponse(socket, reader, loadBalancer.dnsName(), "before");
                given().header("Authorization", AUTH)
                        .formParam("Action", "ModifyListener").formParam("ListenerArn", listener)
                        .formParam("Certificates.member.1.CertificateArn", replacement.getArn())
                        .formParam("DefaultActions.member.1.Type", "fixed-response")
                        .formParam("DefaultActions.member.1.FixedResponseConfig.StatusCode", "200")
                        .formParam("DefaultActions.member.1.FixedResponseConfig.ContentType", "text/plain")
                        .formParam("DefaultActions.member.1.FixedResponseConfig.MessageBody", "after")
                        .when().post("/").then().statusCode(200);
                awaitHttpsMaterial(loadBalancer.dnsName(), 200, "after", replacement, original, replacement);
                assertPersistentResponse(socket, reader, loadBalancer.dnsName(), "after");
                assertPeerCertificate(socket.getSession(), original, loadBalancer.dnsName());
                assertPersistentResponse(socket, reader, loadBalancer.dnsName(), "after");
            }
        } finally {
            String ownedListener = listener;
            Certificate ownedOriginal = original;
            Certificate ownedReplacement = replacement;
            assertAll("Delete all keep-alive fixture resources",
                    () -> {
                        if (ownedListener != null) {
                            given().header("Authorization", AUTH).formParam("Action", "DeleteListener")
                                    .formParam("ListenerArn", ownedListener).when().post("/").then().statusCode(200);
                        }
                    },
                    () -> given().header("Authorization", AUTH).formParam("Action", "DeleteLoadBalancer")
                            .formParam("LoadBalancerArn", loadBalancer.arn()).when().post("/").then().statusCode(200),
                    () -> {
                        if (ownedOriginal != null) {
                            acmService.deleteCertificate(ownedOriginal.getArn(), REGION);
                        }
                    },
                    () -> {
                        if (ownedReplacement != null) {
                            acmService.deleteCertificate(ownedReplacement.getArn(), REGION);
                        }
                    });
        }
    }

    private static void assertPersistentResponse(SSLSocket socket, BufferedReader reader, String host, String body)
            throws IOException {
        String request = "GET / HTTP/1.1\r\nHost: " + host + "\r\nConnection: keep-alive\r\n\r\n";
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        String status = reader.readLine();
        assertTrue(status != null && status.startsWith("HTTP/1.1 200 "), "Unexpected HTTP status: " + status);
        int length = -1;
        String header;
        while ((header = reader.readLine()) != null && !header.isEmpty()) {
            String[] parts = header.split(":", 2);
            if (parts.length == 2 && parts[0].equalsIgnoreCase("Content-Length")) {
                length = Integer.parseInt(parts[1].trim());
            }
        }
        assertEquals(body.length(), length, "The fixed ASCII response must be length-delimited");
        char[] received = new char[length];
        int offset = 0;
        while (offset < length) {
            int read = reader.read(received, offset, length - offset);
            if (read < 0) {
                throw new EOFException("Connection closed before the response body completed");
            }
            offset += read;
        }
        assertEquals(body, new String(received));
    }

    @Test
    @Order(9)
    void iamMaterialBelongsToTheListenerAccountAfterTheRequestEnds() throws Exception {
        String owner = "111111111111";
        String foreign = "222222222222";
        String name = "elb-tls-owner-" + Long.toString(System.nanoTime(), 36);
        LoadBalancerRef loadBalancer = null;
        String listener = null;
        String ownerCertificate = null;
        String foreignCertificate = null;
        try {
            Response created = given().header("Authorization", accountAuthorization(owner, "elasticloadbalancing"))
                    .formParam("Action", "CreateLoadBalancer").formParam("Name", "https-scoped-iam")
                    .formParam("Type", "application")
                    .formParam("Subnets.member.1", Ec2Service.defaultSubnetId(REGION, "a"))
                    .formParam("Subnets.member.2", Ec2Service.defaultSubnetId(REGION, "b"))
                    .when().post("/").then().statusCode(200).extract().response();
            loadBalancer = new LoadBalancerRef(
                    created.xmlPath().getString("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn"),
                    created.xmlPath().getString("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.DNSName"));
            assertTrue(loadBalancer.arn().contains(":" + owner + ":"));
            CertificateGenerator generator = new CertificateGenerator();
            CertificateGenerator.GeneratedCertificate first = generator.generateSelfSignedCertificate(
                    loadBalancer.dnsName(), List.of(), KeyAlgorithm.RSA_2048);
            CertificateGenerator.GeneratedCertificate other = generator.generateSelfSignedCertificate(
                    loadBalancer.dnsName(), List.of(), KeyAlgorithm.RSA_2048);
            Certificate expected = new Certificate();
            expected.setCertificateBody(first.certificatePem());
            Certificate foreignExpected = new Certificate();
            foreignExpected.setCertificateBody(other.certificatePem());
            assertNotEquals(fingerprint(parseCertificate(first.certificatePem())),
                    fingerprint(parseCertificate(other.certificatePem())));
            ownerCertificate = uploadIamCertificate(owner, name, first);
            foreignCertificate = uploadIamCertificate(foreign, name, other);
            assertTrue(ownerCertificate.contains(":" + owner + ":"));
            assertTrue(foreignCertificate.contains(":" + foreign + ":"));
            listener = given().header("Authorization", accountAuthorization(owner, "elasticloadbalancing"))
                    .formParam("Action", "CreateListener").formParam("LoadBalancerArn", loadBalancer.arn())
                    .formParam("Protocol", "HTTPS").formParam("Port", HTTPS_PORT)
                    .formParam("Certificates.member.1.CertificateArn", ownerCertificate)
                    .formParam("DefaultActions.member.1.Type", "fixed-response")
                    .formParam("DefaultActions.member.1.FixedResponseConfig.StatusCode", "200")
                    .formParam("DefaultActions.member.1.FixedResponseConfig.ContentType", "text/plain")
                    .formParam("DefaultActions.member.1.FixedResponseConfig.MessageBody", "scoped")
                    .when().post("/").then().statusCode(200).extract()
                    .path("CreateListenerResponse.CreateListenerResult.Listeners.member.ListenerArn");
            assertTrue(listener.contains(":" + owner + ":"));
            awaitHttpsMaterial(loadBalancer.dnsName(), 200, "scoped", expected, expected, foreignExpected);
            given().header("Authorization", accountAuthorization(owner, "elasticloadbalancing"))
                    .formParam("Action", "ModifyListener").formParam("ListenerArn", listener)
                    .formParam("Certificates.member.1.CertificateArn", foreignCertificate)
                    .when().post("/").then().statusCode(400)
                    .body("ErrorResponse.Error.Code", equalTo("CertificateNotFound"));
            given().header("Authorization", accountAuthorization(owner, "elasticloadbalancing"))
                    .formParam("Action", "DescribeListenerCertificates").formParam("ListenerArn", listener)
                    .when().post("/").then().statusCode(200)
                    .body("DescribeListenerCertificatesResponse.DescribeListenerCertificatesResult.Certificates.member.CertificateArn",
                            equalTo(ownerCertificate));
            awaitHttpsMaterial(loadBalancer.dnsName(), 200, "scoped", expected, expected, foreignExpected);
        } finally {
            String ownedListener = listener;
            LoadBalancerRef ownedBalancer = loadBalancer;
            String ownedCertificate = ownerCertificate;
            String otherCertificate = foreignCertificate;
            assertAll("Delete all scoped IAM TLS fixture resources",
                    () -> {
                        if (ownedListener != null) {
                            given().header("Authorization", accountAuthorization(owner, "elasticloadbalancing"))
                                    .formParam("Action", "DeleteListener").formParam("ListenerArn", ownedListener)
                                    .when().post("/").then().statusCode(200);
                        }
                    },
                    () -> {
                        if (ownedBalancer != null) {
                            given().header("Authorization", accountAuthorization(owner, "elasticloadbalancing"))
                                    .formParam("Action", "DeleteLoadBalancer").formParam("LoadBalancerArn", ownedBalancer.arn())
                                    .when().post("/").then().statusCode(200);
                        }
                    },
                    () -> {
                        if (ownedCertificate != null) {
                            given().header("Authorization", accountAuthorization(owner, "iam"))
                                    .formParam("Action", "DeleteServerCertificate").formParam("ServerCertificateName", name)
                                    .when().post("/").then().statusCode(200);
                        }
                    },
                    () -> {
                        if (otherCertificate != null) {
                            given().header("Authorization", accountAuthorization(foreign, "iam"))
                                    .formParam("Action", "DeleteServerCertificate").formParam("ServerCertificateName", name)
                                    .when().post("/").then().statusCode(200);
                        }
                    });
        }
    }

    private static String accountAuthorization(String account, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20260720/" + REGION + "/" + service + "/aws4_request";
    }

    private static String uploadIamCertificate(String account, String name, CertificateGenerator.GeneratedCertificate pair) {
        return given().header("Authorization", accountAuthorization(account, "iam"))
                .formParam("Action", "UploadServerCertificate").formParam("ServerCertificateName", name)
                .formParam("CertificateBody", pair.certificatePem()).formParam("PrivateKey", pair.privateKeyPem())
                .when().post("/").then().statusCode(200).extract()
                .path("UploadServerCertificateResponse.UploadServerCertificateResult.ServerCertificateMetadata.Arn");
    }

    @Test
    @Order(Integer.MAX_VALUE - 1)
    void identicalCertificateUpdateRecoversAfterAnActualSslRefreshFailure() throws Exception {
        ElbV2DataPlane runtime = ClientProxy.unwrap(dataPlane);
        CertificateReferenceResolver originalResolver = runtime.certificateResolver;
        CertificateReferenceResolver wrapped = mock(CertificateReferenceResolver.class, delegatesTo(originalResolver));
        AtomicInteger updates = new AtomicInteger();
        AtomicReference<String> replacementArn = new AtomicReference<>();
        AtomicReference<CertificateMaterial> failedMaterial = new AtomicReference<>();
        doAnswer(call -> {
            CertificateMaterial material = originalResolver.publishedMaterial(
                    call.getArgument(0), call.getArgument(1), call.getArgument(2));
            if (material.arn().equals(replacementArn.get())) {
                int read = updates.incrementAndGet();
                if (read == 1) {
                    CertificateMaterial invalid = InlineMocks.inlineSpy(material);
                    // Let pair validation succeed, then fail the key-manager load.
                    AtomicInteger keyReads = new AtomicInteger();
                    doAnswer(ignored -> keyReads.incrementAndGet() == 1 ? material.privateKey() : "not a PEM private key")
                            .when(invalid).privateKey();
                    failedMaterial.set(invalid);
                }
                if (read <= 2) {
                    return failedMaterial.get();
                }
            }
            return material;
        }).when(wrapped).publishedMaterial(anyString(), anyString(), anyString());
        LoadBalancerRef loadBalancer = createLoadBalancer("https-refresh-retry");
        Certificate original = null;
        Certificate replacement = null;
        String listener = null;
        try {
            runtime.certificateResolver = wrapped;
            original = requestCertificate(loadBalancer.dnsName());
            replacement = requestCertificate(loadBalancer.dnsName());
            replacementArn.set(replacement.getArn());
            assertNotEquals(fingerprint(parseCertificate(original.getCertificateBody())),
                    fingerprint(parseCertificate(replacement.getCertificateBody())));
            listener = createFixedResponseListener(loadBalancer.arn(), "HTTPS", HTTPS_PORT,
                    original.getArn(), "retry");
            awaitHttpsMaterial(loadBalancer.dnsName(), 200, "retry", original, original, replacement);
            for (int attempt = 0; attempt < 2; attempt++) {
                given().header("Authorization", AUTH).formParam("Action", "ModifyListener")
                        .formParam("ListenerArn", listener)
                        .formParam("Certificates.member.1.CertificateArn", replacement.getArn())
                        .when().post("/").then().statusCode(200);
                if (attempt == 0) {
                    assertEquals(2, updates.get(), "the failed snapshot must pass the publication recheck");
                    awaitHttpsMaterial(loadBalancer.dnsName(), 200, "retry", original, original, replacement);
                } else {
                    awaitHttpsMaterial(loadBalancer.dnsName(), 200, "retry", replacement, original, replacement);
                }
            }
            assertEquals(4, updates.get(), "both API attempts must resolve and recheck their certificate snapshot");
        } finally {
            runtime.certificateResolver = originalResolver;
            String ownedListener = listener;
            Certificate ownedOriginal = original;
            Certificate ownedReplacement = replacement;
            assertAll("Delete all failed-refresh fixture resources",
                    () -> {
                        if (ownedListener != null) {
                            deleteListener(ownedListener);
                            awaitListenerPortClosed();
                        }
                    },
                    () -> deleteLoadBalancer(loadBalancer.arn()),
                    () -> {
                        if (ownedOriginal != null) {
                            acmService.deleteCertificate(ownedOriginal.getArn(), REGION);
                        }
                    },
                    () -> {
                        if (ownedReplacement != null) {
                            acmService.deleteCertificate(ownedReplacement.getArn(), REGION);
                        }
                    });
        }
    }

    @Test
    @Order(Integer.MAX_VALUE)
    void storedIamReferencesReapplyFormerThenCurrentMaterial() throws Exception {
        LoadBalancerRef loadBalancer = createLoadBalancer("https-stored-iam");
        String name = "elb-tls-stored-" + Long.toString(System.nanoTime(), 36);
        String renamed = name + "-renamed";
        String originalName = name;
        String originalArn = null;
        String replacementArn = null;
        String listener = null;
        try {
            CertificateGenerator generator = new CertificateGenerator();
            CertificateGenerator.GeneratedCertificate first = generator.generateSelfSignedCertificate(
                    loadBalancer.dnsName(), List.of(), KeyAlgorithm.RSA_2048);
            CertificateGenerator.GeneratedCertificate second = generator.generateSelfSignedCertificate(
                    loadBalancer.dnsName(), List.of(), KeyAlgorithm.RSA_2048);
            Certificate before = new Certificate();
            before.setCertificateBody(first.certificatePem());
            Certificate after = new Certificate();
            after.setCertificateBody(second.certificatePem());
            assertNotEquals(fingerprint(parseCertificate(first.certificatePem())),
                    fingerprint(parseCertificate(second.certificatePem())));
            originalArn = uploadIamCertificate("test", name, first);
            listener = createFixedResponseListener(loadBalancer.arn(), "HTTPS", HTTPS_PORT, originalArn, "stored");
            awaitHttpsMaterial(loadBalancer.dnsName(), 200, "stored", before, before, after);
            given().header("Authorization", accountAuthorization("test", "iam"))
                    .formParam("Action", "UpdateServerCertificate").formParam("ServerCertificateName", name)
                    .formParam("NewServerCertificateName", renamed).when().post("/").then().statusCode(200);
            originalName = renamed;
            // UpdateServerCertificate has no result metadata; read the canonical record explicitly.
            String renamedArn = given().header("Authorization", accountAuthorization("test", "iam"))
                    .formParam("Action", "GetServerCertificate").formParam("ServerCertificateName", renamed)
                    .when().post("/").then().statusCode(200).extract()
                    .path("GetServerCertificateResponse.GetServerCertificateResult.ServerCertificate.ServerCertificateMetadata.Arn");
            assertNotEquals(originalArn, renamedArn);
            given().header("Authorization", AUTH).formParam("Action", "DescribeListenerCertificates")
                    .formParam("ListenerArn", listener).when().post("/").then().statusCode(200)
                    .body("DescribeListenerCertificatesResponse.DescribeListenerCertificatesResult.Certificates.member.CertificateArn",
                            equalTo(originalArn));
            assertTrue(dataPlane.removeListener(listener, HTTPS_PORT, () -> true));
            awaitListenerPortClosed();
            elbV2Service.restorePersistedRuntime();
            awaitHttpsMaterial(loadBalancer.dnsName(), 200, "stored", before, before, after);
            replacementArn = uploadIamCertificate("test", name, second);
            assertEquals(originalArn, replacementArn);
            assertTrue(dataPlane.removeListener(listener, HTTPS_PORT, () -> true));
            awaitListenerPortClosed();
            elbV2Service.restorePersistedRuntime();
            awaitHttpsMaterial(loadBalancer.dnsName(), 200, "stored", after, before, after);
            given().header("Authorization", AUTH).formParam("Action", "DescribeListenerCertificates")
                    .formParam("ListenerArn", listener).when().post("/").then().statusCode(200)
                    .body("DescribeListenerCertificatesResponse.DescribeListenerCertificatesResult.Certificates.member.CertificateArn",
                            equalTo(originalArn));
        } finally {
            String ownedListener = listener;
            String ownedOriginalName = originalName;
            String ownedOriginal = originalArn;
            String ownedReplacement = replacementArn;
            assertAll("Delete stored IAM TLS fixture resources",
                    () -> {
                        if (ownedListener != null) {
                            given().header("Authorization", AUTH).formParam("Action", "DeleteListener")
                                    .formParam("ListenerArn", ownedListener).when().post("/").then().statusCode(200);
                        }
                    },
                    () -> given().header("Authorization", AUTH).formParam("Action", "DeleteLoadBalancer")
                            .formParam("LoadBalancerArn", loadBalancer.arn()).when().post("/").then().statusCode(200),
                    () -> {
                        if (ownedOriginal != null) {
                            given().header("Authorization", accountAuthorization("test", "iam"))
                                    .formParam("Action", "DeleteServerCertificate").formParam("ServerCertificateName", ownedOriginalName)
                                    .when().post("/").then().statusCode(200);
                        }
                    },
                    () -> {
                        if (ownedReplacement != null) {
                            given().header("Authorization", accountAuthorization("test", "iam"))
                                    .formParam("Action", "DeleteServerCertificate").formParam("ServerCertificateName", name)
                                    .when().post("/").then().statusCode(200);
                        }
                    });
        }
    }

    private static void awaitListenerPortClosed() {
        awaitListenerPortClosed(HTTPS_PORT);
    }

    private static void awaitListenerPortClosed(int port) {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            try (Socket socket = new Socket()) {
                IOException failure = assertThrows(IOException.class,
                        () -> socket.connect(new InetSocketAddress("127.0.0.1", port), 1000));
                assertTrue(hasCause(failure, ConnectException.class), failure.toString());
            }
        });
    }

    @Test
    @Order(Integer.MAX_VALUE - 2)
    void cleanupResources() {
        deleteListener(firstListenerArn);
        deleteListener(secondListenerArn);
        deleteListener(httpListenerArn);
        deleteLoadBalancer(firstLbArn);
        deleteLoadBalancer(secondLbArn);
        deleteLoadBalancer(httpLbArn);
        deleteTargetGroup(firstTargetGroupArn);
    }

    @Test
    @Order(10)
    void attachedExportableCertificateRefusesRevocationWithoutChangingHttps() {
        LoadBalancerRef loadBalancer = createLoadBalancer("tls-revoke-guard");
        Certificate certificate = acmService.requestCertificate(loadBalancer.dnsName(), List.of(), ValidationMethod.DNS,
                null, KeyAlgorithm.RSA_2048, null, new CertificateOptions(null, "ENABLED"), Map.of(), REGION);
        String listener = null;
        try {
            listener = createFixedResponseListener(loadBalancer.arn(), "HTTPS", HTTPS_PORT, certificate.getArn(), "revocation");
            awaitHttpsMaterial(loadBalancer.dnsName(), 200, "revocation", certificate, certificate);
            Response refusal = given().header("X-Amz-Target", "CertificateManager.RevokeCertificate")
                    .header("Authorization", AUTH.replace("elasticloadbalancing", "acm"))
                    .contentType("application/x-amz-json-1.1")
                    .body(Map.of("CertificateArn", certificate.getArn(), "RevocationReason", "KEY_COMPROMISE"))
                    .post("/");
            assertAll(
                    () -> assertEquals(400, refusal.statusCode()),
                    () -> assertTrue(String.valueOf(refusal.jsonPath().getString("__type")).endsWith("ResourceInUseException")),
                    () -> assertEquals(CertificateStatus.ISSUED,
                            acmService.getCertificate(certificate.getArn(), REGION).getStatus()),
                    () -> awaitHttpsMaterial(loadBalancer.dnsName(), 200, "revocation", certificate, certificate));
            deleteListener(listener);
            listener = null;
            given().header("X-Amz-Target", "CertificateManager.RevokeCertificate")
                    .header("Authorization", AUTH.replace("elasticloadbalancing", "acm"))
                    .contentType("application/x-amz-json-1.1")
                    .body(Map.of("CertificateArn", certificate.getArn(), "RevocationReason", "KEY_COMPROMISE"))
                    .post("/").then().statusCode(200);
            assertEquals(CertificateStatus.REVOKED,
                    acmService.getCertificate(certificate.getArn(), REGION).getStatus());
        } finally {
            deleteListener(listener);
            deleteLoadBalancer(loadBalancer.arn());
            acmService.deleteCertificate(certificate.getArn(), REGION);
        }
    }

    private Certificate requestCertificate(String domainName) {
        return acmService.requestCertificate(
                domainName,
                List.of(SHARED_CERTIFICATE_HOST),
                ValidationMethod.DNS,
                null,
                KeyAlgorithm.RSA_2048,
                null,
                CertificateOptions.defaultOptions(),
                Map.of("purpose", "elb-tls-test"),
                REGION);
    }

    private static LoadBalancerRef createLoadBalancer(String name) {
        Response response = given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", name)
                .formParam("Type", "application")
                .formParam("Subnets.member.1", Ec2Service.defaultSubnetId(REGION, "a"))
                .formParam("Subnets.member.2", Ec2Service.defaultSubnetId(REGION, "b"))
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .response();
        return new LoadBalancerRef(
                response.path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn"),
                response.path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.DNSName"));
    }

    private static String createFixedResponseListener(
            String lbArn, String protocol, int port, String certificateArn, String body) {
        RequestSpecification request = given()
                .formParam("Action", "CreateListener")
                .formParam("LoadBalancerArn", lbArn)
                .formParam("Protocol", protocol)
                .formParam("Port", String.valueOf(port))
                .formParam("DefaultActions.member.1.Type", "fixed-response")
                .formParam("DefaultActions.member.1.FixedResponseConfig.StatusCode", "200")
                .formParam("DefaultActions.member.1.FixedResponseConfig.ContentType", "text/plain")
                .formParam("DefaultActions.member.1.FixedResponseConfig.MessageBody", body)
                .header("Authorization", AUTH);
        if (certificateArn != null) {
            request.formParam("SslPolicy", "ELBSecurityPolicy-TLS13-1-2-2021-06")
                    .formParam("Certificates.member.1.CertificateArn", certificateArn);
        }
        return request
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateListenerResponse.CreateListenerResult.Listeners.member.ListenerArn");
    }

    private static String createHealthyTargetGroup() {
        int targetPort = targetServer.getAddress().getPort();
        String targetGroupArn = given()
                .formParam("Action", "CreateTargetGroup")
                .formParam("Name", "https-target")
                .formParam("Protocol", "HTTP")
                .formParam("Port", String.valueOf(targetPort))
                .formParam("VpcId", "vpc-tls-test")
                .formParam("TargetType", "ip")
                .formParam("HealthCheckProtocol", "HTTP")
                .formParam("HealthCheckPath", "/")
                .formParam("HealthCheckIntervalSeconds", "5")
                .formParam("HealthyThresholdCount", "1")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetGroupArn");

        given()
                .formParam("Action", "RegisterTargets")
                .formParam("TargetGroupArn", targetGroupArn)
                .formParam("Targets.member.1.Id", "127.0.0.1")
                .formParam("Targets.member.1.Port", String.valueOf(targetPort))
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);
        return targetGroupArn;
    }

    private static String createForwardListener(
            String lbArn, int port, String certificateArn, String targetGroupArn) {
        return given()
                .formParam("Action", "CreateListener")
                .formParam("LoadBalancerArn", lbArn)
                .formParam("Protocol", "HTTPS")
                .formParam("Port", String.valueOf(port))
                .formParam("SslPolicy", "ELBSecurityPolicy-TLS13-1-2-2021-06")
                .formParam("Certificates.member.1.CertificateArn", certificateArn)
                .formParam("DefaultActions.member.1.Type", "forward")
                .formParam("DefaultActions.member.1.TargetGroupArn", targetGroupArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateListenerResponse.CreateListenerResult.Listeners.member.ListenerArn");
    }

    private static void awaitTargetHealthy(String targetGroupArn) {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                given()
                        .formParam("Action", "DescribeTargetHealth")
                        .formParam("TargetGroupArn", targetGroupArn)
                        .header("Authorization", AUTH)
                    .when()
                        .post("/")
                    .then()
                        .statusCode(200)
                        .body("DescribeTargetHealthResponse.DescribeTargetHealthResult.TargetHealthDescriptions.member.TargetHealth.State",
                                equalTo("healthy")));
    }

    private static void describeHttpsListener(String listenerArn, String certificateArn) {
        given()
                .formParam("Action", "DescribeListeners")
                .formParam("ListenerArns.member.1", listenerArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeListenersResponse.DescribeListenersResult.Listeners.member.Protocol", equalTo("HTTPS"))
                .body("DescribeListenersResponse.DescribeListenersResult.Listeners.member.Port", equalTo(String.valueOf(HTTPS_PORT)))
                .body("DescribeListenersResponse.DescribeListenersResult.Listeners.member.SslPolicy",
                        equalTo("ELBSecurityPolicy-TLS13-1-2-2021-06"))
                .body("DescribeListenersResponse.DescribeListenersResult.Listeners.member.Certificates.member.CertificateArn",
                        equalTo(certificateArn));
    }

    private static HttpsResult awaitHttps(String host, Certificate... trustedCertificates) {
        return awaitHttpsAtPort(host, HTTPS_PORT, trustedCertificates);
    }

    private static HttpsResult awaitHttpsAtPort(String host, int port, Certificate... trustedCertificates) {
        HttpsResult[] result = new HttpsResult[1];
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                .ignoreException(SSLHandshakeException.class).untilAsserted(() -> {
                    result[0] = httpsRequest(host, port, trustedCertificates);
                });
        return result[0];
    }

    private static void awaitHttpsMaterial(String host, int status, String body, Certificate expected,
                                          Certificate... trustedCertificates) {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                .ignoreException(SSLHandshakeException.class).untilAsserted(() -> {
                    HttpsResult response = httpsRequest(host, HTTPS_PORT, trustedCertificates);
                    assertEquals(status, response.statusCode());
                    assertEquals(body, response.body());
                    assertPeerCertificate(response.session(), expected, host);
                });
    }

    private static HttpsResult httpsRequest(String host, int port, Certificate... certificates) throws Exception {
        try (SSLSocket socket = openTlsConnection(host, port, certificates)) {
            HttpResult response = request(socket, host);
            return new HttpsResult(response.statusCode(), response.body(), socket.getSession());
        }
    }

    private static SSLSocket openTlsConnection(String host, int port, Certificate... certificates) throws Exception {
        SSLContext context = trustedContext(certificates);
        Socket transport = new Socket();
        try {
            transport.connect(new InetSocketAddress("127.0.0.1", port), 2000);
            SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(transport, host, port, true);
            try {
                socket.setSoTimeout(2000);
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                socket.setSSLParameters(parameters);
                socket.startHandshake();
                return socket;
            } catch (Exception failure) {
                try (socket) {
                    throw failure;
                }
            }
        } catch (Exception failure) {
            try (transport) {
                throw failure;
            }
        }
    }

    private static HttpResult request(Socket socket, String host) throws IOException {
        socket.getOutputStream().write(("GET / HTTP/1.1\r\nHost: " + host
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int headersEnd = response.indexOf("\r\n\r\n");
        assertTrue(headersEnd >= 0, "Incomplete HTTP response: " + response);
        String statusLine = response.substring(0, response.indexOf("\r\n"));
        int statusCode = Integer.parseInt(statusLine.split(" ", 3)[1]);
        return new HttpResult(statusCode, response.substring(headersEnd + 4));
    }

    private static SSLContext trustedContext(Certificate... certificates) throws Exception {
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        for (int i = 0; i < certificates.length; i++) {
            trustStore.setCertificateEntry("acm-" + i, parseCertificate(certificates[i].getCertificateBody()));
        }
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
        return sslContext;
    }

    private static void assertPeerCertificate(SSLSession session, Certificate expected, String expectedHost)
            throws Exception {
        X509Certificate actual = assertInstanceOf(X509Certificate.class, session.getPeerCertificates()[0]);
        X509Certificate configured = parseCertificate(expected.getCertificateBody());
        assertEquals(fingerprint(configured), fingerprint(actual));
        assertTrue(actual.getSubjectAlternativeNames().stream()
                .anyMatch(san -> expectedHost.equals(san.get(1))));
    }

    private static X509Certificate parseCertificate(String pem) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(
                new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
    }

    private static String fingerprint(X509Certificate certificate) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    private static void deleteListener(String listenerArn) {
        if (listenerArn == null) {
            return;
        }
        given()
                .formParam("Action", "DeleteListener")
                .formParam("ListenerArn", listenerArn)
                .header("Authorization", AUTH)
            .when()
                .post("/");
    }

    private static void deleteLoadBalancer(String lbArn) {
        if (lbArn == null) {
            return;
        }
        given()
                .formParam("Action", "DeleteLoadBalancer")
                .formParam("LoadBalancerArn", lbArn)
                .header("Authorization", AUTH)
            .when()
                .post("/");
    }

    private static void deleteTargetGroup(String targetGroupArn) {
        if (targetGroupArn == null) {
            return;
        }
        given()
                .formParam("Action", "DeleteTargetGroup")
                .formParam("TargetGroupArn", targetGroupArn)
                .header("Authorization", AUTH)
            .when()
                .post("/");
    }

    private record LoadBalancerRef(String arn, String dnsName) {}

    private record HttpResult(int statusCode, String body) {}

    private record HttpsResult(int statusCode, String body, SSLSession session) {}
}
