package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.CfnRequests;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.floci.aws.testing.CfnRequests.describe;
import static io.floci.aws.testing.CfnRequests.output;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end check that CloudFormation provisions the EFS types for real, into EfsService, and
 * that {@code Ref} and each {@code Fn::GetAtt} key resolve to the created entity rather than the
 * stub arm's fake ARN. EFS is metadata only, so the test is Docker-free.
 */
@QuarkusTest
class CloudFormationEfsIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";

    private static String template(String fileSystemProperties, String mountTargetSecurityGroup) {
        return """
                {
                  "Resources": {
                    "Files": {
                      "Type": "AWS::EFS::FileSystem",
                      "Properties": {%s}
                    },
                    "Mount": {
                      "Type": "AWS::EFS::MountTarget",
                      "Properties": {
                        "FileSystemId": {"Ref": "Files"},
                        "SubnetId": "subnet-0123456789abcdef0",
                        "SecurityGroups": ["%s"]
                      }
                    },
                    "Data": {
                      "Type": "AWS::EFS::AccessPoint",
                      "Properties": {
                        "FileSystemId": {"Ref": "Files"},
                        "PosixUser": {"Uid": "1000", "Gid": "1001"},
                        "RootDirectory": {
                          "Path": "/data",
                          "CreationInfo": {"OwnerUid": "1000", "OwnerGid": "1001", "Permissions": "0755"}
                        }
                      }
                    }
                  },
                  "Outputs": {
                    "FsId": {"Value": {"Ref": "Files"}},
                    "FsArn": {"Value": {"Fn::GetAtt": ["Files", "Arn"]}},
                    "FsGetAttId": {"Value": {"Fn::GetAtt": ["Files", "FileSystemId"]}},
                    "MtId": {"Value": {"Ref": "Mount"}},
                    "MtGetAttId": {"Value": {"Fn::GetAtt": ["Mount", "Id"]}},
                    "MtIp": {"Value": {"Fn::GetAtt": ["Mount", "IpAddress"]}},
                    "ApId": {"Value": {"Ref": "Data"}},
                    "ApGetAttId": {"Value": {"Fn::GetAtt": ["Data", "AccessPointId"]}},
                    "ApArn": {"Value": {"Fn::GetAtt": ["Data", "Arn"]}}
                  }
                }
                """.formatted(fileSystemProperties, mountTargetSecurityGroup);
    }

    private static void createStack(String stackName, String template) {
        CfnRequests.deploy("CreateStack", stackName, template, CFN_AUTH, "CREATE_COMPLETE");
    }

    private static void updateStack(String stackName, String template) {
        CfnRequests.deploy("UpdateStack", stackName, template, CFN_AUTH, "UPDATE_COMPLETE");
    }

    private static void submitUpdate(String stackName, String template) {
        CfnRequests.cfn("UpdateStack", stackName)
            .header("Authorization", CFN_AUTH)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    @Test
    void createStackProvisionsTheThreeEfsTypesAndResolvesRefAndGetAtt() {
        String stackName = "cfn-efs-create-" + suffix();
        createStack(stackName, template("\"Encrypted\": false, \"FileSystemTags\": [{\"Key\": \"env\", \"Value\": \"dev\"}]",
                "sg-aaaa1111"));
        try {
            XmlPath stack = describe(stackName, CFN_AUTH);
            String fileSystemId = output(stack, "FsId");
            assertTrue(fileSystemId.startsWith("fs-"), fileSystemId);
            assertEquals(fileSystemId, output(stack, "FsGetAttId"));
            assertTrue(output(stack, "FsArn").endsWith(":file-system/" + fileSystemId), output(stack, "FsArn"));

            String mountTargetId = output(stack, "MtId");
            assertTrue(mountTargetId.startsWith("fsmt-"), mountTargetId);
            assertEquals(mountTargetId, output(stack, "MtGetAttId"));
            assertTrue(output(stack, "MtIp").matches("\\d+\\.\\d+\\.\\d+\\.\\d+"), output(stack, "MtIp"));

            String accessPointId = output(stack, "ApId");
            assertTrue(accessPointId.startsWith("fsap-"), accessPointId);
            assertEquals(accessPointId, output(stack, "ApGetAttId"));
            assertTrue(output(stack, "ApArn").endsWith(":access-point/" + accessPointId), output(stack, "ApArn"));

            given().queryParam("FileSystemId", fileSystemId)
            .when().get("/2015-02-01/file-systems")
            .then().statusCode(200)
                .body("FileSystems", hasSize(1))
                .body("FileSystems[0].NumberOfMountTargets", equalTo(1))
                .body("FileSystems[0].Tags[0].Key", equalTo("env"));

            given().queryParam("AccessPointId", accessPointId)
            .when().get("/2015-02-01/access-points")
            .then().statusCode(200)
                .body("AccessPoints[0].FileSystemId", equalTo(fileSystemId))
                .body("AccessPoints[0].PosixUser.Uid", equalTo(1000))
                .body("AccessPoints[0].RootDirectory.Path", equalTo("/data"))
                .body("AccessPoints[0].RootDirectory.CreationInfo.Permissions", equalTo("0755"));
        } finally {
            CfnStackWaits.deleteStack(stackName, CFN_AUTH);
        }
    }

    @Test
    void updateChangesMutablePropertiesInPlaceKeepingEveryPhysicalId() {
        String stackName = "cfn-efs-inplace-" + suffix();
        createStack(stackName, template("\"Encrypted\": false", "sg-aaaa1111"));
        try {
            XmlPath before = describe(stackName, CFN_AUTH);

            updateStack(stackName, template("""
                    "Encrypted": false,
                    "ThroughputMode": "provisioned",
                    "ProvisionedThroughputInMibps": 64,
                    "FileSystemTags": [{"Key": "team", "Value": "core"}]""", "sg-bbbb2222"));

            XmlPath after = describe(stackName, CFN_AUTH);
            assertEquals(output(before, "FsId"), output(after, "FsId"));
            assertEquals(output(before, "MtId"), output(after, "MtId"));
            assertEquals(output(before, "ApId"), output(after, "ApId"));

            given().queryParam("FileSystemId", output(after, "FsId"))
            .when().get("/2015-02-01/file-systems")
            .then().statusCode(200)
                .body("FileSystems[0].ThroughputMode", equalTo("provisioned"))
                .body("FileSystems[0].ProvisionedThroughputInMibps", equalTo(64.0f))
                .body("FileSystems[0].Tags[0].Key", equalTo("team"));

            given()
            .when().get("/2015-02-01/mount-targets/" + output(after, "MtId") + "/security-groups")
            .then().statusCode(200)
                .body("SecurityGroups", equalTo(List.of("sg-bbbb2222")));
        } finally {
            CfnStackWaits.deleteStack(stackName, CFN_AUTH);
        }
    }

    @Test
    void createOnlyChangeReplacesTheFileSystemAndDeletesTheDisplacedOne() {
        String stackName = "cfn-efs-replace-" + suffix();
        createStack(stackName, template("\"Encrypted\": false", "sg-aaaa1111"));
        try {
            XmlPath before = describe(stackName, CFN_AUTH);
            String oldFileSystemId = output(before, "FsId");
            String oldMountTargetId = output(before, "MtId");

            updateStack(stackName, template("\"Encrypted\": true", "sg-aaaa1111"));

            XmlPath after = describe(stackName, CFN_AUTH);
            assertNotEquals(oldFileSystemId, output(after, "FsId"));
            // The mount target and access point follow the file system they reference.
            assertNotEquals(oldMountTargetId, output(after, "MtId"));
            assertNotEquals(output(before, "ApId"), output(after, "ApId"));

            given().queryParam("FileSystemId", oldFileSystemId)
            .when().get("/2015-02-01/file-systems")
            .then().statusCode(404);
            given().queryParam("FileSystemId", output(after, "FsId"))
            .when().get("/2015-02-01/file-systems")
            .then().statusCode(200)
                .body("FileSystems[0].Encrypted", equalTo(true))
                .body("FileSystems[0].NumberOfMountTargets", equalTo(1));
        } finally {
            CfnStackWaits.deleteStack(stackName, CFN_AUTH);
        }
    }

    @Test
    void deleteStackRemovesTheMountTargetAndAccessPointBeforeTheFileSystem() {
        String stackName = "cfn-efs-delete-" + suffix();
        createStack(stackName, template("\"Encrypted\": false", "sg-aaaa1111"));
        XmlPath stack = describe(stackName, CFN_AUTH);
        String fileSystemId = output(stack, "FsId");
        String accessPointId = output(stack, "ApId");

        // A file system with a mount target cannot be deleted, so the stack delete succeeding
        // means the mount target went first.
        CfnStackWaits.deleteStack(stackName, CFN_AUTH);

        given().queryParam("FileSystemId", fileSystemId)
        .when().get("/2015-02-01/file-systems")
        .then().statusCode(404);
        given().queryParam("FileSystemId", fileSystemId)
        .when().get("/2015-02-01/mount-targets")
        .then().statusCode(200)
            .body("MountTargets", empty());
        given().queryParam("AccessPointId", accessPointId)
        .when().get("/2015-02-01/access-points")
        .then().statusCode(200)
            .body("AccessPoints", empty());
    }

    @Test
    void ipv6OnlyMountTargetHasNoIpv4AddressAndSurvivesAnUpdate() {
        String stackName = "cfn-efs-ipv6-" + suffix();
        String template = """
                {
                  "Resources": {
                    "Files": {"Type": "AWS::EFS::FileSystem", "Properties": {}},
                    "Mount": {
                      "Type": "AWS::EFS::MountTarget",
                      "Properties": {
                        "FileSystemId": {"Ref": "Files"},
                        "SubnetId": "subnet-0123456789abcdef0",
                        "IpAddressType": "IPV6_ONLY",
                        "SecurityGroups": ["%s"]
                      }
                    }
                  },
                  "Outputs": {
                    "FsId": {"Value": {"Ref": "Files"}},
                    "MtId": {"Value": {"Ref": "Mount"}}
                  }
                }
                """;
        createStack(stackName, template.formatted("sg-aaaa1111"));
        try {
            XmlPath stack = describe(stackName, CFN_AUTH);
            String fileSystemId = output(stack, "FsId");
            String mountTargetId = output(stack, "MtId");

            given().queryParam("MountTargetId", mountTargetId)
            .when().get("/2015-02-01/mount-targets")
            .then().statusCode(200)
                .body("MountTargets[0].IpAddressType", equalTo("IPV6_ONLY"))
                .body("MountTargets[0].IpAddress", nullValue());

            updateStack(stackName, template.formatted("sg-bbbb2222"));

            assertEquals(mountTargetId, output(describe(stackName, CFN_AUTH), "MtId"));
            given().queryParam("FileSystemId", fileSystemId)
            .when().get("/2015-02-01/mount-targets")
            .then().statusCode(200)
                .body("MountTargets", hasSize(1))
                .body("MountTargets[0].SecurityGroups", equalTo(List.of("sg-bbbb2222")));
        } finally {
            CfnStackWaits.deleteStack(stackName, CFN_AUTH);
        }
    }

    @Test
    void mountTargetCreatedWithoutASubnetIsRejected() {
        String stackName = "cfn-efs-nosubnet-" + suffix();
        createStack(stackName, """
                {"Resources": {"Files": {"Type": "AWS::EFS::FileSystem", "Properties": {}}},
                 "Outputs": {"FsId": {"Value": {"Ref": "Files"}}}}
                """);
        try {
            String fileSystemId = output(describe(stackName, CFN_AUTH), "FsId");

            given()
                .contentType("application/json")
                .body("{\"FileSystemId\": \"" + fileSystemId + "\"}")
            .when()
                .post("/2015-02-01/mount-targets")
            .then()
                .statusCode(400);

            given().queryParam("FileSystemId", fileSystemId)
            .when().get("/2015-02-01/mount-targets")
            .then().statusCode(200)
                .body("MountTargets", empty());
        } finally {
            CfnStackWaits.deleteStack(stackName, CFN_AUTH);
        }
    }

    /**
     * A replacement creates the new entity before the old one is deleted, so one that keeps the
     * old entity's collision key (same file system and subnet for a mount target) is refused by
     * EFS, the update rolls back, and the old mount target is left as it was.
     */
    @Test
    void mountTargetReplacementThatCollidesWithTheOldOneRollsBackAndKeepsIt() {
        String stackName = "cfn-efs-mtcollide-" + suffix();
        String template = """
                {
                  "Resources": {
                    "Files": {"Type": "AWS::EFS::FileSystem", "Properties": {}},
                    "Mount": {
                      "Type": "AWS::EFS::MountTarget",
                      "Properties": {
                        "FileSystemId": {"Ref": "Files"},
                        "SubnetId": "subnet-0123456789abcdef0",
                        "IpAddress": "%s",
                        "SecurityGroups": ["sg-aaaa1111"]
                      }
                    }
                  },
                  "Outputs": {
                    "FsId": {"Value": {"Ref": "Files"}},
                    "MtId": {"Value": {"Ref": "Mount"}}
                  }
                }
                """;
        createStack(stackName, template.formatted("10.0.0.21"));
        try {
            XmlPath before = describe(stackName, CFN_AUTH);
            String fileSystemId = output(before, "FsId");
            String mountTargetId = output(before, "MtId");

            submitUpdate(stackName, template.formatted("10.0.0.22"));

            CfnStackWaits.awaitStatus(stackName, "UPDATE_ROLLBACK_COMPLETE", CFN_AUTH);
            assertEquals(mountTargetId, output(describe(stackName, CFN_AUTH), "MtId"));
            given().queryParam("FileSystemId", fileSystemId)
            .when().get("/2015-02-01/mount-targets")
            .then().statusCode(200)
                .body("MountTargets", hasSize(1))
                .body("MountTargets[0].MountTargetId", equalTo(mountTargetId))
                .body("MountTargets[0].IpAddress", equalTo("10.0.0.21"));
            assertEquals("Mount target already exists in this subnet",
                    CfnStackWaits.failedResourceReason(stackName, CFN_AUTH));
        } finally {
            CfnStackWaits.deleteStack(stackName, CFN_AUTH);
        }
    }

    /**
     * The same for an access point that keeps an explicit ClientToken: the token must stay
     * unique, so changing a create-only property such as RootDirectory without changing the token
     * is refused, the update rolls back and the old access point is left as it was.
     */
    @Test
    void accessPointReplacementThatKeepsItsClientTokenRollsBackAndKeepsTheOldOne() {
        String stackName = "cfn-efs-apcollide-" + suffix();
        String template = """
                {
                  "Resources": {
                    "Files": {"Type": "AWS::EFS::FileSystem", "Properties": {}},
                    "Data": {
                      "Type": "AWS::EFS::AccessPoint",
                      "Properties": {
                        "FileSystemId": {"Ref": "Files"},
                        "ClientToken": "fixed-token-%s",
                        "RootDirectory": {"Path": "%s"}
                      }
                    }
                  },
                  "Outputs": {
                    "FsId": {"Value": {"Ref": "Files"}},
                    "ApId": {"Value": {"Ref": "Data"}}
                  }
                }
                """;
        String token = suffix();
        createStack(stackName, template.formatted(token, "/one"));
        try {
            XmlPath before = describe(stackName, CFN_AUTH);
            String accessPointId = output(before, "ApId");

            submitUpdate(stackName, template.formatted(token, "/two"));

            CfnStackWaits.awaitStatus(stackName, "UPDATE_ROLLBACK_COMPLETE", CFN_AUTH);
            assertEquals(accessPointId, output(describe(stackName, CFN_AUTH), "ApId"));
            given().queryParam("FileSystemId", output(before, "FsId"))
            .when().get("/2015-02-01/access-points")
            .then().statusCode(200)
                .body("AccessPoints", hasSize(1))
                .body("AccessPoints[0].AccessPointId", equalTo(accessPointId))
                .body("AccessPoints[0].RootDirectory.Path", equalTo("/one"));
            assertEquals("Access point with client token fixed-token-" + token + " already exists.",
                    CfnStackWaits.failedResourceReason(stackName, CFN_AUTH));
        } finally {
            CfnStackWaits.deleteStack(stackName, CFN_AUTH);
        }
    }

    @Test
    void mountTargetCreatedOutsideTheStackFailsTheFileSystemDelete() {
        String stackName = "cfn-efs-inuse-" + suffix();
        String template = """
                {
                  "Resources": {
                    "Files": {"Type": "AWS::EFS::FileSystem", "Properties": {}}
                  },
                  "Outputs": {"FsId": {"Value": {"Ref": "Files"}}}
                }
                """;
        createStack(stackName, template);
        String fileSystemId = output(describe(stackName, CFN_AUTH), "FsId");
        given()
            .contentType("application/json")
            .body("{\"FileSystemId\": \"" + fileSystemId + "\", \"SubnetId\": \"subnet-0123456789abcdef0\"}")
        .when()
            .post("/2015-02-01/mount-targets")
        .then()
            .statusCode(200);

        CfnRequests.cfn("DeleteStack", stackName)
            .header("Authorization", CFN_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stackName, CFN_AUTH);
        assertEquals("DELETE_FAILED", state.status());
        String events = CfnRequests.cfn("DescribeStackEvents", stackName)
            .header("Authorization", CFN_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();
        assertTrue(events.contains("<ResourceType>AWS::EFS::FileSystem</ResourceType>"
                + "<ResourceStatus>DELETE_FAILED</ResourceStatus>"
                + "<ResourceStatusReason>File system " + fileSystemId + " is in use."), events);
        given().queryParam("FileSystemId", fileSystemId)
        .when().get("/2015-02-01/file-systems")
        .then().statusCode(200)
            .body("FileSystems[0].FileSystemId", startsWith("fs-"));
    }
}
