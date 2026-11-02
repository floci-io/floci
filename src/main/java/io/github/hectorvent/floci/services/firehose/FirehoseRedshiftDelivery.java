package io.github.hectorvent.floci.services.firehose;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.DatabaseSecretCredentials;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.firehose.model.RedshiftDestination;
import io.github.hectorvent.floci.services.redshift.RedshiftService;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.redshiftserverless.RedshiftServerlessService;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.secretsmanager.model.SecretVersion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Loads a delivered S3 object into a Floci Redshift cluster or Redshift Serverless workgroup. The
 * {@code COPY} runs over a JDBC connection to the cluster's wire proxy, or to the workgroup's runtime,
 * in simple query mode, so the S3 COPY emulation does the work exactly as it does for any other client.
 */
@ApplicationScoped
public class FirehoseRedshiftDelivery {

    private static final Logger LOG = Logger.getLogger(FirehoseRedshiftDelivery.class);
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int SOCKET_TIMEOUT_SECONDS = 150;

    /** Seam for tests; production opens a real JDBC connection. */
    interface SqlRunner {
        void execute(String jdbcUrl, String user, String password, String sql) throws SQLException;
    }

    /**
     * Where a delivery connects. {@code defaultUser} signs in when the destination names none.
     */
    private record Backend(String description, String host, int port, String database, String defaultUser) {
    }

    private final RedshiftService redshiftService;
    private final RedshiftServerlessService serverlessService;
    private final SecretsManagerService secretsManagerService;
    private final ObjectMapper mapper;
    private final SqlRunner runner;

    @Inject
    public FirehoseRedshiftDelivery(RedshiftService redshiftService, RedshiftServerlessService serverlessService,
                                    SecretsManagerService secretsManagerService, ObjectMapper mapper) {
        this(redshiftService, serverlessService, secretsManagerService, mapper, FirehoseRedshiftDelivery::runJdbc);
    }

    FirehoseRedshiftDelivery(RedshiftService redshiftService, RedshiftServerlessService serverlessService,
                             SecretsManagerService secretsManagerService, ObjectMapper mapper, SqlRunner runner) {
        this.redshiftService = redshiftService;
        this.serverlessService = serverlessService;
        this.secretsManagerService = secretsManagerService;
        this.mapper = mapper;
        this.runner = runner;
    }

    /**
     * Runs the destination's COPY for the object at {@code s3://bucket/key}.
     *
     * Throws {@code AwsException} when the cluster, the workgroup or the secret cannot be resolved.
     *
     * @throws IllegalStateException when the statement itself fails; the message carries the table and SQL state
     */
    public void copy(String accountId, String region, RedshiftDestination destination, String bucket, String key) {
        RedshiftJdbcUrl target = RedshiftJdbcUrl.parse(destination.getClusterJdbcUrl());
        Backend backend = target.serverless()
                ? workgroupBackend(accountId, region, target)
                : clusterBackend(accountId, target);
        String[] credentials = credentials(destination, backend, region);
        RedshiftDestination.CopyCommand command = destination.getCopyCommand();
        String sql = RedshiftCopySql.build(command.getDataTableName(), command.getDataTableColumns(), bucket, key,
                command.getCopyOptions());
        String jdbcUrl = "jdbc:postgresql://" + backend.host() + ":" + backend.port() + "/" + backend.database()
                + "?preferQueryMode=simple";
        try {
            runner.execute(jdbcUrl, credentials[0], credentials[1], sql);
        } catch (SQLException e) {
            throw new IllegalStateException("COPY into " + command.getDataTableName() + " on "
                    + backend.description() + " failed (SQLSTATE " + e.getSQLState() + "): " + e.getMessage(), e);
        }
        LOG.debugv("Firehose copied s3://{0}/{1} into {2} on {3}", bucket, key,
                command.getDataTableName(), backend.description());
    }

