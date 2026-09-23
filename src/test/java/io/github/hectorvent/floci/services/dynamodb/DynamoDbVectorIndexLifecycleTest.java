package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.VectorIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two phases a vector index added by UpdateTable walks through.
 *
 * <p>The phases are derived from one stored instant on read, so a test moves through them by
 * rewinding that instant rather than by sleeping, the way {@code AcmIssuanceServiceTest} rewinds
 * a certificate's validation wait. Both durations are 0 under {@code @QuarkusTest}, which is why
 * this coverage lives in a unit test.
 */
class DynamoDbVectorIndexLifecycleTest {

    private static final String REGION = "us-east-1";
    private static final String TABLE = "Docs";
    private static final int ALLOCATION_SECONDS = 4;
    private static final int BACKFILL_SECONDS = 10;

    private DynamoDbService service;

    @BeforeEach
    void setUp() {
        service = new DynamoDbService(new InMemoryStorage<>(), null, null, null,
                new RegionResolver(REGION, "000000000000"), null, null, null, null,
                ALLOCATION_SECONDS, BACKFILL_SECONDS);
        TableDefinition table = service.createTable(TABLE,
                List.of(new KeySchemaElement("pk", "HASH")),
                List.of(new AttributeDefinition("pk", "S")),
                null, null, List.of(), List.of(), List.of(), "PAY_PER_REQUEST", REGION);
        // The handler stamps the billing mode after CreateTable returns, and UpdateTable reads it
        // back whenever the request does not resend BillingMode.
        table.setBillingMode("PAY_PER_REQUEST");
    }

    private static VectorIndex vectorIndex(String indexName) {
        return new VectorIndex(indexName, "embedding", List.of(), "ALL", List.of(), 3L, "COSINE");
    }

    private static DynamoDbService.VectorIndexCreate create(String indexName, String memberPath) {
        return new DynamoDbService.VectorIndexCreate(vectorIndex(indexName), memberPath);
    }

    private TableDefinition addIndex(String indexName) {
        return service.updateTable(TABLE, null, null, List.of(), List.of(), List.of(),
                List.of(create(indexName, "vectorIndexUpdates.1.member.create")), List.of(), null, REGION);
    }

    private TableDefinition deleteIndex(String indexName) {
        return service.updateTable(TABLE, null, null, List.of(), List.of(), List.of(),
                List.of(), List.of(indexName), null, REGION);
    }

    private void elapse(String indexName, int seconds) {
        service.describeTable(TABLE, REGION).findVectorIndex(indexName).orElseThrow()
                .setCreationStartedAt(Instant.now().minusSeconds(seconds));
    }

    private VectorIndex describeIndex(String indexName) {
        return service.describeTable(TABLE, REGION).findVectorIndex(indexName).orElseThrow();
    }

    @Test
    void indexCreatedWithTheTableIsActiveAndNeverReportsBackfilling() {
        service.createTable("Fresh",
                List.of(new KeySchemaElement("pk", "HASH")),
                List.of(new AttributeDefinition("pk", "S")),
                null, null, List.of(), List.of(), List.of(create("vix", "vectorIndexes.1.member")),
                "PAY_PER_REQUEST", REGION);

        TableDefinition described = service.describeTable("Fresh", REGION);
        VectorIndex index = described.findVectorIndex("vix").orElseThrow();

        assertEquals("ACTIVE", described.getTableStatus());
        assertEquals("ACTIVE", index.getIndexStatus());
        assertFalse(service.reportsVectorIndexBackfilling(index));
    }

    @Test
    void resourceAllocationReportsAnUpdatingTableAndBackfillingFalse() {
        assertEquals("UPDATING", addIndex("vix").getTableStatus());

        TableDefinition described = service.describeTable(TABLE, REGION);
        VectorIndex index = described.findVectorIndex("vix").orElseThrow();

        assertEquals("UPDATING", described.getTableStatus());
        assertEquals("CREATING", index.getIndexStatus());
        assertTrue(service.reportsVectorIndexBackfilling(index));
        assertFalse(service.isVectorIndexBackfilling(index));
    }

