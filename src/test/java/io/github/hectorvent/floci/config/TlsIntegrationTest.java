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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Integration test verifying TLS/HTTPS support when enabled via configuration.
 *
 * <p>Uses a {@link QuarkusTestProfile} to enable self-signed TLS so the Quarkus
 * HTTP server serves HTTPS alongside HTTP (dual-mode, LocalStack parity).
 */
@QuarkusTest
@TestProfile(TlsIntegrationTest.TlsEnabledProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TlsIntegrationTest {

    @ConfigProperty(name = "quarkus.http.test-ssl-port", defaultValue = "0")
    int testSslPort;

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "0")
    int testHttpPort;

    @BeforeAll
    void setupRestAssured() {
        // Trust self-signed certificates for all requests in this test class
        RestAssured.config = RestAssuredConfig.config()
                .sslConfig(SSLConfig.sslConfig().relaxedHTTPSValidation());
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    // ==================== HTTPS Connectivity ====================

    @Test
    void healthEndpointOverHttps() {
        given()
            .baseUri("https://localhost:" + testSslPort)
        .when()
            .get("/_floci/health")
        .then()
            .statusCode(200)
            .body("edition", is("community"));
    }

    @Test
    void healthEndpointStillWorksOverHttp() {
        given()
            .baseUri("http://localhost:" + testHttpPort)
        .when()
            .get("/_floci/health")
        .then()
            .statusCode(200)
            .body("edition", is("community"));
    }

    // ==================== SQS via HTTPS ====================

    @Test
    void sqsCreateQueueOverHttps() {
        given()
            .baseUri("https://localhost:" + testSslPort)
            .header("X-Amz-Target", "AmazonSQS.CreateQueue")
            .contentType("application/x-amz-json-1.0")
            .body("{\"QueueName\": \"tls-test-queue\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("QueueUrl", startsWith("https://"));
    }

    // ==================== STS via HTTPS ====================

    @Test
    void stsGetCallerIdentityOverHttps() {
        given()
            .baseUri("https://localhost:" + testSslPort)
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetCallerIdentity")
            .formParam("Version", "2011-06-15")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("GetCallerIdentityResult"));
    }

    // ==================== Test Profile ====================

    public static final class TlsEnabledProfile implements QuarkusTestProfile {

        private static final Path CERT_DIR = Path.of("/tmp/floci-tls-integration-test");
        private static final Path CERT_FILE = CERT_DIR.resolve("test.crt");
        private static final Path KEY_FILE = CERT_DIR.resolve("test.key");

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
                    "floci.storage.persistent-path", "/tmp/floci-tls-integration-test-data",
                    "quarkus.http.ssl.certificate.files", CERT_FILE.toAbsolutePath().toString(),
                    "quarkus.http.ssl.certificate.key-files", KEY_FILE.toAbsolutePath().toString(),
                    "quarkus.http.insecure-requests", "enabled"
            );
        }
    }
}
