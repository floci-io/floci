package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class CloudFormationGetAttYamlIntegrationTest {

    @Test
    void getAttSequenceAndScalarFormsReturnTheProvisionedQueueArn() {
        String name = "cfn-getatt-yaml-" + Long.toString(System.nanoTime(), 36);
        String template = """
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

                for (String output : List.of("Flow", "Block", "Scalar")) {
                    assertEquals(queueArn, CfnStackWaits.output(stack, output), output);
                }
            } finally {
                cloudFormation.deleteStack(request -> request.stackName(name));
                CfnStackWaits.awaitStackDeleted(name);
            }
        }
    }
}
