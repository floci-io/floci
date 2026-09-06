package io.github.hectorvent.floci.services.verifiedpermissions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class VerifiedPermissionsServiceTest {
    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private VerifiedPermissionsService service;

    @BeforeEach
    void setUp() {
        StorageBackend<String, PolicyStore> policyStores = AccountAwareStorageBackend.inMemory(ACCOUNT);
        StorageBackend<String, PolicyStoreAlias> aliases = AccountAwareStorageBackend.inMemory(ACCOUNT);
        StorageBackend<String, Policy> policies = AccountAwareStorageBackend.inMemory(ACCOUNT);
        StorageBackend<String, PolicyTemplate> templates = AccountAwareStorageBackend.inMemory(ACCOUNT);
        StorageBackend<String, IdempotencyRecord> idempotency = AccountAwareStorageBackend.inMemory(ACCOUNT);
        StorageBackend<String, IdentitySource> identitySources = AccountAwareStorageBackend.inMemory(ACCOUNT);
        service = new VerifiedPermissionsService(policyStores, aliases, policies, templates, idempotency,
                identitySources, new RegionResolver(REGION, ACCOUNT), objectMapper, mock(KmsService.class));
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
