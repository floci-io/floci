package io.floci.aws.services.dsql.dataplane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DsqlStatementsTest {

    @Test
    void createIndexAsyncBecomesTheJobItAnswers() {
        List<DsqlStatements.Statement> statements =
                DsqlStatements.rewrite("CREATE UNIQUE INDEX ASYNC IF NOT EXISTS t_u ON probe.t (n) WHERE n > 0");

        assertEquals(1, statements.size());
        assertEquals("SELECT job_id FROM sys.create_index_async($dsql$CREATE UNIQUE INDEX  IF NOT EXISTS t_u"
                + " ON probe.t (n) WHERE n > 0$dsql$, 't_u', 'probe.t')", statements.get(0).sql());
        assertNull(statements.get(0).commandTag());
    }

    @Test
    void awsIamGrantAndRevokeBecomeCallsWithDsqlsCommandTags() {
        List<DsqlStatements.Statement> statements = DsqlStatements.rewrite(
                "AWS IAM GRANT App_Role TO 'arn:aws:iam::123456789012:role/app';"
                        + " aws iam revoke \"Mixed\" from 'arn:aws:iam::123456789012:role/o''x'");

        assertEquals("CALL sys.aws_iam_grant('app_role', 'arn:aws:iam::123456789012:role/app')",
                statements.get(0).sql());
        assertEquals("AWS IAM GRANT", statements.get(0).commandTag());
        assertEquals("CALL sys.aws_iam_revoke('Mixed', 'arn:aws:iam::123456789012:role/o''x')",
                statements.get(1).sql());
        assertEquals("AWS IAM REVOKE", statements.get(1).commandTag());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "TRUNCATE t|0A000|unsupported statement: Truncate",
        "LISTEN chan|0A000|unsupported statement: Listen",
        "NOTIFY chan|0A000|unsupported statement: Notify",
        "VACUUM t|0A000|unsupported VACUUM statement",
        "DO $$ BEGIN END $$|0A000|DO with language plpgsql not supported",
        "CREATE DATABASE x|0A000|CREATE DATABASE not supported",
        "CREATE TABLESPACE ts LOCATION '/tmp'|0A000|unsupported statement: CreateTableSpace",
        "CREATE EXTENSION pgcrypto|0A000|unsupported statement: CreateExtension",
        "GRANT CONNECT ON DATABASE postgres TO r|0A000|unsupported object type in GRANT",
        "CREATE SEQUENCE s|0A000|CREATE SEQUENCE is not supported without an explicit cache size. please define CACHE"
                + " greater than or equal to 65536 or equal to 1",
        "CREATE SEQUENCE s2 CACHE 100|22023|CACHE (100) must be greater than or equal to 65536 or equal to 1",
        "ALTER TABLE t ADD COLUMN c int DEFAULT 1|0A000|ALTER TABLE ADD COLUMN with constraint not supported",
        "ALTER TABLE t ADD CONSTRAINT ck CHECK (n > 0)|0A000|unsupported ALTER TABLE ADD CONSTRAINT statement",
        "SELECT * FROM t FOR SHARE|0A000|locking clauses other than FOR UPDATE/FOR KEY SHARE are not supported",
        "SELECT * FROM t FOR NO KEY UPDATE|0A000|locking clauses other than FOR UPDATE/FOR KEY SHARE are not supported",
        "SELECT pg_advisory_lock(1)|0A000|function pg_advisory_lock not supported",
        "SELECT pg_try_advisory_xact_lock(1)|0A000|function pg_try_advisory_xact_lock not supported",
        "SELECT \"pg_advisory_lock\"(1)|0A000|function pg_advisory_lock not supported",
        "CREATE SEQUENCE s9 /* CACHE 65536 */ CACHE 2|22023|CACHE (2) must be greater than or equal to 65536 or equal to 1",
        "SET \"default_transaction_isolation\" TO 'read committed'|0A000|setting configuration parameter"
                + " \"default_transaction_isolation\" not supported",
        "SELECT set_config('default_transaction_isolation', 'read committed', false)|0A000|setting configuration"
                + " parameter \"default_transaction_isolation\" not supported",
        "SELECT set_config('transaction_isolation', 'read committed', false)|0A000|setting configuration parameter"
                + " \"transaction_isolation\" not supported",
        "ALTER ROLE admin SET default_transaction_isolation = 'read committed'|0A000|setting configuration parameter"
                + " \"default_transaction_isolation\" not supported",
        "ALTER DATABASE postgres SET search_path = public|0A000|unsupported statement: AlterDatabaseSet",
        "ALTER DATABASE postgres RESET ALL|0A000|unsupported statement: AlterDatabaseSet",
        "SET TRANSACTION ISOLATION LEVEL SERIALIZABLE|0A000|Unsupported isolation level: SERIALIZABLE",
        "BEGIN ISOLATION LEVEL READ COMMITTED|0A000|Unsupported isolation level: READ COMMITTED",
        "SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL SERIALIZABLE|0A000|Unsupported isolation level: SERIALIZABLE",
        "SET default_transaction_isolation = 'read committed'|0A000|setting configuration parameter"
                + " \"default_transaction_isolation\" not supported",
    })
    void whatDsqlRefusesRaisesItsOwnError(String sql, String state, String message) {
        assertEquals(DsqlStatements.reject(state, message, null), DsqlStatements.rewrite(sql).get(0).sql());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "SET dsql.x = 1|dsql.x",
        "SET LOCAL dsql.row_count TO 0|dsql.row_count",
        "RESET dsql.x|dsql.x",
        "SELECT set_config('dsql.internal', 'on', false)|dsql.internal",
        "SELECT 1, set_config('dsql.x', '1', true) FROM t|dsql.x",
        "ALTER ROLE admin SET dsql.x = 1|dsql.x",
        "ALTER ROLE admin IN DATABASE postgres RESET dsql.x|dsql.x",
        "CREATE FUNCTION f() RETURNS int LANGUAGE sql SET dsql.x = 1 AS 'SELECT 1'|dsql.x",
        "SET \"dsql\".x = 1|dsql.x",
        "SELECT \"set_config\"('dsql.x', '1', false)|dsql.x",
    })
    void theDsqlPrefixIsReservedOnEveryChannel(String sql, String name) {
        assertEquals(DsqlStatements.reject("42602", "invalid configuration parameter name \"" + name + "\"",
                "\"dsql\" is a reserved prefix."), DsqlStatements.rewrite(sql).get(0).sql());
    }

    @Test
    void showingADsqlParameterFindsNone() {
        assertEquals(DsqlStatements.reject("42704", "unrecognized configuration parameter \"dsql.session_id\"", null),
                DsqlStatements.rewrite("SHOW dsql.session_id").get(0).sql());
    }

    @Test
    void setConfigOfAnotherDottedParameterIsNotSupportedThoughSetIs() {
        assertEquals(DsqlStatements.reject("0A000", "setting configuration parameter \"myapp.foo\" not supported", null),
                DsqlStatements.rewrite("SELECT set_config('myapp.foo', 'x', false)").get(0).sql());
        assertEquals("SET myapp.foo = 'x'", DsqlStatements.rewrite("SET myapp.foo = 'x'").get(0).sql());
        assertEquals("SELECT set_config('search_path', 'public', false)",
                DsqlStatements.rewrite("SELECT set_config('search_path', 'public', false)").get(0).sql());
    }

    @Test
    void dropRoleBecomesTheCallThatRefusesAMappedRole() {
        assertEquals("CALL sys.drop_roles(ARRAY['app_role', 'Mixed']::text[], false)",
                DsqlStatements.rewrite("DROP ROLE App_Role, \"Mixed\"").get(0).sql());
        DsqlStatements.Statement user = DsqlStatements.rewrite("drop user if exists u").get(0);
        assertEquals("CALL sys.drop_roles(ARRAY['u']::text[], true)", user.sql());
        assertEquals("DROP ROLE", user.commandTag());
        assertEquals("DROP ROLE", DsqlStatements.rewrite("DROP GROUP g").get(0).commandTag());
    }

    @Test
    void aCacheTooLargeForABigintIsLeftToThePostgresqlError() {
        String sql = "CREATE SEQUENCE huge CACHE 99999999999999999999";

        assertEquals(sql, DsqlStatements.rewrite(sql).get(0).sql());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "SELECT * FROM t FOR UPDATE SKIP LOCKED",
        "SELECT * FROM t FOR KEY SHARE",
        "CREATE SEQUENCE s1 CACHE 1",
        "CREATE SEQUENCE s3 CACHE 65536",
        "ALTER TABLE t ADD COLUMN d int",
        "ALTER TABLE t DROP COLUMN d",
        "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ",
        "SET search_path TO probe",
        "DROP ROLE CURRENT_USER",
        "DROP ROLE IF EXISTS",
        "DROP ROLE a b",
        "DROP ROLE a,",
        "SELECT 'AWS IAM GRANT a TO b' AS text",
        "SELECT 'TRUNCATE t; FOR SHARE' AS text",
        "INSERT INTO t (note) VALUES ($x$ pg_advisory_lock( $x$)",
        "CREATE INDEX ON t (n)",
        "CREATE SEQUENCE \"cache 2\" CACHE 65536",
    })
    void whatDsqlAcceptsPassesUnchanged(String sql) {
        assertEquals(sql, DsqlStatements.rewrite(sql).get(0).sql());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "CREATE ROLE r1|CREATE ROLE",
        "create user u1 WITH LOGIN|CREATE ROLE",
        "ALTER ROLE r1 SET search_path = public|ALTER ROLE",
        "ALTER GROUP g ADD USER u1|ALTER ROLE",
        "GRANT r1 TO u1|GRANT ROLE",
        "REVOKE r1 FROM u1|REVOKE ROLE",
    })
    void aStatementOnRolesIsCountedAsDdlUnderPostgresqlsTag(String sql, String tag) {
        DsqlStatements.Statement statement = DsqlStatements.rewrite(sql).get(0);

        assertEquals("CALL sys.run_role_ddl($dsql$" + sql + "$dsql$)", statement.sql());
        assertEquals(tag, statement.commandTag());
    }

    @Test
    void aGrantOnAnObjectOrAUserMappingIsNoRoleStatement() {
        for (String sql : List.of("GRANT SELECT ON t TO r1", "REVOKE ALL ON t FROM r1",
                "CREATE USER MAPPING FOR r1 SERVER s")) {
            assertEquals(sql, DsqlStatements.rewrite(sql).get(0).sql(), sql);
        }
    }

    @Test
    void aLineCommentEndsAtACarriageReturnAsInPostgresql() {
        List<DsqlStatements.Statement> statements = DsqlStatements.rewrite("SELECT 1 --\r; TRUNCATE t");

        assertEquals(2, statements.size());
        assertEquals(DsqlStatements.reject("0A000", "unsupported statement: Truncate", null), statements.get(1).sql());
    }

    @Test
    void aQueryWithNothingToRewriteGoesToTheDatabaseExactlyAsSent() {
        String query = "-- comment\nSELECT 1;\n/* two */ SELECT 'a;b';";

        DsqlStatements.Query rewritten = DsqlStatements.rewriteQuery(query);

        assertEquals(query, rewritten.sql());
        assertEquals(false, rewritten.overridesTags());
    }

    @Test
    void aQueryWithDsqlSyntaxIsRewrittenStatementByStatementWithItsTags() {
        DsqlStatements.Query rewritten = DsqlStatements.rewriteQuery(
                "SELECT 1; AWS IAM GRANT app TO 'arn:aws:iam::123456789012:role/app'");

        assertEquals("SELECT 1;\nCALL sys.aws_iam_grant('app', 'arn:aws:iam::123456789012:role/app')",
                rewritten.sql());
        assertEquals(Arrays.asList(null, "AWS IAM GRANT"), rewritten.commandTags());
        assertEquals(true, rewritten.overridesTags());
    }

    @Test
    void statementsAreSplitOutsideStringsCommentsAndDollarQuotes() {
        List<DsqlStatements.Statement> statements = DsqlStatements.rewrite(
                "SELECT 'a;b'; /* ; */ SELECT $f$ ; $f$ -- ;\n; SELECT \"x;y\"");

        assertEquals(3, statements.size());
        assertEquals("SELECT 'a;b'", statements.get(0).sql());
        assertEquals("SELECT $f$ ; $f$", statements.get(1).sql());
        assertEquals("SELECT \"x;y\"", statements.get(2).sql());
    }
}
