package io.github.hectorvent.floci.services.appintegrations;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AppIntegrationsIntegrationTest {

    private static final String EVENT_INTEGRATION_NAME = "floci-event-integration";

    private static String eventIntegrationArn;
    private static String dataIntegrationId;
    private static String dataIntegrationArn;

    @Test
    @Order(1)
    void createEventIntegration() {
        eventIntegrationArn = given()
            .contentType("application/json")
            .body("""
                {
                  "Name": "%s",
                  "Description": "partner events",
                  "EventBridgeBus": "floci-bus",
                  "EventFilter": {"Source": "aws.partner/example.com/1234"},
                  "Tags": {"team": "data"}
                }
                """.formatted(EVENT_INTEGRATION_NAME))
        .when()
            .post("/eventIntegrations")
        .then()
            .statusCode(200)
            .body("EventIntegrationArn", containsString(":app-integrations:"))
            .body("EventIntegrationArn", containsString(":event-integration/" + EVENT_INTEGRATION_NAME))
            .extract().path("EventIntegrationArn");
    }

    @Test
    @Order(2)
    void getEventIntegrationEchoesTheRequest() {
        given()
        .when()
            .get("/eventIntegrations/" + EVENT_INTEGRATION_NAME)
        .then()
            .statusCode(200)
            .body("Name", equalTo(EVENT_INTEGRATION_NAME))
            .body("Description", equalTo("partner events"))
            .body("EventIntegrationArn", equalTo(eventIntegrationArn))
            .body("EventBridgeBus", equalTo("floci-bus"))
            .body("EventFilter.Source", equalTo("aws.partner/example.com/1234"))
            .body("Tags.team", equalTo("data"));
    }

    @Test
    @Order(3)
    void listEventIntegrations() {
        given()
        .when()
            .get("/eventIntegrations")
        .then()
            .statusCode(200)
            .body("EventIntegrations.Name", hasItem(EVENT_INTEGRATION_NAME))
            .body("EventIntegrations.find { it.Name == '" + EVENT_INTEGRATION_NAME + "' }.EventBridgeBus",
                    equalTo("floci-bus"));
    }

    @Test
    @Order(4)
    void listEventIntegrationAssociationsIsEmptyForANewIntegration() {
        given()
        .when()
            .get("/eventIntegrations/" + EVENT_INTEGRATION_NAME + "/associations")
        .then()
            .statusCode(200)
            .body("EventIntegrationAssociations.size()", equalTo(0));
    }

    @Test
    @Order(5)
    void listAssociationsForAMissingIntegrationReturnsResourceNotFound() {
        given()
        .when()
            .get("/eventIntegrations/no-such-integration/associations")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(6)
    void tagRoundTripOnTheEventIntegration() {
        given()
        .when()
            .get("/tags/" + eventIntegrationArn)
        .then()
            .statusCode(200)
            .body("tags.team", equalTo("data"));

        given()
            .contentType("application/json")
            .body("{\"tags\": {\"env\": \"test\"}}")
        .when()
            .post("/tags/" + eventIntegrationArn)
        .then()
            .statusCode(204);

        given()
        .when()
            .get("/tags/" + eventIntegrationArn)
        .then()
            .statusCode(200)
            .body("tags.env", equalTo("test"));

        given()
            .queryParam("tagKeys", "env")
        .when()
            .delete("/tags/" + eventIntegrationArn)
        .then()
            .statusCode(204);

        given()
        .when()
            .get("/tags/" + eventIntegrationArn)
        .then()
            .statusCode(200)
            .body("tags.env", nullValue())
            .body("tags.team", equalTo("data"));
    }

    @Test
    @Order(7)
    void updateEventIntegration() {
        given()
            .contentType("application/json")
            .body("{\"Description\": \"partner events, revised\"}")
        .when()
            .patch("/eventIntegrations/" + EVENT_INTEGRATION_NAME)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/eventIntegrations/" + EVENT_INTEGRATION_NAME)
        .then()
            .statusCode(200)
            .body("Description", equalTo("partner events, revised"))
            .body("EventBridgeBus", equalTo("floci-bus"))
            .body("EventFilter.Source", equalTo("aws.partner/example.com/1234"));
    }

    @Test
    @Order(8)
    void duplicateEventIntegrationNameReturnsDuplicateResource() {
        given()
            .contentType("application/json")
            .body("""
                {
                  "Name": "%s",
                  "EventBridgeBus": "floci-bus",
                  "EventFilter": {"Source": "aws.partner/example.com/1234"}
                }
                """.formatted(EVENT_INTEGRATION_NAME))
        .when()
            .post("/eventIntegrations")
        .then()
            .statusCode(409)
            .body("__type", equalTo("DuplicateResourceException"));
    }

    @Test
    @Order(9)
    void createEventIntegrationWithoutEventFilterReturnsInvalidRequest() {
        given()
            .contentType("application/json")
            .body("{\"Name\": \"no-filter\", \"EventBridgeBus\": \"floci-bus\"}")
        .when()
            .post("/eventIntegrations")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    @Order(10)
    void getMissingEventIntegrationReturnsResourceNotFound() {
        given()
        .when()
            .get("/eventIntegrations/no-such-integration")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(11)
    void createDataIntegration() {
        var response = given()
            .contentType("application/json")
            .body("""
                {
                  "Name": "floci-data-integration",
                  "Description": "salesforce pull",
                  "KmsKey": "arn:aws:kms:us-east-1:000000000000:key/abc",
                  "SourceURI": "Salesforce://AppFlow/test",
                  "ScheduleConfig": {"ScheduleExpression": "rate(1 hour)", "FirstExecutionFrom": "1439788800000"},
                  "FileConfiguration": {"Folders": ["/home/data"]},
                  "Tags": {"team": "data"}
                }
                """)
        .when()
            .post("/dataIntegrations")
        .then()
            .statusCode(200)
            .body("Id", notNullValue())
            .body("Arn", containsString(":data-integration/"))
            .body("Name", equalTo("floci-data-integration"))
            .body("KmsKey", equalTo("arn:aws:kms:us-east-1:000000000000:key/abc"))
            .body("SourceURI", equalTo("Salesforce://AppFlow/test"))
            .body("ScheduleConfiguration.ScheduleExpression", equalTo("rate(1 hour)"))
            .body("FileConfiguration.Folders[0]", equalTo("/home/data"))
            .body("Tags.team", equalTo("data"))
            .extract();
        dataIntegrationId = response.path("Id");
        dataIntegrationArn = response.path("Arn");
    }

    @Test
    @Order(12)
    void getDataIntegrationByIdAndByArn() {
        given()
        .when()
            .get("/dataIntegrations/" + dataIntegrationId)
        .then()
            .statusCode(200)
            .body("Id", equalTo(dataIntegrationId))
            .body("Arn", equalTo(dataIntegrationArn))
            .body("Description", equalTo("salesforce pull"))
            .body("ScheduleConfiguration.FirstExecutionFrom", equalTo("1439788800000"));

        String encodedArn = java.net.URLEncoder.encode(dataIntegrationArn,
                java.nio.charset.StandardCharsets.UTF_8);
        given()
            .urlEncodingEnabled(false)
        .when()
            .get("/dataIntegrations/" + encodedArn)
        .then()
            .statusCode(200)
            .body("Id", equalTo(dataIntegrationId));
    }

    @Test
    @Order(13)
    void listDataIntegrations() {
        given()
        .when()
            .get("/dataIntegrations")
        .then()
            .statusCode(200)
            .body("DataIntegrations.Name", hasItem("floci-data-integration"))
            .body("DataIntegrations.find { it.Name == 'floci-data-integration' }.Arn",
                    equalTo(dataIntegrationArn))
            .body("DataIntegrations.find { it.Name == 'floci-data-integration' }.SourceURI",
                    equalTo("Salesforce://AppFlow/test"));
    }

    @Test
    @Order(14)
    void tagRoundTripOnTheDataIntegration() {
        given()
            .contentType("application/json")
            .body("{\"tags\": {\"tier\": \"gold\"}}")
        .when()
            .post("/tags/" + dataIntegrationArn)
        .then()
            .statusCode(204);

        given()
        .when()
            .get("/tags/" + dataIntegrationArn)
        .then()
            .statusCode(200)
            .body("tags.tier", equalTo("gold"))
            .body("tags.team", equalTo("data"));
    }

    @Test
    @Order(15)
    void updateDataIntegration() {
        given()
            .contentType("application/json")
            .body("{\"Description\": \"salesforce pull, revised\"}")
        .when()
            .patch("/dataIntegrations/" + dataIntegrationId)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/dataIntegrations/" + dataIntegrationId)
        .then()
            .statusCode(200)
            .body("Description", equalTo("salesforce pull, revised"))
            .body("Name", equalTo("floci-data-integration"));
    }

    @Test
    @Order(16)
    void createDataIntegrationWithoutKmsKeyReturnsInvalidRequest() {
        given()
            .contentType("application/json")
            .body("{\"Name\": \"no-key\"}")
        .when()
            .post("/dataIntegrations")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    @Order(17)
    void getMissingDataIntegrationReturnsResourceNotFound() {
        given()
        .when()
            .get("/dataIntegrations/00000000-0000-0000-0000-00000000dead")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(18)
    void deleteDataIntegration() {
        given()
        .when()
            .delete("/dataIntegrations/" + dataIntegrationId)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/dataIntegrations/" + dataIntegrationId)
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(19)
    void deleteEventIntegration() {
        given()
        .when()
            .delete("/eventIntegrations/" + EVENT_INTEGRATION_NAME)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/eventIntegrations/" + EVENT_INTEGRATION_NAME)
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));

        given()
        .when()
            .delete("/eventIntegrations/" + EVENT_INTEGRATION_NAME)
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }
}
