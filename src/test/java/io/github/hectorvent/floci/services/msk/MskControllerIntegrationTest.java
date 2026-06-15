package io.github.hectorvent.floci.services.msk;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

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
}
