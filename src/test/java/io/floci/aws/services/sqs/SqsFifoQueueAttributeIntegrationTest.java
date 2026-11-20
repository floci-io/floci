package io.floci.aws.services.sqs;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.InvalidAttributeNameException;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A standard queue has no FifoQueue attribute, so SQS refuses one even when it says {@code false}. */
@QuarkusTest
class SqsFifoQueueAttributeIntegrationTest {

    @Test
    void aStandardQueueWithFifoQueueFalseIsRefusedAsAnUnknownAttribute() {
        String queueName = "standard-" + UUID.randomUUID().toString().substring(0, 8);
        try (SqsClient sqs = AwsTestClients.create(SqsClient.builder(), "test", Region.US_EAST_1)) {
            InvalidAttributeNameException refused = assertThrows(InvalidAttributeNameException.class,
                    () -> sqs.createQueue(r -> r.queueName(queueName)
                            .attributes(Map.of(QueueAttributeName.FIFO_QUEUE, "false"))));

            assertEquals("Unknown Attribute FifoQueue.", refused.awsErrorDetails().errorMessage());
            assertThrows(QueueDoesNotExistException.class, () -> sqs.getQueueUrl(r -> r.queueName(queueName)));
        }
    }
}
