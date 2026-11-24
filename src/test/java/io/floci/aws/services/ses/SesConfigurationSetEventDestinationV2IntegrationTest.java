package io.floci.aws.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static io.floci.aws.services.ses.SesV2Requests.AUTH;
import static io.floci.aws.services.ses.SesV2Requests.v2;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

/**
 * Integration tests for SES V2 ConfigurationSetEventDestination endpoints under
 * /v2/email/configuration-sets/{name}/event-destinations.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesConfigurationSetEventDestinationV2IntegrationTest {

    private static final String CS = "v2-cs-ed";

    private static final String SCOPED_AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=111111111111/20260101/us-west-2/ses/aws4_request";
    private static final String SCOPED_DEFAULT_BUS_ARN =
            "arn:aws:events:us-west-2:111111111111:event-bus/default";

    @Test
    @Order(1)
    void createConfigurationSet() {
        for (String topic : new String[] {"ses-events", "ses-events-2", "dup", "x", "ed-a", "ed-b", "ed-c",
                "ed-a-updated"}) {
            SnsTopicFixture.create("us-east-1", topic);
        }
        v2()
            .body("{\"ConfigurationSetName\": \"" + CS + "\"}")
        .when()
            .post("/v2/email/configuration-sets")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(2)
    void createEventDestination() {
        v2()
            .body("""
                {
                  "EventDestinationName": "ed-sns",
                  "EventDestination": {
                    "Enabled": true,
                    "MatchingEventTypes": ["SEND", "BOUNCE"],
                    "SnsDestination": {"TopicArn": "arn:aws:sns:us-east-1:000000000000:ses-events"}
                  }
                }
                """)
        .when()
            .post("/v2/email/configuration-sets/" + CS + "/event-destinations")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(3)
    void getEventDestinations_returnsRoundTrip() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v2/email/configuration-sets/" + CS + "/event-destinations")
        .then()
            .statusCode(200)
            .body("EventDestinations", hasSize(1))
            .body("EventDestinations[0].Name", equalTo("ed-sns"))
            .body("EventDestinations[0].Enabled", equalTo(true))
            .body("EventDestinations[0].MatchingEventTypes", hasItem("SEND"))
            .body("EventDestinations[0].MatchingEventTypes", hasItem("BOUNCE"))
            .body("EventDestinations[0].SnsDestination.TopicArn",
                    equalTo("arn:aws:sns:us-east-1:000000000000:ses-events"));
    }

    @Test
    @Order(4)
    void createEventDestination_duplicateRejected() {
        v2()
            .body("""
                {
                  "EventDestinationName": "ed-sns",
                  "EventDestination": {
                    "MatchingEventTypes": ["SEND"],
                    "SnsDestination": {"TopicArn": "arn:aws:sns:us-east-1:000000000000:dup"}
                  }
                }
                """)
        .when()
            .post("/v2/email/configuration-sets/" + CS + "/event-destinations")
        .then()
            .statusCode(400)
            .body("__type", equalTo("AlreadyExistsException"))
            .body("message", equalTo(
                    "An event destination with name <ed-sns> already exists for configuration set <v2-cs-ed>."));
    }

    @Test
    @Order(5)
    void createEventDestination_unknownConfigSetReturns404() {
        v2()
            .body("""
                {
                  "EventDestinationName": "ed-x",
                  "EventDestination": {
                    "MatchingEventTypes": ["SEND"],
                    "SnsDestination": {"TopicArn": "arn:aws:sns:us-east-1:000000000000:x"}
                  }
                }
                """)
        .when()
            .post("/v2/email/configuration-sets/v2-cs-ghost/event-destinations")
        .then()
            .statusCode(404)
            .body("__type", equalTo("NotFoundException"))
            .body("message", equalTo("Configuration set <v2-cs-ghost> does not exist."));
    }

    @Test
    @Order(6)
    void createEventDestination_emptyMatchingEventTypes_returns400() {
        v2()
            .body("""
                {
                  "EventDestinationName": "ed-empty",
                  "EventDestination": {
                    "MatchingEventTypes": [],
                    "SnsDestination": {"TopicArn": "arn:aws:sns:us-east-1:000000000000:x"}
                  }
                }
                """)
        .when()
            .post("/v2/email/configuration-sets/" + CS + "/event-destinations")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(7)
    void createEventDestination_noDestination_returns400() {
        v2()
            .body("""
                {
                  "EventDestinationName": "ed-nodest",
                  "EventDestination": {
                    "MatchingEventTypes": ["SEND"]
                  }
                }
                """)
        .when()
            .post("/v2/email/configuration-sets/" + CS + "/event-destinations")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(8)
    void updateEventDestination_replacesDefinition() {
        v2()
            .body("""
                {
                  "EventDestination": {
                    "Enabled": false,
                    "MatchingEventTypes": ["DELIVERY"],
                    "SnsDestination": {"TopicArn": "arn:aws:sns:us-east-1:000000000000:ses-events-2"}
                  }
                }
                """)
        .when()
            .put("/v2/email/configuration-sets/" + CS + "/event-destinations/ed-sns")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v2/email/configuration-sets/" + CS + "/event-destinations")
        .then()
            .statusCode(200)
            .body("EventDestinations", hasSize(1))
            .body("EventDestinations[0].Name", equalTo("ed-sns"))
            .body("EventDestinations[0].Enabled", equalTo(false))
            .body("EventDestinations[0].MatchingEventTypes", hasItem("DELIVERY"))
            .body("EventDestinations[0].SnsDestination.TopicArn",
                    equalTo("arn:aws:sns:us-east-1:000000000000:ses-events-2"));
    }

    @Test
    @Order(9)
    void updateEventDestination_unknownReturns404() {
        v2()
            .body("""
                {
                  "EventDestination": {
                    "MatchingEventTypes": ["SEND"],
                    "SnsDestination": {"TopicArn": "arn:aws:sns:us-east-1:000000000000:x"}
                  }
                }
                """)
        .when()
            .put("/v2/email/configuration-sets/" + CS + "/event-destinations/ed-ghost")
        .then()
            .statusCode(404)
            .body("__type", equalTo("NotFoundException"))
            .body("message", equalTo(
                    "An event destination with name <ed-ghost> does not exist for configuration set <v2-cs-ed>."));
    }

    @Test
    @Order(10)
    void deleteEventDestination() {
        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/v2/email/configuration-sets/" + CS + "/event-destinations/ed-sns")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v2/email/configuration-sets/" + CS + "/event-destinations")
        .then()
            .statusCode(200)
            .body("EventDestinations", hasSize(0));
    }

    @Test
    @Order(11)
    void deleteEventDestination_unknownReturns404() {
        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/v2/email/configuration-sets/" + CS + "/event-destinations/ed-ghost")
        .then()
            .statusCode(404)
            .body("__type", equalTo("NotFoundException"))
            .body("message", equalTo(
                    "An event destination with name <ed-ghost> does not exist for configuration set <v2-cs-ed>."));
    }

    @Test
    @Order(12)
    void getEventDestinations_unknownConfigSetReturns404() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v2/email/configuration-sets/v2-cs-ghost/event-destinations")
        .then()
            .statusCode(404)
            .body("__type", equalTo("NotFoundException"))
            .body("message", equalTo("Configuration set <v2-cs-ghost> does not exist."));
    }

    @Test
    @Order(13)
    void updateEventDestination_invalidName_returns400() {
        v2()
            .body("""
                {
                  "EventDestination": {
                    "MatchingEventTypes": ["SEND"],
                    "SnsDestination": {"TopicArn": "arn:aws:sns:us-east-1:000000000000:x"}
                  }
                }
                """)
        .when()
            .put("/v2/email/configuration-sets/" + CS + "/event-destinations/bad.name")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("Invalid event destination name <bad.name>: only alphanumeric ASCII "
                    + "characters, '_', and '-' are allowed."));
    }

    @Test
    @Order(14)
    void deleteEventDestination_invalidName_returns400() {
        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/v2/email/configuration-sets/" + CS + "/event-destinations/bad.name")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("Invalid event destination name <bad.name>: only alphanumeric ASCII "
                    + "characters, '_', and '-' are allowed."));
    }

    @Test
    @Order(15)
    void updateEventDestination_preservesOrderAmongMultiple() {
        String cs = "v2-cs-ed-multi";
        v2()
            .body("{\"ConfigurationSetName\": \"" + cs + "\"}")
        .when()
            .post("/v2/email/configuration-sets")
        .then()
            .statusCode(200);

        for (String n : new String[]{"ed-a", "ed-b"}) {
            v2()
                .body("""
                    {
                      "EventDestinationName": "%s",
                      "EventDestination": {
                        "Enabled": true,
                        "MatchingEventTypes": ["SEND"],
                        "SnsDestination": {"TopicArn": "arn:aws:sns:us-east-1:000000000000:%s"}
                      }
                    }
                    """.formatted(n, n))
            .when()
                .post("/v2/email/configuration-sets/" + cs + "/event-destinations")
            .then()
                .statusCode(200);
        }

        v2()
            .body("""
                {
                  "EventDestination": {
                    "Enabled": false,
                    "MatchingEventTypes": ["DELIVERY"],
                    "SnsDestination": {"TopicArn": "arn:aws:sns:us-east-1:000000000000:ed-a-updated"}
                  }
                }
                """)
        .when()
            .put("/v2/email/configuration-sets/" + cs + "/event-destinations/ed-a")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v2/email/configuration-sets/" + cs + "/event-destinations")
        .then()
            .statusCode(200)
            .body("EventDestinations", hasSize(2))
            .body("EventDestinations[0].Name", equalTo("ed-a"))
            .body("EventDestinations[1].Name", equalTo("ed-b"))
            .body("EventDestinations[0].Enabled", equalTo(false))
            .body("EventDestinations[0].SnsDestination.TopicArn",
                    equalTo("arn:aws:sns:us-east-1:000000000000:ed-a-updated"));
    }

    @Test
    @Order(16)
    void createEventBridgeConfigurationSet() {
        String cs = "v2-cs-ed-ebus";
        v2()
            .body("{\"ConfigurationSetName\": \"" + cs + "\"}")
        .when()
            .post("/v2/email/configuration-sets")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(17)
    void createEventDestination_eventBridgeCustomBusRejected() {
        String cs = "v2-cs-ed-ebus";
        v2()
            .body("""
                {
                  "EventDestinationName": "ed-eb-custom",
                  "EventDestination": {
                    "Enabled": true,
                    "MatchingEventTypes": ["SEND"],
                    "EventBridgeDestination": {
                      "EventBusArn": "arn:aws:events:us-east-1:000000000000:event-bus/custom-bus"
                    }
                  }
                }
                """)
        .when()
            .post("/v2/email/configuration-sets/" + cs + "/event-destinations")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("Invalid Event Bridge destination, must be default bus."));

        // The rejected destination must not have been stored.
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v2/email/configuration-sets/" + cs + "/event-destinations")
        .then()
            .statusCode(200)
            .body("EventDestinations", hasSize(0));
    }

    @Test
    @Order(18)
    void updateEventDestination_eventBridgeCustomBusRejectedAndDefaultPreserved() {
        String cs = "v2-cs-ed-ebus";
        String defaultBusArn = "arn:aws:events:us-east-1:000000000000:event-bus/default";

        // Seed a destination on the default event bus.
        v2()
            .body("""
                {
                  "EventDestinationName": "ed-eb-default",
                  "EventDestination": {
                    "Enabled": true,
                    "MatchingEventTypes": ["SEND"],
                    "EventBridgeDestination": {"EventBusArn": "%s"}
                  }
                }
                """.formatted(defaultBusArn))
        .when()
            .post("/v2/email/configuration-sets/" + cs + "/event-destinations")
        .then()
            .statusCode(200);

        // An otherwise identical ARN naming a custom event bus must be rejected.
        v2()
            .body("""
                {
                  "EventDestination": {
                    "Enabled": true,
                    "MatchingEventTypes": ["SEND"],
                    "EventBridgeDestination": {
                      "EventBusArn": "arn:aws:events:us-east-1:000000000000:event-bus/custom-bus"
                    }
                  }
                }
                """)
        .when()
            .put("/v2/email/configuration-sets/" + cs + "/event-destinations/ed-eb-default")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("Invalid Event Bridge destination, must be default bus."));

        // The stored destination must still point at the default event bus.
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v2/email/configuration-sets/" + cs + "/event-destinations")
        .then()
            .statusCode(200)
            .body("EventDestinations", hasSize(1))
            .body("EventDestinations[0].Name", equalTo("ed-eb-default"))
            .body("EventDestinations[0].EventBridgeDestination.EventBusArn",
                    equalTo(defaultBusArn));
    }

    @Test
    @Order(19)
    void createEventDestination_eventBridgeDefaultBusNonCommercialPartitionSucceeds() {
        String cs = "v2-cs-ed-ebus";
        String cnAuthHeader = SesV2Requests.authFor("cn-north-1");
        String cnDefaultBusArn =
                "arn:aws-cn:events:cn-north-1:000000000000:event-bus/default";
        v2(cnAuthHeader)
            .body("{\"ConfigurationSetName\": \"" + cs + "\"}")
        .when()
            .post("/v2/email/configuration-sets")
        .then()
            .statusCode(200);

        v2(cnAuthHeader)
            .body("""
                {
                  "EventDestinationName": "ed-eb-default-cn",
                  "EventDestination": {
                    "Enabled": true,
                    "MatchingEventTypes": ["SEND"],
                    "EventBridgeDestination": {"EventBusArn": "%s"}
                  }
                }
                """.formatted(cnDefaultBusArn))
        .when()
            .post("/v2/email/configuration-sets/" + cs + "/event-destinations")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", cnAuthHeader)
        .when()
            .get("/v2/email/configuration-sets/" + cs + "/event-destinations")
        .then()
            .statusCode(200)
            .body("EventDestinations.find { it.Name == 'ed-eb-default-cn' }"
                    + ".EventBridgeDestination.EventBusArn",
                    equalTo(cnDefaultBusArn));
    }

    @Test
    @Order(20)
    void createEventDestination_eventBridgeBlankBusArnRejected() {
        String cs = "v2-cs-ed-ebus";
        v2()
            .body("""
                {
                  "EventDestinationName": "ed-eb-blank",
                  "EventDestination": {
                    "Enabled": true,
                    "MatchingEventTypes": ["SEND"],
                    "EventBridgeDestination": {"EventBusArn": ""}
                  }
                }
                """)
        .when()
            .post("/v2/email/configuration-sets/" + cs + "/event-destinations")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "empty-fields,arn::events:::event-bus/default",
            "missing-partition,arn::events:us-east-1:000000000000:event-bus/default",
            "missing-region,arn:aws:events::000000000000:event-bus/default",
            "missing-account,arn:aws:events:us-east-1::event-bus/default",
            "invalid-partition,arn:example:events:us-east-1:000000000000:event-bus/default",
            "short-account,arn:aws:events:us-east-1:123:event-bus/default",
            "mismatched-account,arn:aws:events:us-east-1:111111111111:event-bus/default",
            "mismatched-region,arn:aws:events:us-west-2:000000000000:event-bus/default"
    })
    @Order(21)
    void createEventDestination_eventBridgeInvalidArnRejected(String caseName, String busArn) {
        String cs = "v2-cs-ed-ebus";
        String destinationName = "ed-eb-invalid-create-" + caseName;
        v2()
            .body("""
                {
                  "EventDestinationName": "%s",
                  "EventDestination": {
                    "Enabled": true,
                    "MatchingEventTypes": ["SEND"],
                    "EventBridgeDestination": {"EventBusArn": "%s"}
                  }
                }
                """.formatted(destinationName, busArn))
        .when()
            .post("/v2/email/configuration-sets/" + cs + "/event-destinations")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v2/email/configuration-sets/" + cs + "/event-destinations")
        .then()
            .statusCode(200)
            .body("EventDestinations.find { it.Name == '" + destinationName + "' }", nullValue());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "empty-fields,arn::events:::event-bus/default",
            "missing-partition,arn::events:us-east-1:000000000000:event-bus/default",
            "missing-region,arn:aws:events::000000000000:event-bus/default",
            "missing-account,arn:aws:events:us-east-1::event-bus/default",
            "invalid-partition,arn:example:events:us-east-1:000000000000:event-bus/default",
            "short-account,arn:aws:events:us-east-1:123:event-bus/default",
            "mismatched-account,arn:aws:events:us-east-1:111111111111:event-bus/default",
            "mismatched-region,arn:aws:events:us-west-2:000000000000:event-bus/default"
    })
    @Order(22)
    void updateEventDestination_eventBridgeInvalidArnRejectedAndDefaultPreserved(
            String caseName, String busArn) {
        String cs = "v2-cs-ed-ebus";
        String destinationName = "ed-eb-invalid-update-" + caseName;
        String defaultBusArn = "arn:aws:events:us-east-1:000000000000:event-bus/default";
        v2()
            .body("""
                {
                  "EventDestinationName": "%s",
                  "EventDestination": {
                    "Enabled": true,
                    "MatchingEventTypes": ["SEND"],
                    "EventBridgeDestination": {"EventBusArn": "%s"}
                  }
                }
                """.formatted(destinationName, defaultBusArn))
        .when()
            .post("/v2/email/configuration-sets/" + cs + "/event-destinations")
        .then()
            .statusCode(200);

        try {
            v2()
                .body("""
                    {
                      "EventDestination": {
                        "Enabled": false,
                        "MatchingEventTypes": ["BOUNCE"],
                        "EventBridgeDestination": {"EventBusArn": "%s"}
                      }
                    }
                    """.formatted(busArn))
            .when()
                .put("/v2/email/configuration-sets/" + cs + "/event-destinations/" + destinationName)
            .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"));

            given()
                .header("Authorization", AUTH)
            .when()
                .get("/v2/email/configuration-sets/" + cs + "/event-destinations")
            .then()
                .statusCode(200)
                .body("EventDestinations.find { it.Name == '" + destinationName + "' }"
                        + ".Enabled", equalTo(true))
                .body("EventDestinations.find { it.Name == '" + destinationName + "' }"
                        + ".MatchingEventTypes", equalTo(List.of("SEND")))
                .body("EventDestinations.find { it.Name == '" + destinationName + "' }"
                        + ".EventBridgeDestination.EventBusArn", equalTo(defaultBusArn));
        } finally {
            given()
                .header("Authorization", AUTH)
            .when()
                .delete("/v2/email/configuration-sets/" + cs + "/event-destinations/" + destinationName)
            .then()
                .statusCode(200);
        }
    }

    @Test
    @Order(23)
    void eventBridgeDefaultBusMatchingNonDefaultRequestScopeSucceeds() {
        String cs = "v2-cs-ed-request-scope";
        v2(SCOPED_AUTH_HEADER)
            .body("{\"ConfigurationSetName\": \"" + cs + "\"}")
        .when()
            .post("/v2/email/configuration-sets")
        .then()
            .statusCode(200);

        v2(SCOPED_AUTH_HEADER)
            .body("""
                {
                  "EventDestinationName": "ed-eb-scoped-default",
                  "EventDestination": {
                    "Enabled": true,
                    "MatchingEventTypes": ["SEND"],
                    "EventBridgeDestination": {"EventBusArn": "%s"}
                  }
                }
                """.formatted(SCOPED_DEFAULT_BUS_ARN))
        .when()
            .post("/v2/email/configuration-sets/" + cs + "/event-destinations")
        .then()
            .statusCode(200);

        v2(SCOPED_AUTH_HEADER)
            .body("""
                {
                  "EventDestination": {
                    "Enabled": false,
                    "MatchingEventTypes": ["BOUNCE"],
                    "EventBridgeDestination": {"EventBusArn": "%s"}
                  }
                }
                """.formatted(SCOPED_DEFAULT_BUS_ARN))
        .when()
            .put("/v2/email/configuration-sets/" + cs + "/event-destinations/ed-eb-scoped-default")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", SCOPED_AUTH_HEADER)
        .when()
            .get("/v2/email/configuration-sets/" + cs + "/event-destinations")
        .then()
            .statusCode(200)
            .body("EventDestinations", hasSize(1))
            .body("EventDestinations[0].Enabled", equalTo(false))
            .body("EventDestinations[0].MatchingEventTypes", equalTo(List.of("BOUNCE")))
            .body("EventDestinations[0].EventBridgeDestination.EventBusArn",
                    equalTo(SCOPED_DEFAULT_BUS_ARN));
    }

    @Test
    @Order(100)
    void snsDestination_missingTopicRejectedOnCreateAndUpdate() {
        String body = """
                {
                  "EventDestinationName": "ed-sns",
                  "EventDestination": {
                    "MatchingEventTypes": ["SEND"],
                    "SnsDestination": {"TopicArn": "arn:aws:sns:us-east-1:000000000000:never-created"}
                  }
                }
                """;
        String message = "SNS topic <arn:aws:sns:us-east-1:000000000000:never-created> not found.";
        String set = "v2-cs-sns-check";
        v2()
            .body("{\"ConfigurationSetName\": \"" + set + "\"}")
        .when()
            .post("/v2/email/configuration-sets")
        .then()
            .statusCode(200);
        v2()
            .body(body.replace("never-created", "x"))
        .when()
            .post("/v2/email/configuration-sets/" + set + "/event-destinations")
        .then()
            .statusCode(200);
        v2()
            .body(body)
        .when()
            .post("/v2/email/configuration-sets/" + set + "/event-destinations")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo(message));
        v2()
            .body(body)
        .when()
            .post("/v2/email/configuration-sets/v2-cs-ghost/event-destinations")
        .then()
            .statusCode(400)
            .body("message", equalTo(message));
        v2()
            .body(body)
        .when()
            .put("/v2/email/configuration-sets/" + set + "/event-destinations/ed-sns")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo(message));
    }
}
