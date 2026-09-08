package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}

