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
}
