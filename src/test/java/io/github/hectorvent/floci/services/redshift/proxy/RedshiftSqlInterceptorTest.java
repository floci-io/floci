package io.github.hectorvent.floci.services.redshift.proxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedshiftSqlInterceptorTest {

    @Test
    void shouldReturnNullAndEmptyAsIs() {
        assertNull(RedshiftSqlInterceptor.rewrite(null));
        assertEquals("", RedshiftSqlInterceptor.rewrite(""));
    }

    @Test
    void shouldReturnSameInstanceWhenNoMatchOccursFastPath() {
        String sql = "SELECT * FROM my_table WHERE id = 1";
        String rewritten = RedshiftSqlInterceptor.rewrite(sql);
        assertSame(sql, rewritten, "Fast-path must return the identical string reference");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ALL", "EVEN", "KEY", "AUTO", "all", "even", "key", "auto"})
    void shouldRemoveDiststyle(String style) {
        String sql = "CREATE TABLE users (id INT, name VARCHAR(50)) DISTSTYLE " + style + ";";
        String rewritten = RedshiftSqlInterceptor.rewrite(sql);
        assertTrue(!rewritten.contains("DISTSTYLE") && !rewritten.contains("diststyle"),
                "Rewritten SQL should not contain DISTSTYLE: " + rewritten);
    }

    @Test
    void shouldRemoveDistkeyParenAndIdent() {
        String sql1 = "CREATE TABLE t (id INT) DISTKEY (id);";
        String rewritten1 = RedshiftSqlInterceptor.rewrite(sql1);
        assertTrue(!rewritten1.contains("DISTKEY"), "Should remove DISTKEY (col): " + rewritten1);

        String sql2 = "CREATE TABLE t (id INT) DISTKEY id;";
        String rewritten2 = RedshiftSqlInterceptor.rewrite(sql2);
        assertTrue(!rewritten2.contains("DISTKEY"), "Should remove DISTKEY col: " + rewritten2);

        String sql3 = "CREATE TABLE t (id INT) DISTKEY \"id\";";
        String rewritten3 = RedshiftSqlInterceptor.rewrite(sql3);
        assertTrue(!rewritten3.contains("DISTKEY"), "Should remove quoted DISTKEY \"col\": " + rewritten3);

        String sql4 = "CREATE TABLE t (id INT DISTKEY, name VARCHAR(50));";
        String rewritten4 = RedshiftSqlInterceptor.rewrite(sql4);
        assertTrue(!rewritten4.contains("DISTKEY"), "Should remove column-level DISTKEY: " + rewritten4);

        String sql5 = "CREATE TABLE t (id INT DISTKEY NOT NULL, name VARCHAR(50));";
        String rewritten5 = RedshiftSqlInterceptor.rewrite(sql5);
        assertTrue(!rewritten5.contains("DISTKEY"), "Should remove column-level DISTKEY followed by NOT NULL: " + rewritten5);
        assertTrue(rewritten5.contains("id INT NOT NULL"), "Should preserve 'id INT NOT NULL': " + rewritten5);

        String sql6 = "CREATE TABLE t (id INT DISTKEY PRIMARY KEY, code VARCHAR(10) DISTKEY UNIQUE);";
        String rewritten6 = RedshiftSqlInterceptor.rewrite(sql6);
        assertTrue(!rewritten6.contains("DISTKEY"), "Should remove column-level DISTKEY before PRIMARY KEY/UNIQUE: " + rewritten6);
        assertTrue(rewritten6.contains("id INT PRIMARY KEY"), "Should preserve PRIMARY KEY: " + rewritten6);
        assertTrue(rewritten6.contains("code VARCHAR(10) UNIQUE"), "Should preserve UNIQUE: " + rewritten6);
    }

    @Test
    void shouldRemoveSortkeyVariants() {
        String sql1 = "CREATE TABLE t (id INT, created_at TIMESTAMP) SORTKEY (created_at);";
        String rewritten1 = RedshiftSqlInterceptor.rewrite(sql1);
        assertTrue(!rewritten1.contains("SORTKEY"), "Should remove SORTKEY (col): " + rewritten1);

        String sql2 = "CREATE TABLE t (id INT, c1 INT, c2 INT) COMPOUND SORTKEY (c1, c2);";
        String rewritten2 = RedshiftSqlInterceptor.rewrite(sql2);
        assertTrue(!rewritten2.contains("SORTKEY") && !rewritten2.contains("COMPOUND"),
                "Should remove COMPOUND SORTKEY (c1, c2): " + rewritten2);

        String sql3 = "CREATE TABLE t (id INT, c1 INT, c2 INT) INTERLEAVED SORTKEY (c1, c2);";
        String rewritten3 = RedshiftSqlInterceptor.rewrite(sql3);
        assertTrue(!rewritten3.contains("SORTKEY") && !rewritten3.contains("INTERLEAVED"),
                "Should remove INTERLEAVED SORTKEY (c1, c2): " + rewritten3);

        String sql4 = "CREATE TABLE t (id INT) SORTKEY id;";
        String rewritten4 = RedshiftSqlInterceptor.rewrite(sql4);
        assertTrue(!rewritten4.contains("SORTKEY"), "Should remove SORTKEY col: " + rewritten4);

        String sql5 = "CREATE TABLE t (id INT SORTKEY, name VARCHAR(50));";
        String rewritten5 = RedshiftSqlInterceptor.rewrite(sql5);
        assertTrue(!rewritten5.contains("SORTKEY"), "Should remove column-level SORTKEY: " + rewritten5);

        String sql6 = "CREATE TABLE t (id INT SORTKEY NOT NULL, name VARCHAR(50));";
        String rewritten6 = RedshiftSqlInterceptor.rewrite(sql6);
        assertTrue(!rewritten6.contains("SORTKEY"), "Should remove column-level SORTKEY followed by NOT NULL: " + rewritten6);
        assertTrue(rewritten6.contains("id INT NOT NULL"), "Should preserve 'id INT NOT NULL': " + rewritten6);

        String sql7 = "CREATE TABLE t (id INT DISTKEY SORTKEY NOT NULL, name VARCHAR(50));";
        String rewritten7 = RedshiftSqlInterceptor.rewrite(sql7);
        assertTrue(!rewritten7.contains("DISTKEY") && !rewritten7.contains("SORTKEY"),
                "Should remove both DISTKEY and SORTKEY: " + rewritten7);
        assertTrue(rewritten7.contains("id INT NOT NULL"), "Should preserve 'id INT NOT NULL': " + rewritten7);
    }

    @ParameterizedTest
    @ValueSource(strings = {"zstd", "az64", "RAW", "raw", "lzo", "delta", "mostly8", "text255", "text32k"})
    void shouldRemoveEncode(String codec) {
        String sql = "CREATE TABLE t (id INT ENCODE " + codec + ", name VARCHAR(50) ENCODE " + codec + ");";
        String rewritten = RedshiftSqlInterceptor.rewrite(sql);
        assertTrue(!rewritten.contains("ENCODE") && !rewritten.contains("encode"),
                "Should remove ENCODE " + codec + ": " + rewritten);
    }

    @Test
    void shouldCleanOrphanedAndDoubleCommas() {
        String sqlWithTrailingComma = "CREATE TABLE t (\n  id INT,\n  name VARCHAR(50),\n  DISTKEY (id)\n);";
        String rewritten1 = RedshiftSqlInterceptor.rewrite(sqlWithTrailingComma);
        assertTrue(!rewritten1.contains(",\n)") && !rewritten1.contains(", )") && !rewritten1.contains(",\n  )"),
                "Should remove comma before closing parenthesis: " + rewritten1);

        String sqlWithDoubleComma = "CREATE TABLE t (\n  id INT,\n  DISTKEY (id),\n  name VARCHAR(50)\n);";
        String rewritten2 = RedshiftSqlInterceptor.rewrite(sqlWithDoubleComma);
        assertTrue(!rewritten2.contains(",\n  ,") && !rewritten2.contains(", ,"),
                "Should clean double commas: " + rewritten2);
    }

    @Test
    void shouldBeIdempotent() {
        String complexSql = """
                CREATE TABLE sales (
                    sale_id INT ENCODE az64,
                    cust_id INT ENCODE zstd,
                    sale_date DATE ENCODE raw,
                    amount NUMERIC(10,2) ENCODE az64
                )
                DISTSTYLE KEY
                DISTKEY (cust_id)
                COMPOUND SORTKEY (sale_date, cust_id);
                """;

        String once = RedshiftSqlInterceptor.rewrite(complexSql);
        String twice = RedshiftSqlInterceptor.rewrite(once);

        assertEquals(once, twice, "Subsequent rewrite must produce identical result");
    }

    @Test
    void shouldNotTouchNonTableDdlEvenIfItMentionsKeywords() {
        // A query that merely contains the words distkey/sortkey/encode must pass through untouched.
        String select = "SELECT distkey, sortkey FROM catalog WHERE encode = 'az64' ORDER BY distkey";
        assertSame(select, RedshiftSqlInterceptor.rewrite(select));

        String insert = "INSERT INTO audit (action) VALUES ('ran UNLOAD with ENCODE zstd')";
        assertSame(insert, RedshiftSqlInterceptor.rewrite(insert));
    }

    @Test
    void shouldKeepColumnNamedLikeAKeyword() {
        // 'sortkey' / 'distkey' as real column names (followed by a type) survive;
        // only the trailing table-level clause is stripped.
        String sql = "CREATE TABLE t (id INT, sortkey VARCHAR(20), distkey INT) DISTKEY (id) SORTKEY (sortkey);";
        String rewritten = RedshiftSqlInterceptor.rewrite(sql);
        assertTrue(rewritten.contains("sortkey VARCHAR(20)"), "column 'sortkey' must survive: " + rewritten);
        assertTrue(rewritten.contains("distkey INT"), "column 'distkey' must survive: " + rewritten);
        assertTrue(!rewritten.contains("DISTKEY (") && !rewritten.contains("SORTKEY ("),
                "table-level key clauses must be gone: " + rewritten);

        String sqlWithConstraints = "CREATE TABLE t (distkey INT NOT NULL, sortkey VARCHAR(20) NOT NULL);";
        String rewrittenWithConstraints = RedshiftSqlInterceptor.rewrite(sqlWithConstraints);
        assertTrue(rewrittenWithConstraints.contains("distkey INT NOT NULL"), "column 'distkey INT NOT NULL' must survive: " + rewrittenWithConstraints);
        assertTrue(rewrittenWithConstraints.contains("sortkey VARCHAR(20) NOT NULL"), "column 'sortkey VARCHAR(20) NOT NULL' must survive: " + rewrittenWithConstraints);
    }

    @Test
    void shouldNotStripEncodeWhenFollowedByAnOrdinaryType() {
        // A column literally named 'encode' of type varchar is not a Redshift ENCODE clause.
        String sql = "CREATE TABLE t (id INT, encode VARCHAR(50));";
        String rewritten = RedshiftSqlInterceptor.rewrite(sql);
        assertTrue(rewritten.contains("encode VARCHAR(50)"), "column 'encode' must survive: " + rewritten);
    }

    @Test
    void shouldRemoveEncodeAuto() {
        String col = RedshiftSqlInterceptor.rewrite("CREATE TABLE t (a int ENCODE AUTO, b text);");
        assertTrue(!col.contains("ENCODE"), "column-level ENCODE AUTO must be stripped: " + col);
        String table = RedshiftSqlInterceptor.rewrite("CREATE TABLE t (a int) ENCODE AUTO;");
        assertTrue(!table.contains("ENCODE"), "table-level ENCODE AUTO must be stripped: " + table);
    }

    @Test
    void shouldStripDistkeyRegardlessOfColumnAttributeOrder() {
        String sql = "CREATE TABLE t (id INT DISTKEY ENCODE az64, d date);";
        String rewritten = RedshiftSqlInterceptor.rewrite(sql);
        assertTrue(!rewritten.contains("DISTKEY") && !rewritten.contains("ENCODE"),
                "both column attributes must be stripped regardless of order: " + rewritten);
        assertEquals(rewritten, RedshiftSqlInterceptor.rewrite(rewritten), "must be idempotent");
    }

    @Test
    void shouldNotMangleStringLiteralsInsideDdl() {
        String sql = "CREATE TABLE t (a text DEFAULT 'x,)' CHECK (a <> 'SORTKEY'), b int) DISTKEY (b);";
        String rewritten = RedshiftSqlInterceptor.rewrite(sql);
        assertTrue(rewritten.contains("DEFAULT 'x,)'"), "string default must be intact: " + rewritten);
        assertTrue(rewritten.contains("'SORTKEY'"), "string literal must be intact: " + rewritten);
        assertTrue(!rewritten.contains("DISTKEY (b)"), "table-level DISTKEY clause must be gone: " + rewritten);
    }

    @Test
    void shouldNotTouchSecondStatementOfAMultiStatementBatch() {
        // The batch starts with CREATE TABLE, but the INSERT's string literal must survive verbatim.
        String sql = "CREATE TABLE a (x int); INSERT INTO log VALUES ('DISTKEY (y)');";
        assertSame(sql, RedshiftSqlInterceptor.rewrite(sql));
    }

    @Test
    void shouldPreserveNonDdlStatementsInMultiStatementBatch() {
        // Functions or column identifiers named like Redshift keywords in later statements must not be rewritten.
        String sql = "CREATE TABLE t (x int DISTKEY); SELECT distkey(a), encode FROM s;";
        String rewritten = RedshiftSqlInterceptor.rewrite(sql);
        assertEquals("CREATE TABLE t (x int); SELECT distkey(a), encode FROM s;", rewritten);

        String sql2 = "CREATE TABLE t (x int DISTKEY); SELECT a, distkey, b FROM s;";
        String rewritten2 = RedshiftSqlInterceptor.rewrite(sql2);
        assertEquals("CREATE TABLE t (x int); SELECT a, distkey, b FROM s;", rewritten2);
    }

    @Test
    void shouldHandleSemicolonInsideStringLiteralInDdl() {
        String sql = "CREATE TABLE t (x varchar DEFAULT ';', id int DISTKEY);";
        String rewritten = RedshiftSqlInterceptor.rewrite(sql);
        assertEquals("CREATE TABLE t (x varchar DEFAULT ';', id int);", rewritten);
    }

    @Test
    void shouldPreserveDollarQuotedStringsWithDdlKeywordsAndSemicolons() {
        String sql = "CREATE TABLE t (code text DEFAULT $$a; CREATE TABLE fake (id int DISTKEY)$$);";
        String rewritten = RedshiftSqlInterceptor.rewrite(sql);
        assertEquals(sql, rewritten, "Dollar-quoted string literal containing DDL text must not be altered");

        String sqlTagged = "CREATE TABLE t (code text DEFAULT $tag$a; CREATE TABLE fake (id int DISTKEY)$tag$);";
        String rewrittenTagged = RedshiftSqlInterceptor.rewrite(sqlTagged);
        assertEquals(sqlTagged, rewrittenTagged, "Tagged dollar-quoted string must not be altered");

        String sqlCustomTag = "CREATE TABLE t (code text DEFAULT $custom_tag$a; CREATE TABLE fake (id int DISTKEY)$custom_tag$);";
        String rewrittenCustomTag = RedshiftSqlInterceptor.rewrite(sqlCustomTag);
        assertEquals(sqlCustomTag, rewrittenCustomTag, "Custom-tagged dollar-quoted string must not be altered");

        String sqlWithDistkey = "CREATE TABLE t (code text DEFAULT $$a; CREATE TABLE fake (id int DISTKEY)$$, id int DISTKEY);";
        String rewrittenWithDistkey = RedshiftSqlInterceptor.rewrite(sqlWithDistkey);
        assertEquals("CREATE TABLE t (code text DEFAULT $$a; CREATE TABLE fake (id int DISTKEY)$$, id int);", rewrittenWithDistkey);
    }

    @Test
    void shouldHandleComprehensiveComplexDdl() {
        String complexSql = """
                CREATE TABLE public.orders (
                    order_id BIGINT NOT NULL ENCODE az64,
                    customer_id INT NOT NULL ENCODE zstd,
                    order_status VARCHAR(20) ENCODE lzo,
                    order_date TIMESTAMP WITHOUT TIME ZONE NOT NULL ENCODE raw,
                    total_amount NUMERIC(12, 4) ENCODE az64,
                    PRIMARY KEY (order_id)
                )
                DISTSTYLE AUTO
                DISTKEY (customer_id)
                INTERLEAVED SORTKEY (order_date, customer_id);
                """;

        String rewritten = RedshiftSqlInterceptor.rewrite(complexSql);
        assertTrue(!rewritten.contains("ENCODE"), "Should not contain ENCODE: " + rewritten);
        assertTrue(!rewritten.contains("DISTSTYLE"), "Should not contain DISTSTYLE: " + rewritten);
        assertTrue(!rewritten.contains("DISTKEY"), "Should not contain DISTKEY: " + rewritten);
        assertTrue(!rewritten.contains("SORTKEY"), "Should not contain SORTKEY: " + rewritten);
        assertTrue(!rewritten.contains("INTERLEAVED"), "Should not contain INTERLEAVED: " + rewritten);
        assertTrue(!rewritten.contains(",\n)"), "Should not contain trailing comma before parenthesis: " + rewritten);
    }
}
