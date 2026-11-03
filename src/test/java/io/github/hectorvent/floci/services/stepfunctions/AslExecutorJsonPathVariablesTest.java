package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.sqs.SqsJsonHandler;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JSONPath workflow variables: {@code Assign} on JSONPath states (evaluated against the state result,
 * committed for later states) and {@code $name} / {@code $name.path} references in Parameters,
 * intrinsic arguments and Choice rules, as AWS supports them for JSONPath (not only JSONata).
 */
class AslExecutorJsonPathVariablesTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private AslExecutor executor;

    @BeforeEach
    void setUp() {
        executor = AslExecutorFixture.builder().objectMapper(mapper).build();
    }

    private Execution run(String definition, String input) {
        return AslExecutorFixture.runSync(executor, "us-east-1", "vars", definition, input, new ArrayList<>());
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

    @ParameterizedTest
    @ValueSource(strings = {"r\u00E9sultat", "\u53D8\u91CF", "e\u0301", "var\u0661", "\uD801\uDC00value", "_existing"})
    void unicodeVariableNamesAreReadByParametersAndIntrinsics(String variableName) throws Exception {
        Execution exec = run("""
                {"StartAt":"Set","States":{
                  "Set":{"Type":"Pass","Assign":{"%s":"ok"},"Next":"Use"},
                  "Use":{"Type":"Pass","Parameters":{
                    "direct.$":"$%s",
                    "formatted.$":"States.Format('{}!', $%s)"},"End":true}}}
                """.formatted(variableName, variableName, variableName), "{}");

        assertEquals("SUCCEEDED", exec.getStatus(), exec.getCause());
        assertEquals("ok", output(exec).path("direct").asText());
        assertEquals("ok!", output(exec).path("formatted").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"r\u00E9sultat", "\u53D8\u91CF", "e\u0301", "var\u0661", "\uD801\uDC00value", "_existing"})
    void unicodeVariableNamesWithFieldAndIndexSuffixesWorkInChoice(String variableName) throws Exception {
        assertTrue(ChoiceOperators.isReferencePath("$" + variableName + ".field[0]"));

        Execution exec = run("""
                {"StartAt":"Set","States":{
                  "Set":{"Type":"Pass","Assign":{"%s":{"field":[true]}},"Next":"Route"},
                  "Route":{"Type":"Choice","Choices":[{
                    "Variable":"$%s.field[0]","BooleanEquals":true,"Next":"Matched"}],
                    "Default":"Unmatched"},
                  "Matched":{"Type":"Pass","Result":"matched","End":true},
                  "Unmatched":{"Type":"Pass","Result":"unmatched","End":true}}}
                """.formatted(variableName, variableName), "{}");

        assertEquals("SUCCEEDED", exec.getStatus(), exec.getCause());
        assertEquals("matched", output(exec).asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1name", "\u0301name", "\uD83D\uDE00name"})
    void variableNamesRejectInvalidFirstCharacter(String variableName) {
        assertFalse(ChoiceOperators.isReferencePath("$" + variableName));
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

    @ParameterizedTest
    @ValueSource(strings = {"Task", "Parallel", "Map"})
    void resultSelectorReadsEntryVariablesAndNextStateReadsAssignments(String type) throws Exception {
        SqsJsonHandler sqs = mock(SqsJsonHandler.class);
        when(sqs.handle(anyString(), any(), anyString()))
                .thenReturn(Response.ok(mapper.createObjectNode().put("MessageId", "message-1")).build());
        executor = AslExecutorFixture.builder().objectMapper(mapper).sqsJsonHandler(sqs).build();
        String work = "Task".equals(type)
                ? "\"Resource\":\"arn:aws:states:::sqs:sendMessage\",\"Parameters\":{\"QueueUrl\":\"http://localhost/queue\",\"MessageBody\":\"hello\"}"
                : "Parallel".equals(type)
                ? "\"Branches\":[{\"StartAt\":\"Done\",\"States\":{\"Done\":{\"Type\":\"Pass\",\"End\":true}}}]"
                : "\"ItemsPath\":\"$.items\",\"ItemProcessor\":{\"StartAt\":\"Done\",\"States\":{\"Done\":{\"Type\":\"Pass\",\"End\":true}}}";
        Execution exec = run("""
                {"StartAt":"Set","States":{
                  "Set":{"Type":"Pass","Assign":{"x":1},"Next":"Work"},
                  "Work":{"Type":"%s",%s,"Assign":{"x":2},
                    "ResultSelector":{"before.$":"$x"},"Next":"Use"},
                  "Use":{"Type":"Pass","Parameters":{"before.$":"$.before","after.$":"$x"},"End":true}}}
                """.formatted(type, work), "{\"items\":[1,2]}");
        assertEquals("SUCCEEDED", exec.getStatus(), exec.getCause());
        assertEquals(1, output(exec).path("before").asInt());
        assertEquals(2, output(exec).path("after").asInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ItemSelector", "Parameters"})
    void mapSelectorReadsOuterVariables(String selector) throws Exception {
        Execution exec = run("""
                {"StartAt":"Set","States":{
                  "Set":{"Type":"Pass","Assign":{"shared":"outer"},"Next":"Map"},
                  "Map":{"Type":"Map","ItemsPath":"$.items","%s":{"shared.$":"$shared","item.$":"$$.Map.Item.Value"},
                    "ItemProcessor":{"StartAt":"Use","States":{
                      "Use":{"Type":"Pass","Parameters":{"selected.$":"$.shared","inherited.$":"$shared"},"End":true}}},
                    "End":true}}}
                """.formatted(selector), "{\"items\":[1,2]}");
        assertEquals("SUCCEEDED", exec.getStatus(), exec.getCause());
        assertEquals(2, output(exec).size());
        for (JsonNode item : output(exec)) {
            assertEquals("outer", item.path("selected").asText());
            assertEquals("outer", item.path("inherited").asText());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"$obj.missing", "States.Format('{}', $obj.missing)", "States.Format('{}', $obj.items[2])"})
    void missingVariableChildFailsWithStatesRuntime(String reference) {
        Execution exec = run("""
                {"StartAt":"Set","States":{
                  "Set":{"Type":"Pass","Assign":{"obj":{"items":[]}},"Next":"Use"},
                  "Use":{"Type":"Pass","Parameters":{"value.$":"%s"},"End":true}}}
                """.formatted(reference), "{}");
        assertEquals("FAILED", exec.getStatus());
        assertEquals("States.Runtime", exec.getError());
    }

    @Test
    void plainVariableArrayReferenceAllowsOutOfRangeIndex() throws Exception {
        Execution exec = run("""
                {"StartAt":"Set","States":{
                  "Set":{"Type":"Pass","Assign":{"items":[]},"Next":"Use"},
                  "Use":{"Type":"Pass","Parameters":{"value.$":"$items[2]"},"End":true}}}
                """, "{}");
        assertEquals("SUCCEEDED", exec.getStatus(), exec.getCause());
        assertTrue(output(exec).has("value"));
        assertTrue(output(exec).get("value").isNull());
    }

    @Test
    void waitAssignIsReadByNextState() throws Exception {
        Execution exec = run("""
                {"StartAt":"Wait","States":{
                  "Wait":{"Type":"Wait","Seconds":0,"Assign":{"saved.$":"$.value"},"Next":"Use"},
                  "Use":{"Type":"Pass","Parameters":{"value.$":"$saved"},"End":true}}}
                """, "{\"value\":42}");
        assertEquals("SUCCEEDED", exec.getStatus(), exec.getCause());
        assertEquals(42, output(exec).path("value").asInt());
    }

    @Test
    void failedStateDiscardsAssignmentsBeforeCatchReadsEntryVariables() throws Exception {
        Execution exec = run("""
                {"StartAt":"Set","States":{
                  "Set":{"Type":"Pass","Assign":{"x":1},"Next":"Work"},
                  "Work":{"Type":"Parallel","Branches":[{"StartAt":"Done","States":{
                    "Done":{"Type":"Pass","End":true}}}],"Assign":{"x":2},"ResultPath":"$.items[2]",
                    "Catch":[{"ErrorEquals":["States.ResultPathMatchFailure"],"Assign":{"caught.$":"$x"},
                      "ResultPath":null,"Next":"Use"}],"Next":"Use"},
                  "Use":{"Type":"Pass","Parameters":{"x.$":"$x","caught.$":"$caught"},"End":true}}}
                """, "{\"items\":[]}");
        assertEquals("SUCCEEDED", exec.getStatus(), exec.getCause());
        assertEquals(1, output(exec).path("x").asInt());
        assertEquals(1, output(exec).path("caught").asInt());
    }

    @Test
    @SuppressWarnings("unchecked")
    void distributedMapSelectorReadsOuterVariables() throws Exception {
        Instance<StepFunctionsService> service = mock(Instance.class);
        when(service.get()).thenReturn(mock(StepFunctionsService.class));
        executor = AslExecutorFixture.builder().objectMapper(mapper).sfnService(service).build();
        Execution exec = run("""
                {"StartAt":"Set","States":{
                  "Set":{"Type":"Pass","Assign":{"shared":"outer"},"Next":"Map"},
                  "Map":{"Type":"Map","ItemsPath":"$.items","ItemSelector":{"shared.$":"$shared"},
                    "ItemProcessor":{"ProcessorConfig":{"Mode":"DISTRIBUTED","ExecutionType":"STANDARD"},
                      "StartAt":"Use","States":{"Use":{"Type":"Pass","End":true}}},"End":true}}}
                """, "{\"items\":[1,2]}");
        assertEquals("SUCCEEDED", exec.getStatus(), exec.getCause());
        assertEquals(2, output(exec).size());
        assertEquals("outer", output(exec).get(0).path("shared").asText());
    }
}
