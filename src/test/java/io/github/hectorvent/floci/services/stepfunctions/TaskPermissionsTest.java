package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.stepfunctions.LambdaTaskAuthorizer.Permission;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskPermissionsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "111122223333";

    /** Each form resolves in the state machine's Region, whichever host it names, as the queue lookup does. */
    @ParameterizedTest
    @CsvSource({
            "http://localhost:4566/111122223333/orders,                 111122223333",
            "https://sqs.ap-south-1.amazonaws.com/111122223333/orders,  111122223333",
            "http://localhost:4566/sqs/444455556666/orders,             444455556666",
            "orders,                                                    111122223333",
            "444455556666/orders,                                       444455556666",
            "http://localhost:4566/not-an-account/orders,               111122223333"})
    void aQueueUrlNamesTheQueueItsLookupReaches(String queueUrl, String account) {
        assertEquals(new TaskPermissions("SQS.", List.of(new Permission("sqs:SendMessage",
                        "arn:aws:sqs:eu-west-1:" + account + ":orders"))),
                TaskPermissions.of(integration("sqs:sendMessage"), input(queueUrl), machine("aws")).orElseThrow());
    }

    @Test
    void theQueueIsInTheStateMachinesPartition() {
        assertEquals(List.of(new Permission("sqs:SendMessage", "arn:aws-cn:sqs:eu-west-1:" + ACCOUNT + ":orders")),
                TaskPermissions.of(integration("sqs:sendMessage"), input("orders"), machine("aws-cn"))
                        .orElseThrow().permissions());
    }

    @Test
    void theSdkFormNamesItsOwnErrorPrefix() {
        assertEquals("Sqs.", TaskPermissions.of(integration("aws-sdk:sqs:sendMessage"), input("orders"), machine("aws"))
                .orElseThrow().errorPrefix());
    }

    /** SQS answers these with its own validation error once the role has passed. */
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"QueueUrl\":\"  \"}", "{\"QueueUrl\":7}"})
    void aRequestNamingNoQueueNeedsNoPermission(String request) throws Exception {
        assertEquals(new TaskPermissions("SQS.", List.of()),
                TaskPermissions.of(integration("sqs:sendMessage"), MAPPER.readTree(request), machine("aws")).orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"lambda:invoke", "apigateway:invoke", "sns:publish", "aws-sdk:sqs:receiveMessage",
            "states:startExecution", "dynamodb:putItem", "http:invoke"})
    void integrationsNotMappedStayUnchecked(String integration) {
        assertEquals(Optional.empty(), TaskPermissions.of(integration(integration), input("orders"), machine("aws")));
    }

    private static StatesIntegration integration(String tail) {
        return StatesIntegration.parse("arn:aws:states:::" + tail).orElseThrow();
    }

    private static JsonNode input(String queueUrl) {
        return MAPPER.valueToTree(Map.of("QueueUrl", queueUrl));
    }

    private static StateMachine machine(String partition) {
        StateMachine machine = new StateMachine();
        machine.setStateMachineArn("arn:" + partition + ":states:eu-west-1:" + ACCOUNT + ":stateMachine:m");
        return machine;
    }
}
