package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.*;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.iam.model.AccessKey;
import io.github.hectorvent.floci.services.iam.model.AccountPasswordPolicy;
import io.github.hectorvent.floci.services.iam.model.IamGroup;
import io.github.hectorvent.floci.services.iam.model.IamPolicy;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.IamUser;
import io.github.hectorvent.floci.services.iam.model.InstanceProfile;
import io.github.hectorvent.floci.services.iam.model.LoginProfile;
import io.github.hectorvent.floci.services.iam.model.OpenIDConnectProvider;
import io.github.hectorvent.floci.services.iam.model.SAMLProvider;
import io.github.hectorvent.floci.services.iam.model.PolicyVersion;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Query-protocol handler for IAM actions.
 * Receives pre-dispatched calls from {@link AwsQueryController}.
 * All responses use the IAM XML namespace {@code https://iam.amazonaws.com/doc/2010-05-08/}.
 */
@ApplicationScoped
public class IamQueryHandler {

    private static final Logger LOG = Logger.getLogger(IamQueryHandler.class);

    private final IamService iamService;
    private final IamPolicyEvaluator policyEvaluator;
    private final AccountResolver accountResolver;
    private final SAMLProviderService samlProviderService;
    private final RegionResolver regionResolver;

    @Inject
    public IamQueryHandler(IamService iamService, IamPolicyEvaluator policyEvaluator,
                           AccountResolver accountResolver, SAMLProviderService samlProviderService,
                           RegionResolver regionResolver) {
        this.iamService = iamService;
        this.policyEvaluator = policyEvaluator;
        this.accountResolver = accountResolver;
        this.samlProviderService = samlProviderService;
        this.regionResolver = regionResolver;
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
            case "TagUser" -> handleTagUser(params);
            case "UntagUser" -> handleUntagUser(params);
            case "ListUserTags" -> handleListUserTags(params);
            case "ListMFADevices" -> handleListMFADevices(params);
            case "CreateLoginProfile" -> handleCreateLoginProfile(params, authorization);
            case "GetLoginProfile" -> handleGetLoginProfile(params, authorization);
            case "UpdateLoginProfile" -> handleUpdateLoginProfile(params);
            case "DeleteLoginProfile" -> handleDeleteLoginProfile(params, authorization);

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
            case "ListServerCertificates" -> handleListServerCertificates(params);

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

            // Managed Policies
            case "CreatePolicy" -> handleCreatePolicy(params);
            case "GetPolicy" -> handleGetPolicy(params);
            case "DeletePolicy" -> handleDeletePolicy(params);
            case "ListPolicies" -> handleListPolicies(params);
            case "ListEntitiesForPolicy" -> handleListEntitiesForPolicy(params);
            case "GetAccountSummary" -> handleGetAccountSummary(params);
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
            case "UpdateAccessKey" -> handleUpdateAccessKey(params);
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
        Map<String, String> tags = extractTags(params);
        IamUser user = iamService.createUser(userName, path);
        if (!tags.isEmpty()) iamService.tagUser(userName, tags);
        user = iamService.getUser(userName);
        String result = new XmlBuilder().start("User").raw(userXml(user, true)).end("User").build();
        return Response.ok(AwsQueryResponse.envelope("CreateUser", AwsNamespaces.IAM, result)).build();
    }

    // UserName is optional on GetUser/ListAccessKeys/DeleteAccessKey: real AWS determines
    // it implicitly from the access key that signed the request. Mirrors moto: an access
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
        var xml = new XmlBuilder().start("Users");
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
        iamService.tagUser(userName, extractTags(params));
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

    private Response handleListMFADevices(MultivaluedMap<String, String> params) {
        // MFA device state is not modeled; return the wire-accurate empty result
        // (MFADevices list + IsTruncated=false). Real AWS returns NoSuchEntity
        // (HTTP 404) for an unknown user — Floci returns an empty list regardless.
        String result = new XmlBuilder()
                .start("MFADevices").end("MFADevices")
                .elem("IsTruncated", false)
                .build();
        return Response.ok(AwsQueryResponse.envelope("ListMFADevices", AwsNamespaces.IAM, result)).build();
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

    private String loginProfileXml(LoginProfile profile) {
        return new XmlBuilder()
                .elem("PasswordResetRequired", profile.isPasswordResetRequired())
                .elem("UserName", profile.getUserName())
                .elem("CreateDate", isoDate(profile.getCreateDate()))
                .build();
    }

    private Response handleListSAMLProviders(String authorization) {
        var xml = new XmlBuilder().start("SAMLProviderList");
        for (SAMLProvider provider : samlProviderService.list(accountResolver.resolve(authorization))) {
            xml.start("member").elem("Arn", provider.getArn()).end("member");
        }
        xml.end("SAMLProviderList");
        return Response.ok(AwsQueryResponse.envelope("ListSAMLProviders", AwsNamespaces.IAM, xml.build())).build();
    }

    private Response handleCreateSAMLProvider(MultivaluedMap<String, String> params, String authorization) {
        checkSamlTagMembers(params);
        SAMLProvider provider = samlProviderService.create(regionResolver.getPartition(),
                accountResolver.resolve(authorization), getParam(params, "Name"),
                getParam(params, "SAMLMetadataDocument"), extractTags(params));
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
        checkSamlTagMembers(params);
        samlProviderService.tag(accountId, getParam(params, "SAMLProviderArn"), extractTags(params));
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
        var xml = new XmlBuilder().start("OpenIDConnectProviderList");
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
                extractTags(params));
        var xml = new XmlBuilder().elem("OpenIDConnectProviderArn", provider.getArn());
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
        var xml = new XmlBuilder().elem("Url", provider.getUrl());
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
        iamService.tagOpenIDConnectProvider(getParam(params, "OpenIDConnectProviderArn"), extractTags(params));
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

    private Response handleListServerCertificates(MultivaluedMap<String, String> params) {
        // Server certificates are not modeled; return an empty paginated list.
        String result = new XmlBuilder()
                .start("ServerCertificateMetadataList").end("ServerCertificateMetadataList")
                .elem("IsTruncated", false)
                .build();
        return Response.ok(AwsQueryResponse.envelope("ListServerCertificates", AwsNamespaces.IAM, result)).build();
    }

    // =========================================================================
    // Account Aliases
    // =========================================================================

    // ListAccountAliases is paginated on the wire (IsTruncated) even though an account can only
    // ever hold one alias, so the envelope carries the flag to match the AWS response shape.
    private Response handleListAccountAliases(MultivaluedMap<String, String> params) {
        var xml = new XmlBuilder().start("AccountAliases");
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
        var xml = new XmlBuilder().start("PasswordPolicy")
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
        var xml = new XmlBuilder()
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
        var xml = new XmlBuilder().start("Groups");
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
        var xml = new XmlBuilder().start("Groups");
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
        Map<String, String> tags = extractTags(params);
        IamRole role = iamService.createRole(roleName, path, trustPolicy, description, maxSession, tags);
        String result = new XmlBuilder().start("Role").raw(roleXml(role, true)).end("Role").build();
        return Response.ok(AwsQueryResponse.envelope("CreateRole", AwsNamespaces.IAM, result)).build();
    }

    private Response handleGetRole(MultivaluedMap<String, String> params) {
        IamRole role = iamService.getRole(getParam(params, "RoleName"));
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
        List<IamRole> roleList = iamService.listRoles(getParam(params, "PathPrefix"));
        var xml = new XmlBuilder().start("Roles");
        for (IamRole r : roleList) {
            xml.start("member").raw(roleXml(r, false)).end("member");
        }
        xml.end("Roles").elem("IsTruncated", false);
        return Response.ok(AwsQueryResponse.envelope("ListRoles", AwsNamespaces.IAM, xml.build())).build();
    }

    private Response handleUpdateRole(MultivaluedMap<String, String> params) {
        iamService.updateRole(getParam(params, "RoleName"),
                getParam(params, "Description"),
                getIntParam(params, "MaxSessionDuration", 0));
        return Response.ok(AwsQueryResponse.envelopeNoResult("UpdateRole", AwsNamespaces.IAM)).build();
    }

    private Response handleUpdateAssumeRolePolicy(MultivaluedMap<String, String> params) {
        iamService.updateAssumeRolePolicy(getParam(params, "RoleName"),
                getParam(params, "PolicyDocument"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("UpdateAssumeRolePolicy", AwsNamespaces.IAM)).build();
    }

    private Response handleTagRole(MultivaluedMap<String, String> params) {
        iamService.tagRole(getParam(params, "RoleName"), extractTags(params));
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
    // Managed Policies
    // =========================================================================

    private Response handleCreatePolicy(MultivaluedMap<String, String> params) {
        String policyName = getParam(params, "PolicyName");
        String path = getParam(params, "Path");
        String description = getParam(params, "Description");
        String document = getParam(params, "PolicyDocument");
        Map<String, String> tags = extractTags(params);
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
        List<IamPolicy> policyList = iamService.listPolicies(
                getParam(params, "Scope"), getParam(params, "PathPrefix"));
        var xml = new XmlBuilder().start("Policies");
        for (IamPolicy p : policyList) {
            xml.start("member").raw(policyXml(p, false)).end("member");
        }
        xml.end("Policies").elem("IsTruncated", false);
        return Response.ok(AwsQueryResponse.envelope("ListPolicies", AwsNamespaces.IAM, xml.build())).build();
    }

    private Response handleListEntitiesForPolicy(MultivaluedMap<String, String> params) {
        IamService.PolicyEntities entities = iamService.listEntitiesForPolicy(getParam(params, "PolicyArn"));
        var xml = new XmlBuilder().start("PolicyGroups");
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
        var xml = new XmlBuilder().start("SummaryMap");
        for (Map.Entry<String, Long> entry : iamService.getAccountSummary().entrySet()) {
            xml.start("entry").elem("key", entry.getKey()).elem("value", entry.getValue()).end("entry");
        }
        xml.end("SummaryMap");
        return Response.ok(AwsQueryResponse.envelope("GetAccountSummary", AwsNamespaces.IAM, xml.build())).build();
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
                .raw(permissionsBoundaryXml(u.getPermissionsBoundaryArn()))
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
        var xml = new XmlBuilder().start("Versions");
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
        iamService.tagPolicy(getParam(params, "PolicyArn"), extractTags(params));
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
        var xml = new XmlBuilder().start("AccessKeyMetadata");
        for (AccessKey k : keys) {
            xml.start("member").raw(accessKeyXml(k, false)).end("member");
        }
        xml.end("AccessKeyMetadata").elem("IsTruncated", false);
        return Response.ok(AwsQueryResponse.envelope("ListAccessKeys", AwsNamespaces.IAM, xml.build())).build();
    }

    private Response handleUpdateAccessKey(MultivaluedMap<String, String> params) {
        iamService.updateAccessKey(getParam(params, "UserName"),
                getParam(params, "AccessKeyId"), getParam(params, "Status"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("UpdateAccessKey", AwsNamespaces.IAM)).build();
    }

    private Response handleGetAccessKeyLastUsed(MultivaluedMap<String, String> params) {
        // Access-key usage is not modeled, so return the IAM API's documented
        // "never used" shape: an AccessKeyLastUsed with ServiceName=N/A and Region=N/A
        // and NO LastUsedDate. UserName is optional and its type is non-empty
        // (length 1-128); since the emulator exposes no AccessKeyId→UserName lookup,
        // the element is omitted rather than emitted empty — an empty string could be
        // rejected by a strict client, and callers that need the owner already have it
        // from the preceding ListAccessKeys call.
        String result = new XmlBuilder()
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
        return rootFeaturesResponse("ListOrganizationsFeatures", iamService.listOrganizationsFeatures());
    }

    private Response handleEnableOrganizationsRootCredentialsManagement(MultivaluedMap<String, String> params) {
        return rootFeaturesResponse("EnableOrganizationsRootCredentialsManagement",
                iamService.enableOrganizationsRootCredentialsManagement());
    }

    private Response handleEnableOrganizationsRootSessions(MultivaluedMap<String, String> params) {
        return rootFeaturesResponse("EnableOrganizationsRootSessions",
                iamService.enableOrganizationsRootSessions());
    }

    private Response handleDisableOrganizationsRootCredentialsManagement(MultivaluedMap<String, String> params) {
        return rootFeaturesResponse("DisableOrganizationsRootCredentialsManagement",
                iamService.disableOrganizationsRootCredentialsManagement());
    }

    private Response handleDisableOrganizationsRootSessions(MultivaluedMap<String, String> params) {
        return rootFeaturesResponse("DisableOrganizationsRootSessions",
                iamService.disableOrganizationsRootSessions());
    }

    /**
     * Builds the shared {@code <EnabledFeatures><member>..</member></EnabledFeatures>} result used by
     * both {@code ListOrganizationsFeatures} and the enable/disable ops. The org-scoped
     * {@code OrganizationId} is intentionally omitted — LZA's root-user-management module reads only
     * the enabled-feature set, and omitting it avoids coupling the IAM endpoint to Organizations.
     */
    private Response rootFeaturesResponse(String action, List<String> features) {
        XmlBuilder xml = new XmlBuilder().start("EnabledFeatures");
        for (String feature : features) {
            xml.elem("member", feature);
        }
        String result = xml.end("EnabledFeatures").build();
        return Response.ok(AwsQueryResponse.envelope(action, AwsNamespaces.IAM, result)).build();
    }

    // =========================================================================
    // Instance Profiles
    // =========================================================================

    private Response handleCreateInstanceProfile(MultivaluedMap<String, String> params) {
        Map<String, String> tags = extractTags(params);
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
        var xml = new XmlBuilder().start("InstanceProfiles");
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
        var xml = new XmlBuilder().start("InstanceProfiles");
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
        String userName = getParam(params, "UserName");
        String boundaryArn = getParam(params, "PermissionsBoundary");
        iamService.putUserPermissionsBoundary(userName, boundaryArn);
        return Response.ok(AwsQueryResponse.envelope("PutUserPermissionsBoundary", AwsNamespaces.IAM, "")).build();
    }

    private Response handleDeleteUserPermissionsBoundary(MultivaluedMap<String, String> params) {
        String userName = getParam(params, "UserName");
        iamService.deleteUserPermissionsBoundary(userName);
        return Response.ok(AwsQueryResponse.envelope("DeleteUserPermissionsBoundary", AwsNamespaces.IAM, "")).build();
    }

    private Response handlePutRolePermissionsBoundary(MultivaluedMap<String, String> params) {
        String roleName = getParam(params, "RoleName");
        String boundaryArn = getParam(params, "PermissionsBoundary");
        iamService.putRolePermissionsBoundary(roleName, boundaryArn);
        return Response.ok(AwsQueryResponse.envelope("PutRolePermissionsBoundary", AwsNamespaces.IAM, "")).build();
    }

    private Response handleDeleteRolePermissionsBoundary(MultivaluedMap<String, String> params) {
        String roleName = getParam(params, "RoleName");
        iamService.deleteRolePermissionsBoundary(roleName);
        return Response.ok(AwsQueryResponse.envelope("DeleteRolePermissionsBoundary", AwsNamespaces.IAM, "")).build();
    }

    private Response handleSimulatePrincipalPolicy(MultivaluedMap<String, String> params) {
        String policySourceArn = getParam(params, "PolicySourceArn");
        CallerContext caller = iamService.resolvePrincipalContext(policySourceArn);
        List<String> actionNames = requireActionNames(params);
        List<String> resourceArns = extractResourceArnsOrWildcard(params);
        Map<String, List<String>> context = extractContextEntries(params);
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
        String policySourceArn = getParam(params, "PolicySourceArn");
        CallerContext caller = iamService.resolvePrincipalContext(policySourceArn);
        List<String> allDocuments = new ArrayList<>(caller.identityPolicies());
        allDocuments.addAll(extractIndexedValues(params, "PolicyInputList.member"));
        List<String> keys = policyEvaluator.contextKeysReferencedIn(allDocuments);
        return Response.ok(AwsQueryResponse.envelope("GetContextKeysForPrincipalPolicy", AwsNamespaces.IAM,
                contextKeyNamesXml(keys))).build();
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
        var xml = new XmlBuilder()
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
        var xml = new XmlBuilder()
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
        var xml = new XmlBuilder().start("AttachedPolicies");
        for (IamPolicy p : policyList) {
            xml.start("member")
               .elem("PolicyName", p.getPolicyName())
               .elem("PolicyArn", p.getArn())
               .end("member");
        }
        return xml.end("AttachedPolicies").elem("IsTruncated", false).build();
    }

    private String inlinePolicyNamesXml(List<String> names) {
        var xml = new XmlBuilder().start("PolicyNames");
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
        var xml = new XmlBuilder();
        for (var entry : tags.entrySet()) {
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
     * Enforces {@code tagListType}'s {@code max: 50} on the members as sent. extractTags collapses
     * repeated keys into a map, and AWS resolves a repeated key by overwriting rather than
     * rejecting, so counting the map would let a 51-member list through whenever any key repeats.
     */
    private void checkSamlTagMembers(MultivaluedMap<String, String> params) {
        int members = 0;
        while (params.getFirst("Tags.member." + (members + 1) + ".Key") != null) {
            members++;
        }
        if (members > SAMLProviderService.MAX_TAGS_PER_SAML_PROVIDER) {
            throw new AwsException("ValidationError",
                    "Value at 'tags' failed to satisfy constraint: Member must have length "
                            + "less than or equal to " + SAMLProviderService.MAX_TAGS_PER_SAML_PROVIDER, 400);
        }
    }

    private Map<String, String> extractTags(MultivaluedMap<String, String> params) {
        Map<String, String> tags = new HashMap<>();
        for (int i = 1; ; i++) {
            String key = params.getFirst("Tags.member." + i + ".Key");
            String value = params.getFirst("Tags.member." + i + ".Value");
            if (key == null) break;
            tags.put(key, value != null ? value : "");
        }
        return tags;
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
        return DateTimeFormatter.ISO_INSTANT.format(instant);
    }

    private Response handleTagInstanceProfile(MultivaluedMap<String, String> params) {
        iamService.tagInstanceProfile(getParam(params, "InstanceProfileName"), extractTags(params));
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
