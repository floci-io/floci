package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.lambda.LambdaExecutorService;
import io.github.hectorvent.floci.services.lambda.LambdaFunctionStore;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.github.hectorvent.floci.services.sns.SnsJsonHandler;
import io.github.hectorvent.floci.services.sqs.SqsJsonHandler;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the Distributed Map {@code ItemReader} with the
 * {@code arn:aws:states:::s3:listObjectsV2} resource: the emitted item shape (per the AWS
 * ListObjectsV2 ItemReader specification) and the pagination loop that follows S3's
 * continuation token so every object under a prefix is seen, not just the first page.
 */
class AslExecutorS3ListObjectsV2ItemReaderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private S3Service s3Service;
    private AslExecutor executor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        s3Service = mock(S3Service.class);
        Instance<StepFunctionsService> sfnService = mock(Instance.class);
        when(sfnService.get()).thenReturn(mock(StepFunctionsService.class));
        executor = new AslExecutor(
                mock(LambdaExecutorService.class),
                mock(LambdaFunctionStore.class),
                mock(DynamoDbService.class),
                mock(DynamoDbJsonHandler.class),
                mock(SqsJsonHandler.class), mock(SnsJsonHandler.class),
                mock(io.github.hectorvent.floci.services.cloudformation.CloudFormationQueryHandler.class),
                mock(io.github.hectorvent.floci.services.ec2.Ec2Service.class),
                s3Service,
                mock(io.github.hectorvent.floci.services.ecs.EcsService.class),
                mock(io.github.hectorvent.floci.services.ecs.EcsJsonHandler.class),
                mock(io.github.hectorvent.floci.services.eventbridge.EventBridgeHandler.class),
                mock(io.github.hectorvent.floci.services.scheduler.SchedulerService.class),
                mock(io.github.hectorvent.floci.services.scheduler.SchedulerController.class),
                mapper,
                new JsonataEvaluator(mapper),
                sfnService,
                mock(EmulatorConfig.class),
                null,
                null);
    }

    private S3Object object(String key) {
        S3Object object = new S3Object();
        object.setKey(key);
        object.setSize(key.length());
        object.setETag("\"" + key.hashCode() + "\"");
        object.setLastModified(Instant.ofEpochSecond(1700000000L));
        return object;
    }

    private StateMachine machine(String definition) {
        StateMachine sm = new StateMachine();
        sm.setName("listObjectsV2");
        sm.setStateMachineArn("arn:aws:states:us-east-1:000000000000:stateMachine:listObjectsV2");
        sm.setRoleArn("arn:aws:iam::000000000000:role/test-role");
        sm.setDefinition(definition);
        return sm;
    }

    private Execution execution(StateMachine sm, String name) {
        Execution execution = new Execution();
        execution.setName(name);
        execution.setExecutionArn(
                "arn:aws:states:us-east-1:000000000000:execution:" + sm.getName() + ":" + name);
        execution.setStateMachineArn(sm.getStateMachineArn());
        execution.setInput("{}");
        return execution;
    }

    @Test
    void emitsOneItemPerObjectShapedPerAwsListObjectsV2ItemReaderSpec() throws Exception {
        S3Service.ListObjectsResult page = new S3Service.ListObjectsResult(
                List.of(object("prefix/a.json"), object("prefix/b.json")), List.of(), false, null);
        when(s3Service.listObjectsWithPrefixes(eq("my-bucket"), eq("prefix/"), isNull(), anyInt(), isNull(), isNull()))
                .thenReturn(page);

        StateMachine sm = machine("""
                {
                  "StartAt":"Process",
                  "States":{
                    "Process":{
                      "Type":"Map",
                      "ItemReader":{
                        "Resource":"arn:aws:states:::s3:listObjectsV2",
                        "Parameters":{"Bucket":"my-bucket","Prefix":"prefix/"}
                      },
                      "ItemProcessor":{
                        "ProcessorConfig":{"Mode":"DISTRIBUTED","ExecutionType":"STANDARD"},
                        "StartAt":"PassItem",
                        "States":{"PassItem":{"Type":"Pass","End":true}}
                      },
                      "End":true
                    }
                  }
                }
                """);
        Execution execution = execution(sm, "shape");

        executor.executeSync(sm, execution, new ArrayList<HistoryEvent>(), (u, h) -> { });

        assertEquals("SUCCEEDED", execution.getStatus(), execution.getCause());
        JsonNode output = mapper.readTree(execution.getOutput());
        assertEquals(2, output.size());
        JsonNode first = output.get(0);
        assertEquals("prefix/a.json", first.path("Key").asText());
        assertEquals("STANDARD", first.path("StorageClass").asText());
        assertTrue(first.path("Etag").isTextual());
        assertTrue(first.path("LastModified").isIntegralNumber());
        assertTrue(first.path("Size").isIntegralNumber());
    }

    @Test
    void pagesThroughS3ContinuationTokenUntilNotTruncated() throws Exception {
        S3Service.ListObjectsResult firstPage = new S3Service.ListObjectsResult(
                List.of(object("prefix/1"), object("prefix/2"), object("prefix/3")),
                List.of(), true, "prefix/3");
        S3Service.ListObjectsResult secondPage = new S3Service.ListObjectsResult(
                List.of(object("prefix/4"), object("prefix/5")), List.of(), false, null);
        when(s3Service.listObjectsWithPrefixes(
                eq("pagination-bucket"), eq("prefix/"), isNull(), anyInt(), any(), isNull()))
                .thenReturn(firstPage, secondPage);

        StateMachine sm = machine("""
                {
                  "StartAt":"Process",
                  "States":{
                    "Process":{
                      "Type":"Map",
                      "ItemReader":{
                        "Resource":"arn:aws:states:::s3:listObjectsV2",
                        "Parameters":{"Bucket":"pagination-bucket","Prefix":"prefix/"}
                      },
                      "ItemProcessor":{
                        "ProcessorConfig":{"Mode":"DISTRIBUTED","ExecutionType":"STANDARD"},
                        "StartAt":"PassItem",
                        "States":{"PassItem":{"Type":"Pass","End":true}}
                      },
                      "End":true
                    }
                  }
                }
                """);
        Execution execution = execution(sm, "pagination");

        executor.executeSync(sm, execution, new ArrayList<HistoryEvent>(), (u, h) -> { });

        assertEquals("SUCCEEDED", execution.getStatus(), execution.getCause());
        JsonNode output = mapper.readTree(execution.getOutput());
        assertEquals(5, output.size());
        for (int i = 0; i < 5; i++) {
            assertEquals("prefix/" + (i + 1), output.get(i).path("Key").asText());
        }
        verify(s3Service, times(2)).listObjectsWithPrefixes(
                eq("pagination-bucket"), eq("prefix/"), isNull(), anyInt(), any(), isNull());
    }
}
