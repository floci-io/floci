package io.floci.aws.services.iam;

import io.floci.aws.core.common.ServicePrincipals;

import java.util.Optional;
import java.util.Set;

/**
 * Supplies what centralized root access needs to know about an account's organization: who runs
 * it, whether IAM is trusted in it, and who may act for IAM inside it.
 *
 * <p>Implemented by the Organizations service; consumed lazily by {@link CentralRootAccess} via
 * {@code Instance<RootAccessOrganization>} so IAM never depends on Organizations directly, the
 * way {@link ScpProvider} already does it (Organizations depends on IAM for role provisioning).
 */
public interface RootAccessOrganization {

    /** The IAM service principal whose trusted access and delegated administrators decide this. */
    String IAM_SERVICE_PRINCIPAL = ServicePrincipals.of("iam");

    /**
     * The organization the account belongs to, or empty when it belongs to none.
     *
     * @param accountId the account a request arrived for
     */
    Optional<Membership> membershipOf(String accountId);

    /**
     * One account's view of its organization.
     *
     * @param organizationId        the organization's own id, which the root-feature operations answer
     * @param managementAccountId   the account that created the organization
     * @param allFeatures           whether the organization has the All features set enabled
     * @param iamTrustedAccess      whether trusted access is enabled for IAM in Organizations
     * @param iamDelegatedAdmins    the accounts registered as delegated administrators for IAM
     * @param memberAccountIds      every account in the organization, the management account included
     */
    record Membership(String organizationId, String managementAccountId, boolean allFeatures,
                      boolean iamTrustedAccess, Set<String> iamDelegatedAdmins,
                      Set<String> memberAccountIds) {

        public boolean isManagementAccount(String accountId) {
            return managementAccountId.equals(accountId);
        }

        public boolean isManagementOrDelegatedAdministrator(String accountId) {
            return isManagementAccount(accountId) || iamDelegatedAdmins.contains(accountId);
        }
    }
}
