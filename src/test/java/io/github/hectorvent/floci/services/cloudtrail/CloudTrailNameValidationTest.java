package io.github.hectorvent.floci.services.cloudtrail;

import io.github.hectorvent.floci.core.common.AwsException;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;

@QuarkusTest
class CloudTrailNameValidationTest {

    private static final String CT_TARGET = "CloudTrail_20131101.";
    private static final String JSON11 = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void tooShortName_rejectsWithInvalidTrailNameException() {
        given()
            .header("X-Amz-Target", CT_TARGET + "CreateTrail")
            .contentType(JSON11)
            .body("{\"Name\":\"ab\",\"S3BucketName\":\"some-bucket\"}")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("InvalidTrailNameException"));
    }

    @Test
    void singleCharName_rejectsWithInvalidTrailNameException() {
        given()
            .header("X-Amz-Target", CT_TARGET + "CreateTrail")
            .contentType(JSON11)
            .body("{\"Name\":\"a\",\"S3BucketName\":\"some-bucket\"}")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("InvalidTrailNameException"));
    }

    @Test
    void nameStartingWithHyphen_rejectsWithInvalidTrailNameException() {
        given()
            .header("X-Amz-Target", CT_TARGET + "CreateTrail")
            .contentType(JSON11)
            .body("{\"Name\":\"-trail\",\"S3BucketName\":\"some-bucket\"}")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("InvalidTrailNameException"));
    }

    @Test
    void nameEndingWithHyphen_rejectsWithInvalidTrailNameException() {
        given()
            .header("X-Amz-Target", CT_TARGET + "CreateTrail")
            .contentType(JSON11)
            .body("{\"Name\":\"trail-\",\"S3BucketName\":\"some-bucket\"}")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("InvalidTrailNameException"));
    }

    @Test
    void nameWithInvalidChar_rejectsWithInvalidTrailNameException() {
        given()
            .header("X-Amz-Target", CT_TARGET + "CreateTrail")
            .contentType(JSON11)
            .body("{\"Name\":\"my trail!\",\"S3BucketName\":\"some-bucket\"}")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("InvalidTrailNameException"));
    }

    @Test
    void validName_isAccepted() {
        given()
            .header("X-Amz-Target", CT_TARGET + "CreateTrail")
            .contentType(JSON11)
            .body("{\"Name\":\"my-trail_1.0\",\"S3BucketName\":\"some-bucket\"}")
        .when().post("/")
        .then()
            .statusCode(200);
    }
}
