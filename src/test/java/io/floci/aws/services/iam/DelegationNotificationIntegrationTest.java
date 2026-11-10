package io.floci.aws.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The delegation workflow end to end, with the partner reading its side of it off an SNS topic.
 *
 * <p>This is the half that had no way through before: the exchange token is answered by no
 * operation on the family, so a partner can only obtain it from the {@code ExchangeToken}
 * notification. The test subscribes a queue to the topic the request names, drives the lifecycle
 * over the wire, and exchanges the token it reads out of the notification, which is the whole loop
 * a real integration runs.
 */
@QuarkusTest
class DelegationNotificationIntegrationTest {

    private static final String PARTNER = "000000000001";
    private static final String CUSTOMER = "000000000002";
    private static final String PARTNER_AUTH = "AWS4-HMAC-SHA256 Credential=" + PARTNER
            + "/20261021/us-east-1/iam/aws4_request, SignedHeaders=host, Signature=abc";
    private static final String CUSTOMER_AUTH = "AWS4-HMAC-SHA256 Credential=" + CUSTOMER
            + "/20261021/us-east-1/iam/aws4_request, SignedHeaders=host, Signature=abc";
    private static final String PARTNER_STS_AUTH = "AWS4-HMAC-SHA256 Credential=" + PARTNER
            + "/20261021/us-east-1/sts/aws4_request, SignedHeaders=host, Signature=abc";
    private static final String SNS_AUTH = "AWS4-HMAC-SHA256 Credential=" + PARTNER
            + "/20261021/us-east-1/sns/aws4_request, SignedHeaders=host, Signature=abc";
    private static final String SQS_AUTH = "AWS4-HMAC-SHA256 Credential=" + PARTNER
            + "/20261021/us-east-1/sqs/aws4_request, SignedHeaders=host, Signature=abc";

    private static final String JSON_10 = "application/x-amz-json-1.0";
    private static final String TEMPLATE = "arn:aws:iam::partner:policy/template/example";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String workflowId() {
        return "workflow-" + Long.toString(System.nanoTime(), 36);
    }

    private static RequestSpecification sqs(String target, String body) {
        return given().contentType(JSON_10).header("Authorization", SQS_AUTH)
                .header("X-Amz-Target", "AmazonSQS." + target).body(body);
    }

    private static RequestSpecification sns(String target, String body) {
        return given().contentType(JSON_10).header("Authorization", SNS_AUTH)
                .header("X-Amz-Target", "SNS_20100331." + target).body(body);
    }

    private static ValidatableResponse iam(String authorization, String action, String id) {
        return given().header("Authorization", authorization)
                .formParam("Action", action)
                .formParam("Version", "2010-05-08")
                .formParam("DelegationRequestId", id)
            .when().post("/").then();
    }

    private static String createQueue(String name) {
        return sqs("CreateQueue", "{\"QueueName\":\"" + name + "\"}")
                .when().post("/").then().statusCode(200)
                .extract().jsonPath().getString("QueueUrl");
    }

    /** A fresh topic delivering to {@code queueUrl}, so what IAM publishes can be read back. */
    private static String topicDeliveringTo(String name, String queueUrl) {
        String queueArn = sqs("GetQueueAttributes", "{\"QueueUrl\":\"" + queueUrl
                + "\",\"AttributeNames\":[\"All\"]}")
                .when().post("/").then().statusCode(200)
                .extract().jsonPath().getString("Attributes.QueueArn");
        String topicArn = sns("CreateTopic", "{\"Name\":\"" + name + "\"}")
                .when().post("/").then().statusCode(200)
                .extract().jsonPath().getString("TopicArn");
        sns("Subscribe", "{\"TopicArn\":\"" + topicArn + "\",\"Protocol\":\"sqs\",\"Endpoint\":\""
                + queueArn + "\"}").when().post("/").then().statusCode(200);
        return topicArn;
    }

    private static String createRequest(String notificationChannel) {
        return createRequestCall(notificationChannel).statusCode(200)
                .extract().path("CreateDelegationRequestResponse.CreateDelegationRequestResult"
                        + ".DelegationRequestId");
    }

