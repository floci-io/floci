package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.s3tables.S3TablesClient;
import software.amazon.awssdk.services.s3tables.model.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3TablesTest {
    private static S3TablesClient client;
    private static String bucketName;
    private static String bucketArn;
    private static String tableName;
    private static String versionToken;
    private static final String NAMESPACE = "analytics";

    @BeforeAll
    static void setup() {
        client = TestFixtures.s3tablesClient();
        bucketName = TestFixtures.uniqueName("table-bucket");
        tableName = TestFixtures.uniqueName("events");
    }

    @AfterAll
    static void cleanup() {
        if (client == null) {
            return;
        }
        if (bucketArn != null) {
            cleanup("table " + tableName, () -> client.deleteTable(DeleteTableRequest.builder()
                    .tableBucketARN(bucketArn).namespace(NAMESPACE).name(tableName).build()));
            cleanup("namespace " + NAMESPACE, () -> client.deleteNamespace(DeleteNamespaceRequest.builder()
                    .tableBucketARN(bucketArn).namespace(NAMESPACE).build()));
            cleanup("table bucket " + bucketArn, () -> client.deleteTableBucket(DeleteTableBucketRequest.builder()
                    .tableBucketARN(bucketArn).build()));
        }
        client.close();
    }

    private static void cleanup(String resource, Runnable deletion) {
        try {
            deletion.run();
        } catch (Exception exception) {
            System.err.printf("S3 Tables SDK test cleanup failed for %s: %s%n", resource, exception.getMessage());
        }
    }

    @Test
    @Order(1)
    void createsAndGetsTableBucket() {
        CreateTableBucketResponse created = client.createTableBucket(
                CreateTableBucketRequest.builder()
                        .name(bucketName)
                        .tags(Map.of("suite", "sdk-java"))
                        .encryptionConfiguration(EncryptionConfiguration.builder().sseAlgorithm(SSEAlgorithm.AES256).build())
                        .storageClassConfiguration(StorageClassConfiguration.builder().storageClass(StorageClass.STANDARD).build())
                        .build());
        bucketArn = created.arn();
        assertThat(bucketArn).contains("arn:aws:s3tables:").endsWith("bucket/" + bucketName);

        GetTableBucketResponse fetched = client.getTableBucket(
                GetTableBucketRequest.builder().tableBucketARN(bucketArn).build());
        assertThat(fetched.name()).isEqualTo(bucketName);
        assertThat(fetched.arn()).isEqualTo(bucketArn);
    }

    @Test
    @Order(2)
    void createsNamespaceAndTable() {
        CreateNamespaceResponse namespace = client.createNamespace(
                CreateNamespaceRequest.builder().tableBucketARN(bucketArn).namespace(NAMESPACE).build());
        assertThat(namespace.namespace()).containsExactly(NAMESPACE);

        CreateTableResponse table = client.createTable(CreateTableRequest.builder()
                .tableBucketARN(bucketArn)
                .namespace(NAMESPACE)
                .name(tableName)
                .format(OpenTableFormat.ICEBERG)
                .metadata(TableMetadata.builder().iceberg(IcebergMetadata.builder()
                        .properties(Map.of("format-version", "2")).build()).build())
                .encryptionConfiguration(EncryptionConfiguration.builder().sseAlgorithm(SSEAlgorithm.AES256).build())
                .storageClassConfiguration(StorageClassConfiguration.builder().storageClass(StorageClass.STANDARD).build())
                .tags(Map.of("suite", "sdk-java"))
                .build());
        versionToken = table.versionToken();
        assertThat(table.tableARN()).isEqualTo(bucketArn + "/table/" + tableName);
        assertThat(versionToken).isNotBlank();

        GetTableResponse fetched = client.getTable(GetTableRequest.builder()
                .tableBucketARN(bucketArn).namespace(NAMESPACE).name(tableName).build());
        assertThat(fetched.tableARN()).isEqualTo(table.tableARN());
        assertThat(fetched.namespace()).containsExactly(NAMESPACE);
        assertThat(fetched.format()).isEqualTo(OpenTableFormat.ICEBERG);
    }

    @Test
    @Order(3)
    void getsAndListsResourcesWithPrefixes() {
        assertThat(client.listTableBuckets(ListTableBucketsRequest.builder().prefix("table-bucket").build()).tableBuckets())
                .anyMatch(bucket -> bucketName.equals(bucket.name()));
        assertThat(client.getNamespace(GetNamespaceRequest.builder().tableBucketARN(bucketArn).namespace(NAMESPACE).build())
                .namespace()).containsExactly(NAMESPACE);
        assertThat(client.listNamespaces(ListNamespacesRequest.builder().tableBucketARN(bucketArn).prefix("anal").build())
                .namespaces()).anyMatch(namespace -> namespace.namespace().contains(NAMESPACE));
        assertThat(client.listTables(ListTablesRequest.builder().tableBucketARN(bucketArn).namespace(NAMESPACE)
                        .prefix("events").build()).tables())
                .anyMatch(table -> tableName.equals(table.name()));
    }

    @Test
    @Order(4)
    void updatesAndGetsMetadataLocationAndRejectsStaleVersionToken() {
        UpdateTableMetadataLocationResponse updated = client.updateTableMetadataLocation(
                UpdateTableMetadataLocationRequest.builder()
                        .tableBucketARN(bucketArn)
                        .namespace(NAMESPACE)
                        .name(tableName)
                        .metadataLocation("s3://warehouse/" + tableName + "/v2.metadata.json")
                        .versionToken(versionToken)
                        .build());
        assertThat(updated.metadataLocation()).endsWith("v2.metadata.json");
        assertThat(updated.versionToken()).isNotEqualTo(versionToken);
        assertThat(client.getTableMetadataLocation(GetTableMetadataLocationRequest.builder()
                        .tableBucketARN(bucketArn).namespace(NAMESPACE).name(tableName).build())
                .metadataLocation()).isEqualTo(updated.metadataLocation());

        assertThatThrownBy(() -> client.updateTableMetadataLocation(
                UpdateTableMetadataLocationRequest.builder()
                        .tableBucketARN(bucketArn).namespace(NAMESPACE).name(tableName)
                        .metadataLocation("s3://warehouse/stale.metadata.json")
                        .versionToken(versionToken).build()))
                .isInstanceOf(ConflictException.class);
        versionToken = updated.versionToken();
    }

    @Test
    @Order(5)
    void roundTripsAndDeletesBucketAndTablePolicies() {
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";
        client.putTableBucketPolicy(PutTableBucketPolicyRequest.builder()
                .tableBucketARN(bucketArn).resourcePolicy(policy).build());
        assertThat(client.getTableBucketPolicy(GetTableBucketPolicyRequest.builder().tableBucketARN(bucketArn).build())
                .resourcePolicy()).isEqualTo(policy);

        client.putTablePolicy(PutTablePolicyRequest.builder()
                .tableBucketARN(bucketArn).namespace(NAMESPACE).name(tableName).resourcePolicy(policy).build());
        assertThat(client.getTablePolicy(GetTablePolicyRequest.builder()
                .tableBucketARN(bucketArn).namespace(NAMESPACE).name(tableName).build()).resourcePolicy()).isEqualTo(policy);
    }

    @Test
    @Order(6)
    void roundTripsTypedMaintenanceConfigurations() {
        TableBucketMaintenanceConfigurationValue bucketValue = TableBucketMaintenanceConfigurationValue.builder()
                .status(MaintenanceStatus.ENABLED).build();
        client.putTableBucketMaintenanceConfiguration(PutTableBucketMaintenanceConfigurationRequest.builder()
                .tableBucketARN(bucketArn)
                .type(TableBucketMaintenanceType.ICEBERG_UNREFERENCED_FILE_REMOVAL)
                .value(bucketValue)
                .build());
        assertThat(client.getTableBucketMaintenanceConfiguration(GetTableBucketMaintenanceConfigurationRequest.builder()
                        .tableBucketARN(bucketArn).build())
                .configuration().get(TableBucketMaintenanceType.ICEBERG_UNREFERENCED_FILE_REMOVAL).status())
                .isEqualTo(MaintenanceStatus.ENABLED);

        TableMaintenanceConfigurationValue tableValue = TableMaintenanceConfigurationValue.builder()
                .status(MaintenanceStatus.ENABLED).build();
        client.putTableMaintenanceConfiguration(PutTableMaintenanceConfigurationRequest.builder()
                .tableBucketARN(bucketArn).namespace(NAMESPACE).name(tableName)
                .type(TableMaintenanceType.ICEBERG_COMPACTION)
                .value(tableValue)
                .build());
        assertThat(client.getTableMaintenanceConfiguration(GetTableMaintenanceConfigurationRequest.builder()
                        .tableBucketARN(bucketArn).namespace(NAMESPACE).name(tableName).build())
                .configuration().get(TableMaintenanceType.ICEBERG_COMPACTION).status())
                .isEqualTo(MaintenanceStatus.ENABLED);
    }

    @Test
    @Order(7)
    void renamesTableAndRotatesItsVersionToken() {
        String originalToken = versionToken;
        String renamedTableName = tableName + "-renamed";
        client.renameTable(RenameTableRequest.builder()
                .tableBucketARN(bucketArn).namespace(NAMESPACE).name(tableName)
                .newName(renamedTableName).versionToken(originalToken).build());

        GetTableResponse renamed = client.getTable(GetTableRequest.builder()
                .tableBucketARN(bucketArn).namespace(NAMESPACE).name(renamedTableName).build());
        assertThat(renamed.name()).isEqualTo(renamedTableName);
        assertThat(renamed.versionToken()).isNotEqualTo(originalToken);
        assertThatThrownBy(() -> client.getTable(GetTableRequest.builder()
                .tableBucketARN(bucketArn).namespace(NAMESPACE).name(tableName).build()))
                .isInstanceOf(NotFoundException.class);
        tableName = renamedTableName;
        versionToken = renamed.versionToken();
    }

    @Test
    @Order(8)
    void deletesPoliciesAndResourceHierarchy() {
        client.deleteTablePolicy(DeleteTablePolicyRequest.builder()
                .tableBucketARN(bucketArn).namespace(NAMESPACE).name(tableName).build());
        assertThatThrownBy(() -> client.getTablePolicy(GetTablePolicyRequest.builder()
                .tableBucketARN(bucketArn).namespace(NAMESPACE).name(tableName).build()))
                .isInstanceOf(NotFoundException.class);

        client.deleteTableBucketPolicy(DeleteTableBucketPolicyRequest.builder().tableBucketARN(bucketArn).build());
        assertThatThrownBy(() -> client.getTableBucketPolicy(GetTableBucketPolicyRequest.builder().tableBucketARN(bucketArn).build()))
                .isInstanceOf(NotFoundException.class);

        client.deleteTable(DeleteTableRequest.builder().tableBucketARN(bucketArn).namespace(NAMESPACE).name(tableName).build());
        assertThatThrownBy(() -> client.getTable(GetTableRequest.builder()
                .tableBucketARN(bucketArn).namespace(NAMESPACE).name(tableName).build()))
                .isInstanceOf(NotFoundException.class);
        client.deleteNamespace(DeleteNamespaceRequest.builder().tableBucketARN(bucketArn).namespace(NAMESPACE).build());
        assertThatThrownBy(() -> client.getNamespace(GetNamespaceRequest.builder()
                .tableBucketARN(bucketArn).namespace(NAMESPACE).build()))
                .isInstanceOf(NotFoundException.class);
        client.deleteTableBucket(DeleteTableBucketRequest.builder().tableBucketARN(bucketArn).build());
        assertThatThrownBy(() -> client.getTableBucket(GetTableBucketRequest.builder().tableBucketARN(bucketArn).build()))
                .isInstanceOf(NotFoundException.class);
    }
}
