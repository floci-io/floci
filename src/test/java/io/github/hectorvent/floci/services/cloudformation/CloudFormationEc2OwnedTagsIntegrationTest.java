package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Tag;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The {@code Tags} of an {@code AWS::EC2::VPC}, {@code AWS::EC2::SecurityGroup} and
 * {@code AWS::EC2::Instance} through the AWS SDK. A stack update changes only the keys the stack
 * wrote: AWS keeps a tag added to the resource through EC2.
 */
@QuarkusTest
class CloudFormationEc2OwnedTagsIntegrationTest {

    private static final List<String> OUTPUTS = List.of("VpcId", "GroupId", "InstanceId");

    private final String stackName = "cfn-ec2-owned-tags-" + UUID.randomUUID();
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
    void droppingTheTemplatesTagsKeepsATagAddedThroughEc2() {
        Map<String, String> ids = createStackWithExternalTags();

        cfn.updateStack(request -> request.stackName(stackName).templateBody(template(false)));
        CfnStackWaits.awaitStatus(cfn, stackName, "UPDATE_COMPLETE");

        for (String output : OUTPUTS) {
            assertEquals(Map.of("external", "preserve"), tags(ids.get(output)), output);
        }
        assertEquals(ids, outputs(), "every resource is updated in place");
    }

    @Test
    void aTagKeyFromAChangedParameterReplacesOnlyTheOldKey() {
        Map<String, String> ids = createStackWithExternalTags();

        cfn.updateStack(request -> request.stackName(stackName).templateBody(template(true))
                .parameters(parameter -> parameter.parameterKey("TagKey").parameterValue("owner")));
        CfnStackWaits.awaitStatus(cfn, stackName, "UPDATE_COMPLETE");

        // Re-resolving the previous template would read owner too and leave team behind.
        for (String output : OUTPUTS) {
            assertEquals(Map.of("owner", "core", "external", "preserve"), tags(ids.get(output)), output);
        }
    }

    /** A stack whose VPC, group and instance carry the template's {@code team=core} and an EC2-added tag. */
    private Map<String, String> createStackWithExternalTags() {
        cfn.createStack(request -> request.stackName(stackName).templateBody(template(true)));
        stackCreated = true;
        CfnStackWaits.awaitStatus(cfn, stackName, "CREATE_COMPLETE");
        Map<String, String> ids = outputs();
        for (String output : OUTPUTS) {
            String id = ids.get(output);
            assertEquals(Map.of("team", "core"), tags(id), output);
            ec2.createTags(request -> request.resources(id)
                    .tags(Tag.builder().key("external").value("preserve").build()));
        }
        return ids;
    }

    private static String template(boolean tagged) {
        String tags = tagged ? """
                ,
                        "Tags": [{"Key": {"Ref": "TagKey"}, "Value": "core"}]""" : "";
        return """
                {
                  "Parameters": {"TagKey": {"Type": "String", "Default": "team"}},
                  "Resources": {
                    "Vpc": {"Type": "AWS::EC2::VPC", "Properties": {
                        "CidrBlock": "10.41.0.0/16"%1$s
                    }},
                    "Group": {"Type": "AWS::EC2::SecurityGroup", "Properties": {
                        "GroupDescription": "owned tags",
                        "VpcId": {"Ref": "Vpc"}%1$s
                    }},
                    "Server": {"Type": "AWS::EC2::Instance", "Properties": {
                        "ImageId": "ami-12345678",
                        "InstanceType": "t3.micro"%1$s
                    }}
                  },
                  "Outputs": {
                    "VpcId": {"Value": {"Ref": "Vpc"}},
                    "GroupId": {"Value": {"Ref": "Group"}},
                    "InstanceId": {"Value": {"Ref": "Server"}}
                  }
                }
                """.formatted(tags);
    }

    private Map<String, String> outputs() {
        Map<String, String> outputs = new TreeMap<>();
        for (Output output : cfn.describeStacks(request -> request.stackName(stackName)).stacks().getFirst()
                .outputs()) {
            outputs.put(output.outputKey(), output.outputValue());
        }
        return outputs;
    }

    private Map<String, String> tags(String resourceId) {
        Map<String, String> tags = new TreeMap<>();
        ec2.describeTags(request -> request.filters(filter -> filter.name("resource-id").values(resourceId)))
                .tags().forEach(tag -> tags.put(tag.key(), tag.value()));
        return tags;
    }
}
