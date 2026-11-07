package io.floci.aws.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Usage plan metering on the execute-api data plane, measured on a REST API in eu-west-1: a call made
 * with a key of the stage's usage plan counts toward the key's quota on every method, even when it then
 * fails request validation, a refused call does not, and the plan's quota and throttle answer 429 with
 * the AWS default gateway responses.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayUsageMeteringIntegrationTest {

    private static String apiId;
    private static String quotaPlanId;
    private static String quotaKeyId;
    private static String quotaKeyValue;
    private static String throttlePlanId;
    private static String throttleKeyId;
    private static String throttleKeyValue;
    private static String validatedPlanId;
    private static String validatedKeyId;
    private static String validatedKeyValue;

    @Inject
    Clock clock;

    @Test @Order(1)
    void setupApiPlansAndKeys() {
        apiId = given().contentType(ContentType.JSON)
                .body("{\"name\":\"usage-metering-api\"}")
                .when().post("/restapis")
                .then().statusCode(201)
                .extract().path("id");
        String rootId = given().when().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200)
                .extract().path("item[0].id");
        mockMethod(rootId, "secret", "\"apiKeyRequired\":true");
        mockMethod(rootId, "open", "\"apiKeyRequired\":false");
        String validatorId = given().contentType(ContentType.JSON)
                .body("{\"name\":\"params\",\"validateRequestParameters\":true}")
                .when().post("/restapis/" + apiId + "/requestvalidators")
                .then().statusCode(201)
                .extract().path("id");
        mockMethod(rootId, "checked", "\"requestValidatorId\":\"" + validatorId
                + "\",\"requestParameters\":{\"method.request.querystring.q\":true}");

        String deploymentId = given().contentType(ContentType.JSON)
                .body("{}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201)
                .extract().path("id");
        for (String stage : List.of("prod", "throttled", "validated")) {
            given().contentType(ContentType.JSON)
                    .body("{\"stageName\":\"" + stage + "\",\"deploymentId\":\"" + deploymentId + "\"}")
                    .when().post("/restapis/" + apiId + "/stages")
                    .then().statusCode(201);
        }

        quotaPlanId = plan("prod", "\"quota\":{\"limit\":2,\"period\":\"DAY\"}");
        quotaKeyId = keyOn(quotaPlanId);
        quotaKeyValue = keyValue(quotaKeyId);
        throttlePlanId = plan("throttled", "\"throttle\":{\"burstLimit\":1,\"rateLimit\":0}");
        throttleKeyId = keyOn(throttlePlanId);
        throttleKeyValue = keyValue(throttleKeyId);
        validatedPlanId = plan("validated", "\"quota\":{\"limit\":1,\"period\":\"DAY\"}");
        validatedKeyId = keyOn(validatedPlanId);
        validatedKeyValue = keyValue(validatedKeyId);
    }

    // Measured: a key of the stage's plan is metered on a method that does not require one, too.
    @Test @Order(2)
    void quotaCountsEveryMethodAndRefusesTheCallOverTheLimit() {
        call("prod", "secret", quotaKeyValue).then().statusCode(200);
        assertUsage(quotaPlanId, quotaKeyId, 1, 1);
        call("prod", "open", quotaKeyValue).then().statusCode(200);
        assertUsage(quotaPlanId, quotaKeyId, 2, 0);

        call("prod", "secret", quotaKeyValue).then().statusCode(429)
                .header("x-amzn-ErrorType", "LimitExceededException")
                .body("message", equalTo("Limit Exceeded"));
        assertUsage(quotaPlanId, quotaKeyId, 2, 0);
    }

    @Test @Order(3)
    void callWithoutAKeyOfThePlanIsNotMetered() {
        call("prod", "open", null).then().statusCode(200);
    }

    @Test @Order(4)
    void throttleRefusesTheCallOverTheBurst() {
        call("throttled", "open", throttleKeyValue).then().statusCode(200);
        call("throttled", "open", throttleKeyValue).then().statusCode(429)
                .header("x-amzn-ErrorType", "TooManyRequestsException")
                .body("message", equalTo("Too Many Requests"));
        // Measured: a plan without a quota reports [0, 0].
        assertUsage(throttlePlanId, throttleKeyId, 0, 0);
    }

    // Measured: the key is metered before request validation, so a call that fails it counts and an
    // exhausted key is refused with 429 rather than 400.
    @Test @Order(5)
    void callThatFailsValidationCountsAndAnExhaustedKeyIsRefusedBeforeValidation() {
        call("validated", "checked", validatedKeyValue).then().statusCode(400);
        assertUsage(validatedPlanId, validatedKeyId, 1, 0);

        call("validated", "checked", validatedKeyValue).then().statusCode(429)
                .body("message", equalTo("Limit Exceeded"));
        assertUsage(validatedPlanId, validatedKeyId, 1, 0);
    }

    // The refusals are gateway responses, so the API's own QUOTA_EXCEEDED, or its DEFAULT_4XX for a
    // type it did not customise, shapes the 429.
    @Test @Order(6)
    void refusalsAnswerWithTheApisCustomisedGatewayResponses() {
        customiseGatewayResponse("QUOTA_EXCEEDED", "quota spent");
        customiseGatewayResponse("DEFAULT_4XX", "slow down");

        call("prod", "secret", quotaKeyValue).then().statusCode(429)
                .header("Access-Control-Allow-Origin", "*")
                .body("message", equalTo("quota spent"));
        call("throttled", "open", throttleKeyValue).then().statusCode(429)
                .header("Access-Control-Allow-Origin", "*")
                .body("message", equalTo("slow down"));
    }

    private void customiseGatewayResponse(String type, String message) {
        given().contentType(ContentType.JSON)
                .body("{\"responseParameters\":{\"gatewayresponse.header.Access-Control-Allow-Origin\":\"'*'\"},"
                        + "\"responseTemplates\":{\"application/json\":\"{\\\"message\\\":\\\"" + message + "\\\"}\"}}")
                .when().put("/restapis/" + apiId + "/gatewayresponses/" + type)
                .then().statusCode(201);
    }

    private void mockMethod(String parentId, String pathPart, String methodFields) {
        String resourceId = given().contentType(ContentType.JSON)
                .body("{\"pathPart\":\"" + pathPart + "\"}")
                .when().post("/restapis/" + apiId + "/resources/" + parentId)
                .then().statusCode(201)
                .extract().path("id");
        String method = "/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET";
        given().contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"," + methodFields + "}")
                .when().put(method)
                .then().statusCode(201);
        given().contentType(ContentType.JSON)
                .body("{}")
                .when().put(method + "/responses/200")
                .then().statusCode(201);
        given().contentType(ContentType.JSON)
                .body("{\"type\":\"MOCK\",\"requestTemplates\":{\"application/json\":\"{\\\"statusCode\\\": 200}\"}}")
                .when().put(method + "/integration")
                .then().statusCode(201);
        given().contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"\",\"responseTemplates\":{\"application/json\":\"{}\"}}")
                .when().put(method + "/integration/responses/200")
                .then().statusCode(201);
    }

    private String plan(String stage, String limits) {
        return given().contentType(ContentType.JSON)
                .body("{\"name\":\"metering-" + stage + "\",\"apiStages\":[{\"apiId\":\"" + apiId
                        + "\",\"stage\":\"" + stage + "\"}]," + limits + "}")
                .when().post("/usageplans")
                .then().statusCode(201)
                .extract().path("id");
    }

    private static String keyOn(String planId) {
        String keyId = given().contentType(ContentType.JSON)
                .body("{\"name\":\"metering-key-" + planId + "\",\"enabled\":true}")
                .when().post("/apikeys")
                .then().statusCode(201)
                .extract().path("id");
        given().contentType(ContentType.JSON)
                .body("{\"keyId\":\"" + keyId + "\",\"keyType\":\"API_KEY\"}")
                .when().post("/usageplans/" + planId + "/keys")
                .then().statusCode(201);
        return keyId;
    }

    private static String keyValue(String keyId) {
        return given().when().get("/apikeys/" + keyId + "?includeValue=true")
                .then().statusCode(200)
                .extract().path("value");
    }

    private static Response call(String stage, String path, String keyValue) {
        RequestSpecification request = given();
        if (keyValue != null) {
            request.header("x-api-key", keyValue);
        }
        return request.when().get("/execute-api/" + apiId + "/" + stage + "/" + path);
    }

    // API key ids can start with a digit, which a dotted GPath parses as a number.
    private void assertUsage(String planId, String keyId, int used, int remaining) {
        String today = LocalDate.now(clock).toString();
        given().when().get("/usageplans/" + planId + "/usage?startDate=" + today + "&endDate=" + today)
                .then().statusCode(200)
                .body("values['" + keyId + "'][0][0]", equalTo(used))
                .body("values['" + keyId + "'][0][1]", equalTo(remaining));
    }
}
