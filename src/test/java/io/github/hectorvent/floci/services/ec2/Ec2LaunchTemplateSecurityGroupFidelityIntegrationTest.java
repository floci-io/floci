package io.github.hectorvent.floci.services.ec2;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * lex00/floci#123: DescribeLaunchTemplateVersions' top-level SecurityGroupIds and
 * NetworkInterfaces[].Groups are documented (and, per the terraform-aws provider's own docs for
 * aws_launch_template's vpc_security_group_ids vs network_interfaces.security_groups, relied
 * upon) as mutually exclusive - a template sets its security groups one way or the other, never
 * both, and a real DescribePolicies response never invents the field the caller didn't set.
 * floci's own parseLaunchTemplateSecurityGroupIds used to fold BOTH
 * LaunchTemplateData.SecurityGroupId AND every LaunchTemplateData.NetworkInterface.N.Groups value
 * into the single top-level securityGroupIds field, so a template created with security groups
 * ONLY on a network interface echoed those same group IDs back on the top-level
 * securityGroupIdSet too - a perpetual diff for any real module (found by choudoufu's round-5
 * repin re-measure against corpus-autoscaling-complete). Oracle: botocore's
 * ec2/2016-11-15/service-2.json ResponseLaunchTemplateData's SecurityGroupIds and
 * LaunchTemplatesNetworkInterface's Groups shapes, kept as two distinct fields. Asserts the raw
 * DescribeLaunchTemplateVersions wire XML, not the store.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2LaunchTemplateSecurityGroupFidelityIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-west-2/ec2/aws4_request";

    @Test
    @Order(1)
    void networkInterfaceOnlyGroupsDoNotLeakIntoTopLevelSecurityGroupIds() {
        given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", "ni-only-groups-test")
            .formParam("LaunchTemplateData.ImageId", "ami-0123456789abcdef0")
            .formParam("LaunchTemplateData.InstanceType", "t3.micro")
            .formParam("LaunchTemplateData.NetworkInterface.1.DeviceIndex", "0")
            .formParam("LaunchTemplateData.NetworkInterface.1.SecurityGroupId.1", "sg-nigroup0000000001")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "DescribeLaunchTemplateVersions")
            .formParam("LaunchTemplateName", "ni-only-groups-test")
            .formParam("Versions.1", "$Latest")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            // The per-interface group must round-trip under the network interface itself...
            .body(containsString("<groupSet><groupId>sg-nigroup0000000001</groupId></groupSet>"))
            // ...and must NOT also appear as a top-level securityGroupIdSet entry - the classic
            // conflation this issue is about.
            .body(not(containsString("sg-nigroup0000000001</item>")))
            .body(containsString("<securityGroupIdSet></securityGroupIdSet>"));
    }

    @Test
    @Order(2)
    void topLevelGroupsDoNotLeakIntoNetworkInterfaceGroups() {
        given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", "top-level-only-groups-test")
            .formParam("LaunchTemplateData.ImageId", "ami-0123456789abcdef0")
            .formParam("LaunchTemplateData.InstanceType", "t3.micro")
            .formParam("LaunchTemplateData.SecurityGroupId.1", "sg-toplevel000000001")
            .formParam("LaunchTemplateData.NetworkInterface.1.DeviceIndex", "0")
            .formParam("LaunchTemplateData.NetworkInterface.1.SubnetId", "subnet-0123456789abcdef0")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "DescribeLaunchTemplateVersions")
            .formParam("LaunchTemplateName", "top-level-only-groups-test")
            .formParam("Versions.1", "$Latest")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<securityGroupIdSet><item>sg-toplevel000000001</item></securityGroupIdSet>"))
            .body(not(containsString("groupSet")));
    }
}
