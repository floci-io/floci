package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import org.h2.Driver;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedshiftDataSqlParametersTest {

    static {
        Driver.load();
    }

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void rewritesNamedPlaceholdersInOrderAndSkipsLiteralsAndCasts() {
        RedshiftDataSqlParameters.ParsedSql parsed = RedshiftDataSqlParameters.parse(
                "select * from t where a = :a and b = ':b' and c = :a and d = 1::int");
        assertEquals("select * from t where a = ? and b = ':b' and c = ? and d = 1::int", parsed.sql());
        assertEquals(List.of("a", "a"), parsed.parameterOrder());
    }

    @Test
    void bindsValuesAsStringsAndDatabaseCoerces() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            c.createStatement().execute("create table t (id int, name varchar(20))");
            c.createStatement().execute("insert into t values (2, 'bob')");
            RedshiftDataSqlParameters.ParsedSql parsed =
                    RedshiftDataSqlParameters.parse("select name from t where id = :id");
            try (PreparedStatement ps = c.prepareStatement(parsed.sql())) {
                RedshiftDataSqlParameters.bind(ps, parsed.parameterOrder(), Map.of("id", "2"));
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("bob", rs.getString(1));
                }
            }
        }
    }

    @Test
    void missingParameterValueIsRejected() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            c.createStatement().execute("create table t (id int)");
            RedshiftDataSqlParameters.ParsedSql parsed =
                    RedshiftDataSqlParameters.parse("select * from t where id = :id");
            try (PreparedStatement ps = c.prepareStatement(parsed.sql())) {
                AwsException e = assertThrows(AwsException.class,
                        () -> RedshiftDataSqlParameters.bind(ps, parsed.parameterOrder(), Map.of()));
                assertEquals("ValidationException", e.getErrorCode());
            }
        }
    }

    @Test
    void parseParametersReadsNameValueObjectsAndRejectsDuplicates() {
        ObjectNode request = om.createObjectNode();
        var params = request.putArray("Parameters");
        params.addObject().put("name", "id").put("value", "1");
        params.addObject().put("name", "id").put("value", "2");
        AwsException e = assertThrows(AwsException.class,
                () -> RedshiftDataSqlParameters.parseParameters(request, "Parameters"));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void isMultiStatementIgnoresSemicolonsInsideCommentsLiteralsAndDollarQuotes() {
        assertFalse(RedshiftDataSqlParameters.isMultiStatement("select 1 -- a; b\n"));
        assertFalse(RedshiftDataSqlParameters.isMultiStatement("select * from t /* x; y */ where a = 1"));
        assertFalse(RedshiftDataSqlParameters.isMultiStatement("select ';' as sep"));
        assertFalse(RedshiftDataSqlParameters.isMultiStatement("select $tag$a;b$tag$"));
        assertFalse(RedshiftDataSqlParameters.isMultiStatement("select 1;"));
        assertFalse(RedshiftDataSqlParameters.isMultiStatement("select 1 ;  \n "));
    }

    @Test
    void isMultiStatementDetectsARealSecondStatement() {
        assertTrue(RedshiftDataSqlParameters.isMultiStatement("select 1; select 2"));
        assertTrue(RedshiftDataSqlParameters.isMultiStatement("insert into t values (1); delete from t"));
    }

    @Test
    void backslashEscapedQuoteInsideAnEscapeStringDoesNotEndTheLiteral() {
        // E'it\'s :value' is one string literal; :value is literal text, not a bind marker.
        RedshiftDataSqlParameters.ParsedSql parsed =
                RedshiftDataSqlParameters.parse("select E'it\\'s :value' as v where id = :id");
        assertEquals("select E'it\\'s :value' as v where id = ?", parsed.sql());
        assertEquals(List.of("id"), parsed.parameterOrder());
    }

    @Test
    void escapeStringWithAnEscapedQuoteIsNotSeenAsMultiStatement() {
        assertFalse(RedshiftDataSqlParameters.isMultiStatement("select E'a\\';b' as v"));
    }

    @Test
    void plainLiteralStillTreatsBackslashLiterally() {
        // Not an E'' string: backslash is an ordinary character, '' still ends the literal.
        RedshiftDataSqlParameters.ParsedSql parsed =
                RedshiftDataSqlParameters.parse("select 'a\\' , :x");
        assertEquals("select 'a\\' , ?", parsed.sql());
        assertEquals(List.of("x"), parsed.parameterOrder());
    }
}
