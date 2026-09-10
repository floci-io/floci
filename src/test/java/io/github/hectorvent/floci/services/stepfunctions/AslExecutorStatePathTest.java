package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationQueryHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ecs.EcsJsonHandler;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.lambda.LambdaExecutorService;
import io.github.hectorvent.floci.services.lambda.LambdaFunctionStore;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.sns.SnsJsonHandler;
import io.github.hectorvent.floci.services.sqs.SqsJsonHandler;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class AslExecutorStatePathTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private AslExecutor executor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        executor = new AslExecutor(
                mock(LambdaExecutorService.class),
                mock(LambdaFunctionStore.class),
                mock(DynamoDbService.class),
                mock(DynamoDbJsonHandler.class),
                mock(SqsJsonHandler.class), mock(SnsJsonHandler.class),
                mock(CloudFormationQueryHandler.class),
                mock(Ec2Service.class),
                mock(S3Service.class),
                mock(EcsService.class),
                mock(EcsJsonHandler.class),
                mock(io.github.hectorvent.floci.services.eventbridge.EventBridgeHandler.class),
                mock(io.github.hectorvent.floci.services.scheduler.SchedulerService.class),
                mock(io.github.hectorvent.floci.services.scheduler.SchedulerController.class),
                mapper,
                new JsonataEvaluator(mapper),
                mock(Instance.class),
                mock(EmulatorConfig.class),
                null, null);
    }

    @AfterEach
    void tearDown() {
        executor.stop();
    }

    @Test
    void choiceInputPathControlsRuleEvaluation() throws Exception {
        assertOutput("""
                {"StartAt":"Pick","States":{
                  "Pick":{"Type":"Choice","InputPath":"$.scoped",
                    "Choices":[{"Variable":"$.route","StringEquals":"yes","Next":"Done"}],
                    "Default":"Wrong"},
                  "Done":{"Type":"Pass","End":true},
                  "Wrong":{"Type":"Fail","Error":"WrongBranch"}}}
                """,
                "{\"scoped\":{\"route\":\"yes\",\"value\":1},\"route\":\"no\"}",
                "{\"route\":\"yes\",\"value\":1}");
    }

    @Test
    void choiceOutputPathFiltersSelectedInput() throws Exception {
        assertOutput("""
                {"StartAt":"Pick","States":{
                  "Pick":{"Type":"Choice","OutputPath":"$.visible",
                    "Choices":[{"Variable":"$.route","StringEquals":"yes","Next":"Done"}]},
                  "Done":{"Type":"Pass","End":true}}}
                """,
                "{\"route\":\"yes\",\"visible\":{\"kept\":true},\"hidden\":9}",
                "{\"kept\":true}");
    }

    @Test
    void choiceDefaultRouteAppliesOutputPath() throws Exception {
        assertOutput("""
                {"StartAt":"Pick","States":{
                  "Pick":{"Type":"Choice","OutputPath":"$.visible",
                    "Choices":[{"Variable":"$.route","StringEquals":"yes","Next":"Wrong"}],
                    "Default":"Done"},
                  "Done":{"Type":"Pass","End":true},
                  "Wrong":{"Type":"Fail","Error":"WrongBranch"}}}
                """,
                "{\"route\":\"no\",\"visible\":{\"kept\":true},\"hidden\":9}",
                "{\"kept\":true}");
    }

    @Test
    void waitInputPathFeedsSecondsPath() throws Exception {
        assertOutput("""
                {"StartAt":"Wait","States":{
                  "Wait":{"Type":"Wait","InputPath":"$.scoped","SecondsPath":"$.delay","End":true}}}
                """,
                "{\"scoped\":{\"delay\":0,\"value\":1},\"outside\":9}",
                "{\"delay\":0,\"value\":1}");
    }

    @Test
    void waitOutputPathFiltersStateOutput() throws Exception {
        assertOutput("""
                {"StartAt":"Wait","States":{
                  "Wait":{"Type":"Wait","Seconds":0,"OutputPath":"$.visible","End":true}}}
                """,
                "{\"visible\":{\"kept\":true},\"hidden\":9}",
                "{\"kept\":true}");
    }

    @Test
    void succeedAppliesInputPathBeforeOutputPath() throws Exception {
        assertOutput("""
                {"StartAt":"Done","States":{
                  "Done":{"Type":"Succeed","InputPath":"$.scoped","OutputPath":"$.visible"}}}
                """,
                "{\"visible\":{\"source\":\"wrong\"},\"scoped\":{\"visible\":{\"source\":\"right\"}}}",
                "{\"source\":\"right\"}");
    }

    @Test
    void passInputPathNullDiscardsInputAsEmptyObject() throws Exception {
        assertOutput("""
                {"StartAt":"Pass","States":{
                  "Pass":{"Type":"Pass","InputPath":null,"End":true}}}
                """,
                "{\"discarded\":true}",
                "{}");
    }

    @Test
    void passOutputPathNullDiscardsOutputAsEmptyObject() throws Exception {
        assertOutput("""
                {"StartAt":"Pass","States":{
                  "Pass":{"Type":"Pass","OutputPath":null,"End":true}}}
                """,
                "{\"discarded\":true}",
                "{}");
    }

    @Test
    void mapIteratorOutputPathNullProducesEmptyObjects() throws Exception {
        assertOutput("""
                {"StartAt":"Each","States":{
                  "Each":{"Type":"Map","ItemsPath":"$.ids","MaxConcurrency":1,
                    "Iterator":{"StartAt":"Pass","States":{
                      "Pass":{"Type":"Pass","OutputPath":null,"End":true}}},
                    "End":true}}}
                """,
                "{\"ids\":[1,2]}",
                "[{},{}]");
    }

    @Test
    void parallelInputPathFeedsBranchesAndResultPathStillMergesIntoOriginalInput() throws Exception {
        assertOutput("""
                {"StartAt":"Parallel","States":{
                  "Parallel":{"Type":"Parallel","InputPath":"$.scoped","ResultPath":"$.branches",
                    "Branches":[{"StartAt":"Copy","States":{"Copy":{"Type":"Pass","End":true}}}],
                    "End":true}}}
                """,
                "{\"scoped\":{\"value\":\"right\"},\"outside\":9}",
                "{\"scoped\":{\"value\":\"right\"},\"outside\":9,\"branches\":[{\"value\":\"right\"}]}");
    }

    @Test
    void parallelOutputPathFiltersMergedResult() throws Exception {
        assertOutput("""
                {"StartAt":"Parallel","States":{
                  "Parallel":{"Type":"Parallel","ResultPath":"$.branches","OutputPath":"$.branches",
                    "Branches":[{"StartAt":"Copy","States":{"Copy":{"Type":"Pass","End":true}}}],
                    "End":true}}}
                """,
                "{\"value\":\"right\",\"outside\":9}",
                "[{\"value\":\"right\",\"outside\":9}]");
    }

    private void assertOutput(String definition, String input, String expected) throws Exception {
        Execution execution = run(definition, input);
        assertEquals("SUCCEEDED", execution.getStatus(), execution.getCause());
        assertEquals(mapper.readTree(expected), mapper.readTree(execution.getOutput()));
    }

    private Execution run(String definition, String input) {
        StateMachine stateMachine = new StateMachine();
        stateMachine.setName("state-path-test");
        stateMachine.setStateMachineArn("arn:aws:states:us-east-1:000000000000:stateMachine:state-path-test");
        stateMachine.setRoleArn("arn:aws:iam::000000000000:role/test-role");
        stateMachine.setDefinition(definition);

        Execution execution = new Execution();
        execution.setName("state-path-execution");
        execution.setExecutionArn(
                "arn:aws:states:us-east-1:000000000000:execution:state-path-test:state-path-execution");
        execution.setStateMachineArn(stateMachine.getStateMachineArn());
        execution.setInput(input);

        List<HistoryEvent> history = new ArrayList<>();
        executor.executeSync(stateMachine, execution, history, (updated, events) -> {
        });
        return execution;
    }
}
