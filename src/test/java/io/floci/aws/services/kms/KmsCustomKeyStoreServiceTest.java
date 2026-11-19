package io.floci.aws.services.kms;

import io.floci.aws.core.common.RequestContext;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.kms.model.KmsCustomKeyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class KmsCustomKeyStoreServiceTest {
    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String ACCESS_KEY = XksTestFixtures.ACCESS_KEY_ID;
    private static final String SECRET_KEY = XksTestFixtures.SECRET_ACCESS_KEY;
    private KmsCustomKeyStoreRegistry registry;
    private XksProxyClient proxy;
    private KmsCustomKeyStoreService service;

    @BeforeEach
    void setUp() {
        registry = new KmsCustomKeyStoreRegistry(AccountAwareStorageBackend.inMemory(ACCOUNT),
                new RegionResolver(REGION, ACCOUNT),
                XksTestFixtures.credentialCipher());
        proxy = mock(XksProxyClient.class);
        service = new KmsCustomKeyStoreService(registry, proxy, mock(RequestContext.class));
    }

    @Test
    void connectFailureIsReportedInDescribeStateAndDoesNotTurnTheOperationIntoAnApiError() {
        KmsCustomKeyStore store = registry.create(candidate(), SECRET_KEY, REGION);
        doThrow(new XksProxyClient.XksProxyException(
                "XksProxyIncorrectAuthenticationCredentialException", "untrusted proxy message", 400))
                .when(proxy).requireHealthy(any(URI.class), eq("/kms/xks/v1"), eq(ACCESS_KEY),
                        eq(SECRET_KEY), eq(REGION), eq("ConnectCustomKeyStore"), anyString());

        service.connect(store.getCustomKeyStoreId(), REGION);

        KmsCustomKeyStore failed = registry.require(store.getCustomKeyStoreId(), REGION);
        assertEquals(KmsCustomKeyStore.STATE_FAILED, failed.getConnectionState());
        assertEquals("XKS_PROXY_ACCESS_DENIED", failed.getConnectionErrorCode());
        assertEquals("The XKS proxy denied access to the configured credentials", failed.getConnectionErrorMessage());
        assertThrows(AwsException.class, () -> service.connect(store.getCustomKeyStoreId(), REGION));

        service.disconnect(store.getCustomKeyStoreId(), REGION);
        assertEquals(KmsCustomKeyStore.STATE_DISCONNECTED,
                registry.require(store.getCustomKeyStoreId(), REGION).getConnectionState());
    }

    @Test
    void connectedCustomKeyStoreAllowsOnlyNameUpdates() {
        KmsCustomKeyStore store = registry.create(candidate(), SECRET_KEY, REGION);
        registry.beginConnect(store.getCustomKeyStoreId(), REGION);
        registry.markConnected(store.getCustomKeyStoreId(), REGION);

        KmsCustomKeyStore renamed = service.update(store.getCustomKeyStoreId(), "renamed",
                null, null, null, null, REGION);
        assertEquals("renamed", renamed.getCustomKeyStoreName());
        assertEquals(KmsCustomKeyStore.STATE_CONNECTED, renamed.getConnectionState());
        assertThrows(AwsException.class, () -> service.update(store.getCustomKeyStoreId(), null,
                "https://new-proxy.example.com", null, null, null, REGION));
        KmsCustomKeyStore pathUpdated = service.update(store.getCustomKeyStoreId(), null,
                null, "/new-prefix/kms/xks/v1", null, null, REGION);
        assertEquals("/new-prefix/kms/xks/v1", pathUpdated.getProxyUriPath());
        assertEquals(KmsCustomKeyStore.STATE_CONNECTED, pathUpdated.getConnectionState());
    }

    @Test
    void describeUsesAccountAndRegionScopedSignedPaginationMarkers() {
        for (int i = 0; i < 4; i++) {
            KmsCustomKeyStore store = candidate();
            store.setCustomKeyStoreName("store-" + i);
            store.setProxyUriEndpoint("https://proxy" + i + ".example.com");
            registry.create(store, SECRET_KEY, REGION);
        }

        KmsCustomKeyStoreService.Page first = service.describe(null, null, null, 2, REGION);
        KmsCustomKeyStoreService.Page second = service.describe(null, null, first.nextMarker(), 2, REGION);

        assertEquals(2, first.items().size());
        assertEquals(2, second.items().size());
        assertNotEquals(first.items().get(1).getCustomKeyStoreId(),
                second.items().get(0).getCustomKeyStoreId());
        assertThrows(AwsException.class, () -> service.describe(null, null, "invalid-marker", 2, REGION));
    }

    private static KmsCustomKeyStore candidate() {
        return XksTestFixtures.candidate("primary");
    }
}
