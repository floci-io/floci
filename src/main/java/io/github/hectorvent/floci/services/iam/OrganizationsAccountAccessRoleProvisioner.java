package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.services.organizations.AccountAccessRoleProvisioner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Creates the account access role Organizations preconfigures in each member account, through
 * IAM's own role creation so storage, validation and ARN minting stay identical to
 * {@code CreateRole}'s. {@code OrganizationsService} depends only on the interface, the way
 * IAM's reads of organization policy depend only on {@code ScpProvider}.
 */
@ApplicationScoped
public class OrganizationsAccountAccessRoleProvisioner implements AccountAccessRoleProvisioner {

    private final IamService iamService;

    @Inject
    public OrganizationsAccountAccessRoleProvisioner(IamService iamService) {
        this.iamService = iamService;
    }

    @Override
    public void provision(String memberAccountId, String roleName, String managementAccountId) {
        iamService.provisionAccountAccessRole(memberAccountId, roleName, managementAccountId);
    }
}
