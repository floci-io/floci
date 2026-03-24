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
class S3RangeRequestIntegrationTest {

    private static final String BUCKET = "s3-range-request-test-bucket";
    private static final String KEY = "range-test.bin";
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

    // --- valid range requests ---

    @Test
    @Order(10)
    void getFullObjectWithoutRangeHeader() {
        given()
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(200)
            .body(equalTo(CONTENT))
            .header("Content-Length", String.valueOf(CONTENT.length()));
    }

    @Test
    @Order(11)
    void getRangeFromStart() {
        // bytes=0-4 → "Hello"
        given()
            .header("Range", "bytes=0-4")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(206)
            .body(equalTo("Hello"))
            .header("Content-Length", "5")
            .header("Content-Range", "bytes 0-4/13");
    }

    @Test
    @Order(12)
    void getRangeFromMiddle() {
        // bytes=7-11 → "World"
        given()
            .header("Range", "bytes=7-11")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(206)
            .body(equalTo("World"))
            .header("Content-Length", "5")
            .header("Content-Range", "bytes 7-11/13");
    }

    @Test
    @Order(13)
    void getSuffixRange() {
        // bytes=-6 → "World!" (last 6 bytes)
        given()
            .header("Range", "bytes=-6")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(206)
            .body(equalTo("World!"))
            .header("Content-Length", "6");
    }

    @Test
    @Order(14)
    void getOpenEndedRange() {
        // bytes=7- → "World!"
        given()
            .header("Range", "bytes=7-")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(206)
            .body(equalTo("World!"))
            .header("Content-Length", "6")
            .header("Content-Range", "bytes 7-12/13");
    }

    @Test
    @Order(15)
    void getFirstByte() {
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
    @Order(16)
    void getLastByte() {
        // bytes=-1 → "!" (last byte)
        given()
            .header("Range", "bytes=-1")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(206)
            .body(equalTo("!"))
            .header("Content-Length", "1");
    }

    @Test
    @Order(17)
    void getLastValidByteExplicit() {
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
    @Order(18)
    void getFullRange() {
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
    @Order(19)
    void headObjectReturnsAcceptRanges() {
        given()
        .when()
            .head("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(200)
            .header("Accept-Ranges", "bytes");
    }

    // --- clamping: end beyond file size ---

    @Test
    @Order(20)
    void endBeyondFileSizeIsClamped() {
        // bytes=7-999 → clamp to end of file
        given()
            .header("Range", "bytes=7-999")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(206)
            .body(equalTo("World!"))
            .header("Content-Length", "6")
            .header("Content-Range", "bytes 7-12/13");
    }

    @Test
    @Order(21)
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

    @Test
    @Order(22)
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

    // --- non-bytes range unit is ignored ---

    @Test
    @Order(23)
    void nonBytesRangeUnitIgnored() {
        given()
            .header("Range", "items=0-5")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(200)
            .body(equalTo(CONTENT));
    }

    // --- start >= totalSize ---

    @Test
    @Order(30)
    void startAtTotalSizeReturns416() {
        given()
            .header("Range", "bytes=13-13")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(416)
            .body(containsString("InvalidRange"));
    }

    @Test
    @Order(31)
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
    @Order(32)
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
    @Order(33)
    void openEndedStartBeyondTotalSizeReturns416() {
        given()
            .header("Range", "bytes=999-")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(416)
            .body(containsString("InvalidRange"));
    }

    @Test
    @Order(34)
    void veryLargeStartReturns416() {
        given()
            .header("Range", "bytes=9999999999999-")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(416)
            .body(containsString("InvalidRange"));
    }

    // --- start > end ---

    @Test
    @Order(40)
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
    @Order(41)
    void startMuchGreaterThanEndReturns416() {
        given()
            .header("Range", "bytes=10-0")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(416)
            .body(containsString("InvalidRange"));
    }

    // --- suffix edge cases ---

    @Test
    @Order(50)
    void suffixZeroLengthReturns416() {
        given()
            .header("Range", "bytes=-0")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(416)
            .body(containsString("InvalidRange"));
    }

    // --- non-numeric / malformed values ---

    @ParameterizedTest(name = "float range \"{0}\" returns 416")
    @ValueSource(strings = {
        "bytes=1.5-3.5",
        "bytes=0.0-5.0",
        "bytes=1.5-",
        "bytes=-2.5"
    })
    @Order(60)
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
    @Order(61)
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
    @Order(62)
    void malformedRangeSpecReturns416(String rangeHeader) {
        given()
            .header("Range", rangeHeader)
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(416)
            .body(containsString("InvalidRange"));
    }

    @Test
    @Order(63)
    void negativeStartInExplicitRangeReturns416() {
        given()
            .header("Range", "bytes=-1-5")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(416)
            .body(containsString("InvalidRange"));
    }

    @Test
    @Order(64)
    void longOverflowReturns416() {
        given()
            .header("Range", "bytes=9999999999999999999999-")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(416)
            .body(containsString("InvalidRange"));
    }

    // --- zero-byte file ---

    @Test
    @Order(70)
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
    @Order(71)
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
    @Order(72)
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
    @Order(73)
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
