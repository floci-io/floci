package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.kms.model.KmsKey;
import io.github.hectorvent.floci.services.redshift.model.Endpoint;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.redshiftserverless.RedshiftServerlessClient;
import software.amazon.awssdk.services.redshiftserverless.model.CreateNamespaceRequest;
import software.amazon.awssdk.services.redshiftserverless.model.Namespace;
import software.amazon.awssdk.services.redshiftserverless.model.ValidationException;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.DescribeSecretResponse;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class RedshiftServerlessAdminSecretsIntegrationTest {

    private static final String GENERATED_PASSWORD = "ManagedSecret123!";
    private static final String ORIGINAL_PASSWORD = "OriginalSecret123!";
    private static final String REPLACEMENT_PASSWORD = "ReplacementSecret123!";
    private static final String SECRET_KEY = "alias/admin-secret-key";

    @TestHTTPResource("/")
    URI endpoint;

    @InjectMock
    RedshiftServerlessRuntime runtime;

    @Inject
    ObjectMapper mapper;

    @Inject
    KmsService kms;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private RedshiftServerlessClient redshift;
    private SecretsManagerClient secrets;

    @BeforeEach
    void setup() {
        RestAssuredJsonUtils.configureAwsContentTypes();
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test"));
        redshift = RedshiftServerlessClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(credentials).build();
        secrets = SecretsManagerClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(credentials).build();
        cleanup.register(redshift::close);
        cleanup.register(secrets::close);
        when(runtime.generatePassword()).thenReturn(GENERATED_PASSWORD);
        when(runtime.start(any(String.class), any(String.class), any(String.class), any(String.class),
                any(String.class), any(String.class), any(Endpoint.class), anyBoolean(), any()))
                .thenReturn(new RedshiftServerlessRuntime.Backend("127.0.0.1", 55432));
    }

    @Test
    void sdkCreateExposesAnOwnedSecretAndKeepsThePasswordOutOfNamespaceResponses() throws Exception {
        Namespace created = createNamespace(CreateNamespaceRequest.builder().namespaceName(name())
                .adminUsername("warehouse_admin").dbName("analytics").manageAdminPassword(true).build());
        String arn = created.adminPasswordSecretArn();
        assertNotNull(arn);
        JsonNode value = secretValue(arn);
        assertEquals("warehouse_admin", value.path("username").asText());
        assertEquals(GENERATED_PASSWORD, value.path("password").asText());
        assertEquals("analytics", value.path("dbname").asText());
        assertEquals("redshift", value.path("engine").asText());
        DescribeSecretResponse described = secrets.describeSecret(request -> request.secretId(arn));
        assertEquals("redshift", described.owningService());
        assertEquals(arn, redshift.getNamespace(request -> request.namespaceName(created.namespaceName()))
                .namespace().adminPasswordSecretArn());
        Namespace listed = redshift.listNamespaces(request -> request.maxResults(100)).namespaces().stream()
                .filter(namespace -> created.namespaceName().equals(namespace.namespaceName()))
                .findFirst().orElseThrow();
        assertEquals(arn, listed.adminPasswordSecretArn());
        assertPasswordAbsentFromWireResponse(created.namespaceName());
    }

    @Test
    void sdkCreateAndUpdateExposeTheSecretEncryptionKey() {
        String secretKey = createSecretKey();
        Namespace created = createNamespace(CreateNamespaceRequest.builder().namespaceName(name())
                .adminUsername("admin").manageAdminPassword(true).adminPasswordSecretKmsKeyId(secretKey).build());
        String arn = created.adminPasswordSecretArn();
        assertEquals(secretKey, created.adminPasswordSecretKmsKeyId());
        assertEquals(secretKey, secrets.describeSecret(request -> request.secretId(arn)).kmsKeyId());
        assertEquals(secretKey, redshift.getNamespace(request -> request.namespaceName(created.namespaceName()))
                .namespace().adminPasswordSecretKmsKeyId());
        Namespace updated = redshift.updateNamespace(request -> request.namespaceName(created.namespaceName())
                .logExportsWithStrings("userlog")).namespace();
        assertEquals(arn, updated.adminPasswordSecretArn());
        assertEquals(secretKey, updated.adminPasswordSecretKmsKeyId());
        Namespace listed = redshift.listNamespaces(request -> request.maxResults(100)).namespaces().stream()
                .filter(namespace -> created.namespaceName().equals(namespace.namespaceName()))
                .findFirst().orElseThrow();
        assertEquals(secretKey, listed.adminPasswordSecretKmsKeyId());
    }

    @Test
    void sdkRejectsManagedPasswordAndExplicitPasswordTogetherOnCreateAndUpdate() {
        assertThrows(ValidationException.class, () -> createNamespace(CreateNamespaceRequest.builder()
                .namespaceName(name()).adminUsername("admin").manageAdminPassword(true)
                .adminUserPassword(ORIGINAL_PASSWORD).build()));
        Namespace created = createNamespace(CreateNamespaceRequest.builder().namespaceName(name())
                .adminUsername("admin").adminUserPassword(ORIGINAL_PASSWORD).build());
        assertThrows(ValidationException.class, () -> redshift.updateNamespace(request -> request
                .namespaceName(created.namespaceName()).manageAdminPassword(true)
                .adminUserPassword(REPLACEMENT_PASSWORD)));
        assertNull(redshift.getNamespace(request -> request.namespaceName(created.namespaceName()))
                .namespace().adminPasswordSecretArn());
    }

    @Test
    void sdkRequiresManagementWhenSpecifyingASecretKey() {
        assertThrows(ValidationException.class, () -> createNamespace(CreateNamespaceRequest.builder()
                .namespaceName(name()).adminUsername("admin").adminPasswordSecretKmsKeyId(SECRET_KEY).build()));
        assertThrows(ValidationException.class, () -> createNamespace(CreateNamespaceRequest.builder()
                .namespaceName(name()).adminUsername("admin").manageAdminPassword(false)
                .adminPasswordSecretKmsKeyId(SECRET_KEY).build()));
        Namespace created = createNamespace(CreateNamespaceRequest.builder().namespaceName(name())
                .adminUsername("admin").adminUserPassword(ORIGINAL_PASSWORD).build());
        assertThrows(ValidationException.class, () -> redshift.updateNamespace(request -> request
                .namespaceName(created.namespaceName()).adminPasswordSecretKmsKeyId(SECRET_KEY)));
        assertThrows(ValidationException.class, () -> redshift.updateNamespace(request -> request
                .namespaceName(created.namespaceName()).manageAdminPassword(false)
                .adminUserPassword(REPLACEMENT_PASSWORD).adminPasswordSecretKmsKeyId(SECRET_KEY)));
    }

    @Test
    void sdkEnablingAndDisablingManagementChangesTheLivePasswordAndRemovesTheSecret() throws Exception {
        String secretKey = createSecretKey();
        Namespace created = createNamespace(CreateNamespaceRequest.builder().namespaceName(name())
                .adminUsername("admin").adminUserPassword(ORIGINAL_PASSWORD).dbName("analytics").build());
        String workgroup = name();
        redshift.createWorkgroup(request -> request.workgroupName(workgroup).namespaceName(created.namespaceName()));
        cleanup.register(() -> redshift.deleteWorkgroup(request -> request.workgroupName(workgroup)));
        Namespace managed = redshift.updateNamespace(request -> request.namespaceName(created.namespaceName())
                .manageAdminPassword(true).adminPasswordSecretKmsKeyId(secretKey)).namespace();
        String arn = managed.adminPasswordSecretArn();
        assertNotNull(arn);
        assertEquals(secretKey, managed.adminPasswordSecretKmsKeyId());
        assertEquals(GENERATED_PASSWORD, secretValue(arn).path("password").asText());
        verify(runtime).changeMasterPassword(any(String.class), eq("us-east-1"), eq(workgroup), eq("admin"),
                eq("analytics"), eq(ORIGINAL_PASSWORD), eq(GENERATED_PASSWORD));

        assertThrows(ValidationException.class, () -> redshift.updateNamespace(request -> request
                .namespaceName(created.namespaceName()).manageAdminPassword(false)));
        assertEquals(arn, redshift.getNamespace(request -> request.namespaceName(created.namespaceName()))
                .namespace().adminPasswordSecretArn());
        assertEquals(GENERATED_PASSWORD, secretValue(arn).path("password").asText());

        Namespace unmanaged = redshift.updateNamespace(request -> request.namespaceName(created.namespaceName())
                .manageAdminPassword(false).adminUserPassword(REPLACEMENT_PASSWORD)).namespace();
        assertNull(unmanaged.adminPasswordSecretArn());
        assertNull(unmanaged.adminPasswordSecretKmsKeyId());
        assertNull(redshift.getNamespace(request -> request.namespaceName(created.namespaceName()))
                .namespace().adminPasswordSecretArn());
        verify(runtime).changeMasterPassword(any(String.class), eq("us-east-1"), eq(workgroup), eq("admin"),
                eq("analytics"), eq(GENERATED_PASSWORD), eq(REPLACEMENT_PASSWORD));
        assertThrows(ResourceNotFoundException.class, () -> secrets.getSecretValue(request -> request.secretId(arn)));
    }

    @Test
    void sdkDeletingTheNamespaceDeletesItsManagedSecret() {
        Namespace created = createNamespace(CreateNamespaceRequest.builder().namespaceName(name())
                .adminUsername("admin").manageAdminPassword(true).build());
        String arn = created.adminPasswordSecretArn();
        assertNotNull(arn);
        redshift.deleteNamespace(request -> request.namespaceName(created.namespaceName()));
        assertThrows(ResourceNotFoundException.class, () -> secrets.describeSecret(request -> request.secretId(arn)));
        assertThrows(ResourceNotFoundException.class, () -> secrets.getSecretValue(request -> request.secretId(arn)));
    }

    private Namespace createNamespace(CreateNamespaceRequest request) {
        Namespace namespace = redshift.createNamespace(request).namespace();
        cleanup.register(() -> {
            try {
                redshift.deleteNamespace(delete -> delete.namespaceName(namespace.namespaceName()));
            } catch (software.amazon.awssdk.services.redshiftserverless.model.ResourceNotFoundException expected) {
                // This SDK exception clashes with Secrets Manager's; the lifecycle test already deleted the namespace.
            }
        });
        return namespace;
    }

    private JsonNode secretValue(String arn) throws Exception {
        return mapper.readTree(secrets.getSecretValue(request -> request.secretId(arn)).secretString());
    }

    private String createSecretKey() {
        KmsKey key = kms.createKey("Redshift Serverless admin secret integration test", "us-east-1");
        cleanup.register(() -> kms.scheduleKeyDeletion(key.getKeyId(), 7, "us-east-1"));
        return key.getArn();
    }

    private void assertPasswordAbsentFromWireResponse(String namespace) throws Exception {
        String response = given().header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=test/20261025/us-east-1/redshift-serverless/aws4_request")
                .header("X-Amz-Target", "RedshiftServerless.GetNamespace")
                .contentType("application/x-amz-json-1.1").body(Map.of("namespaceName", namespace))
                .post(endpoint).then().statusCode(200).extract().asString();
        JsonNode body = mapper.readTree(response).path("namespace");
        assertFalse(body.has("adminUserPassword"));
        assertFalse(response.contains(GENERATED_PASSWORD));
        assertTrue(body.hasNonNull("adminPasswordSecretArn"));
    }

    private static String name() {
        return "managed-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
