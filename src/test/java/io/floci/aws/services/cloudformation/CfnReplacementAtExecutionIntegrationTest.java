package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;

import static io.floci.aws.services.cloudformation.CfnStackTestSupport.physicalId;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * A create-only property that reads an attribute another resource changes while the update runs
 * (a launch template's latest version) is unchanged in the template and in the preview, but the
 * resource it feeds is replaced when the update executes, as the value it resolves to differs.
 */
@QuarkusTest
class CfnReplacementAtExecutionIntegrationTest {

    private static String template(String instanceType) {
        return "{\"Resources\": {"
                + "\"Template\": {\"Type\": \"AWS::EC2::LaunchTemplate\", \"Properties\": {"
                + "\"LaunchTemplateName\": \"at-execution\", \"LaunchTemplateData\": {\"InstanceType\": \""
                + instanceType + "\"}}},"
                + "\"App\": {\"Type\": \"AWS::AppConfig::Application\", \"Properties\": {\"Name\": \"at-exec\"}},"
                + "\"Subject\": {\"Type\": \"AWS::AppConfig::ConfigurationProfile\", \"Properties\": {"
                + "\"ApplicationId\": {\"Ref\": \"App\"}, \"Name\": \"settings\", \"LocationUri\":"
                + " {\"Fn::Sub\": \"ssm-parameter://version-${Template.LatestVersionNumber}\"}}}}}";
    }

    @Test
    void aCreateOnlyPropertyReadingAnAttributeThatMovedReplacesTheResource() throws Exception {
        String stack = "at-exec-" + Long.toString(System.nanoTime(), 36);
        try (CloudFormationClient client = AwsTestClients.create(
                CloudFormationClient.builder(), "test", Region.US_EAST_1)) {
            client.createStack(request -> request.stackName(stack).templateBody(template("t3.micro")));
            CfnStackWaits.awaitStatus(client, stack, "CREATE_COMPLETE");
            String before = physicalId(client, stack, "Subject");

            client.updateStack(request -> request.stackName(stack).templateBody(template("t3.small")));
            CfnStackWaits.awaitStatus(client, stack, "UPDATE_COMPLETE");

            assertNotEquals(before, physicalId(client, stack, "Subject"),
                    "the profile's LocationUri resolves to another version, so it is replaced");
        } finally {
            CfnStackWaits.deleteStack(stack);
        }
    }
}
