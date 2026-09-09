package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers SetLogDeliveryConfiguration and GetLogDeliveryConfiguration.
 *
 * <p>The enum sets, the missing-destination rejection and the 1-based validation path were
 * measured against the live Cognito API.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CognitoLogDeliveryConfigurationIntegrationTest {

    private static String poolId;

    private static final String LOG_GROUP_ARN =
            "arn:aws:logs:us-east-1:000000000000:log-group:cognito-test-logs";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createPool() throws Exception {
        JsonNode response = cognitoJson("CreateUserPool", """
                {
                  "PoolName": "LogDeliveryTestPool"
                }
                """);
        poolId = response.path("UserPool").path("Id").asText();
    }

    @Test
    @Order(2)
    void getReturnsAnEmptyListBeforeAnythingIsConfigured() throws Exception {
        JsonNode config = cognitoJson("GetLogDeliveryConfiguration", """
                {
                  "UserPoolId": "%s"
                }
                """.formatted(poolId)).path("LogDeliveryConfiguration");

        assertEquals(poolId, config.path("UserPoolId").asText());
        assertTrue(config.path("LogConfigurations").isArray());
        assertEquals(0, config.path("LogConfigurations").size());
    }

    @Test
    @Order(3)
    void setStoresAndEchoesTheConfiguration() throws Exception {
        JsonNode config = cognitoJson("SetLogDeliveryConfiguration", """
                {
                  "UserPoolId": "%s",
                  "LogConfigurations": [
                    {
                      "LogLevel": "ERROR",
                      "EventSource": "userNotification",
                      "CloudWatchLogsConfiguration": {"LogGroupArn": "%s"}
                    }
                  ]
                }
                """.formatted(poolId, LOG_GROUP_ARN)).path("LogDeliveryConfiguration");

        assertEquals(1, config.path("LogConfigurations").size());
        JsonNode entry = config.path("LogConfigurations").get(0);
        assertEquals("ERROR", entry.path("LogLevel").asText());
        assertEquals("userNotification", entry.path("EventSource").asText());
        assertEquals(LOG_GROUP_ARN,
                entry.path("CloudWatchLogsConfiguration").path("LogGroupArn").asText());

        JsonNode readBack = cognitoJson("GetLogDeliveryConfiguration", """
                {
                  "UserPoolId": "%s"
                }
                """.formatted(poolId)).path("LogDeliveryConfiguration");
        assertEquals(config.path("LogConfigurations"), readBack.path("LogConfigurations"));
    }

    @Test
    @Order(4)
    void setRejectsAnUnknownLogLevel() {
        cognitoAction("SetLogDeliveryConfiguration", """
                {
                  "UserPoolId": "%s",
                  "LogConfigurations": [
                    {
                      "LogLevel": "TRACE",
                      "EventSource": "userNotification",
                      "CloudWatchLogsConfiguration": {"LogGroupArn": "%s"}
                    }
                  ]
                }
                """.formatted(poolId, LOG_GROUP_ARN))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", equalTo(
                        "1 validation error detected: Value 'TRACE' at "
                                + "'logConfigurations.1.member.logLevel' failed to satisfy constraint: "
                                + "Member must satisfy enum value set: [ERROR, INFO]"));
    }

    @Test
    @Order(5)
    void setRejectsAnUnknownEventSource() {
        cognitoAction("SetLogDeliveryConfiguration", """
                {
                  "UserPoolId": "%s",
                  "LogConfigurations": [
                    {
                      "LogLevel": "ERROR",
                      "EventSource": "notAThing",
                      "CloudWatchLogsConfiguration": {"LogGroupArn": "%s"}
                    }
                  ]
                }
                """.formatted(poolId, LOG_GROUP_ARN))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", equalTo(
                        "1 validation error detected: Value 'notAThing' at "
                                + "'logConfigurations.1.member.eventSource' failed to satisfy constraint: "
                                + "Member must satisfy enum value set: [userAuthEvents, userNotification]"));
    }

    @Test
    @Order(6)
    void setRejectsAConfigurationWithNoDestination() {
        cognitoAction("SetLogDeliveryConfiguration", """
                {
                  "UserPoolId": "%s",
                  "LogConfigurations": [
                    {
                      "LogLevel": "ERROR",
                      "EventSource": "userNotification"
                    }
                  ]
                }
                """.formatted(poolId))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", equalTo(
                        "Request validation Failed. Following event sources in request have no "
                                + "destination: [userNotification]."));
    }

    @Test
    @Order(7)
    void setReplacesRatherThanMerges() throws Exception {
        JsonNode config = cognitoJson("SetLogDeliveryConfiguration", """
                {
                  "UserPoolId": "%s",
                  "LogConfigurations": [
                    {
                      "LogLevel": "INFO",
                      "EventSource": "userAuthEvents",
                      "CloudWatchLogsConfiguration": {"LogGroupArn": "%s"}
                    }
                  ]
                }
                """.formatted(poolId, LOG_GROUP_ARN)).path("LogDeliveryConfiguration");

        assertEquals(1, config.path("LogConfigurations").size(),
                "Set replaces the whole list; the userNotification entry must be gone");
        assertEquals("userAuthEvents", config.path("LogConfigurations").get(0).path("EventSource").asText());
    }

    @Test
    @Order(8)
    void anEmptyListClearsTheConfiguration() throws Exception {
        assertEquals(0, cognitoJson("SetLogDeliveryConfiguration", """
                {
                  "UserPoolId": "%s",
                  "LogConfigurations": []
                }
                """.formatted(poolId)).path("LogDeliveryConfiguration").path("LogConfigurations").size());

        assertEquals(0, cognitoJson("GetLogDeliveryConfiguration", """
                {
                  "UserPoolId": "%s"
                }
                """.formatted(poolId)).path("LogDeliveryConfiguration").path("LogConfigurations").size());
    }

    /**
     * A malformed request must not be read as an intentional clear: AWS rejects a null
     * LogConfigurations outright, and reports a wrong JSON type as a serialization failure.
     */
    @Test
    @Order(9)
    void malformedRequestsAreRejectedRatherThanClearingTheConfiguration() throws Exception {
        cognitoJson("SetLogDeliveryConfiguration", """
                {
                  "UserPoolId": "%s",
                  "LogConfigurations": [
                    {
                      "LogLevel": "ERROR",
                      "EventSource": "userNotification",
                      "CloudWatchLogsConfiguration": {"LogGroupArn": "%s"}
                    }
                  ]
                }
                """.formatted(poolId, LOG_GROUP_ARN));

        cognitoAction("SetLogDeliveryConfiguration", """
                {
                  "UserPoolId": "%s"
                }
                """.formatted(poolId))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", equalTo(
                        "1 validation error detected: Value null at 'logConfigurations' failed to "
                                + "satisfy constraint: Member must not be null"));

        cognitoAction("SetLogDeliveryConfiguration", """
                {
                  "UserPoolId": "%s",
                  "LogConfigurations": "nope"
                }
                """.formatted(poolId))
                .then()
                .statusCode(400)
                .body("__type", equalTo("SerializationException"));

        assertEquals(1, cognitoJson("GetLogDeliveryConfiguration", """
                {
                  "UserPoolId": "%s"
                }
                """.formatted(poolId)).path("LogDeliveryConfiguration").path("LogConfigurations").size(),
                "a rejected request must leave the stored configuration untouched");
    }

    /**
     * A scalar inside the array is a deserialization failure for AWS, not a 500, and a null
     * element is dropped rather than rejected.
     */
    @Test
    @Order(10)
    void malformedArrayElementsMatchAwsHandling() throws Exception {
        cognitoAction("SetLogDeliveryConfiguration", """
                {
                  "UserPoolId": "%s",
                  "LogConfigurations": [1, 2]
                }
                """.formatted(poolId))
                .then()
                .statusCode(400)
                .body("__type", equalTo("SerializationException"));

        cognitoAction("SetLogDeliveryConfiguration", """
                {
                  "UserPoolId": "%s",
                  "LogConfigurations": ["a string"]
                }
                """.formatted(poolId))
                .then()
                .statusCode(400)
                .body("__type", equalTo("SerializationException"));

        assertEquals(0, cognitoJson("SetLogDeliveryConfiguration", """
                {
                  "UserPoolId": "%s",
                  "LogConfigurations": [null]
                }
                """.formatted(poolId)).path("LogDeliveryConfiguration").path("LogConfigurations").size(),
                "AWS drops a null element rather than rejecting it");
    }

    @Test
    @Order(11)
    void unknownPoolIsRejected() {
        cognitoAction("GetLogDeliveryConfiguration", """
                {
                  "UserPoolId": "us-east-1_nosuchpool"
                }
                """)
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(12)
    void setRejectsMoreThanTwoConfigurations() {
        cognitoAction("SetLogDeliveryConfiguration", """
                {
                  "UserPoolId": "%s",
                  "LogConfigurations": [
                    {"LogLevel": "ERROR", "EventSource": "userNotification",
                     "CloudWatchLogsConfiguration": {"LogGroupArn": "%s"}},
                    {"LogLevel": "INFO", "EventSource": "userAuthEvents",
                     "CloudWatchLogsConfiguration": {"LogGroupArn": "%s"}},
                    {"LogLevel": "ERROR", "EventSource": "userNotification",
                     "CloudWatchLogsConfiguration": {"LogGroupArn": "%s"}}
                  ]
                }
                """.formatted(poolId, LOG_GROUP_ARN, LOG_GROUP_ARN, LOG_GROUP_ARN))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", equalTo(
                        "1 validation error detected: Value '["
                                + cloudWatchConfig("ERROR", "userNotification") + ", "
                                + cloudWatchConfig("INFO", "userAuthEvents") + ", "
                                + cloudWatchConfig("ERROR", "userNotification")
                                + "]' at 'logConfigurations' failed to satisfy constraint: "
                                + "Member must have length less than or equal to 2"));
    }

    @Test
    @Order(13)
    void theLengthViolationIsReportedAlongsideElementViolations() {
        cognitoAction("SetLogDeliveryConfiguration", """
                {
                  "UserPoolId": "%s",
                  "LogConfigurations": [
                    {"LogLevel": "ERROR", "EventSource": "userNotification",
                     "CloudWatchLogsConfiguration": {"LogGroupArn": "%s"}},
                    {"LogLevel": "BOGUS", "EventSource": "userAuthEvents",
                     "CloudWatchLogsConfiguration": {"LogGroupArn": "%s"}},
                    {"LogLevel": "ERROR", "EventSource": "userNotification",
                     "CloudWatchLogsConfiguration": {"LogGroupArn": "%s"}}
                  ]
                }
                """.formatted(poolId, LOG_GROUP_ARN, LOG_GROUP_ARN, LOG_GROUP_ARN))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", equalTo(
                        "2 validation errors detected: Value '["
                                + cloudWatchConfig("ERROR", "userNotification") + ", "
                                + cloudWatchConfig("BOGUS", "userAuthEvents") + ", "
                                + cloudWatchConfig("ERROR", "userNotification")
                                + "]' at 'logConfigurations' failed to satisfy constraint: "
                                + "Member must have length less than or equal to 2; "
                                + "Value 'BOGUS' at 'logConfigurations.2.member.logLevel' failed to satisfy "
                                + "constraint: Member must satisfy enum value set: [ERROR, INFO]"));
    }

    @Test
    @Order(14)
    void shapeViolationsAreReportedBeforeTheMissingPool() {
        cognitoAction("SetLogDeliveryConfiguration", """
                {
                  "UserPoolId": "us-east-1_nosuchpool",
                  "LogConfigurations": [
                    {"LogLevel": "BOGUS", "EventSource": "userNotification",
                     "CloudWatchLogsConfiguration": {"LogGroupArn": "%s"}}
                  ]
                }
                """.formatted(LOG_GROUP_ARN))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", equalTo(
                        "1 validation error detected: Value 'BOGUS' at "
                                + "'logConfigurations.1.member.logLevel' failed to satisfy constraint: "
                                + "Member must satisfy enum value set: [ERROR, INFO]"));
    }

    @Test
    @Order(15)
    void setRejectsARepeatedEventSource() {
        cognitoAction("SetLogDeliveryConfiguration", """
                {
                  "UserPoolId": "%s",
                  "LogConfigurations": [
                    {"LogLevel": "ERROR", "EventSource": "userNotification",
                     "CloudWatchLogsConfiguration": {"LogGroupArn": "%s"}},
                    {"LogLevel": "INFO", "EventSource": "userNotification",
                     "CloudWatchLogsConfiguration": {"LogGroupArn": "%s"}}
                  ]
                }
                """.formatted(poolId, LOG_GROUP_ARN, LOG_GROUP_ARN))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", equalTo(
                        "Request validation Failed. Following event sources appear more then once "
                                + "in a request: [userNotification]."));
    }

    @Test
    @Order(16)
    void aRepeatedEventSourceWithNoDestinationReportsBothClauses() {
        cognitoAction("SetLogDeliveryConfiguration", """
                {
                  "UserPoolId": "%s",
                  "LogConfigurations": [
                    {"LogLevel": "ERROR", "EventSource": "userNotification"},
                    {"LogLevel": "ERROR", "EventSource": "userNotification"}
                  ]
                }
                """.formatted(poolId))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", equalTo(
                        "Request validation Failed. Following event sources in request have no "
                                + "destination: [userNotification]. Following event sources appear "
                                + "more then once in a request: [userNotification]."));
    }

    @Test
    @Order(17)
    void aRejectedOversizedRequestLeavesTheConfigurationAlone() throws Exception {
        assertEquals(0, cognitoJson("GetLogDeliveryConfiguration", """
                {
                  "UserPoolId": "%s"
                }
                """.formatted(poolId)).path("LogDeliveryConfiguration").path("LogConfigurations").size());
    }

    @Test
    @Order(18)
    void deletePool() {
        cognitoAction("DeleteUserPool", """
                {
                  "UserPoolId": "%s"
                }
                """.formatted(poolId))
                .then()
                .statusCode(200);
    }

    private static String cloudWatchConfig(String logLevel, String eventSource) {
        return "LogConfigurationType(logLevel=" + logLevel + ", eventSource=" + eventSource
                + ", cloudWatchLogsConfiguration=CloudWatchLogsConfigurationType(logGroupArn="
                + LOG_GROUP_ARN + "), s3Configuration=null, firehoseConfiguration=null)";
    }
}
