package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.github.hectorvent.floci.services.sqs.model.Queue;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class LambdaDeadLetterQueueIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String OTHER_ACCOUNT = "111122223333";

    @Inject
    LambdaService lambdaService;

    @Inject
    SqsService sqsService;

    @Inject
    AsyncInvokeDestinationRouter destinationRouter;

    @Test
    void failedInvocation_deliversPayloadAndAttributesToSqsDeadLetterQueue() {
        String queueName = "dlq-integ-queue";
        String functionName = "dlq-integ-fn";

        Queue queue = RequestScopes.callAs(ACCOUNT, () ->
                sqsService.createQueue(queueName, Map.of(), REGION));
        String queueUrl = queue.getQueueUrl();
        String queueArn = RequestScopes.callAs(ACCOUNT, () ->
                sqsService.getQueueAttributes(queueUrl, List.of("QueueArn"), REGION).get("QueueArn"));

        LambdaFunction fn = RequestScopes.callAs(ACCOUNT, () ->
                lambdaService.createFunction(REGION, new HashMap<>(Map.of(
                        "FunctionName", functionName,
                        "Runtime", "nodejs22.x",
                        "Role", "arn:aws:iam::" + ACCOUNT + ":role/test-role",
                        "Handler", "index.handler",
                        "DeadLetterConfig", Map.of("TargetArn", queueArn)))));

        assertEquals(queueArn, fn.getDeadLetterTargetArn());

        byte[] requestPayload = "{\"probe\":1,\"message\":\"hello\"}".getBytes();
        InvokeResult failureResult = new InvokeResult(200, "Unhandled",
                "{\"errorMessage\":\"always fails\",\"errorType\":\"Error\"}".getBytes(),
                null, "test-req-123");

        destinationRouter.route(fn, requestPayload, failureResult, 0);

        List<Message> messages = RequestScopes.callAs(ACCOUNT, () ->
                sqsService.receiveMessage(queueUrl, 10, 30, 0, REGION));

        assertEquals(1, messages.size(), "DLQ should receive exactly 1 message");
        Message message = messages.get(0);
        assertEquals("{\"probe\":1,\"message\":\"hello\"}", message.getBody());
        assertNotNull(message.getMessageAttributes());
        assertThat(message.getMessageAttributes().get("RequestID").getStringValue(), equalTo("test-req-123"));
        assertThat(message.getMessageAttributes().get("RequestID").getDataType(), equalTo("String"));
        assertThat(message.getMessageAttributes().get("ErrorCode").getStringValue(), equalTo("200"));
        assertThat(message.getMessageAttributes().get("ErrorCode").getDataType(), equalTo("Number"));
        assertThat(message.getMessageAttributes().get("ErrorMessage").getStringValue(), equalTo("always fails"));
        assertThat(message.getMessageAttributes().get("ErrorMessage").getDataType(), equalTo("String"));
    }

    @Test
    void failedInvocation_deliversToDeadLetterQueueInTheTargetAccount() {
        String queueName = "dlq-cross-account-queue";
        String functionName = "dlq-cross-account-fn";

        Queue queue = RequestScopes.callAs(OTHER_ACCOUNT, () ->
                sqsService.createQueue(queueName, Map.of(), REGION));
        String queueArn = RequestScopes.callAs(OTHER_ACCOUNT, () ->
                sqsService.getQueueAttributes(queue.getQueueUrl(), List.of("QueueArn"), REGION).get("QueueArn"));

        LambdaFunction fn = RequestScopes.callAs(ACCOUNT, () ->
                lambdaService.createFunction(REGION, new HashMap<>(Map.of(
                        "FunctionName", functionName,
                        "Runtime", "nodejs22.x",
                        "Role", "arn:aws:iam::" + ACCOUNT + ":role/test-role",
                        "Handler", "index.handler",
                        "DeadLetterConfig", Map.of("TargetArn", queueArn)))));
        InvokeResult failureResult = new InvokeResult(200, "Unhandled",
                "{\"errorMessage\":\"always fails\"}".getBytes(), null, "test-cross-account-req");

        destinationRouter.route(fn, "{\"probe\":1}".getBytes(), failureResult, 0);

        List<Message> messages = RequestScopes.callAs(OTHER_ACCOUNT, () ->
                sqsService.receiveMessage(queue.getQueueUrl(), 10, 0, 0, REGION));
        assertEquals(1, messages.size(), "the DLQ should receive the failed invocation in its owning account");
        assertEquals("{\"probe\":1}", messages.get(0).getBody());
    }
}
