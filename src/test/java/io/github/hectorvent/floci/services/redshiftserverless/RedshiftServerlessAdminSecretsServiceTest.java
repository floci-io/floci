package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshift.model.Endpoint;
import io.github.hectorvent.floci.services.redshiftserverless.model.Namespace;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedshiftServerlessAdminSecretsServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "123456789012";
    private static final String NAME = "managed-ns";
    private static final String WORKGROUP = "managed-wg";
    private static final String STORAGE_KEY = REGION + "::" + NAME;
    private static final String SECRET_ARN = "arn:aws:secretsmanager:us-east-1:123456789012:secret:managed-admin-abcdef";
    private static final String SECRET_KEY = "arn:aws:kms:us-east-1:123456789012:key/admin-secret-key";
    private static final String ORIGINAL_PASSWORD = "Original123!";
    private static final String GENERATED_PASSWORD = "Generated123!";

    private RedshiftServerlessService service;
    private AccountAwareStorageBackend<Namespace> namespaces;
    private RedshiftServerlessRuntime runtime;
    private SecretsManagerService secrets;
    private ObjectMapper mapper;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setup() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        namespaces = spy(AccountAwareStorageBackend.inMemory(ACCOUNT));
        when(storageFactory.create(eq("redshiftserverless"), eq("redshiftserverless-namespaces.json"),
                any(TypeReference.class))).thenReturn((AccountAwareStorageBackend) namespaces);
        when(storageFactory.create(eq("redshiftserverless"), eq("redshiftserverless-workgroups.json"),
                any(TypeReference.class))).thenReturn(AccountAwareStorageBackend.inMemory(ACCOUNT));
        when(storageFactory.create(eq("redshiftserverless"), eq("redshiftserverless-snapshots.json"),
                any(TypeReference.class))).thenReturn(AccountAwareStorageBackend.inMemory(ACCOUNT));
        RegionResolver regions = mock(RegionResolver.class);
        when(regions.getAccountId()).thenReturn(ACCOUNT);
        when(regions.buildArn(eq("redshift-serverless"), eq(REGION), any(String.class)))
                .thenAnswer(invocation -> "arn:aws:redshift-serverless:" + REGION + ":" + ACCOUNT + ":"
                        + invocation.getArgument(2, String.class));
        RedshiftServerlessEndpoints endpoints = mock(RedshiftServerlessEndpoints.class);
        when(endpoints.allocate()).thenReturn(new Endpoint("localhost", 7100));
        runtime = mock(RedshiftServerlessRuntime.class);
        when(runtime.generatePassword()).thenReturn(GENERATED_PASSWORD);
        when(runtime.start(any(String.class), any(String.class), any(String.class), any(String.class),
                any(String.class), any(String.class), any(Endpoint.class), anyBoolean(), any()))
                .thenReturn(new RedshiftServerlessRuntime.Backend("127.0.0.1", 55432));
        secrets = mock(SecretsManagerService.class);
        Secret secret = new Secret();
        secret.setArn(SECRET_ARN);
        secret.setKmsKeyId(SECRET_KEY);
        when(secrets.createSecret(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(secret);
        when(secrets.createSecret(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(secret);
        mapper = new ObjectMapper().findAndRegisterModules();
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        service = new RedshiftServerlessService(storageFactory, regions, endpoints, runtime, config,
                new RedshiftServerlessAdminSecrets(secrets, mapper));
    }

    @Test
    void managedCreateStorageFailureDeletesTheNewSecret() {
        IllegalStateException failure = failStorageWrites();
        assertSame(failure, assertThrows(IllegalStateException.class, this::createManaged));
        verify(secrets).deleteSecret(SECRET_ARN, null, true, REGION);
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.getNamespace(NAME, REGION)).getErrorCode());
    }

    @Test
    void enablingManagementBackendFailureDeletesTheNewSecretAndPreservesNamespace() {
        Namespace previous = createUnmanaged();
        attachWorkgroup();
        AwsException failure = new AwsException("InternalServerException", "Backend unavailable", 500);
        doThrow(failure).when(runtime).changeMasterPassword(ACCOUNT, REGION, WORKGROUP, "admin", "analytics",
                ORIGINAL_PASSWORD, GENERATED_PASSWORD);
        assertSame(failure, assertThrows(AwsException.class, () -> update(null, null, true)));
        verify(secrets).deleteSecret(SECRET_ARN, null, true, REGION);
        assertSame(previous, service.getNamespace(NAME, REGION));
        assertNull(previous.getAdminPasswordSecretArn());
        assertEquals(ORIGINAL_PASSWORD, previous.getAdminUserPassword());
    }

    @Test
    void enablingManagementStorageFailureRestoresBackendAndDeletesTheNewSecret() {
        Namespace previous = createUnmanaged();
        attachWorkgroup();
        IllegalStateException failure = failStorageWrites();
        assertSame(failure, assertThrows(IllegalStateException.class, () -> update(null, null, true)));
        verify(runtime).changeMasterPassword(ACCOUNT, REGION, WORKGROUP, "admin", "analytics",
                ORIGINAL_PASSWORD, GENERATED_PASSWORD);
        verify(runtime).restoreMasterPassword(ACCOUNT, REGION, WORKGROUP, "admin", "analytics", ORIGINAL_PASSWORD);
        verify(secrets).deleteSecret(SECRET_ARN, null, true, REGION);
        assertSame(previous, service.getNamespace(NAME, REGION));
        assertNull(previous.getAdminPasswordSecretArn());
    }

    @Test
    void continuingManagementUsernameChangeUpdatesTheExistingSecret() throws Exception {
        createManaged();
        clearInvocations(secrets);
        Namespace updated = update("renamed_admin", null, null);
        ArgumentCaptor<String> values = ArgumentCaptor.forClass(String.class);
        verify(secrets).putSecretValue(eq(SECRET_ARN), values.capture(), isNull(), isNull(), eq(REGION), any());
        JsonNode value = mapper.readTree(values.getValue());
        assertEquals("renamed_admin", value.path("username").asText());
        assertEquals(GENERATED_PASSWORD, value.path("password").asText());
        assertEquals("analytics", value.path("dbname").asText());
        assertEquals("redshift", value.path("engine").asText());
        assertEquals(SECRET_ARN, updated.getAdminPasswordSecretArn());
        verify(secrets, never()).deleteSecret(any(), any(), anyBoolean(), any());
    }

    @Test
    void continuingManagementStorageFailureRestoresTheOriginalSecretValue() throws Exception {
        Namespace previous = createManaged();
        clearInvocations(secrets);
        IllegalStateException failure = failStorageWrites();
        assertSame(failure, assertThrows(IllegalStateException.class, () -> update("renamed_admin", null, null)));
        ArgumentCaptor<String> values = ArgumentCaptor.forClass(String.class);
        verify(secrets, times(2)).putSecretValue(eq(SECRET_ARN), values.capture(), isNull(), isNull(), eq(REGION), any());
        List<String> writes = values.getAllValues();
        assertEquals("renamed_admin", mapper.readTree(writes.getFirst()).path("username").asText());
        JsonNode restored = mapper.readTree(writes.getLast());
        assertEquals("admin", restored.path("username").asText());
        assertEquals(GENERATED_PASSWORD, restored.path("password").asText());
        assertSame(previous, service.getNamespace(NAME, REGION));
        assertEquals("admin", previous.getAdminUsername());
        verify(secrets, never()).deleteSecret(any(), any(), anyBoolean(), any());
    }

    @Test
    void disablingManagementStorageFailureKeepsTheSecretAndRestoresBackend() {
        Namespace previous = createManaged();
        attachWorkgroup();
        clearInvocations(secrets);
        IllegalStateException failure = failStorageWrites();
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> update(null, ORIGINAL_PASSWORD, false)));
        verify(secrets, never()).deleteSecret(any(), any(), anyBoolean(), any());
        verify(runtime).restoreMasterPassword(ACCOUNT, REGION, WORKGROUP, "admin", "analytics", GENERATED_PASSWORD);
        assertSame(previous, service.getNamespace(NAME, REGION));
        assertEquals(SECRET_ARN, previous.getAdminPasswordSecretArn());
    }

    @Test
    void deletingNamespaceWithWorkgroupDoesNotDeleteTheSecret() {
        createManaged();
        attachWorkgroup();
        clearInvocations(secrets);
        assertEquals("ConflictException", assertThrows(AwsException.class,
                () -> service.deleteNamespace(NAME, REGION)).getErrorCode());
        verify(secrets, never()).deleteSecret(any(), any(), anyBoolean(), any());
        assertEquals(SECRET_ARN, service.getNamespace(NAME, REGION).getAdminPasswordSecretArn());
    }

    @Test
    void secretDeletionFailureRestoresNamespace() {
        Namespace previous = createManaged();
        clearInvocations(namespaces);
        AwsException failure = new AwsException("AccessDeniedException", "Cannot delete secret", 400);
        doThrow(failure).when(secrets).deleteSecret(SECRET_ARN, null, true, REGION);
        assertSame(failure, assertThrows(AwsException.class, () -> service.deleteNamespace(NAME, REGION)));
        assertSame(previous, service.getNamespace(NAME, REGION));
        verify(namespaces).delete(STORAGE_KEY);
        verify(namespaces).put(STORAGE_KEY, previous);
    }

    @Test
    void namespaceDeletionFailureKeepsItsManagedSecret() {
        Namespace previous = createManaged();
        IllegalStateException failure = new IllegalStateException("Namespace deletion failed");
        doThrow(failure).when(namespaces).delete(STORAGE_KEY);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.deleteNamespace(NAME, REGION)));

        verify(secrets, never()).deleteSecret(any(), any(), anyBoolean(), any());
        assertSame(previous, service.getNamespace(NAME, REGION));
    }

    @Test
    void missingSecretDoesNotPreventNamespaceDeletion() {
        createManaged();
        doThrow(new AwsException("ResourceNotFoundException", "Already deleted", 400))
                .when(secrets).deleteSecret(SECRET_ARN, null, true, REGION);
        assertEquals("DELETING", service.deleteNamespace(NAME, REGION).getStatus());
        assertThrows(AwsException.class, () -> service.getNamespace(NAME, REGION));
    }

    @Test
    void namespaceJsonRoundTripAndCopyRetainSecretMetadata() throws Exception {
        Namespace created = createManaged();
        Namespace restored = mapper.readValue(mapper.writeValueAsBytes(created), Namespace.class);
        assertEquals(SECRET_ARN, restored.getAdminPasswordSecretArn());
        assertEquals(SECRET_KEY, restored.getAdminPasswordSecretKmsKeyId());
        assertEquals(GENERATED_PASSWORD, restored.getAdminUserPassword());
        Namespace copy = new Namespace(restored);
        assertEquals(SECRET_ARN, copy.getAdminPasswordSecretArn());
        assertEquals(SECRET_KEY, copy.getAdminPasswordSecretKmsKeyId());
    }

    private Namespace createManaged() {
        return service.createNamespace(NAME, "admin", null, "analytics", null, null, List.of(), List.of(), Map.of(),
                true, SECRET_KEY, REGION);
    }

    private Namespace createUnmanaged() {
        return service.createNamespace(NAME, "admin", ORIGINAL_PASSWORD, "analytics", null, null, List.of(),
                List.of(), Map.of(), REGION);
    }

    private Namespace update(String username, String password, Boolean manage) {
        return service.updateNamespace(NAME, username, password, null, null, null, null, manage, null, REGION);
    }

    private void attachWorkgroup() {
        service.createWorkgroup(WORKGROUP, NAME,
                new WorkgroupSettings(null, null, null, null, null, null, null, null, null, null, null, null), null, REGION);
    }

    private IllegalStateException failStorageWrites() {
        IllegalStateException failure = new IllegalStateException("Namespace write failed");
        doThrow(failure).when(namespaces).put(eq(STORAGE_KEY), any(Namespace.class));
        return failure;
    }
}
