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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
 * {@code AWS::IAM::InstanceProfile} in isolation: Ref is the name, Fn::GetAtt Arn is the arn, Path
 * and Roles are applied, a role swap reconciles in place, a createOnly change (name, or path on an
 * auto-named profile, or a dropped name) replaces the profile and marks the old one for cleanup, a
 * path change that keeps an explicit name collides with EntityAlreadyExists, a missing Roles is
 * rejected, and delete detaches roles before removing the profile.
 */
class IamInstanceProfileCfnProvisionerTest {

    private static final String TYPE = "AWS::IAM::InstanceProfile";

    private final IamService iam = mock(IamService.class);
    private final IamInstanceProfileCfnProvisioner provisioner = new IamInstanceProfileCfnProvisioner(iam);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void publishesNameArnAndAttachesTheRole() throws Exception {
        when(iam.createInstanceProfile("web", "/")).thenReturn(profile("web", "/"));

        StackResource r = resource();
        provisioner.provision(r, props("""
                {"InstanceProfileName": "web", "Roles": ["app-role"]}
                """), ctx(null));

        assertEquals("web", r.getPhysicalId());
        // Arn is the published attribute; the __Floci record is internal and filtered before publishing.
        assertEquals(Set.of("Arn", "__FlociIamInstanceProfileExplicitName"), r.getAttributes().keySet());
        assertEquals("arn:aws:iam::000000000000:instance-profile/web", r.getAttributes().get("Arn"));
        verify(iam).addRoleToInstanceProfile("web", "app-role");
    }

    @Test
    void appliesTheDeclaredPath() throws Exception {
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
        provisioner.provision(r, props("{\"Roles\": [\"app-role\"]}"), ctx(null));

        assertTrue(r.getPhysicalId().matches("my-stack-Profile-[0-9a-f]{12}"), r.getPhysicalId());
    }

