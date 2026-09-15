package io.github.hectorvent.floci.services.appsync.graphql.datasource;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.appsync.model.DataSource;
import io.github.hectorvent.floci.services.appsync.model.DataSourceType;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What reaches the function, and what a function's answer becomes. */
class LambdaDataSourceInvokerTest {

    private static final String FN_ARN = "arn:aws:lambda:eu-west-1:000000000000:function:resolver-fn";

    private final LambdaService lambda = mock(LambdaService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final LambdaDataSourceInvoker invoker = new LambdaDataSourceInvoker(lambda, mapper);

    private DataSource dataSource() {
        DataSource ds = new DataSource();
        ds.setName("resolverFn");
        ds.setType(DataSourceType.AWS_LAMBDA);
        ds.setLambdaConfig(Map.of("lambdaFunctionArn", FN_ARN));
        return ds;
    }

    private void answers(String json) {
        when(lambda.invokeArn(eq(FN_ARN), any(), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult(200, null, json.getBytes(StandardCharsets.UTF_8), null, "req-1"));
    }

    private String capturePayload() {
        ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
        verify(lambda).invokeArn(eq(FN_ARN), captor.capture(), eq(InvocationType.RequestResponse));
        return new String(captor.getValue(), StandardCharsets.UTF_8);
    }

    @Test
    void onlyThePayloadReachesTheFunction() {
        answers("{\"id\": \"1\"}");

        Object result = invoker.invoke(dataSource(),
                Map.of("operation", "Invoke", "payload", Map.of("field", "getMessages")), "eu-west-1");

        // The operation is AppSync's envelope, not something the function should see.
        assertEquals("{\"field\":\"getMessages\"}", capturePayload());
        assertEquals(Map.of("id", "1"), result);
    }

    @Test
    void aRequestWithNoEnvelopeIsThePayload() {
        answers("{}");

        invoker.invoke(dataSource(), Map.of("field", "getMessages"), "eu-west-1");

        assertEquals("{\"field\":\"getMessages\"}", capturePayload());
    }

    @Test
    void aBatchInvokeAnswersTheListTheFunctionReturned() {
        answers("[{\"id\": \"1\"}, {\"id\": \"2\"}]");

        Object result = invoker.invoke(dataSource(),
                Map.of("operation", "BatchInvoke", "payload", List.of(Map.of("id", "1"), Map.of("id", "2"))),
                "eu-west-1");

        assertEquals(List.of(Map.of("id", "1"), Map.of("id", "2")), result);
    }

    @Test
    void aFunctionErrorFailsTheFieldRatherThanResolvingToTheErrorObject() {
        when(lambda.invokeArn(eq(FN_ARN), any(), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult(200, "Unhandled",
                        "{\"errorMessage\": \"boom\"}".getBytes(StandardCharsets.UTF_8), null, "req-1"));

        AwsException e = assertThrows(AwsException.class, () -> invoker.invoke(dataSource(),
                Map.of("payload", Map.of()), "eu-west-1"));

        assertTrue(e.getMessage().contains("boom"), e.getMessage());
    }

    @Test
    void anEmptyAnswerIsNull() {
        answers("");

        assertNull(invoker.invoke(dataSource(), Map.of("payload", Map.of()), "eu-west-1"));
    }

    @Test
    void aNonJsonAnswerComesBackAsText() {
        answers("just text");

        assertEquals("just text", invoker.invoke(dataSource(), Map.of("payload", Map.of()), "eu-west-1"));
    }

    @Test
    void aDataSourceWithoutAFunctionArnFailsClearly() {
        DataSource ds = dataSource();
        ds.setLambdaConfig(Map.of());

        AwsException e = assertThrows(AwsException.class,
                () -> invoker.invoke(ds, Map.of(), "eu-west-1"));

        assertTrue(e.getMessage().contains("lambdaFunctionArn"), e.getMessage());
    }
}
