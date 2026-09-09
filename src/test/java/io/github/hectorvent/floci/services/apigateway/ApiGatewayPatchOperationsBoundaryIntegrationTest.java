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

    /**
     * A {@code null} element inside the patchOperations array must be rejected at the boundary. Every
     * downstream handler assumes each element is a map and calls {@code .get("op")} on it directly, so
     * letting a null through turns a client error into a 500 instead of the modelled 400.
     */
    @Test
    void testNullElementInPatchOperationsArrayIsRejected() {
        patchModelExpecting("{\"patchOperations\":[null]}", 400);
    }

    /**
     * UpdateRestApi has no per-handler null guard of its own, unlike some of the newer handlers — this
     * is the case that would 500 (NPE on {@code op.get("op")}) without the shared boundary rejecting
     * the null element before it ever reaches the service layer.
     */
    @Test
    void testNullElementInPatchOperationsArrayIsRejectedOnRestApi() {
        String apiId = createApi("patch-boundary-restapi-null-op");

        given()
                .contentType(ContentType.JSON)
                .body("{\"patchOperations\":[null]}")
                .patch("/restapis/" + apiId)
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

    // ── op (botocore: enum add|remove|replace|move|copy|test) ──

    private String createApiKey(String name) {
        return given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"" + name + "\",\"enabled\":true}")
                .post("/apikeys")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    /**
     * UpdateApiKey skips any operation it does not recognise, so an unmodelled op used to answer
     * 200 with the resource untouched — success for a request AWS rejects outright.
     */
    @Test
    void testUnmodelledOpOnApiKeyIsRejected() {
        String keyId = createApiKey("patch-boundary-op-enum");

        given()
                .contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"frobnicate\",\"path\":\"/name\",\"value\":\"after\"}]}")
                .patch("/apikeys/" + keyId)
                .then()
                .statusCode(400);

        given()
                .get("/apikeys/" + keyId)
                .then()
                .statusCode(200)
                .body("name", equalTo("patch-boundary-op-enum"));
    }

    @Test
    void testUnmodelledOpOnModelIsRejected() {
        patchModelExpecting("{\"patchOperations\":[{\"op\":\"REPLACE\",\"path\":\"/description\",\"value\":\"new\"}]}", 400);
    }

    /** A modelled op the handler does support must still go through. */
    @Test
    void testModelledOpOnApiKeyIsAccepted() {
        String keyId = createApiKey("patch-boundary-op-modelled");

        given()
                .contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/name\",\"value\":\"after\"}]}")
                .patch("/apikeys/" + keyId)
                .then()
                .statusCode(200)
                .body("name", equalTo("after"));
    }
}
