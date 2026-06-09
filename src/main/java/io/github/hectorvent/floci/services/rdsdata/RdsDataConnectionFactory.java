package io.github.hectorvent.floci.services.rdsdata;

import io.github.hectorvent.floci.core.common.AwsException;
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
        if (target.engine() != DatabaseEngine.MYSQL && target.engine() != DatabaseEngine.MARIADB) {
            throw new AwsException("BadRequestException",
                    "RDS Data API currently supports local MySQL and MariaDB resources only.", 400);
        }
        String url = "jdbc:mysql://" + target.host() + ":" + target.port() + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true";
        Properties props = new Properties();
        props.setProperty("user", username);
        props.setProperty("password", password);
        props.setProperty("connectTimeout", "5000");
        return DriverManager.getConnection(url, props);
    }
}
