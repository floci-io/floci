package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import org.junit.jupiter.api.Test;

import java.util.List;

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
}
