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
    void parsesIamRoleOnCopy() {
        CopyStatementParser.S3CopyFrom c = copyFrom(
                "COPY t FROM 's3://b/k' IAM_ROLE 'arn:aws:iam::000000000000:role/CopyRole'");
        assertEquals("arn:aws:iam::000000000000:role/CopyRole", c.iamRoleArn());
    }

    @Test
    void parsesIamRoleCombinedWithOtherOptions() {
        CopyStatementParser.S3CopyFrom c = copyFrom(
                "COPY t FROM 's3://b/k' IAM_ROLE 'arn:aws:iam::000000000000:role/CopyRole' "
                        + "GZIP DELIMITER ','");
        assertEquals("arn:aws:iam::000000000000:role/CopyRole", c.iamRoleArn());
        assertTrue(c.gzip());
        assertEquals(",", c.delimiter());
    }

    @Test
    void copyWithoutIamRoleLeavesItNull() {
        CopyStatementParser.S3CopyFrom c = copyFrom("COPY t FROM 's3://b/k'");
        assertNull(c.iamRoleArn());
    }

    @Test
    void iamRoleDefaultKeywordIsStillUnsupported() {
        // Bare `default` (no quotes) does not match the quoted-ARN clause, so it falls through
        // to the existing catch-all and the whole statement is rejected (fail-open), same as today.
        assertNull(CopyStatementParser.parse("COPY t FROM 's3://b/k' IAM_ROLE default"));
    }

    @Test
    void parsesIamRoleOnUnload() {
        CopyStatementParser.S3Unload u = (CopyStatementParser.S3Unload) CopyStatementParser.parse(
                "UNLOAD ('select 1') TO 's3://b/p' IAM_ROLE 'arn:aws:iam::000000000000:role/UnloadRole'");
        assertEquals("arn:aws:iam::000000000000:role/UnloadRole", u.iamRoleArn());
    }

    @Test
    void unloadWithoutIamRoleLeavesItNull() {
        CopyStatementParser.S3Unload u = (CopyStatementParser.S3Unload) CopyStatementParser.parse(
                "UNLOAD ('select 1') TO 's3://b/p'");
        assertNull(u.iamRoleArn());
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
        assertNull(CopyStatementParser.parse("COPY t FROM 's3://b/k' JSON 's3://mybucket/jsonpaths.json'"));
        assertNull(CopyStatementParser.parse("COPY t FROM 's3://b/k' FIXEDWIDTH 'a:1,b:2'"));
        assertNull(CopyStatementParser.parse("COPY t FROM 's3://b/k' GZIP MAXERROR 10"));
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
        assertNull(CopyStatementParser.parse("UNLOAD ('select 1') TO 's3://b/p/' MASTER_SYMMETRIC_KEY 'k'"));
        assertNull(CopyStatementParser.parse("UNLOAD ('select 1') TO 's3://b/p/' KMS_KEY_ID 'k'"));
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

    @Test
    void copyAcceptsAndIgnoresRegion() {
        CopyStatementParser.S3CopyFrom c = copyFrom(
                "COPY t FROM 's3://b/k' REGION 'eu-west-1'");
        assertNotNull(c);
        assertNull(c.iamRoleArn());
    }

    @Test
    void copyMapsCredentialsIamRoleToIamRoleArn() {
        CopyStatementParser.S3CopyFrom c = copyFrom(
                "COPY t FROM 's3://b/k' CREDENTIALS 'aws_iam_role=arn:aws:iam::000000000000:role/r'");
        assertEquals("arn:aws:iam::000000000000:role/r", c.iamRoleArn());
    }

    @Test
    void copyAcceptsKeyCredentialsWithoutRole() {
        CopyStatementParser.S3CopyFrom c = copyFrom(
                "COPY t FROM 's3://b/k' ACCESS_KEY_ID 'test' SECRET_ACCESS_KEY 'test' SESSION_TOKEN 'test'");
        assertNotNull(c);
        assertNull(c.iamRoleArn());
    }

    @Test
    void copyAcceptsKeyCredentialsInsideCredentialsString() {
        CopyStatementParser.S3CopyFrom c = copyFrom(
                "COPY t FROM 's3://b/k' CREDENTIALS 'aws_access_key_id=test;aws_secret_access_key=test'");
        assertNotNull(c);
        assertNull(c.iamRoleArn());
    }

    @Test
    void copyRejectsConflictingOrIncompleteAuthorization() {
        assertNull(CopyStatementParser.parse(
                "COPY t FROM 's3://b/k' IAM_ROLE 'arn:aws:iam::000000000000:role/r' "
                        + "CREDENTIALS 'aws_iam_role=arn:aws:iam::000000000000:role/r2'"));
        assertNull(CopyStatementParser.parse(
                "COPY t FROM 's3://b/k' IAM_ROLE 'arn:aws:iam::000000000000:role/r' "
                        + "ACCESS_KEY_ID 'test' SECRET_ACCESS_KEY 'test'"));
        assertNull(CopyStatementParser.parse("COPY t FROM 's3://b/k' ACCESS_KEY_ID 'test'"));
        assertNull(CopyStatementParser.parse("COPY t FROM 's3://b/k' ACCESS_KEY_ID '' SECRET_ACCESS_KEY ''"));
        assertNull(CopyStatementParser.parse("COPY t FROM 's3://b/k' ACCESS_KEY_ID 'test' SECRET_ACCESS_KEY ' '"));
        assertNull(CopyStatementParser.parse(
                "COPY t FROM 's3://b/k' ACCESS_KEY_ID 'test' SECRET_ACCESS_KEY 'test' SESSION_TOKEN ''"));
        assertNull(CopyStatementParser.parse("COPY t FROM 's3://b/k' IAM_ROLE ''"));
        assertNull(CopyStatementParser.parse(
                "UNLOAD ('select 1') TO 's3://b/out/' ACCESS_KEY_ID '' SECRET_ACCESS_KEY ''"));
        assertNull(CopyStatementParser.parse("COPY t FROM 's3://b/k' SESSION_TOKEN 'test'"));
        assertNull(CopyStatementParser.parse("COPY t FROM 's3://b/k' REGION 'a' REGION 'b'"));
        assertNull(CopyStatementParser.parse("COPY t FROM 's3://b/k' CREDENTIALS 'aws_access_key_id=test'"));
        assertNull(CopyStatementParser.parse("COPY t FROM 's3://b/k' CREDENTIALS 'something_else=1'"));
    }

    @Test
    void copyStillRejectsEncryptionClauses() {
        assertNull(CopyStatementParser.parse("COPY t FROM 's3://b/k' ENCRYPTED"));
        assertNull(CopyStatementParser.parse("COPY t FROM 's3://b/k' MASTER_SYMMETRIC_KEY 'k'"));
    }

    @Test
    void unloadAcceptsRegionAndCredentials() {
        CopyStatementParser.S3Unload u = unload(
                "UNLOAD ('select 1') TO 's3://b/out/' REGION 'us-east-1' "
                        + "CREDENTIALS 'aws_iam_role=arn:aws:iam::000000000000:role/r'");
        assertEquals("arn:aws:iam::000000000000:role/r", u.iamRoleArn());
        assertNotNull(unload("UNLOAD ('select 1') TO 's3://b/out/' ACCESS_KEY_ID 'test' SECRET_ACCESS_KEY 'test'"));
    }

    @Test
    void unloadParsesExtensionVerbatim() {
        CopyStatementParser.S3Unload u = unload(
                "UNLOAD ('select 1') TO 's3://b/out/' EXTENSION '.csv'");
        assertEquals(".csv", u.extension());
        assertNull(unload("UNLOAD ('select 1') TO 's3://b/out/'").extension());
    }

    @Test
    void unloadRejectsUnsafeOrRepeatedExtension() {
        assertNull(CopyStatementParser.parse("UNLOAD ('select 1') TO 's3://b/out/' EXTENSION ''"));
        assertNull(CopyStatementParser.parse("UNLOAD ('select 1') TO 's3://b/out/' EXTENSION 'a/b'"));
        assertNull(CopyStatementParser.parse(
                "UNLOAD ('select 1') TO 's3://b/out/' EXTENSION '.a' EXTENSION '.b'"));
    }

    @Test
    void copyParsesFieldContentOptions() {
        CopyStatementParser.S3CopyFrom c = copyFrom(
                "COPY t FROM 's3://b/k' EMPTYASNULL BLANKSASNULL REMOVEQUOTES ACCEPTINVCHARS AS '#'");
        assertTrue(c.transforms().emptyAsNull());
        assertTrue(c.transforms().blanksAsNull());
        assertTrue(c.transforms().removeQuotes());
        assertEquals('#', c.transforms().invalidCharReplacement());
    }

    @Test
    void copyParsesTruncateColumns() {
        assertTrue(copyFrom("COPY t FROM 's3://b/k' TRUNCATECOLUMNS").transforms().truncateColumns());
        assertNull(CopyStatementParser.parse("COPY t FROM 's3://b/k' TRUNCATECOLUMNS TRUNCATECOLUMNS"));
        assertNull(CopyStatementParser.parse(
                "COPY t FROM 's3://b/k' FORMAT AS JSON 'auto' TRUNCATECOLUMNS"));
    }

    @Test
    void acceptInvCharsDefaultsToQuestionMark() {
        CopyStatementParser.S3CopyFrom c = copyFrom("COPY t FROM 's3://b/k' ACCEPTINVCHARS");
        assertEquals('?', c.transforms().invalidCharReplacement());
        assertEquals(CopyStatementParser.CopyTransforms.NONE, copyFrom("COPY t FROM 's3://b/k'").transforms());
    }

    @Test
    void acceptInvCharsReplacementCannotBeFramingSyntax() {
        assertNull(CopyStatementParser.parse("COPY t FROM 's3://b/k' ACCEPTINVCHARS AS '|'"));
        assertNull(CopyStatementParser.parse("COPY t FROM 's3://b/k' ACCEPTINVCHARS AS '\\'"));
        assertNull(CopyStatementParser.parse("COPY t FROM 's3://b/k' CSV ACCEPTINVCHARS AS '\"'"));
        assertNotNull(CopyStatementParser.parse("COPY t FROM 's3://b/k' ACCEPTINVCHARS AS '#'"));
    }

    @Test
    void copyRejectsInvalidTransformCombinations() {
        assertNull(CopyStatementParser.parse("COPY t FROM 's3://b/k' CSV REMOVEQUOTES"));
        assertNull(CopyStatementParser.parse(
                "COPY t FROM 's3://b/k' FORMAT AS JSON 'auto' EMPTYASNULL"));
        assertNull(CopyStatementParser.parse("COPY t FROM 's3://b/k' DELIMITER 'é' EMPTYASNULL"));
        assertNull(CopyStatementParser.parse("COPY t FROM 's3://b/k' EMPTYASNULL EMPTYASNULL"));
    }

    @Test
    void unloadParsesEscapeOnlyWhenFramingStaysText() {
        assertTrue(unload("UNLOAD ('select 1') TO 's3://b/out/' ESCAPE").escape());
        assertNull(CopyStatementParser.parse("UNLOAD ('select 1') TO 's3://b/out/' CSV ESCAPE"));
        assertNull(CopyStatementParser.parse("UNLOAD ('select 1') TO 's3://b/out/' ADDQUOTES ESCAPE"));
        assertNull(CopyStatementParser.parse("UNLOAD ('select 1') TO 's3://b/out/' HEADER ESCAPE"));
        assertNull(CopyStatementParser.parse("UNLOAD ('select 1') TO 's3://b/out/' ESCAPE ESCAPE"));
    }

    @Test
    void unloadParsesCleanPathAndFailsOpenWhenItCouldBeUnsafe() {
        assertTrue(unload("UNLOAD ('select 1') TO 's3://b/out/' CLEANPATH").cleanPath());
        assertNull(CopyStatementParser.parse("UNLOAD ('select 1') TO 's3://b/out/' CLEANPATH ALLOWOVERWRITE"));
        assertNull(CopyStatementParser.parse("UNLOAD ('select 1') TO 's3://b/' CLEANPATH"));
        assertNull(CopyStatementParser.parse("UNLOAD ('select 1') TO 's3://b/out/' CLEANPATH CLEANPATH"));
    }

    @Test
    void unloadParsesServerSideEncryptionOptions() {
        CopyStatementParser.S3Unload kms = unload(
                "UNLOAD ('select 1') TO 's3://b/out/' ENCRYPTED KMS_KEY_ID 'key-1'");
        assertTrue(kms.encrypted());
        assertEquals("key-1", kms.sseKmsKeyId());

        CopyStatementParser.S3Unload reversed = unload(
                "UNLOAD ('select 1') TO 's3://b/out/' KMS_KEY_ID 'key-2' ENCRYPTED");
        assertEquals("key-2", reversed.sseKmsKeyId());

        CopyStatementParser.S3Unload auto = unload("UNLOAD ('select 1') TO 's3://b/out/' ENCRYPTED AUTO");
        assertTrue(auto.encrypted());
        assertNull(auto.sseKmsKeyId());
        assertFalse(unload("UNLOAD ('select 1') TO 's3://b/out/'").encrypted());
    }

    @Test
    void unloadEncryptionFailsOpenWhenItWouldNeedClientSideKeys() {
        assertNull(CopyStatementParser.parse("UNLOAD ('select 1') TO 's3://b/out/' ENCRYPTED"));
        assertNull(CopyStatementParser.parse("UNLOAD ('select 1') TO 's3://b/out/' KMS_KEY_ID 'k'"));
        assertNull(CopyStatementParser.parse("UNLOAD ('select 1') TO 's3://b/out/' ENCRYPTED KMS_KEY_ID '  '"));
        assertNull(CopyStatementParser.parse(
                "UNLOAD ('select 1') TO 's3://b/out/' ENCRYPTED AUTO KMS_KEY_ID 'k'"));
        assertNull(CopyStatementParser.parse(
                "UNLOAD ('select 1') TO 's3://b/out/' ENCRYPTED MASTER_SYMMETRIC_KEY 'k'"));
    }

    @Test
    void unloadRejectsConflictingAuthorization() {
        assertNull(CopyStatementParser.parse(
                "UNLOAD ('select 1') TO 's3://b/out/' IAM_ROLE 'arn:aws:iam::000000000000:role/r' "
                        + "ACCESS_KEY_ID 'test' SECRET_ACCESS_KEY 'test'"));
        assertNull(CopyStatementParser.parse("UNLOAD ('select 1') TO 's3://b/out/' REGION 'a' REGION 'b'"));
    }

    @Test
    void parseCopy_jsonAuto_explicitColumns() {
        CopyStatementParser.S3CopyFrom c = copyFrom("COPY users (id, name) FROM 's3://mybucket/users.json' "
                + "IAM_ROLE 'arn:aws:iam::123456789012:role/RedshiftRole' "
                + "FORMAT AS JSON 'auto'");
        assertEquals("users", c.targetTable());
        assertEquals(List.of("id", "name"), c.columns());
        assertEquals("mybucket", c.bucket());
        assertEquals("users.json", c.keyOrPrefix());
        assertTrue(c.jsonAuto());
        assertFalse(c.manifest());
    }

    @Test
    void parseCopy_jsonAuto_variations() {
        CopyStatementParser.S3CopyFrom c1 = copyFrom("COPY tbl FROM 's3://b/data.json' JSON 'auto'");
        assertTrue(c1.jsonAuto());
        assertFalse(c1.jsonAutoIgnoreCase());

        CopyStatementParser.S3CopyFrom c2 = copyFrom("COPY tbl FROM 's3://b/data.json' JSON AS 'auto'");
        assertTrue(c2.jsonAuto());
        assertFalse(c2.jsonAutoIgnoreCase());

        CopyStatementParser.S3CopyFrom c3 = copyFrom("COPY tbl FROM 's3://b/data.json' FORMAT JSON 'auto'");
        assertTrue(c3.jsonAuto());
        assertFalse(c3.jsonAutoIgnoreCase());

        CopyStatementParser.S3CopyFrom c4 = copyFrom("COPY tbl FROM 's3://b/data.json' JSON 'auto ignorecase'");
        assertTrue(c4.jsonAuto());
        assertTrue(c4.jsonAutoIgnoreCase());

        CopyStatementParser.S3CopyFrom c5 = copyFrom("COPY tbl FROM 's3://b/data.json' FORMAT AS JSON 'auto ignorecase'");
        assertTrue(c5.jsonAuto());
        assertTrue(c5.jsonAutoIgnoreCase());
    }

    @Test
    void parseCopy_manifest_gzip() {
        CopyStatementParser.S3CopyFrom c = copyFrom("COPY sales FROM 's3://mybucket/manifest.json' "
                + "IAM_ROLE 'arn:aws:iam::123456789012:role/RedshiftRole' "
                + "MANIFEST GZIP CSV");
        assertTrue(c.manifest());
        assertTrue(c.gzip());
        assertTrue(c.csv());
        assertFalse(c.jsonAuto());
    }

    @Test
    void parseCopy_jsonAutoAndManifest() {
        CopyStatementParser.S3CopyFrom c = copyFrom("COPY tbl FROM 's3://b/manifest' "
                + "MANIFEST FORMAT AS JSON 'auto'");
        assertTrue(c.manifest());
        assertTrue(c.jsonAuto());
    }

    @Test
    void parseCopy_rejectsUnsupportedJsonPathsAndParquet() {
        // Only JSON 'auto' is supported; jsonpaths or bare JSON must be rejected
        assertNull(CopyStatementParser.parse("COPY tbl FROM 's3://b/k' JSON 's3://b/paths.json'"));
        assertNull(CopyStatementParser.parse("COPY tbl FROM 's3://b/k' PARQUET"));
    }
}

