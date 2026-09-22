package io.github.hectorvent.floci.services.redshift.spectrum;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpectrumCatalogTest {

    private static final String ACCOUNT = "000000000000";
    private SpectrumCatalog catalog;

    @BeforeEach
    void setUp() {
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create(eq("redshift"), eq("redshift-spectrum-schemas.json"), any(TypeReference.class)))
                .thenReturn(AccountAwareStorageBackend.inMemory(ACCOUNT));
        when(factory.create(eq("redshift"), eq("redshift-spectrum-tables.json"), any(TypeReference.class)))
                .thenReturn(AccountAwareStorageBackend.inMemory(ACCOUNT));
        catalog = new SpectrumCatalog(factory);
    }

    @Test
    void createsAndLooksUpExternalSchema() {
        SpectrumExternalSchema schema = new SpectrumExternalSchema(
                ACCOUNT, "dev", "analytics", "s3://warehouse/root/", null);

        catalog.createSchema(schema);

        assertEquals(java.util.Optional.of(schema), catalog.schema(ACCOUNT, "dev", "analytics"));
    }

    @Test
    void createsAndListsExternalTablesInStableNameOrder() {
        SpectrumExternalTable second = table("zebra");
        SpectrumExternalTable first = table("alpha");

        catalog.createTable(second);
        catalog.createTable(first);

        assertEquals(List.of(first, second), catalog.tablesForSchema(ACCOUNT, "dev", "analytics"));
    }

    @Test
    void rejectsDuplicateTableAndDuplicateColumnNames() {
        catalog.createTable(table("events"));

        SpectrumSqlException duplicate = assertThrows(SpectrumSqlException.class,
                () -> catalog.createTable(table("events")));
        assertEquals("42P07", duplicate.sqlState());
        assertThrows(IllegalArgumentException.class, () -> new SpectrumExternalTable(
                ACCOUNT, "dev", "analytics", "duplicate",
                List.of(new SpectrumColumn("id", SpectrumColumn.Type.BIGINT),
                        new SpectrumColumn("id", SpectrumColumn.Type.VARCHAR)),
                "s3://warehouse/events/", ',', '"', '\\', "\\N", 0));
    }

    @Test
    void isolatesLookupsByAccountAndClearsState() {
        SpectrumExternalTable table = table("events");
        catalog.createTable(table);

        assertTrue(catalog.table("111111111111", "dev", "analytics", "events").isEmpty());
        catalog.clear();
        assertTrue(catalog.tablesForSchema(ACCOUNT, "dev", "analytics").isEmpty());
    }

    @Test
    void validatesLocationAndColumnType() {
        assertThrows(IllegalArgumentException.class, () -> new SpectrumExternalSchema(
                ACCOUNT, "dev", "analytics", "https://example.invalid/data", null));
        assertThrows(IllegalArgumentException.class, () -> new SpectrumColumn("id", null));
    }

    private static SpectrumExternalTable table(String name) {
        return new SpectrumExternalTable(
                ACCOUNT, "dev", "analytics", name,
                List.of(new SpectrumColumn("id", SpectrumColumn.Type.BIGINT)),
                "s3://warehouse/events/", ',', '"', '\\', "\\N", 1);
    }
}
