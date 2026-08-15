package io.github.hectorvent.floci.services.msk;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
class MskControllerIntegrationTest {

    @Test
    void createClusterV1EchoesRequestedKafkaVersion() {
        String clusterArn = given()
            .contentType("application/json")
            .body("""
                {"clusterName": "v1-version-test", "kafkaVersion": "3.5.1"}
                """)
        .when()
            .post("/v1/clusters")
        .then()
            .statusCode(200)
            .extract().path("clusterArn");

        given()
        .when()
            .get("/v1/clusters/{clusterArn}", clusterArn)
        .then()
            .statusCode(200)
            .body("clusterInfo.currentBrokerSoftwareInfo.kafkaVersion", equalTo("3.5.1"));
    }

    @Test
    void createClusterV2EchoesRequestedKafkaVersionFromProvisioned() {
        String clusterArn = given()
            .contentType("application/json")
            .body("""
                {"clusterName": "v2-version-test", "provisioned": {"kafkaVersion": "3.5.1"}}
                """)
        .when()
            .post("/api/v2/clusters")
        .then()
            .statusCode(200)
            .extract().path("clusterArn");

        given()
        .when()
            .get("/api/v2/clusters/{clusterArn}", clusterArn)
        .then()
            .statusCode(200)
            .body("clusterInfo.currentBrokerSoftwareInfo.kafkaVersion", equalTo("3.5.1"));
    }

    @Test
    void createClusterV2WithoutProvisionedFallsBackToDefaultKafkaVersion() {
        String clusterArn = given()
            .contentType("application/json")
            .body("""
                {"clusterName": "v2-default-version-test"}
                """)
        .when()
            .post("/api/v2/clusters")
        .then()
            .statusCode(200)
            .extract().path("clusterArn");

        given()
        .when()
            .get("/api/v2/clusters/{clusterArn}", clusterArn)
        .then()
            .statusCode(200)
            .body("clusterInfo.currentBrokerSoftwareInfo.kafkaVersion", equalTo("3.6.0"));
    }

    @Test
    void configurationCrudRoundTrip() {
        String properties = "auto.create.topics.enable=true\nlog.retention.hours=168";
        String propertiesB64 = Base64.getEncoder().encodeToString(properties.getBytes(StandardCharsets.UTF_8));

        String arn = given()
            .contentType("application/json")
            .body("""
                {"name": "test-config", "description": "a test config", "kafkaVersions": ["3.6.0"], "serverProperties": "%s"}
                """.formatted(propertiesB64))
        .when()
            .post("/v1/configurations")
        .then()
            .statusCode(200)
            .body("name", equalTo("test-config"))
            .body("state", equalTo("ACTIVE"))
            .body("latestRevision.revision", equalTo(1))
            .extract().path("arn");

        given()
        .when()
            .get("/v1/configurations/{arn}", arn)
        .then()
            .statusCode(200)
            .body("name", equalTo("test-config"))
            .body("description", equalTo("a test config"))
            .body("kafkaVersions", hasSize(1))
            .body("arn", equalTo(arn));

        given()
        .when()
            .get("/v1/configurations")
        .then()
            .statusCode(200)
            .body("configurations.name", hasItem("test-config"));

        given()
        .when()
            .delete("/v1/configurations/{arn}", arn)
        .then()
            .statusCode(200)
            .body("arn", equalTo(arn))
            .body("state", equalTo("DELETING"));

        given()
        .when()
            .get("/v1/configurations/{arn}", arn)
        .then()
            .statusCode(404);
    }

    @Test
    void createConfigurationRejectsNonBase64ServerProperties() {
        given()
            .contentType("application/json")
            .body("""
                {"name": "bad-config", "kafkaVersions": ["3.6.0"], "serverProperties": "not-valid-base64!!"}
                """)
        .when()
            .post("/v1/configurations")
        .then()
            .statusCode(400);
    }

    // A wrong-typed field must fail with an AWS-shaped 400, not an unhandled
    // ClassCastException surfacing as a 500.
    @Test
    void createConfigurationRejectsNonStringName() {
        given()
            .contentType("application/json")
            .body("""
                {"name": 123, "kafkaVersions": ["3.6.0"], "serverProperties": "cHJvcHM="}
                """)
        .when()
            .post("/v1/configurations")
        .then()
            .statusCode(400);
    }

    @Test
    void createConfigurationRejectsNonArrayKafkaVersions() {
        given()
            .contentType("application/json")
            .body("""
                {"name": "bad-config", "kafkaVersions": "3.6.0", "serverProperties": "cHJvcHM="}
                """)
        .when()
            .post("/v1/configurations")
        .then()
            .statusCode(400);
    }

    @Test
    void createConfigurationRejectsKafkaVersionsWithNonStringElements() {
        given()
            .contentType("application/json")
            .body("""
                {"name": "bad-config", "kafkaVersions": [3.6], "serverProperties": "cHJvcHM="}
                """)
        .when()
            .post("/v1/configurations")
        .then()
            .statusCode(400);
    }

    @Test
    void describeConfigurationReturnsNotFoundForUnknownArn() {
        given()
        .when()
            .get("/v1/configurations/{arn}", "arn:aws:kafka:us-east-1:000000000000:configuration/missing/id")
        .then()
            .statusCode(404);
    }
}
