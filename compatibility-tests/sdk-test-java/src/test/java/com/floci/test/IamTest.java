package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AddRoleToInstanceProfileRequest;
import software.amazon.awssdk.services.iam.model.AddUserToGroupRequest;
import software.amazon.awssdk.services.iam.model.AttachRolePolicyRequest;
import software.amazon.awssdk.services.iam.model.AttachUserPolicyRequest;
import software.amazon.awssdk.services.iam.model.CreateAccessKeyRequest;
import software.amazon.awssdk.services.iam.model.CreateAccessKeyResponse;
import software.amazon.awssdk.services.iam.model.CreateGroupRequest;
import software.amazon.awssdk.services.iam.model.CreateGroupResponse;
import software.amazon.awssdk.services.iam.model.CreateInstanceProfileRequest;
import software.amazon.awssdk.services.iam.model.CreateInstanceProfileResponse;
import software.amazon.awssdk.services.iam.model.CreatePolicyRequest;
import software.amazon.awssdk.services.iam.model.CreatePolicyResponse;
import software.amazon.awssdk.services.iam.model.CreateRoleRequest;
import software.amazon.awssdk.services.iam.model.CreateRoleResponse;
import software.amazon.awssdk.services.iam.model.CreateUserRequest;
import software.amazon.awssdk.services.iam.model.CreateUserResponse;
import software.amazon.awssdk.services.iam.model.DeleteAccessKeyRequest;
import software.amazon.awssdk.services.iam.model.DeleteGroupRequest;
import software.amazon.awssdk.services.iam.model.DeleteInstanceProfileRequest;
import software.amazon.awssdk.services.iam.model.DeletePolicyRequest;
import software.amazon.awssdk.services.iam.model.DeleteRoleRequest;
import software.amazon.awssdk.services.iam.model.DeleteRolePolicyRequest;
import software.amazon.awssdk.services.iam.model.DeleteUserRequest;
import software.amazon.awssdk.services.iam.model.DetachRolePolicyRequest;
import software.amazon.awssdk.services.iam.model.DetachUserPolicyRequest;
import software.amazon.awssdk.services.iam.model.GetGroupRequest;
import software.amazon.awssdk.services.iam.model.GetGroupResponse;
import software.amazon.awssdk.services.iam.model.GetInstanceProfileRequest;
import software.amazon.awssdk.services.iam.model.GetInstanceProfileResponse;
import software.amazon.awssdk.services.iam.model.GetPolicyRequest;
import software.amazon.awssdk.services.iam.model.GetPolicyResponse;
import software.amazon.awssdk.services.iam.model.GetRoleRequest;
import software.amazon.awssdk.services.iam.model.GetRoleResponse;
import software.amazon.awssdk.services.iam.model.GetRolePolicyRequest;
import software.amazon.awssdk.services.iam.model.GetRolePolicyResponse;
import software.amazon.awssdk.services.iam.model.GetUserRequest;
import software.amazon.awssdk.services.iam.model.GetUserResponse;
import software.amazon.awssdk.services.iam.model.ListAccessKeysRequest;
import software.amazon.awssdk.services.iam.model.ListAccessKeysResponse;
import software.amazon.awssdk.services.iam.model.ListAttachedRolePoliciesRequest;
import software.amazon.awssdk.services.iam.model.ListAttachedRolePoliciesResponse;
import software.amazon.awssdk.services.iam.model.ListAttachedUserPoliciesRequest;
import software.amazon.awssdk.services.iam.model.ListAttachedUserPoliciesResponse;
import software.amazon.awssdk.services.iam.model.ListGroupsForUserRequest;
import software.amazon.awssdk.services.iam.model.ListGroupsForUserResponse;
import software.amazon.awssdk.services.iam.model.ListInstanceProfilesResponse;
import software.amazon.awssdk.services.iam.model.ListRolePoliciesRequest;
import software.amazon.awssdk.services.iam.model.ListRolePoliciesResponse;
import software.amazon.awssdk.services.iam.model.ListRolesResponse;
import software.amazon.awssdk.services.iam.model.ListUserTagsRequest;
import software.amazon.awssdk.services.iam.model.ListUserTagsResponse;
import software.amazon.awssdk.services.iam.model.ListUsersResponse;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;
import software.amazon.awssdk.services.iam.model.PutRolePolicyRequest;
import software.amazon.awssdk.services.iam.model.RemoveRoleFromInstanceProfileRequest;
import software.amazon.awssdk.services.iam.model.RemoveUserFromGroupRequest;
import software.amazon.awssdk.services.iam.model.StatusType;
import software.amazon.awssdk.services.iam.model.TagUserRequest;
import software.amazon.awssdk.services.iam.model.UntagUserRequest;
import software.amazon.awssdk.services.iam.model.UpdateAccessKeyRequest;

