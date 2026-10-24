package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static Cluster enabledCluster(List<String> exports) {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("analytics");
        cluster.setLoggingEnabled(true);
        cluster.setLoggingDestinationType("cloudwatch");
        cluster.setLoggingExports(exports);
        return cluster;
    }
}
