package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import org.junit.jupiter.api.Test;

import jakarta.inject.Inject;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-style tests for the IAM enforcement engine components:
 * {@link IamPolicyEvaluator}, {@link IamActionRegistry}, and glob matching.
 *
 * The full HTTP enforcement path (filter → evaluator) is covered by the SDK
 * compatibility test {@code IamEnforcementTest.java} in sdk-test-java.
 */
@QuarkusTest
class IamEnforcementIntegrationTest {

    @Inject
    IamPolicyEvaluator evaluator;

    // =========================================================================
    // IamPolicyEvaluator — basic allow / deny / implicit-deny
    // =========================================================================

    @Test
    void allowMatchingAction() {
        String policy = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"s3:GetObject","Resource":"*"}
            ]}""";
        assertEquals(Decision.ALLOW,
                evaluator.evaluate(List.of(policy), "s3:GetObject", "arn:aws:s3:::my-bucket/key"));
    }

    @Test
    void implicitDenyWhenNoPolicies() {
        assertEquals(Decision.DENY,
                evaluator.evaluate(List.of(), "s3:GetObject", "arn:aws:s3:::my-bucket/key"));
    }

    @Test
    void implicitDenyWhenNoMatchingStatement() {
        String policy = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"s3:PutObject","Resource":"*"}
            ]}""";
        assertEquals(Decision.DENY,
                evaluator.evaluate(List.of(policy), "s3:GetObject", "arn:aws:s3:::my-bucket/key"));
    }

    @Test
    void explicitDenyOverridesAllow() {
        String allow = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"s3:*","Resource":"*"}
            ]}""";
        String deny = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Deny","Action":"s3:GetObject","Resource":"*"}
            ]}""";
        assertEquals(Decision.DENY,
                evaluator.evaluate(List.of(allow, deny), "s3:GetObject", "arn:aws:s3:::bucket/key"));
    }

    @Test
    void wildcardActionMatchesService() {
        String policy = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"s3:*","Resource":"*"}
            ]}""";
        assertEquals(Decision.ALLOW,
                evaluator.evaluate(List.of(policy), "s3:DeleteObject", "arn:aws:s3:::bucket/key"));
    }

    @Test
    void fullyWildcardPolicyAllowsAnything() {
        String policy = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"*","Resource":"*"}
            ]}""";
        assertEquals(Decision.ALLOW,
                evaluator.evaluate(List.of(policy), "lambda:InvokeFunction",
                        "arn:aws:lambda:us-east-1:000000000000:function:my-fn"));
    }

    @Test
    void resourceArnPatternMatchesBucket() {
        String policy = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"s3:GetObject","Resource":"arn:aws:s3:::my-bucket/*"}
            ]}""";
        assertEquals(Decision.ALLOW,
                evaluator.evaluate(List.of(policy), "s3:GetObject", "arn:aws:s3:::my-bucket/sub/key.txt"));
        assertEquals(Decision.DENY,
                evaluator.evaluate(List.of(policy), "s3:GetObject", "arn:aws:s3:::other-bucket/key"));
    }

    @Test
    void actionListInStatement() {
        String policy = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":["s3:GetObject","s3:PutObject"],"Resource":"*"}
            ]}""";
        assertEquals(Decision.ALLOW, evaluator.evaluate(List.of(policy), "s3:GetObject", "*"));
        assertEquals(Decision.ALLOW, evaluator.evaluate(List.of(policy), "s3:PutObject", "*"));
        assertEquals(Decision.DENY, evaluator.evaluate(List.of(policy), "s3:DeleteObject", "*"));
    }

    @Test
    void malformedPolicyDocumentIsSkipped() {
        // Should not throw; malformed doc is silently ignored
        assertEquals(Decision.DENY,
                evaluator.evaluate(List.of("not-json"), "s3:GetObject", "*"));
    }

    // =========================================================================
    // IamPolicyEvaluator.globMatches — unit tests
    // =========================================================================

    @Test
    void globMatchesStar() {
        assertTrue(IamPolicyEvaluator.globMatches("s3:*", "s3:GetObject"));
        assertTrue(IamPolicyEvaluator.globMatches("*", "anything"));
        assertFalse(IamPolicyEvaluator.globMatches("s3:*", "lambda:InvokeFunction"));
    }

    @Test
    void globMatchesLiteral() {
        assertTrue(IamPolicyEvaluator.globMatches("s3:GetObject", "s3:GetObject"));
        assertFalse(IamPolicyEvaluator.globMatches("s3:GetObject", "s3:PutObject"));
    }

    @Test
    void globMatchesQuestionMark() {
        assertTrue(IamPolicyEvaluator.globMatches("s3:GetObjec?", "s3:GetObject"));
        assertFalse(IamPolicyEvaluator.globMatches("s3:GetObjec?", "s3:GetObjects"));
    }

    @Test
    void globMatchesCaseInsensitive() {
        assertTrue(IamPolicyEvaluator.globMatches("S3:GetObject", "s3:getobject"));
    }

    @Test
    void globMatchesArnWildcard() {
        assertTrue(IamPolicyEvaluator.globMatches(
                "arn:aws:s3:::my-bucket/*",
                "arn:aws:s3:::my-bucket/sub/dir/file.txt"));
        assertFalse(IamPolicyEvaluator.globMatches(
                "arn:aws:s3:::my-bucket/*",
                "arn:aws:s3:::other-bucket/file.txt"));
    }
}
