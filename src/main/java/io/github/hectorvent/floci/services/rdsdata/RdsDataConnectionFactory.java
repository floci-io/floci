package io.github.hectorvent.floci.services.rdsdata;

import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import jakarta.enterprise.context.ApplicationScoped;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

@ApplicationScoped
class RdsDataConnectionFactory {

    Connection open(RdsDataResourceResolver.DatabaseTarget target,
                    String username,
                    String password,
                    String database) throws SQLException {
        String url = buildUrl(target.engine(), target.host(), target.port(), database);
        Properties props = new Properties();
        props.setProperty("user", username);
        props.setProperty("password", password);
        props.setProperty("connectTimeout", connectTimeout(target.engine()));
        return DriverManager.getConnection(url, props);
    }

    static String buildUrl(DatabaseEngine engine, String host, int port, String database) {
        return switch (engine) {
            case MYSQL, MARIADB -> "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useSSL=false&allowPublicKeyRetrieval=true";
            case POSTGRES -> "jdbc:postgresql://" + host + ":" + port + "/" + database
                    + "?sslmode=disable";
        };
    }

    private static String connectTimeout(DatabaseEngine engine) {
        // MySQL Connector/J expects milliseconds; the PostgreSQL driver expects seconds.
        return switch (engine) {
            case MYSQL, MARIADB -> "5000";
            case POSTGRES -> "5";
        };
    }
}
