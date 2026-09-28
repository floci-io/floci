package com.floci.test;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AddUserToGroupRequest;
import software.amazon.awssdk.services.iam.model.CreateAccessKeyRequest;
import software.amazon.awssdk.services.iam.model.CreateGroupRequest;
import software.amazon.awssdk.services.iam.model.CreateUserRequest;
import software.amazon.awssdk.services.iam.model.DeleteAccessKeyRequest;
import software.amazon.awssdk.services.iam.model.DeleteConflictException;
import software.amazon.awssdk.services.iam.model.DeleteGroupRequest;
import software.amazon.awssdk.services.iam.model.DeleteUserPolicyRequest;
import software.amazon.awssdk.services.iam.model.DeleteUserRequest;
import software.amazon.awssdk.services.iam.model.GetGroupRequest;
import software.amazon.awssdk.services.iam.model.ListAccessKeysRequest;
import software.amazon.awssdk.services.iam.model.PutUserPolicyRequest;
import software.amazon.awssdk.services.iam.model.RemoveUserFromGroupRequest;
import software.amazon.awssdk.services.iam.model.UpdateUserRequest;
import software.amazon.awssdk.services.iam.model.User;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("IAM DeleteUser prerequisites and UpdateUser rename")
class IamDeleteUserTest {

    private static final String POLICY_NAME = "delete-user-inline";
    private static final String POLICY_DOCUMENT = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}";

    private IamClient iam;
    private String userName;
    private String renamedUserName;
    private String groupName;

    @BeforeEach
    void setup() {
        iam = TestFixtures.iamClient();
        userName = TestFixtures.uniqueName("sdk-delete-user");
        renamedUserName = userName + "-renamed";
        groupName = TestFixtures.uniqueName("sdk-delete-group");
        iam.createUser(CreateUserRequest.builder().userName(userName).build());
    }

    @AfterEach
    void cleanup() {
        for (String name : new String[] {userName, renamedUserName}) {
            try {
                iam.listAccessKeys(ListAccessKeysRequest.builder().userName(name).build())
                        .accessKeyMetadata().forEach(key -> iam.deleteAccessKey(DeleteAccessKeyRequest.builder()
                                .userName(name).accessKeyId(key.accessKeyId()).build()));
            } catch (Exception ignored) {
                // The user may not exist under this name, so there is nothing to clean up for it.
            }
            try {
                iam.deleteUserPolicy(DeleteUserPolicyRequest.builder()
                        .userName(name).policyName(POLICY_NAME).build());
            } catch (Exception ignored) {
                // The inline policy may already be gone or never have been created.
            }
            try {
                iam.removeUserFromGroup(RemoveUserFromGroupRequest.builder()
                        .groupName(groupName).userName(name).build());
            } catch (Exception ignored) {
                // The user may not be a member of the group.
            }
            try {
                iam.deleteUser(DeleteUserRequest.builder().userName(name).build());
            } catch (Exception ignored) {
                // The user may not exist under this name.
            }
        }
        try {
            iam.deleteGroup(DeleteGroupRequest.builder().groupName(groupName).build());
        } catch (Exception ignored) {
            // The group may never have been created.
        }
        iam.close();
    }

    @Test
    @DisplayName("DeleteUser is a DeleteConflict while the user has an inline policy")
    void deleteUserWithInlinePolicyIsDeleteConflict() {
        iam.putUserPolicy(PutUserPolicyRequest.builder()
                .userName(userName).policyName(POLICY_NAME).policyDocument(POLICY_DOCUMENT).build());

        assertThatThrownBy(() -> iam.deleteUser(DeleteUserRequest.builder().userName(userName).build()))
                .isInstanceOf(DeleteConflictException.class);

        iam.deleteUserPolicy(DeleteUserPolicyRequest.builder()
                .userName(userName).policyName(POLICY_NAME).build());
        iam.deleteUser(DeleteUserRequest.builder().userName(userName).build());
    }

    @Test
    @DisplayName("DeleteUser is a DeleteConflict while the user has an access key")
    void deleteUserWithAccessKeyIsDeleteConflict() {
        String accessKeyId = iam.createAccessKey(CreateAccessKeyRequest.builder().userName(userName).build())
                .accessKey().accessKeyId();

        assertThatThrownBy(() -> iam.deleteUser(DeleteUserRequest.builder().userName(userName).build()))
                .isInstanceOf(DeleteConflictException.class);

        iam.deleteAccessKey(DeleteAccessKeyRequest.builder().userName(userName).accessKeyId(accessKeyId).build());
        iam.deleteUser(DeleteUserRequest.builder().userName(userName).build());
    }

    @Test
    @DisplayName("UpdateUser rename carries the access keys and group membership to the new name")
    void renameCarriesAccessKeysAndGroupMembership() {
        String accessKeyId = iam.createAccessKey(CreateAccessKeyRequest.builder().userName(userName).build())
                .accessKey().accessKeyId();
        iam.createGroup(CreateGroupRequest.builder().groupName(groupName).build());
        iam.addUserToGroup(AddUserToGroupRequest.builder().groupName(groupName).userName(userName).build());

        iam.updateUser(UpdateUserRequest.builder().userName(userName).newUserName(renamedUserName).build());

        assertThat(iam.listAccessKeys(ListAccessKeysRequest.builder().userName(renamedUserName).build())
                .accessKeyMetadata())
                .singleElement()
                .satisfies(key -> {
                    assertThat(key.accessKeyId()).isEqualTo(accessKeyId);
                    assertThat(key.userName()).isEqualTo(renamedUserName);
                });
        assertThat(iam.getGroup(GetGroupRequest.builder().groupName(groupName).build()).users())
                .extracting(User::userName)
                .containsExactly(renamedUserName);

        iam.createUser(CreateUserRequest.builder().userName(userName).build());
        assertThat(iam.listAccessKeys(ListAccessKeysRequest.builder().userName(userName).build())
                .accessKeyMetadata()).isEmpty();
        iam.deleteUser(DeleteUserRequest.builder().userName(userName).build());

        iam.deleteAccessKey(DeleteAccessKeyRequest.builder()
                .userName(renamedUserName).accessKeyId(accessKeyId).build());
        iam.removeUserFromGroup(RemoveUserFromGroupRequest.builder()
                .groupName(groupName).userName(renamedUserName).build());
        iam.deleteGroup(DeleteGroupRequest.builder().groupName(groupName).build());
        iam.deleteUser(DeleteUserRequest.builder().userName(renamedUserName).build());
    }
}
