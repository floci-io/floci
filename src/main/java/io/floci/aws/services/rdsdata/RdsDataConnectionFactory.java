package io.floci.aws.services.rdsdata;

import io.floci.aws.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.aws.services.rds.model.DatabaseEngine;
import jakarta.enterprise.context.ApplicationScoped;

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
     * The first character of {@code database} that would change the JDBC URL's structure rather than
     * name a database (one that starts or separates connection properties, a path, a fragment, a user
     * or an escape, whitespace, or a control character), or null when there is none. The database
     * goes into the URL as written, so such a character would let the name add or override connection
     * properties.
     */
    static Character urlUnsafeCharacter(String database) {
        for (int i = 0; i < database.length(); i++) {
            char c = database.charAt(i);
            if ("?&=#/\\@;%:".indexOf(c) >= 0 || Character.isWhitespace(c) || Character.isISOControl(c)) {
                return c;
            }
        }
        return null;
    }

    static String buildUrl(DatabaseEngine engine, String host, int port, String database) {
        if (urlUnsafeCharacter(database) != null) {
            throw new IllegalArgumentException("Database name cannot be used in a JDBC URL: " + database);
        }
        return switch (engine) {
            case MYSQL, MARIADB -> "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useSSL=false&allowPublicKeyRetrieval=true";
            case POSTGRES -> "jdbc:postgresql://" + host + ":" + port + "/" + database
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
