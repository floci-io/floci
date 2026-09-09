package io.github.hectorvent.floci.config;

import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Boots the HTTP server on the TLS registry keys {@code TlsConfigSource} publishes, with a leaf
 * issued by the local CA, and proves a client that trusts only {@code GET /_floci/ca.pem} completes
 * a handshake. A file assertion cannot show that Quarkus read the registry entry or that the served
 * chain is the CA's; only a handshake can.
 */
@QuarkusTest
@TestProfile(TlsLocalCaIntegrationTest.LocalCaProfile.class)
class TlsLocalCaIntegrationTest {

    @ConfigProperty(name = "quarkus.http.test-ssl-port", defaultValue = "0")
    int testSslPort;

    @Test
    void handshakeSucceedsTrustingOnlyTheServedCa() throws Exception {
        String caPem = given().when().get("/_floci/ca.pem").then().statusCode(200).extract().asString();
        X509Certificate ca = parse(caPem);

        HttpsURLConnection connection = open(trustOnly(ca));
        assertEquals(200, connection.getResponseCode());
        Certificate[] served = connection.getServerCertificates();
        X509Certificate leaf = (X509Certificate) served[0];
        assertEquals(ca.getSubjectX500Principal(), leaf.getIssuerX500Principal());
        assertEquals(-1, leaf.getBasicConstraints());
        leaf.verify(ca.getPublicKey());
        connection.disconnect();
    }

    @Test
    void handshakeFailsTrustingAnotherCa() throws Exception {
        X509Certificate stranger = FlociCertificateAuthority.loadOrCreate(
                Files.createTempDirectory("floci-stranger-ca")).certificate();

        HttpsURLConnection connection = open(trustOnly(stranger));
        assertThrows(SSLHandshakeException.class, connection::getResponseCode);
    }

    private HttpsURLConnection open(SSLContext sslContext) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) URI.create(
                "https://localhost:" + testSslPort + "/_floci/health").toURL().openConnection();
        connection.setSSLSocketFactory(sslContext.getSocketFactory());
        return connection;
    }

    private static SSLContext trustOnly(X509Certificate ca) throws Exception {
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("floci-ca", ca);
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);
        return context;
    }

    private static X509Certificate parse(String pem) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
    }

    /**
     * Lays down what {@code TlsConfigSource} would: a CA under {persistent-path}/tls and a leaf
     * issued by it, published as the registry's default key store. The persistent path is the
     * same directory, so the CDI producer behind {@code /_floci/ca.pem} loads the same CA.
     */
    public static final class LocalCaProfile implements QuarkusTestProfile {

        private static final Path DATA_DIR = Path.of("target", "floci-tls-local-ca-test").toAbsolutePath();
        private static final Path TLS_DIR = DATA_DIR.resolve("tls");
        private static final Path CERT_FILE = TLS_DIR.resolve("floci-server.crt");
        private static final Path KEY_FILE = TLS_DIR.resolve("floci-server.key");

        static {
            try {
                FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(TLS_DIR);
                var leaf = ca.issueServerCertificate("localhost", List.of("localhost", "127.0.0.1"),
                        KeyAlgorithm.RSA_2048, null);
                Files.writeString(CERT_FILE, leaf.certificatePem());
                Files.writeString(KEY_FILE, leaf.privateKeyPem());
            } catch (IOException e) {
                throw new IllegalStateException("Failed to prepare the local CA test files", e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.storage.persistent-path", DATA_DIR.toString(),
                    "quarkus.tls.key-store.pem.0.cert", CERT_FILE.toString(),
                    "quarkus.tls.key-store.pem.0.key", KEY_FILE.toString(),
                    "quarkus.http.insecure-requests", "enabled"
            );
        }
    }
}
