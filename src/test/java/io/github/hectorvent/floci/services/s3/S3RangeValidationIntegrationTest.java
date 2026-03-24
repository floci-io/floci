package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3RangeValidationIntegrationTest {

    private static final String BUCKET = "s3-range-validation-test-bucket";
    private static final String KEY = "range-validation.bin";
    private static final String EMPTY_KEY = "empty.bin";
    // "Hello, World!" = 13 bytes (indices 0-12)
    private static final String CONTENT = "Hello, World!";

    @Test
    @Order(1)
    void setup() {
        given().when().put("/" + BUCKET).then().statusCode(200);
        given().body(CONTENT.getBytes()).contentType("application/octet-stream")
            .when().put("/" + BUCKET + "/" + KEY)
            .then().statusCode(200);
        given().body(new byte[0]).contentType("application/octet-stream")
            .when().put("/" + BUCKET + "/" + EMPTY_KEY)
            .then().statusCode(200);
    }

    // --- start >= totalSize ---

    @Test
    @Order(10)
    void startAtTotalSizeReturns416() {
        // start=13 on a 13-byte file (valid indices 0-12)
        given()
            .header("Range", "bytes=13-13")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(416)
            .body(containsString("InvalidRange"));
    }

    @Test
    @Order(11)
    void startBeyondTotalSizeReturns416() {
        given()
            .header("Range", "bytes=100-200")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(416)
            .body(containsString("InvalidRange"));
    }

    @Test
    @Order(12)
    void openEndedStartAtTotalSizeReturns416() {
        given()
            .header("Range", "bytes=13-")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(416)
            .body(containsString("InvalidRange"));
    }

    @Test
    @Order(13)
    void openEndedStartBeyondTotalSizeReturns416() {
        given()
            .header("Range", "bytes=999-")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(416)
            .body(containsString("InvalidRange"));
    }

    // --- start > end ---

    @Test
    @Order(20)
    void startGreaterThanEndReturns416() {
        given()
            .header("Range", "bytes=5-3")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(416)
            .body(containsString("InvalidRange"));
    }

    @Test
    @Order(21)
    void startMuchGreaterThanEndReturns416() {
        given()
            .header("Range", "bytes=10-0")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(416)
            .body(containsString("InvalidRange"));
    }

    // --- suffix range edge cases ---

    @Test
    @Order(30)
    void suffixZeroLengthReturns416() {
        // bytes=-0 means "last 0 bytes" which is nonsensical
        given()
            .header("Range", "bytes=-0")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(416)
            .body(containsString("InvalidRange"));
    }

    @Test
    @Order(31)
    void suffixLargerThanFileReturnsFullContent() {
        // bytes=-999 on a 13-byte file: clamp start to 0, return all
        given()
            .header("Range", "bytes=-999")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(206)
            .body(equalTo(CONTENT))
            .header("Content-Length", String.valueOf(CONTENT.length()));
    }

    // --- non-numeric / malformed values ---

    @ParameterizedTest(name = "float range \"{0}\" returns 416")
    @ValueSource(strings = {
        "bytes=1.5-3.5",
        "bytes=0.0-5.0",
        "bytes=1.5-",
        "bytes=-2.5"
    })
    @Order(40)
    void floatValuesReturn416(String rangeHeader) {
        given()
            .header("Range", rangeHeader)
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(416)
            .body(containsString("InvalidRange"));
    }

    @ParameterizedTest(name = "non-numeric range \"{0}\" returns 416")
    @ValueSource(strings = {
        "bytes=abc-def",
        "bytes=foo-",
        "bytes=-bar",
        "bytes=one-two",
        "bytes=0xDEAD-0xBEEF"
    })
    @Order(41)
    void arbitraryStringsReturn416(String rangeHeader) {
        given()
            .header("Range", rangeHeader)
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(416)
            .body(containsString("InvalidRange"));
    }

    @ParameterizedTest(name = "malformed range \"{0}\" returns 416")
    @ValueSource(strings = {
        "bytes=",
        "bytes=-",
        "bytes=--",
        "bytes=5",
        "bytes=1-2-3",
        "bytes= 0-5",
        "bytes=0 -5",
        "bytes=0- 5"
    })
    @Order(42)
    void malformedRangeSpecReturns416(String rangeHeader) {
        given()
            .header("Range", rangeHeader)
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(416)
            .body(containsString("InvalidRange"));
    }

    // --- negative values in explicit ranges ---

    @Test
    @Order(50)
    void negativeStartInExplicitRangeReturns416() {
        given()
            .header("Range", "bytes=-1-5")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(416)
            .body(containsString("InvalidRange"));
    }

    // --- boundary: valid edge cases that should still succeed ---

    @Test
    @Order(60)
    void lastValidByteReturns206() {
        // byte 12 is the last valid index
        given()
            .header("Range", "bytes=12-12")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(206)
            .body(equalTo("!"))
            .header("Content-Range", "bytes 12-12/13");
    }

    @Test
    @Order(61)
    void firstByteReturns206() {
        given()
            .header("Range", "bytes=0-0")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(206)
            .body(equalTo("H"))
            .header("Content-Range", "bytes 0-0/13");
    }

    @Test
    @Order(62)
    void fullRangeReturns206() {
        given()
            .header("Range", "bytes=0-12")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(206)
            .body(equalTo(CONTENT))
            .header("Content-Range", "bytes 0-12/13");
    }

    @Test
    @Order(63)
    void nonRangeHeaderIgnored() {
        // A Range header that doesn't start with "bytes=" should be ignored
        given()
            .header("Range", "items=0-5")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(200)
            .body(equalTo(CONTENT));
    }

    // --- large long values ---

    @Test
    @Order(70)
    void veryLargeStartReturns416() {
        given()
            .header("Range", "bytes=9999999999999-")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(416)
            .body(containsString("InvalidRange"));
    }

    @Test
    @Order(71)
    void veryLargeEndClampedToFileSize() {
        given()
            .header("Range", "bytes=0-9999999999999")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(206)
            .body(equalTo(CONTENT))
            .header("Content-Range", "bytes 0-12/13");
    }

    // --- Long overflow ---

    @Test
    @Order(72)
    void longOverflowReturns416() {
        // Value exceeds Long.MAX_VALUE → NumberFormatException → 416
        given()
            .header("Range", "bytes=9999999999999999999999-")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(416)
            .body(containsString("InvalidRange"));
    }

    // --- zero-byte file: any range is unsatisfiable ---

    @Test
    @Order(80)
    void emptyFileExplicitRangeReturns416() {
        given()
            .header("Range", "bytes=0-0")
        .when()
            .get("/" + BUCKET + "/" + EMPTY_KEY)
        .then()
            .statusCode(416)
            .body(containsString("InvalidRange"));
    }

    @Test
    @Order(81)
    void emptyFileOpenEndedRangeReturns416() {
        given()
            .header("Range", "bytes=0-")
        .when()
            .get("/" + BUCKET + "/" + EMPTY_KEY)
        .then()
            .statusCode(416)
            .body(containsString("InvalidRange"));
    }

    @Test
    @Order(82)
    void emptyFileSuffixRangeReturns416() {
        given()
            .header("Range", "bytes=-1")
        .when()
            .get("/" + BUCKET + "/" + EMPTY_KEY)
        .then()
            .statusCode(416)
            .body(containsString("InvalidRange"));
    }

    @Test
    @Order(83)
    void emptyFileNoRangeReturns200() {
        given()
        .when()
            .get("/" + BUCKET + "/" + EMPTY_KEY)
        .then()
            .statusCode(200);
    }

    // --- cleanup ---

    @Test
    @Order(100)
    void cleanUp() {
        given().when().delete("/" + BUCKET + "/" + KEY).then().statusCode(204);
        given().when().delete("/" + BUCKET + "/" + EMPTY_KEY).then().statusCode(204);
        given().when().delete("/" + BUCKET).then().statusCode(204);
    }
}
