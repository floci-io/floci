package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.acm.AcmClient;
import software.amazon.awssdk.services.acm.model.CertificateStatus;
import software.amazon.awssdk.services.acm.model.ResourceInUseException;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Action;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ActionTypeEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.FixedResponseActionConfig;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Listener;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancer;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancerTypeEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ProtocolEnum;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("Local ELBv2 HTTPS listeners")
class ElbV2HttpsDataPlaneTest {

    private static final int LISTENER_PORT = 7796;

    private static AcmClient acm;
    private static ElasticLoadBalancingV2Client elb;
    private static String certificateArn;
    private static String loadBalancerArn;
    private static String listenerArn;

    @BeforeAll
    static void setup() {
        assumeFalse(TestFixtures.isRealAws(), "Creates local HTTPS listener fixtures");
        acm = TestFixtures.acmClient();
        elb = TestFixtures.elbV2Client();
    }

    @AfterAll
    static void cleanup() {
        assertAll("Delete every HTTPS fixture resource and close both clients",
                () -> {
                    if (listenerArn != null) {
                        elb.deleteListener(request -> request.listenerArn(listenerArn));
                    }
                },
                () -> {
                    if (loadBalancerArn != null) {
                        elb.deleteLoadBalancer(request -> request.loadBalancerArn(loadBalancerArn));
                    }
                },
                () -> {
                    if (certificateArn != null) {
                        acm.deleteCertificate(request -> request.certificateArn(certificateArn));
                    }
                },
                () -> {
                    if (elb != null) {
                        elb.close();
                    }
                },
                () -> {
                    if (acm != null) {
                        acm.close();
                    }
                });
    }

    @Test
    @DisplayName("Official clients configure an ACM-backed listener that serves HTTPS")
    void officialClientsConfigureAcmBackedHttpsListenerThatTerminatesTls() throws Exception {
        LoadBalancer loadBalancer = elb.createLoadBalancer(request -> request
                        .name(TestFixtures.uniqueName("tls"))
                        .type(LoadBalancerTypeEnum.APPLICATION)
                        .subnets(TestFixtures.defaultVpcSubnets()))
                .loadBalancers().get(0);
        loadBalancerArn = loadBalancer.loadBalancerArn();
        String dnsName = loadBalancer.dnsName();

        certificateArn = acm.requestCertificate(request -> request.domainName(dnsName)).certificateArn();
        acm.updateCertificateOptions(request -> request.certificateArn(certificateArn)
                .options(options -> options.export("ENABLED")));
        assertThat(acm.describeCertificate(request -> request.certificateArn(certificateArn))
                .certificate().options().exportAsString()).isEqualTo("ENABLED");
        String certificatePem = acm.getCertificate(request -> request.certificateArn(certificateArn)).certificate();

        listenerArn = elb.createListener(request -> request
                        .loadBalancerArn(loadBalancerArn)
                        .protocol(ProtocolEnum.HTTPS)
                        .port(LISTENER_PORT)
                        .sslPolicy("ELBSecurityPolicy-TLS13-1-2-2021-06")
                        .certificates(certificate -> certificate.certificateArn(certificateArn))
                        .defaultActions(Action.builder()
                                .type(ActionTypeEnum.FIXED_RESPONSE)
                                .fixedResponseConfig(FixedResponseActionConfig.builder()
                                        .statusCode("200")
                                        .contentType("text/plain")
                                        .messageBody("sdk-tls")
                                        .build())
                                .build()))
                .listeners().get(0).listenerArn();

        Listener described = elb.describeListeners(request -> request.listenerArns(listenerArn)).listeners().get(0);
        assertThat(described.protocol()).isEqualTo(ProtocolEnum.HTTPS);
        assertThat(described.port()).isEqualTo(LISTENER_PORT);
        assertThat(described.sslPolicy()).isEqualTo("ELBSecurityPolicy-TLS13-1-2-2021-06");
        assertThat(described.certificates()).extracting(certificate -> certificate.certificateArn())
                .containsExactly(certificateArn);

        X509Certificate expected = parseCertificate(certificatePem);
        HttpClient client = trustedClient(expected);
        HttpResponse<String> result = awaitHttps(client, dnsName);

        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.body()).isEqualTo("sdk-tls");
        X509Certificate actual = (X509Certificate) result.sslSession().orElseThrow().getPeerCertificates()[0];
        assertThat(fingerprint(actual)).isEqualTo(fingerprint(expected));
        assertThat(actual.getSubjectAlternativeNames())
                .anySatisfy(san -> assertThat(san.get(1)).isEqualTo(dnsName));

        ResourceInUseException refusal = assertThrows(ResourceInUseException.class,
                () -> acm.revokeCertificate(request -> request.certificateArn(certificateArn)
                        .revocationReason("KEY_COMPROMISE")));
        assertThat(refusal.statusCode()).isEqualTo(400);
        assertThat(acm.describeCertificate(request -> request.certificateArn(certificateArn)).certificate().status())
                .isEqualTo(CertificateStatus.ISSUED);
        HttpClient fresh = trustedClient(expected);
        HttpResponse<String> afterRefusal = awaitHttps(fresh, dnsName);
        assertThat(afterRefusal.statusCode()).isEqualTo(200);
        assertThat(afterRefusal.body()).isEqualTo("sdk-tls");
        assertThat(fingerprint((X509Certificate) afterRefusal.sslSession().orElseThrow().getPeerCertificates()[0]))
                .isEqualTo(fingerprint(expected));
        elb.deleteListener(request -> request.listenerArn(listenerArn));
        listenerArn = null;
        acm.revokeCertificate(request -> request.certificateArn(certificateArn).revocationReason("KEY_COMPROMISE"));
        assertThat(acm.describeCertificate(request -> request.certificateArn(certificateArn)).certificate().status())
                .isEqualTo(CertificateStatus.REVOKED);
    }

    private static HttpClient trustedClient(X509Certificate certificate) throws Exception {
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("acm", certificate);
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .sslContext(sslContext)
                .build();
    }

    private static HttpResponse<String> awaitHttps(HttpClient client, String dnsName) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        Exception lastFailure = null;
        do {
            try {
                return client.send(
                        HttpRequest.newBuilder(URI.create("https://" + dnsName + ":" + LISTENER_PORT + "/"))
                                .timeout(Duration.ofSeconds(2))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                lastFailure = e;
                Thread.sleep(100);
            }
        } while (System.nanoTime() < deadline);
        throw lastFailure;
    }

    private static X509Certificate parseCertificate(String pem) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(
                new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
    }

    private static String fingerprint(X509Certificate certificate) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
    }
}
