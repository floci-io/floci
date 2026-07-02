package io.github.hectorvent.floci.services.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.ecs.model.ContainerOverride;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.scheduler.model.EventBridgeParameters;
import io.github.hectorvent.floci.services.scheduler.model.AwsVpcConfiguration;
import io.github.hectorvent.floci.services.scheduler.model.EcsParameters;
import io.github.hectorvent.floci.services.scheduler.model.NetworkConfiguration;
import io.github.hectorvent.floci.services.scheduler.model.SqsParameters;
import io.github.hectorvent.floci.services.scheduler.model.Target;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduleInvokerTest {

    private static final String TOPIC_ARN = "arn:aws:sns:us-east-1:000000000000:repro-topic";

    private SqsService sqsService;
    private LambdaService lambdaService;
    private SnsService snsService;
    private EventBridgeService eventBridgeService;
    private EcsService ecsService;
    private ScheduleInvoker invoker;

    @BeforeEach
    void setUp() {
        sqsService = mock(SqsService.class);
        lambdaService = mock(LambdaService.class);
        snsService = mock(SnsService.class);
        eventBridgeService = mock(EventBridgeService.class);
        ecsService = mock(EcsService.class);
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.baseUrl()).thenReturn("http://localhost:4566");
        invoker = new ScheduleInvoker(sqsService, lambdaService, snsService,
                eventBridgeService, ecsService, new ObjectMapper(), config);
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

        invoker.invoke(target, "us-east-1");

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
    void universalSnsPublishWithNoMessageAttributesPassesNull() {
        Target target = new Target();
        target.setArn("arn:aws:scheduler:::aws-sdk:sns:publish");
        target.setRoleArn("arn:aws:iam::000000000000:role/x");
        target.setInput("{\"TopicArn\":\"" + TOPIC_ARN + "\",\"Message\":\"hello\"}");

        invoker.invoke(target, "us-east-1");

        verify(snsService).publish(eq(TOPIC_ARN), isNull(), isNull(),
                eq("hello"), isNull(), isNull(),
                isNull(), isNull(), eq("us-east-1"));
    }

    @Test
    void universalSnsPublishReadsTopicArnAndMessageFromInput() {
        Target target = new Target();
        target.setArn("arn:aws:scheduler:::aws-sdk:sns:publish");
        target.setRoleArn("arn:aws:iam::000000000000:role/x");
        target.setInput("{\"TopicArn\":\"" + TOPIC_ARN + "\",\"Message\":\"scheduled-universal-target\"}");

        invoker.invoke(target, "us-east-1");

        // The real TopicArn from Input must be used, NOT the universal-target ARN.
        verify(snsService).publish(eq(TOPIC_ARN), isNull(), isNull(),
                eq("scheduled-universal-target"), isNull(), isNull(),
                isNull(), isNull(), eq("us-east-1"));
        verify(snsService, never()).publish(eq("arn:aws:scheduler:::aws-sdk:sns:publish"),
                any(), any(), any(), any());
    }

    @Test
    void universalSqsSendMessageReadsQueueUrlBodyAndGroupIdFromInput() {
        Target target = new Target();
        target.setArn("arn:aws:scheduler:::aws-sdk:sqs:sendMessage");
        target.setRoleArn("arn:aws:iam::000000000000:role/x");
        target.setInput("{\"QueueUrl\":\"http://localhost:4566/000000000000/q.fifo\","
                + "\"MessageBody\":\"hi\",\"MessageGroupId\":\"g1\"}");

        invoker.invoke(target, "us-east-1");

        verify(sqsService).sendMessage(eq("http://localhost:4566/000000000000/q.fifo"),
                eq("hi"), eq(0), eq("g1"), isNull(), eq("us-east-1"));
    }

    @Test
    void templatedFifoSqsHonorsSqsParametersMessageGroupId() {
        Target target = new Target();
        target.setArn("arn:aws:sqs:us-east-1:000000000000:test.fifo");
        target.setInput("{\"hello\":\"world\"}");
        target.setSqsParameters(new SqsParameters("group-7"));

        invoker.invoke(target, "us-east-1");

        verify(sqsService).sendMessage(anyString(), eq("{\"hello\":\"world\"}"), eq(0),
                eq("group-7"), isNull(), eq("us-east-1"));
    }

    @Test
    void directSnsTopicArnStillDelivers() {
        Target target = new Target();
        target.setArn(TOPIC_ARN);
        target.setInput("{\"hello\":\"world\"}");

        invoker.invoke(target, "us-east-1");

        verify(snsService).publish(eq(TOPIC_ARN), isNull(), eq("{\"hello\":\"world\"}"),
                eq("Scheduler"), eq("us-east-1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void eventBusTargetUsesDeclaredDetailTypeAndSource() {
        Target target = new Target();
        target.setArn("arn:aws:events:us-east-1:000000000000:event-bus/my-bus");
        target.setInput("{\"hello\":\"world\"}");
        target.setEventBridgeParameters(new EventBridgeParameters("Order Placed", "my.app"));

        invoker.invoke(target, "us-east-1");

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

        invoker.invoke(target, "us-east-1");

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

        invoker.invoke(target, "us-west-2");

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

        invoker.invoke(target, "us-east-1");

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
    void unsupportedUniversalActionDoesNotThrowOrDispatch() {
        Target target = new Target();
        target.setArn("arn:aws:scheduler:::aws-sdk:dynamodb:putItem");
        target.setInput("{}");

        invoker.invoke(target, "us-east-1");

        verify(sqsService, never()).sendMessage(anyString(), anyString(), anyInt(), anyString(), any(), anyString());
        verify(snsService, never()).publish(anyString(), any(), anyString(), anyString(), anyString());
    }
}
