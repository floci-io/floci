package io.floci.aws.services.autoscaling;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class AutoScalingCapacitySuspensionIntegrationTest {
    private static final String GROUP_PATH = "DescribeAutoScalingGroupsResponse."
            + "DescribeAutoScalingGroupsResult.AutoScalingGroups.member";

    @Inject
    AutoScalingReconciler reconciler;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void suspendAndResumePreserveDesiredCapacityAndControlEc2Instances(boolean allProcesses) {
        String name = "capacity-pause-" + UUID.randomUUID().toString().substring(0, 8);
        request("CreateLaunchConfiguration")
                .formParam("LaunchConfigurationName", name)
                .formParam("ImageId", "ami-amazonlinux2023").formParam("InstanceType", "t3.micro")
                .post("/").then().statusCode(200);
        try {
            request("CreateAutoScalingGroup").formParam("AutoScalingGroupName", name)
                    .formParam("LaunchConfigurationName", name)
                    .formParam("MinSize", 0).formParam("MaxSize", 1).formParam("DesiredCapacity", 0)
                    .formParam("AvailabilityZones.member.1", "us-east-1a")
                    .post("/").then().statusCode(200);
            try {
                process("SuspendProcesses", name, allProcesses, "Launch");
                setDesired(name, 1);
                assertPaused(name, 1, 0);
                process("ResumeProcesses", name, allProcesses, "Launch");
                reconciler.reconcileAll();
                reconciler.reconcileAll();
                assertCapacity(name, 1, 1);
                String instanceId = describe(name).xmlPath().getString(GROUP_PATH + ".Instances.member.InstanceId");

                process("SuspendProcesses", name, allProcesses, "Terminate");
                setDesired(name, 0);
                assertPaused(name, 0, 1);
                assertEquals("running", instanceState(instanceId));
                process("ResumeProcesses", name, allProcesses, "Terminate");
                reconciler.reconcileAll();
                reconciler.reconcileAll();
                assertCapacity(name, 0, 0);
                assertEquals("terminated", instanceState(instanceId));
            } finally {
                request("DeleteAutoScalingGroup").formParam("AutoScalingGroupName", name)
                        .formParam("ForceDelete", true).post("/").then().statusCode(200);
            }
        } finally {
            request("DeleteLaunchConfiguration").formParam("LaunchConfigurationName", name)
                    .post("/").then().statusCode(200);
        }
    }

    private void assertPaused(String name, int desired, int instances) {
        reconciler.reconcileAll();
        reconciler.reconcileAll();
        assertCapacity(name, desired, instances);
    }

    private static void assertCapacity(String name, int desired, int instances) {
        Response response = describe(name);
        assertEquals(desired, response.xmlPath().getInt(GROUP_PATH + ".DesiredCapacity"));
        assertEquals(instances, response.xmlPath().getList(GROUP_PATH + ".Instances.member").size());
    }

    private static Response describe(String name) {
        return request("DescribeAutoScalingGroups").formParam("AutoScalingGroupNames.member.1", name)
                .post("/").then().statusCode(200).extract().response();
    }

    private static String instanceState(String id) {
        return given().header("Authorization", auth("ec2"))
                .formParam("Action", "DescribeInstances").formParam("InstanceId.1", id)
                .post("/").then().statusCode(200).extract().xmlPath()
                .getString("DescribeInstancesResponse.reservationSet.item.instancesSet.item.instanceState.name");
    }

    private static void setDesired(String name, int desired) {
        request("SetDesiredCapacity").formParam("AutoScalingGroupName", name)
                .formParam("DesiredCapacity", desired).post("/").then().statusCode(200);
    }

    private static void process(String action, String name, boolean allProcesses, String process) {
        RequestSpecification request = request(action).formParam("AutoScalingGroupName", name);
        if (!allProcesses) {
            request.formParam("ScalingProcesses.member.1", process);
        }
        request.post("/").then().statusCode(200);
    }

    private static RequestSpecification request(String action) {
        return given().header("Authorization", auth("autoscaling")).formParam("Action", action);
    }

    private static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260215/us-east-1/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
