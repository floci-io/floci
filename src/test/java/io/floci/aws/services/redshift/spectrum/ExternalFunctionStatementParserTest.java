package io.floci.aws.services.redshift.spectrum;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExternalFunctionStatementParserTest {
    private static final String ROLE = "arn:aws:iam::000000000000:role/R";
    private static final String BASIC = "CREATE EXTERNAL FUNCTION exfunc_sum(INT, INT) RETURNS INT VOLATILE "
            + "LAMBDA 'lambda_sum' IAM_ROLE '" + ROLE + "'";
    private final ExternalStatementParser parser = new ExternalStatementParser();

    private ExternalStatement.CreateFunction create(String sql) {
        return (ExternalStatement.CreateFunction) parser.parse(sql).orElseThrow();
    }

    private String errorState(String sql) {
        return assertThrows(SpectrumSqlException.class, () -> parser.parse(sql)).sqlState();
    }

    @Test
    void parsesTheDocumentedExampleWithDefaults() {
        ExternalStatement.CreateFunction function = create(BASIC + ";");

        assertThat(function.schemaName(), nullValue());
        assertThat(function.functionName(), equalTo("exfunc_sum"));
        assertThat(function.argumentTypes(), equalTo(List.of("integer", "integer")));
        assertThat(function.returnType(), equalTo("integer"));
        assertThat(function.volatility(), equalTo("VOLATILE"));
        assertThat(function.lambdaFunction(), equalTo("lambda_sum"));
        assertThat(function.iamRoleArn(), equalTo(ROLE));
        assertThat(function.orReplace(), equalTo(false));
        assertThat(function.retryTimeoutMillis(), equalTo(20_000L));
        assertThat(function.maxBatchRows(), equalTo(Integer.MAX_VALUE));
        assertThat(function.maxBatchSizeBytes(), equalTo(5L * 1024 * 1024));
    }

    @Test
    void parsesOrReplaceSchemaQualifiedNamesAndAFunctionWithoutArguments() {
        ExternalStatement.CreateFunction function = create("create or replace external function \"Sales\".F_Now() "
                + "returns varchar(32) stable lambda 'now' iam_role '" + ROLE + "'");

        assertThat(function.orReplace(), equalTo(true));
        assertThat(function.schemaName(), equalTo("Sales"));
        assertThat(function.functionName(), equalTo("f_now"));
        assertThat(function.argumentTypes(), equalTo(List.of()));
        assertThat(function.returnType(), equalTo("character varying(32)"));
        assertThat(function.volatility(), equalTo("STABLE"));
    }

    @Test
    void collapsesTypeAliasesAndDropsLengthsFromTheSignature() {
        ExternalStatement.CreateFunction function = create("CREATE EXTERNAL FUNCTION f(INT2, int8, DECIMAL(10, 2), "
                + "FLOAT4, FLOAT8, NVARCHAR(20), BOOL, DATE, TIMESTAMP WITHOUT TIME ZONE, DOUBLE PRECISION, "
                + "CHAR(3)) RETURNS DECIMAL(10,2) STABLE LAMBDA 'f' IAM_ROLE '" + ROLE + "'");

        assertThat(function.argumentTypes(), equalTo(List.of("smallint", "bigint", "numeric", "real",
                "double precision", "character varying", "boolean", "date", "timestamp", "double precision",
                "character")));
        assertThat(function.returnType(), equalTo("numeric(10,2)"));
    }

    @Test
    void readsTheBatchAndRetryOptionsInAnyOrder() {
        ExternalStatement.CreateFunction function = create("CREATE EXTERNAL FUNCTION f(INT) RETURNS INT STABLE "
                + "LAMBDA 'f' MAX_BATCH_SIZE 2 MB MAX_BATCH_ROWS 100 RETRY_TIMEOUT 0 IAM_ROLE '" + ROLE + "'");

        assertThat(function.maxBatchSizeBytes(), equalTo(2L * 1024 * 1024));
        assertThat(function.maxBatchRows(), equalTo(100));
        assertThat(function.retryTimeoutMillis(), equalTo(0L));
    }

    @Test
    void aBatchSizeWithoutAUnitIsInKilobytes() {
        ExternalStatement.CreateFunction function = create("CREATE EXTERNAL FUNCTION f(INT) RETURNS INT STABLE "
                + "LAMBDA 'f' IAM_ROLE '" + ROLE + "' MAX_BATCH_SIZE 64");

        assertThat(function.maxBatchSizeBytes(), equalTo(64L * 1024));
    }

    @Test
    void rejectsOutOfRangeOptions() {
        String head = "CREATE EXTERNAL FUNCTION f(INT) RETURNS INT STABLE LAMBDA 'f' IAM_ROLE '" + ROLE + "' ";
        assertThat(errorState(head + "MAX_BATCH_ROWS 0"), equalTo("22023"));
        assertThat(errorState(head + "MAX_BATCH_ROWS 2147483648"), equalTo("22023"));
        assertThat(errorState(head + "MAX_BATCH_SIZE 6 MB"), equalTo("22023"));
        assertThat(errorState(head + "MAX_BATCH_SIZE 0 KB"), equalTo("22023"));
        assertThat(errorState(head + "RETRY_TIMEOUT -1"), equalTo("22023"));
        assertThat(errorState(head + "RETRY_TIMEOUT soon"), equalTo("42601"));
    }

    @Test
    void rejectsWhatTheStatementCannotExpress() {
        assertThat(errorState(BASIC.replace("VOLATILE", "IMMUTABLE")), equalTo("0A000"));
        assertThat(errorState(BASIC.replace("'" + ROLE + "'", "DEFAULT")), equalTo("0A000"));
        assertThat(errorState("CREATE EXTERNAL FUNCTION f(TIMESTAMPTZ) RETURNS INT STABLE LAMBDA 'f' IAM_ROLE '"
                + ROLE + "'"), equalTo("0A000"));
        assertThat(errorState("CREATE EXTERNAL FUNCTION f(INT) RETURNS SUPER STABLE LAMBDA 'f' IAM_ROLE '"
                + ROLE + "'"), equalTo("0A000"));
    }

    @Test
    void rejectsMalformedOrIncompleteStatements() {
        assertThat(errorState("CREATE EXTERNAL FUNCTION f(INT) VOLATILE LAMBDA 'f' IAM_ROLE '" + ROLE + "'"),
                equalTo("42601"));
        assertThat(errorState("CREATE EXTERNAL FUNCTION f(INT) RETURNS INT LAMBDA 'f' IAM_ROLE '" + ROLE + "'"),
                equalTo("42601"));
        assertThat(errorState("CREATE EXTERNAL FUNCTION f(INT) RETURNS INT STABLE IAM_ROLE '" + ROLE + "'"),
                equalTo("42601"));
        assertThat(errorState("CREATE EXTERNAL FUNCTION f(INT) RETURNS INT STABLE LAMBDA 'f'"), equalTo("42601"));
        assertThat(errorState("CREATE EXTERNAL FUNCTION f(INT RETURNS INT STABLE LAMBDA 'f' IAM_ROLE '" + ROLE + "'"),
                equalTo("42601"));
        assertThat(errorState(BASIC + " LAMBDA 'other'"), equalTo("42601"));
        assertThat(errorState(BASIC + " SOMETHING 'else'"), equalTo("42601"));
        assertThat(errorState(BASIC.replace("'lambda_sum'", "lambda_sum")), equalTo("42601"));
    }

    @Test
    void parsesDropFunctionByItsSignatureIgnoringArgumentNames() {
        ExternalStatement.DropFunction drop = (ExternalStatement.DropFunction) parser.parse(
                "DROP FUNCTION IF EXISTS Sales.exfunc_sum(a INT, b double precision, VARCHAR(5)) CASCADE;").orElseThrow();

        assertThat(drop.schemaName(), equalTo("sales"));
        assertThat(drop.functionName(), equalTo("exfunc_sum"));
        assertThat(drop.argumentTypes(), equalTo(List.of("integer", "double precision", "character varying")));
        assertThat(drop.ifExists(), equalTo(true));
        assertThat(drop.cascade(), equalTo(true));
    }

    @Test
    void dropFunctionDefaultsToRestrictAndAllowsNoArguments() {
        ExternalStatement.DropFunction drop = (ExternalStatement.DropFunction) parser.parse(
                "DROP FUNCTION f_now()").orElseThrow();

        assertThat(drop.argumentTypes(), equalTo(List.of()));
        assertThat(drop.cascade(), equalTo(false));
        assertThat(drop.ifExists(), equalTo(false));
    }

    @Test
    void leavesADropOfAnyOtherFunctionToTheBackend() {
        assertThat(parser.parse("DROP FUNCTION f_py(INTERVAL)").isPresent(), equalTo(false));
        assertThat(parser.parse("DROP FUNCTION f_py").isPresent(), equalTo(false));
    }

    @Test
    void anUnsupportedStatementErrorNamesTheType() {
        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> parser.parse("CREATE EXTERNAL FUNCTION f(JSONB) RETURNS INT STABLE LAMBDA 'f' IAM_ROLE '"
                        + ROLE + "'"));

        assertThat(error.getMessage(), containsString("JSONB"));
    }
}
