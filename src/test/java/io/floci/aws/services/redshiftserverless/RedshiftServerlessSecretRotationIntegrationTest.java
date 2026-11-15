package io.floci.aws.services.redshiftserverless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.redshift.model.Endpoint;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.redshiftserverless.RedshiftServerlessClient;
import software.amazon.awssdk.services.redshiftserverless.model.Namespace;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.InvalidRequestException;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Rotates the managed admin secret of a namespace through Secrets Manager, with the workgroup backend mocked. */
@QuarkusTest
class RedshiftServerlessSecretRotationIntegrationTest {

    private static final String INITIAL_PASSWORD = "InitialManaged123";
    private static final String ROTATED_PASSWORD = "RotatedManaged456";

    @TestHTTPResource("/")
    URI endpoint;

    @InjectMock
    RedshiftServerlessRuntime runtime;

    @Inject
    ObjectMapper mapper;

    @Inject
    RedshiftServerlessService service;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private RedshiftServerlessClient redshift;
    private SecretsManagerClient secrets;

    @BeforeEach
    void setup() {
        redshift = AwsTestClients.create(RedshiftServerlessClient.builder(), "test", Region.US_EAST_1, endpoint);
        secrets = AwsTestClients.create(SecretsManagerClient.builder(), "test", Region.US_EAST_1, endpoint);
        cleanup.register(redshift::close);
        cleanup.register(secrets::close);
        when(runtime.generatePassword()).thenReturn(INITIAL_PASSWORD, ROTATED_PASSWORD);
        when(runtime.start(any(String.class), any(String.class), any(String.class), any(String.class),
                any(String.class), any(String.class), any(Endpoint.class), anyBoolean(), any()))
                .thenReturn(new RedshiftServerlessRuntime.Backend("127.0.0.1", 55432));
    }

