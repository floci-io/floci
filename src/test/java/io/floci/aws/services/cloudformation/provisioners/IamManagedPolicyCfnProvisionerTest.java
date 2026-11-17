package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.IamGroup;
import io.floci.aws.services.iam.model.IamPolicy;
import io.floci.aws.services.iam.model.IamUser;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code AWS::IAM::ManagedPolicy} in isolation: a create publishes the ARN for Ref and Fn::GetAtt
 * PolicyArn, attaches the declared roles, and a delete detaches the roles before removing the
 * policy, tolerating an already-gone attachment while propagating real failures. The deep
 * adopt-on-update, version-pruning and rollback paths are exercised through the dispatcher by
 * {@code CloudFormationIamAttachmentProvisionerTest} and the managed-policy integration test.
 */
class IamManagedPolicyCfnProvisionerTest {

    private static final String TYPE = "AWS::IAM::ManagedPolicy";
    private static final String ARN = "arn:aws:iam::000000000000:policy/app-policy";

    private final IamService iam = mock(IamService.class);
    private final IamManagedPolicyCfnProvisioner provisioner = new IamManagedPolicyCfnProvisioner(iam);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void createPublishesTheArnAndAttachesRoles() throws Exception {
        IamPolicy policy = mock(IamPolicy.class);
        when(policy.getArn()).thenReturn(ARN);
        when(policy.getPolicyId()).thenReturn("AID1");
        when(policy.getDefaultVersionId()).thenReturn("v1");
        when(iam.createPolicy(eq("app-policy"), eq("/"), eq(null), any(), eq(Map.of()))).thenReturn(policy);

        StackResource r = resource();
        provisioner.provision(r, props("""
                {"ManagedPolicyName": "app-policy",
                 "PolicyDocument": {"Version": "2012-10-17", "Statement": []},
                 "Roles": ["web-role"]}
                """), ctx(null));

        assertEquals(ARN, r.getPhysicalId());
        assertEquals(ARN, r.getAttributes().get("PolicyArn"));
        assertEquals(ARN, r.getAttributes().get("Arn"));
        assertEquals("v1", r.getAttributes().get("DefaultVersionId"));
        verify(iam).attachRolePolicy("web-role", ARN);
    }

    @Test
    void missingGroupFailsBeforeCreatingThePolicy() throws Exception {
        when(iam.getGroup("missing-group"))
                .thenThrow(new AwsException("NoSuchEntity", "missing group", 404));

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(resource(), props("""
                {"ManagedPolicyName": "app-policy",
                 "PolicyDocument": {"Version": "2012-10-17", "Statement": []},
                 "Users": ["app-user"], "Groups": ["missing-group"]}
                """), ctx(null)));

