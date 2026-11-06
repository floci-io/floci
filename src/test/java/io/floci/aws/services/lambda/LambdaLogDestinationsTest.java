package io.floci.aws.services.lambda;

import io.floci.aws.services.lambda.model.LambdaFunction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void inASharedGroupOnlyTheStreamsCarryingTheFunctionNameAreItsOwn() {
        LambdaFunction fn = function();
        fn.setLogGroup("/shared/app-logs");

        assertTrue(LambdaLogDestinations.isOwnStream(fn, "2026/10/17/orders[$LATEST]1a2b3c4d"));
        assertFalse(LambdaLogDestinations.isOwnStream(fn, "2026/10/17/orders-archive[$LATEST]1a2b3c4d"));
        assertFalse(LambdaLogDestinations.isOwnStream(fn, "2026/10/17/billing[$LATEST]1a2b3c4d"));
        assertFalse(LambdaLogDestinations.isOwnStream(fn, null));
    }

    @Test
    void everyStreamOfTheDefaultGroupIsTheFunctionsOwn() {
        assertTrue(LambdaLogDestinations.isOwnStream(function(), "2026/10/17/[$LATEST]1a2b3c4d"));
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
