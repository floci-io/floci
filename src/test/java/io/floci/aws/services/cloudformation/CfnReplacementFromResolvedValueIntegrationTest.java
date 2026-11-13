package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.ChangeSetType;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.ParameterType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * A create-only property behind a value the template text does not show (a dynamic reference)
 * replaces the resource when the resolved value moves: the change set says
 * {@code Replacement: True} and the update replaces exactly then.
 */
@QuarkusTest
class CfnReplacementFromResolvedValueIntegrationTest {

    private static String template(String locationUri, String description) {
        return "{\"Resources\": {\"App\": {\"Type\": \"AWS::AppConfig::Application\","
                + " \"Properties\": {\"Name\": \"resolved-app\"}},"
                + "\"Subject\": {\"Type\": \"AWS::AppConfig::ConfigurationProfile\", \"Properties\": {"
                + "\"ApplicationId\": {\"Ref\": \"App\"}, \"Name\": \"settings\", \"LocationUri\": " + locationUri
                + ", \"Type\": \"AWS.Freeform\", \"Description\": \"" + description + "\"}}}}";
    }

    @Test
    void aDynamicReferenceWhoseStoredValueMovedReplacesTheProfile() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "resolved-ssm-" + suffix;
        String parameter = "/resolved/" + suffix + "/location";
        String reference = "\"{{resolve:ssm:" + parameter + "}}\"";
        try (CloudFormationClient client = AwsTestClients.create(
                CloudFormationClient.builder(), "test", Region.US_EAST_1);
             SsmClient ssm = AwsTestClients.create(SsmClient.builder(), "test", Region.US_EAST_1)) {
            ssm.putParameter(request -> request.name(parameter).value("ssm-parameter://one")
                    .type(ParameterType.STRING).overwrite(true));
            client.createStack(request -> request.stackName(stack).templateBody(template(reference, "first")));
            CfnStackWaits.awaitStatus(client, stack, "CREATE_COMPLETE");
            String before = physicalId(client, stack);

            ssm.putParameter(request -> request.name(parameter).value("ssm-parameter://two")
                    .type(ParameterType.STRING).overwrite(true));
            client.createChangeSet(request -> request.stackName(stack).changeSetName("move")
                    .changeSetType(ChangeSetType.UPDATE).templateBody(template(reference, "second")));
            assertEquals(List.of("True"), replacement(client, stack, "move"));

            client.executeChangeSet(request -> request.stackName(stack).changeSetName("move"));
            CfnStackWaits.awaitStatus(client, stack, "UPDATE_COMPLETE");
            assertNotEquals(before, physicalId(client, stack), "the profile is replaced, as the preview said");
        } finally {
            CfnStackWaits.deleteStack(stack);
        }
    }

    @Test
    void aRefWrittenAsAJoinOfTheSameValueIsNotAChange() throws Exception {
        String stack = "resolved-join-" + Long.toString(System.nanoTime(), 36);
        String asRef = "{\"Ref\": \"App\"}";
        String asJoin = "{\"Fn::Join\": [\"\", [{\"Ref\": \"App\"}]]}";
        try (CloudFormationClient client = AwsTestClients.create(
                CloudFormationClient.builder(), "test", Region.US_EAST_1)) {
            client.createStack(request -> request.stackName(stack)
                    .templateBody(applicationTemplate(asRef, "first")));
            CfnStackWaits.awaitStatus(client, stack, "CREATE_COMPLETE");
            String before = physicalId(client, stack);

            client.createChangeSet(request -> request.stackName(stack).changeSetName("rewrite")
                    .changeSetType(ChangeSetType.UPDATE).templateBody(applicationTemplate(asJoin, "second")));
            assertEquals(List.of("False"), replacement(client, stack, "rewrite"));

            client.executeChangeSet(request -> request.stackName(stack).changeSetName("rewrite"));
            CfnStackWaits.awaitStatus(client, stack, "UPDATE_COMPLETE");
            assertEquals(before, physicalId(client, stack), "the same ApplicationId keeps the profile");
        } finally {
            CfnStackWaits.deleteStack(stack);
        }
    }

    private static String applicationTemplate(String applicationId, String description) {
        return "{\"Resources\": {\"App\": {\"Type\": \"AWS::AppConfig::Application\","
                + " \"Properties\": {\"Name\": \"resolved-join-app\"}},"
                + "\"Subject\": {\"Type\": \"AWS::AppConfig::ConfigurationProfile\", \"Properties\": {"
                + "\"ApplicationId\": " + applicationId + ", \"Name\": \"settings\", \"LocationUri\": \"hosted\","
                + " \"Description\": \"" + description + "\"}}}}";
    }

    private static List<String> replacement(CloudFormationClient client, String stack, String changeSet) {
        return client.describeChangeSet(request -> request.stackName(stack).changeSetName(changeSet)).changes()
                .stream().filter(change -> "Subject".equals(change.resourceChange().logicalResourceId()))
                .map(change -> change.resourceChange().replacementAsString()).toList();
    }

    private static String physicalId(CloudFormationClient client, String stack) {
        return client.describeStackResource(request -> request.stackName(stack).logicalResourceId("Subject"))
                .stackResourceDetail().physicalResourceId();
    }
}
