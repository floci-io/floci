package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Measured on AWS (eu-west-1, 2026-10-16): with no LoggingConfig a stream is
 * {@code 2026/10/16/[$LATEST]<id>} in {@code /aws/lambda/<name>}; once LoggingConfig names a log
 * group, the default one's name included, the stream is {@code 2026/10/16/<name>[$LATEST]<id>}.
 */
class LambdaLogDestinationsTest {

    @Test
    void aFunctionWithoutLoggingConfigWritesToItsOwnGroup() {
        LambdaFunction fn = function();

        assertEquals("/aws/lambda/orders", LambdaLogDestinations.logGroup(fn));
        assertEquals("[$LATEST]1a2b3c4d", LambdaLogDestinations.logStreamSuffix(fn, "1a2b3c4d"));
    }

    @Test
    void aGroupLoggingConfigNamesTakesTheLogsAndTheFunctionNameInEachStream() {
        LambdaFunction fn = function();
        fn.setLogGroup("/shared/app-logs");

        assertEquals("/shared/app-logs", LambdaLogDestinations.logGroup(fn));
        assertEquals("orders[$LATEST]1a2b3c4d", LambdaLogDestinations.logStreamSuffix(fn, "1a2b3c4d"));
    }

    @Test
    void namingTheDefaultGroupStillPutsTheFunctionNameInEachStream() {
        LambdaFunction fn = function();
        fn.setLogGroup("/aws/lambda/orders");

        assertEquals("/aws/lambda/orders", LambdaLogDestinations.logGroup(fn));
        assertEquals("orders[$LATEST]1a2b3c4d", LambdaLogDestinations.logStreamSuffix(fn, "1a2b3c4d"));
    }

    private static LambdaFunction function() {
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("orders");
        return fn;
    }
}
