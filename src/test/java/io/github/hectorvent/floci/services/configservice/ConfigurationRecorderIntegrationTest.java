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
class ConfigurationRecorderIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "StarlingDoveService.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    // --- Configuration Recorder ---

    @Test
    @Order(1)
    void putConfigurationRecorder() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "PutConfigurationRecorder")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ConfigurationRecorder": {
                        "name": "default",
                        "roleARN": "arn:aws:iam::123456789012:role/config-role",
                        "recordingGroup": {
                            "allSupported": true,
                            "includeGlobalResourceTypes": false
                        }
                    }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(2)
    void describeConfigurationRecorders() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeConfigurationRecorders")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigurationRecorderNames": ["default"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConfigurationRecorders", hasSize(1))
            .body("ConfigurationRecorders[0].name", equalTo("default"))
            .body("ConfigurationRecorders[0].roleARN", equalTo("arn:aws:iam::123456789012:role/config-role"))
            .body("ConfigurationRecorders[0].recordingGroup.allSupported", equalTo(true));

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeConfigurationRecorders")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConfigurationRecorders", hasSize(1));
    }

    @Test
    @Order(3)
    void startConfigurationRecorder() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "StartConfigurationRecorder")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigurationRecorderName": "default"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(4)
    void describeConfigurationRecorderStatusAfterStart() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeConfigurationRecorderStatus")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigurationRecorderNames": ["default"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConfigurationRecordersStatus", hasSize(1))
            .body("ConfigurationRecordersStatus[0].name", equalTo("default"))
            .body("ConfigurationRecordersStatus[0].recording", equalTo(true))
            .body("ConfigurationRecordersStatus[0].lastStatus", equalTo("SUCCESS"))
            .body("ConfigurationRecordersStatus[0].lastStartTime", notNullValue());
    }

    @Test
    @Order(5)
    void stopConfigurationRecorder() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "StopConfigurationRecorder")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigurationRecorderName": "default"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(6)
    void describeConfigurationRecorderStatusAfterStop() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeConfigurationRecorderStatus")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigurationRecorderNames": ["default"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConfigurationRecordersStatus[0].recording", equalTo(false))
            .body("ConfigurationRecordersStatus[0].lastStopTime", notNullValue());
    }

    @Test
    @Order(7)
    void startNonexistentConfigurationRecorder() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "StartConfigurationRecorder")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigurationRecorderName": "no-such-recorder"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("NoSuchConfigurationRecorderException"));
    }

    // --- Delivery Channel ---

    @Test
    @Order(8)
    void putDeliveryChannel() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "PutDeliveryChannel")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "DeliveryChannel": {
                        "name": "default",
                        "s3BucketName": "my-config-bucket",
                        "s3KeyPrefix": "config-snapshots",
                        "configSnapshotDeliveryProperties": {
                            "deliveryFrequency": "Twelve_Hours"
                        }
                    }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(9)
    void describeDeliveryChannels() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeDeliveryChannels")
            .contentType(CONTENT_TYPE)
            .body("""
                {"DeliveryChannelNames": ["default"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeliveryChannels", hasSize(1))
            .body("DeliveryChannels[0].name", equalTo("default"))
            .body("DeliveryChannels[0].s3BucketName", equalTo("my-config-bucket"))
            .body("DeliveryChannels[0].s3KeyPrefix", equalTo("config-snapshots"))
            .body("DeliveryChannels[0].configSnapshotDeliveryProperties.deliveryFrequency", equalTo("Twelve_Hours"));

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeDeliveryChannels")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeliveryChannels", hasSize(1));
    }
}
