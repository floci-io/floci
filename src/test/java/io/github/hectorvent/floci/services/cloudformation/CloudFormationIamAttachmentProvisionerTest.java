package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CloudFormationResourceRegistry;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamPolicy;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CloudFormationIamAttachmentProvisionerTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String EXISTING_POLICY = "arn:aws:iam::aws:policy/ReadOnlyAccess";
    private static final String NEW_POLICY = "arn:aws:iam::aws:policy/SecurityAudit";
    private static final String MISSING_POLICY = "arn:aws:iam::aws:policy/Missing";

    private final ObjectMapper mapper = new ObjectMapper();
    private IamService iamService;
    private CloudFormationResourceProvisioner provisioner;

    @BeforeEach
    void setUp() {
        iamService = mock(IamService.class);
        provisioner = new CloudFormationResourceProvisioner(
                null, null, null, null, null, iamService, null, null, null, null,
                null, null, null, null, null, null,
                mapper,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                new CloudFormationResourceRegistry(List.of()));
    }

    @Test
    void newRoleAttachmentFailureDetachesPolicyThenDeletesRole() {
        IamRole role = role("new-role");
        when(iamService.createRole("new-role", "/", emptyTrustPolicy(), null, 3600, Map.of()))
                .thenReturn(role);
        doThrow(new AwsException("NoSuchEntity", "missing policy", 404))
                .when(iamService).attachRolePolicy("new-role", MISSING_POLICY);

        StackResource result = provisionRole("new-role", List.of(NEW_POLICY, MISSING_POLICY));

        assertEquals("CREATE_FAILED", result.getStatus());
        assertEquals("missing policy", result.getStatusReason());
        InOrder cleanup = inOrder(iamService);
        cleanup.verify(iamService).detachRolePolicy("new-role", NEW_POLICY);
        cleanup.verify(iamService).deleteRole("new-role");
    }

    @Test
    void sameStackRoleRetryPreservesOriginalPoliciesAndRole() {
        IamRole role = role("existing-role");
        role.getAttachedPolicyArns().add(EXISTING_POLICY);
        when(iamService.createRole("existing-role", "/", emptyTrustPolicy(), null, 3600, Map.of()))
                .thenThrow(new AwsException("EntityAlreadyExists", "already exists", 409));
        when(iamService.getRole("existing-role")).thenReturn(role);
        doThrow(new AwsException("NoSuchEntity", "missing policy", 404))
                .when(iamService).attachRolePolicy("existing-role", MISSING_POLICY);

        StackResource result = provisionRole(
                "existing-role", List.of(EXISTING_POLICY, NEW_POLICY, MISSING_POLICY),
                "existing-role", Map.of("RoleId", role.getRoleId()));

        assertEquals("CREATE_FAILED", result.getStatus());
        verify(iamService).detachRolePolicy("existing-role", NEW_POLICY);
        verify(iamService, never()).detachRolePolicy("existing-role", EXISTING_POLICY);
        verify(iamService, never()).deleteRole("existing-role");
    }

    @Test
    void freshRoleCollisionDoesNotAdoptUserOwnedRole() {
        when(iamService.createRole("external-role", "/", emptyTrustPolicy(), null, 3600, Map.of()))
                .thenThrow(new AwsException("EntityAlreadyExists", "already exists", 409));

        StackResource result = provisionRole("external-role", List.of(NEW_POLICY));

        assertEquals("CREATE_FAILED", result.getStatus());
        assertNull(result.getPhysicalId());
        verify(iamService, never()).getRole("external-role");
        verify(iamService, never()).attachRolePolicy("external-role", NEW_POLICY);
        verify(iamService, never()).deleteRole("external-role");
    }

    @Test
    void generatedRoleNameRemainsStableOnUpdate() {
        IamRole role = role("generated-role");
        when(iamService.createRole("generated-role", "/", emptyTrustPolicy(), null, 3600, Map.of()))
                .thenThrow(new AwsException("EntityAlreadyExists", "already exists", 409));
        when(iamService.getRole("generated-role")).thenReturn(role);

        StackResource result = provision(
                "Role", "AWS::IAM::Role", "{}", "generated-role",
                Map.of("RoleId", role.getRoleId()));

        assertEquals("CREATE_COMPLETE", result.getStatus());
        assertEquals("generated-role", result.getPhysicalId());
    }

    @Test
    void sameNameRoleWithDifferentIdIsNotAdopted() {
        IamRole replacement = role("same-name");
        when(iamService.createRole("same-name", "/", emptyTrustPolicy(), null, 3600, Map.of()))
                .thenThrow(new AwsException("EntityAlreadyExists", "already exists", 409));
        when(iamService.getRole("same-name")).thenReturn(replacement);

        StackResource result = provision(
                "Role", "AWS::IAM::Role", """
                        {"RoleName":"same-name","ManagedPolicyArns":["%s"]}
                        """.formatted(NEW_POLICY), "same-name",
                Map.of("RoleId", "AROAOLD", CloudFormationResourceProvisioner.ROLLBACK_OWNED_ATTR, "true"));

        assertEquals("CREATE_FAILED", result.getStatus());
        assertEquals("already exists", result.getStatusReason());
        assertNull(result.getAttributes().get(CloudFormationResourceProvisioner.ROLLBACK_OWNED_ATTR));
        verify(iamService, never()).attachRolePolicy("same-name", NEW_POLICY);
        verify(iamService, never()).deleteRole("same-name");
    }

    @Test
    void roleNameChangeFailsBeforeCreatingReplacement() {
        StackResource result = provision(
                "Role", "AWS::IAM::Role", "{\"RoleName\":\"new-role\"}",
                "old-role", Map.of());

        assertEquals("CREATE_FAILED", result.getStatus());
        assertEquals("Updating RoleName requires resource replacement, which is not supported.",
                result.getStatusReason());
        verify(iamService, never()).createRole(
                "new-role", "/", emptyTrustPolicy(), null, 3600, Map.of());
    }

    @Test
    void unexpectedRoleCreationFailureIsNotTreatedAsAnExistingRole() {
        when(iamService.createRole("denied-role", "/", emptyTrustPolicy(), null, 3600, Map.of()))
                .thenThrow(new AwsException("AccessDenied", "denied", 403));

        StackResource result = provisionRole("denied-role", List.of());

        assertEquals("CREATE_FAILED", result.getStatus());
        assertEquals("denied", result.getStatusReason());
        verify(iamService, never()).getRole("denied-role");
    }

    @Test
    void managedPolicyAttachmentFailureDetachesRolesThenDeletesPolicy() {
        String policyArn = "arn:aws:iam::" + ACCOUNT_ID + ":policy/test-policy";
        IamPolicy policy = new IamPolicy(
                "ANPATEST", "test-policy", "/", policyArn, null, "{}");
        when(iamService.createPolicy("test-policy", "/", null, policyDocument(), Map.of()))
                .thenReturn(policy);
        doThrow(new AwsException("NoSuchEntity", "missing role", 404))
                .when(iamService).attachRolePolicy("missing-role", policyArn);

        StackResource result = provision("ManagedPolicy", "AWS::IAM::ManagedPolicy", """
                {
                  "ManagedPolicyName": "test-policy",
                  "PolicyDocument": {"Version": "2012-10-17", "Statement": []},
                  "Roles": ["existing-role", "missing-role"]
                }
                """);

        assertEquals("CREATE_FAILED", result.getStatus());
        InOrder cleanup = inOrder(iamService);
        cleanup.verify(iamService).detachRolePolicy("existing-role", policyArn);
        cleanup.verify(iamService).deletePolicy(policyArn);
        verify(iamService, never()).deleteRole("existing-role");
    }

    @Test
    void cleanupFailureDoesNotMaskPrimaryFailureOrSkipDeletion() {
        IamRole role = role("cleanup-role");
        when(iamService.createRole("cleanup-role", "/", emptyTrustPolicy(), null, 3600, Map.of()))
                .thenReturn(role);
        doThrow(new AwsException("NoSuchEntity", "primary failure", 404))
                .when(iamService).attachRolePolicy("cleanup-role", MISSING_POLICY);
        doThrow(new AwsException("ServiceFailure", "cleanup failure", 500))
                .when(iamService).detachRolePolicy("cleanup-role", NEW_POLICY);

        StackResource result = provisionRole("cleanup-role", List.of(NEW_POLICY, MISSING_POLICY));

        assertEquals("CREATE_FAILED", result.getStatus());
        assertEquals("primary failure", result.getStatusReason());
        verify(iamService).detachRolePolicy("cleanup-role", NEW_POLICY);
        verify(iamService).deleteRole("cleanup-role");
    }

    @Test
    void managedPolicyDeletionDetachesStoredRolesBeforeDeletingPolicy() {
        String policyArn = "arn:aws:iam::" + ACCOUNT_ID + ":policy/test-policy";
        StackResource resource = new StackResource();
        resource.setResourceType("AWS::IAM::ManagedPolicy");
        resource.setPhysicalId(policyArn);
        resource.setAttributes(Map.of("ManagedPolicyRoleTargets", "role-a\nrole-b"));

        provisioner.delete(resource, "us-east-1");

        InOrder deletion = inOrder(iamService);
        deletion.verify(iamService).detachRolePolicy("role-a", policyArn);
        deletion.verify(iamService).detachRolePolicy("role-b", policyArn);
        deletion.verify(iamService).deletePolicy(policyArn);
    }

    @Test
    void managedPolicyDeletionIgnoresAlreadyMissingAttachments() {
        String policyArn = "arn:aws:iam::" + ACCOUNT_ID + ":policy/test-policy";
        StackResource resource = new StackResource();
        resource.setResourceType("AWS::IAM::ManagedPolicy");
        resource.setPhysicalId(policyArn);
        resource.setAttributes(Map.of("ManagedPolicyRoleTargets", "missing-role"));
        doThrow(new AwsException("NoSuchEntity", "already gone", 404))
                .when(iamService).detachRolePolicy("missing-role", policyArn);

        provisioner.delete(resource, "us-east-1");

        verify(iamService).deletePolicy(policyArn);
    }

    @Test
    void managedPolicyDeletionDiscoversTargetsForOlderPersistedStacks() {
        String policyArn = "arn:aws:iam::" + ACCOUNT_ID + ":policy/test-policy";
        IamRole role = role("legacy-role");
        role.getAttachedPolicyArns().add(policyArn);
        when(iamService.listRoles("/")).thenReturn(List.of(role));
        StackResource resource = new StackResource();
        resource.setResourceType("AWS::IAM::ManagedPolicy");
        resource.setPhysicalId(policyArn);
        resource.setAttributes(Map.of());

        provisioner.delete(resource, "us-east-1");

        InOrder deletion = inOrder(iamService);
        deletion.verify(iamService).detachRolePolicy("legacy-role", policyArn);
        deletion.verify(iamService).deletePolicy(policyArn);
    }

    @Test
    void managedPolicyDeletionPropagatesUnexpectedDetachFailure() {
        String policyArn = "arn:aws:iam::" + ACCOUNT_ID + ":policy/test-policy";
        StackResource resource = new StackResource();
        resource.setResourceType("AWS::IAM::ManagedPolicy");
        resource.setPhysicalId(policyArn);
        resource.setAttributes(Map.of("ManagedPolicyRoleTargets", "role-a"));
        doThrow(new AwsException("AccessDenied", "denied", 403))
                .when(iamService).detachRolePolicy("role-a", policyArn);

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.delete(resource, "us-east-1"));

        assertEquals("AccessDenied", failure.getErrorCode());
        verify(iamService, never()).deletePolicy(policyArn);
    }

    private StackResource provisionRole(String roleName, List<String> policyArns) {
        return provisionRole(roleName, policyArns, null);
    }

    private StackResource provisionRole(String roleName, List<String> policyArns,
                                        String existingPhysicalId) {
        return provisionRole(roleName, policyArns, existingPhysicalId, Map.of());
    }

    private StackResource provisionRole(String roleName, List<String> policyArns,
                                        String existingPhysicalId,
                                        Map<String, String> existingAttributes) {
        String policies = policyArns.stream()
                .map(value -> "\"" + value + "\"")
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return provision("Role", "AWS::IAM::Role", """
                {"RoleName":"%s","ManagedPolicyArns":[%s]}
                """.formatted(roleName, policies), existingPhysicalId, existingAttributes);
    }

    private StackResource provision(String logicalId, String type, String json) {
        return provision(logicalId, type, json, null, Map.of());
    }

    private StackResource provision(String logicalId, String type, String json,
                                    String existingPhysicalId, Map<String, String> existingAttributes) {
        return provisioner.provision(logicalId, type, props(json), engine(),
                "us-east-1", ACCOUNT_ID, "test-stack", existingPhysicalId, existingAttributes);
    }

    private CloudFormationTemplateEngine engine() {
        return new CloudFormationTemplateEngine(
                ACCOUNT_ID, "us-east-1", "test-stack", "stack/id",
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), mapper,
                (Function<String, String>) name -> null);
    }

    private JsonNode props(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static IamRole role(String name) {
        return new IamRole("AROA" + name, name, "/",
                "arn:aws:iam::" + ACCOUNT_ID + ":role/" + name, emptyTrustPolicy());
    }

    private static String emptyTrustPolicy() {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[]}";
    }

    private static String policyDocument() {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[]}";
    }
}
