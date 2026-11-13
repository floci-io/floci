package io.floci.aws.services.ssoadmin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.services.identitystore.IdentityStoreService;
import io.floci.aws.services.ssooidc.SsoOidcService;
import io.floci.aws.services.ssooidc.model.DeviceAuthorization;
import io.floci.aws.services.ssooidc.model.RegisteredClient;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AttachedPolicy;
import software.amazon.awssdk.services.iam.model.Role;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.SsmException;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Permission-set provisioning must produce the IAM identity used by portal credentials (#8219). */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class SsoPermissionSetRoleIntegrationTest {

    private static final String TARGET = "000000008219";
    private static final String OTHER = "000000008220";
    private static final String AWS_POLICY = "arn:aws:iam::aws:policy/ReadOnlyAccess";
    private static final String OTHER_AWS_POLICY = "arn:aws:iam::aws:policy/AmazonS3ReadOnlyAccess";
    private static final String ROLE_PATH = "/aws-reserved/sso.amazonaws.com/";
    private static final String ALLOW_GET = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
            "Action":"ssm:GetParameter","Resource":"*"}]}""";
    private static final String ALLOW_ALL = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"*","Resource":"*"}]}""";

    @Inject
    ObjectMapper mapper;

    @Inject
    SsoAdminService admin;

    @Inject
    IdentityStoreService identityStore;

    @Inject
    SsoOidcService oidc;

    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureJson() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void provisionCreatesTargetAccountRoleAndSynchronizesPoliciesWithoutReplacingItsIdentity() throws Exception {
        String name = "Provision" + suffix();
        String permissionSet = permissionSet(name);
        IamClient target = iam(TARGET);
        IamClient management = iam("test");
        String customerName = "Customer" + suffix();
        String customer = policy(target, customerName, "/team/");
        policy(management, customerName, "/team/");
        String boundaryName = "Boundary" + suffix();
        String boundary = policy(target, boundaryName, "/team/");
        String replacement = policy(target, "Replacement" + suffix(), "/team/");
        trackRoles(target, name);

        managedPolicy("AttachManagedPolicyToPermissionSet", permissionSet, AWS_POLICY);
        customerPolicy("AttachCustomerManagedPolicyReferenceToPermissionSet", permissionSet,
                customerName.toLowerCase(Locale.ROOT), "/team/");
        inlinePolicy(permissionSet, ALLOW_GET);
        ObjectNode boundaryRequest = request(permissionSet);
        boundaryRequest.putObject("PermissionsBoundary").putObject("CustomerManagedPolicyReference")
                .put("Name", boundaryName).put("Path", "/team/");
        successful("PutPermissionsBoundaryToPermissionSet", boundaryRequest);

        provision(permissionSet, TARGET);
        Role first = provisionedRole(target, name);
        assertEquals(ROLE_PATH, first.path());
        assertEquals("arn:aws:iam::" + TARGET + ":role" + ROLE_PATH + first.roleName(), first.arn());
        assertEquals(Set.of(AWS_POLICY, customer), attachedPolicies(target, first));
        assertEquals(boundary, first.permissionsBoundary().permissionsBoundaryArn());
        List<String> inlineNames = target.listRolePolicies(r -> r.roleName(first.roleName())).policyNames();
        assertEquals(1, inlineNames.size());
        assertEquals(mapper.readTree(ALLOW_GET), mapper.readTree(URLDecoder.decode(
                target.getRolePolicy(r -> r.roleName(first.roleName()).policyName(inlineNames.getFirst()))
                        .policyDocument(), StandardCharsets.UTF_8)));
        assertTrue(roles(management, name).isEmpty(), "Provisioning must not create the role in the management account");
        assertTrue(roles(iam(OTHER), name).isEmpty(), "Provisioning must not leak into another target account");

        managedPolicy("DetachManagedPolicyFromPermissionSet", permissionSet, AWS_POLICY);
        managedPolicy("AttachManagedPolicyToPermissionSet", permissionSet, OTHER_AWS_POLICY);
        customerPolicy("DetachCustomerManagedPolicyReferenceFromPermissionSet", permissionSet,
                customerName.toLowerCase(Locale.ROOT), "/team/");
        customerPolicy("AttachCustomerManagedPolicyReferenceToPermissionSet", permissionSet,
                replacement.substring(replacement.lastIndexOf('/') + 1), "/team/");
        successful("DeleteInlinePolicyFromPermissionSet", request(permissionSet));
        successful("DeletePermissionsBoundaryFromPermissionSet", request(permissionSet));
        provision(permissionSet, TARGET);

        Role updated = provisionedRole(target, name);
        assertEquals(first.roleName(), updated.roleName());
        assertEquals(first.roleId(), updated.roleId());
        assertEquals(first.arn(), updated.arn());
        assertEquals(Set.of(OTHER_AWS_POLICY, replacement), attachedPolicies(target, updated));
        assertTrue(target.listRolePolicies(r -> r.roleName(updated.roleName())).policyNames().isEmpty());
        assertNull(updated.permissionsBoundary());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingTargetCustomerPolicyFailsRatherThanCopyingManagementPolicy(boolean wrongTargetPath) {
        String name = "Missing" + suffix();
        String permissionSet = permissionSet(name);
        String customerName = "ManagementOnly" + suffix();
        IamClient target = iam(TARGET);
        policy(iam("test"), customerName, "/required/");
        if (wrongTargetPath) {
            policy(target, customerName, "/different/");
        }
        trackRoles(target, name);
        customerPolicy("AttachCustomerManagedPolicyReferenceToPermissionSet", permissionSet,
                customerName, "/required/");

        failedProvision(permissionSet, TARGET);

        assertTrue(roles(target, name).isEmpty());
        assertFalse(target.listPolicies().policies().stream().anyMatch(p ->
                p.policyName().equalsIgnoreCase(customerName) && p.path().equals("/required/")),
                "Identity Center must require an existing target policy, not copy the management policy");
        ObjectNode list = request(permissionSet);
        list.put("AccountId", TARGET);
        sso("ListPermissionSetsProvisionedToAccount", list).then().statusCode(200)
                .body("PermissionSets", not(hasItem(permissionSet)));

        String principal = UUID.randomUUID().toString();
        ObjectNode assignment = request(permissionSet).put("TargetId", TARGET).put("TargetType", "AWS_ACCOUNT")
                .put("PrincipalType", "USER").put("PrincipalId", principal);
        sso("CreateAccountAssignment", assignment).then().statusCode(200)
                .body("AccountAssignmentCreationStatus.Status", equalTo("FAILED"))
                .body("AccountAssignmentCreationStatus.FailureReason", not(emptyOrNullString()));
        sso("ListAccountAssignments", request(permissionSet).put("AccountId", TARGET)).then().statusCode(200)
                .body("AccountAssignments.PrincipalId", not(hasItem(principal)));
    }

    @Test
    void missingCustomerManagedBoundaryFailsBeforeCreatingTheRole() {
        String name = "MissingBoundary" + suffix();
        String permissionSet = permissionSet(name);
        String boundaryName = "ManagementBoundary" + suffix();
        policy(iam("test"), boundaryName, "/boundaries/");
        IamClient target = iam(TARGET);
        trackRoles(target, name);
        ObjectNode boundaryRequest = request(permissionSet);
        boundaryRequest.putObject("PermissionsBoundary").putObject("CustomerManagedPolicyReference")
                .put("Name", boundaryName).put("Path", "/boundaries/");
        successful("PutPermissionsBoundaryToPermissionSet", boundaryRequest);

        failedProvision(permissionSet, TARGET);

        assertTrue(roles(target, name).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"Version\":\"2012-10-17\",\"Statement\":[", " \n\t"})
    void malformedInlinePolicyReprovisionRetainsThePreviouslyProvisionedRoleAndPolicies(String invalidInline)
            throws Exception {
        String name = "Rollback" + suffix();
        String permissionSet = permissionSet(name);
        IamClient target = iam(TARGET);
        trackRoles(target, name);
        managedPolicy("AttachManagedPolicyToPermissionSet", permissionSet, AWS_POLICY);
        inlinePolicy(permissionSet, ALLOW_GET);
        provision(permissionSet, TARGET);
        Role first = provisionedRole(target, name);
        List<String> inlineNames = target.listRolePolicies(r -> r.roleName(first.roleName())).policyNames();
        assertEquals(1, inlineNames.size());

        managedPolicy("DetachManagedPolicyFromPermissionSet", permissionSet, AWS_POLICY);
        managedPolicy("AttachManagedPolicyToPermissionSet", permissionSet, OTHER_AWS_POLICY);
        if (invalidInline.isBlank()) {
            sso("PutInlinePolicyToPermissionSet", request(permissionSet).put("InlinePolicy", invalidInline))
                    .then().statusCode(400).body("__type", equalTo("ValidationException"));
        } else {
            inlinePolicy(permissionSet, invalidInline);
            failedProvision(permissionSet, TARGET);
        }

        Role retained = provisionedRole(target, name);
        assertEquals(first.arn(), retained.arn());
        assertEquals(first.roleId(), retained.roleId());
        assertEquals(Set.of(AWS_POLICY), attachedPolicies(target, retained));
        assertEquals(inlineNames, target.listRolePolicies(r -> r.roleName(retained.roleName())).policyNames());
        assertEquals(mapper.readTree(ALLOW_GET), mapper.readTree(URLDecoder.decode(
                target.getRolePolicy(r -> r.roleName(retained.roleName()).policyName(inlineNames.getFirst()))
                        .policyDocument(), StandardCharsets.UTF_8)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void managedPoliciesFromAnotherPartitionCannotBeProvisionedIntoACommercialAccount(boolean boundary) {
        String name = "WrongPartition" + suffix();
        String permissionSet = permissionSet(name);
        IamClient target = iam(TARGET);
        trackRoles(target, name);
        String chinaPolicy = "arn:aws-cn:iam::aws:policy/AdministratorAccess";
        if (boundary) {
            ObjectNode body = request(permissionSet);
            body.putObject("PermissionsBoundary").put("ManagedPolicyArn", chinaPolicy);
            successful("PutPermissionsBoundaryToPermissionSet", body);
        } else {
            managedPolicy("AttachManagedPolicyToPermissionSet", permissionSet, chinaPolicy);
        }

        failedProvision(permissionSet, TARGET);

        assertTrue(roles(target, name).isEmpty(), "A policy from another partition must not produce a target role");
    }

    @Test
    void deletingTheLastAssignmentRemovesTheRoleAndReassignmentGeneratesANewIdentity() {
        String name = "Reassign" + suffix();
        String permissionSet = permissionSet(name);
        IamClient target = iam(TARGET);
        trackRoles(target, name);
        ObjectNode firstAssignment = assignment(permissionSet, UUID.randomUUID().toString());
        ObjectNode secondAssignment = assignment(permissionSet, UUID.randomUUID().toString());
        sso("CreateAccountAssignment", firstAssignment).then().statusCode(200)
                .body("AccountAssignmentCreationStatus.Status", equalTo("SUCCEEDED"));
        sso("CreateAccountAssignment", secondAssignment).then().statusCode(200)
                .body("AccountAssignmentCreationStatus.Status", equalTo("SUCCEEDED"));
        Role first = provisionedRole(target, name);

        sso("DeleteAccountAssignment", firstAssignment).then().statusCode(200)
                .body("AccountAssignmentDeletionStatus.Status", equalTo("SUCCEEDED"));
        assertEquals(first.roleId(), provisionedRole(target, name).roleId());
        sso("DeleteAccountAssignment", secondAssignment).then().statusCode(200)
                .body("AccountAssignmentDeletionStatus.Status", equalTo("SUCCEEDED"));
        assertTrue(roles(target, name).isEmpty(), "The last assignment must remove the generated IAM role");

        sso("CreateAccountAssignment", firstAssignment).then().statusCode(200)
                .body("AccountAssignmentCreationStatus.Status", equalTo("SUCCEEDED"));
        cleanup.register(() -> successful("DeleteAccountAssignment", firstAssignment));
        Role recreated = provisionedRole(target, name);
        assertNotEquals(first.roleName(), recreated.roleName());
        assertNotEquals(first.roleId(), recreated.roleId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletingSsoRecordsPreservesAnExternallyRecreatedRole(boolean deletePermissionSet) {
        String name = "ForeignRole" + suffix();
        String permissionSet = permissionSet(name);
        IamClient target = iam(TARGET);
        trackRoles(target, name);
        ObjectNode assignment = assignment(permissionSet, UUID.randomUUID().toString());
        sso("CreateAccountAssignment", assignment).then().statusCode(200)
                .body("AccountAssignmentCreationStatus.Status", equalTo("SUCCEEDED"));
        Role original = provisionedRole(target, name);
        target.deleteRole(r -> r.roleName(original.roleName()));
        Role replacement = target.createRole(r -> r.roleName(original.roleName()).path(original.path())
                .assumeRolePolicyDocument(URLDecoder.decode(original.assumeRolePolicyDocument(), StandardCharsets.UTF_8)))
                .role();
        target.attachRolePolicy(r -> r.roleName(replacement.roleName()).policyArn(AWS_POLICY));
        assertEquals(original.arn(), replacement.arn());
        assertNotEquals(original.roleId(), replacement.roleId());

        if (deletePermissionSet) {
            successful("DeletePermissionSet", request(permissionSet));
            sso("DescribePermissionSet", request(permissionSet)).then().statusCode(400)
                    .body("__type", equalTo("ResourceNotFoundException"));
        } else {
            sso("DeleteAccountAssignment", assignment).then().statusCode(200)
                    .body("AccountAssignmentDeletionStatus.Status", equalTo("SUCCEEDED"));
            sso("ListAccountAssignments", request(permissionSet).put("AccountId", TARGET)).then().statusCode(200)
                    .body("AccountAssignments.size()", equalTo(0));
        }

        Role retained = target.getRole(r -> r.roleName(replacement.roleName())).role();
        assertEquals(replacement.roleId(), retained.roleId());
        assertEquals(replacement.arn(), retained.arn());
        assertEquals(replacement.path(), retained.path());
        assertEquals(Set.of(AWS_POLICY), attachedPolicies(target, retained));
    }

    @Test
    void stalePermissionSetRemainsUsableInPortalWithItsPreviouslyProvisionedSessionDuration() {
        String name = "StalePortal" + suffix();
        String permissionSet = permissionSet(name);
        IamClient target = iam(TARGET);
        trackRoles(target, name);
        inlinePolicy(permissionSet, ALLOW_GET);
        successful("UpdatePermissionSet", request(permissionSet).put("SessionDuration", "PT1H"));
        String userId = portalUser();
        ObjectNode assignment = assignment(permissionSet, userId);
        sso("CreateAccountAssignment", assignment).then().statusCode(200)
                .body("AccountAssignmentCreationStatus.Status", equalTo("SUCCEEDED"));
        cleanup.register(() -> successful("DeleteAccountAssignment", assignment));
        Role role = provisionedRole(target, name);

        successful("UpdatePermissionSet", request(permissionSet).put("SessionDuration", "PT2H"));
        inlinePolicy(permissionSet, ALLOW_ALL);
        ObjectNode list = mapper.createObjectNode().put("InstanceArn", admin.getInstanceArn())
                .put("AccountId", TARGET).put("ProvisioningStatus", "LATEST_PERMISSION_SET_NOT_PROVISIONED");
        sso("ListPermissionSetsProvisionedToAccount", list).then().statusCode(200)
                .body("PermissionSets", hasItem(permissionSet));

        long requestedAt = System.currentTimeMillis();
        Response portal = given().header("x-amz-sso_bearer_token", accessToken(userId))
                .queryParam("account_id", TARGET).queryParam("role_name", name).get("/federation/credentials");
        portal.then().statusCode(200);
        long expiration = ((Number) portal.path("roleCredentials.expiration")).longValue();
        assertTrue(expiration >= requestedAt + 3_540_000L && expiration <= System.currentTimeMillis() + 3_660_000L,
                "An unprovisioned duration edit must not change the one-hour provisioned session");
        AwsSessionCredentials credentials = AwsSessionCredentials.create(
                portal.path("roleCredentials.accessKeyId"), portal.path("roleCredentials.secretAccessKey"),
                portal.path("roleCredentials.sessionToken"));
        try (StsClient sts = AwsTestClients.create(StsClient.builder(), credentials, Region.US_EAST_1, endpoint)) {
            assertTrue(sts.getCallerIdentity().arn().contains(role.roleName()));
        }
        assertEquals(role.roleId(), provisionedRole(target, name).roleId());
    }

    @Test
    void accountAssignmentProvisionsTheRoleUsedByPortalCredentialsAndIamEnforcement() {
        String name = "Portal" + suffix();
        String permissionSet = permissionSet(name);
        IamClient target = iam(TARGET);
        String boundaryName = "PortalBoundary" + suffix();
        String boundary = target.createPolicy(r -> r.policyName(boundaryName).policyDocument(ALLOW_GET)).policy().arn();
        cleanup.register(() -> target.deletePolicy(r -> r.policyArn(boundary)));
        trackRoles(target, name);
        inlinePolicy(permissionSet, ALLOW_ALL);
        ObjectNode boundaryRequest = request(permissionSet);
        boundaryRequest.putObject("PermissionsBoundary").putObject("CustomerManagedPolicyReference")
                .put("Name", boundaryName).put("Path", "/");
        successful("PutPermissionsBoundaryToPermissionSet", boundaryRequest);
        String userId = portalUser();
        ObjectNode assignment = assignment(permissionSet, userId);
        sso("CreateAccountAssignment", assignment).then().statusCode(200)
                .body("AccountAssignmentCreationStatus.Status", equalTo("SUCCEEDED"));
        cleanup.register(() -> successful("DeleteAccountAssignment", assignment));
        Role role = provisionedRole(target, name);
        assertEquals(ROLE_PATH, role.path());

        Response portal = given().header("x-amz-sso_bearer_token", accessToken(userId))
                .queryParam("account_id", TARGET).queryParam("role_name", name)
                .get("/federation/credentials");
        portal.then().statusCode(200);
        AwsSessionCredentials credentials = AwsSessionCredentials.create(
                portal.path("roleCredentials.accessKeyId"), portal.path("roleCredentials.secretAccessKey"),
                portal.path("roleCredentials.sessionToken"));
        String parameter = "/sso-role-" + suffix();
        try (SsmClient owner = AwsTestClients.create(SsmClient.builder(), TARGET, Region.US_EAST_1, endpoint);
             SsmClient session = AwsTestClients.create(SsmClient.builder(), credentials, Region.US_EAST_1, endpoint);
             StsClient sts = AwsTestClients.create(StsClient.builder(), credentials, Region.US_EAST_1, endpoint)) {
            owner.putParameter(r -> r.name(parameter).type("String").value("permitted"));
            cleanup.register(() -> {
                try (SsmClient client = AwsTestClients.create(SsmClient.builder(), TARGET, Region.US_EAST_1, endpoint)) {
                    client.deleteParameter(r -> r.name(parameter));
                }
            });
            GetCallerIdentityResponse caller = sts.getCallerIdentity();
            assertEquals(TARGET, caller.account());
            assertTrue(caller.arn().contains(role.roleName()), "The session must identify the actual provisioned role");
            assertEquals("permitted", session.getParameter(r -> r.name(parameter)).parameter().value());
            SsmException denied = assertThrows(SsmException.class,
                    () -> session.putParameter(r -> r.name(parameter).type("String").value("forbidden").overwrite(true)));
            assertEquals(400, denied.statusCode());
            assertEquals("AccessDeniedException", denied.awsErrorDetails().errorCode());
            assertEquals("permitted", owner.getParameter(r -> r.name(parameter)).parameter().value());
        }
    }

    private String permissionSet(String name) {
        ObjectNode create = mapper.createObjectNode().put("InstanceArn", admin.getInstanceArn()).put("Name", name);
        String arn = sso("CreatePermissionSet", create).then().statusCode(200)
                .extract().path("PermissionSet.PermissionSetArn");
        cleanup.register(() -> {
            Response deleted = sso("DeletePermissionSet", request(arn));
            if (deleted.statusCode() == 400) {
                deleted.then().body("__type", equalTo("ResourceNotFoundException"));
            } else {
                deleted.then().statusCode(200);
            }
        });
        return arn;
    }

    private ObjectNode request(String permissionSet) {
        return mapper.createObjectNode().put("InstanceArn", admin.getInstanceArn())
                .put("PermissionSetArn", permissionSet);
    }

    private ObjectNode assignment(String permissionSet, String principal) {
        return request(permissionSet).put("TargetId", TARGET).put("TargetType", "AWS_ACCOUNT")
                .put("PrincipalType", "USER").put("PrincipalId", principal);
    }

    private String portalUser() {
        ObjectNode user = mapper.createObjectNode().put("IdentityStoreId", admin.getIdentityStoreId())
                .put("UserName", "portal-" + suffix() + "@example.com");
        String userId = identityStore.createUser(user).userId();
        cleanup.register(() -> identityStore.deleteUser(mapper.createObjectNode()
                .put("IdentityStoreId", admin.getIdentityStoreId()).put("UserId", userId)));
        return userId;
    }

    private void provision(String permissionSet, String account) {
        sso("ProvisionPermissionSet", request(permissionSet).put("TargetType", "AWS_ACCOUNT").put("TargetId", account))
                .then().statusCode(200).body("PermissionSetProvisioningStatus.Status", equalTo("SUCCEEDED"));
    }

    private void failedProvision(String permissionSet, String account) {
        sso("ProvisionPermissionSet", request(permissionSet).put("TargetType", "AWS_ACCOUNT").put("TargetId", account))
                .then().statusCode(200).body("PermissionSetProvisioningStatus.Status", equalTo("FAILED"))
                .body("PermissionSetProvisioningStatus.FailureReason", not(emptyOrNullString()));
    }

    private void managedPolicy(String action, String permissionSet, String policyArn) {
        successful(action, request(permissionSet).put("ManagedPolicyArn", policyArn));
    }

    private void customerPolicy(String action, String permissionSet, String name, String path) {
        ObjectNode body = request(permissionSet);
        body.putObject("CustomerManagedPolicyReference").put("Name", name).put("Path", path);
        successful(action, body);
    }

    private void inlinePolicy(String permissionSet, String document) {
        successful("PutInlinePolicyToPermissionSet", request(permissionSet).put("InlinePolicy", document));
    }

    private Response sso(String action, ObjectNode body) {
        return given().contentType("application/x-amz-json-1.1")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20261113/us-east-1/sso/aws4_request")
                .header("X-Amz-Target", "SWBExternalService." + action).body(body.toString()).post("/");
    }

    private void successful(String action, ObjectNode body) {
        sso(action, body).then().statusCode(200);
    }

    private IamClient iam(String account) {
        IamClient client = AwsTestClients.create(IamClient.builder(), account, Region.US_EAST_1, endpoint);
        cleanup.register(client::close);
        return client;
    }

    private String policy(IamClient client, String name, String path) {
        String arn = client.createPolicy(r -> r.policyName(name).path(path).policyDocument(ALLOW_ALL)).policy().arn();
        cleanup.register(() -> client.deletePolicy(r -> r.policyArn(arn)));
        return arn;
    }

    private List<Role> roles(IamClient client, String permissionSetName) {
        return client.listRoles(r -> r.pathPrefix(ROLE_PATH)).roles().stream()
                .filter(r -> r.roleName().startsWith("AWSReservedSSO_" + permissionSetName + "_")).toList();
    }

    private Role provisionedRole(IamClient client, String permissionSetName) {
        List<Role> roles = roles(client, permissionSetName);
        assertEquals(1, roles.size(), "Permission-set provisioning must create exactly one real IAM role in the target account");
        Role role = client.getRole(r -> r.roleName(roles.getFirst().roleName())).role();
        assertTrue(role.roleName().matches("AWSReservedSSO_" + permissionSetName + "_[0-9a-fA-F]{16}"));
        return role;
    }

    private Set<String> attachedPolicies(IamClient client, Role role) {
        return client.listAttachedRolePolicies(r -> r.roleName(role.roleName())).attachedPolicies().stream()
                .map(AttachedPolicy::policyArn).collect(Collectors.toSet());
    }

    private void trackRoles(IamClient client, String name) {
        cleanup.register(() -> {
            for (Role role : roles(client, name)) {
                for (AttachedPolicy policy : client.listAttachedRolePolicies(r -> r.roleName(role.roleName())).attachedPolicies()) {
                    client.detachRolePolicy(r -> r.roleName(role.roleName()).policyArn(policy.policyArn()));
                }
                for (String policy : client.listRolePolicies(r -> r.roleName(role.roleName())).policyNames()) {
                    client.deleteRolePolicy(r -> r.roleName(role.roleName()).policyName(policy));
                }
                if (client.getRole(r -> r.roleName(role.roleName())).role().permissionsBoundary() != null) {
                    client.deleteRolePermissionsBoundary(r -> r.roleName(role.roleName()));
                }
                client.deleteRole(r -> r.roleName(role.roleName()));
            }
        });
    }

    private String accessToken(String userId) {
        ObjectNode registration = mapper.createObjectNode().put("clientName", "Role regression " + suffix())
                .put("clientType", "public");
        registration.putArray("grantTypes").add("urn:ietf:params:oauth:grant-type:device_code");
        registration.putArray("scopes").add("sso:account:access");
        RegisteredClient client = oidc.registerClient(registration);
        DeviceAuthorization authorization = oidc.startDeviceAuthorization(mapper.createObjectNode()
                .put("clientId", client.clientId()).put("clientSecret", client.clientSecret())
                .put("startUrl", "https://example.awsapps.com/start"));
        oidc.authorizeDevice(authorization.userCode(), userId);
        return oidc.createToken(mapper.createObjectNode().put("clientId", client.clientId())
                .put("clientSecret", client.clientSecret()).put("grantType", "urn:ietf:params:oauth:grant-type:device_code")
                .put("deviceCode", authorization.deviceCode())).accessToken();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
