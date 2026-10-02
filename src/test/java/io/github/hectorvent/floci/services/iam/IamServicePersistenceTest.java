package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Totp;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.iam.model.AccessKey;
import io.github.hectorvent.floci.services.iam.model.AccountPasswordPolicy;
import io.github.hectorvent.floci.services.iam.model.IamGroup;
import io.github.hectorvent.floci.services.iam.model.IamPolicy;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.IamUser;
import io.github.hectorvent.floci.services.iam.model.InstanceProfile;
import io.github.hectorvent.floci.services.iam.model.OpenIDConnectProvider;
import io.github.hectorvent.floci.services.iam.model.SessionCredential;
import io.github.hectorvent.floci.services.iam.model.VirtualMfaDevice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IAM state is persisted through StorageFactory, so it must survive a restart. This builds an
 * IamService over PersistentStorage in a temp dir, then builds a SECOND IamService over the SAME
 * files (simulating a process restart) and asserts the resources are still intact.
 */
class IamServicePersistenceTest {

    private static final String OIDC_URL =
            "https://oidc.eks.eu-central-1.amazonaws.com/id/PERSISTED0EXAMPLE";
    private static final String THUMBPRINT = "9e99a48a9960b14926bb7f3b02e22da2b0ab7280";

    @Test
    void openIdConnectProviderSurvivesRestart(@TempDir Path dir) {
        IamService first = newService(dir);
        OpenIDConnectProvider created = first.createOpenIDConnectProvider(
                OIDC_URL, List.of("sts.amazonaws.com"), List.of(THUMBPRINT), Map.of("env", "prod"));

        // A fresh service over the same persistent files = a restart with the same data dir.
        IamService restarted = newService(dir);

        OpenIDConnectProvider reloaded = restarted.getOpenIDConnectProvider(created.getArn());
        assertEquals(created.getArn(), reloaded.getArn());
        assertEquals("oidc.eks.eu-central-1.amazonaws.com/id/PERSISTED0EXAMPLE", reloaded.getUrl());
        assertEquals(List.of("sts.amazonaws.com"), reloaded.getClientIdList());
        assertEquals(List.of(THUMBPRINT), reloaded.getThumbprintList());
        assertEquals("prod", reloaded.getTags().get("env"));
        // createDate is an Instant, so a broken time round trip would surface here.
        assertNotNull(reloaded.getCreateDate());
        assertEquals(created.getCreateDate(), reloaded.getCreateDate());

        assertEquals(1, restarted.listOpenIDConnectProviders().size());
    }

    @Test
    void openIdConnectProviderMutationsSurviveRestart(@TempDir Path dir) {
        IamService first = newService(dir);
        OpenIDConnectProvider created = first.createOpenIDConnectProvider(
                OIDC_URL, List.of("sts.amazonaws.com"), List.of(THUMBPRINT), Map.of());
        first.addClientIdToOpenIDConnectProvider(created.getArn(), "extra.audience");
        first.updateOpenIDConnectProviderThumbprint(created.getArn(), List.of("aaaa", "bbbb"));

        IamService restarted = newService(dir);

        OpenIDConnectProvider reloaded = restarted.getOpenIDConnectProvider(created.getArn());
        assertEquals(List.of("sts.amazonaws.com", "extra.audience"), reloaded.getClientIdList());
        assertEquals(List.of("aaaa", "bbbb"), reloaded.getThumbprintList());
    }

    @Test
    void userAndRoleSurviveRestart(@TempDir Path dir) {
        IamService first = newService(dir);
        first.createUser("alice", "/");
        first.createRole("LambdaExec", "/", "{\"Version\":\"2012-10-17\",\"Statement\":[]}",
                "Lambda role", 3600, null);

        IamService restarted = newService(dir);

        assertEquals("alice", restarted.getUser("alice").getUserName());
        assertEquals("LambdaExec", restarted.getRole("LambdaExec").getRoleName());
    }

