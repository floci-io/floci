package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class LambdaArnUtilsTest {

    @ParameterizedTest
    @CsvSource({
            // input, expectedName, expectedQualifier, expectedRegion
            "my-fn, my-fn, , ",
            "my-fn:prod, my-fn, prod, ",
            "my-fn:$LATEST, my-fn, $LATEST, ",
            "my-fn:42, my-fn, 42, ",
            "000000000000:function:my-fn, my-fn, , ",
            "000000000000:function:my-fn:prod, my-fn, prod, ",
            "arn:aws:lambda:us-east-1:000000000000:function:my-fn, my-fn, , us-east-1",
            "arn:aws:lambda:eu-west-1:123456789012:function:my-fn, my-fn, , eu-west-1",
            "arn:aws:lambda:us-east-1:000000000000:function:my-fn:prod, my-fn, prod, us-east-1",
            "arn:aws:lambda:us-east-1:000000000000:function:my-fn:$LATEST, my-fn, $LATEST, us-east-1",
            "arn:aws:lambda:us-east-1:000000000000:function:my_fn-1, my_fn-1, , us-east-1",
            "my.fn.v2, my.fn.v2, , ",
            "arn:aws:lambda:us-east-1:000000000000:function:my.fn.v2, my.fn.v2, , us-east-1",
    })
    void resolveAcceptsValidForms(String input, String expectedName, String expectedQualifier, String expectedRegion) {
        LambdaArnUtils.ResolvedFunctionRef ref = LambdaArnUtils.resolve(input);
        assertEquals(expectedName, ref.name());
        assertEquals(emptyToNull(expectedQualifier), ref.qualifier());
        assertEquals(emptyToNull(expectedRegion), ref.region());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            " ",
            "foo:function:",
            ":function:foo",
            "foo:function:name:q1:q2",
            "arn:aws:lambda:us-east-1:000000000000:function:",
            "arn:aws:lambda::000000000000:function:my-fn",
            "arn:aws:lambda:us-east-1:abc:function:my-fn",
            "arn:aws:lambda:us-east-1:000000000000:layer:my-layer",
            "arn:aws:s3:us-east-1:000000000000:function:my-fn",
            "arn:aws:lambda:us-east-1:000000000000:function:my-fn:q1:q2",
            "my-fn:bad/qualifier",
            "my:fn:extra:segments",
            "name with spaces",
            "name!bang",
    })
    void resolveRejectsMalformedInputs(String input) {
        AwsException ex = assertThrows(AwsException.class, () -> LambdaArnUtils.resolve(input));
        assertEquals("InvalidParameterValueException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void resolveRejectsNameLongerThan256Chars() {
        String tooLong = "a".repeat(257);
        AwsException ex = assertThrows(AwsException.class, () -> LambdaArnUtils.resolve(tooLong));
        assertEquals("InvalidParameterValueException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void resolveAccepts256CharName() {
        String maxLen = "a".repeat(256);
        LambdaArnUtils.ResolvedFunctionRef ref = LambdaArnUtils.resolve(maxLen);
        assertEquals(maxLen, ref.name());
    }

    @Test
    void resolveRejectsNull() {
        assertThrows(AwsException.class, () -> LambdaArnUtils.resolve(null));
    }

    @Test
    void resolveWithQualifierTakesEmbeddedWhenOnlyEmbedded() {
        LambdaArnUtils.ResolvedFunctionRef ref = LambdaArnUtils.resolveWithQualifier("my-fn:prod", null);
        assertEquals("prod", ref.qualifier());
    }

    @Test
    void resolveWithQualifierTakesQueryWhenOnlyQuery() {
        LambdaArnUtils.ResolvedFunctionRef ref = LambdaArnUtils.resolveWithQualifier("my-fn", "prod");
        assertEquals("prod", ref.qualifier());
    }

    @Test
    void resolveWithQualifierAcceptsMatching() {
        LambdaArnUtils.ResolvedFunctionRef ref = LambdaArnUtils.resolveWithQualifier("my-fn:prod", "prod");
        assertEquals("prod", ref.qualifier());
    }

    @Test
    void resolveWithQualifierRejectsConflict() {
        AwsException ex = assertThrows(AwsException.class,
                () -> LambdaArnUtils.resolveWithQualifier("my-fn:prod", "dev"));
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void resolveWithQualifierTreatsBlankQueryAsAbsent() {
        LambdaArnUtils.ResolvedFunctionRef ref = LambdaArnUtils.resolveWithQualifier("my-fn:prod", "");
        assertEquals("prod", ref.qualifier());
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    // ──────────────────────────── extractFunctionNameFromUri tests ────────────────────────────

    @ParameterizedTest
    @CsvSource({
            // input URI, expected function name
            "arn:aws:lambda:us-east-1:000000000000:function:myFn/invocations, myFn",
            "arn:aws:lambda:us-east-1:000000000000:function:myFn, myFn",
            "arn:aws:lambda:eu-west-1:123456789012:function:my-handler/invocations, my-handler",
            "arn:aws:lambda:us-east-1:000000000000:function:my_fn-2/invocations, my_fn-2",
            "myFn, myFn",
            "my-handler, my-handler",
    })
    void extractFunctionNameFromUri_validInputs(String uri, String expectedName) {
        assertEquals(expectedName, LambdaArnUtils.extractFunctionNameFromUri(uri));
    }

    @Test
    void extractFunctionNameFromUri_nullReturnsNull() {
        assertNull(LambdaArnUtils.extractFunctionNameFromUri(null));
    }

    @Test
    void extractFunctionNameFromUri_noFunctionPrefixReturnsFull() {
        // When no "function:" prefix, the entire URI is treated as the function name
        assertEquals("just-a-name", LambdaArnUtils.extractFunctionNameFromUri("just-a-name"));
    }

    @Test
    void extractFunctionNameFromUri_stripsInvocationsSuffix() {
        String uri = "arn:aws:lambda:us-east-1:000000000000:function:handler/invocations";
        assertEquals("handler", LambdaArnUtils.extractFunctionNameFromUri(uri));
    }

    @Test
    void extractFunctionNameFromUri_handlesStageVariableSubstitutedUri() {
        // After stage variable substitution, the URI looks like a normal ARN
        String uri = "arn:aws:lambda:us-east-1:000000000000:function:ws-stage-var-fn/invocations";
        assertEquals("ws-stage-var-fn", LambdaArnUtils.extractFunctionNameFromUri(uri));
    }

    @Test
    void extractFunctionNameFromUri_handlesApiGatewayStyleUri() {
        // API Gateway v1 uses a longer URI format
        String uri = "arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/arn:aws:lambda:us-east-1:000000000000:function:myFn/invocations";
        assertEquals("myFn", LambdaArnUtils.extractFunctionNameFromUri(uri));
    }

    @ParameterizedTest
    @CsvSource({
            // Qualified targets (alias / numbered version) must preserve the :qualifier
            // so downstream invoke resolves the configured target instead of $LATEST.
            "arn:aws:lambda:us-east-1:000000000000:function:myFn:PROD/invocations, myFn:PROD",
            "arn:aws:lambda:us-east-1:000000000000:function:myFn:PROD, myFn:PROD",
            "arn:aws:lambda:us-east-1:000000000000:function:myFn:1/invocations, myFn:1",
            "arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/arn:aws:lambda:us-east-1:000000000000:function:myFn:PROD/invocations, myFn:PROD",
            "arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/arn:aws:lambda:us-east-1:000000000000:function:myFn:42/invocations, myFn:42",
    })
    void extractFunctionNameFromUri_preservesQualifier(String uri, String expected) {
        assertEquals(expected, LambdaArnUtils.extractFunctionNameFromUri(uri));
    }

    @Test
    void extractFunctionNameFromUri_qualifiedRef_resolvesToNameAndQualifier() {
        // The extracted "myFn:PROD" must resolve into a distinct name + qualifier,
        // which is what invoke() relies on to target the alias/version.
        String extracted = LambdaArnUtils.extractFunctionNameFromUri(
                "arn:aws:lambda:us-east-1:000000000000:function:myFn:PROD/invocations");
        LambdaArnUtils.ResolvedFunctionRef ref = LambdaArnUtils.resolve(extracted);
        assertEquals("myFn", ref.name());
        assertEquals("PROD", ref.qualifier());
    }
}
