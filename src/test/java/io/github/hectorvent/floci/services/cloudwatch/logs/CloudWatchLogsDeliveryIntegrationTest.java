package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Vended-log delivery over the Logs JSON 1.1 API: an EventBridge bus as delivery source, a log
 * group as delivery destination, and the delivery that pairs them, as
 * {@code terraform-aws-modules/eventbridge}'s {@code log_delivery} creates them.
 */
@QuarkusTest
class CloudWatchLogsDeliveryIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20261103/us-east-1/logs/aws4_request";
    private static final String PREFIX = "arn:aws:logs:us-east-1:000000000000:";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void anEventBusDeliversToALogGroup() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bus = "arn:aws:events:us-east-1:000000000000:event-bus/orders-" + suffix;
        String logGroup = PREFIX + "log-group:/aws/events/orders-" + suffix;

        logs("PutDeliverySource", """
                {"name": "orders-%s", "resourceArn": "%s", "logType": "ERROR_LOGS", "tags": {"team": "data"}}
                """.formatted(suffix, bus)).then().statusCode(200)
                .body("deliverySource.name", equalTo("orders-" + suffix))
                .body("deliverySource.arn", equalTo(PREFIX + "delivery-source:orders-" + suffix))
                .body("deliverySource.resourceArns", contains(bus))
                .body("deliverySource.service", equalTo("events"))
                .body("deliverySource.logType", equalTo("ERROR_LOGS"))
                .body("deliverySource.tags", nullValue());
        String destinationArn = logs("PutDeliveryDestination", """
                {"name": "orders-logs-%s", "outputFormat": "json",
                 "deliveryDestinationConfiguration": {"destinationResourceArn": "%s"}}
                """.formatted(suffix, logGroup)).then().statusCode(200)
                .body("deliveryDestination.arn", equalTo(PREFIX + "delivery-destination:orders-logs-" + suffix))
                .body("deliveryDestination.deliveryDestinationType", equalTo("CWL"))
                .body("deliveryDestination.outputFormat", equalTo("json"))
                .body("deliveryDestination.deliveryDestinationConfiguration.destinationResourceArn",
                        equalTo(logGroup))
                .extract().path("deliveryDestination.arn");

        String id = logs("CreateDelivery", """
                {"deliverySourceName": "orders-%s", "deliveryDestinationArn": "%s", "tags": {"team": "data"}}
                """.formatted(suffix, destinationArn)).then().statusCode(200)
                .body("delivery.id", matchesPattern("[0-9A-Za-z]{16}"))
                .body("delivery.deliveryDestinationType", equalTo("CWL"))
                .extract().path("delivery.id");
        String deliveryArn = PREFIX + "delivery:" + id;

        // GetDelivery reports the event bus template's record fields and the S3 suffix path.
        logs("GetDelivery", "{\"id\": \"" + id + "\"}").then().statusCode(200)
                .body("delivery.arn", equalTo(deliveryArn))
                .body("delivery.deliverySourceName", equalTo("orders-" + suffix))
                .body("delivery.deliveryDestinationArn", equalTo(destinationArn))
                .body("delivery.recordFields", contains("resource_arn", "message_timestamp_ms", "event_bus_name",
                        "request_id", "event_id", "invocation_id", "message_type", "log_level", "details", "error"))
                .body("delivery.s3DeliveryConfiguration.suffixPath", equalTo("{region}/{yyyy}/{MM}/{dd}/{HH}/"))
                .body("delivery.s3DeliveryConfiguration.enableHiveCompatiblePath", equalTo(false))
                .body("delivery.fieldDelimiter", nullValue())
                .body("delivery.tags", nullValue());
        logs("DescribeDeliveries", "{}").then().statusCode(200)
                .body("deliveries.id", hasItem(id))
                .body("deliveries.find { it.id == '" + id + "' }.recordFields", nullValue());

        logs("UpdateDeliveryConfiguration", "{\"id\": \"" + id + "\", \"recordFields\": [\"event_id\", \"error\"]}")
                .then().statusCode(200);
        logs("GetDelivery", "{\"id\": \"" + id + "\"}").then()
                .body("delivery.recordFields", contains("event_id", "error"));

        logs("ListTagsForResource", "{\"resourceArn\": \"" + deliveryArn + "\"}").then().statusCode(200)
                .body("tags.team", equalTo("data"));
        logs("TagResource", "{\"resourceArn\": \"" + PREFIX + "delivery-source:orders-" + suffix
                + "\", \"tags\": {\"env\": \"local\"}}").then().statusCode(200);
        logs("ListTagsForResource", "{\"resourceArn\": \"" + PREFIX + "delivery-source:orders-" + suffix + "\"}")
                .then().body("tags.team", equalTo("data")).body("tags.env", equalTo("local"));

        // In use: neither end can go while the delivery pairs them, and a second delivery is refused.
        logs("CreateDelivery", """
                {"deliverySourceName": "orders-%s", "deliveryDestinationArn": "%s"}
                """.formatted(suffix, destinationArn)).then().statusCode(400)
                .body("__type", equalTo("ConflictException"));
        logs("DeleteDeliverySource", "{\"name\": \"orders-" + suffix + "\"}").then().statusCode(400)
                .body("__type", equalTo("ConflictException"));
        logs("DeleteDeliveryDestination", "{\"name\": \"orders-logs-" + suffix + "\"}").then().statusCode(400)
                .body("__type", equalTo("ConflictException"));

        logs("DeleteDelivery", "{\"id\": \"" + id + "\"}").then().statusCode(200);
        logs("DeleteDeliverySource", "{\"name\": \"orders-" + suffix + "\"}").then().statusCode(200);
        logs("DeleteDeliveryDestination", "{\"name\": \"orders-logs-" + suffix + "\"}").then().statusCode(200);
        logs("DescribeDeliverySources", "{}").then().statusCode(200)
                .body("deliverySources.name", not(hasItem("orders-" + suffix)));
    }

    @Test
    void unknownResourcesAndInvalidRequestsAreRefusedWithAwsMessages() {
        refused("GetDeliverySource", "{\"name\": \"no-such-source\"}", "ResourceNotFoundException",
                "Requested Delivery Source does not exist in this account.");
        refused("GetDeliveryDestination", "{\"name\": \"no-such-destination\"}", "ResourceNotFoundException",
                "Requested Delivery Destination does not exist in this account.");
        refused("GetDelivery", "{\"id\": \"AAAAAAAAAAAAAAAA\"}", "ResourceNotFoundException",
                "Requested Delivery does not exist in this account.");
        refused("DeleteDelivery", "{\"id\": \"AAAAAAAAAAAAAAAA\"}", "ResourceNotFoundException",
                "Requested Delivery does not exist in this account.");
        refused("ListTagsForResource", "{\"resourceArn\": \"" + PREFIX + "delivery:AAAAAAAAAAAAAAAA\"}",
                "ResourceNotFoundException", "Requested Delivery does not exist in this account.");
        refused("DescribeDeliverySources", "{\"limit\": 51}", "ValidationException",
                "1 validation error detected: Value at 'limit' failed to satisfy constraint: Member must have value "
                        + "less than or equal to 50");
        refused("DescribeDeliverySources", "{\"nextToken\": \"bogus\"}", "ValidationException",
                "Invalid Next Token.");

        logs("PutDeliveryDestination", """
                {"name": "bad-destination", "deliveryDestinationConfiguration":
                 {"destinationResourceArn": "arn:aws:sqs:us-east-1:000000000000:queue"}}
                """).then().statusCode(400).body("__type", equalTo("ValidationException"));
        logs("PutDeliveryDestination", "{\"name\": \"traces\", \"deliveryDestinationType\": \"XRAY\"}")
                .then().statusCode(200)
                .body("deliveryDestination.deliveryDestinationType", equalTo("XRAY"))
                .body("deliveryDestination.deliveryDestinationConfiguration", nullValue());
        logs("DeleteDeliveryDestination", "{\"name\": \"traces\"}").then().statusCode(200);
        logs("PutDeliverySource", "{\"name\": \"bad name\", \"resourceArn\": \"x\", \"logType\": \"ERROR_LOGS\"}")
                .then().statusCode(400).body("__type", equalTo("ValidationException"));
        logs("CreateDelivery", "{\"deliverySourceName\": \"no-such-source\", \"deliveryDestinationArn\": \""
                + PREFIX + "delivery-destination:no-such-destination\"}").then().statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("message", notNullValue());
    }

    private static void refused(String action, String body, String type, String message) {
        logs(action, body).then().statusCode(400).body("__type", equalTo(type)).body("message", equalTo(message));
    }

    private static Response logs(String action, String body) {
        return given().contentType(CONTENT_TYPE).header("Authorization", AUTH)
                .header("X-Amz-Target", "Logs_20140328." + action).body(body).post("/");
    }
}
