package io.floci.aws.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.iam.IamPolicyEvaluator.Decision;
import io.floci.aws.services.iam.model.CallerContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An action, a resource and a principal are written as strings, so a list entry that is no string
 * names no value, which is what a bare non-string element already came to. It tells where the
 * element carves an exception out: a {@code NotAction} or {@code NotResource} naming nothing
 * excludes nothing, and a statement that excludes nothing would otherwise cover everything.
 */
class IamStatementPatternValuesTest {

    private static final String RESOURCE = "arn:aws:s3:::b/k";

    private final IamPolicyEvaluator evaluator = new IamPolicyEvaluator(new ObjectMapper());

    private Decision decide(String document) {
        return evaluator.evaluate(
                CallerContext.of(List.of(document)), null, "s3:GetObject", RESOURCE, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"123", "true", "null", "{}", "[\"arn:aws:s3:::b/k\"]"})
    void aNotResourceEntryThatIsNoStringExcludesNothingAndCoversNothing(String entry) {
        String document = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Action\":\"s3:*\",\"NotResource\":[" + entry + "]}]}";

        assertEquals(Decision.DENY, decide(document));
    }

    @ParameterizedTest
    @ValueSource(strings = {"123", "true", "null", "{}", "[\"s3:GetObject\"]"})
    void aNotActionEntryThatIsNoStringLeavesTheStatementNamingNoAction(String entry) {
        String document = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"NotAction\":[" + entry + "],\"Resource\":\"*\"}]}";

        assertEquals(Decision.DENY, decide(document));
    }

    @Test
    void aDenyWhoseNotResourceNamesNothingDeniesNothing() {
        String document = "{\"Version\":\"2012-10-17\",\"Statement\":["
                + "{\"Effect\":\"Allow\",\"Action\":\"s3:*\",\"Resource\":\"*\"},"
                + "{\"Effect\":\"Deny\",\"Action\":\"s3:*\",\"NotResource\":[123]}]}";

        assertEquals(Decision.ALLOW, decide(document));
    }

    /**
     * The report and the decision read one statement one way. A {@code Deny} naming no resource
     * reaches no request, so the service it names stays reachable; reading the absence as every
     * resource would take a service off the report that every request can still reach.
     */
    @Test
    void aDenyWhoseResourceNamesNothingTakesNoServiceOffTheReport() {
        String document = "{\"Version\":\"2012-10-17\",\"Statement\":["
                + "{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"},"
                + "{\"Effect\":\"Deny\",\"Action\":\"s3:*\",\"Resource\":[123]}]}";

        assertEquals(Decision.ALLOW, decide(document));
        assertTrue(evaluator.servicesAllowedByScpLevels(List.of(List.of(document))).contains("s3"));
    }

    @Test
    void aDenyWhoseNotResourceNamesNothingTakesNoServiceOffTheReport() {
        String document = "{\"Version\":\"2012-10-17\",\"Statement\":["
                + "{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"},"
                + "{\"Effect\":\"Deny\",\"Action\":\"s3:*\",\"NotResource\":[123]}]}";

        assertEquals(Decision.ALLOW, decide(document));
        assertTrue(evaluator.servicesAllowedByScpLevels(List.of(List.of(document))).contains("s3"));
    }

    @Test
    void aBareNonStringElementIsReadTheSameWayAsAListCarryingOne() {
        String bare = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Action\":\"s3:*\",\"NotResource\":123}]}";
        String listed = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Action\":\"s3:*\",\"NotResource\":[123]}]}";

        assertEquals(decide(bare), decide(listed));
    }

    @Test
    void theStringsBesideItAreStillNamed() {
        String document = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Action\":[\"s3:GetObject\",17],\"Resource\":[\"" + RESOURCE + "\",17]}]}";

        assertEquals(Decision.ALLOW, decide(document));
        assertEquals(Decision.DENY, evaluator.evaluate(CallerContext.of(List.of(document)), null,
                "s3:GetObject", "arn:aws:s3:::other/k", null));
    }

    @Test
    void aConditionValueListStillReadsANumberAsTheTextOfTheValue() {
        String document = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Action\":\"s3:*\",\"Resource\":\"*\",\"Condition\":{\"NumericLessThan\":"
                + "{\"aws:MultiFactorAuthAge\":[3600]}}}]}";

        assertEquals(Decision.ALLOW, evaluator.simulateCustomPolicy(List.of(document),
                "s3:GetObject", RESOURCE, Map.of("aws:MultiFactorAuthAge", List.of("1000"))));
        assertEquals(Decision.DENY, evaluator.simulateCustomPolicy(List.of(document),
                "s3:GetObject", RESOURCE, Map.of("aws:MultiFactorAuthAge", List.of("7200"))));
    }
}
