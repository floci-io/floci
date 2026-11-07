package io.floci.aws.services.redshift;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.ServiceCallAuthorizer;
import io.floci.aws.services.cloudwatch.logs.CloudWatchLogsService;
import io.floci.aws.services.iam.IamPolicyEvaluator.Decision;
import io.floci.aws.services.redshift.model.Cluster;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void redactsCopyCredentialClausesFromActivityRecords() {
        assertEquals("COPY t FROM 's3://b/k' ACCESS_KEY_ID 'AKIAEXAMPLE' SECRET_ACCESS_KEY '[REDACTED]' "
                        + "SESSION_TOKEN '[REDACTED]'",
                RedshiftAuditLogPublisher.sanitizeSql("COPY t FROM 's3://b/k' ACCESS_KEY_ID 'AKIAEXAMPLE' "
                        + "SECRET_ACCESS_KEY 'top-secret' SESSION_TOKEN 'tok'"));
        assertEquals("COPY t FROM 's3://b/k' ACCESS_KEY_ID AS 'AKIAEXAMPLE' SECRET_ACCESS_KEY AS '[REDACTED]' "
                        + "SESSION_TOKEN AS '[REDACTED]'",
                RedshiftAuditLogPublisher.sanitizeSql("COPY t FROM 's3://b/k' ACCESS_KEY_ID AS 'AKIAEXAMPLE' "
                        + "SECRET_ACCESS_KEY AS 'top-secret' SESSION_TOKEN AS 'tok'"));
        assertEquals("COPY t FROM 's3://b/k' CREDENTIALS '[REDACTED]' CSV",
                RedshiftAuditLogPublisher.sanitizeSql("COPY t FROM 's3://b/k' "
                        + "CREDENTIALS 'aws_access_key_id=AKIA;aws_secret_access_key=top-secret' CSV"));
        assertEquals("COPY t FROM 's3://b/k' CREDENTIALS AS '[REDACTED]' CSV",
                RedshiftAuditLogPublisher.sanitizeSql("COPY t FROM 's3://b/k' "
                        + "CREDENTIALS AS 'aws_access_key_id=AKIA;aws_secret_access_key=top-secret' CSV"));
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

    @Test
    void publishesConnectionLogToS3GzipWithPipeDelimiter() throws Exception {
        CloudWatchLogsService logs = mock(CloudWatchLogsService.class);
        S3Service s3 = mock(S3Service.class);
        ServiceCallAuthorizer authorizer = mock(ServiceCallAuthorizer.class);
        when(authorizer.authorize(anyString(), anyString(), anyString(), any(), any())).thenReturn(Decision.ALLOW);
        RegionResolver regionResolver = new RegionResolver("us-east-1", "111111111111");
        Instant now = Instant.parse("2026-11-06T15:30:00Z");
        RedshiftAuditLogPublisher publisher = new RedshiftAuditLogPublisher(logs, s3, authorizer, regionResolver,
                Clock.fixed(now, ZoneOffset.UTC));

        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("analytics");
        cluster.setLoggingEnabled(true);
        cluster.setLoggingDestinationType("s3");
        cluster.setLoggingBucketName("audit-bucket");
        cluster.setLoggingS3KeyPrefix("cluster-logs");
        cluster.setLoggingExports(List.of("connectionlog"));
        publisher.register("111111111111", "us-east-1", cluster);

        AtomicReference<Instant> deliveryTimestamp = new AtomicReference<>();
        AtomicBoolean deliverySuccess = new AtomicBoolean();
        publisher.setDeliveryStatusListener((acc, clId, ts, success, msg) -> {
            deliveryTimestamp.set(ts);
            deliverySuccess.set(success);
        });

        publisher.publishRegisteredConnection("111111111111", "analytics", "dev", "admin", "authenticated",
                "192.0.2.1", 54321);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> dataCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(s3).putObject(eq("audit-bucket"), keyCaptor.capture(), dataCaptor.capture(),
                eq("application/x-gzip"), eq(Map.of()));

        assertEquals("cluster-logs/AWSLogs/111111111111/redshift/us-east-1/2026/11/06/"
                + "111111111111_redshift_us-east-1_analytics_connectionlog_2026-11-06T15:30.gz", keyCaptor.getValue());

        String decompressed = decompressGzip(dataCaptor.getValue());
        String[] lines = decompressed.split("\n");
        assertEquals(1, lines.length);
        String[] columns = lines[0].split("\\|", -1);
        assertEquals(22, columns.length);
        assertEquals("authenticated", columns[0]);
        assertEquals("2026-11-06T15:30:00Z", columns[1]);
        assertEquals("192.0.2.1", columns[2]);
        assertEquals("54321", columns[3]);
        assertEquals("dev", columns[5]);
        assertEquals("admin", columns[6]);
        assertEquals("password", columns[7]);

        assertTrue(deliverySuccess.get());
        assertEquals(now, deliveryTimestamp.get());
    }

    @Test
    void publishesUserLogToS3GzipWithPipeDelimiter() throws Exception {
        CloudWatchLogsService logs = mock(CloudWatchLogsService.class);
        S3Service s3 = mock(S3Service.class);
        ServiceCallAuthorizer authorizer = mock(ServiceCallAuthorizer.class);
        when(authorizer.authorize(anyString(), anyString(), anyString(), any(), any())).thenReturn(Decision.ALLOW);
        RegionResolver regionResolver = new RegionResolver("us-east-1", "111111111111");
        Instant now = Instant.parse("2026-11-06T15:30:00Z");
        RedshiftAuditLogPublisher publisher = new RedshiftAuditLogPublisher(logs, s3, authorizer, regionResolver,
                Clock.fixed(now, ZoneOffset.UTC));

        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("analytics");
        cluster.setLoggingEnabled(true);
        cluster.setLoggingDestinationType("s3");
        cluster.setLoggingBucketName("audit-bucket");
        cluster.setLoggingExports(List.of("userlog"));

        publisher.publish(cluster, "111111111111", "us-east-1", "userlog", Map.of(
                "userid", "101",
                "username", "admin",
                "action", "create user alice",
                "usecreatedb", "true",
                "usesuper", "false",
                "usecatupd", "false",
                "valuntil", "infinity",
                "pid", "1234",
                "recordtime", "2026-11-06T15:30:00Z"
        ));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> dataCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(s3).putObject(eq("audit-bucket"), keyCaptor.capture(), dataCaptor.capture(),
                eq("application/x-gzip"), eq(Map.of()));

        assertEquals("AWSLogs/111111111111/redshift/us-east-1/2026/11/06/"
                + "111111111111_redshift_us-east-1_analytics_userlog_2026-11-06T15:30.gz", keyCaptor.getValue());

        String decompressed = decompressGzip(dataCaptor.getValue());
        String[] columns = decompressed.strip().split("\\|", -1);
        assertEquals(11, columns.length);
        assertEquals("101", columns[0]);
        assertEquals("admin", columns[1]);
        assertEquals("", columns[2]);
        assertEquals("create user alice", columns[3]);
        assertEquals("true", columns[4]);
        assertEquals("false", columns[5]);
        assertEquals("false", columns[6]);
        assertEquals("infinity", columns[7]);
        assertEquals("1234", columns[8]);
        assertEquals("", columns[9]);
        assertEquals("2026-11-06T15:30:00Z", columns[10]);
    }

    @Test
    void publishesUserActivityLogToS3GzipWithStandardFormatAndSanitizesSql() throws Exception {
        CloudWatchLogsService logs = mock(CloudWatchLogsService.class);
        S3Service s3 = mock(S3Service.class);
        ServiceCallAuthorizer authorizer = mock(ServiceCallAuthorizer.class);
        when(authorizer.authorize(anyString(), anyString(), anyString(), any(), any())).thenReturn(Decision.ALLOW);
        RegionResolver regionResolver = new RegionResolver("us-east-1", "111111111111");
        Instant now = Instant.parse("2026-11-06T15:30:00Z");
        RedshiftAuditLogPublisher publisher = new RedshiftAuditLogPublisher(logs, s3, authorizer, regionResolver,
                Clock.fixed(now, ZoneOffset.UTC));

        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("analytics");
        cluster.setLoggingEnabled(true);
        cluster.setLoggingDestinationType("s3");
        cluster.setLoggingBucketName("audit-bucket");
        cluster.setLoggingExports(List.of("useractivitylog"));
        cluster.setUserActivityLoggingEnabled(true);

        publisher.publishActivity(cluster, "111111111111", "us-east-1", "dev", "admin",
                "ALTER USER alice PASSWORD 'secret123'");

        ArgumentCaptor<byte[]> dataCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(s3).putObject(eq("audit-bucket"), anyString(), dataCaptor.capture(),
                eq("application/x-gzip"), eq(Map.of()));

        String decompressed = decompressGzip(dataCaptor.getValue());
        assertEquals("'2026-11-06T15:30:00Z UTC [ db=dev user=admin pid= userid= xid= ]' "
                + "LOG: ALTER USER alice PASSWORD '[REDACTED]'\n", decompressed);
    }

    @Test
    void appendsToExistingS3GzipObject() throws Exception {
        CloudWatchLogsService logs = mock(CloudWatchLogsService.class);
        S3Service s3 = mock(S3Service.class);
        ServiceCallAuthorizer authorizer = mock(ServiceCallAuthorizer.class);
        when(authorizer.authorize(anyString(), anyString(), anyString(), any(), any())).thenReturn(Decision.ALLOW);
        RegionResolver regionResolver = new RegionResolver("us-east-1", "111111111111");
        Instant now = Instant.parse("2026-11-06T15:30:00Z");
        RedshiftAuditLogPublisher publisher = new RedshiftAuditLogPublisher(logs, s3, authorizer, regionResolver,
                Clock.fixed(now, ZoneOffset.UTC));

        String key = "AWSLogs/111111111111/redshift/us-east-1/2026/11/06/"
                + "111111111111_redshift_us-east-1_analytics_useractivitylog_2026-11-06T15:30.gz";
        byte[] existingGzip = compressGzip("'2026-11-06T15:30:00Z UTC [ db=dev user=admin pid= userid= xid= ]' LOG: SELECT 1\n");
        S3Object existingObj = new S3Object("audit-bucket", key, existingGzip, "application/x-gzip");
        when(s3.getObject("audit-bucket", key)).thenReturn(existingObj);

        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("analytics");
        cluster.setLoggingEnabled(true);
        cluster.setLoggingDestinationType("s3");
        cluster.setLoggingBucketName("audit-bucket");
        cluster.setLoggingExports(List.of("useractivitylog"));
        cluster.setUserActivityLoggingEnabled(true);

        publisher.publishActivity(cluster, "111111111111", "us-east-1", "dev", "admin", "SELECT 2");

        ArgumentCaptor<byte[]> dataCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(s3).putObject(eq("audit-bucket"), eq(key), dataCaptor.capture(),
                eq("application/x-gzip"), eq(Map.of()));

        String decompressed = decompressGzip(dataCaptor.getValue());
        assertEquals("'2026-11-06T15:30:00Z UTC [ db=dev user=admin pid= userid= xid= ]' LOG: SELECT 1\n"
                + "'2026-11-06T15:30:00Z UTC [ db=dev user=admin pid= userid= xid= ]' LOG: SELECT 2\n", decompressed);
    }

    @Test
    void s3AuthorizationDenialRecordsFailureAndDoesNotThrow() {
        CloudWatchLogsService logs = mock(CloudWatchLogsService.class);
        S3Service s3 = mock(S3Service.class);
        ServiceCallAuthorizer authorizer = mock(ServiceCallAuthorizer.class);
        when(authorizer.authorize(anyString(), anyString(), anyString(), any(), any())).thenReturn(Decision.DENY);
        RegionResolver regionResolver = new RegionResolver("us-east-1", "111111111111");
        RedshiftAuditLogPublisher publisher = new RedshiftAuditLogPublisher(logs, s3, authorizer, regionResolver,
                Clock.systemUTC());

        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("analytics");
        cluster.setLoggingEnabled(true);
        cluster.setLoggingDestinationType("s3");
        cluster.setLoggingBucketName("audit-bucket");
        cluster.setLoggingExports(List.of("connectionlog"));
        publisher.register("111111111111", "us-east-1", cluster);

        AtomicReference<String> failureMsg = new AtomicReference<>();
        AtomicBoolean deliverySuccess = new AtomicBoolean(true);
        publisher.setDeliveryStatusListener((acc, clId, ts, success, msg) -> {
            deliverySuccess.set(success);
            failureMsg.set(msg);
        });

        assertDoesNotThrow(() -> publisher.publishRegisteredConnection("111111111111", "analytics", "dev", "admin",
                "authenticated", "192.0.2.1", 54321));

        verify(s3, never()).putObject(anyString(), anyString(), any(), anyString(), anyMap());
        assertFalse(deliverySuccess.get());
        assertTrue(failureMsg.get().contains("Access Denied"));
    }

    @Test
    void s3PutObjectFailureRecordsFailureAndDoesNotThrow() {
        CloudWatchLogsService logs = mock(CloudWatchLogsService.class);
        S3Service s3 = mock(S3Service.class);
        doThrow(new RuntimeException("S3 connection timeout")).when(s3)
                .putObject(anyString(), anyString(), any(), anyString(), anyMap());
        ServiceCallAuthorizer authorizer = mock(ServiceCallAuthorizer.class);
        when(authorizer.authorize(anyString(), anyString(), anyString(), any(), any())).thenReturn(Decision.ALLOW);
        RegionResolver regionResolver = new RegionResolver("us-east-1", "111111111111");
        RedshiftAuditLogPublisher publisher = new RedshiftAuditLogPublisher(logs, s3, authorizer, regionResolver,
                Clock.systemUTC());

        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("analytics");
        cluster.setLoggingEnabled(true);
        cluster.setLoggingDestinationType("s3");
        cluster.setLoggingBucketName("audit-bucket");
        cluster.setLoggingExports(List.of("connectionlog"));
        publisher.register("111111111111", "us-east-1", cluster);

        AtomicReference<String> failureMsg = new AtomicReference<>();
        AtomicBoolean deliverySuccess = new AtomicBoolean(true);
        publisher.setDeliveryStatusListener((acc, clId, ts, success, msg) -> {
            deliverySuccess.set(success);
            failureMsg.set(msg);
        });

        assertDoesNotThrow(() -> publisher.publishRegisteredConnection("111111111111", "analytics", "dev", "admin",
                "authenticated", "192.0.2.1", 54321));

        assertFalse(deliverySuccess.get());
        assertTrue(failureMsg.get().contains("S3 connection timeout"));
    }

    private static String decompressGzip(byte[] gzipped) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gzipped));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private static byte[] compressGzip(String text) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
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
