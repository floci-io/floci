package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.testing.FourWorkerThreadsProfile;
import io.github.hectorvent.floci.testing.HeldWorkerProbes;
import io.github.hectorvent.floci.testing.HeldWorkerProbes.Probe;
import io.github.hectorvent.floci.testing.HeldWorkerProbes.Scope;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.github.hectorvent.floci.testing.HeldWorkerProbes.SCOPES;
import static io.github.hectorvent.floci.testing.HeldWorkerProbes.signed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * A synchronous Invoke or function URL request waits for its function without holding one of the
 * shared request workers. The profile leaves four workers, and four invocations held at the service
 * boundary would otherwise queue every request that still needs a worker, such as the health check
 * and SSM, behind them. The service is mocked, so the test exercises the REST dispatch and its
 * request scope, not a runtime.
 */
@QuarkusTest
@TestProfile(FourWorkerThreadsProfile.class)
class LambdaSynchronousInvokeDispatchIntegrationTest {

    @TestHTTPResource("/")
    URI endpoint;

    @InjectMock
    LambdaService lambdaService;

    @Inject
    RequestContext context;

    @Inject
    ObjectMapper mapper;

    @ParameterizedTest
    @ValueSource(strings = {"Invoke", "GET", "POST", "PUT", "DELETE", "PATCH"})
    void heldInvocationsLeaveOtherRequestsServed(String route) throws Exception {
        boolean invoke = "Invoke".equals(route);
        CountDownLatch invoking = new CountDownLatch(SCOPES.size());
        Map<String, Held> held = new ConcurrentHashMap<>();
        Map<String, CompletableFuture<InvokeResult>> releases = new ConcurrentHashMap<>();
        for (Scope scope : SCOPES) {
            releases.put(target(scope, invoke), new CompletableFuture<>());
        }
        // An unknown URL id gets null, which the controller answers with a 404 the await below reports.
        when(lambdaService.getTargetByUrlId(anyString())).thenAnswer(call -> SCOPES.stream()
                .filter(candidate -> candidate.identity().equals(call.getArgument(0)))
                .findFirst()
                .map(LambdaSynchronousInvokeDispatchIntegrationTest::function)
                .orElse(null));
        // Each answer only records what it saw and waits for its own release; the checks run on the
        // test thread, so a mismatch fails there with its own message instead of a 500 to the client.
        doAnswer(call -> hold(call.getArgument(1), call.getArgument(4), held, invoking, releases))
                .when(lambdaService).invoke(anyString(), anyString(), any(), any(), any(), any(), any());
        doAnswer(call -> hold(call.getArgument(0), call.getArgument(2), held, invoking, releases))
                .when(lambdaService).invokeArn(anyString(), any(), any());

        List<CompletableFuture<HttpResponse<String>>> invocations = new ArrayList<>();
        try (HttpClient client = HttpClient.newHttpClient()) {
            try {
                for (Scope scope : SCOPES) {
                    String path = invoke
                            ? "2015-03-31/functions/" + functionName(scope) + "/invocations"
                            : "lambda-url/" + scope.identity() + "/check";
                    invocations.add(client.sendAsync(signed(endpoint.resolve(path), scope, "lambda")
                            .header("Content-Type", "application/json")
                            .method(invoke ? "POST" : route, HttpRequest.BodyPublishers.ofString("{}"))
                            .build(), HttpResponse.BodyHandlers.ofString()));
                }
                assertTrue(invoking.await(HeldWorkerProbes.ARRIVAL_SECONDS, TimeUnit.SECONDS),
                        () -> "every invocation reaches LambdaService; answered without it: " + answered(invocations));
                assertEquals(releases.keySet(), held.keySet(), "each function is invoked once, by its own target");
                for (Scope scope : SCOPES) {
                    Held invocation = held.get(target(scope, invoke));
                    assertEquals(InvocationType.RequestResponse, invocation.type());
                    assertEquals(scope, invocation.scope(), "an invocation runs in the account and Region of its function");
                }

                // The health check and SSM need a worker; DynamoDB and EC2 run on virtual threads.
                List<Probe> probes = new ArrayList<>(HeldWorkerProbes.sharedProbes(client, endpoint));
                probes.add(HeldWorkerProbes.probe("DynamoDB ListTables", client,
                        signed(endpoint, SCOPES.getFirst(), "dynamodb")
                                .header("Content-Type", "application/x-amz-json-1.0")
                                .header("X-Amz-Target", "DynamoDB_20120810.ListTables")
                                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                .build(), tables -> {
                                    assertEquals(200, tables.statusCode());
                                    assertTrue(mapper.readTree(tables.body()).path("TableNames").isArray());
                                }));
                HeldWorkerProbes.assertServed(probes);
                for (CompletableFuture<HttpResponse<String>> invocation : invocations) {
                    assertFalse(invocation.isDone(), "the invocations are still waiting for their functions");
                }

                for (int i = 0; i < SCOPES.size(); i++) {
                    Scope scope = SCOPES.get(i);
                    InvokeResult result = new InvokeResult(200, null, ("{\"statusCode\":201,\"body\":\""
                            + scope.identity() + "\"}").getBytes(StandardCharsets.UTF_8), null, "req-" + i);
                    result.setExecutedVersion("1");
                    releases.get(target(scope, invoke)).complete(result);
                    HttpResponse<String> response = invocations.get(i).get(10, TimeUnit.SECONDS);
                    if (invoke) {
                        assertEquals(200, response.statusCode());
                        assertEquals("1", response.headers().firstValue("X-Amz-Executed-Version").orElseThrow());
                        assertEquals(scope.identity(), mapper.readTree(response.body()).path("body").asText());
                    } else {
                        assertEquals(201, response.statusCode());
                        assertEquals(scope.identity(), response.body());
                    }
                }
            } finally {
                for (CompletableFuture<InvokeResult> release : releases.values()) {
                    release.complete(new InvokeResult(200, null, null, null, "released"));
                }
            }
        }
    }

