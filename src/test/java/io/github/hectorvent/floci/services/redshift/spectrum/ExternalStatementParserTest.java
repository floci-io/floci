package io.github.hectorvent.floci.services.redshift.spectrum;

import org.junit.jupiter.api.Test;

import java.util.List;

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
    void parsesPartitionColumnsWhoseTypesContainParentheses() {
        ExternalStatement.CreateTable table = (ExternalStatement.CreateTable) parser.parse(
                "CREATE EXTERNAL TABLE analytics.events (id BIGINT) PARTITIONED BY (price decimal(10,2), day string) "
                        + "STORED AS PARQUET LOCATION 's3://bucket/events/'").orElseThrow();
        assertThat(table.partitionColumns().size(), equalTo(2));
        assertThat(table.partitionColumns().get(0).type(), equalTo("decimal(10,2)"));
        assertThat(table.partitionColumns().get(1).name(), equalTo("day"));
    }

    @Test
    void parsesSerdePropertiesOfAnOpenCsvTable() {
        ExternalStatement.CreateTable table = (ExternalStatement.CreateTable) parser.parse(
                "CREATE EXTERNAL TABLE analytics.events (id INT, note VARCHAR(20)) "
                        + "ROW FORMAT SERDE 'org.apache.hadoop.hive.serde2.OpenCSVSerde' "
                        + "WITH SERDEPROPERTIES ('separatorChar'='|', 'quoteChar'='\"') "
                        + "STORED AS TEXTFILE LOCATION 's3://bucket/events/'").orElseThrow();
        assertThat(table.serde(), equalTo("org.apache.hadoop.hive.serde2.OpenCSVSerde"));
        assertThat(table.serdeProperties().get("separatorChar"), equalTo("|"));
        assertThat(table.serdeProperties().get("quoteChar"), equalTo("\""));
    }

    @Test
    void parsesSerdePropertyValuesThatContainClosingParentheses() {
        ExternalStatement.CreateTable table = (ExternalStatement.CreateTable) parser.parse(
                "CREATE EXTERNAL TABLE analytics.events (id INT) "
                        + "ROW FORMAT SERDE 'org.apache.hadoop.hive.serde2.OpenCSVSerde' "
                        + "WITH SERDEPROPERTIES ('separatorChar'=')', 'quoteChar'='(') "
                        + "STORED AS TEXTFILE LOCATION 's3://bucket/events/'").orElseThrow();

        assertThat(table.serdeProperties().get("separatorChar"), equalTo(")"));
        assertThat(table.serdeProperties().get("quoteChar"), equalTo("("));
    }

    @Test
    void parsesColumnNamesContainingCommasWhenQuoted() {
        ExternalStatement.CreateTable table = (ExternalStatement.CreateTable) parser.parse(
                "CREATE EXTERNAL TABLE analytics.events (\"event,id\" VARCHAR, amount DECIMAL(10,2)) "
                        + "STORED AS PARQUET LOCATION 's3://bucket/events/'").orElseThrow();

        assertThat(table.columns().stream().map(ExternalStatement.ColumnDefinition::name).toList(),
                equalTo(List.of("event,id", "amount")));
    }

    @Test
    void createDatabaseOptionSurvivesLineBreaksAndExtraWhitespace() {
        ExternalStatement.CreateSchema schema = (ExternalStatement.CreateSchema) parser.parse(
                "CREATE EXTERNAL SCHEMA a FROM DATA CATALOG DATABASE 'lake' IAM_ROLE 'arn:aws:iam::000000000000:role/R'\n"
                        + "CREATE   EXTERNAL\n DATABASE IF NOT   EXISTS").orElseThrow();
        assertThat(schema.createDatabaseIfNotExists(), equalTo(true));
    }

    @Test
    void otherCreateExternalStatementsAreReportedAsUnsupported() {
        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> parser.parse("CREATE EXTERNAL FUNCTION f(int) RETURNS int LAMBDA 'x' IAM_ROLE 'r'"));
        assertThat(error.sqlState(), equalTo("0A000"));
        assertThat(error.getMessage().contains("unsupported"), equalTo(true));
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

    @Test
    void dropStatementsDefaultToRestrictAndOnlyCascadeWhenAsked() {
        assertThat(parser.parse("DROP SCHEMA analytics").orElseThrow(),
                equalTo(new ExternalStatement.DropSchema("analytics", false, false)));
        assertThat(parser.parse("DROP SCHEMA analytics RESTRICT").orElseThrow(),
                equalTo(new ExternalStatement.DropSchema("analytics", false, false)));
        assertThat(parser.parse("DROP SCHEMA IF EXISTS analytics CASCADE;").orElseThrow(),
                equalTo(new ExternalStatement.DropSchema("analytics", true, true)));
        assertThat(parser.parse("DROP TABLE analytics.events").orElseThrow(),
                equalTo(new ExternalStatement.DropTable("analytics", "events", false, false)));
        assertThat(parser.parse("DROP TABLE IF EXISTS analytics.events RESTRICT").orElseThrow(),
                equalTo(new ExternalStatement.DropTable("analytics", "events", true, false)));
        assertThat(parser.parse("drop table analytics.events cascade").orElseThrow(),
                equalTo(new ExternalStatement.DropTable("analytics", "events", false, true)));
    }
}
