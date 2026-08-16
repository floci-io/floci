package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.iam.model.AccessKey;
import io.github.hectorvent.floci.services.iam.model.IamGroup;
import io.github.hectorvent.floci.services.iam.model.IamPolicy;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.IamUser;
import io.github.hectorvent.floci.services.iam.model.OpenIDConnectProvider;
import io.github.hectorvent.floci.services.iam.model.InstanceProfile;
import io.github.hectorvent.floci.services.iam.model.PolicyVersion;
import io.github.hectorvent.floci.services.iam.model.SessionCredential;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IamServiceTest {

    private IamService iamService;

    @BeforeEach
    void setUp() {
        iamService = iamService(false);
    }

    private static IamService iamService(boolean seedDeployerPrincipal) {
        return iamService(seedDeployerPrincipal, new InMemoryStorage<>());
    }

    private static IamService iamService(boolean seedDeployerPrincipal, InMemoryStorage<String, AccessKey> accessKeys) {
        return iamService(seedDeployerPrincipal, accessKeys, new InMemoryStorage<>());
    }

    private static IamService iamService(boolean seedDeployerPrincipal, InMemoryStorage<String, AccessKey> accessKeys,
                                         StorageBackend<String, SessionCredential> sessions) {
        return new IamService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                accessKeys,
                new InMemoryStorage<>(),
                sessions,
                new RegionResolver("us-east-1", "000000000000"),
                seedDeployerPrincipal
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
    void updateAssumeRolePolicyWithMatchingExpectedIdApplies() {
        IamRole role = iamService.createRole("LambdaExec", "/", "{}", null, 3600, null);
        String newDoc = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\"}]}";

        iamService.updateAssumeRolePolicy("LambdaExec", newDoc, role.getRoleId());

        assertEquals(newDoc, iamService.getRole("LambdaExec").getAssumeRolePolicyDocument());
    }

    @Test
    void updateAssumeRolePolicyWithNullExpectedIdSkipsCheck() {
        iamService.createRole("LambdaExec", "/", "{}", null, 3600, null);
        String newDoc = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\"}]}";

        iamService.updateAssumeRolePolicy("LambdaExec", newDoc, null);

        assertEquals(newDoc, iamService.getRole("LambdaExec").getAssumeRolePolicyDocument());
    }

    @Test
    void updateAssumeRolePolicyRejectsMismatchedExpectedId() {
        // github.com/floci-io/floci/issues/2084 (Greptile follow-up) — if the role named here was
        // deleted and recreated under the same name since the caller last verified its identity,
        // the recreated role has a different RoleId. The update must be refused rather than
        // silently applied to a role the caller never actually verified owning.
        IamRole role = iamService.createRole("LambdaExec", "/", "{}", null, 3600, null);
        String originalDoc = role.getAssumeRolePolicyDocument();
        String newDoc = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\"}]}";

        AwsException ex = assertThrows(AwsException.class,
                () -> iamService.updateAssumeRolePolicy("LambdaExec", newDoc, "AROAWRONGID"));
        assertEquals("EntityAlreadyExists", ex.getErrorCode());
        assertEquals(originalDoc, iamService.getRole("LambdaExec").getAssumeRolePolicyDocument(),
                "rejected update must not have changed the role's trust policy");
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

    @Test
    void getSessionTokenStyleSessionBypassesEnforcementResolution() {
        iamService.registerSession(
                "ASIAIOSFODNN7EXAMPLE",
                "temporary-secret",
                null,
                Instant.now().plusSeconds(3600),
                null
        );

        assertNull(iamService.resolveCallerContext("ASIAIOSFODNN7EXAMPLE"));
        assertNull(iamService.resolveCallerPolicies("ASIAIOSFODNN7EXAMPLE"));
    }

    // =========================================================================
    // Session account routing (SessionAccountLookup)
    // =========================================================================

    @Test
    void resolveAccountIdUsesRoleArnAccount() {
        iamService.registerSession(
                "ASIACROSSACCOUNT",
                "temp-secret",
                "arn:aws:iam::222233334444:role/CrossAccountAccess",
                Instant.now().plusSeconds(3600),
                null,
                "111122223333"
        );

        assertEquals("222233334444", iamService.resolveAccountId("ASIACROSSACCOUNT").orElseThrow());
    }

    @Test
    void resolveAccountIdFallsBackToOriginAccountWhenNoRoleArn() {
        iamService.registerSession(
                "ASIASESSIONTOKEN",
                "temp-secret",
                null,
                Instant.now().plusSeconds(3600),
                null,
                "111122223333"
        );

        assertEquals("111122223333", iamService.resolveAccountId("ASIASESSIONTOKEN").orElseThrow());
    }

    @Test
    void resolveAccountIdEmptyForUnknownKey() {
        assertTrue(iamService.resolveAccountId("ASIANOTREGISTERED").isEmpty());
        assertTrue(iamService.resolveAccountId(null).isEmpty());
    }

    @Test
    void resolveAccountIdDoesNotScanSessionsForLongTermAccessKey() {
        CountingAccountAwareSessionStorage sessions = new CountingAccountAwareSessionStorage();
        IamService service = iamService(false, new InMemoryStorage<>(), sessions);

        assertTrue(service.resolveAccountId("AKIAIOSFODNN7EXAMPLE").isEmpty());
        assertEquals(0, sessions.scanAllAccountsAsMapCalls);
    }

    private static final class CountingAccountAwareSessionStorage
            extends AccountAwareStorageBackend<SessionCredential> {

        private int scanAllAccountsAsMapCalls;

        private CountingAccountAwareSessionStorage() {
            super(new InMemoryStorage<>(), null, "000000000000");
        }

        @Override
        public Map<String, SessionCredential> scanAllAccountsAsMap() {
            scanAllAccountsAsMapCalls++;
            return super.scanAllAccountsAsMap();
        }
    }

    @Test
    void resolveAccountIdEmptyForExpiredSession() {
        iamService.registerSession(
                "ASIAEXPIRED",
                "temp-secret",
                "arn:aws:iam::222233334444:role/CrossAccountAccess",
                Instant.now().minusSeconds(60),
                null,
                "111122223333"
        );

        assertTrue(iamService.resolveAccountId("ASIAEXPIRED").isEmpty());
    }

    @Test
    void resolveCallerContextDeletesExpiredCrossAccountSessionFromOriginAccount() {
        String accessKeyId = "ASIAEXPIREDCROSSACCOUNT";
        AccountAwareStorageBackend<SessionCredential> sessions = new AccountAwareStorageBackend<>(
                new InMemoryStorage<>(), null, "222233334444");
        sessions.putForAccount("111122223333", accessKeyId, new SessionCredential(
                accessKeyId,
                "temp-secret",
                "arn:aws:iam::222233334444:role/CrossAccountAccess",
                Instant.now().minusSeconds(60),
                null,
                "111122223333"));
        IamService service = iamService(false, new InMemoryStorage<>(), sessions);

        assertNull(service.resolveCallerContext(accessKeyId));

        assertTrue(sessions.getForAccount("111122223333", accessKeyId).isEmpty());
    }

    @Test
    void lambdaExecutionRoleSessionUsesExplicitAccountAndHasNoExpiration() {
        String accountId = "222233334444";
        String accessKeyId = "ASIALAMBDAEXPLICIT";
        AccountAwareStorageBackend<SessionCredential> sessions = new AccountAwareStorageBackend<>(
                new InMemoryStorage<>(), null, "000000000000");
        IamService service = iamService(false, new InMemoryStorage<>(), sessions);

        service.registerLambdaExecutionRoleSession(
                accountId, accessKeyId, "lambda-secret",
                "arn:aws:iam::222233334444:role/LambdaRole");

        SessionCredential stored = sessions.getForAccount(accountId, accessKeyId).orElseThrow();
        assertEquals(accountId, stored.getOriginAccountId());
        assertNull(stored.getExpiration());
        assertTrue(stored.isLambdaExecutionRole());
        assertTrue(sessions.getForAccount("000000000000", accessKeyId).isEmpty());

        service.unregisterSession(accountId, accessKeyId);
        assertTrue(sessions.getForAccount(accountId, accessKeyId).isEmpty());
    }

    @Test
    void lambdaExecutionRoleSessionSweepPreservesStsSessions() {
        AccountAwareStorageBackend<SessionCredential> sessions = new AccountAwareStorageBackend<>(
                new InMemoryStorage<>(), null, "000000000000");
        IamService service = iamService(false, new InMemoryStorage<>(), sessions);
        service.registerLambdaExecutionRoleSession(
                "222233334444", "ASIALAMBDAORPHAN", "lambda-secret",
                "arn:aws:iam::222233334444:role/LambdaRole");
        service.registerSession(
                "ASIASTSSESSION", "sts-secret", "arn:aws:iam::000000000000:role/StsRole",
                Instant.now().plusSeconds(3600), null, "000000000000");

        assertEquals(1, service.sweepOrphanedLambdaExecutionRoleSessions());

        assertTrue(sessions.getForAccount("222233334444", "ASIALAMBDAORPHAN").isEmpty());
        assertTrue(sessions.getForAccount("000000000000", "ASIASTSSESSION").isPresent());
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

        // Referenced by the roles `cdk bootstrap` and the aws-bench scenario stacks create.
        // A missing entry surfaces as "Policy <arn> does not exist" on the consuming role,
        // which rolls the whole stack back.
        for (String name : new String[] {
                "AWSCloudFormationReadOnlyAccess", "AmazonAthenaFullAccess",
                "AmazonRedshiftFullAccess", "AmazonS3TablesReadOnlyAccess" }) {
            IamPolicy seeded = iamService.getPolicy("arn:aws:iam::aws:policy/" + name);
            assertEquals(name, seeded.getPolicyName());
            assertEquals("/", seeded.getPath());
        }

        IamPolicy lambda = iamService.getPolicy(
                "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole");
        assertEquals("AWSLambdaBasicExecutionRole", lambda.getPolicyName());
        assertEquals("/service-role/", lambda.getPath());

        IamPolicy ssm = iamService.getPolicy("arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore");
        assertEquals("AmazonSSMManagedInstanceCore", ssm.getPolicyName());
        assertEquals("/", ssm.getPath());

        IamPolicy cloudWatchAgent = iamService.getPolicy("arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy");
        assertEquals("CloudWatchAgentServerPolicy", cloudWatchAgent.getPolicyName());
        assertEquals("/", cloudWatchAgent.getPath());

        IamPolicy ecrReadOnly = iamService.getPolicy("arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly");
        assertEquals("AmazonEC2ContainerRegistryReadOnly", ecrReadOnly.getPolicyName());
        assertEquals("/", ecrReadOnly.getPath());

        IamPolicy rdsMonitoring = iamService.getPolicy(
                "arn:aws:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole");
        assertEquals("AmazonRDSEnhancedMonitoringRole", rdsMonitoring.getPolicyName());
        assertEquals("/service-role/", rdsMonitoring.getPath());

        IamPolicy bedrock = iamService.getPolicy("arn:aws:iam::aws:policy/AmazonBedrockFullAccess");
        assertEquals("AmazonBedrockFullAccess", bedrock.getPolicyName());
        assertEquals("/", bedrock.getPath());

        IamPolicy bedrockReadOnly = iamService.getPolicy("arn:aws:iam::aws:policy/AmazonBedrockReadOnly");
        assertEquals("AmazonBedrockReadOnly", bedrockReadOnly.getPolicyName());
        assertEquals("/", bedrockReadOnly.getPath());
    }

    @Test
    void attachAmazonBedrockFullAccessToRole() {
        // Regression: AmazonBedrockFullAccess was absent from the seed catalog, so
        // AttachRolePolicy (the Terraform path — CloudFormation attaches inline and
        // never calls it) 404'd with NoSuchEntity. Attaching it must now succeed.
        iamService.createRole("BedrockRole", "/", "{}", null, 0, null);
        iamService.attachRolePolicy("BedrockRole", "arn:aws:iam::aws:policy/AmazonBedrockFullAccess");

        List<String> attached = iamService.listAttachedRolePolicies("BedrockRole", null).stream()
                .map(IamPolicy::getArn).toList();
        assertTrue(attached.contains("arn:aws:iam::aws:policy/AmazonBedrockFullAccess"));
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
    void seedDefaultsDoesNotCreateDefaultDeployerPrincipalByDefault() {
        iamService.seedDefaults();

        assertThrows(AwsException.class, () -> iamService.getUser("floci-deployer"));
        assertTrue(iamService.resolveCallerArn("floci").isEmpty());
    }

    @Test
    void seedDefaultsCreatesConfiguredDefaultDeployerPrincipal() {
        iamService = iamService(true);
        iamService.seedDefaults();

        IamUser user = iamService.getUser("floci-deployer");
        assertEquals("arn:aws:iam::000000000000:user/floci-deployer", user.getArn());
        assertTrue(user.getAttachedPolicyArns().contains("arn:aws:iam::aws:policy/AdministratorAccess"));
        assertEquals(
                "arn:aws:iam::000000000000:user/floci-deployer",
                iamService.resolveCallerArn("floci").orElseThrow());
        assertNotNull(iamService.resolveCallerContext("floci"));
        assertNotNull(iamService.resolvePrincipalContext(user.getArn()));
    }

    @Test
    void simulatePrincipalPolicyRejectsUnsupportedPrincipalArnType() {
        AwsException ex = assertThrows(AwsException.class,
                () -> iamService.resolvePrincipalContext("arn:aws:iam::000000000000:group/admins"));

        assertEquals("InvalidInput", ex.getErrorCode());
        assertEquals("PolicySourceArn must identify an IAM user or role.", ex.getMessage());
    }

    @Test
    void seedDefaultsDoesNotReplaceExistingDeployerAccessKey() {
        InMemoryStorage<String, AccessKey> accessKeys = new InMemoryStorage<>();
        iamService = iamService(true, accessKeys);
        iamService.createUser("existing", "/");
        accessKeys.put("floci", new AccessKey("floci", "existing-secret", "existing"));

        iamService.seedDefaults();

        assertEquals(
                "arn:aws:iam::000000000000:user/existing",
                iamService.resolveCallerArn("floci").orElseThrow());
        assertEquals("existing-secret", iamService.findSecretKey("floci").orElseThrow());
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
    void attachSsmManagedInstanceCorePolicyToRole() {
        iamService.seedAwsManagedPolicies();
        iamService.createRole("Ec2Exec", "/", "{}", null, 0, null);

        String policyArn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore";
        iamService.attachRolePolicy("Ec2Exec", policyArn);

        List<IamPolicy> attached = iamService.listAttachedRolePolicies("Ec2Exec", null);
        assertEquals(1, attached.size());
        assertEquals(policyArn, attached.getFirst().getArn());
    }

    @Test
    void attachCloudWatchAgentServerPolicyToRole() {
        iamService.seedAwsManagedPolicies();
        iamService.createRole("Ec2Exec", "/", "{}", null, 0, null);

        String policyArn = "arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy";
        iamService.attachRolePolicy("Ec2Exec", policyArn);

        List<IamPolicy> attached = iamService.listAttachedRolePolicies("Ec2Exec", null);
        assertEquals(1, attached.size());
        assertEquals(policyArn, attached.getFirst().getArn());
    }

    @Test
    void attachEcrReadOnlyPolicyToRole() {
        iamService.seedAwsManagedPolicies();
        iamService.createRole("Ec2Exec", "/", "{}", null, 0, null);

        String policyArn = "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly";
        iamService.attachRolePolicy("Ec2Exec", policyArn);

        List<IamPolicy> attached = iamService.listAttachedRolePolicies("Ec2Exec", null);
        assertEquals(1, attached.size());
        assertEquals(policyArn, attached.getFirst().getArn());
    }

    @Test
    void attachRdsEnhancedMonitoringRolePolicyToRole() {
        iamService.seedAwsManagedPolicies();
        iamService.createRole("RdsMonitor", "/", "{}", null, 0, null);

        String policyArn = "arn:aws:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole";
        iamService.attachRolePolicy("RdsMonitor", policyArn);

        List<IamPolicy> attached = iamService.listAttachedRolePolicies("RdsMonitor", null);
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

    // =========================================================================
    // OIDC Identity Providers
    // =========================================================================

    private static final String OIDC_URL =
            "https://oidc.eks.eu-central-1.amazonaws.com/id/EXAMPLED539D4633E53DE1B716D3041E";
    private static final String OIDC_HOST =
            "oidc.eks.eu-central-1.amazonaws.com/id/EXAMPLED539D4633E53DE1B716D3041E";
    private static final String THUMBPRINT = "9e99a48a9960b14926bb7f3b02e22da2b0ab7280";

    @Test
    void createOpenIDConnectProviderStripsTheSchemeFromUrlAndArn() {
        OpenIDConnectProvider provider = iamService.createOpenIDConnectProvider(
                OIDC_URL, List.of("sts.amazonaws.com"), List.of(THUMBPRINT), Map.of());

        assertEquals(OIDC_HOST, provider.getUrl());
        assertEquals("arn:aws:iam::000000000000:oidc-provider/" + OIDC_HOST, provider.getArn());
        assertEquals(List.of("sts.amazonaws.com"), provider.getClientIdList());
        assertEquals(List.of(THUMBPRINT), provider.getThumbprintList());
        assertNotNull(provider.getCreateDate());
    }

    @Test
    void createOpenIDConnectProviderRequiresHttps() {
        AwsException error = assertThrows(AwsException.class, () -> iamService.createOpenIDConnectProvider(
                "http://oidc.example.com/id/1", List.of(), List.of(), Map.of()));
        assertEquals("ValidationError", error.getErrorCode());
    }

    @Test
    void createDuplicateOpenIDConnectProviderFails() {
        iamService.createOpenIDConnectProvider(OIDC_URL, List.of(), List.of(THUMBPRINT), Map.of());

        AwsException error = assertThrows(AwsException.class, () -> iamService.createOpenIDConnectProvider(
                OIDC_URL, List.of(), List.of(THUMBPRINT), Map.of()));
        assertEquals("EntityAlreadyExists", error.getErrorCode());
    }

    @Test
    void getOpenIDConnectProviderNotFoundThrows() {
        AwsException error = assertThrows(AwsException.class, () -> iamService.getOpenIDConnectProvider(
                "arn:aws:iam::000000000000:oidc-provider/missing.example.com"));
        assertEquals("NoSuchEntity", error.getErrorCode());
    }

    @Test
    void listOpenIDConnectProviders() {
        assertTrue(iamService.listOpenIDConnectProviders().isEmpty());
        OpenIDConnectProvider provider =
                iamService.createOpenIDConnectProvider(OIDC_URL, List.of(), List.of(THUMBPRINT), Map.of());

        List<OpenIDConnectProvider> all = iamService.listOpenIDConnectProviders();
        assertEquals(1, all.size());
        assertEquals(provider.getArn(), all.getFirst().getArn());
    }

    @Test
    void deleteOpenIDConnectProvider() {
        OpenIDConnectProvider provider =
                iamService.createOpenIDConnectProvider(OIDC_URL, List.of(), List.of(THUMBPRINT), Map.of());

        iamService.deleteOpenIDConnectProvider(provider.getArn());

        assertTrue(iamService.listOpenIDConnectProviders().isEmpty());
        assertThrows(AwsException.class, () -> iamService.getOpenIDConnectProvider(provider.getArn()));
    }

    @Test
    void addAndRemoveClientId() {
        OpenIDConnectProvider provider = iamService.createOpenIDConnectProvider(
                OIDC_URL, List.of("sts.amazonaws.com"), List.of(THUMBPRINT), Map.of());

        iamService.addClientIdToOpenIDConnectProvider(provider.getArn(), "extra.audience");
        assertEquals(List.of("sts.amazonaws.com", "extra.audience"),
                iamService.getOpenIDConnectProvider(provider.getArn()).getClientIdList());

        iamService.removeClientIdFromOpenIDConnectProvider(provider.getArn(), "extra.audience");
        assertEquals(List.of("sts.amazonaws.com"),
                iamService.getOpenIDConnectProvider(provider.getArn()).getClientIdList());
    }

    /**
     * Verified against a live AWS account: adding a client ID that is already present and removing
     * one that was never added both succeed and change nothing.
     */
    @Test
    void clientIdAddAndRemoveAreIdempotent() {
        OpenIDConnectProvider provider = iamService.createOpenIDConnectProvider(
                OIDC_URL, List.of("sts.amazonaws.com"), List.of(THUMBPRINT), Map.of());

        iamService.addClientIdToOpenIDConnectProvider(provider.getArn(), "sts.amazonaws.com");
        assertEquals(List.of("sts.amazonaws.com"),
                iamService.getOpenIDConnectProvider(provider.getArn()).getClientIdList());

        iamService.removeClientIdFromOpenIDConnectProvider(provider.getArn(), "never.added");
        assertEquals(List.of("sts.amazonaws.com"),
                iamService.getOpenIDConnectProvider(provider.getArn()).getClientIdList());
    }

    @Test
    void updateThumbprintReplacesTheList() {
        OpenIDConnectProvider provider =
                iamService.createOpenIDConnectProvider(OIDC_URL, List.of(), List.of(THUMBPRINT), Map.of());

        iamService.updateOpenIDConnectProviderThumbprint(provider.getArn(), List.of("aaaa", "bbbb"));

        assertEquals(List.of("aaaa", "bbbb"),
                iamService.getOpenIDConnectProvider(provider.getArn()).getThumbprintList());
    }

    @Test
    void openIdConnectProviderUrlIsCappedAt255Characters() {
        String longUrl = "https://oidc.example.com/id/" + "a".repeat(255);

        AwsException error = assertThrows(AwsException.class, () ->
                iamService.createOpenIDConnectProvider(longUrl, List.of(), List.of(THUMBPRINT), Map.of()));
        assertEquals("ValidationError", error.getErrorCode());
    }

    @Test
    void thumbprintListIsCappedAtFive() {
        // Five is accepted; six is not, and AWS reports InvalidInput rather than LimitExceeded.
        iamService.createOpenIDConnectProvider(OIDC_URL, List.of(),
                List.of("a", "b", "c", "d", "e"), Map.of());

        AwsException error = assertThrows(AwsException.class, () -> iamService.createOpenIDConnectProvider(
                "https://oidc.example.com/id/six", List.of(), List.of("a", "b", "c", "d", "e", "f"), Map.of()));
        assertEquals("InvalidInput", error.getErrorCode());
    }

    /**
     * A missing required parameter must fail as a ValidationError before any lookup. A null
     * ClientID would otherwise read as "not in the list" and report a no-op success, and a null
     * ARN as a missing provider.
     */
    @Test
    void oidcMutatorsRejectMissingRequiredParameters() {
        OpenIDConnectProvider provider = iamService.createOpenIDConnectProvider(
                OIDC_URL, List.of("sts.amazonaws.com"), List.of(THUMBPRINT), Map.of());

        assertEquals("ValidationError", assertThrows(AwsException.class, () ->
                iamService.removeClientIdFromOpenIDConnectProvider(provider.getArn(), null)).getErrorCode());
        assertEquals("ValidationError", assertThrows(AwsException.class, () ->
                iamService.addClientIdToOpenIDConnectProvider(provider.getArn(), "  ")).getErrorCode());
        assertEquals("ValidationError", assertThrows(AwsException.class, () ->
                iamService.deleteOpenIDConnectProvider(null)).getErrorCode());
        assertEquals("ValidationError", assertThrows(AwsException.class, () ->
                iamService.updateOpenIDConnectProviderThumbprint(null, List.of("aaaa"))).getErrorCode());

        // The provider and its client IDs are untouched by any of the rejected calls.
        assertEquals(List.of("sts.amazonaws.com"),
                iamService.getOpenIDConnectProvider(provider.getArn()).getClientIdList());
    }

    @Test
    void clientIdListIsCappedAtOneHundred() {
        List<String> tooMany = java.util.stream.IntStream.range(0, 101)
                .mapToObj(i -> "client-" + i).toList();

        AwsException error = assertThrows(AwsException.class, () ->
                iamService.createOpenIDConnectProvider(OIDC_URL, tooMany, List.of(THUMBPRINT), Map.of()));
        assertEquals("LimitExceeded", error.getErrorCode());
    }

    /**
     * Verified against a live AWS account: AWS does not normalize the URL, so a trailing slash or
     * a case difference yields a separate provider rather than a duplicate.
     */
    @Test
    void providerUrlsAreNotNormalized() {
        iamService.createOpenIDConnectProvider(OIDC_URL, List.of(), List.of(THUMBPRINT), Map.of());
        iamService.createOpenIDConnectProvider(OIDC_URL + "/", List.of(), List.of(THUMBPRINT), Map.of());
        iamService.createOpenIDConnectProvider(
                "https://OIDC.eks.eu-central-1.amazonaws.com/id/EXAMPLED539D4633E53DE1B716D3041E",
                List.of(), List.of(THUMBPRINT), Map.of());

        assertEquals(3, iamService.listOpenIDConnectProviders().size());
    }

    /**
     * Verified against a live AWS account: an empty tag map or key list is rejected as InvalidInput
     * rather than accepted as a no-op.
     */
    @Test
    void oidcTagMutatorsRejectEmptyCollections() {
        OpenIDConnectProvider provider = iamService.createOpenIDConnectProvider(
                OIDC_URL, List.of(), List.of(THUMBPRINT), Map.of("env", "prod"));

        assertEquals("InvalidInput", assertThrows(AwsException.class, () ->
                iamService.tagOpenIDConnectProvider(provider.getArn(), Map.of())).getErrorCode());
        assertEquals("InvalidInput", assertThrows(AwsException.class, () ->
                iamService.untagOpenIDConnectProvider(provider.getArn(), List.of())).getErrorCode());
        assertEquals("ValidationError", assertThrows(AwsException.class, () ->
                iamService.tagOpenIDConnectProvider(null, Map.of("k", "v"))).getErrorCode());

        // The rejected calls leave the existing tags alone.
        assertEquals(Map.of("env", "prod"), iamService.listOpenIDConnectProviderTags(provider.getArn()));
    }

    @Test
    void tagAndUntagOpenIDConnectProvider() {
        OpenIDConnectProvider provider = iamService.createOpenIDConnectProvider(
                OIDC_URL, List.of(), List.of(THUMBPRINT), Map.of("env", "prod"));

        assertEquals("prod", iamService.listOpenIDConnectProviderTags(provider.getArn()).get("env"));

        iamService.tagOpenIDConnectProvider(provider.getArn(), Map.of("team", "platform"));
        assertEquals(2, iamService.listOpenIDConnectProviderTags(provider.getArn()).size());

        iamService.untagOpenIDConnectProvider(provider.getArn(), List.of("env"));
        Map<String, String> remaining = iamService.listOpenIDConnectProviderTags(provider.getArn());
        assertEquals(1, remaining.size());
        assertEquals("platform", remaining.get("team"));
    }
    // =========================================================================
    // Account Aliases
    // =========================================================================

    @Test
    void createAndGetAccountAlias() {
        assertTrue(iamService.getAccountAlias().isEmpty());

        iamService.createAccountAlias("my-account");

        assertEquals("my-account", iamService.getAccountAlias().orElseThrow());
    }

    /**
     * Verified against a live AWS account: creating a free alias while another is set replaces it
     * rather than failing, which is how the one-alias-per-account rule is actually enforced.
     */
    @Test
    void createAccountAliasReplacesAnExistingOne() {
        iamService.createAccountAlias("my-account");

        iamService.createAccountAlias("other-account");

        assertEquals("other-account", iamService.getAccountAlias().orElseThrow());
    }

    /** Re-creating the alias the account already holds is the case AWS rejects. */
    @Test
    void createAccountAliasRejectsTheAliasAlreadyHeld() {
        iamService.createAccountAlias("my-account");

        AwsException ex = assertThrows(AwsException.class,
                () -> iamService.createAccountAlias("my-account"));
        assertEquals("EntityAlreadyExists", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("my-account"));
        assertEquals("my-account", iamService.getAccountAlias().orElseThrow());
    }

    @Test
    void deleteAccountAliasRejectsAMalformedValue() {
        iamService.createAccountAlias("my-account");

        AwsException ex = assertThrows(AwsException.class,
                () -> iamService.deleteAccountAlias("Bad_Alias"));
        assertEquals("ValidationError", ex.getErrorCode());
        assertEquals("my-account", iamService.getAccountAlias().orElseThrow());
    }

    @Test
    void deleteAccountAlias() {
        iamService.createAccountAlias("my-account");

        iamService.deleteAccountAlias("my-account");

        assertTrue(iamService.getAccountAlias().isEmpty());
    }

    @Test
    void deleteAccountAliasWithMismatchedNameFails() {
        iamService.createAccountAlias("my-account");

        AwsException ex = assertThrows(AwsException.class,
                () -> iamService.deleteAccountAlias("some-other-alias"));
        assertEquals("NoSuchEntity", ex.getErrorCode());
        assertEquals("my-account", iamService.getAccountAlias().orElseThrow());
    }

    @Test
    void deleteAccountAliasWhenNoneSetFails() {
        AwsException ex = assertThrows(AwsException.class,
                () -> iamService.deleteAccountAlias("my-account"));
        assertEquals("NoSuchEntity", ex.getErrorCode());
    }

    @Test
    void malformedAccountAliasesAreRejected() {
        for (String alias : List.of("ab", "-leading", "trailing-", "Upper", "under_score", "a".repeat(64))) {
            AwsException ex = assertThrows(AwsException.class,
                    () -> iamService.createAccountAlias(alias), "expected rejection for: " + alias);
            assertEquals("ValidationError", ex.getErrorCode(), "wrong code for: " + alias);
        }
        assertThrows(AwsException.class, () -> iamService.createAccountAlias(null));
    }

    /**
     * AWS documents the pattern as {@code ^[a-z0-9]([a-z0-9]|-(?!-)){1,61}[a-z0-9]$} — consecutive
     * dashes are rejected, which a plain character class would let through.
     */
    @Test
    void accountAliasRejectsConsecutiveDashes() {
        AwsException ex = assertThrows(AwsException.class,
                () -> iamService.createAccountAlias("my--account"));
        assertEquals("ValidationError", ex.getErrorCode());
        assertTrue(iamService.getAccountAlias().isEmpty());

        iamService.createAccountAlias("my-account");
        assertEquals("my-account", iamService.getAccountAlias().orElseThrow());
    }

    @Test
    void accountAliasBoundaryLengthsAreAccepted() {
        iamService.createAccountAlias("abc");
        iamService.deleteAccountAlias("abc");

        iamService.createAccountAlias("a".repeat(63));
        assertEquals("a".repeat(63), iamService.getAccountAlias().orElseThrow());
    }
}
