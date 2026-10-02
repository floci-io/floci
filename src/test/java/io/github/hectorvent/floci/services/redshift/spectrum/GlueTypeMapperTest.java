package io.github.hectorvent.floci.services.redshift.spectrum;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GlueTypeMapperTest {
    @Test
    void mapsGlueScalarAndParameterizedTypes() {
        assertThat(GlueTypeMapper.toPostgres("string"), equalTo("text"));
        assertThat(GlueTypeMapper.toPostgres("int"), equalTo("integer"));
        assertThat(GlueTypeMapper.toPostgres("DECIMAL(10, 2)"), equalTo("numeric(10,2)"));
        assertThat(GlueTypeMapper.toPostgres("char(3)"), equalTo("varchar(3)"));
        assertThat(GlueTypeMapper.toPostgres("character(3)"), equalTo("varchar(3)"));
        assertThat(GlueTypeMapper.toPostgres("varbyte"), equalTo("bytea"));
        assertThat(GlueTypeMapper.toPostgres("binary"), equalTo("bytea"));
        assertThat(GlueTypeMapper.toPostgres("array<string>"), equalTo("jsonb"));
        assertThat(GlueTypeMapper.isNested("array<string>"), is(true));
    }

    @Test
    void rejectsUnknownType() {
        SpectrumSqlException exception = assertThrows(SpectrumSqlException.class,
                () -> GlueTypeMapper.toPostgres("interval"));
        assertThat(exception.sqlState(), equalTo("0A000"));
    }

    @Test
    void quotesProjectionIdentifiersAndPreservesNullMarker() {
        assertThat(GlueTypeMapper.duckProjection("we\"ird", "string"),
                equalTo("COALESCE(CAST(\"we\"\"ird\" AS VARCHAR), '\\N') AS \"we\"\"ird\""));
        assertThat(GlueTypeMapper.duckProjection("payload", "varbyte"),
                equalTo("COALESCE(CAST('\\x' || hex(\"payload\") AS VARCHAR), '\\N') AS \"payload\""));
        assertThat(GlueTypeMapper.duckProjection("payload", "binary"),
                equalTo("COALESCE(CAST('\\x' || hex(\"payload\") AS VARCHAR), '\\N') AS \"payload\""));
    }

    @Test
    void canonicalisesRedshiftAliases() {
        assertThat(GlueTypeMapper.canonicalGlueType("DOUBLE PRECISION"), equalTo("double"));
        assertThat(GlueTypeMapper.canonicalGlueType("float8"), equalTo("double"));
        assertThat(GlueTypeMapper.canonicalGlueType("real"), equalTo("float"));
        assertThat(GlueTypeMapper.canonicalGlueType("int2"), equalTo("smallint"));
        assertThat(GlueTypeMapper.canonicalGlueType("int4"), equalTo("int"));
        assertThat(GlueTypeMapper.canonicalGlueType("INT8"), equalTo("bigint"));
        assertThat(GlueTypeMapper.canonicalGlueType("bool"), equalTo("boolean"));
        assertThat(GlueTypeMapper.canonicalGlueType("character varying(20)"), equalTo("varchar(20)"));
        assertThat(GlueTypeMapper.canonicalGlueType("CHARACTER VARYING"), equalTo("varchar(256)"));
        assertThat(GlueTypeMapper.canonicalGlueType("VARCHAR"), equalTo("varchar(256)"));
        assertThat(GlueTypeMapper.canonicalGlueType("CHARACTER(10)"), equalTo("char(10)"));
        assertThat(GlueTypeMapper.canonicalGlueType("character"), equalTo("char(1)"));
        assertThat(GlueTypeMapper.canonicalGlueType("CHAR"), equalTo("char(1)"));
        assertThat(GlueTypeMapper.canonicalGlueType("VARBYTE"), equalTo("binary"));
        assertThat(GlueTypeMapper.canonicalGlueType("varbyte(64000)"), equalTo("binary"));
        assertThat(GlueTypeMapper.canonicalGlueType("VARBINARY"), equalTo("binary"));
        assertThat(GlueTypeMapper.canonicalGlueType("binary varying(100)"), equalTo("binary"));
        assertThat(GlueTypeMapper.canonicalGlueType("Decimal(10, 2)"), equalTo("decimal(10,2)"));
        assertThat(GlueTypeMapper.canonicalGlueType("NUMERIC(10, 2)"), equalTo("decimal(10,2)"));
        assertThat(GlueTypeMapper.canonicalGlueType("numeric"), equalTo("decimal"));
        assertThat(GlueTypeMapper.canonicalGlueType("array<string>"), equalTo("array<string>"));
    }
}
