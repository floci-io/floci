package io.github.hectorvent.floci.services.organizations;

/**
 * Told when a member account takes a new place in an organization or leaves it. CloudFormation StackSets
 * follows the account there: a service-managed stack set with automatic deployment deploys to an account
 * added to a target organizational unit and removes its stack instances when the account leaves every
 * target (CloudFormation User Guide, "Enable or disable automatic deployments for StackSets in AWS
 * Organizations").
 *
 * <p>Organizations says where the account is now; the listener owns what follows from it, the way
 * {@link AccountAccessRoleProvisioner} keeps Organizations apart from IAM.
 */
public interface AccountPlacementListener {

    /**
     * {@code accountId} was created in, invited into, moved within or removed from the organization that
     * {@code managementAccountId} manages. Called once the change is stored, in the request that made it.
     */
    void accountPlacementChanged(String managementAccountId, String accountId);
}
