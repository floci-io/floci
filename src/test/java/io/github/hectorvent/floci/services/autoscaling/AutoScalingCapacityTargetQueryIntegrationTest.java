package io.github.hectorvent.floci.services.autoscaling;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class AutoScalingCapacityTargetQueryIntegrationTest {
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261023/us-east-1/autoscaling/aws4_request";
    private static final String MEMBER =
            "DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult.AutoScalingGroups.member";
    private static final String TARGET = "CapacityReservationSpecification.CapacityReservationTarget.";

    @ParameterizedTest
    @CsvSource({"CapacityReservationIds,false", "CapacityReservationIds,true",
            "CapacityReservationResourceGroupArns,false", "CapacityReservationResourceGroupArns,true"})
    void sparseMultipleTargetsCannotMutateGroup(String target, boolean update) {
        String name = "sparse-target-" + UUID.randomUUID();
        createConfiguration(name);
        try {
            if (update) {
                create(name).post("/").then().statusCode(200);
            }
            RequestSpecification request = update ? request("UpdateAutoScalingGroup", name)
                    .formParam("DesiredCapacity", "1") : create(name);
            request.formParam("CapacityReservationSpecification.CapacityReservationPreference",
                            "capacity-reservations-only")
                    .formParam(TARGET + target + ".member.1", "target-one")
                    .formParam(TARGET + target + ".member.3", "target-three")
                    .post("/").then().statusCode(400).body(containsString("ValidationError"));
            describe(name).body(MEMBER + ".size()", equalTo(update ? 1 : 0));
            if (update) {
                describe(name).body(MEMBER + ".DesiredCapacity", equalTo("0"))
                        .body(MEMBER + ".CapacityReservationSpecification.size()", equalTo(0));
            }
        } finally {
            cleanUp(name);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CapacityReservationIds", "CapacityReservationResourceGroupArns"})
    void sparseSingletonTargetIsRetained(String target) {
        String name = "sparse-single-" + UUID.randomUUID();
        createConfiguration(name);
        try {
            create(name).formParam("CapacityReservationSpecification.CapacityReservationPreference",
                            "capacity-reservations-only")
                    .formParam(TARGET + target + ".member.3", "target-three")
                    .post("/").then().statusCode(200);
            describe(name).body(MEMBER + ".CapacityReservationSpecification.CapacityReservationTarget."
                    + target + ".member", equalTo("target-three"));
        } finally {
            cleanUp(name);
        }
    }

    private static RequestSpecification request(String action, String name) {
        return given().header("Authorization", AUTH).formParam("Action", action)
                .formParam("AutoScalingGroupName", name);
    }

    private static RequestSpecification create(String name) {
        return request("CreateAutoScalingGroup", name).formParam("LaunchConfigurationName", name)
                .formParam("MinSize", "0").formParam("MaxSize", "1").formParam("DesiredCapacity", "0")
                .formParam("AvailabilityZones.member.1", "us-east-1a");
    }

    private static ValidatableResponse describe(String name) {
        return given().header("Authorization", AUTH).formParam("Action", "DescribeAutoScalingGroups")
                .formParam("AutoScalingGroupNames.member.1", name).post("/").then().statusCode(200);
    }

    private static void createConfiguration(String name) {
        given().header("Authorization", AUTH).formParam("Action", "CreateLaunchConfiguration")
                .formParam("LaunchConfigurationName", name).formParam("ImageId", "ami-amazonlinux2023")
                .formParam("InstanceType", "t3.small").post("/").then().statusCode(200);
    }

    private static void cleanUp(String name) {
        int count = describe(name).extract().xmlPath().getInt(MEMBER + ".size()");
        if (count > 0) {
            request("DeleteAutoScalingGroup", name).formParam("ForceDelete", "true")
                    .post("/").then().statusCode(200);
        }
        given().header("Authorization", AUTH).formParam("Action", "DeleteLaunchConfiguration")
                .formParam("LaunchConfigurationName", name).post("/").then().statusCode(200);
    }
}
