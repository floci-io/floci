package io.github.hectorvent.floci.services.msk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.kms.model.KmsKey;
import io.github.hectorvent.floci.services.kms.model.KmsKeySpec;
import io.github.hectorvent.floci.services.kms.model.KmsKeyUsage;
import io.github.hectorvent.floci.services.msk.model.ClusterState;
import io.github.hectorvent.floci.services.msk.model.MskCluster;
import io.github.hectorvent.floci.services.msk.model.MskConfiguration;
import io.github.hectorvent.floci.services.msk.model.MskScramAssociations;
import io.github.hectorvent.floci.services.msk.model.ScramSecretAssociation;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import io.github.hectorvent.floci.services.secretsmanager.model.SecretVersion;
import io.github.hectorvent.floci.testutil.LogCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.LogRecord;
import java.util.stream.Stream;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MskScramServiceTest {

    private static final String ACCOUNT = "000000000000";
    private static final String SECRET =
        "arn:aws:secretsmanager:us-east-1:000000000000:secret:AmazonMSK_alice";
    private AccountAwareStorageBackend<MskCluster> clusters;
    private AccountAwareStorageBackend<MskScramAssociations> associations;
    private StorageFactory storageFactory;
    private EmulatorConfig config;
    private RegionResolver regionResolver;
    private RedpandaManager redpanda;
    private SecretsManagerService secrets;
    private KmsService kms;
    private ObjectMapper mapper;
    private MskService clusterService;
    private MskScramSecretsService service;
    private MskCluster cluster;

    @BeforeEach
    void setUp() {
        clusters = AccountAwareStorageBackend.inMemory(ACCOUNT);
        associations = spy(AccountAwareStorageBackend.<MskScramAssociations>inMemory(ACCOUNT));
        storageFactory = mock(StorageFactory.class);
        when(storageFactory.<MskCluster>create(eq("msk"), eq("msk-clusters.json"), any()))
            .thenReturn(clusters);
        when(storageFactory.<MskScramAssociations>create(eq("msk"), eq("msk-scram-associations.json"), any()))
            .thenReturn(associations);
        when(storageFactory.<MskConfiguration>create(eq("msk"), eq("msk-configurations.json"), any()))
            .thenReturn(AccountAwareStorageBackend.inMemory(ACCOUNT));
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().msk().mock()).thenReturn(true);
        regionResolver = mock(RegionResolver.class);
        when(regionResolver.getRegion()).thenReturn("us-east-1");
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT);
        redpanda = mock(RedpandaManager.class);
        secrets = mock(SecretsManagerService.class);
        kms = mock(KmsService.class);
        when(secrets.describeSecret(anyString(), anyString())).thenAnswer(invocation -> {
            Secret metadata = new Secret();
            metadata.setArn(invocation.getArgument(0));
            metadata.setKmsKeyId("customer-key");
            return metadata;
        });
        when(kms.describeKey(eq("customer-key"), anyString()))
            .thenReturn(new KmsKey());
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        clusterService = spy(new MskService(storageFactory, config, regionResolver, redpanda));
        service = new MskScramSecretsService(storageFactory, config, clusterService, redpanda, secrets, kms, mapper);
        cluster = clusterService.createCluster("scram");
        cluster.setContainerId("broker-1");
        when(config.services().msk().mock())
            .thenReturn(false);
    }

    private SecretVersion version(String username, String password) {
        SecretVersion version = new SecretVersion();
        version.setVersionId(UUID.randomUUID().toString());
        version.setSecretString(
            "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
        return version;
    }

    @Test
    void brokerOperationDoesNotBlockAnotherCluster() throws Exception {
        when(config.services().msk().mock())
            .thenReturn(true);
        MskCluster otherCluster = clusterService.createCluster("other-scram");
        when(config.services().msk().mock())
            .thenReturn(false);
        CountDownLatch operationInFirstClusterStarted = new CountDownLatch(1);
        CountDownLatch operationInSecondClusterDone = new CountDownLatch(1);
        doAnswer(invocation -> {
            operationInFirstClusterStarted.countDown();
            assertTrue(operationInSecondClusterDone.await(10, TimeUnit.SECONDS));
            return null;
        }).when(redpanda).createUser(cluster, "alice", "password");

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> associationInFirstCluster = executor.submit(
                () -> associate(SECRET, version("alice", "password")));
            try {
                assertTrue(operationInFirstClusterStarted.await(5, TimeUnit.SECONDS));
                Future<List<String>> listInSecondCluster = executor.submit(
                    () -> service.listScramSecrets(otherCluster.getClusterArn(), null, null).items());
                assertTrue(listInSecondCluster.get(5, TimeUnit.SECONDS).isEmpty());
            } finally {
                operationInSecondClusterDone.countDown();
            }
            associationInFirstCluster.get(5, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void reconciliationRejectsNonPositiveInterval(long intervalMs) {
        when(config.services().msk().scramReconciliationIntervalMs()).thenReturn(intervalMs);

        try {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, service::init);
            assertEquals("floci.services.msk.scram-reconciliation-interval-ms must be greater than zero.",
                error.getMessage());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void scheduledReconciliationKeepsOtherClustersSyncingWhileOneBrokerIsBlocked() throws Exception {
        when(config.services().msk().mock()).thenReturn(true);
        MskCluster healthyCluster = clusterService.createCluster("healthy-scram");
        when(config.services().msk().mock()).thenReturn(false);
        String healthySecret = SECRET + "-healthy";
        associate(SECRET, version("alice", "original"));
        setSecretValue(healthySecret, version("bob", "original"));
        assertTrue(service.batchAssociateScramSecret(healthyCluster.getClusterArn(), List.of(healthySecret)).isEmpty());

        CountDownLatch operationForFirstClusterStarted = new CountDownLatch(1);
        CountDownLatch operationForSecondClusterDone = new CountDownLatch(1);
        CountDownLatch operationForFirstClusterDone = new CountDownLatch(1);
        doAnswer(invocation -> {
            operationForFirstClusterStarted.countDown();
            try {
                assertTrue(operationForSecondClusterDone.await(30, TimeUnit.SECONDS));
            } finally {
                operationForFirstClusterDone.countDown();
            }
            return null;
        }).when(redpanda).createUser(cluster, "alice", "rotated");
        setSecretValue(SECRET, version("alice", "rotated"));
        when(config.services().msk().scramReconciliationIntervalMs()).thenReturn(5L);

        try {
            service.init();
            assertTrue(operationForFirstClusterStarted.await(5, TimeUnit.SECONDS));

            // More rotations than workers expose duplicate blocked jobs exhausting the pool.
            for (int rotation = 1; rotation <= 6; rotation++) {
                String password = "healthy-rotation-" + rotation;
                setSecretValue(healthySecret, version("bob", password));
                await()
                    .atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> verify(redpanda).createUser(healthyCluster, "bob", password));
            }

            verify(redpanda, times(1)).createUser(cluster, "alice", "rotated");
        } finally {
            operationForSecondClusterDone.countDown();
            try {
                assertTrue(operationForFirstClusterDone.await(5, TimeUnit.SECONDS));
            } finally {
                service.shutdown();
            }
        }
    }

    @Test
    void scheduledReconciliationLogsRepeatedFailuresAndRecovers() {
        associate(SECRET, version("alice", "original"));
        setSecretValue(SECRET, version("alice", "rotated"));
        doThrow(new IllegalStateException("Broker unavailable"))
            .doThrow(new IllegalStateException("Broker unavailable"))
            .doThrow(new IllegalArgumentException("Invalid broker"))
            .doNothing()
            .when(redpanda)
            .createUser(cluster, "alice", "rotated");
        when(config.services().msk().scramReconciliationIntervalMs()).thenReturn(5L);

        try {
            List<LogRecord> logs = LogCapture.capture(MskScramSecretsService.class, () -> {
                service.init();
                await()
                    .atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> verify(redpanda, times(4)).createUser(cluster, "alice", "rotated"));
            });
            assertEquals(List.of("ERROR", "DEBUG", "ERROR"), logLevels(logs));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void scheduledReconciliationAppliesRotationsUsingConfiguredInterval() {
        associate(SECRET, version("alice", "original"));
        when(config.services().msk().scramReconciliationIntervalMs()).thenReturn(5L);
        setSecretValue(SECRET, version("alice", "rotated"));

        try {
            service.init();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                verify(redpanda).createUser(cluster, "alice", "rotated"));

            setSecretValue(SECRET, version("alice", "rotated-again"));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                verify(redpanda).createUser(cluster, "alice", "rotated-again"));
        } finally {
            service.shutdown();
        }
    }

    private void associate(String arn, SecretVersion version) {
        setSecretValue(arn, version);
        assertTrue(
            service.batchAssociateScramSecret(cluster.getClusterArn(), List.of(arn)).isEmpty());
    }

    private void setSecretValue(String arn, SecretVersion version) {
        Secret secret = new Secret();
        secret.setArn(arn);
        secret.setKmsKeyId("customer-key");
        version.setVersionStages(List.of("AWSCURRENT"));
        secret.setVersions(Map.of(version.getVersionId(), version));
        doReturn(secret).when(secrets).describeSecret(arn, AwsArnUtils.parse(arn).region());
    }

    private void setSecretReadError(String errorCode, String message) {
        doThrow(new AwsException(errorCode, message, 400))
            .when(secrets).describeSecret(SECRET, "us-east-1");
    }

    private void scheduleDeletion(String arn) {
        Secret metadata = new Secret();
        metadata.setArn(arn);
        metadata.setDeletedDate(Instant.now().plusSeconds(86400));
        doReturn(metadata).when(secrets).describeSecret(arn, "us-east-1");
    }

    private MskScramAssociations state() {
        return associations
            .getForAccount(ACCOUNT, cluster.getClusterArn())
            .orElseGet(MskScramAssociations::new);
    }

    private ScramSecretAssociation association(String secretArn) {
        return state().getAssociations().get(secretArn);
    }

    private static Stream<Arguments> invalidCredentials() {
        return Stream.of(
            Arguments.of(Named.of("empty content", "")),
            Arguments.of(Named.of("malformed JSON", "{")),
            Arguments.of(Named.of("array instead of object", "[]")),
            Arguments.of(Named.of("string instead of object", "\"alice\"")),
            Arguments.of(Named.of("number instead of object", "42")),
            Arguments.of(Named.of("empty object", "{}")),
            Arguments.of(Named.of("missing username", "{\"password\":\"pw\"}")),
            Arguments.of(Named.of("missing password", "{\"username\":\"alice\"}")),
            Arguments.of(Named.of("null username", "{\"username\":null,\"password\":\"pw\"}")),
            Arguments.of(Named.of("empty username", "{\"username\":\"\",\"password\":\"pw\"}")),
            Arguments.of(Named.of("blank username", "{\"username\":\"   \",\"password\":\"pw\"}")),
            Arguments.of(Named.of("empty password", "{\"username\":\"alice\",\"password\":\"\"}")),
            Arguments.of(Named.of("blank password", "{\"username\":\"alice\",\"password\":\"   \"}")),
            Arguments.of(Named.of("array username", "{\"username\":[],\"password\":\"pw\"}")),
            Arguments.of(Named.of("object username", "{\"username\":{},\"password\":\"pw\"}")),
            Arguments.of(Named.of("boolean username", "{\"username\":true,\"password\":\"pw\"}")),
            Arguments.of(Named.of("null password", "{\"username\":\"alice\",\"password\":null}")),
            Arguments.of(Named.of("array password", "{\"username\":\"alice\",\"password\":[]}")),
            Arguments.of(Named.of("object password", "{\"username\":\"alice\",\"password\":{}}")),
            Arguments.of(Named.of("numeric password", "{\"username\":\"alice\",\"password\":123}")));
    }

    private static Stream<Arguments> validCredentials() {
        return Stream.of(
            Arguments.of(Named.of("string credentials", "{\"username\":\"alice\",\"password\":\"pw\"}"), "alice", "pw"),
            Arguments.of(Named.of("numeric username converted to string", "{\"username\":123,\"password\":\"pw\"}"), "123", "pw"),
            Arguments.of(Named.of("boolean password converted to string", "{\"username\":\"alice\",\"password\":true}"), "alice", "true"),
            Arguments.of(Named.of("extra fields ignored", "{\"username\":\"alice\",\"password\":\"pw\",\"extra\":42}"), "alice", "pw"));
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("validCredentials")
    void readScramCredentialsWithValidValues(
        String content, String username, String password) {
        // Supported scalar values become strings, while unrelated JSON fields are ignored.
        MskScramSecretsService.ScramCredentials credentials = service.readScramCredentials(content);

        assertEquals(username, credentials.username());
        assertEquals(password, credentials.password());
    }

    @Test
    void reconciliationRemovesOnlyDeletedClusterAssociationsAcrossAccounts() {
        associate(SECRET, version("alice", "password"));
        String otherAccount = "111111111111";
        String deletedCluster = cluster.getClusterArn().replace(ACCOUNT, otherAccount);
        associations.putForAccount(otherAccount, deletedCluster, new MskScramAssociations());

        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);
        service.reconcileCluster(deletedCluster, otherAccount);

        assertTrue(associations.getForAccount(ACCOUNT, cluster.getClusterArn()).isPresent());
        assertTrue(associations.getForAccount(otherAccount, deletedCluster).isEmpty());
        verify(clusterService).getClusterForAccount(otherAccount, deletedCluster);
    }

    @Test
    void reconciliationPreservesAssociationsWhenClusterLookupFails() {
        associate(SECRET, version("alice", "password"));
        AwsException failure = new AwsException("InternalServerErrorException", "Lookup failed.", 500);
        doThrow(failure).when(clusterService).getClusterForAccount(ACCOUNT, cluster.getClusterArn());

        assertEquals(failure, assertThrows(AwsException.class, () -> service.reconcileCluster(cluster.getClusterArn(), ACCOUNT)));
        assertTrue(associations.getForAccount(ACCOUNT, cluster.getClusterArn()).isPresent());
    }

    @Test
    void readScramCredentialsRejectsNullSecretStringWithInternalServerError() {
        // A missing secret string maps to an internal error rather than invalid credentials.
        AwsException error = assertThrows(AwsException.class, () -> service.readScramCredentials(null));

        assertEquals("InternalServerError", error.getErrorCode());
        assertEquals(500, error.getHttpStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"alias/msk-scram",
            "arn:aws:kms:us-east-1:000000000000:alias/msk-scram"})
    void rejectsKmsAliasReferencesBeforeKeyLookup(String keyReference) {
        Secret metadata = new Secret();
        metadata.setArn(SECRET);
        metadata.setKmsKeyId(keyReference);
        when(secrets.describeSecret(SECRET, "us-east-1")).thenReturn(metadata);

        List<Map<String, String>> failures = service.batchAssociateScramSecret(cluster.getClusterArn(), List.of(SECRET));

        assertEquals("BadRequestException", failures.getFirst().get("errorCode"));
        assertTrue(service.listScramSecrets(cluster.getClusterArn(), null, null).items().isEmpty());
        verifyNoInteractions(kms);
        verify(secrets, never()).batchGetSecretValue(any(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345678-1234-1234-1234-123456789012",
            "arn:aws:kms:us-east-1:000000000000:key/12345678-1234-1234-1234-123456789012"})
    void acceptsKmsKeyIdsAndKeyArns(String keyReference) {
        Secret metadata = new Secret();
        metadata.setArn(SECRET);
        metadata.setKmsKeyId(keyReference);
        when(secrets.describeSecret(SECRET, "us-east-1")).thenReturn(metadata);
        when(kms.describeKey(keyReference, "us-east-1")).thenReturn(new KmsKey());
        setSecretValue(SECRET, version("alice", "password"));
        Secret secret = secrets.describeSecret(SECRET, "us-east-1");
        secret.setKmsKeyId(keyReference);

        assertTrue(service.batchAssociateScramSecret(cluster.getClusterArn(), List.of(SECRET)).isEmpty());

        verify(kms).describeKey(keyReference, "us-east-1");
        assertEquals(List.of(SECRET), service.listScramSecrets(cluster.getClusterArn(), null, null).items());
    }

    @Test
    void rejectsPartialSecretArnForAssociation() {
        String fullArn = SECRET + "-Ab12Cd";
        Secret metadata = new Secret();
        metadata.setArn(fullArn);
        metadata.setKmsKeyId("customer-key");
        when(secrets.describeSecret(SECRET, "us-east-1")).thenReturn(metadata);

        List<Map<String, String>> failures = service.batchAssociateScramSecret(cluster.getClusterArn(), List.of(SECRET));

        assertEquals(List.of(Map.of("secretArn", SECRET, "errorCode", "InvalidSecretArn",
                "errorMessage", "Amazon MSK wasn't able to access the provided secret.")), failures);
        assertTrue(service.listScramSecrets(cluster.getClusterArn(), null, null).items().isEmpty());
        verifyNoInteractions(kms);
        verify(secrets, never()).batchGetSecretValue(any(), anyString());
        associate(fullArn, version("alice", "password"));
        assertEquals(List.of(fullArn), service.listScramSecrets(cluster.getClusterArn(), null, null).items());
    }

    @Test
    void rejectsPartialSecretArnForDisassociation() {
        String fullArn = SECRET + "-Ab12Cd";
        associate(fullArn, version("alice", "password"));
        clearInvocations(secrets);

        List<Map<String, String>> failures = service.batchDisassociateScramSecret(cluster.getClusterArn(), List.of(SECRET));

        assertEquals(List.of(Map.of("secretArn", SECRET, "errorCode", "InvalidSecretArn",
                "errorMessage", "The provided secret ARN is invalid.")), failures);
        assertEquals(List.of(fullArn), service.listScramSecrets(cluster.getClusterArn(), null, null).items());
        assertTrue(service.batchDisassociateScramSecret(cluster.getClusterArn(), List.of(fullArn)).isEmpty());
        verifyNoInteractions(secrets);
    }

    @Test
    void disassociatesDeletedSecretWithoutLookingItUp() {
        associate(SECRET, version("alice", "password"));
        when(secrets.describeSecret(SECRET, "us-east-1"))
                .thenThrow(new AwsException("ResourceNotFoundException", "Secret not found.", 400));
        clearInvocations(secrets);

        assertTrue(service.batchDisassociateScramSecret(cluster.getClusterArn(), List.of(SECRET)).isEmpty());

        assertTrue(service.listScramSecrets(cluster.getClusterArn(), null, null).items().isEmpty());
        assertTrue(associations.getForAccount(ACCOUNT, cluster.getClusterArn()).isEmpty());
        verifyNoInteractions(secrets);
    }

    @Test
    void rejectsReturnedSecretArnForDifferentSecret() {
        Secret secret = new Secret();
        secret.setArn(SECRET + "Other");
        when(secrets.describeSecret(SECRET, "us-east-1")).thenReturn(secret);

        List<Map<String, String>> failures = service.batchAssociateScramSecret(cluster.getClusterArn(), List.of(SECRET));

        assertEquals("InvalidSecretArn", failures.getFirst().get("errorCode"));
        assertTrue(service.listScramSecrets(cluster.getClusterArn(), null, null).items().isEmpty());
    }

    @Test
    void readScramCredentialsRejectsJsonNullWithInternalServerError() {
        // JSON null has the same internal-error contract as a missing secret string.
        AwsException error =
            assertThrows(AwsException.class, () -> service.readScramCredentials("null"));

        assertEquals("InternalServerError", error.getErrorCode());
        assertEquals(500, error.getHttpStatus());
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("invalidCredentials")
    void readScramCredentialsWithInvalidValues(String content) {
        // Malformed content and unsupported credential values produce InvalidSecretArn.
        AwsException error =
            assertThrows(AwsException.class, () -> service.readScramCredentials(content));

        assertEquals("InvalidSecretArn", error.getErrorCode());
    }

    @Test
    void associationRejectsDefaultAndIncompatibleKmsKeysWithoutChangingCredentials() {
        // Only compatible customer-managed keys are accepted, without disturbing working credentials.
        SecretVersion original = version("alice", "password");
        associate(SECRET, original);
        clearInvocations(redpanda);
        Secret metadata = new Secret();
        String invalidArn = SECRET + "-invalid-key";
        metadata.setArn(invalidArn);
        when(secrets.describeSecret(invalidArn, "us-east-1")).thenReturn(metadata);
        KmsKey key = new KmsKey();
        when(kms.describeKey("incompatible-key", "us-east-1")).thenReturn(key);

        for (String keyKind : List.of("default", "aws-managed", "asymmetric", "hmac")) {
            metadata.setKmsKeyId("default".equals(keyKind) ? null : "incompatible-key");
            key.setKeyManager("aws-managed".equals(keyKind) ? "AWS" : "CUSTOMER");
            key.setKeySpec(
                "asymmetric".equals(keyKind)
                    ? KmsKeySpec.RSA_2048
                    : "hmac".equals(keyKind)
                    ? KmsKeySpec.HMAC_256
                    : KmsKeySpec.SYMMETRIC_DEFAULT);
            key.setKeyUsage(
                "hmac".equals(keyKind)
                    ? KmsKeyUsage.GENERATE_VERIFY_MAC
                    : KmsKeyUsage.ENCRYPT_DECRYPT);

            List<Map<String, String>> failures =
                service.batchAssociateScramSecret(cluster.getClusterArn(), List.of(invalidArn));

            assertEquals(1, failures.size(), keyKind);
            assertEquals("BadRequestException", failures.getFirst().get("errorCode"), keyKind);
            assertNull(association(invalidArn));
            assertEquals(original.getVersionId(), association(SECRET).getAppliedVersionId(), keyKind);
        }
        verifyNoInteractions(redpanda);
        assertTrue(service.batchDisassociateScramSecret(cluster.getClusterArn(), List.of(SECRET)).isEmpty());
    }

    @Test
    void associationRejectsKmsAliasBeforeLookupWithoutReadingCredentials() {
        // KMS aliases fail validation before any key lookup, credential read, or broker change.
        Secret metadata = new Secret();
        metadata.setArn(SECRET);
        metadata.setKmsKeyId("alias/msk-scram");
        when(secrets.describeSecret(SECRET, "us-east-1")).thenReturn(metadata);

        List<Map<String, String>> failures = service.batchAssociateScramSecret(cluster.getClusterArn(), List.of(SECRET));

        assertEquals(1, failures.size());
        assertEquals(SECRET, failures.getFirst().get("secretArn"));
        assertEquals("BadRequestException", failures.getFirst().get("errorCode"));
        assertNull(association(SECRET));
        verifyNoInteractions(kms, redpanda);
        verify(secrets, never()).batchGetSecretValue(any(), anyString());
    }

    @Test
    void associationReportsKmsLookupFailureWithoutCreatingAssociation() {
        // Key lookup errors remain item failures and must not create association state.
        doThrow(new AwsException("NotFoundException", "Key not found.", 400))
            .when(kms)
            .describeKey("customer-key", "us-east-1");

        List<Map<String, String>> failures =
            service.batchAssociateScramSecret(cluster.getClusterArn(), List.of(SECRET));

        assertEquals("NotFoundException", failures.getFirst().get("errorCode"));
        assertNull(association(SECRET));
        verifyNoInteractions(redpanda);
    }

    @Test
    void duplicateAssociationBatchIsRejectedBeforeAnySecretIsProcessed() {
        // Duplicate ARNs invalidate the whole batch before any item can cause side effects.
        String fresh = SECRET + "-fresh";

        AwsException error = assertThrows(
                AwsException.class,
                () -> service.batchAssociateScramSecret(cluster.getClusterArn(), List.of(fresh, SECRET, SECRET))
            );

        assertEquals("BadRequestException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertTrue(state().getAssociations().isEmpty());
        verifyNoInteractions(redpanda, secrets, kms);
        verify(associations, never()).putForAccount(anyString(), anyString(), any());
    }

    @Test
    void duplicateAssociationBatchPreservesExistingAssociation() {
        // Rejecting a duplicate batch must leave existing credentials and membership untouched.
        SecretVersion original = version("alice", "original");
        associate(SECRET, original);
        clearInvocations(redpanda, secrets, kms, associations);
        String fresh = SECRET + "-fresh";

        AwsException error = assertThrows(
                AwsException.class,
                () -> service.batchAssociateScramSecret(cluster.getClusterArn(), List.of(fresh, SECRET, SECRET))
            );

        assertEquals("BadRequestException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals(
            List.of(SECRET),
            service.listScramSecrets(cluster.getClusterArn(), null, null).items());
        assertEquals("alice", association(SECRET).getUsername());
        assertEquals(original.getVersionId(), association(SECRET).getAppliedVersionId());
        verifyNoInteractions(redpanda, secrets, kms);
        verify(associations, never()).putForAccount(anyString(), anyString(), any());
    }

    @Test
    void associationRejectsSecretsWithoutAmazonMskPrefix() {
        String nonPrefixSecret =
            "arn:aws:secretsmanager:us-east-1:000000000000:secret:NotAmazonMSK_user";
        List<Map<String, String>> associateFailures =
            service.batchAssociateScramSecret(
                cluster.getClusterArn(), List.of(nonPrefixSecret));
        assertEquals(1, associateFailures.size());
        assertEquals("BadRequestException", associateFailures.getFirst().get("errorCode"));
    }

    @Test
    void listingAndDisassociationRejectServerlessClusters() {
        // Serverless clusters do not support these SCRAM management operations.
        cluster.setClusterType("SERVERLESS");
        assertThrows(AwsException.class, () -> service.listScramSecrets(cluster.getClusterArn(), null, null));
        assertThrows(AwsException.class, () -> service.batchDisassociateScramSecret(cluster.getClusterArn(), List.of(SECRET)));
    }

    @Test
    void associationReadsCurrentVersionFromSecretMetadata() {
        // Association applies the AWSCURRENT secret value and records its version only after success.
        SecretVersion version = version("alice", "password");
        setSecretValue(SECRET, version);
        Secret secret = secrets.describeSecret(SECRET, "us-east-1");
        SecretVersion previous = version("bob", "old-password");
        previous.setVersionStages(List.of("AWSPREVIOUS"));
        secret.setVersions(Map.of(version.getVersionId(), version, previous.getVersionId(), previous));
        secret.setCurrentVersionId(previous.getVersionId());
        Instant lastAccessed = Instant.parse("2026-01-01T00:00:00Z");
        secret.setLastAccessedDate(lastAccessed);
        clearInvocations(secrets);
        
        assertTrue(service.batchAssociateScramSecret(cluster.getClusterArn(), List.of(SECRET)).isEmpty());

        verify(secrets).describeSecret(SECRET, "us-east-1");
        verify(secrets, never()).batchGetSecretValue(any(), anyString());
        verify(secrets, never()).getSecretValue(anyString(), any(), any(), anyString());
        verify(secrets, never()).batchGetSecretValueByFilters(any(), anyString());
        verify(redpanda).createUser(cluster, "alice", "password");
        assertEquals(version.getVersionId(), association(SECRET).getAppliedVersionId());
        assertEquals(lastAccessed, secret.getLastAccessedDate());
    }

    @Test
    void associationOfSecretScheduledForDeletionReportsInternalServerError() {
        setSecretValue(SECRET, version("alice", "password"));
        Secret secret = secrets.describeSecret(SECRET, "us-east-1");
        secret.setDeletedDate(Instant.now().plusSeconds(86400));

        List<Map<String, String>> failures =
            service.batchAssociateScramSecret(cluster.getClusterArn(), List.of(SECRET));

        assertEquals(List.of(Map.of("secretArn", SECRET, "errorCode", "InternalServerError",
            "errorMessage", "You can't perform this operation on the secret because it was marked for deletion.")),
            failures);
        assertNull(association(SECRET));
        verifyNoInteractions(redpanda);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ResourceNotFoundException", "InvalidRequestException", "AccessDeniedException"})
    void secretLookupErrorsAreReportedPerSecretWithoutBlockingOtherAssociations(String errorCode) {
        setSecretReadError(errorCode, "Secret cannot be read.");
        String validArn = SECRET + "-valid";
        setSecretValue(validArn, version("bob", "password"));

        List<Map<String, String>> failures = service.batchAssociateScramSecret(
            cluster.getClusterArn(), List.of(SECRET, validArn));

        assertEquals(List.of(Map.of("secretArn", SECRET, "errorCode", errorCode,
            "errorMessage", "Secret cannot be read.")), failures);
        assertNull(association(SECRET));
        assertNotNull(association(validArn));
        verify(redpanda).createUser(cluster, "bob", "password");
    }

    @Test
    void associationIntentIsStoredBeforeBrokerUpdate() {
        // Persisted ownership must precede broker creation so an interrupted operation remains recoverable.
        doAnswer(
            invocation -> {
                ScramSecretAssociation intent = association(SECRET);
                assertNotNull(intent);
                assertEquals("alice", intent.getUsername());
                assertNull(intent.getAppliedVersionId());
                return null;
            })
            .when(redpanda)
            .createUser(cluster, "alice", "password");

        associate(SECRET, version("alice", "password"));
        assertNotNull(association(SECRET).getAppliedVersionId());
    }

    @Test
    void failedAssociationCanBeRetried() {
        SecretVersion version = version("alice", "password");
        setSecretValue(SECRET, version);
        doThrow(new AwsException("ServiceUnavailableException", "Broker unavailable.", 503))
            .when(redpanda)
            .createUser(cluster, "alice", "password");
        assertEquals(
            1,
            service.batchAssociateScramSecret(cluster.getClusterArn(), List.of(SECRET)).size());
        assertTrue(service.listScramSecrets(cluster.getClusterArn(), null, null).items().isEmpty());

        doNothing().when(redpanda).createUser(cluster, "alice", "password");
        associate(SECRET, version);

        assertEquals("alice", association(SECRET).getUsername());
        assertEquals(version.getVersionId(), association(SECRET).getAppliedVersionId());
        assertEquals(List.of(SECRET), service.listScramSecrets(cluster.getClusterArn(), null, null).items());
    }

    @Test
    void successfulAssociationCannotBeRepeated() {
        SecretVersion version = version("alice", "password");
        associate(SECRET, version);
        clearInvocations(redpanda, associations);
        setSecretValue(SECRET, version("bob", "changed"));
        List<Map<String, String>> failures =
            service.batchAssociateScramSecret(cluster.getClusterArn(), List.of(SECRET));
        assertEquals("InvalidSecretArn", failures.getFirst().get("errorCode"));
        assertEquals("alice", association(SECRET).getUsername());
        assertEquals(version.getVersionId(), association(SECRET).getAppliedVersionId());
        verifyNoInteractions(redpanda);
        verify(associations, never()).putForAccount(anyString(), anyString(), any());
        assertEquals(List.of(SECRET), service.listScramSecrets(cluster.getClusterArn(), null, null).items());
    }

    @Test
    void repeatedAssociationIsRejectedBeforeReadingUnavailableSecret() {
        // Existing membership determines duplicate rejection even when the secret cannot be read.
        associate(SECRET, version("alice", "password"));
        clearInvocations(redpanda);
        for (boolean scheduledForDeletion : List.of(false, true)) {
            Secret secret = new Secret();
            secret.setArn(SECRET);
            secret.setDeletedDate(scheduledForDeletion ? Instant.now().plusSeconds(86400) : null);
            doReturn(secret).when(secrets).describeSecret(SECRET, "us-east-1");

            List<Map<String, String>> failures =
                service.batchAssociateScramSecret(cluster.getClusterArn(), List.of(SECRET));

            assertEquals("InvalidSecretArn", failures.getFirst().get("errorCode"));
            assertEquals("alice", association(SECRET).getUsername());
        }
        verifyNoInteractions(redpanda);
    }

    @Test
    void disassociationRejectsNullEmptyAndOversizedBatches() {
        // Disassociation requires a nonempty batch containing at most ten secret ARNs.
        String clusterArn = cluster.getClusterArn();
        assertThrows(AwsException.class, () -> service.batchDisassociateScramSecret(clusterArn, null));
        assertThrows(AwsException.class, () -> service.batchDisassociateScramSecret(clusterArn, List.of()));
        assertThrows(AwsException.class, () -> service.batchDisassociateScramSecret(clusterArn, Collections.nCopies(11, SECRET)));
    }

    @Test
    void disassociationReportsMalformedSecretArnAsAnItemFailure() {
        // A malformed secret ARN is reported per item rather than failing the whole request.
        assertEquals(1, service.batchDisassociateScramSecret(cluster.getClusterArn(), List.of("malformed")).size());
    }

    @Test
    void unassociatedDisassociationReturnsOneFailurePerOccurrenceWithoutBrokerCalls() {
        // Each unassociated ARN occurrence fails independently without storage or broker changes.
        List<Map<String, String>> failures =
            service.batchDisassociateScramSecret(
                cluster.getClusterArn(), List.of(SECRET, SECRET));

        assertEquals(2, failures.size());
        for (Map<String, String> failure : failures) {
            assertEquals(SECRET, failure.get("secretArn"));
            assertEquals("InvalidSecretArn", failure.get("errorCode"));
        }
        assertTrue(state().getAssociations().isEmpty());
        verifyNoInteractions(redpanda);
        verify(associations, never()).putForAccount(anyString(), anyString(), any());
    }

    @Test
    void repeatedDisassociationPreservesOtherAssociations() {
        // Removing an already absent secret must not affect other cluster associations.
        String other = SECRET + "-other";
        associate(SECRET, version("alice", "pw"));
        associate(other, version("bob", "pw"));
        assertTrue(service.batchDisassociateScramSecret(cluster.getClusterArn(), List.of(SECRET)).isEmpty());
        clearInvocations(redpanda);

        List<Map<String, String>> failures = service.batchDisassociateScramSecret(cluster.getClusterArn(), List.of(SECRET));

        assertEquals("InvalidSecretArn", failures.getFirst().get("errorCode"));
        assertEquals(List.of(other), service.listScramSecrets(cluster.getClusterArn(), null, null).items());
        verifyNoInteractions(redpanda);
    }

    @Test
    void failedDisassociationRetriesOriginalUsernameWithoutApplyingChangedCredentials() {
        // Pending cleanup retains the original username even if the secret rotates before retry.
        associate(SECRET, version("alice", "password"));
        doThrow(new AwsException("ServiceUnavailableException", "Broker unavailable.", 503))
            .when(redpanda)
            .deleteUser(cluster, "alice");

        assertEquals(1, service.batchDisassociateScramSecret(cluster.getClusterArn(), List.of(SECRET)).size());
        assertEquals("alice", association(SECRET).getUsername());
        assertTrue(association(SECRET).isDisassociating());
        clearInvocations(redpanda);
        setSecretValue(SECRET, version("charlie", "changed-during-cleanup"));
        doNothing().when(redpanda).deleteUser(cluster, "alice");
        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);
        assertTrue(state().getAssociations().isEmpty());
        verify(redpanda).deleteUser(cluster, "alice");
        verify(redpanda, never()).createUser(any(), anyString(), anyString());
    }

    @Test
    void listScramSecretsReturnsEachPageAndOnlyProvidesTokenWhenMoreResultsExist() {
        // Pagination exposes each association once and omits the token on the final page.
        String second = SECRET + "-second";
        associate(SECRET, version("alice", "password"));
        associate(second, version("bob", "password"));
        PaginatedResult<String> page = service.listScramSecrets(cluster.getClusterArn(), 1, null);
        assertEquals(List.of(SECRET), page.items());
        assertNotNull(page.nextToken());
        PaginatedResult<String> last =
            service.listScramSecrets(cluster.getClusterArn(), 1, page.nextToken());
        assertEquals(List.of(second), last.items());
        assertNull(last.nextToken());
    }

    @Test
    void listScramSecretsRejectsPageSizesOutsideOneToOneHundred() {
        // List page sizes must stay within the AWS range of one through one hundred.
        assertThrows(AwsException.class, () -> service.listScramSecrets(cluster.getClusterArn(), 0, null));
        assertThrows(AwsException.class, () -> service.listScramSecrets(cluster.getClusterArn(), 101, null));
    }

    @Test
    void listScramSecretsRejectsMalformedNextToken() {
        // Invalid pagination tokens must fail instead of silently restarting the listing.
        assertThrows(AwsException.class, () -> service.listScramSecrets(cluster.getClusterArn(), 1, "!"));
    }

    @Test
    void listScramSecretsRejectsMissingCluster() {
        // Listing an unknown cluster must fail rather than appear to return an empty membership.
        assertThrows(AwsException.class, () -> service.listScramSecrets("missing", null, null));
    }

    @Test
    void reconcileUpdatesPasswordAndSkipsUnchangedVersion() {
        // Reconciliation applies each new password version once and skips already applied versions.
        associate(SECRET, version("alice", "old-password"));
        clearInvocations(redpanda);
        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);
        verifyNoInteractions(redpanda);

        SecretVersion changed = version("alice", "new-password");
        setSecretValue(SECRET, changed);
        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);
        verify(redpanda, times(1)).createUser(cluster, "alice", "new-password");
        assertEquals(changed.getVersionId(), association(SECRET).getAppliedVersionId());
    }

    @Test
    void passwordRotationWritesOnlyCompletedState() {
        // Password-only rotation persists completion once without an extra intent write.
        associate(SECRET, version("alice", "password"));
        clearInvocations(associations);
        SecretVersion changed = version("alice", "new-password");
        setSecretValue(SECRET, changed);

        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);

        verify(associations, times(1)).putForAccount(eq(ACCOUNT), eq(cluster.getClusterArn()), any());
        assertEquals(changed.getVersionId(), association(SECRET).getAppliedVersionId());
    }

    @Test
    void usernameRotationReplacesOldUserAndDisassociationDeletesTheNewUser() {
        // Username rotation removes the old broker user and makes later cleanup target the new one.
        associate(SECRET, version("alice", "password"));
        setSecretValue(SECRET, version("bob", "new-password"));
        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);

        assertEquals("bob", association(SECRET).getUsername());
        verify(redpanda).deleteUser(cluster, "alice");
        verify(redpanda).createUser(cluster, "bob", "new-password");
        assertTrue(
            service.batchDisassociateScramSecret(cluster.getClusterArn(), List.of(SECRET))
                .isEmpty());
        verify(redpanda).deleteUser(cluster, "bob");
        assertTrue(state().getAssociations().isEmpty());
    }

    @Test
    void associationIsRejectedWhenRotatedContentIsInvalid() {
        associate(SECRET, version("alice", "original"));
        clearInvocations(redpanda);
        SecretVersion invalid = version("unused", "unused");
        invalid.setSecretString("{}");
        setSecretValue(SECRET, invalid);
        clearInvocations(secrets);

        List<Map<String, String>> failures =
            service.batchAssociateScramSecret(cluster.getClusterArn(), List.of(SECRET));

        assertEquals(1, failures.size());
        assertEquals("InvalidSecretArn", failures.getFirst().get("errorCode"));
        verify(secrets).describeSecret(SECRET, "us-east-1");
        verify(secrets, never()).batchGetSecretValue(any(), anyString());
        verifyNoInteractions(redpanda);
    }

    @Test
    void reconciliationPreservesAppliedCredentialsWhenRotatedContentIsInvalid() {
        SecretVersion original = version("alice", "original");
        associate(SECRET, original);
        clearInvocations(redpanda);
        SecretVersion invalid = version("unused", "unused");
        invalid.setSecretString("{}");
        setSecretValue(SECRET, invalid);

        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);

        assertEquals("alice", association(SECRET).getUsername());
        assertEquals(original.getVersionId(), association(SECRET).getAppliedVersionId());
        verifyNoInteractions(redpanda);
    }

    @Test
    void repeatedReconciliationFailuresLogAtDebugUntilErrorChangesOrRecovery() {
        associate(SECRET, version("alice", "original"));
        setSecretReadError("AccessDeniedException", "Denied.");

        List<LogRecord> logs = LogCapture.capture(MskScramSecretsService.class, () -> {
            service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);
            service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);
            setSecretReadError("AccessDeniedException", "Different denial.");
            service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);
            setSecretReadError("InvalidRequestException", "Invalid request.");
            service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);
        });
        assertEquals(List.of("WARN", "DEBUG", "WARN", "WARN"), logLevels(logs));

        setSecretValue(SECRET, version("alice", "recovered"));
        logs = LogCapture.capture(MskScramSecretsService.class, () -> service.reconcileCluster(cluster.getClusterArn(), ACCOUNT));
        assertTrue(logs.isEmpty());
        verify(redpanda).createUser(cluster, "alice", "recovered");

        setSecretReadError("InvalidRequestException", "Invalid request.");
        logs = LogCapture.capture(MskScramSecretsService.class, () -> service.reconcileCluster(cluster.getClusterArn(), ACCOUNT));
        assertEquals(List.of("WARN"), logLevels(logs));
    }

    private List<String> logLevels(List<LogRecord> logs) {
        return logs.stream().map(logRecord -> logRecord.getLevel().getName()).toList();
    }

    @Test
    void invalidRequestsMalformedUpdatesAndAccessDenialsPreserveAppliedVersion() {
        // Unreadable or malformed updates are not deletion signals and must preserve the applied version.
        SecretVersion original = version("alice", "password");
        associate(SECRET, original);
        clearInvocations(redpanda);
        setSecretReadError("InvalidRequestException", "The requested operation is not valid for this secret.");
        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);
        SecretVersion invalid = new SecretVersion();
        invalid.setVersionId("invalid");
        invalid.setSecretString("{}");
        setSecretValue(SECRET, invalid);
        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);
        setSecretReadError("AccessDeniedException", "Denied.");
        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);

        assertEquals(original.getVersionId(), association(SECRET).getAppliedVersionId());
        verifyNoInteractions(redpanda);
    }

    @Test
    void metadataAccessDenialPreservesWorkingCredentials() {
        // Metadata access denial must not revoke credentials that are already working.
        SecretVersion original = version("alice", "password");
        associate(SECRET, original);
        doThrow(new AwsException("AccessDeniedException", "Denied.", 403))
            .when(secrets)
            .describeSecret(SECRET, "us-east-1");
        clearInvocations(redpanda);

        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);

        assertFalse(association(SECRET).isCredentialsRevoked());
        assertEquals(original.getVersionId(), association(SECRET).getAppliedVersionId());
        verifyNoInteractions(redpanda);
    }

    @Test
    void missingAwsCurrentStagePreservesAssociationAndAppliedCredentials() {
        associate(SECRET, version("alice", "password"));
        clearInvocations(redpanda);

        Secret secret = new Secret();
        secret.setVersions(Map.of());
        doReturn(secret).when(secrets).describeSecret(SECRET, "us-east-1");

        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);

        assertNotNull(association(SECRET));
        assertFalse(association(SECRET).isCredentialsRevoked());
        verifyNoInteractions(redpanda);
    }

    @Test
    void brokerResourceNotFoundDoesNotTreatSecretAsDeleted() {
        // Broker not-found errors must not be mistaken for a deleted Secrets Manager secret.
        associate(SECRET, version("alice", "old-password"));
        SecretVersion changed = version("alice", "new-password");
        setSecretValue(SECRET, changed);
        doThrow(new AwsException("ResourceNotFoundException", "Broker user not found.", 404))
            .when(redpanda)
            .createUser(cluster, "alice", "new-password");

        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);

        assertNotNull(association(SECRET));
        assertTrue(state().getAssociations().containsKey(SECRET));
        verify(redpanda, never()).deleteUser(any(), anyString());
    }

    @Test
    void brokerFailureDoesNotMarkVersionAppliedAndIsRetried() {
        // Failed broker updates leave the old applied version intact so reconciliation can retry.
        SecretVersion original = version("alice", "old-password");
        associate(SECRET, original);
        SecretVersion changed = version("alice", "new-password");
        setSecretValue(SECRET, changed);
        doThrow(new AwsException("ServiceUnavailableException", "Broker unavailable.", 503))
            .when(redpanda)
            .createUser(cluster, "alice", "new-password");
        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);
        assertEquals(original.getVersionId(), association(SECRET).getAppliedVersionId());
        doNothing().when(redpanda).createUser(cluster, "alice", "new-password");
        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);
        assertEquals(changed.getVersionId(), association(SECRET).getAppliedVersionId());
    }

    @Test
    void reconcileUsesOwningAccountAndRegionWithoutRequestContext() {
        // Background reconciliation resolves credentials and storage using the resource owner's context.
        String account = "111111111111";
        String region = "eu-west-1";
        String arn = AwsArnUtils.Arn.of("kafka", region, account, "cluster/foreign/id").toString();
        String secretArn =
            AwsArnUtils.Arn.of("secretsmanager", region, account, "secret:foreign").toString();
        MskCluster foreign = new MskCluster(arn, "foreign", "3.6.0");
        foreign.setState(ClusterState.ACTIVE);
        foreign.setContainerId("foreign-broker");
        MskScramAssociations foreignAssociations = new MskScramAssociations();
        foreignAssociations.getAssociations().put(secretArn, new ScramSecretAssociation());
        clusters.putForAccount(account, arn, foreign);
        associations.putForAccount(account, arn, foreignAssociations);
        setSecretValue(secretArn, version("foreign", "password"));

        service.reconcileCluster(arn, account);

        verify(secrets).describeSecret(secretArn, region);
        verify(redpanda).createUser(foreign, "foreign", "password");
        assertTrue(clusters.get(arn).isEmpty());
        assertEquals(
            "foreign",
            associations
                .getForAccount(account, arn)
                .orElseThrow()
                .getAssociations()
                .get(secretArn)
                .getUsername());
    }

    @Test
    void permanentDeletionRevokesCredentialsButRetainsMembershipAndRetriesBrokerFailure() {
        // Deleted secrets retain membership, with revocation recorded only after broker cleanup succeeds.
        SecretVersion original = version("alice", "password");
        associate(SECRET, original);
        clearInvocations(redpanda, secrets, associations);
        doThrow(
            new AwsException(
                "ResourceNotFoundException",
                "Secrets Manager can't find the specified secret.",
                400))
            .when(secrets)
            .describeSecret(SECRET, "us-east-1");
        doThrow(new AwsException("ServiceUnavailableException", "Broker unavailable.", 503))
            .when(redpanda)
            .deleteUser(cluster, "alice");

        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);

        assertFalse(association(SECRET).isCredentialsRevoked());
        assertEquals(original.getVersionId(), association(SECRET).getAppliedVersionId());
        verify(associations, never()).putForAccount(anyString(), anyString(), any());
        doNothing().when(redpanda).deleteUser(cluster, "alice");
        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);

        assertTrue(association(SECRET).isCredentialsRevoked());
        assertNull(association(SECRET).getAppliedVersionId());
        assertNull(association(SECRET).getAppliedContainerId());
        assertEquals(
            List.of(SECRET),
            service.listScramSecrets(cluster.getClusterArn(), null, null).items());
        verify(secrets, never()).batchGetSecretValue(any(), anyString());
        verify(associations, times(1))
            .putForAccount(eq(ACCOUNT), eq(cluster.getClusterArn()), any());
        clearInvocations(redpanda, associations);

        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);

        verifyNoInteractions(redpanda);
        verify(associations, never()).putForAccount(anyString(), anyString(), any());
        assertTrue(service.batchDisassociateScramSecret(cluster.getClusterArn(), List.of(SECRET)).isEmpty());
        assertTrue(state().getAssociations().isEmpty());
        verifyNoInteractions(redpanda);
    }

    @Test
    void scheduledDeletionRevokesCredentialsAndRestorationReappliesUnchangedVersion() {
        // Scheduled deletion revokes credentials, and restoration reapplies them even without a new version.
        SecretVersion original = version("alice", "password");
        associate(SECRET, original);
        scheduleDeletion(SECRET);
        clearInvocations(redpanda, secrets, associations);

        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);

        assertTrue(association(SECRET).isCredentialsRevoked());
        assertEquals(
            List.of(SECRET),
            service.listScramSecrets(cluster.getClusterArn(), null, null).items());
        verify(redpanda, times(1)).deleteUser(cluster, "alice");
        verify(redpanda, never()).createUser(any(), anyString(), anyString());
        verify(secrets, never()).batchGetSecretValue(any(), anyString());
        verify(associations, times(1))
            .putForAccount(eq(ACCOUNT), eq(cluster.getClusterArn()), any());

        setSecretValue(SECRET, original);
        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);

        assertFalse(association(SECRET).isCredentialsRevoked());
        assertEquals(original.getVersionId(), association(SECRET).getAppliedVersionId());
        assertEquals(cluster.getContainerId(), association(SECRET).getAppliedContainerId());
        verify(redpanda, times(1)).createUser(cluster, "alice", "password");
        verify(associations, times(2))
            .putForAccount(eq(ACCOUNT), eq(cluster.getClusterArn()), any());
    }

    @Test
    void revokedAssociationSurvivesRestartWithoutRepeatedDeletionOrStorageWrites()
        throws Exception {
        // Persisted revocation survives restart without repeated cleanup and still permits restoration.
        SecretVersion original = version("alice", "password");
        associate(SECRET, original);
        scheduleDeletion(SECRET);
        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);
        MskScramAssociations restored =
            mapper.readValue(mapper.writeValueAsString(state()), MskScramAssociations.class);
        associations.putForAccount(ACCOUNT, cluster.getClusterArn(), restored);
        MskScramSecretsService restarted =
            new MskScramSecretsService(
                storageFactory, config, clusterService, redpanda, secrets, kms, mapper);
        clearInvocations(redpanda, associations);

        restarted.reconcileCluster(cluster.getClusterArn(), ACCOUNT);

        assertTrue(association(SECRET).isCredentialsRevoked());
        assertEquals(
            List.of(SECRET),
            restarted.listScramSecrets(cluster.getClusterArn(), null, null).items());
        verifyNoInteractions(redpanda);
        verify(associations, never()).putForAccount(anyString(), anyString(), any());

        setSecretValue(SECRET, original);
        restarted.reconcileCluster(cluster.getClusterArn(), ACCOUNT);

        assertFalse(association(SECRET).isCredentialsRevoked());
        assertEquals(original.getVersionId(), association(SECRET).getAppliedVersionId());
        verify(redpanda).createUser(cluster, "alice", "password");
    }

    @Test
    void failedRevocationIsRetriedAfterRestart() throws Exception {
        // Restart must preserve an unfinished revocation so broker cleanup is retried once successfully.
        associate(SECRET, version("alice", "password"));
        scheduleDeletion(SECRET);
        doThrow(new AwsException("ServiceUnavailableException", "Broker unavailable.", 503))
            .when(redpanda)
            .deleteUser(cluster, "alice");
        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);
        MskScramAssociations restored =
            mapper.readValue(mapper.writeValueAsString(state()), MskScramAssociations.class);
        associations.putForAccount(ACCOUNT, cluster.getClusterArn(), restored);
        MskScramSecretsService restarted =
            new MskScramSecretsService(
                storageFactory, config, clusterService, redpanda, secrets, kms, mapper);
        doNothing().when(redpanda).deleteUser(cluster, "alice");
        clearInvocations(redpanda);

        restarted.reconcileCluster(cluster.getClusterArn(), ACCOUNT);

        assertTrue(association(SECRET).isCredentialsRevoked());
        verify(redpanda, times(1)).deleteUser(cluster, "alice");
    }

    @Test
    void failedRestorationRemainsRetryable() {
        // Failed credential restoration keeps revocation state intact until broker creation succeeds.
        SecretVersion original = version("alice", "password");
        associate(SECRET, original);
        scheduleDeletion(SECRET);
        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);
        setSecretValue(SECRET, original);
        doThrow(new AwsException("ServiceUnavailableException", "Broker unavailable.", 503))
            .when(redpanda)
            .createUser(cluster, "alice", "password");

        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);

        assertTrue(association(SECRET).isCredentialsRevoked());
        assertNull(association(SECRET).getAppliedVersionId());
        doNothing().when(redpanda).createUser(cluster, "alice", "password");
        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);
        assertFalse(association(SECRET).isCredentialsRevoked());
        assertEquals(original.getVersionId(), association(SECRET).getAppliedVersionId());
    }

    @Test
    void deletionAfterMetadataReadIsHandledOnNextReconciliation() {
        SecretVersion original = version("alice", "password");
        associate(SECRET, original);
        Secret metadata = secrets.describeSecret(SECRET, "us-east-1");
        doReturn(metadata)
            .doThrow(new AwsException("ResourceNotFoundException", "Secret deleted.", 400))
            .when(secrets)
            .describeSecret(SECRET, "us-east-1");

        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);

        assertFalse(association(SECRET).isCredentialsRevoked());
        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);

        assertTrue(association(SECRET).isCredentialsRevoked());
        assertEquals(
            List.of(SECRET),
            service.listScramSecrets(cluster.getClusterArn(), null, null).items());
    }

    private static Stream<Arguments> sharedUsernameAssociations() {
        return Stream.of(
            Arguments.of(Named.of("same password, sequential", false), false, "password"),
            Arguments.of(Named.of("same password, reversed sequential", false), true, "password"),
            Arguments.of(Named.of("same password, batch", true), false, "password"),
            Arguments.of(Named.of("same password, reversed batch", true), true, "password"),
            Arguments.of(Named.of("different passwords, sequential", false), false, "another-password"),
            Arguments.of(Named.of("different passwords, reversed sequential", false), true, "another-password"),
            Arguments.of(Named.of("different passwords, batch", true), false, "another-password"),
            Arguments.of(Named.of("different passwords, reversed batch", true), true, "another-password")
        );
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("sharedUsernameAssociations")
    void associationAcceptsSharedUsernameRegardlessOfPasswordAndOrder(
        boolean batch, boolean reversed, String secondPassword) {
        // Distinct secrets may share a broker username regardless of passwords, ordering, or batching.
        String second = SECRET + "-second";
        SecretVersion firstVersion = version("alice", "password");
        SecretVersion secondVersion = version("alice", secondPassword);
        setSecretValue(SECRET, firstVersion);
        setSecretValue(second, secondVersion);
        List<String> ordered = reversed ? List.of(second, SECRET) : List.of(SECRET, second);

        if (batch) {
            assertTrue(
                service.batchAssociateScramSecret(cluster.getClusterArn(), ordered).isEmpty());
        } else {
            for (String arn : ordered) {
                assertTrue(
                    service.batchAssociateScramSecret(cluster.getClusterArn(), List.of(arn))
                        .isEmpty());
            }
        }

        assertEquals(
            ordered.stream().sorted().toList(),
            service.listScramSecrets(cluster.getClusterArn(), null, null).items().stream()
                .sorted()
                .toList());
        assertEquals("alice", association(SECRET).getUsername());
        assertEquals("alice", association(second).getUsername());
        assertEquals(firstVersion.getVersionId(), association(SECRET).getAppliedVersionId());
        assertEquals(secondVersion.getVersionId(), association(second).getAppliedVersionId());
        verify(redpanda, times("password".equals(secondPassword) ? 2 : 1))
            .createUser(cluster, "alice", "password");
        verify(redpanda, times("password".equals(secondPassword) ? 2 : 1))
            .createUser(cluster, "alice", secondPassword);
    }

    @Test
    void disassociationInvalidatesSharedUserAndReconciliationReappliesUnchangedSecret() {
        // Deleting a shared broker user invalidates surviving associations so unchanged credentials reapply.
        String second = SECRET + "-second";
        SecretVersion surviving = version("alice", "surviving-password");
        associate(SECRET, version("alice", "password"));
        associate(second, surviving);
        clearInvocations(redpanda);

        assertTrue(
            service.batchDisassociateScramSecret(cluster.getClusterArn(), List.of(SECRET))
                .isEmpty());

        verify(redpanda).deleteUser(cluster, "alice");
        assertNull(association(SECRET));
        assertEquals("alice", association(second).getUsername());
        assertFalse(association(second).isDisassociating());
        assertNull(association(second).getAppliedVersionId());
        assertNull(association(second).getAppliedContainerId());
        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);

        verify(redpanda, times(1)).createUser(cluster, "alice", "surviving-password");
        assertEquals(surviving.getVersionId(), association(second).getAppliedVersionId());
        assertEquals(cluster.getContainerId(), association(second).getAppliedContainerId());
    }

    @Test
    void disassociationPersistsRemovalAndSharedUserInvalidationInTwoWrites() {
        // Disassociation persists cleanup intent and then the removal with shared-user invalidation.
        String second = SECRET + "-second";
        associate(SECRET, version("alice", "password"));
        associate(second, version("alice", "surviving-password"));
        clearInvocations(associations);

        assertTrue(
            service.batchDisassociateScramSecret(cluster.getClusterArn(), List.of(SECRET))
                .isEmpty());

        verify(associations, times(2))
            .putForAccount(eq(ACCOUNT), eq(cluster.getClusterArn()), any());
        assertNull(association(SECRET));
        assertNull(association(second).getAppliedVersionId());
        assertNull(association(second).getAppliedContainerId());
    }

    @Test
    void usernameRotationDoesNotReapplyUnrelatedUsers() {
        String unrelated = SECRET + "-unrelated";
        SecretVersion other = version("charlie", "other-password");
        associate(SECRET, version("alice", "password"));
        associate(unrelated, other);
        clearInvocations(redpanda);

        setSecretValue(SECRET, version("bob", "new-password"));
        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);

        assertEquals(other.getVersionId(), association(unrelated).getAppliedVersionId());
        assertEquals(cluster.getContainerId(), association(unrelated).getAppliedContainerId());
        verify(redpanda).createUser(cluster, "bob", "new-password");
        verify(redpanda, never()).createUser(cluster, "charlie", "other-password");
    }

    @Test
    void disassociationAfterRenameOnlyDeletesTheRelevantUsername() {
        // After shared secrets diverge in username, each disassociation deletes only its own broker user.
        String second = SECRET + "-second";
        associate(SECRET, version("alice", "password"));
        associate(second, version("alice", "shared-password"));
        setSecretValue(second, version("bob", "renamed-password"));
        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);
        clearInvocations(redpanda);

        assertTrue(
            service.batchDisassociateScramSecret(cluster.getClusterArn(), List.of(SECRET))
                .isEmpty());
        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);

        assertEquals(
            List.of(second),
            service.listScramSecrets(cluster.getClusterArn(), null, null).items());
        assertEquals("bob", association(second).getUsername());
        verify(redpanda).deleteUser(cluster, "alice");
        verify(redpanda, never()).deleteUser(cluster, "bob");
        verify(redpanda, never()).createUser(any(), anyString(), anyString());
        clearInvocations(redpanda);

        assertTrue(
            service.batchDisassociateScramSecret(cluster.getClusterArn(), List.of(second))
                .isEmpty());

        assertTrue(service.listScramSecrets(cluster.getClusterArn(), null, null).items().isEmpty());
        verify(redpanda).deleteUser(cluster, "bob");
        verify(redpanda, never()).deleteUser(cluster, "alice");
    }

    @Test
    void failedUserDeletionDoesNotInvalidateSharedAssociations() {
        // Failed broker deletion leaves shared credentials applied while retaining the cleanup intent.
        String second = SECRET + "-second";
        SecretVersion surviving = version("alice", "surviving-password");
        associate(SECRET, version("alice", "password"));
        associate(second, surviving);
        doThrow(new AwsException("ServiceUnavailableException", "Broker unavailable.", 503))
            .when(redpanda)
            .deleteUser(cluster, "alice");

        assertEquals(
            1,
            service.batchDisassociateScramSecret(cluster.getClusterArn(), List.of(SECRET))
                .size());

        assertEquals(surviving.getVersionId(), association(second).getAppliedVersionId());
        assertEquals(cluster.getContainerId(), association(second).getAppliedContainerId());
        assertTrue(association(SECRET).isDisassociating());
    }

    @Test
    void revocationRestoresSharedUserAndDisassociationDoesNotDeleteItAgain() {
        // Revocation restores surviving shared credentials, and later removal must not delete them again.
        String second = SECRET + "-second";
        SecretVersion surviving = version("alice", "surviving-password");
        associate(SECRET, version("alice", "password"));
        associate(second, surviving);
        scheduleDeletion(SECRET);
        clearInvocations(redpanda);

        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);

        assertTrue(association(SECRET).isCredentialsRevoked());
        assertEquals(surviving.getVersionId(), association(second).getAppliedVersionId());
        assertEquals(
            List.of(SECRET, second),
            service.listScramSecrets(cluster.getClusterArn(), null, null).items());
        verify(redpanda, times(1)).deleteUser(cluster, "alice");
        verify(redpanda, times(1)).createUser(cluster, "alice", "surviving-password");
        clearInvocations(redpanda);

        assertTrue(
            service.batchDisassociateScramSecret(cluster.getClusterArn(), List.of(SECRET))
                .isEmpty());
        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);

        assertEquals(surviving.getVersionId(), association(second).getAppliedVersionId());
        assertEquals(
            List.of(second),
            service.listScramSecrets(cluster.getClusterArn(), null, null).items());
        verifyNoInteractions(redpanda);
    }

    @Test
    void failedRevocationDoesNotInvalidateSharedUser() {
        // A failed revocation must not invalidate or rewrite still-working shared-user state.
        String second = SECRET + "-second";
        SecretVersion surviving = version("alice", "surviving-password");
        associate(SECRET, version("alice", "password"));
        associate(second, surviving);
        scheduleDeletion(SECRET);
        doThrow(new AwsException("ServiceUnavailableException", "Broker unavailable.", 503))
            .when(redpanda)
            .deleteUser(cluster, "alice");
        clearInvocations(associations);

        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);

        assertFalse(association(SECRET).isCredentialsRevoked());
        assertEquals(surviving.getVersionId(), association(second).getAppliedVersionId());
        verify(associations, never()).putForAccount(anyString(), anyString(), any());
    }

    @Test
    void restoredSecretWithNewUsernameDoesNotDeleteSurvivingSharedUser() {
        // Restoring a revoked secret under a new username must preserve the surviving shared user.
        String second = SECRET + "-second";
        SecretVersion surviving = version("alice", "surviving-password");
        associate(SECRET, version("alice", "password"));
        associate(second, surviving);
        scheduleDeletion(SECRET);
        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);
        clearInvocations(redpanda);
        doReturn(new Secret()).when(secrets).describeSecret(SECRET, "us-east-1");
        setSecretValue(SECRET, version("bob", "restored-password"));

        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);

        assertFalse(association(SECRET).isCredentialsRevoked());
        assertEquals("bob", association(SECRET).getUsername());
        assertEquals(surviving.getVersionId(), association(second).getAppliedVersionId());
        verify(redpanda).createUser(cluster, "bob", "restored-password");
        verify(redpanda, never()).deleteUser(any(), anyString());
    }

    @Test
    void associationsPersistSeparatelyWithoutPasswords() throws Exception {
        // Separate association storage survives restart without persisting passwords or reapplying credentials.
        associate(SECRET, version("alice", "sensitive-password"));
        String json = mapper.writeValueAsString(state());
        assertFalse(json.contains("sensitive-password"));
        assertFalse(mapper.readTree(mapper.writeValueAsString(cluster)).has("scramSecrets"));
        MskScramAssociations restored = mapper.readValue(json, MskScramAssociations.class);
        associations.putForAccount(ACCOUNT, cluster.getClusterArn(), restored);
        MskScramSecretsService restarted =
            new MskScramSecretsService(
                storageFactory, config, clusterService, redpanda, secrets, kms, mapper);

        assertEquals(
            List.of(SECRET),
            restarted.listScramSecrets(cluster.getClusterArn(), null, null).items());
        assertEquals("alice", restored.getAssociations().get(SECRET).getUsername());
        clearInvocations(redpanda);
        restarted.reconcileCluster(cluster.getClusterArn(), ACCOUNT);
        verifyNoInteractions(redpanda);
    }

    @Test
    void recreatedBrokerReceivesCurrentCredentialsOnlyAfterClusterBecomesActive() {
        // A replacement broker receives existing credentials only once the cluster is active.
        associate(SECRET, version("alice", "password"));
        clearInvocations(redpanda);
        cluster.setContainerId("broker-2");
        cluster.setState(ClusterState.CREATING);
        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);
        verifyNoInteractions(redpanda);
        cluster.setState(ClusterState.ACTIVE);
        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);
        verify(redpanda).createUser(cluster, "alice", "password");
        assertEquals("broker-2", association(SECRET).getAppliedContainerId());
    }

    @Test
    void reconciliationRemovesOrphansFromTheirOwningAccount() {
        // Orphan cleanup uses the owning account and must not remove another account's valid associations.
        String account = "111111111111";
        String arn =
            AwsArnUtils.Arn.of("kafka", "eu-west-1", account, "cluster/deleted/id").toString();
        MskScramAssociations orphan = new MskScramAssociations();
        orphan.getAssociations().put(SECRET, new ScramSecretAssociation());
        associations.putForAccount(account, arn, orphan);
        associate(SECRET, version("alice", "password"));
        clearInvocations(redpanda, secrets);

        service.reconcileCluster(arn, account);

        assertTrue(associations.getForAccount(account, arn).isEmpty());
        assertNotNull(association(SECRET));
        verifyNoInteractions(redpanda);
    }

    @Test
    void mockModeTracksAndReconcilesAssociationsWithoutBrokerCalls() {
        // Mock mode maintains association lifecycle state while suppressing all broker operations.
        when(config.services().msk().mock()).thenReturn(true);
        associate(SECRET, version("alice", "password"));
        setSecretValue(SECRET, version("bob", "password"));
        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);
        assertEquals("bob", association(SECRET).getUsername());
        service.batchDisassociateScramSecret(cluster.getClusterArn(), List.of(SECRET));
        verifyNoInteractions(redpanda);
    }

    @Test
    void reconcileSkipsServerlessClusterWithStaleAssociation() {
        // Stale SCRAM state on a serverless cluster must not trigger credential reads or broker calls.
        String serverlessArn =
            AwsArnUtils.Arn.of("kafka", "us-east-1", ACCOUNT, "cluster/serverless/id")
                .toString();
        MskCluster serverless = new MskCluster(serverlessArn, "serverless", "3.6.0");
        serverless.setClusterType("SERVERLESS");
        serverless.setState(ClusterState.ACTIVE);
        serverless.setContainerId("unrelated-broker");
        clusters.putForAccount(ACCOUNT, serverlessArn, serverless);
        MskScramAssociations staleAssociations = new MskScramAssociations();
        staleAssociations.getAssociations().put(SECRET, new ScramSecretAssociation());
        associations.putForAccount(ACCOUNT, serverlessArn, staleAssociations);
        clearInvocations(secrets, redpanda);

        service.reconcileCluster(serverlessArn, ACCOUNT);

        verifyNoInteractions(secrets, redpanda);
    }

    @Test
    void failedClusterDeletionRetainsScramAssociationState() {
        // Failed cluster deletion retains both the cluster and its recoverable SCRAM association state.
        associate(SECRET, version("alice", "password"));
        doThrow(new AwsException("ServiceUnavailableException", "Broker unavailable.", 503))
            .when(redpanda)
            .stopContainer(cluster);

        assertThrows(
            AwsException.class, () -> clusterService.deleteCluster(cluster.getClusterArn()));
        service.reconcileCluster(cluster.getClusterArn(), ACCOUNT);

        assertNotNull(association(SECRET));
        assertTrue(clusters.get(cluster.getClusterArn()).isPresent());
    }
}
