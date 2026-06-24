package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * An S3 bucket named exactly <code>configuration</code> must NOT collide with
 * the AppConfigData runtime route (<code>GET /configuration</code>, issue #1294).
 *
 * <p>Discriminator: a genuine AppConfigData GetLatestConfiguration request always
 * carries a <code>configuration_token</code> query parameter. An S3 path-style
 * request to a bucket named <code>configuration</code> does not, so it must be
 * routed to S3 instead of NPE-ing inside the AppConfig handler.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3ConfigurationBucketIntegrationTest {

    private static final String BUCKET = "configuration";
    private static final String KEY = "settings.json";
    private static final String BODY = "{\"feature\":\"on\"}";

    @Test
    @Order(0)
    void createConfigurationBucket() {
        given()
        .when()
            .put("/" + BUCKET)
        .then()
            .statusCode(200);
    }

    @Test
    @Order(1)
    void listObjectsV2_mustNotBeRoutedToAppConfig() {
        String body = given()
            .queryParam("list-type", "2")
        .when()
            .get("/" + BUCKET)
        .then()
            .statusCode(200)
            .body(containsString("ListBucketResult"))
            .body(containsString("<Name>configuration</Name>"))
            .extract().asString();

        if (body.contains("BadRequestException") || body.contains("Invalid configuration token")) {
            throw new AssertionError(
                "GET /" + BUCKET + "?list-type=2 was routed to AppConfig instead of S3. Response: " + body);
        }
    }

    @Test
    @Order(2)
    void putAndGetObjectInConfigurationBucket() {
        given()
            .header("Content-Type", "application/json")
            .body(BODY)
        .when()
            .put("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(200)
            .body(equalTo(BODY));
    }

    @Test
    @Order(3)
    void listObjectsV2_returnsStoredObject() {
        given()
            .queryParam("list-type", "2")
        .when()
            .get("/" + BUCKET)
        .then()
            .statusCode(200)
            .body(containsString("<Key>" + KEY + "</Key>"));
    }
}
