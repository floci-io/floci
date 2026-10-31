package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationQueryHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbFacade;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * JSONPath workflow variables: {@code Assign} on JSONPath states (evaluated against the state result,
 * committed for later states) and {@code $name} / {@code $name.path} references in Parameters,
 * intrinsic arguments and Choice rules, as AWS supports them for JSONPath (not only JSONata).
 */
class AslExecutorJsonPathVariablesTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private AslExecutor executor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        executor = new AslExecutor(
                mock(LambdaExecutorService.class), mock(LambdaFunctionStore.class),
                mock(DynamoDbFacade.class), mock(DynamoDbJsonHandler.class), mock(SqsJsonHandler.class), mock(SnsJsonHandler.class),
                mock(CloudFormationQueryHandler.class), mock(Ec2Service.class), mock(S3Service.class),
                mock(EcsService.class), mock(EcsJsonHandler.class),
                mock(io.github.hectorvent.floci.services.eventbridge.EventBridgeHandler.class),
                mock(io.github.hectorvent.floci.services.scheduler.SchedulerService.class),
                mock(io.github.hectorvent.floci.services.scheduler.SchedulerController.class),
                mapper, new JsonataEvaluator(mapper),
                mock(Instance.class), mock(EmulatorConfig.class), null, null);
    }

    private Execution run(String definition, String input) {
        StateMachine sm = new StateMachine();
        sm.setName("vars");
        sm.setStateMachineArn("arn:aws:states:us-east-1:000000000000:stateMachine:vars");
        sm.setRoleArn("arn:aws:iam::000000000000:role/r");
        sm.setDefinition(definition);
        Execution exec = new Execution();
        exec.setName("vars-exec");
        exec.setExecutionArn("arn:aws:states:us-east-1:000000000000:execution:vars:e");
        exec.setStateMachineArn(sm.getStateMachineArn());
        exec.setInput(input);
        executor.executeSync(sm, exec, new ArrayList<HistoryEvent>(), (u, e) -> {
        });
        return exec;
    }

    private JsonNode output(Execution exec) throws Exception {
        return mapper.readTree(exec.getOutput());
    }

    @Test
    void passAssignFromParametersIsReadByLaterStatesInIntrinsicsAndParameters() throws Exception {
        // Assign's $ is the Pass result (after Parameters), not the raw input; literal values are allowed.
        Execution exec = run("""
                {"StartAt":"Extract","States":{
                  "Extract":{"Type":"Pass","Parameters":{"fileKey.$":"$.key"},
                    "Assign":{"projectId.$":"States.ArrayGetItem(States.StringSplit($.fileKey, '/'), 1)","baseUrl":"http://api/"},
                    "Next":"Use"},
                  "Use":{"Type":"Pass","Parameters":{
                    "url.$":"States.Format('{}projects/{}/status', $baseUrl, $projectId)",
                    "id.$":"$projectId"},"End":true}}}
                """, "{\"key\":\"root/42/DWG/a.dwg\"}");
        assertEquals("SUCCEEDED", exec.getStatus(), exec.getCause());
        assertEquals("http://api/projects/42/status", output(exec).path("url").asText());
        assertEquals("42", output(exec).path("id").asText());
    }

    @Test
    void choiceRuleTestsAVariableAndVariableFieldPaths() throws Exception {
        Execution exec = run("""
                {"StartAt":"Set","States":{
                  "Set":{"Type":"Pass","Assign":{"isDwg":true,"file":{"name":"a.dwg"}},"Next":"Route"},
                  "Route":{"Type":"Choice","Choices":[{"Variable":"$isDwg","BooleanEquals":true,"Next":"Dwg"}],"Default":"Gis"},
                  "Dwg":{"Type":"Pass","Parameters":{"route":"dwg","name.$":"$file.name"},"End":true},
                  "Gis":{"Type":"Pass","Parameters":{"route":"gis"},"End":true}}}
                """, "{}");
        assertEquals("SUCCEEDED", exec.getStatus(), exec.getCause());
        assertEquals("dwg", output(exec).path("route").asText());
        assertEquals("a.dwg", output(exec).path("name").asText());
    }

    @Test
    void assignEntriesSeeTheValuesFromStateEntry() throws Exception {
        Execution exec = run("""
                {"StartAt":"A","States":{
                  "A":{"Type":"Pass","Assign":{"x":1},"Next":"B"},
                  "B":{"Type":"Pass","Assign":{"x":2,"before.$":"$x"},"Next":"C"},
                  "C":{"Type":"Pass","Parameters":{"x.$":"$x","before.$":"$before"},"End":true}}}
                """, "{}");
        assertEquals("SUCCEEDED", exec.getStatus(), exec.getCause());
        assertEquals(2, output(exec).path("x").asInt());
        assertEquals(1, output(exec).path("before").asInt());
    }

    @Test
    void catchAssignReadsTheErrorOutput() throws Exception {
        Execution exec = run("""
                {"StartAt":"Boom","States":{
                  "Boom":{"Type":"Parallel","Branches":[{"StartAt":"F","States":{"F":{"Type":"Fail","Error":"Custom.Err","Cause":"bad"}}}],
                    "Catch":[{"ErrorEquals":["States.ALL"],"Assign":{"err.$":"$.Error"},"ResultPath":"$.caught","Next":"After"}],"End":true},
                  "After":{"Type":"Pass","Parameters":{"err.$":"$err"},"End":true}}}
                """, "{}");
        assertEquals("SUCCEEDED", exec.getStatus(), exec.getCause());
        assertEquals("Custom.Err", output(exec).path("err").asText());
    }

    @Test
    void taskLikeAssignReadsTheRawResultNotTheResultSelectorOutput() throws Exception {
        // As on AWS: Assign and ResultSelector both see the raw result, so both can read $.ResponseBody.
        Execution exec = run("""
                {"StartAt":"Call","States":{
                  "Call":{"Type":"Parallel","Branches":[{"StartAt":"B","States":{"B":{"Type":"Pass",
                      "Result":{"ResponseBody":{"tilesetId":"t-1"}},"End":true}}}],
                    "ResultSelector":{"selected.$":"$[0].ResponseBody.tilesetId"},
                    "Assign":{"tilesetId.$":"$[0].ResponseBody.tilesetId"},
                    "ResultPath":"$.apiResult","Next":"Use"},
                  "Use":{"Type":"Pass","Parameters":{"v.$":"$tilesetId","s.$":"$.apiResult.selected"},"End":true}}}
                """, "{}");
        assertEquals("SUCCEEDED", exec.getStatus(), exec.getCause());
        assertEquals("t-1", output(exec).path("v").asText());
        assertEquals("t-1", output(exec).path("s").asText());
    }

    @Test
    void undefinedVariableFailsWithStatesRuntime() {
        Execution exec = run("""
                {"StartAt":"Use","States":{"Use":{"Type":"Pass","Parameters":{"v.$":"$missing"},"End":true}}}
                """, "{}");
        assertEquals("FAILED", exec.getStatus());
        assertEquals("States.Runtime", exec.getError());
        assertTrue(exec.getCause().contains("missing"), exec.getCause());
    }
}
