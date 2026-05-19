package io.github.hectorvent.floci.services.apigatewayv2;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;

/**
 * Regression coverage for the HTTP API v2 management API returning {@code connectionType}
 * on Integration resources and {@code enableSimpleResponses} on Authorizer resources.
 * Both values are accepted on create/update and are required by IaC tools like Terraform
 * to avoid perceived drift on every plan.
 */
@QuarkusTest
class IntegrationConnectionTypeAndAuthorizerSimpleResponsesTest {

    private static final String AMZ_JSON = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "AmazonApiGatewayV2.";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260519/us-east-1/apigatewayv2/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    // ──────────────────────────── Integration.connectionType ────────────────────────────

    @Test
    void connectionTypeReturnedFromCreateGetAndList() {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"conn-type-create","protocolType":"HTTP"}
                        """)
                .when().post("/v2/apis")
                .then().statusCode(201)
                .extract().path("apiId");

        String integrationId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "integrationType":"HTTP_PROXY",
                          "integrationUri":"https://example.com",
                          "integrationMethod":"GET",
                          "connectionType":"VPC_LINK",
                          "payloadFormatVersion":"1.0"
                        }
                        """)
                .when().post("/v2/apis/" + apiId + "/integrations")
                .then().statusCode(201)
                .body("connectionType", equalTo("VPC_LINK"))
                .extract().path("integrationId");

        given()
                .when().get("/v2/apis/" + apiId + "/integrations/" + integrationId)
                .then().statusCode(200)
                .body("connectionType", equalTo("VPC_LINK"));

        given()
                .when().get("/v2/apis/" + apiId + "/integrations")
                .then().statusCode(200)
                .body("items.connectionType", hasItem("VPC_LINK"));
    }

    @Test
    void connectionTypeReturnedAfterUpdate() {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"conn-type-update","protocolType":"HTTP"}
                        """)
                .when().post("/v2/apis")
                .then().statusCode(201)
                .extract().path("apiId");

        String integrationId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "integrationType":"HTTP_PROXY",
                          "integrationUri":"https://example.com",
                          "integrationMethod":"GET",
                          "payloadFormatVersion":"1.0"
                        }
                        """)
                .when().post("/v2/apis/" + apiId + "/integrations")
                .then().statusCode(201)
                .extract().path("integrationId");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"connectionType":"VPC_LINK"}
                        """)
                .when().patch("/v2/apis/" + apiId + "/integrations/" + integrationId)
                .then().statusCode(200)
                .body("connectionType", equalTo("VPC_LINK"));

        given()
                .when().get("/v2/apis/" + apiId + "/integrations/" + integrationId)
                .then().statusCode(200)
                .body("connectionType", equalTo("VPC_LINK"));
    }

    @Test
    void connectionTypeOmittedWhenNull() {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"conn-type-omit","protocolType":"HTTP"}
                        """)
                .when().post("/v2/apis")
                .then().statusCode(201)
                .extract().path("apiId");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "integrationType":"HTTP_PROXY",
                          "integrationUri":"https://example.com",
                          "integrationMethod":"GET",
                          "payloadFormatVersion":"1.0"
                        }
                        """)
                .when().post("/v2/apis/" + apiId + "/integrations")
                .then().statusCode(201)
                .body("$", not(hasKey("connectionType")))
                .body("connectionType", nullValue());
    }

    // ──────────────────────────── Authorizer.enableSimpleResponses ────────────────────────────

    @Test
    void enableSimpleResponsesReturnedFromCreateGetAndList() {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"simple-resp-create","protocolType":"HTTP"}
                        """)
                .when().post("/v2/apis")
                .then().statusCode(201)
                .extract().path("apiId");

        String authorizerId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "authorizerType":"REQUEST",
                          "name":"lambda-authz",
                          "identitySource":["$request.header.Authorization"],
                          "authorizerUri":"arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/arn:aws:lambda:us-east-1:000000000000:function:authz/invocations",
                          "authorizerPayloadFormatVersion":"2.0",
                          "enableSimpleResponses":true
                        }
                        """)
                .when().post("/v2/apis/" + apiId + "/authorizers")
                .then().statusCode(201)
                .body("enableSimpleResponses", equalTo(true))
                .extract().path("authorizerId");

        given()
                .when().get("/v2/apis/" + apiId + "/authorizers/" + authorizerId)
                .then().statusCode(200)
                .body("enableSimpleResponses", equalTo(true));

        given()
                .when().get("/v2/apis/" + apiId + "/authorizers")
                .then().statusCode(200)
                .body("items.enableSimpleResponses", hasItem(true));
    }

    @Test
    void enableSimpleResponsesReturnedAfterUpdate() {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"simple-resp-update","protocolType":"HTTP"}
                        """)
                .when().post("/v2/apis")
                .then().statusCode(201)
                .extract().path("apiId");

        String authorizerId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "authorizerType":"REQUEST",
                          "name":"lambda-authz-2",
                          "identitySource":["$request.header.Authorization"],
                          "authorizerUri":"arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/arn:aws:lambda:us-east-1:000000000000:function:authz/invocations",
                          "authorizerPayloadFormatVersion":"2.0"
                        }
                        """)
                .when().post("/v2/apis/" + apiId + "/authorizers")
                .then().statusCode(201)
                .extract().path("authorizerId");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"enableSimpleResponses":true}
                        """)
                .when().patch("/v2/apis/" + apiId + "/authorizers/" + authorizerId)
                .then().statusCode(200)
                .body("enableSimpleResponses", equalTo(true));

        given()
                .when().get("/v2/apis/" + apiId + "/authorizers/" + authorizerId)
                .then().statusCode(200)
                .body("enableSimpleResponses", equalTo(true));
    }

    @Test
    void enableSimpleResponsesOmittedWhenNull() {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"simple-resp-omit","protocolType":"HTTP"}
                        """)
                .when().post("/v2/apis")
                .then().statusCode(201)
                .extract().path("apiId");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "authorizerType":"JWT",
                          "name":"jwt-authz",
                          "identitySource":["$request.header.Authorization"],
                          "jwtConfiguration":{"issuer":"https://example.com","audience":["api"]}
                        }
                        """)
                .when().post("/v2/apis/" + apiId + "/authorizers")
                .then().statusCode(201)
                .body("$", not(hasKey("enableSimpleResponses")))
                .body("enableSimpleResponses", nullValue());
    }

    // ──────────────────────────── JSON 1.1 dispatch ────────────────────────────

    @Test
    void connectionTypeReturnedViaJson11() {
        String apiId = given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "CreateApi")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"Name":"conn-type-json11","ProtocolType":"HTTP"}
                        """)
                .when().post("/")
                .then().statusCode(201)
                .extract().path("ApiId");

        String integrationId = given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "CreateIntegration")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","IntegrationType":"HTTP_PROXY","IntegrationUri":"https://example.com","IntegrationMethod":"GET","ConnectionType":"VPC_LINK","PayloadFormatVersion":"1.0"}
                        """.formatted(apiId))
                .when().post("/")
                .then().statusCode(201)
                .body("ConnectionType", equalTo("VPC_LINK"))
                .extract().path("IntegrationId");

        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "GetIntegration")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","IntegrationId":"%s"}
                        """.formatted(apiId, integrationId))
                .when().post("/")
                .then().statusCode(200)
                .body("ConnectionType", equalTo("VPC_LINK"));
    }

    @Test
    void enableSimpleResponsesReturnedViaJson11() {
        String apiId = given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "CreateApi")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"Name":"simple-resp-json11","ProtocolType":"HTTP"}
                        """)
                .when().post("/")
                .then().statusCode(201)
                .extract().path("ApiId");

        String authorizerId = given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "CreateAuthorizer")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {
                          "ApiId":"%s",
                          "AuthorizerType":"REQUEST",
                          "Name":"lambda-authz-json11",
                          "IdentitySource":["$request.header.Authorization"],
                          "AuthorizerUri":"arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/arn:aws:lambda:us-east-1:000000000000:function:authz/invocations",
                          "AuthorizerPayloadFormatVersion":"2.0",
                          "EnableSimpleResponses":true
                        }
                        """.formatted(apiId))
                .when().post("/")
                .then().statusCode(201)
                .body("EnableSimpleResponses", equalTo(true))
                .extract().path("AuthorizerId");

        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "GetAuthorizer")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","AuthorizerId":"%s"}
                        """.formatted(apiId, authorizerId))
                .when().post("/")
                .then().statusCode(200)
                .body("EnableSimpleResponses", equalTo(true));
    }
}
