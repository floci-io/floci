package io.floci.aws.services.glue;

import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.resourcegroupstagging.ResourceGroupsTaggingService;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3tables.S3TablesService;
import io.floci.aws.services.s3tables.model.S3Table;
import io.floci.aws.services.s3tables.model.TableBucket;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Amazon S3 Tables federated into the Glue Data Catalog: once the account creates the {@code s3tablescatalog}
 * catalog on the {@code aws:s3tables} connection, each table bucket is a child catalog whose databases are its
 * namespaces and whose tables are its tables, Iceberg tables with their current metadata location and columns.
 */
@QuarkusTest
class GlueS3TablesCatalogIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String METADATA = """
            {"format-version":2,"table-uuid":"11111111-2222-3333-4444-555555555555","location":"%s",
             "last-sequence-number":0,"last-updated-ms":1,"last-column-id":2,"current-schema-id":0,
             "schemas":[{"type":"struct","schema-id":0,"fields":[
               {"id":1,"name":"id","required":true,"type":"long"},
               {"id":2,"name":"name","required":false,"type":"string","doc":"who"}]}],
             "default-spec-id":0,"partition-specs":[{"spec-id":0,"fields":[]}],"last-partition-id":999,
             "default-sort-order-id":0,"sort-orders":[{"order-id":0,"fields":[]}],"properties":{},
             "current-snapshot-id":-1,"snapshots":[],"snapshot-log":[],"metadata-log":[]}""";

    @Inject
    S3TablesService s3Tables;

    @Inject
    S3Service s3;

    @Inject
    ResourceGroupsTaggingService tagging;

    private TableBucket bucket;
    private String metadataLocation;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void createTableBucket() {
        String name = "lake-" + System.nanoTime();
        RequestScopes.runAs(ACCOUNT, REGION, () -> {
            bucket = s3Tables.createTableBucket(name, null, null, Map.of(), REGION);
            s3Tables.createNamespace(bucket.getArn(), List.of("sales"), REGION);
            S3Table table = s3Tables.createTable(bucket.getArn(), "sales", "orders", "ICEBERG", null, null, null,
                    Map.of(), REGION);
            String warehouse = table.getWarehouseLocation().replaceAll("/$", "");
            metadataLocation = warehouse + "/metadata/00000-a.metadata.json";
            String warehouseBucket = warehouse.substring("s3://".length()).split("/", 2)[0];
            String keyPrefix = warehouse.substring(("s3://" + warehouseBucket).length()).replaceAll("^/", "");
            s3.putObject(warehouseBucket, (keyPrefix.isEmpty() ? "" : keyPrefix + "/") + "metadata/00000-a.metadata.json",
                    METADATA.formatted(warehouse).getBytes(StandardCharsets.UTF_8), "application/json", null);
            s3Tables.updateTableMetadataLocation(bucket.getArn(), "sales", "orders", metadataLocation,
                    table.getVersionToken(), REGION);
        });
    }

    @AfterEach
    void deleteCatalogAndBucket() {
        glue("DeleteCatalog", "{\"CatalogId\":\"s3tablescatalog\"}");
        RequestScopes.runAs(ACCOUNT, REGION, () -> {
            s3Tables.deleteTable(bucket.getArn(), "sales", "orders", REGION);
            s3Tables.deleteNamespace(bucket.getArn(), "sales", REGION);
            s3Tables.deleteTableBucket(bucket.getArn(), REGION);
        });
    }

    private ValidatableResponse glue(String action, String body) {
        return given().contentType(CONTENT_TYPE).header("X-Amz-Target", "AWSGlue." + action).body(body)
                .post("/").then();
    }

    private void integrate() {
        glue("CreateCatalog", """
                {"Name":"s3tablescatalog","CatalogInput":{"FederatedCatalog":{
                  "Identifier":"arn:aws:s3tables:us-east-1:000000000000:bucket/*","ConnectionName":"aws:s3tables"},
                  "AllowFullTableExternalDataAccess":"True"}}""").statusCode(200);
    }

    @Test
    void eachTableBucketIsAChildCatalogOfTheFederatedS3TablesCatalog() {
        String child = "000000000000:s3tablescatalog/" + bucket.getName();
        glue("GetCatalog", "{\"CatalogId\":\"s3tablescatalog/" + bucket.getName() + "\"}").statusCode(400)
                .body("__type", equalTo("EntityNotFoundException"));

        integrate();

        glue("GetCatalog", "{\"CatalogId\":\"s3tablescatalog\"}").statusCode(200)
                .body("Catalog.Name", equalTo("s3tablescatalog"))
                .body("Catalog.FederatedCatalog.ConnectionName", equalTo("aws:s3tables"))
                .body("Catalog.AllowFullTableExternalDataAccess", equalTo("True"));
        glue("GetCatalogs", "{\"ParentCatalogId\":\"s3tablescatalog\"}").statusCode(200)
                .body("CatalogList.CatalogId", hasItem(child));
        glue("GetCatalog", "{\"CatalogId\":\"" + child + "\"}").statusCode(200)
                .body("Catalog.Name", equalTo(bucket.getName()))
                .body("Catalog.ResourceArn", equalTo("arn:aws:glue:us-east-1:000000000000:catalog/s3tablescatalog/"
                        + bucket.getName()))
                .body("Catalog.FederatedCatalog.Identifier", equalTo(bucket.getArn()));
        glue("GetCatalog", "{\"CatalogId\":\"111122223333:s3tablescatalog/" + bucket.getName() + "\"}")
                .statusCode(400).body("__type", equalTo("EntityNotFoundException"));
        glue("GetCatalog", "{\"CatalogId\":\"s3tablescatalog/no-such-bucket\"}").statusCode(400)
                .body("__type", equalTo("EntityNotFoundException"));
    }

    @Test
    void aTableBucketsNamespacesAndTablesAreItsCatalogsDatabasesAndIcebergTables() {
        integrate();
        String catalogId = "s3tablescatalog/" + bucket.getName();

        String qualified = ACCOUNT + ":" + catalogId;
        glue("GetDatabases", "{\"CatalogId\":\"" + catalogId + "\"}").statusCode(200)
                .body("DatabaseList.Name", equalTo(List.of("sales")))
                .body("DatabaseList[0].CatalogId", equalTo(qualified));
        glue("GetDatabase", "{\"CatalogId\":\"" + catalogId + "\",\"Name\":\"sales\"}").statusCode(200)
                .body("Database.Name", equalTo("sales"));
        glue("GetTables", "{\"CatalogId\":\"" + catalogId + "\",\"DatabaseName\":\"sales\"}").statusCode(200)
                .body("TableList.Name", equalTo(List.of("orders")));
        glue("GetTable", "{\"CatalogId\":\"" + catalogId + "\",\"DatabaseName\":\"sales\",\"Name\":\"orders\"}")
                .statusCode(200)
                .body("Table.CatalogId", equalTo(qualified))
                .body("Table.DatabaseName", equalTo("sales"))
                .body("Table.Parameters.table_type", equalTo("ICEBERG"))
                .body("Table.Parameters.metadata_location", equalTo(metadataLocation))
                .body("Table.StorageDescriptor.Columns.Name", equalTo(List.of("id", "name")))
                .body("Table.StorageDescriptor.Columns.Type", equalTo(List.of("bigint", "string")))
                .body("Table.StorageDescriptor.Columns[1].Comment", equalTo("who"));
        glue("GetTable", "{\"CatalogId\":\"" + catalogId + "\",\"DatabaseName\":\"sales\",\"Name\":\"missing\"}")
                .statusCode(400).body("__type", equalTo("EntityNotFoundException"));
        glue("GetDatabases", "{}").statusCode(200).body("DatabaseList.Name", not(hasItem("sales")));
        glue("GetDatabases", "{\"CatalogId\":\"" + ACCOUNT + "\"}").statusCode(200)
                .body("DatabaseList.Name", not(hasItem("sales")));
    }

    @Test
    void aTableWithoutMetadataYetKeepsItsWarehouseLocation() {
        integrate();
        S3Table fresh = RequestScopes.callAs(ACCOUNT, REGION, () -> s3Tables.createTable(bucket.getArn(), "sales",
                "fresh", "ICEBERG", null, null, null, Map.of(), REGION));
        try {
            glue("GetTable", "{\"CatalogId\":\"s3tablescatalog/" + bucket.getName()
                    + "\",\"DatabaseName\":\"sales\",\"Name\":\"fresh\"}").statusCode(200)
                    .body("Table.StorageDescriptor.Location", equalTo(fresh.getWarehouseLocation()))
                    .body("Table.Parameters.table_type", equalTo("ICEBERG"))
                    .body("Table.Parameters.metadata_location", nullValue());
        } finally {
            RequestScopes.runAs(ACCOUNT, REGION, () -> s3Tables.deleteTable(bucket.getArn(), "sales", "fresh", REGION));
        }
    }

    @Test
    void aFederationIdentifierNamingOneBucketFederatesOnlyThatBucket() {
        glue("CreateCatalog", "{\"Name\":\"s3tablescatalog\",\"CatalogInput\":{\"FederatedCatalog\":{"
                + "\"Identifier\":\"" + bucket.getArn() + "\",\"ConnectionName\":\"aws:s3tables\"}}}")
                .statusCode(200);
        glue("GetCatalogs", "{\"ParentCatalogId\":\"s3tablescatalog\"}").statusCode(200)
                .body("CatalogList.Name", equalTo(List.of(bucket.getName())));
        glue("UpdateCatalog", "{\"CatalogId\":\"s3tablescatalog\",\"CatalogInput\":{\"FederatedCatalog\":{"
                + "\"Identifier\":\"arn:aws:s3tables:eu-west-1:000000000000:bucket/*\","
                + "\"ConnectionName\":\"aws:s3tables\"}}}").statusCode(400)
                .body("__type", equalTo("InvalidInputException"));
    }

    @Test
    void aRefusedInputChangesNothingAndADeletedCatalogTakesItsTagsAlong() {
        glue("CreateCatalog", "{\"Name\":\"s3tablescatalog\",\"CatalogInput\":{\"Description\":\"first\","
                + "\"FederatedCatalog\":{\"Identifier\":\"arn:aws:s3tables:us-east-1:000000000000:bucket/*\","
                + "\"ConnectionName\":\"aws:s3tables\"}},\"Tags\":{\"team\":\"data\"}}").statusCode(200);
        glue("UpdateCatalog", "{\"CatalogId\":\"s3tablescatalog\",\"CatalogInput\":{\"Description\":\"second\","
                + "\"AllowFullTableExternalDataAccess\":\"Maybe\"}}").statusCode(400)
                .body("__type", equalTo("InvalidInputException"));
        glue("GetCatalog", "{\"CatalogId\":\"s3tablescatalog\"}").statusCode(200)
                .body("Catalog.Description", equalTo("first"));
        glue("UpdateCatalog", "{\"CatalogId\":\"s3tablescatalog\"}").statusCode(400)
                .body("__type", equalTo("InvalidInputException"));
        String arn = "arn:aws:glue:us-east-1:000000000000:catalog/s3tablescatalog";
        assertEquals(Map.of("team", "data"), tagging.getTagsForResource(REGION, arn));

        glue("DeleteCatalog", "{\"CatalogId\":\"s3tablescatalog\"}").statusCode(200);

        assertTrue(tagging.getTagsForResource(REGION, arn).isEmpty());
    }

    @Test
    void aCatalogIsCreatedOnceAndOnlyACreatedCatalogIsChangedOrDeleted() {
        integrate();
        glue("CreateCatalog", "{\"Name\":\"s3tablescatalog\",\"CatalogInput\":{}}").statusCode(400)
                .body("__type", equalTo("AlreadyExistsException"));
        for (String badName : List.of("bad/name", "000000000000", "111122223333:other")) {
            glue("CreateCatalog", "{\"Name\":\"" + badName + "\",\"CatalogInput\":{}}").statusCode(400)
                    .body("__type", equalTo("InvalidInputException"));
        }
        glue("CreateCatalog", "{\"Name\":\"no-input\"}").statusCode(400)
                .body("__type", equalTo("InvalidInputException"));
        glue("UpdateCatalog", "{\"CatalogId\":\"s3tablescatalog\",\"CatalogInput\":{\"Description\":\"tables\"}}")
                .statusCode(200);
        glue("GetCatalog", "{\"CatalogId\":\"s3tablescatalog\"}").statusCode(200)
                .body("Catalog.Description", equalTo("tables"));
        glue("DeleteCatalog", "{\"CatalogId\":\"s3tablescatalog/" + bucket.getName() + "\"}").statusCode(400)
                .body("__type", equalTo("InvalidInputException"));
    }
}
