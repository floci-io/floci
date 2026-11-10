package io.floci.aws.services.lambda;

import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.lambda.model.EventSourceMapping;
import io.floci.aws.services.lambda.model.InvokeResult;
import io.floci.aws.services.sns.SnsService;
import io.floci.aws.services.sqs.SqsService;
import io.floci.aws.testing.SqsTestQueue;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A poller thread carries no request context, so account-aware stores fall back to the default
 * account. A mapping outside it must still reach its SQS and SNS destinations, as the poller sends
 * from such a thread. The test thread has no request context either.
 */
@QuarkusTest
class StreamBatchFailuresAccountIntegrationTest {

    private static final String ACCOUNT = "111122223333";
    private static final String REGION = "us-east-1";
    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(5);
    private static final StreamBatchFailures.DiscardedBatch BATCH = new StreamBatchFailures.DiscardedBatch(
            "DDBStreamBatchInfo", "shardId-000000000000", "s1", "s1",
            "2026-10-20T12:07:26Z", "2026-10-20T12:07:26Z", 1,
            "arn:aws:dynamodb:us-east-1:111122223333:table/t/stream/2026-08-01T00:00:00.000", () -> "{\"Records\":[]}");

    @Inject
    StreamBatchFailures failures;

    @Inject
    SqsService sqsService;

    @Inject
    SnsService snsService;

    @Test
    void sqsDestinationOutsideTheDefaultAccountGetsTheRecord() throws Exception {
        SqsTestQueue queue = SqsTestQueue.create(sqsService, ACCOUNT, REGION, "esm-failures-");

        failures.sendToOnFailureDestination(mappingTo(queue.arn()), BATCH, new InvokeResult(), 1,
                StreamBatchFailures.RETRY_ATTEMPTS_EXHAUSTED);

        assertEquals("RetryAttemptsExhausted",
                queue.awaitJson(DELIVERY_TIMEOUT).at("/requestContext/condition").asText());
    }

    @Test
    void snsDestinationOutsideTheDefaultAccountGetsTheRecord() throws Exception {
        SqsTestQueue queue = SqsTestQueue.create(sqsService, ACCOUNT, REGION, "esm-failures-");
        String topicArn = RequestScopes.callAs(ACCOUNT, () -> snsService.createTopic(
                "esm-failures-" + UUID.randomUUID().toString().substring(0, 8), Map.of(), null, REGION).getTopicArn());
        RequestScopes.runAs(ACCOUNT, () -> snsService.subscribe(topicArn, "sqs", queue.arn(), REGION,
                Map.of("RawMessageDelivery", "true")));

        failures.sendToOnFailureDestination(mappingTo(topicArn), BATCH, new InvokeResult(), 1,
                StreamBatchFailures.RETRY_ATTEMPTS_EXHAUSTED);

        assertEquals("RetryAttemptsExhausted",
                queue.awaitJson(DELIVERY_TIMEOUT).at("/requestContext/condition").asText());
    }

    private static EventSourceMapping mappingTo(String destinationArn) {
        EventSourceMapping esm = new EventSourceMapping();
        esm.setUuid("esm-1");
        esm.setAccountId(ACCOUNT);
        esm.setRegion(REGION);
        esm.setEventSourceArn(BATCH.streamArn());
        EventSourceMapping.OnFailure onFailure = new EventSourceMapping.OnFailure();
        onFailure.setDestination(destinationArn);
        EventSourceMapping.DestinationConfig destinationConfig = new EventSourceMapping.DestinationConfig();
        destinationConfig.setOnFailure(onFailure);
        esm.setDestinationConfig(destinationConfig);
        return esm;
    }
}
