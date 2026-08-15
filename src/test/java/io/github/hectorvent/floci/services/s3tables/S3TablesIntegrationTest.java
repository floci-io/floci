package io.github.hectorvent.floci.services.s3tables;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3TablesIntegrationTest {

    private static final String JSON_CONTENT_TYPE = "application/json";
    private static final String BUCKET_NAME = "s3tables-integration-bucket";
    private static final String NAMESPACE = "analytics";
    private static final String TABLE_NAME = "events";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String BUCKET_ARN = "arn:aws:s3tables:%s:%s:bucket/%s"
            .formatted(REGION, ACCOUNT_ID, BUCKET_NAME);
    private static final String ENCODED_BUCKET_ARN = URLEncoder.encode(BUCKET_ARN, StandardCharsets.UTF_8);

    private String versionToken;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createsTableBucketThroughTheS3TablesRoute() {
        given()
                .urlEncodingEnabled(false)
                .contentType(JSON_CONTENT_TYPE)
                .body("""
                        { "name": "%s" }
                        """.formatted(BUCKET_NAME))
        .when()
                .put("/buckets")
        .then()
                .statusCode(200)
                .contentType(containsString(JSON_CONTENT_TYPE))
                .body("arn", equalTo(BUCKET_ARN));
    }

    @Test
    @Order(2)
    void managesNamespaceAndTableThroughEncodedBucketArnRoutes() {
        given()
                .urlEncodingEnabled(false)
                .contentType(JSON_CONTENT_TYPE)
                .body("""
                        { "namespace": ["%s"] }
                        """.formatted(NAMESPACE))
        .when()
                .put("/namespaces/" + ENCODED_BUCKET_ARN)
        .then()
                .statusCode(200)
                .contentType(containsString(JSON_CONTENT_TYPE))
                .body("namespace", contains(NAMESPACE))
                .body("tableBucketARN", equalTo(BUCKET_ARN));

        versionToken = given()
                .urlEncodingEnabled(false)
                .contentType(JSON_CONTENT_TYPE)
                .body("""
                        {
                          "name": "%s",
                          "format": "ICEBERG",
                          "metadata": { "iceberg": { "metadataLocation": "s3://warehouse/events/metadata/v1.json" } }
                        }
                        """.formatted(TABLE_NAME))
        .when()
                .put("/tables/" + ENCODED_BUCKET_ARN + "/" + NAMESPACE)
        .then()
                .statusCode(200)
                .contentType(containsString(JSON_CONTENT_TYPE))
                .body("tableARN", equalTo(BUCKET_ARN + "/table/" + TABLE_NAME))
                .body("versionToken", not(""))
                .extract()
                .path("versionToken");
    }

    @Test
    @Order(3)
    void updatesMetadataLocationUsingOptimisticVersionTokens() {
        String replacementToken = given()
                .urlEncodingEnabled(false)
                .contentType(JSON_CONTENT_TYPE)
                .body("""
                        {
                          "metadataLocation": "s3://warehouse/events/metadata/v2.json",
                          "versionToken": "%s"
                        }
                        """.formatted(versionToken))
        .when()
                .put("/tables/" + ENCODED_BUCKET_ARN + "/" + NAMESPACE + "/" + TABLE_NAME + "/metadata-location")
        .then()
                .statusCode(200)
                .body("metadataLocation", equalTo("s3://warehouse/events/metadata/v2.json"))
                .body("versionToken", not(equalTo(versionToken)))
                .extract()
                .path("versionToken");

        given()
                .urlEncodingEnabled(false)
                .contentType(JSON_CONTENT_TYPE)
                .body("""
                        {
                          "metadataLocation": "s3://warehouse/events/metadata/v3.json",
                          "versionToken": "%s"
                        }
                        """.formatted(versionToken))
        .when()
                .put("/tables/" + ENCODED_BUCKET_ARN + "/" + NAMESPACE + "/" + TABLE_NAME + "/metadata-location")
        .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        versionToken = replacementToken;
    }

    @Test
    @Order(4)
    void roundTripsTableBucketPolicy() {
        given()
                .urlEncodingEnabled(false)
                .contentType(JSON_CONTENT_TYPE)
                .body("""
                        { "resourcePolicy": "{\\\"Version\\\":\\\"2012-10-17\\\"}" }
                        """)
        .when()
                .put("/buckets/" + ENCODED_BUCKET_ARN + "/policy")
        .then()
                .statusCode(200);

        given()
                .urlEncodingEnabled(false)
        .when()
                .get("/buckets/" + ENCODED_BUCKET_ARN + "/policy")
        .then()
                .statusCode(200)
                .contentType(containsString(JSON_CONTENT_TYPE))
                .body("resourcePolicy", equalTo("{\"Version\":\"2012-10-17\"}"));
    }

    @Test
    @Order(5)
    void roundTripsTypedMaintenanceConfigurationMaps() {
        given()
                .urlEncodingEnabled(false)
                .contentType(JSON_CONTENT_TYPE)
                .body("{ \"value\": { \"status\": \"enabled\" } }")
        .when()
                .put("/buckets/" + ENCODED_BUCKET_ARN + "/maintenance/icebergUnreferencedFileRemoval")
        .then()
                .statusCode(200);

        given()
                .urlEncodingEnabled(false)
        .when()
                .get("/buckets/" + ENCODED_BUCKET_ARN + "/maintenance")
        .then()
                .statusCode(200)
                .body("configuration.icebergUnreferencedFileRemoval.status", equalTo("enabled"));

        given()
                .urlEncodingEnabled(false)
                .contentType(JSON_CONTENT_TYPE)
                .body("{ \"value\": { \"status\": \"enabled\" } }")
        .when()
                .put("/tables/" + ENCODED_BUCKET_ARN + "/" + NAMESPACE + "/" + TABLE_NAME
                        + "/maintenance/icebergCompaction")
        .then()
                .statusCode(200);

        given()
                .urlEncodingEnabled(false)
        .when()
                .get("/tables/" + ENCODED_BUCKET_ARN + "/" + NAMESPACE + "/" + TABLE_NAME + "/maintenance")
        .then()
                .statusCode(200)
                .body("configuration.icebergCompaction.status", equalTo("enabled"));
    }
}
