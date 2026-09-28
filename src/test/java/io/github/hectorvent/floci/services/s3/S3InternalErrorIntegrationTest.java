package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * S3 routes must never surface Quarkus's plain-text 500 page: SDK REST-XML parsers choke on
 * it (issue #1664 — a lazily-instantiated S3Service whose constructor throws turned every S3
 * call into an opaque 500). Unhandled throwables map to AWS's InternalError XML contract.
 *
 * <p>The failure is baked into the mock's default answer instead of stubbed afterwards with
 * {@code when(...).thenThrow(...)}. The mocked S3Service is shared with background writers
 * (CloudTrail delivery, flow logs, Firehose, ...) that may call it mid-stubbing; Mockito then
 * attaches the answer to their invocation and {@code listBuckets()} keeps returning an empty
 * list, rendering a 200 instead of the expected error (issue #3833).
 */
@QuarkusTest
class S3InternalErrorIntegrationTest {

    private static void failListBucketsWith(RuntimeException failure) {
        S3Service failing = Mockito.mock(S3Service.class, invocation -> {
            if ("listBuckets".equals(invocation.getMethod().getName())) {
                throw failure;
            }
            return Mockito.RETURNS_DEFAULTS.answer(invocation);
        });
        QuarkusMock.installMockForType(failing, S3Service.class);
    }

    @Test
    void unhandledThrowableRendersInternalErrorXml() {
        failListBucketsWith(new RuntimeException("simulated bean failure"));

        given()
        .when()
            .get("/")
        .then()
            .statusCode(500)
            .contentType(containsString("application/xml"))
            .body("Error.Code", equalTo("InternalError"))
            .body("Error.Message", equalTo("We encountered an internal error. Please try again."))
            .body("Error.RequestId", not(emptyOrNullString()));
    }

    @Test
    void awsExceptionStillRendersItsOwnXmlError() {
        failListBucketsWith(new AwsException("AccessDenied", "Access Denied", 403));

        given()
        .when()
            .get("/")
        .then()
            .statusCode(403)
            .contentType(containsString("application/xml"))
            .body("Error.Code", equalTo("AccessDenied"))
            .body("Error.Message", equalTo("Access Denied"));
    }
}
