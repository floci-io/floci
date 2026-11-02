package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.SessionCredential;
import io.github.hectorvent.floci.testing.StorageFactories;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class Ec2CredentialRetirementStorageTest {
    private static final String ACCOUNT = "000000000000";
    private static final String OTHER_ACCOUNT = "111122223333";
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({"false,1", "false,2", "false,3", "false,4", "true,1", "true,2", "true,3", "true,4"})
    void snapshotFailureDoesNotInterruptRetirementOfAllGenerations(boolean clear, int failedDeletion) throws Exception {
        EmulatorConfig config = StorageFactories.config(directory, ACCOUNT);
        StorageFactory factory = StorageFactories.open(config, "persistent");
        Path obstruction = directory.resolve("iam-sessions.json.tmp");
        Path snapshot = directory.resolve("iam-sessions.json");
        List<SessionCredential> issued = new ArrayList<>();
        SessionCredential other;
        try {
            IamService iam = spy(new IamService(factory, config,
                    new RegionResolver("us-east-1", ACCOUNT), Clock.fixed(NOW, ZoneOffset.UTC)));
            iam.createRole("worker", "/", "{}", null, 3600, null);
            String profile = iam.createInstanceProfile("profile", "/").getArn();
            iam.addRoleToInstanceProfile("profile", "worker");
            Ec2InstanceCredentials credentials = new Ec2InstanceCredentials(iam);
            Instance instance = instance("i-retiring");
            instance.setIamInstanceProfileArn(profile);
            credentials.register(instance);
            issued.add(credentials.get(instance, "worker", NOW).orElseThrow());
            issued.add(credentials.get(instance, "worker", NOW.plusSeconds(3300)).orElseThrow());
            issued.add(credentials.identity(instance, ACCOUNT, NOW).orElseThrow());
            issued.add(credentials.identity(instance, ACCOUNT, NOW.plusSeconds(3300)).orElseThrow());
            Ec2InstanceCredentials unrelated = clear ? new Ec2InstanceCredentials(iam) : credentials;
            Instance otherInstance = instance("i-unrelated");
            unrelated.register(otherInstance);
            other = unrelated.identity(otherInstance, OTHER_ACCOUNT, NOW).orElseThrow();
            AtomicInteger deletions = new AtomicInteger();
            AtomicReference<byte[]> beforeFailure = new AtomicReference<>();
            doAnswer(invocation -> {
                if (deletions.incrementAndGet() == failedDeletion) {
                    beforeFailure.set(Files.readAllBytes(snapshot));
                    Files.createDirectory(obstruction);
                }
                return invocation.callRealMethod();
            }).when(iam).unregisterSession(anyString(), anyString());

            assertDoesNotThrow(() -> retire(credentials, instance, clear));
            assertArrayEquals(beforeFailure.get(), Files.readAllBytes(snapshot));
            assertTrue(iam.findSecretKey(issued.get(failedDeletion - 1).getAccessKeyId(),
                    issued.get(failedDeletion - 1).getSessionToken()).isEmpty());
            assertAll(
                    () -> assertTrue(credentials.get(instance, "worker", NOW).isEmpty()),
                    () -> assertTrue(credentials.identity(instance, ACCOUNT, NOW).isEmpty()),
                    () -> assertTrue(credentials.role(instance).isEmpty()),
                    () -> assertTrue(credentials.profile(instance).isEmpty()),
                    () -> {
                        Files.delete(obstruction);
                        factory.flushAll();
                        List<String> persisted = new ObjectMapper().readTree(Files.readAllBytes(snapshot))
                                .findValuesAsText("accessKeyId");
                        for (SessionCredential session : issued) {
                            assertFalse(persisted.contains(session.getAccessKeyId()));
                            assertTrue(iam.findSecretKey(session.getAccessKeyId(), session.getSessionToken()).isEmpty());
                        }
                        assertSame(other, unrelated.identity(otherInstance, OTHER_ACCOUNT, NOW).orElseThrow());
                    });
        } finally {
            Files.deleteIfExists(obstruction);
            factory.shutdownAll();
        }
        StorageFactory reopened = StorageFactories.open(config, "persistent");
        try {
            AccountAwareStorageBackend<SessionCredential> sessions = reopened.create(
                    "iam", "iam-sessions.json", new TypeReference<>() {});
            for (SessionCredential session : issued) {
                assertTrue(sessions.getForAccount(ACCOUNT, session.getAccessKeyId()).isEmpty());
            }
            assertTrue(sessions.getForAccount(OTHER_ACCOUNT, other.getAccessKeyId()).isPresent());
        } finally {
            reopened.shutdownAll();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void imdsUnregisterCompletesDespiteSnapshotFailure(int failedDeletion) throws Exception {
        EmulatorConfig config = StorageFactories.config(directory, ACCOUNT);
        StorageFactory factory = StorageFactories.open(config, "persistent");
        Path obstruction = directory.resolve("iam-sessions.json.tmp");
        try {
            IamService iam = spy(new IamService(factory, config,
                    new RegionResolver("us-east-1", ACCOUNT), Clock.systemUTC()));
            iam.createRole("worker", "/", "{}", null, 3600, null);
            String profile = iam.createInstanceProfile("profile", "/").getArn();
            iam.addRoleToInstanceProfile("profile", "worker");
            Instance instance = instance("i-imds-retirement");
            instance.setIamInstanceProfileArn(profile);
            try (Ec2MetadataTestFixture fixture = new Ec2MetadataTestFixture()) {
                fixture.start(iam, config, instance);
                try {
                    HttpResponse<String> tokenResponse = fixture.putToken("60");
                    assertEquals(200, tokenResponse.statusCode());
                    String token = tokenResponse.body();
                    String profilePath = "/latest/meta-data/iam/security-credentials/worker";
                    String identityPath = "/latest/meta-data/identity-credentials/ec2/security-credentials/ec2-instance";
                    HttpResponse<String> profileResponse = fixture.get(profilePath, token);
                    HttpResponse<String> identityResponse = fixture.get(identityPath, token);
                    assertEquals(200, profileResponse.statusCode());
                    assertEquals(200, identityResponse.statusCode());
                    List<JsonObject> issued = List.of(new JsonObject(profileResponse.body()),
                            new JsonObject(identityResponse.body()));
                    AtomicInteger deletions = new AtomicInteger();
                    doAnswer(invocation -> {
                        if (deletions.incrementAndGet() == failedDeletion) {
                            Files.createDirectory(obstruction);
                        }
                        return invocation.callRealMethod();
                    }).when(iam).unregisterSession(anyString(), anyString());
                    assertDoesNotThrow(() -> fixture.server().unregisterInstance(instance));
                    assertTrue(fixture.server().registeredContainer(Ec2MetadataTestFixture.CALLER_IP).isEmpty());
                    assertEquals(404, fixture.get(profilePath, token).statusCode());
                    assertEquals(404, fixture.get(identityPath, token).statusCode());
                    JsonObject remaining = issued.get(failedDeletion - 1);
                    assertTrue(iam.findSecretKey(remaining.getString("AccessKeyId"), remaining.getString("Token")).isEmpty());
                    Files.delete(obstruction);
                    factory.flushAll();
                    assertTrue(fixture.server().registeredContainer(Ec2MetadataTestFixture.CALLER_IP).isEmpty());
                    assertEquals(404, fixture.get(profilePath, token).statusCode());
                    for (JsonObject session : issued) {
                        assertTrue(iam.findSecretKey(session.getString("AccessKeyId"), session.getString("Token")).isEmpty());
                    }
                } finally {
                    Files.deleteIfExists(obstruction);
                }
            }
        } finally {
            Files.deleteIfExists(obstruction);
            factory.shutdownAll();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void clearRevokesEveryInstanceDespiteSnapshotFailure(int failedDeletion) throws Exception {
        EmulatorConfig config = StorageFactories.config(directory, ACCOUNT);
        StorageFactory factory = StorageFactories.open(config, "persistent");
        Path obstruction = directory.resolve("iam-sessions.json.tmp");
        try {
            IamService iam = spy(new IamService(factory, config,
                    new RegionResolver("us-east-1", ACCOUNT), Clock.fixed(NOW, ZoneOffset.UTC)));
            Ec2InstanceCredentials credentials = new Ec2InstanceCredentials(iam);
            Instance first = instance("i-first");
            Instance second = instance("i-second");
            credentials.register(first);
            credentials.register(second);
            SessionCredential firstSession = credentials.identity(first, ACCOUNT, NOW).orElseThrow();
            SessionCredential secondSession = credentials.identity(second, OTHER_ACCOUNT, NOW).orElseThrow();
            AtomicInteger deletions = new AtomicInteger();
            doAnswer(invocation -> {
                if (deletions.incrementAndGet() == failedDeletion) {
                    Files.createDirectory(obstruction);
                }
                return invocation.callRealMethod();
            }).when(iam).unregisterSession(anyString(), anyString());
            assertDoesNotThrow(credentials::clear);
            assertAll(
                    () -> assertTrue(credentials.identity(first, ACCOUNT, NOW).isEmpty()),
                    () -> assertTrue(credentials.identity(second, OTHER_ACCOUNT, NOW).isEmpty()),
                    () -> {
                        Files.delete(obstruction);
                        factory.flushAll();
                        assertTrue(iam.findSecretKey(firstSession.getAccessKeyId(), firstSession.getSessionToken()).isEmpty());
                        assertTrue(iam.findSecretKey(secondSession.getAccessKeyId(), secondSession.getSessionToken()).isEmpty());
                    });
        } finally {
            Files.deleteIfExists(obstruction);
            factory.shutdownAll();
        }
    }

    private static void retire(Ec2InstanceCredentials credentials, Instance instance, boolean clear) {
        if (clear) {
            credentials.clear();
        } else {
            credentials.unregister(instance);
        }
    }

    private static Instance instance(String id) {
        Instance instance = new Instance();
        instance.setInstanceId(id);
        instance.setRegion("us-east-1");
        return instance;
    }
}
