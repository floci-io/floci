package io.floci.aws.services.firehose;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedshiftJdbcUrlTest {

    @Test
    void provisionedUrlYieldsClusterIdentifierAndDatabase() {
        RedshiftJdbcUrl url = RedshiftJdbcUrl.parse(
                "jdbc:redshift://my-cluster.abc123.us-east-1.redshift.amazonaws.com:5439/analytics");

        assertEquals("my-cluster", url.clusterIdentifier());
        assertEquals("analytics", url.database());
        assertFalse(url.serverless());
    }

    @Test
    void serverlessUrlIsFlagged() {
        RedshiftJdbcUrl url = RedshiftJdbcUrl.parse(
                "jdbc:postgresql://wg.123456789012.us-east-1.redshift-serverless.amazonaws.com:5439/dev");

        assertEquals("wg", url.clusterIdentifier());
        assertTrue(url.serverless());
    }

    @Test
    void hostWithoutRegionSegmentStillYieldsFirstLabel() {
        RedshiftJdbcUrl url = RedshiftJdbcUrl.parse("jdbc:redshift://c1.redshift.localhost:5439/dev");

        assertEquals("c1", url.clusterIdentifier());
    }

    @Test
    void garbageIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> RedshiftJdbcUrl.parse("not a url"));
    }
}