    @Test
    void rotateSecretChangesTheBackendPasswordAndPublishesItInTheSecret() throws Exception {
        String namespaceName = name();
        Namespace namespace = managedNamespaceWithWorkgroup(namespaceName);
        String secretArn = namespace.adminPasswordSecretArn();
        String previousVersion = secrets.getSecretValue(request -> request.secretId(secretArn)).versionId();

        secrets.rotateSecret(request -> request.secretId(secretArn));

        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> ROTATED_PASSWORD.equals(
                passwordOf(secretArn)));
        verify(runtime).changeMasterPassword(anyString(), eq("us-east-1"), anyString(), eq("admin"),
                eq("dev"), eq(INITIAL_PASSWORD), eq(ROTATED_PASSWORD));
        assertEquals(INITIAL_PASSWORD, mapper.readTree(secrets.getSecretValue(request -> request
                .secretId(secretArn).versionStage("AWSPREVIOUS")).secretString()).path("password").asText());
        assertEquals(previousVersion, secrets.getSecretValue(request -> request
                .secretId(secretArn).versionStage("AWSPREVIOUS")).versionId());
    }

    @Test
    void aNamespaceWithoutAWorkgroupIsNotReportedAsRotated() throws Exception {
        Namespace namespace = redshift.createNamespace(request -> request.namespaceName(name())
                .adminUsername("admin").manageAdminPassword(true)).namespace();
        cleanup.register(() -> redshift.deleteNamespace(request -> request.namespaceName(namespace.namespaceName())));
        String secretArn = namespace.adminPasswordSecretArn();

        assertThrows(InvalidRequestException.class, () -> secrets.rotateSecret(request -> request.secretId(secretArn)));

        assertEquals(INITIAL_PASSWORD, passwordOf(secretArn));
        verify(runtime, never()).changeMasterPassword(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    void aBackendFailureLeavesTheSecretAndTheBackendOnTheCurrentPassword() throws Exception {
        Namespace namespace = managedNamespaceWithWorkgroup(name());
        String secretArn = namespace.adminPasswordSecretArn();
        // only the change to the rotated password fails, so the rollback to the current one can run
        doThrow(new IllegalStateException("backend refused the password")).when(runtime).changeMasterPassword(
                anyString(), anyString(), anyString(), anyString(), anyString(), eq(INITIAL_PASSWORD),
                eq(ROTATED_PASSWORD));

        secrets.rotateSecret(request -> request.secretId(secretArn));

        verify(runtime, timeout(10_000)).changeMasterPassword(anyString(), eq("us-east-1"), anyString(),
                eq("admin"), eq("dev"), eq(ROTATED_PASSWORD), eq(INITIAL_PASSWORD));
        assertEquals(INITIAL_PASSWORD, passwordOf(secretArn));
    }

    @Test
    void aNamespaceOfAnotherAccountIsRotatedOnTheRotationThread() {
        RedshiftServerlessClient otherRedshift = AwsTestClients.create(RedshiftServerlessClient.builder(),
                "222222222222", Region.US_EAST_1, endpoint);
        SecretsManagerClient otherSecrets = AwsTestClients.create(SecretsManagerClient.builder(),
                "222222222222", Region.US_EAST_1, endpoint);
        cleanup.register(otherRedshift::close);
        cleanup.register(otherSecrets::close);
        String namespaceName = name();
        String workgroup = name();
        String secretArn = otherRedshift.createNamespace(request -> request.namespaceName(namespaceName)
                .adminUsername("admin").dbName("dev").manageAdminPassword(true)).namespace().adminPasswordSecretArn();
        otherRedshift.createWorkgroup(request -> request.workgroupName(workgroup).namespaceName(namespaceName));
        cleanup.register(() -> otherRedshift.deleteNamespace(request -> request.namespaceName(namespaceName)));
        cleanup.register(() -> otherRedshift.deleteWorkgroup(request -> request.workgroupName(workgroup)));

        otherSecrets.rotateSecret(request -> request.secretId(secretArn));

        verify(runtime, timeout(10_000)).changeMasterPassword(eq("222222222222"), eq("us-east-1"), eq(workgroup),
                eq("admin"), eq("dev"), eq(INITIAL_PASSWORD), eq(ROTATED_PASSWORD));
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> ROTATED_PASSWORD.equals(
                mapper.readTree(otherSecrets.getSecretValue(request -> request.secretId(secretArn)).secretString())
                        .path("password").asText()));
    }

    @Test
    void aFailedBackendRestoreKeepsTheRotatedPasswordOnTheNamespace() {
        String namespaceName = name();
        String secretArn = managedNamespaceWithWorkgroup(namespaceName).adminPasswordSecretArn();
        doThrow(new IllegalStateException("restore failed")).when(runtime).changeMasterPassword(
                anyString(), eq("us-east-1"), anyString(), eq("admin"), eq("dev"), eq(ROTATED_PASSWORD),
                eq(INITIAL_PASSWORD));

        assertThrows(IllegalStateException.class, () -> service.rotate(secretArn, "us-east-1",
                (changeBackend, rollbackBackend) -> {
                    changeBackend.get();
                    rollbackBackend.run();
                }));

        assertEquals(ROTATED_PASSWORD, service.getNamespace(namespaceName, "us-east-1").getAdminUserPassword());
    }

    private Namespace managedNamespaceWithWorkgroup(String namespaceName) {
        Namespace namespace = redshift.createNamespace(request -> request.namespaceName(namespaceName)
                .adminUsername("admin").dbName("dev").manageAdminPassword(true)).namespace();
        String workgroup = name();
        redshift.createWorkgroup(request -> request.workgroupName(workgroup).namespaceName(namespaceName));
        cleanup.register(() -> redshift.deleteNamespace(request -> request.namespaceName(namespaceName)));
        cleanup.register(() -> redshift.deleteWorkgroup(request -> request.workgroupName(workgroup)));
        return namespace;
    }

    private String passwordOf(String secretArn) throws Exception {
        JsonNode value = mapper.readTree(secrets.getSecretValue(request -> request.secretId(secretArn)).secretString());
        return value.path("password").asText();
    }

    private static String name() {
        return "rot-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
