package io.github.hectorvent.floci.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static io.github.hectorvent.floci.testing.RestAssuredJsonUtils.awsActionJson;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End to end, with TLS on and the real certificate manager: an ACM certificate is requested, the
 * three custom domain operations are called over the wire with their normal responses, and a
 * client that trusts only the chain ACM returned, sends each domain as SNI and verifies the
 * server's identity for it completes the handshake, where it failed before the domain existed.
 * A reset takes the names back. The fixture leaf carries no 127.0.0.1 SAN and the socket connects
 * by IP, so the SNI name is the only one the identity check can match.
 */
@QuarkusTest
@TestProfile(CustomDomainTlsIntegrationTest.Profile.class)
class CustomDomainTlsIntegrationTest {

    static final Path DATA_DIR = Path.of("target", "floci-custom-domain-tls-test").toAbsolutePath();
    static final Path TLS_DIR = DATA_DIR.resolve("tls");
    static final String API_DOMAIN = "api.custom-domain-it.localhost.floci.io";
    static final String IOT_DOMAIN = "iot.custom-domain-it.localhost.floci.io";
    static final String AUTH_DOMAIN = "auth.custom-domain-it.localhost.floci.io";

    @ConfigProperty(name = "quarkus.http.test-ssl-port", defaultValue = "0")
    int sslPort;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void customDomainsAreServedUnderTheAcmChainAsSoonAsTheyExist() throws Exception {
        JsonNode requested = awsActionJson("CertificateManager", "RequestCertificate",
                "{\"DomainName\": \"*.custom-domain-it.localhost.floci.io\", \"ValidationMethod\": \"DNS\"}");
        String certificateArn = requested.path("CertificateArn").asText();
        String chain = awsActionJson("CertificateManager", "GetCertificate",
                "{\"CertificateArn\": \"" + certificateArn + "\"}").path("CertificateChain").asText();
        assertTrue(chain.contains("BEGIN CERTIFICATE"), chain);

        for (String domain : List.of(API_DOMAIN, IOT_DOMAIN, AUTH_DOMAIN)) {
            assertThrows(SSLHandshakeException.class, () -> handshake(chain, domain, true),
                    "precondition: " + domain + " is not served before it is created");
        }

        given().contentType(ContentType.JSON)
                .body("{\"domainName\":\"" + API_DOMAIN + "\",\"regionalCertificateArn\":\"" + certificateArn + "\","
                        + "\"endpointConfiguration\":{\"types\":[\"REGIONAL\"]}}")
                .when().post("/domainnames").then().statusCode(201);
        given().contentType(ContentType.JSON)
                .body("{\"domainName\":\"" + IOT_DOMAIN + "\",\"serverCertificateArns\":[\"" + certificateArn + "\"]}")
                .when().post("/domainConfigurations/custom-domain-it").then().statusCode(200);
        String poolId = cognitoJson("CreateUserPool", "{\"PoolName\": \"custom-domain-it\"}")
                .path("UserPool").path("Id").asText();
        JsonNode created = cognitoJson("CreateUserPoolDomain", "{\"Domain\": \"" + AUTH_DOMAIN + "\", \"UserPoolId\": \""
                + poolId + "\", \"CustomDomainConfig\": {\"CertificateArn\": \"" + certificateArn + "\"}}");
        assertTrue(created.path("CloudFrontDomain").asText().endsWith(".cloudfront.net"), created.toString());

        for (String domain : List.of(API_DOMAIN, IOT_DOMAIN, AUTH_DOMAIN)) {
            X509Certificate served = handshake(chain, domain, true);
            assertTrue(sans(served).contains(domain), domain + " not in " + sans(served));
            assertEquals(parse(chain).get(0).getSubjectX500Principal(), served.getIssuerX500Principal(),
                    "the served leaf is issued by the CA in the ACM chain");
        }

        given().when().post("/_floci/state/reset").then().statusCode(200);

        for (String domain : List.of(API_DOMAIN, IOT_DOMAIN, AUTH_DOMAIN)) {
            assertThrows(SSLHandshakeException.class, () -> handshake(chain, domain, true),
                    "reset drops " + domain);
        }
        assertFalse(sans(handshake(chain, API_DOMAIN, false)).contains(API_DOMAIN));
    }

    /**
     * TLS by IP trusting only {@code chainPem}, sending {@code sniHost} as SNI and, when asked,
     * verifying the server's identity for that name the way an SDK does.
     */
    private X509Certificate handshake(String chainPem, String sniHost, boolean verifyIdentity) throws Exception {
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
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), sslPort), 5000);
            SSLParameters params = socket.getSSLParameters();
            params.setServerNames(List.of(new SNIHostName(sniHost)));
            if (verifyIdentity) {
                params.setEndpointIdentificationAlgorithm("HTTPS");
            }
            socket.setSSLParameters(params);
            socket.startHandshake();
            return (X509Certificate) socket.getSession().getPeerCertificates()[0];
        }
    }

    private static List<X509Certificate> parse(String pem) throws Exception {
        return CertificateFactory.getInstance("X.509")
                .generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)))
                .stream().map(X509Certificate.class::cast).toList();
    }

    private static Set<String> sans(X509Certificate leaf) throws Exception {
        Set<String> sans = new TreeSet<>();
        for (List<?> entry : leaf.getSubjectAlternativeNames()) {
            sans.add(String.valueOf(entry.get(1)));
        }
        return sans;
    }

    /**
     * Boots with TLS on and a CA-issued leaf like the one TlsConfigSource writes, in a directory
     * of its own. The wildcard covers one label only, so the three-label names are uncovered
     * until the operations add them.
     */
    public static final class Profile implements QuarkusTestProfile {

        static {
            try {
                Files.createDirectories(TLS_DIR);
                for (String name : List.of("floci-server.crt", "floci-server.key", "floci-server.metadata.json")) {
                    Files.deleteIfExists(TLS_DIR.resolve(name));
                }
                FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(TLS_DIR);
                List<String> sans = List.of("localhost", "*.localhost.floci.io");
                var leaf = ca.issueServerCertificate("localhost", sans, KeyAlgorithm.RSA_2048, null);
                Files.writeString(TLS_DIR.resolve("floci-server.crt"), leaf.certificatePem());
                Files.writeString(TLS_DIR.resolve("floci-server.key"), leaf.privateKeyPem());
                Files.writeString(TLS_DIR.resolve("floci-server.metadata.json"),
                        new ObjectMapper().writeValueAsString(CertificateMetadata.create(sans, "dev")));
            } catch (IOException e) {
                throw new IllegalStateException("could not prepare the TLS fixtures", e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.tls.enabled", "true",
                    "floci.tls.self-signed", "true",
                    "floci.tls.aws-https-port", "0",
                    "floci.storage.persistent-path", DATA_DIR.toString(),
                    "quarkus.tls.key-store.pem.0.cert", TLS_DIR.resolve("floci-server.crt").toString(),
                    "quarkus.tls.key-store.pem.0.key", TLS_DIR.resolve("floci-server.key").toString(),
                    "quarkus.http.insecure-requests", "enabled");
        }
    }
}
