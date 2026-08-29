package io.github.hectorvent.floci.services.aps;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class ApsControllerIntegrationTest {

    // Region comes from the SigV4 credential scope; requests without the header use the default us-east-1.
    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260827/" + region
                + "/aps/aws4_request, SignedHeaders=host, Signature=abc";
    }

    private String createWorkspace(String alias) {
        return given()
            .contentType("application/json")
            .body("""
                {"alias": "%s", "tags": {"team": "devops"}, "clientToken": "token-123"}
                """.formatted(alias))
        .when()
            .post("/workspaces")
        .then()
            .statusCode(202)
            .body("workspaceId", startsWith("ws-"))
            .body("arn", containsString(":workspace/ws-"))
            .body("status.statusCode", equalTo("ACTIVE"))
            .body("tags.team", equalTo("devops"))
            .extract().path("workspaceId");
    }

    @Test
    void workspaceLifecycleRoundTrip() {
        String workspaceId = createWorkspace("lifecycle-test");

        given()
        .when()
            .get("/workspaces/{workspaceId}", workspaceId)
        .then()
            .statusCode(200)
            .body("workspace.workspaceId", equalTo(workspaceId))
            .body("workspace.alias", equalTo("lifecycle-test"))
            .body("workspace.status.statusCode", equalTo("ACTIVE"))
            .body("workspace.prometheusEndpoint",
                    equalTo("https://aps-workspaces.us-east-1.amazonaws.com/workspaces/" + workspaceId + "/"))
            // Epoch-seconds number, not an ISO string: restJson1 timestamps with no
            // timestampFormat trait fail SDK deserialization as strings.
            .body("workspace.createdAt", notNullValue())
            .body("workspace.tags.team", equalTo("devops"));

        given()
        .when()
            .get("/workspaces")
        .then()
            .statusCode(200)
            .body("workspaces.workspaceId", hasItem(workspaceId))
            // The WorkspaceSummary shape never carries prometheusEndpoint.
            .body("workspaces.find { it.workspaceId == '" + workspaceId + "' }.prometheusEndpoint",
                    equalTo(null));

        // The model documents "an HTTP 202 response with an empty HTTP body" for the delete.
        given()
        .when()
            .delete("/workspaces/{workspaceId}", workspaceId)
        .then()
            .statusCode(202)
            .body(is(emptyString()));

        // The terraform/pulumi provider's delete waiter matches the typed error via the
        // X-Amzn-Errortype header; assert the wire contract, not just the status.
        given()
        .when()
            .get("/workspaces/{workspaceId}", workspaceId)
        .then()
            .statusCode(404)
            .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listWorkspacesFiltersByAliasPrefix() {
        String matching = createWorkspace("prefix-match-a");
        createWorkspace("other-alias");

        given()
        .when()
            .get("/workspaces?alias=prefix-match")
        .then()
            .statusCode(200)
            .body("workspaces.workspaceId", hasItem(matching))
            .body("workspaces.alias", not(hasItem("other-alias")));
    }

    @Test
    void listWorkspacesRejectsZeroMaxResults() {
        given()
        .when()
            .get("/workspaces?maxResults=0")
        .then()
            .statusCode(400)
            .header("X-Amzn-Errortype", equalTo("ValidationException"));
    }

    @Test
    void updateWorkspaceAliasReturns204AndPersists() {
        String workspaceId = createWorkspace("alias-before");

        given()
            .contentType("application/json")
            .body("{\"alias\": \"alias-after\"}")
        .when()
            .post("/workspaces/{workspaceId}/alias", workspaceId)
        .then()
            .statusCode(204);

        given()
        .when()
            .get("/workspaces/{workspaceId}", workspaceId)
        .then()
            .statusCode(200)
            .body("workspace.alias", equalTo("alias-after"));
    }

    @Test
    void workspacesAreIsolatedBetweenRegions() {
        String workspaceId = createWorkspace("region-isolation-test");

        // The same workspace id does not exist when the request is signed for another region.
        given()
            .header("Authorization", auth("eu-west-1"))
        .when()
            .get("/workspaces/{workspaceId}", workspaceId)
        .then()
            .statusCode(404)
            .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));

        given()
            .header("Authorization", auth("eu-west-1"))
        .when()
            .get("/workspaces")
        .then()
            .statusCode(200)
            .body("workspaces.workspaceId", not(hasItem(workspaceId)));

        given()
            .header("Authorization", auth("eu-west-1"))
        .when()
            .delete("/workspaces/{workspaceId}", workspaceId)
        .then()
            .statusCode(404);

        // Still present in its own region after the cross-region delete attempt.
        given()
        .when()
            .get("/workspaces/{workspaceId}", workspaceId)
        .then()
            .statusCode(200);
    }

    @Test
    void deleteWorkspaceUnknownIdReturns404() {
        given()
        .when()
            .delete("/workspaces/{workspaceId}", "ws-missing")
        .then()
            .statusCode(404)
            .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));
    }

    @Test
    void tagsRoundTripThroughSharedTagsDispatcher() {
        String workspaceId = createWorkspace("tags-test");
        String arn = given()
            .when().get("/workspaces/{workspaceId}", workspaceId)
            .then().statusCode(200)
            .extract().path("workspace.arn");

        given()
        .when()
            .get("/tags/{arn}", arn)
        .then()
            .statusCode(200)
            .body("tags.team", equalTo("devops"));

        // AMP defines TagResource/UntagResource with 200 responses, not the dispatcher's
        // default 204.
        given()
            .contentType("application/json")
            .body("{\"tags\": {\"env\": \"test\"}}")
        .when()
            .post("/tags/{arn}", arn)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/tags/{arn}", arn)
        .then()
            .statusCode(200)
            .body("tags.team", equalTo("devops"))
            .body("tags.env", equalTo("test"));

        given()
        .when()
            .delete("/tags/{arn}?tagKeys=team", arn)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/tags/{arn}", arn)
        .then()
            .statusCode(200)
            .body("tags.env", equalTo("test"))
            .body("tags", not(org.hamcrest.Matchers.hasKey("team")));
    }

    @Test
    void tagResourceRejectsReservedAwsKeyPrefix() {
        String workspaceId = createWorkspace("aws-prefix-test");
        String arn = given()
            .when().get("/workspaces/{workspaceId}", workspaceId)
            .then().statusCode(200)
            .extract().path("workspace.arn");

        // The shared /tags route resolves the error-header protocol from the SigV4 credential
        // scope, so this request carries one the way a real SDK call would.
        given()
            .header("Authorization", auth("us-east-1"))
            .contentType("application/json")
            .body("{\"tags\": {\"aws:cloudformation:stack\": \"x\"}}")
        .when()
            .post("/tags/{arn}", arn)
        .then()
            .statusCode(400)
            .header("X-Amzn-Errortype", equalTo("ValidationException"));
    }
}