    private InvokeResult hold(String target, InvocationType type, Map<String, Held> held,
                              CountDownLatch invoking, Map<String, CompletableFuture<InvokeResult>> releases)
            throws Exception {
        held.put(target, new Held(type, new Scope(context.getAccountId(), context.getRegion())));
        invoking.countDown();
        CompletableFuture<InvokeResult> release = releases.get(target);
        return release == null ? new InvokeResult(200, null, null, null, "unexpected")
                : release.get(HeldWorkerProbes.HOLD_SECONDS, TimeUnit.SECONDS);
    }

    private static LambdaFunction function(Scope scope) {
        LambdaFunction function = new LambdaFunction();
        function.setFunctionName(functionName(scope));
        function.setFunctionArn(functionArn(scope));
        function.setAccountId(scope.account());
        return function;
    }

    /** The status and body of each request that was answered while it should have been waiting. */
    private static List<String> answered(List<CompletableFuture<HttpResponse<String>>> invocations) {
        List<String> answers = new ArrayList<>();
        for (CompletableFuture<HttpResponse<String>> invocation : invocations) {
            HttpResponse<String> response = invocation.getNow(null);
            if (response != null) {
                answers.add(response.statusCode() + " " + response.body());
            }
        }
        return answers;
    }

    /** What a held invocation saw: how it was invoked and the account and Region it ran in. */
    private record Held(InvocationType type, Scope scope) {
    }

    private static String functionName(Scope scope) {
        return "held-" + scope.identity();
    }

    private static String functionArn(Scope scope) {
        return "arn:aws:lambda:" + scope.region() + ":" + scope.account() + ":function:" + functionName(scope);
    }

    /** What LambdaService receives: the name from Invoke, the ARN from a function URL. */
    private static String target(Scope scope, boolean invoke) {
        return invoke ? functionName(scope) : functionArn(scope);
    }
}
