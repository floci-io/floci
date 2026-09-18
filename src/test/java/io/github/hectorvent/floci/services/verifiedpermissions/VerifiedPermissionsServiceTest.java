package io.github.hectorvent.floci.services.verifiedpermissions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.verifiedpermissions.model.IdempotencyRecord;
import io.github.hectorvent.floci.services.verifiedpermissions.model.IdentitySource;
import io.github.hectorvent.floci.services.verifiedpermissions.model.Policy;
import io.github.hectorvent.floci.services.verifiedpermissions.model.PolicyStore;
import io.github.hectorvent.floci.services.verifiedpermissions.model.PolicyStoreAlias;
import io.github.hectorvent.floci.services.verifiedpermissions.model.PolicyTemplate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VerifiedPermissionsServiceTest {
    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";

    /**
     * A stand-in for the Cedar sidecar that rejects every schema. These tests cover the service's
     * ordering (reject before persist), not Cedar itself; Cedar semantics are covered by
     * {@code VerifiedPermissionsIntegrationTest} against the real image.
     */
    private static HttpServer fakeCedar;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private VerifiedPermissionsService service;

    @BeforeAll
    static void startFakeCedar() throws Exception {
        fakeCedar = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeCedar.createContext("/v1/schema/validate", exchange -> {
            byte[] bytes = "{\"error\":\"entityTypes must be an object\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        fakeCedar.start();
    }

    @AfterAll
    static void stopFakeCedar() {
        fakeCedar.stop(0);
    }

    @BeforeEach
    void setUp() {
        StorageBackend<String, PolicyStore> policyStores = AccountAwareStorageBackend.inMemory(ACCOUNT);
        StorageBackend<String, PolicyStoreAlias> aliases = AccountAwareStorageBackend.inMemory(ACCOUNT);
        StorageBackend<String, Policy> policies = AccountAwareStorageBackend.inMemory(ACCOUNT);
        StorageBackend<String, PolicyTemplate> templates = AccountAwareStorageBackend.inMemory(ACCOUNT);
        StorageBackend<String, IdempotencyRecord> idempotency = AccountAwareStorageBackend.inMemory(ACCOUNT);
        StorageBackend<String, IdentitySource> identitySources = AccountAwareStorageBackend.inMemory(ACCOUNT);
        CedarSidecarManager manager = mock(CedarSidecarManager.class);
        when(manager.ensureReady()).thenReturn("http://127.0.0.1:" + fakeCedar.getAddress().getPort());
        CedarSidecarClient cedarClient = new CedarSidecarClient(manager, objectMapper);
        service = new VerifiedPermissionsService(policyStores, aliases, policies, templates, idempotency,
                identitySources, new RegionResolver(REGION, ACCOUNT), objectMapper, mock(KmsService.class), cedarClient);
    }

    @Test
    void policyStoreLifecycleIsAvailableAtServiceLayer() throws Exception {
        PolicyStore created = service.createPolicyStore(json("""
                {"validationSettings":{"mode":"OFF"},"description":"service-test"}
                """), REGION);

        assertEquals("OFF", created.validationMode());
        assertEquals("service-test", service.getPolicyStore(created.policyStoreId(), REGION).description());

        service.clear();

        AwsException error = assertThrows(AwsException.class,
                () -> service.getPolicyStore(created.policyStoreId(), REGION));
        assertEquals("ResourceNotFoundException", error.getErrorCode());
    }

    @Test
    void invalidCedarSchemaIsRejectedBeforePersistence() throws Exception {
        PolicyStore store = service.createPolicyStore(json("""
                {"validationSettings":{"mode":"OFF"}}
                """), REGION);

        AwsException error = assertThrows(AwsException.class, () -> service.putSchema(json("""
                {"policyStoreId":"%s","definition":{"cedarJson":"{\\"Demo\\":{\\"entityTypes\\":[]}}"}}
                """.formatted(store.policyStoreId())), REGION));

        assertEquals("ValidationException", error.getErrorCode());
        assertNull(service.getPolicyStore(store.policyStoreId(), REGION).schema());
        AwsException missing = assertThrows(AwsException.class,
                () -> service.getSchema(store.policyStoreId(), REGION));
        assertEquals("ResourceNotFoundException", missing.getErrorCode());
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }
}