    private static ValidatableResponse createRequestCall(String notificationChannel) {
        return given().header("Authorization", PARTNER_AUTH)
                .formParam("Action", "CreateDelegationRequest")
                .formParam("Version", "2010-05-08")
                .formParam("Description", "Set up the integration")
                .formParam("NotificationChannel", notificationChannel)
                .formParam("RequestorWorkflowId", workflowId())
                .formParam("SessionDuration", "3600")
                .formParam("Permissions.PolicyTemplateArn", TEMPLATE)
            .when().post("/").then();
    }

    /**
     * The delegation payloads delivered so far, each read out of the {@code Message} of the SNS
     * notification wrapping it. It polls rather than waits: delivery is another request's work, and
     * a queue that is empty on one attempt is not an empty queue.
     */
    private static List<JsonNode> delivered(String queueUrl, int expected) throws Exception {
        List<JsonNode> payloads = new ArrayList<>();
        for (int attempt = 0; attempt < 10 && payloads.size() < expected; attempt++) {
            Response received = sqs("ReceiveMessage", "{\"QueueUrl\":\"" + queueUrl
                    + "\",\"MaxNumberOfMessages\":10,\"WaitTimeSeconds\":1}")
                    .when().post("/").then().statusCode(200).extract().response();
            List<String> bodies = received.jsonPath().getList("Messages.Body");
            if (bodies == null) {
                continue;
            }
            for (String body : bodies) {
                payloads.add(MAPPER.readTree(MAPPER.readTree(body).path("Message").asText()));
            }
        }
        return payloads;
    }

    @Test
    void theLifecycleIsAnnouncedAndTheTokenArrivesOnTheTopic() throws Exception {
        String queueUrl = createQueue("delegation-notices");
        String id = createRequest(topicDeliveringTo("delegation-notices", queueUrl));

        iam(CUSTOMER_AUTH, "AssociateDelegationRequest", id).statusCode(200);
        iam(CUSTOMER_AUTH, "AcceptDelegationRequest", id).statusCode(200);
        iam(CUSTOMER_AUTH, "SendDelegationToken", id).statusCode(200);

        List<JsonNode> payloads = delivered(queueUrl, 4);
        List<String> announced = payloads.stream()
                .map(payload -> payload.path("Type").asText() + ":" + payload.path("State").asText())
                .sorted().toList();
        // Four notifications for three calls: FINALIZED carries a state change and the token.
        assertEquals(List.of("ExchangeToken:FINALIZED", "StateChange:ACCEPTED",
                "StateChange:ASSIGNED", "StateChange:FINALIZED"), announced);

        JsonNode tokenNotice = payloads.stream()
                .filter(payload -> "ExchangeToken".equals(payload.path("Type").asText()))
                .findFirst().orElseThrow();
        assertEquals(id, tokenNotice.path("RequestId").asText());
        assertEquals(CUSTOMER, tokenNotice.path("OwnerAccountId").asText());
        assertTrue(tokenNotice.path("ExpiresAt").asText().endsWith("Z"),
                "ISO 8601, as the guide's example carries it");

        // The loop closes: the token the partner was sent is the one STS exchanges.
        given().header("Authorization", PARTNER_STS_AUTH)
                .formParam("Action", "GetDelegatedAccessToken")
                .formParam("Version", "2011-06-15")
                .formParam("TradeInToken", tokenNotice.path("ExchangeToken").asText())
            .when().post("/").then().statusCode(200)
                .body("GetDelegatedAccessTokenResponse.GetDelegatedAccessTokenResult"
                        + ".Credentials.AccessKeyId", startsWith("ASIA"));
    }

    /**
     * A channel that passes the member's own pattern and still names no topic is refused where the
     * request is made, rather than accepted into a lifecycle nobody would ever hear about.
     */
    @Test
    void aChannelThatNamesNoTopicIsRefusedOnTheCreate() {
        createRequestCall("partner-notifications").statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidInput"));
    }
}
