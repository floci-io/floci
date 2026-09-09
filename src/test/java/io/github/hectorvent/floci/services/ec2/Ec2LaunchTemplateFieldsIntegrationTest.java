package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Regression tests for CreateLaunchTemplate discarding nearly all of RequestLaunchTemplateData.
 *
 * <p>Only ImageId, InstanceType, KeyName, UserData, IamInstanceProfile, SecurityGroupIds and
 * TagSpecifications survived a create; MetadataOptions, BlockDeviceMappings, NetworkInterfaces and
 * every options block were dropped without an error. Terraform then read back its own input
 * missing and reported "Provider produced inconsistent result after apply", or diffed forever.
 *
 * <p>Field names and shapes here follow the EC2 service model's RequestLaunchTemplateData and
 * ResponseLaunchTemplateData. The request parameter names are the ones botocore's EC2 serializer
 * actually emits — note the singular {@code BlockDeviceMapping.1} / {@code NetworkInterface.1}
 * list prefixes, which come from each list member's locationName.
 */
@QuarkusTest
class Ec2LaunchTemplateFieldsIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static final String DATA =
            "DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.";

    private String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String describeBody(String name) {
        return describeLatest(name).extract().body().asString();
    }

    private ValidatableResponse describeLatest(String name) {
        return given()
            .formParam("Action", "DescribeLaunchTemplateVersions")
            .formParam("LaunchTemplateName", name)
            .formParam("Versions.1", "$Latest")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    void metadataOptionsAndBlockDeviceMappingsRoundTrip() {
        String name = uniqueName("repro-lt");
        given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", name)
            .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
            .formParam("LaunchTemplateData.InstanceType", "t3.micro")
            .formParam("LaunchTemplateData.BlockDeviceMapping.1.DeviceName", "/dev/xvda")
            .formParam("LaunchTemplateData.BlockDeviceMapping.1.Ebs.VolumeSize", "20")
            .formParam("LaunchTemplateData.BlockDeviceMapping.1.Ebs.VolumeType", "gp3")
            .formParam("LaunchTemplateData.BlockDeviceMapping.1.Ebs.Encrypted", "true")
            .formParam("LaunchTemplateData.MetadataOptions.HttpTokens", "required")
            .formParam("LaunchTemplateData.MetadataOptions.HttpPutResponseHopLimit", "2")
            .formParam("LaunchTemplateData.EbsOptimized", "true")
            .formParam("LaunchTemplateData.Monitoring.Enabled", "true")
            .formParam("LaunchTemplateData.CpuOptions.CoreCount", "1")
            .formParam("LaunchTemplateData.CpuOptions.ThreadsPerCore", "1")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        describeLatest(name)
            // IMDSv2 — appears in essentially every Gruntwork ASG/ECS module.
            .body(DATA + "metadataOptions.httpTokens", equalTo("required"))
            .body(DATA + "metadataOptions.httpPutResponseHopLimit", equalTo("2"))
            .body(DATA + "metadataOptions.state", equalTo("applied"))
            .body(DATA + "blockDeviceMappingSet.item.deviceName", equalTo("/dev/xvda"))
            .body(DATA + "blockDeviceMappingSet.item.ebs.volumeSize", equalTo("20"))
            .body(DATA + "blockDeviceMappingSet.item.ebs.volumeType", equalTo("gp3"))
            .body(DATA + "blockDeviceMappingSet.item.ebs.encrypted", equalTo("true"))
            .body(DATA + "ebsOptimized", equalTo("true"))
            .body(DATA + "monitoring.enabled", equalTo("true"))
            .body(DATA + "cpuOptions.coreCount", equalTo("1"))
            .body(DATA + "cpuOptions.threadsPerCore", equalTo("1"));
    }

    @Test
    void remainingOptionsBlocksRoundTrip() {
        String name = uniqueName("options-lt");
        given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", name)
            .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
            .formParam("LaunchTemplateData.Placement.Tenancy", "default")
            .formParam("LaunchTemplateData.Placement.AvailabilityZone", "us-east-1a")
            .formParam("LaunchTemplateData.CreditSpecification.CpuCredits", "unlimited")
            .formParam("LaunchTemplateData.EnclaveOptions.Enabled", "false")
            .formParam("LaunchTemplateData.HibernationOptions.Configured", "false")
            .formParam("LaunchTemplateData.MaintenanceOptions.AutoRecovery", "default")
            .formParam("LaunchTemplateData.PrivateDnsNameOptions.HostnameType", "ip-name")
            .formParam("LaunchTemplateData.PrivateDnsNameOptions.EnableResourceNameDnsARecord", "true")
            .formParam("LaunchTemplateData.CapacityReservationSpecification.CapacityReservationPreference", "open")
            .formParam("LaunchTemplateData.DisableApiTermination", "false")
            .formParam("LaunchTemplateData.InstanceInitiatedShutdownBehavior", "terminate")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        describeLatest(name)
            .body(DATA + "placement.tenancy", equalTo("default"))
            .body(DATA + "placement.availabilityZone", equalTo("us-east-1a"))
            .body(DATA + "creditSpecification.cpuCredits", equalTo("unlimited"))
            .body(DATA + "enclaveOptions.enabled", equalTo("false"))
            .body(DATA + "hibernationOptions.configured", equalTo("false"))
            .body(DATA + "maintenanceOptions.autoRecovery", equalTo("default"))
            .body(DATA + "privateDnsNameOptions.hostnameType", equalTo("ip-name"))
            .body(DATA + "privateDnsNameOptions.enableResourceNameDnsARecord", equalTo("true"))
            .body(DATA + "capacityReservationSpecification.capacityReservationPreference", equalTo("open"))
            .body(DATA + "disableApiTermination", equalTo("false"))
            .body(DATA + "instanceInitiatedShutdownBehavior", equalTo("terminate"));
    }

    @Test
    void iamInstanceProfileKeepsTheFormItWasGiven() {
        // Terraform sets .name and read back .arn, so iam_instance_profile.name never converged.
        String byName = uniqueName("profile-name-lt");
        given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", byName)
            .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
            .formParam("LaunchTemplateData.IamInstanceProfile.Name", "audit-ip")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        describeLatest(byName)
            .body(DATA + "iamInstanceProfile.name", equalTo("audit-ip"));
        assertFalse(describeBody(byName).contains("<arn>"),
                "a profile submitted as Name must not read back carrying an arn");

        String byArn = uniqueName("profile-arn-lt");
        given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", byArn)
            .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
            .formParam("LaunchTemplateData.IamInstanceProfile.Arn",
                    "arn:aws:iam::000000000000:instance-profile/explicit")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        describeLatest(byArn)
            .body(DATA + "iamInstanceProfile.arn",
                    equalTo("arn:aws:iam::000000000000:instance-profile/explicit"));
        assertFalse(describeBody(byArn).contains("<name>"),
                "a profile submitted as Arn must not read back carrying a name");
    }

    @Test
    void networkInterfacesSurviveAsNetworkInterfacesInsteadOfBeingPromoted() {
        // Groups used to be hoisted into top-level SecurityGroupIds and the whole
        // NetworkInterfaces block discarded. On AWS the two are mutually exclusive, so the
        // network_interfaces block simply vanished from Terraform state.
        String name = uniqueName("eni-lt");
        given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", name)
            .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
            .formParam("LaunchTemplateData.NetworkInterface.1.DeviceIndex", "0")
            .formParam("LaunchTemplateData.NetworkInterface.1.SubnetId", "subnet-0123456789abcdef0")
            .formParam("LaunchTemplateData.NetworkInterface.1.AssociatePublicIpAddress", "true")
            .formParam("LaunchTemplateData.NetworkInterface.1.DeleteOnTermination", "true")
            .formParam("LaunchTemplateData.NetworkInterface.1.SecurityGroupId.1", "sg-1111111111111111a")
            .formParam("LaunchTemplateData.NetworkInterface.1.SecurityGroupId.2", "sg-2222222222222222b")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        describeLatest(name)
            .body(DATA + "networkInterfaceSet.item.deviceIndex", equalTo("0"))
            .body(DATA + "networkInterfaceSet.item.subnetId", equalTo("subnet-0123456789abcdef0"))
            .body(DATA + "networkInterfaceSet.item.associatePublicIpAddress", equalTo("true"))
            .body(DATA + "networkInterfaceSet.item.deleteOnTermination", equalTo("true"))
            .body(DATA + "networkInterfaceSet.item.groupSet.item",
                    contains("sg-1111111111111111a", "sg-2222222222222222b"));
        // Not promoted to the top level: that promotion is what made the block disappear.
        assertFalse(describeBody(name).contains("securityGroupIdSet"),
                "interface groups must not be hoisted into top-level SecurityGroupIds");
    }

    @Test
    void createLaunchTemplateVersionInheritsOptionsBlocksItDoesNotRestate() {
        String name = uniqueName("version-lt");
        given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", name)
            .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
            .formParam("LaunchTemplateData.InstanceType", "t3.micro")
            .formParam("LaunchTemplateData.MetadataOptions.HttpTokens", "required")
            .formParam("LaunchTemplateData.BlockDeviceMapping.1.DeviceName", "/dev/xvda")
            .formParam("LaunchTemplateData.BlockDeviceMapping.1.Ebs.VolumeSize", "20")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "CreateLaunchTemplateVersion")
            .formParam("LaunchTemplateName", name)
            .formParam("SourceVersion", "1")
            .formParam("LaunchTemplateData.InstanceType", "t3.small")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateLaunchTemplateVersionResponse.launchTemplateVersion.launchTemplateData.instanceType",
                    equalTo("t3.small"))
            .body("CreateLaunchTemplateVersionResponse.launchTemplateVersion.launchTemplateData"
                    + ".metadataOptions.httpTokens", equalTo("required"));

        describeLatest(name)
            .body(DATA + "instanceType", equalTo("t3.small"))
            .body(DATA + "metadataOptions.httpTokens", equalTo("required"))
            .body(DATA + "blockDeviceMappingSet.item.deviceName", equalTo("/dev/xvda"))
            .body(DATA + "blockDeviceMappingSet.item.ebs.volumeSize", equalTo("20"));
    }

    @Test
    void createLaunchTemplateVersionWithoutSourceVersionDoesNotInherit() {
        // AWS documents "no SourceVersion" as "no inheritance" — the new version must start from
        // an empty LaunchTemplateData, not merge onto the latest version the way an explicit
        // SourceVersion does (see createLaunchTemplateVersionInheritsOptionsBlocksItDoesNotRestate
        // above, which covers the explicit-SourceVersion case).
        String name = uniqueName("no-source-lt");
        given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", name)
            .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
            .formParam("LaunchTemplateData.InstanceType", "t3.micro")
            .formParam("LaunchTemplateData.KeyName", "app-key")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "CreateLaunchTemplateVersion")
            .formParam("LaunchTemplateName", name)
            // No SourceVersion parameter at all.
            .formParam("LaunchTemplateData.InstanceType", "t3.small")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateLaunchTemplateVersionResponse.launchTemplateVersion.launchTemplateData.instanceType",
                    equalTo("t3.small"));

        String body = describeBody(name);
        assertFalse(body.contains("<imageId>"),
                "an omitted SourceVersion must not inherit ImageId from the latest version");
        assertFalse(body.contains("<keyName>"),
                "an omitted SourceVersion must not inherit KeyName from the latest version");
    }

    @Test
    void versionDescriptionRoundTripsThroughCreateAndDescribe() {
        String name = uniqueName("described-lt");
        given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", name)
            .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
            .formParam("VersionDescription", "initial rollout")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId", org.hamcrest.Matchers.notNullValue());

        given()
            .formParam("Action", "CreateLaunchTemplateVersion")
            .formParam("LaunchTemplateName", name)
            .formParam("SourceVersion", "1")
            .formParam("LaunchTemplateData.InstanceType", "t3.small")
            .formParam("VersionDescription", "bump instance type")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateLaunchTemplateVersionResponse.launchTemplateVersion.versionDescription",
                    equalTo("bump instance type"));

        given()
            .formParam("Action", "DescribeLaunchTemplateVersions")
            .formParam("LaunchTemplateName", name)
            .formParam("Versions.1", "1")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.versionDescription",
                    equalTo("initial rollout"));

        describeLatest(name)
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.versionDescription",
                    equalTo("bump instance type"));
    }

    @Test
    void securityGroupsByNameAreAcceptedAndIgnored() {
        // By-name SecurityGroups stay out of scope (see docs/services/ec2.md): resolving names to
        // IDs would need lookup machinery no other EC2 action here has either. The request must
        // still succeed, and SecurityGroupIds — the supported form — must be unaffected.
        String name = uniqueName("sg-by-name-lt");
        given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", name)
            .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
            .formParam("LaunchTemplateData.SecurityGroup.1", "my-app-sg")
            .formParam("LaunchTemplateData.SecurityGroupId.1", "sg-1111111111111111a")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        describeLatest(name)
            .body(DATA + "securityGroupIdSet.item", equalTo("sg-1111111111111111a"));
        assertFalse(describeBody(name).contains("my-app-sg"),
                "SecurityGroups (by name) is accepted and ignored, not stored");
    }
}
