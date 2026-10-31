package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.SimulationDecision;
import io.github.hectorvent.floci.services.iam.IamService.HeldPolicies;
import io.github.hectorvent.floci.services.iam.IamService.HeldPolicy;
import io.github.hectorvent.floci.services.iam.model.CallerContext;

import java.util.List;
import java.util.Map;

/**
 * The access denied message AWS returns when a policy refuses a call, and the end of it that names the
 * policy type that refused it (IAM User Guide, "Access denied error message examples").
 */
public final class AccessDeniedMessages {

    /** A policy type the end of the message names, as AWS words it. */
    public enum PolicyType {
        SERVICE_CONTROL_POLICY("a", "service control policy"),
        RESOURCE_BASED_POLICY("a", "resource-based policy"),
        ROLE_TRUST_POLICY("the", "role trust policy"),
        IDENTITY_BASED_POLICY("an", "identity-based policy"),
        PERMISSIONS_BOUNDARY("a", "permissions boundary"),
        SESSION_POLICY("a", "session policy");

        private final String article;
        private final String name;

        PolicyType(String article, String name) {
            this.article = article;
            this.name = name;
        }

        public String explicitDeny() {
            return explicitDeny(null);
        }

        /** Names the policy by its ARN too when one is given, as AWS names a managed policy or a boundary. */
        public String explicitDeny(String policyArn) {
            return "with an explicit deny in " + article + " " + name + (policyArn == null ? "" : ": " + policyArn);
        }

        public String noAllow(String action) {
            return "because no " + name + " allows the " + action + " action";
        }

        public String refusal(SimulationDecision decision, String action, String policyArn) {
            return decision == SimulationDecision.EXPLICIT_DENY ? explicitDeny(policyArn) : noAllow(action);
        }
    }

    private AccessDeniedMessages() {
    }

    /** The message, ending with {@code reason} when there is one. */
    public static String notAuthorized(String callerArn, String action, String resource, String reason) {
        return "User: " + callerArn + " is not authorized to perform: " + action + " on resource: " + resource
                + (reason == null ? "" : " " + reason);
    }

    /**
     * Why {@code caller} may not perform {@code action} on {@code resource}, or null when it may, for a
     * resource with no policy of its own. Each policy type is checked in turn, service control policies,
     * identity policies, permissions boundary, then session policy, and the first that refuses names the
     * reason, by its explicit deny or by the allow it lacks. So identity policies with no allow win over
     * an explicit deny in the boundary (measured for {@code lambda:PassNetworkConnector}). AWS names one
     * of several denying identity policies at random, and this names the first in {@code held}, which is
     * null when the caller holds no policies by ARN.
     */
    public static String firstRefusal(IamPolicyEvaluator evaluator, CallerContext caller,
                                      HeldPolicies held, String action, String resource,
                                      Map<String, List<String>> context) {
        for (List<String> level : caller.scpLevels() == null ? List.<List<String>>of() : caller.scpLevels()) {
            SimulationDecision decision = level.isEmpty()
                    ? SimulationDecision.ALLOWED : simulate(evaluator, level, action, resource, context);
            if (decision != SimulationDecision.ALLOWED) {
                return PolicyType.SERVICE_CONTROL_POLICY.refusal(decision, action, null);
            }
        }
        SimulationDecision identity = simulate(evaluator, caller.identityPolicies(), action, resource, context);
        if (identity != SimulationDecision.ALLOWED) {
            String denying = identity == SimulationDecision.EXPLICIT_DENY && held != null
                    ? held.identity().stream()
                            .filter(policy -> simulate(evaluator, List.of(policy.document()), action, resource,
                                    context) == SimulationDecision.EXPLICIT_DENY)
                            .findFirst()
                            .map(HeldPolicy::arn)
                            .orElse(null)
                    : null;
            return PolicyType.IDENTITY_BASED_POLICY.refusal(identity, action, denying);
        }
        if (caller.boundaryPolicyDocument() != null) {
            SimulationDecision boundary = simulate(evaluator, List.of(caller.boundaryPolicyDocument()), action,
                    resource, context);
            if (boundary != SimulationDecision.ALLOWED) {
                return PolicyType.PERMISSIONS_BOUNDARY.refusal(boundary, action,
                        held == null ? null : held.permissionsBoundaryArn());
            }
        }
        if (caller.sessionPolicyDocument() != null) {
            SimulationDecision session = simulate(evaluator, List.of(caller.sessionPolicyDocument()), action,
                    resource, context);
            if (session != SimulationDecision.ALLOWED) {
                return PolicyType.SESSION_POLICY.refusal(session, action, null);
            }
        }
        return null;
    }

    private static SimulationDecision simulate(IamPolicyEvaluator evaluator, List<String> documents, String action,
                                               String resource, Map<String, List<String>> context) {
        return evaluator.simulatePrincipalPolicy(CallerContext.of(documents), action, resource, context);
    }
}
