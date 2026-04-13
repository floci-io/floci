package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.iam.model.AccessKey;
import io.github.hectorvent.floci.services.iam.model.IamGroup;
import io.github.hectorvent.floci.services.iam.model.IamPolicy;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.IamUser;
import io.github.hectorvent.floci.services.iam.model.InstanceProfile;
import io.github.hectorvent.floci.services.iam.model.PolicyVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IamServiceTest {

    private IamService iamService;

    @BeforeEach
    void setUp() {
        iamService = new IamService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                "000000000000"
        );
    }

    // =========================================================================
    // Users
    // =========================================================================

    @Test
    void createAndGetUser() {
        IamUser user = iamService.createUser("alice", "/");

        assertEquals("alice", user.getUserName());
        assertEquals("/", user.getPath());
        assertNotNull(user.getUserId());
        assertTrue(user.getUserId().startsWith("AIDA"));
        assertEquals("arn:aws:iam::000000000000:user/alice", user.getArn());
        assertNotNull(user.getCreateDate());
    }

    @Test
    void createUserDuplicateFails() {
        iamService.createUser("alice", "/");
        assertThrows(AwsException.class, () -> iamService.createUser("alice", "/"));
    }

    @Test
    void getUserNotFoundThrows() {
        assertThrows(AwsException.class, () -> iamService.getUser("nonexistent"));
    }

    @Test
    void deleteUser() {
        iamService.createUser("alice", "/");
        iamService.deleteUser("alice");
        assertThrows(AwsException.class, () -> iamService.getUser("alice"));
    }

    @Test
    void deleteUserWithAttachedPolicyFails() {
        iamService.createUser("alice", "/");
        String policyArn = iamService.createPolicy("MyPolicy", "/", null,
                "{\"Version\":\"2012-10-17\"}", null).getArn();
        iamService.attachUserPolicy("alice", policyArn);
        assertThrows(AwsException.class, () -> iamService.deleteUser("alice"));
    }

    @Test
    void listUsers() {
        iamService.createUser("alice", "/");
        iamService.createUser("bob", "/team/");
        iamService.createUser("carol", "/admin/");

        List<IamUser> all = iamService.listUsers("/");
        assertEquals(3, all.size());

        List<IamUser> teamOnly = iamService.listUsers("/team/");
        assertEquals(1, teamOnly.size());
        assertEquals("bob", teamOnly.getFirst().getUserName());
    }

    @Test
    void updateUser() {
        iamService.createUser("alice", "/");
        iamService.updateUser("alice", "alice-renamed", "/new/");

        assertThrows(AwsException.class, () -> iamService.getUser("alice"));
        IamUser renamed = iamService.getUser("alice-renamed");
        assertEquals("/new/", renamed.getPath());
    }

    @Test
    void tagAndUntagUser() {
        iamService.createUser("alice", "/");
        iamService.tagUser("alice", Map.of("env", "prod", "team", "eng"));
        Map<String, String> tags = iamService.listUserTags("alice");
        assertEquals("prod", tags.get("env"));
        assertEquals("eng", tags.get("team"));

        iamService.untagUser("alice", List.of("team"));
        Map<String, String> tags2 = iamService.listUserTags("alice");
        assertFalse(tags2.containsKey("team"));
        assertTrue(tags2.containsKey("env"));
    }

    // =========================================================================
    // Groups
    // =========================================================================

    @Test
    void createAndGetGroup() {
        IamGroup group = iamService.createGroup("developers", "/");

        assertEquals("developers", group.getGroupName());
        assertEquals("/", group.getPath());
        assertTrue(group.getGroupId().startsWith("AGPA"));
        assertEquals("arn:aws:iam::000000000000:group/developers", group.getArn());
    }

    @Test
    void addAndRemoveUserFromGroup() {
        iamService.createUser("alice", "/");
        iamService.createGroup("developers", "/");

        iamService.addUserToGroup("developers", "alice");

        IamGroup group = iamService.getGroup("developers");
        assertTrue(group.getUserNames().contains("alice"));

        IamUser user = iamService.getUser("alice");
        assertTrue(user.getGroupNames().contains("developers"));

        iamService.removeUserFromGroup("developers", "alice");

        assertFalse(iamService.getGroup("developers").getUserNames().contains("alice"));
        assertFalse(iamService.getUser("alice").getGroupNames().contains("developers"));
    }

    @Test
    void listGroupsForUser() {
        iamService.createUser("alice", "/");
        iamService.createGroup("dev", "/");
        iamService.createGroup("ops", "/");
        iamService.addUserToGroup("dev", "alice");
        iamService.addUserToGroup("ops", "alice");

        List<IamGroup> groups = iamService.listGroupsForUser("alice");
        assertEquals(2, groups.size());
    }

    @Test
    void deleteGroupWithUsersFails() {
        iamService.createUser("alice", "/");
        iamService.createGroup("dev", "/");
        iamService.addUserToGroup("dev", "alice");
        assertThrows(AwsException.class, () -> iamService.deleteGroup("dev"));
    }

    // =========================================================================
    // Roles
    // =========================================================================

    @Test
    void createAndGetRole() {
        String trustPolicy = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";
        IamRole role = iamService.createRole("LambdaExec", "/", trustPolicy, "Lambda role", 3600, null);

        assertEquals("LambdaExec", role.getRoleName());
        assertEquals("/", role.getPath());
        assertTrue(role.getRoleId().startsWith("AROA"));
        assertEquals("arn:aws:iam::000000000000:role/LambdaExec", role.getArn());
        assertEquals(trustPolicy, role.getAssumeRolePolicyDocument());
        assertEquals("Lambda role", role.getDescription());
    }

    @Test
    void deleteRoleWithAttachedPolicyFails() {
        iamService.createRole("LambdaExec", "/", "{}", null, 0, null);
        String policyArn = iamService.createPolicy("P", "/", null, "{}", null).getArn();
        iamService.attachRolePolicy("LambdaExec", policyArn);
        assertThrows(AwsException.class, () -> iamService.deleteRole("LambdaExec"));
    }

    @Test
    void tagAndUntagRole() {
        iamService.createRole("MyRole", "/", "{}", null, 0, Map.of("env", "test"));
        iamService.tagRole("MyRole", Map.of("owner", "team-a"));
        Map<String, String> tags = iamService.listRoleTags("MyRole");
        assertEquals("test", tags.get("env"));
        assertEquals("team-a", tags.get("owner"));

        iamService.untagRole("MyRole", List.of("env"));
        assertFalse(iamService.listRoleTags("MyRole").containsKey("env"));
    }

    // =========================================================================
    // Managed Policies
    // =========================================================================

    @Test
    void createAndGetPolicy() {
        String doc = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";
        IamPolicy policy = iamService.createPolicy("ReadOnly", "/", "Read-only access", doc, null);

        assertEquals("ReadOnly", policy.getPolicyName());
        assertEquals("/", policy.getPath());
        assertTrue(policy.getPolicyId().startsWith("ANPA"));
        assertEquals("arn:aws:iam::000000000000:policy/ReadOnly", policy.getArn());
        assertEquals("v1", policy.getDefaultVersionId());
        assertEquals(doc, policy.getDefaultDocument());
    }

    @Test
    void createPolicyVersionAndSetDefault() {
        String doc1 = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\"}]}";
        String doc2 = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Deny\"}]}";
        IamPolicy policy = iamService.createPolicy("P", "/", null, doc1, null);
        String policyArn = policy.getArn();

        PolicyVersion v2 = iamService.createPolicyVersion(policyArn, doc2, false);
        assertEquals("v2", v2.getVersionId());
        assertFalse(v2.isDefaultVersion());

        iamService.setDefaultPolicyVersion(policyArn, "v2");
        IamPolicy updated = iamService.getPolicy(policyArn);
        assertEquals("v2", updated.getDefaultVersionId());
        assertEquals(doc2, updated.getDefaultDocument());
    }

    @Test
    void deletePolicyVersionDefaultFails() {
        IamPolicy policy = iamService.createPolicy("P", "/", null, "{}", null);
        assertThrows(AwsException.class,
                () -> iamService.deletePolicyVersion(policy.getArn(), "v1"));
    }

    @Test
    void policyVersionLimit() {
        IamPolicy policy = iamService.createPolicy("P", "/", null, "{}", null);
        String arn = policy.getArn();
        for (int i = 2; i <= 5; i++) {
            iamService.createPolicyVersion(arn, "{\"v\":" + i + "}", false);
        }
        assertThrows(AwsException.class,
                () -> iamService.createPolicyVersion(arn, "{\"v\":6}", false));
    }

    @Test
    void deletePolicyWithAttachmentsFails() {
        iamService.createUser("alice", "/");
        IamPolicy policy = iamService.createPolicy("P", "/", null, "{}", null);
        iamService.attachUserPolicy("alice", policy.getArn());
        assertThrows(AwsException.class, () -> iamService.deletePolicy(policy.getArn()));
    }

    @Test
    void tagAndUntagPolicy() {
        IamPolicy policy = iamService.createPolicy("P", "/", null, "{}", null);
        iamService.tagPolicy(policy.getArn(), Map.of("team", "security"));
        assertEquals("security", iamService.listPolicyTags(policy.getArn()).get("team"));
        iamService.untagPolicy(policy.getArn(), List.of("team"));
        assertFalse(iamService.listPolicyTags(policy.getArn()).containsKey("team"));
    }

    // =========================================================================
    // Policy Attachments
    // =========================================================================

    @Test
    void attachAndDetachUserPolicy() {
        iamService.createUser("alice", "/");
        IamPolicy policy = iamService.createPolicy("P", "/", null, "{}", null);
        iamService.attachUserPolicy("alice", policy.getArn());

        List<IamPolicy> attached = iamService.listAttachedUserPolicies("alice", null);
        assertEquals(1, attached.size());
        assertEquals(policy.getArn(), attached.getFirst().getArn());
        assertEquals(1, iamService.getPolicy(policy.getArn()).getAttachmentCount());

        iamService.detachUserPolicy("alice", policy.getArn());
        assertTrue(iamService.listAttachedUserPolicies("alice", null).isEmpty());
        assertEquals(0, iamService.getPolicy(policy.getArn()).getAttachmentCount());
    }

    @Test
    void attachAndDetachGroupPolicy() {
        iamService.createGroup("dev", "/");
        IamPolicy policy = iamService.createPolicy("P", "/", null, "{}", null);
        iamService.attachGroupPolicy("dev", policy.getArn());

        assertEquals(1, iamService.listAttachedGroupPolicies("dev", null).size());
        iamService.detachGroupPolicy("dev", policy.getArn());
        assertTrue(iamService.listAttachedGroupPolicies("dev", null).isEmpty());
    }

    @Test
    void attachAndDetachRolePolicy() {
        iamService.createRole("LambdaExec", "/", "{}", null, 0, null);
        IamPolicy policy = iamService.createPolicy("P", "/", null, "{}", null);
        iamService.attachRolePolicy("LambdaExec", policy.getArn());

        assertEquals(1, iamService.listAttachedRolePolicies("LambdaExec", null).size());
        iamService.detachRolePolicy("LambdaExec", policy.getArn());
        assertTrue(iamService.listAttachedRolePolicies("LambdaExec", null).isEmpty());
    }

    @Test
    void detachNonAttachedPolicyThrows() {
        iamService.createUser("alice", "/");
        IamPolicy policy = iamService.createPolicy("P", "/", null, "{}", null);
        assertThrows(AwsException.class, () -> iamService.detachUserPolicy("alice", policy.getArn()));
    }

    // =========================================================================
    // Inline Policies
    // =========================================================================

    @Test
    void userInlinePolicyCrud() {
        iamService.createUser("alice", "/");
        String doc = "{\"Version\":\"2012-10-17\"}";
        iamService.putUserPolicy("alice", "inline-1", doc);

        assertEquals(doc, iamService.getUserPolicy("alice", "inline-1"));
        assertEquals(List.of("inline-1"), iamService.listUserPolicies("alice"));

        iamService.deleteUserPolicy("alice", "inline-1");
        assertTrue(iamService.listUserPolicies("alice").isEmpty());
    }

    @Test
    void roleInlinePolicyCrud() {
        iamService.createRole("R", "/", "{}", null, 0, null);
        iamService.putRolePolicy("R", "inline-exec", "{\"Effect\":\"Allow\"}");
        assertEquals("{\"Effect\":\"Allow\"}", iamService.getRolePolicy("R", "inline-exec"));
        iamService.deleteRolePolicy("R", "inline-exec");
        assertThrows(AwsException.class, () -> iamService.getRolePolicy("R", "inline-exec"));
    }

    // =========================================================================
    // Access Keys
    // =========================================================================

    @Test
    void createAndListAccessKeys() {
        iamService.createUser("alice", "/");
        AccessKey key = iamService.createAccessKey("alice");

        assertNotNull(key.getAccessKeyId());
        assertTrue(key.getAccessKeyId().startsWith("AKIA"));
        assertNotNull(key.getSecretAccessKey());
        assertEquals("alice", key.getUserName());
        assertEquals("Active", key.getStatus());

        List<AccessKey> keys = iamService.listAccessKeys("alice");
        assertEquals(1, keys.size());
    }

    @Test
    void createThirdAccessKeyFails() {
        iamService.createUser("alice", "/");
        iamService.createAccessKey("alice");
        iamService.createAccessKey("alice");
        assertThrows(AwsException.class, () -> iamService.createAccessKey("alice"));
    }

    @Test
    void deleteAndUpdateAccessKey() {
        iamService.createUser("alice", "/");
        AccessKey key = iamService.createAccessKey("alice");

        iamService.updateAccessKey("alice", key.getAccessKeyId(), "Inactive");
        AccessKey updated = iamService.listAccessKeys("alice").getFirst();
        assertEquals("Inactive", updated.getStatus());

        iamService.deleteAccessKey("alice", key.getAccessKeyId());
        assertTrue(iamService.listAccessKeys("alice").isEmpty());
    }

    // =========================================================================
    // Instance Profiles
    // =========================================================================

    @Test
    void createAndGetInstanceProfile() {
        InstanceProfile profile = iamService.createInstanceProfile("MyProfile", "/");

        assertEquals("MyProfile", profile.getInstanceProfileName());
        assertTrue(profile.getInstanceProfileId().startsWith("AIPA"));
        assertEquals("arn:aws:iam::000000000000:instance-profile/MyProfile", profile.getArn());
    }

    @Test
    void addAndRemoveRoleFromInstanceProfile() {
        iamService.createRole("LambdaExec", "/", "{}", null, 0, null);
        iamService.createInstanceProfile("MyProfile", "/");

        iamService.addRoleToInstanceProfile("MyProfile", "LambdaExec");
        InstanceProfile profile = iamService.getInstanceProfile("MyProfile");
        assertTrue(profile.getRoleNames().contains("LambdaExec"));

        iamService.removeRoleFromInstanceProfile("MyProfile", "LambdaExec");
        assertFalse(iamService.getInstanceProfile("MyProfile").getRoleNames().contains("LambdaExec"));
    }

    @Test
    void instanceProfileMaxOneRole() {
        iamService.createRole("Role1", "/", "{}", null, 0, null);
        iamService.createRole("Role2", "/", "{}", null, 0, null);
        iamService.createInstanceProfile("Profile", "/");

        iamService.addRoleToInstanceProfile("Profile", "Role1");
        assertThrows(AwsException.class,
                () -> iamService.addRoleToInstanceProfile("Profile", "Role2"));
    }

    @Test
    void deleteInstanceProfileWithRoleFails() {
        iamService.createRole("R", "/", "{}", null, 0, null);
        iamService.createInstanceProfile("P", "/");
        iamService.addRoleToInstanceProfile("P", "R");
        assertThrows(AwsException.class, () -> iamService.deleteInstanceProfile("P"));
    }

    @Test
    void listInstanceProfilesForRole() {
        iamService.createRole("R", "/", "{}", null, 0, null);
        iamService.createInstanceProfile("P1", "/");
        iamService.createInstanceProfile("P2", "/");
        iamService.addRoleToInstanceProfile("P1", "R");

        List<InstanceProfile> profiles = iamService.listInstanceProfilesForRole("R");
        assertEquals(1, profiles.size());
        assertEquals("P1", profiles.getFirst().getInstanceProfileName());
    }

    // =========================================================================
    // AWS Managed Policy Seeding
    // =========================================================================

    @Test
    void seedAwsManagedPolicies() {
        iamService.seedAwsManagedPolicies();

        IamPolicy admin = iamService.getPolicy("arn:aws:iam::aws:policy/AdministratorAccess");
        assertEquals("AdministratorAccess", admin.getPolicyName());
        assertEquals("/", admin.getPath());
        assertTrue(admin.getPolicyId().startsWith("ANPA"));
        assertEquals("v1", admin.getDefaultVersionId());

        IamPolicy lambda = iamService.getPolicy(
                "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole");
        assertEquals("AWSLambdaBasicExecutionRole", lambda.getPolicyName());
        assertEquals("/service-role/", lambda.getPath());
    }

    @Test
    void seedIsIdempotent() {
        iamService.seedAwsManagedPolicies();
        String firstId = iamService.getPolicy("arn:aws:iam::aws:policy/AdministratorAccess").getPolicyId();

        iamService.seedAwsManagedPolicies();
        String secondId = iamService.getPolicy("arn:aws:iam::aws:policy/AdministratorAccess").getPolicyId();

        assertEquals(firstId, secondId);
    }

    @Test
    void attachManagedPolicyToRole() {
        iamService.seedAwsManagedPolicies();
        iamService.createRole("LambdaExec", "/", "{}", null, 0, null);

        String policyArn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole";
        iamService.attachRolePolicy("LambdaExec", policyArn);

        List<IamPolicy> attached = iamService.listAttachedRolePolicies("LambdaExec", null);
        assertEquals(1, attached.size());
        assertEquals(policyArn, attached.getFirst().getArn());
    }

    @Test
    void awsManagedPolicyDeleteRejected() {
        iamService.seedAwsManagedPolicies();
        String arn = "arn:aws:iam::aws:policy/AdministratorAccess";
        AwsException ex = assertThrows(AwsException.class, () -> iamService.deletePolicy(arn));
        assertEquals("AccessDenied", ex.getErrorCode());
    }

    @Test
    void awsManagedPolicyCreateVersionRejected() {
        iamService.seedAwsManagedPolicies();
        String arn = "arn:aws:iam::aws:policy/AdministratorAccess";
        assertThrows(AwsException.class, () -> iamService.createPolicyVersion(arn, "{}", false));
    }

    @Test
    void awsManagedPolicyTagRejected() {
        iamService.seedAwsManagedPolicies();
        String arn = "arn:aws:iam::aws:policy/AdministratorAccess";
        assertThrows(AwsException.class, () -> iamService.tagPolicy(arn, Map.of("k", "v")));
    }

    @Test
    void awsManagedPolicyUntagRejected() {
        iamService.seedAwsManagedPolicies();
        String arn = "arn:aws:iam::aws:policy/AdministratorAccess";
        assertThrows(AwsException.class, () -> iamService.untagPolicy(arn, List.of("k")));
    }

    @Test
    void listPoliciesInvalidScopeRejected() {
        AwsException ex = assertThrows(AwsException.class,
                () -> iamService.listPolicies("Invalid", "/"));
        assertEquals("ValidationError", ex.getErrorCode());
    }

    @Test
    void listPoliciesScopeFiltering() {
        iamService.seedAwsManagedPolicies();
        iamService.createPolicy("MyCustomPolicy", "/", null, "{}", null);

        List<IamPolicy> awsOnly = iamService.listPolicies("AWS", "/");
        assertTrue(awsOnly.stream().allMatch(p -> p.getArn().startsWith("arn:aws:iam::aws:policy")));
        assertFalse(awsOnly.isEmpty());

        List<IamPolicy> localOnly = iamService.listPolicies("Local", "/");
        assertTrue(localOnly.stream().noneMatch(p -> p.getArn().startsWith("arn:aws:iam::aws:policy")));
        assertEquals(1, localOnly.size());

        List<IamPolicy> all = iamService.listPolicies(null, "/");
        assertEquals(awsOnly.size() + localOnly.size(), all.size());
    }
}
