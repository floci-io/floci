package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class ApiGatewayUpdateAuthorizerIntegrationTest {

    @Test
    void shouldUpdateAuthorizerAndPersistChanges() {
        // Step 1: Create API
        Map<String, Object> createApiBody = new HashMap<>();
        createApiBody.put("name", "update-authorizer-api");

        String apiId = given()
                .contentType("application/json")
                .body(createApiBody)
                .when()
                .post("/restapis")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        // Step 2: Create Authorizer
        Map<String, Object> createAuthorizerBody = new HashMap<>();
        createAuthorizerBody.put("name", "before-update");
        createAuthorizerBody.put("type", "TOKEN");
        createAuthorizerBody.put("authorizerUri", "arn:aws:lambda:us-east-1:123456789012:function:my-authorizer");
        createAuthorizerBody.put("identitySource", "method.request.header.Authorization");
        createAuthorizerBody.put("authorizerResultTtlInSeconds", 300);

        String authorizerId = given()
                .contentType("application/json")
                .body(createAuthorizerBody)
                .when()
                .post("/restapis/" + apiId + "/authorizers")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        // Step 3: Update Authorizer via PATCH
        Map<String, Object> patchOp1 = new HashMap<>();
        patchOp1.put("op", "replace");
        patchOp1.put("path", "/name");
        patchOp1.put("value", "after-update");

        Map<String, Object> patchOp2 = new HashMap<>();
        patchOp2.put("op", "replace");
        patchOp2.put("path", "/identitySource");
        patchOp2.put("value", "method.request.header.X-Token");

        Map<String, Object> patchBody = new HashMap<>();
        patchBody.put("patchOperations", new Object[]{patchOp1, patchOp2});

        given()
                .contentType("application/json")
                .body(patchBody)
                .when()
                .patch("/restapis/" + apiId + "/authorizers/" + authorizerId)
                .then()
                .statusCode(200)
                .body("id", equalTo(authorizerId))
                .body("name", equalTo("after-update"))
                .body("identitySource", equalTo("method.request.header.X-Token"));

        // Step 4: GET Authorizer to verify persistence
        given()
                .when()
                .get("/restapis/" + apiId + "/authorizers/" + authorizerId)
                .then()
                .statusCode(200)
                .body("name", equalTo("after-update"))
                .body("identitySource", equalTo("method.request.header.X-Token"));
    }

    /**
     * A non-numeric /authorizerResultTtlInSeconds must be rejected before any mutation, otherwise the
     * stored value can no longer be serialised and every later GET/ListAuthorizers fails permanently.
     */
    @Test
    void shouldRejectNonNumericAuthorizerResultTtlAndLeaveAuthorizerReadable() {
        Map<String, Object> createApiBody = new HashMap<>();
        createApiBody.put("name", "ttl-validation-api");

        String apiId = given()
                .contentType("application/json")
                .body(createApiBody)
                .when()
                .post("/restapis")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        Map<String, Object> createAuthorizerBody = new HashMap<>();
        createAuthorizerBody.put("name", "ttl-authorizer");
        createAuthorizerBody.put("type", "TOKEN");
        createAuthorizerBody.put("authorizerUri", "arn:aws:lambda:us-east-1:123456789012:function:my-authorizer");
        createAuthorizerBody.put("identitySource", "method.request.header.Authorization");
        createAuthorizerBody.put("authorizerResultTtlInSeconds", 300);

        String authorizerId = given()
                .contentType("application/json")
                .body(createAuthorizerBody)
                .when()
                .post("/restapis/" + apiId + "/authorizers")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        Map<String, Object> badOp = new HashMap<>();
        badOp.put("op", "replace");
        badOp.put("path", "/authorizerResultTtlInSeconds");
        badOp.put("value", "not-a-number");

        Map<String, Object> patchBody = new HashMap<>();
        patchBody.put("patchOperations", new Object[]{badOp});

        given()
                .contentType("application/json")
                .body(patchBody)
                .when()
                .patch("/restapis/" + apiId + "/authorizers/" + authorizerId)
                .then()
                .statusCode(400);

        given()
                .when()
                .get("/restapis/" + apiId + "/authorizers/" + authorizerId)
                .then()
                .statusCode(200)
                .body("authorizerResultTtlInSeconds", equalTo(300));

        given()
                .when()
                .get("/restapis/" + apiId + "/authorizers")
                .then()
                .statusCode(200);
    }

    /**
     * A PATCH is all-or-nothing: a valid op followed by an invalid one must reject the whole request
     * without leaving the valid op's mutation visible.
     */
    @Test
    void shouldRejectWholePatchAndLeaveNameUnchangedWhenALaterOpIsInvalid() {
        Map<String, Object> createApiBody = new HashMap<>();
        createApiBody.put("name", "partial-apply-api");

        String apiId = given()
                .contentType("application/json")
                .body(createApiBody)
                .when()
                .post("/restapis")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        Map<String, Object> createAuthorizerBody = new HashMap<>();
        createAuthorizerBody.put("name", "original-name");
        createAuthorizerBody.put("type", "TOKEN");
        createAuthorizerBody.put("authorizerUri", "arn:aws:lambda:us-east-1:123456789012:function:my-authorizer");
        createAuthorizerBody.put("identitySource", "method.request.header.Authorization");

        String authorizerId = given()
                .contentType("application/json")
                .body(createAuthorizerBody)
                .when()
                .post("/restapis/" + apiId + "/authorizers")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        Map<String, Object> validOp = new HashMap<>();
        validOp.put("op", "replace");
        validOp.put("path", "/name");
        validOp.put("value", "renamed");

        Map<String, Object> invalidOp = new HashMap<>();
        invalidOp.put("op", "replace");
        invalidOp.put("path", "/authorizerResultTtlInSeconds");
        invalidOp.put("value", "not-a-number");

        Map<String, Object> patchBody = new HashMap<>();
        patchBody.put("patchOperations", new Object[]{validOp, invalidOp});

        given()
                .contentType("application/json")
                .body(patchBody)
                .when()
                .patch("/restapis/" + apiId + "/authorizers/" + authorizerId)
                .then()
                .statusCode(400);

        given()
                .when()
                .get("/restapis/" + apiId + "/authorizers/" + authorizerId)
                .then()
                .statusCode(200)
                .body("name", equalTo("original-name"));
    }
}
