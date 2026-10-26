package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.iam.AssumeRolePolicyEvaluator.TrustDecision;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssumeRolePolicyEvaluatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AssumeRolePolicyEvaluator evaluator =
            new AssumeRolePolicyEvaluator(mapper, new IamPolicyEvaluator(mapper));

    private static final String CALLER_ARN = "arn:aws:iam::111111111111:user/alice";
    private static final String CALLER_ACCOUNT = "111111111111";
    private static final IamService.CallerArns CALLER = caller(CALLER_ARN, CALLER_ARN);

    /** A caller carrying its two ARNs and no principal keys, which is what most of these need. */
    private static IamService.CallerArns caller(String callerArn, String principalArn) {
        return new IamService.CallerArns(callerArn, principalArn);
    }

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
    void anyPrincipalOfTheAccount_throughItsRootOrAPrincipalItNames() {
        assertTrue(evaluator.allowsAnyPrincipalOf(trust("{\"AWS\":\"arn:aws:iam::111111111111:root\"}"),
                CALLER_ACCOUNT, "aws"));
        assertTrue(evaluator.allowsAnyPrincipalOf(trust("{\"AWS\":\"111111111111\"}"), CALLER_ACCOUNT, "aws"));
        assertTrue(evaluator.allowsAnyPrincipalOf(trust("\"*\""), CALLER_ACCOUNT, "aws"));
        assertTrue(evaluator.allowsAnyPrincipalOf(
                trust("{\"AWS\":[\"arn:aws:iam::999999999999:root\",\"arn:aws:iam::111111111111:role/cfn-exec\"]}"),
                CALLER_ACCOUNT, "aws"));
        assertTrue(evaluator.allowsAnyPrincipalOf(trust("{\"AWS\":\"arn:aws:iam::111111111111:user/alice\"}"),
                CALLER_ACCOUNT, "aws"));
    }

    @Test
    void anyPrincipalOfTheAccount_notAnotherAccountNorAService() {
        assertFalse(evaluator.allowsAnyPrincipalOf(trust("{\"AWS\":\"arn:aws:iam::999999999999:root\"}"),
                CALLER_ACCOUNT, "aws"));
        assertFalse(evaluator.allowsAnyPrincipalOf(trust("{\"AWS\":\"arn:aws:iam::999999999999:role/cfn-exec\"}"),
                CALLER_ACCOUNT, "aws"));
        assertFalse(evaluator.allowsAnyPrincipalOf(trust("{\"Service\":\"lambda.amazonaws.com\"}"),
                CALLER_ACCOUNT, "aws"));
        assertFalse(evaluator.allowsAnyPrincipalOf(null, CALLER_ACCOUNT, "aws"));
    }

    /** A Deny on the account reaches each of its principals; a condition no key here answers grants nothing. */
    @Test
    void anyPrincipalOfTheAccount_honoursDenyAndConditions() {
        assertFalse(evaluator.allowsAnyPrincipalOf("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Principal":{"AWS":"arn:aws:iam::111111111111:role/cfn-exec"},"Action":"sts:AssumeRole"},
                  {"Effect":"Deny","Principal":{"AWS":"arn:aws:iam::111111111111:root"},"Action":"sts:AssumeRole"}]}
                """, CALLER_ACCOUNT, "aws"));
        assertFalse(evaluator.allowsAnyPrincipalOf("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Principal":{"AWS":"arn:aws:iam::111111111111:root"},"Action":"sts:AssumeRole",
                   "Condition":{"StringEquals":{"sts:ExternalId":"secret"}}}]}
                """, CALLER_ACCOUNT, "aws"));
    }

    @Test
    void allowsAccountRootPrincipal() {
        assertTrue(evaluator.allows(
                trust("{\"AWS\":\"arn:aws:iam::111111111111:root\"}"),
                CALLER, CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void allowsBareAccountPrincipal() {
        assertTrue(evaluator.allows(trust("{\"AWS\":\"111111111111\"}"),
                CALLER, CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void allowsExactPrincipalArn() {
        assertTrue(evaluator.allows(
                trust("{\"AWS\":\"arn:aws:iam::111111111111:user/alice\"}"),
                CALLER, CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void allowsWildcardPrincipal() {
        assertTrue(evaluator.allows(trust("\"*\""), CALLER, CALLER_ACCOUNT, Map.of()));
        assertTrue(evaluator.allows(trust("{\"AWS\":\"*\"}"), CALLER, CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void allowsWhenPrincipalListContainsCaller() {
        assertTrue(evaluator.allows(
                trust("{\"AWS\":[\"arn:aws:iam::999999999999:root\",\"arn:aws:iam::111111111111:root\"]}"),
                CALLER, CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void deniesWhenAccountDoesNotMatch() {
        assertFalse(evaluator.allows(
                trust("{\"AWS\":\"arn:aws:iam::999999999999:root\"}"),
                CALLER, CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void deniesWhenPrincipalArnDiffers() {
        assertFalse(evaluator.allows(
                trust("{\"AWS\":\"arn:aws:iam::111111111111:user/bob\"}"),
                CALLER, CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void explicitDenyOverridesAllow() {
        String doc = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"AWS":"*"},"Action":"sts:AssumeRole"},
              {"Effect":"Deny","Principal":{"AWS":"arn:aws:iam::111111111111:root"},"Action":"sts:AssumeRole"}]}
            """;
        assertFalse(evaluator.allows(doc, CALLER, CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void deniesWhenActionIsNotAssumeRole() {
        String doc = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"AWS":"*"},"Action":"sts:TagSession"}]}
            """;
        assertFalse(evaluator.allows(doc, CALLER, CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void allowsWildcardAction() {
        String doc = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"AWS":"111111111111"},"Action":"sts:*"}]}
            """;
        assertTrue(evaluator.allows(doc, CALLER, CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void deniesWhenDenyNotActionExcludesAssumeRole() {
        // Deny applies to every action except sts:TagSession — which includes sts:AssumeRole — so it blocks.
        String doc = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"AWS":"*"},"Action":"sts:AssumeRole"},
              {"Effect":"Deny","Principal":{"AWS":"*"},"NotAction":"sts:TagSession"}]}
            """;
        assertFalse(evaluator.allows(doc, CALLER, CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void allowsWhenDenyNotActionIncludesAssumeRole() {
        // Deny applies to every action except sts:AssumeRole, so it does NOT block the assume.
        String doc = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"AWS":"*"},"Action":"sts:AssumeRole"},
              {"Effect":"Deny","Principal":{"AWS":"*"},"NotAction":"sts:AssumeRole"}]}
            """;
        assertTrue(evaluator.allows(doc, CALLER, CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void allowsWhenAllowNotActionDoesNotCoverAssumeRole() {
        // Allow applies to every action except sts:GetSessionToken, so it grants sts:AssumeRole.
        String doc = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"AWS":"111111111111"},"NotAction":"sts:GetSessionToken"}]}
            """;
        assertTrue(evaluator.allows(doc, CALLER, CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void allowsAssumedRoleCallerAgainstRolePrincipalArn() {
        // The caller used assumed-role temp creds (callerArn is the STS assumed-role ARN), but the
        // trust policy names the role itself, the canonical trust-policy form, so it matches through
        // the role's ARN.
        String assumedRoleArn = "arn:aws:sts::111111111111:assumed-role/AppRole/session-abc";
        assertTrue(evaluator.allows(
                trust("{\"AWS\":\"arn:aws:iam::111111111111:role/AppRole\"}"),
                caller(assumedRoleArn, "arn:aws:iam::111111111111:role/AppRole"), CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void assumedRoleCallerMatchesItsRoleByTheFullArnWithItsPath() {
        // IAM stores a Principal naming a role as the role's unique ID, so the session matches the
        // role's own ARN, path included, and not an ARN rebuilt from the session, which has no path.
        String assumedRoleArn = "arn:aws:sts::111111111111:assumed-role/AppRole/session-abc";
        String roleArn = "arn:aws:iam::111111111111:role/team/AppRole";
        assertTrue(evaluator.allows(trust("{\"AWS\":\"arn:aws:iam::111111111111:role/team/AppRole\"}"),
                caller(assumedRoleArn, roleArn), CALLER_ACCOUNT, Map.of()));
        assertFalse(evaluator.allows(trust("{\"AWS\":\"arn:aws:iam::111111111111:role/AppRole\"}"),
                caller(assumedRoleArn, roleArn), CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void deniesAssumedRoleCallerWhenRolePrincipalDiffers() {
        // A trust policy naming a different role must not be satisfied by this session.
        String assumedRoleArn = "arn:aws:sts::111111111111:assumed-role/AppRole/session-abc";
        assertFalse(evaluator.allows(
                trust("{\"AWS\":\"arn:aws:iam::111111111111:role/OtherRole\"}"),
                caller(assumedRoleArn, "arn:aws:iam::111111111111:role/AppRole"), CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void allowsAssumedRoleCallerAgainstExactSessionArn() {
        // A trust policy that names the exact assumed-role session ARN still matches directly.
        String assumedRoleArn = "arn:aws:sts::111111111111:assumed-role/AppRole/session-abc";
        assertTrue(evaluator.allows(
                trust("{\"AWS\":\"arn:aws:sts::111111111111:assumed-role/AppRole/session-abc\"}"),
                caller(assumedRoleArn, "arn:aws:iam::111111111111:role/AppRole"), CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void allowsAssumedRoleCallerAgainstRolePrincipalArnInGovCloud() {
        String assumedRoleArn = "arn:aws-us-gov:sts::111111111111:assumed-role/AppRole/session-abc";
        assertTrue(evaluator.allows(
                trust("{\"AWS\":\"arn:aws-us-gov:iam::111111111111:role/AppRole\"}"),
                caller(assumedRoleArn, "arn:aws-us-gov:iam::111111111111:role/AppRole"), CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void allowsAssumedRoleCallerAgainstRolePrincipalArnInChina() {
        String assumedRoleArn = "arn:aws-cn:sts::111111111111:assumed-role/AppRole/session-abc";
        assertTrue(evaluator.allows(
                trust("{\"AWS\":\"arn:aws-cn:iam::111111111111:role/AppRole\"}"),
                caller(assumedRoleArn, "arn:aws-cn:iam::111111111111:role/AppRole"), CALLER_ACCOUNT, Map.of()));
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
                caller(assumedRoleArn, "arn:aws-us-gov:iam::111111111111:role/AppRole"), CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void allowsAccountRootPrincipalInGovCloud() {
        String userArn = "arn:aws-us-gov:iam::111111111111:user/alice";
        assertTrue(evaluator.allows(
                trust("{\"AWS\":\"arn:aws-us-gov:iam::111111111111:root\"}"),
                caller(userArn, userArn), CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void deniesServiceOnlyPrincipal() {
        assertFalse(evaluator.allows(
                trust("{\"Service\":\"lambda.amazonaws.com\"}"), CALLER, CALLER_ACCOUNT, Map.of()));
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
        assertFalse(evaluator.allows(doc, CALLER, CALLER_ACCOUNT, Map.of()));
        assertFalse(evaluator.allows(doc, CALLER, CALLER_ACCOUNT,
                Map.of("sts:ExternalId", List.of("unique-id-1"))));
        assertTrue(evaluator.allows(doc, CALLER, CALLER_ACCOUNT,
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
        assertFalse(evaluator.allows(doc, CALLER, CALLER_ACCOUNT, Map.of()));
        assertFalse(evaluator.allows(doc, CALLER, CALLER_ACCOUNT,
                Map.of("sts:ExternalId", List.of("other-id"))));
        assertTrue(evaluator.allows(doc, CALLER, CALLER_ACCOUNT,
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
        assertTrue(evaluator.allows(doc, CALLER, CALLER_ACCOUNT,
                Map.of("sts:RoleSessionName", List.of("alice"))));
        assertFalse(evaluator.allows(doc, CALLER, CALLER_ACCOUNT,
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
        assertTrue(evaluator.allows(doc, caller(sessionArn, "arn:aws:iam::111111111111:role/team/AppRole"),
                CALLER_ACCOUNT, Map.of()));
        assertFalse(evaluator.allows(doc, caller(sessionArn, "arn:aws:iam::111111111111:role/AppRole"),
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
        assertFalse(evaluator.allows(doc, CALLER, CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void aWildcardPrincipalNamesAServiceToo() {
        assertTrue(evaluator.allowsService(trust("{\"AWS\":\"*\"}"), "lambda.amazonaws.com"));
        assertTrue(evaluator.allowsService(trust("{\"AWS\":[\"*\"]}"), "lambda.amazonaws.com", null, null));
        assertTrue(evaluator.allowsService(trust("\"*\""), "lambda.amazonaws.com", null, null));
        assertFalse(evaluator.allowsService(trust("{\"AWS\":\"arn:aws:iam::000000000000:root\"}"),
                "lambda.amazonaws.com", null, null));
        String denied = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"},
              {"Effect":"Deny","Principal":{"AWS":"*"},"Action":"sts:AssumeRole"}]}
            """;
        assertFalse(evaluator.allowsService(denied, "lambda.amazonaws.com"));
        assertFalse(evaluator.allowsService(denied, "lambda.amazonaws.com", null, null));
    }

    @Test
    void serviceTrustSeesThePrincipalKeysOfTheService() {
        assertTrue(evaluator.allowsService(serviceTrust("{\"Bool\":{\"aws:PrincipalIsAWSService\":\"true\"}}"),
                "lambda.amazonaws.com", null, null));
        assertTrue(evaluator.allowsService(
                serviceTrust("{\"StringEquals\":{\"aws:PrincipalServiceName\":\"lambda.amazonaws.com\"}}"),
                "lambda.amazonaws.com", null, null));
        assertFalse(evaluator.allowsService(serviceTrust("{\"Bool\":{\"aws:PrincipalIsAWSService\":\"false\"}}"),
                "lambda.amazonaws.com", null, null));
        assertFalse(evaluator.allowsService(serviceTrust("{\"Null\":{\"aws:PrincipalIsAWSService\":\"true\"}}"),
                "lambda.amazonaws.com", null, null));
        assertFalse(evaluator.allowsService(serviceTrust("{\"Null\":{\"aws:PrincipalServiceName\":\"true\"}}"),
                "lambda.amazonaws.com", null, null));
    }

    private static String serviceTrust(String condition) {
        return """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole",
               "Condition":%s}]}
            """.formatted(condition);
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

    /**
     * A trust policy is a resource policy, and those take policy variables too. The caller's own
     * account is one of the keys the request carries, so a session name can be held to it.
     */
    @Test
    void aTrustConditionResolvesAPolicyVariable() {
        String doc = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"AWS":"111111111111"},"Action":"sts:AssumeRole",
               "Condition":{"StringLike":{"sts:RoleSessionName":"${aws:PrincipalAccount}-*"}}}]}
            """;
        assertTrue(evaluator.allows(doc, CALLER, CALLER_ACCOUNT,
                sessionNamed(CALLER_ACCOUNT + "-build")));
        assertFalse(evaluator.allows(doc, CALLER, CALLER_ACCOUNT,
                sessionNamed("222222222222-build")));
    }

    /** The same condition under the version that predates variables compares the text itself. */
    @Test
    void aTrustConditionVariableStaysLiteralWithoutTheVersion() {
        String doc = """
            {"Statement":[
              {"Effect":"Allow","Principal":{"AWS":"111111111111"},"Action":"sts:AssumeRole",
               "Condition":{"StringLike":{"sts:RoleSessionName":"${aws:PrincipalAccount}-*"}}}]}
            """;
        assertFalse(evaluator.allows(doc, CALLER, CALLER_ACCOUNT,
                sessionNamed(CALLER_ACCOUNT + "-build")));
        assertTrue(evaluator.allows(doc, CALLER, CALLER_ACCOUNT,
                sessionNamed("${aws:PrincipalAccount}-build")));
    }

    @Test
    void aTrustConditionReadsTheCallersPrincipalKeys() {
        String doc = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"AWS":"111111111111"},"Action":"sts:AssumeRole",
               "Condition":{"StringEquals":{"aws:username":"alice"}}}]}
            """;
        IamService.CallerArns alice = new IamService.CallerArns(CALLER_ARN, CALLER_ARN,
                IamService.CallerArns.TYPE_USER, "AIDAALICE", "alice");

        assertTrue(evaluator.allows(doc, alice, CALLER_ACCOUNT, Map.of()));
        // The same caller without the keys: aws:username is absent, so the statement does not apply.
        assertFalse(evaluator.allows(doc, CALLER, CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void aTrustConditionTellsAUserApartFromARoleSession() {
        String sessionArn = "arn:aws:sts::111111111111:assumed-role/AppRole/session-abc";
        String roleArn = "arn:aws:iam::111111111111:role/AppRole";
        IamService.CallerArns session = new IamService.CallerArns(sessionArn, roleArn,
                IamService.CallerArns.TYPE_ASSUMED_ROLE, "AROAAPPROLE:session-abc", null);
        IamService.CallerArns alice = new IamService.CallerArns(CALLER_ARN, CALLER_ARN,
                IamService.CallerArns.TYPE_USER, "AIDAALICE", "alice");
        String byType = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"AWS":"111111111111"},"Action":"sts:AssumeRole",
               "Condition":{"StringEquals":{"aws:PrincipalType":"AssumedRole"}}}]}
            """;
        String bySessionId = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"AWS":"111111111111"},"Action":"sts:AssumeRole",
               "Condition":{"StringLike":{"aws:userid":"AROAAPPROLE:*"}}}]}
            """;

        assertTrue(evaluator.allows(byType, session, CALLER_ACCOUNT, Map.of()));
        assertFalse(evaluator.allows(byType, alice, CALLER_ACCOUNT, Map.of()));
        assertTrue(evaluator.allows(bySessionId, session, CALLER_ACCOUNT, Map.of()));
        assertFalse(evaluator.allows(bySessionId, alice, CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void aPrincipalKeyTheCallerCarriesWinsOverTheRequestsOwnCopy() {
        // The caller is who the request is from, so its keys are written over the context the
        // request assembled, exactly as the enforcement filter writes them last.
        String doc = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"AWS":"111111111111"},"Action":"sts:AssumeRole",
               "Condition":{"StringEquals":{"aws:username":"alice"}}}]}
            """;
        IamService.CallerArns alice = new IamService.CallerArns(CALLER_ARN, CALLER_ARN,
                IamService.CallerArns.TYPE_USER, "AIDAALICE", "alice");

        assertTrue(evaluator.allows(doc, alice, CALLER_ACCOUNT, Map.of("aws:username", List.of("mallory"))));
    }

    private static Map<String, List<String>> sessionNamed(String sessionName) {
        return Map.of("aws:PrincipalAccount", List.of(CALLER_ACCOUNT),
                "sts:RoleSessionName", List.of(sessionName));
    }

    @Test
    void aGrantNamingTheCallerIsToldApartFromAGrantToItsAccount() {
        assertEquals(TrustDecision.ALLOW, evaluator.decide(trust("{\"AWS\":\"" + CALLER_ARN + "\"}"),
                CALLER, CALLER_ACCOUNT, Map.of()));
        assertEquals(TrustDecision.ALLOW, evaluator.decide(trust("\"*\""),
                CALLER, CALLER_ACCOUNT, Map.of()));
        assertEquals(TrustDecision.ALLOW_ACCOUNT, evaluator.decide(trust("{\"AWS\":\"arn:aws:iam::111111111111:root\"}"),
                CALLER, CALLER_ACCOUNT, Map.of()));
        assertEquals(TrustDecision.ALLOW_ACCOUNT, evaluator.decide(trust("{\"AWS\":\"111111111111\"}"),
                CALLER, CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void aStatementNamingBothTheAccountAndTheCallerGrantsToTheCaller() {
        String doc = trust("{\"AWS\":[\"111111111111\",\"" + CALLER_ARN + "\"]}");
        assertEquals(TrustDecision.ALLOW, evaluator.decide(doc, CALLER, CALLER_ACCOUNT, Map.of()));

        String twoStatements = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"AWS":"%1$s"},"Action":"sts:AssumeRole"},
              {"Effect":"Allow","Principal":{"AWS":"111111111111"},"Action":"sts:AssumeRole"}]}
            """.formatted(CALLER_ARN);
        assertEquals(TrustDecision.ALLOW,
                evaluator.decide(twoStatements, CALLER, CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void aDenyOrASilentPolicyIsReportedAsSuch() {
        String deny = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"AWS":"%1$s"},"Action":"sts:AssumeRole"},
              {"Effect":"Deny","Principal":{"AWS":"111111111111"},"Action":"sts:AssumeRole"}]}
            """.formatted(CALLER_ARN);
        assertEquals(TrustDecision.EXPLICIT_DENY, evaluator.decide(deny, CALLER, CALLER_ACCOUNT, Map.of()));
        assertEquals(TrustDecision.NEUTRAL, evaluator.decide(trust("{\"AWS\":\"222222222222\"}"),
                CALLER, CALLER_ACCOUNT, Map.of()));
        assertEquals(TrustDecision.NEUTRAL, evaluator.decide(null, CALLER, CALLER_ACCOUNT, Map.of()));
    }

    @Test
    void deniesBlankOrMalformedDocument() {
        assertFalse(evaluator.allows(null, CALLER, CALLER_ACCOUNT, Map.of()));
        assertFalse(evaluator.allows("", CALLER, CALLER_ACCOUNT, Map.of()));
        assertFalse(evaluator.allows("{}", CALLER, CALLER_ACCOUNT, Map.of()));
        assertFalse(evaluator.allows("not json", CALLER, CALLER_ACCOUNT, Map.of()));
    }
}
