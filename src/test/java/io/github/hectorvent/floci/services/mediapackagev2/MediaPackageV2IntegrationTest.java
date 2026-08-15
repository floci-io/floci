package io.github.hectorvent.floci.services.mediapackagev2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MediaPackageV2IntegrationTest {

    private static String channelGroupArn;

    @Test
    @Order(1)
    void createChannelGroupEmitsUppercaseTags() {
        var response = given()
            .contentType("application/json")
            .body("""
                {"ChannelGroupName": "integration-group", "Description": "test group", "tags": {"team": "video"}}
                """)
        .when()
            .post("/channelGroup")
        .then()
            .statusCode(200)
            .body("Arn", containsString(":mediapackagev2:"))
            .body("Arn", containsString(":channelGroup/integration-group"))
            .body("ChannelGroupName", equalTo("integration-group"))
            .body("Description", equalTo("test group"))
            .body("EgressDomain", containsString(".mediapackagev2."))
            .body("CreatedAt", notNullValue())
            .body("Tags.team", equalTo("video"))
            .extract();
        channelGroupArn = response.path("Arn");
    }

    @Test
    @Order(2)
    void getChannelGroupEmitsLowercaseTags() {
        given()
        .when()
            .get("/channelGroup/integration-group")
        .then()
            .statusCode(200)
            .body("Arn", equalTo(channelGroupArn))
            .body("ChannelGroupName", equalTo("integration-group"))
            .body("tags.team", equalTo("video"))
            .body("Tags", nullValue());
    }

    @Test
    @Order(3)
    void duplicateNameRejected() {
        given()
            .contentType("application/json")
            .body("{\"ChannelGroupName\": \"integration-group\"}")
        .when()
            .post("/channelGroup")
        .then()
            .statusCode(409)
            .body("__type", equalTo("ConflictException"));
    }

    @Test
    @Order(4)
    void listTagsForChannelGroup() {
        given()
        .when()
            .get("/tags/" + channelGroupArn)
        .then()
            .statusCode(200)
            .body("tags.team", equalTo("video"));
    }

    @Test
    @Order(5)
    void omittedDescriptionStaysNull() {
        given()
            .contentType("application/json")
            .body("{\"ChannelGroupName\": \"no-desc-group\"}")
        .when()
            .post("/channelGroup")
        .then()
            .statusCode(200)
            .body("Description", nullValue());

        given()
        .when()
            .delete("/channelGroup/no-desc-group")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(6)
    void deleteChannelGroup() {
        given()
        .when()
            .delete("/channelGroup/integration-group")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/channelGroup/integration-group")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }
}
