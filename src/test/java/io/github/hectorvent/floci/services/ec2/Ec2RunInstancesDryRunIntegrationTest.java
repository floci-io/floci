package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.emptyOrNullString;

@QuarkusTest
class Ec2RunInstancesDryRunIntegrationTest {
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    @Test
    void dryRunReturnsEc2ErrorWithoutCreatingAnInstanceOrConsumingClientToken() {
        String token = UUID.randomUUID().toString();
        given().header("Authorization", AUTH)
                .formParam("Action", "RunInstances")
                .formParam("ImageId", "ami-0abcdef1234567890")
                .formParam("InstanceType", "t3.micro")
                .formParam("MinCount", "1").formParam("MaxCount", "1")
                .formParam("ClientToken", token).formParam("DryRun", "true")
                .formParam("TagSpecification.1.ResourceType", "instance")
                .formParam("TagSpecification.1.Tag.1.Key", "DryRunTest")
                .formParam("TagSpecification.1.Tag.1.Value", token)
                .post("/").then().statusCode(412)
                .body("Response.Errors.Error.Code", equalTo("DryRunOperation"));

        given().header("Authorization", AUTH).formParam("Action", "DescribeInstances")
                .formParam("Filter.1.Name", "tag:DryRunTest").formParam("Filter.1.Value.1", token)
                .post("/").then().statusCode(200)
                .body("DescribeInstancesResponse.reservationSet", emptyOrNullString());

        String id = given().header("Authorization", AUTH).formParam("Action", "RunInstances")
                .formParam("ImageId", "ami-0abcdef1234567890")
                .formParam("InstanceType", "t3.micro")
                .formParam("ClientToken", token).formParam("DryRun", "false")
                .post("/").then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        given().header("Authorization", AUTH).formParam("Action", "TerminateInstances")
                .formParam("InstanceId.1", id).post("/").then().statusCode(200);
    }
}
