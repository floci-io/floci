package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationQueryHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.lambda.LambdaExecutorService;
import io.github.hectorvent.floci.services.lambda.LambdaFunctionStore;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.sqs.SqsJsonHandler;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Regression + functionality coverage for the {@code States.JsonMerge} intrinsic. AWS Step Functions
 * supports only the shallow merge form {@code States.JsonMerge($.a, $.b, false)} where the second
 * object's top-level fields win on a key conflict; deep-merge ({@code true}) and non-object arguments
 * are rejected. DPS's provisioning state machine relies on this intrinsic, which the executor
 * previously failed with "Unsupported intrinsic function: States.JsonMerge".
 */
class AslExecutorJsonMergeTest {

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
                mock(SqsJsonHandler.class),
                mock(CloudFormationQueryHandler.class),
                mock(Ec2Service.class),
                mock(S3Service.class),
                mapper,
                new JsonataEvaluator(mapper),
                mock(Instance.class));
    }

    @Test
    void shallowMergeSecondObjectWins() throws Exception {
        JsonNode root = mapper.readTree("{\"a\":{\"x\":1,\"y\":2},\"b\":{\"y\":9,\"z\":3}}");
        JsonNode out = executor.resolvePath("States.JsonMerge($.a, $.b, false)", root);
        assertTrue(out.isObject());
        assertEquals(1, out.path("x").asInt());
        assertEquals(9, out.path("y").asInt(), "second object's value should win on key conflict");
        assertEquals(3, out.path("z").asInt());
    }

    @Test
    void deepMergeTrueIsRejected() throws Exception {
        JsonNode root = mapper.readTree("{\"a\":{\"x\":1},\"b\":{\"y\":2}}");
        assertThrows(RuntimeException.class,
                () -> executor.resolvePath("States.JsonMerge($.a, $.b, true)", root));
    }

    @Test
    void nonObjectArgumentsRejected() throws Exception {
        JsonNode root = mapper.readTree("{\"a\":[1,2],\"b\":{\"y\":2}}");
        assertThrows(RuntimeException.class,
                () -> executor.resolvePath("States.JsonMerge($.a, $.b, false)", root));
    }

    @Test
    void wrongArgumentCountRejected() throws Exception {
        JsonNode root = mapper.readTree("{\"a\":{\"x\":1},\"b\":{\"y\":2}}");
        assertThrows(RuntimeException.class,
                () -> executor.resolvePath("States.JsonMerge($.a, $.b)", root));
    }
}
