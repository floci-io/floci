package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessDetail;
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
import software.amazon.awssdk.services.iam.model.CreateLoginProfileRequest;
import software.amazon.awssdk.services.iam.model.CreateLoginProfileResponse;
import software.amazon.awssdk.services.iam.model.CreatePolicyRequest;
import software.amazon.awssdk.services.iam.model.CreatePolicyResponse;
import software.amazon.awssdk.services.iam.model.CreateRoleRequest;
import software.amazon.awssdk.services.iam.model.CreateRoleResponse;
import software.amazon.awssdk.services.iam.model.CreateSamlProviderRequest;
import software.amazon.awssdk.services.iam.model.CreateSamlProviderResponse;
import software.amazon.awssdk.services.iam.model.CreateUserRequest;
import software.amazon.awssdk.services.iam.model.CreateUserResponse;
import software.amazon.awssdk.services.iam.model.DeleteAccessKeyRequest;
import software.amazon.awssdk.services.iam.model.DeleteGroupRequest;
import software.amazon.awssdk.services.iam.model.DeleteInstanceProfileRequest;
import software.amazon.awssdk.services.iam.model.DeleteLoginProfileRequest;
import software.amazon.awssdk.services.iam.model.DeletePolicyRequest;
import software.amazon.awssdk.services.iam.model.DeleteRolePolicyRequest;
import software.amazon.awssdk.services.iam.model.DeleteRoleRequest;
import software.amazon.awssdk.services.iam.model.DeleteSamlProviderRequest;
import software.amazon.awssdk.services.iam.model.DeleteUserRequest;
import software.amazon.awssdk.services.iam.model.DetachRolePolicyRequest;
import software.amazon.awssdk.services.iam.model.DetachUserPolicyRequest;
import software.amazon.awssdk.services.iam.model.GenerateOrganizationsAccessReportRequest;
import software.amazon.awssdk.services.iam.model.GenerateOrganizationsAccessReportResponse;
import software.amazon.awssdk.services.iam.model.GenerateServiceLastAccessedDetailsRequest;
import software.amazon.awssdk.services.iam.model.GenerateServiceLastAccessedDetailsResponse;
import software.amazon.awssdk.services.iam.model.GetAccountSummaryResponse;
import software.amazon.awssdk.services.iam.model.GetGroupRequest;
import software.amazon.awssdk.services.iam.model.GetGroupResponse;
import software.amazon.awssdk.services.iam.model.GetInstanceProfileRequest;
import software.amazon.awssdk.services.iam.model.GetInstanceProfileResponse;
import software.amazon.awssdk.services.iam.model.GetLoginProfileRequest;
import software.amazon.awssdk.services.iam.model.GetLoginProfileResponse;
import software.amazon.awssdk.services.iam.model.GetOrganizationsAccessReportRequest;
import software.amazon.awssdk.services.iam.model.GetOrganizationsAccessReportResponse;
import software.amazon.awssdk.services.iam.model.GetPolicyRequest;
import software.amazon.awssdk.services.iam.model.GetPolicyResponse;
import software.amazon.awssdk.services.iam.model.GetPolicyVersionRequest;
import software.amazon.awssdk.services.iam.model.GetPolicyVersionResponse;
import software.amazon.awssdk.services.iam.model.GetRolePolicyRequest;
import software.amazon.awssdk.services.iam.model.GetRolePolicyResponse;
import software.amazon.awssdk.services.iam.model.GetRoleRequest;
import software.amazon.awssdk.services.iam.model.GetRoleResponse;
import software.amazon.awssdk.services.iam.model.GetSamlProviderRequest;
import software.amazon.awssdk.services.iam.model.GetSamlProviderResponse;
import software.amazon.awssdk.services.iam.model.GetServiceLastAccessedDetailsRequest;
import software.amazon.awssdk.services.iam.model.GetServiceLastAccessedDetailsResponse;
import software.amazon.awssdk.services.iam.model.GetServiceLastAccessedDetailsWithEntitiesRequest;
import software.amazon.awssdk.services.iam.model.GetServiceLastAccessedDetailsWithEntitiesResponse;
import software.amazon.awssdk.services.iam.model.GetUserRequest;
import software.amazon.awssdk.services.iam.model.GetUserResponse;
import software.amazon.awssdk.services.iam.model.JobStatusType;
import software.amazon.awssdk.services.iam.model.ListAccessKeysRequest;
import software.amazon.awssdk.services.iam.model.ListAccessKeysResponse;
import software.amazon.awssdk.services.iam.model.ListAttachedRolePoliciesRequest;
import software.amazon.awssdk.services.iam.model.ListAttachedRolePoliciesResponse;
import software.amazon.awssdk.services.iam.model.ListAttachedUserPoliciesRequest;
import software.amazon.awssdk.services.iam.model.ListAttachedUserPoliciesResponse;
import software.amazon.awssdk.services.iam.model.ListEntitiesForPolicyResponse;
import software.amazon.awssdk.services.iam.model.ListGroupsForUserRequest;
import software.amazon.awssdk.services.iam.model.ListGroupsForUserResponse;
import software.amazon.awssdk.services.iam.model.ListInstanceProfileTagsRequest;
import software.amazon.awssdk.services.iam.model.ListInstanceProfileTagsResponse;
import software.amazon.awssdk.services.iam.model.ListInstanceProfilesResponse;
import software.amazon.awssdk.services.iam.model.ListPoliciesGrantingServiceAccessEntry;
import software.amazon.awssdk.services.iam.model.ListPoliciesGrantingServiceAccessRequest;
import software.amazon.awssdk.services.iam.model.ListPoliciesGrantingServiceAccessResponse;
import software.amazon.awssdk.services.iam.model.ListPoliciesResponse;
import software.amazon.awssdk.services.iam.model.ListRolePoliciesRequest;
import software.amazon.awssdk.services.iam.model.ListRolePoliciesResponse;
import software.amazon.awssdk.services.iam.model.ListRolesResponse;
import software.amazon.awssdk.services.iam.model.ListSamlProviderTagsRequest;
import software.amazon.awssdk.services.iam.model.ListSamlProviderTagsResponse;
import software.amazon.awssdk.services.iam.model.ListSamlProvidersResponse;
import software.amazon.awssdk.services.iam.model.ListUserTagsRequest;
import software.amazon.awssdk.services.iam.model.ListUserTagsResponse;
import software.amazon.awssdk.services.iam.model.ListUsersResponse;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;
import software.amazon.awssdk.services.iam.model.Policy;
import software.amazon.awssdk.services.iam.model.PolicyScopeType;
import software.amazon.awssdk.services.iam.model.PutRolePolicyRequest;
import software.amazon.awssdk.services.iam.model.RemoveRoleFromInstanceProfileRequest;
import software.amazon.awssdk.services.iam.model.RemoveUserFromGroupRequest;
import software.amazon.awssdk.services.iam.model.Role;
import software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyRequest;
import software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyResponse;
import software.amazon.awssdk.services.iam.model.StatusType;
import software.amazon.awssdk.services.iam.model.SummaryKeyType;
import software.amazon.awssdk.services.iam.model.Tag;
import software.amazon.awssdk.services.iam.model.TagInstanceProfileRequest;
import software.amazon.awssdk.services.iam.model.TagSamlProviderRequest;
import software.amazon.awssdk.services.iam.model.TagUserRequest;
import software.amazon.awssdk.services.iam.model.UntagInstanceProfileRequest;
import software.amazon.awssdk.services.iam.model.UntagSamlProviderRequest;
import software.amazon.awssdk.services.iam.model.UntagUserRequest;
import software.amazon.awssdk.services.iam.model.UpdateAccessKeyRequest;
import software.amazon.awssdk.services.iam.model.UpdateLoginProfileRequest;
import software.amazon.awssdk.services.iam.model.UpdateSamlProviderRequest;
import software.amazon.awssdk.services.organizations.OrganizationsClient;
import software.amazon.awssdk.services.organizations.model.Organization;
import software.amazon.awssdk.services.organizations.model.OrganizationFeatureSet;
import software.amazon.awssdk.services.organizations.model.PolicyType;
import software.amazon.awssdk.services.organizations.model.Root;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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
    private static final String AWS_MANAGED_POLICY_PREFIX = TestFixtures.globalArn("iam", "aws", "policy/");
    private static final String ADMIN_POLICY_ARN = AWS_MANAGED_POLICY_PREFIX + "AdministratorAccess";
    private static final String READ_ONLY_POLICY_ARN = AWS_MANAGED_POLICY_PREFIX + "ReadOnlyAccess";
    private static final String LAMBDA_BASIC_POLICY_ARN =
            AWS_MANAGED_POLICY_PREFIX + "service-role/AWSLambdaBasicExecutionRole";
    private static final String TRUST_POLICY = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Principal\":{\"Service\":\"lambda.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";
    private static final String POLICY_DOCUMENT = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}";
    private static final String LOGIN_PROFILE_PASSWORD = "Sdk-Test-P4ssword!";
    private static final String SAML_PROVIDER_NAME = "sdk-test-saml-provider";
    /**
     * The Organizations access report reads an organization, and only its management account can
     * ask for one. This suite builds that organization under an account of its own so it never
     * contends with the one the Organizations suite builds for the default account.
     */
    private static final String REPORT_MANAGEMENT_ACCOUNT = "555555555557";
    /** The minimum AWS declares for {@code SAMLMetadataDocument}. */
    private static final int SAML_METADATA_MINIMUM = 1000;
    private static final String SAML_METADATA_BODY = "<md:EntityDescriptor "
            + "xmlns:md=\"urn:oasis:names:tc:SAML:2.0:metadata\" entityID=\"https://idp.example.test/sdk-test\">"
            + "<md:IDPSSODescriptor><md:KeyDescriptor use=\"signing\"><ds:KeyInfo "
            + "xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\"><ds:X509Data><ds:X509Certificate>"
            + "c2RrLXRlc3QtY2VydGlmaWNhdGU=</ds:X509Certificate></ds:X509Data></ds:KeyInfo></md:KeyDescriptor>"
            + "</md:IDPSSODescriptor>";
    private static final String SAML_METADATA_DOCUMENT = samlMetadata(SAML_METADATA_BODY);
    /** The same document under another entity id and certificate, padded on its own: the
     * replacements are shorter than what they replace, so padding the first document and editing
     * it would leave the second under the minimum. */
    private static final String SAML_METADATA_DOCUMENT_UPDATED = samlMetadata(SAML_METADATA_BODY
            .replace("sdk-test", "sdk-test-updated").replace("c2RrLXRlc3QtY2VydGlmaWNhdGU=", "dXBkYXRlZC1jZXJ0"));
    private static String policyArn;
    private static String accessKeyId;
    private static String samlProviderArn;

    /**
     * The document closed off and padded to the minimum AWS declares for it. A document carrying
     * nothing but an entity id and one signing key is about a third of that, and the service holds
     * the member to its published bound, so the filler comment brings it up: it parses as nothing,
     * the entity id and the certificate being what the provider is read from.
     */
    private static String samlMetadata(String body) {
        String end = "</md:EntityDescriptor>";
        String open = "<!-- ";
        String close = " -->";
        int filler = SAML_METADATA_MINIMUM - body.length() - end.length() - open.length() - close.length();
        return body + open + "x".repeat(Math.max(1, filler)) + close + end;
    }

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
                try {
                    iam.detachGroupPolicy(request -> request
                            .groupName(GROUP_NAME).policyArn(policyArn));
                } catch (Exception cleanupError) {
                    System.err.printf("Could not detach IAM test group policy during cleanup: %s%n",
                            cleanupError.getMessage());
                }
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
                iam.detachUserPolicy(DetachUserPolicyRequest.builder()
                        .userName(USER_NAME).policyArn(READ_ONLY_POLICY_ARN).build());
            } catch (Exception ignored) {
                // The policy may not have been attached if the test failed before reaching cleanup.
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
                .tags(Tag.builder().key("env").value("sdk-test").build())
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
        assertThat(iam.listRolesPaginator().roles())
                .anyMatch(r -> ROLE_NAME.equals(r.roleName()));
    }

    @Test
    @Order(17)
    void listRolesPagesThroughMaxItemsAndMarker() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String path = "/sdk-page-roles-" + suffix + "/";
        List<String> names = List.of("sdk-page-" + suffix + "-a", "sdk-page-" + suffix + "-b",
                "sdk-page-" + suffix + "-c");
        names.forEach(name -> iam.createRole(CreateRoleRequest.builder()
                .roleName(name).path(path).assumeRolePolicyDocument(TRUST_POLICY).build()));
        try {
            List<ListRolesResponse> pages = iam.listRolesPaginator(request -> request
                    .pathPrefix(path).maxItems(2)).stream().toList();

            assertThat(pages).hasSize(2);
            assertThat(pages.get(0).isTruncated()).isTrue();
            assertThat(pages.get(0).marker()).isNotBlank();
            assertThat(pages.get(1).isTruncated()).isFalse();
            assertThat(pages.get(1).marker()).isNull();
            assertThat(pages.stream().flatMap(page -> page.roles().stream()).map(Role::roleName).toList())
                    .containsExactlyElementsOf(names);
        } finally {
            names.forEach(name -> iam.deleteRole(DeleteRoleRequest.builder().roleName(name).build()));
        }
    }

    @Test
    @Order(17)
    void listPoliciesPagesThroughMaxItemsAndMarker() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String path = "/sdk-page-policies-" + suffix + "/";
        List<String> arns = new ArrayList<>();
        for (String letter : List.of("a", "b", "c")) {
            arns.add(iam.createPolicy(CreatePolicyRequest.builder().policyName("sdk-page-" + suffix + "-" + letter)
                    .path(path).policyDocument(POLICY_DOCUMENT).build()).policy().arn());
        }
        try {
            List<ListPoliciesResponse> pages = iam.listPoliciesPaginator(request -> request
                    .scope(PolicyScopeType.LOCAL).pathPrefix(path).maxItems(2)).stream().toList();

            assertThat(pages).hasSize(2);
            assertThat(pages.get(0).isTruncated()).isTrue();
            assertThat(pages.get(0).marker()).isNotBlank();
            assertThat(pages.get(1).isTruncated()).isFalse();
            assertThat(pages.get(1).marker()).isNull();
            assertThat(pages.stream().flatMap(page -> page.policies().stream()).map(Policy::arn).toList())
                    .containsExactlyElementsOf(arns);
        } finally {
            arns.forEach(arn -> iam.deletePolicy(DeletePolicyRequest.builder().policyArn(arn).build()));
        }
    }

    @Test
    @Order(17)
    void listPoliciesReturnsOneHundredWhenMaxItemsIsOmitted() {
        ListPoliciesResponse response = iam.listPolicies(request -> request.scope(PolicyScopeType.AWS));

        assertThat(response.policies()).hasSize(100);
        assertThat(response.isTruncated()).isTrue();
        assertThat(response.marker()).isNotBlank();
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
    @Order(100)
    void listEntitiesForPolicy() {
        Assumptions.assumeTrue(policyArn != null);

        iam.attachGroupPolicy(request -> request
                .groupName(GROUP_NAME).policyArn(policyArn));

        ListEntitiesForPolicyResponse response = iam.listEntitiesForPolicy(request -> request
                .policyArn(policyArn));

        assertThat(response.policyRoles()).anySatisfy(role -> {
            assertThat(role.roleName()).isEqualTo(ROLE_NAME);
            assertThat(role.roleId()).isNotBlank();
        });
        assertThat(response.policyUsers()).anySatisfy(user -> {
            assertThat(user.userName()).isEqualTo(USER_NAME);
            assertThat(user.userId()).isNotBlank();
        });
        assertThat(response.policyGroups()).anySatisfy(group -> {
            assertThat(group.groupName()).isEqualTo(GROUP_NAME);
            assertThat(group.groupId()).isNotBlank();
        });
        assertThat(response.isTruncated()).isFalse();
    }

    @Test
    @Order(101)
    void getAccountSummary() {
        GetAccountSummaryResponse response = iam.getAccountSummary();
        // GlobalEndpointTokenVersion reports the global STS endpoint, which only the commercial partition has.
        boolean globalSts = "aws".equals(TestFixtures.partition());
        assertThat(response.summaryMap()).hasSize(globalSts ? 34 : 33);
        assertThat(response.summaryMap().containsKey(SummaryKeyType.GLOBAL_ENDPOINT_TOKEN_VERSION))
                .isEqualTo(globalSts);
        assertThat(response.summaryMap().get(SummaryKeyType.USERS_QUOTA)).isEqualTo(5000);
        assertThat(response.summaryMap().get(SummaryKeyType.GROUPS_QUOTA)).isEqualTo(300);
        assertThat(response.summaryMap().get(SummaryKeyType.ROLES_QUOTA)).isEqualTo(1000);
        assertThat(response.summaryMap().get(SummaryKeyType.POLICIES_QUOTA)).isEqualTo(1500);
        assertThat(response.summaryMap().get(SummaryKeyType.INSTANCE_PROFILES_QUOTA)).isEqualTo(1000);
        assertThat(response.summaryMap().get(SummaryKeyType.ATTACHED_POLICIES_PER_ROLE_QUOTA)).isEqualTo(20);
        assertThat(response.summaryMap().get(SummaryKeyType.POLICY_SIZE_QUOTA)).isEqualTo(6144);
    }

    @Test
    @Order(24)
    void simulatePrincipalPolicy() {
        SimulatePrincipalPolicyResponse response = iam.simulatePrincipalPolicy(SimulatePrincipalPolicyRequest.builder()
                .policySourceArn(TestFixtures.globalArn("iam", "000000000000", "user/" + USER_NAME))
                .actionNames("s3:GetObject", "ec2:RunInstances")
                .resourceArns("*")
                .build());

        assertThat(response.evaluationResults()).hasSize(2);
        assertThat(response.evaluationResults())
                .anySatisfy(result -> {
                    assertThat(result.evalActionName()).isEqualTo("s3:GetObject");
                    assertThat(result.evalDecisionAsString()).isEqualTo("allowed");
                })
                .anySatisfy(result -> {
                    assertThat(result.evalActionName()).isEqualTo("ec2:RunInstances");
                    assertThat(result.evalDecisionAsString()).isEqualTo("implicitDeny");
                });
    }

    @Test
    @Order(25)
    void putRolePolicy() {
        iam.putRolePolicy(PutRolePolicyRequest.builder()
                .roleName(ROLE_NAME)
                .policyName("inline-exec")
                .policyDocument("{\"Version\":\"2012-10-17\"}")
                .build());
    }

    @Test
    @Order(26)
    void getRolePolicy() {
        GetRolePolicyResponse response = iam.getRolePolicy(GetRolePolicyRequest.builder()
                .roleName(ROLE_NAME).policyName("inline-exec").build());

        assertThat(response.policyName()).isEqualTo("inline-exec");
    }

    @Test
    @Order(27)
    void listRolePolicies() {
        ListRolePoliciesResponse response = iam.listRolePolicies(
                ListRolePoliciesRequest.builder().roleName(ROLE_NAME).build());

        assertThat(response.policyNames()).contains("inline-exec");
    }

    // ── Instance Profiles ──────────────────────────────────────────────

    @Test
    @Order(28)
    void createInstanceProfile() {
        CreateInstanceProfileResponse response = iam.createInstanceProfile(
                CreateInstanceProfileRequest.builder()
                        .instanceProfileName(INSTANCE_PROFILE_NAME).build());

        assertThat(response.instanceProfile().instanceProfileName())
                .isEqualTo(INSTANCE_PROFILE_NAME);
    }

    @Test
    @Order(29)
    void addRoleToInstanceProfile() {
        iam.addRoleToInstanceProfile(AddRoleToInstanceProfileRequest.builder()
                .instanceProfileName(INSTANCE_PROFILE_NAME).roleName(ROLE_NAME).build());
    }

    @Test
    @Order(30)
    void getInstanceProfile() {
        GetInstanceProfileResponse response = iam.getInstanceProfile(
                GetInstanceProfileRequest.builder()
                        .instanceProfileName(INSTANCE_PROFILE_NAME).build());

        assertThat(response.instanceProfile().roles())
                .anyMatch(r -> ROLE_NAME.equals(r.roleName()));
    }

    @Test
    @Order(31)
    void listInstanceProfiles() {
        ListInstanceProfilesResponse response = iam.listInstanceProfiles();

        assertThat(response.instanceProfiles())
                .anyMatch(p -> INSTANCE_PROFILE_NAME.equals(p.instanceProfileName()));
    }

    @Test
    @Order(44)
    void tagInstanceProfile() {
        iam.tagInstanceProfile(TagInstanceProfileRequest.builder()
                .instanceProfileName(INSTANCE_PROFILE_NAME)
                .tags(Tag.builder().key("env").value("sdk-test").build())
                .build());
    }

    @Test
    @Order(45)
    void listInstanceProfileTags() {
        ListInstanceProfileTagsResponse response = iam.listInstanceProfileTags(
                ListInstanceProfileTagsRequest.builder().instanceProfileName(INSTANCE_PROFILE_NAME).build());

        assertThat(response.tags())
                .anyMatch(t -> "env".equals(t.key()));
    }

    @Test
    @Order(46)
    void untagInstanceProfile() {
        iam.untagInstanceProfile(UntagInstanceProfileRequest.builder()
                .instanceProfileName(INSTANCE_PROFILE_NAME).tagKeys("env").build());
    }

    // ── SAML Identity Provider ──────────────────────────────────────────

    @Test
    @Order(47)
    void createSamlProvider() {
        CreateSamlProviderResponse response = iam.createSAMLProvider(CreateSamlProviderRequest.builder()
                .name(SAML_PROVIDER_NAME).samlMetadataDocument(SAML_METADATA_DOCUMENT)
                .tags(Tag.builder().key("owner").value("sdk-test").build())
                .build());

        samlProviderArn = response.samlProviderArn();
        assertThat(samlProviderArn).contains(SAML_PROVIDER_NAME);
        assertThat(response.tags()).anyMatch(t -> "owner".equals(t.key()) && "sdk-test".equals(t.value()));
    }

    @Test
    @Order(48)
    void getSamlProvider() {
        GetSamlProviderResponse response = iam.getSAMLProvider(GetSamlProviderRequest.builder()
                .samlProviderArn(samlProviderArn).build());

        assertThat(response.samlMetadataDocument()).contains("sdk-test");
    }

    @Test
    @Order(49)
    void listSamlProviders() {
        ListSamlProvidersResponse response = iam.listSAMLProviders();

        assertThat(response.samlProviderList()).anyMatch(p -> samlProviderArn.equals(p.arn()));
    }

    @Test
    @Order(50)
    void tagSamlProvider() {
        iam.tagSAMLProvider(TagSamlProviderRequest.builder()
                .samlProviderArn(samlProviderArn)
                .tags(Tag.builder().key("env").value("sdk-test").build())
                .build());
    }

    @Test
    @Order(51)
    void listSamlProviderTags() {
        ListSamlProviderTagsResponse response = iam.listSAMLProviderTags(
                ListSamlProviderTagsRequest.builder().samlProviderArn(samlProviderArn).build());

        assertThat(response.tags()).anyMatch(t -> "env".equals(t.key()));
    }

    @Test
    @Order(52)
    void untagSamlProvider() {
        iam.untagSAMLProvider(UntagSamlProviderRequest.builder()
                .samlProviderArn(samlProviderArn).tagKeys("env").build());
    }

    @Test
    @Order(53)
    void updateSamlProvider() {
        iam.updateSAMLProvider(UpdateSamlProviderRequest.builder()
                .samlProviderArn(samlProviderArn).samlMetadataDocument(SAML_METADATA_DOCUMENT_UPDATED).build());

        GetSamlProviderResponse response = iam.getSAMLProvider(GetSamlProviderRequest.builder()
                .samlProviderArn(samlProviderArn).build());
        assertThat(response.samlMetadataDocument()).contains("sdk-test-updated");
    }

    @Test
    @Order(54)
    void deleteSamlProvider() {
        iam.deleteSAMLProvider(DeleteSamlProviderRequest.builder().samlProviderArn(samlProviderArn).build());
    }

    // ── Login Profile ──────────────────────────────────────────────────

    @Test
    @Order(39)
    void createLoginProfile() {
        CreateLoginProfileResponse response = iam.createLoginProfile(CreateLoginProfileRequest.builder()
                .userName(USER_NAME).password(LOGIN_PROFILE_PASSWORD).passwordResetRequired(false).build());

        assertThat(response.loginProfile().userName()).isEqualTo(USER_NAME);
        assertThat(response.loginProfile().passwordResetRequired()).isFalse();
        assertThat(response.loginProfile().createDate()).isNotNull();
    }

    @Test
    @Order(40)
    void getLoginProfile() {
        GetLoginProfileResponse response = iam.getLoginProfile(GetLoginProfileRequest.builder()
                .userName(USER_NAME).build());

        assertThat(response.loginProfile().userName()).isEqualTo(USER_NAME);
        assertThat(response.loginProfile().passwordResetRequired()).isFalse();
    }

    @Test
    @Order(41)
    void updateLoginProfile() {
        iam.updateLoginProfile(UpdateLoginProfileRequest.builder()
                .userName(USER_NAME).passwordResetRequired(true).build());

        GetLoginProfileResponse response = iam.getLoginProfile(GetLoginProfileRequest.builder()
                .userName(USER_NAME).build());
        assertThat(response.loginProfile().passwordResetRequired()).isTrue();
    }

    @Test
    @Order(42)
    void deleteLoginProfile() {
        iam.deleteLoginProfile(DeleteLoginProfileRequest.builder().userName(USER_NAME).build());
    }

    @Test
    @Order(43)
    void getLoginProfileAfterDeleteThrows() {
        assertThatThrownBy(() -> iam.getLoginProfile(GetLoginProfileRequest.builder()
                .userName(USER_NAME).build()))
                .isInstanceOf(NoSuchEntityException.class);
    }

    // ── Error Cases ────────────────────────────────────────────────────

    @Test
    @Order(32)
    void getUserNotFoundThrows() {
        assertThatThrownBy(() -> iam.getUser(GetUserRequest.builder()
                .userName("nonexistent-user-xyz").build()))
                .isInstanceOf(NoSuchEntityException.class);
    }

    // ── AWS managed policies ───────────────────────────────────────────
    // The catalog is parsed while the native image is built and lives in its
    // heap, so only a run against a native binary proves the documents survived.

    @Test
    @Order(33)
    void getAwsManagedPolicy() {
        GetPolicyResponse response = iam.getPolicy(GetPolicyRequest.builder().policyArn(ADMIN_POLICY_ARN).build());

        assertThat(response.policy().policyName()).isEqualTo("AdministratorAccess");
        assertThat(response.policy().arn()).isEqualTo(ADMIN_POLICY_ARN);
        assertThat(response.policy().path()).isEqualTo("/");
        assertThat(response.policy().defaultVersionId()).matches("v[1-9][0-9]*");
        assertThat(response.policy().isAttachable()).isTrue();
    }

    @Test
    @Order(34)
    void getAwsManagedPolicyWithServiceRolePath() {
        GetPolicyResponse response =
                iam.getPolicy(GetPolicyRequest.builder().policyArn(LAMBDA_BASIC_POLICY_ARN).build());

        assertThat(response.policy().policyName()).isEqualTo("AWSLambdaBasicExecutionRole");
        assertThat(response.policy().path()).isEqualTo("/service-role/");
    }

    @Test
    @Order(35)
    void getAwsManagedPolicyVersionDocument() {
        String versionId = iam.getPolicy(GetPolicyRequest.builder().policyArn(ADMIN_POLICY_ARN).build())
                .policy().defaultVersionId();
        GetPolicyVersionResponse response = iam.getPolicyVersion(GetPolicyVersionRequest.builder()
                .policyArn(ADMIN_POLICY_ARN).versionId(versionId).build());

        assertThat(response.policyVersion().versionId()).isEqualTo(versionId);
        assertThat(response.policyVersion().isDefaultVersion()).isTrue();
        String document = URLDecoder.decode(response.policyVersion().document(), StandardCharsets.UTF_8);
        assertThat(document).startsWith("{").contains("\"Statement\"").contains("\"Effect\"");
    }

    @Test
    @Order(36)
    void listAwsManagedPolicies() {
        List<String> names = iam.listPoliciesPaginator(request -> request.scope(PolicyScopeType.AWS))
                .policies().stream().map(policy -> policy.policyName()).toList();

        assertThat(names).hasSizeGreaterThan(1000)
                .contains("AdministratorAccess", "ReadOnlyAccess", "AWSLambdaBasicExecutionRole");
    }

    @Test
    @Order(37)
    void attachAwsManagedPolicyToUser() {
        iam.attachUserPolicy(AttachUserPolicyRequest.builder()
                .userName(USER_NAME).policyArn(READ_ONLY_POLICY_ARN).build());

        ListAttachedUserPoliciesResponse response = iam.listAttachedUserPolicies(ListAttachedUserPoliciesRequest.builder()
                .userName(USER_NAME).build());

        assertThat(response.attachedPolicies())
                .anyMatch(p -> READ_ONLY_POLICY_ARN.equals(p.policyArn())
                        && "ReadOnlyAccess".equals(p.policyName()));

        iam.detachUserPolicy(DetachUserPolicyRequest.builder()
                .userName(USER_NAME).policyArn(READ_ONLY_POLICY_ARN).build());
    }

    @Test
    @Order(38)
    void attachUnknownAwsManagedPolicyThrows() {
        assertThatThrownBy(() -> iam.attachUserPolicy(AttachUserPolicyRequest.builder()
                .userName(USER_NAME)
                .policyArn(AWS_MANAGED_POLICY_PREFIX + "NoSuchManagedPolicyXyz").build()))
                .isInstanceOf(NoSuchEntityException.class);
    }

    // ── Last-Accessed Reporting ────────────────────────────────────────

    @Test
    @Order(102)
    void generateAndGetServiceLastAccessedDetails() {
        GenerateServiceLastAccessedDetailsResponse generated = iam.generateServiceLastAccessedDetails(
                GenerateServiceLastAccessedDetailsRequest.builder()
                        .arn(TestFixtures.globalArn("iam", "000000000000", "user/" + USER_NAME)).build());

        assertThat(generated.jobId()).hasSize(36);

        GetServiceLastAccessedDetailsResponse details = iam.getServiceLastAccessedDetails(
                GetServiceLastAccessedDetailsRequest.builder().jobId(generated.jobId()).build());

        assertThat(details.jobStatusAsString()).isEqualTo("COMPLETED");
        assertThat(details.jobCreationDate()).isNotNull();
        assertThat(details.jobCompletionDate()).isNotNull();
        // A service the entity could reach is listed even though it was never used, so the
        // attempt details are the part that stays null, not the service itself.
        assertThat(details.servicesLastAccessed())
                .allSatisfy(service -> {
                    assertThat(service.serviceNamespace()).isNotBlank();
                    assertThat(service.lastAuthenticated()).isNull();
                    assertThat(service.totalAuthenticatedEntities()).isNull();
                });
    }

    @Test
    @Order(103)
    void getServiceLastAccessedDetailsWithEntitiesDescribesReachingEntities() {
        GenerateServiceLastAccessedDetailsResponse generated = iam.generateServiceLastAccessedDetails(
                GenerateServiceLastAccessedDetailsRequest.builder()
                        .arn(TestFixtures.globalArn("iam", "000000000000", "user/" + USER_NAME)).build());

        GetServiceLastAccessedDetailsWithEntitiesResponse details =
                iam.getServiceLastAccessedDetailsWithEntities(
                        GetServiceLastAccessedDetailsWithEntitiesRequest.builder()
                                .jobId(generated.jobId()).serviceNamespace("s3").build());

        assertThat(details.jobStatusAsString()).isEqualTo("COMPLETED");
        // Entities are those that could reach the service, so the access time is what stays null.
        assertThat(details.entityDetailsList())
                .allSatisfy(entity -> {
                    assertThat(entity.entityInfo().arn()).isNotBlank();
                    assertThat(entity.entityInfo().id()).isNotBlank();
                    assertThat(entity.entityInfo().typeAsString()).isIn("USER", "ROLE", "GROUP");
                    assertThat(entity.lastAuthenticated()).isNull();
                });
    }

    @Test
    @Order(104)
    void getServiceLastAccessedDetailsForUnknownJobThrows() {
        assertThatThrownBy(() -> iam.getServiceLastAccessedDetails(
                GetServiceLastAccessedDetailsRequest.builder()
                        .jobId("00000000-0000-0000-0000-000000000000").build()))
                .isInstanceOf(NoSuchEntityException.class);
    }

    @Test
    @Order(105)
    void listPoliciesGrantingServiceAccessEchoesEachRequestedNamespace() {
        ListPoliciesGrantingServiceAccessResponse response = iam.listPoliciesGrantingServiceAccess(
                ListPoliciesGrantingServiceAccessRequest.builder()
                        .arn(TestFixtures.globalArn("iam", "000000000000", "user/" + USER_NAME))
                        .serviceNamespaces("s3", "dynamodb").build());

        assertThat(response.policiesGrantingServiceAccess())
                .extracting(ListPoliciesGrantingServiceAccessEntry::serviceNamespace)
                .containsExactly("s3", "dynamodb");
    }

    // ── Organizations Access Report ────────────────────────────────────

    @Test
    @Order(106)
    void generateAndGetOrganizationsAccessReport() {
        try (OrganizationsClient organizations =
                     TestFixtures.organizationsClient(REPORT_MANAGEMENT_ACCOUNT);
             IamClient managementIam = TestFixtures.iamClient(REPORT_MANAGEMENT_ACCOUNT)) {
            Organization organization = organizations
                    .createOrganization(r -> r.featureSet(OrganizationFeatureSet.ALL))
                    .organization();

            // Every assertion runs inside the try, so a failure still deletes the organization:
            // one left behind fails the next run's CreateOrganization for this account.
            try {
                Root root = organizations.listRoots().roots().get(0);
                // The report is SCP logic, which the root has to have service control policies
                // enabled for before it can be asked about.
                organizations.enablePolicyType(r -> r.rootId(root.id())
                        .policyType(PolicyType.SERVICE_CONTROL_POLICY));

                GenerateOrganizationsAccessReportResponse generated =
                        managementIam.generateOrganizationsAccessReport(
                                GenerateOrganizationsAccessReportRequest.builder()
                                        .entityPath(organization.id() + "/" + root.id()).build());

                assertThat(generated.jobId()).hasSize(36);

                GetOrganizationsAccessReportResponse report =
                        managementIam.getOrganizationsAccessReport(
                                GetOrganizationsAccessReportRequest.builder()
                                        .jobId(generated.jobId()).build());

                assertThat(report.jobStatus()).isEqualTo(JobStatusType.COMPLETED);
                assertThat(report.jobCreationDate()).isNotNull();
                assertThat(report.jobCompletionDate()).isNotNull();
                assertThat(report.errorDetails()).isNull();
                // The management account is not limited by SCPs, so its report is the whole
                // catalog, and nothing was ever reached, so the two counts are the same number.
                assertThat(report.numberOfServicesAccessible()).isPositive();
                assertThat(report.numberOfServicesNotAccessed())
                        .isEqualTo(report.numberOfServicesAccessible());
                // Those counts describe the whole report while AccessDetails carries one page of
                // it. The catalog is larger than the default MaxItems, so this page is truncated
                // and comes with the marker the next one starts at.
                assertThat(report.accessDetails())
                        .isNotEmpty()
                        .hasSizeLessThanOrEqualTo(report.numberOfServicesAccessible())
                        .allSatisfy(IamTest::assertServiceNobodyReached);
                assertThat(report.isTruncated()).isTrue();
                assertThat(report.marker()).isNotBlank();

                GetOrganizationsAccessReportResponse nextPage =
                        managementIam.getOrganizationsAccessReport(
                                GetOrganizationsAccessReportRequest.builder()
                                        .jobId(generated.jobId())
                                        .marker(report.marker()).build());

                // The page the marker points at is parsed the same way, down to the members:
                // nothing says a later page carries the same shape, and the SDK can hand back a
                // detail with its required members missing.
                assertThat(nextPage.jobStatus()).isEqualTo(JobStatusType.COMPLETED);
                assertThat(nextPage.numberOfServicesAccessible())
                        .isEqualTo(report.numberOfServicesAccessible());
                assertThat(nextPage.numberOfServicesNotAccessed())
                        .isEqualTo(report.numberOfServicesNotAccessed());
                assertThat(nextPage.accessDetails())
                        .isNotEmpty()
                        .doesNotContainAnyElementsOf(report.accessDetails())
                        .allSatisfy(IamTest::assertServiceNobodyReached);
            } finally {
                organizations.deleteOrganization();
            }
        }
    }

    /**
     * A service in the report that principals are allowed to reach and nobody ever did. Floci
     * records no access, so every entry takes that shape: the two required members, a zero count
     * and none of the three the API reference's own example omits for a service with no attempt
     * behind it.
     */
    private static void assertServiceNobodyReached(AccessDetail detail) {
        assertThat(detail.serviceName()).isNotBlank();
        assertThat(detail.serviceNamespace()).isNotBlank();
        assertThat(detail.totalAuthenticatedEntities()).isZero();
        assertThat(detail.lastAuthenticatedTime()).isNull();
        assertThat(detail.region()).isNull();
        assertThat(detail.entityPath()).isNull();
    }
}
