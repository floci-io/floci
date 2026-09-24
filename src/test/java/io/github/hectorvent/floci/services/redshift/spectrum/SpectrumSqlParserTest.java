package io.github.hectorvent.floci.services.redshift.spectrum;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpectrumSqlParserTest {

    private final SpectrumStatementParser parser = new SpectrumStatementParser();

    @Test
    void parsesExternalSchemaAndNormalizesUnquotedIdentifiers() {
        SpectrumStatement statement = parser.parse(
                "CREATE EXTERNAL SCHEMA Analytics FROM DATA CATALOG DATABASE 'warehouse' IAM_ROLE 'arn:aws:iam::000000000000:role/Reader'")
                .orElseThrow();

        SpectrumStatement.CreateSchema schema = assertInstanceOf(SpectrumStatement.CreateSchema.class, statement);
        assertEquals("analytics", schema.schemaName());
        assertEquals("warehouse", schema.databaseName());
        assertEquals("arn:aws:iam::000000000000:role/Reader", schema.iamRoleArn());
    }

    @Test
    void parsesQuotedIdentifiersEscapedLiteralsAndCsvProperties() {
        SpectrumStatement statement = parser.parse(""
                + "-- define the table\n"
                + "CREATE EXTERNAL TABLE \"Sales\".\"Order\"\"Items\" ("
                + "order_id BIGINT, description VARCHAR, active BOOLEAN, created DATE) "
                + "STORED AS TEXTFILE LOCATION 's3://bucket/a''b/' "
                + "TBLPROPERTIES ("
                + "'skip.header.line.count'='1', 'field.delim'='|', "
                + "'serialization.format'='|', 'quoteChar'='\"', 'escapeChar'='\\', "
                + "'serialization.null.format'='NULL')").orElseThrow();

        SpectrumStatement.CreateTable table = assertInstanceOf(SpectrumStatement.CreateTable.class, statement);
        assertEquals("Sales", table.schemaName());
        assertEquals("Order\"Items", table.tableName());
        assertEquals(List.of(
                new SpectrumColumn("order_id", SpectrumColumn.Type.BIGINT),
                new SpectrumColumn("description", SpectrumColumn.Type.VARCHAR),
                new SpectrumColumn("active", SpectrumColumn.Type.BOOLEAN),
                new SpectrumColumn("created", SpectrumColumn.Type.DATE)), table.columns());
        assertEquals("s3://bucket/a'b/", table.location());
        assertEquals('|', table.delimiter());
        assertEquals('"', table.quote());
        assertEquals('\\', table.escape());
        assertEquals("NULL", table.nullValue());
        assertEquals(1, table.headerLines());
    }

    @Test
    void acceptsOptionalExternalDatabaseClauseAndTrailingSemicolon() {
        SpectrumStatement statement = parser.parse(
                "CREATE EXTERNAL SCHEMA s FROM DATA CATALOG DATABASE 'db' IAM_ROLE 'role' "
                        + "CREATE EXTERNAL DATABASE IF NOT EXISTS;").orElseThrow();

        SpectrumStatement.CreateSchema schema = assertInstanceOf(SpectrumStatement.CreateSchema.class, statement);
        assertEquals("s", schema.schemaName());
    }

    @Test
    void returnsEmptyForNonSpectrumSql() {
        assertTrue(parser.parse("SELECT 1").isEmpty());
    }

    @Test
    void rejectsTrailingStatementsDuplicatePropertiesAndUnsupportedFormat() {
        assertThrows(SpectrumSqlException.class, () -> parser.parse(
                "CREATE EXTERNAL SCHEMA s FROM DATA CATALOG DATABASE 'db' IAM_ROLE 'role'; SELECT 1"));
        assertThrows(SpectrumSqlException.class, () -> parser.parse(
                "CREATE EXTERNAL TABLE s.t (id INTEGER) STORED AS PARQUET LOCATION 's3://b/p'"));
        assertThrows(SpectrumSqlException.class, () -> parser.parse(
                "CREATE EXTERNAL TABLE s.t (id INTEGER) STORED AS TEXTFILE LOCATION 's3://b/p' "
                        + "TBLPROPERTIES ('field.delim'=',', 'field.delim'='|')"));
        assertThrows(SpectrumSqlException.class, () -> parser.parse("CREATE EXTERNAL VIEW v AS SELECT 1"));
    }
}
