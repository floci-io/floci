package io.github.hectorvent.floci.services.ses;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * The deserializer messages SES v2 answers for a body member of the wrong JSON type, and the
 * request assertion the member-type integration tests share. The messages per JSON type are
 * covered by SesV2RequestShapesTest; the integration tests use these to show an endpoint runs the
 * shape check.
 */
final class SesV2SerializationErrors {

    static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request";
    static final String STRUCTURE = "Start of structure or map found where not expected.";
    static final String LIST = "Start of list found where not expected";
    static final String NUMBER = "NUMBER_VALUE can not be converted to a String";
    static final String EXPECTED_NULL = "Expected null";
    static final String EXPECTED_LIST = "Expected list or null";

    private SesV2SerializationErrors() {
    }

    static void assertError(String method, String path, String body, String type, String message) {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH)
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
}
