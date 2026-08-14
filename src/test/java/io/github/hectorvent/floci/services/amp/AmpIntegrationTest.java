package io.github.hectorvent.floci.services.amp;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AmpIntegrationTest {

    private static String workspaceId;
    private static String workspaceArn;
    private static String scraperId;

    @Test
    @Order(1)
    void createWorkspace() {
        var response = given()
            .contentType("application/json")
            .body("""
                {"alias": "integration-workspace", "tags": {"team": "obs"}}
                """)
        .when()
            .post("/workspaces")
        .then()
            .statusCode(202)
            .body("workspaceId", startsWith("ws-"))
            .body("arn", containsString(":aps:"))
            .body("status.statusCode", equalTo("ACTIVE"))
            .body("tags.team", equalTo("obs"))
            .extract();
        workspaceId = response.path("workspaceId");
        workspaceArn = response.path("arn");
    }

    @Test
    @Order(2)
    void describeWorkspace() {
        given()
        .when()
            .get("/workspaces/" + workspaceId)
        .then()
            .statusCode(200)
            .body("workspace.workspaceId", equalTo(workspaceId))
            .body("workspace.alias", equalTo("integration-workspace"))
            .body("workspace.status.statusCode", equalTo("ACTIVE"))
            .body("workspace.prometheusEndpoint", notNullValue())
            .body("workspace.createdAt", notNullValue())
            .body("workspace.tags.team", equalTo("obs"));
    }

    @Test
    @Order(3)
    void describeMissingWorkspaceReturnsResourceNotFound() {
        given()
        .when()
            .get("/workspaces/ws-does-not-exist")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(4)
    void listTagsForWorkspace() {
        given()
        .when()
            .get("/tags/" + workspaceArn)
        .then()
            .statusCode(200)
            .body("tags.team", equalTo("obs"));
    }

    @Test
    @Order(5)
    void alertManagerDefinitionRoundTrip() {
        String data = java.util.Base64.getEncoder()
                .encodeToString("route:\n  receiver: default\n".getBytes());
        given()
            .contentType("application/json")
            .body("{\"data\": \"" + data + "\"}")
        .when()
            .post("/workspaces/" + workspaceId + "/alertmanager/definition")
        .then()
            .statusCode(202)
            .body("status.statusCode", equalTo("ACTIVE"));

        given()
        .when()
            .get("/workspaces/" + workspaceId + "/alertmanager/definition")
        .then()
            .statusCode(200)
            .body("alertManagerDefinition.data", equalTo(data))
            .body("alertManagerDefinition.status.statusCode", equalTo("ACTIVE"))
            .body("alertManagerDefinition.createdAt", notNullValue());
    }

    @Test
    @Order(6)
    void queryLoggingConfigurationRoundTrip() {
        given()
            .contentType("application/json")
            .body("""
                {"destinations": [{"cloudWatchLogs": {"logGroupArn": "arn:aws:logs:us-east-1:000000000000:log-group:/aps:*"},
                                   "filters": {"qspThreshold": 0}}]}
                """)
        .when()
            .post("/workspaces/" + workspaceId + "/logging/query")
        .then()
            .statusCode(202)
            .body("status.statusCode", equalTo("ACTIVE"));

        given()
        .when()
            .get("/workspaces/" + workspaceId + "/logging/query")
        .then()
            .statusCode(200)
            .body("queryLoggingConfiguration.workspace", equalTo(workspaceId))
            .body("queryLoggingConfiguration.status.statusCode", equalTo("ACTIVE"))
            .body("queryLoggingConfiguration.destinations[0].cloudWatchLogs.logGroupArn",
                    containsString(":log-group:/aps"));
    }

    @Test
    @Order(7)
    void createScraper() {
        var response = given()
            .contentType("application/json")
            .body("""
                {"scrapeConfiguration": {"configurationBlob": "Z2xvYmFsOiB7fQo="},
                 "source": {"eksConfiguration": {"clusterArn": "arn:aws:eks:us-east-1:000000000000:cluster/c1",
                                                 "subnetIds": ["subnet-0123456789abcdef0"]}},
                 "destination": {"ampConfiguration": {"workspaceArn": "%s"}},
                 "tags": {"team": "obs"}}
                """.formatted(workspaceArn))
        .when()
            .post("/scrapers")
        .then()
            .statusCode(202)
            .body("scraperId", startsWith("s-"))
            .body("arn", containsString(":scraper/"))
            .body("status.statusCode", equalTo("ACTIVE"))
            .extract();
        scraperId = response.path("scraperId");
    }

    @Test
    @Order(8)
    void describeScraper() {
        given()
        .when()
            .get("/scrapers/" + scraperId)
        .then()
            .statusCode(200)
            .body("scraper.scraperId", equalTo(scraperId))
            .body("scraper.roleArn", containsString(":role/"))
            .body("scraper.status.statusCode", equalTo("ACTIVE"))
            .body("scraper.destination.ampConfiguration.workspaceArn", equalTo(workspaceArn))
            .body("scraper.scrapeConfiguration.configurationBlob", equalTo("Z2xvYmFsOiB7fQo="));
    }

    @Test
    @Order(9)
    void scraperLoggingConfigurationRoundTrip() {
        given()
            .contentType("application/json")
            .body("""
                {"loggingDestination": {"cloudWatchLogs": {"logGroupArn": "arn:aws:logs:us-east-1:000000000000:log-group:/scraper:*"}}}
                """)
        .when()
            .put("/scrapers/" + scraperId + "/logging-configuration")
        .then()
            .statusCode(202)
            .body("status.statusCode", equalTo("ACTIVE"));

        given()
        .when()
            .get("/scrapers/" + scraperId + "/logging-configuration")
        .then()
            .statusCode(200)
            .body("scraperId", equalTo(scraperId))
            .body("loggingDestination.cloudWatchLogs.logGroupArn", containsString("/scraper"))
            .body("status.statusCode", equalTo("ACTIVE"));
    }

    @Test
    @Order(10)
    void deleteScraper() {
        given()
        .when()
            .delete("/scrapers/" + scraperId)
        .then()
            .statusCode(202)
            .body("scraperId", equalTo(scraperId))
            .body("status.statusCode", equalTo("DELETING"));

        given()
        .when()
            .get("/scrapers/" + scraperId)
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(11)
    void deleteWorkspace() {
        given()
        .when()
            .delete("/workspaces/" + workspaceId)
        .then()
            .statusCode(202);

        given()
        .when()
            .get("/workspaces/" + workspaceId)
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }
}
