package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * RestApi response members that clients read but that a caller never supplies:
 * rootResourceId, apiKeySource and disableExecuteApiEndpoint.
 *
 * @see <a href="https://docs.aws.amazon.com/apigateway/latest/api/API_GetRestApi.html">GetRestApi</a>
 */
@QuarkusTest
class ApiGatewayRestApiResponseFieldsTest {

    private String createApi(String name) {
        return given()
            .contentType("application/json")
            .body("{\"name\":\"" + name + "\"}")
        .when()
            .post("/restapis")
        .then()
            .statusCode(201)
            .extract().path("id");
    }

    @Test
    void createRestApiReportsTheRootResourceId() {
        given()
            .contentType("application/json")
            .body("{\"name\":\"root-id-on-create\"}")
        .when()
            .post("/restapis")
        .then()
            .statusCode(201)
            .body("rootResourceId", notNullValue());
    }

    @Test
    void getRestApiReportsTheIdOfTheResourceAtRoot() {
        String apiId = createApi("root-id-on-get");

        // Whatever GetResources calls the "/" resource is what the API must report,
        // otherwise a resource parented on rootResourceId would be orphaned.
        String rootFromResources = given()
        .when()
            .get("/restapis/" + apiId + "/resources")
        .then()
            .statusCode(200)
            // A freshly created API has exactly one resource: "/".
            .body("item.size()", equalTo(1))
            .body("item[0].path", equalTo("/"))
            .extract().path("item[0].id");

        given()
        .when()
            .get("/restapis/" + apiId)
        .then()
            .statusCode(200)
            .body("rootResourceId", equalTo(rootFromResources));
    }

    @Test
    void theReportedRootResourceIdCanParentANewResource() {
        String apiId = createApi("root-id-usable");
        String rootId = given()
        .when()
            .get("/restapis/" + apiId)
        .then()
            .statusCode(200)
            .extract().path("rootResourceId");

        given()
            .contentType("application/json")
            .body("{\"pathPart\":\"hello\"}")
        .when()
            .post("/restapis/" + apiId + "/resources/" + rootId)
        .then()
            .statusCode(201)
            .body("path", equalTo("/hello"))
            .body("parentId", equalTo(rootId));
    }

    @Test
    void getRestApiReportsApiKeySourceAndExecuteApiEndpointDefaults() {
        String apiId = createApi("api-defaults");

        given()
        .when()
            .get("/restapis/" + apiId)
        .then()
            .statusCode(200)
            .body("apiKeySource", equalTo("HEADER"))
            .body("disableExecuteApiEndpoint", is(false));
    }

    /**
     * rootResourceId is reported only when a resource at "/" exists, so the root has to be
     * undeletable for the member to be dependable. AWS keeps the root for the life of the
     * API: it is created with the API, has no pathPart to address it by, and DeleteResource
     * is documented as raising BadRequestException for a request it will not carry out.
     */
    @Test
    void theRootResourceCannotBeDeleted() {
        String apiId = createApi("root-undeletable");
        String rootId = given()
        .when()
            .get("/restapis/" + apiId)
        .then()
            .statusCode(200)
            .extract().path("rootResourceId");

        given()
        .when()
            .delete("/restapis/" + apiId + "/resources/" + rootId)
        .then()
            .statusCode(400)
            .body("message", containsString("root resource"));

        // And the API still reports it, rather than silently losing the member.
        given()
        .when()
            .get("/restapis/" + apiId)
        .then()
            .statusCode(200)
            .body("rootResourceId", equalTo(rootId));
    }

    @Test
    void deletingANonRootResourceStillSucceeds() {
        String apiId = createApi("child-deletable");
        String rootId = given()
        .when()
            .get("/restapis/" + apiId)
        .then()
            .extract().path("rootResourceId");

        String childId = given()
            .contentType("application/json")
            .body("{\"pathPart\":\"child\"}")
        .when()
            .post("/restapis/" + apiId + "/resources/" + rootId)
        .then()
            .statusCode(201)
            .extract().path("id");

        given()
        .when()
            .delete("/restapis/" + apiId + "/resources/" + childId)
        .then()
            .statusCode(202);
    }

    @Test
    void listRestApisReportsTheSameMembersAsGetRestApi() {
        String apiId = createApi("api-in-list");

        given()
        .when()
            .get("/restapis")
        .then()
            .statusCode(200)
            .body("item.findAll { it.id == '" + apiId + "' }.rootResourceId", everyItem(notNullValue()));
    }
}