    @Test
    void missingRolesIsRejected() {
        StackResource r = resource();
        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(r, props("{\"InstanceProfileName\": \"web\"}"), ctx(null)));
        assertEquals("ValidationError", failure.getErrorCode());
        verify(iam, never()).createInstanceProfile(anyString(), anyString());
    }

    @Test
    void changingTheRoleOnUpdateSwapsItInPlace() throws Exception {
        InstanceProfile existing = profile("web", "/");
        existing.getRoleNames().add("old-role");
        when(iam.createInstanceProfile("web", "/"))
                .thenThrow(new AwsException("EntityAlreadyExists", "exists", 409));
        when(iam.getInstanceProfile("web")).thenReturn(existing);

        StackResource r = resource();
        r.setPhysicalId("web");
        provisioner.provision(r, props("""
                {"InstanceProfileName": "web", "Roles": ["new-role"]}
                """), ctx("web"));

        org.mockito.InOrder order = inOrder(iam);
        order.verify(iam).removeRoleFromInstanceProfile("web", "old-role");
        order.verify(iam).addRoleToInstanceProfile("web", "new-role");
    }

    @Test
    void changingThePathOnANamedProfileCollidesWithEntityAlreadyExists() throws Exception {
        // A Path change needs replacement, but the replacement keeps the explicit name, so
        // CreateInstanceProfile collides and IAM answers EntityAlreadyExists, as CloudFormation does.
        when(iam.getInstanceProfile("web")).thenReturn(profile("web", "/"));
        when(iam.createInstanceProfile("web", "/team/"))
                .thenThrow(new AwsException("EntityAlreadyExists", "exists", 409));

        StackResource r = resource();
        r.setPhysicalId("web");
        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(r, props("""
                        {"InstanceProfileName": "web", "Path": "/team/", "Roles": ["app-role"]}
                        """), ctx("web")));
        assertEquals("EntityAlreadyExists", failure.getErrorCode());
    }

    @Test
    void droppingTheExplicitNameReplacesWithAGeneratedName() throws Exception {
        // Created under an explicit name ("web"), which the create records; removing
        // InstanceProfileName afterwards is a createOnly change that replaces it.
        when(iam.getInstanceProfile("web")).thenReturn(profile("web", "/"));
        when(iam.createInstanceProfile(anyString(), eq("/")))
                .thenAnswer(inv -> profile(inv.getArgument(0), "/"));
        StackResource r = resource();
        provisioner.provision(r, props("{\"InstanceProfileName\": \"web\", \"Roles\": [\"app-role\"]}"), ctx(null));
        assertEquals("web", r.getPhysicalId());
        assertEquals("true", r.getAttributes().get("__FlociIamInstanceProfileExplicitName"));

        provisioner.provision(r, props("{\"Roles\": [\"app-role\"]}"), ctx("web"));

        assertTrue(r.getPhysicalId().matches("my-stack-Profile-[0-9a-f]{12}"), r.getPhysicalId());
        assertEquals("false", r.getAttributes().get("__FlociIamInstanceProfileExplicitName"));
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals("web", provisioner.updateCleanupPhysicalId(r));
    }

    /**
     * The generated-name shape is not a reliable "was it explicit" signal: past 128 characters the
     * {@code <stack>-<logicalId>-} prefix is truncated and only the suffix survives, so an
     * auto-named profile in a long-named stack must still read as auto-named on a no-op update, or
     * every UpdateStack would replace it.
     */
    @Test
    void anAutoNamedProfileInALongNamedStackIsNotReplacedOnANoOpUpdate() throws Exception {
        String stackName = "a".repeat(120);
        when(iam.createInstanceProfile(anyString(), eq("/")))
                .thenAnswer(inv -> profile(inv.getArgument(0), "/"));
        StackResource r = resource();
        provisioner.provision(r, props("{\"Roles\": [\"app-role\"]}"), ctx(stackName, null));
        String generated = r.getPhysicalId();
        assertEquals(128, generated.length());
        assertTrue(generated.matches("a{115}-[0-9a-f]{12}"), generated);
        when(iam.getInstanceProfile(generated)).thenReturn(profile(generated, "/"));

        provisioner.provision(r, props("{\"Roles\": [\"app-role\"]}"), ctx(stackName, generated));

        assertEquals(generated, r.getPhysicalId());
        assertTrue(!provisioner.hasReplacementUpdate(r), "a no-op update must not replace the profile");
        verify(iam, never()).deleteInstanceProfile(anyString());
    }

    /** A prior profile with no create-time record (state from before the record) is kept, not churned. */
    @Test
    void anUnrecordedPriorProfileIsNotReplacedWhenTheTemplateNamesNone() throws Exception {
        when(iam.getInstanceProfile("web")).thenReturn(profile("web", "/"));
        when(iam.createInstanceProfile("web", "/")).thenReturn(profile("web", "/"));
        StackResource r = resource();
        r.setPhysicalId("web");

        provisioner.provision(r, props("{\"Roles\": [\"app-role\"]}"), ctx("web"));

        assertEquals("web", r.getPhysicalId());
        assertTrue(!provisioner.hasReplacementUpdate(r));
        verify(iam, never()).deleteInstanceProfile(anyString());
    }

    @Test
    void changingTheNameOnUpdateReplacesTheProfileAndMarksTheOldForCleanup() throws Exception {
        when(iam.getInstanceProfile("old")).thenReturn(profile("old", "/"));
        when(iam.createInstanceProfile("new", "/")).thenReturn(profile("new", "/"));

        StackResource r = resource();
        r.setPhysicalId("old");
        r.getAttributes().put("Arn", "arn:aws:iam::000000000000:instance-profile/old");
        provisioner.provision(r, props("""
                {"InstanceProfileName": "new", "Roles": ["app-role"]}
                """), ctx("old"));

        assertEquals("new", r.getPhysicalId());
        assertEquals("arn:aws:iam::000000000000:instance-profile/new", r.getAttributes().get("Arn"));
        verify(iam).createInstanceProfile("new", "/");
        verify(iam).addRoleToInstanceProfile("new", "app-role");
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals("old", provisioner.updateCleanupPhysicalId(r));
    }

    @Test
    void changingThePathOnAnAutoNamedProfileReplacesWithAFreshName() throws Exception {
        String priorName = "my-stack-Profile-0123456789ab";
        when(iam.getInstanceProfile(priorName)).thenReturn(profile(priorName, "/"));
        when(iam.createInstanceProfile(anyString(), eq("/team/")))
                .thenAnswer(inv -> profile(inv.getArgument(0), "/team/"));

        StackResource r = resource();
        r.setPhysicalId(priorName);
        provisioner.provision(r, props("{\"Path\": \"/team/\", \"Roles\": [\"app-role\"]}"), ctx(priorName));

        assertTrue(r.getPhysicalId().matches("my-stack-Profile-[0-9a-f]{12}"), r.getPhysicalId());
        assertNotEquals(priorName, r.getPhysicalId());
        verify(iam).createInstanceProfile(r.getPhysicalId(), "/team/");
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals(priorName, provisioner.updateCleanupPhysicalId(r));
    }

    @Test
    void completingAReplacingUpdateDeletesTheDisplacedProfile() throws Exception {
        when(iam.getInstanceProfile("old")).thenReturn(profile("old", "/"));
        when(iam.createInstanceProfile("new", "/")).thenReturn(profile("new", "/"));
        StackResource r = resource();
        r.setPhysicalId("old");
        provisioner.provision(r, props("""
                {"InstanceProfileName": "new", "Roles": ["app-role"]}
                """), ctx("old"));

        UpdateCleanupResult result = provisioner.completeUpdate(r);

        assertTrue(result.applicable() && result.complete());
        verify(iam).deleteInstanceProfile("old");
    }

    @Test
    void aCreateCollisionOutsideAnUpdatePropagates() throws Exception {
        when(iam.createInstanceProfile("web", "/"))
                .thenThrow(new AwsException("EntityAlreadyExists", "exists", 409));

        StackResource r = resource();
        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(r, props("""
                        {"InstanceProfileName": "web", "Roles": ["app-role"]}
                        """), ctx(null)));
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
        return ctx("my-stack", priorPhysicalId);
    }

    private ProvisionContext ctx(String stackName, String priorPhysicalId) {
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
        return new ProvisionContext(engine, "us-east-1", "000000000000", stackName, priorPhysicalId);
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
