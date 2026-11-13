package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.efs.EfsClient;
import software.amazon.awssdk.services.efs.model.AccessPointDescription;
import software.amazon.awssdk.services.efs.model.FileSystemDescription;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * A stack that defines an EFS file system and access point, and a stack with a mount target,
 * deployed and read back through the CloudFormation and EFS SDK clients.
 */
@DisplayName("CloudFormation EFS file system, access point and mount target")
class CloudFormationEfsTest {

    private static final Duration DEPLOY_TIMEOUT =
            TestFixtures.isRealAws() ? Duration.ofMinutes(15) : Duration.ofSeconds(15);
    private static final String STACK = TestFixtures.uniqueName("compat-cfn-efs");
    private static final String MOUNT_TARGET_STACK = TestFixtures.uniqueName("compat-cfn-efs-mt");

    private static CloudFormationClient cfn;
    private static EfsClient efs;

    @BeforeAll
    static void setup() {
        cfn = TestFixtures.cloudFormationClient();
        efs = TestFixtures.efsClient();
    }

    @AfterAll
    static void cleanup() throws InterruptedException {
        try {
            if (cfn != null) {
                // DeleteStack answers for a stack that is gone too, so a failure here is a real one.
                CloudFormationStackTestHelper.deleteStack(cfn, STACK, STACK);
                CloudFormationStackTestHelper.deleteStack(cfn, MOUNT_TARGET_STACK, MOUNT_TARGET_STACK);
            }
        } finally {
            try {
                if (cfn != null) {
                    cfn.close();
                }
            } finally {
                if (efs != null) {
                    efs.close();
                }
            }
        }
    }

    @Test
    @DisplayName("the file system and access point the stack created are described with the template's settings")
    void fileSystemAndAccessPointAreDescribed() throws InterruptedException {
        cfn.createStack(request -> request.stackName(STACK).templateBody(TEMPLATE));
        assertThat(TestFixtures.awaitStackTerminal(cfn, STACK, DEPLOY_TIMEOUT)).isEqualTo("CREATE_COMPLETE");

        String fileSystemId = physicalId(STACK, "Files");
        String accessPointId = physicalId(STACK, "Data");

        FileSystemDescription fileSystem = efs.describeFileSystems(request -> request.fileSystemId(fileSystemId))
                .fileSystems().get(0);
        assertThat(fileSystem.fileSystemId()).startsWith("fs-");
        assertThat(fileSystem.tags()).anyMatch(tag -> tag.key().equals("env") && tag.value().equals("compat"));

        AccessPointDescription accessPoint = efs.describeAccessPoints(request -> request.accessPointId(accessPointId))
                .accessPoints().get(0);
        assertThat(accessPoint.fileSystemId()).isEqualTo(fileSystemId);
        assertThat(accessPoint.posixUser().uid()).isEqualTo(1000L);
        assertThat(accessPoint.rootDirectory().path()).isEqualTo("/data");
        assertThat(accessPoint.rootDirectory().creationInfo().permissions()).isEqualTo("0755");
    }

    @Test
    @DisplayName("a mount target created by the stack is deleted before its file system")
    void mountTargetIsDeletedBeforeItsFileSystem() throws InterruptedException {
        assumeFalse(TestFixtures.isRealAws(), "A mount target needs a real subnet and takes minutes to delete on AWS");
        cfn.createStack(request -> request.stackName(MOUNT_TARGET_STACK).templateBody("""
                {
                  "Resources": {
                    "Files": {"Type": "AWS::EFS::FileSystem", "Properties": {}},
                    "Mount": {
                      "Type": "AWS::EFS::MountTarget",
                      "Properties": {
                        "FileSystemId": {"Ref": "Files"},
                        "SubnetId": "subnet-0123456789abcdef0",
                        "SecurityGroups": ["sg-0123456789abcdef0"]
                      }
                    }
                  }
                }
                """));
        assertThat(TestFixtures.awaitStackTerminal(cfn, MOUNT_TARGET_STACK, DEPLOY_TIMEOUT))
                .isEqualTo("CREATE_COMPLETE");
        String fileSystemId = physicalId(MOUNT_TARGET_STACK, "Files");
        assertThat(efs.describeMountTargets(request -> request.fileSystemId(fileSystemId)).mountTargets())
                .singleElement()
                .satisfies(mountTarget -> assertThat(mountTarget.subnetId()).isEqualTo("subnet-0123456789abcdef0"));

        // The stack only goes away if the file system delete was not refused for its mount target;
        // a refused delete leaves DELETE_FAILED, which deleteStack reports as a failure.
        CloudFormationStackTestHelper.deleteStack(cfn, MOUNT_TARGET_STACK, MOUNT_TARGET_STACK);
        assertThat(efs.describeFileSystems().fileSystems())
                .noneMatch(fileSystem -> fileSystem.fileSystemId().equals(fileSystemId));
    }

    private static String physicalId(String stackName, String logicalId) {
        return cfn.describeStackResource(request -> request.stackName(stackName).logicalResourceId(logicalId))
                .stackResourceDetail().physicalResourceId();
    }

    private static final String TEMPLATE = """
            {
              "Resources": {
                "Files": {
                  "Type": "AWS::EFS::FileSystem",
                  "Properties": {
                    "FileSystemTags": [{"Key": "env", "Value": "compat"}]
                  }
                },
                "Data": {
                  "Type": "AWS::EFS::AccessPoint",
                  "Properties": {
                    "FileSystemId": {"Ref": "Files"},
                    "PosixUser": {"Uid": "1000", "Gid": "1000"},
                    "RootDirectory": {
                      "Path": "/data",
                      "CreationInfo": {"OwnerUid": "1000", "OwnerGid": "1000", "Permissions": "0755"}
                    }
                  }
                }
              }
            }
            """;
}
