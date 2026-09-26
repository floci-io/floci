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
    }
}
