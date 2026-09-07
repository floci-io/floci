package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.identitystore.IdentitystoreClient;
import software.amazon.awssdk.services.identitystore.model.AlternateIdentifier;
import software.amazon.awssdk.services.identitystore.model.AttributeOperation;
import software.amazon.awssdk.services.identitystore.model.CreateGroupMembershipRequest;
import software.amazon.awssdk.services.identitystore.model.CreateGroupRequest;
import software.amazon.awssdk.services.identitystore.model.CreateUserRequest;
import software.amazon.awssdk.services.identitystore.model.DeleteGroupMembershipRequest;
import software.amazon.awssdk.services.identitystore.model.DeleteGroupRequest;
import software.amazon.awssdk.services.identitystore.model.DeleteUserRequest;
import software.amazon.awssdk.services.identitystore.model.DescribeGroupMembershipRequest;
import software.amazon.awssdk.services.identitystore.model.DescribeGroupRequest;
import software.amazon.awssdk.services.identitystore.model.DescribeUserRequest;
import software.amazon.awssdk.services.identitystore.model.Email;
import software.amazon.awssdk.services.identitystore.model.Filter;
import software.amazon.awssdk.services.identitystore.model.GetGroupIdRequest;
import software.amazon.awssdk.services.identitystore.model.GetGroupMembershipIdRequest;
import software.amazon.awssdk.services.identitystore.model.GetUserIdRequest;
import software.amazon.awssdk.services.identitystore.model.IsMemberInGroupsRequest;
import software.amazon.awssdk.services.identitystore.model.ListGroupMembershipsForMemberRequest;
import software.amazon.awssdk.services.identitystore.model.ListGroupMembershipsRequest;
import software.amazon.awssdk.services.identitystore.model.ListGroupsRequest;
import software.amazon.awssdk.services.identitystore.model.ListUsersRequest;
import software.amazon.awssdk.services.identitystore.model.MemberId;
import software.amazon.awssdk.services.identitystore.model.UpdateGroupRequest;
import software.amazon.awssdk.services.identitystore.model.UpdateUserRequest;
import software.amazon.awssdk.services.identitystore.model.UniqueAttribute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentityStoreTest {
    private static final String STORE = "d-1234567890";

    @Test
    void completeIdentityStoreApiUsesAwsSdk() {
        try (IdentitystoreClient client = TestFixtures.identityStoreClient()) {
            var group = client.createGroup(CreateGroupRequest.builder()
                    .identityStoreId(STORE)
                    .displayName("PlatformAdmins")
                    .description("Platform administrators")
                    .build());
            assertTrue(group.groupId().startsWith("1234567890-"));
            assertEquals(STORE, group.identityStoreId());

            var user = client.createUser(CreateUserRequest.builder()
                    .identityStoreId(STORE)
                    .userName("identitystore-sdk@example.com")
                    .displayName("Identity Store SDK")
                    .emails(Email.builder().value("identitystore-sdk@example.com").primary(true).build())
                    .build());
            assertTrue(user.userId().startsWith("1234567890-"));
            assertEquals(STORE, user.identityStoreId());

            var describedGroup = client.describeGroup(DescribeGroupRequest.builder()
                    .identityStoreId(STORE).groupId(group.groupId()).build());
            assertEquals("PlatformAdmins", describedGroup.displayName());
            assertNotNull(describedGroup.createdAt());

            var describedUser = client.describeUser(DescribeUserRequest.builder()
                    .identityStoreId(STORE).userId(user.userId()).build());
            assertEquals("identitystore-sdk@example.com", describedUser.userName());
            assertEquals("identitystore-sdk@example.com", describedUser.emails().get(0).value());
            assertNotNull(describedUser.createdAt());

            assertEquals(group.groupId(), client.getGroupId(GetGroupIdRequest.builder()
                    .identityStoreId(STORE)
                    .alternateIdentifier(AlternateIdentifier.fromUniqueAttribute(UniqueAttribute.builder()
                            .attributePath("displayName").attributeValue(Document.fromString("PlatformAdmins")).build()))
                    .build()).groupId());
            assertEquals(user.userId(), client.getUserId(GetUserIdRequest.builder()
                    .identityStoreId(STORE)
                    .alternateIdentifier(AlternateIdentifier.fromUniqueAttribute(UniqueAttribute.builder()
                            .attributePath("userName").attributeValue(Document.fromString("identitystore-sdk@example.com")).build()))
                    .build()).userId());

            assertEquals(1, client.listGroups(ListGroupsRequest.builder()
                    .identityStoreId(STORE)
                    .filters(Filter.builder().attributePath("DisplayName").attributeValue("PlatformAdmins").build())
                    .maxResults(1)
                    .build()).groups().size());
            assertEquals(1, client.listUsers(ListUsersRequest.builder()
                    .identityStoreId(STORE)
                    .filters(Filter.builder().attributePath("UserName").attributeValue("identitystore-sdk@example.com").build())
                    .maxResults(1)
                    .build()).users().size());

            client.updateGroup(UpdateGroupRequest.builder()
                    .identityStoreId(STORE)
                    .groupId(group.groupId())
                    .operations(AttributeOperation.builder()
                            .attributePath("description")
                            .attributeValue(Document.fromString("Updated platform administrators"))
                            .build())
                    .build());
            assertEquals("Updated platform administrators", client.describeGroup(DescribeGroupRequest.builder()
                    .identityStoreId(STORE).groupId(group.groupId()).build()).description());

            client.updateUser(UpdateUserRequest.builder()
                    .identityStoreId(STORE)
                    .userId(user.userId())
                    .operations(AttributeOperation.builder()
                            .attributePath("displayName")
                            .attributeValue(Document.fromString("Updated Identity Store SDK"))
                            .build())
                    .build());
            assertEquals("Updated Identity Store SDK", client.describeUser(DescribeUserRequest.builder()
                    .identityStoreId(STORE).userId(user.userId()).build()).displayName());

            MemberId member = MemberId.builder().userId(user.userId()).build();
            assertFalse(client.isMemberInGroups(IsMemberInGroupsRequest.builder()
                    .identityStoreId(STORE).memberId(member).groupIds(group.groupId()).build())
                    .results().get(0).membershipExists());

            var membership = client.createGroupMembership(CreateGroupMembershipRequest.builder()
                    .identityStoreId(STORE).groupId(group.groupId()).memberId(member).build());
            assertTrue(membership.membershipId().startsWith("1234567890-"));

            assertTrue(client.isMemberInGroups(IsMemberInGroupsRequest.builder()
                    .identityStoreId(STORE).memberId(member).groupIds(group.groupId()).build())
                    .results().get(0).membershipExists());

            var describedMembership = client.describeGroupMembership(DescribeGroupMembershipRequest.builder()
                    .identityStoreId(STORE).membershipId(membership.membershipId()).build());
            assertEquals(group.groupId(), describedMembership.groupId());
            assertEquals(user.userId(), describedMembership.memberId().userId());
            assertNotNull(describedMembership.createdAt());

            assertEquals(membership.membershipId(), client.getGroupMembershipId(GetGroupMembershipIdRequest.builder()
                    .identityStoreId(STORE).groupId(group.groupId()).memberId(member).build()).membershipId());
            assertEquals(1, client.listGroupMemberships(ListGroupMembershipsRequest.builder()
                    .identityStoreId(STORE).groupId(group.groupId()).maxResults(1).build()).groupMemberships().size());
            assertEquals(1, client.listGroupMembershipsForMember(ListGroupMembershipsForMemberRequest.builder()
                    .identityStoreId(STORE).memberId(member).maxResults(1).build()).groupMemberships().size());

            client.deleteGroupMembership(DeleteGroupMembershipRequest.builder()
                    .identityStoreId(STORE).membershipId(membership.membershipId()).build());
            assertFalse(client.isMemberInGroups(IsMemberInGroupsRequest.builder()
                    .identityStoreId(STORE).memberId(member).groupIds(group.groupId()).build())
                    .results().get(0).membershipExists());

            client.deleteUser(DeleteUserRequest.builder().identityStoreId(STORE).userId(user.userId()).build());
            client.deleteGroup(DeleteGroupRequest.builder().identityStoreId(STORE).groupId(group.groupId()).build());

            assertTrue(client.listUsers(ListUsersRequest.builder().identityStoreId(STORE).build()).users().isEmpty());
            assertTrue(client.listGroups(ListGroupsRequest.builder().identityStoreId(STORE).build()).groups().isEmpty());
        }
    }
}
