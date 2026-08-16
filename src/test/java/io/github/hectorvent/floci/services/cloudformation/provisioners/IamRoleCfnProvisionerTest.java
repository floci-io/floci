package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The IAM role CFN provisioner in isolation, with the inline {@code Policies} behavior of #1952.
 * Rollback and role-adoption paths are covered end to end through the public provision entry point
 * in {@code CloudFormationIamAttachmentProvisionerTest}.
 */
class IamRoleCfnProvisionerTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String EMPTY_TRUST = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";

    private final IamService iam = mock(IamService.class);
    private final IamRoleCfnProvisioner provisioner = new IamRoleCfnProvisioner(iam);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        when(engine.resolveJsonAttribute(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node != null && node.isTextual() ? node.asText() : node.toString();
        });
        return new ProvisionContext(engine, "us-east-1", ACCOUNT_ID, "test-stack");
    }

    private StackResource resource() {
        StackResource r = new StackResource();
        r.setLogicalId("AppRole");
        r.setResourceType("AWS::IAM::Role");
        r.setAttributes(new HashMap<>());
        return r;
    }

    private JsonNode props(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private IamRole stubCreate(String roleName) {
        IamRole role = new IamRole("AROA" + roleName, roleName, "/",
                "arn:aws:iam::" + ACCOUNT_ID + ":role/" + roleName, EMPTY_TRUST);
        when(iam.createRole(eq(roleName), eq("/"), anyString(), any(), eq(3600), eq(Map.of())))
                .thenReturn(role);
        return role;
    }

    @Test
    void inlinePoliciesArePutOnTheRoleWithResolvedDocuments() {
        stubCreate("app-role");
        StackResource r = resource();

        provisioner.provision(r, props("""
                {
                  "RoleName": "app-role",
                  "Policies": [
                    {"PolicyName": "bucket-read",
                     "PolicyDocument": {"Version": "2012-10-17", "Statement": []}},
                    {"PolicyName": "log-write",
                     "PolicyDocument": {"Version": "2012-10-17", "Statement": []}}
                  ]
                }
                """), ctx());

        assertEquals("app-role", r.getPhysicalId());
        InOrder order = inOrder(iam);
        order.verify(iam).putRolePolicy("app-role", "bucket-read", EMPTY_TRUST);
        order.verify(iam).putRolePolicy("app-role", "log-write", EMPTY_TRUST);
    }

    @Test
    void inlinePolicyPassesThroughAlreadySerializedPolicyDocumentString() {
        // Same failure mode as #2317: CDK can emit an inline PolicyDocument as an
        // already-serialized JSON string (e.g. via Fn::Join). resolveNode collapses that to a
        // TextNode whose toString() re-quotes/escapes the JSON; the policy must be stored verbatim.
        stubCreate("app-role");
        StackResource r = resource();
        String serialized = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}";
        JsonNode props = props(String.format("""
                {
                  "RoleName": "app-role",
                  "Policies": [
                    {"PolicyName": "serialized-doc",
                     "PolicyDocument": "%s"}
                  ]
                }
                """, serialized.replace("\"", "\\\"")));

        provisioner.provision(r, props, ctx());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<String> docCaptor = ArgumentCaptor.forClass(String.class);
        verify(iam).putRolePolicy(eq("app-role"), eq("serialized-doc"), docCaptor.capture());
        assertEquals(serialized, docCaptor.getValue());
    }

    @Test
    void inlinePolicyWithoutANameFailsTheResource() {
        // PolicyName is required on AWS::IAM::Role Policies. Generating one gave the same policy a
        // fresh name on every execution, so an update accumulated copies instead of replacing it.
        stubCreate("app-role");
        StackResource r = resource();

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {
                  "RoleName": "app-role",
                  "Policies": [{"PolicyDocument": {"Version": "2012-10-17", "Statement": []}}]
                }
                """), ctx()));

        assertEquals("ValidationError", failure.getErrorCode());
        verify(iam, never()).putRolePolicy(anyString(), anyString(), anyString());
        verify(iam).deleteRole("app-role");
        assertNull(r.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR));
    }

    @Test
    void inlinePolicyWithoutADocumentFailsTheResource() {
        // Skipping it reached CREATE_COMPLETE without the declared policy — the same class of
        // bug as the silent drop this PR is about.
        stubCreate("app-role");
        StackResource r = resource();

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {"RoleName": "app-role", "Policies": [{"PolicyName": "no-document"}]}
                """), ctx()));

        assertEquals("ValidationError", failure.getErrorCode());
        verify(iam, never()).putRolePolicy(anyString(), anyString(), anyString());
        // The role this attempt created is cleaned up rather than left behind.
        verify(iam).deleteRole("app-role");
        assertNull(r.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR));
    }

    @Test
    void inlinePolicyFailureRemovesTheRoleAndTheEarlierInlineWrites() {
        stubCreate("app-role");
        doThrow(new AwsException("MalformedPolicyDocument", "bad policy", 400))
                .when(iam).putRolePolicy(eq("app-role"), eq("second"), anyString());
        StackResource r = resource();

        assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {
                  "RoleName": "app-role",
                  "Policies": [
                    {"PolicyName": "first", "PolicyDocument": {"Version": "2012-10-17", "Statement": []}},
                    {"PolicyName": "second", "PolicyDocument": {"Version": "2012-10-17", "Statement": []}}
                  ]
                }
                """), ctx()));

        // "first" was written by this attempt and had no prior value, so it is removed, and the
        // role goes with it. Previously both survived a CREATE_FAILED.
        verify(iam).deleteRolePolicy("app-role", "first");
        verify(iam).deleteRole("app-role");
        assertNull(r.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR));
    }

    @Test
    void updateRemovesTheInlinePoliciesAndAttachmentsTheTemplateDropped() {
        // A policy the previous execution wrote and the new template no longer declares used to
        // stay on the role while the stack still reported UPDATE_COMPLETE.
        IamRole existing = new IamRole("AROAapp-role", "app-role", "/",
                "arn:aws:iam::" + ACCOUNT_ID + ":role/app-role", EMPTY_TRUST);
        existing.getInlinePolicies().put("keep", EMPTY_TRUST);
        existing.getInlinePolicies().put("drop", EMPTY_TRUST);
        existing.getAttachedPolicyArns().add("arn:aws:iam::aws:policy/Keep");
        existing.getAttachedPolicyArns().add("arn:aws:iam::aws:policy/Drop");
        when(iam.createRole(eq("app-role"), eq("/"), anyString(), any(), eq(3600), eq(Map.of())))
                .thenThrow(new AwsException("EntityAlreadyExists", "exists", 409));
        when(iam.getRole("app-role")).thenReturn(existing);

        StackResource r = resource();
        r.setPhysicalId("app-role");
        r.getAttributes().put("RoleId", "AROAapp-role");
        r.getAttributes().put("__FlociInlinePolicyNames", "keep\ndrop");
        r.getAttributes().put("__FlociManagedPolicyArns",
                "arn:aws:iam::aws:policy/Keep\narn:aws:iam::aws:policy/Drop");

        provisioner.provision(r, props("""
                {
                  "RoleName": "app-role",
                  "ManagedPolicyArns": ["arn:aws:iam::aws:policy/Keep"],
                  "Policies": [
                    {"PolicyName": "keep", "PolicyDocument": {"Version": "2012-10-17", "Statement": []}}
                  ]
                }
                """), ctx());

        verify(iam).deleteRolePolicy("app-role", "drop");
        verify(iam).detachRolePolicy("app-role", "arn:aws:iam::aws:policy/Drop");
        verify(iam, never()).deleteRolePolicy("app-role", "keep");
        verify(iam, never()).detachRolePolicy("app-role", "arn:aws:iam::aws:policy/Keep");
        assertEquals("keep", r.getAttributes().get("__FlociInlinePolicyNames"));
    }

    @Test
    void updateToleratesATrackedPolicyThatIsAlreadyGone() {
        // Removed out of band between executions. deleteRolePolicy and detachRolePolicy both raise
        // NoSuchEntity on an absent target, which would fail an update whose desired end state,
        // the policy not being on the role, already holds.
        IamRole existing = new IamRole("AROAapp-role", "app-role", "/",
                "arn:aws:iam::" + ACCOUNT_ID + ":role/app-role", EMPTY_TRUST);
        when(iam.createRole(eq("app-role"), eq("/"), anyString(), any(), eq(3600), eq(Map.of())))
                .thenThrow(new AwsException("EntityAlreadyExists", "exists", 409));
        when(iam.getRole("app-role")).thenReturn(existing);

        StackResource r = resource();
        r.setPhysicalId("app-role");
        r.getAttributes().put("RoleId", "AROAapp-role");
        r.getAttributes().put("__FlociInlinePolicyNames", "gone");
        r.getAttributes().put("__FlociManagedPolicyArns", "arn:aws:iam::aws:policy/Gone");

        provisioner.provision(r, props("""
                {"RoleName": "app-role", "Policies": []}
                """), ctx());

        verify(iam, never()).deleteRolePolicy(anyString(), anyString());
        verify(iam, never()).detachRolePolicy(anyString(), anyString());
        assertNull(r.getAttributes().get("__FlociInlinePolicyNames"));
    }

    @Test
    void updateLeavesInlinePoliciesTheStackNeverWroteAlone() {
        // Only names a previous execution recorded are removed, so a policy added out of band
        // survives an update that does not declare it.
        IamRole existing = new IamRole("AROAapp-role", "app-role", "/",
                "arn:aws:iam::" + ACCOUNT_ID + ":role/app-role", EMPTY_TRUST);
        existing.getInlinePolicies().put("added-out-of-band", EMPTY_TRUST);
        when(iam.createRole(eq("app-role"), eq("/"), anyString(), any(), eq(3600), eq(Map.of())))
                .thenThrow(new AwsException("EntityAlreadyExists", "exists", 409));
        when(iam.getRole("app-role")).thenReturn(existing);

        StackResource r = resource();
        r.setPhysicalId("app-role");
        r.getAttributes().put("RoleId", "AROAapp-role");

        provisioner.provision(r, props("""
                {"RoleName": "app-role", "Policies": []}
                """), ctx());

        verify(iam, never()).deleteRolePolicy(anyString(), anyString());
    }

    @Test
    void failedUpdateRestoresTheInlinePolicyItAlreadyOverwrote() {
        // An update that adopts an existing role must not keep the permissions a half-applied
        // attempt granted, and must not delete a role it did not create.
        String priorDocument = "{\"Version\":\"2012-10-17\",\"Statement\":[\"prior\"]}";
        IamRole existing = new IamRole("AROAapp-role", "app-role", "/",
                "arn:aws:iam::" + ACCOUNT_ID + ":role/app-role", EMPTY_TRUST);
        existing.getInlinePolicies().put("first", priorDocument);
        when(iam.createRole(eq("app-role"), eq("/"), anyString(), any(), eq(3600), eq(Map.of())))
                .thenThrow(new AwsException("EntityAlreadyExists", "exists", 409));
        when(iam.getRole("app-role")).thenReturn(existing);
        doThrow(new AwsException("MalformedPolicyDocument", "bad policy", 400))
                .when(iam).putRolePolicy(eq("app-role"), eq("second"), anyString());

        StackResource r = resource();
        r.setPhysicalId("app-role");
        r.getAttributes().put("RoleId", "AROAapp-role");

        assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {
                  "RoleName": "app-role",
                  "Policies": [
                    {"PolicyName": "first", "PolicyDocument": {"Version": "2012-10-17", "Statement": []}},
                    {"PolicyName": "second", "PolicyDocument": {"Version": "2012-10-17", "Statement": []}}
                  ]
                }
                """), ctx()));

        InOrder order = inOrder(iam);
        order.verify(iam).putRolePolicy("app-role", "first", EMPTY_TRUST);
        order.verify(iam).putRolePolicy("app-role", "first", priorDocument);
        verify(iam, never()).deleteRolePolicy(anyString(), anyString());
        verify(iam, never()).deleteRole(anyString());
    }

    @Test
    void inlinePolicyFailureFailsTheResourceInsteadOfDroppingThePolicy() {
        stubCreate("app-role");
        doThrow(new AwsException("MalformedPolicyDocument", "bad policy", 400))
                .when(iam).putRolePolicy(eq("app-role"), eq("broken"), anyString());

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(resource(), props("""
                {
                  "RoleName": "app-role",
                  "Policies": [{"PolicyName": "broken",
                                "PolicyDocument": {"Version": "2012-10-17", "Statement": []}}]
                }
                """), ctx()));

        assertEquals("MalformedPolicyDocument", failure.getErrorCode());
    }

    @Test
    void deleteRemovesAttachedAndInlinePoliciesBeforeTheRole() {
        IamRole role = new IamRole("AROAapp-role", "app-role", "/",
                "arn:aws:iam::" + ACCOUNT_ID + ":role/app-role", EMPTY_TRUST);
        role.getAttachedPolicyArns().add("arn:aws:iam::aws:policy/ReadOnlyAccess");
        role.getInlinePolicies().put("bucket-read", EMPTY_TRUST);
        when(iam.getRole("app-role")).thenReturn(role);

        provisioner.delete("AWS::IAM::Role", "app-role", "us-east-1");

        InOrder order = inOrder(iam);
        order.verify(iam).detachRolePolicy("app-role", "arn:aws:iam::aws:policy/ReadOnlyAccess");
        order.verify(iam).deleteRolePolicy("app-role", "bucket-read");
        order.verify(iam).deleteRole("app-role");
    }

    @Test
    void deleteTreatsAnAlreadyMissingRoleAsDeleted() {
        when(iam.getRole("gone-role")).thenThrow(new AwsException("NoSuchEntity", "gone", 404));

        provisioner.delete("AWS::IAM::Role", "gone-role", "us-east-1");

        verify(iam, never()).deleteRole("gone-role");
    }

    @Test
    void deletePropagatesUnexpectedLookupFailures() {
        when(iam.getRole("denied-role")).thenThrow(new AwsException("AccessDenied", "denied", 403));

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.delete("AWS::IAM::Role", "denied-role", "us-east-1"));

        assertEquals("AccessDenied", failure.getErrorCode());
        verify(iam, never()).deleteRole("denied-role");
    }
}