        assertEquals("NoSuchEntity", failure.getErrorCode());
        verify(iam, never()).createPolicy(eq("app-policy"), eq("/"), eq(null), any(), eq(Map.of()));
    }

    @Test
    void failedAttachmentSnapshotDoesNotMutateAnExistingPolicy() throws Exception {
        IamPolicy policy = mock(IamPolicy.class);
        when(policy.getPolicyId()).thenReturn("AID1");
        when(iam.createPolicy(eq("app-policy"), eq("/"), eq(null), any(), eq(Map.of())))
                .thenThrow(new AwsException("EntityAlreadyExists", "already exists", 409));
        when(iam.getPolicy(ARN)).thenReturn(policy);
        when(iam.listEntitiesForPolicy(ARN))
                .thenThrow(new AwsException("ServiceFailure", "snapshot failed", 500));
        StackResource r = resource();
        r.setPhysicalId(ARN);
        r.getAttributes().put("PolicyId", "AID1");

        assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {"ManagedPolicyName": "app-policy",
                 "PolicyDocument": {"Version": "2012-10-17", "Statement": []},
                 "Users": ["app-user"]}
                """), ctx(ARN)));

        verify(iam, never()).createPolicyVersion(eq(ARN), any(), eq(true));
        verify(iam, never()).detachUserPolicy("app-user", ARN);
    }

    @Test
    void updateDetachesExplicitLegacyUserAndGroupTargets() throws Exception {
        IamPolicy policy = mock(IamPolicy.class);
        when(policy.getArn()).thenReturn(ARN);
        when(policy.getPolicyId()).thenReturn("AID1");
        when(policy.getDefaultVersionId()).thenReturn("v1");
        when(iam.createPolicy(eq("app-policy"), eq("/"), eq(null), any(), eq(Map.of())))
                .thenThrow(new AwsException("EntityAlreadyExists", "already exists", 409));
        when(iam.getPolicy(ARN)).thenReturn(policy);
        when(iam.listEntitiesForPolicy(ARN))
                .thenReturn(new IamService.PolicyEntities(List.of(), List.of(), List.of()));
        when(iam.listPolicyVersions(ARN)).thenReturn(List.of());
        StackResource r = resource();
        r.setPhysicalId(ARN);
        r.getAttributes().put("PolicyId", "AID1");
        r.getAttributes().put("ManagedPolicyUserTargets", "legacy-user");
        r.getAttributes().put("ManagedPolicyGroupTargets", "legacy-group");

        provisioner.provision(r, props("""
                {"ManagedPolicyName": "app-policy",
                 "PolicyDocument": {"Version": "2012-10-17", "Statement": []}}
                """), ctx(ARN));

        verify(iam).detachUserPolicy("legacy-user", ARN);
        verify(iam).detachGroupPolicy("legacy-group", ARN);
        verify(iam, never()).listUsers("/");
        verify(iam, never()).listGroups("/");
        assertEquals("", r.getAttributes().get("ManagedPolicyUserTargets"));
        assertEquals("", r.getAttributes().get("ManagedPolicyGroupTargets"));
    }

    @Test
    void failedGroupAttachUndoesNewRoleAndUserAttachments() throws Exception {
        IamPolicy policy = mock(IamPolicy.class);
        when(policy.getArn()).thenReturn(ARN);
        when(iam.createPolicy(eq("app-policy"), eq("/"), eq(null), any(), eq(Map.of()))).thenReturn(policy);
        doThrow(new AwsException("ServiceFailure", "group attach failed", 500))
                .when(iam).attachGroupPolicy("app-group", ARN);

        assertThrows(AwsException.class, () -> provisioner.provision(resource(), props("""
                {"ManagedPolicyName": "app-policy",
                 "PolicyDocument": {"Version": "2012-10-17", "Statement": []},
                 "Roles": ["app-role"], "Users": ["app-user"], "Groups": ["app-group"]}
                """), ctx(null)));

        InOrder order = inOrder(iam);
        order.verify(iam).attachRolePolicy("app-role", ARN);
        order.verify(iam).attachUserPolicy("app-user", ARN);
        order.verify(iam).attachGroupPolicy("app-group", ARN);
        order.verify(iam).detachUserPolicy("app-user", ARN);
        order.verify(iam).detachRolePolicy("app-role", ARN);
        order.verify(iam).deletePolicy(ARN);
    }

    @Test
    void deleteDetachesRolesThenRemovesThePolicy() {
        StackResource r = resource();
        r.setPhysicalId(ARN);
        r.getAttributes().put("ManagedPolicyRoleTargets", "web-role");

        provisioner.delete(r, "us-east-1");

        InOrder order = inOrder(iam);
        order.verify(iam).detachRolePolicy("web-role", ARN);
        order.verify(iam).deletePolicy(ARN);
    }

    @Test
    void deleteDetachesEveryStoredPrincipalBeforeRemovingThePolicy() {
        StackResource r = resource();
        r.setPhysicalId(ARN);
        r.getAttributes().put("ManagedPolicyRoleTargets", "app-role");
        r.getAttributes().put("ManagedPolicyUserTargets", "app-user");
        r.getAttributes().put("ManagedPolicyGroupTargets", "app-group");
        r.getAttributes().put("ManagedPolicyUserTargetIds", "AIDAUSER");
        r.getAttributes().put("ManagedPolicyGroupTargetIds", "AGROUP");
        IamUser user = mock(IamUser.class);
        when(user.getUserId()).thenReturn("AIDAUSER");
        when(user.getUserName()).thenReturn("app-user");
        when(iam.listUsers("/")).thenReturn(List.of(user));
        IamGroup group = mock(IamGroup.class);
        when(group.getGroupId()).thenReturn("AGROUP");
        when(group.getGroupName()).thenReturn("app-group");
        when(iam.listGroups("/")).thenReturn(List.of(group));

        provisioner.delete(r, "us-east-1");

        InOrder order = inOrder(iam);
        order.verify(iam).detachRolePolicy("app-role", ARN);
        order.verify(iam).detachUserPolicy("app-user", ARN);
        order.verify(iam).detachGroupPolicy("app-group", ARN);
        order.verify(iam).deletePolicy(ARN);
    }

    @Test
    void deleteUsesExplicitLegacyUserAndGroupTargetsWithoutScanning() {
        StackResource r = resource();
        r.setPhysicalId(ARN);
        r.getAttributes().put("ManagedPolicyUserTargets", "legacy-user");
        r.getAttributes().put("ManagedPolicyGroupTargets", "legacy-group");

        provisioner.delete(r, "us-east-1");

        verify(iam).detachUserPolicy("legacy-user", ARN);
        verify(iam).detachGroupPolicy("legacy-group", ARN);
        verify(iam, never()).listUsers("/");
        verify(iam, never()).listGroups("/");
        verify(iam).deletePolicy(ARN);
    }

    @Test
    void legacyPolicyDeletePreservesUnrecordedUserAndGroupAttachments() {
        StackResource r = resource();
        r.setPhysicalId(ARN);
        when(iam.listRoles("/")).thenReturn(List.of());
        IamUser outsideUser = mock(IamUser.class);
        when(outsideUser.getAttachedPolicyArns()).thenReturn(new ArrayList<>(List.of(ARN)));
        when(outsideUser.getUserName()).thenReturn("outside-user");
        when(iam.listUsers("/")).thenReturn(List.of(outsideUser));
        IamGroup outsideGroup = mock(IamGroup.class);
        when(outsideGroup.getAttachedPolicyArns()).thenReturn(new ArrayList<>(List.of(ARN)));
        when(outsideGroup.getGroupName()).thenReturn("outside-group");
        when(iam.listGroups("/")).thenReturn(List.of(outsideGroup));
        doThrow(new AwsException("DeleteConflict", "policy still attached", 409))
                .when(iam).deletePolicy(ARN);

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.delete(r, "us-east-1"));

        assertEquals("DeleteConflict", failure.getErrorCode());
        verify(iam, never()).detachUserPolicy("outside-user", ARN);
        verify(iam, never()).detachGroupPolicy("outside-group", ARN);
        verify(iam, never()).listUsers("/");
        verify(iam, never()).listGroups("/");
    }

    @Test
    void emptyRecordedPrincipalTargetsDoNotScanOrDetach() {
        StackResource r = resource();
        r.setPhysicalId(ARN);
        r.getAttributes().put("ManagedPolicyUserTargets", "\n");
        r.getAttributes().put("ManagedPolicyGroupTargets", "\n");
        r.getAttributes().put("ManagedPolicyUserTargetIds", "AIDAUSER");
        r.getAttributes().put("ManagedPolicyGroupTargetIds", "AGROUP");

        provisioner.delete(r, "us-east-1");

        verify(iam, never()).listUsers("/");
        verify(iam, never()).listGroups("/");
        verify(iam, never()).detachUserPolicy(any(), eq(ARN));
        verify(iam, never()).detachGroupPolicy(any(), eq(ARN));
        verify(iam).deletePolicy(ARN);
    }

    @Test
    void deleteToleratesAnAlreadyDetachedRole() {
        StackResource r = resource();
        r.setPhysicalId(ARN);
        r.getAttributes().put("ManagedPolicyRoleTargets", "web-role");
        doThrow(new AwsException("NoSuchEntity", "gone", 404))
                .when(iam).detachRolePolicy("web-role", ARN);

        assertDoesNotThrow(() -> provisioner.delete(r, "us-east-1"));
        verify(iam).deletePolicy(ARN);
    }

    @Test
    void deletePropagatesANonNotFoundDetachFailure() {
        StackResource r = resource();
        r.setPhysicalId(ARN);
        r.getAttributes().put("ManagedPolicyRoleTargets", "web-role");
        doThrow(new AwsException("AccessDenied", "nope", 403))
                .when(iam).detachRolePolicy("web-role", ARN);

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.delete(r, "us-east-1"));
        assertEquals("AccessDenied", failure.getErrorCode());
    }

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveJsonAttributeStrict(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.toString();
        });
        when(engine.resolveStringList(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            List<String> out = new ArrayList<>();
            if (node != null && node.isArray()) {
                node.forEach(element -> out.add(element.asText()));
            }
            return out;
        });
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack", priorPhysicalId);
    }

    private JsonNode props(String json) throws Exception {
        return mapper.readTree(json);
    }

    private static StackResource resource() {
        StackResource r = new StackResource();
        r.setLogicalId("Policy");
        r.setResourceType(TYPE);
        r.setAttributes(new HashMap<>());
        return r;
    }
}
