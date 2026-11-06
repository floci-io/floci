package io.floci.aws.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.stepfunctions.LambdaTaskAuthorizer.Permission;
import io.floci.aws.services.stepfunctions.model.StateMachine;
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
    private static final String TOPIC = "arn:aws:sns:eu-west-1:111122223333:orders";
    private static final String ENDPOINT = "arn:aws:sns:eu-west-1:111122223333:endpoint/GCM/app/id";

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
        assertEquals(new TaskPermissions("SQS.AccessDeniedException", List.of(new Permission("sqs:SendMessage",
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
        assertEquals("Sqs.AccessDeniedException", TaskPermissions.of(integration("aws-sdk:sqs:sendMessage"),
                input("orders"), machine("aws")).orElseThrow().deniedError());
    }

    /** SQS answers these with its own validation error once the role has passed. */
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"QueueUrl\":\"  \"}", "{\"QueueUrl\":7}"})
    void aRequestNamingNoQueueNeedsNoPermission(String request) throws Exception {
        assertEquals(new TaskPermissions("SQS.AccessDeniedException", List.of()),
                TaskPermissions.of(integration("sqs:sendMessage"), MAPPER.readTree(request), machine("aws")).orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"lambda:invoke", "apigateway:invoke", "aws-sdk:sqs:receiveMessage", "aws-sdk:sns:listTopics",
            "sns:publishBatch", "aws-sdk:eventbridge:putEvents", "events:listRules", "states:startExecution",
            "dynamodb:putItem", "http:invoke"})
    void integrationsNotMappedStayUnchecked(String integration) {
        assertEquals(Optional.empty(), TaskPermissions.of(integration(integration), input("orders"), machine("aws")));
    }

    @ParameterizedTest
    @CsvSource({"sns:publish,SNS.AuthorizationErrorException", "aws-sdk:sns:publish,Sns.AuthorizationErrorException"})
    void aPublishNeedsThePermissionOnItsTopicAndIsRefusedAsSnsRefuses(String integration, String deniedError) throws Exception {
        assertEquals(new TaskPermissions(deniedError, List.of(new Permission("sns:Publish", TOPIC))),
                TaskPermissions.of(integration(integration), json("{\"TopicArn\":\"" + TOPIC + "\"}"), machine("aws"))
                        .orElseThrow());
    }

    @Test
    void aPublishToATargetNeedsThePermissionOnTheTarget() throws Exception {
        assertEquals(List.of(new Permission("sns:Publish", ENDPOINT)), snsPermissions("{\"TargetArn\":\"" + ENDPOINT + "\"}"));
    }

    /** {@code SnsService.publish} takes the topic before the target, so that is what it sends to. */
    @Test
    void aPublishNamingBothNeedsThePermissionOnTheTopic() throws Exception {
        assertEquals(List.of(new Permission("sns:Publish", TOPIC)),
                snsPermissions("{\"TargetArn\":\"" + ENDPOINT + "\",\"TopicArn\":\"" + TOPIC + "\"}"));
        assertEquals(List.of(new Permission("sns:Publish", ENDPOINT)),
                snsPermissions("{\"TopicArn\":null,\"TargetArn\":\"" + ENDPOINT + "\"}"));
    }

    /** {@code SnsService.publish} takes a phone number before any ARN, so a topic in the request is not reached. */
    @ParameterizedTest
    @ValueSource(strings = {"{\"PhoneNumber\":\"+15555550100\"}",
            "{\"PhoneNumber\":\"+15555550100\",\"TopicArn\":\"" + TOPIC + "\"}"})
    void anSmsNeedsThePermissionOnAnyResource(String request) throws Exception {
        assertEquals(List.of(new Permission("sns:Publish", "*")), snsPermissions(request));
    }

    /** SNS answers these with its own error once the role has passed: none of them reaches a topic. */
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"TopicArn\":\"orders\"}", "{\"TopicArn\":\"\"}", "{\"TopicArn\":7}",
            "{\"TopicArn\":\"arn:aws:sns\"}", "{\"TopicArn\":\"arn:aws:sqs:eu-west-1:111122223333:orders\"}",
            "{\"TargetArn\":\"orders\"}", "{\"PhoneNumber\":null}",
            "{\"TopicArn\":\"orders\",\"TargetArn\":\"" + ENDPOINT + "\"}"})
    void aPublishNamingNoSnsResourceNeedsNoPermission(String request) throws Exception {
        assertEquals(List.of(), snsPermissions(request));
    }

    @Test
    void anEntryNamingNoBusNeedsThePermissionOnTheDefaultBus() throws Exception {
        assertEquals(new TaskPermissions("EventBridge.AccessDeniedException", List.of(new Permission("events:PutEvents", bus("default")))),
                TaskPermissions.of(integration("events:putEvents"), json("{\"Entries\":[{\"Source\":\"a\"}]}"),
                        machine("aws")).orElseThrow());
    }

    /** Each form reaches the bus of that name where the call runs, whichever account and Region an ARN names. */
    @ParameterizedTest
    @CsvSource({
            "orders,                                                orders",
            "arn:aws:events:eu-west-1:111122223333:event-bus/orders, orders",
            "arn:aws-cn:events:cn-north-1:444455556666:event-bus/orders, orders",
            "arn:aws:events:eu-west-1:111122223333:event-bus/default, default",
            "'',                                                    default",
            "'  ',                                                  default",
            "arn:aws:s3:::orders,                                   arn:aws:s3:::orders"})
    void aBusNameOrArnNamesTheBusItsLookupReaches(String busName, String reached) throws Exception {
        assertEquals(List.of(new Permission("events:PutEvents", bus(reached))),
                eventsPermissions(MAPPER.writeValueAsString(Map.of("Entries", List.of(Map.of("EventBusName", busName))))));
    }

    @Test
    void aNullOrNonTextBusIsReadAsTheHandlerReadsIt() throws Exception {
        assertEquals(List.of(new Permission("events:PutEvents", bus("default"))),
                eventsPermissions("{\"Entries\":[{\"EventBusName\":null},{\"EventBusName\":{\"a\":1}}]}"));
        assertEquals(List.of(new Permission("events:PutEvents", bus("7"))),
                eventsPermissions("{\"Entries\":[{\"EventBusName\":7}]}"));
    }

    @Test
    void entriesNeedOnePermissionForEachDistinctBusInOrder() throws Exception {
        assertEquals(List.of(new Permission("events:PutEvents", bus("b")), new Permission("events:PutEvents", bus("default")),
                        new Permission("events:PutEvents", bus("a"))),
                eventsPermissions("{\"Entries\":[{\"EventBusName\":\"b\"},{},{\"EventBusName\":\"a\"},"
                        + "{\"EventBusName\":\"" + bus("b") + "\"},{\"EventBusName\":\"default\"}]}"));
    }

    @Test
    void theBusIsInTheStateMachinesPartition() throws Exception {
        assertEquals(List.of(new Permission("events:PutEvents",
                        "arn:aws-cn:events:eu-west-1:" + ACCOUNT + ":event-bus/default")),
                TaskPermissions.of(integration("events:putEvents"), json("{\"Entries\":[{}]}"), machine("aws-cn"))
                        .orElseThrow().permissions());
    }

    /** A malformed member names no bus, so EventBridge answers it, and the others are still checked. */
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"Entries\":[]}", "{\"Entries\":{\"EventBusName\":\"a\"}}", "{\"Entries\":{\"x\":{\"EventBusName\":\"a\"}}}", "{\"Entries\":\"a\"}",
            "{\"Entries\":[7]}", "{\"Entries\":[[{\"EventBusName\":\"a\"}]]}",
            "{\"Entries\":[{\"EventBusName\":\"arn:aws:events:\"}]}",
            "{\"Entries\":[{\"EventBusName\":\"arn:aws:events:eu-west-1:111122223333:rule/orders\"}]}"})
    void entriesNamingNoBusNeedNoPermission(String request) throws Exception {
        assertEquals(List.of(), eventsPermissions(request));
    }

    @Test
    void aMalformedEntryLeavesTheOthersChecked() throws Exception {
        assertEquals(List.of(new Permission("events:PutEvents", bus("a"))),
                eventsPermissions("{\"Entries\":[7,{\"EventBusName\":\"arn:aws:events:\"},{\"EventBusName\":\"a\"}]}"));
    }

    private static List<Permission> snsPermissions(String request) throws Exception {
        return TaskPermissions.of(integration("sns:publish"), json(request), machine("aws")).orElseThrow().permissions();
    }

    private static List<Permission> eventsPermissions(String request) throws Exception {
        return TaskPermissions.of(integration("events:putEvents"), json(request), machine("aws")).orElseThrow().permissions();
    }

    private static String bus(String name) {
        return "arn:aws:events:eu-west-1:" + ACCOUNT + ":event-bus/" + name;
    }

    private static JsonNode json(String request) throws Exception {
        return MAPPER.readTree(request);
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
