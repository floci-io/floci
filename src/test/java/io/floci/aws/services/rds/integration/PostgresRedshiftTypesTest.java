package io.floci.aws.services.rds.integration;

import io.floci.aws.services.rds.integration.PostgresRedshiftTypes.MappedColumn;
import io.floci.aws.services.rds.integration.PostgresRedshiftTypes.SourceColumn;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Redshift types AWS documents for an Aurora PostgreSQL source, and the types it does not replicate. */
class PostgresRedshiftTypesTest {

    private static SourceColumn base(String typeName, String formatted) {
        return new SourceColumn("c", typeName, "b", "N", true, null, null, false, formatted);
    }

    private static MappedColumn mapped(SourceColumn column) {
        return PostgresRedshiftTypes.map(column).orElseThrow();
    }

    @Test
    void keepsNumericAndTemporalTypes() {
        assertEquals("SMALLINT", mapped(base("int2", "smallint")).redshiftType());
        assertEquals("INTEGER", mapped(base("int4", "integer")).redshiftType());
        assertEquals("BIGINT", mapped(base("int8", "bigint")).redshiftType());
        assertEquals("DOUBLE PRECISION", mapped(base("float8", "double precision")).redshiftType());
        assertEquals("TIMESTAMPTZ", mapped(base("timestamptz", "timestamp with time zone")).redshiftType());
        assertEquals("\"c\"", mapped(base("int4", "integer")).selectExpression());
    }

    @Test
    void mapsNumericWithItsPrecisionAndRefusesOneWithout() {
        assertEquals("DECIMAL(10,2)", mapped(base("numeric", "numeric(10,2)")).redshiftType());
        assertTrue(PostgresRedshiftTypes.map(base("numeric", "numeric")).isEmpty());
        assertTrue(PostgresRedshiftTypes.map(base("numeric", "numeric(40,2)")).isEmpty());
    }

    @Test
    void readsTextTypesAsText() {
        MappedColumn text = mapped(base("text", "text"));
        assertEquals("VARCHAR(65535)", text.redshiftType());
        assertEquals("\"c\"::text", text.selectExpression());
        assertEquals("VARCHAR(65535)", mapped(base("varchar", "character varying(20)")).redshiftType());
        assertEquals("VARCHAR(36)", mapped(base("uuid", "uuid")).redshiftType());
        assertEquals("VARCHAR(19)", mapped(base("time", "time without time zone")).redshiftType());
        assertEquals("CHAR(3)", mapped(base("bpchar", "character(3)")).redshiftType());
    }

    @Test
    void mapsJsonAndArraysToSuper() {
        MappedColumn jsonb = mapped(base("jsonb", "jsonb"));
        assertEquals("pg_catalog.super", jsonb.redshiftType());
        assertEquals("\"c\"::jsonb", jsonb.selectExpression());
        MappedColumn array = mapped(new SourceColumn("tags", "_text", "b", "A", true, "text", "b", true, "text[]"));
        assertEquals("pg_catalog.super", array.redshiftType());
        assertEquals("to_jsonb(\"tags\")", array.selectExpression());
        assertEquals("CAST(CAST(to_jsonb(CAST(? AS text[])) AS text) AS pg_catalog.super)",
                array.valueExpression());
        assertEquals("DECIMAL(20,3)", mapped(base("money", "money")).redshiftType());
        assertEquals("pg_catalog.varbyte", mapped(base("bytea", "bytea")).redshiftType());
    }

    @Test
    void refusesTypesAwsDoesNotReplicate() {
        Optional<MappedColumn> enumColumn = PostgresRedshiftTypes.map(
                new SourceColumn("mood", "mood", "e", "E", false, null, null, false, "mood"));
        assertTrue(enumColumn.isEmpty());
        assertTrue(PostgresRedshiftTypes.map(
                new SourceColumn("moods", "_mood", "b", "A", false, "mood", "e", false, "mood[]")).isEmpty());
        assertTrue(PostgresRedshiftTypes.map(
                new SourceColumn("moods", "_mood", "b", "A", true, "mood", "e", false, "mood[]")).isEmpty());
        assertTrue(PostgresRedshiftTypes.map(base("point", "point")).isEmpty());
        assertTrue(PostgresRedshiftTypes.map(
                new SourceColumn("r", "int4range", "r", "R", true, null, null, false, "int4range")).isEmpty());
    }

    @Test
    void quotesIdentifiers() {
        MappedColumn column = mapped(new SourceColumn("Order \"Items\"", "int4", "b", "N", true,
                null, null, false, "integer"));
        assertEquals("\"Order \"\"Items\"\"\"", column.selectExpression());
    }
}
