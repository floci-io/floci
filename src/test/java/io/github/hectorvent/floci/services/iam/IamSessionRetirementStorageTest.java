package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ecs.container.EcsTaskRoleCredentials;
import io.github.hectorvent.floci.services.iam.model.SessionCredential;
import io.github.hectorvent.floci.testing.StorageFactories;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class IamSessionRetirementStorageTest {

    private static final String DEFAULT_ACCOUNT = "000000000000";
    private static final String OTHER_ACCOUNT = "111122223333";
    private static final String ACCESS_KEY = "ASIARETIREMENTFIXTURE";

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"memory", "persistent", "hybrid", "wal"})
    void retirementPreservesOtherAccountAndSurvivesReload(String mode) {
        EmulatorConfig config = StorageFactories.config(directory, DEFAULT_ACCOUNT);
        StorageFactory factory = StorageFactories.open(config, mode);
        try {
            IamService iam = new IamService(factory, config,
                    new RegionResolver("us-east-1", DEFAULT_ACCOUNT), Clock.systemUTC());
            AccountAwareStorageBackend<SessionCredential> sessions = factory.create(
                    "iam", "iam-sessions.json", new TypeReference<>() {});
            iam.registerLambdaExecutionRoleSession(DEFAULT_ACCOUNT, ACCESS_KEY, "fixture-secret",
                    "arn:aws:iam::000000000000:role/default");
            iam.registerLambdaExecutionRoleSession(OTHER_ACCOUNT, ACCESS_KEY, "fixture-secret",
                    "arn:aws:iam::111122223333:role/other");

            iam.unregisterSession(OTHER_ACCOUNT, null);
            iam.unregisterSession(OTHER_ACCOUNT, " ");
            assertTrue(sessions.getForAccount(OTHER_ACCOUNT, ACCESS_KEY).isPresent());
            iam.unregisterSession(OTHER_ACCOUNT, ACCESS_KEY);
            iam.unregisterSession(OTHER_ACCOUNT, ACCESS_KEY);

            assertTrue(sessions.getForAccount(OTHER_ACCOUNT, ACCESS_KEY).isEmpty());
            assertTrue(sessions.getForAccount(DEFAULT_ACCOUNT, ACCESS_KEY).isPresent());
        } finally {
            factory.shutdownAll();
        }
        if (!"memory".equals(mode)) {
            StorageFactory reopened = StorageFactories.open(config, mode);
            try {
                IamService iam = new IamService(reopened, config,
                        new RegionResolver("us-east-1", DEFAULT_ACCOUNT), Clock.systemUTC());
                AccountAwareStorageBackend<SessionCredential> sessions = reopened.create(
                        "iam", "iam-sessions.json", new TypeReference<>() {});
                assertTrue(sessions.getForAccount(OTHER_ACCOUNT, ACCESS_KEY).isEmpty());
                assertTrue(sessions.getForAccount(DEFAULT_ACCOUNT, ACCESS_KEY).isPresent());
                iam.unregisterSession(null, ACCESS_KEY);
                assertTrue(sessions.getForAccount(DEFAULT_ACCOUNT, ACCESS_KEY).isEmpty());
            } finally {
                reopened.shutdownAll();
            }
        }
    }
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void snapshotFailureRevokesImmediatelyAndTheNextWritePersistsRemoval(boolean explicitAccount) throws Exception {
        EmulatorConfig config = StorageFactories.config(directory, DEFAULT_ACCOUNT);
        StorageFactory factory = StorageFactories.open(config, "persistent");
        Path obstruction = directory.resolve("iam-sessions.json.tmp");
        String account = explicitAccount ? OTHER_ACCOUNT : DEFAULT_ACCOUNT;
        try {
            IamService iam = new IamService(factory, config,
                    new RegionResolver("us-east-1", DEFAULT_ACCOUNT), Clock.systemUTC());
            AccountAwareStorageBackend<SessionCredential> sessions = factory.create(
                    "iam", "iam-sessions.json", new TypeReference<>() {});
            iam.registerLambdaExecutionRoleSession(account, ACCESS_KEY, "fixture-secret",
                    "arn:aws:iam::" + account + ":role/retirement");
            Path snapshot = directory.resolve("iam-sessions.json");
            byte[] before = Files.readAllBytes(snapshot);
            Files.createDirectory(obstruction);

            assertDoesNotThrow(
                    () -> iam.unregisterSession(explicitAccount ? account : null, ACCESS_KEY));

            assertArrayEquals(before, Files.readAllBytes(snapshot));
            assertTrue(sessions.getForAccount(account, ACCESS_KEY).isEmpty());
            assertTrue(iam.resolveCallerArns(ACCESS_KEY).isEmpty());
            Files.delete(obstruction);
            iam.registerLambdaExecutionRoleSession(DEFAULT_ACCOUNT, "ASIANEXTWRITE", "fixture-secret",
                    "arn:aws:iam::000000000000:role/next-write");
            assertFalse(new ObjectMapper().readTree(Files.readAllBytes(snapshot))
                    .findValuesAsText("accessKeyId").contains(ACCESS_KEY));
            assertTrue(sessions.getForAccount(account, ACCESS_KEY).isEmpty());
        } finally {
            Files.deleteIfExists(obstruction);
            factory.shutdownAll();
        }
        StorageFactory reopened = StorageFactories.open(config, "persistent");
        try {
            AccountAwareStorageBackend<SessionCredential> sessions = reopened.create(
                    "iam", "iam-sessions.json", new TypeReference<>() {});
            assertTrue(sessions.getForAccount(account, ACCESS_KEY).isEmpty());
        } finally {
            reopened.shutdownAll();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void ecsRetirementRevokesEveryGenerationDespiteSnapshotFailure(int failedDeletion) throws Exception {
        EmulatorConfig config = StorageFactories.config(directory, DEFAULT_ACCOUNT);
        when(config.services().ecs().taskRoleCredentials().ttlSeconds()).thenReturn(21600L);
        StorageFactory factory = StorageFactories.open(config, "persistent");
        Path obstruction = directory.resolve("iam-sessions.json.tmp");
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        String taskArn = "arn:aws:ecs:us-east-1:000000000000:task/cluster/retirement";
        try {
            IamService iam = spy(new IamService(factory, config,
                    new RegionResolver("us-east-1", DEFAULT_ACCOUNT), Clock.fixed(now, ZoneOffset.UTC)));
            String roleArn = iam.createRole("retirement", "/", "{}", null, 3600, null).getArn();
            EcsTaskRoleCredentials credentials = new EcsTaskRoleCredentials(iam, config);
            String path = credentials.issue(taskArn, roleArn, DEFAULT_ACCOUNT, now).orElseThrow();
            SessionCredential first = credentials.resolveByPath(path, now).orElseThrow();
            Instant rotatedAt = first.getExpiration().minusSeconds(60);
            SessionCredential second = credentials.resolveByPath(path, rotatedAt).orElseThrow();
            AtomicInteger deletions = new AtomicInteger();
            doAnswer(invocation -> {
                if (deletions.incrementAndGet() == failedDeletion) {
                    Files.createDirectory(obstruction);
                }
                return invocation.callRealMethod();
            }).when(iam).unregisterSession(anyString(), anyString());

            assertDoesNotThrow(() -> credentials.revoke(taskArn));
            assertAll(
                    () -> assertTrue(credentials.resolveByPath(path, rotatedAt).isEmpty()),
                    () -> assertTrue(iam.findSecretKey(first.getAccessKeyId(), first.getSessionToken()).isEmpty()),
                    () -> assertTrue(iam.findSecretKey(second.getAccessKeyId(), second.getSessionToken()).isEmpty()));
            Files.delete(obstruction);
            factory.flushAll();
        } finally {
            Files.deleteIfExists(obstruction);
            factory.shutdownAll();
        }
    }

}
