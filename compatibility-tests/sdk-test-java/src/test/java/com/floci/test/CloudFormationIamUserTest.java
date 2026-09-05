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
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.DeleteUserRequest;
import software.amazon.awssdk.services.iam.model.GetUserRequest;
import software.amazon.awssdk.services.iam.model.GetUserResponse;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;
import software.amazon.awssdk.services.iam.model.User;

import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CloudFormation AWS::IAM::User")
class CloudFormationIamUserTest {

    private static final Logger LOG = Logger.getLogger(CloudFormationIamUserTest.class.getName());

    private static CloudFormationClient cloudFormation;
    private static IamClient iam;
    private static String stackName;
    private static String userName;

    @BeforeAll
    static void setup() {
        cloudFormation = TestFixtures.cloudFormationClient();
        iam = TestFixtures.iamClient();
        stackName = TestFixtures.uniqueName("compat-cfn-iam-user");
        userName = TestFixtures.uniqueName("compat-user");
    }

    @AfterAll
    static void cleanup() {
        if (cloudFormation != null) {
            try {
                cloudFormation.deleteStack(
                        DeleteStackRequest.builder().stackName(stackName).build());
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to delete CloudFormation IAM user test stack: " + stackName, e);
            }
            cloudFormation.close();
        }
        if (iam != null) {
            try {
                iam.deleteUser(DeleteUserRequest.builder().userName(userName).build());
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to delete IAM user directly during cleanup: " + userName, e);
            }
            iam.close();
        }
    }

    @Test
    void createsValidatesUpdatesAndDeletesIamUserThroughAwsSdk() throws InterruptedException {
        cloudFormation.createStack(CreateStackRequest.builder()
                .stackName(stackName)
                .templateBody(template(userName, "/initial/"))
                .build());
        assertThat(waitForTerminal(stackName, 30)).isEqualTo("CREATE_COMPLETE");

        Stack createdStack = cloudFormation.describeStacks(
                DescribeStacksRequest.builder().stackName(stackName).build())
                .stacks().get(0);
        Map<String, String> outputs = createdStack.outputs().stream()
                .collect(Collectors.toMap(Output::outputKey, Output::outputValue));

        assertThat(outputs).containsEntry("UserRef", userName);
        String userArn = outputs.get("UserArn");
        assertThat(userArn).startsWith("arn:aws:iam::").endsWith(":user/initial/" + userName);

        GetUserResponse userResponse = iam.getUser(GetUserRequest.builder().userName(userName).build());
        User user = userResponse.user();
        assertThat(user.userName()).isEqualTo(userName);
        assertThat(user.path()).isEqualTo("/initial/");
        assertThat(user.arn()).isEqualTo(userArn);
        String userId = user.userId();
        assertThat(userId).startsWith("AIDA");

        // Update path on existing user
        cloudFormation.updateStack(r -> r
                .stackName(stackName)
                .templateBody(template(userName, "/updated/")));
        assertThat(waitForTerminal(stackName, 30)).isEqualTo("UPDATE_COMPLETE");

        GetUserResponse updatedUserResponse = iam.getUser(GetUserRequest.builder().userName(userName).build());
        assertThat(updatedUserResponse.user().path()).isEqualTo("/updated/");
        assertThat(updatedUserResponse.user().userId()).isEqualTo(userId);

        // Delete stack and verify user is removed
        cloudFormation.deleteStack(DeleteStackRequest.builder().stackName(stackName).build());
        waitForDeleted(stackName, 30);

        assertThatThrownBy(() -> iam.getUser(GetUserRequest.builder().userName(userName).build()))
                .isInstanceOf(NoSuchEntityException.class);
    }

    private static String template(String name, String path) {
        return """
                {
                  "Resources": {
                    "AppUser": {
                      "Type": "AWS::IAM::User",
                      "Properties": {
                        "UserName": "%s",
                        "Path": "%s"
                      }
                    }
                  },
                  "Outputs": {
                    "UserRef": {"Value": {"Ref": "AppUser"}},
                    "UserArn": {"Value": {"Fn::GetAtt": ["AppUser", "Arn"]}}
                  }
                }
                """.formatted(name, path);
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
