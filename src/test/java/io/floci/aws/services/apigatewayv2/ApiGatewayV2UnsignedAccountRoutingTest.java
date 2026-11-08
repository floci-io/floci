package io.floci.aws.services.apigatewayv2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class ApiGatewayV2UnsignedAccountRoutingTest {

    private static final String ACCOUNT_A = "123456789012";
    private static final String ACCOUNT_B = "210987654321";

    @Test
    void signedAndUnsignedRequestsResolveTheAccountFromApiId() {
        String apiA = createApiGraph(ACCOUNT_A, "account-a-function");
        String apiB = createApiGraph(ACCOUNT_B, "account-b-function");

        // Each route is found in its API's account and reaches its integration, whose function does
        // not exist: API Gateway's 500, where an API it could not find would be a 404.
        given()
                .header("Authorization", authorization(ACCOUNT_B, "execute-api"))
                .when().get("/execute-api/" + apiA + "/$default/health")
                .then()
                .statusCode(500)
                .body(containsString("Internal Server Error"));

        given()
                .header("Host", apiB + ".execute-api.localhost.floci.io")
                .when().get("/health")
                .then()
                .statusCode(500)
                .body(containsString("Internal Server Error"));
    }

    @Test
    void rejectsDuplicateApiIdsAcrossAccounts() {
        String apiId = "shared-account-api";
        createApiWithId(ACCOUNT_A, apiId, 201);
        createApiWithId(ACCOUNT_B, apiId, 409);
    }

    private String createApiGraph(String accountId, String functionName) {
        String authorization = authorization(accountId, "apigateway");
        String apiId = given()
                .contentType(ContentType.JSON)
                .header("Authorization", authorization)
                .body("""
                        {"name":"shared-local-api","protocolType":"HTTP"}
                        """)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .body("apiId", notNullValue())
                .extract().path("apiId");

        String integrationId = given()
                .contentType(ContentType.JSON)
                .header("Authorization", authorization)
                .body("""
                        {
                          "integrationType":"AWS_PROXY",
                          "integrationUri":"arn:aws:lambda:us-east-1:%s:function:%s",
                          "payloadFormatVersion":"2.0"
                        }
                        """.formatted(accountId, functionName))
                .when().post("/v2/apis/" + apiId + "/integrations")
                .then()
                .statusCode(201)
                .body("integrationId", notNullValue())
                .extract().path("integrationId");

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", authorization)
                .body("""
                        {"routeKey":"GET /health","target":"integrations/%s"}
                        """.formatted(integrationId))
                .when().post("/v2/apis/" + apiId + "/routes")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .header("Authorization", authorization)
                .body("""
                        {"stageName":"$default","autoDeploy":true}
                        """)
                .when().post("/v2/apis/" + apiId + "/stages")
                .then()
                .statusCode(201);

        return apiId;
    }

    private String authorization(String accountId, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId
                + "/20260803/us-east-1/" + service + "/aws4_request";
    }

    private void createApiWithId(String accountId, String apiId, int expectedStatus) {
        given()
                .contentType(ContentType.JSON)
                .header("Authorization", authorization(accountId, "apigateway"))
                .body("""
                        {
                          "name":"account-owned-api",
                          "protocolType":"HTTP",
                          "tags":{"floci:override-id":"%s"}
                        }
                        """.formatted(apiId))
                .when().post("/v2/apis")
                .then()
                .statusCode(expectedStatus);
    }
}
