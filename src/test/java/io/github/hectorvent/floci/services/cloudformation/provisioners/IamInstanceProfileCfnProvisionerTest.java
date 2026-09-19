package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.InstanceProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
 * {@code AWS::IAM::InstanceProfile} in isolation: Ref is the name, Fn::GetAtt Arn is the arn, an
 * unnamed profile keeps its name across updates, Path and Roles are applied, a real create failure
 * propagates, and delete detaches roles before removing the profile.
 */
class IamInstanceProfileCfnProvisionerTest {

    private static final String TYPE = "AWS::IAM::InstanceProfile";

    private final IamService iam = mock(IamService.class);
    private final IamInstanceProfileCfnProvisioner provisioner = new IamInstanceProfileCfnProvisioner(iam);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void publishesNameAndArn() throws Exception {
        when(iam.createInstanceProfile("web", "/")).thenReturn(profile("web", "/"));

        StackResource r = resource();
        provisioner.provision(r, props("{\"InstanceProfileName\": \"web\"}"), ctx(null));

        assertEquals("web", r.getPhysicalId());
        assertEquals(Set.of("Arn"), r.getAttributes().keySet());
        assertEquals("arn:aws:iam::000000000000:instance-profile/web", r.getAttributes().get("Arn"));
        verify(iam, never()).addRoleToInstanceProfile(anyString(), anyString());
    }

    @Test
    void appliesTheDeclaredPathAndRoles() throws Exception {
        when(iam.createInstanceProfile("web", "/team/")).thenReturn(profile("web", "/team/"));

        StackResource r = resource();
        provisioner.provision(r, props("""
                {"InstanceProfileName": "web", "Path": "/team/", "Roles": ["app-role"]}
                """), ctx(null));

        verify(iam).createInstanceProfile("web", "/team/");
        verify(iam).addRoleToInstanceProfile("web", "app-role");
    }

    @Test
    void absentNameIsGeneratedFromStackAndLogicalId() throws Exception {
        when(iam.createInstanceProfile(anyString(), eq("/"))).thenReturn(profile("generated", "/"));

        StackResource r = resource();
        provisioner.provision(r, props("{}"), ctx(null));

        assertTrue(r.getPhysicalId().matches("my-stack-Profile-[0-9a-f]{12}"), r.getPhysicalId());
    }

    @Test
    void anUnnamedProfileKeepsItsNameOnUpdateInsteadOfOrphaning() throws Exception {
        String priorName = "my-stack-Profile-0123456789ab";
        when(iam.createInstanceProfile(priorName, "/"))
                .thenThrow(new AwsException("EntityAlreadyExists", "exists", 409));
        when(iam.getInstanceProfile(priorName)).thenReturn(profile(priorName, "/"));

        StackResource r = resource();
        r.setPhysicalId(priorName);
        provisioner.provision(r, props("{}"), ctx(priorName));

        assertEquals(priorName, r.getPhysicalId());
        assertEquals("arn:aws:iam::000000000000:instance-profile/" + priorName,
                r.getAttributes().get("Arn"));
    }

    @Test
    void changingTheNameOnUpdateIsRejected() throws Exception {
        StackResource r = resource();
        r.setPhysicalId("old");

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(r, props("{\"InstanceProfileName\": \"new\"}"), ctx("old")));
        assertEquals("ValidationError", failure.getErrorCode());
    }

    @Test
    void aCreateCollisionOutsideAnUpdatePropagates() throws Exception {
        when(iam.createInstanceProfile("web", "/"))
                .thenThrow(new AwsException("EntityAlreadyExists", "exists", 409));

        StackResource r = resource();
        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(r, props("{\"InstanceProfileName\": \"web\"}"), ctx(null)));
        assertEquals("EntityAlreadyExists", failure.getErrorCode());
    }

    @Test
    void deleteDetachesRolesThenRemovesTheProfile() {
        InstanceProfile withRole = profile("web", "/");
        withRole.getRoleNames().add("app-role");
        when(iam.getInstanceProfile("web")).thenReturn(withRole);

        provisioner.delete(TYPE, "web", "us-east-1");

        org.mockito.InOrder order = inOrder(iam);
        order.verify(iam).removeRoleFromInstanceProfile("web", "app-role");
        order.verify(iam).deleteInstanceProfile("web");
    }

    @Test
    void deleteToleratesAProfileAlreadyGone() {
        when(iam.getInstanceProfile("web")).thenThrow(new AwsException("NoSuchEntity", "gone", 404));

        assertDoesNotThrow(() -> provisioner.delete(TYPE, "web", "us-east-1"));
    }

    @Test
    void deletePropagatesADeleteConflict() {
        when(iam.getInstanceProfile("web")).thenReturn(profile("web", "/"));
        doThrow(new AwsException("DeleteConflict", "roles attached", 409))
                .when(iam).deleteInstanceProfile("web");

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.delete(TYPE, "web", "us-east-1"));
        assertEquals("DeleteConflict", failure.getErrorCode());
    }

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
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

    private static InstanceProfile profile(String name, String path) {
        InstanceProfile profile = new InstanceProfile();
        profile.setInstanceProfileName(name);
        profile.setPath(path);
        profile.setArn("arn:aws:iam::000000000000:instance-profile/" + name);
        profile.setRoleNames(new ArrayList<>());
        return profile;
    }

    private static StackResource resource() {
        StackResource r = new StackResource();
        r.setLogicalId("Profile");
        r.setResourceType(TYPE);
        r.setAttributes(new HashMap<>());
        return r;
    }
}
