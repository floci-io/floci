package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class CloudFormationHijackTest {
    @Test
    void testCloudFormationHijack() {
        // Mock a CloudFormation request sent with Host: cloudformation.us-west-1.amazonaws.com
        given()
            .header("Host", "cloudformation.us-west-1.amazonaws.com")
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("Version", "2010-05-15")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("DescribeStacksResponse")); // Should be routed to CloudFormation, not S3
    }
}
