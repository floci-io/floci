package io.floci.aws.services.kms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.kms.model.KmsCustomKeyStore;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KmsJsonHandlerCustomKeyStoreTest {
    private static final String REGION = "us-east-1";
    private final ObjectMapper mapper = new ObjectMapper();
    private KmsCustomKeyStoreService customStores;
    private KmsJsonHandler handler;

    @BeforeEach
    void setUp() {
        customStores = mock(KmsCustomKeyStoreService.class);
        handler = new KmsJsonHandler(mock(KmsService.class), mapper, mock(RegionResolver.class), customStores);
    }

    @Test
    void createPassesTheXksCredentialsToTheServiceAndReturnsTheId() throws Exception {
        KmsCustomKeyStore store = store();
        when(customStores.create(anyString(), anyString(), anyString(), anyString(), anyString(), eq(REGION)))
                .thenReturn(store);
        JsonNode request = mapper.readTree("""
                {"CustomKeyStoreName":"primary","CustomKeyStoreType":"EXTERNAL_KEY_STORE",
                 "XksProxyConnectivity":"PUBLIC_ENDPOINT","XksProxyUriEndpoint":"https://proxy.example.com",
                 "XksProxyUriPath":"/kms/xks/v1",
                 "XksProxyAuthenticationCredential":{"AccessKeyId":"ABCDEFGHIJKLMNOPQRST",
                 "RawSecretAccessKey":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}}
                """);

        Response response = handler.handle("CreateCustomKeyStore", request, REGION);

        assertEquals("cks-123", ((JsonNode) response.getEntity()).path("CustomKeyStoreId").asText());
        verify(customStores).create("primary", "https://proxy.example.com", "/kms/xks/v1",
                "ABCDEFGHIJKLMNOPQRST", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", REGION);
    }

    @Test
    void describeReturnsOnlyPublicConfigurationAndNeverTheEncryptedSecret() throws Exception {
        KmsCustomKeyStore store = store();
        store.setEncryptedSecretAccessKey("v1:encrypted-secret-envelope");
        when(customStores.describe(isNull(), isNull(), isNull(), isNull(), eq(REGION)))
                .thenReturn(new KmsCustomKeyStoreService.Page(List.of(store), null));

        Response response = handler.handle("DescribeCustomKeyStores", mapper.readTree("{}"), REGION);
        JsonNode body = (JsonNode) response.getEntity();
        String json = body.toString();

        assertEquals("cks-123", body.path("CustomKeyStores").get(0).path("CustomKeyStoreId").asText());
        assertEquals("ABCDEFGHIJKLMNOPQRST", body.path("CustomKeyStores").get(0)
                .path("XksProxyConfiguration").path("AccessKeyId").asText());
        assertTrue(body.path("Truncated").isBoolean());
        assertFalse(json.contains("encrypted-secret-envelope"));
        assertFalse(json.contains("RawSecretAccessKey"));
        assertFalse(json.contains("CustomKeyStoreArn"));
        assertFalse(json.contains("ConnectionErrorMessage"));
    }

    @Test
    void describeUsesTheAwsSingularNameFilter() throws Exception {
        when(customStores.describe(isNull(), eq("primary"), isNull(), isNull(), eq(REGION)))
                .thenReturn(new KmsCustomKeyStoreService.Page(List.of(store()), null));

        handler.handle("DescribeCustomKeyStores", mapper.readTree("{\"CustomKeyStoreName\":\"primary\"}"), REGION);

        verify(customStores).describe(null, "primary", null, null, REGION);
    }

    @Test
    void updateReadsNewCustomKeyStoreName() throws Exception {
        JsonNode request = mapper.readTree("{\"CustomKeyStoreId\":\"cks-123\",\"NewCustomKeyStoreName\":\"renamed\"}");

        handler.handle("UpdateCustomKeyStore", request, REGION);

        verify(customStores).update("cks-123", "renamed", null, null, null, null, REGION);
    }

    @Test
    void routesConnectAndDisconnectOperations() throws Exception {
        JsonNode request = mapper.readTree("{\"CustomKeyStoreId\":\"cks-123\"}");
        handler.handle("ConnectCustomKeyStore", request, REGION);
        handler.handle("DisconnectCustomKeyStore", request, REGION);
        verify(customStores).connect("cks-123", REGION);
        verify(customStores).disconnect("cks-123", REGION);
    }

    private static KmsCustomKeyStore store() {
        KmsCustomKeyStore store = new KmsCustomKeyStore();
        store.setCustomKeyStoreId("cks-123");
        store.setCustomKeyStoreArn("arn:aws:kms:us-east-1:000000000000:custom-key-store/cks-123");
        store.setCustomKeyStoreName("primary");
        store.setAccessKeyId("ABCDEFGHIJKLMNOPQRST");
        store.setProxyUriEndpoint("https://proxy.example.com");
        store.setProxyUriPath("/kms/xks/v1");
        store.setConnectionState(KmsCustomKeyStore.STATE_DISCONNECTED);
        return store;
    }
}
