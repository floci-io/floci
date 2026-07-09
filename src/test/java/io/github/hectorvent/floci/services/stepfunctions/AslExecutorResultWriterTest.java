package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.lambda.LambdaExecutorService;
import io.github.hectorvent.floci.services.lambda.LambdaFunctionStore;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.sqs.SqsJsonHandler;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                 "ResultWriter":{"Resource":"arn:aws:states:::s3:putObject",
                   "Parameters":{"Bucket":"my-bucket","Prefix":"csvJobs"}}}
                """);
        ArrayNode results = arr("{\"ok\":1}", "{\"ok\":2}");
        ArrayNode inputs = arr("{\"in\":1}", "{\"in\":2}");
        List<long[]> timings = List.of(new long[]{1000L, 2000L}, new long[]{3000L, 4000L});

        JsonNode out = executor.applyResultWriter("Process", stateDef, mapper.createObjectNode(),
                results, inputs, timings, sm, context, false);

        // The Map state returns the MapRunArn + the S3 manifest location, not the inline array.
        assertTrue(out.has("MapRunArn"));
        assertTrue(out.path("MapRunArn").asText().startsWith(
                "arn:aws:states:us-east-1:000000000000:mapRun:orderProcessing/"));
        assertEquals("my-bucket", out.path("ResultWriterDetails").path("Bucket").asText());
        String manifestKey = out.path("ResultWriterDetails").path("Key").asText();
        assertTrue(manifestKey.startsWith("csvJobs/"), manifestKey);
        assertTrue(manifestKey.endsWith("/manifest.json"), manifestKey);
        // The manifest uuid segment must match the MapRunArn uuid.
        String mapRunUuid = out.path("MapRunArn").asText().substring(
                out.path("MapRunArn").asText().lastIndexOf('/') + 1);
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
        assertTrue(rec.path("ExecutionArn").asText().contains(":execution:orderProcessing/Process:"));
        assertEquals("arn:aws:states:us-east-1:000000000000:stateMachine:orderProcessing/Process",
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
    void jsonlOutputTypeWritesOneJsonRecordPerLine() throws Exception {
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
            if (keys.getAllValues().get(i).endsWith("SUCCEEDED_0.jsonl")) {
                resultKey = keys.getAllValues().get(i);
                String body = new String(bodies.getAllValues().get(i), java.nio.charset.StandardCharsets.UTF_8);
                String[] lines = body.strip().split("\n");
                assertEquals(2, lines.length);
                assertEquals(1, mapper.readTree(lines[0]).path("a").asInt());
            }
        }
        assertFalse(resultKey == null, "JSONL result file should use a .jsonl extension");
        assertTrue(out.path("ResultWriterDetails").path("Key").asText().endsWith("/manifest.json"));
    }
}
