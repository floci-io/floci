package io.github.hectorvent.floci.services.eventbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.batch.BatchService;
import io.github.hectorvent.floci.services.eventbridge.model.BatchParameters;
import io.github.hectorvent.floci.services.eventbridge.model.InputTransformer;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import io.github.hectorvent.floci.services.firehose.FirehoseService;
import io.github.hectorvent.floci.services.firehose.model.Record;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EventBridgeInvokerTest {

    private EventBridgeInvoker invoker;
    private SqsService sqsService;
    private BatchService batchService;
    private FirehoseService firehoseService;

    @BeforeEach
    void setUp() {
        LambdaService lambdaService = mock(LambdaService.class);
        sqsService = mock(SqsService.class);
        SnsService snsService = mock(SnsService.class);
        batchService = mock(BatchService.class);
        firehoseService = mock(FirehoseService.class);
        invoker = new EventBridgeInvoker(
                lambdaService,
                sqsService,
                snsService,
                batchService,
                firehoseService,
                new ObjectMapper(),
                mock(io.github.hectorvent.floci.config.EmulatorConfig.class)
        );
    }

    @Test
    void invokeTarget_sqsTarget_usesSuppliedRegion() {
        Target target = new Target("id1", "arn:aws:sqs:eu-west-1:000000000000:my-queue", null, null);
        String event = "{\"test\": \"data\"}";

        invoker.invokeTarget(target, event, "eu-west-1");

        verify(sqsService).sendMessage(anyString(), eq(event), anyInt(), isNull(), isNull(), eq("eu-west-1"));
    }

    @Test
    void invokeTarget_batchTarget_submitsBatchJobWithParametersFromPayload() throws Exception {
        JsonNode retryStrategy = new ObjectMapper().readTree("{\"Attempts\":2}");
        Target target = new Target("id1",
                "arn:aws:batch:us-west-2:000000000000:job-queue/my-queue",
                "{\"Parameters\":{\"inputKey\":\"inputs/1.json\",\"count\":2}}",
                null);
        BatchParameters batchParameters = new BatchParameters();
        batchParameters.setJobDefinition("my-job:1");
        batchParameters.setJobName("scheduled-job");
        batchParameters.setRetryStrategy(retryStrategy);
        target.setBatchParameters(batchParameters);

        invoker.invokeTarget(target, "{\"ignored\":true}", "us-east-1");

        verify(batchService).submitFromEventBridge(
                eq("arn:aws:batch:us-west-2:000000000000:job-queue/my-queue"),
                eq("my-job:1"),
                eq("scheduled-job"),
                eq(Map.of("inputKey", "inputs/1.json", "count", "2")),
                eq(retryStrategy),
                eq("us-west-2")
        );
    }

    @Test
    void invokeTarget_batchTargetDoesNotMapFlatPayloadToParameters() {
        Target target = new Target("id1",
                "arn:aws:batch:us-west-2:000000000000:job-queue/my-queue",
                "{\"inputKey\":\"ignored\"}",
                null);
        BatchParameters batchParameters = new BatchParameters();
        batchParameters.setJobDefinition("my-job:1");
        batchParameters.setJobName("scheduled-job");
        target.setBatchParameters(batchParameters);

        invoker.invokeTarget(target, "{\"ignored\":true}", "us-east-1");

        verify(batchService).submitFromEventBridge(
                eq("arn:aws:batch:us-west-2:000000000000:job-queue/my-queue"),
                eq("my-job:1"),
                eq("scheduled-job"),
                eq(Map.of()),
                isNull(),
                eq("us-west-2")
        );
    }

    @Test
    void invokeTarget_batchTargetWithoutExplicitInputDoesNotMapEventEnvelopeToParameters() {
        Target target = new Target("id1",
                "arn:aws:batch:us-west-2:000000000000:job-queue/my-queue",
                null,
                null);
        BatchParameters batchParameters = new BatchParameters();
        batchParameters.setJobDefinition("my-job:1");
        batchParameters.setJobName("scheduled-job");
        target.setBatchParameters(batchParameters);

        invoker.invokeTarget(target, """
                {"source":"local.test","region":"us-east-1","detail":{"inputKey":"ignored"}}
                """, "us-east-1");

        verify(batchService).submitFromEventBridge(
                eq("arn:aws:batch:us-west-2:000000000000:job-queue/my-queue"),
                eq("my-job:1"),
                eq("scheduled-job"),
                eq(Map.of()),
                isNull(),
                eq("us-west-2")
        );
    }

    @Test
    void invokeTarget_firehoseTarget_putsEventJsonAsRecordData() {
        Target target = new Target("id1",
                "arn:aws:firehose:us-east-1:000000000000:deliverystream/my-stream", null, null);
        String event = "{\"source\":\"local.test\",\"detail\":{\"orderId\":\"o-1\"}}";

        invoker.invokeTarget(target, event, "us-east-1");

        ArgumentCaptor<Record> captor = ArgumentCaptor.forClass(Record.class);
        verify(firehoseService).putRecord(eq("my-stream"), captor.capture());
        // The event JSON is the record Data verbatim: no wrapping, no trailing newline.
        assertEquals(event, new String(captor.getValue().getData(), StandardCharsets.UTF_8));
    }

    @Test
    void invokeTarget_firehoseTarget_deliversInputOverrideNotEnvelope() {
        Target target = new Target("id1",
                "arn:aws:firehose:us-east-1:000000000000:deliverystream/my-stream",
                "{\"custom\":\"payload\"}", null);

        invoker.invokeTarget(target, "{\"ignored\":true}", "us-east-1");

        ArgumentCaptor<Record> captor = ArgumentCaptor.forClass(Record.class);
        verify(firehoseService).putRecord(eq("my-stream"), captor.capture());
        assertEquals("{\"custom\":\"payload\"}",
                new String(captor.getValue().getData(), StandardCharsets.UTF_8));
    }

    @Test
    void extractJsonPath_topLevelField() {
        String event = "{\"source\":\"aws.s3\",\"detail-type\":\"Object Created\"}";
        assertEquals("aws.s3", invoker.extractJsonPath("$.source", event));
    }

    @Test
    void extractJsonPath_nestedField() {
        String event = "{\"detail\":{\"bucket\":{\"name\":\"my-bucket\"},\"object\":{\"key\":\"file.txt\"}}}";
        assertEquals("my-bucket", invoker.extractJsonPath("$.detail.bucket.name", event));
        assertEquals("file.txt", invoker.extractJsonPath("$.detail.object.key", event));
    }

    @Test
    void extractJsonPath_missingField_returnsNull() {
        String event = "{\"source\":\"aws.s3\"}";
        assertNull(invoker.extractJsonPath("$.detail.bucket.name", event));
    }

    @Test
    void extractJsonPath_nonTextualValueReturnsRawJson() {
        String event = "{\"detail\":{\"size\":42}}";
        assertEquals("42", invoker.extractJsonPath("$.detail.size", event));
    }

    @Test
    void applyInputPath_extractsNestedField() {
        String event = "{\"source\":\"aws.s3\",\"detail\":{\"bucket\":\"my-bucket\",\"key\":\"file.txt\"}}";
        String result = invoker.applyInputPath("$.detail", event);
        assertEquals("{\"bucket\":\"my-bucket\",\"key\":\"file.txt\"}", result);
    }

    @Test
    void applyInputPath_dollarSignReturnsFullEvent() {
        String event = "{\"source\":\"aws.s3\"}";
        assertEquals(event, invoker.applyInputPath("$", event));
    }

    @Test
    void applyInputPath_missingField_returnsFullEvent() {
        String event = "{\"source\":\"aws.s3\"}";
        assertEquals(event, invoker.applyInputPath("$.detail", event));
    }

    @Test
    void applyInputPath_scalarField_returnsText() {
        String event = "{\"detail\":{\"name\":\"test\"}}";
        assertEquals("test", invoker.applyInputPath("$.detail.name", event));
    }

    @Test
    void applyInputTransformer_substitutesVariables() {
        String eventJson = "{\"source\":\"aws.s3\",\"detail\":{\"bucket\":{\"name\":\"my-bucket\"},\"object\":{\"key\":\"photos/cat.jpg\"}}}";
        InputTransformer transformer = new InputTransformer(
                Map.of("bucket", "$.detail.bucket.name", "key", "$.detail.object.key"),
                "{\"bucket\": \"<bucket>\", \"key\": \"<key>\"}"
        );
        String result = invoker.applyInputTransformer(transformer, eventJson);
        assertEquals("{\"bucket\": \"my-bucket\", \"key\": \"photos/cat.jpg\"}", result);
    }

    @Test
    void applyInputTransformer_missingPath_substituteEmpty() {
        String eventJson = "{\"source\":\"aws.s3\"}";
        InputTransformer transformer = new InputTransformer(
                Map.of("bucket", "$.detail.bucket.name"),
                "bucket=<bucket>"
        );
        assertEquals("bucket=", invoker.applyInputTransformer(transformer, eventJson));
    }

    @Test
    void applyInputTransformer_nullTemplate_returnsEventJson() {
        String eventJson = "{\"source\":\"aws.s3\"}";
        InputTransformer transformer = new InputTransformer(Map.of(), null);
        assertEquals(eventJson, invoker.applyInputTransformer(transformer, eventJson));
    }
}
