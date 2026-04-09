package io.github.hectorvent.floci.services.eventbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.eventbridge.model.InputTransformer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class EventBridgeInvokerTest {

    private EventBridgeInvoker invoker;

    @BeforeEach
    void setUp() {
        invoker = new EventBridgeInvoker(
                mock(io.github.hectorvent.floci.services.lambda.LambdaService.class),
                mock(io.github.hectorvent.floci.services.sqs.SqsService.class),
                mock(io.github.hectorvent.floci.services.sns.SnsService.class),
                new ObjectMapper(),
                mock(io.github.hectorvent.floci.config.EmulatorConfig.class)
        );
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
