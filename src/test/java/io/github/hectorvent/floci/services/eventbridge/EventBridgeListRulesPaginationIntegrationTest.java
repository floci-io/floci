package io.github.hectorvent.floci.services.eventbridge;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class EventBridgeListRulesPaginationIntegrationTest {

    private static final String CT = "application/x-amz-json-1.1";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static void call(String action, String body) {
        given().contentType(CT).header("X-Amz-Target", "AWSEvents." + action)
                .body(body)
                .when().post("/")
                .then().statusCode(200);
    }

    private static void putRule(String name, String bus) {
        String busField = bus == null ? "" : ",\"EventBusName\":\"" + bus + "\"";
        call("PutRule", "{\"Name\":\"" + name + "\",\"EventPattern\":\"{\\\"source\\\":[\\\"paging\\\"]}\""
                + busField + "}");
    }

    private static JsonPath listRules(String body) {
        return given().contentType(CT).header("X-Amz-Target", "AWSEvents.ListRules")
                .body(body)
                .when().post("/")
                .then().statusCode(200)
                .extract().jsonPath();
    }

    @Test
    void limitOnePagesThroughEveryRuleExactlyOnce() {
        putRule("lr-page-c", null);
        putRule("lr-page-a", null);
        putRule("lr-page-b", null);
        putRule("lr-other", null);

        List<String> seen = new ArrayList<>();
        String token = null;
        do {
            String tokenField = token == null ? "" : ",\"NextToken\":\"" + token + "\"";
            JsonPath page = listRules("{\"NamePrefix\":\"lr-page-\",\"Limit\":1" + tokenField + "}");
            List<String> names = page.getList("Rules.Name");
            assertEquals(1, names.size());
            seen.add(names.get(0));
            token = page.getString("NextToken");
        } while (token != null);

        assertEquals(List.of("lr-page-a", "lr-page-b", "lr-page-c"), seen);
    }

    @Test
    void customBusAndPrefixPageTogether() {
        call("CreateEventBus", "{\"Name\":\"lr-paging-bus\"}");
        putRule("lr-bus-1", "lr-paging-bus");
        putRule("lr-bus-2", "lr-paging-bus");
        putRule("lr-bus-skip", "lr-paging-bus");
        putRule("lr-bus-3", null);

        String token = given().contentType(CT).header("X-Amz-Target", "AWSEvents.ListRules")
                .body("{\"EventBusName\":\"lr-paging-bus\",\"NamePrefix\":\"lr-bus-\",\"Limit\":2}")
                .when().post("/")
                .then().statusCode(200)
                .body("Rules", hasSize(2))
                .body("Rules[0].Name", equalTo("lr-bus-1"))
                .body("Rules[1].Name", equalTo("lr-bus-2"))
                .body("NextToken", notNullValue())
                .extract().jsonPath().getString("NextToken");

        given().contentType(CT).header("X-Amz-Target", "AWSEvents.ListRules")
                .body("{\"EventBusName\":\"lr-paging-bus\",\"NamePrefix\":\"lr-bus-\",\"Limit\":2,"
                        + "\"NextToken\":\"" + token + "\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("Rules", hasSize(1))
                .body("Rules[0].Name", equalTo("lr-bus-skip"))
                .body("NextToken", nullValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "101", "4294967297", "-4294967297"})
    void outOfRangeLimitIsAValidationException(String limit) {
        given().contentType(CT).header("X-Amz-Target", "AWSEvents.ListRules")
                .body("{\"Limit\":" + limit + "}")
                .when().post("/")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", containsString("Value '" + limit + "' at 'limit' failed to satisfy constraint"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"100.9", "true", "\"5\""})
    void nonIntegerLimitIsASerializationException(String limit) {
        given().contentType(CT).header("X-Amz-Target", "AWSEvents.ListRules")
                .body("{\"Limit\":" + limit + "}")
                .when().post("/")
                .then().statusCode(400)
                .body("__type", equalTo("SerializationException"))
                .body("message", containsString("can not be converted to Integer"));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100})
    void boundaryLimitsAreAccepted(int limit) {
        putRule("lr-bound-a", null);
        putRule("lr-bound-b", null);

        given().contentType(CT).header("X-Amz-Target", "AWSEvents.ListRules")
                .body("{\"NamePrefix\":\"lr-bound-\",\"Limit\":" + limit + "}")
                .when().post("/")
                .then().statusCode(200)
                .body("Rules", hasSize(Math.min(limit, 2)));
    }

    @Test
    void unknownNextTokenIsInvalidToken() {
        given().contentType(CT).header("X-Amz-Target", "AWSEvents.ListRules")
                .body("{\"Limit\":1,\"NextToken\":\"not-a-token!\"}")
                .when().post("/")
                .then().statusCode(400)
                .body("__type", equalTo("InvalidToken"))
                .body("message", equalTo("Invalid NextToken."));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2049})
    void outOfBoundsTokenLengthsFailValidation(int length) {
        given().contentType(CT).header("X-Amz-Target", "AWSEvents.ListRules")
                .body("{\"NextToken\":\"" + "a".repeat(length) + "\"}")
                .when().post("/")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("NextToken must contain 1 to 2048 characters."));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2048})
    void inBoundsMalformedTokensReturnInvalidToken(int length) {
        given().contentType(CT).header("X-Amz-Target", "AWSEvents.ListRules")
                .body("{\"NextToken\":\"" + "a".repeat(length) + "\"}")
                .when().post("/")
                .then().statusCode(400)
                .body("__type", equalTo("InvalidToken"))
                .body("message", equalTo("Invalid NextToken."));
    }
}
