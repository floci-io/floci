package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.AccessKey;
import io.github.hectorvent.floci.services.iam.model.IamUser;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IamUserCfnProvisionerTest {

    private static final String ACCOUNT_ID = "000000000000";

    private final IamService iam = mock(IamService.class);
    private final IamUserCfnProvisioner provisioner = new IamUserCfnProvisioner(iam);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);

        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });

        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));

        // resolveStringList delegates to the real engine method; for the literal arrays these
        // tests use it just walks the array and calls resolve(...) per element, stubbed above.
        when(engine.resolveStringList(any())).thenCallRealMethod();

        when(engine.resolveJsonAttribute(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node != null && node.isTextual() ? node.asText() : node.toString();
        });

        return new ProvisionContext(engine, "us-east-1", ACCOUNT_ID, "test-stack");
    }

    private ProvisionContext updateCtx(String priorPhysicalId) {
        ProvisionContext base = ctx();
        return new ProvisionContext(base.engine(), base.region(), base.accountId(), base.stackName(), priorPhysicalId);
    }

    private StackResource resource() {
        StackResource r = new StackResource();
        r.setLogicalId("AppUser");
        r.setResourceType("AWS::IAM::User");
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

    private IamUser stubCreate(String userName, String path) {
        IamUser user = new IamUser("AIDA" + userName, userName, path,
                "arn:aws:iam::" + ACCOUNT_ID + ":user" + path + userName);
        when(iam.createUser(eq(userName), eq(path))).thenReturn(user);
        return user;
    }

    @Test
    void declaresIamUserResourceType() {
        assertEquals(java.util.Set.of("AWS::IAM::User"), provisioner.resourceTypes());
    }

    @Test
    void provisionCreatesUserWithExplicitUserNameAndDefaultPath() {
        stubCreate("custom-user", "/");
        StackResource r = resource();

        provisioner.provision(r, props("""
                {
                  "UserName": "custom-user"
                }
                """), ctx());

        assertEquals("custom-user", r.getPhysicalId());
        assertEquals("arn:aws:iam::" + ACCOUNT_ID + ":user/custom-user", r.getAttributes().get("Arn"));
        verify(iam).createUser("custom-user", "/");
    }

    @Test
    void provisionCreatesUserWithCustomPath() {
        stubCreate("custom-user", "/engineering/");
        StackResource r = resource();

        provisioner.provision(r, props("""
                {
                  "UserName": "custom-user",
                  "Path": "/engineering/"
                }
                """), ctx());

        assertEquals("custom-user", r.getPhysicalId());
        assertEquals("arn:aws:iam::" + ACCOUNT_ID + ":user/engineering/custom-user", r.getAttributes().get("Arn"));
        verify(iam).createUser("custom-user", "/engineering/");
    }

    @Test
    void provisionGeneratesPhysicalNameWhenUserNameIsOmitted() {
        when(iam.createUser(any(), eq("/"))).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            return new IamUser("AIDA" + name, name, "/", "arn:aws:iam::" + ACCOUNT_ID + ":user/" + name);
        });
        StackResource r = resource();

        provisioner.provision(r, mapper.createObjectNode(), ctx());

        assertNotNull(r.getPhysicalId());
        assertTrue(r.getPhysicalId().contains("test-stack"));
        assertEquals("arn:aws:iam::" + ACCOUNT_ID + ":user/" + r.getPhysicalId(), r.getAttributes().get("Arn"));
    }

    @Test
    void provisionAttachesManagedPoliciesAndInlinePoliciesAndGroups() {
        IamUser user = stubCreate("app-user", "/");
        StackResource r = resource();

        provisioner.provision(r, props("""
                {
                  "UserName": "app-user",
                  "Groups": ["developers", "qa"],
                  "ManagedPolicyArns": [
                    "arn:aws:iam::aws:policy/ReadOnlyAccess",
                    "arn:aws:iam::aws:policy/PowerUserAccess"
                  ],
                  "Policies": [
                    {
                      "PolicyName": "s3-access",
                      "PolicyDocument": {"Version": "2012-10-17", "Statement": []}
                    }
                  ]
                }
                """), ctx());

        verify(iam).addUserToGroup("developers", "app-user");
        verify(iam).addUserToGroup("qa", "app-user");
        verify(iam).attachUserPolicy("app-user", "arn:aws:iam::aws:policy/ReadOnlyAccess");
        verify(iam).attachUserPolicy("app-user", "arn:aws:iam::aws:policy/PowerUserAccess");
        verify(iam).putUserPolicy(eq("app-user"), eq("s3-access"), any());
    }

    @Test
    void updatingUserNameRequiresReplacement() {
        StackResource r = resource();
        r.setPhysicalId("old-user");

        AwsException failure = assertThrows(AwsException.class, () ->
                provisioner.provision(r, props("""
                        {
                          "UserName": "new-user"
                        }
                        """), updateCtx("old-user")));

        assertEquals("ValidationError", failure.getErrorCode());
        assertTrue(failure.getMessage().contains("resource replacement"));
        verify(iam, never()).createUser(any(), any());
    }

    @Test
    void deleteDetachesPoliciesRemovesGroupsAndDeletesUser() {
        IamUser user = new IamUser("AIDAuser", "my-user", "/", "arn:aws:iam::" + ACCOUNT_ID + ":user/my-user");
        user.getAttachedPolicyArns().add("arn:aws:iam::aws:policy/ReadOnlyAccess");
        user.getInlinePolicies().put("inline-1", "{}");
        user.getGroupNames().add("dev-group");
        when(iam.getUser("my-user")).thenReturn(user);

        AccessKey key = new AccessKey("AKIA123", "secret", "my-user");
        when(iam.listAccessKeys("my-user")).thenReturn(List.of(key));

        provisioner.delete("AWS::IAM::User", "my-user", "us-east-1");

        verify(iam).detachUserPolicy("my-user", "arn:aws:iam::aws:policy/ReadOnlyAccess");
        verify(iam).deleteUserPolicy("my-user", "inline-1");
        verify(iam).removeUserFromGroup("dev-group", "my-user");
        verify(iam).deleteAccessKey("my-user", "AKIA123");
        verify(iam).deleteUser("my-user");
    }

    @Test
    void deleteToleratesAlreadyDeletedUser() {
        when(iam.getUser("already-gone"))
                .thenThrow(new AwsException("NoSuchEntity", "User does not exist", 404));

        provisioner.delete("AWS::IAM::User", "already-gone", "us-east-1");

        verify(iam, never()).deleteUser(any());
    }

    @Test
    void updateReconcilesRemovedGroupsManagedPoliciesAndInlinePolicies() {
        IamUser existing = new IamUser("AIDAuser", "my-user", "/", "arn:aws:iam::" + ACCOUNT_ID + ":user/my-user");
        existing.getGroupNames().add("keep-group");
        existing.getGroupNames().add("drop-group");
        existing.getAttachedPolicyArns().add("arn:aws:iam::aws:policy/Keep");
        existing.getAttachedPolicyArns().add("arn:aws:iam::aws:policy/Drop");
        existing.getInlinePolicies().put("keep-inline", "{}");
        existing.getInlinePolicies().put("drop-inline", "{}");

        when(iam.createUser(eq("my-user"), eq("/"))).thenThrow(new AwsException("EntityAlreadyExists", "exists", 409));
        when(iam.getUser("my-user")).thenReturn(existing);

        StackResource r = resource();
        r.setPhysicalId("my-user");
        r.getAttributes().put("__FlociUserId", "AIDAuser");
        r.getAttributes().put("__FlociGroups", "keep-group\ndrop-group");
        r.getAttributes().put("__FlociManagedPolicyArns", "arn:aws:iam::aws:policy/Keep\narn:aws:iam::aws:policy/Drop");
        r.getAttributes().put("__FlociInlinePolicyNames", "keep-inline\ndrop-inline");

        provisioner.provision(r, props("""
                {
                  "UserName": "my-user",
                  "Groups": ["keep-group"],
                  "ManagedPolicyArns": ["arn:aws:iam::aws:policy/Keep"],
                  "Policies": [
                    {
                      "PolicyName": "keep-inline",
                      "PolicyDocument": {"Version": "2012-10-17", "Statement": []}
                    }
                  ]
                }
                """), updateCtx("my-user"));

        verify(iam).removeUserFromGroup("drop-group", "my-user");
        verify(iam).detachUserPolicy("my-user", "arn:aws:iam::aws:policy/Drop");
        verify(iam).deleteUserPolicy("my-user", "drop-inline");
        verify(iam, never()).removeUserFromGroup("keep-group", "my-user");
        verify(iam, never()).detachUserPolicy("my-user", "arn:aws:iam::aws:policy/Keep");
        verify(iam, never()).deleteUserPolicy("my-user", "keep-inline");
    }

    @Test
    void updateReconcilesPathChange() {
        IamUser existing = new IamUser("AIDAuser", "my-user", "/", "arn:aws:iam::" + ACCOUNT_ID + ":user/my-user");
        when(iam.createUser(eq("my-user"), eq("/new-path/"))).thenThrow(new AwsException("EntityAlreadyExists", "exists", 409));
        when(iam.getUser("my-user")).thenReturn(existing);

        StackResource r = resource();
        r.setPhysicalId("my-user");
        r.getAttributes().put("__FlociUserId", "AIDAuser");

        provisioner.provision(r, props("""
                {
                  "UserName": "my-user",
                  "Path": "/new-path/"
                }
                """), updateCtx("my-user"));

        verify(iam).updateUser("my-user", null, "/new-path/", "AIDAuser");
    }

    @Test
    void updateRefusesToAdoptReplacementUserWithDifferentUserId() {
        IamUser replacement = new IamUser("AIDAnew-user", "my-user", "/", "arn:aws:iam::" + ACCOUNT_ID + ":user/my-user");
        when(iam.createUser(eq("my-user"), eq("/"))).thenThrow(new AwsException("EntityAlreadyExists", "exists", 409));
        when(iam.getUser("my-user")).thenReturn(replacement);

        StackResource r = resource();
        r.setPhysicalId("my-user");
        r.getAttributes().put("__FlociUserId", "AIDAold-user");

        AwsException failure = assertThrows(AwsException.class, () ->
                provisioner.provision(r, props("""
                        {
                          "UserName": "my-user"
                        }
                        """), updateCtx("my-user")));

        assertEquals("EntityAlreadyExists", failure.getErrorCode());
        verify(iam, never()).addUserToGroup(any(), any());
        verify(iam, never()).attachUserPolicy(any(), any());
    }

    @Test
    void failedUpdateRestoresPriorInlinePolicyAndDetachesNewManagedPolicy() {
        IamUser existing = new IamUser("AIDAuser", "my-user", "/", "arn:aws:iam::" + ACCOUNT_ID + ":user/my-user");
        String priorDocument = "{\"Version\":\"2012-10-17\",\"Statement\":[\"prior\"]}";
        existing.getInlinePolicies().put("first", priorDocument);
        when(iam.createUser(eq("my-user"), eq("/"))).thenThrow(new AwsException("EntityAlreadyExists", "exists", 409));
        when(iam.getUser("my-user")).thenReturn(existing);
        doThrow(new AwsException("MalformedPolicyDocument", "bad policy", 400))
                .when(iam).putUserPolicy(eq("my-user"), eq("second"), any());

        StackResource r = resource();
        r.setPhysicalId("my-user");
        r.getAttributes().put("__FlociUserId", "AIDAuser");

        assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {
                  "UserName": "my-user",
                  "ManagedPolicyArns": ["arn:aws:iam::aws:policy/NewPolicy"],
                  "Policies": [
                    {"PolicyName": "first", "PolicyDocument": {"Version": "2012-10-17", "Statement": []}},
                    {"PolicyName": "second", "PolicyDocument": {"Version": "2012-10-17", "Statement": []}}
                  ]
                }
                """), updateCtx("my-user")));

        verify(iam).attachUserPolicy("my-user", "arn:aws:iam::aws:policy/NewPolicy");
        verify(iam).detachUserPolicy("my-user", "arn:aws:iam::aws:policy/NewPolicy");
        verify(iam).putUserPolicy("my-user", "first", priorDocument);
        verify(iam, never()).deleteUser("my-user");
    }
}
