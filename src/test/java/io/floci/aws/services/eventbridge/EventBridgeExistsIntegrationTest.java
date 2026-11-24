package io.floci.aws.services.eventbridge;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class EventBridgeExistsIntegrationTest {

    private static final String ACCOUNT = "725018800073";
    private static final String PRESENT_PATTERN = "{\"detail\":{\"status\":[{\"exists\":true}]}}";
    private static final String ABSENT_PATTERN = "{\"detail\":{\"status\":[{\"exists\":false}]}}";
    private static final String OBJECT_EVENT = """
            {"version":"0","id":"exists-leaf","detail-type":"Example","source":"example.test",
             "account":"725018800073","time":"2026-11-24T00:00:00Z","region":"us-east-1",
             "resources":[],"detail":{"status":{"nested":"v"}}}
            """;

    @Test
    void objectFieldMatchesOnlyAbsentRuleThroughSdk() {
        try (EventBridgeClient events = AwsTestClients.create(EventBridgeClient.builder(), ACCOUNT, Region.US_EAST_1);
                SqsClient sqs = AwsTestClients.create(SqsClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            assertFalse(events.testEventPattern(request -> request
                    .eventPattern(PRESENT_PATTERN).event(OBJECT_EVENT)).result());
            assertTrue(events.testEventPattern(request -> request
                    .eventPattern(ABSENT_PATTERN).event(OBJECT_EVENT)).result());

            String name = "exists-leaf-" + UUID.randomUUID();
            String queueUrl = sqs.createQueue(request -> request.queueName(name)).queueUrl();
            boolean ruleCreated = false;
            try {
                String queueArn = sqs.getQueueAttributes(request -> request.queueUrl(queueUrl)
                        .attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);
                events.putRule(request -> request.name(name).eventPattern(PRESENT_PATTERN));
                ruleCreated = true;
                events.putTargets(request -> request.rule(name).targets(target -> target.id("q").arn(queueArn)));
                events.putEvents(request -> request.entries(entry -> entry
                        .source("example.test").detailType("Example")
                        .detail("{\"status\":{\"nested\":\"v\"}}")));
                assertTrue(sqs.receiveMessage(request -> request.queueUrl(queueUrl)
                        .waitTimeSeconds(1)).messages().isEmpty());

                events.putRule(request -> request.name(name).eventPattern(ABSENT_PATTERN));
                events.putEvents(request -> request.entries(entry -> entry
                        .source("example.test").detailType("Example")
                        .detail("{\"status\":{\"nested\":\"v\"}}")));
                assertEquals(1, sqs.receiveMessage(request -> request.queueUrl(queueUrl)
                        .waitTimeSeconds(5)).messages().size());
            } finally {
                try {
                    if (ruleCreated) {
                        events.removeTargets(request -> request.rule(name).ids("q"));
                    }
                } finally {
                    try {
                        if (ruleCreated) {
                            events.deleteRule(request -> request.name(name));
                        }
                    } finally {
                        sqs.deleteQueue(request -> request.queueUrl(queueUrl));
                    }
                }
            }
        }
    }
}
