package io.floci.aws.services.elbv2;

import com.sun.net.httpserver.HttpServer;
import io.floci.aws.config.SelfSignedTlsProfile;
import io.floci.aws.config.TlsProxyServer;
import io.floci.aws.services.acm.AcmService;
import io.floci.aws.services.acm.CertificateGenerator;
import io.floci.aws.services.acm.model.KeyAlgorithm;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.floci.aws.testing.AlbFixtures.createLoadBalancer;
import static io.floci.aws.testing.AlbFixtures.createTargetGroup;
import static io.floci.aws.testing.AlbFixtures.deleteListener;
import static io.floci.aws.testing.AlbFixtures.loadBalancerDnsName;
import static io.floci.aws.testing.AlbFixtures.registerTarget;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * An HTTPS listener on {@code floci.tls.aws-https-port} (443 outside tests), which the TLS proxy
 * holds for AWS API calls, receives the connections whose SNI server name it claims, and only those.
 */
@QuarkusTest
@TestProfile(SelfSignedTlsProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElbV2AwsHttpsPortIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260720/us-east-1/elasticloadbalancing/aws4_request";

    private static HttpServer target;
    private static String forwardingDns;
    private static String forwardingListener;
    private static String fixedDns;
    private static String fixedListener;

    @Inject
    AcmService acmService;

    @Inject
    TlsProxyServer proxy;

    // Read from the application, not the profile class, which loads again with its own port there.
    @ConfigProperty(name = "floci.tls.aws-https-port")
    int port;

    @BeforeAll
    static void startTarget() throws IOException {
        RestAssuredJsonUtils.configureAwsContentTypes();
        target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/", exchange -> {
            byte[] body = ("target port=" + exchange.getRequestHeaders().getFirst("X-Forwarded-Port"))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        target.start();
    }

    @AfterAll
    static void stopTarget() {
        if (target != null) {
            target.stop(0);
        }
    }

    @BeforeEach
    void requireTheProxyOnThePort() throws Exception {
        assertEquals(port, proxy.boundAwsHttpsPort().toCompletionStage().toCompletableFuture()
                .get(10, TimeUnit.SECONDS), "the TLS proxy must bind the configured AWS-HTTPS port");
    }

    @Test
    @Order(1)
    void aClaimedServerNameReachesTheListenerWithTheListenerPortForwarded() throws Exception {
        String forwarding = createLoadBalancer("sni-forwarding");
        forwardingDns = loadBalancerDnsName(forwarding);
        String targetGroup = createTargetGroup("sni-forwarding-tg", "ip", target.getAddress().getPort());
        registerTarget(targetGroup, "127.0.0.1", target.getAddress().getPort());
        forwardingListener = createHttpsListener(forwarding, forwardingDns,
                "DefaultActions.member.1.Type", "forward",
                "DefaultActions.member.1.TargetGroupArn", targetGroup);
        String fixed = createLoadBalancer("sni-fixed");
        fixedDns = loadBalancerDnsName(fixed);
        fixedListener = createHttpsListener(fixed, fixedDns,
                "DefaultActions.member.1.Type", "fixed-response",
                "DefaultActions.member.1.FixedResponseConfig.StatusCode", "200",
                "DefaultActions.member.1.FixedResponseConfig.ContentType", "text/plain",
                "DefaultActions.member.1.FixedResponseConfig.MessageBody", "fixed");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(get(forwardingDns, forwardingDns, "/"), containsString("target port=" + port)));
        assertThat(get(fixedDns, fixedDns, "/"), containsString("fixed"));
    }

    @Test
    @Order(2)
    void theServerNamePinsTheLoadBalancerWhateverTheHostHeaderSays() throws Exception {
        assertThat(get(forwardingDns, fixedDns, "/"), containsString("target port=" + port));
        assertThat(get(fixedDns, forwardingDns, "/"), containsString("fixed"));
    }

    @Test
    @Order(3)
    void noOrAnUnclaimedServerNameStaysWithFloci() {
        assertGoesToFloci(null);
        assertGoesToFloci("unclaimed.example.test");
    }

    @Test
    @Order(4)
    void aWildcardRuleCannotClaimFlociServerNames() throws Exception {
        try (ElasticLoadBalancingV2Client elb = AwsTestClients.create(
                ElasticLoadBalancingV2Client.builder(), "test", Region.US_EAST_1)) {
            elb.createRule(request -> request.listenerArn(fixedListener).priority(10)
                    .conditions(condition -> condition.field("host-header")
                            .hostHeaderConfig(hosts -> hosts.values("*.floci.io", "*.example.test")))
                    .actions(action -> action.type("fixed-response").fixedResponseConfig(response -> response
                            .statusCode("200").contentType("text/plain").messageBody("wildcard rule"))));
        }

        assertThat(get("app.example.test", "app.example.test", "/"), containsString("wildcard rule"));
        assertThat(get(forwardingDns, forwardingDns, "/"), containsString("target port=" + port));
        assertGoesToFloci("localhost.floci.io");
        assertGoesToFloci("sqs.us-east-1.localhost.floci.io");
        assertGoesToFloci(SelfSignedTlsProfile.IOT_ENDPOINT_HOST);
    }

    @Test
    @Order(5)
    void aListenerModifiedAwayFromHttpsNoLongerClaimsItsName() {
        // Listeners sharing a port must share a protocol, so the other one leaves first.
        deleteListener(fixedListener);
        given()
                .formParam("Action", "ModifyListener")
                .formParam("ListenerArn", forwardingListener)
                .formParam("Protocol", "HTTP")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .log().ifValidationFails()
                .statusCode(200);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertGoesToFloci(forwardingDns));
    }

    private String createHttpsListener(String loadBalancerArn, String dnsName, String... action) {
        CertificateGenerator.GeneratedCertificate certificate = new CertificateGenerator()
                .generateSelfSignedCertificate(dnsName, List.of(), KeyAlgorithm.RSA_2048);
        String certificateArn = acmService.importCertificate(certificate.certificatePem(),
                certificate.privateKeyPem(), null, null, Map.of(), "us-east-1").getArn();
        RequestSpecification request = given()
                .formParam("Action", "CreateListener")
                .formParam("LoadBalancerArn", loadBalancerArn)
                .formParam("Protocol", "HTTPS")
                .formParam("Port", String.valueOf(port))
                .formParam("Certificates.member.1.CertificateArn", certificateArn)
                .header("Authorization", AUTH);
        for (int i = 0; i < action.length; i += 2) {
            request.formParam(action[i], action[i + 1]);
        }
        return request
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateListenerResponse.CreateListenerResult.Listeners.member.ListenerArn");
    }

    /**
     * Under {@code @QuarkusTest} Floci serves HTTPS on its test port, not the internal port the proxy
     * hands Floci's connections to, so a connection the proxy leaves with Floci fails its handshake
     * here instead of reaching a load balancer, which would complete it.
     */
    private void assertGoesToFloci(String serverName) {
        assertThrows(SSLHandshakeException.class, () -> get(serverName, "localhost", "/"));
    }

    /** One HTTP/1.1 request over TLS to the AWS-HTTPS port, sending {@code serverName} as SNI when set. */
    private String get(String serverName, String host, String path) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{new TrustAll()}, null);
        try (Socket transport = new Socket()) {
            transport.connect(new InetSocketAddress("127.0.0.1", port), 2000);
            try (SSLSocket socket = (SSLSocket) context.getSocketFactory()
                    .createSocket(transport, "127.0.0.1", port, true)) {
                socket.setSoTimeout(5000);
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setServerNames(serverName == null ? List.of() : List.of(new SNIHostName(serverName)));
                socket.setSSLParameters(parameters);
                socket.startHandshake();
                OutputStream out = socket.getOutputStream();
                out.write(("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
                InputStream in = socket.getInputStream();
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    private static final class TrustAll implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
