package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class CloudFormationYamlDateIntegrationTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void dateDefaultsRemainTextInResourcesAndOutputs(boolean quoted) {
        String name = "cfn-yaml-date-" + UUID.randomUUID();
        String date = quoted ? "'2012-10-17'" : "2012-10-17";
        String template = """
                AWSTemplateFormatVersion: '2010-09-09'
                Parameters:
                  DateValue:
                    Type: String
                    Default: %s
                Resources:
                  Parameter:
                    Type: AWS::SSM::Parameter
                    Properties:
                      Name: %s
                      Type: String
                      Value: !Ref DateValue
                Outputs:
                  DateText:
                    Value: !Ref DateValue
                """.formatted(date, name);

        try (CloudFormationClient cloudFormation = AwsTestClients.create(
                CloudFormationClient.builder(), "test", Region.US_EAST_1);
                SsmClient ssm = AwsTestClients.create(SsmClient.builder(), "test", Region.US_EAST_1)) {
            cloudFormation.createStack(request -> request.stackName(name).templateBody(template));
            try {
                Stack stack = CfnStackWaits.awaitStatus(cloudFormation, name, "CREATE_COMPLETE");

                assertEquals("2012-10-17", ssm.getParameter(request -> request.name(name)).parameter().value());
                assertEquals("2012-10-17", CfnStackWaits.output(stack, "DateText"));
                assertEquals("2012-10-17", stack.parameters().stream()
                        .filter(parameter -> "DateValue".equals(parameter.parameterKey()))
                        .findFirst().orElseThrow().parameterValue());
            } finally {
                cloudFormation.deleteStack(request -> request.stackName(name));
                CfnStackWaits.awaitStackDeleted(name);
            }
        }
    }
}
