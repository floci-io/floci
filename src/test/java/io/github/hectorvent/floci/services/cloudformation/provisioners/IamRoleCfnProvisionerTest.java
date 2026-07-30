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
    void inlinePolicyWithoutANameGetsAGeneratedOne() {
        stubCreate("app-role");

        provisioner.provision(resource(), props("""
                {
                  "RoleName": "app-role",
                  "Policies": [{"PolicyDocument": {"Version": "2012-10-17", "Statement": []}}]
                }
                """), ctx());

        ArgumentCaptor<String> policyName = ArgumentCaptor.forClass(String.class);
        verify(iam).putRolePolicy(eq("app-role"), policyName.capture(), eq(EMPTY_TRUST));
        assertTrue(policyName.getValue().startsWith("test-stack-AppRolePolicy-"),
                "unexpected generated policy name: " + policyName.getValue());
    }

    @Test
    void inlinePolicyWithoutADocumentIsSkipped() {
        stubCreate("app-role");

        provisioner.provision(resource(), props("""
                {"RoleName": "app-role", "Policies": [{"PolicyName": "no-document"}]}
                """), ctx());

        verify(iam, never()).putRolePolicy(anyString(), anyString(), anyString());
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
