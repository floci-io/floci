package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamPolicy;
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
