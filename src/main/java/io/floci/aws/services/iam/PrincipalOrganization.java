package io.floci.aws.services.iam;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Supplies the organization an account belongs to, for the three context keys a policy evaluated
 * for that account's principals can match on. The simulator populates them itself rather than
 * asking a caller for them: the guide lists {@code aws:PrincipalOrgID},
 * {@code aws:PrincipalOrgMasterAccountId} and {@code aws:PrincipalOrgPaths} as the organization
 * context it fills in, and says that "because these keys are populated for you, SCPs and other
 * policies that use principal or organization conditions are evaluated correctly".
 *
 * <p>Implemented by the Organizations service; consumed lazily through
 * {@code Instance<PrincipalOrganization>} so IAM never depends on Organizations directly, the way
 * {@link ScpProvider} and {@link RootAccessOrganization} already do it (Organizations depends on
 * IAM for role provisioning).
 */
public interface PrincipalOrganization {

    /**
     * The organization context for principals in an account, or empty when the account belongs to
     * no organization: both published keys carry the same availability, "included in the request
     * context only if the principal is a member of an organization".
     *
     * @param accountId the account a principal belongs to
     */
    Optional<OrganizationContext> organizationContextOf(String accountId);

    /**
     * One account's organization, as the keys that name it are populated.
     *
     * @param organizationId      the organization's own id, which {@code aws:PrincipalOrgID} carries
     * @param managementAccountId the account that created the organization
     * @param path                the Organizations path down to the account's parent. A path is
     *                            "a text representation of the structure of an AWS Organizations
     *                            entity", built "using the IDs of the organization, root, and all
     *                            OUs in the path down to and including the OU", and the key's own
     *                            published condition compares it with {@code StringEquals} to
     *                            {@code o-a1b2c3d4e5/r-ab12/ou-ab12-11111111/ou-ab12-22222222/},
     *                            which "returns true for principals in accounts that are attached
     *                            directly to the ou-ab12-22222222 OU": the value a condition is
     *                            held against therefore stops at the parent and does not carry
     *                            the account's own id, which the entity path of an account does
     */
    record OrganizationContext(String organizationId, String managementAccountId, String path) {

        /**
         * Writes the organization keys onto a request context, beside the principal keys
         * {@link IamService.CallerArns#putPrincipalKeys} writes, so every context an organization's
         * principal is evaluated in names the same organization. A part the organization does not
         * answer is left absent, which is what makes a policy matching on it fail to match.
         */
        public void putOrganizationKeys(Map<String, List<String>> context) {
            if (organizationId != null) {
                context.put("aws:PrincipalOrgID", List.of(organizationId));
            }
            if (managementAccountId != null) {
                context.put("aws:PrincipalOrgMasterAccountId", List.of(managementAccountId));
            }
            if (path != null) {
                // Multivalued by publication, one path per principal: the account sits in one
                // place in the hierarchy, so a ForAnyValue condition is held to that one path.
                context.put("aws:PrincipalOrgPaths", List.of(path));
            }
        }
    }
}
