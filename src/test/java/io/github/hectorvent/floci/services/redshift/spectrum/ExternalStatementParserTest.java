package io.github.hectorvent.floci.services.redshift.spectrum;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExternalStatementParserTest {
    private final ExternalStatementParser parser = new ExternalStatementParser();

    @Test
    void parsesCreateExternalSchema() {
        ExternalStatement.CreateSchema schema = (ExternalStatement.CreateSchema) parser.parse(
                "CREATE EXTERNAL SCHEMA Analytics FROM DATA CATALOG DATABASE 'lake' REGION 'us-east-1' IAM_ROLE 'arn:aws:iam::000000000000:role/R' CREATE EXTERNAL DATABASE IF NOT EXISTS").orElseThrow();
        assertThat(schema.schemaName(), equalTo("analytics"));
        assertThat(schema.glueDatabase(), equalTo("lake"));
        assertThat(schema.createDatabaseIfNotExists(), equalTo(true));
    }

    @Test
    void parsesCreateTableWithNestedTypesAndFormat() {
        ExternalStatement.CreateTable table = (ExternalStatement.CreateTable) parser.parse(
                "CREATE EXTERNAL TABLE analytics.events (id BIGINT, tags array<string>) STORED AS PARQUET LOCATION 's3://bucket/events/'").orElseThrow();
        assertThat(table.format(), equalTo(ExternalStatement.TableFormat.PARQUET));
        assertThat(table.columns().get(1).type(), equalTo("array<string>"));
    }

    @Test
    void parsesMultiplePartitionClausesAndPreservesQuotedNames() {
        ExternalStatement.AddPartitions add = (ExternalStatement.AddPartitions) parser.parse(
                "ALTER TABLE \"Mixed\".\"Events\" ADD IF NOT EXISTS "
                        + "PARTITION (dt='2024-01-01', region='eu') LOCATION 's3://b/one/' "
                        + "PARTITION (dt='2024-01-02', region='eu') LOCATION 's3://b/two/'").orElseThrow();
        assertThat(add.schemaName(), equalTo("Mixed"));
        assertThat(add.tableName(), equalTo("Events"));
        assertThat(add.ifNotExists(), equalTo(true));
        assertThat(add.partitions().size(), equalTo(2));
        assertThat(add.partitions().get(1).values().get("dt"), equalTo("2024-01-02"));
    }

    @Test
    void rejectsUnsupportedRoleDefaultAndNonS3Location() {
        SpectrumSqlException role = assertThrows(SpectrumSqlException.class,
                () -> parser.parse("CREATE EXTERNAL SCHEMA a FROM DATA CATALOG DATABASE 'd' IAM_ROLE default"));
        assertThat(role.sqlState(), equalTo("0A000"));
        SpectrumSqlException location = assertThrows(SpectrumSqlException.class,
                () -> parser.parse("CREATE EXTERNAL TABLE a.t (id int) STORED AS PARQUET LOCATION 'http://b/x'"));
        assertThat(location.sqlState(), equalTo("22023"));
    }

    @Test
    void rejectsFormatsOutsideTheSupportedAthenaReadPlan() {
        SpectrumSqlException error = assertThrows(SpectrumSqlException.class, () -> parser.parse(
                "CREATE EXTERNAL TABLE analytics.events (id int) STORED AS ORC LOCATION 's3://bucket/events/'"));
        assertThat(error.sqlState(), equalTo("0A000"));
    }
}
