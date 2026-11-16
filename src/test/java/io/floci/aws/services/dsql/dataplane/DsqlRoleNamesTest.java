package io.floci.aws.services.dsql.dataplane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class DsqlRoleNamesTest {

    private static final RoleNames NAMES = new PrefixedRoleNames("x");
    private static final String ARN = "'arn:aws:iam::123456789012:role/r'";

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "CREATE ROLE app WITH LOGIN|CALL sys.run_role_ddl($dsql$CREATE ROLE dx_app WITH LOGIN$dsql$)",
        "GRANT app TO reader|CALL sys.run_role_ddl($dsql$GRANT dx_app TO dx_reader$dsql$)",
        "GRANT SELECT ON t TO app, PUBLIC|GRANT SELECT ON t TO dx_app, PUBLIC",
        "REVOKE ALL ON t FROM app|REVOKE ALL ON t FROM dx_app",
        "ALTER TABLE t OWNER TO app|ALTER TABLE t OWNER TO dx_app",
        "SET ROLE app|SET ROLE dx_app",
        "CREATE SCHEMA s AUTHORIZATION app|CREATE SCHEMA s AUTHORIZATION dx_app",
        "CREATE POLICY p ON t TO app|CREATE POLICY p ON t TO dx_app",
        "ALTER DEFAULT PRIVILEGES FOR ROLE app GRANT SELECT ON TABLES TO reader"
                + "|ALTER DEFAULT PRIVILEGES FOR ROLE dx_app GRANT SELECT ON TABLES TO dx_reader",
        "REASSIGN OWNED BY app TO reader|REASSIGN OWNED BY dx_app TO dx_reader",
        "DROP OWNED BY app|DROP OWNED BY dx_app",
        "DROP ROLE app, reader|CALL sys.drop_roles(ARRAY['dx_app', 'dx_reader']::text[], false)",
        "AWS IAM GRANT app TO " + ARN + "|CALL sys.aws_iam_grant('dx_app', " + ARN + ")",
        "CREATE ROLE \"App\"|CALL sys.run_role_ddl($dsql$CREATE ROLE \"dx_App\"$dsql$)",
        "SELECT 'app'|SELECT 'app'",
        "GRANT pg_read_all_data TO app|CALL sys.run_role_ddl($dsql$GRANT pg_read_all_data TO dx_app$dsql$)",
        "SET ROLE NONE|SET ROLE NONE",
        // Beyond the brief's rows: the other positions the grammar names a role in.
        "GRANT \"app\" TO r|CALL sys.run_role_ddl($dsql$GRANT \"dx_app\" TO dx_r$dsql$)",
        "CREATE ROLE App|CALL sys.run_role_ddl($dsql$CREATE ROLE dx_app$dsql$)",
        "CREATE ROLE a IN ROLE b, c ROLE d ADMIN e USER f"
                + "|CALL sys.run_role_ddl($dsql$CREATE ROLE dx_a IN ROLE dx_b, dx_c ROLE dx_d ADMIN dx_e USER dx_f$dsql$)",
        "CREATE USER u IN GROUP g PASSWORD 'p'|CALL sys.run_role_ddl($dsql$CREATE USER dx_u IN GROUP dx_g PASSWORD 'p'$dsql$)",
        "ALTER ROLE app RENAME TO other|CALL sys.run_role_ddl($dsql$ALTER ROLE dx_app RENAME TO dx_other$dsql$)",
        "ALTER ROLE app SET role TO 'x'|CALL sys.run_role_ddl($dsql$ALTER ROLE dx_app SET role TO 'x'$dsql$)",
        "ALTER ROLE ALL SET work_mem = '1MB'|CALL sys.run_role_ddl($dsql$ALTER ROLE ALL SET work_mem = '1MB'$dsql$)",
        "ALTER GROUP g ADD USER a, b|CALL sys.run_role_ddl($dsql$ALTER GROUP dx_g ADD USER dx_a, dx_b$dsql$)",
        "ALTER ROLE CURRENT_USER WITH NOLOGIN|CALL sys.run_role_ddl($dsql$ALTER ROLE CURRENT_USER WITH NOLOGIN$dsql$)",
        "GRANT a, b TO c WITH ADMIN OPTION GRANTED BY d"
                + "|CALL sys.run_role_ddl($dsql$GRANT dx_a, dx_b TO dx_c WITH ADMIN OPTION GRANTED BY dx_d$dsql$)",
        "REVOKE ADMIN OPTION FOR app FROM reader CASCADE"
                + "|CALL sys.run_role_ddl($dsql$REVOKE ADMIN OPTION FOR dx_app FROM dx_reader CASCADE$dsql$)",
        "REVOKE INHERIT OPTION FOR app FROM reader"
                + "|CALL sys.run_role_ddl($dsql$REVOKE INHERIT OPTION FOR dx_app FROM dx_reader$dsql$)",
        "REVOKE SET OPTION FOR app, other FROM reader GRANTED BY admin RESTRICT"
                + "|CALL sys.run_role_ddl($dsql$REVOKE SET OPTION FOR dx_app, dx_other FROM dx_reader GRANTED BY dx_admin"
                + " RESTRICT$dsql$)",
        "revoke inherit option for app from GROUP reader"
                + "|CALL sys.run_role_ddl($dsql$revoke inherit option for dx_app from GROUP dx_reader$dsql$)",
        "REVOKE app FROM reader|CALL sys.run_role_ddl($dsql$REVOKE dx_app FROM dx_reader$dsql$)",
        "GRANT SELECT ON t TO GROUP g, CURRENT_USER WITH GRANT OPTION"
                + "|GRANT SELECT ON t TO GROUP dx_g, CURRENT_USER WITH GRANT OPTION",
        "SET SESSION ROLE app|SET SESSION ROLE dx_app",
        "SET SESSION AUTHORIZATION app|SET SESSION AUTHORIZATION dx_app",
        "SET SESSION AUTHORIZATION DEFAULT|SET SESSION AUTHORIZATION DEFAULT",
        "CREATE SCHEMA IF NOT EXISTS AUTHORIZATION app|CREATE SCHEMA IF NOT EXISTS AUTHORIZATION dx_app",
        "ALTER POLICY p ON t TO a, PUBLIC USING (n > 0)|ALTER POLICY p ON t TO dx_a, PUBLIC USING (n > 0)",
        "ALTER POLICY p ON t RENAME TO q|ALTER POLICY p ON t RENAME TO q",
        "ALTER DEFAULT PRIVILEGES FOR USER a, b IN SCHEMA s REVOKE ALL ON TABLES FROM c"
                + "|ALTER DEFAULT PRIVILEGES FOR USER dx_a, dx_b IN SCHEMA s REVOKE ALL ON TABLES FROM dx_c",
        "DROP OWNED BY a, b CASCADE|DROP OWNED BY dx_a, dx_b CASCADE",
        "ALTER SCHEMA s OWNER TO CURRENT_USER|ALTER SCHEMA s OWNER TO CURRENT_USER",
        // Not role positions: a column, constraint or attribute called owner, renamed.
        "ALTER TABLE t RENAME COLUMN owner TO x|ALTER TABLE t RENAME COLUMN owner TO x",
        "ALTER TABLE t RENAME owner TO x|ALTER TABLE t RENAME owner TO x",
        "ALTER TABLE t RENAME CONSTRAINT owner TO c|ALTER TABLE t RENAME CONSTRAINT owner TO c",
        "ALTER TYPE ty RENAME ATTRIBUTE owner TO y|ALTER TYPE ty RENAME ATTRIBUTE owner TO y",
        "ALTER TABLE owner OWNER TO app|ALTER TABLE owner OWNER TO dx_app",
    })
    void roleNamesAreTranslatedWhereSqlNamesARole(String sql, String expected) {
        assertEquals(expected, DsqlStatements.rewriteQuery(sql, NAMES).sql());
        assertEquals(DsqlStatements.rewriteQuery(sql).sql(), DsqlStatements.rewriteQuery(sql, RoleNames.IDENTITY).sql());
        assertEquals(DsqlStatements.rewriteQuery(sql).commandTags(),
                DsqlStatements.rewriteQuery(sql, NAMES).commandTags());
    }

    @Test
    void eachStatementOfAQueryIsTranslated() {
        DsqlStatements.Query query = DsqlStatements.rewriteQuery("SELECT 'app'; SET ROLE app; DROP ROLE reader", NAMES);

        assertEquals("SELECT 'app';\nSET ROLE dx_app;\nCALL sys.drop_roles(ARRAY['dx_reader']::text[], false)",
                query.sql());
        assertEquals(Arrays.asList(null, null, "DROP ROLE"), query.commandTags());
    }

    @Test
    void theOneArgumentRewriteStillAnswersWhatItDid() {
        List<DsqlStatements.Statement> statements = DsqlStatements.rewrite("DROP ROLE app", NAMES);

        assertEquals("CALL sys.drop_roles(ARRAY['dx_app']::text[], false)", statements.get(0).sql());
        assertEquals(DsqlStatements.rewrite("DROP ROLE app"), DsqlStatements.rewrite("DROP ROLE app", RoleNames.IDENTITY));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "CREATE ROLE abcdefghijabcdefghijabcdefghijabcdef",
        "GRANT SELECT ON t TO ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEF",
    })
    void overlongRoleNameIsRefused(String sql) {
        DsqlStatements.Statement statement = DsqlStatements.rewrite(sql, NAMES).get(0);

        assertEquals(DsqlStatements.reject("42602", "role name \"abcdefghijabcdefghijabcdefghijabcdef\" is too long"
                + " for Floci's external backend (max 35 bytes)", null), statement.sql());
        assertNull(statement.commandTag());
    }

    @Test
    void aMultibyteNameOfFewerCharactersThanTheLimitButMoreBytesIsRefused() {
        String name = "\u00e9".repeat(18);
        assertEquals(18, name.length());

        DsqlStatements.Statement statement = DsqlStatements.rewrite("CREATE ROLE \"" + name + "\"", NAMES).get(0);

        assertEquals(DsqlStatements.reject("42602", "role name \"" + name + "\" is too long for Floci's external"
                + " backend (max 35 bytes)", null), statement.sql());
    }

    @Test
    void aMultibyteNameOfExactlyThirtyFiveBytesIsAccepted() {
        String name = "\u00e9".repeat(17) + "a";
        assertEquals(35, name.getBytes(StandardCharsets.UTF_8).length);

        assertEquals("CALL sys.run_role_ddl($dsql$CREATE ROLE \"dx_" + name + "\"$dsql$)",
                DsqlStatements.rewriteQuery("CREATE ROLE \"" + name + "\"", NAMES).sql());
    }

    @Test
    void aThirtyFiveByteNameFillsTheIdentifierOfARealClusterId() {
        RoleNames longPrefix = new PrefixedRoleNames("abcdefghijklmnopqrstuvwxyz");
        String name = "r".repeat(35);
        assertEquals("CALL sys.run_role_ddl($dsql$CREATE ROLE dabcdefghijklmnopqrstuvwxyz_" + name + "$dsql$)",
                DsqlStatements.rewriteQuery("CREATE ROLE " + name, longPrefix).sql());
        assertEquals(63, longPrefix.toBackend(name).getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void thirtyFiveCharactersAreAccepted() {
        String sql = DsqlStatements.rewriteQuery("SET ROLE abcdefghijabcdefghijabcdefghijabcde", NAMES).sql();

        assertEquals("SET ROLE dx_abcdefghijabcdefghijabcdefghijabcde", sql);
    }

    @Test
    void longNamesAreOnlyCheckedWhenNamesAreTranslated() {
        String sql = "CREATE ROLE abcdefghijabcdefghijabcdefghijabcdef";

        assertEquals(DsqlStatements.rewriteQuery(sql).sql(), DsqlStatements.rewriteQuery(sql, RoleNames.IDENTITY).sql());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "CALL sys.run_role_ddl('CREATE ROLE \"dother_x\"')|procedure sys.run_role_ddl(text) does not exist",
        "call SYS.Run_Role_Ddl($$GRANT a TO b$$)|procedure sys.run_role_ddl(text) does not exist",
        "CALL \"sys\".\"drop_roles\"(ARRAY['x'], true)|procedure sys.drop_roles(text[], boolean) does not exist",
        "CALL sys.aws_iam_grant('x', 'arn')|procedure sys.aws_iam_grant(text, text) does not exist",
        "CALL sys.aws_iam_revoke ( 'x', 'arn' )|procedure sys.aws_iam_revoke(text, text) does not exist",
        "SELECT sys._reject('42000', 'm')|function sys._reject(unknown, unknown) does not exist",
        "SELECT 1; CALL sys._record_backend_roles()|procedure sys._record_backend_roles() does not exist",
        "SELECT sys._strip_role_prefix(f(1, 2))|function sys._strip_role_prefix(unknown) does not exist",
        "CREATE PROCEDURE p() LANGUAGE sql BEGIN ATOMIC CALL sys.run_role_ddl('x')"
                + "|procedure sys.run_role_ddl(text) does not exist",
    })
    void clientCallsOfFlocisInternalRoutinesAreRefusedOnASharedServer(String sql, String message) {
        List<DsqlStatements.Statement> statements = DsqlStatements.rewrite(sql, NAMES);
        DsqlStatements.Statement statement = statements.get(statements.size() - 1);

        assertEquals(DsqlStatements.reject("42883", message, null), statement.sql());
        assertNull(statement.commandTag());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "CALL sys.run_role_ddl('CREATE ROLE x')",
        "SELECT sys._reject('42000', 'm')",
    })
    void internalRoutinesAreLeftAsTheyAreWithTheClustersOwnDatabase(String sql) {
        assertEquals(sql, DsqlStatements.rewriteQuery(sql, RoleNames.IDENTITY).sql());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT sys.iam_session_user()",
        "CALL sys.wait_for_job('abc')",
        "SELECT public.run_role_ddl('x')",
        "SELECT run_role_ddl FROM t",
        "SELECT 'sys.run_role_ddl(''x'')'",
        "SELECT sys.current_session_id(), my._helper()",
        "CREATE TABLE run_role_ddl (id int)",
        "INSERT INTO drop_roles (a) VALUES (1)",
        "CALL run_role_ddl('a client procedure of that name')",
        "SELECT aws_iam_grant(1, 2), _private(3)",
    })
    void otherRoutinesAndMentionsAreNotRefused(String sql) {
        assertEquals(sql, DsqlStatements.rewriteQuery(sql, NAMES).sql());
    }

    @Test
    void prefixedNamesRoundTrip() {
        assertEquals("App", NAMES.toLogical(NAMES.toBackend("App")));
        assertEquals("dx_app", NAMES.toBackend("app"));
        assertEquals("admin", NAMES.toLogical("admin"));
        assertFalse(NAMES.isIdentity());
    }

    @ParameterizedTest
    @ValueSource(strings = {"public", "current_user", "current_role", "session_user", "none", "default",
        "pg_read_all_data"})
    void specifiersAndReservedNamesAreNotPrefixed(String name) {
        assertEquals(name, NAMES.toBackend(name, false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"public", "current_user", "current_role", "session_user", "none", "default"})
    void aQuotedOrExactSpecifierNameIsARoleAndIsPrefixed(String name) {
        assertEquals("dx_" + name, NAMES.toBackend(name, true));
        assertEquals("dx_" + name, NAMES.toBackend(name), "a login's user name is a role's exact name");
        assertEquals("CALL sys.run_role_ddl($dsql$CREATE ROLE \"dx_" + name + "\"$dsql$)",
                DsqlStatements.rewriteQuery("CREATE ROLE \"" + name + "\"", NAMES).sql());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "GRANT SELECT ON t TO \"public\", PUBLIC|GRANT SELECT ON t TO \"dx_public\", PUBLIC",
        "SET ROLE \"none\"|SET ROLE \"dx_none\"",
        "ALTER TABLE t OWNER TO \"current_user\"|ALTER TABLE t OWNER TO \"dx_current_user\"",
        "DROP ROLE \"default\"|CALL sys.drop_roles(ARRAY['dx_default']::text[], false)",
        "CREATE ROLE \"pg_x\"|CALL sys.run_role_ddl($dsql$CREATE ROLE \"pg_x\"$dsql$)",
        "GRANT pg_read_all_data TO \"public\"|CALL sys.run_role_ddl($dsql$GRANT pg_read_all_data TO \"dx_public\"$dsql$)",
    })
    void onlyUnquotedSpecifiersKeepTheirNames(String sql, String expected) {
        assertEquals(expected, DsqlStatements.rewriteQuery(sql, NAMES).sql());
    }

    @Test
    void theLongestLogicalNameFitsInPostgresqlsLimit() {
        assertEquals(35, PrefixedRoleNames.MAX_LOGICAL);
    }
}
