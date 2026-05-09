package io.github.hectorvent.floci.config;

import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.config.RestAssuredConfig;
import io.restassured.config.SSLConfig;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

/**
 * Integration test verifying TLS works with user-provided certificate and key files.
 *
 * <p>Generates a cert/key pair in the static initializer (before Quarkus starts),
 * then configures the profile to use those paths for both floci config and
 * Quarkus SSL properties.
 */
@QuarkusTest
@TestProfile(TlsUserCertIntegrationTest.UserCertProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TlsUserCertIntegrationTest {

    @ConfigProperty(name = "quarkus.http.test-ssl-port", defaultValue = "0")
    int testSslPort;

    @BeforeAll
    void setupRestAssured() {
        RestAssured.config = RestAssuredConfig.config()
                .sslConfig(SSLConfig.sslConfig().relaxedHTTPSValidation());
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void healthEndpointOverHttpsWithUserCert() {
        given()
            .baseUri("https://localhost:" + testSslPort)
        .when()
            .get("/_floci/health")
        .then()
            .statusCode(200)
            .body("edition", is("community"));
    }

    @Test
    void ssmPutParameterOverHttps() {
        given()
            .baseUri("https://localhost:" + testSslPort)
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .contentType("application/x-amz-json-1.1")
            .body("{\"Name\": \"/tls-user-cert-test\", \"Value\": \"test-value\", \"Type\": \"String\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    public static final class UserCertProfile implements QuarkusTestProfile {

        private static final Path CERT_DIR = Path.of("/tmp/floci-tls-usercert-test");
        private static final Path CERT_FILE = CERT_DIR.resolve("user-test.crt");
        private static final Path KEY_FILE = CERT_DIR.resolve("user-test.key");

        static {
            generateCertIfNeeded();
        }

        private static void generateCertIfNeeded() {
            if (Files.exists(CERT_FILE) && Files.exists(KEY_FILE)) {
                return;
            }
            try {
                Files.createDirectories(CERT_DIR);
                if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                    Security.addProvider(new BouncyCastleProvider());
                }
                CertificateGenerator gen = new CertificateGenerator();
                CertificateGenerator.GeneratedCertificate cert = gen.generateCertificate(
                        "localhost", List.of("localhost", "127.0.0.1"), KeyAlgorithm.RSA_2048);
                Files.writeString(CERT_FILE, cert.certificatePem());
                Files.writeString(KEY_FILE, cert.privateKeyPem());
            } catch (IOException e) {
                throw new RuntimeException("Failed to generate test cert", e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.tls.enabled", "true",
                    "floci.tls.self-signed", "false",
                    "floci.tls.cert-path", CERT_FILE.toAbsolutePath().toString(),
                    "floci.tls.key-path", KEY_FILE.toAbsolutePath().toString(),
                    "floci.storage.persistent-path", "/tmp/floci-tls-usercert-test-data",
                    "quarkus.http.ssl.certificate.files", CERT_FILE.toAbsolutePath().toString(),
                    "quarkus.http.ssl.certificate.key-files", KEY_FILE.toAbsolutePath().toString(),
                    "quarkus.http.insecure-requests", "enabled"
            );
        }
    }
}
