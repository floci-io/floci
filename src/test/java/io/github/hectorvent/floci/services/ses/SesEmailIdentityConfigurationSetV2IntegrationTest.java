package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the SES v2 {@code PutEmailIdentityConfigurationSetAttributes} action:
 * associating a default configuration set with an email identity, surfacing it through
 * {@code GetEmailIdentity}, clearing it, and the resulting event-publishing behavior — a send
 * from the identity with no explicit configuration set must route through the identity's default
 * configuration set's event destinations.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesEmailIdentityConfigurationSetV2IntegrationTest {

    private static final String SES_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";
    private static final String SNS_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/sns/aws4_request";
    private static final String SQS_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/sqs/aws4_request";
    private static final String JSON_10 = "application/x-amz-json-1.0";

    private static final String IDENTITY = "cs-attr-sender@floci.test";
    private static final String CS = "cs-attr-default-cs";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String topicArn;
    private static String queueUrl;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void setup_identity_configSet_snsEventDestination() {
        given().contentType("application/json").header("Authorization", SES_AUTH)
                .body("{\"EmailIdentity\":\"" + IDENTITY + "\"}")
        .when().post("/v2/email/identities").then().statusCode(200);

        given().contentType("application/json").header("Authorization", SES_AUTH)
                .body("{\"ConfigurationSetName\":\"" + CS + "\"}")
        .when().post("/v2/email/configuration-sets").then().statusCode(200);

        queueUrl = given().contentType(JSON_10).header("Authorization", SQS_AUTH)
                .header("X-Amz-Target", "AmazonSQS.CreateQueue")
                .body("{\"QueueName\":\"cs-attr-queue\"}")
        .when().post("/").then().statusCode(200)
                .extract().jsonPath().getString("QueueUrl");
        String queueArn = given().contentType(JSON_10).header("Authorization", SQS_AUTH)
                .header("X-Amz-Target", "AmazonSQS.GetQueueAttributes")
                .body("{\"QueueUrl\":\"" + queueUrl + "\",\"AttributeNames\":[\"All\"]}")
        .when().post("/").then().statusCode(200)
                .extract().jsonPath().getString("Attributes.QueueArn");

        topicArn = given().contentType(JSON_10).header("Authorization", SNS_AUTH)
                .header("X-Amz-Target", "SNS_20100331.CreateTopic")
                .body("{\"Name\":\"cs-attr-topic\"}")
        .when().post("/").then().statusCode(200)
                .extract().jsonPath().getString("TopicArn");
        assertNotNull(topicArn);

        given().contentType(JSON_10).header("Authorization", SNS_AUTH)
                .header("X-Amz-Target", "SNS_20100331.Subscribe")
                .body("{\"TopicArn\":\"" + topicArn + "\",\"Protocol\":\"sqs\",\"Endpoint\":\"" + queueArn + "\"}")
        .when().post("/").then().statusCode(200);

        given().contentType("application/json").header("Authorization", SES_AUTH)
                .body("""
                    {
                      "EventDestinationName": "ed-sns",
                      "EventDestination": {
                        "Enabled": true,
                        "MatchingEventTypes": ["SEND", "BOUNCE"],
                        "SnsDestination": {"TopicArn": "%s"}
                      }
                    }
                    """.formatted(topicArn))
        .when().post("/v2/email/configuration-sets/" + CS + "/event-destinations")
        .then().statusCode(200);
    }

    @Test
    @Order(2)
    void putEmailIdentityConfigurationSet_associatesAndSurfacesInGet() {
        given().contentType("application/json").header("Authorization", SES_AUTH)
                .body("{\"ConfigurationSetName\":\"" + CS + "\"}")
        .when().put("/v2/email/identities/" + IDENTITY + "/configuration-set")
        .then().statusCode(200);

        given().header("Authorization", SES_AUTH)
        .when().get("/v2/email/identities/" + IDENTITY)
        .then().statusCode(200)
                .body("ConfigurationSetName", equalTo(CS));
    }

    @Test
    @Order(3)
    void sendWithoutConfigSet_routesThroughIdentityDefault_publishesEvent() throws Exception {
        drainQueue();
        // No ConfigurationSetName on the send — it must resolve to the identity's default.
        given().contentType("application/json").header("Authorization", SES_AUTH)
                .body("""
                    {
                      "FromEmailAddress": "%s",
                      "Destination": {"ToAddresses": ["bounce@simulator.amazonses.com"]},
                      "Content": {"Simple": {"Subject": {"Data": "cs-attr"}, "Body": {"Text": {"Data": "hi"}}}}
                    }
                    """.formatted(IDENTITY))
        .when().post("/v2/email/outbound-emails").then().statusCode(200);

        List<JsonNode> events = receiveEvents(2);
        assertTrue(events.stream().anyMatch(e -> "Send".equals(e.path("eventType").asText())),
                "Send event must publish via the identity's default configuration set");
        JsonNode bounce = events.stream()
                .filter(e -> "Bounce".equals(e.path("eventType").asText()))
                .findFirst().orElseThrow();
        assertEquals(CS, bounce.path("mail").path("tags").path("ses:configuration-set").get(0).asText());
    }

    @Test
    @Order(4)
    void clearConfigurationSet_removesAssociation() {
        given().contentType("application/json").header("Authorization", SES_AUTH)
                .body("{}")
        .when().put("/v2/email/identities/" + IDENTITY + "/configuration-set")
        .then().statusCode(200);

        given().header("Authorization", SES_AUTH)
        .when().get("/v2/email/identities/" + IDENTITY)
        .then().statusCode(200)
                .body("$", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasKey("ConfigurationSetName")));
    }

    @Test
    @Order(5)
    void sendRawContentWithoutConfigSet_routesThroughIdentityDefault() throws Exception {
        // Re-associate (Order 4 cleared it) so this test is self-contained.
        given().contentType("application/json").header("Authorization", SES_AUTH)
                .body("{\"ConfigurationSetName\":\"" + CS + "\"}")
        .when().put("/v2/email/identities/" + IDENTITY + "/configuration-set")
        .then().statusCode(200);

        drainQueue();
        String raw = "From: " + IDENTITY + "\r\n"
                + "To: bounce@simulator.amazonses.com\r\n"
                + "Subject: cs-attr-raw\r\n\r\nbody";
        String rawB64 = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        // SendEmail with Raw content and no ConfigurationSetName must route through the
        // identity's default, just like the Simple-content path.
        given().contentType("application/json").header("Authorization", SES_AUTH)
                .body("""
                    {
                      "FromEmailAddress": "%s",
                      "Destination": {"ToAddresses": ["bounce@simulator.amazonses.com"]},
                      "Content": {"Raw": {"Data": "%s"}}
                    }
                    """.formatted(IDENTITY, rawB64))
        .when().post("/v2/email/outbound-emails").then().statusCode(200);

        List<JsonNode> events = receiveEvents(2);
        JsonNode bounce = events.stream()
                .filter(e -> "Bounce".equals(e.path("eventType").asText()))
                .findFirst().orElseThrow();
        assertEquals(CS, bounce.path("mail").path("tags").path("ses:configuration-set").get(0).asText(),
                "Raw-content sends must also route through the identity default configuration set");
    }

    @Test
    @Order(6)
    void unknownIdentity_returns404() {
        given().contentType("application/json").header("Authorization", SES_AUTH)
                .body("{\"ConfigurationSetName\":\"" + CS + "\"}")
        .when().put("/v2/email/identities/cs-attr-ghost@floci.test/configuration-set")
        .then().statusCode(404)
                .body("message", equalTo("Identity <cs-attr-ghost@floci.test> does not exist."));
    }

    @Test
    @Order(7)
    void unknownConfigurationSet_returns404() {
        given().contentType("application/json").header("Authorization", SES_AUTH)
                .body("{\"ConfigurationSetName\":\"cs-attr-missing\"}")
        .when().put("/v2/email/identities/" + IDENTITY + "/configuration-set")
        .then().statusCode(404)
                .body("message", equalTo("Configuration set <cs-attr-missing> does not exist."));
    }

    @Test
    @Order(8)
    void sendRawContentWithoutFromAddress_routesThroughIdentityDefault() throws Exception {
        // Re-associate so this test is self-contained.
        given().contentType("application/json").header("Authorization", SES_AUTH)
                .body("{\"ConfigurationSetName\":\"" + CS + "\"}")
        .when().put("/v2/email/identities/" + IDENTITY + "/configuration-set")
        .then().statusCode(200);

        drainQueue();
        // Raw content with NO FromEmailAddress: the sender is taken only from the MIME "From".
        // The identity's default configuration set must still be resolved from that header.
        String raw = "From: " + IDENTITY + "\r\n"
                + "To: bounce@simulator.amazonses.com\r\n"
                + "Subject: cs-attr-raw-nofrom\r\n\r\nbody";
        String rawB64 = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        given().contentType("application/json").header("Authorization", SES_AUTH)
                .body("""
                    {
                      "Destination": {"ToAddresses": ["bounce@simulator.amazonses.com"]},
                      "Content": {"Raw": {"Data": "%s"}}
                    }
                    """.formatted(rawB64))
        .when().post("/v2/email/outbound-emails").then().statusCode(200);

        List<JsonNode> events = receiveEvents(2);
        JsonNode bounce = events.stream()
                .filter(e -> "Bounce".equals(e.path("eventType").asText()))
                .findFirst().orElseThrow();
        assertEquals(CS, bounce.path("mail").path("tags").path("ses:configuration-set").get(0).asText(),
                "A Raw send without FromEmailAddress must route through the identity default "
                        + "configuration set resolved from the MIME From header");
    }

    @Test
    @Order(9)
    void putConfigurationSet_whitespaceOnlyBody_returnsSerializationExceptionAndKeepsAssociation() {
        // Re-associate so we can confirm a whitespace-only body does NOT clear.
        given().contentType("application/json").header("Authorization", SES_AUTH)
                .body("{\"ConfigurationSetName\":\"" + CS + "\"}")
        .when().put("/v2/email/identities/" + IDENTITY + "/configuration-set")
        .then().statusCode(200);

        // A whitespace-only body is a serialization error on real AWS, not a silent clear.
        given().contentType("application/json").header("Authorization", SES_AUTH)
                .body(" \n")
        .when().put("/v2/email/identities/" + IDENTITY + "/configuration-set")
        .then().statusCode(400)
                .body("__type", equalTo("SerializationException"));

        // The association is unchanged (the invalid body neither cleared nor updated it).
        given().header("Authorization", SES_AUTH)
        .when().get("/v2/email/identities/" + IDENTITY)
        .then().statusCode(200)
                .body("ConfigurationSetName", equalTo(CS));
    }

    @Test
    @Order(10)
    void sendThroughIdentityWhoseDefaultConfigSetWasDeleted_returns400() {
        // Verified against the SES Developer Guide: deleting the configuration set that is an
        // identity's default, then sending through that identity, fails with a bad-request error.
        String id = "cs-attr-stale@floci.test";
        String cs = "cs-attr-stale-cs";
        given().contentType("application/json").header("Authorization", SES_AUTH)
                .body("{\"EmailIdentity\":\"" + id + "\"}")
        .when().post("/v2/email/identities").then().statusCode(200);
        given().contentType("application/json").header("Authorization", SES_AUTH)
                .body("{\"ConfigurationSetName\":\"" + cs + "\"}")
        .when().post("/v2/email/configuration-sets").then().statusCode(200);
        given().contentType("application/json").header("Authorization", SES_AUTH)
                .body("{\"ConfigurationSetName\":\"" + cs + "\"}")
        .when().put("/v2/email/identities/" + id + "/configuration-set").then().statusCode(200);

        // Delete the configuration set that is now the identity's default.
        given().header("Authorization", SES_AUTH)
        .when().delete("/v2/email/configuration-sets/" + cs).then().statusCode(200);

        // A send with no explicit ConfigurationSetName must fail rather than silently drop it.
        given().contentType("application/json").header("Authorization", SES_AUTH)
                .body("""
                    {
                      "FromEmailAddress": "%s",
                      "Destination": {"ToAddresses": ["bounce@simulator.amazonses.com"]},
                      "Content": {"Simple": {"Subject": {"Data": "stale"}, "Body": {"Text": {"Data": "hi"}}}}
                    }
                    """.formatted(id))
        .when().post("/v2/email/outbound-emails").then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("Configuration set <" + cs + "> does not exist."));
    }

    private void drainQueue() {
        for (int i = 0; i < 5; i++) {
            Response r = given().contentType(JSON_10).header("Authorization", SQS_AUTH)
                    .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                    .body("{\"QueueUrl\":\"" + queueUrl + "\",\"MaxNumberOfMessages\":10,\"WaitTimeSeconds\":0}")
            .when().post("/").then().statusCode(200).extract().response();
            List<String> handles = r.jsonPath().getList("Messages.ReceiptHandle");
            if (handles == null || handles.isEmpty()) {
                return;
            }
            deleteMessages(handles);
        }
    }

    private List<JsonNode> receiveEvents(int expectedAtLeast) throws Exception {
        List<JsonNode> events = new ArrayList<>();
        for (int attempt = 0; attempt < 10 && events.size() < expectedAtLeast; attempt++) {
            Response r = given().contentType(JSON_10).header("Authorization", SQS_AUTH)
                    .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                    .body("{\"QueueUrl\":\"" + queueUrl + "\",\"MaxNumberOfMessages\":10,\"WaitTimeSeconds\":1}")
            .when().post("/").then().statusCode(200).extract().response();
            List<String> bodies = r.jsonPath().getList("Messages.Body");
            List<String> handles = r.jsonPath().getList("Messages.ReceiptHandle");
            if (bodies == null || bodies.isEmpty()) {
                continue;
            }
            for (String body : bodies) {
                JsonNode wrapper = MAPPER.readTree(body);
                events.add(MAPPER.readTree(wrapper.path("Message").asText()));
            }
            deleteMessages(handles);
        }
        return events;
    }

    private void deleteMessages(List<String> receiptHandles) {
        StringBuilder entries = new StringBuilder();
        for (int i = 0; i < receiptHandles.size(); i++) {
            if (i > 0) {
                entries.append(",");
            }
            entries.append("{\"Id\":\"m").append(i).append("\",\"ReceiptHandle\":\"")
                    .append(receiptHandles.get(i)).append("\"}");
        }
        given().contentType(JSON_10).header("Authorization", SQS_AUTH)
                .header("X-Amz-Target", "AmazonSQS.DeleteMessageBatch")
                .body("{\"QueueUrl\":\"" + queueUrl + "\",\"Entries\":[" + entries + "]}")
        .when().post("/").then().statusCode(200);
    }
}
