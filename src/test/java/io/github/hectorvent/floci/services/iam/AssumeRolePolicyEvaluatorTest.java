package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssumeRolePolicyEvaluatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AssumeRolePolicyEvaluator evaluator =
            new AssumeRolePolicyEvaluator(mapper, new IamPolicyEvaluator(mapper));

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
                trust("{\"AWS\":\"arn:aws:iam::111111111111:root\"}"),
                CALLER_ARN, CALLER_ARN, CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void allowsBareAccountPrincipal() {
        assertTrue(evaluator.allows(trust("{\"AWS\":\"111111111111\"}"),
                CALLER_ARN, CALLER_ARN, CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void allowsExactPrincipalArn() {
        assertTrue(evaluator.allows(
                trust("{\"AWS\":\"arn:aws:iam::111111111111:user/alice\"}"),
                CALLER_ARN, CALLER_ARN, CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void allowsWildcardPrincipal() {
        assertTrue(evaluator.allows(trust("\"*\""), CALLER_ARN, CALLER_ARN, CALLER_ACCOUNT, Map.of()));
        assertTrue(evaluator.allows(trust("{\"AWS\":\"*\"}"), CALLER_ARN, CALLER_ARN, CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void allowsWhenPrincipalListContainsCaller() {
        assertTrue(evaluator.allows(
                trust("{\"AWS\":[\"arn:aws:iam::999999999999:root\",\"arn:aws:iam::111111111111:root\"]}"),
                CALLER_ARN, CALLER_ARN, CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void deniesWhenAccountDoesNotMatch() {
        assertFalse(evaluator.allows(
                trust("{\"AWS\":\"arn:aws:iam::999999999999:root\"}"),
                CALLER_ARN, CALLER_ARN, CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void deniesWhenPrincipalArnDiffers() {
        assertFalse(evaluator.allows(
                trust("{\"AWS\":\"arn:aws:iam::111111111111:user/bob\"}"),
                CALLER_ARN, CALLER_ARN, CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void explicitDenyOverridesAllow() {
        String doc = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"AWS":"*"},"Action":"sts:AssumeRole"},
              {"Effect":"Deny","Principal":{"AWS":"arn:aws:iam::111111111111:root"},"Action":"sts:AssumeRole"}]}
            """;
        assertFalse(evaluator.allows(doc, CALLER_ARN, CALLER_ARN, CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void deniesWhenActionIsNotAssumeRole() {
        String doc = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"AWS":"*"},"Action":"sts:TagSession"}]}
            """;
        assertFalse(evaluator.allows(doc, CALLER_ARN, CALLER_ARN, CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void allowsWildcardAction() {
        String doc = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"AWS":"111111111111"},"Action":"sts:*"}]}
            """;
        assertTrue(evaluator.allows(doc, CALLER_ARN, CALLER_ARN, CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void deniesWhenDenyNotActionExcludesAssumeRole() {
        // Deny applies to every action except sts:TagSession — which includes sts:AssumeRole — so it blocks.
        String doc = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"AWS":"*"},"Action":"sts:AssumeRole"},
              {"Effect":"Deny","Principal":{"AWS":"*"},"NotAction":"sts:TagSession"}]}
            """;
        assertFalse(evaluator.allows(doc, CALLER_ARN, CALLER_ARN, CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void allowsWhenDenyNotActionIncludesAssumeRole() {
        // Deny applies to every action except sts:AssumeRole, so it does NOT block the assume.
        String doc = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"AWS":"*"},"Action":"sts:AssumeRole"},
              {"Effect":"Deny","Principal":{"AWS":"*"},"NotAction":"sts:AssumeRole"}]}
            """;
        assertTrue(evaluator.allows(doc, CALLER_ARN, CALLER_ARN, CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void allowsWhenAllowNotActionDoesNotCoverAssumeRole() {
        // Allow applies to every action except sts:GetSessionToken, so it grants sts:AssumeRole.
        String doc = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"AWS":"111111111111"},"NotAction":"sts:GetSessionToken"}]}
            """;
        assertTrue(evaluator.allows(doc, CALLER_ARN, CALLER_ARN, CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void allowsAssumedRoleCallerAgainstRolePrincipalArn() {
        // The caller used assumed-role temp creds (callerArn is the STS assumed-role ARN), but the
        // trust policy names the role itself, the canonical trust-policy form, so it matches through
        // the role's ARN.
        String assumedRoleArn = "arn:aws:sts::111111111111:assumed-role/AppRole/session-abc";
        assertTrue(evaluator.allows(
                trust("{\"AWS\":\"arn:aws:iam::111111111111:role/AppRole\"}"),
                assumedRoleArn, "arn:aws:iam::111111111111:role/AppRole", CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void assumedRoleCallerMatchesItsRoleByTheFullArnWithItsPath() {
        // IAM stores a Principal naming a role as the role's unique ID, so the session matches the
        // role's own ARN, path included, and not an ARN rebuilt from the session, which has no path.
        String assumedRoleArn = "arn:aws:sts::111111111111:assumed-role/AppRole/session-abc";
        String roleArn = "arn:aws:iam::111111111111:role/team/AppRole";
        assertTrue(evaluator.allows(trust("{\"AWS\":\"arn:aws:iam::111111111111:role/team/AppRole\"}"),
                assumedRoleArn, roleArn, CALLER_ACCOUNT, Map.of()));
        assertFalse(evaluator.allows(trust("{\"AWS\":\"arn:aws:iam::111111111111:role/AppRole\"}"),
                assumedRoleArn, roleArn, CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void deniesAssumedRoleCallerWhenRolePrincipalDiffers() {
        // A trust policy naming a different role must not be satisfied by this session.
        String assumedRoleArn = "arn:aws:sts::111111111111:assumed-role/AppRole/session-abc";
        assertFalse(evaluator.allows(
                trust("{\"AWS\":\"arn:aws:iam::111111111111:role/OtherRole\"}"),
                assumedRoleArn, "arn:aws:iam::111111111111:role/AppRole", CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void allowsAssumedRoleCallerAgainstExactSessionArn() {
        // A trust policy that names the exact assumed-role session ARN still matches directly.
        String assumedRoleArn = "arn:aws:sts::111111111111:assumed-role/AppRole/session-abc";
        assertTrue(evaluator.allows(
                trust("{\"AWS\":\"arn:aws:sts::111111111111:assumed-role/AppRole/session-abc\"}"),
                assumedRoleArn, "arn:aws:iam::111111111111:role/AppRole", CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void allowsAssumedRoleCallerAgainstRolePrincipalArnInGovCloud() {
        String assumedRoleArn = "arn:aws-us-gov:sts::111111111111:assumed-role/AppRole/session-abc";
        assertTrue(evaluator.allows(
                trust("{\"AWS\":\"arn:aws-us-gov:iam::111111111111:role/AppRole\"}"),
                assumedRoleArn, "arn:aws-us-gov:iam::111111111111:role/AppRole", CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void allowsAssumedRoleCallerAgainstRolePrincipalArnInChina() {
        String assumedRoleArn = "arn:aws-cn:sts::111111111111:assumed-role/AppRole/session-abc";
        assertTrue(evaluator.allows(
                trust("{\"AWS\":\"arn:aws-cn:iam::111111111111:role/AppRole\"}"),
                assumedRoleArn, "arn:aws-cn:iam::111111111111:role/AppRole", CALLER_ACCOUNT, Map.of()));
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
                assumedRoleArn, "arn:aws-us-gov:iam::111111111111:role/AppRole", CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void allowsAccountRootPrincipalInGovCloud() {
        String userArn = "arn:aws-us-gov:iam::111111111111:user/alice";
        assertTrue(evaluator.allows(
                trust("{\"AWS\":\"arn:aws-us-gov:iam::111111111111:root\"}"),
                userArn, userArn, CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void deniesServiceOnlyPrincipal() {
        assertFalse(evaluator.allows(
                trust("{\"Service\":\"lambda.amazonaws.com\"}"), CALLER_ARN, CALLER_ARN, CALLER_ACCOUNT, Map.of()));
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
    void serviceSourceArnWildcardCannotCrossArnComponents() {
        String doc = """
            {"Statement":{"Effect":"Allow","Principal":{"Service":"appsync.amazonaws.com"},
              "Action":"sts:AssumeRole",
              "Condition":{"ArnLike":{"aws:SourceArn":"arn:aws:lambda:us-east-1:*:worker"}}}}
            """;
        assertFalse(evaluator.allowsService(doc, "appsync.amazonaws.com",
                "arn:aws:lambda:us-east-1:111122223333:function:worker", "111122223333"));
    }

    @Test
    void externalIdConditionNeedsTheMatchingExternalId() {
        String doc = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"AWS":"111111111111"},"Action":"sts:AssumeRole",
               "Condition":{"StringEquals":{"sts:ExternalId":"Unique-ID-1"}}}]}
            """;
        assertFalse(evaluator.allows(doc, CALLER_ARN, CALLER_ARN, CALLER_ACCOUNT, Map.of()));
        assertFalse(evaluator.allows(doc, CALLER_ARN, CALLER_ARN, CALLER_ACCOUNT,
                Map.of("sts:ExternalId", List.of("unique-id-1"))));
        assertTrue(evaluator.allows(doc, CALLER_ARN, CALLER_ARN, CALLER_ACCOUNT,
                Map.of("sts:ExternalId", List.of("Unique-ID-1"))));
    }

    @Test
    void denyUnlessExternalIdRefusesACallWithoutOne() {
        // A negated operator on an absent key holds, so this Deny applies when ExternalId is missing.
        String doc = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"AWS":"111111111111"},"Action":"sts:AssumeRole"},
              {"Effect":"Deny","Principal":{"AWS":"111111111111"},"Action":"sts:AssumeRole",
               "Condition":{"StringNotEquals":{"sts:ExternalId":"Unique-ID-1"}}}]}
            """;
        assertFalse(evaluator.allows(doc, CALLER_ARN, CALLER_ARN, CALLER_ACCOUNT, Map.of()));
        assertFalse(evaluator.allows(doc, CALLER_ARN, CALLER_ARN, CALLER_ACCOUNT,
                Map.of("sts:ExternalId", List.of("other-id"))));
        assertTrue(evaluator.allows(doc, CALLER_ARN, CALLER_ARN, CALLER_ACCOUNT,
                Map.of("sts:ExternalId", List.of("Unique-ID-1"))));
    }

    @Test
    void conditionalDenyAppliesOnlyWhenItsConditionMatches() {
        String doc = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"AWS":"111111111111"},"Action":"sts:AssumeRole"},
              {"Effect":"Deny","Principal":{"AWS":"111111111111"},"Action":"sts:AssumeRole",
               "Condition":{"StringLike":{"sts:RoleSessionName":"blocked-*"}}}]}
            """;
        assertTrue(evaluator.allows(doc, CALLER_ARN, CALLER_ARN, CALLER_ACCOUNT,
                Map.of("sts:RoleSessionName", List.of("alice"))));
        assertFalse(evaluator.allows(doc, CALLER_ARN, CALLER_ARN, CALLER_ACCOUNT,
                Map.of("sts:RoleSessionName", List.of("blocked-alice"))));
    }

    @Test
    void principalArnConditionSeesTheRoleArnWithItsPath() {
        String doc = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"AWS":"111111111111"},"Action":"sts:AssumeRole",
               "Condition":{"ArnLike":{"aws:PrincipalArn":"arn:aws:iam::111111111111:role/team/*"}}}]}
            """;
        String sessionArn = "arn:aws:sts::111111111111:assumed-role/AppRole/session-abc";
        assertTrue(evaluator.allows(doc, sessionArn, "arn:aws:iam::111111111111:role/team/AppRole",
                CALLER_ACCOUNT, Map.of()));
        assertFalse(evaluator.allows(doc, sessionArn, "arn:aws:iam::111111111111:role/AppRole",
                CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void malformedConditionInvalidatesTheWholeTrustDocument() {
        String doc = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"AWS":"111111111111"},"Action":"sts:AssumeRole"},
              {"Effect":"Deny","Principal":{"AWS":"111111111111"},"Action":"sts:AssumeRole",
               "Condition":{"StringEquals":"sts:ExternalId"}}]}
            """;
        assertFalse(evaluator.allows(doc, CALLER_ARN, CALLER_ARN, CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void serviceTrustEvaluatesNegatedSourceConditions() {
        String doc = """
            {"Statement":[
              {"Effect":"Allow","Principal":{"Service":"appsync.amazonaws.com"},"Action":"sts:AssumeRole"},
              {"Effect":"Deny","Principal":{"Service":"appsync.amazonaws.com"},"Action":"sts:AssumeRole",
               "Condition":{"StringNotEquals":{"aws:SourceAccount":"000000000000"}}}]}
            """;
        assertTrue(evaluator.allowsService(doc, "appsync.amazonaws.com",
                "arn:aws:appsync:us-east-1:000000000000:apis/example", "000000000000"));
        assertFalse(evaluator.allowsService(doc, "appsync.amazonaws.com",
                "arn:aws:appsync:us-east-1:111111111111:apis/example", "111111111111"));
    }

    @Test
    void deniesBlankOrMalformedDocument() {
        assertFalse(evaluator.allows(null, CALLER_ARN, CALLER_ARN, CALLER_ACCOUNT, Map.of()));
        assertFalse(evaluator.allows("", CALLER_ARN, CALLER_ARN, CALLER_ACCOUNT, Map.of()));
        assertFalse(evaluator.allows("{}", CALLER_ARN, CALLER_ARN, CALLER_ACCOUNT, Map.of()));
        assertFalse(evaluator.allows("not json", CALLER_ARN, CALLER_ARN, CALLER_ACCOUNT, Map.of()));
    }
}
