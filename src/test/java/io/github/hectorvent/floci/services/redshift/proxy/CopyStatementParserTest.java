package io.github.hectorvent.floci.services.redshift.proxy;

import io.github.hectorvent.floci.services.redshift.proxy.CopyStatementParser.S3CopyFrom;
import io.github.hectorvent.floci.services.redshift.proxy.CopyStatementParser.S3Statement;
import io.github.hectorvent.floci.services.redshift.proxy.CopyStatementParser.S3Unload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopyStatementParserTest {

    @Test
    @DisplayName("Parse standard COPY FROM S3 with CSV, DELIMITER, IGNOREHEADER, GZIP and IAM_ROLE")
    void testParseCopyStandard() {
        String sql = "COPY sales FROM 's3://mybucket/sales.csv' IAM_ROLE 'arn:aws:iam::123456789012:role/MyRole' CSV DELIMITER ',' IGNOREHEADER 1 GZIP;";
        S3Statement stmt = CopyStatementParser.parse(sql);

        assertNotNull(stmt);
        S3CopyFrom copy = assertInstanceOf(S3CopyFrom.class, stmt);
        assertEquals("sales", copy.targetTable());
        assertTrue(copy.columns() == null || copy.columns().isEmpty());
        assertEquals("mybucket", copy.bucket());
        assertEquals("sales.csv", copy.keyOrPrefix());
        assertEquals(",", copy.delimiter());
        assertTrue(copy.header());
        assertTrue(copy.gzip());
        assertNull(copy.nullAs());
    }

    @Test
    @DisplayName("Parse COPY FROM S3 with column list, custom delimiter, and NULL AS")
    void testParseCopyWithColumnsAndNullAs() {
        String sql = "COPY sales (id, name) FROM 's3://mybucket/sales/' DELIMITER '|' NULL AS 'NULL_VAL';";
        S3Statement stmt = CopyStatementParser.parse(sql);

        assertNotNull(stmt);
        S3CopyFrom copy = assertInstanceOf(S3CopyFrom.class, stmt);
        assertEquals("sales", copy.targetTable());
        assertEquals(List.of("id", "name"), copy.columns());
        assertEquals("mybucket", copy.bucket());
        assertEquals("sales/", copy.keyOrPrefix());
        assertEquals("|", copy.delimiter());
        assertFalse(copy.header());
        assertFalse(copy.gzip());
        assertEquals("NULL_VAL", copy.nullAs());
    }

    @Test
    @DisplayName("Parse UNLOAD TO S3 with all options: MANIFEST, HEADER, GZIP, CSV, ADDQUOTES")
    void testParseUnloadAllOptions() {
        String sql = "UNLOAD ('SELECT * FROM sales') TO 's3://mybucket/unload/' IAM_ROLE 'arn:aws:iam::123456789012:role/MyRole' MANIFEST HEADER GZIP CSV ADDQUOTES;";
        S3Statement stmt = CopyStatementParser.parse(sql);

        assertNotNull(stmt);
        S3Unload unload = assertInstanceOf(S3Unload.class, stmt);
        assertEquals("SELECT * FROM sales", unload.selectQuery());
        assertEquals("mybucket", unload.bucket());
        assertEquals("unload/", unload.prefix());
        assertEquals(",", unload.delimiter());
        assertTrue(unload.header());
        assertTrue(unload.gzip());
        assertTrue(unload.csv());
        assertTrue(unload.addQuotes());
        assertTrue(unload.manifest());
        assertNull(unload.nullAs());
    }

    @Test
    @DisplayName("Parse UNLOAD TO S3 with custom delimiter, NULL AS, and complex query")
    void testParseUnloadCustomDelimiterAndNullAs() {
        String sql = "UNLOAD ('SELECT id, name FROM users WHERE active = 1') TO 's3://export-bucket/users/part_' DELIMITER '|' NULL AS 'N/A';";
        S3Statement stmt = CopyStatementParser.parse(sql);

        assertNotNull(stmt);
        S3Unload unload = assertInstanceOf(S3Unload.class, stmt);
        assertEquals("SELECT id, name FROM users WHERE active = 1", unload.selectQuery());
        assertEquals("export-bucket", unload.bucket());
        assertEquals("users/part_", unload.prefix());
        assertEquals("|", unload.delimiter());
        assertFalse(unload.header());
        assertFalse(unload.gzip());
        assertFalse(unload.csv());
        assertFalse(unload.addQuotes());
        assertFalse(unload.manifest());
        assertEquals("N/A", unload.nullAs());
    }

    @Test
    @DisplayName("Parse COPY with ignored keywords (fail-open)")
    void testParseCopyIgnoredKeywords() {
        String sql = """
            COPY public.orders (order_id, customer_id, amount)
            FROM 's3://data-lake/orders/2026/08/'
            CREDENTIALS 'aws_access_key_id=xxx;aws_secret_access_key=yyy'
            REGION 'us-east-1'
            MAXERROR 100
            COMPUPDATE ON
            STATUPDATE ON
            ACCEPTINVCHARS '?'
            TIMEFORMAT 'auto'
            DATEFORMAT 'auto'
            TRUNCATECOLUMNS
            BLANKSASNULL
            EMPTYASNULL
            TRIMBLANKS
            DELIMITER '\t'
            IGNOREHEADER 2
            GZIP;
            """;
        S3Statement stmt = CopyStatementParser.parse(sql);

        assertNotNull(stmt);
        S3CopyFrom copy = assertInstanceOf(S3CopyFrom.class, stmt);
        assertEquals("public.orders", copy.targetTable());
        assertEquals(List.of("order_id", "customer_id", "amount"), copy.columns());
        assertEquals("data-lake", copy.bucket());
        assertEquals("orders/2026/08/", copy.keyOrPrefix());
        assertEquals("\t", copy.delimiter());
        assertTrue(copy.header());
        assertTrue(copy.gzip());
    }

    @Test
    @DisplayName("Parse UNLOAD with ignored keywords (fail-open)")
    void testParseUnloadIgnoredKeywords() {
        String sql = """
            UNLOAD ('SELECT order_id, amount FROM orders')
            TO 's3://data-lake/unloaded/orders_'
            CREDENTIALS 'aws_access_key_id=xxx;aws_secret_access_key=yyy'
            PARALLEL OFF
            ALLOWOVERWRITE
            MAXFILESIZE 100 MB
            REGION 'us-east-1'
            ENCRYPTED
            CSV
            HEADER;
            """;
        S3Statement stmt = CopyStatementParser.parse(sql);

        assertNotNull(stmt);
        S3Unload unload = assertInstanceOf(S3Unload.class, stmt);
        assertEquals("SELECT order_id, amount FROM orders", unload.selectQuery());
        assertEquals("data-lake", unload.bucket());
        assertEquals("unloaded/orders_", unload.prefix());
        assertEquals(",", unload.delimiter());
        assertTrue(unload.header());
        assertTrue(unload.csv());
        assertFalse(unload.gzip());
        assertFalse(unload.manifest());
    }

    @Test
    @DisplayName("Parse S3 URI with bucket only without key/prefix")
    void testParseS3UriBucketOnly() {
        String sql1 = "COPY test FROM 's3://mybucket' DELIMITER ',';";
        S3Statement stmt1 = CopyStatementParser.parse(sql1);
        assertNotNull(stmt1);
        S3CopyFrom copy1 = assertInstanceOf(S3CopyFrom.class, stmt1);
        assertEquals("mybucket", copy1.bucket());
        assertEquals("", copy1.keyOrPrefix());

        String sql2 = "COPY test FROM 's3://mybucket/' DELIMITER ',';";
        S3Statement stmt2 = CopyStatementParser.parse(sql2);
        assertNotNull(stmt2);
        S3CopyFrom copy2 = assertInstanceOf(S3CopyFrom.class, stmt2);
        assertEquals("mybucket", copy2.bucket());
        assertEquals("", copy2.keyOrPrefix());
    }

    @Test
    @DisplayName("Case insensitivity of SQL keywords")
    void testCaseInsensitivity() {
        String sql = "copy Sales from 's3://MyBucket/Data.csv' delimiter ',' ignoreheader 1 gzip;";
        S3Statement stmt = CopyStatementParser.parse(sql);

        assertNotNull(stmt);
        S3CopyFrom copy = assertInstanceOf(S3CopyFrom.class, stmt);
        assertEquals("Sales", copy.targetTable());
        assertEquals("MyBucket", copy.bucket());
        assertEquals("Data.csv", copy.keyOrPrefix());
        assertEquals(",", copy.delimiter());
        assertTrue(copy.header());
        assertTrue(copy.gzip());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT 1;",
        "SELECT * FROM sales;",
        "COPY t FROM STDIN;",
        "COPY t TO STDOUT;",
        "COPY t FROM '/tmp/file.csv';",
        "UNLOAD ('SELECT 1') TO '/tmp/file.csv';",
        "INSERT INTO sales (id) VALUES (1);",
        "UPDATE sales SET id = 1;",
        "DELETE FROM sales WHERE id = 1;",
        "CREATE TABLE sales (id int);",
        "DROP TABLE sales;",
        "",
        "   ",
        "-- comment only"
    })
    @DisplayName("Non-S3 queries must return null")
    void testNonS3QueriesReturnNull(String sql) {
        assertNull(CopyStatementParser.parse(sql));
    }

    @Test
    @DisplayName("Null query returns null")
    void testNullReturnsNull() {
        assertNull(CopyStatementParser.parse(null));
    }
}
