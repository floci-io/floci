package io.floci.aws.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.iam.IamPolicyEvaluator.Decision;
import io.floci.aws.services.iam.IamPolicyEvaluator.ResourcePolicyDecision;
import io.floci.aws.services.iam.model.CallerContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A statement names one of the two effects the policy language defines or it names none, and one
 * that names none states nothing: it grants nothing and denies nothing, whichever family of policy
 * it was written in. A document that describes no permission must not be read as granting one.
 */
class IamStatementEffectTest {

    private final IamPolicyEvaluator evaluator = new IamPolicyEvaluator(new ObjectMapper());

    private static String identityStatement(String effectElement) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{" + effectElement
                + "\"Action\":\"s3:*\",\"Resource\":\"*\"}]}";
    }

    @Test
    void aStatementCarryingNoEffectGrantsNothing() {
        String document = identityStatement("");

        assertEquals(Decision.DENY, evaluator.evaluate(
                CallerContext.of(List.of(document)), null, "s3:GetObject", "arn:aws:s3:::b/k", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "\"Effect\":[\"Allow\"],",
        "\"Effect\":{\"Allow\":true},",
        "\"Effect\":1,",
        "\"Effect\":true,",
        "\"Effect\":null,",
    })
    void anEffectThatIsNoStringNamesNeitherOfThem(String effectElement) {
        String document = identityStatement(effectElement);

        assertEquals(Decision.DENY, evaluator.evaluate(
                CallerContext.of(List.of(document)), null, "s3:GetObject", "arn:aws:s3:::b/k", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Permit", "Grant", "ALLOWED", "", " Allow"})
    void aWordThatIsNeitherEffectGrantsNothing(String effect) {
        String document = identityStatement("\"Effect\":\"" + effect + "\",");

        assertEquals(Decision.DENY, evaluator.evaluate(
                CallerContext.of(List.of(document)), null, "s3:GetObject", "arn:aws:s3:::b/k", null));
    }

    @Test
    void aStatementThatStatesNothingLeavesTheRestOfTheDocumentReadable() {
        String document = "{\"Version\":\"2012-10-17\",\"Statement\":["
                + "{\"Action\":\"*\",\"Resource\":\"*\"},"
                + "{\"Effect\":\"Allow\",\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}";

        assertEquals(Decision.ALLOW, evaluator.evaluate(
                CallerContext.of(List.of(document)), null, "s3:GetObject", "arn:aws:s3:::b/k", null));
        assertEquals(Decision.DENY, evaluator.evaluate(
                CallerContext.of(List.of(document)), null, "s3:PutObject", "arn:aws:s3:::b/k", null));
    }

    @Test
    void aResourcePolicyStatementCarryingNoEffectGrantsNothing() {
        String bucketPolicy = "{\"Version\":\"2012-10-17\",\"Statement\":[{"
                + "\"Principal\":\"*\",\"Action\":\"s3:*\",\"Resource\":\"*\"}]}";

        assertEquals(ResourcePolicyDecision.NEUTRAL, evaluator.evaluateResourcePolicy(
                List.of(bucketPolicy), "arn:aws:iam::123456789012:user/alice", "s3:GetObject",
                "arn:aws:s3:::b/k", null));
    }

    @Test
    void aSimulatedStatementCarryingNoEffectGrantsNothing() {
        assertEquals(Decision.DENY, evaluator.simulateCustomPolicy(
                List.of(identityStatement("")), "s3:GetObject", "arn:aws:s3:::b/k", null));
    }

    @Test
    void anScpStatementCarryingNoEffectReachesNoService() {
        List<String> reachable =
                evaluator.servicesAllowedByScpLevels(List.of(List.of(identityStatement(""))));

        assertTrue(reachable.isEmpty(), () -> "reached " + reachable);
    }

    @Test
    void anScpStatementThatAllowsIsStillRead() {
        List<String> reachable = evaluator.servicesAllowedByScpLevels(
                List.of(List.of(identityStatement("\"Effect\":\"Allow\","))));

        assertEquals(List.of("s3"), reachable);
    }
}
