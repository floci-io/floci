package io.github.hectorvent.floci.services.ses;

import org.junit.jupiter.params.provider.Arguments;

import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * The deserializer messages SES v2 answers for a body member of the wrong JSON type, and the
 * request assertion the member-type integration tests share. The messages per JSON type are
 * covered by SesV2RequestShapesTest; the integration tests use these to show an endpoint runs the
 * shape check.
 */
final class SesV2SerializationErrors {

    static final String AUTH = authFor("us-east-1");
    static final String STRUCTURE = "Start of structure or map found where not expected.";
    static final String LIST = "Start of list found where not expected";
    static final String NUMBER = "NUMBER_VALUE can not be converted to a String";
    static final String EXPECTED_NULL = "Expected null";
    static final String EXPECTED_LIST = "Expected list or null";
    static final String EXPECTED_MAP = "Expected map or null";
    static final String STRING_FOR_INTEGER = "STRING_VALUE can not be converted to an Integer";

    private SesV2SerializationErrors() {
    }

    /** A body that is not a JSON object, with the message AWS answers it with. */
    static Stream<Arguments> nonObjectBodies() {
        return Stream.of(
                Arguments.of("[1,2,3]", LIST),
                Arguments.of("null", null),
                Arguments.of("\"hello\"", null));
    }

    /** An Authorization header signed for {@code region}, for a test that needs its own region state. */
    static String authFor(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260101/" + region + "/email/aws4_request";
    }

    static void assertError(String method, String path, String body, String type, String message) {
        assertError(AUTH, method, path, body, type, message);
    }

    static void assertError(String auth, String method, String path, String body, String type, String message) {
        given()
            .contentType("application/json")
            .header("Authorization", auth)
            .body(body)
        .when()
            .request(method, path)
        .then()
            .statusCode(400)
            .body("__type", equalTo(type))
            .body("message", equalTo(message));
    }

    static void assertSerializationException(String method, String path, String body, String message) {
        assertError(method, path, body, "SerializationException", message);
    }

    static void assertSerializationException(String auth, String method, String path, String body,
                                             String message) {
        assertError(auth, method, path, body, "SerializationException", message);
    }
}
