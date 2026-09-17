package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.ResourceAccountRelationship;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.ResourcePolicyDecision;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * SCP semantics in policy evaluation: service control policies gate the decision before
 * identity policies, an action must be allowed at every organization level, and a deny
 * at any level wins regardless of identity-policy allows.
 */
class IamPolicyEvaluatorTest {

    private static final String ALLOW_ALL =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                    + "\"Action\":\"*\",\"Resource\":\"*\"}]}";
    private static final String ALLOW_S3_ONLY =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                    + "\"Action\":\"s3:*\",\"Resource\":\"*\"}]}";
    private static final String DENY_S3 =
            "{\"Version\":\"2012-10-17\",\"Statement\":["
                    + "{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"},"
                    + "{\"Effect\":\"Deny\",\"Action\":\"s3:*\",\"Resource\":\"*\"}]}";

    private static final String MALFORMED = "{\"Version\":\"2012-10-17\",\"Statement\":[";

    private final IamPolicyEvaluator evaluator = new IamPolicyEvaluator(new ObjectMapper());

    private static CallerContext adminWithScps(List<List<String>> scpLevels) {
        return CallerContext.of(List.of(ALLOW_ALL)).withScpLevels(scpLevels);
    }

    @Test
    void withoutScpLevelsIdentityDecides() {
        CallerContext caller = CallerContext.of(List.of(ALLOW_ALL));
        assertEquals(Decision.ALLOW,
                evaluator.evaluate(caller, null, "s3:GetObject", "*", null));
    }

    @Test
    void resolvedResourceAllowCompletesIdentityImplicitDeny() {
        CallerContext caller = CallerContext.of(List.of());

        assertEquals(Decision.ALLOW, evaluator.evaluateResolvedResourcePolicy(
                caller,
                ResourcePolicyDecision.ALLOW,
                "s3:GetObject",
                "arn:aws:s3:::bucket/key",
                null));
    }

    @Test
    void identityExplicitDenyOverridesResolvedResourceAllow() {
        CallerContext caller = CallerContext.of(List.of("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Deny","Action":"s3:GetObject","Resource":"*"}
                ]}"""));

        assertEquals(Decision.DENY, evaluator.evaluateResolvedResourcePolicy(
                caller,
                ResourcePolicyDecision.ALLOW,
                "s3:GetObject",
                "arn:aws:s3:::bucket/key",
                null));
    }

    @Test
    void resolvedResourceExplicitDenyOverridesIdentityAllow() {
        CallerContext caller = CallerContext.of(List.of(ALLOW_ALL));

        assertEquals(Decision.DENY, evaluator.evaluateResolvedResourcePolicy(
                caller,
                ResourcePolicyDecision.EXPLICIT_DENY,
                "s3:GetObject",
                "arn:aws:s3:::bucket/key",
                null));
    }

    @Test
    void crossAccountIdentityAllowRequiresResourceAllow() {
        CallerContext caller = CallerContext.of(List.of(ALLOW_ALL));

        assertEquals(Decision.DENY, evaluator.evaluateResolvedResourcePolicy(
                caller,
                ResourcePolicyDecision.NEUTRAL,
                ResourceAccountRelationship.CROSS_ACCOUNT,
                "s3:GetObject",
                "arn:aws:s3:::bucket/key",
                null));
    }

    @Test
    void crossAccountResourceAllowRequiresIdentityAllow() {
        CallerContext caller = CallerContext.of(List.of());

        assertEquals(Decision.DENY, evaluator.evaluateResolvedResourcePolicy(
                caller,
                ResourcePolicyDecision.ALLOW,
                ResourceAccountRelationship.CROSS_ACCOUNT,
                "s3:GetObject",
                "arn:aws:s3:::bucket/key",
                null));
    }

    @Test
    void crossAccountAccessAllowsWhenIdentityAndResourcePoliciesAllow() {
        CallerContext caller = CallerContext.of(List.of(ALLOW_ALL));

        assertEquals(Decision.ALLOW, evaluator.evaluateResolvedResourcePolicy(
                caller,
                ResourcePolicyDecision.ALLOW,
                ResourceAccountRelationship.CROSS_ACCOUNT,
                "s3:GetObject",
                "arn:aws:s3:::bucket/key",
                null));
    }

    @Test
    void sameAccountDirectUserGrantBypassesBoundaryImplicitDeny() {
        CallerContext caller = new CallerContext(
                List.of(),
                null,
                """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"dynamodb:*","Resource":"*"}
                ]}""");

        assertEquals(Decision.ALLOW, evaluator.evaluateResolvedResourcePolicy(
                caller,
                ResourcePolicyDecision.ALLOW_DIRECT_IAM_USER,
                ResourceAccountRelationship.SAME_ACCOUNT,
                "s3:GetObject",
                "arn:aws:s3:::bucket/key",
                null));
    }

    @Test
    void sameAccountWildcardGrantRemainsLimitedByBoundary() {
        CallerContext caller = new CallerContext(
                List.of(),
                null,
                """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"dynamodb:*","Resource":"*"}
                ]}""");

        assertEquals(Decision.DENY, evaluator.evaluateResolvedResourcePolicy(
                caller,
                ResourcePolicyDecision.ALLOW,
                ResourceAccountRelationship.SAME_ACCOUNT,
                "s3:GetObject",
                "arn:aws:s3:::bucket/key",
                null));
    }

    @Test
    void sameAccountDirectUserGrantDoesNotBypassBoundaryExplicitDeny() {
        CallerContext caller = new CallerContext(
                List.of(),
                null,
                DENY_S3);

        assertEquals(Decision.DENY, evaluator.evaluateResolvedResourcePolicy(
                caller,
                ResourcePolicyDecision.ALLOW_DIRECT_IAM_USER,
                ResourceAccountRelationship.SAME_ACCOUNT,
                "s3:GetObject",
                "arn:aws:s3:::bucket/key",
                null));
    }

    @Test
    void scpDenyWinsOverIdentityAllow() {
        CallerContext caller = adminWithScps(List.of(List.of(DENY_S3)));
        assertEquals(Decision.DENY,
                evaluator.evaluate(caller, null, "s3:GetObject", "*", null));
        assertEquals(Decision.ALLOW,
                evaluator.evaluate(caller, null, "ec2:DescribeInstances", "*", null));
    }

    @Test
    void actionMustBeAllowedAtEveryLevel() {
        // Root allows everything, the OU level only allows s3.
        CallerContext caller = adminWithScps(List.of(List.of(ALLOW_ALL), List.of(ALLOW_S3_ONLY)));
        assertEquals(Decision.ALLOW,
                evaluator.evaluate(caller, null, "s3:GetObject", "*", null));
        assertEquals(Decision.DENY,
                evaluator.evaluate(caller, null, "ec2:DescribeInstances", "*", null));
    }

    @Test
    void scpAllowIsNotAGrant() {
        // SCPs permit s3 but the identity has no policy allowing it: still denied.
        CallerContext caller = CallerContext.of(List.of())
                .withScpLevels(List.of(List.of(ALLOW_ALL)));
        assertEquals(Decision.DENY,
                evaluator.evaluate(caller, null, "s3:GetObject", "*", null));
    }

    @Test
    void unparseableScpDeniesEvenWhenTheLevelAlsoHoldsFullAwsAccess() {
        // FullAWSAccess is attached to every target, so a level almost always carries it
        // alongside the customer's guardrail. Dropping the malformed guardrail would leave
        // FullAWSAccess allowing the action — the ceiling has to fail closed instead.
        CallerContext caller = adminWithScps(List.of(List.of(MALFORMED, ALLOW_ALL)));
        assertEquals(Decision.DENY,
                evaluator.evaluate(caller, null, "s3:GetObject", "*", null));
    }

    @Test
    void allUnparseableScpsInALevelDeny() {
        CallerContext caller = adminWithScps(List.of(List.of(MALFORMED)));
        assertEquals(Decision.DENY,
                evaluator.evaluate(caller, null, "s3:GetObject", "*", null));
    }

    @Test
    void unparseableIdentityPolicyDoesNotAffectOtherPolicies() {
        // Only the SCP ceiling fails closed; identity evaluation keeps skipping bad documents.
        CallerContext caller = CallerContext.of(List.of(MALFORMED, ALLOW_ALL));
        assertEquals(Decision.ALLOW,
                evaluator.evaluate(caller, null, "s3:GetObject", "*", null));
    }

    @Test
    void emptyLevelIsFullAwsAccessSemantics() {
        CallerContext caller = adminWithScps(List.of(List.of(), List.of(ALLOW_ALL)));
        assertEquals(Decision.ALLOW,
                evaluator.evaluate(caller, null, "s3:GetObject", "*", null));
    }

    @Test
    void singleValuedConditionKeyStillMatchesUnderTheMultiValuedContext() {
        // A plain (non-set) operator against a one-element list keeps today's semantics:
        // the first value is compared against the OR of the policy's condition values.
        String policy = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"s3:GetObject","Resource":"*",
               "Condition":{"StringEquals":{"aws:PrincipalArn":"arn:aws:iam::111122223333:user/alice"}}}
            ]}""";

        assertEquals(Decision.ALLOW, evaluator.simulateCustomPolicy(
                List.of(policy), "s3:GetObject", "arn:aws:s3:::bucket/key",
                Map.of("aws:PrincipalArn", List.of("arn:aws:iam::111122223333:user/alice"))));
        assertEquals(Decision.DENY, evaluator.simulateCustomPolicy(
                List.of(policy), "s3:GetObject", "arn:aws:s3:::bucket/key",
                Map.of("aws:PrincipalArn", List.of("arn:aws:iam::111122223333:user/bob"))));
    }

    private static final String LEADING_KEYS_FOR_ALL = """
        {"Version":"2012-10-17","Statement":[
          {"Effect":"Allow","Action":"dynamodb:GetItem","Resource":"*",
           "Condition":{"ForAllValues:StringLike":{"dynamodb:LeadingKeys":["USER_alice*"]}}}
        ]}""";

    @Test
    void forAllValuesRequiresEveryContextValueToMatch() {
        assertEquals(Decision.ALLOW, evaluator.simulateCustomPolicy(
                List.of(LEADING_KEYS_FOR_ALL), "dynamodb:GetItem", "*",
                Map.of("dynamodb:LeadingKeys", List.of("USER_alice"))));
        assertEquals(Decision.DENY, evaluator.simulateCustomPolicy(
                List.of(LEADING_KEYS_FOR_ALL), "dynamodb:GetItem", "*",
                Map.of("dynamodb:LeadingKeys", List.of("USER_bob"))));
        assertEquals(Decision.ALLOW, evaluator.simulateCustomPolicy(
                List.of(LEADING_KEYS_FOR_ALL), "dynamodb:GetItem", "*",
                Map.of("dynamodb:LeadingKeys", List.of("USER_alice", "USER_alice_2"))));
        assertEquals(Decision.DENY, evaluator.simulateCustomPolicy(
                List.of(LEADING_KEYS_FOR_ALL), "dynamodb:GetItem", "*",
                Map.of("dynamodb:LeadingKeys", List.of("USER_alice", "USER_bob"))));
    }

    @Test
    void forAnyValueRequiresAtLeastOneContextValueToMatch() {
        String policy = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"dynamodb:GetItem","Resource":"*",
               "Condition":{"ForAnyValue:StringEquals":{"dynamodb:LeadingKeys":["USER_alice"]}}}
            ]}""";

        assertEquals(Decision.ALLOW, evaluator.simulateCustomPolicy(
                List.of(policy), "dynamodb:GetItem", "*",
                Map.of("dynamodb:LeadingKeys", List.of("USER_bob", "USER_alice"))));
        assertEquals(Decision.DENY, evaluator.simulateCustomPolicy(
                List.of(policy), "dynamodb:GetItem", "*",
                Map.of("dynamodb:LeadingKeys", List.of("USER_bob", "USER_carol"))));
    }

    @Test
    void emptySetMatchesForAllValuesAndNotForAnyValue() {
        // AWS: ForAllValues over an empty set is vacuously true; ForAnyValue is false.
        String anyValue = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"dynamodb:GetItem","Resource":"*",
               "Condition":{"ForAnyValue:StringLike":{"dynamodb:LeadingKeys":["USER_alice*"]}}}
            ]}""";

        assertEquals(Decision.ALLOW, evaluator.simulateCustomPolicy(
                List.of(LEADING_KEYS_FOR_ALL), "dynamodb:GetItem", "*",
                Map.of("dynamodb:LeadingKeys", List.<String>of())));
        assertEquals(Decision.DENY, evaluator.simulateCustomPolicy(
                List.of(anyValue), "dynamodb:GetItem", "*",
                Map.of("dynamodb:LeadingKeys", List.<String>of())));
    }

    @Test
    void setOperatorWithoutIfExistsFailsClosedWhenTheKeyIsAbsent() {
        // The key is absent from the context entirely — not an empty set. A request that
        // cannot be proven in scope is treated as out of scope.
        assertEquals(Decision.DENY, evaluator.simulateCustomPolicy(
                List.of(LEADING_KEYS_FOR_ALL), "dynamodb:GetItem", "*", Map.of()));
    }

    @Test
    void setOperatorComposesWithIfExists() {
        String policy = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"dynamodb:GetItem","Resource":"*",
               "Condition":{"ForAnyValue:StringEqualsIfExists":{"dynamodb:LeadingKeys":["USER_alice"]}}}
            ]}""";

        assertEquals(Decision.ALLOW, evaluator.simulateCustomPolicy(
                List.of(policy), "dynamodb:GetItem", "*", Map.of()));
        assertEquals(Decision.DENY, evaluator.simulateCustomPolicy(
                List.of(policy), "dynamodb:GetItem", "*",
                Map.of("dynamodb:LeadingKeys", List.of("USER_bob"))));
    }

    @Test
    void nullGuardBlocksTheVacuousForAllValuesAllowWhenTheKeyIsAbsent() {
        // The idiomatic AWS pairing: ForAllValues plus Null:false so the vacuous
        // empty-set truth cannot grant access when the key was never populated.
        String policy = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"dynamodb:GetItem","Resource":"*",
               "Condition":{
                 "ForAllValues:StringLikeIfExists":{"dynamodb:LeadingKeys":["USER_alice*"]},
                 "Null":{"dynamodb:LeadingKeys":"false"}}}
            ]}""";

        assertEquals(Decision.DENY, evaluator.simulateCustomPolicy(
                List.of(policy), "dynamodb:GetItem", "*", Map.of()));
        assertEquals(Decision.ALLOW, evaluator.simulateCustomPolicy(
                List.of(policy), "dynamodb:GetItem", "*",
                Map.of("dynamodb:LeadingKeys", List.of("USER_alice"))));
    }

    @Test
    void setOperatorPrefixIsMatchedCaseSensitively() {
        // AWS spells these exactly "ForAllValues:" / "ForAnyValue:". A different spelling is
        // an unknown operator and must not silently behave like the real thing.
        String allValues = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"dynamodb:GetItem","Resource":"*",
               "Condition":{"forallvalues:StringLike":{"dynamodb:LeadingKeys":["USER_alice*"]}}}
            ]}""";
        String anyValue = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"dynamodb:GetItem","Resource":"*",
               "Condition":{"forAnyValue:StringLike":{"dynamodb:LeadingKeys":["USER_alice*"]}}}
            ]}""";

        assertEquals(Decision.DENY, evaluator.simulateCustomPolicy(
                List.of(allValues), "dynamodb:GetItem", "*",
                Map.of("dynamodb:LeadingKeys", List.of("USER_alice"))));
        assertEquals(Decision.DENY, evaluator.simulateCustomPolicy(
                List.of(anyValue), "dynamodb:GetItem", "*",
                Map.of("dynamodb:LeadingKeys", List.of("USER_alice"))));
    }

    @Test
    void forAllValuesWithANegatedOperatorAndsAcrossThePolicyValues() {
        // The deny-list idiom for dynamodb:Attributes: allow only while none of the
        // attributes the request touches is one of the forbidden names. AWS ANDs the
        // negated match across the listed values; an OR would let "ssn" through as long
        // as it differed from "secret".
        String policy = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"dynamodb:GetItem","Resource":"*",
               "Condition":{"ForAllValues:StringNotEquals":{"dynamodb:Attributes":["secret","ssn"]}}}
            ]}""";

        assertEquals(Decision.ALLOW, evaluator.simulateCustomPolicy(
                List.of(policy), "dynamodb:GetItem", "*",
                Map.of("dynamodb:Attributes", List.of("name", "email"))));
        assertEquals(Decision.DENY, evaluator.simulateCustomPolicy(
                List.of(policy), "dynamodb:GetItem", "*",
                Map.of("dynamodb:Attributes", List.of("name", "ssn"))));
    }

    @Test
    void nullTreatsAPresentButEmptySetAsAbsent() {
        // Same idiomatic pairing as the key-absent case, but here the key is present with an
        // empty set. AWS treats an empty-set key as nonexistent for Null, so the Null:false
        // guard must still block the vacuous ForAllValues allow.
        String policy = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"dynamodb:GetItem","Resource":"*",
               "Condition":{
                 "ForAllValues:StringLike":{"dynamodb:LeadingKeys":["USER_alice*"]},
                 "Null":{"dynamodb:LeadingKeys":"false"}}}
            ]}""";

        assertEquals(Decision.DENY, evaluator.simulateCustomPolicy(
                List.of(policy), "dynamodb:GetItem", "*",
                Map.of("dynamodb:LeadingKeys", List.of())));
        assertEquals(Decision.ALLOW, evaluator.simulateCustomPolicy(
                List.of(policy), "dynamodb:GetItem", "*",
                Map.of("dynamodb:LeadingKeys", List.of("USER_alice"))));
    }

    @Test
    void sameDocumentIsParsedOnce() throws Exception {
        ObjectMapper mapper = spy(new ObjectMapper());
        IamPolicyEvaluator cachingEvaluator = new IamPolicyEvaluator(mapper);
        CallerContext caller = CallerContext.of(List.of(ALLOW_S3_ONLY));

        assertEquals(Decision.ALLOW, cachingEvaluator.evaluate(caller, null, "s3:GetObject", "*", null));
        assertEquals(Decision.DENY, cachingEvaluator.evaluate(caller, null, "sqs:SendMessage", "*", null));

        verify(mapper, times(1)).readTree(anyString());
    }

    @Test
    void malformedDocumentIsParsedOnceAndStillDeniesScpLevel() throws Exception {
        ObjectMapper mapper = spy(new ObjectMapper());
        IamPolicyEvaluator cachingEvaluator = new IamPolicyEvaluator(mapper);
        CallerContext caller = adminWithScps(List.of(List.of(MALFORMED, ALLOW_ALL)));

        assertEquals(Decision.DENY, cachingEvaluator.evaluate(caller, null, "s3:GetObject", "*", null));
        assertEquals(Decision.DENY, cachingEvaluator.evaluate(caller, null, "s3:GetObject", "*", null));

        verify(mapper, times(1)).readTree(MALFORMED);
        verify(mapper, times(1)).readTree(ALLOW_ALL);
    }

    @Test
    void cacheIsClearedWhenBoundIsExceeded() throws Exception {
        ObjectMapper mapper = spy(new ObjectMapper());
        IamPolicyEvaluator cachingEvaluator = new IamPolicyEvaluator(mapper);
        String first = sidDocument(0);
        cachingEvaluator.evaluate(CallerContext.of(List.of(first)), null, "s3:GetObject", "*", null);
        for (int i = 1; i <= IamPolicyEvaluator.MAX_CACHED_DOCUMENTS; i++) {
            cachingEvaluator.evaluate(CallerContext.of(List.of(sidDocument(i))), null, "s3:GetObject", "*", null);
        }

        cachingEvaluator.evaluate(CallerContext.of(List.of(first)), null, "s3:GetObject", "*", null);

        verify(mapper, times(2)).readTree(first);
    }

    @Test
    void actionMatchingIsCaseInsensitive() {
        String mixedCase = "{\"Version\":\"2012-10-17\",\"Statement\":["
                + "{\"Effect\":\"Allow\",\"Action\":\"S3:GetObject\",\"Resource\":\"arn:aws:s3:::bucket/*\"},"
                + "{\"Effect\":\"Deny\",\"NotAction\":\"s3:*\",\"Resource\":\"*\"}]}";
        CallerContext caller = CallerContext.of(List.of(mixedCase));

        assertEquals(Decision.ALLOW,
                evaluator.evaluate(caller, null, "s3:getobject", "arn:aws:s3:::bucket/key", null));
        assertEquals(Decision.ALLOW,
                evaluator.evaluate(caller, null, "S3:GETOBJECT", "arn:aws:s3:::bucket/key", null));
        assertEquals(Decision.DENY,
                evaluator.evaluate(caller, null, "SQS:SendMessage", "arn:aws:sqs:us-east-1:000000000000:q", null));
    }

    @Test
    void resourceMatchingIsCaseSensitive() {
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":["
                + "{\"Effect\":\"Allow\",\"Action\":\"iam:GetUser\","
                + "\"Resource\":\"arn:aws:iam::000000000000:user/Bob\"},"
                + "{\"Effect\":\"Allow\",\"Action\":\"s3:GetObject\","
                + "\"Resource\":\"arn:aws:s3:::bucket/Private/*\"}]}";
        CallerContext caller = CallerContext.of(List.of(policy));

        assertEquals(Decision.ALLOW,
                evaluator.evaluate(caller, null, "iam:GetUser", "arn:aws:iam::000000000000:user/Bob", null));
        assertEquals(Decision.DENY,
                evaluator.evaluate(caller, null, "iam:GetUser", "arn:aws:iam::000000000000:user/bob", null));
        assertEquals(Decision.ALLOW,
                evaluator.evaluate(caller, null, "s3:GetObject", "arn:aws:s3:::bucket/Private/report.csv", null));
        assertEquals(Decision.DENY,
                evaluator.evaluate(caller, null, "s3:GetObject", "arn:aws:s3:::bucket/private/report.csv", null));
    }

    @Test
    void notResourceMatchingIsCaseSensitive() {
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":["
                + "{\"Effect\":\"Deny\",\"Action\":\"s3:*\","
                + "\"NotResource\":\"arn:aws:s3:::bucket/Public/*\"},"
                + "{\"Effect\":\"Allow\",\"Action\":\"s3:*\",\"Resource\":\"*\"}]}";
        CallerContext caller = CallerContext.of(List.of(policy));

        assertEquals(Decision.ALLOW,
                evaluator.evaluate(caller, null, "s3:GetObject", "arn:aws:s3:::bucket/Public/index.html", null));
        assertEquals(Decision.DENY,
                evaluator.evaluate(caller, null, "s3:GetObject", "arn:aws:s3:::bucket/public/index.html", null));
    }

    private static String sidDocument(int sid) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"S" + sid + "\",\"Effect\":\"Allow\","
                + "\"Action\":\"s3:*\",\"Resource\":\"*\"}]}";
    }

    @Test
    void evaluatesResourcePolicyPrincipalTypes() {
        // Wildcard '*'
        String wildcard = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":"*","Action":"s3:*","Resource":"*"}]}""";
        assertEquals(ResourcePolicyDecision.ALLOW, evaluator.evaluateResourcePolicy(
                List.of(wildcard), "arn:aws:iam::123456789012:user/alice", "s3:GetObject", "arn:aws:s3:::b/k", null));
        assertEquals(ResourcePolicyDecision.ALLOW, evaluator.evaluateResourcePolicy(
                List.of(wildcard), null, "s3:GetObject", "arn:aws:s3:::b/k", null));

        // AWS wildcard '{"AWS":"*"}'
        String awsWildcard = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":"*"},"Action":"s3:*","Resource":"*"}]}""";
        assertEquals(ResourcePolicyDecision.ALLOW, evaluator.evaluateResourcePolicy(
                List.of(awsWildcard), "arn:aws:iam::123456789012:user/alice", "s3:GetObject", "arn:aws:s3:::b/k", null));
        assertEquals(ResourcePolicyDecision.NEUTRAL, evaluator.evaluateResourcePolicy(
                List.of(awsWildcard), null, "s3:GetObject", "arn:aws:s3:::b/k", null));

        // Specific IAM user ARN
        String userPolicy = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":"arn:aws:iam::123456789012:user/alice"},"Action":"s3:*","Resource":"*"}]}""";
        assertEquals(ResourcePolicyDecision.ALLOW_DIRECT_IAM_USER, evaluator.evaluateResourcePolicy(
                List.of(userPolicy), "arn:aws:iam::123456789012:user/alice", "s3:GetObject", "arn:aws:s3:::b/k", null));
        assertEquals(ResourcePolicyDecision.NEUTRAL, evaluator.evaluateResourcePolicy(
                List.of(userPolicy), "arn:aws:iam::123456789012:user/bob", "s3:GetObject", "arn:aws:s3:::b/k", null));

        // IAM role matches assumed-role ARN
        String rolePolicy = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":"arn:aws:iam::123456789012:role/r"},"Action":"s3:*","Resource":"*"}]}""";
        assertEquals(ResourcePolicyDecision.ALLOW, evaluator.evaluateResourcePolicy(
                List.of(rolePolicy), "arn:aws:sts::123456789012:assumed-role/r/sess", "s3:GetObject", "arn:aws:s3:::b/k", null));
        assertEquals(ResourcePolicyDecision.NEUTRAL, evaluator.evaluateResourcePolicy(
                List.of(rolePolicy), "arn:aws:sts::123456789012:assumed-role/other/sess", "s3:GetObject", "arn:aws:s3:::b/k", null));

        // 12-digit account ID and root ARN match account principals
        String acctPolicy = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":"123456789012"},"Action":"s3:*","Resource":"*"}]}""";
        String rootPolicy = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":"arn:aws:iam::123456789012:root"},"Action":"s3:*","Resource":"*"}]}""";
        for (String p : List.of(acctPolicy, rootPolicy)) {
            assertEquals(ResourcePolicyDecision.ALLOW, evaluator.evaluateResourcePolicy(
                    List.of(p), "arn:aws:iam::123456789012:user/alice", "s3:GetObject", "arn:aws:s3:::b/k", null));
            assertEquals(ResourcePolicyDecision.NEUTRAL, evaluator.evaluateResourcePolicy(
                    List.of(p), "arn:aws:iam::999999999999:user/bob", "s3:GetObject", "arn:aws:s3:::b/k", null));
        }

        // Service principal and array of principals
        String servicePolicy = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},"Action":"s3:*","Resource":"*"}]}""";
        assertEquals(ResourcePolicyDecision.ALLOW, evaluator.evaluateResourcePolicy(
                List.of(servicePolicy), "lambda.amazonaws.com", "s3:GetObject", "arn:aws:s3:::b/k", null));
        assertEquals(ResourcePolicyDecision.NEUTRAL, evaluator.evaluateResourcePolicy(
                List.of(servicePolicy), "ec2.amazonaws.com", "s3:GetObject", "arn:aws:s3:::b/k", null));

        String arrayPolicy = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":["arn:aws:iam::123456789012:user/alice","arn:aws:iam::123456789012:user/bob"]},"Action":"s3:*","Resource":"*"}]}""";
        assertEquals(ResourcePolicyDecision.ALLOW_DIRECT_IAM_USER, evaluator.evaluateResourcePolicy(
                List.of(arrayPolicy), "arn:aws:iam::123456789012:user/alice", "s3:GetObject", "arn:aws:s3:::b/k", null));
        assertEquals(ResourcePolicyDecision.ALLOW_DIRECT_IAM_USER, evaluator.evaluateResourcePolicy(
                List.of(arrayPolicy), "arn:aws:iam::123456789012:user/bob", "s3:GetObject", "arn:aws:s3:::b/k", null));
        assertEquals(ResourcePolicyDecision.NEUTRAL, evaluator.evaluateResourcePolicy(
                List.of(arrayPolicy), "arn:aws:iam::123456789012:user/charlie", "s3:GetObject", "arn:aws:s3:::b/k", null));
    }

    @Test
    void evaluatesResourcePolicyDenyAndNotPrincipal() {
        // NotPrincipal inversion: anyone NOT alice is denied
        String notPrincipal = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Deny","NotPrincipal":{"AWS":"arn:aws:iam::123456789012:user/alice"},"Action":"s3:*","Resource":"*"}]}""";
        assertEquals(ResourcePolicyDecision.NEUTRAL, evaluator.evaluateResourcePolicy(
                List.of(notPrincipal), "arn:aws:iam::123456789012:user/alice", "s3:GetObject", "arn:aws:s3:::b/k", null));
        assertEquals(ResourcePolicyDecision.EXPLICIT_DENY, evaluator.evaluateResourcePolicy(
                List.of(notPrincipal), "arn:aws:iam::123456789012:user/bob", "s3:GetObject", "arn:aws:s3:::b/k", null));

        // Explicit Deny overrides Allow
        String denyWins = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":"*","Action":"s3:*","Resource":"*"},
              {"Effect":"Deny","Principal":{"AWS":"arn:aws:iam::123456789012:user/alice"},"Action":"s3:*","Resource":"*"}
            ]}""";
        assertEquals(ResourcePolicyDecision.EXPLICIT_DENY, evaluator.evaluateResourcePolicy(
                List.of(denyWins), "arn:aws:iam::123456789012:user/alice", "s3:GetObject", "arn:aws:s3:::b/k", null));
        assertEquals(ResourcePolicyDecision.ALLOW, evaluator.evaluateResourcePolicy(
                List.of(denyWins), "arn:aws:iam::123456789012:user/bob", "s3:GetObject", "arn:aws:s3:::b/k", null));

        // Resource policy statement without Principal matches nothing
        String noPrincipal = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"s3:*","Resource":"*"}]}""";
        assertEquals(ResourcePolicyDecision.NEUTRAL, evaluator.evaluateResourcePolicy(
                List.of(noPrincipal), "arn:aws:iam::123456789012:user/alice", "s3:GetObject", "arn:aws:s3:::b/k", null));
    }

    @Test
    void evaluatesResourcePolicyConditionsAndIdentityParity() {
        String condPolicy = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":"*","Action":"s3:*","Resource":"*",
             "Condition":{"StringEquals":{"aws:SecureTransport":"true"}}}]}""";
        assertEquals(ResourcePolicyDecision.ALLOW, evaluator.evaluateResourcePolicy(
                List.of(condPolicy), "arn:aws:iam::123456789012:user/alice", "s3:GetObject", "arn:aws:s3:::b/k",
                Map.of("aws:SecureTransport", List.of("true"))));
        assertEquals(ResourcePolicyDecision.NEUTRAL, evaluator.evaluateResourcePolicy(
                List.of(condPolicy), "arn:aws:iam::123456789012:user/alice", "s3:GetObject", "arn:aws:s3:::b/k",
                Map.of("aws:SecureTransport", List.of("false"))));

        // Identity-based policy without Principal matches regardless of caller principal
        String identityPolicy = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"s3:*","Resource":"*"}]}""";
        CallerContext caller = CallerContext.of(List.of(identityPolicy))
                .withPrincipalArn("arn:aws:iam::123456789012:user/alice");
        assertEquals(Decision.ALLOW, evaluator.evaluate(
                caller, null, "s3:GetObject", "arn:aws:s3:::b/k", null));
    }

    @Test
    void crossAccountEvaluationRequiresBothIdentityAndResourcePolicies() {
        String bucketPolicy = """
            {"Version":"2012-10-17","Statement":[{
              "Effect":"Allow",
              "Principal":{"AWS":"arn:aws:iam::111111111111:user/alice"},
              "Action":"s3:GetObject",
              "Resource":"arn:aws:s3:::account-b-bucket/*"
            }]}""";

        // 1. Cross-account caller without identity permission is denied despite bucket policy allow
        CallerContext callerWithoutIdentity = CallerContext.of(List.of())
                .withPrincipalArn("arn:aws:iam::111111111111:user/alice");
        ResourcePolicyDecision resourceDecision = evaluator.evaluateResourcePolicy(
                List.of(bucketPolicy), "arn:aws:iam::111111111111:user/alice", "s3:GetObject", "arn:aws:s3:::account-b-bucket/file.txt", null);
        assertEquals(ResourcePolicyDecision.ALLOW_DIRECT_IAM_USER, resourceDecision);
        assertEquals(Decision.DENY, evaluator.evaluateResolvedResourcePolicy(
                callerWithoutIdentity,
                resourceDecision,
                ResourceAccountRelationship.CROSS_ACCOUNT,
                "s3:GetObject",
                "arn:aws:s3:::account-b-bucket/file.txt",
                null));

        // 2. Cross-account caller with matching identity permission is allowed
        String identityPolicy = """
            {"Version":"2012-10-17","Statement":[{
              "Effect":"Allow",
              "Action":"s3:GetObject",
              "Resource":"arn:aws:s3:::account-b-bucket/*"
            }]}""";
        CallerContext callerWithIdentity = CallerContext.of(List.of(identityPolicy))
                .withPrincipalArn("arn:aws:iam::111111111111:user/alice");
        assertEquals(Decision.ALLOW, evaluator.evaluateResolvedResourcePolicy(
                callerWithIdentity,
                resourceDecision,
                ResourceAccountRelationship.CROSS_ACCOUNT,
                "s3:GetObject",
                "arn:aws:s3:::account-b-bucket/file.txt",
                null));
    }

    @Test
    void sameAccountDirectUserBypassesBoundaryWhileAccountGrantDoesNot() {
        String boundaryOmittingS3 = """
            {"Version":"2012-10-17","Statement":[{
              "Effect":"Allow",
              "Action":"dynamodb:*",
              "Resource":"*"
            }]}""";
        CallerContext caller = new CallerContext(
                List.of(),
                null,
                boundaryOmittingS3,
                null,
                "arn:aws:iam::111111111111:user/alice");

        // Direct user ARN in bucket policy bypasses boundary implicit deny
        String directUserPolicy = """
            {"Version":"2012-10-17","Statement":[{
              "Effect":"Allow",
              "Principal":{"AWS":"arn:aws:iam::111111111111:user/alice"},
              "Action":"s3:GetObject",
              "Resource":"arn:aws:s3:::bucket/*"
            }]}""";
        ResourcePolicyDecision directDecision = evaluator.evaluateResourcePolicy(
                List.of(directUserPolicy), "arn:aws:iam::111111111111:user/alice", "s3:GetObject", "arn:aws:s3:::bucket/k", null);
        assertEquals(ResourcePolicyDecision.ALLOW_DIRECT_IAM_USER, directDecision);
        assertEquals(Decision.ALLOW, evaluator.evaluateResolvedResourcePolicy(
                caller,
                directDecision,
                ResourceAccountRelationship.SAME_ACCOUNT,
                "s3:GetObject",
                "arn:aws:s3:::bucket/k",
                null));

        // Account delegation or root grant in bucket policy does not bypass boundary implicit deny
        String accountGrantPolicy = """
            {"Version":"2012-10-17","Statement":[{
              "Effect":"Allow",
              "Principal":{"AWS":"arn:aws:iam::111111111111:root"},
              "Action":"s3:GetObject",
              "Resource":"arn:aws:s3:::bucket/*"
            }]}""";
        ResourcePolicyDecision accountDecision = evaluator.evaluateResourcePolicy(
                List.of(accountGrantPolicy), "arn:aws:iam::111111111111:user/alice", "s3:GetObject", "arn:aws:s3:::bucket/k", null);
        assertEquals(ResourcePolicyDecision.ALLOW, accountDecision);
        assertEquals(Decision.DENY, evaluator.evaluateResolvedResourcePolicy(
                caller,
                accountDecision,
                ResourceAccountRelationship.SAME_ACCOUNT,
                "s3:GetObject",
                "arn:aws:s3:::bucket/k",
                null));
    }
}
