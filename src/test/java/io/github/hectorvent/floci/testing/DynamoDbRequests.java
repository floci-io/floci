package io.github.hectorvent.floci.testing;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.response.ValidatableResponse;

import java.math.BigDecimal;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class DynamoDbRequests {

    public static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    // JsonPath reads numbers as float, which drops whole seconds at epoch magnitude.
    private static final ObjectMapper EXACT = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private DynamoDbRequests() {}

    public static ValidatableResponse ddb(String action, String body) {
        return given()
                .header("X-Amz-Target", "DynamoDB_20120810." + action)
                .contentType(CONTENT_TYPE)
                .body(body)
                .when().post("/")
                .then();
    }

    public static BigDecimal decimalAt(ValidatableResponse response, String pointer) throws Exception {
        return EXACT.readTree(response.extract().asString()).at(pointer).decimalValue();
    }

    public static void assertEpochMillisBetween(long before, BigDecimal epochSeconds, long after) {
        long millis = epochSeconds.movePointRight(3).longValueExact();
        assertTrue(before <= millis && millis <= after, epochSeconds + " is not between " + before + " and " + after);
    }
}