    @Test
    void legacySessionWithoutStoredTokenCannotAuthenticate(@TempDir Path dir) throws IOException {
        String accessKeyId = "ASIALEGACYPERSISTED";
        String secretAccessKey = "legacy-persisted-secret";
        Files.writeString(dir.resolve("iam-sessions.json"), """
                {
                  "%s": {
                    "accessKeyId": "%s",
                    "secretAccessKey": "%s",
                    "expiration": "%s"
                  }
                }
                """.formatted(
                accessKeyId, accessKeyId, secretAccessKey, Instant.now().plusSeconds(60)));

        IamService restarted = newService(dir);

        assertTrue(restarted.findSecretKey(accessKeyId, "legacy-session-token").isEmpty());
    }

    @Test
    void expiredTemporarySessionsAreRemovedAfterRestart(@TempDir Path dir) {
        Instant now = Instant.now();
        IamService first = newService(dir);
        first.registerSessionForAccount("000000000000", "ASIAEXPIREDPRESIGN", "expired-secret",
                "expired-token", null, now.minusSeconds(1), null);
        first.registerSessionForAccount("000000000000", "ASIAVALIDPRESIGN", "valid-secret",
                "valid-token", null, now.plusSeconds(3600), null);

        IamService restarted = newService(dir);
        assertEquals(1, restarted.sweepExpiredSessions(now));
        assertEquals(0, restarted.sweepExpiredSessions(now));
        assertEquals("valid-secret", restarted.findSecretKey("ASIAVALIDPRESIGN", "valid-token").orElseThrow());

        IamService subsequentRestart = newService(dir);
        assertEquals(0, subsequentRestart.sweepExpiredSessions(now));
    }

    @Test
    void expiredSessionsWithoutOriginAreRemovedFromTheirStoredAccounts(@TempDir Path dir) {
        Instant now = Instant.now();
        String foreignKey = "ASIAFOREIGNEXPIRED";
        String legacyKey = "ASIALEGACYEXPIRED";
        String validKey = "ASIAFOREIGNVALID";
        StorageBackend<String, SessionCredential> raw = load(dir, "iam-sessions.json",
                new TypeReference<Map<String, SessionCredential>>() {});
        raw.put("111122223333/" + foreignKey,
                new SessionCredential(foreignKey, "secret", "token", null, now.minusSeconds(1), null));
        raw.put(legacyKey,
                new SessionCredential(legacyKey, "secret", "token", null, now.minusSeconds(1), null));
        raw.put("111122223333/" + validKey,
                new SessionCredential(validKey, "secret", "token", null, now.plusSeconds(3600), null));

        StorageBackend<String, SessionCredential> reloaded = load(dir, "iam-sessions.json",
                new TypeReference<Map<String, SessionCredential>>() {});
        IamService restarted = newService(dir,
                new AccountAwareStorageBackend<>(reloaded, null, "000000000000"));
        assertEquals(2, restarted.sweepExpiredSessions(now));
        assertTrue(reloaded.get("111122223333/" + foreignKey).isEmpty());
        assertTrue(reloaded.get(legacyKey).isEmpty());
        assertTrue(reloaded.get("111122223333/" + validKey).isPresent());

        StorageBackend<String, SessionCredential> subsequentReload = load(dir, "iam-sessions.json",
                new TypeReference<Map<String, SessionCredential>>() {});
        IamService subsequentRestart = newService(dir,
                new AccountAwareStorageBackend<>(subsequentReload, null, "000000000000"));
        assertEquals(0, subsequentRestart.sweepExpiredSessions(now));
    }

    private IamService newService(Path dir) {
        return newService(dir, load(dir, "iam-sessions.json",
                new TypeReference<Map<String, SessionCredential>>() {}));
    }

