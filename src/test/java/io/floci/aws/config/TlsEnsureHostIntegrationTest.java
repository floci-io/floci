package io.floci.aws.config;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLHandshakeException;
import java.net.URI;
import java.security.cert.X509Certificate;
import java.util.Set;

import static io.floci.aws.config.TlsTestSupport.dnsSans;
import static io.floci.aws.config.TlsTestSupport.trustOnlyTheCa;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End to end: after {@code ensureHost}, a TLS client that trusts only the local CA and sends the
 * new name as SNI receives a leaf whose SAN list contains it, and a client that also verifies the
 * server's identity for that name (what every SDK does) completes the handshake, with no restart
 * in between. A file assertion cannot show that the reload reached the listener; only a
 * handshake can.
 */
@QuarkusTest
@TestProfile(SelfSignedTlsProfile.class)
class TlsEnsureHostIntegrationTest {

    static final String NEW_HOST = "api.example.localhost.floci.io";

    @ConfigProperty(name = "quarkus.http.test-ssl-port", defaultValue = "0")
    int sslPort;

    @Inject
    TlsCertificateManager manager;

    @Test
    void ensureHostIsVisibleToTheNextHandshakeAndResetTakesItBack() throws Exception {
        X509Certificate boot = peerLeaf();
        assertFalse(dnsSans(boot).contains(NEW_HOST), "precondition: the boot leaf does not cover " + NEW_HOST);
        assertThrows(SSLHandshakeException.class, this::handshakeVerifyingNewHost,
                "precondition: a client verifying the new name rejects the boot leaf");
        assertEquals(200, healthOverHttpsAsLocalhost(), "the configured name is served before");

        manager.ensureHost(NEW_HOST);

        X509Certificate extended = peerLeaf();
        Set<String> sans = dnsSans(extended);
        assertTrue(sans.contains(NEW_HOST), sans.toString());
        assertTrue(sans.contains("localhost"), "configured names are still served: " + sans);
        assertEquals(boot.getPublicKey(), extended.getPublicKey(), "same key pair across the reload");
        handshakeVerifyingNewHost();
        assertEquals(200, healthOverHttpsAsLocalhost(), "the configured name is still served after the swap");

        given().when().post("/_floci/state/reset").then().statusCode(200);

        Set<String> afterReset = dnsSans(peerLeaf());
        assertFalse(afterReset.contains(NEW_HOST), "reset drops the learned name: " + afterReset);
        assertTrue(afterReset.contains("localhost"), afterReset.toString());
        assertThrows(SSLHandshakeException.class, this::handshakeVerifyingNewHost, "reset: the new name is rejected again");
        assertEquals(200, healthOverHttpsAsLocalhost(), "the configured name is served after reset");
    }

    /** The leaf the server presents for SNI {@code NEW_HOST}, with no identity check. */
    private X509Certificate peerLeaf() throws Exception {
        return handshake(false);
    }

    /** A handshake as an SDK would do it: SNI {@code NEW_HOST} and RFC 6125 identity verification. */
    private void handshakeVerifyingNewHost() throws Exception {
        handshake(true);
    }

    private X509Certificate handshake(boolean verifyIdentity) throws Exception {
        return TlsTestSupport.handshake(sslPort, NEW_HOST, verifyIdentity);
    }

    /** A plain HTTPS client with the JDK's default hostname verifier, for the configured name. */
    private int healthOverHttpsAsLocalhost() throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) URI.create(
                "https://localhost:" + sslPort + "/_floci/health").toURL().openConnection();
        connection.setSSLSocketFactory(trustOnlyTheCa().getSocketFactory());
        try {
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }
}
