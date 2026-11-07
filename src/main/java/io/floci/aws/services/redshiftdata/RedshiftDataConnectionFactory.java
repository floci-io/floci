package io.floci.aws.services.redshiftdata;

import io.floci.aws.services.redshift.proxy.RedshiftInternalAuth;
import jakarta.enterprise.context.ApplicationScoped;
import org.postgresql.Driver;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

@ApplicationScoped
class RedshiftDataConnectionFactory {

    private final Driver driver = new Driver();

    Connection open(RedshiftDataResourceResolver.DatabaseTarget target) throws SQLException {
        // stringtype=unspecified: Redshift Data API parameter values are always strings on the
        // wire, so every bind goes through PreparedStatement.setString. With the default
        // (varchar) the driver stamps an explicit type OID and the server refuses
        // "integer = character varying"; unspecified sends the value untyped and lets the
        // server infer the column's type from context, matching how AWS binds parameters.
        String applicationName = RedshiftInternalAuth.issueDataApiToken();
        String url = "jdbc:postgresql://" + target.host() + ":" + target.port() + "/" + target.database()
                + "?sslmode=disable&stringtype=unspecified&ApplicationName=" + applicationName;
        Properties props = new Properties();
        props.setProperty("user", target.user());
        props.setProperty("password", target.password());
        props.setProperty("ApplicationName", applicationName);
        props.setProperty("connectTimeout", "5");
        return driver.connect(url, props);
    }
}
