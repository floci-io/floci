package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import static io.restassured.RestAssured.given;

/**
 * Conditional GET and HEAD compare Last-Modified at second precision. Objects are stamped to the
 * millisecond but Last-Modified goes out to the second, so a client revalidating with the value it
 * was given sends a date up to 999 ms before the stored one. The stored object is stubbed with a
 * fixed timestamp, so the sub-second case is exercised on every run rather than whenever a write
 * happens to land off a whole second.
 *
 * <p>The stub is a default answer rather than {@code when(...)} stubbing because background writers
 * share the mocked S3Service (see {@link S3InternalErrorIntegrationTest}).
 */
@QuarkusTest
class S3ConditionalGetPrecisionIntegrationTest {
    private static final String BUCKET = "conditional-get-precision";
    private static final byte[] BODY = "body".getBytes(StandardCharsets.UTF_8);
    // IMF-fixdate, two-digit day, as Last-Modified is sent.
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneOffset.UTC);
    private static final Instant LAST_MODIFIED_SECOND = Instant.parse("2026-01-01T00:00:05Z");

    @BeforeEach
    void stubObjects() {
        S3Service stub = Mockito.mock(S3Service.class, invocation -> {
            String method = invocation.getMethod().getName();
            if ("headObject".equals(method)) {
                return storedObject(invocation.getArgument(1));
            }
            if ("openObject".equals(method)) {
                return new S3Service.ObjectRead(storedObject(invocation.getArgument(1)),
                        new ByteArrayInputStream(BODY));
            }
            return Mockito.RETURNS_DEFAULTS.answer(invocation);
        });
        QuarkusMock.installMockForType(stub, S3Service.class);
    }

    /** {@code fraction.txt} is stamped 456 ms past the second, {@code whole.txt} on it. */
    private static S3Object storedObject(String key) {
        S3Object object = new S3Object(BUCKET, key, BODY, "text/plain");
        long millis = "fraction.txt".equals(key) ? 456 : 0;
        object.setLastModified(LAST_MODIFIED_SECOND.plusMillis(millis));
        return object;
    }

    @ParameterizedTest(name = "{0} {1} {2} at Last-Modified {3}s -> {4}")
    @CsvSource({
        "GET,  fraction.txt, If-Modified-Since,   -1, 200",
        "GET,  fraction.txt, If-Modified-Since,    0, 304",
        "GET,  fraction.txt, If-Modified-Since,    1, 304",
        "GET,  fraction.txt, If-Unmodified-Since, -1, 412",
        "GET,  fraction.txt, If-Unmodified-Since,  0, 200",
        "GET,  fraction.txt, If-Unmodified-Since,  1, 200",
        "HEAD, fraction.txt, If-Modified-Since,   -1, 200",
        "HEAD, fraction.txt, If-Modified-Since,    0, 304",
        "HEAD, fraction.txt, If-Modified-Since,    1, 304",
        "HEAD, fraction.txt, If-Unmodified-Since, -1, 412",
        "HEAD, fraction.txt, If-Unmodified-Since,  0, 200",
        "HEAD, fraction.txt, If-Unmodified-Since,  1, 200",
        "GET,  whole.txt,    If-Modified-Since,   -1, 200",
        "GET,  whole.txt,    If-Modified-Since,    0, 304",
        "GET,  whole.txt,    If-Modified-Since,    1, 304",
        "GET,  whole.txt,    If-Unmodified-Since, -1, 412",
        "GET,  whole.txt,    If-Unmodified-Since,  0, 200",
        "GET,  whole.txt,    If-Unmodified-Since,  1, 200",
        "HEAD, whole.txt,    If-Modified-Since,   -1, 200",
        "HEAD, whole.txt,    If-Modified-Since,    0, 304",
        "HEAD, whole.txt,    If-Modified-Since,    1, 304",
        "HEAD, whole.txt,    If-Unmodified-Since, -1, 412",
        "HEAD, whole.txt,    If-Unmodified-Since,  0, 200",
        "HEAD, whole.txt,    If-Unmodified-Since,  1, 200",
    })
    void dateConditionComparesToTheSecond(String method, String key, String header,
                                          long offsetSeconds, int status) {
        String date = HTTP_DATE.format(LAST_MODIFIED_SECOND.plusSeconds(offsetSeconds));
        RequestSpecification request = given().header(header, date);
        String path = "/" + BUCKET + "/" + key;
        ("HEAD".equals(method) ? request.when().head(path) : request.when().get(path))
            .then().statusCode(status);
    }
}
