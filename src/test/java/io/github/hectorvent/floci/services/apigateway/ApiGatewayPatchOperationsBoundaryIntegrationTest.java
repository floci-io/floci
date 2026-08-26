package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * A malformed {@code patchOperations} envelope is a client error: AWS answers BadRequestException,
 * never a server fault. Each case below is a distinct shape that fails at a different point of the
 * shared boundary parse.
 */
@QuarkusTest
class ApiGatewayPatchOperationsBoundaryIntegrationTest {

    private String createApi(String name) {
        return given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"" + name + "\"}")
                .post("/restapis")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    private String createModel(String apiId, String modelName) {
        return given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"" + modelName + "\",\"description\":\"old\",\"contentType\":\"application/json\",\"schema\":\"{}\"}")
                .post("/restapis/" + apiId + "/models")
                .then()
                .statusCode(201)
                .extract()
                .path("name");
    }

    private void patchModelExpecting(String bodyJson, int expectedStatus) {
        String apiId = createApi("patch-boundary-" + Integer.toHexString(bodyJson.hashCode()));
        String modelName = createModel(apiId, "BoundaryModel");
        given()
                .contentType(ContentType.JSON)
                .body(bodyJson)
                .patch("/restapis/" + apiId + "/models/" + modelName)
                .then()
                .statusCode(expectedStatus);
    }

    /** A scalar where an array belongs. */
    @Test
    void testPatchOperationsAsStringIsRejected() {
        patchModelExpecting("{\"patchOperations\":\"oops\"}", 400);
    }

    /** A bare operation object rather than a one-element array. */
    @Test
    void testPatchOperationsAsObjectIsRejected() {
        patchModelExpecting("{\"patchOperations\":{\"op\":\"replace\",\"path\":\"/description\",\"value\":\"new\"}}", 400);
    }

    /** A structured {@code value} cannot be a PatchOperation value, which AWS models as a string. */
    @Test
    void testStructuredPatchValueIsRejected() {
        patchModelExpecting("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/description\",\"value\":{\"nested\":1}}]}", 400);
    }

    /** Body that is not JSON at all still fails at the boundary, not in the service. */
    @Test
    void testNonJsonBodyIsRejected() {
        patchModelExpecting("not json at all", 400);
    }

    /** A JSON scalar is coerced to the string PatchOperation values are modelled as. */
    @Test
    void testNumericPatchValueIsCoercedToString() {
        String apiId = createApi("patch-boundary-numeric");
        String modelName = createModel(apiId, "NumericModel");
        given()
                .contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/description\",\"value\":5}]}")
                .patch("/restapis/" + apiId + "/models/" + modelName)
                .then()
                .statusCode(200)
                .body("description", equalTo("5"));
    }

    /** UpdateRestApi predates this branch and shares the same boundary parse. */
    @Test
    void testMalformedEnvelopeOnRestApiIsRejected() {
        String apiId = createApi("patch-boundary-restapi");

        given()
                .contentType(ContentType.JSON)
                .body("{\"patchOperations\":\"oops\"}")
                .patch("/restapis/" + apiId)
                .then()
                .statusCode(400);
    }

    /** UpdateApiKey likewise, including the structured-value shape that used to reach the service. */
    @Test
    void testStructuredPatchValueOnApiKeyIsRejected() {
        String keyId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"patch-boundary-key\",\"enabled\":true}")
                .post("/apikeys")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/description\",\"value\":{\"nested\":1}}]}")
                .patch("/apikeys/" + keyId)
                .then()
                .statusCode(400);
    }

    /** The same boundary contract holds on a second handler, proving the parse is shared. */
    @Test
    void testMalformedEnvelopeOnRequestValidatorIsRejected() {
        String apiId = createApi("patch-boundary-validator");
        String validatorId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"before\",\"validateRequestBody\":false,\"validateRequestParameters\":true}")
                .post("/restapis/" + apiId + "/requestvalidators")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"patchOperations\":\"oops\"}")
                .patch("/restapis/" + apiId + "/requestvalidators/" + validatorId)
                .then()
                .statusCode(400);
    }
}
