package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.lambda.LambdaExecutorService;
import io.github.hectorvent.floci.services.lambda.LambdaFunctionStore;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.sqs.SqsJsonHandler;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A {@code Fail} state's {@code ErrorPath} and {@code CausePath} resolve the {@code Error} and
 * {@code Cause} it reports dynamically from the state's input, the same way real AWS and Step
 * Functions Local do. Fixes issue #3255.
 *
 * <p>Both fields are resolved through the same reference-path resolver a {@code ".$"} payload
 * template field uses, so an unresolvable path fails with {@code States.Runtime}, matching the
 * precedent set for issue #2521 in {@link AslExecutorUnresolvableJsonPathTest}.
 *
 * <p>The fixture supplies all service handlers required by the production executor, including
 * the SNS handler introduced by the Step Functions SNS integration.
 */
@QuarkusTest
class AslExecutorFailStateErrorCauseTest {

    private static final String REGION = "us-east-2";
    private static final String ACCOUNT = "000000000000";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AslExecutor executor;

    @Inject
    Vertx vertx;

    @BeforeEach
    void setUp() {
        executor = new AslExecutor(
                mock(LambdaExecutorService.class),
                mock(LambdaFunctionStore.class),
                mock(DynamoDbService.class),
                mock(DynamoDbJsonHandler.class),
                mock(SqsJsonHandler.class),
                mock(io.github.hectorvent.floci.services.sns.SnsJsonHandler.class),
                mock(io.github.hectorvent.floci.services.cloudformation.CloudFormationQueryHandler.class),
                mock(io.github.hectorvent.floci.services.ec2.Ec2Service.class),
                mock(S3Service.class),
                mock(io.github.hectorvent.floci.services.ecs.EcsService.class),
                mock(io.github.hectorvent.floci.services.ecs.EcsJsonHandler.class),
                mock(io.github.hectorvent.floci.services.eventbridge.EventBridgeHandler.class),
                mock(io.github.hectorvent.floci.services.scheduler.SchedulerService.class),
                mock(io.github.hectorvent.floci.services.scheduler.SchedulerController.class),
                objectMapper,
                new JsonataEvaluator(objectMapper),
                mock(Instance.class), mock(EmulatorConfig.class), vertx, null);
    }

    /** The exact reproduction from issue #3255: both ErrorPath and CausePath resolve from input. */
    @Test
    void errorPathAndCausePathResolveFromInput() {
        Execution execution = run("""
                {"StartAt":"Boom","States":{
                  "Boom":{"Type":"Fail","ErrorPath":"$.e","CausePath":"$.c"}}}
                """, "{\"e\":\"StudentDeletionFailed\",\"c\":\"boom\"}");

        assertEquals("FAILED", execution.getStatus());
        assertEquals("StudentDeletionFailed", execution.getError());
        assertEquals("boom", execution.getCause());
    }

    /** A literal Error alongside a CausePath resolves the cause dynamically and keeps the literal error. */
    @Test
    void literalErrorWithCausePathResolvesOnlyTheCause() {
        Execution execution = run("""
                {"StartAt":"Boom","States":{
                  "Boom":{"Type":"Fail","Error":"422","CausePath":"$.Cause"}}}
                """, "{\"Cause\":\"Unable to create an account\"}");

        assertEquals("FAILED", execution.getStatus());
        assertEquals("422", execution.getError());
        assertEquals("Unable to create an account", execution.getCause());
    }

    /** ErrorPath and CausePath can also be a States.* intrinsic, per the ASL specification. */
    @Test
    void errorPathAsAnIntrinsicFunctionResolves() {
        Execution execution = run("""
                {"StartAt":"Boom","States":{
                  "Boom":{"Type":"Fail","ErrorPath":"States.Format('Order{}Failed', $.orderId)"}}}
                """, "{\"orderId\":42}");

        assertEquals("FAILED", execution.getStatus());
        assertEquals("Order42Failed", execution.getError());
    }

    /** An ErrorPath that matches nothing in the input fails with States.Runtime, not a silent miss. */
    @Test
    void unresolvableErrorPathFailsWithStatesRuntime() {
        Execution execution = run("""
                {"StartAt":"Boom","States":{
                  "Boom":{"Type":"Fail","ErrorPath":"$.nope"}}}
                """, "{\"other\":1}");

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.Runtime", execution.getError());
        assertEquals("An error occurred while executing the state 'Boom' (entered at the event id #1). "
                + "The JSONPath '$.nope' specified for the field 'ErrorPath' could not be "
                + "found in the input '{\"other\":1}'", execution.getCause());
    }

    /** CausePath fails the same way as ErrorPath when it cannot be resolved. */
    @Test
    void unresolvableCausePathFailsWithStatesRuntime() {
        Execution execution = run("""
                {"StartAt":"Boom","States":{
                  "Boom":{"Type":"Fail","Error":"OrderFailed","CausePath":"$.nope"}}}
                """, "{\"other\":1}");

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.Runtime", execution.getError());
        assertEquals("An error occurred while executing the state 'Boom' (entered at the event id #1). "
                + "The JSONPath '$.nope' specified for the field 'CausePath' could not be "
                + "found in the input '{\"other\":1}'", execution.getCause());
    }

    /** ErrorPath must select a string; any other resolved type fails with States.Runtime. */
    @Test
    void errorPathResolvingToANonStringFailsWithStatesRuntime() {
        Execution execution = run("""
                {"StartAt":"Boom","States":{
                  "Boom":{"Type":"Fail","ErrorPath":"$.e"}}}
                """, "{\"e\":404}");

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.Runtime", execution.getError());
        assertEquals("An error occurred while executing the state 'Boom' (entered at the event id #1). "
                + "ErrorPath must resolve to a string", execution.getCause());
    }

    private Execution run(String definition, String input) {
        StateMachine stateMachine = new StateMachine();
        stateMachine.setName("fail-error-cause-test");
        stateMachine.setStateMachineArn(
                "arn:aws:states:%s:%s:stateMachine:fail-error-cause-test".formatted(REGION, ACCOUNT));
        stateMachine.setRoleArn("arn:aws:iam::%s:role/test-role".formatted(ACCOUNT));
        stateMachine.setDefinition(definition);

        Execution execution = new Execution();
        execution.setName("fail-error-cause-test-execution");
        execution.setExecutionArn(
                "arn:aws:states:%s:%s:execution:fail-error-cause-test:fail-error-cause-test-execution"
                        .formatted(REGION, ACCOUNT));
        execution.setStateMachineArn(stateMachine.getStateMachineArn());
        execution.setInput(input);

        List<HistoryEvent> history = new ArrayList<>();
        executor.executeSync(stateMachine, execution, history, (updated, events) -> {
        });
        return execution;
    }

    private static <T> T mock(Class<T> type) {
        return org.mockito.Mockito.mock(type);
    }
}
