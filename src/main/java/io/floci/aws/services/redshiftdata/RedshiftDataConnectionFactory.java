package io.floci.aws.services.redshiftdata;

import io.floci.aws.services.redshift.RedshiftDbIdentityProvisioner;
import io.floci.aws.services.redshift.proxy.RedshiftInternalAuth;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.postgresql.Driver;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

@ApplicationScoped
class RedshiftDataConnectionFactory {

    private final Driver driver = new Driver();
    private final RedshiftDbIdentityProvisioner identityProvisioner;

    RedshiftDataConnectionFactory() {
        this(null);
    }

    @Inject
    RedshiftDataConnectionFactory(RedshiftDbIdentityProvisioner identityProvisioner) {
        this.identityProvisioner = identityProvisioner;
    }

    Connection open(RedshiftDataResourceResolver.DatabaseTarget target) throws SQLException {
        RedshiftDataResourceResolver.DbIdentity dbIdentity = target.dbIdentity();
        if (identityProvisioner == null || dbIdentity == null) {
            return openRaw(target, target.user(), target.password());
        }
        String backendKey = target.backendKey() != null ? target.backendKey() : target.arn();
        RedshiftDbIdentityProvisioner.PreparedIdentity prepared;
        try (Connection admin = openRaw(target, target.user(), target.password())) {
            prepared = identityProvisioner.prepare(
                    admin, backendKey, dbIdentity.role(), dbIdentity.autoCreate(),
                    dbIdentity.dbGroups(), () -> openRaw(target, target.user(), target.password()));
        }
        try {
            // The session authenticates as the role itself, so PostgreSQL refuses any switch to
            // another user and loads the role's own ALTER USER ... SET defaults.
            String password = prepared.backendPassword() != null ? prepared.backendPassword() : target.password();
            Connection connection = openRaw(target, prepared.databaseRole(), password);
            return wrapWithRelease(connection, prepared.releaseLease());
        } catch (SQLException | RuntimeException failure) {
            prepared.releaseLease().run();
            throw failure;
        }
    }

    private Connection openRaw(RedshiftDataResourceResolver.DatabaseTarget target,
                               String user, String password) throws SQLException {
        // stringtype=unspecified: Redshift Data API parameter values are always strings on the
        // wire, so every bind goes through PreparedStatement.setString. With the default
        // (varchar) the driver stamps an explicit type OID and the server refuses
        // "integer = character varying"; unspecified sends the value untyped and lets the
        // server infer the column's type from context, matching how AWS binds parameters.
        String applicationName = RedshiftInternalAuth.issueDataApiToken();
        String url = "jdbc:postgresql://" + target.host() + ":" + target.port() + "/" + target.database()
                + "?sslmode=disable&stringtype=unspecified&ApplicationName=" + applicationName;
        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        props.setProperty("ApplicationName", applicationName);
        props.setProperty("connectTimeout", "5");
        return driver.connect(url, props);
    }

    private static Connection wrapWithRelease(Connection delegate, Runnable releaseLease) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName()) && method.getParameterCount() == 0) {
                        try {
                            return method.invoke(delegate, args);
                        } catch (InvocationTargetException e) {
                            throw e.getTargetException();
                        } finally {
                            releaseLease.run();
                        }
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getTargetException();
                    }
                });
    }
}
