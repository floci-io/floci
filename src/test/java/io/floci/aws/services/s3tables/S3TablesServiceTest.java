package io.floci.aws.services.s3tables;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3tables.model.S3Table;
import io.floci.aws.services.s3tables.model.TableBucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3TablesServiceTest {
    private static final String ACCOUNT_ID = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String BUCKET = "unit-test-table-bucket";

    private S3TablesService service;
    private S3Service s3Service;
    private AccountAwareStorageBackend<TableBucket> store;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = new StorageFactory(null, null) {
            @Override
            @SuppressWarnings("unchecked")
            public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                    TypeReference<Map<String, V>> typeReference) {
                store = Mockito.spy(AccountAwareStorageBackend.<TableBucket>inMemory(ACCOUNT_ID));
                return (AccountAwareStorageBackend<V>) store;
            }
        };
        s3Service = Mockito.mock(S3Service.class);
        Mockito.when(s3Service.listObjectsWithPrefixes(Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.anyInt(),
                Mockito.any(), Mockito.any())).thenReturn(new S3Service.ListObjectsResult(List.of(), List.of(), false, null));
        Mockito.when(s3Service.listObjectVersions(Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.anyInt(),
                Mockito.any(), Mockito.any())).thenReturn(new S3Service.ListVersionsResult(List.of(), List.of(), false, null, null));
        service = new S3TablesService(storageFactory, new RegionResolver(REGION, ACCOUNT_ID), s3Service);
    }

    /**
     * The tags IAM gives a resource-tag condition: the table bucket's or a table's, read on the whole ARN in the
     * account and Region it names, and none for anything else.
     */
    @Test
    void storedTagsReadsTheBucketOrTheTableItsArnNames() {
        TableBucket bucket = service.createTableBucket(BUCKET, null, null, Map.of("team", "data"), REGION);
        service.createNamespace(bucket.getArn(), List.of("analytics"), REGION);
        S3Table table = service.createTable(bucket.getArn(), "analytics", "events", "ICEBERG", null, null, null,
                Map.of("owner", "alice"), REGION);

        assertEquals(Map.of("team", "data"), service.storedTags(bucket.getArn()));
        assertEquals(Map.of("owner", "alice"), service.storedTags(table.getArn()));
        assertEquals(Map.of(), service.storedTags(bucket.getArn() + "/table/other"));
        assertEquals(Map.of(), service.storedTags(bucket.getArn().replace(":" + ACCOUNT_ID + ":", ":111122223333:")));
        assertEquals(Map.of(), service.storedTags(bucket.getArn().replace(REGION, "eu-west-1")));
        assertEquals(Map.of(), service.storedTags("arn:aws:s3:::" + BUCKET));
    }

    /** A bucket stored before storage was account-aware is read in place for the default account, never migrated. */
    @Test
    void storedTagsReadsAPreMultiAccountBucketOfTheDefaultAccountInPlace() {
        InMemoryStorage<String, TableBucket> raw = new InMemoryStorage<>();
        StorageFactory legacyFactory = new StorageFactory(null, null) {
            @Override
            @SuppressWarnings("unchecked")
            public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                    TypeReference<Map<String, V>> typeReference) {
                return (AccountAwareStorageBackend<V>) new AccountAwareStorageBackend<>(raw, null, ACCOUNT_ID);
            }
        };
        S3TablesService legacy = new S3TablesService(legacyFactory, new RegionResolver(REGION, ACCOUNT_ID), s3Service);
        String arn = "arn:aws:s3tables:" + REGION + ":" + ACCOUNT_ID + ":bucket/" + BUCKET;
        raw.put(REGION + "::" + BUCKET, new TableBucket(BUCKET, arn, "2026-01-01T00:00:00Z", ACCOUNT_ID, null, null,
                Map.of("team", "data")));

        assertEquals(Map.of("team", "data"), legacy.storedTags(arn));
        assertEquals(Map.of(), legacy.storedTags(arn.replace(":" + ACCOUNT_ID + ":", ":111122223333:")));
        assertTrue(raw.get(REGION + "::" + BUCKET).isPresent());
    }

    @Test
    void listingAndUpdatingATableFromBeforeWarehousesGiveItOne() {
        String arn = service.createTableBucket(BUCKET, null, null, Map.of(), REGION).getArn();
        service.createNamespace(arn, List.of("analytics"), REGION);
        S3Table legacy = createTable(arn, "analytics", "events");
        legacy.setWarehouseLocation(null);

        S3Table listed = service.listTables(arn, "analytics", null, REGION).getFirst();
        listed.setWarehouseLocation(null);
        S3Table updated = service.updateTableMetadataLocation(arn, "analytics", "events", "s3://w/m.json",
                listed.getVersionToken(), REGION);

        assertTrue(updated.getWarehouseLocation().matches("s3://[0-9a-f-]{36}--table-s3"), updated.getWarehouseLocation());
        Mockito.verify(s3Service, Mockito.times(3)).createBucket(Mockito.endsWith("--table-s3"), Mockito.eq(REGION));
    }

    @Test
    void aFailedSaveTakesTheNewTableAndItsWarehouseBackOut() {
        String arn = service.createTableBucket(BUCKET, null, null, Map.of(), REGION).getArn();
        service.createNamespace(arn, List.of("analytics"), REGION);
        Mockito.doThrow(new IllegalStateException("disk full")).when(store).put(Mockito.anyString(), Mockito.any());

        assertThrows(IllegalStateException.class, () -> createTable(arn, "analytics", "events"));

        Mockito.verify(s3Service).deleteBucket(Mockito.endsWith("--table-s3"));
        Mockito.doCallRealMethod().when(store).put(Mockito.anyString(), Mockito.any());
        assertEquals(List.of(), service.listTables(arn, "analytics", null, REGION));
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
    void tablesOfOneNameInTwoNamespacesHaveTheirOwnArnsEndingInAMintedId() {
        String arn = createBucketWithNamespace("analytics");
        service.createNamespace(arn, List.of("staging"), REGION);

        S3Table analytics = createTable(arn, "analytics", "events");
        S3Table staging = createTable(arn, "staging", "events");

        assertTrue(analytics.getArn().matches(Pattern.quote(arn) + "/table/[0-9a-f-]{36}"), analytics.getArn());
        assertNotEquals(analytics.getArn(), staging.getArn());
        assertEquals("staging", service.getTableByArn(staging.getArn(), REGION).getNamespace());
        assertEquals(Optional.of(analytics.getArn()), service.tableArn(arn, "analytics", "events", REGION));
        assertEquals(Optional.empty(), service.tableArn(arn, "analytics", "missing", REGION));
        assertError("NotFoundException", () -> service.getTableByArn(arn + "/table/unknown", REGION));
    }

    @Test
    void renameMovesTableAndRotatesItsVersionToken() {
        String arn = createBucketWithNamespace("analytics");
        service.createNamespace(arn, List.of("reporting"), REGION);
        S3Table created = createTable(arn, "analytics", "events");
        String originalToken = created.getVersionToken();
        String tableArn = created.getArn();

        S3Table renamed = service.renameTable(arn, "analytics", "events", "reporting", "daily_events",
                originalToken, REGION);

        assertEquals("reporting", renamed.getNamespace());
        assertEquals("daily_events", renamed.getName());
        assertEquals(tableArn, renamed.getArn(), "a rename keeps the table's id, so its ARN");
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

    private void assertError(String expectedCode, Executable action) {
        AwsException exception = assertThrows(AwsException.class, action);
        assertEquals(expectedCode, exception.getErrorCode());
    }

    @Test
    void allowsOnlyOneConcurrentMetadataUpdatePerVersionToken() throws Exception {
        String arn = createBucketWithNamespace("analytics");
        S3Table created = createTable(arn, "analytics", "events");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<String> first = executor.submit(() -> updateMetadataWithToken(arn, created.getVersionToken(),
                    "s3://warehouse/events/metadata/v2.json", ready, start));
            Future<String> second = executor.submit(() -> updateMetadataWithToken(arn, created.getVersionToken(),
                    "s3://warehouse/events/metadata/v3.json", ready, start));

            assertEquals(true, ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<String> outcomes = List.of(first.get(5, TimeUnit.SECONDS),
                    second.get(5, TimeUnit.SECONDS));

            assertEquals(1L, outcomes.stream().filter("updated"::equals).count());
            assertEquals(1L, outcomes.stream().filter("ConflictException"::equals).count());
        } finally {
            executor.shutdownNow();
        }
    }

    private String updateMetadataWithToken(String arn, String token, String metadataLocation,
                                           CountDownLatch ready,
                                           CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
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
