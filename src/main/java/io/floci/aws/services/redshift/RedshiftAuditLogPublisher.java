package io.floci.aws.services.redshift;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.ServiceCallAuthorizer;
import io.floci.aws.services.cloudwatch.logs.CloudWatchLogsService;
import io.floci.aws.services.iam.IamPolicyEvaluator.Decision;
import io.floci.aws.services.redshift.model.Cluster;
import io.floci.aws.services.s3.S3ResourceArns;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

@ApplicationScoped
public class RedshiftAuditLogPublisher {

    private static final Logger LOG = Logger.getLogger(RedshiftAuditLogPublisher.class);
    private static final Set<String> LOG_TYPES = Set.of("connectionlog", "userlog", "useractivitylog");
    private static final Map<String, List<String>> LOG_COLUMNS = Map.of(
            "connectionlog", List.of("event", "recordtime", "remotehost", "remoteport", "pid", "dbname",
                    "username", "authmethod", "duration", "sslversion", "sslcipher", "mtu", "sslcompression",
                    "sslexpansion", "iamauthguid", "application_name", "os_version", "driver_version",
                    "plugin_name", "protocol_version", "sessionid", "compression"),
            "userlog", List.of("userid", "username", "oldusername", "action", "usecreatedb", "usesuper",
                    "usecatupd", "valuntil", "pid", "xid", "recordtime"),
            "useractivitylog", List.of("recordtime", "db", "user", "pid", "userid", "xid", "query"));

    @FunctionalInterface
    public interface DeliveryStatusListener {
        void recordDeliveryOutcome(String accountId, String clusterIdentifier, long loggingGeneration,
                                   Instant timestamp, boolean success, String failureMessage);
    }

    private static final int STRIPE_COUNT = 64;
    private final CloudWatchLogsService logs;
    private final S3Service s3;
    private final ServiceCallAuthorizer authorizer;
    private final RegionResolver regionResolver;
    private final Clock clock;
    private final Map<String, Registration> registrations = new ConcurrentHashMap<>();
    private final Object[] deliveryStripes = new Object[STRIPE_COUNT];
    private volatile DeliveryStatusListener statusListener;

    {
        for (int i = 0; i < STRIPE_COUNT; i++) {
            deliveryStripes[i] = new Object();
        }
    }

    private record Registration(String region, String identifier, boolean enabled, String destination,
                                String bucketName, String s3KeyPrefix,
                                List<String> exports, boolean activityLoggingEnabled,
                                long loggingGeneration) { }

    @Inject
    public RedshiftAuditLogPublisher(CloudWatchLogsService logs, S3Service s3,
                                     ServiceCallAuthorizer authorizer, RegionResolver regionResolver) {
        this(logs, s3, authorizer, regionResolver, Clock.systemUTC());
    }

    public RedshiftAuditLogPublisher(CloudWatchLogsService logs) {
        this(logs, null, null, null, Clock.systemUTC());
    }

    public RedshiftAuditLogPublisher(CloudWatchLogsService logs, Clock clock) {
        this(logs, null, null, null, clock);
    }

    RedshiftAuditLogPublisher(CloudWatchLogsService logs, S3Service s3,
                              ServiceCallAuthorizer authorizer, RegionResolver regionResolver, Clock clock) {
        this.logs = logs;
        this.s3 = s3;
        this.authorizer = authorizer;
        this.regionResolver = regionResolver;
        this.clock = clock;
    }

    public void setDeliveryStatusListener(DeliveryStatusListener statusListener) {
        this.statusListener = statusListener;
    }

    public void register(String accountId, String region, Cluster cluster) {
        registrations.put(registryKey(accountId, cluster.getClusterIdentifier()), new Registration(region,
                cluster.getClusterIdentifier(), cluster.isLoggingEnabled(), cluster.getLoggingDestinationType(),
                cluster.getLoggingBucketName(), cluster.getLoggingS3KeyPrefix(),
                cluster.getLoggingExports() == null ? null : List.copyOf(cluster.getLoggingExports()),
                cluster.isUserActivityLoggingEnabled(),
                cluster.getLoggingGeneration()));
    }

