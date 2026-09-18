package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.xml.HasXPath.hasXPath;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #3775: EC2 reported {@code OwnerId: 000000000000} on every resource regardless of the
 * account the caller resolved to, so {@code aws ec2 describe-images --owners <account>} for the
 * account actually in use returned nothing and Terraform's {@code data "aws_ami"} with a pinned
 * {@code owners} list failed with "Your query returned no results". The owner reported on the wire
 * must be the same account STS would report for the credentials, and the {@code self} alias must
 * resolve to it.
 */
@QuarkusTest
class Ec2OwnerIdIntegrationTest {

    private static final String ACCOUNT = "444444444444";
    private static final String DEFAULT_ACCOUNT = "000000000000";

    /** SigV4 access key ids that are 12 digits resolve to that account. */
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260917/us-east-1/ec2/aws4_request";

    @Test
    void registeredImageReportsTheCallersAccountAsOwner() {
        String imageId = registerImage(uniqueName("owner-test"));

        given()
            .formParam("Action", "DescribeImages")
            .formParam("ImageId.1", imageId)
            .header("Authorization", AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(hasXPath("//*[local-name()='imageOwnerId']", equalTo(ACCOUNT)));
    }

    @Test
    void describeImagesOwnersFilterMatchesTheCallersAccount() {
        String imageId = registerImage(uniqueName("owner-filter"));

        assertTrue(ec2("DescribeImages", "Owner.1", ACCOUNT).contains(imageId),
                "--owners <caller account> must match the image the caller registered");
        assertTrue(ec2("DescribeImages", "Owner.1", "self").contains(imageId),
                "--owners self must resolve to the caller account");
        assertFalse(ec2("DescribeImages", "Owner.1", DEFAULT_ACCOUNT).contains(imageId),
                "the default account must not own an image registered by another account");
    }

    @Test
    void synthesizedLookupImageForSelfIsOwnedByTheCaller() {
        // CDK/Terraform AMI lookups by name wildcard synthesize an image when nothing matches;
        // with Owner.1=self that image must belong to the caller, not the default account.
        given()
            .formParam("Action", "DescribeImages")
            .formParam("Owner.1", "self")
            .formParam("Filter.1.Name", "name")
            .formParam("Filter.1.Value.1", "floci-3775-lookup-" + UUID.randomUUID() + "-*")
            .header("Authorization", AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(hasXPath("//*[local-name()='imageOwnerId']", equalTo(ACCOUNT)));
    }

    @Test
    void instanceProfileArnBuiltFromANameCarriesTheCallersAccount() {
        given().header("Authorization", AUTH.replace("/ec2/", "/iam/")).formParam("Action", "CreateInstanceProfile")
                .formParam("InstanceProfileName", "owner-profile").post("/").then().statusCode(200);
        given()
            .formParam("Action", "RunInstances")
            .formParam("ImageId", "ami-0abcdef1234567890")
            .formParam("InstanceType", "t3.micro")
            .formParam("MinCount", "1")
            .formParam("MaxCount", "1")
            .formParam("IamInstanceProfile.Name", "owner-profile")
            .header("Authorization", AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(hasXPath("//*[local-name()='iamInstanceProfile']/*[local-name()='arn']",
                    equalTo("arn:aws:iam::" + ACCOUNT + ":instance-profile/owner-profile")));
    }

    @Test
    void securityGroupReportsTheCallersAccountAsOwner() {
        String groupId = xmlValue(ec2("CreateSecurityGroup",
                "GroupName", uniqueName("owner-sg"), "GroupDescription", "t"), "groupId");

        given()
            .formParam("Action", "DescribeSecurityGroups")
            .formParam("GroupId.1", groupId)
            .header("Authorization", AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(hasXPath("//*[local-name()='securityGroupInfo']/*[local-name()='item']"
                    + "/*[local-name()='ownerId']", equalTo(ACCOUNT)));
    }

    private String registerImage(String name) {
        return xmlValue(ec2("RegisterImage", "Name", name, "Architecture", "x86_64",
                "RootDeviceName", "/dev/xvda", "VirtualizationType", "hvm"), "imageId");
    }

    private String ec2(String action, String... formParams) {
        RequestSpecification req = given().formParam("Action", action).header("Authorization", AUTH);
        for (int i = 0; i < formParams.length; i += 2) {
            req = req.formParam(formParams[i], formParams[i + 1]);
        }
        return req.when().post("/").then().statusCode(200).extract().asString();
    }

    private static String xmlValue(String xml, String element) {
        String open = "<" + element + ">";
        String close = "</" + element + ">";
        int start = xml.indexOf(open);
        return start < 0 ? null : xml.substring(start + open.length(), xml.indexOf(close, start));
    }

    /** A name unique per test run, so reruns never collide on InvalidAMIName.Duplicate. */
    private static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
