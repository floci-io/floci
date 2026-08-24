package io.github.hectorvent.floci.services.autoscaling;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * lex00/floci#112's round-5 re-measure (choudoufu's round-5 repin, commit ae2a613b25, direct
 * probe against image sha256:827a6c5a): "CreateAutoScalingGroup with DesiredCapacityType=vcpu
 * and a MixedInstancesPolicy.Overrides entry round-trips through DescribeAutoScalingGroups as
 * DesiredCapacityType=None and Overrides absent". Two separate root causes:
 *
 * <p>1. DesiredCapacityType had no field at all - AutoScalingGroup's own doc comment on
 * desiredCapacityType has the oracle.
 *
 * <p>2. parseMixedLaunchTemplateOverrides used InstanceType's presence as its sole "is there
 * another override" signal. terraform-aws-autoscaling's own module.instance_requirements example
 * sets an override with ONLY InstanceRequirements (attribute-based selection - mutually
 * exclusive with InstanceType by AWS's own design), so the loop broke immediately at member 1
 * and dropped the ENTIRE Overrides list, not just the InstanceRequirements field.
 *
 * <p>Reproduces the exact shape .corpus/autoscaling/examples/complete/main.tf's
 * module.instance_requirements uses. Asserts the raw DescribeAutoScalingGroups wire response,
 * not the store.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AutoScalingInstanceRequirementsOverrideIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260501/us-east-1/autoscaling/aws4_request";
    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260501/us-east-1/ec2/aws4_request";

    @Test
    @Order(1)
    void createGroupWithDesiredCapacityTypeAndInstanceRequirementsOnlyOverride() {
        given()
                .formParam("Action", "CreateLaunchTemplate")
                .formParam("LaunchTemplateName", "instance-requirements-asg-lt")
                .formParam("LaunchTemplateData.ImageId", "ami-0123456789abcdef0")
                .header("Authorization", EC2_AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);

        given()
                .formParam("Action", "CreateAutoScalingGroup")
                .formParam("AutoScalingGroupName", "instance-requirements-asg")
                .formParam("MinSize", "0")
                .formParam("MaxSize", "5")
                .formParam("DesiredCapacity", "1")
                .formParam("DesiredCapacityType", "vcpu")
                .formParam("AvailabilityZones.member.1", "us-east-1a")
                .formParam("MixedInstancesPolicy.LaunchTemplate.LaunchTemplateSpecification.LaunchTemplateName",
                        "instance-requirements-asg-lt")
                // Attribute-based override: ONLY InstanceRequirements, no InstanceType at all -
                // the exact shape that used to make the parser's loop drop the whole list.
                .formParam("MixedInstancesPolicy.LaunchTemplate.Overrides.member.1.InstanceRequirements.CpuManufacturers.member.1",
                        "amd")
                .formParam("MixedInstancesPolicy.LaunchTemplate.Overrides.member.1.InstanceRequirements.LocalStorageTypes.member.1",
                        "ssd")
                .formParam("MixedInstancesPolicy.LaunchTemplate.Overrides.member.1.InstanceRequirements.MaxSpotPriceAsPercentageOfOptimalOnDemandPrice",
                        "60")
                .formParam("MixedInstancesPolicy.LaunchTemplate.Overrides.member.1.InstanceRequirements.MemoryGiBPerVCpu.Min", "2")
                .formParam("MixedInstancesPolicy.LaunchTemplate.Overrides.member.1.InstanceRequirements.MemoryGiBPerVCpu.Max", "4")
                .formParam("MixedInstancesPolicy.LaunchTemplate.Overrides.member.1.InstanceRequirements.MemoryMiB.Min", "2048")
                .formParam("MixedInstancesPolicy.LaunchTemplate.Overrides.member.1.InstanceRequirements.VCpuCount.Min", "2")
                .formParam("MixedInstancesPolicy.LaunchTemplate.Overrides.member.1.InstanceRequirements.VCpuCount.Max", "4")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }

    @Test
    @Order(2)
    void describeAutoScalingGroupsEchoesBothBack() {
        given()
                .formParam("Action", "DescribeAutoScalingGroups")
                .formParam("AutoScalingGroupNames.member.1", "instance-requirements-asg")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<DesiredCapacityType>vcpu</DesiredCapacityType>"))
                .body(containsString("<InstanceRequirements>"))
                .body(containsString("<member>amd</member>"))
                .body(containsString("<member>ssd</member>"))
                .body(containsString("<MaxSpotPriceAsPercentageOfOptimalOnDemandPrice>60</MaxSpotPriceAsPercentageOfOptimalOnDemandPrice>"))
                .body(containsString("<MemoryGiBPerVCpu>"))
                .body(containsString("<MemoryMiB>"))
                .body(containsString("<VCpuCount>"))
                .body(containsString("<Min>2048</Min>"))
                .body(containsString("<Min>2</Min>"))
                .body(containsString("<Max>4</Max>"));
    }
}
