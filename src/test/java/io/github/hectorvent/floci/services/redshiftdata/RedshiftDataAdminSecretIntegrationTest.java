package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.redshift.model.Endpoint;
import io.github.hectorvent.floci.services.redshiftserverless.RedshiftServerlessRuntime;
import io.github.hectorvent.floci.services.redshiftserverless.RedshiftServerlessService;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.h2.Driver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.redshiftdata.RedshiftDataClient;
import software.amazon.awssdk.services.redshiftdata.model.DescribeStatementResponse;
import software.amazon.awssdk.services.redshiftdata.model.ExecuteStatementResponse;
import software.amazon.awssdk.services.redshiftdata.model.StatusString;
import software.amazon.awssdk.services.redshiftserverless.RedshiftServerlessClient;
import software.amazon.awssdk.services.redshiftserverless.model.Namespace;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

import java.net.URI;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class RedshiftDataAdminSecretIntegrationTest {

    private static final String GENERATED_PASSWORD = "ManagedDataSecret123!";

    @TestHTTPResource("/")
    URI endpoint;

    @InjectMock
    RedshiftServerlessRuntime runtime;

    @InjectMock
    RedshiftDataConnectionFactory factory;

    @Inject
    RedshiftServerlessService serverlessService;

    @Inject
    RedshiftDataService dataService;

    @Inject
    ObjectMapper mapper;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private RedshiftServerlessClient redshift;
    private SecretsManagerClient secrets;
    private RedshiftDataClient data;

    @BeforeEach
    void setup() throws Exception {
        Driver.load();
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        when(factory.open(any())).thenAnswer(invocation -> DriverManager.getConnection(url));
        when(runtime.generatePassword()).thenReturn(GENERATED_PASSWORD);
        when(runtime.start(any(String.class), any(String.class), any(String.class), any(String.class),
                any(String.class), any(String.class), any(Endpoint.class), anyBoolean(), any()))
                .thenReturn(new RedshiftServerlessRuntime.Backend("127.0.0.1", 55432));
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test"));
        redshift = RedshiftServerlessClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(credentials).build();
        secrets = SecretsManagerClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(credentials).build();
        data = RedshiftDataClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(credentials).build();
        cleanup.register(redshift::close);
        cleanup.register(secrets::close);
        cleanup.register(data::close);
        cleanup.register(dataService::clear);
    }

    @Test
    void sdkExecutesSqlUsingTheNamespaceManagedSecretAndPersistedCredentials() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String namespaceName = "data-secret-" + suffix;
        String workgroup = "data-secret-wg-" + suffix;
        Namespace namespace = redshift.createNamespace(request -> request.namespaceName(namespaceName)
                .adminUsername("warehouse_admin").dbName("analytics").manageAdminPassword(true)).namespace();
        cleanup.register(() -> redshift.deleteNamespace(request -> request.namespaceName(namespaceName)));
        redshift.createWorkgroup(request -> request.workgroupName(workgroup).namespaceName(namespaceName));
        cleanup.register(() -> redshift.deleteWorkgroup(request -> request.workgroupName(workgroup)));
        assertNotNull(namespace.adminPasswordSecretArn());
        JsonNode value = mapper.readTree(secrets.getSecretValue(request -> request
                .secretId(namespace.adminPasswordSecretArn())).secretString());
        assertEquals(serverlessService.getNamespace(namespaceName, "us-east-1").getAdminUserPassword(),
                value.path("password").asText());

        ExecuteStatementResponse executed = data.executeStatement(request -> request.workgroupName(workgroup)
                .secretArn(namespace.adminPasswordSecretArn()).database("analytics")
                .waitTimeSeconds(30).sql("SELECT 42 AS answer"));
        DescribeStatementResponse described = data.describeStatement(request -> request.id(executed.id()));
        assertEquals(StatusString.FINISHED, described.status(), described.error());
        assertEquals(42L, data.getStatementResult(request -> request.id(executed.id()))
                .records().getFirst().getFirst().longValue());
        ArgumentCaptor<RedshiftDataResourceResolver.DatabaseTarget> target =
                ArgumentCaptor.forClass(RedshiftDataResourceResolver.DatabaseTarget.class);
        verify(factory).open(target.capture());
        assertEquals("warehouse_admin", target.getValue().user());
        assertEquals(value.path("username").asText(), target.getValue().user());
        assertEquals(GENERATED_PASSWORD, target.getValue().password());
        assertEquals(value.path("password").asText(), target.getValue().password());
        assertEquals("analytics", target.getValue().database());
    }

    @Test
    void sdkExecutesSqlUsingManagedSecretAfterAdminUsernameRename() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String namespaceName = "data-rename-" + suffix;
        String workgroup = "data-rename-wg-" + suffix;
        Namespace namespace = redshift.createNamespace(request -> request.namespaceName(namespaceName)
                .adminUsername("initial_admin").dbName("analytics").manageAdminPassword(true)).namespace();
        cleanup.register(() -> redshift.deleteNamespace(request -> request.namespaceName(namespaceName)));
        redshift.createWorkgroup(request -> request.workgroupName(workgroup).namespaceName(namespaceName));
        cleanup.register(() -> redshift.deleteWorkgroup(request -> request.workgroupName(workgroup)));

        redshift.updateNamespace(request -> request.namespaceName(namespaceName).adminUsername("renamed_admin"));

        JsonNode value = mapper.readTree(secrets.getSecretValue(request -> request
                .secretId(namespace.adminPasswordSecretArn())).secretString());
        assertEquals("renamed_admin", value.path("username").asText());
        String partialSecretArn = namespace.adminPasswordSecretArn().substring(0,
                namespace.adminPasswordSecretArn().length() - 7);

        ExecuteStatementResponse executed = data.executeStatement(request -> request.workgroupName(workgroup)
                .secretArn(partialSecretArn).database("analytics")
                .waitTimeSeconds(30).sql("SELECT 101 AS answer"));
        DescribeStatementResponse described = data.describeStatement(request -> request.id(executed.id()));
        assertEquals(StatusString.FINISHED, described.status(), described.error());

        ArgumentCaptor<RedshiftDataResourceResolver.DatabaseTarget> target =
                ArgumentCaptor.forClass(RedshiftDataResourceResolver.DatabaseTarget.class);
        verify(factory, atLeastOnce()).open(target.capture());
        assertEquals("initial_admin", target.getValue().user());
    }

    @Test
    void sdkExecutesSqlUsingManagedSecretWhenManagementEnabledAfterUsernameRename() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String namespaceName = "data-late-manage-" + suffix;
        String workgroup = "data-late-manage-wg-" + suffix;
        redshift.createNamespace(request -> request.namespaceName(namespaceName)
                .adminUsername("initial_admin").adminUserPassword("ExplicitSecret123!").dbName("analytics"));
        cleanup.register(() -> redshift.deleteNamespace(request -> request.namespaceName(namespaceName)));
        redshift.createWorkgroup(request -> request.workgroupName(workgroup).namespaceName(namespaceName));
        cleanup.register(() -> redshift.deleteWorkgroup(request -> request.workgroupName(workgroup)));

        redshift.updateNamespace(request -> request.namespaceName(namespaceName).adminUsername("earlier_renamed_admin"));
        Namespace managed = redshift.updateNamespace(request -> request.namespaceName(namespaceName)
                .manageAdminPassword(true)).namespace();
        assertNotNull(managed.adminPasswordSecretArn());

        JsonNode value = mapper.readTree(secrets.getSecretValue(request -> request
                .secretId(managed.adminPasswordSecretArn())).secretString());
        assertEquals("earlier_renamed_admin", value.path("username").asText());

        ExecuteStatementResponse executed = data.executeStatement(request -> request.workgroupName(workgroup)
                .secretArn(managed.adminPasswordSecretArn()).database("analytics")
                .waitTimeSeconds(30).sql("SELECT 202 AS answer"));
        DescribeStatementResponse described = data.describeStatement(request -> request.id(executed.id()));
        assertEquals(StatusString.FINISHED, described.status(), described.error());

        ArgumentCaptor<RedshiftDataResourceResolver.DatabaseTarget> target =
                ArgumentCaptor.forClass(RedshiftDataResourceResolver.DatabaseTarget.class);
        verify(factory, atLeastOnce()).open(target.capture());
        assertEquals("initial_admin", target.getValue().user());
    }

    @Test
    void callerOwnedSecretConnectsAsItsOwnDatabaseUserEvenWhenMatchingAdminUsername() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String namespaceName = "data-caller-secret-" + suffix;
        String workgroup = "data-caller-secret-wg-" + suffix;
        Namespace namespace = redshift.createNamespace(request -> request.namespaceName(namespaceName)
                .adminUsername("initial_admin").dbName("analytics").manageAdminPassword(true)).namespace();
        cleanup.register(() -> redshift.deleteNamespace(request -> request.namespaceName(namespaceName)));
        redshift.createWorkgroup(request -> request.workgroupName(workgroup).namespaceName(namespaceName));
        cleanup.register(() -> redshift.deleteWorkgroup(request -> request.workgroupName(workgroup)));

        String callerSecretArn = secrets.createSecret(request -> request.name("caller-secret-" + suffix)
                .secretString("{\"username\":\"report_reader\",\"password\":\"ReaderSecret123!\"}")).arn();
        cleanup.register(() -> secrets.deleteSecret(request -> request.secretId(callerSecretArn).forceDeleteWithoutRecovery(true)));

        redshift.updateNamespace(request -> request.namespaceName(namespaceName).adminUsername("report_reader"));

        ExecuteStatementResponse executed = data.executeStatement(request -> request.workgroupName(workgroup)
                .secretArn(callerSecretArn).database("analytics")
                .waitTimeSeconds(30).sql("SELECT 303 AS answer"));
        DescribeStatementResponse described = data.describeStatement(request -> request.id(executed.id()));
        assertEquals(StatusString.FINISHED, described.status(), described.error());

        ArgumentCaptor<RedshiftDataResourceResolver.DatabaseTarget> target =
                ArgumentCaptor.forClass(RedshiftDataResourceResolver.DatabaseTarget.class);
        verify(factory, atLeastOnce()).open(target.capture());
        assertEquals("report_reader", target.getValue().user());
        assertEquals("ReaderSecret123!", target.getValue().password());
    }
}
