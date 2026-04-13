package io.github.hectorvent.floci.services.iam.model;

import java.util.List;

/**
 * Full IAM context for the calling identity, used by the enforcement filter.
 *
 * <p>Carries all inputs required for the complete AWS policy evaluation algorithm:
 * <ul>
 *   <li>{@code identityPolicies} — inline + attached policies of the user, role, and groups</li>
 *   <li>{@code sessionPolicyDocument} — optional inline session policy from AssumeRole (Phase 3)</li>
 *   <li>{@code boundaryPolicyDocument} — optional permissions boundary document (Phase 3)</li>
 * </ul>
 */
public record CallerContext(
        List<String> identityPolicies,
        String sessionPolicyDocument,
        String boundaryPolicyDocument
) {
    /** Convenience factory: no session policy, no boundary. */
    public static CallerContext of(List<String> identityPolicies) {
        return new CallerContext(identityPolicies, null, null);
    }
}
