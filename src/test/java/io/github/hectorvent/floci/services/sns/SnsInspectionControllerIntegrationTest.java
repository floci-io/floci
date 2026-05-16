package io.github.hectorvent.floci.services.sns;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
class SnsInspectionControllerIntegrationTest {

    @Inject
    SnsService snsService;

    @BeforeEach
    void clear() {
        snsService.clearSentMessages();
    }

    @Test
    @DisplayName("GET /_aws/sns returns empty when no SMS published")
    void emptyWhenNoMessages() {
        given()
        .when().get("/_aws/sns")
        .then()
            .statusCode(200)
            .body("messages", hasSize(0));
    }

    @Test
    @DisplayName("publishing SMS makes it inspectable via GET /_aws/sns")
    void publishedSmsIsInspectable() {
        snsService.publish(null, null, "+5215551234567",
                "Your code is 123456", null, null, "us-east-1");

        given()
        .when().get("/_aws/sns")
        .then()
            .statusCode(200)
            .body("messages", hasSize(1))
            .body("messages[0].PhoneNumber", equalTo("+5215551234567"))
            .body("messages[0].Message", equalTo("Your code is 123456"))
            .body("messages[0].Region", equalTo("us-east-1"));
    }

    @Test
    @DisplayName("GET /_aws/sns?phone= filters by phone number")
    void filtersByPhoneNumber() {
        snsService.publish(null, null, "+5215551111111",
                "first", null, null, "us-east-1");
        snsService.publish(null, null, "+5215552222222",
                "second", null, null, "us-east-1");
        snsService.publish(null, null, "+5215553333333",
                "third", null, null, "us-east-1");

        given().queryParam("phone", "+5215552222222")
        .when().get("/_aws/sns")
        .then()
            .statusCode(200)
            .body("messages", hasSize(1))
            .body("messages[0].Message", equalTo("second"));
    }

    @Test
    @DisplayName("DELETE /_aws/sns clears all stored SMS")
    void deleteClearsAll() {
        snsService.publish(null, null, "+5215551234567",
                "to clear", null, null, "us-east-1");

        given()
        .when().delete("/_aws/sns")
        .then().statusCode(200);

        given()
        .when().get("/_aws/sns")
        .then()
            .statusCode(200)
            .body("messages", hasSize(0));
    }
}
