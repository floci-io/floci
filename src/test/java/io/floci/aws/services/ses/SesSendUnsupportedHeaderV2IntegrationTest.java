package io.floci.aws.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.floci.aws.services.ses.SesV2Requests.DESTINATION;
import static io.floci.aws.services.ses.SesV2Requests.post;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * SES V2 SendEmail refuses a {@code Content.Simple.Headers} entry that names a header SES sets
 * itself, ahead of the configuration set lookup and the sender verification.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesSendUnsupportedHeaderV2IntegrationTest {

    private static final String SENDER = "header-sender@example.com";
    private static final String UNVERIFIED_SENDER = "header-unverified@example.com";

    @Test
    @Order(1)
    void createSender() {
        post("/v2/email/identities", "{\"EmailIdentity\": \"" + SENDER + "\"}").statusCode(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"From", "To", "Cc", "Bcc", "Subject", "Date", "Message-ID", "Reply-To",
            "Return-Path", "MIME-Version", "Content-Type", "from"})
    void unsupportedHeader_isRefusedWithItsNameAsSent(String name) {
        send(SENDER, name, "")
                .statusCode(400)
                .header("x-amzn-ErrorType", equalTo("BadRequestException"))
                .body("message", equalTo("Header <" + name + "> is not supported"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Sender", "DKIM-Signature", "Received", "List-Unsubscribe", "List-Unsubscribe-Post",
            "X-Custom"})
    void otherHeader_isAcceptedAndCaptured(String name) {
        String messageId = send(SENDER, name, "").statusCode(200)
                .body("MessageId", notNullValue())
                .extract().path("MessageId");

        given().when().get("/_aws/ses?id=" + messageId).then().statusCode(200)
                .body("messages", hasSize(1))
                .body("messages[0].Headers.find { it.Name == '" + name + "' }.Value", equalTo("probe-value"));
    }

    @Test
    void unsupportedHeader_isRefusedBeforeAnUnknownConfigurationSet() {
        send(SENDER, "From", "\"ConfigurationSetName\": \"header-missing-set\",")
                .statusCode(400)
                .body("message", equalTo("Header <From> is not supported"));
    }

    @Test
    void supportedHeader_leavesAnUnknownConfigurationSetToAnswer() {
        send(SENDER, "X-Custom", "\"ConfigurationSetName\": \"header-missing-set\",")
                .statusCode(404)
                .header("x-amzn-ErrorType", equalTo("NotFoundException"))
                .body("message", equalTo("Configuration set <header-missing-set> does not exist."));
    }

    @Test
    void unsupportedHeader_isRefusedBeforeAnUnverifiedSender() {
        send(UNVERIFIED_SENDER, "Subject", "")
                .statusCode(400)
                .header("x-amzn-ErrorType", equalTo("BadRequestException"))
                .body("message", equalTo("Header <Subject> is not supported"));
    }

    @Test
    void supportedHeader_leavesAnUnverifiedSenderToAnswer() {
        send(UNVERIFIED_SENDER, "X-Custom", "")
                .statusCode(400)
                .header("x-amzn-ErrorType", equalTo("MessageRejected"))
                .body("message", startsWith("Email address is not verified."));
    }

    @Test
    void secondHeader_isRefusedWhenTheFirstIsSupported() {
        post("/v2/email/outbound-emails", """
                {"FromEmailAddress": "%s",
                 %s,
                 "Content": {"Simple": {
                    "Subject": {"Data": "Headers"},
                    "Body": {"Text": {"Data": "body"}},
                    "Headers": [{"Name": "X-Custom", "Value": "a"}, {"Name": "Reply-To", "Value": "b"}]}}}
                """.formatted(SENDER, DESTINATION))
                .statusCode(400)
                .body("message", equalTo("Header <Reply-To> is not supported"));
    }

    private static ValidatableResponse send(String sender, String headerName, String extraMember) {
        return post("/v2/email/outbound-emails", """
                {"FromEmailAddress": "%s",
                 %s
                 %s,
                 "Content": {"Simple": {
                    "Subject": {"Data": "Headers"},
                    "Body": {"Text": {"Data": "body"}},
                    "Headers": [{"Name": "%s", "Value": "probe-value"}]}}}
                """.formatted(sender, extraMember, DESTINATION, headerName));
    }
}
