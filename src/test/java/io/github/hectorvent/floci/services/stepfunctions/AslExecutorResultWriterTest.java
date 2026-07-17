package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.lambda.LambdaExecutorService;
import io.github.hectorvent.floci.services.lambda.LambdaFunctionStore;
import io.github.hectorvent.floci.services.s3.S3Service;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the Distributed Map {@code ResultWriter} emulation: the {@code WriterConfig}
 * transformations ({@code NONE}/{@code COMPACT}/{@code FLATTEN}), the S3 export (manifest.json +
 * result files), and the {@code {MapRunArn, ResultWriterDetails}} object the Map state returns —
 * per the AWS Step Functions ResultWriter specification.
 */
class AslExecutorResultWriterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private S3Service s3Service;
    private AslExecutor executor;
    private StateMachine sm;
    private JsonNode context;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        s3Service = mock(S3Service.class);
        when(s3Service.putObject(anyString(), anyString(), any(byte[].class), anyString(), any()))
                .thenReturn(null);
        executor = new AslExecutor(
                mock(LambdaExecutorService.class),
                mock(LambdaFunctionStore.class),
                mock(DynamoDbService.class),
                mock(DynamoDbJsonHandler.class),
                mock(SqsJsonHandler.class),
                mock(io.github.hectorvent.floci.services.cloudformation.CloudFormationQueryHandler.class),
                mock(io.github.hectorvent.floci.services.ec2.Ec2Service.class),
                s3Service,
                mock(io.github.hectorvent.floci.services.ecs.EcsService.class),
                mock(io.github.hectorvent.floci.services.ecs.EcsJsonHandler.class),
                mapper,
                new JsonataEvaluator(mapper),
                mock(Instance.class));

        sm = new StateMachine();
        sm.setStateMachineArn("arn:aws:states:us-east-1:000000000000:stateMachine:orderProcessing");
        sm.setName("orderProcessing");

        ObjectNode ctx = mapper.createObjectNode();
        ctx.putObject("StateMachine").put("Name", "orderProcessing");
        ctx.putObject("Execution").put("Name", "exec-1");
        context = ctx;
    }

    private ArrayNode arr(String... jsons) throws Exception {
        ArrayNode a = mapper.createArrayNode();
        for (String j : jsons) {
            a.add(mapper.readTree(j));
        }
        return a;
    }

    @Test
    void exportWritesManifestAndResultFileAndReturnsResultWriterDetails() throws Exception {
        JsonNode stateDef = mapper.readTree("""
                {"Type":"Map",
                 "Label":"orders",
                 "ResultWriter":{"Resource":"arn:aws:states:::s3:putObject",
                   "Parameters":{"Bucket":"my-bucket","Prefix":"/csvJobs//"}}}
                """);
        ArrayNode results = arr("{\"ok\":1}", "{\"ok\":2}");
        ArrayNode inputs = arr("{\"in\":1}", "{\"in\":2}");
        List<long[]> timings = List.of(new long[]{1000L, 2000L}, new long[]{3000L, 4000L});

        JsonNode out = executor.applyResultWriter("Process", stateDef, mapper.createObjectNode(),
                results, inputs, timings, sm, context, false);

        // The Map state returns the MapRunArn + the S3 manifest location, not the inline array.
        assertTrue(out.has("MapRunArn"));
        assertTrue(out.path("MapRunArn").asText().startsWith(
                "arn:aws:states:us-east-1:000000000000:mapRun:orderProcessing/orders:"));
        assertEquals("my-bucket", out.path("ResultWriterDetails").path("Bucket").asText());
        String manifestKey = out.path("ResultWriterDetails").path("Key").asText();
        assertTrue(manifestKey.startsWith("/csvJobs//"), manifestKey);
        assertTrue(manifestKey.endsWith("/manifest.json"), manifestKey);
        // The manifest uuid segment must match the MapRunArn uuid.
        String mapRunUuid = out.path("MapRunArn").asText().substring(
                out.path("MapRunArn").asText().lastIndexOf(':') + 1);
        assertTrue(manifestKey.contains("/" + mapRunUuid + "/"), manifestKey);

        // Capture the two S3 writes: SUCCEEDED_0.json and manifest.json.
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> bodies = ArgumentCaptor.forClass(byte[].class);
        verify(s3Service, org.mockito.Mockito.times(2))
                .putObject(eq("my-bucket"), keys.capture(), bodies.capture(), anyString(), any());

        int manifestIdx = keys.getAllValues().indexOf(manifestKey);
        assertTrue(manifestIdx >= 0, "manifest.json should have been written");
        JsonNode manifest = mapper.readTree(bodies.getAllValues().get(manifestIdx));
        assertEquals("my-bucket", manifest.path("DestinationBucket").asText());
        assertEquals(out.path("MapRunArn").asText(), manifest.path("MapRunArn").asText());
        assertEquals(0, manifest.path("ResultFiles").path("FAILED").size());
        assertEquals(0, manifest.path("ResultFiles").path("PENDING").size());
        assertEquals(1, manifest.path("ResultFiles").path("SUCCEEDED").size());
        JsonNode succeededEntry = manifest.path("ResultFiles").path("SUCCEEDED").get(0);
        assertTrue(succeededEntry.path("Key").asText().endsWith("SUCCEEDED_0.json"));
        assertTrue(succeededEntry.path("Size").asInt() > 0);

        // Default (no WriterConfig) export uses Transformation NONE: execution records with metadata.
        int recIdx = manifestIdx == 0 ? 1 : 0;
        JsonNode records = mapper.readTree(bodies.getAllValues().get(recIdx));
        assertTrue(records.isArray());
        assertEquals(2, records.size());
        JsonNode rec = records.get(0);
        assertEquals("SUCCEEDED", rec.path("Status").asText());
        assertEquals("NOT_REDRIVABLE", rec.path("RedriveStatus").asText());
        assertTrue(rec.path("ExecutionArn").asText().contains(":execution:orderProcessing/orders:"));
        assertEquals("arn:aws:states:us-east-1:000000000000:stateMachine:orderProcessing/orders",
                rec.path("StateMachineArn").asText());
        // Output is stored as a JSON string (not a nested object), matching AWS.
        assertTrue(rec.path("Output").isTextual());
        assertEquals("{\"ok\":1}", rec.path("Output").asText());
        assertTrue(rec.path("InputDetails").path("Included").asBoolean());
    }

    @Test
    void compactTransformationReturnsRawOutputsAndDoesNotWriteWhenNoResource() throws Exception {
        JsonNode stateDef = mapper.readTree("""
                {"Type":"Map",
                 "ResultWriter":{"WriterConfig":{"Transformation":"COMPACT","OutputType":"JSON"}}}
                """);
        ArrayNode results = arr("[{\"a\":1}]", "[{\"b\":2}]");

        JsonNode out = executor.applyResultWriter("Process", stateDef, mapper.createObjectNode(),
                results, mapper.createArrayNode(), List.of(), sm, context, false);

        // COMPACT keeps the original per-child array structure; no S3 Resource -> no export.
        assertEquals(results, out);
        verify(s3Service, org.mockito.Mockito.never())
                .putObject(anyString(), anyString(), any(byte[].class), anyString(), any());
    }

    @Test
    void flattenTransformationFlattensChildArrays() throws Exception {
        JsonNode stateDef = mapper.readTree("""
                {"Type":"Map",
                 "ResultWriter":{"WriterConfig":{"Transformation":"FLATTEN","OutputType":"JSON"}}}
                """);
        ArrayNode results = arr("[{\"id\":1},{\"id\":2}]", "[{\"id\":3}]");

        JsonNode out = executor.applyResultWriter("Process", stateDef, mapper.createObjectNode(),
                results, mapper.createArrayNode(), List.of(), sm, context, false);

        assertTrue(out.isArray());
        assertEquals(3, out.size());
        assertEquals(1, out.get(0).path("id").asInt());
        assertEquals(3, out.get(2).path("id").asInt());
    }

    @Test
    void exportRecordsScalarStringInputAndOutputAsQuotedJson() throws Exception {
        JsonNode stateDef = mapper.readTree("""
                {"Type":"Map",
                 "ResultWriter":{"Resource":"arn:aws:states:::s3:putObject",
                   "Parameters":{"Bucket":"my-bucket","Prefix":"p"}}}
                """);
        // A child whose input and output are JSON string scalars.
        ArrayNode results = arr("\"hello\"");
        ArrayNode inputs = arr("\"world\"");
        List<long[]> timings = List.of(new long[]{1000L, 2000L});

        executor.applyResultWriter("Process", stateDef, mapper.createObjectNode(),
                results, inputs, timings, sm, context, false);

        ArgumentCaptor<byte[]> bodies = ArgumentCaptor.forClass(byte[].class);
        verify(s3Service, org.mockito.Mockito.times(2))
                .putObject(eq("my-bucket"), anyString(), bodies.capture(), anyString(), any());
        // The records file is the JSON array write (the other write is manifest.json, an object).
        JsonNode first = mapper.readTree(bodies.getAllValues().get(0));
        JsonNode records = first.isArray() ? first : mapper.readTree(bodies.getAllValues().get(1));
        JsonNode rec = records.get(0);
        // AWS stores a child's Input/Output as a JSON-encoded string, so a string scalar keeps its
        // surrounding quotes (regression: asText() would drop them and yield invalid JSON).
        assertEquals("\"hello\"", rec.path("Output").asText());
        assertEquals("\"world\"", rec.path("Input").asText());
    }

    @Test
    void distributedMapAppliesInputPathBeforeItemsAndWriterParameters() throws Exception {
        StateMachine machine = new StateMachine();
        machine.setName("orderProcessing");
        machine.setStateMachineArn("arn:aws:states:us-east-1:000000000000:stateMachine:orderProcessing");
        machine.setRoleArn("arn:aws:iam::000000000000:role/test-role");
        machine.setDefinition("""
                {
                  "StartAt":"Process",
                  "States":{
                    "Process":{
                      "Type":"Map",
                      "Label":"orders",
                      "InputPath":"$.selected",
                      "ItemsPath":"$.items",
                      "ItemProcessor":{
                        "ProcessorConfig":{"Mode":"DISTRIBUTED","ExecutionType":"STANDARD"},
                        "StartAt":"PassItem",
                        "States":{"PassItem":{"Type":"Pass","End":true}}
                      },
                      "ResultWriter":{
                        "Resource":"arn:aws:states:::s3:putObject",
                        "Parameters":{
                          "Bucket.$":"$.destination.bucket",
                          "Prefix.$":"$.destination.prefix"
                        }
                      },
                      "End":true
                    }
                  }
                }
                """);
        Execution execution = execution(machine, "input-path", """
                {
                  "items":["wrong"],
                  "destination":{"bucket":"raw-bucket","prefix":"raw-prefix"},
                  "selected":{
                    "items":[{"id":1},{"id":2}],
                    "destination":{"bucket":"selected-bucket","prefix":"/csvJobs//"}
                  }
                }
                """);

        executor.executeSync(machine, execution, new ArrayList<>(), (updated, history) -> { });

        assertEquals("SUCCEEDED", execution.getStatus(), execution.getCause());
        JsonNode output = mapper.readTree(execution.getOutput());
        assertEquals("selected-bucket", output.path("ResultWriterDetails").path("Bucket").asText());
        String mapRunArn = output.path("MapRunArn").asText();
        assertTrue(mapRunArn.startsWith(
                "arn:aws:states:us-east-1:000000000000:mapRun:orderProcessing/orders:"), mapRunArn);
        String mapRunId = mapRunArn.substring(mapRunArn.lastIndexOf(':') + 1);
        String manifestKey = output.path("ResultWriterDetails").path("Key").asText();
        assertTrue(manifestKey.startsWith("/csvJobs//" + mapRunId + "/"), manifestKey);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(s3Service, org.mockito.Mockito.times(2))
                .putObject(eq("selected-bucket"), keys.capture(), any(byte[].class), anyString(), any());
        assertTrue(keys.getAllValues().stream().allMatch(key -> key.startsWith("/csvJobs//" + mapRunId + "/")));
    }

    @Test
    void jsonataArgumentsExpressionCanUseWorkflowVariables() throws Exception {
        StateMachine machine = new StateMachine();
        machine.setName("orderProcessing");
        machine.setStateMachineArn("arn:aws:states:us-east-1:000000000000:stateMachine:orderProcessing");
        machine.setRoleArn("arn:aws:iam::000000000000:role/test-role");
        machine.setDefinition("""
                {
                  "QueryLanguage":"JSONata",
                  "StartAt":"SetDestination",
                  "States":{
                    "SetDestination":{
                      "Type":"Pass",
                      "Assign":{"destination":"{% $states.input.destination %}"},
                      "Next":"Process"
                    },
                    "Process":{
                      "Type":"Map",
                      "Label":"orders",
                      "Items":"{% $states.input.items %}",
                      "ItemProcessor":{
                        "ProcessorConfig":{"Mode":"DISTRIBUTED","ExecutionType":"STANDARD"},
                        "StartAt":"PassItem",
                        "States":{"PassItem":{"Type":"Pass","End":true}}
                      },
                      "ResultWriter":{
                        "Resource":"arn:aws:states:::s3:putObject",
                        "Arguments":"{% $destination %}"
                      },
                      "End":true
                    }
                  }
                }
                """);
        Execution execution = execution(machine, "jsonata-writer", """
                {"items":[{"id":1}],"destination":{"Bucket":"jsonata-bucket","Prefix":"exports"}}
                """);

        executor.executeSync(machine, execution, new ArrayList<>(), (updated, history) -> { });

        assertEquals("SUCCEEDED", execution.getStatus(), execution.getCause());
        JsonNode output = mapper.readTree(execution.getOutput());
        assertEquals("jsonata-bucket", output.path("ResultWriterDetails").path("Bucket").asText());
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(s3Service, org.mockito.Mockito.times(2))
                .putObject(eq("jsonata-bucket"), keys.capture(), any(byte[].class), anyString(), any());
        assertTrue(keys.getAllValues().stream().allMatch(key -> key.startsWith("exports/")));
    }

    @Test
    void jsonataArgumentsExpressionMustResolveToAnObject() throws Exception {
        JsonNode stateDef = mapper.readTree("""
                {"Type":"Map",
                 "ResultWriter":{"Resource":"arn:aws:states:::s3:putObject",
                   "Arguments":"{% 1 %}"}}
                """);

        AslExecutor.FailStateException failure = assertThrows(AslExecutor.FailStateException.class,
                () -> executor.applyResultWriter("Process", stateDef, mapper.createObjectNode(),
                        arr("{\"ok\":true}"), mapper.createArrayNode(), List.of(),
                        sm, context, true));

        assertEquals("States.QueryEvaluationError", failure.error);
        assertTrue(failure.getMessage().contains("must resolve to an object"), failure.getMessage());
        verify(s3Service, org.mockito.Mockito.never())
                .putObject(anyString(), anyString(), any(byte[].class), anyString(), any());
    }

    @Test
    void jsonataDestinationFieldsMustResolveToStrings() throws Exception {
        JsonNode stateDef = mapper.readTree("""
                {"Type":"Map",
                 "ResultWriter":{"Resource":"arn:aws:states:::s3:putObject",
                   "Arguments":{"Bucket":"{% 42 %}","Prefix":"results"}}}
                """);

        AslExecutor.FailStateException failure = assertThrows(AslExecutor.FailStateException.class,
                () -> executor.applyResultWriter("Process", stateDef, mapper.createObjectNode(),
                        arr("{\"ok\":true}"), mapper.createArrayNode(), List.of(),
                        sm, context, true));

        assertEquals("States.QueryEvaluationError", failure.error);
        assertTrue(failure.getMessage().contains("Bucket must resolve to a string"), failure.getMessage());
        verify(s3Service, org.mockito.Mockito.never())
                .putObject(anyString(), anyString(), any(byte[].class), anyString(), any());
    }

    @Test
    void jsonataDestinationNullsAreTypeErrorsRatherThanMissingValues() throws Exception {
        JsonNode stateDef = mapper.readTree("""
                {"Type":"Map",
                 "ResultWriter":{"Resource":"arn:aws:states:::s3:putObject",
                   "Arguments":"{% $states.input.destination %}"}}
                """);

        AslExecutor.FailStateException bucketFailure = assertThrows(AslExecutor.FailStateException.class,
                () -> executor.applyResultWriter("Process", stateDef,
                        mapper.readTree("{\"destination\":{\"Bucket\":null}}"),
                        arr("{\"ok\":true}"), mapper.createArrayNode(), List.of(),
                        sm, context, true));
        assertEquals("States.QueryEvaluationError", bucketFailure.error);
        assertTrue(bucketFailure.getMessage().contains("Bucket must resolve to a string"));

        AslExecutor.FailStateException prefixFailure = assertThrows(AslExecutor.FailStateException.class,
                () -> executor.applyResultWriter("Process", stateDef,
                        mapper.readTree("{\"destination\":{\"Bucket\":\"b\",\"Prefix\":null}}"),
                        arr("{\"ok\":true}"), mapper.createArrayNode(), List.of(),
                        sm, context, true));
        assertEquals("States.QueryEvaluationError", prefixFailure.error);
        assertTrue(prefixFailure.getMessage().contains("Prefix must resolve to a string"));

        JsonNode nestedBucketStateDef = mapper.readTree("""
                {"Type":"Map",
                 "ResultWriter":{"Resource":"arn:aws:states:::s3:putObject",
                   "Arguments":{"Bucket":"{% null %}","Prefix":"results"}}}
                """);
        AslExecutor.FailStateException nestedBucketFailure = assertThrows(
                AslExecutor.FailStateException.class,
                () -> executor.applyResultWriter("Process", nestedBucketStateDef, mapper.createObjectNode(),
                        arr("{\"ok\":true}"), mapper.createArrayNode(), List.of(),
                        sm, context, true));
        assertEquals("States.QueryEvaluationError", nestedBucketFailure.error);
        assertTrue(nestedBucketFailure.getMessage().contains("Bucket must resolve to a string"));

        JsonNode nestedPrefixStateDef = mapper.readTree("""
                {"Type":"Map",
                 "ResultWriter":{"Resource":"arn:aws:states:::s3:putObject",
                   "Arguments":{"Bucket":"b","Prefix":"{% $states.input.missing %}"}}}
                """);
        AslExecutor.FailStateException nestedPrefixFailure = assertThrows(
                AslExecutor.FailStateException.class,
                () -> executor.applyResultWriter("Process", nestedPrefixStateDef, mapper.createObjectNode(),
                        arr("{\"ok\":true}"), mapper.createArrayNode(), List.of(),
                        sm, context, true));
        assertEquals("States.QueryEvaluationError", nestedPrefixFailure.error);
        assertTrue(nestedPrefixFailure.getMessage().contains("Prefix must resolve to a string"));

        verify(s3Service, org.mockito.Mockito.never())
                .putObject(anyString(), anyString(), any(byte[].class), anyString(), any());
    }

    @Test
    void s3WriteFailureRaisesCatchableResultWriterFailed() throws Exception {
        when(s3Service.putObject(anyString(), anyString(), any(byte[].class), anyString(), any()))
                .thenThrow(new AwsException("NoSuchBucket", "Destination bucket does not exist", 404));
        StateMachine machine = new StateMachine();
        machine.setName("orderProcessing");
        machine.setStateMachineArn("arn:aws:states:us-east-1:000000000000:stateMachine:orderProcessing");
        machine.setRoleArn("arn:aws:iam::000000000000:role/test-role");
        machine.setDefinition("""
                {
                  "StartAt":"Process",
                  "States":{
                    "Process":{
                      "Type":"Map",
                      "ItemsPath":"$.items",
                      "ItemProcessor":{
                        "ProcessorConfig":{"Mode":"DISTRIBUTED","ExecutionType":"STANDARD"},
                        "StartAt":"PassItem",
                        "States":{"PassItem":{"Type":"Pass","End":true}}
                      },
                      "ResultWriter":{
                        "Resource":"arn:aws:states:::s3:putObject",
                        "Parameters":{"Bucket":"missing-bucket","Prefix":"results"}
                      },
                      "Catch":[{
                        "ErrorEquals":["States.ResultWriterFailed"],
                        "ResultPath":"$.writerError",
                        "Next":"Recovered"
                      }],
                      "End":true
                    },
                    "Recovered":{"Type":"Pass","End":true}
                  }
                }
                """);
        Execution execution = execution(machine, "writer-failure", "{\"items\":[1]}");

        executor.executeSync(machine, execution, new ArrayList<>(), (updated, history) -> { });

        assertEquals("SUCCEEDED", execution.getStatus(), execution.getCause());
        JsonNode output = mapper.readTree(execution.getOutput());
        assertEquals("States.ResultWriterFailed",
                output.path("writerError").path("Error").asText());
        assertTrue(output.path("writerError").path("Cause").asText()
                .contains("Destination bucket does not exist"), output.toString());
    }

    @Test
    void exportWithoutResolvedBucketFailsInsteadOfReturningInlineResults() throws Exception {
        JsonNode stateDef = mapper.readTree("""
                {"Type":"Map",
                 "ResultWriter":{"Resource":"arn:aws:states:::s3:putObject",
                   "Parameters":{"Prefix":"results"}}}
                """);

        AslExecutor.FailStateException failure = assertThrows(AslExecutor.FailStateException.class,
                () -> executor.applyResultWriter("Process", stateDef, mapper.createObjectNode(),
                        arr("{\"ok\":true}"), mapper.createArrayNode(), List.of(),
                        sm, context, false));

        assertEquals("States.ResultWriterFailed", failure.error);
        verify(s3Service, org.mockito.Mockito.never())
                .putObject(anyString(), anyString(), any(byte[].class), anyString(), any());
    }

    @Test
    void resultWriterOnInlineMapIsRejected() {
        StateMachine machine = new StateMachine();
        machine.setName("orderProcessing");
        machine.setStateMachineArn("arn:aws:states:us-east-1:000000000000:stateMachine:orderProcessing");
        machine.setRoleArn("arn:aws:iam::000000000000:role/test-role");
        // An INLINE map (no ProcessorConfig Mode) carrying a ResultWriter but no ItemReader.
        machine.setDefinition("""
                {"StartAt":"M","States":{"M":{"Type":"Map",
                  "ItemProcessor":{"StartAt":"P","States":{"P":{"Type":"Pass","End":true}}},
                  "ResultWriter":{"Resource":"arn:aws:states:::s3:putObject",
                    "Parameters":{"Bucket":"b","Prefix":"p"}},
                  "End":true}}}
                """);
        Execution execution = new Execution();
        execution.setName("exec-1");
        execution.setExecutionArn(
                "arn:aws:states:us-east-1:000000000000:execution:orderProcessing:exec-1");
        execution.setStateMachineArn(machine.getStateMachineArn());
        execution.setInput("[1,2]");

        executor.executeSync(machine, execution, new ArrayList<HistoryEvent>(), (u, e) -> { });

        // AWS rejects ResultWriter on an inline map; Floci fails the execution rather than exporting.
        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.Runtime", execution.getError());
        assertTrue(execution.getCause().contains("not supported for INLINE maps"), execution.getCause());
        verify(s3Service, org.mockito.Mockito.never())
                .putObject(anyString(), anyString(), any(byte[].class), anyString(), any());
    }

    @Test
    void jsonlOutputTypeWritesOneJsonRecordPerLineInJsonNamedFile() throws Exception {
        JsonNode stateDef = mapper.readTree("""
                {"Type":"Map",
                 "ResultWriter":{"Resource":"arn:aws:states:::s3:putObject",
                   "WriterConfig":{"Transformation":"COMPACT","OutputType":"JSONL"},
                   "Parameters":{"Bucket":"b","Prefix":"p"}}}
                """);
        ArrayNode results = arr("{\"a\":1}", "{\"a\":2}");

        JsonNode out = executor.applyResultWriter("Process", stateDef, mapper.createObjectNode(),
                results, mapper.createArrayNode(), List.of(), sm, context, false);

        String resultKey = null;
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> bodies = ArgumentCaptor.forClass(byte[].class);
        verify(s3Service, org.mockito.Mockito.times(2))
                .putObject(anyString(), keys.capture(), bodies.capture(), anyString(), any());
        for (int i = 0; i < keys.getAllValues().size(); i++) {
            if (keys.getAllValues().get(i).endsWith("SUCCEEDED_0.json")) {
                resultKey = keys.getAllValues().get(i);
                String body = new String(bodies.getAllValues().get(i), java.nio.charset.StandardCharsets.UTF_8);
                String[] lines = body.strip().split("\n");
                assertEquals(2, lines.length);
                assertEquals(1, mapper.readTree(lines[0]).path("a").asInt());
            }
        }
        assertFalse(resultKey == null, "JSONL serialization should retain AWS's .json result-file name");
        assertTrue(out.path("ResultWriterDetails").path("Key").asText().endsWith("/manifest.json"));
    }

    private Execution execution(StateMachine machine, String name, String input) {
        Execution execution = new Execution();
        execution.setName(name);
        execution.setExecutionArn(
                "arn:aws:states:us-east-1:000000000000:execution:" + machine.getName() + ":" + name);
        execution.setStateMachineArn(machine.getStateMachineArn());
        execution.setInput(input);
        return execution;
    }
}
