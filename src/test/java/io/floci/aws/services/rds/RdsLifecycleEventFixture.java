package io.floci.aws.services.rds;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.AwsTestClients;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.DeleteRuleRequest;
import software.amazon.awssdk.services.eventbridge.model.PutRuleRequest;
import software.amazon.awssdk.services.eventbridge.model.PutTargetsRequest;
import software.amazon.awssdk.services.eventbridge.model.RemoveTargetsRequest;
import software.amazon.awssdk.services.eventbridge.model.Target;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Owns the SDK clients and EventBridge-to-SQS capture resources for one lifecycle test. */
final class RdsLifecycleEventFixture implements AutoCloseable {

    final String name;
    final String account;
    final Region region;
    final RdsClient rds;

    private final ObjectMapper mapper = new ObjectMapper();
    private final EventBridgeClient eventBridge;
    private final SqsClient sqs;
    private final String queueUrl;
    private boolean ruleCreated;

    RdsLifecycleEventFixture(String account, Region region, String name, String pattern) {
        this(account, region, name, pattern,
                () -> AwsTestClients.create(RdsClient.builder(), account, region),
                () -> AwsTestClients.create(EventBridgeClient.builder(), account, region),
                () -> AwsTestClients.create(SqsClient.builder(), account, region));
    }

    RdsLifecycleEventFixture(String account, Region region, String name, String pattern,
                             Supplier<RdsClient> rdsFactory, Supplier<EventBridgeClient> eventsFactory,
                             Supplier<SqsClient> sqsFactory) {
        this.name = name;
        this.account = account;
        this.region = region;
        try {
            rds = rdsFactory.get();
            eventBridge = eventsFactory.get();
            sqs = sqsFactory.get();
            queueUrl = sqs.createQueue(CreateQueueRequest.builder().queueName(name).build()).queueUrl();
            String queueArn = sqs.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(queueUrl)
                    .attributeNames(QueueAttributeName.QUEUE_ARN).build()).attributes().get(QueueAttributeName.QUEUE_ARN);
            eventBridge.putRule(PutRuleRequest.builder().name(name).eventPattern(pattern).build());
            ruleCreated = true;
            eventBridge.putTargets(PutTargetsRequest.builder().rule(name)
                    .targets(Target.builder().id("queue").arn(queueArn).build()).build());
        } catch (RuntimeException | Error failure) {
            try {
                close();
            } catch (RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != failure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    List<JsonNode> receive() throws Exception {
        List<JsonNode> events = new ArrayList<>();
        for (Message message : sqs.receiveMessage(r -> r.queueUrl(queueUrl).maxNumberOfMessages(10)).messages()) {
            events.add(mapper.readTree(message.body()));
            sqs.deleteMessage(r -> r.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
        }
        return events;
    }

    @Override
    public void close() {
        // Null resources are possible when construction fails; try-with-resources also closes
        // every client if resource cleanup or another client's close throws.
        try (RdsClient database = rds; EventBridgeClient events = eventBridge; SqsClient queues = sqs) {
            try {
                if (ruleCreated) {
                    events.removeTargets(RemoveTargetsRequest.builder().rule(name).ids("queue").build());
                }
            } finally {
                try {
                    if (ruleCreated) {
                        events.deleteRule(DeleteRuleRequest.builder().name(name).build());
                    }
                } finally {
                    if (queueUrl != null) {
                        queues.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build());
                    }
                }
            }
        }
    }
}
