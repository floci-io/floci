package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssumeRolePolicyEvaluatorTest {

    private final AssumeRolePolicyEvaluator evaluator = new AssumeRolePolicyEvaluator(new ObjectMapper());

    private static final String CALLER_ARN = "arn:aws:iam::111111111111:user/alice";
    private static final String CALLER_ACCOUNT = "111111111111";

    private static String trust(String principal) {
        return """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":%s,"Action":"sts:AssumeRole"}]}
            """.formatted(principal);
    }

    @Test
    void allowsAccountRootPrincipal() {
        assertTrue(evaluator.allows(
                trust("{\"AWS\":\"arn:aws:iam::111111111111:root\"}"), CALLER_ARN, CALLER_ACCOUNT));
    }

    @Test
    void allowsBareAccountPrincipal() {
        assertTrue(evaluator.allows(trust("{\"AWS\":\"111111111111\"}"), CALLER_ARN, CALLER_ACCOUNT));
    }

    @Test
    void allowsExactPrincipalArn() {
        assertTrue(evaluator.allows(
                trust("{\"AWS\":\"arn:aws:iam::111111111111:user/alice\"}"), CALLER_ARN, CALLER_ACCOUNT));
    }

    @Test
    void allowsWildcardPrincipal() {
        assertTrue(evaluator.allows(trust("\"*\""), CALLER_ARN, CALLER_ACCOUNT));
        assertTrue(evaluator.allows(trust("{\"AWS\":\"*\"}"), CALLER_ARN, CALLER_ACCOUNT));
    }

    @Test
    void allowsWhenPrincipalListContainsCaller() {
        assertTrue(evaluator.allows(
                trust("{\"AWS\":[\"arn:aws:iam::999999999999:root\",\"arn:aws:iam::111111111111:root\"]}"),
                CALLER_ARN, CALLER_ACCOUNT));
    }

    @Test
    void deniesWhenAccountDoesNotMatch() {
        assertFalse(evaluator.allows(
                trust("{\"AWS\":\"arn:aws:iam::999999999999:root\"}"), CALLER_ARN, CALLER_ACCOUNT));
    }

    @Test
    void deniesWhenPrincipalArnDiffers() {
        assertFalse(evaluator.allows(
                trust("{\"AWS\":\"arn:aws:iam::111111111111:user/bob\"}"), CALLER_ARN, CALLER_ACCOUNT));
    }

    @Test
    void explicitDenyOverridesAllow() {
        String doc = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"AWS":"*"},"Action":"sts:AssumeRole"},
              {"Effect":"Deny","Principal":{"AWS":"arn:aws:iam::111111111111:root"},"Action":"sts:AssumeRole"}]}
            """;
        assertFalse(evaluator.allows(doc, CALLER_ARN, CALLER_ACCOUNT));
    }

    @Test
    void deniesWhenActionIsNotAssumeRole() {
        String doc = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"AWS":"*"},"Action":"sts:TagSession"}]}
            """;
        assertFalse(evaluator.allows(doc, CALLER_ARN, CALLER_ACCOUNT));
    }

    @Test
    void allowsWildcardAction() {
        String doc = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"AWS":"111111111111"},"Action":"sts:*"}]}
            """;
        assertTrue(evaluator.allows(doc, CALLER_ARN, CALLER_ACCOUNT));
    }

    @Test
    void deniesWhenDenyNotActionExcludesAssumeRole() {
        // Deny applies to every action except sts:TagSession — which includes sts:AssumeRole — so it blocks.
        String doc = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"AWS":"*"},"Action":"sts:AssumeRole"},
              {"Effect":"Deny","Principal":{"AWS":"*"},"NotAction":"sts:TagSession"}]}
            """;
        assertFalse(evaluator.allows(doc, CALLER_ARN, CALLER_ACCOUNT));
    }

    @Test
    void allowsWhenDenyNotActionIncludesAssumeRole() {
        // Deny applies to every action except sts:AssumeRole, so it does NOT block the assume.
        String doc = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"AWS":"*"},"Action":"sts:AssumeRole"},
              {"Effect":"Deny","Principal":{"AWS":"*"},"NotAction":"sts:AssumeRole"}]}
            """;
        assertTrue(evaluator.allows(doc, CALLER_ARN, CALLER_ACCOUNT));
    }

    @Test
    void allowsWhenAllowNotActionDoesNotCoverAssumeRole() {
        // Allow applies to every action except sts:GetSessionToken, so it grants sts:AssumeRole.
        String doc = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"AWS":"111111111111"},"NotAction":"sts:GetSessionToken"}]}
            """;
        assertTrue(evaluator.allows(doc, CALLER_ARN, CALLER_ACCOUNT));
    }

    @Test
    void allowsAssumedRoleCallerAgainstRolePrincipalArn() {
        // The caller used assumed-role temp creds (callerArn is the STS assumed-role ARN), but the
        // trust policy names the underlying IAM role ARN — the canonical trust-policy form. It must
        // match, since AWS resolves the session back to its role for trust evaluation.
        String assumedRoleArn = "arn:aws:sts::111111111111:assumed-role/AppRole/session-abc";
        assertTrue(evaluator.allows(
                trust("{\"AWS\":\"arn:aws:iam::111111111111:role/AppRole\"}"),
                assumedRoleArn, CALLER_ACCOUNT));
    }

    @Test
    void deniesAssumedRoleCallerWhenRolePrincipalDiffers() {
        // A trust policy naming a different role must not be satisfied by this session.
        String assumedRoleArn = "arn:aws:sts::111111111111:assumed-role/AppRole/session-abc";
        assertFalse(evaluator.allows(
                trust("{\"AWS\":\"arn:aws:iam::111111111111:role/OtherRole\"}"),
                assumedRoleArn, CALLER_ACCOUNT));
    }

    @Test
    void allowsAssumedRoleCallerAgainstExactSessionArn() {
        // A trust policy that names the exact assumed-role session ARN still matches directly.
        String assumedRoleArn = "arn:aws:sts::111111111111:assumed-role/AppRole/session-abc";
        assertTrue(evaluator.allows(
                trust("{\"AWS\":\"arn:aws:sts::111111111111:assumed-role/AppRole/session-abc\"}"),
                assumedRoleArn, CALLER_ACCOUNT));
    }

    @Test
    void deniesServiceOnlyPrincipal() {
        assertFalse(evaluator.allows(
                trust("{\"Service\":\"lambda.amazonaws.com\"}"), CALLER_ARN, CALLER_ACCOUNT));
    }

    @Test
    void deniesBlankOrMalformedDocument() {
        assertFalse(evaluator.allows(null, CALLER_ARN, CALLER_ACCOUNT));
        assertFalse(evaluator.allows("", CALLER_ARN, CALLER_ACCOUNT));
        assertFalse(evaluator.allows("{}", CALLER_ARN, CALLER_ACCOUNT));
        assertFalse(evaluator.allows("not json", CALLER_ARN, CALLER_ACCOUNT));
    }
}