    @Test
    void deleteDuringResourceAllocationIsRefused() {
        addIndex("vix");

        AwsException refused = assertThrows(AwsException.class, () -> deleteIndex("vix"));

        assertEquals("ResourceInUseException", refused.getErrorCode());
        assertEquals("Attempt to change a resource which is still in use: Index creation is in "
                + "resource allocation phase. Retry deletion during backfilling phase or when "
                + "the index is active. Table: " + TABLE + " Index: vix", refused.getMessage());
        assertEquals("CREATING", describeIndex("vix").getIndexStatus());
    }

    @Test
    void backfillingReportsAnActiveTableAndACreatingIndex() {
        addIndex("vix");
        elapse("vix", ALLOCATION_SECONDS + 1);

        TableDefinition described = service.describeTable(TABLE, REGION);
        VectorIndex index = described.findVectorIndex("vix").orElseThrow();

        assertEquals("ACTIVE", described.getTableStatus());
        assertEquals("CREATING", index.getIndexStatus());
        assertTrue(service.isVectorIndexBackfilling(index));
    }

    @Test
    void deleteDuringBackfillingSucceeds() {
        addIndex("vix");
        elapse("vix", ALLOCATION_SECONDS + 1);

        assertTrue(deleteIndex("vix").getVectorIndexes().isEmpty());
    }

    @Test
    void indexEndsActiveWithBackfillingGone() {
        addIndex("vix");
        elapse("vix", ALLOCATION_SECONDS + BACKFILL_SECONDS + 1);

        TableDefinition described = service.describeTable(TABLE, REGION);
        VectorIndex index = described.findVectorIndex("vix").orElseThrow();

        assertEquals("ACTIVE", described.getTableStatus());
        assertEquals("ACTIVE", index.getIndexStatus());
        assertFalse(service.reportsVectorIndexBackfilling(index));
    }

    @Test
    void twoCreatesInOneRequestExceedTheOnlineIndexLimit() {
        AwsException refused = assertThrows(AwsException.class, () ->
                service.updateTable(TABLE, null, null, List.of(), List.of(), List.of(),
                        List.of(create("one", "vectorIndexUpdates.1.member.create"),
                                create("two", "vectorIndexUpdates.2.member.create")),
                        List.of(), null, REGION));

        assertEquals("LimitExceededException", refused.getErrorCode());
        assertEquals("Subscriber limit exceeded: Only 1 online index can be created or deleted "
                + "simultaneously per table", refused.getMessage());
        assertTrue(service.describeTable(TABLE, REGION).getVectorIndexes().isEmpty());
    }

    @Test
    void secondCreateWhileAnIndexBuildsExceedsTheOnlineIndexLimit() {
        addIndex("first");

        AwsException refused = assertThrows(AwsException.class, () -> addIndex("second"));

        assertEquals("LimitExceededException", refused.getErrorCode());
        assertEquals("Subscriber limit exceeded: Only 1 online index can be created or deleted "
                + "simultaneously per table", refused.getMessage());
        assertEquals(1, service.describeTable(TABLE, REGION).getVectorIndexes().size());
    }

    @Test
    void anotherIndexMayBeCreatedOnceTheFirstIsActive() {
        addIndex("first");
        elapse("first", ALLOCATION_SECONDS + BACKFILL_SECONDS + 1);
        service.describeTable(TABLE, REGION);

        addIndex("second");

        assertEquals(List.of("first", "second"),
                service.describeTable(TABLE, REGION).getVectorIndexes().stream()
                        .map(VectorIndex::getIndexName).toList());
    }

    @Test
    void deleteTableIsRefusedWhileAVectorIndexBuilds() {
        addIndex("vix");

        AwsException refused = assertThrows(AwsException.class,
                () -> service.deleteTable(TABLE, REGION));

        assertEquals("ResourceInUseException", refused.getErrorCode());
        assertEquals("Attempt to change a resource which is still in use: Cannot delete table "
                + "while indexes are being created, updated, or deleted.", refused.getMessage());
    }

    @Test
    void deleteTableSucceedsOnceTheIndexIsActive() {
        addIndex("vix");
        elapse("vix", ALLOCATION_SECONDS + BACKFILL_SECONDS + 1);

        service.deleteTable(TABLE, REGION);

        assertThrows(AwsException.class, () -> service.describeTable(TABLE, REGION));
    }
}
