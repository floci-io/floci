package io.floci.aws.services.elbv2;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.elbv2.PathPatternRegexValues.Compiled;
import io.floci.aws.services.elbv2.model.RuleCondition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static io.floci.aws.services.elbv2.PathRegexFixtures.MATCH_PATTERNS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code PathPatternConfig.RegexValues}: validation and the data plane's full-path match. */
class ElbV2PathRegexTest {

    @Test
    void alternativesAndMultipleExpressionsMatchTheExpectedPaths() {
        List<Compiled> patterns = ElbV2DataPlane.compilePathRegexes(MATCH_PATTERNS);
        for (String path : List.of("/api/items", "/api/items/42", "/api/orders", "/api/orders/42", "/status/200")) {
            assertTrue(ElbV2DataPlane.pathRegexMatches(patterns, path), path);
        }
        for (String path : List.of("/", "/api", "/api/items-extra", "/assets/logo", "/status/ok", "/API/items")) {
            assertFalse(ElbV2DataPlane.pathRegexMatches(patterns, path), path);
        }
    }

    @Test
    void matchIsAgainstTheWholePath() {
        List<Compiled> unanchored = ElbV2DataPlane.compilePathRegexes(List.of("/api"));
        assertTrue(ElbV2DataPlane.pathRegexMatches(unanchored, "/api"));
        assertFalse(ElbV2DataPlane.pathRegexMatches(unanchored, "/api/v1"));
        assertFalse(ElbV2DataPlane.pathRegexMatches(unanchored, "/v1/api"));
    }

    @Test
    void matchIsCaseSensitive() {
        List<Compiled> patterns = ElbV2DataPlane.compilePathRegexes(List.of("/Api/.*"));
        assertTrue(ElbV2DataPlane.pathRegexMatches(patterns, "/Api/x"));
        assertFalse(ElbV2DataPlane.pathRegexMatches(patterns, "/api/x"));
    }

    @Test
    void anExpressionThatDoesNotCompileNeverMatches() {
        List<Compiled> patterns = ElbV2DataPlane.compilePathRegexes(List.of("(", "/ok"));
        assertEquals(1, patterns.size());
        assertTrue(ElbV2DataPlane.pathRegexMatches(patterns, "/ok"));
        assertFalse(ElbV2DataPlane.pathRegexMatches(patterns, null));
    }

    @Test
    void validationAcceptsSupportedExpressions() {
        assertDoesNotThrow(() -> PathPatternRegexValues.validate(MATCH_PATTERNS, false));
    }

    @Test
    void validationRejectsValuesTogetherWithRegexValues() {
        AwsException e = assertThrows(AwsException.class,
                () -> PathPatternRegexValues.validate(List.of("^/a$"), true));
        assertEquals("ValidationError", e.getErrorCode());
    }

    @Test
    void validationRejectsAFourthExpressionBeforeCompiling() {
        assertDoesNotThrow(() -> PathPatternRegexValues.validate(List.of("/a", "/b", "/c"), false));
        AwsException error = assertThrows(AwsException.class,
                () -> PathPatternRegexValues.validate(List.of("(", "/b", "/c", "/d"), false));
        assertEquals("ValidationError", error.getErrorCode());
        assertEquals("A condition can have at most three match evaluations.", error.getMessage());
    }

    @Test
    void validationRejectsInvalidAndOverlongExpressions() {
        assertThrows(AwsException.class,
                () -> PathPatternRegexValues.validate(List.of(""), false));
        assertThrows(AwsException.class,
                () -> PathPatternRegexValues.validate(List.of("(unclosed"), false));
        assertThrows(AwsException.class,
                () -> PathPatternRegexValues.validate(List.of("a".repeat(129)), false));
        assertDoesNotThrow(() -> PathPatternRegexValues.validate(List.of("a".repeat(128)), false));
    }

    @Test
    void validationRejectsUnsupportedAwsSyntax() {
        for (String regex : List.of("/a(?=b)", "/a(?!b)", "(?<=a)/b", "(?<!a)/b",
                "(/a)\\1", "(?<part>/a)\\k<part>", "(?>/a)", "/a*+", "/a++", "/a?+", "/a{1,2}+",
                "(?R)", "(?1)", "(?&part)", "\\p{L}", "\\P{L}", "[\\p{L}]")) {
            AwsException error = assertThrows(AwsException.class,
                    () -> PathPatternRegexValues.validate(List.of(regex), false), regex);
            assertEquals("ValidationError", error.getErrorCode(), regex);
            assertTrue(ElbV2DataPlane.compilePathRegexes(List.of(regex)).isEmpty(), regex);
        }
    }

    @Test
    void escapedUnsupportedSyntaxRemainsLiteral() {
        for (String regex : List.of("/\\(\\?=a\\)", "\\\\p\\{L\\}", "\\Q\\p{L}(?=a)\\E", "/(?:a|b)+?")) {
            assertDoesNotThrow(() -> PathPatternRegexValues.validate(List.of(regex), false), regex);
        }
        assertTrue(ElbV2DataPlane.pathRegexMatches(
                ElbV2DataPlane.compilePathRegexes(List.of("\\Q\\p{L}(?=a)\\E")), "\\p{L}(?=a)"));
    }

    @Test
    void nestedRepetitionDoesNotBacktrackOnLongPaths() {
        List<Compiled> patterns = ElbV2DataPlane.compilePathRegexes(
                List.of("^/(a+)+$", "^/(a|aa)+$", "^/a*a*a*a*a*b$"));
        assertTrue(ElbV2DataPlane.pathRegexMatches(patterns, "/" + "a".repeat(1000)));
        assertTimeout(Duration.ofSeconds(3), () ->
                assertFalse(ElbV2DataPlane.pathRegexMatches(patterns, "/" + "a".repeat(100_000) + "!")));
    }

    @Test
    void storedConditionsRetainRegexValuesAndAcceptOlderGlobOnlyData() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RuleCondition original = new RuleCondition();
        original.setField("path-pattern");
        original.setPathPatternRegexValues(MATCH_PATTERNS);
        RuleCondition restored = mapper.readValue(mapper.writeValueAsBytes(original), RuleCondition.class);
        assertTrue(ElbV2DataPlane.pathRegexMatches(
                ElbV2DataPlane.compilePathRegexes(restored.getPathPatternRegexValues()), "/api/orders/42"));
        assertEquals(MATCH_PATTERNS, restored.getPathPatternRegexValues());

        RuleCondition legacy = mapper.readValue("""
                {
                  "field": "path-pattern",
                  "pathPatternValues": ["/assets/*"]
                }
                """, RuleCondition.class);
        assertTrue(legacy.getPathPatternRegexValues().isEmpty());
        assertEquals(List.of("/assets/*"), legacy.getPathPatternValues());
    }
}
