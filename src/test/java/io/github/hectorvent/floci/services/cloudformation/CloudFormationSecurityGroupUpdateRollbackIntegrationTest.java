package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.SecurityGroup;
import software.amazon.awssdk.services.ec2.model.Tag;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A stack update that changes an {@code AWS::EC2::SecurityGroup} in place and then fails on a later
 * resource rolls the group back through the AWS SDK: its inline rules and the tag keys the stack
 * owns are the first deployment's again, a tag added through EC2 is kept, and a following update
 * still reconciles from that first deployment.
 */
@QuarkusTest
class CloudFormationSecurityGroupUpdateRollbackIntegrationTest {

    private static final String ORIGINAL = """
            "SecurityGroupIngress": [
              {"IpProtocol": "tcp", "FromPort": 80, "ToPort": 80, "CidrIp": "0.0.0.0/0"},
              {"IpProtocol": "tcp", "FromPort": 443, "ToPort": 443, "CidrIp": "0.0.0.0/0"}
            ],
            "Tags": [{"Key": "team", "Value": "core"}]""";

    private static final String CHANGED = """
            "SecurityGroupIngress": [
              {"IpProtocol": "tcp", "FromPort": 80, "ToPort": 80, "CidrIp": "0.0.0.0/0"},
              {"IpProtocol": "tcp", "FromPort": 8080, "ToPort": 8080, "CidrIp": "0.0.0.0/0"}
            ],
            "Tags": [{"Key": "owner", "Value": "platform"}]""";

    private final String stackName = "cfn-sg-rollback-" + UUID.randomUUID();
    private CloudFormationClient cfn;
    private Ec2Client ec2;
    private boolean stackCreated;

    @BeforeEach
    void clients() {
        cfn = AwsTestClients.create(CloudFormationClient.builder(), "test", Region.US_EAST_1);
        ec2 = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1);
    }

    @AfterEach
    void deleteStack() {
        try {
            if (stackCreated) {
                cfn.deleteStack(request -> request.stackName(stackName));
            }
        } finally {
            ec2.close();
            cfn.close();
        }
    }

    @Test
    void aFailedUpdatePutsTheRulesAndTheOwnedTagsBack() {
        cfn.createStack(request -> request.stackName(stackName).templateBody(template(ORIGINAL, "")));
        stackCreated = true;
        String groupId = CfnStackWaits.awaitStatus(cfn, stackName, "CREATE_COMPLETE").outputs().getFirst()
                .outputValue();
        ec2.createTags(request -> request.resources(groupId)
                .tags(Tag.builder().key("external").value("preserve").build()));

        cfn.updateStack(request -> request.stackName(stackName).templateBody(template(CHANGED, failingResource())));
        CfnStackWaits.awaitStatus(cfn, stackName, "UPDATE_ROLLBACK_COMPLETE");

        assertEquals(Set.of(80, 443), ingressPorts(groupId));
        assertEquals(Map.of("team", "core", "external", "preserve"), tags(groupId));

        // The rollback put the first deployment's records back too, so dropping the rules and tags
        // now removes exactly what that deployment wrote.
        cfn.updateStack(request -> request.stackName(stackName).templateBody(template("\"Tags\": []", "")));
        CfnStackWaits.awaitStatus(cfn, stackName, "UPDATE_COMPLETE");
        assertEquals(Set.of(), ingressPorts(groupId));
        assertEquals(Map.of("external", "preserve"), tags(groupId));
    }

    private String template(String groupProperties, String moreResources) {
        return """
                {
                  "Resources": {
                    "Group": {"Type": "AWS::EC2::SecurityGroup", "Properties": {
                        "GroupDescription": "rollback",
                        %s
                    }}%s
                  },
                  "Outputs": {"GroupId": {"Value": {"Ref": "Group"}}}
                }
                """.formatted(groupProperties, moreResources);
    }

    /** A resource that fails after the group, so the update rolls back. */
    private String failingResource() {
        return """
                ,
                    "BadSecret": {
                      "Type": "AWS::SecretsManager::Secret",
                      "DependsOn": "Group",
                      "Properties": {
                        "Name": "%s",
                        "SecretString": "explicit",
                        "GenerateSecretString": {"PasswordLength": 32}
                      }
                    }""".formatted(stackName);
    }

    private Set<Integer> ingressPorts(String groupId) {
        SecurityGroup group = ec2.describeSecurityGroups(request -> request.groupIds(groupId)).securityGroups()
                .getFirst();
        return group.ipPermissions().stream().map(permission -> permission.fromPort()).collect(Collectors.toSet());
    }

    private Map<String, String> tags(String groupId) {
        Map<String, String> tags = new TreeMap<>();
        ec2.describeTags(request -> request.filters(filter -> filter.name("resource-id").values(groupId)))
                .tags().forEach(tag -> tags.put(tag.key(), tag.value()));
        return tags;
    }
}