    public void unregister(String accountId, String clusterIdentifier) {
        registrations.remove(registryKey(accountId, clusterIdentifier));
    }

    public void publishRegistered(String accountId, String clusterIdentifier, String logType,
                                  Map<String, Object> record) {
        try {
            Registration registration = registrations.get(registryKey(accountId, clusterIdentifier));
            if (registration == null) {
                return;
            }
            Cluster cluster = registeredCluster(registration);
            publish(cluster, accountId, registration.region(), logType, record);
        } catch (Exception ex) {
            LOG.warnv(ex, "Failed to publish registered Redshift {0} audit event for cluster {1}",
                    logType, clusterIdentifier);
        }
    }

    public void publishRegisteredActivity(String accountId, String clusterIdentifier, String database,
                                          String username, String sql) {
        try {
            Registration registration = registrations.get(registryKey(accountId, clusterIdentifier));
            if (registration == null) {
                return;
            }
            Cluster cluster = registeredCluster(registration);
            publishActivity(cluster, accountId, registration.region(), database, username, sql);
        } catch (Exception ex) {
            LOG.warnv(ex, "Failed to publish registered Redshift activity audit event for cluster {0}", clusterIdentifier);
        }
    }

    public void publishRegisteredUserChange(String accountId, String clusterIdentifier, String database,
                                            String username, String action, String oldUsername) {
        try {
            Registration registration = registrations.get(registryKey(accountId, clusterIdentifier));
            if (registration == null) {
                return;
            }
            Cluster cluster = registeredCluster(registration);
            publishUserChange(cluster, accountId, registration.region(), database, username, action, oldUsername);
        } catch (Exception ex) {
            LOG.warnv(ex, "Failed to publish registered Redshift user change audit event for cluster {0}", clusterIdentifier);
        }
    }

    private static Cluster registeredCluster(Registration registration) {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier(registration.identifier());
        cluster.setLoggingEnabled(registration.enabled());
        cluster.setLoggingDestinationType(registration.destination());
        cluster.setLoggingBucketName(registration.bucketName());
        cluster.setLoggingS3KeyPrefix(registration.s3KeyPrefix());
        cluster.setLoggingExports(registration.exports());
        cluster.setUserActivityLoggingEnabled(registration.activityLoggingEnabled());
        cluster.setLoggingGeneration(registration.loggingGeneration());
        return cluster;
    }

    private static String registryKey(String accountId, String clusterIdentifier) {
        return accountId + ":" + clusterIdentifier;
    }

    public void publishActivity(Cluster cluster, String accountId, String region, String database, String username,
                                String sql) {
        try {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("recordtime", Instant.ofEpochMilli(clock.millis()).toString());
            record.put("db", database);
            record.put("user", username);
            record.put("query", "LOG: " + sanitizeSql(sql));
            publish(cluster, accountId, region, "useractivitylog", record);
        } catch (Exception ex) {
            LOG.warnv(ex, "Failed to publish Redshift activity audit event for cluster {0}",
                    cluster != null ? cluster.getClusterIdentifier() : "unknown");
        }
    }

    public void publishConnection(Cluster cluster, String accountId, String region, String database, String username,
                                  String event, String remoteAddress, String error) {
        try {
            publish(cluster, accountId, region, "connectionlog",
                    connectionRecord(database, username, event, remoteAddress, null, error));
        } catch (Exception ex) {
            LOG.warnv(ex, "Failed to publish Redshift connection audit event for cluster {0}",
                    cluster != null ? cluster.getClusterIdentifier() : "unknown");
        }
    }

