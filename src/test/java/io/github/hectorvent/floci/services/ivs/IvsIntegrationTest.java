package io.github.hectorvent.floci.services.ivs;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IvsIntegrationTest {

    private static String channelArn;
    private static String keyPairArn;
    private static String recordingConfigurationArn;

    @Test
    @Order(1)
    void createChannel() {
        var response = given()
            .contentType("application/json")
            .body("""
                {"name": "integration-channel", "tags": {"team": "video"}}
                """)
        .when()
            .post("/CreateChannel")
        .then()
            .statusCode(200)
            .body("channel.arn", containsString(":ivs:"))
            .body("channel.arn", containsString(":channel/"))
            .body("channel.name", equalTo("integration-channel"))
            .body("channel.latencyMode", equalTo("LOW"))
            .body("channel.type", equalTo("STANDARD"))
            .body("channel.ingestEndpoint", notNullValue())
            .body("channel.playbackUrl", notNullValue())
            .body("channel.tags.team", equalTo("video"))
            .body("streamKey.arn", containsString(":stream-key/"))
            .body("streamKey.value", notNullValue())
            .extract();
        channelArn = response.path("channel.arn");
    }

    @Test
    @Order(2)
    void getChannel() {
        given()
            .contentType("application/json")
            .body("{\"arn\": \"" + channelArn + "\"}")
        .when()
            .post("/GetChannel")
        .then()
            .statusCode(200)
            .body("channel.arn", equalTo(channelArn))
            .body("channel.name", equalTo("integration-channel"));
    }

    @Test
    @Order(3)
    void getMissingChannelReturnsResourceNotFound() {
        given()
            .contentType("application/json")
            .body("{\"arn\": \"arn:aws:ivs:us-east-1:000000000000:channel/missing000000\"}")
        .when()
            .post("/GetChannel")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(4)
    void listTagsForChannel() {
        given()
        .when()
            .get("/tags/" + channelArn)
        .then()
            .statusCode(200)
            .body("tags.team", equalTo("video"));
    }

    @Test
    @Order(5)
    void importAndGetPlaybackKeyPair() {
        var response = given()
            .contentType("application/json")
            .body("""
                {"publicKeyMaterial": "placeholder", "name": "integration-key"}
                """)
        .when()
            .post("/ImportPlaybackKeyPair")
        .then()
            .statusCode(200)
            .body("keyPair.arn", containsString(":playback-key/"))
            .body("keyPair.name", equalTo("integration-key"))
            .body("keyPair.fingerprint", containsString(":"))
            .extract();
        keyPairArn = response.path("keyPair.arn");

        given()
            .contentType("application/json")
            .body("{\"arn\": \"" + keyPairArn + "\"}")
        .when()
            .post("/GetPlaybackKeyPair")
        .then()
            .statusCode(200)
            .body("keyPair.arn", equalTo(keyPairArn));
    }

    @Test
    @Order(6)
    void createAndGetRecordingConfiguration() {
        var response = given()
            .contentType("application/json")
            .body("""
                {"destinationConfiguration": {"s3": {"bucketName": "placeholder"}},
                 "tags": {"team": "video"}}
                """)
        .when()
            .post("/CreateRecordingConfiguration")
        .then()
            .statusCode(200)
            .body("recordingConfiguration.arn", containsString(":recording-configuration/"))
            .body("recordingConfiguration.state", equalTo("ACTIVE"))
            .body("recordingConfiguration.destinationConfiguration.s3.bucketName", equalTo("placeholder"))
            .extract();
        recordingConfigurationArn = response.path("recordingConfiguration.arn");

        given()
            .contentType("application/json")
            .body("{\"arn\": \"" + recordingConfigurationArn + "\"}")
        .when()
            .post("/GetRecordingConfiguration")
        .then()
            .statusCode(200)
            .body("recordingConfiguration.arn", equalTo(recordingConfigurationArn))
            .body("recordingConfiguration.state", equalTo("ACTIVE"));
    }

    @Test
    @Order(7)
    void deleteResources() {
        given()
            .contentType("application/json")
            .body("{\"arn\": \"" + recordingConfigurationArn + "\"}")
        .when()
            .post("/DeleteRecordingConfiguration")
        .then()
            .statusCode(204);

        given()
            .contentType("application/json")
            .body("{\"arn\": \"" + keyPairArn + "\"}")
        .when()
            .post("/DeletePlaybackKeyPair")
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .body("{\"arn\": \"" + channelArn + "\"}")
        .when()
            .post("/DeleteChannel")
        .then()
            .statusCode(204);

        given()
            .contentType("application/json")
            .body("{\"arn\": \"" + channelArn + "\"}")
        .when()
            .post("/GetChannel")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }
}
