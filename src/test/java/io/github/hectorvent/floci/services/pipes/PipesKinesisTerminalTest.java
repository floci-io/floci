package io.github.hectorvent.floci.services.pipes;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbStreamReader;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import io.github.hectorvent.floci.services.pipes.model.Pipe;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.vertx.core.Vertx;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PipesKinesisTerminalTest {
    @ParameterizedTest
    @CsvSource({"true,0,success", "false,0,success", "true,1,success", "false,1,success", "true,2,success", "false,2,success",
            "true,1,filtered", "true,2,filtered", "true,1,dlq", "true,2,dlq", "true,1,drop", "true,2,drop"})
    void terminalPagesAdvanceBeforeFilteringOrDelivery(boolean account, int recordCount, String outcome) throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        StorageFactory factory = mock(StorageFactory.class);
        Map<String, AccountAwareStorageBackend<?>> stores = new HashMap<>();
        when(factory.create(anyString(), anyString(), any())).thenAnswer(inv -> stores.computeIfAbsent(
                inv.getArgument(1), key -> AccountAwareStorageBackend.inMemory("111111111111")));
        KinesisService kinesis = spy(new KinesisService(factory, new RegionResolver("eu-west-1", "111111111111")));
        kinesis.createStream("s", 1, "eu-west-1");
        for (int i = 0; i < recordCount; i++) {
            kinesis.putRecord("s", new byte[]{1}, "pk", "eu-west-1");
        }
        kinesis.splitShard("s", "shardId-000000000000", "100", "eu-west-1");
        AtomicInteger terminalRecordPages = new AtomicInteger();
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> page = (Map<String, Object>) invocation.callRealMethod();
            if (page.get("NextShardIterator") == null && !((List<?>) page.get("Records")).isEmpty()) {
                terminalRecordPages.incrementAndGet();
            }
            return page;
        }).when(kinesis).getRecordsForAccount(anyString(), anyString(), any(), anyString());
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> page = (Map<String, Object>) invocation.callRealMethod();
            if (page.get("NextShardIterator") == null && !((List<?>) page.get("Records")).isEmpty()) {
                terminalRecordPages.incrementAndGet();
            }
            return page;
        }).when(kinesis).getRecords(anyString(), any(), anyString());
        Pipe pipe = new Pipe();
        pipe.setName("terminal");
        pipe.setArn("arn:aws:pipes:eu-west-1:111111111111:pipe/terminal");
        pipe.setSource("arn:aws:kinesis:eu-west-1:111111111111:stream/s");
        pipe.setTarget("arn:aws:lambda:eu-west-1:111111111111:function:target");
        pipe.setAccountId(account ? "111111111111" : null);
        pipe.setSourceParameters(mapper.readTree("""
                {"KinesisStreamParameters":{"BatchSize":1,
                 "DeadLetterConfig":{"Arn":"arn:aws:sqs:eu-west-1:111111111111:dlq"}}}
                """));
        if ("filtered".equals(outcome)) {
            pipe.getSourceParameters().withObject("/FilterCriteria").putArray("Filters").addObject()
                    .put("Pattern", "{\"eventSource\":[\"aws:sqs\"]}");
        }
        SqsService sqs = mock(SqsService.class);
        PipesTargetInvoker target = mock(PipesTargetInvoker.class);
        if ("dlq".equals(outcome) || "drop".equals(outcome)) {
            doThrow(new RuntimeException("target down")).when(target).invoke(eq(pipe), anyString(), eq("eu-west-1"));
        }
        if ("drop".equals(outcome)) {
            when(sqs.sendMessage(anyString(), anyString(), anyInt(), anyString()))
                    .thenThrow(new RuntimeException("dlq down"));
        }
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        PipesPoller poller = new PipesPoller(mock(Vertx.class), sqs, kinesis, mock(DynamoDbStreamReader.class),
                mock(PipesKafkaConsumerManager.class), target, new PipesFilterMatcher(mapper), mapper, config);
        try {
            for (int i = 0; i < recordCount + 3; i++) {
                poller.pollKinesis(pipe, "eu-west-1");
            }
            verify(target, times("filtered".equals(outcome) ? 0 : recordCount))
                    .invoke(eq(pipe), anyString(), eq("eu-west-1"));
            verify(sqs, times("dlq".equals(outcome) || "drop".equals(outcome) ? recordCount : 0))
                    .sendMessage(anyString(), anyString(), eq(0), eq("eu-west-1"));
            assertEquals(recordCount == 0 ? 0 : 1, terminalRecordPages.get(), "the terminal record page must be consumed once");
        } finally {
            poller.shutdown();
        }
    }
}
