package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.hectorvent.floci.testing.LogsRequests.logs;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
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
        // The same name in another partition is not the caller's source.
        logs("TagResource", "{\"resourceArn\": \"" + PREFIX.replace("arn:aws:", "arn:aws-cn:")
                + "delivery-source:orders-" + suffix + "\", \"tags\": {\"env\": \"foreign\"}}").then().statusCode(400);
        logs("ListTagsForResource", "{\"resourceArn\": \"" + PREFIX + "delivery-source:orders-" + suffix + "\"}")
                .then().body("tags.env", equalTo("local"));

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
    void describeCallsNarrowToTheNamedSourcesDestinationsAndDeliveries() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String[] ids = new String[2];
        for (int i = 0; i < 2; i++) {
            String bus = "arn:aws:events:us-east-1:000000000000:event-bus/filter-" + i + "-" + suffix;
            logs("PutDeliverySource", """
                    {"name": "filter-%d-%s", "resourceArn": "%s", "logType": "ERROR_LOGS"}
                    """.formatted(i, suffix, bus)).then().statusCode(200);
            String destination = logs("PutDeliveryDestination", """
                    {"name": "filter-%d-%s", "deliveryDestinationConfiguration":
                     {"destinationResourceArn": "%slog-group:/aws/events/filter-%d-%s"}}
                    """.formatted(i, suffix, PREFIX, i, suffix)).then().statusCode(200)
                    .extract().path("deliveryDestination.arn");
            ids[i] = logs("CreateDelivery", """
                    {"deliverySourceName": "filter-%d-%s", "deliveryDestinationArn": "%s"}
                    """.formatted(i, suffix, destination)).then().statusCode(200).extract().path("delivery.id");
        }

        logs("DescribeDeliverySources", "{\"resourceArns\": [\"arn:aws:events:us-east-1:000000000000:event-bus/filter-1-"
                + suffix + "\"]}").then().statusCode(200)
                .body("deliverySources.name", contains("filter-1-" + suffix));
        logs("DescribeDeliveryDestinations", "{\"names\": [\"filter-0-" + suffix + "\", \"missing-" + suffix + "\"]}")
                .then().statusCode(200)
                .body("deliveryDestinations.name", contains("filter-0-" + suffix));
        logs("DescribeDeliveries", "{\"deliverySourceNames\": [\"filter-1-" + suffix + "\"]}").then().statusCode(200)
                .body("deliveries.id", contains(ids[1]));
        logs("DescribeDeliveries", "{\"deliverySourceNames\": [\"missing-" + suffix + "\"]}").then().statusCode(200)
                .body("deliveries", empty());

        for (int i = 0; i < 2; i++) {
            logs("DeleteDelivery", "{\"id\": \"" + ids[i] + "\"}").then().statusCode(200);
            logs("DeleteDeliverySource", "{\"name\": \"filter-" + i + "-" + suffix + "\"}").then().statusCode(200);
            logs("DeleteDeliveryDestination", "{\"name\": \"filter-" + i + "-" + suffix + "\"}").then().statusCode(200);
        }
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
        refused("PutDeliverySource", "{\"name\": \"bad name\", \"resourceArn\": \"x\", \"logType\": \"ERROR_LOGS\"}",
                "ValidationException", "1 validation error detected: Value at 'name' failed to satisfy constraint: "
                        + "Member must satisfy regular expression pattern: [\\w-]*");
        refused("PutDeliverySource", "{\"name\": \"source\", \"logType\": \"ERROR_LOGS\"}", "ValidationException",
                "1 validation error detected: Value at 'resourceArn' failed to satisfy constraint: "
                        + "Member must not be null");
        refused("GetDelivery", "{\"id\": \"a-b\"}", "ValidationException",
                "1 validation error detected: Value at 'id' failed to satisfy constraint: "
                        + "Member must satisfy regular expression pattern: ^[0-9A-Za-z]+$");
        refused("DescribeDeliveries", "{\"deliverySourceNames\": [\"bad name\"]}", "ValidationException",
                "1 validation error detected: Value at 'deliverySourceNames' failed to satisfy constraint: "
                        + "Member must satisfy constraint: [Member must have length less than or equal to 60, "
                        + "Member must have length greater than or equal to 1, "
                        + "Member must satisfy regular expression pattern: [\\w-]*]");
        logs("CreateDelivery", "{\"deliverySourceName\": \"no-such-source\", \"deliveryDestinationArn\": \""
                + PREFIX + "delivery-destination:no-such-destination\"}").then().statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("message", notNullValue());
    }

    private static void refused(String action, String body, String type, String message) {
        logs(action, body).then().statusCode(400).body("__type", equalTo(type)).body("message", equalTo(message));
    }
}
