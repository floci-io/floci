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
    void parsesQuotedColumnNamesContainingSpaces() {
        ExternalStatement.CreateTable table = (ExternalStatement.CreateTable) parser.parse(
                "CREATE EXTERNAL TABLE analytics.events (\"event name\" VARCHAR(20), amount DECIMAL(10,2)) "
                        + "STORED AS PARQUET LOCATION 's3://bucket/events/'").orElseThrow();

        assertThat(table.columns().stream().map(ExternalStatement.ColumnDefinition::name).toList(),
                equalTo(List.of("event name", "amount")));
        assertThat(table.columns().get(0).type(), equalTo("varchar(20)"));
    }

    @Test
    void parsesCreateTableWithCharacterAndVarbyteColumns() {
        ExternalStatement.CreateTable table = (ExternalStatement.CreateTable) parser.parse(
                "CREATE EXTERNAL TABLE analytics.events (code CHARACTER(10), payload VARBYTE, memo CHARACTER) "
                        + "STORED AS PARQUET LOCATION 's3://bucket/events/'").orElseThrow();
        assertThat(table.columns().get(0).name(), equalTo("code"));
        assertThat(table.columns().get(0).type(), equalTo("char(10)"));
        assertThat(table.columns().get(1).name(), equalTo("payload"));
        assertThat(table.columns().get(1).type(), equalTo("binary"));
        assertThat(table.columns().get(2).name(), equalTo("memo"));
        assertThat(table.columns().get(2).type(), equalTo("char(1)"));
    }

    @Test
    void defaultsUnsizedCharacterVaryingColumnsToRedshiftLength() {
        ExternalStatement.CreateTable table = (ExternalStatement.CreateTable) parser.parse(
                "CREATE EXTERNAL TABLE analytics.events (memo CHARACTER VARYING) "
                        + "STORED AS PARQUET LOCATION 's3://bucket/events/'").orElseThrow();

        assertThat(table.columns().get(0).type(), equalTo("varchar(256)"));
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

    @Test
    void parsesCreateExternalSchemaIfNotExists() {
        ExternalStatement.CreateSchema schema = (ExternalStatement.CreateSchema) parser.parse(
                "CREATE EXTERNAL SCHEMA IF NOT EXISTS Analytics FROM DATA CATALOG DATABASE 'lake' IAM_ROLE 'arn:aws:iam::000000000000:role/R'").orElseThrow();
        assertThat(schema.schemaName(), equalTo("analytics"));
        assertThat(schema.ifNotExists(), equalTo(true));
        ExternalStatement.CreateSchema plain = (ExternalStatement.CreateSchema) parser.parse(
                "CREATE EXTERNAL SCHEMA analytics FROM DATA CATALOG DATABASE 'lake' IAM_ROLE 'arn:aws:iam::000000000000:role/R'").orElseThrow();
        assertThat(plain.ifNotExists(), equalTo(false));
    }

    @Test
    void canonicalisesRedshiftTypeAliasesToHiveTypes() {
        ExternalStatement.CreateTable table = (ExternalStatement.CreateTable) parser.parse(
                "CREATE EXTERNAL TABLE analytics.events (a DOUBLE PRECISION, b int8, c float4, d bool, e character varying(10)) "
                        + "STORED AS PARQUET LOCATION 's3://bucket/events/'").orElseThrow();
        assertThat(table.columns().stream().map(ExternalStatement.ColumnDefinition::type).toList(),
                equalTo(List.of("double", "bigint", "float", "boolean", "varchar(10)")));
    }

    @Test
    void parsesTablePropertiesWithParenthesesAndEscapedQuotes() {
        ExternalStatement.CreateTable table = (ExternalStatement.CreateTable) parser.parse(
                "CREATE EXTERNAL TABLE analytics.events (id BIGINT) STORED AS PARQUET LOCATION 's3://bucket/events/' "
                        + "TABLE PROPERTIES ('comment'='a (b) it''s', 'numRows'='5')").orElseThrow();
        assertThat(table.properties().get("comment"), equalTo("a (b) it's"));
        assertThat(table.properties().get("numRows"), equalTo("5"));
    }

    @Test
    void parsesPartitionValuesContainingClosingParentheses() {
        ExternalStatement.AddPartitions add = (ExternalStatement.AddPartitions) parser.parse(
                "ALTER TABLE analytics.events ADD PARTITION (region='eu (west)') LOCATION 's3://bucket/events/eu/'").orElseThrow();
        assertThat(add.partitions().get(0).values().get("region"), equalTo("eu (west)"));
        assertThat(add.partitions().get(0).location(), equalTo("s3://bucket/events/eu/"));
    }

    @Test
    void parsesStatementsThatStartWithComments() {
        assertThat(parser.parse("-- drop it\n/* now */ DROP TABLE analytics.events").orElseThrow(),
                equalTo(new ExternalStatement.DropTable("analytics", "events", false, false)));
    }

    @Test
    void parsesQuotedNamesContainingDotsAndSpaces() {
        assertThat(parser.parse("DROP TABLE \"my.schema\".\"my table\"").orElseThrow(),
                equalTo(new ExternalStatement.DropTable("my.schema", "my table", false, false)));
        assertThat(parser.parse("DROP SCHEMA \"a b\" CASCADE").orElseThrow(),
                equalTo(new ExternalStatement.DropSchema("a b", false, true)));
    }

    @Test
    void rejectsIamRoleThatIsNotOneQuotedLiteral() {
        SpectrumSqlException exception = assertThrows(SpectrumSqlException.class, () -> parser.parse(
                "CREATE EXTERNAL SCHEMA a FROM DATA CATALOG DATABASE 'lake' IAM_ROLE 'arn'; DROP TABLE x"));
        assertThat(exception.sqlState(), equalTo("42601"));
    }

    @Test
    void reportsMissingStoredAsClearly() {
        SpectrumSqlException exception = assertThrows(SpectrumSqlException.class, () -> parser.parse(
                "CREATE EXTERNAL TABLE analytics.events (id BIGINT) LOCATION 's3://bucket/events/'"));
        assertThat(exception.getMessage(), equalTo("external table requires a STORED AS clause"));
    }

    @Test
    void keepsPropertyOrderAsWritten() {
        ExternalStatement.CreateTable table = (ExternalStatement.CreateTable) parser.parse(
                "CREATE EXTERNAL TABLE analytics.events (id BIGINT) STORED AS PARQUET LOCATION 's3://bucket/events/' "
                        + "TABLE PROPERTIES ('z'='1', 'a'='2', 'm'='3')").orElseThrow();
        assertThat(List.copyOf(table.properties().keySet()), equalTo(List.of("z", "a", "m")));
    }
}
