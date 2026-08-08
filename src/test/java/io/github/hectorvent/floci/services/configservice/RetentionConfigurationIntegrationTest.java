package io.github.hectorvent.floci.services.configservice;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RetentionConfigurationIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "StarlingDoveService.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void putRetentionConfiguration() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "PutRetentionConfiguration")
            .contentType(CONTENT_TYPE)
            .body("""
                {"RetentionPeriodInDays": 365}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RetentionConfiguration.Name", equalTo("default"))
            .body("RetentionConfiguration.RetentionPeriodInDays", equalTo(365));
    }

    @Test
    @Order(2)
    void describeRetentionConfigurations() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeRetentionConfigurations")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RetentionConfigurations", hasSize(1))
            .body("RetentionConfigurations[0].Name", equalTo("default"))
            .body("RetentionConfigurations[0].RetentionPeriodInDays", equalTo(365));
    }

    @Test
    @Order(3)
    void putRetentionConfigurationBelowMinimumIsRejected() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "PutRetentionConfiguration")
            .contentType(CONTENT_TYPE)
            .body("""
                {"RetentionPeriodInDays": 29}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterValueException"));
    }

    @Test
    @Order(4)
    void describeRetentionConfigurationsUnknownName() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeRetentionConfigurations")
            .contentType(CONTENT_TYPE)
            .body("""
                {"RetentionConfigurationNames": ["no-such-retention"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("NoSuchRetentionConfigurationException"));
    }

    @Test
    @Order(5)
    void deleteRetentionConfiguration() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DeleteRetentionConfiguration")
            .contentType(CONTENT_TYPE)
            .body("""
                {"RetentionConfigurationName": "default"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeRetentionConfigurations")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RetentionConfigurations", hasSize(0));
    }

    @Test
    @Order(6)
    void deleteRetentionConfigurationAgainIsRejected() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DeleteRetentionConfiguration")
            .contentType(CONTENT_TYPE)
            .body("""
                {"RetentionConfigurationName": "default"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("NoSuchRetentionConfigurationException"));
    }
}
