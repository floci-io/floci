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
    void allowsAServicePrincipalInTheUniversalForm() {
        assertTrue(evaluator.allowsService(
                trust("{\"Service\":\"redshift.amazonaws.com\"}"), "redshift.amazonaws.com"));
        assertFalse(evaluator.allowsService(
                trust("{\"Service\":\"lambda.amazonaws.com\"}"), "redshift.amazonaws.com"));
    }

    @Test
    void allowsAServicePrincipalWrittenInTheLegacyPartitionForm() {
        // AWS still honours the per-partition forms it used before the universal rule, so a
        // trust policy written in China or ISO spelling matches the universal principal.
        assertTrue(evaluator.allowsService(
                trust("{\"Service\":\"elasticmapreduce.amazonaws.com.cn\"}"), "elasticmapreduce.amazonaws.com"));
        assertTrue(evaluator.allowsService(
                trust("{\"Service\":[\"logs.cn-north-1.amazonaws.com.cn\",\"lambda.amazonaws.com\"]}"),
                "logs.amazonaws.com"));
        assertTrue(evaluator.allowsService(trust("{\"Service\":\"config.c2s.ic.gov\"}"), "config.amazonaws.com"));
        // And the other way round: the check itself may arrive in a legacy spelling.
        assertTrue(evaluator.allowsService(
                trust("{\"Service\":\"redshift.amazonaws.com\"}"), "redshift.amazonaws.com.cn"));
    }

    @Test
    void aServicePrincipalMatchesExactlyAndCaseSensitivelyInEitherForm() {
        assertFalse(evaluator.allowsService(trust("{\"Service\":\"*.amazonaws.com\"}"), "redshift.amazonaws.com"));
        assertFalse(evaluator.allowsService(
                trust("{\"Service\":\"Redshift.amazonaws.com\"}"), "redshift.amazonaws.com"));
        assertFalse(evaluator.allowsService(
                trust("{\"Service\":\"redshift.AMAZONAWS.COM.CN\"}"), "redshift.amazonaws.com"));
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
    void allowsAssumedRoleCallerAgainstRolePrincipalArnInGovCloud() {
        // The silent-denial case. Resolving the session back to its role used to rebuild the role
        // ARN with a hardcoded "aws" partition, so a GovCloud caller was compared against
        // arn:aws:iam::... and never matched the arn:aws-us-gov:iam::... principal its own trust
        // policy names. AssumeRole was refused with nothing in the response explaining why.
        String assumedRoleArn = "arn:aws-us-gov:sts::111111111111:assumed-role/AppRole/session-abc";
        assertTrue(evaluator.allows(
                trust("{\"AWS\":\"arn:aws-us-gov:iam::111111111111:role/AppRole\"}"),
                assumedRoleArn, CALLER_ACCOUNT));
    }

    @Test
    void allowsAssumedRoleCallerAgainstRolePrincipalArnInChina() {
        String assumedRoleArn = "arn:aws-cn:sts::111111111111:assumed-role/AppRole/session-abc";
        assertTrue(evaluator.allows(
                trust("{\"AWS\":\"arn:aws-cn:iam::111111111111:role/AppRole\"}"),
                assumedRoleArn, CALLER_ACCOUNT));
    }

    /**
     * The guard against over-widening. Partitions are isolated, so a session in one must not
     * satisfy a trust policy written for another even when account, role and session all match.
     */
    @Test
    void deniesAssumedRoleCallerFromAnotherPartition() {
        String assumedRoleArn = "arn:aws-us-gov:sts::111111111111:assumed-role/AppRole/session-abc";
        assertFalse(evaluator.allows(
                trust("{\"AWS\":\"arn:aws:iam::111111111111:role/AppRole\"}"),
                assumedRoleArn, CALLER_ACCOUNT));
    }

    @Test
    void allowsAccountRootPrincipalInGovCloud() {
        assertTrue(evaluator.allows(
                trust("{\"AWS\":\"arn:aws-us-gov:iam::111111111111:root\"}"),
                "arn:aws-us-gov:iam::111111111111:user/alice", CALLER_ACCOUNT));
    }

    @Test
    void deniesServiceOnlyPrincipal() {
        assertFalse(evaluator.allows(
                trust("{\"Service\":\"lambda.amazonaws.com\"}"), CALLER_ARN, CALLER_ACCOUNT));
    }

    @Test
    void allowsMatchingServicePrincipal() {
        assertTrue(evaluator.allowsService(trust("{\"Service\":\"redshift.amazonaws.com\"}"),
                "redshift.amazonaws.com"));
    }

    @Test
    void deniesDifferentServicePrincipal() {
        assertFalse(evaluator.allowsService(trust("{\"Service\":\"lambda.amazonaws.com\"}"),
                "redshift.amazonaws.com"));
    }

    @Test
    void explicitServiceDenyOverridesAllow() {
        String doc = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"Service":"redshift.amazonaws.com"},"Action":"sts:AssumeRole"},
              {"Effect":"Deny","Principal":{"Service":"redshift.amazonaws.com"},"Action":"sts:AssumeRole"}]}
            """;
        assertFalse(evaluator.allowsService(doc, "redshift.amazonaws.com"));
    }

    @Test
    void malformedEffectInvalidatesTheWholeTrustDocument() {
        String doc = """
            {"Statement":[
              {"Effect":"Allow","Principal":{"Service":"appsync.amazonaws.com"},"Action":"sts:AssumeRole"},
              {"Principal":{"Service":"appsync.amazonaws.com"},"Action":"sts:AssumeRole"}]}
            """;
        assertFalse(evaluator.allowsService(doc, "appsync.amazonaws.com"));
        assertFalse(evaluator.allowsService(doc, "appsync.amazonaws.com",
                "arn:aws:appsync:us-east-1:000000000000:apis/example", "000000000000"));
    }

    @Test
    void serviceSourceArnWildcardMatchesWithCaseSensitivity() {
        String doc = """
            {"Statement":{"Effect":"Allow","Principal":{"Service":"appsync.amazonaws.com"},
              "Action":"sts:AssumeRole",
              "Condition":{"ArnLike":{"aws:SourceArn":"arn:aws:appsync:us-east-1:000000000000:apis/Ex*"}}}}
            """;
        assertTrue(evaluator.allowsService(doc, "appsync.amazonaws.com",
                "arn:aws:appsync:us-east-1:000000000000:apis/Example", "000000000000"));
        assertFalse(evaluator.allowsService(doc, "appsync.amazonaws.com",
                "arn:aws:appsync:us-east-1:000000000000:apis/example", "000000000000"));
    }

    @Test
    void deniesBlankOrMalformedDocument() {
        assertFalse(evaluator.allows(null, CALLER_ARN, CALLER_ACCOUNT));
        assertFalse(evaluator.allows("", CALLER_ARN, CALLER_ACCOUNT));
        assertFalse(evaluator.allows("{}", CALLER_ARN, CALLER_ACCOUNT));
        assertFalse(evaluator.allows("not json", CALLER_ARN, CALLER_ACCOUNT));
    }
}
