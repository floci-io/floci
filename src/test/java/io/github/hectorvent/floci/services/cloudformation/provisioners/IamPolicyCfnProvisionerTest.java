package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.iam.IamService;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code AWS::IAM::Policy} (an inline policy) in isolation: it embeds the document into each
 * declared role, user and group, Ref is the policy name with no ARN, an update reuses the name and
 * detaches principals no longer listed, and delete removes the policy from every recorded target.
 */
class IamPolicyCfnProvisionerTest {

    private static final String TYPE = "AWS::IAM::Policy";

    private final IamService iam = mock(IamService.class);
    private final IamPolicyCfnProvisioner provisioner = new IamPolicyCfnProvisioner(iam);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void embedsThePolicyIntoEachPrincipalAndRefIsTheName() throws Exception {
        StackResource r = resource();
        provisioner.provision(r, props("""
                {"PolicyName": "app-policy",
                 "PolicyDocument": {"Version": "2012-10-17", "Statement": []},
                 "Roles": ["web-role"], "Users": ["ci-user"]}
                """), ctx(null));

        assertEquals("app-policy", r.getPhysicalId());
        verify(iam).putRolePolicy(eq("web-role"), eq("app-policy"), any());
        verify(iam).putUserPolicy(eq("ci-user"), eq("app-policy"), any());
        assertEquals("web-role", r.getAttributes().get("InlineRoleTargets"));
        assertEquals("ci-user", r.getAttributes().get("InlineUserTargets"));
        // Inline policies have no ARN.
        assertEquals(null, r.getAttributes().get("Arn"));
    }

    @Test
    void missingPolicyNameOnCreateIsRejected() throws Exception {
        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(resource(), props("""
                {"PolicyDocument": {"Version": "2012-10-17", "Statement": []},
                 "Roles": ["web-role"]}
                """), ctx(null)));
        assertEquals("ValidationError", e.getErrorCode());
    }

    @Test
    void aPolicyThatNamesNoPrincipalIsRejected() throws Exception {
        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(resource(), props("""
                {"PolicyName": "app-policy",
                 "PolicyDocument": {"Version": "2012-10-17", "Statement": []}}
                """), ctx(null)));
        assertEquals("ValidationError", e.getErrorCode());
    }

    @Test
    void updateReusesTheNameAndDetachesAPrincipalNoLongerListed() throws Exception {
        StackResource r = resource();
        r.setPhysicalId("app-policy");
        r.getAttributes().put("InlineRoleTargets", "web-role\nold-role");

        provisioner.provision(r, props("""
                {"PolicyDocument": {"Version": "2012-10-17", "Statement": []},
                 "Roles": ["web-role"]}
                """), ctx("app-policy"));

        assertEquals("app-policy", r.getPhysicalId());
        verify(iam).putRolePolicy(eq("web-role"), eq("app-policy"), any());
        // old-role dropped from the template, so its inline copy is deleted.
        verify(iam).deleteRolePolicy("old-role", "app-policy");
        verify(iam, never()).deleteRolePolicy("web-role", "app-policy");
    }

    @Test
    void deleteRemovesThePolicyFromEveryRecordedTarget() {
        StackResource r = resource();
        r.setPhysicalId("app-policy");
        r.getAttributes().put("InlineRoleTargets", "web-role");
        r.getAttributes().put("InlineUserTargets", "ci-user");
        r.getAttributes().put("InlineGroupTargets", "");

        provisioner.delete(r, "us-east-1");

        verify(iam).deleteRolePolicy("web-role", "app-policy");
        verify(iam).deleteUserPolicy("ci-user", "app-policy");
    }

    @Test
    void deleteToleratesAPrincipalAlreadyGone() {
        StackResource r = resource();
        r.setPhysicalId("app-policy");
        r.getAttributes().put("InlineRoleTargets", "web-role");
        doThrow(new AwsException("NoSuchEntity", "gone", 404))
                .when(iam).deleteRolePolicy("web-role", "app-policy");

        assertDoesNotThrow(() -> provisioner.delete(r, "us-east-1"));
    }

    @Test
    void deletePropagatesANonNotFoundFailure() {
        StackResource r = resource();
        r.setPhysicalId("app-policy");
        r.getAttributes().put("InlineRoleTargets", "web-role");
        doThrow(new AwsException("AccessDenied", "nope", 403))
                .when(iam).deleteRolePolicy("web-role", "app-policy");

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.delete(r, "us-east-1"));
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
