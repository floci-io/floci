package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.iam.AccessDeniedMessages.PolicyType;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.SimulationDecision;
import io.github.hectorvent.floci.services.iam.IamService.HeldPolicies;
import io.github.hectorvent.floci.services.iam.IamService.HeldPolicy;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AccessDeniedMessagesTest {

    private static final String ACTION = "lambda:PassNetworkConnector";
    private static final String CONNECTOR = "arn:aws:lambda:us-east-1:111111111111:network-connector:nc-1";
    private static final String MANAGED = "arn:aws:iam::111111111111:policy/deny";
    private static final String BOUNDARY = "arn:aws:iam::111111111111:policy/boundary";
    private static final String NO_IDENTITY_ALLOW =
            "because no identity-based policy allows the lambda:PassNetworkConnector action";

    private final IamPolicyEvaluator evaluator = new IamPolicyEvaluator(new ObjectMapper());

    @Test
    void eachPolicyTypeIsWordedAsAwsWordsIt() {
        assertEquals("with an explicit deny in a service control policy", PolicyType.SERVICE_CONTROL_POLICY.explicitDeny());
        assertEquals("with an explicit deny in the role trust policy", PolicyType.ROLE_TRUST_POLICY.explicitDeny());
        assertEquals("with an explicit deny in an identity-based policy: " + MANAGED,
                PolicyType.IDENTITY_BASED_POLICY.explicitDeny(MANAGED));
        assertEquals("because no resource-based policy allows the s3:GetObject action",
                PolicyType.RESOURCE_BASED_POLICY.noAllow("s3:GetObject"));
        assertEquals("because no session policy allows the s3:GetObject action",
                PolicyType.SESSION_POLICY.refusal(SimulationDecision.IMPLICIT_DENY, "s3:GetObject", MANAGED));
        assertEquals("User: arn:aws:iam::111111111111:root is not authorized to perform: sts:AssumeRole on resource: r",
                AccessDeniedMessages.notAuthorized("arn:aws:iam::111111111111:root", "sts:AssumeRole", "r", null));
    }

    @Test
    void theVersionArnAsGivenHasToBeAllowed() {
        assertNull(refusal(policy(allow(CONNECTOR + ":1")), CONNECTOR + ":1"));
        assertEquals(NO_IDENTITY_ALLOW, refusal(policy(allow(CONNECTOR + ":1")), CONNECTOR + ":2"));
        assertEquals(NO_IDENTITY_ALLOW, refusal(policy(allow(CONNECTOR)), CONNECTOR + ":1"));
        assertNull(refusal(policy(allow(CONNECTOR + ":*")), CONNECTOR + ":1"));
    }

    @Test
    void anExplicitDenyNamesTheManagedPolicyThatHoldsIt() {
        HeldPolicies held = new HeldPolicies(List.of(new HeldPolicy(null, policy(allow("*"))),
                new HeldPolicy(MANAGED, policy(deny("*")))), null);
        assertEquals("with an explicit deny in an identity-based policy: " + MANAGED,
                AccessDeniedMessages.firstRefusal(evaluator, caller(held, null, null), held, ACTION, CONNECTOR, Map.of()));

        HeldPolicies inlineFirst = new HeldPolicies(List.of(new HeldPolicy(null, policy(allow("*"), deny("*"))),
                new HeldPolicy(MANAGED, policy(deny("*")))), null);
        assertEquals("with an explicit deny in an identity-based policy", AccessDeniedMessages.firstRefusal(
                evaluator, caller(inlineFirst, null, null), inlineFirst, ACTION, CONNECTOR, Map.of()));
        assertEquals("with an explicit deny in an identity-based policy", AccessDeniedMessages.firstRefusal(
                evaluator, caller(held, null, null), null, ACTION, CONNECTOR, Map.of()));
    }

    @Test
    void theIdentityPoliciesThenTheBoundaryThenTheSessionPolicyNameTheReason() {
        String allowAll = policy(allow("*"));
        String denyAll = policy(allow("*"), deny("*"));
        String allowNothing = policy(allowCreate());

        assertEquals(NO_IDENTITY_ALLOW, refusal(allowNothing, denyAll, denyAll));
        assertEquals("because no permissions boundary allows the lambda:PassNetworkConnector action",
                refusal(allowAll, allowNothing, denyAll));
        assertEquals("with an explicit deny in a permissions boundary: " + BOUNDARY, refusal(allowAll, denyAll, allowNothing));
        assertEquals("because no session policy allows the lambda:PassNetworkConnector action",
                refusal(allowAll, allowAll, allowNothing));
        assertEquals("with an explicit deny in a session policy", refusal(allowAll, null, denyAll));
        assertNull(refusal(allowAll, allowAll, allowAll));
    }

    @Test
    void serviceControlPoliciesComeFirstAndAnEmptyLevelIsSkipped() {
        CallerContext caller = CallerContext.of(List.of(policy(allowCreate())));
        assertEquals("with an explicit deny in a service control policy", AccessDeniedMessages.firstRefusal(evaluator,
                caller.withScpLevels(List.of(List.of(), List.of(policy(allow("*"), deny("*"))))), null, ACTION,
                CONNECTOR, Map.of()));
        assertEquals("because no service control policy allows the lambda:PassNetworkConnector action",
                AccessDeniedMessages.firstRefusal(evaluator, caller.withScpLevels(List.of(List.of(policy(allowCreate())))),
                        null, ACTION, CONNECTOR, Map.of()));
    }

    private String refusal(String identity, String resource) {
        return AccessDeniedMessages.firstRefusal(evaluator, CallerContext.of(List.of(identity)), null, ACTION, resource,
                Map.of());
    }

    private String refusal(String identity, String boundary, String session) {
        HeldPolicies held = new HeldPolicies(List.of(new HeldPolicy(null, identity)), boundary == null ? null : BOUNDARY);
        return AccessDeniedMessages.firstRefusal(evaluator, caller(held, boundary, session), held, ACTION, CONNECTOR,
                Map.of());
    }

    private static CallerContext caller(HeldPolicies held, String boundary, String session) {
        return new CallerContext(held.identity().stream().map(HeldPolicy::document).toList(), session, boundary);
    }

    private static String policy(String... statements) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[" + String.join(",", statements) + "]}";
    }

    private static String allow(String resource) {
        return "{\"Effect\":\"Allow\",\"Action\":\"" + ACTION + "\",\"Resource\":\"" + resource + "\"}";
    }

    private static String deny(String resource) {
        return "{\"Effect\":\"Deny\",\"Action\":\"" + ACTION + "\",\"Resource\":\"" + resource + "\"}";
    }

    private static String allowCreate() {
        return "{\"Effect\":\"Allow\",\"Action\":\"lambda:CreateWebFunctionRevision\",\"Resource\":\"*\"}";
    }
}
