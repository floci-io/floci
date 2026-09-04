package io.github.hectorvent.flociappsync.iam.model;

import java.util.List;

/**
 * Full IAM context for the calling identity, resolved by Floci (which owns IAM state)
 * and handed to this service per-request as part of the execute-call payload — the
 * same "gather live state, bundle it into the call that needs it" pattern Athena uses
 * for Glue table metadata against floci-duck. Ported verbatim from Floci's
 * {@code io.github.hectorvent.floci.services.iam.model.CallerContext}.
 *
 * <ul>
 *   <li>{@code identityPolicies} — inline + attached policies of the user, role, and groups</li>
 *   <li>{@code sessionPolicyDocument} — optional inline session policy from AssumeRole</li>
 *   <li>{@code boundaryPolicyDocument} — optional permissions boundary document</li>
 *   <li>{@code scpLevels} — optional effective service control policies for the caller's
 *       account, one list of policy documents per organization level. {@code null} when
 *       SCPs don't apply.</li>
 * </ul>
 */
public record CallerContext(
        List<String> identityPolicies,
        String sessionPolicyDocument,
        String boundaryPolicyDocument,
        List<List<String>> scpLevels
) {
    public CallerContext(List<String> identityPolicies, String sessionPolicyDocument,
                         String boundaryPolicyDocument) {
        this(identityPolicies, sessionPolicyDocument, boundaryPolicyDocument, null);
    }

    public static CallerContext of(List<String> identityPolicies) {
        return new CallerContext(identityPolicies, null, null, null);
    }
}
