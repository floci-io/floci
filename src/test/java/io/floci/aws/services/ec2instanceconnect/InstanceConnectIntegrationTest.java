package io.floci.aws.services.ec2instanceconnect;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.floci.aws.testing.Ec2Requests.ec2;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class InstanceConnectIntegrationTest {

    private static final String JSON_1_1 = "application/x-amz-json-1.1";
    private static final String ED25519 =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl user@host";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response send(String instanceId, String key) {
        return given()
                .header("X-Amz-Target", "AWSEC2InstanceConnectService.SendSSHPublicKey")
                .contentType(JSON_1_1)
                .body("{\"InstanceId\":\"" + instanceId + "\",\"InstanceOSUser\":\"ec2-user\",\"SSHPublicKey\":\""
                        + key + "\"}")
                .post("/");
    }

    @Test
    void sendsAKeyToARunningInstanceAndRejectsWhatAwsRejects() {
        String instanceId = ec2("RunInstances", "000000000000", "us-east-1")
                .formParam("ImageId", "ami-0abcdef1234567890")
                .formParam("InstanceType", "t3.micro")
                .formParam("MinCount", "1").formParam("MaxCount", "1")
                .post("/").then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        try {
            // Mock mode settles a launch on the first DescribeInstances; the key needs a running instance.
            ec2("DescribeInstances", "000000000000", "us-east-1").formParam("InstanceId.1", instanceId)
                    .post("/").then().statusCode(200)
                    .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.instanceState.name",
                            equalTo("running"));
            send(instanceId, ED25519).then().statusCode(200)
                    .body("Success", equalTo(true))
                    .body("RequestId", not(emptyString()));
            send(instanceId, "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5 " + "c".repeat(80)).then().statusCode(400)
                    .body("__type", equalTo("InvalidArgsException"));
        } finally {
            ec2("TerminateInstances", "000000000000", "us-east-1").formParam("InstanceId.1", instanceId)
                    .post("/").then().statusCode(200);
        }
    }

    @Test
    void unknownInstanceIsAModeledError() {
        send("i-0000000000000000f", ED25519).then().statusCode(400)
                .body("__type", equalTo("EC2InstanceNotFoundException"));
    }
}
