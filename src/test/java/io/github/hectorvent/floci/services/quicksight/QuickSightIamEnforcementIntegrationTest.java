package io.github.hectorvent.floci.services.quicksight;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.quicksight.QuickSightClient;
import software.amazon.awssdk.services.quicksight.model.AccessDeniedException;
import software.amazon.awssdk.services.quicksight.model.PhysicalTable;
import software.amazon.awssdk.services.quicksight.model.ResourceNotFoundException;
import software.amazon.awssdk.services.quicksight.model.ResourcePermission;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class QuickSightIamEnforcementIntegrationTest {
    private static final String ACCOUNT = "111122223333";
    private static final Region REGION = Region.EU_WEST_2;

    @TestHTTPResource
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void dashboardPoliciesCheckTheRoutedActionAndExactResource() {
        String name = "qs-iam-" + UUID.randomUUID();
        EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(), auth(ACCOUNT, "iam"), name);
        try (QuickSightClient root = AwsTestClients.create(QuickSightClient.builder(), ACCOUNT, REGION);
                QuickSightClient user = AwsTestClients.create(QuickSightClient.builder(), credentials.accessKeyId(), credentials.secretAccessKey(), REGION)) {
            String arn = root.createDashboard(request -> request.awsAccountId(ACCOUNT).dashboardId(name).name("Protected")
                    .definition(definition -> definition.sheets(List.of()))).arn();
            try {
                List<Executable> requests = List.of(
                        () -> user.createDashboard(request -> request.awsAccountId(ACCOUNT).dashboardId(name + "-new").name("Denied")),
                        () -> user.describeDashboard(request -> request.awsAccountId(ACCOUNT).dashboardId(name)),
                        () -> user.deleteDashboard(request -> request.awsAccountId(ACCOUNT).dashboardId(name)),
                        () -> user.searchDashboards(request -> request.awsAccountId(ACCOUNT)),
                        () -> user.createDataSet(request -> request.awsAccountId(ACCOUNT).dataSetId(name).name("Denied").importMode("SPICE")),
                        () -> user.describeDataSet(request -> request.awsAccountId(ACCOUNT).dataSetId(name)),
                        () -> user.deleteDataSet(request -> request.awsAccountId(ACCOUNT).dataSetId(name)),
                        () -> user.createDataSource(request -> request.awsAccountId(ACCOUNT).dataSourceId(name).name("Denied").type("S3")),
                        () -> user.describeDataSource(request -> request.awsAccountId(ACCOUNT).dataSourceId(name)),
                        () -> user.deleteDataSource(request -> request.awsAccountId(ACCOUNT).dataSourceId(name)),
                        () -> user.registerUser(request -> request.awsAccountId(ACCOUNT).namespace("default").userName(name)
                                .identityType("QUICKSIGHT").userRole("READER").email("reader@example.com")),
                        () -> user.describeUser(request -> request.awsAccountId(ACCOUNT).namespace("default").userName(name)),
                        () -> user.createIngestion(request -> request.awsAccountId(ACCOUNT).dataSetId(name).ingestionId("one")),
                        () -> user.describeIngestion(request -> request.awsAccountId(ACCOUNT).dataSetId(name).ingestionId("one")),
                        () -> user.listIngestions(request -> request.awsAccountId(ACCOUNT).dataSetId(name)),
                        () -> user.generateEmbedUrlForRegisteredUser(request -> request.awsAccountId(ACCOUNT)
                                .userArn("arn:aws:quicksight:eu-west-2:" + ACCOUNT + ":user/default/" + name)));
                for (Executable request : requests) {
                    assertEquals(401, assertThrows(AccessDeniedException.class, request).statusCode());
                }
                policy(name, "quicksight:DescribeDashboard", arn);
                assertEquals(arn, user.describeDashboard(request -> request.awsAccountId(ACCOUNT).dashboardId(name)).dashboard().arn());
                assertThrows(AccessDeniedException.class,
                        () -> user.deleteDashboard(request -> request.awsAccountId(ACCOUNT).dashboardId(name)));
                policy(name, "quicksight:DescribeDashboard", arn + "-other");
                assertThrows(AccessDeniedException.class,
                        () -> user.describeDashboard(request -> request.awsAccountId(ACCOUNT).dashboardId(name)));
                EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth(ACCOUNT, "iam"), name, "p", """
                        {"Version":"2012-10-17","Statement":[
                          {"Effect":"Allow","Action":"quicksight:*","Resource":"*"},
                          {"Effect":"Deny","Action":"quicksight:DescribeDashboard","Resource":"%s"}]}""".formatted(arn));
                assertThrows(AccessDeniedException.class,
                        () -> user.describeDashboard(request -> request.awsAccountId(ACCOUNT).dashboardId(name)));
                policy(name, "quicksight:DeleteDashboard", arn);
                user.deleteDashboard(request -> request.awsAccountId(ACCOUNT).dashboardId(name));
            } finally {
                try {
                    root.deleteDashboard(request -> request.awsAccountId(ACCOUNT).dashboardId(name));
                } catch (ResourceNotFoundException expected) {
                    // The authorized delete may already have removed the dashboard.
                }
            }
        }
    }

    @Test
    void namespaceUsersIngestionsAndEmbeddingUseTheirExactArns() {
        String name = "qs-resources-" + UUID.randomUUID();
        EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(), auth(ACCOUNT, "iam"), name);
        try (QuickSightClient root = AwsTestClients.create(QuickSightClient.builder(), ACCOUNT, REGION);
                QuickSightClient user = AwsTestClients.create(QuickSightClient.builder(), credentials.accessKeyId(), credentials.secretAccessKey(), REGION)) {
            String principal = "arn:aws:quicksight:eu-west-2:" + ACCOUNT + ":user/default/" + name;
            policy(name, "quicksight:RegisterUser", principal);
            assertEquals(principal, user.registerUser(request -> request.awsAccountId(ACCOUNT).namespace("default").userName(name)
                    .identityType("QUICKSIGHT").userRole("READER").email("reader@example.com")).user().arn());
            policy(name, "quicksight:DescribeUser", principal);
            assertEquals(principal, user.describeUser(request -> request.awsAccountId(ACCOUNT).namespace("default").userName(name)).user().arn());
            assertThrows(AccessDeniedException.class,
                    () -> user.describeUser(request -> request.awsAccountId(ACCOUNT).namespace("other").userName(name)));
            root.createDashboard(request -> request.awsAccountId(ACCOUNT).dashboardId(name).name("Shared")
                    .definition(definition -> definition.sheets(List.of()))
                    .permissions(ResourcePermission.builder().principal(principal).actions("quicksight:DescribeDashboard").build()));
            cleanup.register(() -> rootDeleteDashboard(name));
            policy(name, "quicksight:GenerateEmbedUrlForRegisteredUser", principal);
            user.generateEmbedUrlForRegisteredUser(request -> request.awsAccountId(ACCOUNT).userArn(principal)
                    .experienceConfiguration(experience -> experience.dashboard(dashboard -> dashboard.initialDashboardId(name))));
            policy(name, "quicksight:GenerateEmbedUrlForRegisteredUser", principal + "-other");
            assertThrows(AccessDeniedException.class,
                    () -> user.generateEmbedUrlForRegisteredUser(request -> request.awsAccountId(ACCOUNT).userArn(principal)
                            .experienceConfiguration(experience -> experience.dashboard(dashboard -> dashboard.initialDashboardId(name)))));
            String dataset = root.createDataSet(request -> request.awsAccountId(ACCOUNT).dataSetId(name).name("Data")
                    .importMode("SPICE").physicalTableMap(Map.of("table", PhysicalTable.builder()
                            .s3Source(source -> source.inputColumns(column -> column.name("value").type("STRING"))).build()))).arn();
            cleanup.register(() -> rootDeleteDataSet(name));
            policy(name, "quicksight:DescribeDataSet", dataset);
            assertEquals(dataset, user.describeDataSet(request -> request.awsAccountId(ACCOUNT).dataSetId(name)).dataSet().arn());
            assertThrows(AccessDeniedException.class,
                    () -> user.describeDataSet(request -> request.awsAccountId(ACCOUNT).dataSetId(name + "-other")));
            String source = root.createDataSource(request -> request.awsAccountId(ACCOUNT).dataSourceId(name).name("Source").type("S3")).arn();
            cleanup.register(() -> rootDeleteDataSource(name));
            policy(name, "quicksight:DescribeDataSource", source);
            assertEquals(source, user.describeDataSource(request -> request.awsAccountId(ACCOUNT).dataSourceId(name)).dataSource().arn());
            assertThrows(AccessDeniedException.class,
                    () -> user.describeDataSource(request -> request.awsAccountId(ACCOUNT).dataSourceId(name + "-other")));
            String ingestion = dataset + "/ingestion/one";
            policy(name, "quicksight:CreateIngestion", ingestion);
            assertEquals(ingestion, user.createIngestion(request -> request.awsAccountId(ACCOUNT).dataSetId(name).ingestionId("one")).arn());
            policy(name, "quicksight:DescribeIngestion", ingestion);
            assertEquals(ingestion, user.describeIngestion(request -> request.awsAccountId(ACCOUNT).dataSetId(name).ingestionId("one")).ingestion().arn());
            assertThrows(AccessDeniedException.class,
                    () -> user.describeIngestion(request -> request.awsAccountId(ACCOUNT).dataSetId(name).ingestionId("other")));
        }
    }

    @Test
    void creatingADataSetChecksEveryPhysicalTablesDataSource() {
        String name = "qs-tables-" + UUID.randomUUID();
        EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(), auth(ACCOUNT, "iam"), name);
        try (QuickSightClient root = AwsTestClients.create(QuickSightClient.builder(), ACCOUNT, REGION);
                QuickSightClient user = AwsTestClients.create(QuickSightClient.builder(), credentials.accessKeyId(), credentials.secretAccessKey(), REGION)) {
            String source = root.createDataSource(request -> request.awsAccountId(ACCOUNT).dataSourceId(name).name("Source").type("S3")).arn();
            cleanup.register(() -> rootDeleteDataSource(name));
            String other = root.createDataSource(request -> request.awsAccountId(ACCOUNT).dataSourceId(name + "-other").name("Other").type("S3")).arn();
            cleanup.register(() -> rootDeleteDataSource(name + "-other"));
            String sqlSource = root.createDataSource(request -> request.awsAccountId(ACCOUNT).dataSourceId(name + "-sql").name("SQL").type("S3")).arn();
            cleanup.register(() -> rootDeleteDataSource(name + "-sql"));
            Map<String, PhysicalTable> tables = Map.of(
                    "s3", PhysicalTable.builder().s3Source(table -> table.dataSourceArn(source)
                            .inputColumns(column -> column.name("value").type("STRING"))).build(),
                    "relational", PhysicalTable.builder().relationalTable(table -> table.dataSourceArn(other).name("Report")
                            .inputColumns(column -> column.name("value").type("STRING"))).build(),
                    "sql", PhysicalTable.builder().customSql(table -> table.dataSourceArn(sqlSource).name("Query").sqlQuery("SELECT value")
                            .columns(column -> column.name("value").type("STRING"))).build());
            policy(name, "quicksight:CreateDataSet", source);
            assertThrows(AccessDeniedException.class,
                    () -> user.createDataSet(request -> request.awsAccountId(ACCOUNT).dataSetId(name).name("Data").importMode("SPICE").physicalTableMap(tables)));
            EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth(ACCOUNT, "iam"), name, "p", """
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"quicksight:CreateDataSet","Resource":["%s","%s","%s"]}]}"""
                    .formatted(source, other, sqlSource));
            user.createDataSet(request -> request.awsAccountId(ACCOUNT).dataSetId(name).name("Data").importMode("SPICE").physicalTableMap(tables));
            cleanup.register(() -> rootDeleteDataSet(name));
            for (String denied : List.of(source, other, sqlSource)) {
                EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth(ACCOUNT, "iam"), name, "p", """
                        {"Version":"2012-10-17","Statement":[
                          {"Effect":"Allow","Action":"quicksight:CreateDataSet","Resource":"*"},
                          {"Effect":"Deny","Action":"quicksight:CreateDataSet","Resource":"%s"}]}""".formatted(denied));
                assertThrows(AccessDeniedException.class,
                        () -> user.createDataSet(request -> request.awsAccountId(ACCOUNT).dataSetId(name + "-denied").name("Denied")
                                .importMode("SPICE").physicalTableMap(tables)));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SaaSTable", "FileSource"})
    void additionalPhysicalTableSourcesHonorScopedGrantsAndDenies(String kind) {
        String name = "qs-source-" + UUID.randomUUID();
        EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(), auth(ACCOUNT, "iam"), name);
        try (QuickSightClient root = AwsTestClients.create(QuickSightClient.builder(), ACCOUNT, REGION);
                QuickSightClient user = AwsTestClients.create(QuickSightClient.builder(), credentials.accessKeyId(), credentials.secretAccessKey(), REGION)) {
            String source = root.createDataSource(request -> request.awsAccountId(ACCOUNT).dataSourceId(name).name("Source").type("S3")).arn();
            cleanup.register(() -> rootDeleteDataSource(name));
            PhysicalTable table = switch (kind) {
                case "SaaSTable" -> PhysicalTable.builder().saaSTable(value -> value.dataSourceArn(source)
                        .inputColumns(column -> column.name("value").type("STRING"))).build();
                case "FileSource" -> PhysicalTable.builder().fileSource(value -> value.dataSourceArn(source)
                        .inputColumns(column -> column.name("value").type("STRING"))).build();
                default -> throw new IllegalArgumentException(kind);
            };
            policy(name, "quicksight:CreateDataSet", source);
            user.createDataSet(request -> request.awsAccountId(ACCOUNT).dataSetId(name).name("Data").importMode("SPICE")
                    .physicalTableMap(Map.of("table", table)));
            cleanup.register(() -> rootDeleteDataSet(name));
            policy(name, "quicksight:CreateDataSet", source + "-other");
            assertThrows(AccessDeniedException.class,
                    () -> user.createDataSet(request -> request.awsAccountId(ACCOUNT).dataSetId(name + "-denied").name("Denied")
                            .importMode("SPICE").physicalTableMap(Map.of("table", table))));
            EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth(ACCOUNT, "iam"), name, "p", """
                    {"Version":"2012-10-17","Statement":[
                      {"Effect":"Allow","Action":"quicksight:CreateDataSet","Resource":"*"},
                      {"Effect":"Deny","Action":"quicksight:CreateDataSet","Resource":"%s"}]}""".formatted(source));
            AccessDeniedException denied = assertThrows(AccessDeniedException.class,
                    () -> user.createDataSet(request -> request.awsAccountId(ACCOUNT).dataSetId(name + "-denied").name("Denied")
                            .importMode("SPICE").physicalTableMap(Map.of("table", table))));
            assertEquals(401, denied.statusCode());
            assertThrows(ResourceNotFoundException.class,
                    () -> root.describeDataSet(request -> request.awsAccountId(ACCOUNT).dataSetId(name + "-denied")));
        }
    }

    private void rootDeleteDashboard(String id) {
        try (QuickSightClient root = AwsTestClients.create(QuickSightClient.builder(), ACCOUNT, REGION)) {
            root.deleteDashboard(request -> request.awsAccountId(ACCOUNT).dashboardId(id));
        }
    }

    private void rootDeleteDataSet(String id) {
        try (QuickSightClient root = AwsTestClients.create(QuickSightClient.builder(), ACCOUNT, REGION)) {
            root.deleteDataSet(request -> request.awsAccountId(ACCOUNT).dataSetId(id));
        }
    }

    private void rootDeleteDataSource(String id) {
        try (QuickSightClient root = AwsTestClients.create(QuickSightClient.builder(), ACCOUNT, REGION)) {
            root.deleteDataSource(request -> request.awsAccountId(ACCOUNT).dataSourceId(id));
        }
    }

    private void policy(String user, String action, String resource) {
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth(ACCOUNT, "iam"), user, "p", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"%s","Resource":"%s"}]}"""
                .formatted(action, resource));
    }
}
