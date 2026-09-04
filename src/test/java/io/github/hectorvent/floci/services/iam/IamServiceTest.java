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
import java.util.Optional;

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

    private static IamService iamService(boolean seedDeployerPrincipal, StorageBackend<String, AccessKey> accessKeys) {
        return iamService(seedDeployerPrincipal, accessKeys, new InMemoryStorage<>());
    }

    private static IamService iamService(boolean seedDeployerPrincipal, StorageBackend<String, AccessKey> accessKeys,
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

    @Test
    void ecsTaskRoleSessionIsTokenBoundTransientAndFailClosedAfterRevocation() {
        IamRole role = iamService.createRole("TaskRole", "/", "{}", null, 0, null);
        String taskArn = "arn:aws:ecs:us-east-1:000000000000:task/default/task-a";
        String roleArn = role.getArn();
        String accessKey = "ASIAECS" + "A".repeat(13);
        String secret = "s".repeat(40);
        String token = "t".repeat(200);
        String path = "/v2/credentials/" + "P".repeat(48);

        assertFalse(iamService.isCredentialAccessKeyInUse(accessKey));
        iamService.registerEcsTaskRoleSession(taskArn, "000000000000", accessKey, secret, token,
                roleArn, Instant.now().plusSeconds(3600), path);

        assertTrue(iamService.isCredentialAccessKeyInUse(accessKey));
        assertTrue(iamService.findSecretKey(accessKey).isEmpty());
        assertEquals(Optional.of(token), iamService.findSessionToken(accessKey));
        assertEquals(Optional.of(secret), iamService.findSecretKey(accessKey, token));
        assertTrue(iamService.findSecretKey(accessKey, "wrong-token").isEmpty());
        assertTrue(iamService.findSecretKey(accessKey, null).isEmpty());
        assertTrue(iamService.findSecretKey(accessKey, "").isEmpty());
        assertEquals(Optional.of("000000000000"), iamService.resolveAccountId(accessKey));
        assertTrue(iamService.validateEcsTaskRoleSessionToken(accessKey, token));
        assertFalse(iamService.validateEcsTaskRoleSessionToken(accessKey, "wrong-token"));
        assertTrue(iamService.resolveCallerArn(accessKey).orElseThrow().contains("ecs-task-task-a"));
        assertThrows(IllegalArgumentException.class, () -> iamService.registerEcsTaskRoleSession(
                "arn:aws:ecs:us-east-1:000000000000:task/default/task-b",
                "000000000000", "ASIAECS" + "B".repeat(13), secret, token,
                "arn:aws:iam::000000000000:role/other/TaskRole",
                Instant.now().plusSeconds(3600), "/v2/credentials/" + "Q".repeat(48)));

        iamService.revokeEcsTaskRoleSession(taskArn, accessKey);

        assertTrue(iamService.isEcsTaskRoleCredential(accessKey));
        assertFalse(iamService.isCredentialAccessKeyInUse(accessKey));
        assertTrue(iamService.findSecretKey(accessKey).isEmpty());
        assertTrue(iamService.findSecretKey(accessKey, token).isEmpty());
        assertTrue(iamService.findSessionToken(accessKey).isEmpty());
        assertTrue(iamService.resolveAccountId(accessKey).isEmpty());
        assertFalse(iamService.validateEcsTaskRoleSessionToken(accessKey, token));
        assertNotNull(iamService.resolveCallerContext(accessKey));
        assertTrue(iamService.resolveCallerContext(accessKey).identityPolicies().isEmpty());
        assertTrue(iamService.resolveCallerArn(accessKey).isEmpty());
    }

    @Test
    void expiredEcsSessionCannotResolveSecretOrTokenEvenWithIssuedToken() {
        IamRole role = iamService.createRole("ExpiredTaskRole", "/", "{}", null, 0, null);
        String key = "ASIAECS" + "E".repeat(13);
        iamService.registerEcsTaskRoleSession(
                "arn:aws:ecs:us-east-1:000000000000:task/default/expired",
                "000000000000", key, "test-secret", "test-token", role.getArn(),
                Instant.now().plusSeconds(3600), "/v2/credentials/" + "E".repeat(48));
        iamService.resolveEcsTaskRoleSession(key).orElseThrow().setExpiration(Instant.EPOCH);

        assertTrue(iamService.findSessionToken(key).isEmpty());
        assertTrue(iamService.findSecretKey(key).isEmpty());
        assertTrue(iamService.findSecretKey(key, "test-token").isEmpty());
        assertFalse(iamService.validateEcsTaskRoleSessionToken(key, "test-token"));
        assertTrue(iamService.isEcsTaskRoleCredential(key));
    }

    @Test
    void ecsGenerationsOverlapUntilExpiryAndTaskRevocationClearsAll() {
        IamRole role = iamService.createRole("OverlapRole", "/", "{}", null, 0, null);
        String taskArn = "arn:aws:ecs:us-east-1:000000000000:task/default/overlap";
        String path = "/v2/credentials/" + "O".repeat(48);
        String firstKey = "ASIAECS" + "F".repeat(13);
        String secondKey = "ASIAECS" + "S".repeat(13);
        String firstToken = "first-token";
        String secondToken = "second-token";

        iamService.registerEcsTaskRoleSession(taskArn, "000000000000", firstKey,
                "first-secret", firstToken, role.getArn(), Instant.now().plusSeconds(3600), path);
        iamService.registerEcsTaskRoleSession(taskArn, "000000000000", secondKey,
                "second-secret", secondToken, role.getArn(), Instant.now().plusSeconds(7200), path);

        assertEquals(Optional.of(secondKey), iamService.resolveEcsTaskRoleSessionByPath(path)
                .map(SessionCredential::getAccessKeyId));
        assertEquals(Optional.of("first-secret"), iamService.findSecretKey(firstKey, firstToken));
        assertEquals(Optional.of("second-secret"), iamService.findSecretKey(secondKey, secondToken));
        assertTrue(iamService.findSecretKey(firstKey, null).isEmpty());
        assertTrue(iamService.findSecretKey(firstKey, "wrong-token").isEmpty());

        // Retiring an old generation must not interrupt the current generation or endpoint.
        iamService.revokeEcsTaskRoleSession(taskArn, firstKey);
        assertTrue(iamService.resolveEcsTaskRoleSession(firstKey).isEmpty());
        assertEquals(Optional.of(secondKey), iamService.resolveEcsTaskRoleSessionByPath(path)
                .map(SessionCredential::getAccessKeyId));

        // Explicitly revoking the current generation is task-scoped and clears every overlap.
        iamService.revokeEcsTaskRoleSession(taskArn, secondKey);
        assertTrue(iamService.resolveEcsTaskRoleSession(firstKey).isEmpty());
        assertTrue(iamService.resolveEcsTaskRoleSession(secondKey).isEmpty());
        assertTrue(iamService.resolveEcsTaskRoleSessionByPath(path).isEmpty());
        assertFalse(iamService.validateEcsTaskRoleSessionToken(secondKey, secondToken));
    }

    @Test
    void taskRevocationClearsEveryActiveGenerationWithoutCrossTaskImpact() {
        IamRole role = iamService.createRole("TaskRevokeOverlapRole", "/", "{}", null, 0, null);
        String firstTask = "arn:aws:ecs:us-east-1:000000000000:task/default/revoke-overlap-a";
        String secondTask = "arn:aws:ecs:us-east-1:000000000000:task/default/revoke-overlap-b";
        String firstPath = "/v2/credentials/" + "T".repeat(48);
        String secondPath = "/v2/credentials/" + "U".repeat(48);
        String firstKey = "ASIAECS" + "1".repeat(13);
        String latestKey = "ASIAECS" + "2".repeat(13);
        String otherKey = "ASIAECS" + "3".repeat(13);
        Instant expiry = Instant.now().plusSeconds(3600);

        iamService.registerEcsTaskRoleSession(firstTask, "000000000000", firstKey,
                "first-secret", "first-token", role.getArn(), expiry, firstPath);
        iamService.registerEcsTaskRoleSession(firstTask, "000000000000", latestKey,
                "latest-secret", "latest-token", role.getArn(), expiry, firstPath);
        iamService.registerEcsTaskRoleSession(secondTask, "000000000000", otherKey,
                "other-secret", "other-token", role.getArn(), expiry, secondPath);

        assertEquals(Optional.of("first-secret"), iamService.findSecretKey(firstKey, "first-token"));
        assertEquals(Optional.of("latest-secret"), iamService.findSecretKey(latestKey, "latest-token"));
        assertEquals(Optional.of(latestKey), iamService.resolveEcsTaskRoleSessionByPath(firstPath)
                .map(SessionCredential::getAccessKeyId));
        assertEquals(Optional.of(otherKey), iamService.resolveEcsTaskRoleSessionByPath(secondPath)
                .map(SessionCredential::getAccessKeyId));

        // A task-level stop must revoke both active generations, while another task remains live.
        iamService.revokeEcsTaskRoleSession(firstTask);
        assertTrue(iamService.resolveEcsTaskRoleSession(firstKey).isEmpty());
        assertTrue(iamService.resolveEcsTaskRoleSession(latestKey).isEmpty());
        assertTrue(iamService.resolveEcsTaskRoleSessionByPath(firstPath).isEmpty());
        assertTrue(iamService.findSecretKey(firstKey, "first-token").isEmpty());
        assertTrue(iamService.findSecretKey(latestKey, "latest-token").isEmpty());
        assertEquals(Optional.of(otherKey), iamService.resolveEcsTaskRoleSessionByPath(secondPath)
                .map(SessionCredential::getAccessKeyId));

        // Reissue two generations and verify revoking the latest access key has the same
        // task-scoped effect; the other task's endpoint must remain untouched.
        String reissuedKey = "ASIAECS" + "4".repeat(13);
        String reissuedLatestKey = "ASIAECS" + "5".repeat(13);
        iamService.registerEcsTaskRoleSession(firstTask, "000000000000", reissuedKey,
                "reissued-secret", "reissued-token", role.getArn(), expiry, firstPath);
        iamService.registerEcsTaskRoleSession(firstTask, "000000000000", reissuedLatestKey,
                "reissued-latest-secret", "reissued-latest-token", role.getArn(), expiry, firstPath);

        iamService.revokeEcsTaskRoleSession(firstTask, reissuedLatestKey);
        assertTrue(iamService.resolveEcsTaskRoleSession(reissuedKey).isEmpty());
        assertTrue(iamService.resolveEcsTaskRoleSession(reissuedLatestKey).isEmpty());
        assertTrue(iamService.resolveEcsTaskRoleSessionByPath(firstPath).isEmpty());
        assertEquals(Optional.of(otherKey), iamService.resolveEcsTaskRoleSessionByPath(secondPath)
                .map(SessionCredential::getAccessKeyId));
    }

    @Test
    void expiredGenerationIsPrunedWithoutRemovingLatestAndCanBeReissued() {
        IamRole role = iamService.createRole("PruneRole", "/", "{}", null, 0, null);
        String taskArn = "arn:aws:ecs:us-east-1:000000000000:task/default/prune";
        String path = "/v2/credentials/" + "R".repeat(48);
        String firstKey = "ASIAECS" + "P".repeat(13);
        String latestKey = "ASIAECS" + "L".repeat(13);

        iamService.registerEcsTaskRoleSession(taskArn, "000000000000", firstKey,
                "first-secret", "first-token", role.getArn(), Instant.now().plusSeconds(3600), path);
        iamService.registerEcsTaskRoleSession(taskArn, "000000000000", latestKey,
                "latest-secret", "latest-token", role.getArn(), Instant.now().plusSeconds(7200), path);
        iamService.resolveEcsTaskRoleSession(firstKey).orElseThrow().setExpiration(Instant.EPOCH);

        assertTrue(iamService.resolveEcsTaskRoleSession(firstKey).isEmpty());
        assertEquals(Optional.of(latestKey), iamService.resolveEcsTaskRoleSessionByPath(path)
                .map(SessionCredential::getAccessKeyId));

        // Expired generations do not cause an unbounded collision tombstone: the old ID can be
        // accepted again only as a newly registered, separately token-bound session.
        iamService.registerEcsTaskRoleSession(taskArn, "000000000000", firstKey,
                "replacement-secret", "replacement-token", role.getArn(),
                Instant.now().plusSeconds(3600), path);
        assertEquals(Optional.of("replacement-secret"),
                iamService.findSecretKey(firstKey, "replacement-token"));
        assertTrue(iamService.findSecretKey(firstKey, "first-token").isEmpty());
    }

    @Test
    void generationCollisionsFailBeforeReplacingOtherTaskOrCurrentGeneration() {
        IamRole role = iamService.createRole("CollisionRole", "/", "{}", null, 0, null);
        String firstTask = "arn:aws:ecs:us-east-1:000000000000:task/default/collision-a";
        String secondTask = "arn:aws:ecs:us-east-1:000000000000:task/default/collision-b";
        String firstPath = "/v2/credentials/" + "A".repeat(48);
        String secondPath = "/v2/credentials/" + "B".repeat(48);
        String firstKey = "ASIAECS" + "A".repeat(13);
        String secondKey = "ASIAECS" + "B".repeat(13);
        Instant expiry = Instant.now().plusSeconds(3600);

        iamService.registerEcsTaskRoleSession(firstTask, "000000000000", firstKey,
                "first-secret", "first-token", role.getArn(), expiry, firstPath);

        assertThrows(IllegalArgumentException.class, () -> iamService.registerEcsTaskRoleSession(
                secondTask, "000000000000", secondKey, "second-secret", "second-token",
                role.getArn(), expiry, firstPath));
        assertEquals(Optional.of(firstKey), iamService.resolveEcsTaskRoleSessionByPath(firstPath)
                .map(SessionCredential::getAccessKeyId));

        assertThrows(IllegalArgumentException.class, () -> iamService.registerEcsTaskRoleSession(
                firstTask, "000000000000", firstKey, "replacement-secret", "replacement-token",
                role.getArn(), expiry, secondPath));
        assertEquals(Optional.of("first-secret"), iamService.findSecretKey(firstKey, "first-token"));
        assertTrue(iamService.resolveEcsTaskRoleSessionByPath(secondPath).isEmpty());
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
    void accountSummaryUsesAwsDefaultQuotasAndTracksProviders() {
        Map<String, Long> empty = iamService.getAccountSummary();

        assertEquals(300L, empty.get("GroupsQuota"));
        assertEquals(1000L, empty.get("RolesQuota"));
        assertEquals(1500L, empty.get("PoliciesQuota"));
        assertEquals(1000L, empty.get("InstanceProfilesQuota"));
        assertEquals(10L, empty.get("AttachedPoliciesPerUserQuota"));
        assertEquals(10L, empty.get("AttachedPoliciesPerGroupQuota"));
        assertEquals(20L, empty.get("AttachedPoliciesPerRoleQuota"));
        assertEquals(6144L, empty.get("PolicySizeQuota"));
        assertEquals(0L, empty.get("Providers"));
        assertEquals(0L, empty.get("AccountAccessKeysPresent"));

        iamService.createUser("summary-user", "/");
        iamService.createAccessKey("summary-user");
        iamService.createOpenIDConnectProvider(
                "https://oidc.example.com/id/SUMMARY", List.of(), List.of("thumbprint"), Map.of());

        assertEquals(1L, iamService.getAccountSummary().get("Providers"));
        assertEquals(0L, iamService.getAccountSummary().get("AccountAccessKeysPresent"));
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
    void createServiceLinkedRoleForEc2AutoScalingUsesAwsCanonicalName() {
        IamRole role = iamService.createServiceLinkedRole(
                "autoscaling.amazonaws.com", null, "EC2 Auto Scaling SLR");

        assertEquals("AWSServiceRoleForAutoScaling", role.getRoleName());
        assertEquals(
                "arn:aws:iam::000000000000:role/aws-service-role/autoscaling.amazonaws.com/AWSServiceRoleForAutoScaling",
                role.getArn());
    }

    @Test
    void createServiceLinkedRoleForCloud9UsesAwsCanonicalName() {
        IamRole role = iamService.createServiceLinkedRole(
                "cloud9.amazonaws.com", null, "Cloud9 SLR");

        assertEquals("AWSServiceRoleForAWSCloud9", role.getRoleName());
        assertEquals(
                "arn:aws:iam::000000000000:role/aws-service-role/cloud9.amazonaws.com/AWSServiceRoleForAWSCloud9",
                role.getArn());
    }

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
    void createServiceLinkedRoleForAccessAnalyzer() {
        IamRole role = iamService.createServiceLinkedRole(
                "access-analyzer.amazonaws.com", null, "Access Analyzer SLR");

        assertEquals("AWSServiceRoleForAccessAnalyzer", role.getRoleName());
        assertEquals("/aws-service-role/access-analyzer.amazonaws.com/", role.getPath());
        assertTrue(role.getRoleId().startsWith("AROA"));
        assertEquals(
                "arn:aws:iam::000000000000:role/aws-service-role/access-analyzer.amazonaws.com/AWSServiceRoleForAccessAnalyzer",
                role.getArn());
        assertTrue(role.getAssumeRolePolicyDocument().contains("access-analyzer.amazonaws.com"),
                "trust policy should allow the service principal");
    }

    @Test
    void createServiceLinkedRoleWithCustomSuffix() {
        IamRole role = iamService.createServiceLinkedRole(
                "access-analyzer.amazonaws.com", "myapp", null);
        assertEquals("AWSServiceRoleForAccessAnalyzer_myapp", role.getRoleName());
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
    void createPolicyVersionAfterDeletionDoesNotReuseVersionIds() {
        IamPolicy policy = iamService.createPolicy("P", "/", null, "{}", null);
        String arn = policy.getArn();
        for (int i = 2; i <= 5; i++) {
            iamService.createPolicyVersion(arn, "{\"v\":" + i + "}", true);
        }
        // Prune the oldest non-default version (what CloudFormation does at the cap) and
        // create another. AWS version ids are monotonic — a deleted id is never reissued,
        // so the new version must be v6, not a rewrite of the surviving v5.
        iamService.deletePolicyVersion(arn, "v1");
        PolicyVersion next = iamService.createPolicyVersion(arn, "{\"v\":6}", true);
        assertEquals("v6", next.getVersionId());
        assertEquals("{\"v\":5}", iamService.getPolicyVersion(arn, "v5").getDocument());
    }

    @Test
    void createPolicyVersionAfterDeletingHighestSurvivingIdDoesNotReuseIt() {
        // Deleting the HIGHEST surviving version (not the oldest, as above) is the case that
        // breaks a "derive from the live keys" implementation: with v5 gone, the live max is v4,
        // so a naive next-id computation reissues v5 rather than advancing to v6.
        IamPolicy policy = iamService.createPolicy("P", "/", null, "{}", null);
        String arn = policy.getArn();
        for (int i = 2; i <= 5; i++) {
            iamService.createPolicyVersion(arn, "{\"v\":" + i + "}", false);
        }
        iamService.deletePolicyVersion(arn, "v5");
        PolicyVersion next = iamService.createPolicyVersion(arn, "{\"v\":6}", false);
        assertEquals("v6", next.getVersionId());
        assertEquals("{\"v\":4}", iamService.getPolicyVersion(arn, "v4").getDocument());
    }

    @Test
    void createPolicyVersionOnLegacyRehydratedPolicyDoesNotReuseADeletedTopVersion() {
        // Simulates a policy persisted by a Floci version that predates nextVersionNumber: the
        // field is absent from the old JSON, so it rehydrates at the class's fresh-policy default
        // (2) regardless of how many versions actually existed historically. Here we seed the
        // versions map directly (bypassing createPolicyVersion, which is the only path that would
        // have kept nextVersionNumber honest) to reproduce exactly that rehydrated shape: v1-v4
        // live, as if v5 had been created and deleted before this field ever existed on disk.
        IamPolicy policy = iamService.createPolicy("LegacyP", "/", null, "{\"v\":1}", null);
        String arn = policy.getArn();
        for (int i = 2; i <= 4; i++) {
            policy.getVersions().put("v" + i,
                    new io.github.hectorvent.floci.services.iam.model.PolicyVersion(
                            "v" + i, "{\"v\":" + i + "}", false));
        }
        policy.setNextVersionNumber(null); // Jackson leaves this null when absent from old JSON
        PolicyVersion next = iamService.createPolicyVersion(arn, "{\"v\":next}", false);
        assertNotEquals("v5", next.getVersionId(),
                "a legacy-rehydrated policy must not reissue the id of a version deleted before upgrade");
    }

    @Test
    void updateGroupMalformedNewGroupNameIsRejected() {
        iamService.createGroup("orig-group", "/");
        assertThrows(AwsException.class,
                () -> iamService.updateGroup("orig-group", "not a valid name!", null));
    }

    @Test
    void groupRenamePreservesPolicyResolutionForExistingMembers() {
        iamService.createGroup("g-rename", "/");
        iamService.putGroupPolicy("g-rename", "p",
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"}]}");
        iamService.createUser("member-user", "/");
        iamService.addUserToGroup("g-rename", "member-user");
        AccessKey key = iamService.createAccessKey("member-user");

        iamService.updateGroup("g-rename", "g-renamed", null);

        List<String> policies = iamService.resolveCallerPolicies(key.getAccessKeyId());
        assertNotNull(policies);
        assertTrue(policies.stream().anyMatch(doc -> doc.contains("\"Resource\":\"*\"")),
                "group policy should still resolve for the member after the group is renamed");
        assertTrue(iamService.listGroupsForUser("member-user").stream()
                        .anyMatch(g -> g.getGroupName().equals("g-renamed")),
                "ListGroupsForUser should reflect the new group name");
    }

    @Test
    void instanceProfileSetTagsRejectsNullAndDefensivelyCopies() {
        InstanceProfile profile = new InstanceProfile("AIPAX", "p", "/", "arn:aws:iam::111111111111:instance-profile/p");
        profile.setTags(null);
        assertNotNull(profile.getTags());
        assertTrue(profile.getTags().isEmpty());

        Map<String, String> source = new java.util.HashMap<>(Map.of("k", "v"));
        profile.setTags(source);
        source.put("k2", "v2");
        assertFalse(profile.getTags().containsKey("k2"),
                "setTags should defensively copy, not alias the caller's map");
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

    @Test
    void findSecretKeyRequiresTheTemporaryCredentialSessionToken() {
        String accessKeyId = "ASIASESSIONTOKENMATCH";
        iamService.registerSession(
                accessKeyId,
                "temporary-secret",
                "session-token",
                null,
                Instant.now().plusSeconds(3600),
                null
        );

        assertEquals("temporary-secret", iamService.findSecretKey(accessKeyId, "session-token").orElseThrow());
        assertTrue(iamService.findSecretKey(accessKeyId).isPresent());
        assertTrue(iamService.findSecretKey(accessKeyId, "wrong-token").isEmpty());
        assertTrue(iamService.findSecretKey(accessKeyId, null).isEmpty());
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
    void resolveAccountIdUsesLongTermAccessKeyOwnerAccount() {
        AccountAwareStorageBackend<AccessKey> accessKeys = new AccountAwareStorageBackend<>(
                new InMemoryStorage<>(), null, "000000000000");
        AccessKey accessKey = new AccessKey("AKIAIOSFODNN7EXAMPLE", "secret", "worker");
        accessKeys.putForAccount("111122223333", accessKey.getAccessKeyId(), accessKey);

        IamService service = iamService(false, accessKeys, new InMemoryStorage<>());

        assertEquals("111122223333", service.resolveAccountId(accessKey.getAccessKeyId()).orElseThrow());
    }

    @Test
    void resolveAccountIdIgnoresInactiveLongTermAccessKey() {
        AccountAwareStorageBackend<AccessKey> accessKeys = new AccountAwareStorageBackend<>(
                new InMemoryStorage<>(), null, "000000000000");
        AccessKey accessKey = new AccessKey("AKIAINACTIVEEXAMPLE", "secret", "worker");
        accessKey.setStatus("Inactive");
        accessKeys.putForAccount("111122223333", accessKey.getAccessKeyId(), accessKey);

        IamService service = iamService(false, accessKeys, new InMemoryStorage<>());

        assertTrue(service.resolveAccountId(accessKey.getAccessKeyId()).isEmpty());
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
    void temporarySessionUsesExplicitAccountNamespace() {
        String accountId = "222233334444";
        String accessKeyId = "ASIASIGNINEXPLICIT";
        AccountAwareStorageBackend<SessionCredential> sessions = new AccountAwareStorageBackend<>(
                new InMemoryStorage<>(), null, "000000000000");
        IamService service = iamService(false, new InMemoryStorage<>(), sessions);

        service.registerSessionForAccount(
                accountId, accessKeyId, "signin-secret",
                "arn:aws:iam::222233334444:root", Instant.now().plusSeconds(900), null);

        SessionCredential stored = sessions.getForAccount(accountId, accessKeyId).orElseThrow();
        assertEquals(accountId, stored.getOriginAccountId());
        assertEquals("signin-secret", stored.getSecretAccessKey());
        assertTrue(sessions.getForAccount("000000000000", accessKeyId).isEmpty());
        assertEquals(accountId, service.resolveAccountId(accessKeyId).orElseThrow());
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

        // LZA's OperationsStack attaches these to its AWS Backup service roles.
        IamPolicy backup = iamService.getPolicy(
                "arn:aws:iam::aws:policy/service-role/AWSBackupServiceRolePolicyForBackup");
        assertEquals("AWSBackupServiceRolePolicyForBackup", backup.getPolicyName());
        assertEquals("/service-role/", backup.getPath());

        IamPolicy restore = iamService.getPolicy(
                "arn:aws:iam::aws:policy/service-role/AWSBackupServiceRolePolicyForRestores");
        assertEquals("AWSBackupServiceRolePolicyForRestores", restore.getPolicyName());
        assertEquals("/service-role/", restore.getPath());

        // The S3 variants live at the root path in real AWS, not /service-role/.
        IamPolicy s3Backup = iamService.getPolicy(
                "arn:aws:iam::aws:policy/AWSBackupServiceRolePolicyForS3Backup");
        assertEquals("AWSBackupServiceRolePolicyForS3Backup", s3Backup.getPolicyName());
        assertEquals("/", s3Backup.getPath());

        IamPolicy s3Restore = iamService.getPolicy(
                "arn:aws:iam::aws:policy/AWSBackupServiceRolePolicyForS3Restore");
        assertEquals("AWSBackupServiceRolePolicyForS3Restore", s3Restore.getPolicyName());
        assertEquals("/", s3Restore.getPath());
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
