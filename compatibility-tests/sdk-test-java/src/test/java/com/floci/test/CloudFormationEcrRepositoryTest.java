package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.CreateStackRequest;
import software.amazon.awssdk.services.cloudformation.model.DeleteStackRequest;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksRequest;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.DescribeRepositoriesRequest;
import software.amazon.awssdk.services.ecr.model.Repository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CloudFormation AWS::ECR::Repository")
class CloudFormationEcrRepositoryTest {

    private static CloudFormationClient cloudFormation;
    private static EcrClient ecr;
    private static String stackName;
    private static String repoName;

    @BeforeAll
    static void setup() {
        cloudFormation = TestFixtures.cloudFormationClient();
        ecr = TestFixtures.ecrClient();
        stackName = TestFixtures.uniqueName("compat-cfn-ecr");
        repoName = TestFixtures.uniqueName("compat-cfn-repo").toLowerCase();
    }

    @AfterAll
    static void cleanup() {
        if (cloudFormation != null) {
            try {
                cloudFormation.deleteStack(DeleteStackRequest.builder().stackName(stackName).build());
                waitForDeleted(stackName, 30);
            } catch (Exception e) {
                System.err.println("CloudFormation ECR cleanup skipped: " + e.getMessage());
            }
            cloudFormation.close();
        }
        if (ecr != null) {
            ecr.close();
        }
    }

    @Test
    void createsAndUpdatesImageScanningConfigurationThroughAwsSdk() throws InterruptedException {
        cloudFormation.createStack(CreateStackRequest.builder()
                .stackName(stackName)
                .templateBody(templateWithScanOnPush(true, "MUTABLE"))
                .build());
        assertThat(waitForTerminal(stackName, 30)).isEqualTo("CREATE_COMPLETE");

        assertThat(describeRepo().imageScanningConfiguration().scanOnPush()).isTrue();

        cloudFormation.updateStack(r -> r
                .stackName(stackName)
                .templateBody(templateWithScanOnPush(false, "MUTABLE")));
        assertThat(waitForTerminal(stackName, 30)).isEqualTo("UPDATE_COMPLETE");
        assertThat(describeRepo().imageScanningConfiguration().scanOnPush()).isFalse();

        cloudFormation.updateStack(r -> r
                .stackName(stackName)
                .templateBody(templateWithScanOnPush(true, "MUTABLE")));
        assertThat(waitForTerminal(stackName, 30)).isEqualTo("UPDATE_COMPLETE");
        assertThat(describeRepo().imageScanningConfiguration().scanOnPush()).isTrue();

        // Dropping ImageScanningConfiguration leaves the stored scanOnPush value intact.
        cloudFormation.updateStack(r -> r
                .stackName(stackName)
                .templateBody(templateWithoutScanOnPush("IMMUTABLE")));
        assertThat(waitForTerminal(stackName, 30)).isEqualTo("UPDATE_COMPLETE");
        assertThat(describeRepo().imageScanningConfiguration().scanOnPush()).isTrue();

        cloudFormation.deleteStack(DeleteStackRequest.builder().stackName(stackName).build());
        waitForDeleted(stackName, 30);
    }

    private static Repository describeRepo() {
        return ecr.describeRepositories(DescribeRepositoriesRequest.builder()
                        .repositoryNames(repoName)
                        .build())
                .repositories()
                .get(0);
    }

    private static String templateWithScanOnPush(boolean scanOnPush, String mutability) {
        return """
                {
                  "Resources": {
                    "Repo": {
                      "Type": "AWS::ECR::Repository",
                      "Properties": {
                        "RepositoryName": "%s",
                        "ImageTagMutability": "%s",
                        "ImageScanningConfiguration": {
                          "ScanOnPush": %s
                        }
                      }
                    }
                  }
                }
                """.formatted(repoName, mutability, scanOnPush);
    }

    private static String templateWithoutScanOnPush(String mutability) {
        return """
                {
                  "Resources": {
                    "Repo": {
                      "Type": "AWS::ECR::Repository",
                      "Properties": {
                        "RepositoryName": "%s",
                        "ImageTagMutability": "%s"
                      }
                    }
                  }
                }
                """.formatted(repoName, mutability);
    }

    private static String waitForTerminal(String name, int maxSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            List<Stack> stacks = cloudFormation.describeStacks(
                    DescribeStacksRequest.builder().stackName(name).build()).stacks();
            if (!stacks.isEmpty()) {
                String status = stacks.get(0).stackStatusAsString();
                if (!status.endsWith("_IN_PROGRESS")) {
                    return status;
                }
            }
            Thread.sleep(500);
        }
        throw new AssertionError(
                "Stack " + name + " did not reach a terminal state within " + maxSeconds + "s");
    }

    private static void waitForDeleted(String name, int maxSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                List<Stack> stacks = cloudFormation.describeStacks(
                        DescribeStacksRequest.builder().stackName(name).build()).stacks();
                if (stacks.isEmpty()
                        || "DELETE_COMPLETE".equals(stacks.get(0).stackStatusAsString())) {
                    return;
                }
            } catch (CloudFormationException e) {
                if (e.getMessage() != null && e.getMessage().contains("does not exist")) {
                    return;
                }
                throw e;
            }
            Thread.sleep(500);
        }
        throw new AssertionError(
                "Stack " + name + " was not deleted within " + maxSeconds + "s");
    }
}
