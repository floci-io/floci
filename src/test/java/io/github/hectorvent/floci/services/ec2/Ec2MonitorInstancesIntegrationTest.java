package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * MonitorInstances and UnmonitorInstances. Detailed monitoring is a CloudWatch billing
 * switch with no emulated behaviour behind it, but the calls have to be answered and
 * answered honestly: one unsupported action fails a whole deployment, and an action that
 * reports success for an instance that does not exist is worse than one that is missing.
 *
 * @see <a href="https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_MonitorInstances.html">MonitorInstances</a>
 */
@QuarkusTest
class Ec2MonitorInstancesIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";
    private static final String INSTANCE = "RunInstancesResponse.instancesSet.item.";

    private String launchInstance() {
        return given()
                .formParam("Action", "RunInstances")
                .formParam("ImageId", "ami-0abcdef1234567890")
                .formParam("InstanceType", "t3.micro")
                .formParam("MinCount", "1")
                .formParam("MaxCount", "1")
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().path(INSTANCE + "instanceId");
    }

    private String monitoringStateOf(String instanceId) {
        return given()
                .formParam("Action", "DescribeInstances")
                .formParam("InstanceId.1", instanceId)
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("DescribeInstancesResponse.reservationSet.item.instancesSet.item.monitoring.state");
    }

    @Test
    void monitorInstancesReportsMonitoringEnabledForEachInstance() {
        String id = launchInstance();

        given()
            .formParam("Action", "MonitorInstances")
            .formParam("InstanceId.1", id)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("MonitorInstancesResponse.instancesSet.item.instanceId", equalTo(id))
            .body("MonitorInstancesResponse.instancesSet.item.monitoring.state", equalTo("enabled"));
    }

    @Test
    void unmonitorInstancesReportsMonitoringDisabledForEachInstance() {
        String id = launchInstance();

        given()
            .formParam("Action", "UnmonitorInstances")
            .formParam("InstanceId.1", id)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("UnmonitorInstancesResponse.instancesSet.item.instanceId", equalTo(id))
            .body("UnmonitorInstancesResponse.instancesSet.item.monitoring.state", equalTo("disabled"));
    }

    /**
     * The state has to be stored, not just echoed. Instance already carries a monitoring
     * member and DescribeInstances already emits it, so answering "enabled" while every
     * later read still says "disabled" is the accepted-then-never-returned shape.
     */
    @Test
    void monitoringStateSurvivesToDescribeInstances() {
        String id = launchInstance();
        // A fresh instance reports the AWS default before anything asks for monitoring.
        org.junit.jupiter.api.Assertions.assertEquals("disabled", monitoringStateOf(id));

        given().formParam("Action", "MonitorInstances").formParam("InstanceId.1", id)
               .header("Authorization", AUTH_HEADER)
        .when().post("/").then().statusCode(200);

        org.junit.jupiter.api.Assertions.assertEquals("enabled", monitoringStateOf(id));

        given().formParam("Action", "UnmonitorInstances").formParam("InstanceId.1", id)
               .header("Authorization", AUTH_HEADER)
        .when().post("/").then().statusCode(200);

        org.junit.jupiter.api.Assertions.assertEquals("disabled", monitoringStateOf(id));
    }

    /**
     * Echoing an unknown id back with a 200 tells the caller its request took effect on an
     * instance that is not there. Every other instance operation raises this error.
     */
    @Test
    void monitoringAnInstanceThatDoesNotExistIsRejected() {
        given()
            .formParam("Action", "MonitorInstances")
            .formParam("InstanceId.1", "i-1234567890abcdef0")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("InvalidInstanceID.NotFound"));
    }

    @Test
    void unmonitoringAnInstanceThatDoesNotExistIsRejected() {
        given()
            .formParam("Action", "UnmonitorInstances")
            .formParam("InstanceId.1", "i-1234567890abcdef0")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("InvalidInstanceID.NotFound"));
    }
}
