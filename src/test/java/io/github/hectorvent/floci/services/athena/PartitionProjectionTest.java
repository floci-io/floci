package io.github.hectorvent.floci.services.athena;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.glue.model.StorageDescriptor;
import io.github.hectorvent.floci.services.glue.model.Table;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartitionProjectionTest {

    private static final String BUCKET = "s3://audit-logs";

    private static Column column(String name) {
        Column c = new Column();
        c.setName(name);
        c.setType("string");
        return c;
    }

    private static Table table(String name, Map<String, String> params, List<Column> partitionKeys) {
        Table t = new Table();
        t.setName(name);
        StorageDescriptor sd = new StorageDescriptor();
        sd.setLocation(BUCKET + "/");
        t.setStorageDescriptor(sd);
        t.setParameters(params);
        t.setPartitionKeys(partitionKeys);
        return t;
    }

    private static Map<String, String> projecting(String... extra) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("projection.enabled", "true");
        for (int i = 0; i + 1 < extra.length; i += 2) {
            m.put(extra[i], extra[i + 1]);
        }
        return m;
    }

    // ---- read path ----

    /**
     * A workgroup writing results under the table's root is the case that breaks: reading the root
     * wholesale picks the results back up as though they were table data.
     */
    @Test
    void templateGivesAPartitionPathThatExcludesSiblingPrefixes() {
        Table t = table("audit_events", projecting(
                "storage.location.template", BUCKET + "/tenant=${tenant}/year=${year}/month=${month}/day=${day}"),
                List.of(column("tenant"), column("year"), column("month"), column("day")));

        String path = PartitionProjection.readPath(t, BUCKET);

        assertEquals(BUCKET + "/tenant=*/year=*/month=*/day=*", path);
        assertTrue(path.startsWith(BUCKET + "/tenant="), path);
    }

    /** Without a template the default column=value layout is built from the partition keys. */
    @Test
    void partitionKeysGiveTheDefaultLayoutWhenNoTemplateIsSet() {
        Table t = table("t", projecting(), List.of(column("tenant"), column("year")));

        assertEquals(BUCKET + "/tenant=*/year=*", PartitionProjection.readPath(t, BUCKET));
    }

    /** Projection off means the location is read as before. */
    @Test
    void withoutProjectionTheLocationIsUnchanged() {
        Table t = table("t", Map.of(), List.of(column("tenant")));

        assertEquals(BUCKET, PartitionProjection.readPath(t, BUCKET));
    }

    /** A projecting table declaring no partitions has no layout to build. */
    @Test
    void projectionWithoutPartitionKeysLeavesTheLocationUnchanged() {
        assertEquals(BUCKET, PartitionProjection.readPath(table("t", projecting(), null), BUCKET));
    }

    @Test
    void trailingSlashOnTheTemplateIsDropped() {
        Table t = table("t", projecting("storage.location.template", BUCKET + "/tenant=${tenant}/"),
                List.of(column("tenant")));

        assertEquals(BUCKET + "/tenant=*", PartitionProjection.readPath(t, BUCKET));
    }

    // ---- injected columns ----

    private Table injectedTenantTable() {
        return table("audit_events", projecting("projection.tenant.type", "injected"),
                List.of(column("tenant")));
    }

    @Test
    void queryWithoutAnyMentionOfAnInjectedColumnIsRejected() {
        AwsException e = assertThrows(AwsException.class, () ->
                PartitionProjection.assertInjectedColumnsFiltered(
                        "SELECT * FROM audit_events", List.of(injectedTenantTable())));

        assertTrue(e.getMessage().startsWith("CONSTRAINT_VIOLATION:"), e.getMessage());
        assertTrue(e.getMessage().contains("tenant"), e.getMessage());
    }

    @Test
    void queryConstrainingTheInjectedColumnIsAccepted() {
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events WHERE tenant = 'abc'", List.of(injectedTenantTable())));
    }

    /** A table the query never names must not fail it. */
    @Test
    void unrelatedQueryAgainstAnotherTableIsUnaffected() {
        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM other_table", List.of(injectedTenantTable())));
    }

    /** A non-injected projected column generates its own values and needs no filter. */
    @Test
    void nonInjectedPartitionColumnsNeedNoFilter() {
        Table t = table("t", projecting("projection.year.type", "integer"), List.of(column("year")));

        assertDoesNotThrow(() -> PartitionProjection.assertInjectedColumnsFiltered("SELECT * FROM t", List.of(t)));
    }

    /** Identifiers match on word boundaries, so a longer name that contains one does not count. */
    @Test
    void aLongerIdentifierContainingTheColumnNameDoesNotCountAsAMention() {
        assertThrows(AwsException.class, () -> PartitionProjection.assertInjectedColumnsFiltered(
                "SELECT * FROM audit_events WHERE tenant_id = 'abc'", List.of(injectedTenantTable())));
    }
}
