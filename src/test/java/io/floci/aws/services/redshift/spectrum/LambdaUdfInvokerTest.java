package io.floci.aws.services.redshift.spectrum;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.iam.IamPolicyEvaluator;
import io.floci.aws.services.iam.ResourcePolicyProvider.ResourcePolicy;
import io.floci.aws.services.iam.ServiceRoleAuthorizer;
import io.floci.aws.services.lambda.LambdaResourcePolicyProvider;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.lambda.model.InvocationType;
import io.floci.aws.services.lambda.model.InvokeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LambdaUdfInvokerTest {
    private static final String ACCOUNT = "000000000000";
    private static final String ROLE = "arn:aws:iam::000000000000:role/udf";
    private static final String FUNCTION_ARN = "arn:aws:lambda:us-east-1:000000000000:function:sum";
    private static final SpectrumSession SESSION = new SpectrumSession(ACCOUNT, ACCOUNT + ":cluster-1", "dev",
            List.of(), false);
    private static final ObjectMapper JSON = new ObjectMapper();

    private LambdaService lambda;
    private ServiceRoleAuthorizer authorizer;
    private LambdaResourcePolicyProvider lambdaResourcePolicies;
    private EmulatorConfig config;
    private AtomicLong now;
    private List<Long> sleeps;
    private LambdaUdfInvoker invoker;

    @BeforeEach
    void setUp() {
        lambda = mock(LambdaService.class);
        authorizer = mock(ServiceRoleAuthorizer.class);
        when(authorizer.refusal(anyString(), anyString(), anyString(), any(), anyList()))
                .thenReturn(Optional.empty());
        lambdaResourcePolicies = mock(LambdaResourcePolicyProvider.class);
        when(lambdaResourcePolicies.getResourcePolicies(anyString(), anyString())).thenReturn(List.of());
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().iam().enforcementEnabled()).thenReturn(true);
        RegionResolver regions = mock(RegionResolver.class);
        when(regions.getDefaultRegion()).thenReturn("us-east-1");
        when(regions.partitionForRegion("us-east-1")).thenReturn("aws");
        now = new AtomicLong(1_000);
        sleeps = new ArrayList<>();
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(now.get());
            }
        };
        invoker = new LambdaUdfInvoker(lambda, authorizer, lambdaResourcePolicies, new IamPolicyEvaluator(JSON),
                config, regions, clock, millis -> {
            sleeps.add(millis);
            now.addAndGet(millis);
        });
    }

    private static ExternalFunctionBinding function(List<String> args, String returns, int maxRows, long maxBytes,
                                                    long retryMillis) {
        return new ExternalFunctionBinding(ACCOUNT, SESSION.clusterKey(), "dev", "public", "exfunc_sum", args,
                returns, "STABLE", "sum", ROLE, retryMillis, maxRows, maxBytes);
    }

    private static ExternalFunctionBinding twoInts() {
        return function(List.of("integer", "integer"), "integer", Integer.MAX_VALUE, 5L * 1024 * 1024, 20_000);
    }

    private static InvokeResult ok(String body) {
        return new InvokeResult(200, null, body.getBytes(StandardCharsets.UTF_8), null, "req");
    }

    private static List<List<String>> rows(String[]... rows) {
        return Arrays.stream(rows).map(Arrays::asList).toList();
    }

    private JsonNode lastRequest() throws Exception {
        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(lambda, atLeastOnce()).invokeArn(eq(FUNCTION_ARN), payload.capture(),
                eq(InvocationType.RequestResponse));
        return JSON.readTree(payload.getValue());
    }

    @Test
    void sendsTheDocumentedRequestAndReadsTheResultsInOrder() throws Exception {
        when(lambda.invokeArn(eq(FUNCTION_ARN), any(), any())).thenReturn(
                ok("{\"success\":true,\"num_records\":3,\"results\":[3,null,10]}"));

        List<String> results = invoker.invoke(twoInts(), SESSION, "analyst", 5678234L,
                rows(new String[] {"1", "2"}, new String[] {"3", null}, new String[] {"4", "6"}));

        assertThat(results, equalTo(Arrays.asList("3", null, "10")));
        JsonNode request = lastRequest();
        assertThat(request.get("cluster").textValue(),
                equalTo("arn:aws:redshift:us-east-1:000000000000:cluster:cluster-1"));
        assertThat(request.get("user").textValue(), equalTo("analyst"));
        assertThat(request.get("database").textValue(), equalTo("dev"));
        assertThat(request.get("external_function").textValue(), equalTo("public.exfunc_sum"));
        assertThat(request.get("query_id").longValue(), equalTo(5678234L));
        assertThat(request.get("num_records").intValue(), equalTo(3));
        assertThat(request.get("request_id").textValue().length(), equalTo(36));
        assertThat(request.get("arguments").toString(), equalTo("[[1,2],[3,null],[4,6]]"));
    }

    @Test
    void aFunctionOfOneArgumentGetsOneElementArrays() throws Exception {
        when(lambda.invokeArn(eq(FUNCTION_ARN), any(), any())).thenReturn(
                ok("{\"success\":true,\"num_records\":2,\"results\":[\"A\",null]}"));

        List<String> results = invoker.invoke(
                function(List.of("character varying"), "varchar", 100, 1024, 0), SESSION, "u", 1,
                rows(new String[] {"a"}, new String[] {null}));

        assertThat(results, equalTo(Arrays.asList("A", null)));
        assertThat(lastRequest().get("arguments").toString(), equalTo("[[\"a\"],[null]]"));
    }

    @Test
    void aJsonEncodedStringResponseIsAccepted() throws Exception {
        String encoded = JSON.writeValueAsString("{\"success\":true,\"num_records\":2,\"results\":[3,10]}");
        when(lambda.invokeArn(eq(FUNCTION_ARN), any(), any())).thenReturn(ok(encoded));

        List<String> results = invoker.invoke(twoInts(), SESSION, "u", 1,
                rows(new String[] {"1", "2"}, new String[] {"4", "6"}));

        assertThat(results, equalTo(List.of("3", "10")));
    }

    @Test
    void splitsRowsIntoBatchesOfAtMostMaxBatchRows() throws Exception {
        when(lambda.invokeArn(eq(FUNCTION_ARN), any(), any())).thenReturn(
                ok("{\"success\":true,\"num_records\":2,\"results\":[1,2]}"),
                ok("{\"success\":true,\"num_records\":1,\"results\":[3]}"));

        List<String> results = invoker.invoke(
                function(List.of("integer"), "integer", 2, 5L * 1024 * 1024, 0), SESSION, "u", 1,
                rows(new String[] {"1"}, new String[] {"2"}, new String[] {"3"}));

        assertThat(results, equalTo(List.of("1", "2", "3")));
        ArgumentCaptor<byte[]> payloads = ArgumentCaptor.forClass(byte[].class);
        verify(lambda, times(2)).invokeArn(eq(FUNCTION_ARN), payloads.capture(), any());
        assertThat(JSON.readTree(payloads.getAllValues().get(0)).get("arguments").toString(), equalTo("[[1],[2]]"));
        assertThat(JSON.readTree(payloads.getAllValues().get(1)).get("arguments").toString(), equalTo("[[3]]"));
    }

    @Test
    void splitsRowsIntoBatchesByTheirSizeAndSendsAnOversizedRowAlone() throws Exception {
        when(lambda.invokeArn(eq(FUNCTION_ARN), any(), any())).thenAnswer(call -> {
            JsonNode request = JSON.readTree((byte[]) call.getArgument(1));
            int count = request.get("num_records").intValue();
            return ok("{\"success\":true,\"num_records\":" + count + ",\"results\":["
                    + String.join(",", Collections.nCopies(count, "0")) + "]}");
        });

        // the first row alone is over the size limit; the two small ones fit together in the next batch
        invoker.invoke(function(List.of("varchar"), "integer", 100, 1024, 0), SESSION, "u", 1,
                rows(new String[] {"x".repeat(2000)}, new String[] {"y"}, new String[] {"z"}));

        ArgumentCaptor<byte[]> payloads = ArgumentCaptor.forClass(byte[].class);
        verify(lambda, times(2)).invokeArn(eq(FUNCTION_ARN), payloads.capture(), any());
        assertThat(JSON.readTree(payloads.getAllValues().get(0)).get("num_records").intValue(), equalTo(1));
        assertThat(JSON.readTree(payloads.getAllValues().get(1)).get("arguments").toString(),
                equalTo("[[\"y\"],[\"z\"]]"));
    }

    @Test
    void batchesCountArgumentsArrayBracketsAndCommasAgainstMaxBatchSize() throws Exception {
        long limit = 20;
        when(lambda.invokeArn(eq(FUNCTION_ARN), any(), any())).thenAnswer(call -> {
            JsonNode request = JSON.readTree((byte[]) call.getArgument(1));
            int count = request.get("num_records").intValue();
            return ok("{\"success\":true,\"num_records\":" + count + ",\"results\":["
                    + String.join(",", Collections.nCopies(count, "0")) + "]}");
        });

        // each row is "[1]" (3 bytes); 4 rows serialize to "[[1],[1],[1],[1]]" (17 bytes <= 20), 5 rows would be 21
        List<List<String>> input = Collections.nCopies(10, List.of("1"));
        invoker.invoke(function(List.of("integer"), "integer", 100, limit, 0), SESSION, "u", 1, input);

        ArgumentCaptor<byte[]> payloads = ArgumentCaptor.forClass(byte[].class);
        verify(lambda, times(3)).invokeArn(eq(FUNCTION_ARN), payloads.capture(), any());
        for (byte[] payload : payloads.getAllValues()) {
            String argumentsJson = JSON.readTree(payload).get("arguments").toString();
            assertThat((long) argumentsJson.getBytes(StandardCharsets.UTF_8).length, lessThanOrEqualTo(limit));
        }
    }

    @Test
    void anArnFunctionIsInvokedAsGiven() {
        String arn = "arn:aws:lambda:us-east-1:000000000000:function:other:7";
        ExternalFunctionBinding function = new ExternalFunctionBinding(ACCOUNT, SESSION.clusterKey(), "dev",
                "public", "f", List.of("integer"), "integer", "STABLE", arn, ROLE, 0, 10, 1024);
        when(lambda.invokeArn(eq(arn), any(), any())).thenReturn(
                ok("{\"success\":true,\"num_records\":1,\"results\":[5]}"));

        assertThat(invoker.invoke(function, SESSION, "u", 1, rows(new String[] {"1"})), equalTo(List.of("5")));
    }

    @Test
    void noRowsMeansNoCall() {
        assertThat(invoker.invoke(twoInts(), SESSION, "u", 1, List.of()), hasSize(0));

        verify(lambda, never()).invokeArn(anyString(), any(), any());
    }

    @Test
    void aRoleThatMayNotInvokeTheFunctionIsRefusedBeforeAnyCall() {
        when(authorizer.refusal(eq(ROLE), eq(ACCOUNT), anyString(), isNull(), anyList()))
                .thenReturn(Optional.of("The role does not allow lambda:InvokeFunction."));

        SpectrumSqlException refused = assertThrows(SpectrumSqlException.class,
                () -> invoker.invoke(twoInts(), SESSION, "u", 1, rows(new String[] {"1", "2"})));

        assertThat(refused.sqlState(), equalTo("42501"));
        verify(lambda, never()).invokeArn(anyString(), any(), any());
    }

    @Test
    void successFalseSurfacesTheErrorMessage() {
        when(lambda.invokeArn(eq(FUNCTION_ARN), any(), any())).thenReturn(
                ok("{\"success\":false,\"error_msg\":\"my function is not working\"}"));

        SpectrumSqlException failed = assertThrows(SpectrumSqlException.class,
                () -> invoker.invoke(twoInts(), SESSION, "u", 1, rows(new String[] {"1", "2"})));

        assertThat(failed.getMessage(), containsString("my function is not working"));
    }

    @Test
    void aFunctionErrorSurfacesTheRuntimeMessage() {
        when(lambda.invokeArn(eq(FUNCTION_ARN), any(), any())).thenReturn(new InvokeResult(200, "Unhandled",
                "{\"errorMessage\":\"boom\",\"errorType\":\"Error\"}".getBytes(StandardCharsets.UTF_8), null, "r"));

        SpectrumSqlException failed = assertThrows(SpectrumSqlException.class,
                () -> invoker.invoke(twoInts(), SESSION, "u", 1, rows(new String[] {"1", "2"})));

        assertThat(failed.getMessage(), containsString("boom"));
    }

    @Test
    void aTimedOutInvocationFailsTheCall() {
        InvokeResult timedOut = ok("");
        timedOut.setTimedOut(true);
        when(lambda.invokeArn(eq(FUNCTION_ARN), any(), any())).thenReturn(timedOut);

        SpectrumSqlException failed = assertThrows(SpectrumSqlException.class,
                () -> invoker.invoke(twoInts(), SESSION, "u", 1, rows(new String[] {"1", "2"})));

        assertThat(failed.getMessage(), containsString("timed out"));
    }

    @Test
    void aResponseWithTheWrongNumberOfResultsFailsTheCall() {
        when(lambda.invokeArn(eq(FUNCTION_ARN), any(), any())).thenReturn(
                ok("{\"success\":true,\"num_records\":1,\"results\":[1]}"));

        SpectrumSqlException failed = assertThrows(SpectrumSqlException.class,
                () -> invoker.invoke(twoInts(), SESSION, "u", 1,
                        rows(new String[] {"1", "2"}, new String[] {"3", "4"})));

        assertThat(failed.getMessage(), containsString("returned 1 results"));
    }

    @Test
    void aNumRecordsThatDisagreesWithTheRowsFailsEvenWhenTheResultsFit() {
        when(lambda.invokeArn(eq(FUNCTION_ARN), any(), any())).thenReturn(
                ok("{\"success\":true,\"num_records\":5,\"results\":[1]}"),
                ok("{\"success\":true,\"results\":[1]}"),
                ok("{\"success\":true,\"num_records\":\"1\",\"results\":[1]}"));

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThrows(SpectrumSqlException.class,
                    () -> invoker.invoke(twoInts(), SESSION, "u", 1, rows(new String[] {"1", "2"})));
        }
    }

    @Test
    void successMustBeAJsonBoolean() {
        when(lambda.invokeArn(eq(FUNCTION_ARN), any(), any())).thenReturn(
                ok("{\"success\":\"true\",\"num_records\":1,\"results\":[1]}"),
                ok("{\"success\":1,\"num_records\":1,\"results\":[1]}"));

        for (int attempt = 0; attempt < 2; attempt++) {
            assertThrows(SpectrumSqlException.class,
                    () -> invoker.invoke(twoInts(), SESSION, "u", 1, rows(new String[] {"1", "2"})));
        }
    }

    @Test
    void successFalseWithoutAnErrorMessageStillFails() {
        when(lambda.invokeArn(eq(FUNCTION_ARN), any(), any())).thenReturn(ok("{\"success\":false}"));

        SpectrumSqlException failed = assertThrows(SpectrumSqlException.class,
                () -> invoker.invoke(twoInts(), SESSION, "u", 1, rows(new String[] {"1", "2"})));

        assertThat(failed.getMessage(), containsString("no error_msg"));
    }

    @Test
    void aResponseThatIsNotJsonOrHasNoResultsFailsTheCall() {
        when(lambda.invokeArn(eq(FUNCTION_ARN), any(), any())).thenReturn(ok("not json"),
                ok("{\"success\":true,\"num_records\":1}"));

        SpectrumSqlException notJson = assertThrows(SpectrumSqlException.class,
                () -> invoker.invoke(twoInts(), SESSION, "u", 1, rows(new String[] {"1", "2"})));
        SpectrumSqlException noResults = assertThrows(SpectrumSqlException.class,
                () -> invoker.invoke(twoInts(), SESSION, "u", 1, rows(new String[] {"1", "2"})));

        assertThat(notJson.getMessage(), containsString("not JSON"));
        assertThat(noResults.getMessage(), containsString("no results array"));
    }

    @Test
    void aResponseThatIsNotAJsonObjectFailsTheCall() {
        when(lambda.invokeArn(eq(FUNCTION_ARN), any(), any())).thenReturn(ok("[]"), ok(""));

        for (int attempt = 0; attempt < 2; attempt++) {
            assertThrows(SpectrumSqlException.class,
                    () -> invoker.invoke(twoInts(), SESSION, "u", 1, rows(new String[] {"1", "2"})));
        }
    }

    @Test
    void aFunctionErrorWithAnEmptyPayloadReportsTheErrorType() {
        when(lambda.invokeArn(eq(FUNCTION_ARN), any(), any()))
                .thenReturn(new InvokeResult(200, "Unhandled", new byte[0], null, "r"));

        SpectrumSqlException failed = assertThrows(SpectrumSqlException.class,
                () -> invoker.invoke(twoInts(), SESSION, "u", 1, rows(new String[] {"1", "2"})));

        assertThat(failed.getMessage(), containsString("Unhandled"));
    }

    @Test
    void aNumericResultKeepsEveryDigitThroughTheJsonParser() {
        ExternalFunctionBinding function = function(List.of("integer"), "numeric(30,10)", 10, 1024, 0);
        when(lambda.invokeArn(eq(FUNCTION_ARN), any(), any())).thenReturn(ok(
                "{\"success\":true,\"num_records\":2,\"results\":[12345678901234567890.1234567890,3.10]}"));

        List<String> results = invoker.invoke(function, SESSION, "u", 1,
                rows(new String[] {"1"}, new String[] {"2"}));

        assertThat(results, equalTo(List.of("12345678901234567890.1234567890", "3.10")));
    }

    @Test
    void aRowWithTheWrongNumberOfValuesFailsBeforeAnyCall() {
        assertThrows(SpectrumSqlException.class,
                () -> invoker.invoke(twoInts(), SESSION, "u", 1, rows(new String[] {"1"})));
        assertThrows(SpectrumSqlException.class,
                () -> invoker.invoke(twoInts(), SESSION, "u", 1, rows(new String[] {"1", "2", "3"})));

        verify(lambda, never()).invokeArn(anyString(), any(), any());
    }

    @Test
    void anArgumentThatIsNotAValueOfItsTypeFailsBeforeAnyCall() {
        SpectrumSqlException failed = assertThrows(SpectrumSqlException.class,
                () -> invoker.invoke(twoInts(), SESSION, "u", 1, rows(new String[] {"abc", "2"})));

        assertThat(failed.sqlState(), equalTo("22P02"));
        verify(lambda, never()).invokeArn(anyString(), any(), any());
    }

    @Test
    void aFunctionWithoutArgumentsGetsEmptyRecords() throws Exception {
        ExternalFunctionBinding function = function(List.of(), "integer", 10, 1024, 0);
        when(lambda.invokeArn(eq(FUNCTION_ARN), any(), any())).thenReturn(
                ok("{\"success\":true,\"num_records\":2,\"results\":[1,1]}"));

        invoker.invoke(function, SESSION, "u", 1, rows(new String[] {}, new String[] {}));

        assertThat(lastRequest().get("arguments").toString(), equalTo("[[],[]]"));
    }

    @Test
    void aFunctionGivenAsAccountFunctionNameIsInvokedInThatAccountWhenGranted() {
        String arn = "arn:aws:lambda:us-east-1:111111111111:function:sum";
        ExternalFunctionBinding function = new ExternalFunctionBinding(ACCOUNT, SESSION.clusterKey(), "dev",
                "public", "f", List.of("integer"), "integer", "STABLE", "111111111111:function:sum", ROLE, 0, 10,
                1024);
        String grantPolicy = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"AWS":"%s"},"Action":"lambda:InvokeFunction","Resource":"%s"}]}
                """.formatted(ROLE, arn);
        when(lambdaResourcePolicies.getResourcePolicies("lambda", arn))
                .thenReturn(List.of(new ResourcePolicy(grantPolicy, "111111111111")));
        when(lambda.invokeArn(eq(arn), any(), any())).thenReturn(
                ok("{\"success\":true,\"num_records\":1,\"results\":[5]}"));

        assertThat(invoker.invoke(function, SESSION, "u", 1, rows(new String[] {"1"})), equalTo(List.of("5")));
        verify(authorizer).refusal(eq(ROLE), eq(ACCOUNT), anyString(), isNull(), anyList());
    }

    @Test
    void aCrossAccountFunctionWithoutResourcePolicyGrantIsRefusedWhenEnforcementIsOn() {
        String arn = "arn:aws:lambda:us-east-1:111111111111:function:sum";
        ExternalFunctionBinding function = new ExternalFunctionBinding(ACCOUNT, SESSION.clusterKey(), "dev",
                "public", "f", List.of("integer"), "integer", "STABLE", arn, ROLE, 0, 10, 1024);
        when(lambdaResourcePolicies.getResourcePolicies("lambda", arn))
                .thenReturn(List.of(new ResourcePolicy(null, "111111111111")));

        SpectrumSqlException refused = assertThrows(SpectrumSqlException.class,
                () -> invoker.invoke(function, SESSION, "u", 1, rows(new String[] {"1"})));

        assertThat(refused.sqlState(), equalTo("42501"));
        verify(lambda, never()).invokeArn(anyString(), any(), any());
    }

    @Test
    void aCrossAccountFunctionRunsWithoutResourcePolicyWhenEnforcementIsOff() {
        when(config.services().iam().enforcementEnabled()).thenReturn(false);
        String arn = "arn:aws:lambda:us-east-1:111111111111:function:sum";
        ExternalFunctionBinding function = new ExternalFunctionBinding(ACCOUNT, SESSION.clusterKey(), "dev",
                "public", "f", List.of("integer"), "integer", "STABLE", arn, ROLE, 0, 10, 1024);
        when(lambda.invokeArn(eq(arn), any(), any())).thenReturn(
                ok("{\"success\":true,\"num_records\":1,\"results\":[5]}"));

        assertThat(invoker.invoke(function, SESSION, "u", 1, rows(new String[] {"1"})), equalTo(List.of("5")));
    }

    @Test
    void aSameAccountFunctionWithExplicitDenyInResourcePolicyIsRefused() {
        String denyPolicy = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Deny",
                "Principal":{"AWS":"%s"},"Action":"lambda:InvokeFunction","Resource":"%s"}]}
                """.formatted(ROLE, FUNCTION_ARN);
        when(lambdaResourcePolicies.getResourcePolicies("lambda", FUNCTION_ARN))
                .thenReturn(List.of(new ResourcePolicy(denyPolicy, ACCOUNT)));

        SpectrumSqlException refused = assertThrows(SpectrumSqlException.class,
                () -> invoker.invoke(twoInts(), SESSION, "u", 1, rows(new String[] {"1", "2"})));

        assertThat(refused.sqlState(), equalTo("42501"));
        verify(lambda, never()).invokeArn(anyString(), any(), any());
    }

    @Test
    void aFunctionNameWithAQualifierIsAnArnOfThatVersion() {
        String arn = "arn:aws:lambda:us-east-1:000000000000:function:sum:7";
        ExternalFunctionBinding function = new ExternalFunctionBinding(ACCOUNT, SESSION.clusterKey(), "dev",
                "public", "f", List.of("integer"), "integer", "STABLE", "sum:7", ROLE, 0, 10, 1024);
        when(lambda.invokeArn(eq(arn), any(), any())).thenReturn(
                ok("{\"success\":true,\"num_records\":1,\"results\":[5]}"));

        assertThat(invoker.invoke(function, SESSION, "u", 1, rows(new String[] {"1"})), equalTo(List.of("5")));
    }

    @Test
    void anArnThatIsNotALambdaFunctionFailsBeforeAnyCall() {
        for (String reference : List.of("arn:foo", "arn:aws:sqs:us-east-1:000000000000:queue")) {
            ExternalFunctionBinding function = new ExternalFunctionBinding(ACCOUNT, SESSION.clusterKey(), "dev",
                    "public", "f", List.of("integer"), "integer", "STABLE", reference, ROLE, 0, 10, 1024);

            assertThrows(SpectrumSqlException.class,
                    () -> invoker.invoke(function, SESSION, "u", 1, rows(new String[] {"1"})));
        }
        verify(lambda, never()).invokeArn(anyString(), any(), any());
    }

    @Test
    void anUnexpectedFailureOfTheLambdaServiceIsAnSqlError() {
        when(lambda.invokeArn(eq(FUNCTION_ARN), any(), any())).thenThrow(new IllegalStateException("executor down"));

        SpectrumSqlException failed = assertThrows(SpectrumSqlException.class,
                () -> invoker.invoke(twoInts(), SESSION, "u", 1, rows(new String[] {"1", "2"})));

        assertThat(failed.getMessage(), containsString("executor down"));
    }

    @Test
    void aHugeRetryTimeoutDoesNotOverflowIntoNoRetry() {
        AwsException throttled = new AwsException("TooManyRequestsException", "Rate Exceeded.", 429);
        when(lambda.invokeArn(eq(FUNCTION_ARN), any(), any())).thenThrow(throttled).thenReturn(
                ok("{\"success\":true,\"num_records\":1,\"results\":[3]}"));

        List<String> results = invoker.invoke(
                function(List.of("integer"), "integer", 10, 1024, Long.MAX_VALUE), SESSION, "u", 1,
                rows(new String[] {"1"}));

        assertThat(results, equalTo(List.of("3")));
    }

    @Test
    void aMistypedResultFailsTheCall() {
        when(lambda.invokeArn(eq(FUNCTION_ARN), any(), any())).thenReturn(
                ok("{\"success\":true,\"num_records\":1,\"results\":[\"three\"]}"));

        SpectrumSqlException failed = assertThrows(SpectrumSqlException.class,
                () -> invoker.invoke(twoInts(), SESSION, "u", 1, rows(new String[] {"1", "2"})));

        assertThat(failed.sqlState(), equalTo("22P02"));
    }

    @Test
    void aThrottledCallIsRetriedWithGrowingPausesUntilItSucceeds() {
        AwsException throttled = new AwsException("TooManyRequestsException", "Rate Exceeded.", 429);
        when(lambda.invokeArn(eq(FUNCTION_ARN), any(), any())).thenThrow(throttled, throttled).thenReturn(
                ok("{\"success\":true,\"num_records\":1,\"results\":[3]}"));

        List<String> results = invoker.invoke(twoInts(), SESSION, "u", 1, rows(new String[] {"1", "2"}));

        assertThat(results, equalTo(List.of("3")));
        assertThat(sleeps, equalTo(List.of(50L, 100L)));
    }

    @Test
    void aCallThatStaysThrottledFailsOnceTheRetryTimeoutIsSpent() {
        when(lambda.invokeArn(eq(FUNCTION_ARN), any(), any()))
                .thenThrow(new AwsException("TooManyRequestsException", "Rate Exceeded.", 429));

        SpectrumSqlException failed = assertThrows(SpectrumSqlException.class,
                () -> invoker.invoke(function(List.of("integer"), "integer", 10, 1024, 300), SESSION, "u", 1,
                        rows(new String[] {"1"})));

        assertThat(failed.getMessage(), containsString("throttled"));
        // the last pause is cut to what is left of the 300 ms, and one more attempt follows it
        assertThat(sleeps, equalTo(List.of(50L, 100L, 150L)));
        verify(lambda, times(4)).invokeArn(anyString(), any(), any());
    }

    @Test
    void aThrottledCallIsNotRetriedWithAZeroRetryTimeout() {
        when(lambda.invokeArn(eq(FUNCTION_ARN), any(), any()))
                .thenThrow(new AwsException("TooManyRequestsException", "Rate Exceeded.", 429));

        assertThrows(SpectrumSqlException.class,
                () -> invoker.invoke(function(List.of("integer"), "integer", 10, 1024, 0), SESSION, "u", 1,
                        rows(new String[] {"1"})));

        assertThat(sleeps, hasSize(0));
    }

    @Test
    void anotherLambdaErrorFailsTheCallWithoutRetry() {
        when(lambda.invokeArn(eq(FUNCTION_ARN), any(), any()))
                .thenThrow(new AwsException("ResourceNotFoundException", "Function not found", 404));

        SpectrumSqlException failed = assertThrows(SpectrumSqlException.class,
                () -> invoker.invoke(twoInts(), SESSION, "u", 1, rows(new String[] {"1", "2"})));

        assertThat(failed.getMessage(), containsString("Function not found"));
        assertThat(sleeps, hasSize(0));
        verify(lambda, times(1)).invokeArn(anyString(), any(), any());
    }
}
