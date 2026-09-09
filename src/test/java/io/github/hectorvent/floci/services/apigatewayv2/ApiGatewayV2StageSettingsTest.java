package io.github.hectorvent.floci.services.apigatewayv2;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Stage access logging, throttling defaults and per-route overrides, plus the API's version.
 *
 * <p>These are all write-accepted/read-dropped attributes: Terraform sends them on create and
 * re-proposes them on every subsequent plan when a read does not echo them back.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayV2StageSettingsTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static String apiId;

    @Test
    @Order(1)
    void createApiWithVersion() {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"stage-settings-api\",\"protocolType\":\"HTTP\",\"version\":\"1.0.0\"}")
                .when().post("/v2/apis")
                .then().statusCode(201)
                .body("version", equalTo("1.0.0"))
                .body("apiId", notNullValue())
                .extract().path("apiId");

        given().when().get("/v2/apis/" + apiId)
                .then().statusCode(200)
                .body("version", equalTo("1.0.0"));
    }

    @Test
    @Order(2)
    void updateApiVersion() {
        given().contentType(ContentType.JSON)
                .body("{\"version\":\"2.1.0\"}")
                .when().patch("/v2/apis/" + apiId)
                .then().statusCode(200)
                .body("version", equalTo("2.1.0"));

        given().when().get("/v2/apis/" + apiId)
                .then().statusCode(200)
                .body("version", equalTo("2.1.0"));
    }

    @Test
    @Order(3)
    void createStageWithAccessLogAndRouteSettings() throws Exception {
        String format = mapper.writeValueAsString(mapper.createObjectNode()
                .put("requestId", "$context.requestId")
                .put("status", "$context.status"));

        String body = mapper.writeValueAsString(mapper.createObjectNode()
                .put("stageName", "$default")
                .put("autoDeploy", true)
                .set("accessLogSettings", mapper.createObjectNode()
                        .put("destinationArn", "arn:aws:logs:us-east-1:000000000000:log-group:/aws/apigatewayv2/test")
                        .put("format", format)));

        given().contentType(ContentType.JSON).body(body)
                .when().post("/v2/apis/" + apiId + "/stages")
                .then().statusCode(201)
                .body("accessLogSettings.destinationArn",
                        equalTo("arn:aws:logs:us-east-1:000000000000:log-group:/aws/apigatewayv2/test"))
                .body("accessLogSettings.format", equalTo(format));

        given().when().get("/v2/apis/" + apiId + "/stages/$default")
                .then().statusCode(200)
                .body("accessLogSettings.format", equalTo(format));
    }

    @Test
    @Order(4)
    void updateStageDefaultAndPerRouteSettings() {
        given().contentType(ContentType.JSON)
                .body("""
                        {
                          "defaultRouteSettings": {
                            "throttlingRateLimit": 300,
                            "throttlingBurstLimit": 600,
                            "dataTraceEnabled": false
                          },
                          "routeSettings": {
                            "POST /api/chatbot": {"throttlingRateLimit": 10, "throttlingBurstLimit": 20}
                          }
                        }
                        """)
                .when().patch("/v2/apis/" + apiId + "/stages/$default")
                .then().statusCode(200);

        given().when().get("/v2/apis/" + apiId + "/stages/$default")
                .then().statusCode(200)
                .body("defaultRouteSettings.throttlingRateLimit", equalTo(300.0f))
                .body("defaultRouteSettings.throttlingBurstLimit", equalTo(600))
                .body("defaultRouteSettings.dataTraceEnabled", equalTo(false))
                .body("routeSettings.'POST /api/chatbot'.throttlingRateLimit", equalTo(10.0f))
                .body("routeSettings.'POST /api/chatbot'.throttlingBurstLimit", equalTo(20));
    }

    @Test
    @Order(5)
    void unsetThrottlingFieldsAreOmittedNotZeroed() {
        // AWS omits unset knobs; emitting 0 would make Terraform see a changed value forever.
        given().contentType(ContentType.JSON)
                .body("{\"stageName\":\"sparse\",\"defaultRouteSettings\":{\"throttlingRateLimit\":50}}")
                .when().post("/v2/apis/" + apiId + "/stages")
                .then().statusCode(201)
                .body("defaultRouteSettings.throttlingRateLimit", equalTo(50.0f))
                .body("defaultRouteSettings.throttlingBurstLimit", org.hamcrest.Matchers.nullValue())
                .body("defaultRouteSettings.dataTraceEnabled", org.hamcrest.Matchers.nullValue());
    }
}