    private IamService newService(Path dir, StorageBackend<String, SessionCredential> sessionStore) {
        return new IamService(
                load(dir, "iam-users.json", new TypeReference<Map<String, IamUser>>() {}),
                load(dir, "iam-groups.json", new TypeReference<Map<String, IamGroup>>() {}),
                load(dir, "iam-roles.json", new TypeReference<Map<String, IamRole>>() {}),
                load(dir, "iam-policies.json", new TypeReference<Map<String, IamPolicy>>() {}),
                load(dir, "iam-access-keys.json", new TypeReference<Map<String, AccessKey>>() {}),
                load(dir, "iam-instance-profiles.json", new TypeReference<Map<String, InstanceProfile>>() {}),
                sessionStore,
                load(dir, "iam-account-aliases.json", new TypeReference<Map<String, String>>() {}),
                load(dir, "iam-password-policy.json", new TypeReference<Map<String, AccountPasswordPolicy>>() {}),
                load(dir, "iam-oidc-providers.json", new TypeReference<Map<String, OpenIDConnectProvider>>() {}),
                load(dir, "iam-slr-deletions.json", new TypeReference<Map<String, String>>() {}),
                new RegionResolver("us-east-1", "000000000000"),
                false,
                null);
    }

    /**
     * A virtual MFA device's seed is the shared secret an authenticator app was programmed with,
     * so losing it on restart would silently break every paired device: the codes would keep
     * being generated and would simply stop being accepted. The assignment has to survive too,
     * or a restart would quietly detach every user's MFA.
     */
    @Test
    void virtualMfaDeviceSurvivesRestartWithItsSeedAndAssignment(@TempDir Path dir) {
        StorageBackend<String, IamUser> users = load(dir, "iam-users.json", new TypeReference<>() {});
        StorageBackend<String, VirtualMfaDevice> devices =
                load(dir, "iam-virtual-mfa-devices.json", new TypeReference<>() {});
        IamService first = newServiceWithMfa(users, devices);
        first.createUser("mfa-persist-user", "/");
        VirtualMfaDevice created = first.createVirtualMfaDevice("mfa-persist", "/", Map.of("env", "prod"));
        long step = Totp.stepAt(Instant.now());
        first.enableMfaDevice("mfa-persist-user", created.getSerialNumber(),
                Totp.codeAt(created.getBase32Seed(), step - 1),
                Totp.codeAt(created.getBase32Seed(), step));

        // A fresh service over the same files is a restart with the same data dir.
        IamService restarted = newServiceWithMfa(
                load(dir, "iam-users.json", new TypeReference<>() {}),
                load(dir, "iam-virtual-mfa-devices.json", new TypeReference<>() {}));

        VirtualMfaDevice reloaded = restarted.getVirtualMfaDevice(created.getSerialNumber());
        assertEquals(created.getBase32Seed(), reloaded.getBase32Seed(), "the seed must survive");
        assertEquals("mfa-persist-user", reloaded.getUserName());
        assertEquals("prod", reloaded.getTags().get("env"));
        // enableDate and createDate are Instants, so a broken time round trip surfaces here.
        assertNotNull(reloaded.getEnableDate());
        assertEquals(created.getCreateDate(), reloaded.getCreateDate());

        // And the reloaded seed still verifies a code, which is what actually matters to a caller.
        long afterRestart = Totp.stepAt(Instant.now());
        restarted.resyncMfaDevice("mfa-persist-user", created.getSerialNumber(),
                Totp.codeAt(reloaded.getBase32Seed(), afterRestart - 1),
                Totp.codeAt(reloaded.getBase32Seed(), afterRestart));
    }

    private IamService newServiceWithMfa(StorageBackend<String, IamUser> users,
                                         StorageBackend<String, VirtualMfaDevice> devices) {
        return new IamService(
                users, new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), devices,
                new RegionResolver("us-east-1", "000000000000"), false, null);
    }

    private <V> StorageBackend<String, V> load(Path dir, String file, TypeReference<Map<String, V>> type) {
        PersistentStorage<String, V> backend = new PersistentStorage<>(dir.resolve(file), type);
        backend.load();
        return backend;
    }
}
