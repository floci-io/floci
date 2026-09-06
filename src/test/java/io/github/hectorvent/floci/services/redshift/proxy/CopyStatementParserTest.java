package io.github.hectorvent.floci.services.redshift.proxy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopyStatementParserTest {

    /** parse() returns S3Statement now; every COPY test wants the S3CopyFrom view. */
    private static CopyStatementParser.S3CopyFrom copyFrom(String sql) {
        CopyStatementParser.S3Statement s = CopyStatementParser.parse(sql);
        return (CopyStatementParser.S3CopyFrom) s;
    }

    @Test
    void parsesMinimalCopyFromKey() {
        CopyStatementParser.S3CopyFrom c = copyFrom(
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
        CopyStatementParser.S3CopyFrom c = copyFrom(
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
        CopyStatementParser.S3CopyFrom c = copyFrom(
                "COPY t FROM 's3://b/k' CSV HEADER");
        assertEquals(",", c.delimiter());
        assertEquals(1, c.headerLines());
    }

    @Test
    void decodesTabDelimiterToken() {
        CopyStatementParser.S3CopyFrom c = copyFrom(
                "COPY t FROM 's3://b/k' DELIMITER '\\\\t'");
        assertEquals("\t", c.delimiter());
    }

    @Test
    void bucketOnlyPathGivesEmptyPrefix() {
        CopyStatementParser.S3CopyFrom c = copyFrom("COPY t FROM 's3://only-bucket'");
        assertEquals("only-bucket", c.bucket());
        assertEquals("", c.keyOrPrefix());
    }

    @Test
    void stripsLeadingComments() {
        CopyStatementParser.S3CopyFrom c = copyFrom(
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
        CopyStatementParser.S3CopyFrom c = copyFrom(
                "COPY \"My Schema\".\"Tab\" (\"col one\") FROM 's3://b/k'");
        assertEquals("\"My Schema\".\"Tab\"", c.targetTable());
        assertEquals(List.of("\"col one\""), c.columns());
    }

    @Test
    void explicitDelimiterOverridesCsvDefault() {
        CopyStatementParser.S3CopyFrom c = copyFrom(
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
        CopyStatementParser.S3CopyFrom c = copyFrom("COPY t FROM 's3://b/k' GZIP;");
        assertTrue(c.gzip());
    }

    @Test
    void aQuotedKeywordInAnOptionValueDoesNotChangeParsing() {
        CopyStatementParser.S3CopyFrom c = copyFrom(
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
        CopyStatementParser.S3CopyFrom c = copyFrom(
                "COPY t FROM 's3://b/k' DELIMITER ''''");
        assertEquals("'", c.delimiter());
    }

    private static CopyStatementParser.S3Unload unload(String sql) {
        return (CopyStatementParser.S3Unload) CopyStatementParser.parse(sql);
    }

    @Test
    void parsesMinimalUnload() {
        CopyStatementParser.S3Unload u = unload(
                "UNLOAD ('select id, name from sales') TO 's3://warehouse/out/'");
        assertEquals("select id, name from sales", u.selectQuery());
        assertEquals("warehouse", u.bucket());
        assertEquals("out/", u.prefix());
        assertEquals("|", u.delimiter());
        assertFalse(u.csv());
        assertFalse(u.gzip());
        assertFalse(u.header());
        assertFalse(u.manifest());
        assertFalse(u.allowOverwrite());
        assertTrue(u.parallel());
        assertEquals(0L, u.maxFileSizeBytes());
        assertNull(u.nullAs());
    }

    @Test
    void parsesUnloadOptions() {
        CopyStatementParser.S3Unload u = unload(
                "UNLOAD ('select * from t') TO 's3://b/p/' "
                        + "FORMAT AS CSV DELIMITER ',' HEADER GZIP ADDQUOTES MANIFEST "
                        + "ALLOWOVERWRITE PARALLEL OFF NULL AS 'nil' MAXFILESIZE 5 MB");
        assertTrue(u.csv());
        assertEquals(",", u.delimiter());
        assertTrue(u.header());
        assertTrue(u.gzip());
        assertTrue(u.addQuotes());
        assertTrue(u.manifest());
        assertTrue(u.allowOverwrite());
        assertFalse(u.parallel());
        assertEquals("nil", u.nullAs());
        assertEquals(5L * 1024 * 1024, u.maxFileSizeBytes());
    }

    @Test
    void unloadDefaultsCsvDelimiterToComma() {
        assertEquals(",", unload("UNLOAD ('select 1') TO 's3://b/p/' CSV").delimiter());
    }

    @Test
    void unloadParsesMaxFileSizeUnits() {
        assertEquals(10L * 1024 * 1024, unload("UNLOAD ('select 1') TO 's3://b/p/' MAXFILESIZE 10 MB").maxFileSizeBytes());
        assertEquals(1024L * 1024 * 1024 / 8, unload("UNLOAD ('select 1') TO 's3://b/p/' MAXFILESIZE 0.125 GB").maxFileSizeBytes());
        assertEquals(4096L, unload("UNLOAD ('select 1') TO 's3://b/p/' MAXFILESIZE 4096").maxFileSizeBytes());
    }

    @Test
    void unloadRejectsMaxFileSizeAboveTheBufferingCeiling() {
        // The simulator buffers a file at a time, so a per-file size it cannot hold fails open.
        assertNull(CopyStatementParser.parse("UNLOAD ('select 1') TO 's3://b/p/' MAXFILESIZE 2 GB"));
        assertNull(CopyStatementParser.parse("UNLOAD ('select 1') TO 's3://b/p/' MAXFILESIZE 500 MB"));
    }

    @Test
    void unloadUnescapesDoubledSingleQuotesInSelect() {
        assertEquals("select 'x' from t",
                unload("UNLOAD ('select ''x'' from t') TO 's3://b/p/'").selectQuery());
    }

    @Test
    void unloadAcceptsWithCteSelect() {
        assertNotNull(unload("UNLOAD ('with c as (select 1) select * from c') TO 's3://b/p/'"));
    }

    @Test
    void unloadRejectsSubqueryBreakoutAttempts() {
        assertNull(CopyStatementParser.parse("UNLOAD ('select 1) TO PROGRAM ''id'' --') TO 's3://b/p/'"));
        assertNull(CopyStatementParser.parse("UNLOAD ('select 1; drop table t') TO 's3://b/p/'"));
        assertNull(CopyStatementParser.parse("UNLOAD ('select $$x$$') TO 's3://b/p/'"));
        assertNull(CopyStatementParser.parse("UNLOAD ('select 1)') TO 's3://b/p/'"));
        assertNull(CopyStatementParser.parse("UNLOAD ('delete from t') TO 's3://b/p/'"));
        assertNull(CopyStatementParser.parse("UNLOAD ('select 1 /* c */') TO 's3://b/p/'"));
        assertNull(CopyStatementParser.parse("UNLOAD ('select E''breakout''') TO 's3://b/p/'"));
        assertNull(CopyStatementParser.parse("UNLOAD ('select U&''breakout''') TO 's3://b/p/'"));
        assertNull(CopyStatementParser.parse("UNLOAD ('select \\1') TO 's3://b/p/'"));
    }

    @Test
    void unloadRejectsUnsupportedOptions() {
        assertNull(CopyStatementParser.parse("UNLOAD ('select 1') TO 's3://b/p/' PARQUET"));
        assertNull(CopyStatementParser.parse("UNLOAD ('select 1') TO 's3://b/p/' ENCRYPTED"));
        assertNull(CopyStatementParser.parse("UNLOAD ('select 1') TO 's3://b/p/' IAM_ROLE 'arn:aws:iam::0:role/r'"));
        assertNull(CopyStatementParser.parse("UNLOAD ('select 1') TO 's3://b/p/' REGION 'us-west-2'"));
        assertNull(CopyStatementParser.parse("UNLOAD ('select 1') TO 's3://b/p/' EXTENSION 'csv'"));
        assertNull(CopyStatementParser.parse("UNLOAD ('select 1') TO 's3://b/p/' ZSTD"));
        assertNull(CopyStatementParser.parse("UNLOAD ('select 1') TO 's3://b/p/' PARTITION BY (dt)"));
    }

    @Test
    void unloadRejectsDuplicateOptionsAndUnknownTokens() {
        assertNull(CopyStatementParser.parse("UNLOAD ('select 1') TO 's3://b/p/' GZIP GZIP"));
        assertNull(CopyStatementParser.parse("UNLOAD ('select 1') TO 's3://b/p/' FOO"));
        assertNull(CopyStatementParser.parse("UNLOAD ('select 1') TO 's3://b/p/'; SELECT 1"));
    }

    @Test
    void unloadQuotedKeywordInValueDoesNotFlipParsing() {
        CopyStatementParser.S3Unload u = unload("UNLOAD ('select 1') TO 's3://b/p/' NULL AS 'csv'");
        assertFalse(u.csv());
        assertEquals("csv", u.nullAs());
    }
}
