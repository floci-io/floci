package io.floci.aws.services.iam.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Full IAM context for the calling identity, used by the enforcement filter.
 *
 * <p>Carries all inputs required for the complete AWS policy evaluation algorithm:
 * <ul>
 *   <li>{@code identityPolicies}: inline + attached policies of the user, role, and groups</li>
 *   <li>{@code sessionPolicyDocument}: optional inline session policy from AssumeRole (Phase 3)</li>
 *   <li>{@code boundaryPolicyDocument}: optional permissions boundary document (Phase 3)</li>
 *   <li>{@code scpLevels}: optional effective service control policies for the caller's
 *       account, one list of policy documents per organization level (root, each OU on the
 *       path, account). An action must be allowed at every level and denied at none.
 *       {@code null} when SCPs don't apply (no organization, management account, or SCP
 *       enforcement disabled).</li>
 *   <li>{@code principalArn}: optional caller principal ARN used for resource policy evaluation.</li>
 *   <li>{@code identityPolicySources}: where each identity policy came from, position for position
 *       with {@code identityPolicies}, for the statement a simulation reports as deciding.
 *       {@code null} where nothing needs naming, which is every path but a simulation.</li>
 * </ul>
 */
public record CallerContext(
        List<String> identityPolicies,
        String sessionPolicyDocument,
        String boundaryPolicyDocument,
        List<List<String>> scpLevels,
        String principalArn,
        List<PolicySource> identityPolicySources
) {
    /** Source-compatible constructor for callers predating named policy sources. */
    public CallerContext(List<String> identityPolicies, String sessionPolicyDocument,
                         String boundaryPolicyDocument, List<List<String>> scpLevels,
                         String principalArn) {
        this(identityPolicies, sessionPolicyDocument, boundaryPolicyDocument, scpLevels, principalArn, null);
    }

    /** Source-compatible constructor for callers predating principalArn support. */
    public CallerContext(List<String> identityPolicies, String sessionPolicyDocument,
                         String boundaryPolicyDocument, List<List<String>> scpLevels) {
        this(identityPolicies, sessionPolicyDocument, boundaryPolicyDocument, scpLevels, null, null);
    }

    /** Source-compatible constructor for callers predating SCP support. */
    public CallerContext(List<String> identityPolicies, String sessionPolicyDocument,
                         String boundaryPolicyDocument) {
        this(identityPolicies, sessionPolicyDocument, boundaryPolicyDocument, null, null, null);
    }

    /** Convenience factory: no session policy, no boundary, no SCPs. */
    public static CallerContext of(List<String> identityPolicies) {
        return new CallerContext(identityPolicies, null, null, null, null, null);
    }

    /** The context with each identity policy named by where it came from. */
    public static CallerContext named(List<String> identityPolicies, List<PolicySource> sources,
                                      String boundaryPolicyDocument) {
        return new CallerContext(identityPolicies, null, boundaryPolicyDocument, null, null,
                sources == null ? null : List.copyOf(sources));
    }

    /**
     * Where the identity policy at {@code index} came from, or a source naming nothing when this
     * context names none of them, which is what every path but a simulation builds.
     */
    public PolicySource identityPolicySource(int index) {
        if (identityPolicySources == null || index < 0 || index >= identityPolicySources.size()) {
            return PolicySource.unnamed();
        }
        return identityPolicySources.get(index);
    }

    /** Copy of this context with the effective SCP levels attached. */
    public CallerContext withScpLevels(List<List<String>> levels) {
        return new CallerContext(identityPolicies, sessionPolicyDocument, boundaryPolicyDocument, levels,
                principalArn, identityPolicySources);
    }

    /** Copy of this context with one more identity policy, evaluated like the caller's own. */
    public CallerContext withIdentityPolicy(String policyDocument) {
        return withIdentityPolicies(List.of(policyDocument));
    }

    /**
     * Copy of this context with more identity policies, each evaluated like the caller's own. A
     * simulation handed documents of its own adds them this way, so what the request brought and
     * what the principal holds carry the same weight.
     */
    public CallerContext withIdentityPolicies(List<String> policyDocuments) {
        return withIdentityPolicies(policyDocuments, null);
    }

    /**
     * The same, with each added document named by where it came from. The two lists stay position
     * for position: a context that named its own policies pads the added ones with a source naming
     * nothing when none is given, and one that named none starts naming them here.
     */
    public CallerContext withIdentityPolicies(List<String> policyDocuments, List<PolicySource> sources) {
        if (policyDocuments.isEmpty()) {
            return this;
        }
        List<String> policies = new ArrayList<>(identityPolicies);
        policies.addAll(policyDocuments);
        List<PolicySource> named = null;
        if (identityPolicySources != null || sources != null) {
            named = padded(identityPolicySources, identityPolicies.size());
            named.addAll(padded(sources, policyDocuments.size()));
        }
        return new CallerContext(policies, sessionPolicyDocument, boundaryPolicyDocument, scpLevels,
                principalArn, named);
    }

    /** Copy of this context with the permissions boundary document a caller handed in instead. */
    public CallerContext withBoundaryPolicy(String policyDocument) {
        return new CallerContext(identityPolicies, sessionPolicyDocument, policyDocument, scpLevels,
                principalArn, identityPolicySources);
    }

    /** Copy of this context with the caller principal ARN attached. */
    public CallerContext withPrincipalArn(String principalArn) {
        return new CallerContext(identityPolicies, sessionPolicyDocument, boundaryPolicyDocument, scpLevels,
                principalArn, identityPolicySources);
    }

    /** {@code sources} as a list of exactly {@code size}, naming nothing where it says nothing. */
    private static List<PolicySource> padded(List<PolicySource> sources, int size) {
        List<PolicySource> named = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            named.add(sources != null && index < sources.size() ? sources.get(index) : PolicySource.unnamed());
        }
        return named;
    }
}