    private Backend clusterBackend(String accountId, RedshiftJdbcUrl target) {
        Cluster cluster = redshiftService.describeClustersForAccount(accountId, target.clusterIdentifier()).get(0);
        // The database name in the URL is ignored: the emulated container database is always "dev".
        return new Backend("cluster " + target.clusterIdentifier(), "127.0.0.1", cluster.getEndpoint().getPort(),
                "dev", cluster.getMasterUsername());
    }

    /**
     * A workgroup has one database, so a URL that names another one cannot load anything: the same
     * refusal the Data API gives, here a failed delivery that goes through the retry path.
     */
    private Backend workgroupBackend(String accountId, String region, RedshiftJdbcUrl target) {
        RedshiftServerlessService.WorkgroupTarget workgroup = RequestScopes.callAs(accountId, region,
                () -> serverlessService.getWorkgroupTarget(target.clusterIdentifier(), region));
        if (!target.database().equals(workgroup.database())) {
            throw new IllegalStateException("Database " + target.database() + " does not exist in workgroup "
                    + workgroup.workgroupName() + "; the only database is " + workgroup.database() + ".");
        }
        int port = workgroup.endpoint() != null && workgroup.endpoint().getPort() > 0
                ? workgroup.endpoint().getPort()
                : workgroup.port();
        return new Backend("workgroup " + workgroup.workgroupName(), "127.0.0.1", port,
                workgroup.database(), workgroup.adminUsername());
    }

    private String[] credentials(RedshiftDestination destination, Backend backend, String region) {
        RedshiftDestination.SecretsManagerConfiguration secrets = destination.getSecretsManagerConfiguration();
        if (secrets != null && Boolean.TRUE.equals(secrets.getEnabled())) {
            SecretVersion secret = secretsManagerService.getSecretValue(secrets.getSecretArn(), null, null, region);
            DatabaseSecretCredentials credentials = DatabaseSecretCredentials.parse(mapper, secret.getSecretString())
                    .orElseThrow(() -> new IllegalStateException("Secret " + secrets.getSecretArn()
                            + " does not contain username and password fields."));
            return new String[] {credentials.username(), credentials.password()};
        }
        if (destination.getPassword() == null) {
            throw new IllegalStateException("The Redshift destination for " + backend.description()
                    + " has no Password and no enabled SecretsManagerConfiguration, so Firehose cannot sign in.");
        }
        String user = destination.getUsername() != null ? destination.getUsername() : backend.defaultUser();
        return new String[] {user, destination.getPassword()};
    }

    /**
     * Timeouts matter here: a connection that never answers would stall the Redshift delivery thread
     * and delay emulator shutdown.
     */
    static Properties connectionProperties(String user, String password) {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("connectTimeout", String.valueOf(CONNECT_TIMEOUT_SECONDS));
        properties.setProperty("socketTimeout", String.valueOf(SOCKET_TIMEOUT_SECONDS));
        return properties;
    }

    private static void runJdbc(String jdbcUrl, String user, String password, String sql) throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl, connectionProperties(user, password));
        executeAndClose(connection, sql);
    }

    /**
     * Runs the statement and closes the connection. Once the statement has returned the load is committed
     * (autocommit), so a failure while closing must not surface as a failed delivery.
     */
    static void executeAndClose(Connection connection, String sql) throws SQLException {
        try {
            // Closing the connection closes its statement, so the statement is not closed on its own: a failure there
            // would be reported as a failed COPY too.
            Statement statement = connection.createStatement();
            statement.execute(sql);
        } catch (SQLException e) {
            closeAfterFailure(connection, e);
            throw e;
        }
        closeAfterSuccess(connection);
    }

    private static void closeAfterFailure(Connection connection, SQLException failure) {
        try {
            connection.close();
        } catch (SQLException e) {
            failure.addSuppressed(e);
        }
    }

    private static void closeAfterSuccess(Connection connection) {
        try {
            connection.close();
        } catch (SQLException e) {
            LOG.warnv(e, "Closing the Redshift connection after a committed COPY failed: {0}", e.getMessage());
        }
    }
}
