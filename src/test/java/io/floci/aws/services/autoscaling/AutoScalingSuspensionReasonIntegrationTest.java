package io.floci.aws.services.autoscaling;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class AutoScalingSuspensionReasonIntegrationTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private static final String GROUP = "DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult"
            + ".AutoScalingGroups.member";
    private static final String REASON_PREFIX = "User suspended at ";
    private static final List<String> ALL_PROCESSES = List.of("Launch", "Terminate", "AddToLoadBalancer",
            "AlarmNotification", "AZRebalance", "HealthCheck", "InstanceRefresh", "ReplaceUnhealthy", "ScheduledActions");

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void suspensionReasonsFollowExplicitAndAllProcessTransitions(boolean all) {
        String name = "reason-" + UUID.randomUUID();
        createConfiguration(ACCOUNT, REGION, name);
        try {
            createGroup(ACCOUNT, REGION, name);
            try {
                Response initial = describe(ACCOUNT, REGION, name);
                process(ACCOUNT, REGION, "SuspendProcesses", name,
                        all ? List.of() : List.of("Launch", "Terminate"));
                Response firstSuspension = describe(ACCOUNT, REGION, name);
                process(ACCOUNT, REGION, "SuspendProcesses", name, List.of("Launch", "Launch"));
                Response suspended = describe(ACCOUNT, REGION, name);
                process(ACCOUNT, REGION, "ResumeProcesses", name, List.of("Launch"));
                Response partial = describe(ACCOUNT, REGION, name);
                process(ACCOUNT, REGION, "ResumeProcesses", name, List.of());
                Response resumed = describe(ACCOUNT, REGION, name);
                process(ACCOUNT, REGION, "SuspendProcesses", name, List.of("Launch"));
                Response resuspended = describe(ACCOUNT, REGION, name);
                List<String> expected = all ? ALL_PROCESSES : List.of("Launch", "Terminate");
                List<String> remaining = new ArrayList<>(expected);
                remaining.remove("Launch");
                assertAll(() -> assertSuspensions(initial, List.of()),
                        () -> assertSuspensions(suspended, expected),
                        () -> assertSuspensions(partial, remaining),
                        () -> assertSuspensions(resumed, List.of()),
                        () -> assertSuspensions(resuspended, List.of("Launch")),
                        () -> assertEquals(firstSuspension.xmlPath().getList(GROUP + ".SuspendedProcesses.member"),
                                suspended.xmlPath().getList(GROUP + ".SuspendedProcesses.member")));
            } finally {
                deleteGroup(ACCOUNT, REGION, name);
            }
        } finally {
            deleteConfiguration(ACCOUNT, REGION, name);
        }
    }

    @ParameterizedTest
    @CsvSource({"222222222222,us-east-1", "111111111111,us-west-2"})
    void sameNamedGroupsKeepTheirOwnReasons(String otherAccount, String otherRegion) {
        String name = "reason-scope-" + UUID.randomUUID();
        createConfiguration(ACCOUNT, REGION, name);
        try {
            createGroup(ACCOUNT, REGION, name);
            try {
                createConfiguration(otherAccount, otherRegion, name);
                try {
                    createGroup(otherAccount, otherRegion, name);
                    try {
                        process(ACCOUNT, REGION, "SuspendProcesses", name, List.of("Launch"));
                        Response unrelated = describe(otherAccount, otherRegion, name);
                        process(otherAccount, otherRegion, "SuspendProcesses", name, List.of("Terminate"));
                        Response owner = describe(ACCOUNT, REGION, name);
                        Response other = describe(otherAccount, otherRegion, name);
                        assertAll(() -> assertSuspensions(unrelated, List.of()),
                                () -> assertSuspensions(owner, List.of("Launch")),
                                () -> assertSuspensions(other, List.of("Terminate")));
                    } finally {
                        deleteGroup(otherAccount, otherRegion, name);
                    }
                } finally {
                    deleteConfiguration(otherAccount, otherRegion, name);
                }
            } finally {
                deleteGroup(ACCOUNT, REGION, name);
            }
        } finally {
            deleteConfiguration(ACCOUNT, REGION, name);
        }
    }

    private static void assertSuspensions(Response response, List<String> expected) {
        List<String> names = response.xmlPath().getList(GROUP + ".SuspendedProcesses.member.ProcessName", String.class);
        List<String> reasons = response.xmlPath().getList(GROUP + ".SuspendedProcesses.member.SuspensionReason", String.class);
        assertAll(() -> assertEquals(expected.size(), names.size()),
                () -> assertEquals(new HashSet<>(expected), new HashSet<>(names)),
                () -> assertEquals(expected.size(), reasons.size()),
                () -> reasons.forEach(reason -> {
                    assertTrue(reason.startsWith(REASON_PREFIX), reason);
                    assertDoesNotThrow(() -> Instant.parse(reason.substring(REASON_PREFIX.length())));
                }),
                () -> assertTrue(response.xmlPath().getList(GROUP + ".Instances.member").isEmpty()));
    }

    private static RequestSpecification request(String account, String region, String action) {
        return given().header("Authorization", auth(account, "20261018", region, "autoscaling"))
                .formParam("Action", action);
    }

    private static void createConfiguration(String account, String region, String name) {
        request(account, region, "CreateLaunchConfiguration").formParam("LaunchConfigurationName", name)
                .formParam("ImageId", "ami-amazonlinux2023").formParam("InstanceType", "t3.micro")
                .post("/").then().statusCode(200);
    }

    private static void createGroup(String account, String region, String name) {
        request(account, region, "CreateAutoScalingGroup").formParam("AutoScalingGroupName", name)
                .formParam("LaunchConfigurationName", name).formParam("MinSize", 0).formParam("MaxSize", 1)
                .formParam("DesiredCapacity", 0).formParam("AvailabilityZones.member.1", region + "a")
                .post("/").then().statusCode(200);
    }

    private static Response describe(String account, String region, String name) {
        Response response = request(account, region, "DescribeAutoScalingGroups")
                .formParam("AutoScalingGroupNames.member.1", name).post("/").then().statusCode(200).extract().response();
        assertEquals(List.of(name), response.xmlPath().getList(GROUP + ".AutoScalingGroupName", String.class));
        return response;
    }

    private static void process(String account, String region, String action, String name, List<String> names) {
        RequestSpecification request = request(account, region, action).formParam("AutoScalingGroupName", name);
        for (int i = 0; i < names.size(); i++) {
            request.formParam("ScalingProcesses.member." + (i + 1), names.get(i));
        }
        request.post("/").then().statusCode(200);
    }

    private static void deleteGroup(String account, String region, String name) {
        request(account, region, "DeleteAutoScalingGroup").formParam("AutoScalingGroupName", name)
                .formParam("ForceDelete", true).post("/").then().statusCode(200);
    }

    private static void deleteConfiguration(String account, String region, String name) {
        request(account, region, "DeleteLaunchConfiguration").formParam("LaunchConfigurationName", name)
                .post("/").then().statusCode(200);
    }
}
