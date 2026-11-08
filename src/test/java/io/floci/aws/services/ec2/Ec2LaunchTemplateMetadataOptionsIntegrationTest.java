package io.floci.aws.services.ec2;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.autoscaling.AutoScalingReconciler;
import io.floci.aws.services.autoscaling.AutoScalingService;
import io.floci.aws.testing.EnforcementFixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

/**
 * A launch template's MetadataOptions reach instances launched by EC2 Fleet and Auto Scaling, and
 * IMDS enforces them there.
 */
@QuarkusTest
class Ec2LaunchTemplateMetadataOptionsIntegrationTest {
    private static final String OWNER = "212121212121";
    private static final String INSTANCE = "DescribeInstancesResponse.reservationSet.item.instancesSet.item.";

    @Inject
    AutoScalingReconciler reconciler;

    @Inject
    AutoScalingService groups;

    @Inject
    Ec2Service ec2;

    @ParameterizedTest
    @ValueSource(strings = {"fleet", "asg"})
    void templateMetadataOptionsReachTheLaunchedInstance(String source) throws Exception {
        String name = "template-metadata-" + UUID.randomUUID().toString().substring(0, 8);
        String template = request("ec2", "CreateLaunchTemplate").formParam("LaunchTemplateName", name)
                .formParam("LaunchTemplateData.ImageId", "ami-amazonlinux2023")
                .formParam("LaunchTemplateData.InstanceType", "t3.micro")
                .formParam("LaunchTemplateData.MetadataOptions.HttpTokens", "required")
                .formParam("LaunchTemplateData.MetadataOptions.HttpPutResponseHopLimit", "2")
                .post("/").then().statusCode(200)
                .extract().path("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId");
        String instance = null;
        try {
            if ("fleet".equals(source)) {
                instance = request("ec2", "CreateFleet").formParam("Type", "instant")
                        .formParam("LaunchTemplateConfig.1.LaunchTemplateSpecification.LaunchTemplateId", template)
                        .formParam("LaunchTemplateConfig.1.LaunchTemplateSpecification.Version", "1")
                        .formParam("TargetCapacitySpecification.TotalTargetCapacity", 1)
                        .formParam("TargetCapacitySpecification.DefaultTargetCapacityType", "on-demand")
                        .post("/").then().statusCode(200)
                        .extract().path("CreateFleetResponse.fleetInstanceSet.item.instanceIds.item");
            } else {
                request("autoscaling", "CreateAutoScalingGroup").formParam("AutoScalingGroupName", name)
                        .formParam("LaunchTemplate.LaunchTemplateId", template).formParam("LaunchTemplate.Version", "1")
                        .formParam("MinSize", 0).formParam("MaxSize", 1).formParam("DesiredCapacity", 1)
                        .formParam("AvailabilityZones.member.1", "us-east-1a").post("/").then().statusCode(200);
                RequestScopes.runAs(OWNER, () -> reconciler.reconcile(
                        groups.describeAutoScalingGroups("us-east-1", List.of(name)).getFirst()));
                instance = request("autoscaling", "DescribeAutoScalingGroups")
                        .formParam("AutoScalingGroupNames.member.1", name).post("/").then().statusCode(200)
                        .extract().path("DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult"
                                + ".AutoScalingGroups.member.Instances.member.InstanceId");
            }
            request("ec2", "DescribeInstances").formParam("InstanceId.1", instance).post("/").then().statusCode(200)
                    .body(INSTANCE + "metadataOptions.httpTokens", equalTo("required"))
                    .body(INSTANCE + "metadataOptions.httpPutResponseHopLimit", equalTo("2"));
            // IMDS then refuses the launched instance a tokenless read, as AWS does.
            try (Ec2MetadataTestFixture imds = new Ec2MetadataTestFixture()) {
                imds.start(null, mock(EmulatorConfig.class, RETURNS_DEEP_STUBS),
                        ec2.findInstanceForAccount(OWNER, "us-east-1", instance).orElseThrow());
                assertEquals(401, imds.get("/latest/meta-data/instance-id", null).statusCode());
                assertEquals(200, imds.get("/latest/meta-data/instance-id", imds.putToken("60").body()).statusCode());
            }
        } finally {
            // Cleanup ignores the status, so a test that failed before launching keeps its own failure.
            if ("asg".equals(source)) {
                request("autoscaling", "DeleteAutoScalingGroup").formParam("AutoScalingGroupName", name)
                        .formParam("ForceDelete", "true").post("/");
            } else if (instance != null) {
                request("ec2", "TerminateInstances").formParam("InstanceId.1", instance).post("/");
            }
            request("ec2", "DeleteLaunchTemplate").formParam("LaunchTemplateId", template).post("/");
        }
    }

    /**
     * A template stores its metadata options unchecked, as AWS does, so the launch is where they fail.
     * A dry run answers that failure too rather than DryRunOperation.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void createFleetRefusesInvalidTemplateMetadataWithOrWithoutDryRun(boolean dryRun) {
        String template = request("ec2", "CreateLaunchTemplate")
                .formParam("LaunchTemplateName", "bad-metadata-" + UUID.randomUUID().toString().substring(0, 8))
                .formParam("LaunchTemplateData.ImageId", "ami-amazonlinux2023")
                .formParam("LaunchTemplateData.InstanceType", "t3.micro")
                .formParam("LaunchTemplateData.MetadataOptions.HttpTokens", "invalid")
                .post("/").then().statusCode(200)
                .extract().path("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId");
        try {
            request("ec2", "CreateFleet").formParam("Type", "instant").formParam("DryRun", String.valueOf(dryRun))
                    .formParam("LaunchTemplateConfig.1.LaunchTemplateSpecification.LaunchTemplateId", template)
                    .formParam("LaunchTemplateConfig.1.LaunchTemplateSpecification.Version", "1")
                    .formParam("TargetCapacitySpecification.TotalTargetCapacity", 1)
                    .formParam("TargetCapacitySpecification.DefaultTargetCapacityType", "on-demand")
                    .post("/").then().statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"))
                    .body("Response.Errors.Error.Message", equalTo(
                            "Value (invalid) for parameter HttpTokens is invalid. Valid values are: optional, required."));
        } finally {
            request("ec2", "DeleteLaunchTemplate").formParam("LaunchTemplateId", template).post("/");
        }
    }

    private static RequestSpecification request(String service, String action) {
        return given().header("Authorization", EnforcementFixtures.auth(OWNER, service)).formParam("Action", action);
    }
}
