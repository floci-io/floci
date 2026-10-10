package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * The {@code /_aws/ses} inspection endpoint follows LocalStack's contract
 * (<a href="https://docs.localstack.cloud/aws/services/ses/">LocalStack SES</a>): "A {@code DELETE}
 * call clears all messages from the memory. The query parameter {@code id} can be used to delete
 * only a specific message", and "Query parameters {@code id} and {@code email} can be used to
 * filter by message ID and message source respectively." Messages are listed oldest first.
 */
@QuarkusTest
class SesInspectionEndpointIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request";
    private static final String SENDER = "inspection-a@floci.test";
    private static final String OTHER_SENDER = "inspection-b@floci.test";

    @BeforeEach
    void emptyMailbox() {
        verify(SENDER);
        verify(OTHER_SENDER);
        given().delete("/_aws/ses").then().statusCode(200);
    }

    private static void verify(String address) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "VerifyEmailIdentity")
            .formParam("EmailAddress", address)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private static String send(String source, String to, String subject) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", AUTH)
            .formParam("Action", "SendEmail")
            .formParam("Source", source)
            .formParam("Destination.ToAddresses.member.1", to)
            .formParam("Message.Subject.Data", subject)
            .formParam("Message.Body.Text.Data", "body")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract()
            .xmlPath()
            .getString("SendEmailResponse.SendEmailResult.MessageId");
    }

    @Test
    void deleteWithId_removesOnlyThatMessage() {
        String first = send(SENDER, "to1@example.com", "msg-1");
        String second = send(SENDER, "to2@example.com", "msg-2");
        String third = send(SENDER, "to3@example.com", "msg-3");

        given().queryParam("id", second).when().delete("/_aws/ses").then().statusCode(200);

        given()
        .when()
            .get("/_aws/ses")
        .then()
            .statusCode(200)
            .body("messages.Id", contains(first, third));
    }

    @Test
    void deleteWithUnknownId_keepsEveryMessage() {
        String first = send(SENDER, "to1@example.com", "msg-1");

        given().queryParam("id", "no-such-message").when().delete("/_aws/ses").then().statusCode(200);

        given()
        .when()
            .get("/_aws/ses")
        .then()
            .statusCode(200)
            .body("messages.Id", contains(first));
    }

    @Test
    void deleteWithoutId_clearsEveryMessage() {
        send(SENDER, "to1@example.com", "msg-1");
        send(OTHER_SENDER, "to2@example.com", "msg-2");

        given().when().delete("/_aws/ses").then().statusCode(200);

        given()
        .when()
            .get("/_aws/ses")
        .then()
            .statusCode(200)
            .body("messages", empty());
    }

    @Test
    void getWithEmail_filtersByMessageSource() {
        String fromSender = send(SENDER, "to1@example.com", "msg-1");
        send(OTHER_SENDER, "to2@example.com", "msg-2");

        given()
            .queryParam("email", SENDER)
        .when()
            .get("/_aws/ses")
        .then()
            .statusCode(200)
            .body("messages.Id", contains(fromSender))
            .body("messages[0].Source", equalTo(SENDER));

        // A recipient is not a message source.
        given()
            .queryParam("email", "to2@example.com")
        .when()
            .get("/_aws/ses")
        .then()
            .statusCode(200)
            .body("messages", empty());
    }

    @Test
    void getWithIdAndEmail_appliesBothFilters() {
        String fromSender = send(SENDER, "to1@example.com", "msg-1");
        String fromOther = send(OTHER_SENDER, "to2@example.com", "msg-2");

        given()
            .queryParam("id", fromSender)
            .queryParam("email", SENDER)
        .when()
            .get("/_aws/ses")
        .then()
            .statusCode(200)
            .body("messages.Id", contains(fromSender));

        given()
            .queryParam("id", fromOther)
            .queryParam("email", SENDER)
        .when()
            .get("/_aws/ses")
        .then()
            .statusCode(200)
            .body("messages", empty());
    }

    @Test
    void get_listsMessagesOldestFirst() {
        List<String> sent = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            sent.add(send(SENDER, "to" + i + "@example.com", "msg-" + i));
        }

        given()
        .when()
            .get("/_aws/ses")
        .then()
            .statusCode(200)
            .body("messages", hasSize(8))
            .body("messages.Id", contains(sent.toArray()))
            .body("messages.Subject", contains("msg-1", "msg-2", "msg-3", "msg-4",
                    "msg-5", "msg-6", "msg-7", "msg-8"));
    }
}
