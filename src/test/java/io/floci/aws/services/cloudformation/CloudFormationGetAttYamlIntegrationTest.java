package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.ChangeSetType;
import software.amazon.awssdk.services.cloudformation.model.ResourceStatus;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class CloudFormationGetAttYamlIntegrationTest {

    @Test
    void getAttSequenceAndScalarFormsReturnTheProvisionedQueueArn() {
        String name = "cfn-getatt-yaml-" + Long.toString(System.nanoTime(), 36);
        String template = """
                Parameters:
                  AttributeName:
                    Type: String
                    Default: Arn
                Resources:
                  Queue:
                    Type: AWS::SQS::Queue
                    Properties:
                      QueueName: %s
                Outputs:
                  Flow:
                    Value: !GetAtt [Queue, Arn]
                  Block:
                    Value: !GetAtt
                      - Queue
                      - Arn
                  Scalar:
                    Value: !GetAtt Queue.Arn
                  LongScalar:
                    Value:
                      Fn::GetAtt: Queue.Arn
                  Referenced:
                    Value: !GetAtt [Queue, !Ref AttributeName]
                  LongReferenced:
                    Value:
                      Fn::GetAtt:
                        - Queue
                        - Ref: AttributeName
                """.formatted(name);

        try (CloudFormationClient cloudFormation = AwsTestClients.create(
                CloudFormationClient.builder(), "test", Region.US_EAST_1);
                SqsClient sqs = AwsTestClients.create(SqsClient.builder(), "test", Region.US_EAST_1)) {
            cloudFormation.createStack(request -> request.stackName(name).templateBody(template));
            try {
                Stack stack = CfnStackWaits.awaitStatus(cloudFormation, name, "CREATE_COMPLETE");
                String queueUrl = sqs.getQueueUrl(request -> request.queueName(name)).queueUrl();
                String queueArn = sqs.getQueueAttributes(request -> request.queueUrl(queueUrl)
                        .attributeNames(QueueAttributeName.QUEUE_ARN))
                        .attributes().get(QueueAttributeName.QUEUE_ARN);
                assertNotNull(queueArn);

                for (String output : List.of("Flow", "Block", "Scalar", "LongScalar", "Referenced", "LongReferenced")) {
                    assertEquals(queueArn, CfnStackWaits.output(stack, output), output);
                }
            } finally {
                cloudFormation.deleteStack(request -> request.stackName(name));
                CfnStackWaits.awaitStackDeleted(name);
            }
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "!GetAtt [Queue] | false",
        "!GetAtt [Queue, ''] | false",
        "!GetAtt [] | false",
        "!GetAtt [Queue, !Ref EmptyAttribute] | false",
        "!GetAtt Queue | true",
        "!GetAtt Queue. | true",
        "!GetAtt .Arn | true",
        "!GetAtt '' | true",
        "{Fn::GetAtt: Queue} | true"
    })
    void invalidGetAttOutputReportsTheValidationReason(String expression, boolean stringForm) {
        String name = "cfn-getatt-invalid-" + Long.toString(System.nanoTime(), 36);
        String template = """
                Parameters:
                  EmptyAttribute:
                    Type: String
                    Default: ""
                Resources:
                  Queue:
                    Type: AWS::SQS::Queue
                    Properties:
                      QueueName: %s
                Outputs:
                  Invalid:
                    Value: %s
                """.formatted(name, expression);

        try (CloudFormationClient cloudFormation = AwsTestClients.create(
                CloudFormationClient.builder(), "test", Region.US_EAST_1)) {
            cloudFormation.createStack(request -> request.stackName(name).templateBody(template));
            try {
                Stack stack = CfnStackWaits.awaitStatus(cloudFormation, name, "ROLLBACK_COMPLETE");
                String expectedMessage = stringForm
                        ? "Template error: if specifying one argument to Fn::GetAtt, "
                                + "that argument must be a non-empty string in format <LogicalId>.<Attribute>"
                        : "Template error: every Fn::GetAtt object requires two non-empty parameters, "
                                + "the resource name and the resource attribute";
                assertEquals(expectedMessage, stack.stackStatusReason());
            } finally {
                cloudFormation.deleteStack(request -> request.stackName(name));
                CfnStackWaits.awaitStackDeleted(name);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stackUpdatesTrackReferencedAttributeNames(boolean executeChangeSet) {
        String name = "cfn-getatt-update-" + Long.toString(System.nanoTime(), 36);
        String template = """
                Parameters:
                  AttributeName:
                    Type: String
                    Default: LatestVersionNumber
                  StableAttributeName:
                    Type: String
                    Default: DefaultVersionNumber
                Conditions:
                  UseLatest: !Equals [!Ref AttributeName, LatestVersionNumber]
                Resources:
                  Lt:
                    Type: AWS::EC2::LaunchTemplate
                    Properties:
                      LaunchTemplateData:
                        ImageId: ami-12345678
                        InstanceType: t3.micro
                  Latest:
                    Type: AWS::SSM::Parameter
                    Properties:
                      Name: %1$s/latest
                      Type: String
                      Value: !GetAtt [Lt, !Ref AttributeName]
                  Wrapped:
                    Type: AWS::SSM::Parameter
                    Properties:
                      Name: %1$s/wrapped
                      Type: String
                      Value: !Sub ["${Version}", {Version: !If [UseLatest, !GetAtt [Lt, !Ref AttributeName], "0"]}]
                  Stable:
                    Type: AWS::SSM::Parameter
                    Properties:
                      Name: %1$s/stable
                      Type: String
                      Value: !GetAtt [Lt, !Ref StableAttributeName]
                  Inactive:
                    Type: AWS::SSM::Parameter
                    Properties:
                      Name: %1$s/inactive
                      Type: String
                      Value: !If [UseLatest, !GetAtt [Lt, !Ref StableAttributeName], !GetAtt [Lt, !Ref AttributeName]]
                """.formatted(name);
        String updatedTemplate = template.replace("t3.micro", "t3.small");

        try (CloudFormationClient cloudFormation = AwsTestClients.create(
                CloudFormationClient.builder(), "test", Region.US_EAST_1);
                SsmClient ssm = AwsTestClients.create(SsmClient.builder(), "test", Region.US_EAST_1)) {
            cloudFormation.createStack(request -> request.stackName(name).templateBody(template));
            try {
                CfnStackWaits.awaitStatus(cloudFormation, name, "CREATE_COMPLETE");
                for (String parameter : List.of("latest", "wrapped", "stable", "inactive")) {
                    assertEquals("1", ssm.getParameter(request -> request.name(name + "/" + parameter))
                            .parameter().value(), parameter);
                }

                if (executeChangeSet) {
                    cloudFormation.createChangeSet(request -> request.stackName(name)
                            .changeSetName("update-version").changeSetType(ChangeSetType.UPDATE)
                            .templateBody(updatedTemplate));
                    Set<String> changedResources = cloudFormation.describeChangeSet(request ->
                            request.stackName(name).changeSetName("update-version")).changes().stream()
                            .map(change -> change.resourceChange().logicalResourceId()).collect(Collectors.toSet());
                    assertEquals(Set.of("Lt", "Latest", "Wrapped"), changedResources,
                            "The preview must include only consumers of the changed attribute");
                    cloudFormation.executeChangeSet(request ->
                            request.stackName(name).changeSetName("update-version"));
                } else {
                    cloudFormation.updateStack(request -> request.stackName(name).templateBody(updatedTemplate));
                }

                CfnStackWaits.awaitStatus(cloudFormation, name, "UPDATE_COMPLETE");
                for (String parameter : List.of("latest", "wrapped")) {
                    assertEquals("2", ssm.getParameter(request -> request.name(name + "/" + parameter))
                            .parameter().value(), parameter + " must read the updated launch template version");
                }
                for (String parameter : List.of("stable", "inactive")) {
                    assertEquals("1", ssm.getParameter(request -> request.name(name + "/" + parameter))
                            .parameter().value(), parameter);
                }
                Set<String> updatedParameters = cloudFormation.describeStackEvents(request -> request.stackName(name))
                        .stackEvents().stream()
                        .filter(event -> "AWS::SSM::Parameter".equals(event.resourceType())
                                && event.resourceStatus() == ResourceStatus.UPDATE_COMPLETE)
                        .map(event -> event.logicalResourceId()).collect(Collectors.toSet());
                assertEquals(Set.of("Latest", "Wrapped"), updatedParameters,
                        "Unchanged attributes and inactive branches must not trigger an update");
            } finally {
                cloudFormation.deleteStack(request -> request.stackName(name));
                CfnStackWaits.awaitStackDeleted(name);
            }
        }
    }
}
