package io.github.hectorvent.floci.services.glue;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class GlueDeleteJobIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String ROLE = "arn:aws:iam::000000000000:role/my-role";
    private static final String JOB_NAME = "delete-job-" + UUID.randomUUID().toString().substring(0, 8);

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void repeatedDeleteJobRequestsReturnSuccessfulResponses() {
        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.CreateJob")
                .body("""
                        {
                          "Name": "%s",
                          "Role": "%s",
                          "Command": {}
                        }
                        """.formatted(JOB_NAME, ROLE))
        .when().post("/")
        .then().statusCode(200);

        for (int attempt = 0; attempt < 2; attempt++) {
            given().contentType(CONTENT_TYPE)
                    .header("X-Amz-Target", "AWSGlue.DeleteJob")
                    .body("{ \"JobName\": \"" + JOB_NAME + "\" }")
            .when().post("/")
            .then()
                    .statusCode(200)
                    .body("JobName", equalTo(JOB_NAME));
        }
    }
}