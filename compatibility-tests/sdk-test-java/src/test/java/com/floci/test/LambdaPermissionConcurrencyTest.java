package com.floci.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.AddPermissionRequest;
import software.amazon.awssdk.services.lambda.model.AddPermissionResponse;
import software.amazon.awssdk.services.lambda.model.CreateFunctionRequest;
import software.amazon.awssdk.services.lambda.model.DeleteFunctionRequest;
import software.amazon.awssdk.services.lambda.model.FunctionCode;
import software.amazon.awssdk.services.lambda.model.GetPolicyRequest;
import software.amazon.awssdk.services.lambda.model.Runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Lambda - Permission concurrency")
class LambdaPermissionConcurrencyTest {

    private static final int PERMISSION_COUNT = 16;
    private static final String ROLE = "arn:aws:iam::000000000000:role/lambda-role";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static LambdaClient lambda;
    private static String functionName;

    @BeforeAll
    static void setup() {
        lambda = TestFixtures.lambdaClient();
        functionName = TestFixtures.uniqueName("sdk-test-permission-concurrency");
        lambda.createFunction(CreateFunctionRequest.builder()
                .functionName(functionName)
                .runtime(Runtime.NODEJS20_X)
                .role(ROLE)
                .handler("index.handler")
                .code(FunctionCode.builder()
                        .zipFile(SdkBytes.fromByteArray(LambdaUtils.minimalZip()))
                        .build())
                .build());
    }

    @AfterAll
    static void cleanup() {
        if (lambda != null) {
            try {
                lambda.deleteFunction(DeleteFunctionRequest.builder()
                        .functionName(functionName)
                        .build());
            } catch (Exception cleanupError) {
                System.err.println("Could not delete function " + functionName + ": "
                        + cleanupError.getMessage());
            }
            lambda.close();
        }
    }

    @Test
    void concurrentDistinctStatementsAreAllStoredAndSdkResponsesParse() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(PERMISSION_COUNT);
        CountDownLatch ready = new CountDownLatch(PERMISSION_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AddPermissionResponse>> futures = new ArrayList<>();
        Set<String> expectedStatementIds = new HashSet<>();

        try {
            for (int i = 0; i < PERMISSION_COUNT; i++) {
                String statementId = "ConcurrentPermission" + i;
                expectedStatementIds.add(statementId);
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return lambda.addPermission(AddPermissionRequest.builder()
                            .functionName(functionName)
                            .statementId(statementId)
                            .action("lambda:InvokeFunction")
                            .principal("events.amazonaws.com")
                            .sourceArn("arn:aws:events:us-east-1:000000000000:rule/" + statementId)
                            .build());
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            Set<String> responseStatementIds = new HashSet<>();
            for (Future<AddPermissionResponse> future : futures) {
                AddPermissionResponse response = future.get(30, TimeUnit.SECONDS);
                assertThat(response.statement()).isNotBlank();
                JsonNode statement = OBJECT_MAPPER.readTree(response.statement());
                responseStatementIds.add(statement.path("Sid").asText());
            }
            assertThat(responseStatementIds).containsExactlyInAnyOrderElementsOf(expectedStatementIds);

            JsonNode policy = OBJECT_MAPPER.readTree(lambda.getPolicy(GetPolicyRequest.builder()
                    .functionName(functionName)
                    .build()).policy());
            Set<String> storedStatementIds = new HashSet<>();
            policy.path("Statement").forEach(statement ->
                    storedStatementIds.add(statement.path("Sid").asText()));
            assertThat(storedStatementIds).containsExactlyInAnyOrderElementsOf(expectedStatementIds);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }
}
