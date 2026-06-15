package io.github.hectorvent.floci.services.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.scheduler.model.SqsParameters;
import io.github.hectorvent.floci.services.scheduler.model.Target;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
    private ScheduleInvoker invoker;

    @BeforeEach
    void setUp() {
        sqsService = mock(SqsService.class);
        lambdaService = mock(LambdaService.class);
        snsService = mock(SnsService.class);
        eventBridgeService = mock(EventBridgeService.class);
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.baseUrl()).thenReturn("http://localhost:4566");
        invoker = new ScheduleInvoker(sqsService, lambdaService, snsService,
                eventBridgeService, new ObjectMapper(), config);
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
    void unsupportedUniversalActionDoesNotThrowOrDispatch() {
        Target target = new Target();
        target.setArn("arn:aws:scheduler:::aws-sdk:dynamodb:putItem");
        target.setInput("{}");

        invoker.invoke(target, "us-east-1");

        verify(sqsService, never()).sendMessage(anyString(), anyString(), anyInt(), anyString(), any(), anyString());
        verify(snsService, never()).publish(anyString(), any(), anyString(), anyString(), anyString());
    }
}
