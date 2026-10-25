package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RedshiftAuditLogPublisherTest {

    @Test
    void createsAwsNamedResourcesAndWritesSelectedExport() {
        CloudWatchLogsService logs = mock(CloudWatchLogsService.class);
        RedshiftAuditLogPublisher publisher = new RedshiftAuditLogPublisher(logs,
                Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC));
        Cluster cluster = enabledCluster(List.of("useractivitylog"));
        cluster.setUserActivityLoggingEnabled(true);

        publisher.publishActivity(cluster, "111111111111", "us-east-1", "dev", "admin", "select 1,2");

        verify(logs).createLogGroupForAccount("111111111111",
                "/aws/redshift/cluster/analytics/useractivitylog", null, Map.of(), "us-east-1");
        verify(logs).createLogStreamForAccount("111111111111",
                "/aws/redshift/cluster/analytics/useractivitylog", "analytics", "us-east-1");
        ArgumentCaptor<List<Map<String, Object>>> events = ArgumentCaptor.forClass(List.class);
        verify(logs).putLogEventsForAccount(eq("111111111111"),
                eq("/aws/redshift/cluster/analytics/useractivitylog"), eq("analytics"),
                events.capture(), eq("us-east-1"));
        assertEquals(1234L, events.getValue().get(0).get("timestamp"));
        assertEquals("1970-01-01T00:00:01.234Z,dev,admin,,,,\"LOG: select 1,2\"",
                events.getValue().get(0).get("message"));
    }

    @Test
    void skipsUnselectedExportsAndNonCloudWatchDestinations() {
        CloudWatchLogsService logs = mock(CloudWatchLogsService.class);
        RedshiftAuditLogPublisher publisher = new RedshiftAuditLogPublisher(logs, Clock.systemUTC());
        Cluster cluster = enabledCluster(List.of("connectionlog"));

        publisher.publish(cluster, "111111111111", "us-east-1", "userlog", Map.of("action", "Create"));
        cluster.setLoggingDestinationType("s3");
        publisher.publish(cluster, "111111111111", "us-east-1", "connectionlog", Map.of("event", "connect"));

        verify(logs, never()).putLogEventsForAccount(anyString(), anyString(), anyString(), anyList(), anyString());
    }

    @Test
    void userActivityExportRequiresTheParameterGroupFlag() {
        CloudWatchLogsService logs = mock(CloudWatchLogsService.class);
        RedshiftAuditLogPublisher publisher = new RedshiftAuditLogPublisher(logs, Clock.systemUTC());
        Cluster cluster = enabledCluster(List.of("useractivitylog"));

        publisher.publishActivity(cluster, "111111111111", "us-east-1", "dev", "admin", "SELECT 1");

        verify(logs, never()).putLogEventsForAccount(anyString(), anyString(), anyString(), anyList(), anyString());
    }

    @Test
    void registeredConnectionsUseTheSharedCsvColumnLayout() {
        CloudWatchLogsService logs = mock(CloudWatchLogsService.class);
        RedshiftAuditLogPublisher publisher = new RedshiftAuditLogPublisher(logs,
                Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC));
        publisher.register("111111111111", "us-west-2", enabledCluster(List.of("connectionlog")));

        publisher.publishRegisteredConnection("111111111111", "analytics", "reports", "analyst", "authenticated",
                "192.0.2.1", 54321);

        ArgumentCaptor<List<Map<String, Object>>> events = ArgumentCaptor.forClass(List.class);
        verify(logs).putLogEventsForAccount(eq("111111111111"),
                eq("/aws/redshift/cluster/analytics/connectionlog"), eq("analytics"), events.capture(), eq("us-west-2"));
        String[] columns = events.getValue().getFirst().get("message").toString().split(",", -1);
        assertEquals(22, columns.length);
        assertEquals("authenticated", columns[0]);
        assertEquals("1970-01-01T00:00:01.234Z", columns[1]);
        assertEquals("192.0.2.1", columns[2]);
        assertEquals("54321", columns[3]);
        assertEquals("reports", columns[5]);
        assertEquals("analyst", columns[6]);
        assertEquals("password", columns[7]);
    }

    @Test
    void cloudWatchFailureDoesNotEscapePublisher() {
        CloudWatchLogsService logs = mock(CloudWatchLogsService.class);
        doThrow(new IllegalStateException("logs unavailable"))
                .when(logs).createLogGroupForAccount(anyString(), anyString(), isNull(), anyMap(), anyString());
        RedshiftAuditLogPublisher publisher = new RedshiftAuditLogPublisher(logs, Clock.systemUTC());

        assertDoesNotThrow(() -> publisher.publish(enabledCluster(List.of("userlog")),
                "111111111111", "us-east-1", "userlog", Map.of("action", "Create")));
    }

    @Test
    void redactsSqlPasswordLiteralsFromActivityRecords() {
        assertEquals("ALTER USER alice PASSWORD '[REDACTED]'", RedshiftAuditLogPublisher.sanitizeSql(
                "ALTER USER alice PASSWORD 'top-secret'"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ALTER USER alice PASSWORD /* rotation */ 'secret'",
            "ALTER USER alice PASSWORD -- rotation\n 'secret'",
            "ALTER USER alice PASSWORD /* outer /* nested */ comment */ 'secret'",
            "ALTER USER alice PASSWORD E'secret\\'suffix'",
            "ALTER USER alice PASSWORD $tag$secret$tag$",
            "ALTER USER alice PASSWORD 'first'\n 'secret'",
            "ALTER USER alice PASSWORD U&'secret'",
            "ALTER USER alice PASSWORD N'secret'"
    })
    void redactsCompletePasswordsAfterSqlComments(String sql) {
        String sanitized = RedshiftAuditLogPublisher.sanitizeSql(sql);
        assertFalse(sanitized.contains("secret"), sanitized);
        assertFalse(sanitized.contains("suffix"), sanitized);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ALTER USER alice PASSWORD 'prefix\\'suffix'",
            "SET standard_conforming_strings=off; ALTER USER alice PASSWORD 'prefix\\'suffix'",
            "ALTER USER alice PASSWORD N'prefix\\'suffix'",
            "ALTER USER alice PASSWORD 'first'\n 'prefix\\'suffix'",
            "ALTER USER alice PASSWORD 'prefix\\'suffix'; ALTER USER bob PASSWORD 'anothersecret'",
            "SELECT 'path\\'tail'; ALTER USER alice PASSWORD 'anothersecret'",
            "SELECT 'path\\'; ALTER USER alice PASSWORD 'anothersecret'"
    })
    void redactsPasswordsWithUncertainBackslashBoundaries(String sql) {
        String sanitized = RedshiftAuditLogPublisher.sanitizeSql(sql);
        assertFalse(sanitized.contains("prefix"), sanitized);
        assertFalse(sanitized.contains("suffix"), sanitized);
        assertFalse(sanitized.contains("first"), sanitized);
        assertFalse(sanitized.contains("anothersecret"), sanitized);
    }

    @Test
    void explicitEscapeStringsRetainFollowingSqlAndOtherBackslashStringsRemainVisible() {
        assertEquals("ALTER USER alice PASSWORD '[REDACTED]'; SELECT 1", RedshiftAuditLogPublisher.sanitizeSql(
                "ALTER USER alice PASSWORD E'prefix\\'suffix'; SELECT 1"));
        String sql = "SELECT 'C:\\reports\\daily'";
        assertEquals(sql, RedshiftAuditLogPublisher.sanitizeSql(sql));
    }

    private static Cluster enabledCluster(List<String> exports) {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("analytics");
        cluster.setLoggingEnabled(true);
        cluster.setLoggingDestinationType("cloudwatch");
        cluster.setLoggingExports(exports);
        return cluster;
    }
}
