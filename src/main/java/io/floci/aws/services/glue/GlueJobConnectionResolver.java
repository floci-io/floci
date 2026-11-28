package io.floci.aws.services.glue;

import io.floci.aws.core.common.AccountResolver;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.docker.LaunchedContainerAwsEnv;
import io.floci.aws.services.glue.model.Connection;
import io.floci.aws.services.redshift.RedshiftJdbcTargets;
import io.floci.aws.services.redshift.RedshiftJdbcUrl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rewrites Redshift {@code JDBC_CONNECTION_URL} properties returned by {@code GetConnection} to the
 * container-reachable Floci auth proxy address when the caller is an active Glue Spark job container.
 * Callers outside Glue job containers receive the stored connection unchanged.
 */
@ApplicationScoped
public class GlueJobConnectionResolver {

    private static final Logger LOG = Logger.getLogger(GlueJobConnectionResolver.class);

    private final RedshiftJdbcTargets redshiftTargets;
    private final LaunchedContainerAwsEnv awsEnv;
    private final RegionResolver regionResolver;
    private final AccountResolver accountResolver;
    private final Set<String> activeJobSessions = ConcurrentHashMap.newKeySet();

    @Inject
    public GlueJobConnectionResolver(RedshiftJdbcTargets redshiftTargets,
                                     LaunchedContainerAwsEnv awsEnv,
                                     RegionResolver regionResolver,
                                     AccountResolver accountResolver) {
        this.redshiftTargets = redshiftTargets;
        this.awsEnv = awsEnv;
        this.regionResolver = regionResolver;
        this.accountResolver = accountResolver;
    }

    public void registerSession(String accessKeyId) {
        if (accessKeyId != null && !accessKeyId.isBlank()) {
            activeJobSessions.add(accessKeyId);
        }
    }

    public void unregisterSession(String accessKeyId) {
        if (accessKeyId != null && !accessKeyId.isBlank()) {
            activeJobSessions.remove(accessKeyId);
        }
    }

    public boolean isActiveJobSession(String accessKeyId) {
        return accessKeyId != null && activeJobSessions.contains(accessKeyId);
    }

    /**
     * Returns a copy of {@code connection} with a container-reachable Redshift JDBC URL when the request
     * comes from an active Glue job session, or {@code connection} unchanged otherwise.
     */
    public Connection resolveForCaller(Connection connection, String authorization, String region) {
        if (connection == null || authorization == null || activeJobSessions.isEmpty()) {
            return connection;
        }
        String accessKeyId = accountResolver.extractAccessKeyId(authorization);
        if (!isActiveJobSession(accessKeyId)) {
            return connection;
        }
        Map<String, String> properties = connection.getConnectionProperties();
        if (properties == null) {
            return connection;
        }
        String jdbcUrl = properties.get("JDBC_CONNECTION_URL");
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return connection;
        }
        int queryStart = jdbcUrl.indexOf('?');
        String baseUrl = queryStart >= 0 ? jdbcUrl.substring(0, queryStart) : jdbcUrl;
        RedshiftJdbcUrl parsed;
        try {
            parsed = RedshiftJdbcUrl.parse(baseUrl);
        } catch (IllegalArgumentException ignored) {
            // Not a Redshift JDBC URL; leave non-Redshift connections untouched.
            return connection;
        }
        String effectiveRegion = region != null && !region.isBlank() ? region : regionResolver.getRegion();
        RedshiftJdbcTargets.Backend backend;
        try {
            backend = redshiftTargets.resolve(regionResolver.getAccountId(), effectiveRegion, parsed);
        } catch (AwsException | IllegalStateException e) {
            LOG.debugv(e, "Could not resolve Redshift JDBC target for Glue connection {0}", connection.getName());
            return connection;
        }
        String containerHost = URI.create(awsEnv.flociEndpoint()).getHost();
        if (containerHost == null || containerHost.isBlank()) {
            return connection;
        }
        String rewritten = containerJdbcUrl(jdbcUrl, containerHost, backend.port(), backend.database());
        return copyWithJdbcUrl(connection, rewritten);
    }

    static String containerJdbcUrl(String originalUrl, String containerHost, int port, String database) {
        int queryStart = originalUrl.indexOf('?');
        String query = queryStart >= 0 ? originalUrl.substring(queryStart + 1) : "";
        StringBuilder url = new StringBuilder("jdbc:redshift://")
                .append(containerHost).append(':').append(port).append('/').append(database);
        if (query.isEmpty()) {
            url.append("?ssl=false");
        } else {
            url.append('?').append(query);
            if (!hasSslParam(query)) {
                url.append("&ssl=false");
            }
        }
        return url.toString();
    }

    private static boolean hasSslParam(String query) {
        for (String part : query.split("&")) {
            String lower = part.toLowerCase(Locale.ROOT);
            if (lower.startsWith("ssl=")) {
                return true;
            }
        }
        return false;
    }

    private static Connection copyWithJdbcUrl(Connection source, String jdbcUrl) {
        Connection copy = new Connection();
        copy.setName(source.getName());
        copy.setDescription(source.getDescription());
        copy.setConnectionType(source.getConnectionType());
        copy.setMatchCriteria(source.getMatchCriteria());
        Map<String, String> properties = new LinkedHashMap<>(source.getConnectionProperties());
        properties.put("JDBC_CONNECTION_URL", jdbcUrl);
        copy.setConnectionProperties(properties);
        copy.setSparkProperties(source.getSparkProperties());
        copy.setAthenaProperties(source.getAthenaProperties());
        copy.setPythonProperties(source.getPythonProperties());
        copy.setPhysicalConnectionRequirements(source.getPhysicalConnectionRequirements());
        copy.setAuthenticationConfiguration(source.getAuthenticationConfiguration());
        copy.setConnectionSchemaVersion(source.getConnectionSchemaVersion());
        copy.setCreationTime(source.getCreationTime());
        copy.setLastUpdatedTime(source.getLastUpdatedTime());
        copy.setLastUpdatedBy(source.getLastUpdatedBy());
        copy.setStatus(source.getStatus());
        copy.setStatusReason(source.getStatusReason());
        copy.setLastConnectionValidationTime(source.getLastConnectionValidationTime());
        return copy;
    }
}
