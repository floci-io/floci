package io.github.hectorvent.floci.config;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.config.RestAssuredConfig;
import io.restassured.config.SSLConfig;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;

/**
 * Integration test verifying TLS/HTTPS support when enabled via configuration.
 *
 * <p>Uses a {@link QuarkusTestProfile} to enable self-signed TLS so the Quarkus
 * HTTP server serves HTTPS alongside HTTP (dual-mode, LocalStack parity).
 */
@QuarkusTest
@TestProfile(UserCertTlsProfile.class)
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
        String queueUrl = given()
            .baseUri("https://localhost:" + testSslPort)
            .header("X-Amz-Target", "AmazonSQS.CreateQueue")
            .contentType("application/x-amz-json-1.0")
            .body("{\"QueueName\": \"tls-test-queue\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("QueueUrl", startsWith("https://"))
            .extract().path("QueueUrl");

        given()
            .baseUri("https://localhost:" + testSslPort)
            .header("X-Amz-Target", "AmazonSQS.DeleteQueue")
            .contentType("application/x-amz-json-1.0")
            .body("{\"QueueUrl\": \"" + queueUrl + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
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
}
