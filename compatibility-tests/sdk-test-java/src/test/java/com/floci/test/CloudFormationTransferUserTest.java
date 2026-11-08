package com.floci.test;

import org.jboss.logging.Logger;
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
import software.amazon.awssdk.services.transfer.TransferClient;
import software.amazon.awssdk.services.transfer.model.DescribedUser;
import software.amazon.awssdk.services.transfer.model.ResourceNotFoundException;
import software.amazon.awssdk.services.transfer.model.SshPublicKey;
import software.amazon.awssdk.services.transfer.model.Tag;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

@DisplayName("CloudFormation AWS::Transfer::User")
class CloudFormationTransferUserTest {

    private static final Logger LOG = Logger.getLogger(CloudFormationTransferUserTest.class);

    private static final String INITIAL_KEY = "ssh-rsa AAAAB3NzaC1yc2EAAAAinitial";
    private static final String UPDATED_KEY = "ssh-rsa AAAAB3NzaC1yc2EAAAAupdated";

    private static CloudFormationClient cloudFormation;
    private static TransferClient transfer;
    private static String stackName;
    private static String userName;
    private static String initialRole;
    private static String updatedRole;

    @BeforeAll
    static void setup() {
        cloudFormation = TestFixtures.cloudFormationClient();
        transfer = TestFixtures.transferClient();
        stackName = TestFixtures.uniqueName("compat-cfn-transfer-user");
        userName = TestFixtures.uniqueName("compat-sftp-user");
        initialRole = "arn:" + TestFixtures.partition() + ":iam::000000000000:role/compat-transfer-initial";
        updatedRole = "arn:" + TestFixtures.partition() + ":iam::000000000000:role/compat-transfer-updated";
    }

    @AfterAll
    static void cleanup() {
        if (cloudFormation != null) {
            try {
                cloudFormation.deleteStack(
                        DeleteStackRequest.builder().stackName(stackName).build());
            } catch (Exception e) {
                LOG.warnv(e, "Failed to delete CloudFormation Transfer user test stack {0}", stackName);
            }
            cloudFormation.close();
        }
        if (transfer != null) {
            transfer.close();
        }
    }

    @Test
    void createsValidatesUpdatesAndDeletesTransferUserThroughAwsSdk() throws InterruptedException {
        cloudFormation.createStack(CreateStackRequest.builder()
                .stackName(stackName)
                .templateBody(template(initialRole, INITIAL_KEY))
                .build());
        assertThat(waitForTerminal(stackName, 30)).isEqualTo("CREATE_COMPLETE");

        Stack createdStack = cloudFormation.describeStacks(
                DescribeStacksRequest.builder().stackName(stackName).build())
                .stacks().get(0);
        Map<String, String> outputs = createdStack.outputs().stream()
                .collect(Collectors.toMap(Output::outputKey, Output::outputValue));

        String serverId = outputs.get("ServerId");
        assertThat(serverId).startsWith("s-");
        String userArn = outputs.get("UserArn");
        assertThat(userArn).startsWith("arn:" + TestFixtures.partition() + ":transfer:")
                .endsWith(":user/" + serverId + "/" + userName);
        assertThat(outputs).containsEntry("UserRef", userArn);

        DescribedUser user = describeUser(serverId);
        assertThat(user.userName()).isEqualTo(userName);
        assertThat(user.arn()).isEqualTo(userArn);
        assertThat(user.role()).isEqualTo(initialRole);
        assertThat(user.homeDirectory()).isEqualTo("/compat-bucket/home");
        assertThat(user.sshPublicKeys()).extracting(SshPublicKey::sshPublicKeyBody)
                .containsExactly(INITIAL_KEY);
        assertThat(user.tags()).extracting(Tag::key, Tag::value)
                .contains(tuple("Env", "compat"));
        assertThat(transfer.listTagsForResource(r -> r.arn(userArn)).tags())
                .extracting(Tag::key, Tag::value)
                .contains(tuple("Env", "compat"));

        cloudFormation.updateStack(r -> r
                .stackName(stackName)
                .templateBody(template(updatedRole, UPDATED_KEY)));
        assertThat(waitForTerminal(stackName, 30)).isEqualTo("UPDATE_COMPLETE");

        DescribedUser updatedUser = describeUser(serverId);
        assertThat(updatedUser.role()).isEqualTo(updatedRole);
        assertThat(updatedUser.sshPublicKeys()).extracting(SshPublicKey::sshPublicKeyBody)
                .containsExactly(UPDATED_KEY);

        cloudFormation.deleteStack(DeleteStackRequest.builder().stackName(stackName).build());
        waitForDeleted(stackName, 30);

        assertThatThrownBy(() -> describeUser(serverId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private static DescribedUser describeUser(String serverId) {
        return transfer.describeUser(r -> r.serverId(serverId).userName(userName)).user();
    }

    private static String template(String roleArn, String sshKey) {
        return """
                {
                  "Resources": {
                    "SftpServer": {
                      "Type": "AWS::Transfer::Server",
                      "Properties": {
                        "Protocols": ["SFTP"],
                        "IdentityProviderType": "SERVICE_MANAGED",
                        "EndpointType": "PUBLIC"
                      }
                    },
                    "SftpUser": {
                      "Type": "AWS::Transfer::User",
                      "Properties": {
                        "ServerId": {"Fn::GetAtt": ["SftpServer", "ServerId"]},
                        "UserName": "%s",
                        "Role": "%s",
                        "HomeDirectory": "/compat-bucket/home",
                        "SshPublicKeys": ["%s"],
                        "Tags": [{"Key": "Env", "Value": "compat"}]
                      }
                    }
                  },
                  "Outputs": {
                    "UserRef": {"Value": {"Ref": "SftpUser"}},
                    "UserArn": {"Value": {"Fn::GetAtt": ["SftpUser", "Arn"]}},
                    "ServerId": {"Value": {"Fn::GetAtt": ["SftpServer", "ServerId"]}}
                  }
                }
                """.formatted(userName, roleArn, sshKey);
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
