package io.github.hectorvent.floci.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End to end: after {@code ensureHost}, a TLS client that trusts only the local CA and sends the
 * new name as SNI receives a leaf whose SAN list contains it, with no restart in between. A file
 * assertion cannot show that the reload reached the listener; only a handshake can.
 */
@QuarkusTest
@TestProfile(TlsEnsureHostIntegrationTest.Profile.class)
class TlsEnsureHostIntegrationTest {

    static final Path DATA_DIR = Path.of("target", "floci-tls-ensure-host-test").toAbsolutePath();
    static final Path TLS_DIR = DATA_DIR.resolve("tls");
    static final String NEW_HOST = "api.example.localhost.floci.io";

    @ConfigProperty(name = "quarkus.http.test-ssl-port", defaultValue = "0")
    int sslPort;

    @Inject
    TlsCertificateManager manager;

    @Test
    void ensureHostIsVisibleToTheNextHandshakeAndResetTakesItBack() throws Exception {
        X509Certificate boot = peerLeaf();
        assertFalse(sans(boot).contains(NEW_HOST), "precondition: the boot leaf does not cover " + NEW_HOST);

        manager.ensureHost(NEW_HOST);

        X509Certificate extended = peerLeaf();
        Set<String> sans = sans(extended);
        assertTrue(sans.contains(NEW_HOST), sans.toString());
        assertTrue(sans.contains("localhost"), "configured names are still served: " + sans);
        assertEquals(boot.getPublicKey(), extended.getPublicKey(), "same key pair across the reload");

        given().when().post("/_floci/state/reset").then().statusCode(200);

        Set<String> afterReset = sans(peerLeaf());
        assertFalse(afterReset.contains(NEW_HOST), "reset drops the learned name: " + afterReset);
        assertTrue(afterReset.contains("localhost"), afterReset.toString());
    }

    private X509Certificate peerLeaf() throws Exception {
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("floci", FlociCertificateAuthority.loadOrCreate(TLS_DIR).certificate());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);

        try (SSLSocket socket = (SSLSocket) ctx.getSocketFactory().createSocket("localhost", sslPort)) {
            SSLParameters params = socket.getSSLParameters();
            params.setServerNames(List.of(new SNIHostName(NEW_HOST)));
            socket.setSSLParameters(params);
            socket.startHandshake();
            return (X509Certificate) socket.getSession().getPeerCertificates()[0];
        }
    }

    private static Set<String> sans(X509Certificate leaf) throws Exception {
        Set<String> sans = new TreeSet<>();
        for (List<?> entry : leaf.getSubjectAlternativeNames()) {
            sans.add(String.valueOf(entry.get(1)));
        }
        return sans;
    }

    /**
     * Boots with TLS on and a CA-issued leaf like the one TlsConfigSource writes. TlsConfigSource
     * itself reads system properties, not profile overrides, so the profile lays the files down
     * and points the TLS registry's default entry at them. The wildcard covers one label only
     * (RFC 6125), so the three-label name is genuinely uncovered before ensureHost.
     */
    public static final class Profile implements QuarkusTestProfile {

        static {
            try {
                Files.createDirectories(TLS_DIR);
                for (String name : List.of("floci-server.crt", "floci-server.key", "floci-server.metadata.json")) {
                    Files.deleteIfExists(TLS_DIR.resolve(name));
                }
                FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(TLS_DIR);
                List<String> sans = List.of("localhost", "127.0.0.1", "*.localhost.floci.io");
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
