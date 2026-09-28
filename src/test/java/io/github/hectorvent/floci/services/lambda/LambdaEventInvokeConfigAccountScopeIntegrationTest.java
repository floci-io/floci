package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.lambda.model.FunctionEventInvokeConfig;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The event invoke configuration is stored in the owning account's partition of the account-aware
 * backend, and the asynchronous invoke pool that reads it carries no request context. A read that
 * does not re-establish the owner's account lands in the default partition, and a function in any
 * other account is delivered no destination at all.
 */
@QuarkusTest
class LambdaEventInvokeConfigAccountScopeIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String OTHER_ACCOUNT = "100000000012";

    @Inject
    LambdaService lambdaService;

    @Test
    void findEventInvokeConfig_forAFunctionInAnotherAccount_readsThatAccountsPartition() throws Exception {
        String functionName = "event-invoke-account-scope-fn";
        String destination = "arn:aws:sqs:" + REGION + ":" + OTHER_ACCOUNT + ":event-invoke-account-scope-queue";

        LambdaFunction fn = RequestScopes.callAs(OTHER_ACCOUNT, () -> {
            LambdaFunction created = lambdaService.createFunction(REGION, new HashMap<>(Map.of(
                    "FunctionName", functionName,
                    "Runtime", "nodejs20.x",
                    "Role", "arn:aws:iam::" + OTHER_ACCOUNT + ":role/test-role",
                    "Handler", "index.handler")));
            lambdaService.putEventInvokeConfig(REGION, functionName, "$LATEST",
                    new HashMap<>(Map.of("DestinationConfig",
                            Map.of("OnSuccess", Map.of("Destination", destination)))));
            return created;
        });
        assertEquals(OTHER_ACCOUNT, fn.getAccountId(), "function was not created in the target account");

        // The asynchronous invoke pool runs on a plain thread with no request context. Reading the
        // configuration from one here is what the router does after the function returns.
        ExecutorService offRequest = Executors.newSingleThreadExecutor();
        Optional<FunctionEventInvokeConfig> found;
        try {
            found = offRequest.submit(() -> lambdaService.findEventInvokeConfig(fn))
                    .get(10, TimeUnit.SECONDS);
        } finally {
            offRequest.shutdownNow();
        }

        assertTrue(found.isPresent(),
                "the configuration of a function in account " + OTHER_ACCOUNT + " was not found");
        assertEquals(destination, found.get().getDestinationConfig().getOnSuccess().getDestination());
    }
}
