package io.github.hectorvent.floci.services.eventbridge;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;

/**
 * CreateEventBus keeps the KMS key, dead-letter queue and log configuration it is given, as
 * {@code terraform-aws-modules/eventbridge} sets them on a bus with {@code log_config}: the create
 * response echoes them, and DescribeEventBus and ListEventBuses report them afterwards.
 */
@QuarkusTest
class EventBridgeCreateEventBusIntegrationTest {

    private static final String CT = "application/x-amz-json-1.1";
    private static final String CREATE = "AWSEvents.CreateEventBus";
    private static final String DESCRIBE = "AWSEvents.DescribeEventBus";
    private static final String LIST = "AWSEvents.ListEventBuses";
    private static final String DELETE = "AWSEvents.DeleteEventBus";
    private static final String DLQ = "arn:aws:sqs:us-east-1:000000000000:bus-dlq";
    private static final String KEY = "arn:aws:kms:us-east-1:000000000000:key/1234abcd-12ab-34cd-56ef-1234567890ab";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void theConfigurationGivenOnCreateIsKeptAndEchoed() {
        String bus = "create-config-bus";
        given().contentType(CT).header("X-Amz-Target", CREATE)
                .body("""
                        {"Name": "%s", "Description": "orders",
                         "KmsKeyIdentifier": "%s",
                         "DeadLetterConfig": {"Arn": "%s"},
                         "LogConfig": {"IncludeDetail": "NONE", "Level": "ERROR"}}
                        """.formatted(bus, KEY, DLQ))
                .when().post("/")
                .then().statusCode(200)
                .body("EventBusArn", endsWith(":event-bus/" + bus))
                .body("Description", equalTo("orders"))
                .body("KmsKeyIdentifier", equalTo(KEY))
                .body("DeadLetterConfig.Arn", equalTo(DLQ))
                .body("LogConfig.IncludeDetail", equalTo("NONE"))
                .body("LogConfig.Level", equalTo("ERROR"))
                .body("Name", nullValue())
                .body("CreationTime", nullValue());

        given().contentType(CT).header("X-Amz-Target", DESCRIBE)
                .body("{\"Name\":\"" + bus + "\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("KmsKeyIdentifier", equalTo(KEY))
                .body("DeadLetterConfig.Arn", equalTo(DLQ))
                .body("LogConfig.IncludeDetail", equalTo("NONE"))
                .body("LogConfig.Level", equalTo("ERROR"));
        given().contentType(CT).header("X-Amz-Target", LIST)
                .body("{\"NamePrefix\":\"" + bus + "\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("EventBuses.LogConfig.Level", hasItem("ERROR"));

        delete(bus);
    }

    @Test
    void aBusCreatedWithoutConfigurationOrWithEmptyObjectsHasNone() {
        String bus = "create-bare-bus";
        given().contentType(CT).header("X-Amz-Target", CREATE)
                .body("{\"Name\":\"" + bus + "\",\"DeadLetterConfig\":{},\"LogConfig\":{}}")
                .when().post("/")
                .then().statusCode(200)
                .body("EventBusArn", endsWith(":event-bus/" + bus))
                .body("DeadLetterConfig", nullValue())
                .body("LogConfig", nullValue());

        given().contentType(CT).header("X-Amz-Target", DESCRIBE)
                .body("{\"Name\":\"" + bus + "\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("KmsKeyIdentifier", nullValue())
                .body("DeadLetterConfig", nullValue())
                .body("LogConfig", nullValue());

        delete(bus);
    }

    private static void delete(String bus) {
        given().contentType(CT).header("X-Amz-Target", DELETE)
                .body("{\"Name\":\"" + bus + "\"}")
                .when().post("/")
                .then().statusCode(200);
    }
}
