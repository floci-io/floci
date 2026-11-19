package io.floci.aws.services.rdsdata;

import io.floci.aws.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.aws.services.rds.model.DatabaseEngine;
import jakarta.enterprise.context.ApplicationScoped;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

@ApplicationScoped
class RdsDataConnectionFactory {

    static {
        // quarkus-jdbc-mysql set this at startup in JVM mode. Connector/J reads it once, in
        // the static initializer that otherwise starts its abandoned connection cleanup thread.
        System.setProperty("com.mysql.cj.disableAbandonedConnectionCleanup", "true");
    }

    Connection open(RdsDataResourceResolver.DatabaseTarget target,
                    EndpointInfo endpoint,
                    String username,
                    String password,
                    String database) throws SQLException {
        String url = buildUrl(target.engine(), endpoint.host(), endpoint.port(), database);
        Properties props = new Properties();
        props.setProperty("user", username);
        props.setProperty("password", password);
        props.setProperty("connectTimeout", connectTimeout(target.engine()));
        return DriverManager.getConnection(url, props);
    }

    /** The longest database name the RDS Data API accepts ({@code DbName}, at most 64 characters). */
    static final int MAX_DATABASE_NAME_LENGTH = 64;

    /**
     * The JDBC URL for {@code database}, the name percent-encoded so that both drivers read back exactly
     * the name given and nothing in it can start, add or override a connection property, as AWS passes
     * the name to the engine as an identifier. A name the engine does not know is then the engine's
     * error. {@link URLEncoder} writes a literal {@code +} as {@code %2B} and a space as {@code +}, which
     * is replaced by {@code %20}: both drivers decode a bare {@code +} in the path as a space.
     */
    static String buildUrl(DatabaseEngine engine, String host, int port, String database) {
        String path = URLEncoder.encode(database, StandardCharsets.UTF_8).replace("+", "%20");
        return switch (engine) {
            case MYSQL, MARIADB -> "jdbc:mysql://" + host + ":" + port + "/" + path
                    + "?useSSL=false&allowPublicKeyRetrieval=true";
            case POSTGRES -> "jdbc:postgresql://" + host + ":" + port + "/" + path
                    + "?sslmode=disable";
            case SQLSERVER -> throw new IllegalArgumentException(
                    "RDS Data API is not supported for SQL Server");
        };
    }

    private static String connectTimeout(DatabaseEngine engine) {
        // MySQL Connector/J expects milliseconds; the PostgreSQL driver expects seconds.
        return switch (engine) {
            case MYSQL, MARIADB -> "5000";
            case POSTGRES -> "5";
            case SQLSERVER -> "5000";
        };
    }
}
