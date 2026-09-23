package io.github.hectorvent.floci.services.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.ecs.model.ContainerOverride;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.scheduler.model.EventBridgeParameters;
import io.github.hectorvent.floci.services.scheduler.model.AwsVpcConfiguration;
import io.github.hectorvent.floci.services.scheduler.model.EcsParameters;
import io.github.hectorvent.floci.services.scheduler.model.NetworkConfiguration;
import io.github.hectorvent.floci.services.scheduler.model.Schedule;
import io.github.hectorvent.floci.services.scheduler.model.SqsParameters;
import io.github.hectorvent.floci.services.scheduler.model.Target;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue;
import io.github.hectorvent.floci.services.stepfunctions.StepFunctionsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ScheduleInvokerTest {

    private static final String TOPIC_ARN = "arn:aws:sns:us-east-1:000000000000:repro-topic";
    private static final Instant SCHEDULED_AT = Instant.parse("2026-04-21T09:17:54Z");

    private SqsService sqsService;
    private LambdaService lambdaService;
    private SnsService snsService;
    private EventBridgeService eventBridgeService;
    private EcsService ecsService;
    private StepFunctionsService stepFunctionsService;
    private ScheduleInvoker invoker;

    @BeforeEach
    void setUp() {
        sqsService = mock(SqsService.class);
        lambdaService = mock(LambdaService.class);
        snsService = mock(SnsService.class);
        eventBridgeService = mock(EventBridgeService.class);
        ecsService = mock(EcsService.class);
        stepFunctionsService = mock(StepFunctionsService.class);
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.baseUrl()).thenReturn("http://localhost:4566");
        invoker = new ScheduleInvoker(sqsService, lambdaService, snsService,
                eventBridgeService, ecsService, stepFunctionsService, new ObjectMapper(), config);
    }

    /** Delivers one occurrence of a schedule in {@code region} whose target is {@code target}. */
    private String invoke(Target target, String region) {
        return invoker.invoke(scheduleIn(region, target), SCHEDULED_AT);
    }

    private String materializeRequest(Target target, String region) {
        return invoker.materializeRequest(scheduleIn(region, target), SCHEDULED_AT);
    }

    private static Schedule scheduleIn(String region, Target target) {
        Schedule schedule = new Schedule();
        schedule.setName("test-schedule");
        schedule.setGroupName("default");
        schedule.setArn("arn:aws:scheduler:" + region + ":000000000000:schedule/default/test-schedule");
        schedule.setTarget(target);
        return schedule;
    }

    @Test
    void universalSnsPublishForwardsMessageAttributes() {
        Target target = new Target();
        target.setArn("arn:aws:scheduler:::aws-sdk:sns:publish");
        target.setRoleArn("arn:aws:iam::000000000000:role/x");
        target.setInput("{\"TopicArn\":\"" + TOPIC_ARN + "\","
                + "\"Message\":\"{}\","
                + "\"Subject\":\"my-subject\","
                + "\"MessageAttributes\":{"
                + "\"EventName\":{\"DataType\":\"String\",\"StringValue\":\"my-subject\"}"
                + "}}");

        invoke(target, "us-east-1");

        verify(snsService).publish(
                eq(TOPIC_ARN), isNull(), isNull(),
                eq("{}"), eq("my-subject"),
                argThat((Map<String, MessageAttributeValue> attrs) ->
                        attrs != null
                        && attrs.containsKey("EventName")
                        && "my-subject".equals(attrs.get("EventName").getStringValue())
                        && "String".equals(attrs.get("EventName").getDataType())),
                isNull(), isNull(), eq("us-east-1"));
    }

    @Test
    void templatedTargetWithoutInputDeliversTheDefaultScheduledEvent() throws Exception {
        // Repro from floci-io/floci#3951: a templated target created without Input must receive
        // Scheduler's default notification, not "{}".
        Target target = new Target();
        target.setArn("arn:aws:sqs:eu-central-1:000000000000:no-input");
        target.setRoleArn("arn:aws:iam::000000000000:role/scheduler-role");

        invoker.invoke(scheduleIn("eu-central-1", target), Instant.parse("2024-11-07T22:05:00Z"));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sqsService).sendMessage(eq("http://localhost:4566/000000000000/no-input"), body.capture(),
                eq(0), isNull(), isNull(), eq("eu-central-1"));
        assertDefaultScheduledEvent(body.getValue(), "eu-central-1", "2024-11-07T22:05:00Z");
    }

    /**
     * Asserts that {@code delivered} is the default notification Scheduler sends when a templated
     * target has no Input. The expected shape is the event captured from a real invocation in
     * aws/aws-lambda-dotnet#1864 (note {@code detail} is the string "{}"); only the id varies.
     */
    private static void assertDefaultScheduledEvent(String delivered, String region, String time) throws Exception {
        String exampleId = "49672d39-8c3a-4cc6-8de5-c57b0acf718f";
        String id = new ObjectMapper().readTree(delivered).path("id").asText();
        assertEquals("{\"version\":\"0\",\"id\":\"" + exampleId + "\","
                + "\"detail-type\":\"Scheduled Event\",\"source\":\"aws.scheduler\","
                + "\"account\":\"000000000000\",\"time\":\"" + time + "\",\"region\":\"" + region + "\","
                + "\"resources\":[\"arn:aws:scheduler:" + region + ":000000000000:schedule/default/test-schedule\"],"
                + "\"detail\":\"{}\"}",
                id.isEmpty() ? delivered : delivered.replace(id, exampleId));
        assertEquals(id, UUID.fromString(id).toString(), "event id is a UUID");
    }

    @Test
    void materializesSqsRequestForDeadLetterBody() {
        Target target = new Target();
        target.setArn("arn:aws:sqs:us-east-1:000000000000:queue");
        target.setInput("payload");

        assertEquals("{\"MessageBody\":\"payload\",\"QueueUrl\":\"http://localhost:4566/000000000000/queue\"}",
                materializeRequest(target, "us-east-1"));
    }

    // The expected bodies below use the request field names of each target service's API
    // (Lambda Invoke, SNS Publish, Step Functions StartExecution, EventBridge PutEvents, ECS RunTask),
    // matching the SQS SendMessage example in the Scheduler dead-letter queue guide.

    @Test
    void materializesLambdaInvokeRequestForDeadLetterBody() {
        Target target = new Target();
        target.setArn("arn:aws:lambda:us-east-1:000000000000:function:my-func");
        target.setInput("{\"hello\":\"world\"}");

        assertEquals("{\"FunctionName\":\"arn:aws:lambda:us-east-1:000000000000:function:my-func\","
                + "\"InvocationType\":\"Event\",\"Payload\":\"{\\\"hello\\\":\\\"world\\\"}\"}",
                materializeRequest(target, "us-east-1"));
    }

    @Test
    void materializesSnsPublishRequestForDeadLetterBody() {
        Target target = new Target();
        target.setArn(TOPIC_ARN);
        target.setInput("plain text");

        assertEquals("{\"TopicArn\":\"" + TOPIC_ARN + "\",\"Message\":\"plain text\"}",
                materializeRequest(target, "us-east-1"));
    }

    @Test
    void materializesStepFunctionsStartExecutionRequestForDeadLetterBody() {
        Target target = new Target();
        target.setArn("arn:aws:states:eu-west-1:000000000000:stateMachine:scheduled-workflow");
        target.setInput("{\"order\":{\"id\":42}}");

        assertEquals("{\"stateMachineArn\":\"arn:aws:states:eu-west-1:000000000000:stateMachine:scheduled-workflow\","
                + "\"input\":\"{\\\"order\\\":{\\\"id\\\":42}}\"}",
                materializeRequest(target, "us-east-1"));
    }

    @Test
    void materializesEventBridgePutEventsRequestForDeadLetterBody() {
        Target target = new Target();
        target.setArn("arn:aws:events:us-east-1:000000000000:event-bus/my-bus");
        target.setInput("{\"hello\":\"world\"}");
        target.setEventBridgeParameters(new EventBridgeParameters("Order Placed", "my.app"));

        assertEquals("{\"Entries\":[{\"EventBusName\":\"my-bus\",\"Source\":\"my.app\","
                + "\"DetailType\":\"Order Placed\",\"Detail\":\"{\\\"hello\\\":\\\"world\\\"}\"}]}",
                materializeRequest(target, "us-east-1"));
    }

    @Test
    void materializesEcsRunTaskRequestForDeadLetterBody() {
        Target target = new Target();
        target.setArn("arn:aws:ecs:us-east-1:000000000000:cluster/proof");
        EcsParameters ecsParameters = new EcsParameters();
        ecsParameters.setTaskDefinitionArn("arn:aws:ecs:us-east-1:000000000000:task-definition/proof:1");
        ecsParameters.setLaunchType("FARGATE");
        ecsParameters.setGroup("batch-group");
        ecsParameters.setTaskCount(2);
        AwsVpcConfiguration vpc = new AwsVpcConfiguration();
        vpc.setSubnets(List.of("subnet-a", "subnet-b"));
        vpc.setSecurityGroups(List.of("sg-a"));
        vpc.setAssignPublicIp("DISABLED");
        NetworkConfiguration network = new NetworkConfiguration();
        network.setAwsvpcConfiguration(vpc);
        ecsParameters.setNetworkConfiguration(network);
        target.setEcsParameters(ecsParameters);

        assertEquals("{\"cluster\":\"arn:aws:ecs:us-east-1:000000000000:cluster/proof\","
                + "\"taskDefinition\":\"arn:aws:ecs:us-east-1:000000000000:task-definition/proof:1\","
                + "\"count\":2,\"launchType\":\"FARGATE\",\"group\":\"batch-group\","
                + "\"networkConfiguration\":{\"awsvpcConfiguration\":{\"subnets\":[\"subnet-a\",\"subnet-b\"],"
                + "\"securityGroups\":[\"sg-a\"],\"assignPublicIp\":\"DISABLED\"}}}",
                materializeRequest(target, "us-east-1"));
    }

    @Test
    void universalSnsPublishForwardsBinaryMessageAttributes() {
        Target target = new Target();
        target.setArn("arn:aws:scheduler:::aws-sdk:sns:publish");
        target.setRoleArn("arn:aws:iam::000000000000:role/x");
        // "aGVsbG8=" is base64 for "hello"
        target.setInput("{\"TopicArn\":\"" + TOPIC_ARN + "\","
                + "\"Message\":\"payload\","
                + "\"MessageAttributes\":{"
                + "\"BinAttr\":{\"DataType\":\"Binary\",\"BinaryValue\":\"aGVsbG8=\"}"
                + "}}");

        invoke(target, "us-east-1");

        verify(snsService).publish(
                eq(TOPIC_ARN), isNull(), isNull(),
                eq("payload"), isNull(),
                argThat((Map<String, MessageAttributeValue> attrs) ->
                        attrs != null
                        && attrs.containsKey("BinAttr")
                        && "Binary".equals(attrs.get("BinAttr").getDataType())
                        && java.util.Arrays.equals("hello".getBytes(), attrs.get("BinAttr").getBinaryValue())),
                isNull(), isNull(), eq("us-east-1"));
    }

    @Test
    void universalSnsPublishWithNoMessageAttributesPassesNullOrEmpty() {
        Target target = new Target();
        target.setArn("arn:aws:scheduler:::aws-sdk:sns:publish");
        target.setRoleArn("arn:aws:iam::000000000000:role/x");
        target.setInput("{\"TopicArn\":\"" + TOPIC_ARN + "\",\"Message\":\"hello\"}");

        invoke(target, "us-east-1");

        verify(snsService).publish(eq(TOPIC_ARN), isNull(), isNull(),
                eq("hello"), isNull(),
                argThat(attrs -> attrs == null || attrs.isEmpty()),
                isNull(), isNull(), eq("us-east-1"));
    }

    @Test
    void universalSnsPublishReadsTopicArnAndMessageFromInput() {
        Target target = new Target();
        target.setArn("arn:aws:scheduler:::aws-sdk:sns:publish");
        target.setRoleArn("arn:aws:iam::000000000000:role/x");
        target.setInput("{\"TopicArn\":\"" + TOPIC_ARN + "\",\"Message\":\"scheduled-universal-target\"}");

        invoke(target, "us-east-1");

        // The real TopicArn from Input must be used, NOT the universal-target ARN.
        verify(snsService).publish(eq(TOPIC_ARN), isNull(), isNull(),
                eq("scheduled-universal-target"), isNull(),
                argThat(attrs -> attrs == null || attrs.isEmpty()),
                isNull(), isNull(), eq("us-east-1"));
        verify(snsService, never()).publish(eq("arn:aws:scheduler:::aws-sdk:sns:publish"),
                any(), any(), any(), any());
    }

    @Test
    void universalSqsSendMessageReadsQueueUrlBodyAndFifoIdsFromInputWithoutAttributes() {
        Target target = new Target();
        target.setArn("arn:aws:scheduler:::aws-sdk:sqs:sendMessage");
        target.setRoleArn("arn:aws:iam::000000000000:role/x");
        target.setInput("{\"QueueUrl\":\"http://localhost:4566/000000000000/q.fifo\","
                + "\"MessageBody\":\"hi\",\"MessageGroupId\":\"g1\","
                + "\"MessageDeduplicationId\":\"dedup-1\"}");

        invoke(target, "us-east-1");

        verify(sqsService).sendMessage(eq("http://localhost:4566/000000000000/q.fifo"),
                eq("hi"), eq(0), eq("g1"), eq("dedup-1"),
                argThat(attrs -> attrs == null || attrs.isEmpty()), eq("us-east-1"));
    }

    @Test
    void universalSqsSendMessageForwardsStringAndBase64BinaryMessageAttributes() {
        String queueUrl = "http://localhost:4566/000000000000/q.fifo";
        String binaryValueBase64 = "aGVsbG8=";
        Target target = new Target();
        target.setArn("arn:aws:scheduler:::aws-sdk:sqs:sendMessage");
        target.setRoleArn("arn:aws:iam::000000000000:role/x");
        target.setInput("{\"QueueUrl\":\"" + queueUrl + "\","
                + "\"MessageBody\":\"hi\","
                + "\"MessageGroupId\":\"g1\","
                + "\"MessageDeduplicationId\":\"dedup-1\","
                + "\"MessageAttributes\":{"
                + "\"StringAttr\":{\"DataType\":\"String.Custom\",\"StringValue\":\"value\"},"
                + "\"BinaryAttr\":{\"DataType\":\"Binary.Custom\",\"BinaryValue\":\""
                + binaryValueBase64 + "\"}}}");

        invoke(target, "us-east-1");

        ArgumentCaptor<Map<String, MessageAttributeValue>> attributesCaptor = ArgumentCaptor.captor();
        verify(sqsService).sendMessage(eq(queueUrl), eq("hi"), eq(0), eq("g1"), eq("dedup-1"),
                attributesCaptor.capture(), eq("us-east-1"));

        Map<String, MessageAttributeValue> attributes = attributesCaptor.getValue();
        assertEquals(2, attributes.size());

        MessageAttributeValue stringAttribute = attributes.get("StringAttr");
        assertNotNull(stringAttribute);
        assertEquals("String.Custom", stringAttribute.getDataType());
        assertEquals("value", stringAttribute.getStringValue());
        assertNull(stringAttribute.getBinaryValue());

        MessageAttributeValue binaryAttribute = attributes.get("BinaryAttr");
        assertNotNull(binaryAttribute);
        assertEquals("Binary.Custom", binaryAttribute.getDataType());
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), binaryAttribute.getBinaryValue());
        assertNull(binaryAttribute.getStringValue());
    }

    @Test
    void universalSqsSendMessageSkipsAttributesWithoutDataType() {
        String queueUrl = "http://localhost:4566/000000000000/q.fifo";
        Target target = new Target();
        target.setArn("arn:aws:scheduler:::aws-sdk:sqs:sendMessage");
        target.setRoleArn("arn:aws:iam::000000000000:role/x");
        target.setInput("{\"QueueUrl\":\"" + queueUrl + "\","
                + "\"MessageBody\":\"hi\","
                + "\"MessageGroupId\":\"g1\","
                + "\"MessageDeduplicationId\":\"dedup-1\","
                + "\"MessageAttributes\":{"
                + "\"MissingType\":{\"StringValue\":\"ignored\"},"
                + "\"Valid\":{\"DataType\":\"String\",\"StringValue\":\"value\"}"
                + "}}");

        invoke(target, "us-east-1");

        ArgumentCaptor<Map<String, MessageAttributeValue>> attributesCaptor = ArgumentCaptor.captor();
        verify(sqsService).sendMessage(eq(queueUrl), eq("hi"), eq(0), eq("g1"), eq("dedup-1"),
                attributesCaptor.capture(), eq("us-east-1"));

        Map<String, MessageAttributeValue> attributes = attributesCaptor.getValue();
        assertEquals(1, attributes.size());
        assertEquals("value", attributes.get("Valid").getStringValue());
    }

    @Test
    void templatedFifoSqsHonorsSqsParametersMessageGroupId() {
        Target target = new Target();
        target.setArn("arn:aws:sqs:us-east-1:000000000000:test.fifo");
        target.setInput("{\"hello\":\"world\"}");
        target.setSqsParameters(new SqsParameters("group-7"));

        invoke(target, "us-east-1");

        verify(sqsService).sendMessage(anyString(), eq("{\"hello\":\"world\"}"), eq(0),
                eq("group-7"), isNull(), eq("us-east-1"));
    }

    @Test
    void lambdaTargetPreservesArnAccount() {
        String arn = "arn:aws:lambda:ap-south-1:100000000012:function:cross-account-function";
        Target target = new Target();
        target.setArn(arn);
        target.setInput("{\"detail\":{\"job\":\"workflow-recovery\"}}");

        invoke(target, "ap-south-1");

        verify(lambdaService).invokeArn(
                eq(arn),
                org.mockito.AdditionalMatchers.aryEq(
                        "{\"detail\":{\"job\":\"workflow-recovery\"}}".getBytes()),
                eq(io.github.hectorvent.floci.services.lambda.model.InvocationType.Event));
    }

    @Test
    void directSnsTopicArnStillDelivers() {
        Target target = new Target();
        target.setArn(TOPIC_ARN);
        target.setInput("{\"hello\":\"world\"}");

        invoke(target, "us-east-1");

        verify(snsService).publish(eq(TOPIC_ARN), isNull(), eq("{\"hello\":\"world\"}"),
                eq("Scheduler"), eq("us-east-1"));
    }

    @Test
    void stateMachineTargetStartsExecutionWithInputAndArnRegion() {
        String stateMachineArn = "arn:aws:states:eu-west-1:000000000000:stateMachine:scheduled-workflow";
        String input = "{\"order\":{\"id\":42}}";
        Target target = new Target();
        target.setArn(stateMachineArn);
        target.setRoleArn("arn:aws:iam::000000000000:role/scheduler-role");
        target.setInput(input);

        invoke(target, "us-east-1");

        verify(stepFunctionsService).startExecution(stateMachineArn, null, input, "eu-west-1");
    }

    @Test
    void stateMachineTargetWithoutInputStartsExecutionWithTheDefaultScheduledEvent() throws Exception {
        String stateMachineArn = "arn:aws:states:us-east-1:000000000000:stateMachine:scheduled-workflow";
        Target target = new Target();
        target.setArn(stateMachineArn);

        invoke(target, "us-east-1");

        ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
        verify(stepFunctionsService).startExecution(eq(stateMachineArn), isNull(), input.capture(), eq("us-east-1"));
        assertDefaultScheduledEvent(input.getValue(), "us-east-1", "2026-04-21T09:17:54Z");
    }

    @Test
    void stateMachineStartFailurePropagatesToSchedulerDeliveryHandling() {
        String stateMachineArn = "arn:aws:states:us-east-1:000000000000:stateMachine:missing";
        Target target = new Target();
        target.setArn(stateMachineArn);
        target.setInput("{}");
        when(stepFunctionsService.startExecution(stateMachineArn, null, "{}", "us-east-1"))
                .thenThrow(new AwsException("StateMachineDoesNotExist", "State machine does not exist", 400));

        AwsException error = assertThrows(AwsException.class,
                () -> invoke(target, "us-east-1"));

        assertEquals("StateMachineDoesNotExist", error.getErrorCode());
    }

    @Test
    void qualifiedStateMachineTargetRemainsUnsupported() {
        Target target = new Target();
        target.setArn("arn:aws:states:us-east-1:000000000000:stateMachine:scheduled-workflow:PROD");

        assertThrows(UnsupportedOperationException.class,
                () -> invoke(target, "us-east-1"));
        verifyNoInteractions(stepFunctionsService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void eventBusTargetUsesDeclaredDetailTypeAndSource() {
        Target target = new Target();
        target.setArn("arn:aws:events:us-east-1:000000000000:event-bus/my-bus");
        target.setInput("{\"hello\":\"world\"}");
        target.setEventBridgeParameters(new EventBridgeParameters("Order Placed", "my.app"));

        invoke(target, "us-east-1");

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(eventBridgeService).putEvents(captor.capture(), eq("us-east-1"));
        Map<String, Object> entry = captor.getValue().get(0);
        assertEquals("my-bus", entry.get("EventBusName"));
        assertEquals("my.app", entry.get("Source"));
        assertEquals("Order Placed", entry.get("DetailType"));
        assertEquals("{\"hello\":\"world\"}", entry.get("Detail"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void eventBusTargetWithoutParametersFallsBackToDefaults() {
        Target target = new Target();
        target.setArn("arn:aws:events:us-east-1:000000000000:event-bus/my-bus");
        target.setInput("{\"hello\":\"world\"}");

        invoke(target, "us-east-1");

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(eventBridgeService).putEvents(captor.capture(), eq("us-east-1"));
        Map<String, Object> entry = captor.getValue().get(0);
        assertEquals("aws.scheduler", entry.get("Source"));
        assertEquals("Scheduled Event", entry.get("DetailType"));
    }

    @Test
    void ecsSchedulerTargetRunsTaskWithNetworkConfiguration() {
        Target target = new Target();
        target.setArn("arn:aws:ecs:us-east-1:000000000000:cluster/proof");
        EcsParameters ecsParameters = new EcsParameters();
        ecsParameters.setTaskDefinitionArn("arn:aws:ecs:us-east-1:000000000000:task-definition/proof:1");
        ecsParameters.setLaunchType("FARGATE");
        ecsParameters.setGroup("batch-group");
        ecsParameters.setTaskCount(2);
        AwsVpcConfiguration vpc = new AwsVpcConfiguration();
        vpc.setSubnets(List.of("subnet-a", "subnet-b"));
        vpc.setSecurityGroups(List.of("sg-a"));
        vpc.setAssignPublicIp("DISABLED");
        NetworkConfiguration network = new NetworkConfiguration();
        network.setAwsvpcConfiguration(vpc);
        ecsParameters.setNetworkConfiguration(network);
        target.setEcsParameters(ecsParameters);

        invoke(target, "us-west-2");

        ArgumentCaptor<io.github.hectorvent.floci.services.ecs.model.NetworkConfiguration> networkCaptor =
                ArgumentCaptor.forClass(io.github.hectorvent.floci.services.ecs.model.NetworkConfiguration.class);
        verify(ecsService).runTask(
                eq("arn:aws:ecs:us-east-1:000000000000:cluster/proof"),
                eq("arn:aws:ecs:us-east-1:000000000000:task-definition/proof:1"),
                eq(2),
                eq(LaunchType.FARGATE),
                isNull(),
                eq("batch-group"),
                eq(List.<ContainerOverride>of()),
                networkCaptor.capture(),
                eq("us-east-1"));
        io.github.hectorvent.floci.services.ecs.model.AwsVpcConfiguration awsvpc =
                networkCaptor.getValue().getAwsvpcConfiguration();
        org.junit.jupiter.api.Assertions.assertEquals(List.of("subnet-a", "subnet-b"), awsvpc.getSubnets());
        org.junit.jupiter.api.Assertions.assertEquals(List.of("sg-a"), awsvpc.getSecurityGroups());
        org.junit.jupiter.api.Assertions.assertEquals("DISABLED", awsvpc.getAssignPublicIp());
    }

    @Test
    void ecsSchedulerTargetIgnoresUnsupportedLaunchType() {
        Target target = new Target();
        target.setArn("arn:aws:ecs:us-east-1:000000000000:cluster/proof");
        EcsParameters ecsParameters = new EcsParameters();
        ecsParameters.setTaskDefinitionArn("arn:aws:ecs:us-east-1:000000000000:task-definition/proof:1");
        ecsParameters.setLaunchType("UNKNOWN");
        target.setEcsParameters(ecsParameters);

        invoke(target, "us-east-1");

        verify(ecsService).runTask(
                eq("arn:aws:ecs:us-east-1:000000000000:cluster/proof"),
                eq("arn:aws:ecs:us-east-1:000000000000:task-definition/proof:1"),
                eq(1),
                isNull(),
                isNull(),
                eq("scheduler"),
                eq(List.<ContainerOverride>of()),
                isNull(),
                eq("us-east-1"));
    }

    @Test
    void unsupportedUniversalActionFailsWithoutDispatch() {
        Target target = new Target();
        target.setArn("arn:aws:scheduler:::aws-sdk:dynamodb:putItem");
        target.setInput("{}");

        assertThrows(UnsupportedOperationException.class, () -> invoke(target, "us-east-1"));

        verifyNoInteractions(sqsService, lambdaService, snsService, eventBridgeService, ecsService);
    }

    @Test
    void malformedUniversalInputFailsWithoutDispatch() {
        Target target = new Target();
        target.setArn("arn:aws:scheduler:::aws-sdk:sqs:sendMessage");
        target.setInput("{not-json");

        assertThrows(AwsException.class, () -> invoke(target, "us-east-1"));

        verifyNoInteractions(sqsService, lambdaService, snsService, eventBridgeService, ecsService);
    }

    @Test
    void unsupportedTargetArnFailsWithoutDispatch() {
        Target target = new Target();
        target.setArn("arn:aws:dynamodb:us-east-1:000000000000:table/orders");
        target.setInput("{}");

        assertThrows(UnsupportedOperationException.class, () -> invoke(target, "us-east-1"));

        verifyNoInteractions(sqsService, lambdaService, snsService, eventBridgeService, ecsService);
    }
}
