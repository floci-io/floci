package io.floci.aws.services.kms;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.kms.model.KmsCustomKeyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

class KmsCustomKeyStoreRegistryTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private KmsCustomKeyStoreRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new KmsCustomKeyStoreRegistry(AccountAwareStorageBackend.inMemory(ACCOUNT),
                new RegionResolver(REGION, ACCOUNT), XksTestFixtures.credentialCipher());
    }

    @Test
    void createsAccountAndRegionScopedMetadataWithoutPersistingSecret() {
        KmsCustomKeyStore candidate = candidate("primary");

        KmsCustomKeyStore created = registry.create(candidate, "A".repeat(43), REGION);

        assertTrue(created.getCustomKeyStoreId().startsWith("cks-"));
        assertEquals("arn:aws:kms:us-east-1:000000000000:custom-key-store/" + created.getCustomKeyStoreId(),
                created.getCustomKeyStoreArn());
        assertEquals(KmsCustomKeyStore.STATE_DISCONNECTED, created.getConnectionState());
        assertEquals("PUBLIC_ENDPOINT", created.getProxyConnectivity());
        assertNotEquals("A".repeat(43), created.getEncryptedSecretAccessKey());
        assertEquals("A".repeat(43), registry.secretAccessKey(created.getCustomKeyStoreId(), REGION));
        assertEquals(created, registry.require(created.getCustomKeyStoreId(), REGION));
        assertTrue(registry.find(created.getCustomKeyStoreId(), "eu-west-1").isEmpty());
    }

    @Test
    void rejectsDuplicateNamesAndProxyEndpointsWithinRegion() {
        registry.create(candidate("same-name"), "A".repeat(43), REGION);

        AwsException duplicateName = assertThrows(AwsException.class,
                () -> registry.create(candidate("same-name"), "A".repeat(43), REGION));
        assertEquals("CustomKeyStoreNameInUseException", duplicateName.getErrorCode());

        KmsCustomKeyStore duplicateEndpoint = candidate("other-name");
        AwsException duplicateUri = assertThrows(AwsException.class,
                () -> registry.create(duplicateEndpoint, "A".repeat(43), REGION));
        assertEquals("XksProxyUriInUseException", duplicateUri.getErrorCode());
    }

    @Test
    void enforcesTheCustomKeyStoreQuotaPerAccountAndRegion() {
        for (int i = 0; i < 10; i++) {
            KmsCustomKeyStore store = candidate("store-" + i);
            store.setProxyUriEndpoint("https://proxy" + i + ".example.com");
            registry.create(store, "A".repeat(43), REGION);
        }
        KmsCustomKeyStore overQuota = candidate("store-over-quota");
        overQuota.setProxyUriEndpoint("https://proxy-over-quota.example.com");
        AwsException failure = assertThrows(AwsException.class,
                () -> registry.create(overQuota, "A".repeat(43), REGION));
        assertEquals("LimitExceededException", failure.getErrorCode());
    }

    @Test
    void failedConnectionRequiresDisconnectBeforeRetry() {
        String id = registry.create(candidate("retry"), "A".repeat(43), REGION).getCustomKeyStoreId();
        registry.beginConnect(id, REGION);
        KmsCustomKeyStore failed = registry.markFailed(id, REGION,
                "XksProxyIncorrectAuthenticationCredentialException", "XKS_PROXY_ACCESS_DENIED");

        assertEquals(KmsCustomKeyStore.STATE_FAILED, failed.getConnectionState());
        assertEquals("XKS_PROXY_ACCESS_DENIED", failed.getConnectionErrorCode());
        assertEquals("The XKS proxy denied access to the configured credentials",
                failed.getConnectionErrorMessage());
        assertThrows(AwsException.class, () -> registry.beginConnect(id, REGION));

        registry.disconnect(id, REGION);
        assertEquals(KmsCustomKeyStore.STATE_CONNECTING, registry.beginConnect(id, REGION).getConnectionState());
    }

    @Test
    void cannotDeleteConnectedCustomKeyStore() {
        String id = registry.create(candidate("connected"), "A".repeat(43), REGION).getCustomKeyStoreId();
        registry.beginConnect(id, REGION);
        registry.markConnected(id, REGION);

        AwsException failure = assertThrows(AwsException.class, () -> registry.delete(id, REGION));
        assertEquals("CustomKeyStoreInvalidStateException", failure.getErrorCode());

        registry.disconnect(id, REGION);
        registry.delete(id, REGION);
        assertTrue(registry.find(id, REGION).isEmpty());
    }

    @Test
    void metadataUpdatePreservesConnectionStateAndIdentity() {
        KmsCustomKeyStore created = registry.create(candidate("before"), "A".repeat(43), REGION);
        registry.beginConnect(created.getCustomKeyStoreId(), REGION);
        registry.markConnected(created.getCustomKeyStoreId(), REGION);

        KmsCustomKeyStore update = candidate("after");
        update.setCustomKeyStoreId(created.getCustomKeyStoreId());
        KmsCustomKeyStore stored = registry.update(update, null, REGION);

        assertEquals(created.getCustomKeyStoreArn(), stored.getCustomKeyStoreArn());
        assertEquals(created.getCreationDate(), stored.getCreationDate());
        assertEquals(KmsCustomKeyStore.STATE_CONNECTED, stored.getConnectionState());
        assertEquals("after", stored.getCustomKeyStoreName());
    }


    @Test
    void recoversPersistedConnectingStateAfterRegistryRestart() {
        AccountAwareStorageBackend<KmsCustomKeyStore> storage = AccountAwareStorageBackend.inMemory(ACCOUNT);
        RegionResolver resolver = new RegionResolver(REGION, ACCOUNT);
        XksCredentialCipher cipher = XksTestFixtures.credentialCipher();
        KmsCustomKeyStoreRegistry firstProcess = new KmsCustomKeyStoreRegistry(storage, resolver, cipher);
        KmsCustomKeyStore created = firstProcess.create(candidate("interrupted"), "A".repeat(43), REGION);
        firstProcess.beginConnect(created.getCustomKeyStoreId(), REGION);

        KmsCustomKeyStoreRegistry restarted = new KmsCustomKeyStoreRegistry(storage, resolver, cipher);
        KmsCustomKeyStore recovered = restarted.require(created.getCustomKeyStoreId(), REGION);

        assertEquals(KmsCustomKeyStore.STATE_FAILED, recovered.getConnectionState());
        assertEquals("INTERNAL_ERROR", recovered.getConnectionErrorCode());
        assertTrue(recovered.getConnectionErrorMessage().contains("interrupted"));
    }

    @Test
    void recoversInterruptedConnectionsAcrossAllAccounts() {
        AccountAwareStorageBackend<KmsCustomKeyStore> storage = AccountAwareStorageBackend.inMemory(ACCOUNT);
        KmsCustomKeyStore otherAccountStore = candidate("other-account");
        otherAccountStore.setCustomKeyStoreId("cks-other-account");
        otherAccountStore.setRegion(REGION);
        otherAccountStore.setConnectionState(KmsCustomKeyStore.STATE_CONNECTING);
        storage.putForAccount("111122223333", key(REGION, otherAccountStore.getCustomKeyStoreId()), otherAccountStore);

        new KmsCustomKeyStoreRegistry(storage, new RegionResolver(REGION, ACCOUNT),
                XksTestFixtures.credentialCipher());

        KmsCustomKeyStore recovered = storage.scanForAccount("111122223333", ignored -> true).getFirst();
        assertEquals(KmsCustomKeyStore.STATE_FAILED, recovered.getConnectionState());
        assertEquals("INTERNAL_ERROR", recovered.getConnectionErrorCode());
    }

    @Test
    void failedStateWriteDoesNotMutateThePreviouslyStoredObject() {
        AccountAwareStorageBackend<KmsCustomKeyStore> storage = spy(AccountAwareStorageBackend.inMemory(ACCOUNT));
        KmsCustomKeyStoreRegistry testRegistry = new KmsCustomKeyStoreRegistry(storage,
                new RegionResolver(REGION, ACCOUNT),
                XksTestFixtures.credentialCipher());
        KmsCustomKeyStore created = testRegistry.create(candidate("write-failure"), "A".repeat(43), REGION);
        testRegistry.beginConnect(created.getCustomKeyStoreId(), REGION);
        doThrow(new IllegalStateException("simulated persistence failure"))
                .when(storage).put(anyString(), any(KmsCustomKeyStore.class));

        assertThrows(IllegalStateException.class,
                () -> testRegistry.markConnected(created.getCustomKeyStoreId(), REGION));
        assertEquals(KmsCustomKeyStore.STATE_CONNECTING,
                testRegistry.require(created.getCustomKeyStoreId(), REGION).getConnectionState());
    }

    @Test
    void rejectsUpdateWhenConnectHasAlreadyEnteredItsCriticalSection() {
        KmsCustomKeyStore created = registry.create(candidate("concurrent"), "A".repeat(43), REGION);
        registry.beginConnect(created.getCustomKeyStoreId(), REGION);
        KmsCustomKeyStore update = candidate("concurrent-renamed");
        update.setCustomKeyStoreId(created.getCustomKeyStoreId());

        AwsException failure = assertThrows(AwsException.class,
                () -> registry.update(update, null, REGION));
        assertEquals("CustomKeyStoreInvalidStateException", failure.getErrorCode());
        assertEquals("concurrent", registry.require(created.getCustomKeyStoreId(), REGION).getCustomKeyStoreName());
    }

    private static String key(String region, String id) {
        return region + "::" + id;
    }

    private static KmsCustomKeyStore candidate(String name) {
        return XksTestFixtures.candidate(name);
    }
}
