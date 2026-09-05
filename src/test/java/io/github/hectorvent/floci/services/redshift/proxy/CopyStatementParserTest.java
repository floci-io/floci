package io.github.hectorvent.floci.services.redshift.proxy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopyStatementParserTest {

    @Test
    void parsesMinimalCopyFromKey() {
        CopyStatementParser.S3CopyFrom c = CopyStatementParser.parse(
                "COPY sales FROM 's3://warehouse/data/sales.txt'");
        assertEquals("sales", c.targetTable());
        assertEquals(List.of(), c.columns());
        assertEquals("warehouse", c.bucket());
        assertEquals("data/sales.txt", c.keyOrPrefix());
        assertEquals("|", c.delimiter());
        assertEquals(0, c.headerLines());
        assertFalse(c.gzip());
        assertFalse(c.csv());
        assertNull(c.nullAs());
    }

    @Test
    void parsesColumnsOptionsAndPrefix() {
        CopyStatementParser.S3CopyFrom c = CopyStatementParser.parse(
                "COPY public.events (id, ts, note) FROM 's3://bkt/evt/' "
                        + "GZIP DELIMITER ',' IGNOREHEADER 2 NULL AS '\\\\N' FORMAT AS CSV");
        assertEquals("public.events", c.targetTable());
        assertEquals(List.of("id", "ts", "note"), c.columns());
        assertEquals("bkt", c.bucket());
        assertEquals("evt/", c.keyOrPrefix());
        assertTrue(c.gzip());
        assertTrue(c.csv());
        assertEquals(",", c.delimiter());
        assertEquals(2, c.headerLines());
        assertEquals("\\N", c.nullAs());
    }

    @Test
    void defaultsDelimiterToCommaForCsvAndTreatsHeaderKeywordAsOneLine() {
        CopyStatementParser.S3CopyFrom c = CopyStatementParser.parse(
                "COPY t FROM 's3://b/k' CSV HEADER");
        assertEquals(",", c.delimiter());
        assertEquals(1, c.headerLines());
    }

    @Test
    void decodesTabDelimiterToken() {
        CopyStatementParser.S3CopyFrom c = CopyStatementParser.parse(
                "COPY t FROM 's3://b/k' DELIMITER '\\\\t'");
        assertEquals("\t", c.delimiter());
    }

    @Test
    void bucketOnlyPathGivesEmptyPrefix() {
        CopyStatementParser.S3CopyFrom c = CopyStatementParser.parse("COPY t FROM 's3://only-bucket'");
        assertEquals("only-bucket", c.bucket());
        assertEquals("", c.keyOrPrefix());
    }

    @Test
    void stripsLeadingComments() {
        CopyStatementParser.S3CopyFrom c = CopyStatementParser.parse(
                "-- load nightly\n/* batch */ COPY t FROM 's3://b/k'");
        assertEquals("t", c.targetTable());
    }

    @Test
    void rejectsInjectionInTableName() {
        assertNull(CopyStatementParser.parse("COPY t; DROP TABLE u; FROM 's3://b/k'"));
        assertNull(CopyStatementParser.parse("COPY (SELECT 1) FROM 's3://b/k'"));
    }

    @Test
    void rejectsInjectionInColumnList() {
        assertNull(CopyStatementParser.parse("COPY t (id, x) FROM STDIN) --) FROM 's3://b/k'"));
        assertNull(CopyStatementParser.parse("COPY t (id, ts::text) FROM 's3://b/k'"));
    }

    @Test
    void returnsNullForNonCopyAndForCopyWithoutS3() {
        assertNull(CopyStatementParser.parse("SELECT 1"));
        assertNull(CopyStatementParser.parse("CREATE TABLE t (id int) DISTKEY (id)"));
        assertNull(CopyStatementParser.parse("COPY t FROM STDIN"));
        assertNull(CopyStatementParser.parse("COPY t TO 's3://b/k'"));
        assertNull(CopyStatementParser.parse(null));
        assertNull(CopyStatementParser.parse("   "));
    }

    @Test
    void quotedIdentifiersSurvive() {
        CopyStatementParser.S3CopyFrom c = CopyStatementParser.parse(
                "COPY \"My Schema\".\"Tab\" (\"col one\") FROM 's3://b/k'");
        assertEquals("\"My Schema\".\"Tab\"", c.targetTable());
        assertEquals(List.of("\"col one\""), c.columns());
    }

    @Test
    void explicitDelimiterOverridesCsvDefault() {
        CopyStatementParser.S3CopyFrom c = CopyStatementParser.parse(
                "COPY t FROM 's3://b/k' CSV DELIMITER '\t'");
        assertEquals("\t", c.delimiter());
        assertTrue(c.csv());
    }

    @Test
    void returnsNullForUnsupportedClauses() {
        assertNull(CopyStatementParser.parse("COPY t FROM 's3://b/k' FORMAT AS PARQUET"));
        assertNull(CopyStatementParser.parse("COPY t FROM 's3://b/k' JSON 'auto'"));
        assertNull(CopyStatementParser.parse("COPY t FROM 's3://b/k' FIXEDWIDTH 'a:1,b:2'"));
        assertNull(CopyStatementParser.parse("COPY t FROM 's3://b/k' GZIP MAXERROR 10"));
        assertNull(CopyStatementParser.parse("COPY t FROM 's3://b/k' IAM_ROLE 'arn:aws:iam::0:role/r'"));
        assertNull(CopyStatementParser.parse("COPY t FROM 's3://b/k' DATEFORMAT 'YYYY-MM-DD'"));
    }

    @Test
    void returnsNullWhenAStatementFollowsTheCopy() {
        assertNull(CopyStatementParser.parse("COPY t FROM 's3://b/k'; DROP TABLE staging"));
        assertNull(CopyStatementParser.parse("COPY t FROM 's3://b/k' GZIP; SELECT 1"));
    }

    @Test
    void toleratesALoneTrailingSemicolon() {
        CopyStatementParser.S3CopyFrom c = CopyStatementParser.parse("COPY t FROM 's3://b/k' GZIP;");
        assertTrue(c.gzip());
    }

    @Test
    void aQuotedKeywordInAnOptionValueDoesNotChangeParsing() {
        CopyStatementParser.S3CopyFrom c = CopyStatementParser.parse(
                "COPY t FROM 's3://b/k' NULL AS 'csv' DELIMITER ';'");
        assertFalse(c.csv());
        assertEquals(";", c.delimiter());
        assertEquals("csv", c.nullAs());
    }

    @Test
    void rejectsTyposInOptionKeywords() {
        assertNull(CopyStatementParser.parse("COPY t FROM 's3://b/k' DELIMETER ','"));
        assertNull(CopyStatementParser.parse("COPY t FROM 's3://b/k' GZIPP"));
        assertNull(CopyStatementParser.parse("COPY t FROM 's3://b/k' HEADERR"));
    }

    @Test
    void rejectsLeftoverOrUnknownTokens() {
        assertNull(CopyStatementParser.parse("COPY t FROM 's3://b/k' GZIP EXTRA_TOKEN"));
        assertNull(CopyStatementParser.parse("COPY t FROM 's3://b/k' SOME_UNKNOWN_OPTION"));
        assertNull(CopyStatementParser.parse("COPY t FROM 's3://b/k' CSV FOO"));
    }

    @Test
    void rejectsDuplicateOrConflictingOptions() {
        assertNull(CopyStatementParser.parse("COPY t FROM 's3://b/k' GZIP GZIP"));
        assertNull(CopyStatementParser.parse("COPY t FROM 's3://b/k' CSV FORMAT CSV"));
        assertNull(CopyStatementParser.parse("COPY t FROM 's3://b/k' DELIMITER ',' DELIMITER '\t'"));
        assertNull(CopyStatementParser.parse("COPY t FROM 's3://b/k' HEADER IGNOREHEADER 2"));
    }

    @Test
    void parsesEscapedSingleQuoteInDelimiter() {
        CopyStatementParser.S3CopyFrom c = CopyStatementParser.parse(
                "COPY t FROM 's3://b/k' DELIMITER ''''");
        assertEquals("'", c.delimiter());
    }
}
