package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.*;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;
import io.github.hectorvent.floci.services.iam.model.AccessKey;
import io.github.hectorvent.floci.services.iam.model.AccountPasswordPolicy;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.iam.model.DelegationPermission;
import io.github.hectorvent.floci.services.iam.model.DelegationRequest;
import io.github.hectorvent.floci.services.iam.model.IamGroup;
import io.github.hectorvent.floci.services.iam.model.IamPolicy;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.IamUser;
import io.github.hectorvent.floci.services.iam.model.InstanceProfile;
import io.github.hectorvent.floci.services.iam.model.LoginProfile;
import io.github.hectorvent.floci.services.iam.model.OpenIDConnectProvider;
import io.github.hectorvent.floci.services.iam.model.OrganizationsAccessReportJob;
import io.github.hectorvent.floci.services.iam.model.OutboundWebIdentityFederation;
import io.github.hectorvent.floci.services.iam.model.PolicyParameter;
import io.github.hectorvent.floci.services.iam.model.PolicyVersion;
import io.github.hectorvent.floci.services.iam.model.SAMLProvider;
import io.github.hectorvent.floci.services.iam.model.ServerCertificate;
import io.github.hectorvent.floci.services.iam.model.ServiceLastAccessedEntity;
import io.github.hectorvent.floci.services.iam.model.ServiceLastAccessedJob;
import io.github.hectorvent.floci.services.iam.model.ServiceSpecificCredential;
import io.github.hectorvent.floci.services.iam.model.SigningCertificate;
import io.github.hectorvent.floci.services.iam.model.SshPublicKey;
import io.github.hectorvent.floci.services.iam.model.VirtualMfaDevice;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Query-protocol handler for IAM actions.
 * Receives pre-dispatched calls from {@link AwsQueryController}.
 * All responses use the IAM XML namespace {@code https://iam.amazonaws.com/doc/2010-05-08/}.
 */
@ApplicationScoped
public class IamQueryHandler {

    private static final Logger LOG = Logger.getLogger(IamQueryHandler.class);

    /** {@code MaxItems} on the readers that page through {@link #paginate}: "defaults to 100", range 1 to 1000. */
    private static final int DEFAULT_MAX_ITEMS = 100;
    private static final int MAX_MAX_ITEMS = 1000;
    /** {@code jobIDType} on every report reader: min 36 and max 36, which is a UUID string. */
    private static final int JOB_ID_LENGTH = 36;
    /** {@code arnType} on {@code PolicySourceArn} and {@code TemplateArn}: 20 to 2048, no pattern. */
    private static final int ARN_MIN_LENGTH = 20;
    private static final int ARN_MAX_LENGTH = 2048;
    /** {@code serialNumberType} on every MFA member: 9 to 256, pattern {@code [\w+=/:,.@-]+}. */
    private static final int SERIAL_NUMBER_MIN_LENGTH = 9;
    private static final int SERIAL_NUMBER_MAX_LENGTH = 256;
    private static final Pattern SERIAL_NUMBER_PATTERN = Pattern.compile("[\\w+=/:,.@-]+");
    /** {@code userNameType}: 1 to 64, pattern {@code [\w+=,.@-]+}. */
    private static final int USER_NAME_MIN_LENGTH = 1;
    private static final int USER_NAME_MAX_LENGTH = 64;
    private static final Pattern USER_NAME_PATTERN = Pattern.compile("[\\w+=,.@-]+");
    /** {@code mapStringReplacementValueEntry} on {@code AcquireRole}: at most 30 entries. */
    private static final int REPLACEMENT_VALUES_MAX_ENTRIES = 30;
    /** {@code sortKeyType} on {@code GetOrganizationsAccessReport}. */
    private static final Set<String> SORT_KEYS = Set.of("SERVICE_NAMESPACE_ASCENDING",
            "SERVICE_NAMESPACE_DESCENDING", "LAST_AUTHENTICATED_TIME_ASCENDING",
            "LAST_AUTHENTICATED_TIME_DESCENDING");
    /** {@code serviceNamespaceListType}: minimum 1 item, maximum 200. */
    private static final int MAX_SERVICE_NAMESPACES = 200;
    private static final int MAX_TAG_LIST_MEMBERS = 50;
    private static final int MAX_TAG_KEY_LENGTH = 128;
    private static final int MAX_TAG_VALUE_LENGTH = 256;
    private static final Pattern TAG_KEY_PATTERN = Pattern.compile("[\\p{L}\\p{Z}\\p{N}_.:/=+\\-@]+");
    private static final Pattern TAG_VALUE_PATTERN = Pattern.compile("[\\p{L}\\p{Z}\\p{N}_.:/=+\\-@]*");

    private final IamService iamService;
    private final IamPolicyEvaluator policyEvaluator;
    private final AccountResolver accountResolver;
    private final SAMLProviderService samlProviderService;
    private final ServiceLastAccessedService serviceLastAccessedService;
    private final OrganizationsAccessReportService organizationsAccessReportService;
    private final DelegationRequestService delegationRequestService;
    private final CentralRootAccess centralRootAccess;
    private final RegionResolver regionResolver;
    private final Instance<ServerCertificateReferenceProvider> serverCertificateReferenceProviders;
    /** Signs each page's Marker, so only a marker this handler issued names an item. */
    private final SignedMarkers markers = new SignedMarkers();

    @Inject
    public IamQueryHandler(IamService iamService, IamPolicyEvaluator policyEvaluator,
                           AccountResolver accountResolver, SAMLProviderService samlProviderService,
                           ServiceLastAccessedService serviceLastAccessedService,
                           OrganizationsAccessReportService organizationsAccessReportService,
                           DelegationRequestService delegationRequestService,
                           CentralRootAccess centralRootAccess,
                           RegionResolver regionResolver,
                           Instance<ServerCertificateReferenceProvider> serverCertificateReferenceProviders) {
        this.iamService = iamService;
        this.policyEvaluator = policyEvaluator;
        this.accountResolver = accountResolver;
        this.samlProviderService = samlProviderService;
        this.serviceLastAccessedService = serviceLastAccessedService;
        this.organizationsAccessReportService = organizationsAccessReportService;
        this.delegationRequestService = delegationRequestService;
        this.centralRootAccess = centralRootAccess;
        this.regionResolver = regionResolver;
        this.serverCertificateReferenceProviders = serverCertificateReferenceProviders;
    }

    public Response handle(String action, MultivaluedMap<String, String> params, String authorization) {
        LOG.debugv("IAM action: {0}", action);

        try {
            return switch (action) {
            // Users
            case "CreateUser" -> handleCreateUser(params);
            case "GetUser" -> handleGetUser(params, authorization);
            case "DeleteUser" -> handleDeleteUser(params);
            case "ListUsers" -> handleListUsers(params);
            case "UpdateUser" -> handleUpdateUser(params);
            case "CreateServiceSpecificCredential" -> handleCreateServiceSpecificCredential(params);
            case "ListServiceSpecificCredentials" -> handleListServiceSpecificCredentials(params, authorization);
            case "UpdateServiceSpecificCredential" -> handleUpdateServiceSpecificCredential(params, authorization);
            case "ResetServiceSpecificCredential" -> handleResetServiceSpecificCredential(params, authorization);
            case "DeleteServiceSpecificCredential" -> handleDeleteServiceSpecificCredential(params, authorization);
            case "UploadSSHPublicKey" -> handleUploadSshPublicKey(params);
            case "GetSSHPublicKey" -> handleGetSshPublicKey(params);
            case "ListSSHPublicKeys" -> handleListSshPublicKeys(params, authorization);
            case "UpdateSSHPublicKey" -> handleUpdateSshPublicKey(params);
            case "DeleteSSHPublicKey" -> handleDeleteSshPublicKey(params);
            case "UploadSigningCertificate" -> handleUploadSigningCertificate(params, authorization);
            case "ListSigningCertificates" -> handleListSigningCertificates(params, authorization);
            case "UpdateSigningCertificate" -> handleUpdateSigningCertificate(params, authorization);
            case "DeleteSigningCertificate" -> handleDeleteSigningCertificate(params, authorization);
            case "TagUser" -> handleTagUser(params);
            case "UntagUser" -> handleUntagUser(params);
            case "ListUserTags" -> handleListUserTags(params);
            case "CreateLoginProfile" -> handleCreateLoginProfile(params, authorization);
            case "GetLoginProfile" -> handleGetLoginProfile(params, authorization);
            case "UpdateLoginProfile" -> handleUpdateLoginProfile(params);
            case "DeleteLoginProfile" -> handleDeleteLoginProfile(params, authorization);
            case "ChangePassword" -> handleChangePassword(params, authorization);

            // Multi-factor authentication
            case "CreateVirtualMFADevice" -> handleCreateVirtualMFADevice(params);
            case "ListVirtualMFADevices" -> handleListVirtualMFADevices(params);
            case "DeleteVirtualMFADevice" -> handleDeleteVirtualMFADevice(params);
            case "EnableMFADevice" -> handleEnableMFADevice(params);
            case "DeactivateMFADevice" -> handleDeactivateMFADevice(params, authorization);
            case "ResyncMFADevice" -> handleResyncMFADevice(params);
            case "ListMFADevices" -> handleListMFADevices(params, authorization);
            case "GetMFADevice" -> handleGetMFADevice(params);
            case "TagMFADevice" -> handleTagMFADevice(params);
            case "UntagMFADevice" -> handleUntagMFADevice(params);
            case "ListMFADeviceTags" -> handleListMFADeviceTags(params);

            // Identity providers & server certificates
            case "ListSAMLProviders" -> handleListSAMLProviders(authorization);
            case "CreateSAMLProvider" -> handleCreateSAMLProvider(params, authorization);
            case "GetSAMLProvider" -> handleGetSAMLProvider(params, authorization);
            case "UpdateSAMLProvider" -> handleUpdateSAMLProvider(params, authorization);
            case "DeleteSAMLProvider" -> handleDeleteSAMLProvider(params, authorization);
            case "TagSAMLProvider" -> handleTagSAMLProvider(params, authorization);
            case "UntagSAMLProvider" -> handleUntagSAMLProvider(params, authorization);
            case "ListSAMLProviderTags" -> handleListSAMLProviderTags(params, authorization);
            case "ListOpenIDConnectProviders" -> handleListOpenIDConnectProviders(params);
            case "CreateOpenIDConnectProvider" -> handleCreateOpenIDConnectProvider(params);
            case "GetOpenIDConnectProvider" -> handleGetOpenIDConnectProvider(params);
            case "DeleteOpenIDConnectProvider" -> handleDeleteOpenIDConnectProvider(params);
            case "AddClientIDToOpenIDConnectProvider" -> handleAddClientIDToOpenIDConnectProvider(params);
            case "RemoveClientIDFromOpenIDConnectProvider" ->
                    handleRemoveClientIDFromOpenIDConnectProvider(params);
            case "UpdateOpenIDConnectProviderThumbprint" -> handleUpdateOpenIDConnectProviderThumbprint(params);
            case "TagOpenIDConnectProvider" -> handleTagOpenIDConnectProvider(params);
            case "UntagOpenIDConnectProvider" -> handleUntagOpenIDConnectProvider(params);
            case "ListOpenIDConnectProviderTags" -> handleListOpenIDConnectProviderTags(params);
            case "UploadServerCertificate" -> handleUploadServerCertificate(params);
            case "GetServerCertificate" -> handleGetServerCertificate(params);
            case "UpdateServerCertificate" -> handleUpdateServerCertificate(params);
            case "DeleteServerCertificate" -> handleDeleteServerCertificate(params);
            case "ListServerCertificates" -> handleListServerCertificates(params);
            case "TagServerCertificate" -> handleTagServerCertificate(params);
            case "UntagServerCertificate" -> handleUntagServerCertificate(params);
            case "ListServerCertificateTags" -> handleListServerCertificateTags(params);

            // Account Aliases
            case "ListAccountAliases" -> handleListAccountAliases(params);
            case "CreateAccountAlias" -> handleCreateAccountAlias(params);
            case "DeleteAccountAlias" -> handleDeleteAccountAlias(params);

            // Account Password Policy
            case "GetAccountPasswordPolicy" -> handleGetAccountPasswordPolicy(params);
            case "UpdateAccountPasswordPolicy" -> handleUpdateAccountPasswordPolicy(params);
            case "DeleteAccountPasswordPolicy" -> handleDeleteAccountPasswordPolicy(params);

            // Groups
            case "CreateGroup" -> handleCreateGroup(params);
            case "GetGroup" -> handleGetGroup(params);
            case "UpdateGroup" -> handleUpdateGroup(params);
            case "DeleteGroup" -> handleDeleteGroup(params);
            case "ListGroups" -> handleListGroups(params);
            case "AddUserToGroup" -> handleAddUserToGroup(params);
            case "RemoveUserFromGroup" -> handleRemoveUserFromGroup(params);
            case "ListGroupsForUser" -> handleListGroupsForUser(params);

            // Roles
            case "CreateRole" -> handleCreateRole(params);
            case "GetRole" -> handleGetRole(params);
            case "DeleteRole" -> handleDeleteRole(params);
            case "ListRoles" -> handleListRoles(params);
            case "UpdateRole" -> handleUpdateRole(params);
            case "UpdateRoleDescription" -> handleUpdateRoleDescription(params);
            case "CreateServiceLinkedRole" -> handleCreateServiceLinkedRole(params);
            case "DeleteServiceLinkedRole" -> handleDeleteServiceLinkedRole(params);
            case "GetServiceLinkedRoleDeletionStatus" -> handleGetServiceLinkedRoleDeletionStatus(params);
            case "UpdateAssumeRolePolicy" -> handleUpdateAssumeRolePolicy(params);
            case "TagRole" -> handleTagRole(params);
            case "UntagRole" -> handleUntagRole(params);
            case "TagInstanceProfile" -> handleTagInstanceProfile(params);
            case "UntagInstanceProfile" -> handleUntagInstanceProfile(params);
            case "ListInstanceProfileTags" -> handleListInstanceProfileTags(params);
            case "ListRoleTags" -> handleListRoleTags(params);

            // Role templates
            case "AcquireRole" -> handleAcquireRole(params);
            case "GetRoleTemplateVersion" -> handleGetRoleTemplateVersion(params);

            // Managed Policies
            case "CreatePolicy" -> handleCreatePolicy(params);
            case "GetPolicy" -> handleGetPolicy(params);
            case "DeletePolicy" -> handleDeletePolicy(params);
            case "ListPolicies" -> handleListPolicies(params);
            case "ListEntitiesForPolicy" -> handleListEntitiesForPolicy(params);
            case "GetAccountSummary" -> handleGetAccountSummary(params);
            case "GetAccountProperties" -> handleGetAccountProperties(params);
            case "PutAccountProperties" -> handlePutAccountProperties(params);
            case "SetSecurityTokenServicePreferences" ->
                    handleSetSecurityTokenServicePreferences(params);
            case "EnableOutboundWebIdentityFederation" ->
                    handleEnableOutboundWebIdentityFederation(params);
            case "DisableOutboundWebIdentityFederation" ->
                    handleDisableOutboundWebIdentityFederation(params);
            case "GetOutboundWebIdentityFederationInfo" ->
                    handleGetOutboundWebIdentityFederationInfo(params);
            case "GetAccountAuthorizationDetails" -> handleGetAccountAuthorizationDetails(params);
            case "GenerateCredentialReport" -> handleGenerateCredentialReport(params);
            case "GetCredentialReport" -> handleGetCredentialReport(params);
            case "CreatePolicyVersion" -> handleCreatePolicyVersion(params);
            case "GetPolicyVersion" -> handleGetPolicyVersion(params);
            case "DeletePolicyVersion" -> handleDeletePolicyVersion(params);
            case "ListPolicyVersions" -> handleListPolicyVersions(params);
            case "SetDefaultPolicyVersion" -> handleSetDefaultPolicyVersion(params);
            case "TagPolicy" -> handleTagPolicy(params);
            case "UntagPolicy" -> handleUntagPolicy(params);
            case "ListPolicyTags" -> handleListPolicyTags(params);

            // Policy Attachments — Users
            case "AttachUserPolicy" -> handleAttachUserPolicy(params);
            case "DetachUserPolicy" -> handleDetachUserPolicy(params);
            case "ListAttachedUserPolicies" -> handleListAttachedUserPolicies(params);

            // Policy Attachments — Groups
            case "AttachGroupPolicy" -> handleAttachGroupPolicy(params);
            case "DetachGroupPolicy" -> handleDetachGroupPolicy(params);
            case "ListAttachedGroupPolicies" -> handleListAttachedGroupPolicies(params);

            // Policy Attachments — Roles
            case "AttachRolePolicy" -> handleAttachRolePolicy(params);
            case "DetachRolePolicy" -> handleDetachRolePolicy(params);
            case "ListAttachedRolePolicies" -> handleListAttachedRolePolicies(params);

            // Inline Policies — Users
            case "PutUserPolicy" -> handlePutUserPolicy(params);
            case "GetUserPolicy" -> handleGetUserPolicy(params);
            case "DeleteUserPolicy" -> handleDeleteUserPolicy(params);
            case "ListUserPolicies" -> handleListUserPolicies(params);

            // Inline Policies — Groups
            case "PutGroupPolicy" -> handlePutGroupPolicy(params);
            case "GetGroupPolicy" -> handleGetGroupPolicy(params);
            case "DeleteGroupPolicy" -> handleDeleteGroupPolicy(params);
            case "ListGroupPolicies" -> handleListGroupPolicies(params);

            // Inline Policies — Roles
            case "PutRolePolicy" -> handlePutRolePolicy(params);
            case "GetRolePolicy" -> handleGetRolePolicy(params);
            case "DeleteRolePolicy" -> handleDeleteRolePolicy(params);
            case "ListRolePolicies" -> handleListRolePolicies(params);

            // Access Keys
            case "CreateAccessKey" -> handleCreateAccessKey(params);
            case "DeleteAccessKey" -> handleDeleteAccessKey(params, authorization);
            case "ListAccessKeys" -> handleListAccessKeys(params, authorization);
            case "UpdateAccessKey" -> handleUpdateAccessKey(params, authorization);
            case "GetAccessKeyLastUsed" -> handleGetAccessKeyLastUsed(params);

            case "ListOrganizationsFeatures" -> handleListOrganizationsFeatures(params);
            case "EnableOrganizationsRootCredentialsManagement" -> handleEnableOrganizationsRootCredentialsManagement(params);
            case "EnableOrganizationsRootSessions" -> handleEnableOrganizationsRootSessions(params);
            case "DisableOrganizationsRootCredentialsManagement" -> handleDisableOrganizationsRootCredentialsManagement(params);
            case "DisableOrganizationsRootSessions" -> handleDisableOrganizationsRootSessions(params);

            // Instance Profiles
            case "CreateInstanceProfile" -> handleCreateInstanceProfile(params);
            case "GetInstanceProfile" -> handleGetInstanceProfile(params);
            case "DeleteInstanceProfile" -> handleDeleteInstanceProfile(params);
            case "ListInstanceProfiles" -> handleListInstanceProfiles(params);
            case "AddRoleToInstanceProfile" -> handleAddRoleToInstanceProfile(params);
            case "RemoveRoleFromInstanceProfile" -> handleRemoveRoleFromInstanceProfile(params);
            case "ListInstanceProfilesForRole" -> handleListInstanceProfilesForRole(params);

            // Permission Boundaries
            case "PutUserPermissionsBoundary"    -> handlePutUserPermissionsBoundary(params);
            case "DeleteUserPermissionsBoundary" -> handleDeleteUserPermissionsBoundary(params);
            case "PutRolePermissionsBoundary"    -> handlePutRolePermissionsBoundary(params);
            case "DeleteRolePermissionsBoundary" -> handleDeleteRolePermissionsBoundary(params);

            // Policy Simulation
            case "SimulatePrincipalPolicy" -> handleSimulatePrincipalPolicy(params);
            case "SimulateCustomPolicy" -> handleSimulateCustomPolicy(params);
            case "GetContextKeysForCustomPolicy" -> handleGetContextKeysForCustomPolicy(params);
            case "GetContextKeysForPrincipalPolicy" -> handleGetContextKeysForPrincipalPolicy(params);

            // Last-Accessed Reporting (Access Advisor)
            case "GenerateServiceLastAccessedDetails" -> handleGenerateServiceLastAccessedDetails(params, authorization);
            case "GetServiceLastAccessedDetails" -> handleGetServiceLastAccessedDetails(params, authorization);
            case "GetServiceLastAccessedDetailsWithEntities" ->
                    handleGetServiceLastAccessedDetailsWithEntities(params, authorization);
            case "GenerateOrganizationsAccessReport" ->
                    handleGenerateOrganizationsAccessReport(params, authorization);
            case "GetOrganizationsAccessReport" ->
                    handleGetOrganizationsAccessReport(params, authorization);
            case "ListPoliciesGrantingServiceAccess" -> handleListPoliciesGrantingServiceAccess(params, authorization);

            // Delegation requests
            case "CreateDelegationRequest" -> handleCreateDelegationRequest(params);
            case "AssociateDelegationRequest" -> handleAssociateDelegationRequest(params, authorization);
            case "AcceptDelegationRequest" -> handleAcceptDelegationRequest(params, authorization);
            case "RejectDelegationRequest" -> handleRejectDelegationRequest(params);
            case "UpdateDelegationRequest" -> handleUpdateDelegationRequest(params);
            case "SendDelegationToken" -> handleSendDelegationToken(params, authorization);
            case "GetDelegationRequest" -> handleGetDelegationRequest(params);
            case "ListDelegationRequests" -> handleListDelegationRequests(params);
            case "GetHumanReadableSummary" -> handleGetHumanReadableSummary(params);

            default -> AwsQueryResponse.error("UnsupportedOperation",
                    "Operation " + action + " is not supported.", AwsNamespaces.IAM, 400);
            };
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.IAM, e.getHttpStatus());
        }
    }

    // =========================================================================
    // Users
    // =========================================================================

    private Response handleCreateUser(MultivaluedMap<String, String> params) {
        String userName = getParam(params, "UserName");
        String path = getParam(params, "Path");
        Map<String, String> tags = extractTags(params, true);
        IamUser user = iamService.createUser(userName, path, getParam(params, "PermissionsBoundary"));
        if (!tags.isEmpty()) iamService.tagUser(userName, tags);
        user = iamService.getUser(userName);
        String result = new XmlBuilder().start("User").raw(userXml(user, true)).end("User").build();
        return Response.ok(AwsQueryResponse.envelope("CreateUser", AwsNamespaces.IAM, result)).build();
    }

    // UserName is optional on GetUser/ListAccessKeys/DeleteAccessKey/UpdateAccessKey: real AWS
    // determines it implicitly from the access key that signed the request. Mirrors moto: an access
    // key that maps to no stored IAM user is the documented NoSuchEntity error.
    private String resolveUserName(MultivaluedMap<String, String> params, String authorization) {
        String userName = getParam(params, "UserName");
        if (userName != null) {
            return userName;
        }
        String accessKeyId = accountResolver.extractAccessKeyId(authorization);
        if (accessKeyId == null) {
            throw new AwsException("NoSuchEntity",
                    "No access key could be determined from the request.", 404);
        }
        return iamService.findUserNameByAccessKeyId(accessKeyId)
                .orElseThrow(() -> new AwsException("NoSuchEntity",
                        "The Access Key with id " + accessKeyId + " cannot be found.", 404));
    }

    private Response handleGetUser(MultivaluedMap<String, String> params, String authorization) {
        // UserName is optional per the IAM model: it defaults to the user owning the
        // access key that signed the request.
        IamUser user = iamService.getUser(resolveUserName(params, authorization));
        String result = new XmlBuilder().start("User").raw(userXml(user, true)).end("User").build();
        return Response.ok(AwsQueryResponse.envelope("GetUser", AwsNamespaces.IAM, result)).build();
    }

    private Response handleDeleteUser(MultivaluedMap<String, String> params) {
        String userName = getParam(params, "UserName");
        iamService.deleteUser(userName);
        return Response.ok(AwsQueryResponse.envelopeNoResult("DeleteUser", AwsNamespaces.IAM)).build();
    }

    private Response handleListUsers(MultivaluedMap<String, String> params) {
        String pathPrefix = getParam(params, "PathPrefix");
        List<IamUser> userList = iamService.listUsers(pathPrefix);
        XmlBuilder xml = new XmlBuilder().start("Users");
        for (IamUser u : userList) {
            xml.start("member").raw(userXml(u, false)).end("member");
        }
        xml.end("Users").elem("IsTruncated", false);
        return Response.ok(AwsQueryResponse.envelope("ListUsers", AwsNamespaces.IAM, xml.build())).build();
    }

    private Response handleUpdateUser(MultivaluedMap<String, String> params) {
        String userName = getParam(params, "UserName");
        String newUserName = getParam(params, "NewUserName");
        String newPath = getParam(params, "NewPath");
        iamService.updateUser(userName, newUserName, newPath);
        return Response.ok(AwsQueryResponse.envelopeNoResult("UpdateUser", AwsNamespaces.IAM)).build();
    }

    private Response handleTagUser(MultivaluedMap<String, String> params) {
        String userName = getParam(params, "UserName");
        iamService.tagUser(userName, extractTags(params, true));
        return Response.ok(AwsQueryResponse.envelopeNoResult("TagUser", AwsNamespaces.IAM)).build();
    }

    private Response handleUntagUser(MultivaluedMap<String, String> params) {
        String userName = getParam(params, "UserName");
        iamService.untagUser(userName, extractTagKeys(params));
        return Response.ok(AwsQueryResponse.envelopeNoResult("UntagUser", AwsNamespaces.IAM)).build();
    }

    private Response handleListUserTags(MultivaluedMap<String, String> params) {
        String userName = getParam(params, "UserName");
        Map<String, String> tags = iamService.listUserTags(userName);
        String result = new XmlBuilder().start("Tags").raw(tagsXml(tags)).end("Tags")
                .elem("IsTruncated", false).build();
        return Response.ok(AwsQueryResponse.envelope("ListUserTags", AwsNamespaces.IAM, result)).build();
    }

    /**
     * {@code Base32StringSeed} and {@code QRCodePNG} are both typed as base64-encoded binary, so
     * the base32 seed string goes out base64-wrapped. The QR code is not returned: encoding a PNG
     * would mean a new dependency for an optional field, and the {@code otpauth://} URI it encodes
     * is derivable from the seed that is returned. {@code aws iam create-virtual-mfa-device} works
     * against this with {@code --bootstrap-method Base32StringSeed}, and fails only when asked for
     * the QR code specifically.
     */
    private Response handleCreateVirtualMFADevice(MultivaluedMap<String, String> params) {
        VirtualMfaDevice device = iamService.createVirtualMfaDevice(
                getParam(params, "VirtualMFADeviceName"), getParam(params, "Path"),
                extractTags(params, false));
        String result = new XmlBuilder()
                .start("VirtualMFADevice")
                .elem("SerialNumber", device.getSerialNumber())
                .elem("Base32StringSeed", base64(device.getBase32Seed()))
                .raw(tagsElement(new TreeMap<>(device.getTags())))
                .end("VirtualMFADevice")
                .build();
        return Response.ok(AwsQueryResponse.envelope("CreateVirtualMFADevice", AwsNamespaces.IAM, result)).build();
    }

    /**
     * Neither the seed nor the tags appear here: AWS notes that "IAM resource-listing operations
     * return a subset of the available attributes for the resource... this operation does not
     * return tags", and handing the seed back on an unauthenticated-by-device list call would
     * leak the shared secret of every device in the account.
     */
    private Response handleListVirtualMFADevices(MultivaluedMap<String, String> params) {
        Page<VirtualMfaDevice> page = paginate(
                iamService.listVirtualMfaDevices(getParam(params, "AssignmentStatus")),
                VirtualMfaDevice::getSerialNumber, params);
        XmlBuilder xml = new XmlBuilder().start("VirtualMFADevices");
        for (VirtualMfaDevice device : page.items()) {
            xml.start("member").elem("SerialNumber", device.getSerialNumber());
            if (device.isAssigned()) {
                xml.elem("EnableDate", isoDate(device.getEnableDate()));
                // Looked up rather than required: DeleteUser will not let a user go while a device
                // is still assigned, so a device pointing at a missing user means the two stores
                // have drifted on disk. User is an optional member, so this one device loses it
                // instead of the whole account-wide listing failing.
                iamService.findUser(device.getUserName())
                        .ifPresent(user -> xml.start("User").raw(userXml(user, false)).end("User"));
            }
            xml.end("member");
        }
        xml.end("VirtualMFADevices").elem("IsTruncated", page.truncated());
        if (page.marker() != null) {
            xml.elem("Marker", page.marker());
        }
        return Response.ok(AwsQueryResponse.envelope("ListVirtualMFADevices", AwsNamespaces.IAM, xml.build())).build();
    }

    private Response handleDeleteVirtualMFADevice(MultivaluedMap<String, String> params) {
        iamService.deleteVirtualMfaDevice(getParam(params, "SerialNumber"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("DeleteVirtualMFADevice", AwsNamespaces.IAM)).build();
    }

    private Response handleEnableMFADevice(MultivaluedMap<String, String> params) {
        iamService.enableMfaDevice(getParam(params, "UserName"), getParam(params, "SerialNumber"),
                getParam(params, "AuthenticationCode1"), getParam(params, "AuthenticationCode2"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("EnableMFADevice", AwsNamespaces.IAM)).build();
    }

    private Response handleDeactivateMFADevice(MultivaluedMap<String, String> params, String authorization) {
        // UserName is optional here, unlike on Enable/Resync: "If no user name is included, it
        // defaults to the principal making the request."
        iamService.deactivateMfaDevice(resolveUserName(params, authorization),
                getParam(params, "SerialNumber"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("DeactivateMFADevice", AwsNamespaces.IAM)).build();
    }

    private Response handleResyncMFADevice(MultivaluedMap<String, String> params) {
        iamService.resyncMfaDevice(getParam(params, "UserName"), getParam(params, "SerialNumber"),
                getParam(params, "AuthenticationCode1"), getParam(params, "AuthenticationCode2"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("ResyncMFADevice", AwsNamespaces.IAM)).build();
    }

    private Response handleListMFADevices(MultivaluedMap<String, String> params, String authorization) {
        String userName = resolveUserName(params, authorization);
        Page<VirtualMfaDevice> page = paginate(iamService.listMfaDevices(userName), VirtualMfaDevice::getSerialNumber, params);
        XmlBuilder xml = new XmlBuilder().start("MFADevices");
        for (VirtualMfaDevice device : page.items()) {
            xml.start("member")
                    .elem("UserName", device.getUserName())
                    .elem("SerialNumber", device.getSerialNumber())
                    .elem("EnableDate", isoDate(device.getEnableDate()))
                    .end("member");
        }
        xml.end("MFADevices").elem("IsTruncated", page.truncated());
        if (page.marker() != null) {
            xml.elem("Marker", page.marker());
        }
        return Response.ok(AwsQueryResponse.envelope("ListMFADevices", AwsNamespaces.IAM, xml.build())).build();
    }

    private Response handleTagMFADevice(MultivaluedMap<String, String> params) {
        iamService.tagMfaDevice(getParam(params, "SerialNumber"), extractTags(params, false));
        return Response.ok(AwsQueryResponse.envelopeNoResult("TagMFADevice", AwsNamespaces.IAM)).build();
    }

    private Response handleUntagMFADevice(MultivaluedMap<String, String> params) {
        iamService.untagMfaDevice(getParam(params, "SerialNumber"), extractTagKeys(params));
        return Response.ok(AwsQueryResponse.envelopeNoResult("UntagMFADevice", AwsNamespaces.IAM)).build();
    }

    /**
     * Unlike IAM's other tag readers here, this one honours {@code Marker} and {@code MaxItems}:
     * AWS models both on ListMFADeviceTags, and a client asking for a one-item page should be able
     * to walk the result. Tags are sorted by key first, as AWS documents ("the returned list of
     * tags is sorted by tag key"), so the marker names a stable position.
     */
    private Response handleListMFADeviceTags(MultivaluedMap<String, String> params) {
        Map<String, String> tags = new TreeMap<>(iamService.listMfaDeviceTags(getParam(params, "SerialNumber")));
        Page<Map.Entry<String, String>> page = paginate(List.copyOf(tags.entrySet()), Map.Entry::getKey, params);
        XmlBuilder xml = new XmlBuilder().start("Tags");
        for (Map.Entry<String, String> tag : page.items()) {
            xml.start("member").elem("Key", tag.getKey()).elem("Value", tag.getValue()).end("member");
        }
        xml.end("Tags").elem("IsTruncated", page.truncated());
        if (page.marker() != null) {
            xml.elem("Marker", page.marker());
        }
        return Response.ok(AwsQueryResponse.envelope("ListMFADeviceTags", AwsNamespaces.IAM, xml.build())).build();
    }

    /**
     * {@code GetMFADevice} reads a FIDO security key, and no IAM operation creates one, so
     * {@code NoSuchEntity} is the only answer it can give. The reference says of
     * {@code SerialNumber} that "For this API, we only accept FIDO security key ARNs", and the
     * page models that 404 and {@code ServiceFailure} and nothing else, so there is no other
     * refusal to prefer for a serial naming nothing.
     *
     * <p>A virtual device's serial is refused the same way, deliberately.
     * {@code CreateVirtualMFADevice} is the only operation here that mints a device and it mints
     * one under {@code mfa/}, which is not a FIDO key, so answering with it would hand back a
     * device AWS would not return for the same request. Reading one is still better than refusing
     * the action outright, which is what this answered before: a 404 the SDK models beats an
     * {@code UnsupportedOperation} it does not.
     *
     * <p>The declared shapes are read first, as the validation ahead of the operation does. A
     * {@code UserName} the request carries is a value even when it is blank, the member being
     * present whatever it holds, so an empty name breaks {@code userNameType}'s lower bound
     * rather than reading as no name at all. The user is resolved when the request names one, an
     * absent user being the same 404 under a message of its own.
     */
    private Response handleGetMFADevice(MultivaluedMap<String, String> params) {
        String serialNumber = requireParam(params, "SerialNumber");
        requireMemberShape(serialNumber, "serialNumber", SERIAL_NUMBER_MIN_LENGTH,
                SERIAL_NUMBER_MAX_LENGTH, SERIAL_NUMBER_PATTERN);
        String userName = getParam(params, "UserName");
        if (userName != null) {
            requireMemberShape(userName, "userName", USER_NAME_MIN_LENGTH, USER_NAME_MAX_LENGTH,
                    USER_NAME_PATTERN);
            iamService.getUser(userName);
        }
        throw new AwsException("NoSuchEntity",
                "The MFA device with serial number " + serialNumber + " cannot be found.", 404);
    }

    private Response handleCreateLoginProfile(MultivaluedMap<String, String> params, String authorization) {
        String userName = resolveUserName(params, authorization);
        String password = getParam(params, "Password");
        if (password == null) {
            throw new AwsException("ValidationError", "The request must contain the parameter Password.", 400);
        }
        boolean passwordResetRequired = getBooleanParam(params, "PasswordResetRequired", false);
        LoginProfile profile = iamService.createLoginProfile(userName, password, passwordResetRequired);
        String result = new XmlBuilder().start("LoginProfile").raw(loginProfileXml(profile)).end("LoginProfile").build();
        return Response.ok(AwsQueryResponse.envelope("CreateLoginProfile", AwsNamespaces.IAM, result)).build();
    }

    private Response handleGetLoginProfile(MultivaluedMap<String, String> params, String authorization) {
        String userName = resolveUserName(params, authorization);
        LoginProfile profile = iamService.getLoginProfile(userName);
        String result = new XmlBuilder().start("LoginProfile").raw(loginProfileXml(profile)).end("LoginProfile").build();
        return Response.ok(AwsQueryResponse.envelope("GetLoginProfile", AwsNamespaces.IAM, result)).build();
    }

    private Response handleUpdateLoginProfile(MultivaluedMap<String, String> params) {
        String userName = getParam(params, "UserName");
        if (userName == null) {
            throw new AwsException("ValidationError", "The request must contain the parameter UserName.", 400);
        }
        String password = getParam(params, "Password");
        Boolean passwordResetRequired = getOptionalBooleanParam(params, "PasswordResetRequired");
        iamService.updateLoginProfile(userName, password, passwordResetRequired);
        return Response.ok(AwsQueryResponse.envelopeNoResult("UpdateLoginProfile", AwsNamespaces.IAM)).build();
    }

    private Response handleDeleteLoginProfile(MultivaluedMap<String, String> params, String authorization) {
        String userName = resolveUserName(params, authorization);
        iamService.deleteLoginProfile(userName);
        return Response.ok(AwsQueryResponse.envelopeNoResult("DeleteLoginProfile", AwsNamespaces.IAM)).build();
    }

    /**
     * Changes the calling user's console password. The operation takes no {@code UserName}: the
     * reference says it "changes the password of the IAM user who is calling this operation", and
     * the CLI reference adds that it "can be called by IAM users only. If this command is called
     * using AWS account (root) credentials, the command returns an InvalidUserType error". A
     * signing key that belongs to no stored user is Floci's stand-in for exactly that caller, so
     * it answers the same way rather than reporting the key as missing.
     */
    private Response handleChangePassword(MultivaluedMap<String, String> params,
                                          String authorization) {
        // Both members are required, and read through getParam rather than requireParam because
        // requireParam refuses a blank value where passwordType permits one: the pattern starts
        // at \u0020, so a password of spaces is a value the model accepts. The shape check
        // therefore stays with the pattern, which also names which of the two members failed.
        iamService.changePassword(callingIamUser(authorization),
                getParam(params, "OldPassword"), getParam(params, "NewPassword"));
        return Response.ok(
                AwsQueryResponse.envelopeNoResult("ChangePassword", AwsNamespaces.IAM)).build();
    }

    /**
     * The IAM user whose access key signed the request, for the one operation that acts on the
     * caller and gives no way to name anyone else. Unlike {@link #resolveUserName}, a key with no
     * user behind it is not a missing entity here: the caller is not an IAM user, which is the
     * case {@code InvalidUserType} is published for.
     */
    private String callingIamUser(String authorization) {
        String accessKeyId = accountResolver.extractAccessKeyId(authorization);
        return iamService.findUserNameByAccessKeyId(accessKeyId)
                .orElseThrow(() -> new AwsException("InvalidUserType",
                        "The request was rejected because the type of user for the transaction "
                                + "was incorrect.", 400));
    }

    private String loginProfileXml(LoginProfile profile) {
        return new XmlBuilder()
                .elem("PasswordResetRequired", profile.isPasswordResetRequired())
                .elem("UserName", profile.getUserName())
                .elem("CreateDate", isoDate(profile.getCreateDate()))
                .build();
    }

    private Response handleListSAMLProviders(String authorization) {
        XmlBuilder xml = new XmlBuilder().start("SAMLProviderList");
        for (SAMLProvider provider : samlProviderService.list(accountResolver.resolve(authorization))) {
            xml.start("member").elem("Arn", provider.getArn()).end("member");
        }
        xml.end("SAMLProviderList");
        return Response.ok(AwsQueryResponse.envelope("ListSAMLProviders", AwsNamespaces.IAM, xml.build())).build();
    }

    private Response handleCreateSAMLProvider(MultivaluedMap<String, String> params, String authorization) {
        SAMLProvider provider = samlProviderService.create(regionResolver.getPartition(),
                accountResolver.resolve(authorization), getParam(params, "Name"),
                getParam(params, "SAMLMetadataDocument"), extractTags(params, false));
        return Response.ok(AwsQueryResponse.envelope("CreateSAMLProvider", AwsNamespaces.IAM,
                new XmlBuilder().elem("SAMLProviderArn", provider.getArn())
                        .raw(tagsElement(new TreeMap<>(provider.getTags()))).build())).build();
    }

    private Response handleGetSAMLProvider(MultivaluedMap<String, String> params, String authorization) {
        String accountId = accountResolver.resolve(authorization);
        SAMLProvider provider = samlProviderService.getForAccount(accountId, getParam(params, "SAMLProviderArn"));
        String metadata = "<md:EntityDescriptor xmlns:md=\"urn:oasis:names:tc:SAML:2.0:metadata\" entityID=\""
                + provider.getEntityId() + "\"><md:KeyDescriptor use=\"signing\"><ds:KeyInfo xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\"><ds:X509Data><ds:X509Certificate>"
                + provider.getCertificate() + "</ds:X509Certificate></ds:X509Data></ds:KeyInfo></md:KeyDescriptor></md:EntityDescriptor>";
        return Response.ok(AwsQueryResponse.envelope("GetSAMLProvider", AwsNamespaces.IAM,
                new XmlBuilder().elem("SAMLProviderArn", provider.getArn()).elem("CreateDate", isoDate(provider.getCreateDate()))
                        .elem("ValidUntil", isoDate(provider.getCreateDate().plusSeconds(31536000))).elem("SAMLMetadataDocument", metadata)
                        .raw(tagsElement(new TreeMap<>(provider.getTags()))).build())).build();
    }

    private Response handleUpdateSAMLProvider(MultivaluedMap<String, String> params, String authorization) {
        String accountId = accountResolver.resolve(authorization);
        SAMLProvider provider = samlProviderService.update(accountId,
                getParam(params, "SAMLProviderArn"), getParam(params, "SAMLMetadataDocument"));
        return Response.ok(AwsQueryResponse.envelope("UpdateSAMLProvider", AwsNamespaces.IAM,
                new XmlBuilder().elem("SAMLProviderArn", provider.getArn()).build())).build();
    }

    private Response handleDeleteSAMLProvider(MultivaluedMap<String, String> params, String authorization) {
        String accountId = accountResolver.resolve(authorization);
        samlProviderService.delete(accountId, getParam(params, "SAMLProviderArn"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("DeleteSAMLProvider", AwsNamespaces.IAM)).build();
    }

    private Response handleTagSAMLProvider(MultivaluedMap<String, String> params, String authorization) {
        String accountId = accountResolver.resolve(authorization);
        samlProviderService.tag(accountId, getParam(params, "SAMLProviderArn"), extractTags(params, false));
        return Response.ok(AwsQueryResponse.envelopeNoResult("TagSAMLProvider", AwsNamespaces.IAM)).build();
    }

    private Response handleUntagSAMLProvider(MultivaluedMap<String, String> params, String authorization) {
        String accountId = accountResolver.resolve(authorization);
        samlProviderService.untag(accountId, getParam(params, "SAMLProviderArn"), extractTagKeys(params));
        return Response.ok(AwsQueryResponse.envelopeNoResult("UntagSAMLProvider", AwsNamespaces.IAM)).build();
    }

    private Response handleListSAMLProviderTags(MultivaluedMap<String, String> params, String authorization) {
        String accountId = accountResolver.resolve(authorization);
        Map<String, String> tags = new TreeMap<>(
                samlProviderService.listTags(accountId, getParam(params, "SAMLProviderArn")));
        String result = new XmlBuilder().start("Tags").raw(tagsXml(tags)).end("Tags")
                .elem("IsTruncated", false).build();
        return Response.ok(AwsQueryResponse.envelope("ListSAMLProviderTags", AwsNamespaces.IAM, result)).build();
    }

    // ListOpenIDConnectProviders is not paginated and carries only ARNs — the client fetches
    // the rest with GetOpenIDConnectProvider.
    private Response handleListOpenIDConnectProviders(MultivaluedMap<String, String> params) {
        XmlBuilder xml = new XmlBuilder().start("OpenIDConnectProviderList");
        for (OpenIDConnectProvider provider : iamService.listOpenIDConnectProviders()) {
            xml.start("member").elem("Arn", provider.getArn()).end("member");
        }
        xml.end("OpenIDConnectProviderList");
        return Response.ok(AwsQueryResponse.envelope("ListOpenIDConnectProviders", AwsNamespaces.IAM, xml.build())).build();
    }

    private Response handleCreateOpenIDConnectProvider(MultivaluedMap<String, String> params) {
        OpenIDConnectProvider provider = iamService.createOpenIDConnectProvider(
                getParam(params, "Url"),
                getMemberList(params, "ClientIDList"),
                getMemberList(params, "ThumbprintList"),
                extractTags(params, false));
        XmlBuilder xml = new XmlBuilder().elem("OpenIDConnectProviderArn", provider.getArn());
        if (!provider.getTags().isEmpty()) {
            xml.start("Tags").raw(tagsXml(provider.getTags())).end("Tags");
        }
        return Response.ok(AwsQueryResponse.envelope("CreateOpenIDConnectProvider", AwsNamespaces.IAM, xml.build())).build();
    }

    // The response carries no ARN: AWS echoes back the scheme-less Url, and the caller already
    // knows the ARN it asked for.
    private Response handleGetOpenIDConnectProvider(MultivaluedMap<String, String> params) {
        OpenIDConnectProvider provider =
                iamService.getOpenIDConnectProvider(getParam(params, "OpenIDConnectProviderArn"));
        XmlBuilder xml = new XmlBuilder().elem("Url", provider.getUrl());
        xml.start("ClientIDList");
        for (String clientId : provider.getClientIdList()) {
            xml.elem("member", clientId);
        }
        xml.end("ClientIDList").start("ThumbprintList");
        for (String thumbprint : provider.getThumbprintList()) {
            xml.elem("member", thumbprint);
        }
        xml.end("ThumbprintList")
                .elem("CreateDate", isoDate(provider.getCreateDate()));
        if (!provider.getTags().isEmpty()) {
            xml.start("Tags").raw(tagsXml(provider.getTags())).end("Tags");
        }
        return Response.ok(AwsQueryResponse.envelope("GetOpenIDConnectProvider", AwsNamespaces.IAM, xml.build())).build();
    }

    private Response handleDeleteOpenIDConnectProvider(MultivaluedMap<String, String> params) {
        iamService.deleteOpenIDConnectProvider(getParam(params, "OpenIDConnectProviderArn"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("DeleteOpenIDConnectProvider", AwsNamespaces.IAM)).build();
    }

    private Response handleAddClientIDToOpenIDConnectProvider(MultivaluedMap<String, String> params) {
        iamService.addClientIdToOpenIDConnectProvider(
                getParam(params, "OpenIDConnectProviderArn"), getParam(params, "ClientID"));
        return Response.ok(AwsQueryResponse.envelopeNoResult(
                "AddClientIDToOpenIDConnectProvider", AwsNamespaces.IAM)).build();
    }

    private Response handleRemoveClientIDFromOpenIDConnectProvider(MultivaluedMap<String, String> params) {
        iamService.removeClientIdFromOpenIDConnectProvider(
                getParam(params, "OpenIDConnectProviderArn"), getParam(params, "ClientID"));
        return Response.ok(AwsQueryResponse.envelopeNoResult(
                "RemoveClientIDFromOpenIDConnectProvider", AwsNamespaces.IAM)).build();
    }

    private Response handleUpdateOpenIDConnectProviderThumbprint(MultivaluedMap<String, String> params) {
        iamService.updateOpenIDConnectProviderThumbprint(
                getParam(params, "OpenIDConnectProviderArn"), getMemberList(params, "ThumbprintList"));
        return Response.ok(AwsQueryResponse.envelopeNoResult(
                "UpdateOpenIDConnectProviderThumbprint", AwsNamespaces.IAM)).build();
    }

    private Response handleTagOpenIDConnectProvider(MultivaluedMap<String, String> params) {
        iamService.tagOpenIDConnectProvider(getParam(params, "OpenIDConnectProviderArn"), extractTags(params, false));
        return Response.ok(AwsQueryResponse.envelopeNoResult("TagOpenIDConnectProvider", AwsNamespaces.IAM)).build();
    }

    private Response handleUntagOpenIDConnectProvider(MultivaluedMap<String, String> params) {
        iamService.untagOpenIDConnectProvider(
                getParam(params, "OpenIDConnectProviderArn"), extractTagKeys(params));
        return Response.ok(AwsQueryResponse.envelopeNoResult("UntagOpenIDConnectProvider", AwsNamespaces.IAM)).build();
    }

    private Response handleListOpenIDConnectProviderTags(MultivaluedMap<String, String> params) {
        Map<String, String> providerTags =
                iamService.listOpenIDConnectProviderTags(getParam(params, "OpenIDConnectProviderArn"));
        String result = new XmlBuilder()
                .start("Tags").raw(tagsXml(providerTags)).end("Tags")
                .elem("IsTruncated", false)
                .build();
        return Response.ok(AwsQueryResponse.envelope("ListOpenIDConnectProviderTags", AwsNamespaces.IAM, result)).build();
    }

    private Response handleUploadServerCertificate(MultivaluedMap<String, String> params) {
        ServerCertificate certificate = iamService.uploadServerCertificate(
                getParam(params, "ServerCertificateName"), getParam(params, "Path"),
                getParam(params, "CertificateBody"), getParam(params, "PrivateKey"),
                getParam(params, "CertificateChain"), extractTags(params, false));
        String result = new XmlBuilder()
                .start("ServerCertificateMetadata").raw(serverCertificateMetadataXml(certificate))
                .end("ServerCertificateMetadata")
                .raw(tagsElement(new TreeMap<>(certificate.getTags())))
                .build();
        return Response.ok(AwsQueryResponse.envelope("UploadServerCertificate", AwsNamespaces.IAM, result)).build();
    }

    /**
     * Returns the stored certificate and its chain, but never the private key: AWS marks
     * {@code privateKeyType} sensitive and models it only on the upload, so no reader returns it.
     */
    private Response handleGetServerCertificate(MultivaluedMap<String, String> params) {
        ServerCertificate certificate = iamService.getServerCertificate(
                getParam(params, "ServerCertificateName"));
        XmlBuilder xml = new XmlBuilder().start("ServerCertificate")
                .start("ServerCertificateMetadata").raw(serverCertificateMetadataXml(certificate))
                .end("ServerCertificateMetadata")
                .elem("CertificateBody", certificate.getCertificateBody());
        if (certificate.getCertificateChain() != null) {
            xml.elem("CertificateChain", certificate.getCertificateChain());
        }
        xml.raw(tagsElement(new TreeMap<>(certificate.getTags()))).end("ServerCertificate");
        return Response.ok(AwsQueryResponse.envelope("GetServerCertificate", AwsNamespaces.IAM, xml.build())).build();
    }

    private Response handleUpdateServerCertificate(MultivaluedMap<String, String> params) {
        iamService.updateServerCertificate(getParam(params, "ServerCertificateName"),
                getParam(params, "NewServerCertificateName"), getParam(params, "NewPath"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("UpdateServerCertificate", AwsNamespaces.IAM)).build();
    }

    private Response handleDeleteServerCertificate(MultivaluedMap<String, String> params) {
        iamService.deleteServerCertificate(getParam(params, "ServerCertificateName"),
                serverCertificateReferenceProviders.stream().toList());
        return Response.ok(AwsQueryResponse.envelopeNoResult("DeleteServerCertificate", AwsNamespaces.IAM)).build();
    }

    /**
     * The metadata list only, as AWS documents: "this operation does not return the certificate
     * body, certificate chain, or private key".
     */
    private Response handleListServerCertificates(MultivaluedMap<String, String> params) {
        Page<ServerCertificate> page = paginate(
                iamService.listServerCertificates(getParam(params, "PathPrefix")),
                ServerCertificate::getServerCertificateName, params);
        XmlBuilder xml = new XmlBuilder().start("ServerCertificateMetadataList");
        for (ServerCertificate certificate : page.items()) {
            xml.start("member").raw(serverCertificateMetadataXml(certificate)).end("member");
        }
        xml.end("ServerCertificateMetadataList").elem("IsTruncated", page.truncated());
        if (page.marker() != null) {
            xml.elem("Marker", page.marker());
        }
        return Response.ok(AwsQueryResponse.envelope("ListServerCertificates", AwsNamespaces.IAM, xml.build())).build();
    }

    /**
     * UserName is required here and optional on the other four, which is a third pattern again:
     * the signing-certificate operations take it optionally throughout and the SSH ones require it
     * everywhere but the list.
     */
    private Response handleCreateServiceSpecificCredential(
            MultivaluedMap<String, String> params) {
        ServiceSpecificCredential credential = iamService.createServiceSpecificCredential(
                requireParam(params, "UserName"), requireParam(params, "ServiceName"),
                optionalInt(params, "CredentialAgeDays"));
        XmlBuilder xml = new XmlBuilder().start("ServiceSpecificCredential")
                .raw(serviceCredentialXml(credential, true)).end("ServiceSpecificCredential");
        return Response.ok(AwsQueryResponse.envelope("CreateServiceSpecificCredential",
                AwsNamespaces.IAM, xml.build())).build();
    }

    /**
     * The metadata list, which the model defines without either secret. {@code AllUsers} cannot be
     * given together with {@code UserName}, so naming both is a validation error rather than one
     * quietly winning.
     */
    private Response handleListServiceSpecificCredentials(
            MultivaluedMap<String, String> params, String authorization) {
        boolean allUsers = "true".equalsIgnoreCase(getParam(params, "AllUsers"));
        if (allUsers && getParam(params, "UserName") != null) {
            throw new AwsException("ValidationError",
                    "AllUsers cannot be specified together with UserName.", 400);
        }
        String userName = allUsers ? null : resolveUserName(params, authorization);
        Page<ServiceSpecificCredential> page = paginate(
                iamService.listServiceSpecificCredentials(
                        userName, getParam(params, "ServiceName"), allUsers),
                ServiceSpecificCredential::getServiceSpecificCredentialId, params);
        XmlBuilder xml = new XmlBuilder().start("ServiceSpecificCredentials");
        for (ServiceSpecificCredential credential : page.items()) {
            xml.start("member").raw(serviceCredentialXml(credential, false)).end("member");
        }
        xml.end("ServiceSpecificCredentials").elem("IsTruncated", page.truncated());
        if (page.marker() != null) {
            xml.elem("Marker", page.marker());
        }
        return Response.ok(AwsQueryResponse.envelope("ListServiceSpecificCredentials",
                AwsNamespaces.IAM, xml.build())).build();
    }

    private Response handleUpdateServiceSpecificCredential(
            MultivaluedMap<String, String> params, String authorization) {
        iamService.updateServiceSpecificCredential(resolveUserName(params, authorization),
                requireParam(params, "ServiceSpecificCredentialId"), getParam(params, "Status"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("UpdateServiceSpecificCredential",
                AwsNamespaces.IAM)).build();
    }

    /** The reset is the only operation besides the create that returns the secret half. */
    private Response handleResetServiceSpecificCredential(
            MultivaluedMap<String, String> params, String authorization) {
        ServiceSpecificCredential credential = iamService.resetServiceSpecificCredential(
                resolveUserName(params, authorization),
                requireParam(params, "ServiceSpecificCredentialId"));
        XmlBuilder xml = new XmlBuilder().start("ServiceSpecificCredential")
                .raw(serviceCredentialXml(credential, true)).end("ServiceSpecificCredential");
        return Response.ok(AwsQueryResponse.envelope("ResetServiceSpecificCredential",
                AwsNamespaces.IAM, xml.build())).build();
    }

    private Response handleDeleteServiceSpecificCredential(
            MultivaluedMap<String, String> params, String authorization) {
        iamService.deleteServiceSpecificCredential(resolveUserName(params, authorization),
                requireParam(params, "ServiceSpecificCredentialId"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("DeleteServiceSpecificCredential",
                AwsNamespaces.IAM)).build();
    }

    /**
     * A credential, with the secret half only when the operation is one that discloses it. The
     * metadata shape the list uses is defined without {@code ServicePassword} or
     * {@code ServiceCredentialSecret}, so omitting them is the shape rather than a precaution.
     */
    private String serviceCredentialXml(ServiceSpecificCredential credential, boolean withSecret) {
        XmlBuilder xml = new XmlBuilder()
                .elem("UserName", credential.getUserName())
                .elem("ServiceName", credential.getServiceName())
                .elem("ServiceSpecificCredentialId", credential.getServiceSpecificCredentialId())
                .elem("Status", iamService.reportedStatus(credential))
                .elem("CreateDate", isoDate(credential.getCreateDate()));
        if (credential.getServiceUserName() != null) {
            xml.elem("ServiceUserName", credential.getServiceUserName());
        }
        if (credential.getServiceCredentialAlias() != null) {
            xml.elem("ServiceCredentialAlias", credential.getServiceCredentialAlias());
        }
        if (credential.getExpirationDate() != null) {
            xml.elem("ExpirationDate", isoDate(credential.getExpirationDate()));
        }
        if (withSecret) {
            if (credential.getServicePassword() != null) {
                xml.elem("ServicePassword", credential.getServicePassword());
            }
            if (credential.getServiceCredentialSecret() != null) {
                xml.elem("ServiceCredentialSecret", credential.getServiceCredentialSecret());
            }
        }
        return xml.build();
    }

    /** An optional integer parameter that AWS documents as having to be positive. */
    /**
     * An optional integer parameter. Absent means absent, but present and unparseable is a
     * validation error rather than a silent absence: {@code CredentialAgeDays=} would otherwise
     * create a credential that never expires, which is the opposite of what the caller asked for.
     */
    private Integer optionalInt(MultivaluedMap<String, String> params, String name) {
        String raw = getParam(params, name);
        if (raw == null) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw SmithyValidationErrors.validationError(
                    "Value '" + raw + "' at '"
                            + Character.toLowerCase(name.charAt(0)) + name.substring(1)
                            + "' failed to satisfy constraint: Member must be an integer");
        }
    }

    /**
     * UserName is required on every SSH public key operation but the list, which is the opposite
     * of the signing-certificate operations: there the model marks it optional throughout.
     */
    private Response handleUploadSshPublicKey(MultivaluedMap<String, String> params) {
        SshPublicKey key = iamService.uploadSshPublicKey(requireParam(params, "UserName"),
                getParam(params, "SSHPublicKeyBody"));
        XmlBuilder xml = new XmlBuilder()
                .start("SSHPublicKey").raw(sshPublicKeyXml(key)).end("SSHPublicKey");
        return Response.ok(AwsQueryResponse.envelope("UploadSSHPublicKey",
                AwsNamespaces.IAM, xml.build())).build();
    }

    private Response handleGetSshPublicKey(MultivaluedMap<String, String> params) {
        SshPublicKey key = iamService.getSshPublicKey(requireParam(params, "UserName"),
                requireParam(params, "SSHPublicKeyId"), getParam(params, "Encoding"));
        XmlBuilder xml = new XmlBuilder()
                .start("SSHPublicKey").raw(sshPublicKeyXml(key)).end("SSHPublicKey");
        return Response.ok(AwsQueryResponse.envelope("GetSSHPublicKey",
                AwsNamespaces.IAM, xml.build())).build();
    }

    /**
     * The metadata list only. AWS documents SSHPublicKeyMetadata as carrying the key "without the
     * key's body or fingerprint", so neither appears here even though both are stored.
     */
    private Response handleListSshPublicKeys(MultivaluedMap<String, String> params,
                                             String authorization) {
        Page<SshPublicKey> page = paginate(
                iamService.listSshPublicKeys(resolveUserName(params, authorization)),
                SshPublicKey::getSshPublicKeyId, params);
        XmlBuilder xml = new XmlBuilder().start("SSHPublicKeys");
        for (SshPublicKey key : page.items()) {
            xml.start("member")
                    .elem("UserName", key.getUserName())
                    .elem("SSHPublicKeyId", key.getSshPublicKeyId())
                    .elem("Status", key.getStatus())
                    .elem("UploadDate", isoDate(key.getUploadDate()))
                    .end("member");
        }
        xml.end("SSHPublicKeys").elem("IsTruncated", page.truncated());
        if (page.marker() != null) {
            xml.elem("Marker", page.marker());
        }
        return Response.ok(AwsQueryResponse.envelope("ListSSHPublicKeys",
                AwsNamespaces.IAM, xml.build())).build();
    }

    private Response handleUpdateSshPublicKey(MultivaluedMap<String, String> params) {
        iamService.updateSshPublicKey(requireParam(params, "UserName"),
                requireParam(params, "SSHPublicKeyId"), getParam(params, "Status"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("UpdateSSHPublicKey",
                AwsNamespaces.IAM)).build();
    }

    private Response handleDeleteSshPublicKey(MultivaluedMap<String, String> params) {
        iamService.deleteSshPublicKey(requireParam(params, "UserName"),
                requireParam(params, "SSHPublicKeyId"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("DeleteSSHPublicKey",
                AwsNamespaces.IAM)).build();
    }

    /** The five members the model marks required on SSHPublicKey, plus UploadDate. */
    private String sshPublicKeyXml(SshPublicKey key) {
        return new XmlBuilder()
                .elem("UserName", key.getUserName())
                .elem("SSHPublicKeyId", key.getSshPublicKeyId())
                .elem("Fingerprint", key.getFingerprint())
                .elem("SSHPublicKeyBody", key.getSshPublicKeyBody())
                .elem("Status", key.getStatus())
                .elem("UploadDate", isoDate(key.getUploadDate()))
                .build();
    }

    /**
     * UserName is optional on every signing-certificate operation: the model says it is determined
     * implicitly from the access key that signed the request.
     */
    private Response handleUploadSigningCertificate(MultivaluedMap<String, String> params,
                                                    String authorization) {
        SigningCertificate certificate = iamService.uploadSigningCertificate(
                resolveUserName(params, authorization), getParam(params, "CertificateBody"));
        XmlBuilder xml = new XmlBuilder()
                .start("Certificate").raw(signingCertificateXml(certificate)).end("Certificate");
        return Response.ok(AwsQueryResponse.envelope("UploadSigningCertificate",
                AwsNamespaces.IAM, xml.build())).build();
    }

    private Response handleListSigningCertificates(MultivaluedMap<String, String> params,
                                                   String authorization) {
        Page<SigningCertificate> page = paginate(
                iamService.listSigningCertificates(resolveUserName(params, authorization)),
                SigningCertificate::getCertificateId, params);
        XmlBuilder xml = new XmlBuilder().start("Certificates");
        for (SigningCertificate certificate : page.items()) {
            xml.start("member").raw(signingCertificateXml(certificate)).end("member");
        }
        xml.end("Certificates").elem("IsTruncated", page.truncated());
        if (page.marker() != null) {
            xml.elem("Marker", page.marker());
        }
        return Response.ok(AwsQueryResponse.envelope("ListSigningCertificates",
                AwsNamespaces.IAM, xml.build())).build();
    }

    private Response handleUpdateSigningCertificate(MultivaluedMap<String, String> params,
                                                    String authorization) {
        iamService.updateSigningCertificate(resolveUserName(params, authorization),
                requireParam(params, "CertificateId"), getParam(params, "Status"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("UpdateSigningCertificate",
                AwsNamespaces.IAM)).build();
    }

    private Response handleDeleteSigningCertificate(MultivaluedMap<String, String> params,
                                                    String authorization) {
        iamService.deleteSigningCertificate(resolveUserName(params, authorization),
                requireParam(params, "CertificateId"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("DeleteSigningCertificate",
                AwsNamespaces.IAM)).build();
    }

    /** The four members the model marks required, plus UploadDate. */
    private String signingCertificateXml(SigningCertificate certificate) {
        return new XmlBuilder()
                .elem("UserName", certificate.getUserName())
                .elem("CertificateId", certificate.getCertificateId())
                .elem("CertificateBody", certificate.getCertificateBody())
                .elem("Status", certificate.getStatus())
                .elem("UploadDate", isoDate(certificate.getUploadDate()))
                .build();
    }

    private Response handleTagServerCertificate(MultivaluedMap<String, String> params) {
        iamService.tagServerCertificate(getParam(params, "ServerCertificateName"), extractTags(params, false));
        return Response.ok(AwsQueryResponse.envelopeNoResult("TagServerCertificate", AwsNamespaces.IAM)).build();
    }

    private Response handleUntagServerCertificate(MultivaluedMap<String, String> params) {
        iamService.untagServerCertificate(getParam(params, "ServerCertificateName"), extractTagKeys(params));
        return Response.ok(AwsQueryResponse.envelopeNoResult("UntagServerCertificate", AwsNamespaces.IAM)).build();
    }

    private Response handleListServerCertificateTags(MultivaluedMap<String, String> params) {
        Map<String, String> tags = new TreeMap<>(iamService.listServerCertificateTags(
                getParam(params, "ServerCertificateName")));
        Page<Map.Entry<String, String>> page = paginate(List.copyOf(tags.entrySet()), Map.Entry::getKey, params);
        XmlBuilder xml = new XmlBuilder().start("Tags");
        for (Map.Entry<String, String> tag : page.items()) {
            xml.start("member").elem("Key", tag.getKey()).elem("Value", tag.getValue()).end("member");
        }
        xml.end("Tags").elem("IsTruncated", page.truncated());
        if (page.marker() != null) {
            xml.elem("Marker", page.marker());
        }
        return Response.ok(AwsQueryResponse.envelope("ListServerCertificateTags", AwsNamespaces.IAM, xml.build())).build();
    }

    private String serverCertificateMetadataXml(ServerCertificate certificate) {
        return new XmlBuilder()
                .elem("Path", certificate.getPath())
                .elem("ServerCertificateName", certificate.getServerCertificateName())
                .elem("ServerCertificateId", certificate.getServerCertificateId())
                .elem("Arn", certificate.getArn())
                .elem("UploadDate", isoDate(certificate.getUploadDate()))
                .elem("Expiration", isoDate(certificate.getExpiration()))
                .build();
    }

    // =========================================================================
    // Account Aliases
    // =========================================================================

    // ListAccountAliases is paginated on the wire (IsTruncated) even though an account can only
    // ever hold one alias, so the envelope carries the flag to match the AWS response shape.
    private Response handleListAccountAliases(MultivaluedMap<String, String> params) {
        XmlBuilder xml = new XmlBuilder().start("AccountAliases");
        iamService.getAccountAlias().ifPresent(alias -> xml.elem("member", alias));
        xml.end("AccountAliases").elem("IsTruncated", false);
        return Response.ok(AwsQueryResponse.envelope("ListAccountAliases", AwsNamespaces.IAM, xml.build())).build();
    }

    private Response handleCreateAccountAlias(MultivaluedMap<String, String> params) {
        iamService.createAccountAlias(getParam(params, "AccountAlias"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("CreateAccountAlias", AwsNamespaces.IAM)).build();
    }

    private Response handleDeleteAccountAlias(MultivaluedMap<String, String> params) {
        iamService.deleteAccountAlias(getParam(params, "AccountAlias"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("DeleteAccountAlias", AwsNamespaces.IAM)).build();
    }

    // =========================================================================
    // Account Password Policy
    // =========================================================================

    // AWS raises NoSuchEntity (404) when the account has never had a policy set — a documented,
    // expected result, the same shape GetLoginProfile answers when a user has no console password.
    private Response handleGetAccountPasswordPolicy(MultivaluedMap<String, String> params) {
        AccountPasswordPolicy policy = iamService.getAccountPasswordPolicy()
                .orElse(null);
        if (policy == null) {
            return AwsQueryResponse.error("NoSuchEntity",
                    "The account policy with name PasswordPolicy cannot be found.", AwsNamespaces.IAM, 404);
        }
        return Response.ok(AwsQueryResponse.envelope(
                "GetAccountPasswordPolicy", AwsNamespaces.IAM, passwordPolicyXml(policy))).build();
    }

    private Response handleUpdateAccountPasswordPolicy(MultivaluedMap<String, String> params) {
        AccountPasswordPolicy policy = new AccountPasswordPolicy();
        policy.setMinimumPasswordLength(getStrictIntParam(params, "MinimumPasswordLength", 6));
        policy.setRequireSymbols(getBooleanParam(params, "RequireSymbols", false));
        policy.setRequireNumbers(getBooleanParam(params, "RequireNumbers", false));
        policy.setRequireUppercaseCharacters(getBooleanParam(params, "RequireUppercaseCharacters", false));
        policy.setRequireLowercaseCharacters(getBooleanParam(params, "RequireLowercaseCharacters", false));
        policy.setAllowUsersToChangePassword(getBooleanParam(params, "AllowUsersToChangePassword", false));
        policy.setMaxPasswordAge(getOptionalIntParam(params, "MaxPasswordAge"));
        policy.setPasswordReusePrevention(getOptionalIntParam(params, "PasswordReusePrevention"));
        policy.setHardExpiry(getBooleanParam(params, "HardExpiry", false));
        iamService.updateAccountPasswordPolicy(policy);
        return Response.ok(AwsQueryResponse.envelopeNoResult("UpdateAccountPasswordPolicy", AwsNamespaces.IAM))
                .build();
    }

    private Response handleDeleteAccountPasswordPolicy(MultivaluedMap<String, String> params) {
        iamService.deleteAccountPasswordPolicy();
        return Response.ok(AwsQueryResponse.envelopeNoResult("DeleteAccountPasswordPolicy", AwsNamespaces.IAM))
                .build();
    }

    private String passwordPolicyXml(AccountPasswordPolicy policy) {
        XmlBuilder xml = new XmlBuilder().start("PasswordPolicy")
                .elem("MinimumPasswordLength", policy.getMinimumPasswordLength())
                .elem("RequireSymbols", policy.isRequireSymbols())
                .elem("RequireNumbers", policy.isRequireNumbers())
                .elem("RequireUppercaseCharacters", policy.isRequireUppercaseCharacters())
                .elem("RequireLowercaseCharacters", policy.isRequireLowercaseCharacters())
                .elem("AllowUsersToChangePassword", policy.isAllowUsersToChangePassword())
                .elem("ExpirePasswords", policy.isExpirePasswords())
                .elem("HardExpiry", policy.isHardExpiry());
        if (policy.getMaxPasswordAge() != null) {
            xml.elem("MaxPasswordAge", policy.getMaxPasswordAge());
        }
        if (policy.getPasswordReusePrevention() != null) {
            xml.elem("PasswordReusePrevention", policy.getPasswordReusePrevention());
        }
        return xml.end("PasswordPolicy").build();
    }

    /** Unlike {@link #getIntParam}, absence is meaningful here — it must not collapse to a default. */
    private Integer getOptionalIntParam(MultivaluedMap<String, String> params, String name) {
        String value = params.getFirst(name);
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new AwsException("ValidationError", "Invalid value for " + name + ": " + value, 400);
        }
    }

    // Unlike getIntParam's silent fallback, an integer parameter that is present but not
    // parseable is a caller mistake, not an absence — it must be rejected rather than coerced
    // to the default.
    private int getStrictIntParam(MultivaluedMap<String, String> params, String name, int defaultValue) {
        String value = params.getFirst(name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new AwsException("ValidationError", "Invalid value for " + name + ": " + value, 400);
        }
    }

    // Unlike getIntParam's silent fallback, a boolean parameter that is present but not "true"/
    // "false" is a caller mistake, not an absence — it must be rejected rather than coerced to false.
    private boolean getBooleanParam(MultivaluedMap<String, String> params, String name, boolean defaultValue) {
        String value = params.getFirst(name);
        if (value == null) {
            return defaultValue;
        }
        return parseStrictBoolean(name, value);
    }

    /** Unlike {@link #getBooleanParam}, absence is meaningful here: it must not collapse to a default. */
    private Boolean getOptionalBooleanParam(MultivaluedMap<String, String> params, String name) {
        String value = params.getFirst(name);
        if (value == null) {
            return null;
        }
        return parseStrictBoolean(name, value);
    }

    private boolean parseStrictBoolean(String name, String value) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new AwsException("ValidationError", "Invalid value for " + name + ": " + value, 400);
    }

    // =========================================================================
    // Groups
    // =========================================================================

    private Response handleCreateGroup(MultivaluedMap<String, String> params) {
        String groupName = getParam(params, "GroupName");
        String path = getParam(params, "Path");
        IamGroup group = iamService.createGroup(groupName, path);
        String result = new XmlBuilder().start("Group").raw(groupXml(group)).end("Group").build();
        return Response.ok(AwsQueryResponse.envelope("CreateGroup", AwsNamespaces.IAM, result)).build();
    }

    private Response handleGetGroup(MultivaluedMap<String, String> params) {
        String groupName = getParam(params, "GroupName");
        IamGroup group = iamService.getGroup(groupName);
        List<IamUser> members = group.getUserNames().stream()
                .flatMap(un -> {
                    try {
                        return Stream.of(iamService.getUser(un));
                    } catch (AwsException e) {
                        return Stream.empty();
                    }
                }).toList();
        XmlBuilder xml = new XmlBuilder()
                .start("Group").raw(groupXml(group)).end("Group")
                .start("Users");
        for (IamUser u : members) {
            xml.start("member").raw(userXml(u, false)).end("member");
        }
        xml.end("Users").elem("IsTruncated", false);
        return Response.ok(AwsQueryResponse.envelope("GetGroup", AwsNamespaces.IAM, xml.build())).build();
    }

    private Response handleUpdateGroup(MultivaluedMap<String, String> params) {
        iamService.updateGroup(getParam(params, "GroupName"),
                getParam(params, "NewGroupName"), getParam(params, "NewPath"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("UpdateGroup", AwsNamespaces.IAM)).build();
    }

    private Response handleDeleteGroup(MultivaluedMap<String, String> params) {
        iamService.deleteGroup(getParam(params, "GroupName"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("DeleteGroup", AwsNamespaces.IAM)).build();
    }

    private Response handleListGroups(MultivaluedMap<String, String> params) {
        List<IamGroup> groupList = iamService.listGroups(getParam(params, "PathPrefix"));
        XmlBuilder xml = new XmlBuilder().start("Groups");
        for (IamGroup g : groupList) {
            xml.start("member").raw(groupXml(g)).end("member");
        }
        xml.end("Groups").elem("IsTruncated", false);
        return Response.ok(AwsQueryResponse.envelope("ListGroups", AwsNamespaces.IAM, xml.build())).build();
    }

    private Response handleAddUserToGroup(MultivaluedMap<String, String> params) {
        iamService.addUserToGroup(getParam(params, "GroupName"), getParam(params, "UserName"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("AddUserToGroup", AwsNamespaces.IAM)).build();
    }

    private Response handleRemoveUserFromGroup(MultivaluedMap<String, String> params) {
        iamService.removeUserFromGroup(getParam(params, "GroupName"), getParam(params, "UserName"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("RemoveUserFromGroup", AwsNamespaces.IAM)).build();
    }

    private Response handleListGroupsForUser(MultivaluedMap<String, String> params) {
        List<IamGroup> groupList = iamService.listGroupsForUser(getParam(params, "UserName"));
        XmlBuilder xml = new XmlBuilder().start("Groups");
        for (IamGroup g : groupList) {
            xml.start("member").raw(groupXml(g)).end("member");
        }
        xml.end("Groups").elem("IsTruncated", false);
        return Response.ok(AwsQueryResponse.envelope("ListGroupsForUser", AwsNamespaces.IAM, xml.build())).build();
    }

    // =========================================================================
    // Roles
    // =========================================================================

    private Response handleCreateRole(MultivaluedMap<String, String> params) {
        String roleName = getParam(params, "RoleName");
        String path = getParam(params, "Path");
        String trustPolicy = getParam(params, "AssumeRolePolicyDocument");
        String description = getParam(params, "Description");
        int maxSession = getIntParam(params, "MaxSessionDuration", 3600);
        Map<String, String> tags = extractTags(params, true);
        IamRole role = iamService.createRole(roleName, path, trustPolicy, description, maxSession, tags,
                getParam(params, "PermissionsBoundary"));
        String result = new XmlBuilder().start("Role").raw(roleXml(role, true)).end("Role").build();
        return Response.ok(AwsQueryResponse.envelope("CreateRole", AwsNamespaces.IAM, result)).build();
    }

    private Response handleGetRole(MultivaluedMap<String, String> params) {
        IamRole role = iamService.getValidatedRole(getParam(params, "RoleName"));
        String result = new XmlBuilder().start("Role").raw(roleXml(role, true)).end("Role").build();
        return Response.ok(AwsQueryResponse.envelope("GetRole", AwsNamespaces.IAM, result)).build();
    }

    private Response handleDeleteRole(MultivaluedMap<String, String> params) {
        iamService.deleteRole(getParam(params, "RoleName"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("DeleteRole", AwsNamespaces.IAM)).build();
    }

    private Response handleCreateServiceLinkedRole(MultivaluedMap<String, String> params) {
        IamRole role = iamService.createServiceLinkedRole(
                getParam(params, "AWSServiceName"),
                getParam(params, "CustomSuffix"),
                getParam(params, "Description"));
        String result = new XmlBuilder().start("Role").raw(roleXml(role, true)).end("Role").build();
        return Response.ok(AwsQueryResponse.envelope("CreateServiceLinkedRole", AwsNamespaces.IAM, result)).build();
    }

    private Response handleDeleteServiceLinkedRole(MultivaluedMap<String, String> params) {
        String deletionTaskId = iamService.deleteServiceLinkedRole(getParam(params, "RoleName"));
        String result = new XmlBuilder().elem("DeletionTaskId", deletionTaskId).build();
        return Response.ok(AwsQueryResponse.envelope("DeleteServiceLinkedRole", AwsNamespaces.IAM, result)).build();
    }

    private Response handleGetServiceLinkedRoleDeletionStatus(MultivaluedMap<String, String> params) {
        String status = iamService.getServiceLinkedRoleDeletionStatus(getParam(params, "DeletionTaskId"));
        String result = new XmlBuilder().elem("Status", status).build();
        return Response.ok(AwsQueryResponse.envelope("GetServiceLinkedRoleDeletionStatus", AwsNamespaces.IAM, result)).build();
    }

    private Response handleListRoles(MultivaluedMap<String, String> params) {
        Page<IamRole> page = paginate(iamService.listRoles(getParam(params, "PathPrefix")), IamRole::getRoleName, params);
        XmlBuilder xml = new XmlBuilder().start("Roles");
        for (IamRole r : page.items()) {
            xml.start("member").raw(roleXml(r, false)).end("member");
        }
        xml.end("Roles").elem("IsTruncated", page.truncated());
        if (page.marker() != null) {
            xml.elem("Marker", page.marker());
        }
        return Response.ok(AwsQueryResponse.envelope("ListRoles", AwsNamespaces.IAM, xml.build())).build();
    }

    private Response handleUpdateRole(MultivaluedMap<String, String> params) {
        iamService.updateRole(getParam(params, "RoleName"),
                getParam(params, "Description"),
                getIntParam(params, "MaxSessionDuration", 0));
        return Response.ok(AwsQueryResponse.envelopeNoResult("UpdateRole", AwsNamespaces.IAM)).build();
    }

    /**
     * The description half of UpdateRole, which AWS's own reference opens by telling callers to use
     * instead. Both members are required here, where UpdateRole's are optional, and the modified
     * role comes back, which UpdateRole does not answer at all.
     */
    private Response handleUpdateRoleDescription(MultivaluedMap<String, String> params) {
        // Read rather than requireParam: roleDescriptionType declares no minimum length, so an
        // empty Description is a value that clears the description, not an absent member.
        String description = params.getFirst("Description");
        if (description == null) {
            throw SmithyValidationErrors.validationError(
                    SmithyValidationErrors.notNull("description"));
        }
        IamRole role = iamService.updateRoleDescription(getParam(params, "RoleName"), description);
        String result = new XmlBuilder().start("Role").raw(roleXml(role, true)).end("Role").build();
        return Response.ok(AwsQueryResponse.envelope("UpdateRoleDescription", AwsNamespaces.IAM, result)).build();
    }

    private Response handleUpdateAssumeRolePolicy(MultivaluedMap<String, String> params) {
        iamService.updateAssumeRolePolicy(requireParam(params, "RoleName"),
                requireParam(params, "PolicyDocument"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("UpdateAssumeRolePolicy", AwsNamespaces.IAM)).build();
    }

    private Response handleTagRole(MultivaluedMap<String, String> params) {
        iamService.tagRole(getParam(params, "RoleName"), extractTags(params, true));
        return Response.ok(AwsQueryResponse.envelopeNoResult("TagRole", AwsNamespaces.IAM)).build();
    }

    private Response handleUntagRole(MultivaluedMap<String, String> params) {
        iamService.untagRole(getParam(params, "RoleName"), extractTagKeys(params));
        return Response.ok(AwsQueryResponse.envelopeNoResult("UntagRole", AwsNamespaces.IAM)).build();
    }

    private Response handleListRoleTags(MultivaluedMap<String, String> params) {
        Map<String, String> tags = iamService.listRoleTags(getParam(params, "RoleName"));
        String result = new XmlBuilder().start("Tags").raw(tagsXml(tags)).end("Tags")
                .elem("IsTruncated", false).build();
        return Response.ok(AwsQueryResponse.envelope("ListRoleTags", AwsNamespaces.IAM, result)).build();
    }

    // =========================================================================
    // Role templates
    // =========================================================================

    /**
     * {@code GetRoleTemplateVersion} reads a template AWS publishes, and the model has no
     * operation that creates, lists, updates or deletes one, so no account holds a template and
     * {@code NoSuchEntity} is the only answer. The user guide has role manager using "AWS-managed
     * role templates" and a role created from one "with the {@code iam:AcquireRole} API with the
     * template's ARN", so a template is AWS's rather than the customer's, and what the guide
     * publishes is a directory of template ARNs rather than their bodies: there is nothing
     * recorded to answer with.
     *
     * <p>{@code TemplateArn} is held to its declared length first and {@code MinorVersion} is read
     * as the integer it is declared to be, so a request malformed in either is the 400 ahead of
     * the lookup rather than this 404.
     */
    private Response handleGetRoleTemplateVersion(MultivaluedMap<String, String> params) {
        String templateArn = requiredArn(params, "TemplateArn");
        requireIntegerIfPresent(params, "MinorVersion");
        throw noSuchRoleTemplate(templateArn);
    }

    /**
     * {@code AcquireRole} creates a role from a template, and there is never a template to create
     * it from, for the reason {@link #handleGetRoleTemplateVersion} gives, so this answers
     * {@code NoSuchEntity} for the ARN it was handed.
     *
     * <p>{@code RoleTemplateDisabled} is the other refusal the operation models and it is not this
     * one: it reads "the specified role template is disabled", which is an answer about a template
     * that exists, and role manager's own switch, the {@code RoleManager/Enabled} account property,
     * is a different question again. An ARN naming no template is the 404.
     */
    private Response handleAcquireRole(MultivaluedMap<String, String> params) {
        String templateArn = requiredArn(params, "TemplateArn");
        requireIntegerIfPresent(params, "TemplateMinorVersion");
        requireReplacementValuesWithinDeclaredSize(params);
        throw noSuchRoleTemplate(templateArn);
    }

    /**
     * {@code ReplacementValues} is declared with at most 30 entries, and a Query map arrives as
     * {@code ReplacementValues.entry.N.key}, so the keys are counted rather than the indices
     * walked: a caller numbering them sparsely still sends as many values as it sends.
     */
    private static void requireReplacementValuesWithinDeclaredSize(
            MultivaluedMap<String, String> params) {
        long entries = params.keySet().stream()
                .filter(name -> name.startsWith("ReplacementValues.entry.") && name.endsWith(".key"))
                .count();
        if (entries > REPLACEMENT_VALUES_MAX_ENTRIES) {
            throw SmithyValidationErrors.validationError("Value at 'replacementValues' failed to "
                    + "satisfy constraint: Member must have length less than or equal to "
                    + REPLACEMENT_VALUES_MAX_ENTRIES);
        }
    }

    private static AwsException noSuchRoleTemplate(String templateArn) {
        return new AwsException("NoSuchEntity",
                "The role template with ARN " + templateArn + " cannot be found.", 404);
    }

    // =========================================================================
    // Managed Policies
    // =========================================================================

    private Response handleCreatePolicy(MultivaluedMap<String, String> params) {
        String policyName = getParam(params, "PolicyName");
        String path = getParam(params, "Path");
        String description = getParam(params, "Description");
        String document = getParam(params, "PolicyDocument");
        Map<String, String> tags = extractTags(params, false);
        IamPolicy policy = iamService.createPolicy(policyName, path, description, document, tags);
        String result = new XmlBuilder().start("Policy").raw(policyXml(policy, true)).end("Policy").build();
        return Response.ok(AwsQueryResponse.envelope("CreatePolicy", AwsNamespaces.IAM, result)).build();
    }

    private Response handleGetPolicy(MultivaluedMap<String, String> params) {
        IamPolicy policy = iamService.getPolicy(getParam(params, "PolicyArn"));
        String result = new XmlBuilder().start("Policy").raw(policyXml(policy, true)).end("Policy").build();
        return Response.ok(AwsQueryResponse.envelope("GetPolicy", AwsNamespaces.IAM, result)).build();
    }

    private Response handleDeletePolicy(MultivaluedMap<String, String> params) {
        iamService.deletePolicy(getParam(params, "PolicyArn"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("DeletePolicy", AwsNamespaces.IAM)).build();
    }

    private Response handleListPolicies(MultivaluedMap<String, String> params) {
        Page<IamPolicy> page = paginate(iamService.listPolicies(
                getParam(params, "Scope"), getParam(params, "PathPrefix")), IamQueryHandler::policyKey, params);
        XmlBuilder xml = new XmlBuilder().start("Policies");
        for (IamPolicy p : page.items()) {
            xml.start("member").raw(policyXml(p, false)).end("member");
        }
        xml.end("Policies").elem("IsTruncated", page.truncated());
        if (page.marker() != null) {
            xml.elem("Marker", page.marker());
        }
        return Response.ok(AwsQueryResponse.envelope("ListPolicies", AwsNamespaces.IAM, xml.build())).build();
    }

    private Response handleListEntitiesForPolicy(MultivaluedMap<String, String> params) {
        IamService.PolicyEntities entities = iamService.listEntitiesForPolicy(getParam(params, "PolicyArn"));
        XmlBuilder xml = new XmlBuilder().start("PolicyGroups");
        for (IamGroup group : entities.groups()) {
            xml.start("member").elem("GroupName", group.getGroupName())
                    .elem("GroupId", group.getGroupId()).end("member");
        }
        xml.end("PolicyGroups").start("PolicyUsers");
        for (IamUser user : entities.users()) {
            xml.start("member").elem("UserName", user.getUserName())
                    .elem("UserId", user.getUserId()).end("member");
        }
        xml.end("PolicyUsers").start("PolicyRoles");
        for (IamRole role : entities.roles()) {
            xml.start("member").elem("RoleName", role.getRoleName())
                    .elem("RoleId", role.getRoleId()).end("member");
        }
        xml.end("PolicyRoles").elem("IsTruncated", false);
        return Response.ok(AwsQueryResponse.envelope("ListEntitiesForPolicy", AwsNamespaces.IAM, xml.build())).build();
    }

    private Response handleGetAccountSummary(MultivaluedMap<String, String> params) {
        XmlBuilder xml = new XmlBuilder().start("SummaryMap");
        for (Map.Entry<String, Long> entry : iamService.getAccountSummary().entrySet()) {
            xml.start("entry").elem("key", entry.getKey()).elem("value", entry.getValue()).end("entry");
        }
        xml.end("SummaryMap");
        return Response.ok(AwsQueryResponse.envelope("GetAccountSummary", AwsNamespaces.IAM, xml.build())).build();
    }

    private Response handleGetAccountProperties(MultivaluedMap<String, String> params) {
        XmlBuilder xml = new XmlBuilder().start("Properties");
        for (Map.Entry<String, String> entry : iamService.getAccountProperties().entrySet()) {
            xml.start("entry").elem("key", entry.getKey())
                    .elem("value", entry.getValue()).end("entry");
        }
        xml.end("Properties");
        return Response.ok(AwsQueryResponse.envelope("GetAccountProperties",
                AwsNamespaces.IAM, xml.build())).build();
    }

    /**
     * The Properties map arrives as {@code Properties.entry.N.key} and
     * {@code Properties.entry.N.value}, which is how the API Reference spells the member.
     */
    private Response handlePutAccountProperties(MultivaluedMap<String, String> params) {
        Map<String, String> properties = new LinkedHashMap<>();
        for (int i = 1; ; i++) {
            String key = getParam(params, "Properties.entry." + i + ".key");
            if (key == null) {
                break;
            }
            properties.put(key, getParam(params, "Properties.entry." + i + ".value"));
        }
        iamService.putAccountProperties(properties);
        return Response.ok(AwsQueryResponse.envelopeNoResult("PutAccountProperties",
                AwsNamespaces.IAM)).build();
    }

    private Response handleSetSecurityTokenServicePreferences(
            MultivaluedMap<String, String> params) {
        iamService.setSecurityTokenServicePreferences(
                requireParam(params, "GlobalEndpointTokenVersion"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("SetSecurityTokenServicePreferences",
                AwsNamespaces.IAM)).build();
    }

    private Response handleEnableOutboundWebIdentityFederation(
            MultivaluedMap<String, String> params) {
        XmlBuilder xml = new XmlBuilder()
                .elem("IssuerIdentifier", iamService.enableOutboundWebIdentityFederation());
        return Response.ok(AwsQueryResponse.envelope("EnableOutboundWebIdentityFederation",
                AwsNamespaces.IAM, xml.build())).build();
    }

    private Response handleDisableOutboundWebIdentityFederation(
            MultivaluedMap<String, String> params) {
        iamService.disableOutboundWebIdentityFederation();
        return Response.ok(AwsQueryResponse.envelopeNoResult(
                "DisableOutboundWebIdentityFederation", AwsNamespaces.IAM)).build();
    }

    private Response handleGetOutboundWebIdentityFederationInfo(
            MultivaluedMap<String, String> params) {
        OutboundWebIdentityFederation state = iamService.getOutboundWebIdentityFederationInfo();
        XmlBuilder xml = new XmlBuilder()
                .elem("IssuerIdentifier", state.getIssuerIdentifier())
                // Vending is on whenever the feature is: nothing in the API toggles it apart.
                .elem("JwtVendingEnabled", true);
        return Response.ok(AwsQueryResponse.envelope("GetOutboundWebIdentityFederationInfo",
                AwsNamespaces.IAM, xml.build())).build();
    }

    private Response handleGenerateCredentialReport(MultivaluedMap<String, String> params) {
        IamService.CredentialReportGeneration generation = iamService.generateCredentialReport();
        // State before Description: GenerateCredentialReportResponse's member order in the
        // wire model, and the order AWS's own documented example emits them in.
        String result = new XmlBuilder()
                .elem("State", generation.state())
                .elem("Description", generation.description())
                .build();
        return Response.ok(AwsQueryResponse.envelope("GenerateCredentialReport", AwsNamespaces.IAM, result)).build();
    }

    private Response handleGetCredentialReport(MultivaluedMap<String, String> params) {
        IamService.CredentialReportContent content = iamService.getCredentialReport();
        String result = new XmlBuilder()
                .elem("Content", content.base64Content())
                .elem("ReportFormat", content.reportFormat())
                .elem("GeneratedTime", isoDate(content.generatedTime()))
                .build();
        return Response.ok(AwsQueryResponse.envelope("GetCredentialReport", AwsNamespaces.IAM, result)).build();
    }

    // Filter, MaxItems and Marker are not honored: every user, group, role and relevant policy
    // is always returned in one response, matching this handler's general convention elsewhere
    // (ListInstanceProfiles, SimulatePrincipalPolicy, ...) of not implementing pagination.
    private Response handleGetAccountAuthorizationDetails(MultivaluedMap<String, String> params) {
        IamService.AccountAuthorizationDetails details = iamService.getAccountAuthorizationDetails();

        XmlBuilder xml = new XmlBuilder().start("UserDetailList");
        for (IamUser user : details.users()) {
            xml.start("member").raw(userDetailXml(user)).end("member");
        }
        xml.end("UserDetailList").start("GroupDetailList");
        for (IamGroup group : details.groups()) {
            xml.start("member").raw(groupDetailXml(group)).end("member");
        }
        xml.end("GroupDetailList").start("RoleDetailList");
        for (IamRole role : details.roles()) {
            xml.start("member").raw(roleDetailXml(role)).end("member");
        }
        xml.end("RoleDetailList").start("Policies");
        for (IamPolicy policy : details.policies()) {
            xml.start("member")
               .raw(managedPolicyDetailXml(policy, details.attachmentCounts(), details.permissionsBoundaryUsageCounts()))
               .end("member");
        }
        xml.end("Policies").elem("IsTruncated", false);
        return Response.ok(AwsQueryResponse.envelope("GetAccountAuthorizationDetails", AwsNamespaces.IAM, xml.build())).build();
    }

    private String userDetailXml(IamUser u) {
        XmlBuilder xml = new XmlBuilder()
                .raw(userXml(u, true))
                .raw(policyDetailListXml("UserPolicyList", u.getInlinePolicies()))
                .start("GroupList");
        for (String groupName : u.getGroupNames()) {
            xml.elem("member", groupName);
        }
        return xml.end("GroupList")
                .raw(attachedManagedPoliciesXml(iamService.listAttachedUserPolicies(u.getUserName(), null)))
                .build();
    }

    private String groupDetailXml(IamGroup g) {
        return new XmlBuilder()
                .raw(groupXml(g))
                .raw(policyDetailListXml("GroupPolicyList", g.getInlinePolicies()))
                .raw(attachedManagedPoliciesXml(iamService.listAttachedGroupPolicies(g.getGroupName(), null)))
                .build();
    }

    // RoleDetail is not the same subset as ListRoles's Role type: it carries
    // AssumeRolePolicyDocument (which ListRoles also carries) but, per the wire model, never
    // MaxSessionDuration or Description, so this does not reuse roleXml's general fragment.
    private String roleDetailXml(IamRole r) {
        XmlBuilder xml = new XmlBuilder()
                .elem("Path", r.getPath())
                .elem("RoleName", r.getRoleName())
                .elem("RoleId", r.getRoleId())
                .elem("Arn", r.getArn())
                .elem("CreateDate", isoDate(r.getCreateDate()))
                .elem("AssumeRolePolicyDocument", r.getAssumeRolePolicyDocument())
                .raw(tagsElement(r.getTags()))
                .start("InstanceProfileList");
        for (InstanceProfile profile : iamService.listInstanceProfilesForRole(r.getRoleName())) {
            xml.start("member").raw(instanceProfileXml(profile, true)).end("member");
        }
        return xml.end("InstanceProfileList")
                .raw(policyDetailListXml("RolePolicyList", r.getInlinePolicies()))
                .raw(attachedManagedPoliciesXml(iamService.listAttachedRolePolicies(r.getRoleName(), null)))
                .raw(permissionsBoundaryXml(r.getPermissionsBoundaryArn()))
                .build();
    }

    // AttachmentCount and PermissionsBoundaryUsageCount come from the caller's account-scoped
    // scan (see IamService.getAccountAuthorizationDetails), not IamPolicy.getAttachmentCount():
    // for an AWS-managed policy that field is shared process-wide across every account.
    private String managedPolicyDetailXml(IamPolicy p, Map<String, Integer> attachmentCounts,
                                           Map<String, Integer> permissionsBoundaryUsageCounts) {
        XmlBuilder xml = new XmlBuilder()
                .elem("PolicyName", p.getPolicyName())
                .elem("PolicyId", p.getPolicyId())
                .elem("Arn", p.getArn())
                .elem("Path", p.getPath())
                .elem("DefaultVersionId", p.getDefaultVersionId())
                .elem("AttachmentCount", (long) attachmentCounts.getOrDefault(p.getArn(), 0))
                .elem("PermissionsBoundaryUsageCount",
                        (long) permissionsBoundaryUsageCounts.getOrDefault(p.getArn(), 0))
                .elem("IsAttachable", true)
                .elem("Description", p.getDescription())
                .elem("CreateDate", isoDate(p.getCreateDate()))
                .elem("UpdateDate", isoDate(p.getUpdateDate()))
                .start("PolicyVersionList");
        for (PolicyVersion version : p.getVersions().values()) {
            xml.start("member").raw(policyVersionXml(version)).end("member");
        }
        return xml.end("PolicyVersionList").build();
    }

    private String policyDetailListXml(String wrapperName, Map<String, String> inlinePolicies) {
        XmlBuilder xml = new XmlBuilder().start(wrapperName);
        for (Map.Entry<String, String> entry : inlinePolicies.entrySet()) {
            xml.start("member")
               .elem("PolicyName", entry.getKey())
               .elem("PolicyDocument", entry.getValue())
               .end("member");
        }
        return xml.end(wrapperName).build();
    }

    private String attachedManagedPoliciesXml(List<IamPolicy> attached) {
        XmlBuilder xml = new XmlBuilder().start("AttachedManagedPolicies");
        for (IamPolicy p : attached) {
            xml.start("member")
               .elem("PolicyName", p.getPolicyName())
               .elem("PolicyArn", p.getArn())
               .end("member");
        }
        return xml.end("AttachedManagedPolicies").build();
    }

    private String permissionsBoundaryXml(String boundaryArn) {
        if (boundaryArn == null) {
            return "";
        }
        return new XmlBuilder().start("PermissionsBoundary")
                .elem("PermissionsBoundaryType", "Policy")
                .elem("PermissionsBoundaryArn", boundaryArn)
                .end("PermissionsBoundary")
                .build();
    }

    private Response handleCreatePolicyVersion(MultivaluedMap<String, String> params) {
        String policyArn = getParam(params, "PolicyArn");
        String document = getParam(params, "PolicyDocument");
        boolean setAsDefault = "true".equalsIgnoreCase(getParam(params, "SetAsDefault"));
        PolicyVersion version = iamService.createPolicyVersion(policyArn, document, setAsDefault);
        String result = new XmlBuilder().start("PolicyVersion").raw(policyVersionXml(version)).end("PolicyVersion").build();
        return Response.ok(AwsQueryResponse.envelope("CreatePolicyVersion", AwsNamespaces.IAM, result)).build();
    }

    private Response handleGetPolicyVersion(MultivaluedMap<String, String> params) {
        PolicyVersion version = iamService.getPolicyVersion(
                getParam(params, "PolicyArn"), getParam(params, "VersionId"));
        String result = new XmlBuilder().start("PolicyVersion").raw(policyVersionXml(version)).end("PolicyVersion").build();
        return Response.ok(AwsQueryResponse.envelope("GetPolicyVersion", AwsNamespaces.IAM, result)).build();
    }

    private Response handleDeletePolicyVersion(MultivaluedMap<String, String> params) {
        iamService.deletePolicyVersion(getParam(params, "PolicyArn"), getParam(params, "VersionId"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("DeletePolicyVersion", AwsNamespaces.IAM)).build();
    }

    private Response handleListPolicyVersions(MultivaluedMap<String, String> params) {
        List<PolicyVersion> versions = iamService.listPolicyVersions(getParam(params, "PolicyArn"));
        XmlBuilder xml = new XmlBuilder().start("Versions");
        for (PolicyVersion v : versions) {
            xml.start("member").raw(policyVersionXml(v)).end("member");
        }
        xml.end("Versions").elem("IsTruncated", false);
        return Response.ok(AwsQueryResponse.envelope("ListPolicyVersions", AwsNamespaces.IAM, xml.build())).build();
    }

    private Response handleSetDefaultPolicyVersion(MultivaluedMap<String, String> params) {
        iamService.setDefaultPolicyVersion(getParam(params, "PolicyArn"), getParam(params, "VersionId"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("SetDefaultPolicyVersion", AwsNamespaces.IAM)).build();
    }

    private Response handleTagPolicy(MultivaluedMap<String, String> params) {
        iamService.tagPolicy(getParam(params, "PolicyArn"), extractTags(params, false));
        return Response.ok(AwsQueryResponse.envelopeNoResult("TagPolicy", AwsNamespaces.IAM)).build();
    }

    private Response handleUntagPolicy(MultivaluedMap<String, String> params) {
        iamService.untagPolicy(getParam(params, "PolicyArn"), extractTagKeys(params));
        return Response.ok(AwsQueryResponse.envelopeNoResult("UntagPolicy", AwsNamespaces.IAM)).build();
    }

    private Response handleListPolicyTags(MultivaluedMap<String, String> params) {
        Map<String, String> tags = iamService.listPolicyTags(getParam(params, "PolicyArn"));
        String result = new XmlBuilder().start("Tags").raw(tagsXml(tags)).end("Tags")
                .elem("IsTruncated", false).build();
        return Response.ok(AwsQueryResponse.envelope("ListPolicyTags", AwsNamespaces.IAM, result)).build();
    }

    // =========================================================================
    // Policy Attachments — Users
    // =========================================================================

    private Response handleAttachUserPolicy(MultivaluedMap<String, String> params) {
        iamService.attachUserPolicy(getParam(params, "UserName"), getParam(params, "PolicyArn"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("AttachUserPolicy", AwsNamespaces.IAM)).build();
    }

    private Response handleDetachUserPolicy(MultivaluedMap<String, String> params) {
        iamService.detachUserPolicy(getParam(params, "UserName"), getParam(params, "PolicyArn"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("DetachUserPolicy", AwsNamespaces.IAM)).build();
    }

    private Response handleListAttachedUserPolicies(MultivaluedMap<String, String> params) {
        List<IamPolicy> policyList = iamService.listAttachedUserPolicies(
                getParam(params, "UserName"), getParam(params, "PathPrefix"));
        return Response.ok(AwsQueryResponse.envelope("ListAttachedUserPolicies", AwsNamespaces.IAM,
                attachedPoliciesXml(policyList))).build();
    }

    // =========================================================================
    // Policy Attachments — Groups
    // =========================================================================

    private Response handleAttachGroupPolicy(MultivaluedMap<String, String> params) {
        iamService.attachGroupPolicy(getParam(params, "GroupName"), getParam(params, "PolicyArn"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("AttachGroupPolicy", AwsNamespaces.IAM)).build();
    }

    private Response handleDetachGroupPolicy(MultivaluedMap<String, String> params) {
        iamService.detachGroupPolicy(getParam(params, "GroupName"), getParam(params, "PolicyArn"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("DetachGroupPolicy", AwsNamespaces.IAM)).build();
    }

    private Response handleListAttachedGroupPolicies(MultivaluedMap<String, String> params) {
        List<IamPolicy> policyList = iamService.listAttachedGroupPolicies(
                getParam(params, "GroupName"), getParam(params, "PathPrefix"));
        return Response.ok(AwsQueryResponse.envelope("ListAttachedGroupPolicies", AwsNamespaces.IAM,
                attachedPoliciesXml(policyList))).build();
    }

    // =========================================================================
    // Policy Attachments — Roles
    // =========================================================================

    private Response handleAttachRolePolicy(MultivaluedMap<String, String> params) {
        iamService.attachRolePolicy(getParam(params, "RoleName"), getParam(params, "PolicyArn"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("AttachRolePolicy", AwsNamespaces.IAM)).build();
    }

    private Response handleDetachRolePolicy(MultivaluedMap<String, String> params) {
        iamService.detachRolePolicy(getParam(params, "RoleName"), getParam(params, "PolicyArn"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("DetachRolePolicy", AwsNamespaces.IAM)).build();
    }

    private Response handleListAttachedRolePolicies(MultivaluedMap<String, String> params) {
        List<IamPolicy> policyList = iamService.listAttachedRolePolicies(
                getParam(params, "RoleName"), getParam(params, "PathPrefix"));
        return Response.ok(AwsQueryResponse.envelope("ListAttachedRolePolicies", AwsNamespaces.IAM,
                attachedPoliciesXml(policyList))).build();
    }

    // =========================================================================
    // Inline Policies — Users
    // =========================================================================

    private Response handlePutUserPolicy(MultivaluedMap<String, String> params) {
        iamService.putUserPolicy(getParam(params, "UserName"),
                getParam(params, "PolicyName"), getParam(params, "PolicyDocument"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("PutUserPolicy", AwsNamespaces.IAM)).build();
    }

    private Response handleGetUserPolicy(MultivaluedMap<String, String> params) {
        String document = iamService.getUserPolicy(getParam(params, "UserName"), getParam(params, "PolicyName"));
        String result = new XmlBuilder()
                .elem("UserName", getParam(params, "UserName"))
                .elem("PolicyName", getParam(params, "PolicyName"))
                .elem("PolicyDocument", document)
                .build();
        return Response.ok(AwsQueryResponse.envelope("GetUserPolicy", AwsNamespaces.IAM, result)).build();
    }

    private Response handleDeleteUserPolicy(MultivaluedMap<String, String> params) {
        iamService.deleteUserPolicy(getParam(params, "UserName"), getParam(params, "PolicyName"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("DeleteUserPolicy", AwsNamespaces.IAM)).build();
    }

    private Response handleListUserPolicies(MultivaluedMap<String, String> params) {
        List<String> names = iamService.listUserPolicies(getParam(params, "UserName"));
        return Response.ok(AwsQueryResponse.envelope("ListUserPolicies", AwsNamespaces.IAM,
                inlinePolicyNamesXml(names))).build();
    }

    // =========================================================================
    // Inline Policies — Groups
    // =========================================================================

    private Response handlePutGroupPolicy(MultivaluedMap<String, String> params) {
        iamService.putGroupPolicy(getParam(params, "GroupName"),
                getParam(params, "PolicyName"), getParam(params, "PolicyDocument"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("PutGroupPolicy", AwsNamespaces.IAM)).build();
    }

    private Response handleGetGroupPolicy(MultivaluedMap<String, String> params) {
        String document = iamService.getGroupPolicy(getParam(params, "GroupName"), getParam(params, "PolicyName"));
        String result = new XmlBuilder()
                .elem("GroupName", getParam(params, "GroupName"))
                .elem("PolicyName", getParam(params, "PolicyName"))
                .elem("PolicyDocument", document)
                .build();
        return Response.ok(AwsQueryResponse.envelope("GetGroupPolicy", AwsNamespaces.IAM, result)).build();
    }

    private Response handleDeleteGroupPolicy(MultivaluedMap<String, String> params) {
        iamService.deleteGroupPolicy(getParam(params, "GroupName"), getParam(params, "PolicyName"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("DeleteGroupPolicy", AwsNamespaces.IAM)).build();
    }

    private Response handleListGroupPolicies(MultivaluedMap<String, String> params) {
        List<String> names = iamService.listGroupPolicies(getParam(params, "GroupName"));
        return Response.ok(AwsQueryResponse.envelope("ListGroupPolicies", AwsNamespaces.IAM,
                inlinePolicyNamesXml(names))).build();
    }

    // =========================================================================
    // Inline Policies — Roles
    // =========================================================================

    private Response handlePutRolePolicy(MultivaluedMap<String, String> params) {
        iamService.putRolePolicy(getParam(params, "RoleName"),
                getParam(params, "PolicyName"), getParam(params, "PolicyDocument"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("PutRolePolicy", AwsNamespaces.IAM)).build();
    }

    private Response handleGetRolePolicy(MultivaluedMap<String, String> params) {
        String document = iamService.getRolePolicy(getParam(params, "RoleName"), getParam(params, "PolicyName"));
        String result = new XmlBuilder()
                .elem("RoleName", getParam(params, "RoleName"))
                .elem("PolicyName", getParam(params, "PolicyName"))
                .elem("PolicyDocument", document)
                .build();
        return Response.ok(AwsQueryResponse.envelope("GetRolePolicy", AwsNamespaces.IAM, result)).build();
    }

    private Response handleDeleteRolePolicy(MultivaluedMap<String, String> params) {
        iamService.deleteRolePolicy(getParam(params, "RoleName"), getParam(params, "PolicyName"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("DeleteRolePolicy", AwsNamespaces.IAM)).build();
    }

    private Response handleListRolePolicies(MultivaluedMap<String, String> params) {
        List<String> names = iamService.listRolePolicies(getParam(params, "RoleName"));
        return Response.ok(AwsQueryResponse.envelope("ListRolePolicies", AwsNamespaces.IAM,
                inlinePolicyNamesXml(names))).build();
    }

    // =========================================================================
    // Access Keys
    // =========================================================================

    private Response handleCreateAccessKey(MultivaluedMap<String, String> params) {
        AccessKey key = iamService.createAccessKey(getParam(params, "UserName"));
        String result = new XmlBuilder().start("AccessKey").raw(accessKeyXml(key, true)).end("AccessKey").build();
        return Response.ok(AwsQueryResponse.envelope("CreateAccessKey", AwsNamespaces.IAM, result)).build();
    }

    private Response handleDeleteAccessKey(MultivaluedMap<String, String> params, String authorization) {
        iamService.deleteAccessKey(resolveUserName(params, authorization), getParam(params, "AccessKeyId"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("DeleteAccessKey", AwsNamespaces.IAM)).build();
    }

    private Response handleListAccessKeys(MultivaluedMap<String, String> params, String authorization) {
        List<AccessKey> keys = iamService.listAccessKeys(resolveUserName(params, authorization));
        XmlBuilder xml = new XmlBuilder().start("AccessKeyMetadata");
        for (AccessKey k : keys) {
            xml.start("member").raw(accessKeyXml(k, false)).end("member");
        }
        xml.end("AccessKeyMetadata").elem("IsTruncated", false);
        return Response.ok(AwsQueryResponse.envelope("ListAccessKeys", AwsNamespaces.IAM, xml.build())).build();
    }

    private Response handleUpdateAccessKey(MultivaluedMap<String, String> params, String authorization) {
        // "If the UserName is not specified, the user name is determined implicitly based on the
        // AWS access key ID used to sign the request", the same sentence DeleteAccessKey carries.
        // Read the way the other three statusType operations read theirs, the status included.
        iamService.updateAccessKey(resolveUserName(params, authorization),
                requireParam(params, "AccessKeyId"), getParam(params, "Status"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("UpdateAccessKey", AwsNamespaces.IAM)).build();
    }

    private Response handleGetAccessKeyLastUsed(MultivaluedMap<String, String> params) {
        // Access-key usage is not modeled, so return the IAM API's documented
        // "never used" shape: an AccessKeyLastUsed with ServiceName=N/A and Region=N/A
        // and NO LastUsedDate. UserName names the key's owner; Terraform's
        // aws_iam_access_key import reads it and fails on "empty response" without it.
        // Its type is non-empty (length 1-128), so for a key the emulator does not
        // hold the element is omitted rather than emitted empty.
        XmlBuilder xml = new XmlBuilder();
        iamService.findAccessKeyOwnerInThisAccount(getParam(params, "AccessKeyId"))
                .ifPresent(userName -> xml.elem("UserName", userName));
        String result = xml
                .start("AccessKeyLastUsed")
                .elem("ServiceName", "N/A")
                .elem("Region", "N/A")
                .end("AccessKeyLastUsed")
                .build();
        return Response.ok(AwsQueryResponse.envelope("GetAccessKeyLastUsed", AwsNamespaces.IAM, result)).build();
    }

    // =========================================================================
    // Centralized root access management (org-scoped, IAM Query endpoint)
    // =========================================================================

    private Response handleListOrganizationsFeatures(MultivaluedMap<String, String> params) {
        return rootFeaturesResponse("ListOrganizationsFeatures",
                centralRootAccess.list(regionResolver.getAccountId()));
    }

    private Response handleEnableOrganizationsRootCredentialsManagement(MultivaluedMap<String, String> params) {
        return rootFeaturesResponse("EnableOrganizationsRootCredentialsManagement",
                centralRootAccess.enableRootCredentialsManagement(regionResolver.getAccountId()));
    }

    private Response handleEnableOrganizationsRootSessions(MultivaluedMap<String, String> params) {
        return rootFeaturesResponse("EnableOrganizationsRootSessions",
                centralRootAccess.enableRootSessions(regionResolver.getAccountId()));
    }

    private Response handleDisableOrganizationsRootCredentialsManagement(MultivaluedMap<String, String> params) {
        return rootFeaturesResponse("DisableOrganizationsRootCredentialsManagement",
                centralRootAccess.disableRootCredentialsManagement(regionResolver.getAccountId()));
    }

    private Response handleDisableOrganizationsRootSessions(MultivaluedMap<String, String> params) {
        return rootFeaturesResponse("DisableOrganizationsRootSessions",
                centralRootAccess.disableRootSessions(regionResolver.getAccountId()));
    }

    /**
     * The shared {@code OrganizationId} and {@code EnabledFeatures} result all five feature
     * operations answer, in the order the model declares them. The organization id used to be
     * omitted here to keep IAM off Organizations; the caller checks the reference models on these
     * operations need the organization anyway, and {@link CentralRootAccess} asks for it through a
     * provider interface, so the member is answered as published.
     */
    private Response rootFeaturesResponse(String action, CentralRootAccess.Features features) {
        XmlBuilder xml = new XmlBuilder()
                .elem("OrganizationId", features.organizationId())
                .start("EnabledFeatures");
        for (String feature : features.enabledFeatures()) {
            xml.elem("member", feature);
        }
        String result = xml.end("EnabledFeatures").build();
        return Response.ok(AwsQueryResponse.envelope(action, AwsNamespaces.IAM, result)).build();
    }

    // =========================================================================
    // Instance Profiles
    // =========================================================================

    private Response handleCreateInstanceProfile(MultivaluedMap<String, String> params) {
        Map<String, String> tags = extractTags(params, false);
        InstanceProfile profile = iamService.createInstanceProfile(
                getParam(params, "InstanceProfileName"), getParam(params, "Path"));
        if (!tags.isEmpty()) {
            iamService.tagInstanceProfile(profile.getInstanceProfileName(), tags);
            profile = iamService.getInstanceProfile(profile.getInstanceProfileName());
        }
        String result = new XmlBuilder().start("InstanceProfile").raw(instanceProfileXml(profile, true)).end("InstanceProfile").build();
        return Response.ok(AwsQueryResponse.envelope("CreateInstanceProfile", AwsNamespaces.IAM, result)).build();
    }

    private Response handleGetInstanceProfile(MultivaluedMap<String, String> params) {
        InstanceProfile profile = iamService.getInstanceProfile(getParam(params, "InstanceProfileName"));
        String result = new XmlBuilder().start("InstanceProfile").raw(instanceProfileXml(profile, true)).end("InstanceProfile").build();
        return Response.ok(AwsQueryResponse.envelope("GetInstanceProfile", AwsNamespaces.IAM, result)).build();
    }

    private Response handleDeleteInstanceProfile(MultivaluedMap<String, String> params) {
        iamService.deleteInstanceProfile(getParam(params, "InstanceProfileName"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("DeleteInstanceProfile", AwsNamespaces.IAM)).build();
    }

    private Response handleListInstanceProfiles(MultivaluedMap<String, String> params) {
        List<InstanceProfile> profiles = iamService.listInstanceProfiles(getParam(params, "PathPrefix"));
        XmlBuilder xml = new XmlBuilder().start("InstanceProfiles");
        for (InstanceProfile p : profiles) {
            // Documented listing subset: tags are omitted here, unlike GetInstanceProfile.
            xml.start("member").raw(instanceProfileXml(p, false)).end("member");
        }
        xml.end("InstanceProfiles").elem("IsTruncated", false);
        return Response.ok(AwsQueryResponse.envelope("ListInstanceProfiles", AwsNamespaces.IAM, xml.build())).build();
    }

    private Response handleAddRoleToInstanceProfile(MultivaluedMap<String, String> params) {
        iamService.addRoleToInstanceProfile(getParam(params, "InstanceProfileName"), getParam(params, "RoleName"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("AddRoleToInstanceProfile", AwsNamespaces.IAM)).build();
    }

    private Response handleRemoveRoleFromInstanceProfile(MultivaluedMap<String, String> params) {
        iamService.removeRoleFromInstanceProfile(getParam(params, "InstanceProfileName"), getParam(params, "RoleName"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("RemoveRoleFromInstanceProfile", AwsNamespaces.IAM)).build();
    }

    private Response handleListInstanceProfilesForRole(MultivaluedMap<String, String> params) {
        List<InstanceProfile> profiles = iamService.listInstanceProfilesForRole(getParam(params, "RoleName"));
        XmlBuilder xml = new XmlBuilder().start("InstanceProfiles");
        for (InstanceProfile p : profiles) {
            xml.start("member").raw(instanceProfileXml(p, true)).end("member");
        }
        xml.end("InstanceProfiles").elem("IsTruncated", false);
        return Response.ok(AwsQueryResponse.envelope("ListInstanceProfilesForRole", AwsNamespaces.IAM, xml.build())).build();
    }

    // =========================================================================
    // Permission Boundaries
    // =========================================================================

    private Response handlePutUserPermissionsBoundary(MultivaluedMap<String, String> params) {
        String userName = requireParam(params, "UserName");
        String boundaryArn = requireParam(params, "PermissionsBoundary");
        iamService.putUserPermissionsBoundary(userName, boundaryArn);
        return Response.ok(AwsQueryResponse.envelope("PutUserPermissionsBoundary", AwsNamespaces.IAM, "")).build();
    }

    private Response handleDeleteUserPermissionsBoundary(MultivaluedMap<String, String> params) {
        String userName = requireParam(params, "UserName");
        iamService.deleteUserPermissionsBoundary(userName);
        return Response.ok(AwsQueryResponse.envelope("DeleteUserPermissionsBoundary", AwsNamespaces.IAM, "")).build();
    }

    private Response handlePutRolePermissionsBoundary(MultivaluedMap<String, String> params) {
        String roleName = requireParam(params, "RoleName");
        String boundaryArn = requireParam(params, "PermissionsBoundary");
        iamService.putRolePermissionsBoundary(roleName, boundaryArn);
        return Response.ok(AwsQueryResponse.envelope("PutRolePermissionsBoundary", AwsNamespaces.IAM, "")).build();
    }

    private Response handleDeleteRolePermissionsBoundary(MultivaluedMap<String, String> params) {
        String roleName = requireParam(params, "RoleName");
        iamService.deleteRolePermissionsBoundary(roleName);
        return Response.ok(AwsQueryResponse.envelope("DeleteRolePermissionsBoundary", AwsNamespaces.IAM, "")).build();
    }

    private Response handleSimulatePrincipalPolicy(MultivaluedMap<String, String> params) {
        String policySourceArn = validatedPolicySourceArn(params);
        CallerContext caller = iamService.resolvePrincipalContext(policySourceArn);
        List<String> actionNames = requireActionNames(params);
        List<String> resourceArns = extractResourceArnsOrWildcard(params);
        // Keyed without regard to case, as the evaluator compares condition key names: a value
        // the call supplied has to shadow a populated one however the call spelled the key.
        Map<String, List<String>> context = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        context.putAll(extractContextEntries(params));
        // The simulator fills the principal keys in itself rather than asking for them, so a
        // policy scoped to ${aws:username} or keyed on who is calling simulates as it evaluates.
        // A value the call supplied stays, being the one the caller asked to simulate with. The
        // account comes from the identity that was resolved, not from the ARN the call wrote,
        // which need not be one this evaluator can parse.
        iamService.simulationPrincipalKeys(policySourceArn).ifPresent(principal -> {
            Map<String, List<String>> populated = new HashMap<>();
            principal.putPrincipalKeys(populated);
            String account = AwsArnUtils.accountOrDefault(principal.principalArn(), null);
            if (account != null) {
                populated.put("aws:PrincipalAccount", List.of(account));
            }
            populated.forEach(context::putIfAbsent);
        });
        String result = simulationResultsXml(caller, actionNames, resourceArns, context);
        return Response.ok(AwsQueryResponse.envelope("SimulatePrincipalPolicy", AwsNamespaces.IAM, result)).build();
    }

    private Response handleSimulateCustomPolicy(MultivaluedMap<String, String> params) {
        List<String> policyInputList = requirePolicyInputList(params);
        List<String> actionNames = requireActionNames(params);
        List<String> resourceArns = extractResourceArnsOrWildcard(params);
        Map<String, List<String>> context = extractContextEntries(params);
        // AWS accepts only one permissions boundary document per simulation; a request that
        // provides more than one is not modeled and only the first is applied.
        List<String> boundaryInputList = extractIndexedValues(params, "PermissionsBoundaryPolicyInputList.member");
        String boundaryDocument = boundaryInputList.isEmpty() ? null : boundaryInputList.get(0);
        CallerContext caller = new CallerContext(policyInputList, null, boundaryDocument);
        String result = simulationResultsXml(caller, actionNames, resourceArns, context);
        return Response.ok(AwsQueryResponse.envelope("SimulateCustomPolicy", AwsNamespaces.IAM, result)).build();
    }

    private List<String> requireActionNames(MultivaluedMap<String, String> params) {
        List<String> actionNames = extractIndexedValues(params, "ActionNames.member");
        if (actionNames.isEmpty()) {
            throw new AwsException("ValidationError", "At least one ActionNames member is required.", 400);
        }
        return actionNames;
    }

    private List<String> requirePolicyInputList(MultivaluedMap<String, String> params) {
        List<String> policyInputList = extractIndexedValues(params, "PolicyInputList.member");
        if (policyInputList.isEmpty()) {
            throw new AwsException("ValidationError", "At least one PolicyInputList member is required.", 400);
        }
        return policyInputList;
    }

    private List<String> extractResourceArnsOrWildcard(MultivaluedMap<String, String> params) {
        List<String> resourceArns = extractIndexedValues(params, "ResourceArns.member");
        return resourceArns.isEmpty() ? List.of("*") : resourceArns;
    }

    private String simulationResultsXml(CallerContext caller, List<String> actionNames,
                                         List<String> resourceArns, Map<String, List<String>> context) {
        XmlBuilder results = new XmlBuilder().start("EvaluationResults");
        for (String actionName : actionNames) {
            for (String resourceArn : resourceArns) {
                IamPolicyEvaluator.SimulationDecision decision =
                        policyEvaluator.simulatePrincipalPolicy(caller, actionName, resourceArn, context);
                results.start("member")
                        .elem("EvalActionName", actionName)
                        .elem("EvalResourceName", resourceArn)
                        .elem("EvalDecision", decision.awsValue())
                        .start("MatchedStatements").end("MatchedStatements")
                        .start("MissingContextValues").end("MissingContextValues")
                        .end("member");
            }
        }
        return results.end("EvaluationResults")
                .elem("IsTruncated", false)
                .build();
    }

    private Response handleGetContextKeysForCustomPolicy(MultivaluedMap<String, String> params) {
        List<String> policyInputList = requirePolicyInputList(params);
        List<String> keys = policyEvaluator.contextKeysReferencedIn(policyInputList);
        return Response.ok(AwsQueryResponse.envelope("GetContextKeysForCustomPolicy", AwsNamespaces.IAM,
                contextKeyNamesXml(keys))).build();
    }

    private Response handleGetContextKeysForPrincipalPolicy(MultivaluedMap<String, String> params) {
        String policySourceArn = validatedPolicySourceArn(params);
        // "Only context keys referenced by the identity-based policies attached to the specified
        // entity, and any additional policies that you provide, are included": a grant the account
        // applies without attaching it is therefore not read here, though a simulation evaluates
        // with it.
        CallerContext caller = iamService.resolveAttachedPrincipalContext(policySourceArn);
        List<String> allDocuments = new ArrayList<>(caller.identityPolicies());
        allDocuments.addAll(extractIndexedValues(params, "PolicyInputList.member"));
        List<String> keys = policyEvaluator.contextKeysReferencedIn(allDocuments);
        return Response.ok(AwsQueryResponse.envelope("GetContextKeysForPrincipalPolicy", AwsNamespaces.IAM,
                contextKeyNamesXml(keys))).build();
    }

    // =========================================================================
    // Last-Accessed Reporting (Access Advisor)
    // =========================================================================

    /**
     * Generates the report now and stores it with the job. AWS fixes a report at generation time
     * and the readers below only retrieve it, so the service list and the entities are captured
     * here rather than recomputed per read. The ARN is resolved first, because AWS answers
     * {@code NoSuchEntity} for one that names nothing.
     */
    private Response handleGenerateServiceLastAccessedDetails(MultivaluedMap<String, String> params,
                                                              String authorization) {
        String accountId = accountResolver.resolve(authorization);
        String arn = requireParam(params, "Arn");
        // The same rule as the readers below: the granularity is a declared enum, so it is read
        // before the ARN is resolved rather than inside the generate it is passed to.
        ServiceLastAccessedService.validateGranularity(params.getFirst("Granularity"));
        AccessAdvisorTarget target = resolveAccessAdvisorTarget(accountId, arn);
        // Already sorted and expanded, including the wildcard and NotAction grants that name no
        // namespace of their own.
        List<String> granted = policyEvaluator.servicesGrantedBy(target.policyDocuments());
        ServiceLastAccessedJob job = serviceLastAccessedService.generate(accountId, arn,
                params.getFirst("Granularity"), granted, target.entities());
        return Response.ok(AwsQueryResponse.envelope("GenerateServiceLastAccessedDetails", AwsNamespaces.IAM,
                new XmlBuilder().elem("JobId", job.getJobId()).build())).build();
    }

    /**
     * AWS lists a service the entity could reach even when it was never used, leaving
     * {@code LastAuthenticated} and {@code TotalAuthenticatedEntities} null in that case rather
     * than omitting the service. Floci records no access, so every entry here is that "no attempt"
     * shape, over the service list captured when the job ran.
     */
    private Response handleGetServiceLastAccessedDetails(MultivaluedMap<String, String> params,
                                                         String authorization) {
        String jobId = validatedJobId(params);
        validatePagingInputs(params);
        ServiceLastAccessedJob job = serviceLastAccessedService.get(
                accountResolver.resolve(authorization), jobId);
        Page<String> page = paginate(job.getServiceNamespaces(), Function.identity(), params);
        XmlBuilder xml = new XmlBuilder()
                .elem("JobStatus", "COMPLETED")
                .elem("JobType", job.getGranularity())
                .elem("JobCreationDate", isoDate(job.getJobCreationDate()))
                .elem("JobCompletionDate", isoDate(job.getJobCompletionDate()))
                .start("ServicesLastAccessed");
        for (String namespace : page.items()) {
            // ServiceName is a required member and AWS carries a display name ("Amazon S3") that
            // Floci has no mapping for, so the namespace stands in for it.
            xml.start("member")
               .elem("ServiceName", namespace)
               .elem("ServiceNamespace", namespace)
               .end("member");
        }
        xml.end("ServicesLastAccessed").elem("IsTruncated", page.truncated());
        if (page.marker() != null) {
            xml.elem("Marker", page.marker());
        }
        return Response.ok(AwsQueryResponse.envelope("GetServiceLastAccessedDetails", AwsNamespaces.IAM,
                xml.build())).build();
    }

    /**
     * Generates the Organizations access report now and returns its job id, mirroring the Access
     * Advisor generator above. The report is the services the SCPs along the entity path leave
     * reachable; the reference is explicit that service last accessed data "only applies SCP
     * logic", so no identity policy, resource policy or permissions boundary enters it.
     *
     * <p>{@code ReportGenerationLimitExceeded} is the operation's only modeled error and stays
     * unreachable here: it reports concurrent generations for the account, and a Floci job is
     * complete the moment it is created, so two can never overlap.
     */
    private Response handleGenerateOrganizationsAccessReport(MultivaluedMap<String, String> params,
                                                             String authorization) {
        OrganizationsAccessReportJob job = organizationsAccessReportService.generate(
                accountResolver.resolve(authorization), requireParam(params, "EntityPath"),
                params.getFirst("OrganizationsPolicyId"));
        return Response.ok(AwsQueryResponse.envelope("GenerateOrganizationsAccessReport",
                AwsNamespaces.IAM,
                new XmlBuilder().elem("JobId", job.getJobId()).build())).build();
    }

    /**
     * Retrieves a generated report. AWS lists a service account principals are allowed to reach
     * even when it was never used, and its own example gives such a service as a namespace and a
     * zero count with no region, entity path or last-authenticated time. Floci records no access,
     * so every entry takes that shape and {@code NumberOfServicesNotAccessed} equals
     * {@code NumberOfServicesAccessible}.
     *
     * <p>A job that failed carries {@code ErrorDetails} and no report, which is where an entity
     * path naming something outside the organization lands, the operation publishing no
     * synchronous error for one.
     */
    private Response handleGetOrganizationsAccessReport(MultivaluedMap<String, String> params,
                                                        String authorization) {
        String jobId = validatedJobId(params);
        // Read before the lookup, because a value outside the enum is a bad request whether or not
        // the job exists: validating it after would answer NoSuchEntity for a request AWS rejects.
        String sortKey = validatedSortKey(params);
        validatePagingInputs(params);
        OrganizationsAccessReportJob job = organizationsAccessReportService.get(
                accountResolver.resolve(authorization), jobId);
        XmlBuilder xml = new XmlBuilder()
                .elem("JobStatus", job.getJobStatus())
                .elem("JobCreationDate", isoDate(job.getJobCreationDate()))
                .elem("JobCompletionDate", isoDate(job.getJobCompletionDate()));
        if (job.getErrorCode() != null) {
            return Response.ok(AwsQueryResponse.envelope("GetOrganizationsAccessReport",
                    AwsNamespaces.IAM,
                    xml.elem("IsTruncated", false)
                       .start("ErrorDetails")
                       .elem("Message", job.getErrorMessage())
                       .elem("Code", job.getErrorCode())
                       .end("ErrorDetails")
                       .build())).build();
        }
        List<String> namespaces = sortedByKey(job.getServiceNamespaces(), sortKey);
        Page<String> page = paginate(namespaces, Function.identity(), params);
        xml.elem("NumberOfServicesAccessible", namespaces.size())
           .elem("NumberOfServicesNotAccessed", namespaces.size())
           .start("AccessDetails");
        for (String namespace : page.items()) {
            // ServiceName carries a display name ("Amazon S3") that Floci has no mapping for, so
            // the namespace stands in for it, as it does on the last-accessed reader.
            xml.start("member")
               .elem("ServiceName", namespace)
               .elem("ServiceNamespace", namespace)
               .elem("TotalAuthenticatedEntities", 0)
               .end("member");
        }
        xml.end("AccessDetails").elem("IsTruncated", page.truncated());
        if (page.marker() != null) {
            xml.elem("Marker", page.marker());
        }
        return Response.ok(AwsQueryResponse.envelope("GetOrganizationsAccessReport",
                AwsNamespaces.IAM, xml.build())).build();
    }

    /**
     * {@code MaxItems} if the request carries one inside the range the reference publishes, null if
     * it carries none. Read without the list it will page, so a reader can check it before it looks
     * anything up.
     */
    private static Integer validatedMaxItems(MultivaluedMap<String, String> params) {
        String raw = params.getFirst("MaxItems");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int limit;
        try {
            limit = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidInput",
                    "The value " + raw + " at 'maxItems' is not a number.", 400);
        }
        if (limit < 1) {
            throw validationError(raw, "maxItems", "Member must have value greater than or equal to 1");
        }
        if (limit > MAX_MAX_ITEMS) {
            throw validationError(raw, "maxItems",
                    "Member must have value less than or equal to " + MAX_MAX_ITEMS);
        }
        return limit;
    }

    /**
     * {@code JobId} held to the fixed 36 characters {@code jobIDType} declares, before the job is
     * looked up. The order is the whole point: a value that breaks a declared constraint is a bad
     * request whether or not the job exists, and the validation AWS runs ahead of an operation
     * never reaches its store, so checking it afterwards answers {@code NoSuchEntity} 404 where
     * AWS answers 400.
     *
     * <p>A fixed length reports as one constraint rather than as both bounds, the form
     * {@code lifecycleActionToken} already uses for the same shape in Auto Scaling, and the
     * offending value is left out, which is how the most recent IAM recordings render one.
     */
    private String validatedJobId(MultivaluedMap<String, String> params) {
        String jobId = requireParam(params, "JobId");
        if (jobId.length() != JOB_ID_LENGTH) {
            throw SmithyValidationErrors.validationError("Value at 'jobId' failed to satisfy "
                    + "constraint: Member must have length equal to " + JOB_ID_LENGTH);
        }
        return jobId;
    }

    /**
     * The paging members, read ahead of the lookup whose result they page, for the reason
     * {@link #validatedJobId} is. {@link #paginate} reads them again when it runs: most of the
     * readers paged there look nothing up first, and a check only one caller performs is one the
     * next caller forgets.
     */
    private void validatePagingInputs(MultivaluedMap<String, String> params) {
        validatedMaxItems(params);
        String marker = params.getFirst("Marker");
        if (marker != null && !marker.isBlank() && markers.cursorOf(marker.trim()) == null) {
            markerIndex(marker);
        }
    }

    /**
     * {@code PolicySourceArn} held to the 20 to 2048 characters {@code arnType} declares, before
     * the entity it names is resolved. IAM answers that minimum on a sibling ARN member with a
     * {@code ValidationError} naming the length, so a value too short to be any ARN is refused on
     * its shape rather than resolved to whatever follows its last slash.
     *
     * <p>A value that clears 20 characters and still parses as no ARN is left alone: the member
     * publishes a length and no pattern, and what AWS answers for one is not recorded. An absent
     * or blank value is left alone too, the resolver below reporting that one.
     */
    private String validatedPolicySourceArn(MultivaluedMap<String, String> params) {
        String arn = getParam(params, "PolicySourceArn");
        if (arn == null || arn.isBlank()) {
            return arn;
        }
        return arnOfDeclaredLength(arn, "policySourceArn");
    }

    /**
     * A required {@code arnType} member, held to the same 20 to 2048 characters before whatever it
     * names is looked up. {@code TemplateArn} is read through here on both role template
     * operations, which have nothing to look it up in.
     */
    private String requiredArn(MultivaluedMap<String, String> params, String name) {
        return arnOfDeclaredLength(requireParam(params, name), memberPath(name));
    }

    private static String arnOfDeclaredLength(String arn, String path) {
        if (arn.length() < ARN_MIN_LENGTH) {
            throw SmithyValidationErrors.validationError("Value at '" + path + "' failed to satisfy "
                    + "constraint: Member must have length greater than or equal to " + ARN_MIN_LENGTH);
        }
        if (arn.length() > ARN_MAX_LENGTH) {
            throw SmithyValidationErrors.validationError("Value at '" + path + "' failed to satisfy "
                    + "constraint: Member must have length less than or equal to " + ARN_MAX_LENGTH);
        }
        return arn;
    }

    /**
     * {@code SortKey} if the request carries one the enum permits, null if it carries none. A
     * request that carries the member is held to the enum whatever it carries: an empty value is
     * not one of the four, and reading it as an absent member would answer a default-ordered
     * report to a request AWS rejects.
     */
    private static String validatedSortKey(MultivaluedMap<String, String> params) {
        String sortKey = params.getFirst("SortKey");
        if (sortKey == null) {
            return null;
        }
        if (!SORT_KEYS.contains(sortKey)) {
            throw validationError(sortKey, "sortKey", "Member must satisfy enum value set: "
                    + "[SERVICE_NAMESPACE_ASCENDING, SERVICE_NAMESPACE_DESCENDING, "
                    + "LAST_AUTHENTICATED_TIME_ASCENDING, LAST_AUTHENTICATED_TIME_DESCENDING]");
        }
        return sortKey;
    }

    /**
     * Applies {@code SortKey}. The namespace keys sort the list the reference says they do, and
     * the two time keys leave it in namespace order: sorting by a last-authenticated time no
     * entry carries would be ordering on nothing, and the reference's default is the namespace
     * anyway.
     */
    private static List<String> sortedByKey(List<String> namespaces, String sortKey) {
        if (!"SERVICE_NAMESPACE_DESCENDING".equals(sortKey)) {
            return namespaces;
        }
        List<String> descending = new ArrayList<>(namespaces);
        descending.sort(Comparator.reverseOrder());
        return descending;
    }

    /**
     * Lists the entities that could have reached the service through the reported ARN's
     * permissions, which AWS derives from policy content rather than from usage: a group report
     * yields the group's users, a policy report the users and roles it is attached to, and a user
     * or role report that entity itself. {@code LastAuthenticated} stays absent throughout, since
     * Floci records no access.
     */
    private Response handleGetServiceLastAccessedDetailsWithEntities(MultivaluedMap<String, String> params,
                                                                     String authorization) {
        String jobId = validatedJobId(params);
        String namespace = requireParam(params, "ServiceNamespace");
        validatePagingInputs(params);
        ServiceLastAccessedJob job = serviceLastAccessedService.get(
                accountResolver.resolve(authorization), jobId);
        // The report named the services it covers, so an entity is only reported for one of those.
        List<ServiceLastAccessedEntity> reported = job.getServiceNamespaces().contains(namespace)
                ? job.getEntities() : List.of();
        Page<ServiceLastAccessedEntity> page = paginate(reported, ServiceLastAccessedEntity::getArn, params);
        XmlBuilder xml = new XmlBuilder()
                .elem("JobStatus", "COMPLETED")
                .elem("JobCreationDate", isoDate(job.getJobCreationDate()))
                .elem("JobCompletionDate", isoDate(job.getJobCompletionDate()))
                .start("EntityDetailsList");
        for (ServiceLastAccessedEntity entity : page.items()) {
            xml.start("member").start("EntityInfo")
               .elem("Arn", entity.getArn())
               .elem("Name", entity.getName())
               .elem("Type", entity.getType())
               .elem("Id", entity.getId())
               .elem("Path", entity.getPath())
               .end("EntityInfo").end("member");
        }
        xml.end("EntityDetailsList").elem("IsTruncated", page.truncated());
        if (page.marker() != null) {
            xml.elem("Marker", page.marker());
        }
        return Response.ok(AwsQueryResponse.envelope("GetServiceLastAccessedDetailsWithEntities",
                AwsNamespaces.IAM, xml.build())).build();
    }

    /**
     * Unlike the Access Advisor jobs above, this one needs no usage history and no job: AWS defines
     * it purely over permissions-policy logic, so it is answered from the identity's policies as
     * they stand.
     */
    private Response handleListPoliciesGrantingServiceAccess(MultivaluedMap<String, String> params,
                                                             String authorization) {
        String accountId = accountResolver.resolve(authorization);
        String arn = requireParam(params, "Arn");
        List<String> namespaces = getMemberList(params, "ServiceNamespaces");
        if (namespaces.isEmpty()) {
            throw new AwsException("InvalidInput",
                    "The request must include at least one service namespace.", 400);
        }
        checkListLength(namespaces.size(), "serviceNamespaces", MAX_SERVICE_NAMESPACES);
        List<GrantingPolicy> candidates = policiesForIdentity(accountId, arn);
        // The response list carries one entry per requested namespace, so that is what a Marker
        // walks through.
        Page<String> page = paginateByMarker(namespaces, Function.identity(), params);
        XmlBuilder xml = new XmlBuilder().start("PoliciesGrantingServiceAccess");
        for (String namespace : page.items()) {
            xml.start("member").elem("ServiceNamespace", namespace).start("Policies");
            for (GrantingPolicy candidate : candidates) {
                if (policyEvaluator.grantsServiceAccess(candidate.document(), namespace)) {
                    xml.start("member")
                       .elem("PolicyName", candidate.policyName())
                       .elem("PolicyType", candidate.policyType());
                    if (candidate.policyArn() != null) {
                        xml.elem("PolicyArn", candidate.policyArn());
                    } else {
                        xml.elem("EntityType", candidate.entityType())
                           .elem("EntityName", candidate.entityName());
                    }
                    xml.end("member");
                }
            }
            xml.end("Policies").end("member");
        }
        xml.end("PoliciesGrantingServiceAccess").elem("IsTruncated", page.truncated());
        if (page.marker() != null) {
            xml.elem("Marker", page.marker());
        }
        return Response.ok(AwsQueryResponse.envelope("ListPoliciesGrantingServiceAccess",
                AwsNamespaces.IAM, xml.build())).build();
    }

    // ── Delegation requests ───────────────────────────────────────────────────

    /**
     * Files a delegation request for the calling partner account.
     *
     * <p>Members are read without {@code requireParam} and handed over as they arrived: the
     * service validates them and answers {@code ValidationError}, which the reference's common
     * errors publish for a member that breaks its format or constraints across every IAM
     * operation. The modeled {@code InvalidInput} is left for a value that cannot be read as the
     * member's type at all, which is what {@link #delegationInt} and
     * {@link #parseDelegationBoolean} answer.
     */
    private Response handleCreateDelegationRequest(MultivaluedMap<String, String> params) {
        DelegationRequestService.NewRequest command = new DelegationRequestService.NewRequest(
                getParam(params, "Description"),
                getParam(params, "NotificationChannel"),
                getParam(params, "RequestorWorkflowId"),
                delegationInt(params, "SessionDuration"),
                parseDelegationPermissions(params),
                getParam(params, "OwnerAccountId"),
                getParam(params, "RedirectUrl"),
                getParam(params, "RequestMessage"),
                parseDelegationBoolean(params, "OnlySendByOwner"));
        DelegationRequestService.Created created = delegationRequestService.create(
                regionResolver.getAccountId(), regionResolver.getRegion(), command);
        String result = new XmlBuilder()
                .elem("DelegationRequestId", created.request().getDelegationRequestId())
                .elem("ConsoleDeepLink", created.consoleDeepLink())
                .build();
        return Response.ok(AwsQueryResponse.envelope("CreateDelegationRequest",
                AwsNamespaces.IAM, result)).build();
    }

    private Response handleAssociateDelegationRequest(MultivaluedMap<String, String> params,
                                                      String authorization) {
        delegationRequestService.associate(regionResolver.getAccountId(),
                callerPrincipalArn(authorization), getParam(params, "DelegationRequestId"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("AssociateDelegationRequest",
                AwsNamespaces.IAM)).build();
    }

    private Response handleAcceptDelegationRequest(MultivaluedMap<String, String> params,
                                                   String authorization) {
        delegationRequestService.accept(regionResolver.getAccountId(),
                callerPrincipalArn(authorization), getParam(params, "DelegationRequestId"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("AcceptDelegationRequest",
                AwsNamespaces.IAM)).build();
    }

    private Response handleRejectDelegationRequest(MultivaluedMap<String, String> params) {
        delegationRequestService.reject(regionResolver.getAccountId(),
                getParam(params, "DelegationRequestId"), getParam(params, "Notes"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("RejectDelegationRequest",
                AwsNamespaces.IAM)).build();
    }

    private Response handleUpdateDelegationRequest(MultivaluedMap<String, String> params) {
        delegationRequestService.update(regionResolver.getAccountId(),
                getParam(params, "DelegationRequestId"), getParam(params, "Notes"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("UpdateDelegationRequest",
                AwsNamespaces.IAM)).build();
    }

    /**
     * Releases the exchange token on an accepted request, which finalizes it.
     *
     * <p>The operation answers no output shape at all, so the envelope carries no result, and the
     * token leaves by the one door AWS gives it: the {@code ExchangeToken} notification on the
     * partner's topic, which the release publishes, and no operation on the family returns it.
     *
     * <p>The caller's ARN goes with the account because this is the one delegation call that can
     * be scoped to an identity: {@code OnlySendByOwner} holds the release to whoever claimed the
     * request, who need not be the approver that accepted it.
     */
    private Response handleSendDelegationToken(MultivaluedMap<String, String> params,
                                               String authorization) {
        delegationRequestService.release(regionResolver.getAccountId(),
                callerPrincipalArn(authorization), getParam(params, "DelegationRequestId"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("SendDelegationToken",
                AwsNamespaces.IAM)).build();
    }

    /**
     * One request, with the permission check only when it was asked for.
     *
     * <p>{@code PermissionCheckResult} answers {@code UNSURE}, which the enum models for exactly
     * this: the check asks whether the caller may act on the request, and a delegation request
     * carries no permission for Floci to evaluate, so claiming {@code ALLOWED} or {@code DENIED}
     * would be inventing an answer. Omitting both members when the flag is absent keeps a plain
     * read the shape AWS gives it.
     */
    private Response handleGetDelegationRequest(MultivaluedMap<String, String> params) {
        DelegationRequest request = delegationRequestService.get(regionResolver.getAccountId(),
                getParam(params, "DelegationRequestId"));
        XmlBuilder xml = new XmlBuilder().start("DelegationRequest")
                .raw(delegationRequestXml(request)).end("DelegationRequest");
        if (Boolean.TRUE.equals(parseDelegationBoolean(params, "DelegationPermissionCheck"))) {
            xml.elem("PermissionCheckStatus", "COMPLETE").elem("PermissionCheckResult", "UNSURE");
        }
        return Response.ok(AwsQueryResponse.envelope("GetDelegationRequest",
                AwsNamespaces.IAM, xml.build())).build();
    }

    private Response handleListDelegationRequests(MultivaluedMap<String, String> params) {
        Page<DelegationRequest> page = paginate(delegationRequestService.list(
                regionResolver.getAccountId(), getParam(params, "OwnerId")),
                DelegationRequest::getDelegationRequestId, params);
        XmlBuilder xml = new XmlBuilder().start("DelegationRequests");
        for (DelegationRequest request : page.items()) {
            xml.start("member").raw(delegationRequestXml(request)).end("member");
        }
        // isTruncated is lower-cased on this one operation, where every other IAM list spells it
        // IsTruncated. The model names the member that way, so the wire follows the model.
        xml.end("DelegationRequests").elem("isTruncated", page.truncated());
        if (page.marker() != null) {
            xml.elem("Marker", page.marker());
        }
        return Response.ok(AwsQueryResponse.envelope("ListDelegationRequests",
                AwsNamespaces.IAM, xml.build())).build();
    }

    private String delegationRequestXml(DelegationRequest request) {
        XmlBuilder xml = new XmlBuilder()
                .elem("DelegationRequestId", request.getDelegationRequestId())
                .elem("Description", request.getDescription())
                .elem("RequestMessage", request.getRequestMessage())
                .elem("RequestorId", request.getRequestorId())
                .elem("RequestorName", request.getRequestorName())
                .elem("OwnerAccountId", request.getOwnerAccountId())
                .elem("OwnerId", request.getOwnerId())
                .elem("ApproverId", request.getApproverId())
                .elem("State", request.getState() == null ? null : request.getState().name())
                .elem("PermissionPolicy", request.getPermissionPolicy())
                .elem("RedirectUrl", request.getRedirectUrl())
                .elem("Notes", request.getNotes())
                .elem("RejectionReason", request.getRejectionReason());
        if (request.getSessionDuration() != null) {
            xml.elem("SessionDuration", request.getSessionDuration());
        }
        if (request.getOnlySendByOwner() != null) {
            xml.elem("OnlySendByOwner", request.getOnlySendByOwner());
        }
        if (request.getCreateDate() != null) {
            xml.elem("CreateDate", isoDate(request.getCreateDate()));
        }
        if (request.getUpdatedTime() != null) {
            xml.elem("UpdatedTime", isoDate(request.getUpdatedTime()));
        }
        if (request.getExpirationTime() != null) {
            xml.elem("ExpirationTime", isoDate(request.getExpirationTime()));
        }
        if (!request.getRolePermissionRestrictionArns().isEmpty()) {
            xml.start("RolePermissionRestrictionArns");
            for (String arn : request.getRolePermissionRestrictionArns()) {
                xml.elem("member", arn);
            }
            xml.end("RolePermissionRestrictionArns");
        }
        DelegationPermission permissions = request.getPermissions();
        if (permissions != null) {
            xml.start("Permissions").elem("PolicyTemplateArn", permissions.getPolicyTemplateArn());
            if (!permissions.getParameters().isEmpty()) {
                xml.start("Parameters");
                for (PolicyParameter parameter : permissions.getParameters()) {
                    xml.start("member").elem("Name", parameter.getName())
                       .elem("Type", parameter.getType()).start("Values");
                    for (String value : parameter.getValues()) {
                        xml.elem("member", value);
                    }
                    xml.end("Values").end("member");
                }
                xml.end("Parameters");
            }
            xml.end("Permissions");
        }
        return xml.build();
    }

    /**
     * The summary of a delegation request, in Markdown, with the generation state beside it.
     *
     * <p>{@code SummaryContent} is left out of the answer unless the state is {@code AVAILABLE},
     * the model saying it "is non-empty only if the SummaryState is AVAILABLE": an absent member
     * and an empty one read the same to an SDK, and omitting it keeps the response from carrying a
     * summary that is not there.
     */
    private Response handleGetHumanReadableSummary(MultivaluedMap<String, String> params) {
        DelegationRequestService.Summary summary = delegationRequestService.summarize(
                regionResolver.getAccountId(), getParam(params, "EntityArn"),
                getParam(params, "Locale"));
        String result = new XmlBuilder()
                .elem("SummaryContent", summary.content())
                .elem("Locale", summary.locale())
                .elem("SummaryState", summary.state())
                .build();
        return Response.ok(AwsQueryResponse.envelope("GetHumanReadableSummary",
                AwsNamespaces.IAM, result)).build();
    }

    /** {@code Permissions} is a structure, so its members arrive dotted under that prefix. */
    private DelegationPermission parseDelegationPermissions(MultivaluedMap<String, String> params) {
        String templateArn = getParam(params, "Permissions.PolicyTemplateArn");
        List<PolicyParameter> parameters = new ArrayList<>();
        for (int index : memberIndices(params, "Permissions.Parameters.member.")) {
            String prefix = "Permissions.Parameters.member." + index;
            List<String> values = new ArrayList<>();
            for (int valueIndex : memberIndices(params, prefix + ".Values.member.")) {
                // An index is read off the keys, so one can be present through a key of its own
                // that carries no value, as a dotted sub-member does. That is no value to store:
                // AWS reads the member it knows and nothing from the key it does not.
                String value = getParam(params, prefix + ".Values.member." + valueIndex);
                if (value != null) {
                    values.add(value);
                }
            }
            parameters.add(new PolicyParameter(getParam(params, prefix + ".Name"),
                    getParam(params, prefix + ".Type"), values));
        }
        if (templateArn == null && parameters.isEmpty()
                && params.getFirst("Permissions") == null) {
            // Nothing under the prefix at all, which the service reports as the missing required
            // member rather than being read here as an empty structure.
            return null;
        }
        return new DelegationPermission(templateArn, parameters);
    }

    /**
     * The indices a request supplied under an indexed member prefix, in order. Read from the keys
     * the request carries rather than counted up from one, so a list numbered from two, or one
     * with a gap in it, keeps every entry instead of being cut short at the first index missing.
     */
    private static List<Integer> memberIndices(MultivaluedMap<String, String> params, String prefix) {
        return params.keySet().stream()
                .filter(key -> key.startsWith(prefix))
                .map(key -> key.substring(prefix.length()).split("\\.")[0])
                .filter(segment -> segment.matches("\\d+"))
                .map(Integer::valueOf)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * The caller's principal ARN, or the account-root stand-in when the access key maps to no IAM
     * user, which is the same synthesized principal {@code IamEnforcementFilter} uses. An owner has
     * to be an identity ARN, and the credentials a local caller signs with are usually not a
     * stored user's.
     */
    private String callerPrincipalArn(String authorization) {
        String accessKeyId = accountResolver.extractAccessKeyId(authorization);
        return Optional.ofNullable(accessKeyId)
                .flatMap(iamService::resolveCallerArns)
                .map(IamService.CallerArns::callerArn)
                .orElseGet(() -> regionResolver.buildGlobalArn("iam", "root"));
    }

    /** An integer member on a delegation operation, refused with the code those operations model. */
    private Integer delegationInt(MultivaluedMap<String, String> params, String name) {
        String raw = getParam(params, name);
        if (raw == null) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidInput", "The request was rejected because an invalid or "
                    + "out-of-range value was supplied for '"
                    + Character.toLowerCase(name.charAt(0)) + name.substring(1)
                    + "': Member must be a number", 400);
        }
    }

    /**
     * A boolean member on a delegation operation. The Query protocol writes {@code true} or
     * {@code false}, so anything else is a value the member cannot carry and is refused with the
     * code a malformed number gets rather than read as {@code false}.
     */
    private Boolean parseDelegationBoolean(MultivaluedMap<String, String> params, String name) {
        String raw = getParam(params, name);
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.equalsIgnoreCase("true")) {
            return Boolean.TRUE;
        }
        if (value.equalsIgnoreCase("false")) {
            return Boolean.FALSE;
        }
        throw new AwsException("InvalidInput", "The request was rejected because an invalid or "
                + "out-of-range value was supplied for '"
                + Character.toLowerCase(name.charAt(0)) + name.substring(1)
                + "': Member must be a boolean", 400);
    }

    /** One page of a response list, plus the marker that continues it. */
    private record Page<T>(List<T> items, boolean truncated, String marker) {}

    /**
     * Applies {@code MaxItems} and {@code Marker}. The marker names the last item of the page
     * before by {@code keyOf}, so the next page continues after that item wherever it now sits:
     * the API reference documents it as the place "where the next call should start", and an item
     * created or deleted ahead of it skips nothing and repeats nothing.
     */
    private <T> Page<T> paginate(List<T> items, Function<T, String> keyOf, MultivaluedMap<String, String> params) {
        return paginate(items, keyOf, params, true);
    }

    /**
     * Marker-only pagination, for {@code ListPoliciesGrantingServiceAccess}: its request models
     * {@code Marker} but no {@code MaxItems}, and honouring a member AWS does not declare would be
     * inventing one.
     */
    private <T> Page<T> paginateByMarker(List<T> items, Function<T, String> keyOf,
                                         MultivaluedMap<String, String> params) {
        return paginate(items, keyOf, params, false);
    }

    private <T> Page<T> paginate(List<T> items, Function<T, String> keyOf, MultivaluedMap<String, String> params,
                                 boolean maxItemsModeled) {
        // Every input is validated before the bounds check, so a malformed request is rejected
        // rather than answered emptily just because the marker happens to sit past the end.
        int from = resumeIndex(items, keyOf, params);
        // An omitted MaxItems is not "no limit": the API reference states the count "defaults to
        // 100" on each reader paged here, so a list wider than that is truncated with a Marker as
        // it would be against AWS. Operations that model no MaxItems at all pass through whole.
        int limit = maxItemsModeled ? DEFAULT_MAX_ITEMS : items.size();
        if (maxItemsModeled) {
            Integer asked = validatedMaxItems(params);
            if (asked != null) {
                limit = asked;
            }
        }
        if (from >= items.size()) {
            return new Page<>(List.of(), false, null);
        }
        // Counted from the remaining items rather than added to the offset, so a large MaxItems
        // cannot overflow into a negative index.
        int to = from + Math.min(limit, items.size() - from);
        boolean truncated = to < items.size();
        return new Page<>(List.copyOf(items.subList(from, to)), truncated,
                truncated ? markers.issue(keyOf.apply(items.get(to - 1))) : null);
    }

    /**
     * Where a {@code Marker} resumes: after the item it names. When that item is gone, at the first
     * item whose key sorts after it, since every list whose items can be deleted is ordered by the
     * key its reader passes. A marker this handler did not issue is read as an index, as before.
     */
    private <T> int resumeIndex(List<T> items, Function<T, String> keyOf, MultivaluedMap<String, String> params) {
        String marker = params.getFirst("Marker");
        if (marker == null || marker.isBlank()) {
            return 0;
        }
        String anchor = markers.cursorOf(marker.trim());
        if (anchor == null) {
            return markerIndex(marker);
        }
        for (int i = 0; i < items.size(); i++) {
            if (keyOf.apply(items.get(i)).equals(anchor)) {
                return i + 1;
            }
        }
        for (int i = 0; i < items.size(); i++) {
            if (keyOf.apply(items.get(i)).compareTo(anchor) > 0) {
                return i;
            }
        }
        return items.size();
    }

    /**
     * ListPolicies lists customer-managed policies by name, then the AWS-managed catalog, which
     * never changes. The key keeps that order, so a deleted customer-managed policy resumes at the
     * next one, and names the managed policy by ARN, as a customer policy may share its name.
     */
    private static String policyKey(IamPolicy policy) {
        return AwsManagedPolicies.isManagedPolicyArn(policy.getArn())
                ? "1" + policy.getArn() : "0" + policy.getPolicyName();
    }

    /** The index a marker this handler did not issue resumes at, as markers were read before. */
    private static int markerIndex(String marker) {
        int index;
        try {
            index = Integer.parseInt(marker.trim());
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidInput", "The marker " + marker + " is not valid.", 400);
        }
        if (index < 0) {
            throw new AwsException("InvalidInput", "The marker " + marker + " is not valid.", 400);
        }
        return index;
    }

    /**
     * One candidate policy for {@code ListPoliciesGrantingServiceAccess}. A managed policy carries
     * its ARN; an inline policy has none and is identified by the entity holding it instead.
     */
    private record GrantingPolicy(String policyName, String policyType, String policyArn,
                                  String entityType, String entityName, String document) {

        static GrantingPolicy managed(IamPolicy policy, String document) {
            return new GrantingPolicy(policy.getPolicyName(), "MANAGED", policy.getArn(), null, null, document);
        }

        static GrantingPolicy inline(String policyName, String entityType, String entityName, String document) {
            return new GrantingPolicy(policyName, "INLINE", null, entityType, entityName, document);
        }
    }

    /** What an Access Advisor ARN resolves to: the policies to report on, and who holds them. */
    private record AccessAdvisorTarget(List<String> policyDocuments, List<ServiceLastAccessedEntity> entities) {}

    /**
     * Resolves an Access Advisor ARN to the permissions it stands for and the principals holding
     * them. AWS accepts a user, group, role or managed-policy ARN, and reports the entities that
     * could have used those permissions: a group's users, a policy's attached users and roles, or
     * the named entity itself.
     */
    private AccessAdvisorTarget resolveAccessAdvisorTarget(String accountId, String arn) {
        IamArn parsed = requireIamArn(accountId, arn);
        return switch (parsed.type()) {
            case "user" -> {
                IamUser user = requireMatchingArn(iamService.getUser(parsed.name()).getArn(), arn,
                        iamService.getUser(parsed.name()));
                // The report lists "every AWS service that the resource could access using
                // permissions policies", so a permission the account applies to its users without
                // attaching it counts: a user with no policies of their own still reaches IAM
                // while the password policy allows self-service. It is added to the documents the
                // service list is read from rather than to policiesForIdentity, since it is no
                // policy of the user's and has no name or ARN to report as one.
                List<String> documents = new ArrayList<>(identityPolicyDocuments(accountId, arn));
                documents.addAll(iamService.accountLevelUserGrants(accountId));
                yield new AccessAdvisorTarget(documents, List.of(entityOf(user)));
            }
            case "role" -> {
                IamRole role = requireMatchingArn(iamService.getRole(parsed.name()).getArn(), arn,
                        iamService.getRole(parsed.name()));
                yield new AccessAdvisorTarget(identityPolicyDocuments(accountId, arn), List.of(entityOf(role)));
            }
            case "group" -> {
                IamGroup group = requireMatchingArn(iamService.getGroup(parsed.name()).getArn(), arn,
                        iamService.getGroup(parsed.name()));
                List<ServiceLastAccessedEntity> members = new ArrayList<>();
                for (String member : group.getUserNames()) {
                    // A looked-up-by-name member can be gone by now, since the membership list is a
                    // snapshot: leave that user out rather than failing a report for a group that
                    // does exist.
                    iamService.findUser(member).ifPresent(user -> members.add(entityOf(user)));
                }
                yield new AccessAdvisorTarget(identityPolicyDocuments(accountId, arn), members);
            }
            case "policy" -> {
                IamPolicy policy = iamService.getPolicy(arn);
                IamService.PolicyEntities holders = iamService.listEntitiesForPolicy(arn);
                // A user reached through an attached group could have used the policy exactly as a
                // directly attached one could, so AWS reports it too. Keyed by ARN because a user
                // that is both attached directly and a member of an attached group is one entity,
                // not two.
                Map<String, ServiceLastAccessedEntity> byArn = new LinkedHashMap<>();
                for (IamUser user : holders.users()) {
                    byArn.putIfAbsent(user.getArn(), entityOf(user));
                }
                for (IamGroup group : holders.groups()) {
                    for (String member : group.getUserNames()) {
                        // Same reason as the group branch above: a member named by the snapshot may
                        // already be deleted, and that must not fail a report for a live policy.
                        iamService.findUser(member)
                                .ifPresent(user -> byArn.putIfAbsent(user.getArn(), entityOf(user)));
                    }
                }
                for (IamRole role : holders.roles()) {
                    byArn.putIfAbsent(role.getArn(), entityOf(role));
                }
                yield new AccessAdvisorTarget(List.of(defaultPolicyDocument(policy)),
                        List.copyOf(byArn.values()));
            }
            default -> throw new AwsException("InvalidInput",
                    "The ARN " + arn + " must identify an IAM user, group, role, or policy.", 400);
        };
    }

    private ServiceLastAccessedEntity entityOf(IamUser user) {
        return new ServiceLastAccessedEntity(user.getArn(), user.getUserName(), "USER",
                user.getUserId(), user.getPath());
    }

    private ServiceLastAccessedEntity entityOf(IamRole role) {
        return new ServiceLastAccessedEntity(role.getArn(), role.getRoleName(), "ROLE",
                role.getRoleId(), role.getPath());
    }

    /**
     * Rejects an ARN whose path does not match the resolved entity's own. A name is unique within
     * an account, so the lookup finds the right entity, but an ARN carrying the wrong path names
     * nothing in AWS and must not resolve here either.
     */
    private <T> T requireMatchingArn(String resolvedArn, String requestedArn, T entity) {
        if (!requestedArn.equals(resolvedArn)) {
            throw new AwsException("NoSuchEntity", "The ARN " + requestedArn + " cannot be found.", 404);
        }
        return entity;
    }

    /** An IAM ARN split into the parts needed to resolve it, with the account already checked. */
    private record IamArn(String type, String name) {}

    /**
     * Parses an IAM ARN and rejects one belonging to another account. Without that check a name is
     * looked up in the caller's own account, so an ARN naming a different account would silently
     * report a same-named local identity instead.
     */
    private IamArn requireIamArn(String accountId, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (RuntimeException e) {
            throw new AwsException("InvalidInput", "The ARN " + arn + " is not a valid ARN.", 400);
        }
        if (!"iam".equals(parsed.service())) {
            throw new AwsException("InvalidInput", "The ARN " + arn + " is not a valid IAM ARN.", 400);
        }
        // An AWS-managed policy carries the literal "aws" in the account field
        // (arn:aws:iam::aws:policy/...) and is served from the global catalog, so it is not a
        // foreign account and must not be rejected as one.
        boolean awsManaged = "aws".equals(parsed.accountId()); // partition-literal: managed-policy account
        if (!awsManaged && parsed.accountId() != null && !parsed.accountId().isEmpty()
                && !parsed.accountId().equals(accountId)) {
            throw new AwsException("NoSuchEntity", "The ARN " + arn + " cannot be found.", 404);
        }
        String resource = parsed.resource();
        if (awsManaged && !resource.startsWith("policy/")) {
            throw new AwsException("InvalidInput", "The ARN " + arn + " is not a valid IAM ARN.", 400);
        }
        int slash = resource.indexOf('/');
        if (slash < 0) {
            throw new AwsException("InvalidInput",
                    "The ARN " + arn + " must identify an IAM user, group, role, or policy.", 400);
        }
        return new IamArn(resource.substring(0, slash), resource.substring(resource.lastIndexOf('/') + 1));
    }

    /**
     * The policies AWS says {@code ListPoliciesGrantingServiceAccess} considers for each identity
     * type: a user also inherits its groups' policies, while a group or role contributes only its
     * own. Permissions boundaries are deliberately excluded, as the documentation requires.
     */
    private List<GrantingPolicy> policiesForIdentity(String accountId, String arn) {
        IamArn parsed = requireIamArn(accountId, arn);
        return switch (parsed.type()) {
            case "user" -> {
                IamUser user = iamService.getUser(parsed.name());
                requireMatchingArn(user.getArn(), arn, user);
                List<GrantingPolicy> policies = new ArrayList<>();
                collectUserPolicies(user.getUserName(), policies);
                for (IamGroup group : iamService.listGroupsForUser(user.getUserName())) {
                    collectGroupPolicies(group.getGroupName(), policies);
                }
                yield policies;
            }
            case "group" -> {
                IamGroup group = iamService.getGroup(parsed.name());
                requireMatchingArn(group.getArn(), arn, group);
                List<GrantingPolicy> policies = new ArrayList<>();
                collectGroupPolicies(group.getGroupName(), policies);
                yield policies;
            }
            case "role" -> {
                IamRole role = iamService.getRole(parsed.name());
                requireMatchingArn(role.getArn(), arn, role);
                List<GrantingPolicy> policies = new ArrayList<>();
                for (IamPolicy policy : iamService.listAttachedRolePolicies(role.getRoleName(), null)) {
                    policies.add(GrantingPolicy.managed(policy, defaultPolicyDocument(policy)));
                }
                for (String policyName : iamService.listRolePolicies(role.getRoleName())) {
                    policies.add(GrantingPolicy.inline(policyName, "ROLE", role.getRoleName(),
                            iamService.getRolePolicy(role.getRoleName(), policyName)));
                }
                yield policies;
            }
            default -> throw new AwsException("InvalidInput",
                    "The ARN " + arn + " must identify an IAM user, group, or role.", 400);
        };
    }

    private void collectUserPolicies(String userName, List<GrantingPolicy> policies) {
        for (IamPolicy policy : iamService.listAttachedUserPolicies(userName, null)) {
            policies.add(GrantingPolicy.managed(policy, defaultPolicyDocument(policy)));
        }
        for (String policyName : iamService.listUserPolicies(userName)) {
            policies.add(GrantingPolicy.inline(policyName, "USER", userName,
                    iamService.getUserPolicy(userName, policyName)));
        }
    }

    private void collectGroupPolicies(String groupName, List<GrantingPolicy> policies) {
        for (IamPolicy policy : iamService.listAttachedGroupPolicies(groupName, null)) {
            policies.add(GrantingPolicy.managed(policy, defaultPolicyDocument(policy)));
        }
        for (String policyName : iamService.listGroupPolicies(groupName)) {
            policies.add(GrantingPolicy.inline(policyName, "GROUP", groupName,
                    iamService.getGroupPolicy(groupName, policyName)));
        }
    }

    /**
     * The documents behind an identity ARN, reusing the scoping rules above. These are the
     * identity's own policies only: a grant the account applies without attaching it is no policy
     * of theirs, so the user branch above adds it to what it reports rather than finding it here.
     */
    private List<String> identityPolicyDocuments(String accountId, String arn) {
        List<String> documents = new ArrayList<>();
        for (GrantingPolicy candidate : policiesForIdentity(accountId, arn)) {
            documents.add(candidate.document());
        }
        return documents;
    }

    /** A managed policy grants through whichever version is current, so only that one is read. */
    private String defaultPolicyDocument(IamPolicy policy) {
        return iamService.getPolicyVersion(policy.getArn(), policy.getDefaultVersionId()).getDocument();
    }

    private String contextKeyNamesXml(List<String> keys) {
        XmlBuilder xml = new XmlBuilder().start("ContextKeyNames");
        for (String key : keys) {
            xml.elem("member", key);
        }
        return xml.end("ContextKeyNames").build();
    }

    // =========================================================================
    // XML serialization helpers
    // =========================================================================

    /**
     * {@code detailed} is per-operation, not per-user: ListUsers documents that "IAM
     * resource-listing operations return a subset of the available attributes for the resource.
     * This operation does not return the following attributes, even though they are an attribute
     * of the returned object: PermissionsBoundary, Tags". GetGroup likewise lists its members
     * without tags.
     */
    private String userXml(IamUser u, boolean detailed) {
        return new XmlBuilder()
                .elem("Path", u.getPath())
                .elem("UserName", u.getUserName())
                .elem("UserId", u.getUserId())
                .elem("Arn", u.getArn())
                .elem("CreateDate", isoDate(u.getCreateDate()))
                .raw(detailed ? permissionsBoundaryXml(u.getPermissionsBoundaryArn()) : "")
                .raw(detailed ? tagsElement(u.getTags()) : "")
                .build();
    }

    private String groupXml(IamGroup g) {
        return new XmlBuilder()
                .elem("Path", g.getPath())
                .elem("GroupName", g.getGroupName())
                .elem("GroupId", g.getGroupId())
                .elem("Arn", g.getArn())
                .elem("CreateDate", isoDate(g.getCreateDate()))
                .build();
    }

    /**
     * {@code detailed} is per-operation, not per-role: ListRoles documents that "IAM
     * resource-listing operations return a subset of the available attributes for the resource.
     * This operation does not return the following attributes, even though they are an attribute
     * of the returned object: PermissionsBoundary, RoleLastUsed, Tags". {@code Description} is
     * deliberately not gated — it is absent from that exclusion list. Roles embedded in an
     * instance profile are a subset too, as GetInstanceProfile's own example response shows.
     */
    private String roleXml(IamRole r, boolean detailed) {
        return new XmlBuilder()
                .elem("Path", r.getPath())
                .elem("RoleName", r.getRoleName())
                .elem("RoleId", r.getRoleId())
                .elem("Arn", r.getArn())
                .elem("CreateDate", isoDate(r.getCreateDate()))
                .elem("MaxSessionDuration", (long) r.getMaxSessionDuration())
                .elem("AssumeRolePolicyDocument", r.getAssumeRolePolicyDocument())
                .elem("Description", r.getDescription())
                .raw(detailed ? permissionsBoundaryXml(r.getPermissionsBoundaryArn()) : "")
                .raw(detailed ? tagsElement(r.getTags()) : "")
                .build();
    }

    /**
     * {@code detailed} is per-operation, not per-policy: AWS's own {@code Policy} model documents
     * that {@code Description} "is included in the response to the GetPolicy operation. It is not
     * included in the response to the ListPolicies operation" — CreatePolicy documents neither
     * inclusion nor exclusion, so it's treated the same as GetPolicy. ListPolicies excludes
     * {@code Tags} on the same grounds ("this operation does not return tags"), so one flag
     * governs both.
     */
    private String policyXml(IamPolicy p, boolean detailed) {
        return new XmlBuilder()
                .elem("PolicyName", p.getPolicyName())
                .elem("PolicyId", p.getPolicyId())
                .elem("Arn", p.getArn())
                .elem("Path", p.getPath())
                .elem("DefaultVersionId", p.getDefaultVersionId())
                .elem("AttachmentCount", (long) p.getAttachmentCount())
                .elem("IsAttachable", true)
                .elem("CreateDate", isoDate(p.getCreateDate()))
                .elem("UpdateDate", isoDate(p.getUpdateDate()))
                .elem("Description", detailed ? p.getDescription() : null)
                .raw(detailed ? tagsElement(p.getTags()) : "")
                .build();
    }

    private String policyVersionXml(PolicyVersion v) {
        return new XmlBuilder()
                .elem("Document", v.getDocument())
                .elem("VersionId", v.getVersionId())
                .elem("IsDefaultVersion", v.isDefaultVersion())
                .elem("CreateDate", isoDate(v.getCreateDate()))
                .build();
    }

    private String accessKeyXml(AccessKey k, boolean includeSecret) {
        XmlBuilder xml = new XmlBuilder()
                .elem("UserName", k.getUserName())
                .elem("AccessKeyId", k.getAccessKeyId())
                .elem("Status", k.getStatus());
        if (includeSecret) {
            xml.elem("SecretAccessKey", k.getSecretAccessKey());
        }
        return xml.elem("CreateDate", isoDate(k.getCreateDate())).build();
    }

    // detailed is per-operation, not per-profile, mirroring roleXml/userXml/policyXml:
    // ListInstanceProfiles documents itself as a listing subset ("this operation does not
    // return tags, even though they are an attribute of the returned object"), the same note
    // ListRoles carries. GetInstanceProfile, CreateInstanceProfile, ListInstanceProfilesForRole
    // and the InstanceProfileList embedded in GetAccountAuthorizationDetails's RoleDetail carry
    // no such note, so they stay detailed.
    private String instanceProfileXml(InstanceProfile p, boolean detailed) {
        XmlBuilder xml = new XmlBuilder()
                .elem("InstanceProfileName", p.getInstanceProfileName())
                .elem("InstanceProfileId", p.getInstanceProfileId())
                .elem("Arn", p.getArn())
                .elem("Path", p.getPath())
                .elem("CreateDate", isoDate(p.getCreateDate()))
                .start("Roles");
        for (String roleName : p.getRoleNames()) {
            try {
                IamRole role = iamService.getRole(roleName);
                xml.start("member").raw(roleXml(role, false)).end("member");
            } catch (AwsException ignored) {}
        }
        xml.end("Roles");
        return xml.raw(detailed ? tagsElement(p.getTags()) : "").build();
    }

    private String attachedPoliciesXml(List<IamPolicy> policyList) {
        XmlBuilder xml = new XmlBuilder().start("AttachedPolicies");
        for (IamPolicy p : policyList) {
            xml.start("member")
               .elem("PolicyName", p.getPolicyName())
               .elem("PolicyArn", p.getArn())
               .end("member");
        }
        return xml.end("AttachedPolicies").elem("IsTruncated", false).build();
    }

    private String inlinePolicyNamesXml(List<String> names) {
        XmlBuilder xml = new XmlBuilder().start("PolicyNames");
        for (String name : names) {
            xml.elem("member", name);
        }
        return xml.end("PolicyNames").elem("IsTruncated", false).build();
    }

    /**
     * Renders the {@code <Tags>} wrapper for a resource, or nothing when it has no tags.
     *
     * <p>The AWS SDKs and the Terraform provider read a role's, policy's or user's tags off the
     * Get/Create response rather than by calling List*Tags, so omitting this element makes every
     * tagged resource read back untagged and diff on every plan. Emitting nothing rather than an
     * empty {@code <Tags/>} keeps an untagged resource from reading back as having an empty tag
     * set, which would be a diff of its own.
     */
    private String tagsElement(Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return "";
        }
        return new XmlBuilder().start("Tags").raw(tagsXml(tags)).end("Tags").build();
    }

    private String tagsXml(Map<String, String> tags) {
        XmlBuilder xml = new XmlBuilder();
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            xml.start("member")
               .elem("Key", entry.getKey())
               .elem("Value", entry.getValue())
               .end("member");
        }
        return xml.build();
    }

    // =========================================================================
    // Parameter parsing helpers
    // =========================================================================

    /**
     * Every IAM request that carries tags types them as {@code tagListType}, whose {@code max: 50}
     * binds the members as sent, so the count is taken here, before the map collapses repeated keys.
     * Duplicate keys in the request are rejected with InvalidInput (case-insensitively for users and
     * roles, and case-sensitively for other IAM resource types).
     */
    private Map<String, String> extractTags(MultivaluedMap<String, String> params, boolean caseInsensitiveKeys) {
        List<Map.Entry<String, String>> members = new ArrayList<>();
        for (int i = 1; ; i++) {
            String key = params.getFirst("Tags.member." + i + ".Key");
            String value = params.getFirst("Tags.member." + i + ".Value");
            if (key == null) {
                break;
            }
            members.add(Map.entry(key, value != null ? value : ""));
        }
        checkListLength(members.size(), "tags");
        Set<String> seenKeys = new HashSet<>();
        Map<String, String> tags = new LinkedHashMap<>();
        for (int i = 0; i < members.size(); i++) {
            String key = members.get(i).getKey();
            String value = members.get(i).getValue();
            String at = "tags." + (i + 1) + ".member";
            String keyViolation = tagKeyViolation(key);
            if (keyViolation != null) {
                throw validationError(key, at + ".key", keyViolation);
            }
            if (value.codePointCount(0, value.length()) > MAX_TAG_VALUE_LENGTH) {
                throw validationError(value, at + ".value",
                        "Member must have length less than or equal to " + MAX_TAG_VALUE_LENGTH);
            }
            if (!TAG_VALUE_PATTERN.matcher(value).matches()) {
                throw validationError(value, at + ".value",
                        "Member must satisfy regular expression pattern: " + TAG_VALUE_PATTERN.pattern());
            }
            if (caseInsensitiveKeys) {
                for (String seenKey : seenKeys) {
                    if (seenKey.equalsIgnoreCase(key)) {
                        throw new AwsException("InvalidInput",
                                "Duplicate tag keys found. Please note that Tag keys are case insensitive.", 400);
                    }
                }
                seenKeys.add(key);
            } else {
                if (!seenKeys.add(key)) {
                    throw new AwsException("InvalidInput", "Duplicate tag keys found.", 400);
                }
            }
            tags.put(key, value);
        }
        return tags;
    }

    private static void checkListLength(int members, String param) {
        checkListLength(members, param, MAX_TAG_LIST_MEMBERS);
    }

    /**
     * The constraint-violation shape used for a list member. Unlike the scalar form it leaves the
     * value out, which is Floci's choice rather than a rule AWS follows: captured violations
     * elsewhere in AWS do quote the list, such as Lambda's {@code Value '[x86_64, arm64]' at
     * 'architectures'}, while IAM's own are inconsistent about it. Omitting it keeps a request that
     * breaks the limit from echoing every member back in the error message.
     */
    private static void checkListLength(int members, String param, int limit) {
        if (members > limit) {
            throw SmithyValidationErrors.validationError(
                    "Value at '" + param + "' failed to satisfy constraint: "
                            + "Member must have length less than or equal to " + limit);
        }
    }

    /** Returns the {@code tagKeyType} constraint the key breaks, or null when it is valid. */
    private static String tagKeyViolation(String key) {
        if (key.isEmpty()) {
            return "Member must have length greater than or equal to 1";
        }
        if (key.codePointCount(0, key.length()) > MAX_TAG_KEY_LENGTH) {
            return "Member must have length less than or equal to " + MAX_TAG_KEY_LENGTH;
        }
        if (!TAG_KEY_PATTERN.matcher(key).matches()) {
            return "Member must satisfy regular expression pattern: " + TAG_KEY_PATTERN.pattern();
        }
        return null;
    }

    /**
     * IAM's constraint-violation shape for a scalar member, which leads with the error count and
     * quotes the offending value. Only the bound actually broken is named, as AWS does: a value
     * outside a range reports the side it fell outside, not the whole range.
     */
    private static AwsException validationError(String value, String at, String constraint) {
        return SmithyValidationErrors.validationError(
                "Value '" + value + "' at '" + at + "' failed to satisfy constraint: "
                        + constraint);
    }

    private List<String> getMemberList(MultivaluedMap<String, String> params, String name) {
        List<String> values = new ArrayList<>();
        for (int i = 1; ; i++) {
            String value = params.getFirst(name + ".member." + i);
            if (value == null) break;
            values.add(value);
        }
        return values;
    }

    private List<String> extractTagKeys(MultivaluedMap<String, String> params) {
        List<String> keys = new ArrayList<>();
        for (int i = 1; ; i++) {
            String key = params.getFirst("TagKeys.member." + i);
            if (key == null) break;
            keys.add(key);
        }
        checkListLength(keys.size(), "tagKeys");
        for (String key : keys) {
            String violation = tagKeyViolation(key);
            if (violation != null) {
                throw validationError(keys.toString(), "tagKeys",
                        "Member must satisfy constraint: [" + violation + "]");
            }
        }
        return keys;
    }

    private List<String> extractIndexedValues(MultivaluedMap<String, String> params, String prefix) {
        List<String> values = new ArrayList<>();
        for (int i = 1; ; i++) {
            String value = params.getFirst(prefix + "." + i);
            if (value == null) break;
            values.add(value);
        }
        return values;
    }

    /**
     * Reads every {@code ContextEntries.member.N.ContextKeyValues.member.M}. AWS context keys
     * are set-typed, and the evaluator's set operators quantify over the whole set, so reading
     * only {@code member.1} silently dropped the values a ForAnyValue:/ForAllValues: condition
     * has to see. An entry that names a key but supplies no value is skipped.
     */
    private Map<String, List<String>> extractContextEntries(MultivaluedMap<String, String> params) {
        Map<String, List<String>> context = new HashMap<>();
        for (int i = 1; ; i++) {
            String name = params.getFirst("ContextEntries.member." + i + ".ContextKeyName");
            if (name == null) break;
            List<String> values = extractIndexedValues(
                    params, "ContextEntries.member." + i + ".ContextKeyValues.member");
            if (!values.isEmpty()) {
                context.put(name, values);
            }
        }
        return context;
    }

    private String getParam(MultivaluedMap<String, String> params, String name) {
        return params.getFirst(name);
    }

    /**
     * A required request member. {@link #getParam} returns null for an absent one, which lets a
     * malformed request read as a valid empty answer, so anything the model marks required is read
     * through here instead.
     */
    private String requireParam(MultivaluedMap<String, String> params, String name) {
        String value = params.getFirst(name);
        if (value == null || value.isBlank()) {
            throw SmithyValidationErrors.validationError(
                    SmithyValidationErrors.notNull(memberPath(name)));
        }
        return value;
    }

    /** The member's name as a validation message spells it, which is the request's with a lower
     * first letter. */
    private static String memberPath(String name) {
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    /**
     * A member held to the length and the pattern its shape declares, reporting one violation per
     * constraint broken and naming only the length bound the value missed, which is how every
     * recorded IAM length violation reads.
     */
    private static void requireMemberShape(String value, String path, int min, int max,
                                           Pattern pattern) {
        List<String> violations = new ArrayList<>();
        if (value.length() < min) {
            violations.add("Member must have length greater than or equal to " + min);
        } else if (value.length() > max) {
            violations.add("Member must have length less than or equal to " + max);
        }
        if (!pattern.matcher(value).matches()) {
            violations.add("Member must satisfy regular expression pattern: " + pattern.pattern());
        }
        if (violations.isEmpty()) {
            return;
        }
        throw SmithyValidationErrors.validationError(violations.stream()
                .map(violation -> "Value at '" + path + "' failed to satisfy constraint: " + violation)
                .toList());
    }

    /**
     * An integer member read before the lookup it precedes. A value that is not a number answers
     * {@code InvalidInput}, as {@code MaxItems} does, since neither of the two members read here
     * publishes bounds to report a range against.
     */
    private void requireIntegerIfPresent(MultivaluedMap<String, String> params, String name) {
        String raw = params.getFirst(name);
        if (raw == null || raw.isBlank()) {
            return;
        }
        try {
            Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidInput",
                    "The value " + raw + " at '" + memberPath(name) + "' is not a number.", 400);
        }
    }

    private int getIntParam(MultivaluedMap<String, String> params, String name, int defaultValue) {
        String value = params.getFirst(name);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    Response xmlErrorResponse(String code, String message, int status) {
        return AwsQueryResponse.error(code, message, AwsNamespaces.IAM, status);
    }

    private String isoDate(Instant instant) {
        if (instant == null) return "";
        return DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.MILLIS));
    }

    /** For a member AWS types as a blob, which the Query protocol carries base64-encoded. */
    private String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private Response handleTagInstanceProfile(MultivaluedMap<String, String> params) {
        iamService.tagInstanceProfile(getParam(params, "InstanceProfileName"), extractTags(params, false));
        return Response.ok(AwsQueryResponse.envelopeNoResult("TagInstanceProfile", AwsNamespaces.IAM)).build();
    }

    private Response handleUntagInstanceProfile(MultivaluedMap<String, String> params) {
        iamService.untagInstanceProfile(getParam(params, "InstanceProfileName"), extractTagKeys(params));
        return Response.ok(AwsQueryResponse.envelopeNoResult("UntagInstanceProfile", AwsNamespaces.IAM)).build();
    }

    private Response handleListInstanceProfileTags(MultivaluedMap<String, String> params) {
        String instanceProfileName = getParam(params, "InstanceProfileName");
        // AWS documents the result as sorted by tag key.
        Map<String, String> tags = new TreeMap<>(iamService.listInstanceProfileTags(instanceProfileName));
        String result = new XmlBuilder().start("Tags").raw(tagsXml(tags)).end("Tags")
                .elem("IsTruncated", false).build();
        return Response.ok(AwsQueryResponse.envelope("ListInstanceProfileTags", AwsNamespaces.IAM, result)).build();
    }
}
