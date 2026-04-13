package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.*;
import io.github.hectorvent.floci.services.iam.model.AccessKey;
import io.github.hectorvent.floci.services.iam.model.IamGroup;
import io.github.hectorvent.floci.services.iam.model.IamPolicy;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.IamUser;
import io.github.hectorvent.floci.services.iam.model.InstanceProfile;
import io.github.hectorvent.floci.services.iam.model.PolicyVersion;
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

    @Inject
    public IamQueryHandler(IamService iamService) {
        this.iamService = iamService;
    }

    public Response handle(String action, MultivaluedMap<String, String> params) {
        LOG.debugv("IAM action: {0}", action);

        try {
            return switch (action) {
            // Users
            case "CreateUser" -> handleCreateUser(params);
            case "GetUser" -> handleGetUser(params);
            case "DeleteUser" -> handleDeleteUser(params);
            case "ListUsers" -> handleListUsers(params);
            case "UpdateUser" -> handleUpdateUser(params);
            case "TagUser" -> handleTagUser(params);
            case "UntagUser" -> handleUntagUser(params);
            case "ListUserTags" -> handleListUserTags(params);

            // Groups
            case "CreateGroup" -> handleCreateGroup(params);
            case "GetGroup" -> handleGetGroup(params);
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
            case "UpdateAssumeRolePolicy" -> handleUpdateAssumeRolePolicy(params);
            case "TagRole" -> handleTagRole(params);
            case "UntagRole" -> handleUntagRole(params);
            case "ListRoleTags" -> handleListRoleTags(params);

            // Managed Policies
            case "CreatePolicy" -> handleCreatePolicy(params);
            case "GetPolicy" -> handleGetPolicy(params);
            case "DeletePolicy" -> handleDeletePolicy(params);
            case "ListPolicies" -> handleListPolicies(params);
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
            case "DeleteAccessKey" -> handleDeleteAccessKey(params);
            case "ListAccessKeys" -> handleListAccessKeys(params);
            case "UpdateAccessKey" -> handleUpdateAccessKey(params);

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
        String result = new XmlBuilder().start("User").raw(userXml(user)).end("User").build();
        return Response.ok(AwsQueryResponse.envelope("CreateUser", AwsNamespaces.IAM, result)).build();
    }

    private Response handleGetUser(MultivaluedMap<String, String> params) {
        String userName = getParam(params, "UserName");
        IamUser user = iamService.getUser(userName != null ? userName : "root");
        String result = new XmlBuilder().start("User").raw(userXml(user)).end("User").build();
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
            xml.start("member").raw(userXml(u)).end("member");
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
            xml.start("member").raw(userXml(u)).end("member");
        }
        xml.end("Users").elem("IsTruncated", false);
        return Response.ok(AwsQueryResponse.envelope("GetGroup", AwsNamespaces.IAM, xml.build())).build();
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
        String result = new XmlBuilder().start("Role").raw(roleXml(role)).end("Role").build();
        return Response.ok(AwsQueryResponse.envelope("CreateRole", AwsNamespaces.IAM, result)).build();
    }

    private Response handleGetRole(MultivaluedMap<String, String> params) {
        IamRole role = iamService.getRole(getParam(params, "RoleName"));
        String result = new XmlBuilder().start("Role").raw(roleXml(role)).end("Role").build();
        return Response.ok(AwsQueryResponse.envelope("GetRole", AwsNamespaces.IAM, result)).build();
    }

    private Response handleDeleteRole(MultivaluedMap<String, String> params) {
        iamService.deleteRole(getParam(params, "RoleName"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("DeleteRole", AwsNamespaces.IAM)).build();
    }

    private Response handleListRoles(MultivaluedMap<String, String> params) {
        List<IamRole> roleList = iamService.listRoles(getParam(params, "PathPrefix"));
        var xml = new XmlBuilder().start("Roles");
        for (IamRole r : roleList) {
            xml.start("member").raw(roleXml(r)).end("member");
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
        String result = new XmlBuilder().start("Policy").raw(policyXml(policy)).end("Policy").build();
        return Response.ok(AwsQueryResponse.envelope("CreatePolicy", AwsNamespaces.IAM, result)).build();
    }

    private Response handleGetPolicy(MultivaluedMap<String, String> params) {
        IamPolicy policy = iamService.getPolicy(getParam(params, "PolicyArn"));
        String result = new XmlBuilder().start("Policy").raw(policyXml(policy)).end("Policy").build();
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
            xml.start("member").raw(policyXml(p)).end("member");
        }
        xml.end("Policies").elem("IsTruncated", false);
        return Response.ok(AwsQueryResponse.envelope("ListPolicies", AwsNamespaces.IAM, xml.build())).build();
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

    private Response handleDeleteAccessKey(MultivaluedMap<String, String> params) {
        iamService.deleteAccessKey(getParam(params, "UserName"), getParam(params, "AccessKeyId"));
        return Response.ok(AwsQueryResponse.envelopeNoResult("DeleteAccessKey", AwsNamespaces.IAM)).build();
    }

    private Response handleListAccessKeys(MultivaluedMap<String, String> params) {
        List<AccessKey> keys = iamService.listAccessKeys(getParam(params, "UserName"));
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

    // =========================================================================
    // Instance Profiles
    // =========================================================================

    private Response handleCreateInstanceProfile(MultivaluedMap<String, String> params) {
        InstanceProfile profile = iamService.createInstanceProfile(
                getParam(params, "InstanceProfileName"), getParam(params, "Path"));
        String result = new XmlBuilder().start("InstanceProfile").raw(instanceProfileXml(profile)).end("InstanceProfile").build();
        return Response.ok(AwsQueryResponse.envelope("CreateInstanceProfile", AwsNamespaces.IAM, result)).build();
    }

    private Response handleGetInstanceProfile(MultivaluedMap<String, String> params) {
        InstanceProfile profile = iamService.getInstanceProfile(getParam(params, "InstanceProfileName"));
        String result = new XmlBuilder().start("InstanceProfile").raw(instanceProfileXml(profile)).end("InstanceProfile").build();
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
            xml.start("member").raw(instanceProfileXml(p)).end("member");
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
            xml.start("member").raw(instanceProfileXml(p)).end("member");
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

    // =========================================================================
    // XML serialization helpers
    // =========================================================================

    private String userXml(IamUser u) {
        return new XmlBuilder()
                .elem("Path", u.getPath())
                .elem("UserName", u.getUserName())
                .elem("UserId", u.getUserId())
                .elem("Arn", u.getArn())
                .elem("CreateDate", isoDate(u.getCreateDate()))
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

    private String roleXml(IamRole r) {
        return new XmlBuilder()
                .elem("Path", r.getPath())
                .elem("RoleName", r.getRoleName())
                .elem("RoleId", r.getRoleId())
                .elem("Arn", r.getArn())
                .elem("CreateDate", isoDate(r.getCreateDate()))
                .elem("MaxSessionDuration", (long) r.getMaxSessionDuration())
                .elem("AssumeRolePolicyDocument", r.getAssumeRolePolicyDocument())
                .elem("Description", r.getDescription())
                .build();
    }

    private String policyXml(IamPolicy p) {
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

    private String instanceProfileXml(InstanceProfile p) {
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
                xml.start("member").raw(roleXml(role)).end("member");
            } catch (AwsException ignored) {}
        }
        return xml.end("Roles").build();
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

    private List<String> extractTagKeys(MultivaluedMap<String, String> params) {
        List<String> keys = new ArrayList<>();
        for (int i = 1; ; i++) {
            String key = params.getFirst("TagKeys.member." + i);
            if (key == null) break;
            keys.add(key);
        }
        return keys;
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
}
