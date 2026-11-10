package io.floci.aws.services.iam;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Centralized root access: who may turn the two root features on and off, and who may launch a
 * privileged session with them. The five IAM operations behind the feature set
 * ({@code ListOrganizationsFeatures}, {@code Enable}/{@code DisableOrganizationsRootCredentialsManagement},
 * {@code Enable}/{@code DisableOrganizationsRootSessions}) all answer for one organization, so
 * every one of them goes through here and {@link IamService} keeps only the toggles themselves.
 *
 * <p>Two things this fixes about those toggles. They took no caller check at all, so any account
 * could enable or disable organization-wide root features; the reference models five refusals on
 * them and they are all made here. And the record they write is account-scoped like every other
 * IAM record, so the management account's toggle was invisible to a delegated administrator: the
 * feature set belongs to the organization, so it is read and written in the management account's
 * scope whoever asks. A toggle set before this change sat in the calling account's scope and is
 * not read any more, so it is set again once.
 *
 * <p>The organization itself is answered by {@link RootAccessOrganization}, which Organizations
 * implements, so IAM still does not depend on Organizations.
 */
@ApplicationScoped
public class CentralRootAccess {

    /** The wire code is the shape name: botocore gives these five shapes no {@code error.code}. */
    private static final int REFUSED = 400;

    private final IamService iamService;
    private final Instance<RootAccessOrganization> organizations;

    @Inject
    public CentralRootAccess(IamService iamService, Instance<RootAccessOrganization> organizations) {
        this.iamService = iamService;
        this.organizations = organizations;
    }

    /** The organization id and its enabled features, which every one of the five operations answers. */
    public record Features(String organizationId, List<String> enabledFeatures) {}

    public Features list(String callerAccountId) {
        RootAccessOrganization.Membership organization = authorize(callerAccountId, false);
        return features(organization, iamService::listOrganizationsFeatures);
    }

    public Features enableRootCredentialsManagement(String callerAccountId) {
        RootAccessOrganization.Membership organization = authorize(callerAccountId, true);
        return features(organization, iamService::enableOrganizationsRootCredentialsManagement);
    }

    public Features enableRootSessions(String callerAccountId) {
        RootAccessOrganization.Membership organization = authorize(callerAccountId, true);
        return features(organization, iamService::enableOrganizationsRootSessions);
    }

    public Features disableRootCredentialsManagement(String callerAccountId) {
        RootAccessOrganization.Membership organization = authorize(callerAccountId, false);
        return features(organization, iamService::disableOrganizationsRootCredentialsManagement);
    }

    public Features disableRootSessions(String callerAccountId) {
        RootAccessOrganization.Membership organization = authorize(callerAccountId, false);
        return features(organization, iamService::disableOrganizationsRootSessions);
    }

    /**
     * The organization a privileged session may be launched in, or a refusal. The caller must be
     * able to manage root access and {@code RootSessions} must be enabled, which is what the guide
     * means by "You have enabled centralized root access in your organization".
     *
     * <p>{@code AssumeRoot} models neither refusal, so both are {@code AccessDenied} 403, the
     * answer STS publishes for a caller it will not mint credentials for.
     */
    RootAccessOrganization.Membership privilegedSessionOrganization(String callerAccountId) {
        Optional<RootAccessOrganization.Membership> found = membershipOf(callerAccountId);
        if (found.isEmpty() || !found.get().allFeatures() || !found.get().iamTrustedAccess()
                || !found.get().isManagementOrDelegatedAdministrator(callerAccountId)) {
            throw new AwsException("AccessDenied", "User: " + callerAccountId + " is not authorized to perform:"
                    + " sts:AssumeRoot because it is not the management account or a delegated administrator"
                    + " account for centralized root access.", 403);
        }
        RootAccessOrganization.Membership organization = found.get();
        if (!features(organization, iamService::listOrganizationsFeatures)
                .enabledFeatures().contains(IamService.FEATURE_ROOT_SESSIONS)) {
            throw new AwsException("AccessDenied", "User: " + callerAccountId + " is not authorized to perform:"
                    + " sts:AssumeRoot because centralized root access is not enabled for the organization."
                    + " Call EnableOrganizationsRootSessions first.", 403);
        }
        return organization;
    }

    /**
     * The five refusals the reference models on the feature operations, in the order they are
     * asked: whether there is an organization at all, what it has enabled, and who is asking.
     * {@code CallerIsNotManagementAccount} is modeled on the two enable operations only, so a
     * delegated administrator may list and disable but not enable.
     */
    private RootAccessOrganization.Membership authorize(String callerAccountId, boolean managementOnly) {
        RootAccessOrganization.Membership organization = membershipOf(callerAccountId)
                .orElseThrow(() -> refused("OrganizationNotFoundException",
                        "The request was rejected because no organization is associated with your account."));
        if (!organization.allFeatures()) {
            throw refused("OrganizationNotInAllFeaturesModeException", "The request was rejected because your"
                    + " organization does not have All features enabled.");
        }
        if (!organization.iamTrustedAccess()) {
            throw refused("ServiceAccessNotEnabledException", "The request was rejected because trusted access"
                    + " is not enabled for IAM in Organizations.");
        }
        if (!organization.isManagementOrDelegatedAdministrator(callerAccountId)) {
            throw refused("AccountNotManagementOrDelegatedAdministratorException", "The request was rejected"
                    + " because the account making the request is not the management account or delegated"
                    + " administrator account for centralized root access.");
        }
        if (managementOnly && !organization.isManagementAccount(callerAccountId)) {
            throw refused("CallerIsNotManagementAccountException", "The request was rejected because the account"
                    + " making the request is not the management account for the organization.");
        }
        return organization;
    }

    /** The feature set belongs to the organization, so the management account's scope holds it. */
    private Features features(RootAccessOrganization.Membership organization, Supplier<List<String>> toggle) {
        return new Features(organization.organizationId(),
                RequestScopes.callAs(organization.managementAccountId(), toggle));
    }

    private Optional<RootAccessOrganization.Membership> membershipOf(String accountId) {
        if (!organizations.isResolvable()) {
            return Optional.empty();
        }
        return organizations.get().membershipOf(accountId);
    }

    private static AwsException refused(String code, String message) {
        return new AwsException(code, message, REFUSED);
    }
}
