package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;

/**
 * S3 routes must never surface Quarkus's plain-text 500 page: SDK REST-XML parsers choke on
 * it (issue #1664 — a lazily-instantiated S3Service whose constructor throws turned every S3
 * call into an opaque 500). Unhandled throwables map to AWS's InternalError XML contract.
 */
@QuarkusTest
class S3InternalErrorIntegrationTest {

    @InjectMock
    S3Service s3Service;

    @Test
    void unhandledThrowableRendersInternalErrorXml() {
        when(s3Service.listBuckets()).thenThrow(new RuntimeException("simulated bean failure"));

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
        when(s3Service.listBuckets()).thenThrow(new AwsException("AccessDenied", "Access Denied", 403));

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