    public void publishRegisteredConnection(String accountId, String clusterIdentifier, String database, String username,
                                            String event, String remoteAddress, int remotePort) {
        try {
            publishRegistered(accountId, clusterIdentifier, "connectionlog",
                    connectionRecord(database, username, event, remoteAddress, remotePort, null));
        } catch (Exception ex) {
            LOG.warnv(ex, "Failed to publish registered Redshift connection audit event for cluster {0}", clusterIdentifier);
        }
    }

    private Map<String, Object> connectionRecord(String database, String username, String event, String remoteAddress,
                                                  Integer remotePort, String error) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("recordtime", Instant.ofEpochMilli(clock.millis()).toString());
        record.put("dbname", database);
        record.put("username", username);
        record.put("event", event);
        record.put("remotehost", remoteAddress);
        record.put("authmethod", "password");
        record.put("remoteport", remotePort);
        record.put("pid", "");
        record.put("duration", "");
        record.put("sslversion", "");
        record.put("sslcipher", "");
        record.put("mtu", "");
        record.put("sslcompression", "");
        record.put("sslexpansion", "");
        record.put("iamauthguid", "");
        record.put("application_name", "");
        record.put("os_version", "");
        record.put("driver_version", "");
        record.put("plugin_name", "");
        record.put("protocol_version", "");
        record.put("sessionid", "");
        record.put("compression", "");
        if (error != null) {
            record.put("event", "authentication failure");
        }
        return record;
    }

    public void publishUserChange(Cluster cluster, String accountId, String region, String database,
                                  String username, String action, String oldUsername) {
        try {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("recordtime", Instant.ofEpochMilli(clock.millis()).toString());
            record.put("userid", "");
            record.put("db", database);
            record.put("username", username);
            record.put("oldusername", oldUsername == null ? "" : oldUsername);
            record.put("action", action);
            record.put("usecreatedb", "");
            record.put("usesuper", "");
            record.put("usecatupd", "");
            record.put("valuntil", "");
            record.put("pid", "");
            record.put("xid", "");
            publish(cluster, accountId, region, "userlog", record);
        } catch (Exception ex) {
            LOG.warnv(ex, "Failed to publish Redshift user change audit event for cluster {0}",
                    cluster != null ? cluster.getClusterIdentifier() : "unknown");
        }
    }

    public void publish(Cluster cluster, String accountId, String region, String logType,
                        Map<String, Object> record) {
        try {
            if (cluster == null || !cluster.isLoggingEnabled()
                    || "useractivitylog".equals(logType) && !cluster.isUserActivityLoggingEnabled()
                    || !LOG_TYPES.contains(logType) || !exports(cluster.getLoggingExports()).contains(logType)) {
                return;
            }

            String destination = cluster.getLoggingDestinationType();
            if ("cloudwatch".equalsIgnoreCase(destination)) {
                publishCloudWatch(cluster, accountId, region, logType, record);
            } else if ("s3".equalsIgnoreCase(destination)
                    || (destination == null && cluster.getLoggingBucketName() != null)) {
                publishS3(cluster, accountId, region, logType, record);
            }
        } catch (Exception ex) {
            LOG.warnv(ex, "Failed to publish Redshift audit event for cluster {0}",
                    cluster != null ? cluster.getClusterIdentifier() : "unknown");
        }
    }

    private void publishCloudWatch(Cluster cluster, String accountId, String region, String logType,
                                   Map<String, Object> record) {
        String group = "/aws/redshift/cluster/" + cluster.getClusterIdentifier() + "/" + logType;
        String stream = cluster.getClusterIdentifier();
        try {
            createIfMissing(accountId, region, group, stream);
            Map<String, Object> event = Map.of(
                    "timestamp", clock.millis(),
                    "message", csvLine(logType, record));
            logs.putLogEventsForAccount(accountId, group, stream, List.of(event), region);
        } catch (RuntimeException exception) {
            LOG.warnv(exception, "Failed to publish Redshift {0} audit event for cluster {1}",
                    logType, cluster.getClusterIdentifier());
        }
    }

    public static String s3DeliveryKey(String s3KeyPrefix, String accountId, String region,
                                       String clusterIdentifier, String logType, Instant timestamp) {
        ZonedDateTime utc = timestamp.atZone(ZoneOffset.UTC);
        String year = String.format("%04d", utc.getYear());
        String month = String.format("%02d", utc.getMonthValue());
        String day = String.format("%02d", utc.getDayOfMonth());
        String timePart = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm").format(utc);
        String filename = accountId + "_redshift_" + region + "_" + clusterIdentifier
                + "_" + logType + "_" + timePart + ".gz";
        String basePath = (s3KeyPrefix != null && !s3KeyPrefix.isBlank())
                ? (s3KeyPrefix.endsWith("/") ? s3KeyPrefix : s3KeyPrefix + "/")
                : "";
        return basePath + "AWSLogs/" + accountId + "/redshift/" + region + "/" + year + "/" + month + "/" + day + "/" + filename;
    }

    private void publishS3(Cluster cluster, String accountId, String region, String logType,
                           Map<String, Object> record) {
        try {
            String bucket = cluster.getLoggingBucketName();
            if (bucket == null || bucket.isBlank() || s3 == null) {
                return;
            }
            Instant now = Instant.ofEpochMilli(clock.millis());
            String key = s3DeliveryKey(cluster.getLoggingS3KeyPrefix(), accountId, region,
                    cluster.getClusterIdentifier(), logType, now);

            String partition = regionResolver != null ? regionResolver.partitionForRegion(region) : AwsRegions.partitionFor(region);
            String objectArn = S3ResourceArns.object(partition, bucket, key);
            String clusterArn = AwsArnUtils.Arn.of("redshift", region, accountId, "cluster:" + cluster.getClusterIdentifier()).toString();
            Map<String, List<String>> requestKeys = Map.of("aws:RequestedRegion", List.of(region));

            if (authorizer != null && authorizer.authorize("redshift", "s3:PutObject", objectArn, clusterArn, accountId, requestKeys) != Decision.ALLOW) {
                String errorMsg = "Access Denied: The bucket policy does not allow Amazon Redshift to put objects.";
                LOG.warnv("Access Denied delivering Redshift audit log to s3://{0}/{1}", bucket, key);
                safeRecordOutcome(accountId, cluster.getClusterIdentifier(), cluster.getLoggingGeneration(), now, false, errorMsg);
                return;
            }

            String line = formatS3Line(logType, record);
            byte[] newMember = compressGzipMember(line);
            int stripeIndex = Math.floorMod((bucket + "/" + key).hashCode(), STRIPE_COUNT);
            Object lock = deliveryStripes[stripeIndex];
            synchronized (lock) {
                try {
                    byte[] gzipped = appendGzipMember(bucket, key, newMember);
                    s3.putObject(bucket, key, gzipped, "application/x-gzip", Map.of());
                    safeRecordOutcome(accountId, cluster.getClusterIdentifier(), cluster.getLoggingGeneration(), now, true, null);
                } catch (AwsException | IOException exception) {
                    LOG.warnv(exception, "Failed to deliver Redshift {0} audit event to s3://{1}/{2} for cluster {3}",
                            logType, bucket, key, cluster.getClusterIdentifier());
                    String message = exception.getMessage() != null ? exception.getMessage() : "Failed to deliver log to S3";
                    safeRecordOutcome(accountId, cluster.getClusterIdentifier(), cluster.getLoggingGeneration(), now, false, message);
                } catch (RuntimeException unexpected) {
                    LOG.errorv(unexpected, "Unexpected runtime error delivering Redshift {0} audit event to s3://{1}/{2} for cluster {3}",
                            logType, bucket, key, cluster.getClusterIdentifier());
                }
            }
        } catch (Exception unexpected) {
            LOG.errorv(unexpected, "Unexpected error delivering Redshift {0} audit event to S3 for cluster {1}",
                    logType, cluster.getClusterIdentifier());
        }
    }

    private void safeRecordOutcome(String accountId, String clusterIdentifier, long loggingGeneration,
                                   Instant timestamp, boolean success, String failureMessage) {
        DeliveryStatusListener listener = this.statusListener;
        if (listener != null) {
            try {
                listener.recordDeliveryOutcome(accountId, clusterIdentifier, loggingGeneration, timestamp, success, failureMessage);
            } catch (Exception ex) {
                LOG.warnv(ex, "Delivery status listener threw an exception for cluster {0}", clusterIdentifier);
            }
        }
    }

    private static byte[] compressGzipMember(String newLine) throws IOException {
        ByteArrayOutputStream lineGz = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(lineGz)) {
            gos.write(newLine.getBytes(StandardCharsets.UTF_8));
            gos.write('\n');
        }
        return lineGz.toByteArray();
    }

    private byte[] appendGzipMember(String bucket, String key, byte[] newMember) throws IOException {
        byte[] existingBytes = null;
        try {
            S3Object existingObj = s3.getObject(bucket, key);
            if (existingObj != null && existingObj.getData() != null && existingObj.getData().length > 0) {
                existingBytes = existingObj.getData();
            }
        } catch (AwsException e) {
            if (!"NoSuchKey".equals(e.getErrorCode())) {
                throw e;
            }
        }

        if (existingBytes == null || existingBytes.length == 0) {
            return newMember;
        }

        byte[] combined = new byte[existingBytes.length + newMember.length];
        System.arraycopy(existingBytes, 0, combined, 0, existingBytes.length);
        System.arraycopy(newMember, 0, combined, existingBytes.length, newMember.length);
        return combined;
    }

    private static String formatS3Line(String logType, Map<String, Object> record) {
        if ("useractivitylog".equals(logType)) {
            String recordTime = String.valueOf(record.getOrDefault("recordtime", ""));
            String db = String.valueOf(record.getOrDefault("db", ""));
            String user = String.valueOf(record.getOrDefault("user", ""));
            String pid = String.valueOf(record.getOrDefault("pid", ""));
            String userid = String.valueOf(record.getOrDefault("userid", ""));
            String xid = String.valueOf(record.getOrDefault("xid", ""));
            String query = String.valueOf(record.getOrDefault("query", ""));
            return "'" + recordTime + " UTC [ db=" + db + " user=" + user + " pid=" + pid
                    + " userid=" + userid + " xid=" + xid + " ]' " + query;
        }
        return LOG_COLUMNS.get(logType).stream()
                .map(col -> {
                    Object val = record.get(col);
                    return val == null ? "" : val.toString();
                })
                .collect(Collectors.joining("|"));
    }

    private void createIfMissing(String accountId, String region, String group, String stream) {
        try {
            logs.createLogGroupForAccount(accountId, group, null, Map.of(), region);
        } catch (RuntimeException exception) {
            if (!alreadyExists(exception)) {
                throw exception;
            }
        }
        try {
            logs.createLogStreamForAccount(accountId, group, stream, region);
        } catch (RuntimeException exception) {
            if (!alreadyExists(exception)) {
                throw exception;
            }
        }
    }

    private static Set<String> exports(List<String> configured) {
        return configured == null ? LOG_TYPES : Set.copyOf(configured);
    }

    private static boolean alreadyExists(RuntimeException exception) {
        return exception instanceof AwsException awsException
                && "ResourceAlreadyExistsException".equals(awsException.getErrorCode());
    }

    private static String csvLine(String logType, Map<String, Object> record) {
        return LOG_COLUMNS.get(logType).stream()
                .map(column -> csvValue(record.get(column)))
                .collect(Collectors.joining(","));
    }

    private static String csvValue(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    static String sanitizeSql(String sql) {
        return RedshiftAuditSql.sanitize(sql);
    }
}
