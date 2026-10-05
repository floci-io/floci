package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.Parameter;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GroupType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType;

import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CloudFormation AWS::Cognito::UserPoolUserToGroupAttachment")
class CloudFormationCognitoUserToGroupAttachmentTest {

    private static final String USER = "user-one";
    private static final String TEMPLATE = """
            {
              "Parameters": {
                "PoolId": {"Type": "String"},
                "GroupName": {"Type": "String"},
                "Username": {"Type": "String"}
              },
              "Resources": {
                "Attach": {
                  "Type": "AWS::Cognito::UserPoolUserToGroupAttachment",
                  "Properties": {
                    "UserPoolId": {"Ref": "PoolId"},
                    "GroupName": {"Ref": "GroupName"},
                    "Username": {"Ref": "Username"}
                  }
                }
              },
              "Outputs": {"AttachRef": {"Value": {"Ref": "Attach"}}}
            }
            """;

    private static CloudFormationClient cfn;
    private static CognitoIdentityProviderClient cognito;
    private static String poolId;
    private String stackName;
    private boolean stackCreated;

    @BeforeAll
    static void seed() {
        cfn = TestFixtures.cloudFormationClient();
        cognito = TestFixtures.cognitoClient();
        poolId = cognito.createUserPool(r -> r.poolName(TestFixtures.uniqueName("compat-cfn-attach")))
                .userPool().id();
        cognito.adminCreateUser(r -> r.userPoolId(poolId).username(USER)
                .messageAction(MessageActionType.SUPPRESS));
        for (String group : List.of("grp-a", "grp-b")) {
            cognito.createGroup(r -> r.userPoolId(poolId).groupName(group));
        }
    }

    @AfterEach
    void cleanup() throws InterruptedException {
        deleteStack();
    }

    @AfterAll
    static void teardown() {
        try {
            cognito.deleteUserPool(r -> r.userPoolId(poolId));
        } finally {
            cognito.close();
            cfn.close();
        }
    }

    @Test
    void attachmentAddsMovesAndRemovesTheMembership() throws InterruptedException {
        stackName = TestFixtures.uniqueName("compat-cfn-attach");

        cfn.createStack(r -> r.stackName(stackName).templateBody(TEMPLATE).parameters(parameters("grp-a")));
        stackCreated = true;
        awaitStatus("CREATE_COMPLETE");
        assertThat(output()).as("Ref is <UserPoolId>|<GroupName>|<Username>")
                .isEqualTo(poolId + "|grp-a|" + USER);
        assertThat(groups()).containsExactly("grp-a");

        cfn.updateStack(r -> r.stackName(stackName).templateBody(TEMPLATE).parameters(parameters("grp-b")));
        awaitStatus("UPDATE_COMPLETE");
        assertThat(output()).isEqualTo(poolId + "|grp-b|" + USER);
        assertThat(groups()).as("the replaced membership is removed in cleanup").containsExactly("grp-b");

        deleteStack();
        assertThat(groups()).isEmpty();
    }

    private static List<Parameter> parameters(String groupName) {
        return List.of(
                Parameter.builder().parameterKey("PoolId").parameterValue(poolId).build(),
                Parameter.builder().parameterKey("GroupName").parameterValue(groupName).build(),
                Parameter.builder().parameterKey("Username").parameterValue(USER).build());
    }

    private List<String> groups() {
        return cognito.adminListGroupsForUser(r -> r.userPoolId(poolId).username(USER)).groups().stream()
                .map(GroupType::groupName).toList();
    }

    private String output() {
        return cfn.describeStacks(r -> r.stackName(stackName)).stacks().get(0).outputs().stream()
                .filter(o -> "AttachRef".equals(o.outputKey())).map(Output::outputValue)
                .findFirst().orElseThrow();
    }

    private void awaitStatus(String expected) throws InterruptedException {
        await(() -> {
            Stack stack = cfn.describeStacks(r -> r.stackName(stackName)).stacks().get(0);
            String status = stack.stackStatusAsString();
            if (status.endsWith("_FAILED") || status.contains("ROLLBACK")) {
                throw new AssertionError(stackName + " reached " + status + ": " + stack.stackStatusReason());
            }
            return expected.equals(status);
        }, expected);
    }

    private void deleteStack() throws InterruptedException {
        if (!stackCreated) {
            return;
        }
        cfn.deleteStack(r -> r.stackName(stackName));
        await(() -> {
            try {
                List<Stack> stacks = cfn.describeStacks(r -> r.stackName(stackName)).stacks();
                if (stacks.isEmpty()) {
                    return true;
                }
                Stack stack = stacks.get(0);
                if ("DELETE_FAILED".equals(stack.stackStatusAsString())) {
                    throw new AssertionError(stackName + " deletion failed: " + stack.stackStatusReason());
                }
                return "DELETE_COMPLETE".equals(stack.stackStatusAsString());
            } catch (CloudFormationException e) {
                if ("ValidationError".equals(e.awsErrorDetails().errorCode())
                        && e.getMessage().contains("does not exist")) {
                    return true;
                }
                throw e;
            }
        }, "stack deletion");
        stackCreated = false;
    }

    private void await(BooleanSupplier condition, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError(stackName + " timed out waiting for " + expected);
    }
}
