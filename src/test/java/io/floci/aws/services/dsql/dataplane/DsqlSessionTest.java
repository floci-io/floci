package io.floci.aws.services.dsql.dataplane;

import io.floci.aws.core.common.PostgresWire;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DsqlSessionTest {

    @Test
    void startupOptionsCountOnlyTheirFirstDashCSettingForEachName() {
        Map<String, String> settings = DsqlSession.optionSettings(
                "--dsql.iam_session_user=spoofed -c search_path=first -csearch_path=x  -c Search_Path=second"
                        + " -c TimeZone=UTC -B 100 garbage -c application_name -c");

        assertEquals(Map.of("search_path", "first", "TimeZone", "UTC"), settings);
        assertEquals(List.of("search_path", "TimeZone"), List.copyOf(settings.keySet()));
        assertEquals(Map.of(), DsqlSession.optionSettings(null));
        assertEquals(Map.of(), DsqlSession.optionSettings("   "));
    }

    @Test
    void startupOptionsTakeNoEscapesAndReachTheDatabaseEscaped() {
        Map<String, String> settings = DsqlSession.optionSettings("-c search_path=a\\ b");

        assertEquals(Map.of("search_path", "a\\"), settings);
        assertEquals("-c search_path=a\\\\", DsqlSession.backendOptions(settings));
    }

    @Test
    void aCustomParameterInTheStartupIsTheOneRefused() {
        assertEquals("myapp.foo", DsqlSession.customParameter(Map.of("myapp.foo", "x"), Map.of("user", "admin")));
        assertEquals("dsql.iam_session_user", DsqlSession.customParameter(Map.of(),
                Map.of("user", "admin", "dsql.iam_session_user", "spoofed")));
        assertEquals(null, DsqlSession.customParameter(Map.of("search_path", "public"),
                Map.of("user", "admin", "options", "-c search_path=public")));
    }

    @Test
    void anErrorRaisedInsideSysCarriesNeitherContextNorLocation() {
        byte[] postgres = error(Map.of('C', "0A000", 'M', "unsupported statement: Truncate",
                'W', "PL/pgSQL function sys._reject(text,text,text) line 4 at RAISE",
                'F', "pl_exec.c", 'L', "3926", 'R', "exec_stmt_raise"));

        Map<Character, String> fields = PostgresWire.parseFields(DsqlSession.inDsqlWords((byte) 'E', postgres), 0);

        assertEquals("unsupported statement: Truncate", fields.get('M'));
        assertFalse(fields.containsKey('W'));
        assertFalse(fields.containsKey('F'));
        assertFalse(fields.containsKey('L'));
        assertFalse(fields.containsKey('R'));
    }

    @Test
    void aNoticeRaisedInsideSysKeepsItsLocationAsWaitForJobsDoesOnDsql() {
        byte[] postgres = error(Map.of('S', "NOTICE", 'C', "00000", 'M', "Job execution succeeded for job with id x",
                'W', "PL/pgSQL function sys.wait_for_job(text,boolean) line 7 at RAISE",
                'F', "pl_exec.c", 'L', "3926", 'R', "exec_stmt_raise"));

        Map<Character, String> fields = PostgresWire.parseFields(DsqlSession.inDsqlWords((byte) 'N', postgres), 0);

        assertFalse(fields.containsKey('W'));
        assertTrue(fields.containsKey('F'));
        assertEquals("exec_stmt_raise", fields.get('R'));
    }

    @Test
    void whatASysFunctionRanLeavesTheContextWithIt() {
        byte[] postgres = error(Map.of('C', "42704", 'M', "role \"nosuch\" does not exist",
                'W', "SQL statement \"DROP ROLE nosuch\"\nPL/pgSQL function sys.drop_roles(text[],boolean) line 13"
                        + " at EXECUTE"));

        Map<Character, String> fields = PostgresWire.parseFields(DsqlSession.inDsqlWords((byte) 'E', postgres), 0);

        assertFalse(fields.containsKey('W'));
    }

    @Test
    void theClientsOwnFramesAroundASysCallStay() {
        byte[] postgres = error(Map.of('C', "22012", 'M', "division by zero",
                'W', "SQL statement \"SELECT\n    1 / 0\"\nPL/pgSQL function sys.probe() line 3 at PERFORM\n"
                        + "SQL function \"f\" statement 1"));

        Map<Character, String> fields = PostgresWire.parseFields(DsqlSession.inDsqlWords((byte) 'E', postgres), 0);

        assertEquals("SQL function \"f\" statement 1", fields.get('W'));
    }

    @Test
    void anErrorFromTheClientsOwnSqlIsLeftAlone() {
        byte[] postgres = error(Map.of('C', "23505", 'M', "duplicate key value violates unique constraint \"t_u\"",
                'W', "SQL function \"f\" statement 1"));

        assertArrayEquals(postgres, DsqlSession.inDsqlWords((byte) 'E', postgres));
    }

    @Test
    void postgresqlsOwnErrorLosesItsSourceLocationAsOnDsql() {
        byte[] postgres = error(Map.of('C', "23503", 'M', "insert or update on table \"child\" violates foreign key"
                + " constraint \"child_parent_id_fkey\"", 'F', "ri_triggers.c", 'L', "2608", 'R', "ri_ReportViolation"));

        Map<Character, String> fields = PostgresWire.parseFields(DsqlSession.inDsqlWords((byte) 'E', postgres), 0);

        assertEquals("23503", fields.get('C'));
        assertFalse(fields.containsKey('F'));
        assertFalse(fields.containsKey('L'));
        assertFalse(fields.containsKey('R'));
    }

    @Test
    void aSerializationFailureIsDsqlsOc000() {
        byte[] postgres = error(Map.of('C', "40001", 'M', "could not serialize access due to concurrent update",
                'F', "heapam.c", 'L', "1", 'R', "heap_update"));

        Map<Character, String> fields = PostgresWire.parseFields(DsqlSession.inDsqlWords((byte) 'E', postgres), 0);

        assertEquals("40001", fields.get('C'));
        assertEquals("change conflicts with another transaction (OC000)", fields.get('M'));
        assertFalse(fields.containsKey('F'));
    }

    @Test
    void aCascadePastTheRowLimitKeepsTheStatementItRanAndDropsSys() {
        byte[] postgres = error(Map.of('C', "54000", 'M', "transaction row limit exceeded",
                'W', "PL/pgSQL function sys._count_dml() line 14 at RAISE\n"
                        + "SQL statement \"DELETE FROM ONLY \"fk3\".\"c_cascade\" WHERE $1 OPERATOR(pg_catalog.=) \"pid\"\""));

        Map<Character, String> fields = PostgresWire.parseFields(DsqlSession.inDsqlWords((byte) 'E', postgres), 0);

        assertEquals("SQL statement \"DELETE FROM ONLY \"fk3\".\"c_cascade\" WHERE $1 OPERATOR(pg_catalog.=) \"pid\"\"",
                fields.get('W'));
    }

    @Test
    void parseMessageRoleNamesAreTranslated() {
        byte[] parameterTypes = {0, 1, 0, 0, 0, 23};
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(PostgresWire.cStringBytes("s1"));
        body.writeBytes(PostgresWire.cStringBytes("GRANT SELECT ON t TO app"));
        body.writeBytes(parameterTypes);
        PostgresWire.Message parse = new PostgresWire.Message((byte) 'P', body.toByteArray());

        DsqlSession.ParseRewrite prefixed = DsqlSession.rewriteParse(parse, new PrefixedRoleNames("x"));
        DsqlSession.ParseRewrite identity = DsqlSession.rewriteParse(parse, RoleNames.IDENTITY);

        assertEquals("s1", prefixed.name());
        assertEquals('P', prefixed.message().type());
        PostgresWire.CString name = PostgresWire.cString(prefixed.message().body(), 0);
        PostgresWire.CString query = PostgresWire.cString(prefixed.message().body(), name.next());
        assertEquals("s1", name.value());
        assertTrue(query.value().contains("dx_app"), query.value());
        assertArrayEquals(parameterTypes, Arrays.copyOfRange(prefixed.message().body(), query.next(),
                prefixed.message().body().length));
        String untranslated = PostgresWire.cString(identity.message().body(), name.next()).value();
        assertFalse(untranslated.contains("dx_app"), untranslated);
        assertTrue(untranslated.contains("app"), untranslated);
    }

    @Test
    void backendStartupUsesTheBackendRoleAndDatabase() {
        Map<String, String> client = new LinkedHashMap<>();
        client.put("user", "admin");
        client.put("database", "postgres");
        client.put("application_name", "psql");
        client.put("options", "-c search_path=public --ignored=1");
        Map<String, String> settings = DsqlSession.optionSettings(client.get("options"));

        Map<String, String> external = DsqlSession.backendStartup(client, settings, "dx_admin", "dsql_x");
        Map<String, String> docker = DsqlSession.backendStartup(client, settings, "admin", "postgres");

        assertEquals("dx_admin", external.get("user"));
        assertEquals("dsql_x", external.get("database"));
        assertEquals("psql", external.get("application_name"));
        assertEquals("-c search_path=public", external.get("options"));
        assertEquals(List.of("user", "database", "application_name", "options"), List.copyOf(external.keySet()));
        assertEquals("admin", docker.get("user"));
        assertEquals("postgres", docker.get("database"));
        assertEquals("admin", client.get("user"), "the client's own parameters are left as they are");
        assertEquals(Map.of("user", "admin", "database", "postgres"),
                DsqlSession.backendStartup(Map.of("user", "admin"), Map.of(), "admin", "postgres"));
    }

    private static byte[] error(Map<Character, String> fields) {
        Map<Character, String> ordered = new LinkedHashMap<>();
        ordered.put('S', fields.getOrDefault('S', "ERROR"));
        ordered.put('V', fields.getOrDefault('S', "ERROR"));
        fields.forEach((code, value) -> {
            if (code != 'S') {
                ordered.put(code, value);
            }
        });
        return PostgresWire.fields(ordered);
    }
}
