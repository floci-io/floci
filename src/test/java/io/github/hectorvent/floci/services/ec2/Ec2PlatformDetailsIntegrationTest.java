package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class Ec2PlatformDetailsIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    @ParameterizedTest
    @CsvSource({"ami-0abcdef1234567891,Linux/UNIX", "ami-0abcdef1234567893,Windows"})
    void runAndDescribeInstancesReportImagePlatformDetails(String imageId, String platformDetails) {
        String instanceId = given()
            .formParam("Action", "RunInstances")
            .formParam("ImageId", imageId)
            .formParam("InstanceType", "t3.micro")
            .formParam("MinCount", "1")
            .formParam("MaxCount", "1")
            .header("Authorization", AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RunInstancesResponse.instancesSet.item.platformDetails", equalTo(platformDetails))
            .extract().path("RunInstancesResponse.instancesSet.item.instanceId");

        try {
            given()
                .formParam("Action", "DescribeInstances")
                .formParam("InstanceId.1", instanceId)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.platformDetails",
                        equalTo(platformDetails));
        } finally {
            given()
                .formParam("Action", "TerminateInstances")
                .formParam("InstanceId.1", instanceId)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);
        }
    }
}
