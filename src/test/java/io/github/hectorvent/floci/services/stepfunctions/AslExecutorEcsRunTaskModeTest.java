package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.ecs.EcsJsonHandler;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.ecs.model.NetworkConfiguration;
import io.github.hectorvent.floci.services.lambda.LambdaExecutorService;
import io.github.hectorvent.floci.services.lambda.LambdaFunctionStore;
import io.github.hectorvent.floci.services.sqs.SqsJsonHandler;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the ecs:runTask integration's mode-specific failure semantics and the threading of
 * NetworkConfiguration through to {@link EcsService#runTask}:
 * <ul>
 *   <li>request-response never fails the state on a placement failure — it returns the
 *       {@code {Tasks,Failures}} envelope, even with no launched tasks;</li>
 *   <li>{@code .sync} (and {@code .waitForTaskToken}) fail the state on a placement failure,
 *       using the AWS error name {@code AmazonECS.Unknown};</li>
 *   <li>an awsvpc {@code NetworkConfiguration} in the parameters is parsed and passed to runTask
 *       rather than dropped.</li>
 * </ul>
 */
class AslExecutorEcsRunTaskModeTest {

    private static final String REGION = "us-east-2";
    private static final String ACCOUNT = "000000000000";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private EcsService ecsService;
    private AslExecutor executor;

    @BeforeEach
    void setUp() {
        ecsService = mock(EcsService.class);
        // A real handler so parseNetworkConfiguration / parseContainerOverrides actually run.
        EcsJsonHandler ecsJsonHandler = new EcsJsonHandler(ecsService, objectMapper);

        executor = new AslExecutor(
                mock(LambdaExecutorService.class),
                mock(LambdaFunctionStore.class),
                mock(DynamoDbService.class),
                mock(DynamoDbJsonHandler.class),
                mock(SqsJsonHandler.class),
                mock(io.github.hectorvent.floci.services.cloudformation.CloudFormationQueryHandler.class),
                mock(io.github.hectorvent.floci.services.ec2.Ec2Service.class),
                mock(io.github.hectorvent.floci.services.s3.S3Service.class),
                ecsService,
                ecsJsonHandler,
                objectMapper,
                new JsonataEvaluator(objectMapper),
                mock(Instance.class));
    }

    @Test
    void requestResponseDoesNotFailWhenNoTasksLaunched() throws Exception {
        // No tasks could be placed (AWS: 200 with a non-empty Failures list). Request-response
        // must return the envelope and let execution continue, not fail the state.
        when(ecsService.runTask(any(), any(), anyInt(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ArrayList<>());

        Execution execution = run("arn:aws:states:::ecs:runTask",
                "{\"TaskDefinition\":\"my-task-def\"}");

        assertEquals("SUCCEEDED", execution.getStatus());
        JsonNode output = objectMapper.readTree(execution.getOutput());
        assertTrue(output.path("Tasks").isArray(), "Tasks should be an array");
        assertEquals(0, output.path("Tasks").size());
        assertTrue(output.path("Failures").isArray(), "Failures should be an array");
    }

    @Test
    void syncFailsWithAmazonEcsUnknownWhenNoTasksLaunched() throws Exception {
        when(ecsService.runTask(any(), any(), anyInt(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ArrayList<>());

        Execution execution = run("arn:aws:states:::ecs:runTask.sync",
                "{\"TaskDefinition\":\"my-task-def\"}");

        assertEquals("FAILED", execution.getStatus());
        assertEquals("AmazonECS.Unknown", execution.getError());
    }

    @Test
    void networkConfigurationIsThreadedThroughToRunTask() throws Exception {
        when(ecsService.runTask(any(), any(), anyInt(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ArrayList<>());

        run("arn:aws:states:::ecs:runTask", """
                {
                  "TaskDefinition": "my-task-def",
                  "LaunchType": "FARGATE",
                  "NetworkConfiguration": {
                    "AwsvpcConfiguration": {
                      "Subnets": ["subnet-123"],
                      "SecurityGroups": ["sg-abc"],
                      "AssignPublicIp": "ENABLED"
                    }
                  }
                }
                """);

        ArgumentCaptor<NetworkConfiguration> captor = ArgumentCaptor.forClass(NetworkConfiguration.class);
        verify(ecsService).runTask(
                any(), any(), anyInt(), any(), any(), any(), any(), captor.capture(), any());

        NetworkConfiguration passed = captor.getValue();
        assertNotNull(passed, "NetworkConfiguration should be parsed and passed, not dropped");
        assertNotNull(passed.getAwsvpcConfiguration());
        assertEquals(List.of("subnet-123"), passed.getAwsvpcConfiguration().getSubnets());
        assertEquals(List.of("sg-abc"), passed.getAwsvpcConfiguration().getSecurityGroups());
        assertEquals("ENABLED", passed.getAwsvpcConfiguration().getAssignPublicIp());
    }

    private Execution run(String resource, String input) {
        String definition = """
                {
                  "StartAt": "RunTask",
                  "States": {
                    "RunTask": { "Type": "Task", "Resource": "%s", "End": true }
                  }
                }
                """.formatted(resource);

        StateMachine stateMachine = new StateMachine();
        stateMachine.setName("ecs-runtask-test");
        stateMachine.setStateMachineArn("arn:aws:states:%s:%s:stateMachine:ecs-runtask-test".formatted(REGION, ACCOUNT));
        stateMachine.setRoleArn("arn:aws:iam::%s:role/test-role".formatted(ACCOUNT));
        stateMachine.setDefinition(definition);

        Execution execution = new Execution();
        execution.setName("ecs-runtask-execution");
        execution.setExecutionArn("arn:aws:states:%s:%s:execution:ecs-runtask-test:ecs-runtask-execution".formatted(REGION, ACCOUNT));
        execution.setStateMachineArn(stateMachine.getStateMachineArn());
        execution.setInput(input);

        List<HistoryEvent> history = new ArrayList<>();
        executor.executeSync(stateMachine, execution, history, (updated, events) -> { });
        return execution;
    }
}
