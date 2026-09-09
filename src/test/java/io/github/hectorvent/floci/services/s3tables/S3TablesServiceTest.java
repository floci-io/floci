package io.github.hectorvent.floci.services.s3tables;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.s3tables.model.S3Table;
import io.github.hectorvent.floci.services.s3tables.model.TableBucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class S3TablesServiceTest {
    private static final String ACCOUNT_ID = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String BUCKET = "unit-test-table-bucket";

    private S3TablesService service;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = new StorageFactory(null, null) {
            @Override
            public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                    TypeReference<Map<String, V>> typeReference) {
                return AccountAwareStorageBackend.inMemory(ACCOUNT_ID);
            }
        };
        service = new S3TablesService(storageFactory, new RegionResolver(REGION, ACCOUNT_ID));
    }

    @Test
    void isolatesSameBucketNameByRegion() {
        TableBucket east = service.createTableBucket(BUCKET, null, null, Map.of(), REGION);
        TableBucket west = service.createTableBucket(BUCKET, null, null, Map.of(), "eu-west-1");

        assertEquals("arn:aws:s3tables:us-east-1:000000000000:bucket/" + BUCKET, east.getArn());
        assertEquals("arn:aws:s3tables:eu-west-1:000000000000:bucket/" + BUCKET, west.getArn());
        assertEquals(List.of(east), service.listTableBuckets(null, REGION));
        assertEquals(List.of(west), service.listTableBuckets(null, "eu-west-1"));
    }

    @Test
    void rejectsDuplicateBucketsAndNonEmptyParents() {
        TableBucket bucket = createBucket();
        String arn = bucket.getArn();

        assertError("ConflictException", () -> service.createTableBucket(BUCKET, null, null, Map.of(), REGION));

        service.createNamespace(arn, List.of("analytics"), REGION);
        assertError("ConflictException", () -> service.deleteTableBucket(arn, REGION));

        createTable(arn, "analytics", "events");
        assertError("ConflictException", () -> service.deleteNamespace(arn, "analytics", REGION));

        service.deleteTable(arn, "analytics", "events", REGION);
        service.deleteNamespace(arn, "analytics", REGION);
        service.deleteTableBucket(arn, REGION);
        assertError("NotFoundException", () -> service.getTableBucket(arn, REGION));
    }

    @Test
    void updatesMetadataOnlyWithTheCurrentVersionToken() {
        String arn = createBucketWithNamespace("analytics");
        S3Table created = createTable(arn, "analytics", "events");
        String originalToken = created.getVersionToken();

        S3Table updated = service.updateTableMetadataLocation(arn, "analytics", "events",
                "s3://warehouse/events/metadata/v2.json", originalToken, REGION);

        assertEquals("s3://warehouse/events/metadata/v2.json", updated.getMetadataLocation());
        assertNotEquals(originalToken, updated.getVersionToken());
        assertError("ConflictException", () -> service.updateTableMetadataLocation(arn, "analytics", "events",
                "s3://warehouse/events/metadata/v3.json", originalToken, REGION));
        assertEquals("s3://warehouse/events/metadata/v2.json",
                service.getTable(arn, "analytics", "events", REGION).getMetadataLocation());
    }

    @Test
    void renameMovesTableAndRotatesItsVersionToken() {
        String arn = createBucketWithNamespace("analytics");
        service.createNamespace(arn, List.of("reporting"), REGION);
        S3Table created = createTable(arn, "analytics", "events");
        String originalToken = created.getVersionToken();

        S3Table renamed = service.renameTable(arn, "analytics", "events", "reporting", "daily_events",
                originalToken, REGION);

        assertEquals("reporting", renamed.getNamespace());
        assertEquals("daily_events", renamed.getName());
        assertEquals(arn + "/table/daily_events", renamed.getArn());
        assertNotEquals(originalToken, renamed.getVersionToken());
        assertError("NotFoundException", () -> service.getTable(arn, "analytics", "events", REGION));
        assertEquals(renamed, service.getTable(arn, "reporting", "daily_events", REGION));
    }

    @Test
    void roundTripsPoliciesAndTypedMaintenanceConfigurations() {
        String arn = createBucketWithNamespace("analytics");
        createTable(arn, "analytics", "events");
        Map<String, Object> bucketMaintenance = Map.of("unreferencedFileRemoval", Map.of("status", "enabled"));
        Map<String, Object> tableMaintenance = Map.of("status", "enabled");

        service.putTableBucketPolicy(arn, "{\"Version\":\"2012-10-17\"}", REGION);
        service.putTableBucketMaintenance(arn, "UNREFERENCED_FILE_REMOVAL", bucketMaintenance, REGION);
        service.putTablePolicy(arn, "analytics", "events", "{\"Statement\":[]}", REGION);
        service.putTableMaintenance(arn, "analytics", "events", "ICEBERG_COMPACTION", tableMaintenance, REGION);

        assertEquals("{\"Version\":\"2012-10-17\"}", service.getTableBucketPolicy(arn, REGION));
        assertEquals(bucketMaintenance, service.getTableBucketMaintenance(arn, "UNREFERENCED_FILE_REMOVAL", REGION));
        assertEquals(Map.of("UNREFERENCED_FILE_REMOVAL", bucketMaintenance),
                service.getTableBucketMaintenanceConfigurations(arn, REGION));
        assertEquals("{\"Statement\":[]}", service.getTablePolicy(arn, "analytics", "events", REGION));
        assertEquals(tableMaintenance,
                service.getTableMaintenance(arn, "analytics", "events", "ICEBERG_COMPACTION", REGION));
        assertEquals(Map.of("ICEBERG_COMPACTION", tableMaintenance),
                service.getTableMaintenanceConfigurations(arn, "analytics", "events", REGION));

        service.deleteTableBucketPolicy(arn, REGION);
        service.deleteTablePolicy(arn, "analytics", "events", REGION);
        assertError("NotFoundException", () -> service.getTableBucketPolicy(arn, REGION));
        assertError("NotFoundException", () -> service.getTablePolicy(arn, "analytics", "events", REGION));
    }

    private TableBucket createBucket() {
        return service.createTableBucket(BUCKET, null, null, Map.of(), REGION);
    }

    private String createBucketWithNamespace(String namespace) {
        TableBucket bucket = createBucket();
        service.createNamespace(bucket.getArn(), List.of(namespace), REGION);
        return bucket.getArn();
    }

    private S3Table createTable(String arn, String namespace, String name) {
        return service.createTable(arn, namespace, name, "ICEBERG",
                Map.of("iceberg", Map.of("metadataLocation", "s3://warehouse/" + name + "/metadata/v1.json")),
                null, null, Map.of(), REGION);
    }

    private void assertError(String expectedCode, org.junit.jupiter.api.function.Executable action) {
        AwsException exception = assertThrows(AwsException.class, action);
        assertEquals(expectedCode, exception.getErrorCode());
    }

    @Test
    void allowsOnlyOneConcurrentMetadataUpdatePerVersionToken() throws Exception {
        String arn = createBucketWithNamespace("analytics");
        S3Table created = createTable(arn, "analytics", "events");
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);

        try {
            var first = executor.submit(() -> updateMetadataWithToken(arn, created.getVersionToken(),
                    "s3://warehouse/events/metadata/v2.json", ready, start));
            var second = executor.submit(() -> updateMetadataWithToken(arn, created.getVersionToken(),
                    "s3://warehouse/events/metadata/v3.json", ready, start));

            assertEquals(true, ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            List<String> outcomes = List.of(first.get(5, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(5, java.util.concurrent.TimeUnit.SECONDS));

            assertEquals(1L, outcomes.stream().filter("updated"::equals).count());
            assertEquals(1L, outcomes.stream().filter("ConflictException"::equals).count());
        } finally {
            executor.shutdownNow();
        }
    }

    private String updateMetadataWithToken(String arn, String token, String metadataLocation,
                                           java.util.concurrent.CountDownLatch ready,
                                           java.util.concurrent.CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
            return "timed out";
        }
        try {
            service.updateTableMetadataLocation(arn, "analytics", "events", metadataLocation, token, REGION);
            return "updated";
        } catch (AwsException exception) {
            return exception.getErrorCode();
        }
    }

}
