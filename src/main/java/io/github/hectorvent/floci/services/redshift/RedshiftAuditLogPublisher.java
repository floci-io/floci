package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ApplicationScoped
public class RedshiftAuditLogPublisher {

    private static final Logger LOG = Logger.getLogger(RedshiftAuditLogPublisher.class);
    private static final Set<String> LOG_TYPES = Set.of("connectionlog", "userlog", "useractivitylog");
    private static final Pattern SQL_PASSWORD = Pattern.compile(
            "(?i)(\\bPASSWORD\\s+)(?:'(?:''|[^'])*'|\"(?:\"\"|[^\"])*\"|[^\\s;,]+)");
    private static final Map<String, List<String>> LOG_COLUMNS = Map.of(
            "connectionlog", List.of("event", "recordtime", "remotehost", "remoteport", "pid", "dbname",
                    "username", "authmethod", "duration", "sslversion", "sslcipher", "mtu", "sslcompression",
                    "sslexpansion", "iamauthguid", "application_name", "os_version", "driver_version",
                    "plugin_name", "protocol_version", "sessionid", "compression"),
            "userlog", List.of("userid", "username", "oldusername", "action", "usecreatedb", "usesuper",
                    "usecatupd", "valuntil", "pid", "xid", "recordtime"),
            "useractivitylog", List.of("recordtime", "db", "user", "pid", "userid", "xid", "query"));

    private final CloudWatchLogsService logs;
    private final Clock clock;
    private final Map<String, Registration> registrations = new ConcurrentHashMap<>();

    private record Registration(String region, String identifier, boolean enabled, String destination,
                                List<String> exports, boolean activityLoggingEnabled) { }

    @Inject
    public RedshiftAuditLogPublisher(CloudWatchLogsService logs) {
        this(logs, Clock.systemUTC());
    }

    RedshiftAuditLogPublisher(CloudWatchLogsService logs, Clock clock) {
        this.logs = logs;
        this.clock = clock;
    }

    public void register(String accountId, String region, Cluster cluster) {
        registrations.put(registryKey(accountId, cluster.getClusterIdentifier()), new Registration(region,
                cluster.getClusterIdentifier(), cluster.isLoggingEnabled(), cluster.getLoggingDestinationType(),
                cluster.getLoggingExports() == null ? null : List.copyOf(cluster.getLoggingExports()),
                cluster.isUserActivityLoggingEnabled()));
    }

    public void unregister(String accountId, String clusterIdentifier) {
        registrations.remove(registryKey(accountId, clusterIdentifier));
    }

    public void publishRegistered(String accountId, String clusterIdentifier, String logType,
                                  Map<String, Object> record) {
        Registration registration = registrations.get(registryKey(accountId, clusterIdentifier));
        if (registration == null) {
            return;
        }
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier(registration.identifier());
        cluster.setLoggingEnabled(registration.enabled());
        cluster.setLoggingDestinationType(registration.destination());
        cluster.setLoggingExports(registration.exports());
        cluster.setUserActivityLoggingEnabled(registration.activityLoggingEnabled());
        publish(cluster, accountId, registration.region(), logType, record);
    }

    public void publishRegisteredActivity(String accountId, String clusterIdentifier, String database,
                                          String username, String sql) {
        Registration registration = registrations.get(registryKey(accountId, clusterIdentifier));
        if (registration == null) {
            return;
        }
        Cluster cluster = registeredCluster(registration);
        publishActivity(cluster, accountId, registration.region(), database, username, sql);
    }

    public void publishRegisteredUserChange(String accountId, String clusterIdentifier, String database,
                                            String username, String action, String oldUsername) {
        Registration registration = registrations.get(registryKey(accountId, clusterIdentifier));
        if (registration == null) {
            return;
        }
        Cluster cluster = registeredCluster(registration);
        publishUserChange(cluster, accountId, registration.region(), database, username, action, oldUsername);
    }

    private static Cluster registeredCluster(Registration registration) {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier(registration.identifier());
        cluster.setLoggingEnabled(registration.enabled());
        cluster.setLoggingDestinationType(registration.destination());
        cluster.setLoggingExports(registration.exports());
        cluster.setUserActivityLoggingEnabled(registration.activityLoggingEnabled());
        return cluster;
    }

    private static String registryKey(String accountId, String clusterIdentifier) {
        return accountId + ":" + clusterIdentifier;
    }

    public void publishActivity(Cluster cluster, String accountId, String region, String database, String username,
                                String sql) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("recordtime", Instant.ofEpochMilli(clock.millis()).toString());
        record.put("db", database);
        record.put("user", username);
        record.put("query", "LOG: " + sanitizeSql(sql));
        publish(cluster, accountId, region, "useractivitylog", record);
    }

    public void publishConnection(Cluster cluster, String accountId, String region, String database, String username,
                                  String event, String remoteAddress, String error) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("recordtime", Instant.ofEpochMilli(clock.millis()).toString());
        record.put("dbname", database);
        record.put("username", username);
        record.put("event", event);
        record.put("remotehost", remoteAddress);
        record.put("authmethod", "password");
        record.put("remoteport", "");
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
        publish(cluster, accountId, region, "connectionlog", record);
    }

    public void publishUserChange(Cluster cluster, String accountId, String region, String database,
                                  String username, String action, String oldUsername) {
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
    }

    public void publish(Cluster cluster, String accountId, String region, String logType,
                        Map<String, Object> record) {
        if (cluster == null || !cluster.isLoggingEnabled()
                || !"cloudwatch".equalsIgnoreCase(cluster.getLoggingDestinationType())
                || "useractivitylog".equals(logType) && !cluster.isUserActivityLoggingEnabled()
                || !LOG_TYPES.contains(logType) || !exports(cluster.getLoggingExports()).contains(logType)) {
            return;
        }

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
        Matcher matcher = SQL_PASSWORD.matcher(sql);
        StringBuilder sanitized = new StringBuilder(sql.length());
        while (matcher.find()) {
            matcher.appendReplacement(sanitized, Matcher.quoteReplacement(matcher.group(1) + "'[REDACTED]'"));
        }
        matcher.appendTail(sanitized);
        return sanitized.toString();
    }
}
