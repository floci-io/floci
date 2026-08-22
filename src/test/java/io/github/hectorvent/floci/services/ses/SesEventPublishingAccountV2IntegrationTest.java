package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a SES send authenticated as a non-default account reports THAT account in the event
 * payload — {@code mail.sendingAccountId} and {@code mail.sourceArn} — rather than the fixed default.
 * Floci derives the account from a 12-digit access key id (the LocalStack multi-account convention),
 * so every resource here (queue, topic, identity, configuration set) is created under the same
 * non-default account and is account-isolated by the storage layer.
 */
@QuarkusTest
class SesEventPublishingAccountV2IntegrationTest {

    private static final String ACCOUNT = "210987654321";
    private static final String SES_AUTH =
            "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260101/us-east-1/ses/aws4_request";
    private static final String SNS_AUTH =
            "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260101/us-east-1/sns/aws4_request";
    private static final String SQS_AUTH =
            "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260101/us-east-1/sqs/aws4_request";
    private static final String AWS_JSON = "application/x-amz-json-1.0";
    private static final String SENDER = "acct-sender@floci.test";
    private static final String CS = "acct-evt-cs";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void send_reportsCallerAccountInEventPayload() throws Exception {
        String queueUrl = given().contentType(AWS_JSON).header("Authorization", SQS_AUTH)
                .header("X-Amz-Target", "AmazonSQS.CreateQueue")
                .body("{\"QueueName\":\"acct-ses-events-queue\"}")
        .when().post("/").then().statusCode(200)
                .extract().jsonPath().getString("QueueUrl");
        assertNotNull(queueUrl);

        String queueArn = given().contentType(AWS_JSON).header("Authorization", SQS_AUTH)
                .header("X-Amz-Target", "AmazonSQS.GetQueueAttributes")
                .body("{\"QueueUrl\":\"" + queueUrl + "\",\"AttributeNames\":[\"All\"]}")
        .when().post("/").then().statusCode(200)
                .extract().jsonPath().getString("Attributes.QueueArn");
        // The queue ARN carries the caller's account.
        assertEquals("arn:aws:sqs:us-east-1:" + ACCOUNT + ":acct-ses-events-queue", queueArn);

        String topicArn = given().contentType(AWS_JSON).header("Authorization", SNS_AUTH)
                .header("X-Amz-Target", "SNS_20100331.CreateTopic")
                .body("{\"Name\":\"acct-ses-events-topic\"}")
        .when().post("/").then().statusCode(200)
                .extract().jsonPath().getString("TopicArn");

        given().contentType(AWS_JSON).header("Authorization", SNS_AUTH)
                .header("X-Amz-Target", "SNS_20100331.Subscribe")
                .body("{\"TopicArn\":\"" + topicArn + "\",\"Protocol\":\"sqs\",\"Endpoint\":\"" + queueArn + "\"}")
        .when().post("/").then().statusCode(200);

        given().contentType("application/json").header("Authorization", SES_AUTH)
                .body("{\"EmailIdentity\":\"" + SENDER + "\"}")
        .when().post("/v2/email/identities").then().statusCode(200);

        given().contentType("application/json").header("Authorization", SES_AUTH)
                .body("{\"ConfigurationSetName\":\"" + CS + "\"}")
        .when().post("/v2/email/configuration-sets").then().statusCode(200);

        given().contentType("application/json").header("Authorization", SES_AUTH)
                .body("""
                    {"EventDestinationName":"ed-sns","EventDestination":{"Enabled":true,
                     "MatchingEventTypes":["SEND","DELIVERY"],"SnsDestination":{"TopicArn":"%s"}}}
                    """.formatted(topicArn))
        .when().post("/v2/email/configuration-sets/" + CS + "/event-destinations").then().statusCode(200);

        given().contentType("application/json").header("Authorization", SES_AUTH)
                .body("""
                    {"FromEmailAddress":"%s","Destination":{"ToAddresses":["success@simulator.amazonses.com"]},
                     "ConfigurationSetName":"%s",
                     "Content":{"Simple":{"Subject":{"Data":"acct-evt"},"Body":{"Text":{"Data":"hi"}}}}}
                    """.formatted(SENDER, CS))
        .when().post("/v2/email/outbound-emails").then().statusCode(200);

        List<JsonNode> events = receiveEvents(queueUrl, 2);
        assertTrue(events.size() >= 1, "expected at least one SES event");
        for (JsonNode evt : events) {
            JsonNode mail = evt.path("mail");
            assertEquals(ACCOUNT, mail.path("sendingAccountId").asText(),
                    "event should report the caller's account, not the default");
            assertEquals("arn:aws:ses:us-east-1:" + ACCOUNT + ":identity/" + SENDER,
                    mail.path("sourceArn").asText());
        }
    }

    private List<JsonNode> receiveEvents(String queueUrl, int expectedAtLeast) throws Exception {
        List<JsonNode> events = new ArrayList<>();
        for (int attempt = 0; attempt < 10 && events.size() < expectedAtLeast; attempt++) {
            Response r = given().contentType(AWS_JSON).header("Authorization", SQS_AUTH)
                    .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                    .body("{\"QueueUrl\":\"" + queueUrl + "\",\"MaxNumberOfMessages\":10,\"WaitTimeSeconds\":1}")
            .when().post("/").then().statusCode(200).extract().response();
            List<String> bodies = r.jsonPath().getList("Messages.Body");
            if (bodies == null) {
                continue;
            }
            for (String body : bodies) {
                JsonNode snsWrapper = MAPPER.readTree(body);
                events.add(MAPPER.readTree(snsWrapper.path("Message").asText()));
            }
        }
        return events;
    }
}
