package io.floci.aws.services.xray;

import io.floci.aws.core.common.AwsException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XrayFilterExpressionTest {

    private static XrayFilterExpression.Facts failingCall() {
        return new XrayFilterExpression.Facts(Set.of("orders-api", "dynamodb"), true, false, false,
                2.5, 500, Map.of("env", "local", "attempt", "2"));
    }

    private static XrayFilterExpression.Facts healthCall() {
        return new XrayFilterExpression.Facts(Set.of("orders-api"), false, false, false,
                0.25, 200, Map.of("env", "local"));
    }

    private static Predicate<XrayFilterExpression.Facts> compile(String expression) {
        return XrayFilterExpression.compile(expression);
    }

    @Test
    void anEmptyExpressionMatchesEverything() {
        assertTrue(compile(null).test(failingCall()));
        assertTrue(compile("  ").test(healthCall()));
    }

    @Test
    void aServicePredicateMatchesATraceThatCalledIt() {
        Predicate<XrayFilterExpression.Facts> filter = compile("service(\"dynamodb\")");

        assertTrue(filter.test(failingCall()));
        assertFalse(filter.test(healthCall()));
    }

    @Test
    void flagsMatchTheTraceOutcome() {
        assertTrue(compile("error").test(failingCall()));
        assertFalse(compile("error").test(healthCall()));
        assertTrue(compile("fault").test(new XrayFilterExpression.Facts(Set.of("a"), false, true,
                false, 1.0, null, Map.of())));
        assertTrue(compile("throttle").test(new XrayFilterExpression.Facts(Set.of("a"), false,
                false, true, 1.0, null, Map.of())));
    }

    @Test
    void numericComparisonsWorkForKeyFields() {
        assertTrue(compile("duration > 1").test(failingCall()));
        assertFalse(compile("duration > 1").test(healthCall()));
        assertTrue(compile("http.status = 500").test(failingCall()));
        assertTrue(compile("http.status >= 400").test(failingCall()));
        assertFalse(compile("http.status <= 400").test(failingCall()));
        assertTrue(compile("responsetime < 500").test(healthCall()));
    }

    @Test
    void annotationsCompareAsStringsAndNumbers() {
        assertTrue(compile("annotation.env = \"local\"").test(healthCall()));
        assertFalse(compile("annotation.env = \"prod\"").test(healthCall()));
        assertTrue(compile("annotation.attempt = 2").test(failingCall()));
        assertFalse(compile("annotation.missing = \"x\"").test(healthCall()));
    }

    @Test
    void andOrNotAndParenthesesCombine() {
        assertTrue(compile("service(\"dynamodb\") AND error").test(failingCall()));
        assertFalse(compile("service(\"dynamodb\") AND NOT error").test(failingCall()));
        assertTrue(compile("service(\"dynamodb\") OR http.status = 200").test(healthCall()));
        assertTrue(compile("(service(\"dynamodb\") OR service(\"kafka\")) AND error")
                .test(failingCall()));
        assertFalse(compile("NOT (service(\"dynamodb\") OR service(\"kafka\"))").test(failingCall()));
    }

    @Test
    void aLoneExclamationIsRefusedInsteadOfSpinning() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            AwsException refused = assertThrows(AwsException.class, () -> compile("hasError AND !"));
            assertEquals("InvalidRequestException", refused.getErrorCode());

            AwsException alone = assertThrows(AwsException.class, () -> compile("!"));
            assertEquals("InvalidRequestException", alone.getErrorCode());
        });
    }

    @Test
    void responsetimeIsComparedInSecondsLikeDuration() {
        assertTrue(compile("responsetime < 0.5").test(healthCall()));
        assertFalse(compile("responsetime > 1").test(healthCall()));
        assertTrue(compile("responsetime > 1").test(failingCall()));
    }

    @Test
    void theAwsKeywordsMatchTheTraceOutcome() {
        assertTrue(compile("error").test(failingCall()));
        assertFalse(compile("error").test(healthCall()));
        assertTrue(compile("ok").test(healthCall()));
        assertFalse(compile("ok").test(failingCall()));
        assertTrue(compile("fault").test(new XrayFilterExpression.Facts(Set.of("a"), false, true,
                false, 1.0, null, Map.of())));
        assertTrue(compile("throttle").test(new XrayFilterExpression.Facts(Set.of("a"), false,
                false, true, 1.0, null, Map.of())));
    }

    @Test
    void anExclamationNegatesThePredicateAfterIt() {
        assertTrue(compile("!error").test(healthCall()));
        assertFalse(compile("!error").test(failingCall()));
        assertTrue(compile("!(service(\"dynamodb\") AND error)").test(healthCall()));
        assertFalse(compile("service(\"dynamodb\") AND !ok").test(healthCall().services().isEmpty()
                ? healthCall() : new XrayFilterExpression.Facts(Set.of("dynamodb"), false, false,
                false, 0.25, 200, Map.of())));
    }

    @Test
    void anUnsupportedPredicateIsRefusedInsteadOfIgnored() {
        AwsException thrown = assertThrows(AwsException.class, () -> compile("edge(\"a\", \"b\")"));

        assertEquals("InvalidRequestException", thrown.getErrorCode());
        assertTrue(thrown.getMessage().contains("not supported"));
    }

    @Test
    void anUnbalancedExpressionIsRefused() {
        AwsException thrown = assertThrows(AwsException.class,
                () -> compile("(service(\"a\") AND error"));

        assertEquals("InvalidRequestException", thrown.getErrorCode());
    }

    @Test
    void aTrailingTokenIsRefused() {
        AwsException thrown = assertThrows(AwsException.class, () -> compile("hasError junk"));

        assertEquals("InvalidRequestException", thrown.getErrorCode());
    }
}
