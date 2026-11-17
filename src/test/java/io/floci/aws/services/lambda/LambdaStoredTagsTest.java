package io.floci.aws.services.lambda;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.lambda.model.EventSourceMapping;
import io.floci.aws.services.lambda.model.LambdaFunction;
import io.floci.aws.services.lambda.zip.CodeStore;
import io.floci.aws.services.lambda.zip.ZipExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LambdaStoredTagsTest {

    private static final String REGION = "us-east-1";

    private LambdaService service;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(inv -> AccountAwareStorageBackend.inMemory("000000000000"));
        service = new LambdaService(new LambdaFunctionStore(new InMemoryStorage<String, LambdaFunction>()),
                new WarmPool(), new CodeStore(Path.of("target/test-data/lambda-code")), new ZipExtractor(), null,
                new RegionResolver(REGION, "000000000000"), storageFactory);
    }

    private static Map<String, Object> functionRequest(String name, Map<String, String> tags) {
        return new HashMap<>(Map.of(
                "FunctionName", name,
                "Runtime", "nodejs20.x",
                "Role", "arn:aws:iam::000000000000:role/test-role",
                "Handler", "index.handler",
                "Tags", tags));
    }

    @Test
    void aFunctionsVersionsAndAliasesHaveItsTags() {
        LambdaFunction fn = service.createFunction(REGION, functionRequest("tagged-fn", Map.of("team", "data")));
        LambdaFunction version = service.publishVersion(REGION, "tagged-fn", null);
        service.createAlias(REGION, "tagged-fn", "live", version.getVersion(), null, null);
        String arn = fn.getFunctionArn();

        assertEquals(Map.of("team", "data"), service.storedTags(arn));
        assertEquals(Map.of("team", "data"), service.storedTags(arn + ":$LATEST"));
        assertEquals(Map.of("team", "data"), service.storedTags(arn + ":" + version.getVersion()));
        assertEquals(Map.of("team", "data"), service.storedTags(arn + ":live"));
        assertEquals(Map.of(), service.storedTags(arn + ":7"));
        assertEquals(Map.of(), service.storedTags(arn + ":staging"));
        assertEquals(Map.of(), service.storedTags(arn + ":1/durable-execution/run/0a1b2c"));
        assertEquals(Map.of(), service.storedTags(arn.replace("000000000000", "111111111111")));
        assertEquals(Map.of(), service.storedTags(arn.replace(REGION, "us-west-2")));
        assertEquals(Map.of(), service.storedTags(arn + "-missing"));

        service.tagResource(arn, Map.of("stage", "prod"));
        service.untagResource(arn, List.of("team"));

        assertEquals(Map.of("stage", "prod"), service.storedTags(arn + ":live"));
    }

    @Test
    void anEventSourceMappingHasItsOwnTags() {
        service.createFunction(REGION, functionRequest("mapped-fn", Map.of("team", "data")));
        Map<String, Object> request = new HashMap<>(Map.of(
                "FunctionName", "mapped-fn",
                "Topics", List.of("orders"),
                "SelfManagedEventSource", Map.of("Endpoints",
                        Map.of("KAFKA_BOOTSTRAP_SERVERS", List.of("localhost:9092"))),
                "Tags", Map.of("pipeline", "orders")));
        EventSourceMapping esm = service.createEventSourceMapping(REGION, request);
        String arn = LambdaArnUtils.eventSourceMappingArn(REGION, "000000000000", esm.getUuid());

        assertEquals(Map.of("pipeline", "orders"), service.storedTags(arn));

        service.tagResource(arn, Map.of("owner", "ingest"));

        assertEquals(Map.of("pipeline", "orders", "owner", "ingest"), service.storedTags(arn));
        assertEquals(Map.of(), service.storedTags(arn.replace(esm.getUuid(), "00000000-0000-0000-0000-000000000000")));
        assertEquals(Map.of(), service.storedTags(arn.replace("000000000000", "111111111111")));
    }

    @Test
    void taggingPutsANewTagMapInPlaceOfTheOneAReaderHolds() {
        LambdaFunction fn = service.createFunction(REGION, functionRequest("cow-fn", Map.of("team", "data")));
        Map<String, String> held = service.getFunction(REGION, "cow-fn").getTags();

        service.tagResource(fn.getFunctionArn(), Map.of("stage", "prod"));
        service.untagResource(fn.getFunctionArn(), List.of("team"));

        assertEquals(Map.of("team", "data"), held);
        assertEquals(Map.of("stage", "prod"), service.getFunction(REGION, "cow-fn").getTags());
    }
}
