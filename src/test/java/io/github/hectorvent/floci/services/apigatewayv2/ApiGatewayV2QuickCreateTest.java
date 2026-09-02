package io.github.hectorvent.floci.services.apigatewayv2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * CreateApi's {@code target} parameter ("quick create") must provision an integration
 * (AWS_PROXY for a Lambda ARN, HTTP_PROXY for an HTTP URL), a "$default" catch-all route,
 * and an auto-deploy "$default" stage, exactly what AWS does, so the API is immediately
 * invocable. Without a "$default" stage, {@code ApiGatewayExecuteApiHostFilter} can't
 * resolve one and every invocation silently falls through to whichever other
 * virtual-hosted-style filter (e.g. S3) claims the request next, instead of reaching the
 * API. See issue #1902.
 */
@QuarkusTest
class ApiGatewayV2QuickCreateTest {

    private static final String LAMBDA_TARGET =
            "arn:aws:lambda:us-east-1:000000000000:function:quick-create-target";
    private static final String HTTP_TARGET = "https://example.com/webhook";

    @Test
    void quickCreateWithLambdaTargetProvisionsAwsProxyIntegration() {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"quick-create-lambda-api","protocolType":"HTTP","target":"%s"}
                        """.formatted(LAMBDA_TARGET))
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .body("apiId", notNullValue())
                .extract().path("apiId");

        given()
                .when().get("/v2/apis/" + apiId + "/integrations")
                .then()
                .statusCode(200)
                .body("items", hasSize(1))
                .body("items[0].integrationType", equalTo("AWS_PROXY"))
                .body("items[0].integrationUri", equalTo(LAMBDA_TARGET));

        given()
                .when().get("/v2/apis/" + apiId + "/routes")
                .then()
                .statusCode(200)
                .body("items", hasSize(1))
                .body("items[0].routeKey", equalTo("$default"))
                .body("items[0].target", startsWith("integrations/"));

        given()
                .when().get("/v2/apis/" + apiId + "/stages/$default")
                .then()
                .statusCode(200)
                .body("stageName", equalTo("$default"))
                .body("autoDeploy", equalTo(true));
    }

    @Test
    void quickCreateWithHttpUrlTargetProvisionsHttpProxyIntegration() {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"quick-create-http-api","protocolType":"HTTP","target":"%s"}
                        """.formatted(HTTP_TARGET))
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .body("apiId", notNullValue())
                .extract().path("apiId");

        given()
                .when().get("/v2/apis/" + apiId + "/integrations")
                .then()
                .statusCode(200)
                .body("items", hasSize(1))
                .body("items[0].integrationType", equalTo("HTTP_PROXY"))
                .body("items[0].integrationUri", equalTo(HTTP_TARGET))
                .body("items[0].integrationMethod", equalTo("ANY"));

        given()
                .when().get("/v2/apis/" + apiId + "/stages/$default")
                .then()
                .statusCode(200)
                .body("autoDeploy", equalTo(true));
    }

    @Test
    void createApiWithoutTargetProvisionsNothing() {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"no-target-api","protocolType":"HTTP"}
                        """)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .extract().path("apiId");

        given()
                .when().get("/v2/apis/" + apiId + "/integrations")
                .then()
                .statusCode(200)
                .body("items", hasSize(0));

        given()
                .when().get("/v2/apis/" + apiId + "/routes")
                .then()
                .statusCode(200)
                .body("items", hasSize(0));

        given()
                .when().get("/v2/apis/" + apiId + "/stages/$default")
                .then()
                .statusCode(404);
    }
}
