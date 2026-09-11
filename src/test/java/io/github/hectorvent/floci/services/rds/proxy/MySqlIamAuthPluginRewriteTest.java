package io.github.hectorvent.floci.services.rds.proxy;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MySqlIamAuthPluginRewriteTest {

    private static final String HASH = "*0000000000000000000000000000000000000000";

    private static String rewrite(String sql) {
        byte[] rewritten = MySqlProtocolHandler.rewriteIamAuthPlugin(
                sql.getBytes(StandardCharsets.ISO_8859_1));
        return rewritten == null ? null : new String(rewritten, StandardCharsets.ISO_8859_1);
    }

    @Test
    void rewritesTheClauseTheTerraformProviderEmits() {
        assertEquals("CREATE USER 'app'@'%' IDENTIFIED WITH mysql_native_password AS '" + HASH + "'",
                rewrite("CREATE USER 'app'@'%' IDENTIFIED WITH AWSAuthenticationPlugin as 'RDS'"));
    }

    @Test
    void rewritesBackquotedUppercasedAndAlterForms() {
        assertEquals("ALTER USER 'app'@'%' IDENTIFIED WITH mysql_native_password AS '" + HASH + "'",
                rewrite("ALTER USER 'app'@'%' IDENTIFIED WITH `AWSAuthenticationPlugin` AS 'RDS'"));
        assertEquals("CREATE USER a IDENTIFIED WITH mysql_native_password AS '" + HASH + "'",
                rewrite("CREATE USER a identified   with AWSAUTHENTICATIONPLUGIN"));
        assertEquals("CREATE USER a IDENTIFIED WITH mysql_native_password AS '" + HASH + "'",
                rewrite("CREATE USER a IDENTIFIED WITH AWSAuthenticationPlugin AS 0xDEADBEEF"));
    }

    @Test
    void rewritesEveryOccurrenceInOneStatement() {
        assertEquals("CREATE USER a IDENTIFIED WITH mysql_native_password AS '" + HASH + "', b IDENTIFIED WITH mysql_native_password AS '" + HASH + "'",
                rewrite("CREATE USER a IDENTIFIED WITH AWSAuthenticationPlugin AS 'RDS',"
                        + " b IDENTIFIED WITH AWSAuthenticationPlugin AS 'RDS'"));
    }

    @Test
    void leavesEveryOtherStatementUntouched() {
        assertNull(rewrite("SELECT 1"));
        assertNull(rewrite("CREATE USER 'app'@'%' IDENTIFIED BY 'secret'"));
        assertNull(rewrite("CREATE USER 'app'@'%' IDENTIFIED WITH caching_sha2_password BY 'x'"));
    }

    @Test
    void findsTheClauseBehindQueryAttributeMetadata() {
        byte[] payload = new byte[]{0x03, 0x00, 0x01};
        byte[] sql = "CREATE USER a IDENTIFIED WITH AWSAuthenticationPlugin as 'RDS'"
                .getBytes(StandardCharsets.ISO_8859_1);
        byte[] packet = new byte[payload.length + sql.length];
        System.arraycopy(payload, 0, packet, 0, payload.length);
        System.arraycopy(sql, 0, packet, payload.length, sql.length);

        byte[] rewritten = MySqlProtocolHandler.rewriteIamAuthPlugin(packet);

        assertEquals(0x03, rewritten[0]);
        assertEquals(0x00, rewritten[1]);
        assertEquals(0x01, rewritten[2]);
        assertEquals("CREATE USER a IDENTIFIED WITH mysql_native_password AS '" + HASH + "'",
                new String(rewritten, 3, rewritten.length - 3, StandardCharsets.ISO_8859_1));
    }
}
