package io.floci.aws.services.eventbridge;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

/**
 * A partner event bus is created from its {@code EventSourceName}, takes that name, and then works
 * like any custom bus: it is described and deleted by name or ARN, and a rule on it delivers the
 * events put on it.
 */
@QuarkusTest
class EventBridgePartnerEventBusIntegrationTest {

    private static final String EB_CT = "application/x-amz-json-1.1";
    private static final String SQS_CT = "application/x-amz-json-1.0";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aPartnerBusDeliversEventsPutOnItByArn() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bus = "aws.partner/example.com/" + suffix + "/orders";
        String queue = "partner-sink-" + suffix;

        String busArn = given().contentType(EB_CT).header("X-Amz-Target", "AWSEvents.CreateEventBus")
                .body("{\"Name\":\"" + bus + "\",\"EventSourceName\":\"" + bus + "\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("EventBusArn", endsWith(":event-bus/" + bus))
                .extract().jsonPath().getString("EventBusArn");

        given().contentType(EB_CT).header("X-Amz-Target", "AWSEvents.DescribeEventBus")
                .body("{\"Name\":\"" + busArn + "\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("Name", equalTo(bus))
                .body("Arn", equalTo(busArn));

        String queueUrl = given().contentType(SQS_CT).header("X-Amz-Target", "AmazonSQS.CreateQueue")
                .body("{\"QueueName\":\"" + queue + "\"}")
                .when().post("/").then().statusCode(200)
                .extract().jsonPath().getString("QueueUrl");
        String queuePath = URI.create(queueUrl).getPath();
        String queueArn = given().contentType(SQS_CT).header("X-Amz-Target", "AmazonSQS.GetQueueAttributes")
                .body("{\"QueueUrl\":\"" + queueUrl + "\",\"AttributeNames\":[\"All\"]}")
                .when().post(queuePath).then().statusCode(200)
                .extract().jsonPath().getString("Attributes.QueueArn");

        given().contentType(EB_CT).header("X-Amz-Target", "AWSEvents.PutRule")
                .body("{\"Name\":\"partner-rule\",\"EventBusName\":\"" + busArn + "\","
                        + "\"EventPattern\":\"{\\\"source\\\":[\\\"example.com\\\"]}\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("RuleArn", endsWith(":rule/" + bus + "/partner-rule"));

        given().contentType(EB_CT).header("X-Amz-Target", "AWSEvents.PutTargets")
                .body("{\"Rule\":\"partner-rule\",\"EventBusName\":\"" + bus + "\","
                        + "\"Targets\":[{\"Id\":\"sink\",\"Arn\":\"" + queueArn + "\"}]}")
                .when().post("/")
                .then().statusCode(200)
                .body("FailedEntryCount", equalTo(0));

        given().contentType(EB_CT).header("X-Amz-Target", "AWSEvents.PutEvents")
                .body("""
                        {"Entries":[{"EventBusName":"%s","Source":"example.com",
                        "DetailType":"Order Created","Detail":"{\\"orderId\\":\\"o-1\\"}"}]}
                        """.formatted(busArn))
                .when().post("/")
                .then().statusCode(200)
                .body("FailedEntryCount", equalTo(0));

        given().contentType(SQS_CT).header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                .body("{\"QueueUrl\":\"" + queueUrl + "\",\"MaxNumberOfMessages\":1,\"WaitTimeSeconds\":2}")
                .when().post(queuePath)
                .then().statusCode(200)
                .body("Messages", hasSize(1))
                .body("Messages[0].Body", containsString("\"event-bus-name\":\"" + bus + "\""));

        given().contentType(EB_CT).header("X-Amz-Target", "AWSEvents.RemoveTargets")
                .body("{\"Rule\":\"partner-rule\",\"EventBusName\":\"" + bus + "\",\"Ids\":[\"sink\"]}")
                .when().post("/").then().statusCode(200);
        given().contentType(EB_CT).header("X-Amz-Target", "AWSEvents.DeleteRule")
                .body("{\"Name\":\"partner-rule\",\"EventBusName\":\"" + bus + "\"}")
                .when().post("/").then().statusCode(200);
        given().contentType(EB_CT).header("X-Amz-Target", "AWSEvents.DeleteEventBus")
                .body("{\"Name\":\"" + busArn + "\"}")
                .when().post("/").then().statusCode(200);

        given().contentType(EB_CT).header("X-Amz-Target", "AWSEvents.DescribeEventBus")
                .body("{\"Name\":\"" + bus + "\"}")
                .when().post("/")
                .then().statusCode(400)
                .body("__type", containsString("ResourceNotFoundException"));
    }

    @Test
    void aPartnerBusNameMustMatchItsEventSourceName() {
        given().contentType(EB_CT).header("X-Amz-Target", "AWSEvents.CreateEventBus")
                .body("{\"Name\":\"aws.partner/example.com/a\",\"EventSourceName\":\"aws.partner/example.com/b\"}")
                .when().post("/")
                .then().statusCode(400)
                .body("__type", containsString("ValidationException"));
    }

    @Test
    void aRuleNameWithASlashIsRejected() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bus = "aws.partner/example.com/" + suffix;
        given().contentType(EB_CT).header("X-Amz-Target", "AWSEvents.CreateEventBus")
                .body("{\"Name\":\"" + bus + "\",\"EventSourceName\":\"" + bus + "\"}")
                .when().post("/").then().statusCode(200);

        given().contentType(EB_CT).header("X-Amz-Target", "AWSEvents.PutRule")
                .body("{\"Name\":\"eu/rule\",\"EventBusName\":\"" + bus + "\","
                        + "\"EventPattern\":\"{\\\"source\\\":[\\\"example.com\\\"]}\"}")
                .when().post("/")
                .then().statusCode(400)
                .body("__type", containsString("ValidationException"))
                .body("message", containsString("Value 'eu/rule' at 'name' failed to satisfy constraint"));

        given().contentType(EB_CT).header("X-Amz-Target", "AWSEvents.ListRules")
                .body("{\"EventBusName\":\"" + bus + "\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("Rules", hasSize(0));

        given().contentType(EB_CT).header("X-Amz-Target", "AWSEvents.DeleteEventBus")
                .body("{\"Name\":\"" + bus + "\"}")
                .when().post("/").then().statusCode(200);
    }

    @Test
    void aPartnerBusTakesNoPermission() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bus = "aws.partner/example.com/" + suffix;
        given().contentType(EB_CT).header("X-Amz-Target", "AWSEvents.CreateEventBus")
                .body("{\"Name\":\"" + bus + "\",\"EventSourceName\":\"" + bus + "\"}")
                .when().post("/").then().statusCode(200);

        given().contentType(EB_CT).header("X-Amz-Target", "AWSEvents.PutPermission")
                .body("{\"EventBusName\":\"" + bus + "\",\"Action\":\"events:PutEvents\","
                        + "\"Principal\":\"111122223333\",\"StatementId\":\"allow\"}")
                .when().post("/")
                .then().statusCode(400)
                .body("__type", containsString("ValidationException"))
                .body("message", equalTo("1 validation error detected: Value '" + bus + "' at 'eventBusName' "
                        + "failed to satisfy constraint: Member must satisfy regular expression pattern: "
                        + "[\\.\\-_A-Za-z0-9]+"));

        given().contentType(EB_CT).header("X-Amz-Target", "AWSEvents.RemovePermission")
                .body("{\"EventBusName\":\"" + bus + "\",\"RemoveAllPermissions\":true}")
                .when().post("/")
                .then().statusCode(400)
                .body("__type", containsString("ValidationException"));

        given().contentType(EB_CT).header("X-Amz-Target", "AWSEvents.DescribeEventBus")
                .body("{\"Name\":\"" + bus + "\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("Policy", nullValue());

        given().contentType(EB_CT).header("X-Amz-Target", "AWSEvents.DeleteEventBus")
                .body("{\"Name\":\"" + bus + "\"}")
                .when().post("/").then().statusCode(200);
    }

    @Test
    void aNameWithASlashButNoEventSourceNameIsStillRejected() {
        given().contentType(EB_CT).header("X-Amz-Target", "AWSEvents.CreateEventBus")
                .body("{\"Name\":\"aws.partner/example.com/no-source\"}")
                .when().post("/")
                .then().statusCode(400)
                .body("__type", containsString("ValidationException"));
    }
}
