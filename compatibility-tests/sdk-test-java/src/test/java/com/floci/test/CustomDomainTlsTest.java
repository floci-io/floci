package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.acm.AcmClient;
import software.amazon.awssdk.services.acm.model.GetCertificateResponse;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.EndpointConfiguration;
import software.amazon.awssdk.services.apigateway.model.EndpointType;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a deployment tool does with a custom domain: request an ACM certificate for it, create
 * the API Gateway domain name with that certificate, then reach https://&lt;domain&gt; with a
 * client that trusts only the chain ACM returned. DNS is not involved: the socket goes to the
 * Floci host and carries the domain as SNI, which is what a hosts-file or local DNS setup does.
 *
 * <p>Needs Floci with FLOCI_TLS_ENABLED=true; skipped otherwise. Floci-only: on AWS the
 * certificate would wait for DNS validation.
 */
class CustomDomainTlsTest {

    private static AcmClient acm;
    private static ApiGatewayClient apigw;
    private static String certificateArn;
    private static String domainName;

    @BeforeAll
    static void setup() throws Exception {
        Assumptions.assumeFalse(TestFixtures.isRealAws(), "Floci-only: real AWS needs DNS validation");
        Assumptions.assumeTrue(tlsIsServed(), "needs Floci with FLOCI_TLS_ENABLED=true");
        acm = TestFixtures.acmClient();
        apigw = TestFixtures.apiGatewayClient();
        domainName = TestFixtures.uniqueName("api") + ".dev.localhost.floci.io";
    }

    @AfterAll
    static void cleanup() {
        if (apigw != null) {
            try {
                apigw.deleteDomainName(b -> b.domainName(domainName));
            } catch (Exception e) {
                System.err.println("CustomDomainTlsTest cleanup: deleteDomainName failed: " + e.getMessage());
            }
            apigw.close();
        }
        if (acm != null) {
            if (certificateArn != null) {
                try {
                    acm.deleteCertificate(b -> b.certificateArn(certificateArn));
                } catch (Exception e) {
                    System.err.println("CustomDomainTlsTest cleanup: deleteCertificate failed: " + e.getMessage());
                }
            }
            acm.close();
        }
    }

    @Test
    void clientTrustingOnlyTheAcmChainReachesTheCustomDomain() throws Exception {
        certificateArn = acm.requestCertificate(b -> b.domainName(domainName).validationMethod("DNS")).certificateArn();
        GetCertificateResponse issued = acm.getCertificate(b -> b.certificateArn(certificateArn));
        assertThat(issued.certificate()).contains("BEGIN CERTIFICATE");
        assertThat(issued.certificateChain()).contains("BEGIN CERTIFICATE");

        apigw.createDomainName(b -> b
                .domainName(domainName)
                .regionalCertificateArn(certificateArn)
                .endpointConfiguration(EndpointConfiguration.builder().types(EndpointType.REGIONAL).build()));

        X509Certificate served = handshake(issued.certificateChain(), domainName);

        assertThat(sans(served)).contains(domainName);
        X509Certificate acmLeaf = parse(issued.certificate()).iterator().next();
        assertThat(served.getIssuerX500Principal()).isEqualTo(acmLeaf.getIssuerX500Principal());
    }

    /**
     * The compatibility workflow runs Floci with FLOCI_TLS_ENABLED=true but hands the tests
     * FLOCI_ENDPOINT=http://floci:4566 (one port serves both), so the URL scheme says nothing
     * about TLS. Probe instead: fetch the CA over plain HTTP and try one handshake against the
     * same host and port.
     */
    private static boolean tlsIsServed() throws Exception {
        String caPem = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://" + host() + ":" + port() + "/_floci/ca.pem")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        if (!caPem.contains("BEGIN CERTIFICATE")) {
            System.err.println("CustomDomainTlsTest: no CA served on " + host() + ":" + port() + ", skipping");
            return false;
        }
        try {
            handshake(caPem, null);
            return true;
        } catch (IOException e) {
            System.err.println("CustomDomainTlsTest: no TLS on " + host() + ":" + port() + " (" + e.getMessage() + "), skipping");
            return false;
        }
    }

    private static String host() {
        return TestFixtures.endpoint().getHost();
    }

    private static int port() {
        URI endpoint = TestFixtures.endpoint();
        if (endpoint.getPort() != -1) {
            return endpoint.getPort();
        }
        return "https".equalsIgnoreCase(endpoint.getScheme()) ? 443 : 80;
    }

    /**
     * TLS to the Floci host trusting only {@code chainPem}. With {@code sniHost} set, sends it as
     * SNI and asks for hostname verification, the way an SDK would; {@code null} is a bare probe.
     * The JDK falls back to the peer host when the SNI name does not match, and the Floci host
     * is always in the certificate, so the SAN assertion in the test is the check that the
     * domain is served, not the handshake alone.
     */
    private static X509Certificate handshake(String chainPem, String sniHost) throws Exception {
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        int i = 0;
        for (X509Certificate ca : parse(chainPem)) {
            trust.setCertificateEntry("chain-" + i++, ca);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);

        try (SSLSocket socket = (SSLSocket) ctx.getSocketFactory().createSocket()) {
            socket.connect(new InetSocketAddress(host(), port()), 5000);
            socket.setSoTimeout(10000);
            if (sniHost != null) {
                SSLParameters params = socket.getSSLParameters();
                params.setServerNames(List.of(new SNIHostName(sniHost)));
                params.setEndpointIdentificationAlgorithm("HTTPS");
                socket.setSSLParameters(params);
            }
            socket.startHandshake();
            return (X509Certificate) socket.getSession().getPeerCertificates()[0];
        }
    }

    private static Collection<X509Certificate> parse(String pem) throws Exception {
        Collection<? extends Certificate> certs = CertificateFactory.getInstance("X.509")
                .generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
        return certs.stream().map(X509Certificate.class::cast).toList();
    }

    private static Set<String> sans(X509Certificate cert) throws Exception {
        Set<String> out = new TreeSet<>();
        for (List<?> entry : cert.getSubjectAlternativeNames()) {
            out.add(String.valueOf(entry.get(1)));
        }
        return out;
    }
}