import static org.assertj.core.api.Assertions.*;

@DisplayName("IAM Identity and Access Management")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IamTest {

    private static IamClient iam;
    private static final String USER_NAME = "sdk-test-user";
    private static final String GROUP_NAME = "sdk-test-group";
    private static final String ROLE_NAME = "sdk-test-role";
    private static final String POLICY_NAME = "sdk-test-policy";
    private static final String INSTANCE_PROFILE_NAME = "sdk-test-profile";
    private static final String TRUST_POLICY = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Principal\":{\"Service\":\"lambda.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";
    private static final String POLICY_DOCUMENT = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}";
    private static String policyArn;
    private static String accessKeyId;

    @BeforeAll
    static void setup() {
        iam = TestFixtures.iamClient();
    }

    @AfterAll
    static void cleanup() {
        if (iam != null) {
            try {
                iam.removeRoleFromInstanceProfile(RemoveRoleFromInstanceProfileRequest.builder()
                        .instanceProfileName(INSTANCE_PROFILE_NAME).roleName(ROLE_NAME).build());
            } catch (Exception ignored) {}
            try {
                iam.deleteInstanceProfile(DeleteInstanceProfileRequest.builder()
                        .instanceProfileName(INSTANCE_PROFILE_NAME).build());
            } catch (Exception ignored) {}
            try {
                iam.deleteRolePolicy(DeleteRolePolicyRequest.builder()
                        .roleName(ROLE_NAME).policyName("inline-exec").build());
            } catch (Exception ignored) {}
            if (policyArn != null) {
                try {
                    iam.detachRolePolicy(DetachRolePolicyRequest.builder()
                            .roleName(ROLE_NAME).policyArn(policyArn).build());
                } catch (Exception ignored) {}
                try {
                    iam.detachUserPolicy(DetachUserPolicyRequest.builder()
                            .userName(USER_NAME).policyArn(policyArn).build());
                } catch (Exception ignored) {}
            }
            try {
                iam.deleteRole(DeleteRoleRequest.builder().roleName(ROLE_NAME).build());
            } catch (Exception ignored) {}
            if (policyArn != null) {
                try {
                    iam.deletePolicy(DeletePolicyRequest.builder().policyArn(policyArn).build());
                } catch (Exception ignored) {}
            }
            try {
                iam.removeUserFromGroup(RemoveUserFromGroupRequest.builder()
                        .groupName(GROUP_NAME).userName(USER_NAME).build());
            } catch (Exception ignored) {}
            try {
                iam.deleteGroup(DeleteGroupRequest.builder().groupName(GROUP_NAME).build());
            } catch (Exception ignored) {}
            try {
                iam.deleteUser(DeleteUserRequest.builder().userName(USER_NAME).build());
            } catch (Exception ignored) {}
            iam.close();
        }
    }

    // ── Users ──────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void createUser() {
        CreateUserResponse response = iam.createUser(CreateUserRequest.builder()
                .userName(USER_NAME).path("/").build());

        assertThat(response.user().userName()).isEqualTo(USER_NAME);
        assertThat(response.user().userId()).isNotNull();
        assertThat(response.user().arn()).contains(USER_NAME);
    }

    @Test
    @Order(2)
    void getUser() {
        GetUserResponse response = iam.getUser(GetUserRequest.builder()
                .userName(USER_NAME).build());

        assertThat(response.user().userName()).isEqualTo(USER_NAME);
    }

    @Test
    @Order(3)
    void listUsers() {
        ListUsersResponse response = iam.listUsers();

        assertThat(response.users())
                .anyMatch(u -> USER_NAME.equals(u.userName()));
    }

    @Test
    @Order(4)
    void tagUser() {
        iam.tagUser(TagUserRequest.builder()
                .userName(USER_NAME)
                .tags(software.amazon.awssdk.services.iam.model.Tag.builder().key("env").value("sdk-test").build())
                .build());
    }

    @Test
    @Order(5)
    void listUserTags() {
        ListUserTagsResponse response = iam.listUserTags(
                ListUserTagsRequest.builder().userName(USER_NAME).build());

        assertThat(response.tags())
                .anyMatch(t -> "env".equals(t.key()));
    }

    @Test
    @Order(6)
    void untagUser() {
        iam.untagUser(UntagUserRequest.builder()
                .userName(USER_NAME).tagKeys("env").build());
    }

    // ── Access Keys ────────────────────────────────────────────────────

    @Test
    @Order(7)
    void createAccessKey() {
        CreateAccessKeyResponse response = iam.createAccessKey(
                CreateAccessKeyRequest.builder().userName(USER_NAME).build());
        accessKeyId = response.accessKey().accessKeyId();

        assertThat(accessKeyId).isNotNull().startsWith("AKIA");
        assertThat(response.accessKey().secretAccessKey()).isNotNull();
        assertThat(response.accessKey().status()).isEqualTo(StatusType.ACTIVE);
    }

    @Test
    @Order(8)
    void listAccessKeys() {
        ListAccessKeysResponse response = iam.listAccessKeys(
                ListAccessKeysRequest.builder().userName(USER_NAME).build());

        assertThat(response.accessKeyMetadata()).isNotEmpty();
    }

    @Test
    @Order(9)
    void updateAccessKey() {
        Assumptions.assumeTrue(accessKeyId != null);

        iam.updateAccessKey(UpdateAccessKeyRequest.builder()
                .userName(USER_NAME)
                .accessKeyId(accessKeyId)
                .status(StatusType.INACTIVE)
                .build());
    }

    @Test
    @Order(10)
    void deleteAccessKey() {
        Assumptions.assumeTrue(accessKeyId != null);

        iam.deleteAccessKey(DeleteAccessKeyRequest.builder()
                .userName(USER_NAME).accessKeyId(accessKeyId).build());
    }

    // ── Groups ─────────────────────────────────────────────────────────

    @Test
    @Order(11)
    void createGroup() {
        CreateGroupResponse response = iam.createGroup(CreateGroupRequest.builder()
                .groupName(GROUP_NAME).build());

        assertThat(response.group().groupName()).isEqualTo(GROUP_NAME);
    }

    @Test
    @Order(12)
    void addUserToGroup() {
        iam.addUserToGroup(AddUserToGroupRequest.builder()
                .groupName(GROUP_NAME).userName(USER_NAME).build());
    }

    @Test
    @Order(13)
    void getGroup() {
        GetGroupResponse response = iam.getGroup(GetGroupRequest.builder()
                .groupName(GROUP_NAME).build());

        assertThat(response.users())
                .anyMatch(u -> USER_NAME.equals(u.userName()));
    }

    @Test
    @Order(14)
    void listGroupsForUser() {
        ListGroupsForUserResponse response = iam.listGroupsForUser(
                ListGroupsForUserRequest.builder().userName(USER_NAME).build());

        assertThat(response.groups())
                .anyMatch(g -> GROUP_NAME.equals(g.groupName()));
    }

    // ── Roles ──────────────────────────────────────────────────────────

    @Test
    @Order(15)
    void createRole() {
        CreateRoleResponse response = iam.createRole(CreateRoleRequest.builder()
                .roleName(ROLE_NAME)
                .assumeRolePolicyDocument(TRUST_POLICY)
                .description("SDK test role")
                .build());

        assertThat(response.role().roleName()).isEqualTo(ROLE_NAME);
        assertThat(response.role().arn()).contains(ROLE_NAME);
    }

    @Test
    @Order(16)
    void getRole() {
        GetRoleResponse response = iam.getRole(GetRoleRequest.builder()
                .roleName(ROLE_NAME).build());

        assertThat(response.role().roleName()).isEqualTo(ROLE_NAME);
    }

    @Test
    @Order(17)
    void listRoles() {
        ListRolesResponse response = iam.listRoles();

        assertThat(response.roles())
                .anyMatch(r -> ROLE_NAME.equals(r.roleName()));
    }

    // ── Managed Policies ───────────────────────────────────────────────

    @Test
    @Order(18)
    void createPolicy() {
        CreatePolicyResponse response = iam.createPolicy(CreatePolicyRequest.builder()
                .policyName(POLICY_NAME)
                .policyDocument(POLICY_DOCUMENT)
                .description("SDK test policy")
                .build());
        policyArn = response.policy().arn();

        assertThat(response.policy().policyName()).isEqualTo(POLICY_NAME);
        assertThat(policyArn).isNotNull();
    }

    @Test
    @Order(19)
    void getPolicy() {
        Assumptions.assumeTrue(policyArn != null);

        GetPolicyResponse response = iam.getPolicy(
                GetPolicyRequest.builder().policyArn(policyArn).build());

        assertThat(response.policy().policyName()).isEqualTo(POLICY_NAME);
    }

    @Test
    @Order(20)
    void attachRolePolicy() {
        Assumptions.assumeTrue(policyArn != null);

        iam.attachRolePolicy(AttachRolePolicyRequest.builder()
                .roleName(ROLE_NAME).policyArn(policyArn).build());
    }

    @Test
    @Order(21)
    void listAttachedRolePolicies() {
        Assumptions.assumeTrue(policyArn != null);

        ListAttachedRolePoliciesResponse response = iam.listAttachedRolePolicies(
                ListAttachedRolePoliciesRequest.builder().roleName(ROLE_NAME).build());

        assertThat(response.attachedPolicies())
                .anyMatch(p -> policyArn.equals(p.policyArn()));
    }

    @Test
    @Order(22)
    void attachUserPolicy() {
        Assumptions.assumeTrue(policyArn != null);

        iam.attachUserPolicy(AttachUserPolicyRequest.builder()
                .userName(USER_NAME).policyArn(policyArn).build());
    }

    @Test
    @Order(23)
    void listAttachedUserPolicies() {
        Assumptions.assumeTrue(policyArn != null);

        ListAttachedUserPoliciesResponse response = iam.listAttachedUserPolicies(
                ListAttachedUserPoliciesRequest.builder().userName(USER_NAME).build());

        assertThat(response.attachedPolicies())
                .anyMatch(p -> policyArn.equals(p.policyArn()));
    }

    @Test
    @Order(24)
    void putRolePolicy() {
        iam.putRolePolicy(PutRolePolicyRequest.builder()
                .roleName(ROLE_NAME)
                .policyName("inline-exec")
                .policyDocument("{\"Version\":\"2012-10-17\"}")
                .build());
    }

    @Test
    @Order(25)
    void getRolePolicy() {
        GetRolePolicyResponse response = iam.getRolePolicy(GetRolePolicyRequest.builder()
                .roleName(ROLE_NAME).policyName("inline-exec").build());

        assertThat(response.policyName()).isEqualTo("inline-exec");
    }

    @Test
    @Order(26)
    void listRolePolicies() {
        ListRolePoliciesResponse response = iam.listRolePolicies(
                ListRolePoliciesRequest.builder().roleName(ROLE_NAME).build());

        assertThat(response.policyNames()).contains("inline-exec");
    }

    // ── Instance Profiles ──────────────────────────────────────────────

    @Test
    @Order(27)
    void createInstanceProfile() {
        CreateInstanceProfileResponse response = iam.createInstanceProfile(
                CreateInstanceProfileRequest.builder()
                        .instanceProfileName(INSTANCE_PROFILE_NAME).build());

        assertThat(response.instanceProfile().instanceProfileName())
                .isEqualTo(INSTANCE_PROFILE_NAME);
    }

    @Test
    @Order(28)
    void addRoleToInstanceProfile() {
        iam.addRoleToInstanceProfile(AddRoleToInstanceProfileRequest.builder()
                .instanceProfileName(INSTANCE_PROFILE_NAME).roleName(ROLE_NAME).build());
    }

    @Test
    @Order(29)
    void getInstanceProfile() {
        GetInstanceProfileResponse response = iam.getInstanceProfile(
                GetInstanceProfileRequest.builder()
                        .instanceProfileName(INSTANCE_PROFILE_NAME).build());

        assertThat(response.instanceProfile().roles())
                .anyMatch(r -> ROLE_NAME.equals(r.roleName()));
    }

    @Test
    @Order(30)
    void listInstanceProfiles() {
        ListInstanceProfilesResponse response = iam.listInstanceProfiles();

        assertThat(response.instanceProfiles())
                .anyMatch(p -> INSTANCE_PROFILE_NAME.equals(p.instanceProfileName()));
    }

    // ── Error Cases ────────────────────────────────────────────────────

    @Test
    @Order(31)
    void getUserNotFoundThrows() {
        assertThatThrownBy(() -> iam.getUser(GetUserRequest.builder()
                .userName("nonexistent-user-xyz").build()))
                .isInstanceOf(NoSuchEntityException.class);
    }
}
