package io.github.hectorvent.floci.services.organizations;

/**
 * Creates the IAM role AWS Organizations preconfigures in each member account it creates:
 * {@code CreateAccount}'s {@code RoleName} parameter, defaulting to
 * {@code OrganizationAccountAccessRole}, trusting the management account (Organizations
 * User Guide, "Creating a member account in your organization").
 *
 * <p>Organizations says what the new account gets; IAM owns how the role is created, so the
 * implementation lives with IAM and the role is stored and its ARN minted the way every other
 * IAM role is. The interface keeps the two services decoupled the way
 * {@code ScpProvider} decouples IAM's reads of organization policy from Organizations.
 */
public interface AccountAccessRoleProvisioner {

    /** A provisioner that creates nothing, for callers that only exercise the service shape. */
    AccountAccessRoleProvisioner NOOP = (memberAccountId, roleName, managementAccountId) -> { };

    /**
     * Creates {@code roleName} in {@code memberAccountId}, trusting {@code managementAccountId}.
     *
     * @param memberAccountId the account CreateAccount just created, which owns the role
     * @param roleName the role name to create, already defaulted and validated by the caller
     * @param managementAccountId the organization's management account, the trusted principal
     */
    void provision(String memberAccountId, String roleName, String managementAccountId);
}
