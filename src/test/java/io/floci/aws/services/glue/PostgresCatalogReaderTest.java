package io.floci.aws.services.glue;

import io.floci.aws.services.glue.JdbcCatalogReader.JdbcColumn;
import io.floci.aws.services.glue.JdbcCatalogReader.JdbcTableSchema;
import io.floci.aws.services.glue.PostgresCatalogReader.CatalogRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PostgresCatalogReaderTest {

    @Test
    void groupsColumnsByTableInQueryOrder() {
        List<JdbcTableSchema> tables = PostgresCatalogReader.group(List.of(
                new CatalogRow("public", "orders", false, "placed orders", "id", "integer", "key"),
                new CatalogRow("public", "orders", false, "placed orders", "total", "bigint", null),
                new CatalogRow("public", "recent", true, null, "id", "integer", null)));

        assertEquals(List.of(
                new JdbcTableSchema("public", "orders", false, "placed orders", List.of(
                        new JdbcColumn("id", "integer", "key"), new JdbcColumn("total", "bigint", null))),
                new JdbcTableSchema("public", "recent", true, null, List.of(new JdbcColumn("id", "integer", null)))),
                tables);
    }

    @Test
    void keepsApartTablesWhoseQuotedNamesJoinToTheSameDottedName() {
        List<JdbcTableSchema> tables = PostgresCatalogReader.group(List.of(
                new CatalogRow("a", "b.c", false, null, "left_id", "integer", null),
                new CatalogRow("a.b", "c", false, null, "right_id", "bigint", null)));

        assertEquals(List.of(
                new JdbcTableSchema("a", "b.c", false, null, List.of(new JdbcColumn("left_id", "integer", null))),
                new JdbcTableSchema("a.b", "c", false, null, List.of(new JdbcColumn("right_id", "bigint", null)))),
                tables);
    }
}
