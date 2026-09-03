package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.RegionResolver;
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

    private IamService newService(Path dir) {
        return new IamService(
                load(dir, "iam-users.json", new TypeReference<Map<String, IamUser>>() {}),
                load(dir, "iam-groups.json", new TypeReference<Map<String, IamGroup>>() {}),
                load(dir, "iam-roles.json", new TypeReference<Map<String, IamRole>>() {}),
                load(dir, "iam-policies.json", new TypeReference<Map<String, IamPolicy>>() {}),
                load(dir, "iam-access-keys.json", new TypeReference<Map<String, AccessKey>>() {}),
                load(dir, "iam-instance-profiles.json", new TypeReference<Map<String, InstanceProfile>>() {}),
                load(dir, "iam-sessions.json", new TypeReference<Map<String, SessionCredential>>() {}),
                load(dir, "iam-account-aliases.json", new TypeReference<Map<String, String>>() {}),
                load(dir, "iam-password-policy.json", new TypeReference<Map<String, AccountPasswordPolicy>>() {}),
                load(dir, "iam-oidc-providers.json", new TypeReference<Map<String, OpenIDConnectProvider>>() {}),
                load(dir, "iam-slr-deletions.json", new TypeReference<Map<String, String>>() {}),
                new RegionResolver("us-east-1", "000000000000"),
                false,
                null);
    }

    private <V> StorageBackend<String, V> load(Path dir, String file, TypeReference<Map<String, V>> type) {
        PersistentStorage<String, V> backend = new PersistentStorage<>(dir.resolve(file), type);
        backend.load();
        return backend;
    }
}
