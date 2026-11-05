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
import software.amazon.awssdk.services.ec2.model.Tag;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An {@code AWS::EC2::FlowLog}'s {@code Tags} through the AWS SDK. A stack update changes only the
 * keys the template owns: AWS keeps a tag added to the flow log through EC2 whether the update
 * replaces the template's tags or drops the property.
 */
@QuarkusTest
class CloudFormationFlowLogTagsIntegrationTest {

    private final String stackName = "cfn-flow-log-tags-" + UUID.randomUUID();
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
    void replacingTheTemplatesTagsKeepsATagAddedThroughEc2() {
        String flowLogId = createStackWithExternalTag();

        updateStack(tagsProperty("owner", "platform"));

        assertEquals(Map.of("owner", "platform", "external", "preserve"), tags(flowLogId));
        assertEquals(flowLogId, flowLogId());
    }

    @Test
    void droppingTheTemplatesTagsKeepsATagAddedThroughEc2() {
        String flowLogId = createStackWithExternalTag();

        updateStack("");

        assertEquals(Map.of("external", "preserve"), tags(flowLogId));
        assertEquals(flowLogId, flowLogId());
    }

    @Test
    void aTagKeyFromAChangedParameterReplacesTheOldKey() {
        String keyFromParameter = """
                ,
                        "Tags": [{"Key": {"Ref": "TagKey"}, "Value": "core"}]""";
        cfn.createStack(request -> request.stackName(stackName).templateBody(template(keyFromParameter)));
        stackCreated = true;
        CfnStackWaits.awaitStatus(cfn, stackName, "CREATE_COMPLETE");
        String flowLogId = flowLogId();

        cfn.updateStack(request -> request.stackName(stackName).templateBody(template(keyFromParameter))
                .parameters(parameter -> parameter.parameterKey("TagKey").parameterValue("owner")));
        CfnStackWaits.awaitStatus(cfn, stackName, "UPDATE_COMPLETE");

        // Re-resolving the previous template would read owner too and leave team behind.
        assertEquals(Map.of("owner", "core"), tags(flowLogId));
    }

    /** A stack whose flow log carries the template's {@code team=core} and an EC2-added tag. */
    private String createStackWithExternalTag() {
        cfn.createStack(request -> request.stackName(stackName).templateBody(template(tagsProperty("team", "core"))));
        stackCreated = true;
        CfnStackWaits.awaitStatus(cfn, stackName, "CREATE_COMPLETE");
        String flowLogId = flowLogId();
        assertEquals(Map.of("team", "core"), tags(flowLogId));
        ec2.createTags(request -> request.resources(flowLogId)
                .tags(Tag.builder().key("external").value("preserve").build()));
        return flowLogId;
    }

    private void updateStack(String tagsProperty) {
        cfn.updateStack(request -> request.stackName(stackName).templateBody(template(tagsProperty)));
        CfnStackWaits.awaitStatus(cfn, stackName, "UPDATE_COMPLETE");
    }

    private static String tagsProperty(String key, String value) {
        return """
                ,
                        "Tags": [{"Key": "%s", "Value": "%s"}]""".formatted(key, value);
    }

    private static String template(String tagsProperty) {
        return """
                {
                  "Parameters": {"TagKey": {"Type": "String", "Default": "team"}},
                  "Resources": {
                    "Vpc": {"Type": "AWS::EC2::VPC", "Properties": {"CidrBlock": "10.40.0.0/16"}},
                    "Flow": {
                      "Type": "AWS::EC2::FlowLog",
                      "Properties": {
                        "ResourceId": {"Ref": "Vpc"},
                        "ResourceType": "VPC",
                        "TrafficType": "ALL",
                        "LogDestinationType": "cloud-watch-logs",
                        "LogDestination": "arn:aws:logs:us-east-1:000000000000:log-group:/flow/tags"%s
                      }
                    }
                  },
                  "Outputs": {"FlowId": {"Value": {"Ref": "Flow"}}}
                }
                """.formatted(tagsProperty);
    }

    private String flowLogId() {
        return cfn.describeStacks(request -> request.stackName(stackName)).stacks().getFirst()
                .outputs().getFirst().outputValue();
    }

    private Map<String, String> tags(String flowLogId) {
        Map<String, String> tags = new TreeMap<>();
        ec2.describeFlowLogs(request -> request.flowLogIds(flowLogId)).flowLogs().getFirst().tags()
                .forEach(tag -> tags.put(tag.key(), tag.value()));
        return tags;
    }
}
