package io.github.hectorvent.floci.services.firehose;

import io.github.hectorvent.floci.services.redshift.proxy.RedshiftRoleAccess;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedshiftCopySqlTest {

    @Test
    void qualifiedAndQuotedTableNamesAreAccepted() {
        for (String table : new String[] {"events", "Public.MyEvents", "\"my schema\".\"t\""}) {
            assertTrue(RedshiftCopySql.isValidTableName(table), table);
        }
    }

    @Test
    void tableNameThatIsNotAnIdentifierIsRejected() {
        for (String table : new String[] {"t; DROP TABLE x", "a.b.c", "ev\"il", "t (id)", "", null}) {
            assertFalse(RedshiftCopySql.isValidTableName(table), String.valueOf(table));
        }
    }

    @Test
    void columnThatIsNotAnIdentifierIsRejected() {
        assertFalse(RedshiftCopySql.isValidColumnList("id, name); DROP TABLE t; --"));
        assertTrue(RedshiftCopySql.isValidColumnList("Id, \"Full Name\""));
        assertTrue(RedshiftCopySql.isValidColumnList(null));
    }

    @Test
    void statementSeparatorOutsideQuotesIsDetected() {
        assertTrue(RedshiftCopySql.hasStatementSeparator("gzip; DROP TABLE t"));
    }

    @Test
    void semicolonInsideAQuotedCopyOptionValueIsAllowed() {
        assertFalse(RedshiftCopySql.hasStatementSeparator("DELIMITER ';' CSV"));
    }

    @Test
    void apiReferenceCopyOptionsExampleIsAccepted() {
        assertFalse(RedshiftCopySql.hasStatementSeparator("delimiter '\\t' lzop;"));
    }

    @Test
    void oneClosingTerminatorIsAcceptedWithWhitespaceAroundIt() {
        assertFalse(RedshiftCopySql.hasStatementSeparator("gzip ;"));
        assertFalse(RedshiftCopySql.hasStatementSeparator("gzip;  \n"));
    }

    @Test
    void terminatorFollowedByAnythingIsRefused() {
        assertTrue(RedshiftCopySql.hasStatementSeparator("gzip;;"));
        assertTrue(RedshiftCopySql.hasStatementSeparator("gzip; DROP TABLE t;"));
        assertTrue(RedshiftCopySql.hasStatementSeparator("gzip; -- x"));
    }

    @Test
    void terminatorInsideAnUnclosedLiteralIsRefused() {
        assertTrue(RedshiftCopySql.hasStatementSeparator("NULL AS 'a;"));
    }

    @Test
    void withoutTrailingTerminatorDropsOnlyTheClosingSemicolon() {
        assertEquals("gzip", RedshiftCopySql.withoutTrailingTerminator("gzip ; "));
        assertEquals("DELIMITER ';'", RedshiftCopySql.withoutTrailingTerminator("DELIMITER ';'"));
        assertEquals("gzip", RedshiftCopySql.withoutTrailingTerminator("gzip"));
    }

    @Test
    void separatorScanHandlesEscapedQuotes() {
        assertFalse(RedshiftCopySql.hasStatementSeparator("NULL AS 'it''s;ok'"));
        assertTrue(RedshiftCopySql.hasStatementSeparator("NULL AS 'it''s' ; DROP"));
    }

    @Test
    void doubleQuotedValueMayContainASemicolon() {
        assertFalse(RedshiftCopySql.hasStatementSeparator("DELIMITER \";\" CSV"));
    }

    @Test
    void escapeStringCannotHideASecondStatement() {
        assertTrue(RedshiftCopySql.hasStatementSeparator("NULL AS E'\\'';DROP TABLE t;--'"));
    }

    @Test
    void quoteInsideADoubleQuotedIdentifierCannotHideASecondStatement() {
        assertTrue(RedshiftCopySql.hasStatementSeparator("\"a'b\"; DROP TABLE t; --'"));
    }

    @Test
    void escapeStringKeepsAnEscapedQuoteInsideTheLiteral() {
        assertFalse(RedshiftCopySql.hasStatementSeparator("NULL AS E'it\\'s;ok'"));
    }

    @Test
    void dollarQuoteTagsWithDigitsOrNonAsciiLettersCannotHideASecondStatement() {
        assertTrue(RedshiftCopySql.hasStatementSeparator("NULL AS $a1$'$a1$; drop table t; --'"));
        assertTrue(RedshiftCopySql.hasStatementSeparator("NULL AS $é$'$é$;x;--'"));
        assertTrue(RedshiftCopySql.hasStatementSeparator("NULL AS $_tag9$ x $_tag9$"));
    }

    @Test
    void dollarQuoteTagOutsideTheBasicPlaneCannotHideASecondStatement() {
        assertTrue(RedshiftCopySql.hasStatementSeparator("NULL AS $😀$'$😀$; drop table t; --'"));
    }

    @Test
    void dollarSignInsideAQuotedLiteralIsAllowed() {
        assertFalse(RedshiftCopySql.hasStatementSeparator("NULL AS 'a$b$' CSV"));
        assertFalse(RedshiftCopySql.hasStatementSeparator("DELIMITER \"$\" CSV"));
    }

    @Test
    void anyDollarSignOutsideAQuotedLiteralIsRefused() {
        assertTrue(RedshiftCopySql.hasStatementSeparator("NULL AS $x"));
    }

    @Test
    void buildsCopyWithOptionsAndNoIamRole() {
        String sql = RedshiftCopySql.build("events", null, "stage", "2026/10/19/f.gz", "json 'auto' gzip");

        assertEquals("COPY events FROM 's3://stage/2026/10/19/f.gz' json 'auto' gzip", sql);
    }

    @Test
    void buildPutsCredentialsRightAfterTheSourceAndBeforeTheOptions() {
        RedshiftRoleAccess.RoleSession session = new RedshiftRoleAccess.RoleSession("ASIAEXAMPLE", "s3cr3t/+x", "tok==");

        String sql = RedshiftCopySql.build("events", "id, name", "stage", "k", "json 'auto'", session);

        assertEquals("COPY events (id, name) FROM 's3://stage/k' CREDENTIALS "
                + "'aws_access_key_id=ASIAEXAMPLE;aws_secret_access_key=s3cr3t/+x;token=tok==' json 'auto'", sql);
    }

    @Test
    void buildRefusesCredentialsItCannotQuoteWithoutEchoingThem() {
        RedshiftRoleAccess.RoleSession session = new RedshiftRoleAccess.RoleSession("ASIAEXAMPLE", "bad'secret", "tok");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> RedshiftCopySql.build("t", null, "b", "k", null, session));

        assertFalse(e.getMessage().contains("bad"), e.getMessage());
    }

    @Test
    void redactHidesTheSecretAndTokenOnly() {
        RedshiftRoleAccess.RoleSession session = new RedshiftRoleAccess.RoleSession("ASIAEXAMPLE", "s3cr3t", "t0k3n9");

        assertEquals("key ASIAEXAMPLE secret=*** token=***",
                RedshiftCopySql.redact("key ASIAEXAMPLE secret=s3cr3t token=t0k3n9", session));
        assertEquals("plain", RedshiftCopySql.redact("plain", null));
    }

    @Test
    void buildDropsTheClosingTerminatorOfTheApiReferenceExample() {
        String sql = RedshiftCopySql.build("t", null, "b", "k", "delimiter '\\t' lzop;");

        assertEquals("COPY t FROM 's3://b/k' delimiter '\\t' lzop", sql);
    }

    @Test
    void buildKeepsATerminatorInsideAQuotedValue() {
        String sql = RedshiftCopySql.build("t", null, "b", "k", "DELIMITER ';' CSV");

        assertEquals("COPY t FROM 's3://b/k' DELIMITER ';' CSV", sql);
    }

    @Test
    void tableAndColumnsAreKeptVerbatimSoRedshiftCaseFoldingApplies() {
        String sql = RedshiftCopySql.build("Public.MyEvents", "Id, \"Full Name\"", "stage", "k", null);

        assertEquals("COPY Public.MyEvents (Id, \"Full Name\") FROM 's3://stage/k'", sql);
    }

    @Test
    void buildRejectsNamesAndOptionsThatCouldCarryAnotherStatement() {
        assertThrows(IllegalArgumentException.class, () -> RedshiftCopySql.build("t; DROP TABLE x", null, "b", "k", null));
        assertThrows(IllegalArgumentException.class,
                () -> RedshiftCopySql.build("t", "id, name); DROP TABLE t; --", "b", "k", null));
        assertThrows(IllegalArgumentException.class, () -> RedshiftCopySql.build("t", null, "b", "k", "gzip; DROP TABLE t"));
    }

    @Test
    void objectKeyThatTheProxyCouldNotParseIsRejected() {
        for (String key : new String[] {"my prefix/f.gz", "it's/f.gz", "say\"/f.gz"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> RedshiftCopySql.build("t", null, "stage", key, null), key);
        }
    }

    @Test
    void dollarQuotingAndCommentsAreRefused() {
        assertTrue(RedshiftCopySql.hasStatementSeparator("NULL AS $$'$$ gzip"));
        assertTrue(RedshiftCopySql.hasStatementSeparator("gzip -- trailing"));
        assertTrue(RedshiftCopySql.hasStatementSeparator("gzip /* x */"));
    }
}
