package io.floci.aws.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.iam.IamPolicyEvaluator.DecidingStatement;
import io.floci.aws.services.iam.IamPolicyEvaluator.Decision;
import io.floci.aws.services.iam.IamPolicyEvaluator.DecisionRecord;
import io.floci.aws.services.iam.IamPolicyEvaluator.Outcome;
import io.floci.aws.services.iam.IamPolicyEvaluator.PolicyLayer;
import io.floci.aws.services.iam.IamPolicyEvaluator.ResourceAccountRelationship;
import io.floci.aws.services.iam.IamPolicyEvaluator.ResourcePolicyDecision;
import io.floci.aws.services.iam.model.CallerContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The decision record of the policy evaluator. Expected values follow the AWS IAM policy
 * evaluation logic (an explicit deny in any applicable policy wins; service control policies,
 * permissions boundaries and session policies limit; identity or resource policies grant;
 * otherwise the request is implicitly denied) and are computed by hand, not from the evaluator.
 */
class IamPolicyEvaluatorDecisionRecordTest {

    private static final String ACTION = "s3:GetObject";
    private static final String RESOURCE = "arn:aws:s3:::bucket/key";

    private static final String ALLOW_ALL =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                    + "\"Action\":\"*\",\"Resource\":\"*\"}]}";
    private static final String ALLOW_DYNAMODB_ONLY =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                    + "\"Action\":\"dynamodb:*\",\"Resource\":\"*\"}]}";
    private static final String ALLOW_ALL_DENY_S3 =
            "{\"Version\":\"2012-10-17\",\"Statement\":["
                    + "{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"},"
                    + "{\"Effect\":\"Deny\",\"Action\":\"s3:*\",\"Resource\":\"*\"}]}";
    private static final String MALFORMED = "{\"Version\":\"2012-10-17\",\"Statement\":[";

    private final IamPolicyEvaluator evaluator = new IamPolicyEvaluator(new ObjectMapper());

    private record Row(String name, CallerContext caller, ResourcePolicyDecision resourceDecision,
                       ResourceAccountRelationship relationship, Outcome outcome, PolicyLayer layer) {
    }

    private static Row row(String name, CallerContext caller, ResourcePolicyDecision resourceDecision,
                           ResourceAccountRelationship relationship, Outcome outcome, PolicyLayer layer) {
        return new Row(name, caller, resourceDecision, relationship, outcome, layer);
    }

    private static CallerContext callerWithScps(List<String> identity, List<List<String>> scpLevels) {
        return new CallerContext(identity, null, null, scpLevels);
    }

    private static CallerContext callerWithBoundary(List<String> identity, String boundary) {
        return new CallerContext(identity, null, boundary);
    }

    private static CallerContext callerWithSession(List<String> identity, String session) {
        return new CallerContext(identity, session, null);
    }

    private static List<Row> table() {
        ResourcePolicyDecision neutral = ResourcePolicyDecision.NEUTRAL;
        ResourceAccountRelationship same = ResourceAccountRelationship.SAME_ACCOUNT;
        ResourceAccountRelationship cross = ResourceAccountRelationship.CROSS_ACCOUNT;
        CallerContext admin = CallerContext.of(List.of(ALLOW_ALL));
        return List.of(
                row("scp explicit deny beats identity allow",
                        callerWithScps(List.of(ALLOW_ALL), List.of(List.of(ALLOW_ALL_DENY_S3))),
                        neutral, same, Outcome.EXPLICIT_DENY, PolicyLayer.SERVICE_CONTROL),
                row("scp level without a matching allow",
                        callerWithScps(List.of(ALLOW_ALL), List.of(List.of(ALLOW_DYNAMODB_ONLY))),
                        neutral, same, Outcome.IMPLICIT_DENY, PolicyLayer.SERVICE_CONTROL),
                row("scp level holding a malformed document fails closed",
                        callerWithScps(List.of(ALLOW_ALL), List.of(List.of(ALLOW_ALL, MALFORMED))),
                        neutral, same, Outcome.IMPLICIT_DENY, PolicyLayer.SERVICE_CONTROL),
                row("scp allows at every level",
                        callerWithScps(List.of(ALLOW_ALL), List.of(List.of(ALLOW_ALL), List.of(ALLOW_ALL))),
                        neutral, same, Outcome.ALLOW, PolicyLayer.IDENTITY),
                row("identity explicit deny beats resource allow",
                        CallerContext.of(List.of(ALLOW_ALL_DENY_S3)),
                        ResourcePolicyDecision.ALLOW, same, Outcome.EXPLICIT_DENY, PolicyLayer.IDENTITY),
                row("resource explicit deny beats identity allow",
                        admin, ResourcePolicyDecision.EXPLICIT_DENY, same,
                        Outcome.EXPLICIT_DENY, PolicyLayer.RESOURCE),
                row("session policy explicit deny",
                        callerWithSession(List.of(ALLOW_ALL), ALLOW_ALL_DENY_S3),
                        neutral, same, Outcome.EXPLICIT_DENY, PolicyLayer.SESSION),
                row("session policy without a matching allow",
                        callerWithSession(List.of(ALLOW_ALL), ALLOW_DYNAMODB_ONLY),
                        neutral, same, Outcome.IMPLICIT_DENY, PolicyLayer.SESSION),
                row("boundary explicit deny",
                        callerWithBoundary(List.of(ALLOW_ALL), ALLOW_ALL_DENY_S3),
                        neutral, same, Outcome.EXPLICIT_DENY, PolicyLayer.PERMISSIONS_BOUNDARY),
                row("boundary without a matching allow",
                        callerWithBoundary(List.of(ALLOW_ALL), ALLOW_DYNAMODB_ONLY),
                        neutral, same, Outcome.IMPLICIT_DENY, PolicyLayer.PERMISSIONS_BOUNDARY),
                row("boundary also caps a resource policy wildcard grant in the same account",
                        callerWithBoundary(List.of(), ALLOW_DYNAMODB_ONLY),
                        ResourcePolicyDecision.ALLOW, same, Outcome.IMPLICIT_DENY, PolicyLayer.PERMISSIONS_BOUNDARY),
                row("boundary without a matching allow is checked before a session policy that also does not allow",
                        new CallerContext(List.of(ALLOW_ALL), ALLOW_DYNAMODB_ONLY, ALLOW_DYNAMODB_ONLY),
                        neutral, same, Outcome.IMPLICIT_DENY, PolicyLayer.PERMISSIONS_BOUNDARY),
                row("session policy without a matching allow decides once the boundary allows",
                        new CallerContext(List.of(ALLOW_ALL), ALLOW_DYNAMODB_ONLY, ALLOW_ALL),
                        neutral, same, Outcome.IMPLICIT_DENY, PolicyLayer.SESSION),
                row("cross-account needs both: identity allow, resource neutral",
                        admin, neutral, cross, Outcome.IMPLICIT_DENY, PolicyLayer.RESOURCE),
                row("cross-account needs both: resource allow, no identity allow",
                        CallerContext.of(List.of()), ResourcePolicyDecision.ALLOW, cross,
                        Outcome.IMPLICIT_DENY, PolicyLayer.IDENTITY),
                row("cross-account with both allows",
                        admin, ResourcePolicyDecision.ALLOW, cross, Outcome.ALLOW, PolicyLayer.IDENTITY),
                row("same account, no identity allow and no resource grant",
                        CallerContext.of(List.of()), neutral, same, Outcome.IMPLICIT_DENY, PolicyLayer.IDENTITY),
                row("same account, resource policy alone grants",
                        CallerContext.of(List.of()), ResourcePolicyDecision.ALLOW, same,
                        Outcome.ALLOW, PolicyLayer.RESOURCE),
                row("same account, account-only resource grant without identity allow",
                        CallerContext.of(List.of()), ResourcePolicyDecision.ALLOW_ACCOUNT, same,
                        Outcome.IMPLICIT_DENY, PolicyLayer.IDENTITY),
                row("direct IAM user resource grant bypasses the boundary's implicit deny",
                        callerWithBoundary(List.of(), ALLOW_DYNAMODB_ONLY),
                        ResourcePolicyDecision.ALLOW_DIRECT_IAM_USER, same,
                        Outcome.ALLOW, PolicyLayer.RESOURCE),
                row("direct IAM user grant stays resource-attributed when a boundary does not allow",
                        callerWithBoundary(List.of(ALLOW_ALL), ALLOW_DYNAMODB_ONLY),
                        ResourcePolicyDecision.ALLOW_DIRECT_IAM_USER, same,
                        Outcome.ALLOW, PolicyLayer.RESOURCE),
                row("direct IAM user grant with a boundary that allows is an identity allow",
                        callerWithBoundary(List.of(ALLOW_ALL), ALLOW_ALL),
                        ResourcePolicyDecision.ALLOW_DIRECT_IAM_USER, same,
                        Outcome.ALLOW, PolicyLayer.IDENTITY),
                row("direct IAM user resource grant does not bypass a boundary explicit deny",
                        callerWithBoundary(List.of(), ALLOW_ALL_DENY_S3),
                        ResourcePolicyDecision.ALLOW_DIRECT_IAM_USER, same,
                        Outcome.EXPLICIT_DENY, PolicyLayer.PERMISSIONS_BOUNDARY),
                row("malformed identity document grants nothing",
                        CallerContext.of(List.of(MALFORMED)), neutral, same,
                        Outcome.IMPLICIT_DENY, PolicyLayer.IDENTITY),
                row("malformed identity document beside a valid allow is skipped",
                        CallerContext.of(List.of(MALFORMED, ALLOW_ALL)), neutral, same,
                        Outcome.ALLOW, PolicyLayer.IDENTITY),
                row("plain identity allow",
                        admin, neutral, same, Outcome.ALLOW, PolicyLayer.IDENTITY));
    }

    @Test
    void explainDecisionEqualsEvaluateResolvedResourcePolicyForEveryStage() {
        for (Row r : table()) {
            DecisionRecord record = evaluator.explain(r.caller(), r.resourceDecision(), r.relationship(),
                    ACTION, RESOURCE, null);
            Decision expected = r.outcome() == Outcome.ALLOW ? Decision.ALLOW : Decision.DENY;

            assertEquals(expected, evaluator.evaluateResolvedResourcePolicy(r.caller(), r.resourceDecision(),
                    r.relationship(), ACTION, RESOURCE, null), r.name() + ": evaluateResolvedResourcePolicy");
            assertEquals(expected, record.decision(), r.name() + ": explain().decision()");
            assertEquals(r.outcome(), record.outcome(), r.name() + ": outcome");
            assertEquals(r.layer(), record.layer(), r.name() + ": layer");
        }
    }

    @Test
    void identityExplicitDenyNamesLayerDocumentStatementAndSid() {
        String readOnly = """
                {"Version":"2012-10-17","Statement":[
                  {"Sid":"Ec2","Effect":"Allow","Action":"ec2:*","Resource":"*"},
                  {"Sid":"DenyRead","Effect":"Deny","Action":"s3:GetObject","Resource":"*"},
                  {"Sid":"DenyAllS3","Effect":"Deny","Action":"s3:*","Resource":"*"}
                ]}""";
        // The session policy denies too, but the order is SCP, identity, resource, session, boundary.
        CallerContext caller = new CallerContext(List.of(ALLOW_ALL, readOnly), ALLOW_ALL_DENY_S3, null);

        DecisionRecord record = evaluator.explain(caller, ResourcePolicyDecision.NEUTRAL,
                ResourceAccountRelationship.SAME_ACCOUNT, ACTION, RESOURCE, null);

        assertEquals(Outcome.EXPLICIT_DENY, record.outcome());
        assertEquals(PolicyLayer.IDENTITY, record.layer());
        assertEquals(new DecidingStatement(PolicyLayer.IDENTITY, -1, 1, 1, "DenyRead"), record.statement());
        assertEquals(Decision.DENY, record.decision());
    }

    @Test
    void scpExplicitDenyWinsOverMissingIdentityAllowAndNamesTheLevel() {
        String levelOne = """
                {"Version":"2012-10-17","Statement":[
                  {"Sid":"AllowAll","Effect":"Allow","Action":"*","Resource":"*"},
                  {"Sid":"NoS3","Effect":"Deny","Action":"s3:*","Resource":"*"}
                ]}""";
        CallerContext caller = callerWithScps(List.of(), List.of(List.of(ALLOW_ALL), List.of(levelOne)));

        DecisionRecord record = evaluator.explain(caller, ResourcePolicyDecision.NEUTRAL,
                ResourceAccountRelationship.SAME_ACCOUNT, ACTION, RESOURCE, null);

        assertEquals(Outcome.EXPLICIT_DENY, record.outcome());
        assertEquals(PolicyLayer.SERVICE_CONTROL, record.layer());
        assertEquals(new DecidingStatement(PolicyLayer.SERVICE_CONTROL, 1, 0, 1, "NoS3"), record.statement());
    }

    @Test
    void scpWithoutAnAllowIsAnImplicitDenyWithNoStatement() {
        CallerContext caller = callerWithScps(List.of(ALLOW_ALL),
                List.of(List.of(ALLOW_ALL), List.of(ALLOW_DYNAMODB_ONLY)));

        DecisionRecord record = evaluator.explain(caller, ResourcePolicyDecision.NEUTRAL,
                ResourceAccountRelationship.SAME_ACCOUNT, ACTION, RESOURCE, null);

        assertEquals(Outcome.IMPLICIT_DENY, record.outcome());
        assertEquals(PolicyLayer.SERVICE_CONTROL, record.layer());
        assertNull(record.statement());
        assertEquals(Decision.DENY, record.decision());
    }

    @Test
    void noIdentityAllowInTheSameAccountIsAnImplicitIdentityDeny() {
        DecisionRecord record = evaluator.explain(CallerContext.of(List.of(ALLOW_DYNAMODB_ONLY)),
                ResourcePolicyDecision.NEUTRAL, ResourceAccountRelationship.SAME_ACCOUNT,
                ACTION, RESOURCE, null);

        assertEquals(Outcome.IMPLICIT_DENY, record.outcome());
        assertEquals(PolicyLayer.IDENTITY, record.layer());
        assertNull(record.statement());
    }

    @Test
    void aBoundaryThatDoesNotAllowIsAnImplicitBoundaryDeny() {
        CallerContext caller = callerWithBoundary(List.of(ALLOW_ALL), ALLOW_DYNAMODB_ONLY);

        DecisionRecord record = evaluator.explain(caller, ResourcePolicyDecision.NEUTRAL,
                ResourceAccountRelationship.SAME_ACCOUNT, ACTION, RESOURCE, null);

        assertEquals(Outcome.IMPLICIT_DENY, record.outcome());
        assertEquals(PolicyLayer.PERMISSIONS_BOUNDARY, record.layer());
        assertNull(record.statement());
    }

    @Test
    void anAllowNamesTheFirstMatchingIdentityAllowStatement() {
        String firstDocument = """
                {"Version":"2012-10-17","Statement":[
                  {"Sid":"Ec2","Effect":"Allow","Action":"ec2:*","Resource":"*"}
                ]}""";
        String secondDocument = """
                {"Version":"2012-10-17","Statement":[
                  {"Sid":"Ddb","Effect":"Allow","Action":"dynamodb:*","Resource":"*"},
                  {"Sid":"ReadS3","Effect":"Allow","Action":"s3:GetObject","Resource":"*"},
                  {"Sid":"AllS3","Effect":"Allow","Action":"s3:*","Resource":"*"}
                ]}""";
        CallerContext caller = CallerContext.of(List.of(firstDocument, secondDocument));

        DecisionRecord record = evaluator.explain(caller, ResourcePolicyDecision.NEUTRAL,
                ResourceAccountRelationship.SAME_ACCOUNT, ACTION, RESOURCE, Map.of());

        assertEquals(Outcome.ALLOW, record.outcome());
        assertEquals(PolicyLayer.IDENTITY, record.layer());
        assertEquals(new DecidingStatement(PolicyLayer.IDENTITY, -1, 1, 1, "ReadS3"), record.statement());
        assertEquals(Decision.ALLOW, record.decision());
    }

    @Test
    void aDirectIamUserGrantIsAttributedToTheResourceOnlyWhenTheBoundaryDoesNotAllow() {
        String identity = """
                {"Version":"2012-10-17","Statement":[
                  {"Sid":"ReadS3","Effect":"Allow","Action":"s3:GetObject","Resource":"*"}
                ]}""";

        // The boundary does not allow the action: only the resource policy lets the request through.
        DecisionRecord viaResource = evaluator.explain(callerWithBoundary(List.of(identity), ALLOW_DYNAMODB_ONLY),
                ResourcePolicyDecision.ALLOW_DIRECT_IAM_USER, ResourceAccountRelationship.SAME_ACCOUNT,
                ACTION, RESOURCE, null);

        assertEquals(Outcome.ALLOW, viaResource.outcome());
        assertEquals(PolicyLayer.RESOURCE, viaResource.layer());
        assertEquals(new DecidingStatement(PolicyLayer.RESOURCE, -1, -1, -1, null), viaResource.statement());

        // The boundary allows the action: the identity policy grants it.
        DecisionRecord viaIdentity = evaluator.explain(callerWithBoundary(List.of(identity), ALLOW_ALL),
                ResourcePolicyDecision.ALLOW_DIRECT_IAM_USER, ResourceAccountRelationship.SAME_ACCOUNT,
                ACTION, RESOURCE, null);

        assertEquals(Outcome.ALLOW, viaIdentity.outcome());
        assertEquals(PolicyLayer.IDENTITY, viaIdentity.layer());
        assertEquals(new DecidingStatement(PolicyLayer.IDENTITY, -1, 0, 0, "ReadS3"), viaIdentity.statement());
    }
}
