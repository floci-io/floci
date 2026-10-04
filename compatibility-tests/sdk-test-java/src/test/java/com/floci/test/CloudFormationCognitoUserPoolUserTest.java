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
import software.amazon.awssdk.services.cloudformation.model.Parameter;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.cloudformation.model.UpdateStackRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;

import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CloudFormation AWS::Cognito::UserPoolUser")
class CloudFormationCognitoUserPoolUserTest {

    private static final Logger LOG = Logger.getLogger(CloudFormationCognitoUserPoolUserTest.class.getName());

    private static final String TEMPLATE = """
            {
              "Parameters": {
                "PoolId": { "Type": "String" },
                "Username": { "Type": "String" }
              },
              "Resources": {
                "User": {
                  "Type": "AWS::Cognito::UserPoolUser",
                  "Properties": {
                    "UserPoolId": { "Ref": "PoolId" },
                    "Username": { "Ref": "Username" },
                    "MessageAction": "SUPPRESS",
                    "UserAttributes": [
                      { "Name": "email", "Value": "one@example.com" },
                      { "Name": "name", "Value": "One" }
                    ]
                  }
                }
              },
              "Outputs": {
                "UserRef": { "Value": { "Ref": "User" } }
              }
            }
            """;

    private static CloudFormationClient cloudFormation;
    private static CognitoIdentityProviderClient cognito;
    private static String stackName;
    private static String poolId;

    @BeforeAll
    static void setup() {
        cloudFormation = TestFixtures.cloudFormationClient();
        cognito = TestFixtures.cognitoClient();
        stackName = TestFixtures.uniqueName("compat-cfn-cognito-user");
        poolId = cognito.createUserPool(r -> r.poolName(stackName)).userPool().id();
    }

    @AfterAll
    static void cleanup() {
        if (cloudFormation != null) {
            try {
                cloudFormation.deleteStack(DeleteStackRequest.builder().stackName(stackName).build());
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to delete CloudFormation Cognito user test stack: " + stackName, e);
            }
            cloudFormation.close();
        }
        if (cognito != null) {
            try {
                cognito.deleteUserPool(r -> r.userPoolId(poolId));
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to delete Cognito user pool during cleanup: " + poolId, e);
            }
            cognito.close();
        }
    }

    @Test
    @DisplayName("Ref is the username, a rename replaces the user and DeleteStack removes it")
    void createsRenamesAndDeletesUserPoolUserThroughAwsSdk() throws InterruptedException {
        cloudFormation.createStack(CreateStackRequest.builder()
                .stackName(stackName)
                .templateBody(TEMPLATE)
                .parameters(parameters("user-one"))
                .build());
        assertThat(waitForTerminal(stackName, 30)).isEqualTo("CREATE_COMPLETE");
        assertThat(outputs()).containsEntry("UserRef", "user-one");

        AdminGetUserResponse user = cognito.adminGetUser(r -> r.userPoolId(poolId).username("user-one"));
        assertThat(user.username()).isEqualTo("user-one");
        assertThat(attributes(user.userAttributes()))
                .containsEntry("email", "one@example.com")
                .containsEntry("name", "One")
                .containsKey("sub");

        cloudFormation.updateStack(UpdateStackRequest.builder()
                .stackName(stackName)
                .templateBody(TEMPLATE)
                .parameters(parameters("user-one-b"))
                .build());
        assertThat(waitForTerminal(stackName, 30)).isEqualTo("UPDATE_COMPLETE");
        assertThat(outputs()).containsEntry("UserRef", "user-one-b");
        assertThat(cognito.adminGetUser(r -> r.userPoolId(poolId).username("user-one-b")).username())
                .isEqualTo("user-one-b");
        assertThatThrownBy(() -> cognito.adminGetUser(r -> r.userPoolId(poolId).username("user-one")))
                .isInstanceOf(UserNotFoundException.class);

        cloudFormation.deleteStack(DeleteStackRequest.builder().stackName(stackName).build());
        waitForDeleted(stackName, 30);
        assertThatThrownBy(() -> cognito.adminGetUser(r -> r.userPoolId(poolId).username("user-one-b")))
                .isInstanceOf(UserNotFoundException.class);
    }

    private static List<Parameter> parameters(String username) {
        return List.of(
                Parameter.builder().parameterKey("PoolId").parameterValue(poolId).build(),
                Parameter.builder().parameterKey("Username").parameterValue(username).build());
    }

    private static Map<String, String> outputs() {
        return cloudFormation.describeStacks(DescribeStacksRequest.builder().stackName(stackName).build())
                .stacks().get(0).outputs().stream()
                .collect(Collectors.toMap(Output::outputKey, Output::outputValue));
    }

    private static Map<String, String> attributes(List<AttributeType> attributes) {
        return attributes.stream().collect(Collectors.toMap(AttributeType::name, AttributeType::value));
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
        throw new AssertionError("Stack " + name + " did not reach a terminal state within " + maxSeconds + "s");
    }

    private static void waitForDeleted(String name, int maxSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                List<Stack> stacks = cloudFormation.describeStacks(
                        DescribeStacksRequest.builder().stackName(name).build()).stacks();
                if (stacks.isEmpty() || "DELETE_COMPLETE".equals(stacks.get(0).stackStatusAsString())) {
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
        throw new AssertionError("Stack " + name + " was not deleted within " + maxSeconds + "s");
    }
}
