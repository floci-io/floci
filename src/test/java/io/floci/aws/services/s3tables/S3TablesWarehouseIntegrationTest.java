package io.floci.aws.services.s3tables;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Every S3 Tables table has a warehouse, a bucket of its own that an Iceberg client such as Spark's
 * {@code S3TablesCatalog} writes the table's data and metadata files to before committing with
 * {@code UpdateTableMetadataLocation}.
 */
@QuarkusTest
class S3TablesWarehouseIntegrationTest {

    private static final String JSON = "application/json";

    private String encodedBucketArn;
    private S3Client s3;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void createTable() {
        String bucketName = "warehouse-" + System.nanoTime();
        String bucketArn = given().contentType(JSON).body("{\"name\": \"" + bucketName + "\"}")
                .when().put("/buckets").then().statusCode(200).extract().path("arn");
        encodedBucketArn = URLEncoder.encode(bucketArn, StandardCharsets.UTF_8);
        given().urlEncodingEnabled(false).contentType(JSON).body("{\"namespace\": [\"analytics\"]}")
                .when().put("/namespaces/" + encodedBucketArn).then().statusCode(200);
        given().urlEncodingEnabled(false).contentType(JSON).body("{\"name\": \"events\", \"format\": \"ICEBERG\"}")
                .when().put("/tables/" + encodedBucketArn + "/analytics").then().statusCode(200);
        s3 = AwsTestClients.create(S3Client.builder().forcePathStyle(true), "000000000000", Region.US_EAST_1);
    }

    @AfterEach
    void closeClient() {
        s3.close();
    }

    private String warehouseLocation() {
        return given().urlEncodingEnabled(false)
                .when().get("/get-table?tableBucketARN=" + encodedBucketArn + "&namespace=analytics&name=events")
                .then().statusCode(200)
                .body("warehouseLocation", matchesPattern("s3://[0-9a-f-]{36}--table-s3"))
                .body("createdBy", equalTo("000000000000"))
                .body("modifiedBy", equalTo("000000000000"))
                .extract().path("warehouseLocation");
    }

    @Test
    void aTablesWarehouseIsABucketItsMetadataLocationAlsoNames() {
        String warehouse = warehouseLocation();
        String bucket = warehouse.substring("s3://".length());

        s3.putObject(r -> r.bucket(bucket).key("metadata/00000-a.metadata.json"), RequestBody.fromString("{}"));

        given().urlEncodingEnabled(false)
                .when().get("/tables/" + encodedBucketArn + "/analytics/events/metadata-location")
                .then().statusCode(200)
                .body("warehouseLocation", equalTo(warehouse));
        assertEquals(1, s3.listObjectsV2(r -> r.bucket(bucket)).keyCount());
        assertEquals(warehouse, warehouseLocation(), "the warehouse stays the same across reads");
    }

    @Test
    void warehousesAreNotListedAsGeneralPurposeBuckets() {
        String bucket = warehouseLocation().substring("s3://".length());

        assertFalse(s3.listBuckets().buckets().stream().anyMatch(b -> b.name().equals(bucket)));
    }

    @Test
    void deletingATableDeletesEveryVersionInAVersionedWarehouse() {
        String bucket = warehouseLocation().substring("s3://".length());
        s3.putBucketVersioning(r -> r.bucket(bucket).versioningConfiguration(v -> v.status(BucketVersioningStatus.ENABLED)));
        s3.putObject(r -> r.bucket(bucket).key("data/part-0.parquet"), RequestBody.fromString("v1"));
        s3.putObject(r -> r.bucket(bucket).key("data/part-0.parquet"), RequestBody.fromString("v2"));
        s3.deleteObject(r -> r.bucket(bucket).key("data/part-0.parquet"));

        given().urlEncodingEnabled(false)
                .when().delete("/tables/" + encodedBucketArn + "/analytics/events")
                .then().statusCode(204);

        assertThrows(NoSuchBucketException.class, () -> s3.headBucket(r -> r.bucket(bucket)));
    }

    @Test
    void deletingATableDeletesItsWarehouse() {
        String bucket = warehouseLocation().substring("s3://".length());
        s3.putObject(r -> r.bucket(bucket).key("data/part-0.parquet"), RequestBody.fromString("x"));

        given().urlEncodingEnabled(false)
                .when().delete("/tables/" + encodedBucketArn + "/analytics/events")
                .then().statusCode(204);

        assertThrows(NoSuchBucketException.class, () -> s3.headBucket(r -> r.bucket(bucket)));
    }
}
