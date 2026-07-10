package io.github.hectorvent.floci.services.stepfunctions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Regression tests for {@link AslExecutor#extractLambdaFunctionName(String)}.
 *
 * Real-world trigger: CDK/Step Functions emit the optimized lambda:invoke integration with a
 * FunctionName of the form "arn:aws:lambda:region:acct:function:NAME:$LATEST". The previous
 * implementation took the last ':'-segment, which yields the version qualifier ("$LATEST")
 * instead of the function name, causing "Lambda function not found: $LATEST".
 */
class AslExecutorLambdaArnTest {

    @Test
    void qualifiedFunctionArnDropsLatestQualifier() {
        assertEquals("my-fn",
                AslExecutor.extractLambdaFunctionName(
                        "arn:aws:lambda:us-east-1:000000000000:function:my-fn:$LATEST"));
    }

    @Test
    void qualifiedFunctionArnDropsNumericVersion() {
        assertEquals("my-fn",
                AslExecutor.extractLambdaFunctionName(
                        "arn:aws:lambda:us-east-1:000000000000:function:my-fn:7"));
    }

    @Test
    void qualifiedFunctionArnDropsAlias() {
        assertEquals("my-fn",
                AslExecutor.extractLambdaFunctionName(
                        "arn:aws:lambda:us-east-1:000000000000:function:my-fn:prod"));
    }

    @Test
    void unqualifiedFunctionArnReturnsName() {
        assertEquals("my-fn",
                AslExecutor.extractLambdaFunctionName(
                        "arn:aws:lambda:us-east-1:000000000000:function:my-fn"));
    }

    @Test
    void partialFunctionArnReturnsName() {
        assertEquals("my-fn",
                AslExecutor.extractLambdaFunctionName("000000000000:function:my-fn"));
    }

    @Test
    void bareNameReturnsName() {
        assertEquals("my-fn", AslExecutor.extractLambdaFunctionName("my-fn"));
    }

    @Test
    void bareNameWithQualifierDropsQualifier() {
        assertEquals("my-fn", AslExecutor.extractLambdaFunctionName("my-fn:$LATEST"));
    }

    @Test
    void hyphenAndUnderscoreNamesPreserved() {
        assertEquals("local-solutions-prepare-handler",
                AslExecutor.extractLambdaFunctionName(
                        "arn:aws:lambda:us-east-1:000000000000:function:local-solutions-prepare-handler:$LATEST"));
    }

    @Test
    void nullReturnsNull() {
        assertNull(AslExecutor.extractLambdaFunctionName(null));
    }
}
