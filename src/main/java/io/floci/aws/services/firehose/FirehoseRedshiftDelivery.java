package io.floci.aws.services.firehose;

import io.floci.aws.core.common.DatabaseSecretCredentials;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.services.firehose.model.RedshiftDestination;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.ServiceRoleAuthorizer;
import io.floci.aws.services.iam.ServiceRoleAuthorizer.Permission;
import io.floci.aws.services.redshift.RedshiftJdbcTargets;
import io.floci.aws.services.redshift.RedshiftJdbcTargets.Backend;
import io.floci.aws.services.redshift.RedshiftJdbcUrl;
import io.floci.aws.services.redshift.proxy.RedshiftRoleAccess;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
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
    private static final String FIREHOSE_PRINCIPAL = ServicePrincipals.of("firehose");
    /** Outlives one COPY, whose socket timeout is the longest it can run. */
    private static final Duration LOAD_CREDENTIALS_TTL = Duration.ofMinutes(15);

    /** Seam for tests; production opens a real JDBC connection. */
    interface SqlRunner {
        void execute(String jdbcUrl, String user, String password, String sql) throws SQLException;
    }

    private final RedshiftJdbcTargets jdbcTargets;
    private final ServiceRoleAuthorizer roleAuthorizer;
    private final IamService iamService;
    private final SqlRunner runner;

    @Inject
    public FirehoseRedshiftDelivery(RedshiftJdbcTargets jdbcTargets, ServiceRoleAuthorizer roleAuthorizer,
                                    IamService iamService) {
        this(jdbcTargets, roleAuthorizer, iamService, FirehoseRedshiftDelivery::runJdbc);
    }

    FirehoseRedshiftDelivery(RedshiftJdbcTargets jdbcTargets, ServiceRoleAuthorizer roleAuthorizer,
                             IamService iamService, SqlRunner runner) {
        this.jdbcTargets = jdbcTargets;
        this.roleAuthorizer = roleAuthorizer;
        this.iamService = iamService;
        this.runner = runner;
    }

    /**
     * Refuses a batch whose intermediate object the role of the stream's {@code S3Configuration} may not write.
     * Firehose assumes that role to stage the batch, so it has to trust {@code firehose.amazonaws.com} for this
     * stream and allow {@code s3:PutObject} on the object.
     *
     * @throws IllegalStateException when the role may not stage the object
     */
    public void requireStagingAccess(String accountId, String streamArn, RedshiftDestination destination,
                                     String bucket, String key) {
        requireAccess(accountId, streamArn, destination.getS3() == null ? null : destination.getS3().getRoleArn(),
                "s3:PutObject", bucket, key, "stage");
    }

    /**
     * Refuses a load whose staged object the destination's {@code RoleARN} may not read, with the same trust
     * requirement as {@link #requireStagingAccess} and {@code s3:GetObject} on the object.
     *
     * @throws IllegalStateException when the role may not read the object
     */
    public void requireLoadAccess(String accountId, String streamArn, RedshiftDestination destination,
                                  String bucket, String key) {
        requireAccess(accountId, streamArn, destination.getRoleArn(), "s3:GetObject", bucket, key, "load");
    }

    private void requireAccess(String accountId, String streamArn, String roleArn, String action, String bucket,
                               String key, String step) {
        // A missing role is not exempt: the authorizer refuses it with enforcement on and ignores it with enforcement off.
        String role = roleArn == null ? "" : roleArn;
        List<Permission> permissions = role.isBlank()
                ? List.of() : List.of(new Permission(action, RedshiftRoleAccess.objectArn(role, bucket, key)));
        Optional<String> refusal = roleAuthorizer.refusal(role, accountId, FIREHOSE_PRINCIPAL, streamArn, permissions);
        if (refusal.isPresent()) {
            throw new IllegalStateException("Firehose cannot " + step + " s3://" + bucket + "/" + key + ": "
                    + refusal.get());
        }
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
        Backend backend = jdbcTargets.resolve(accountId, region, target);
        String[] credentials = credentials(destination, backend, region);
        RedshiftDestination.CopyCommand command = destination.getCopyCommand();
        String jdbcUrl = "jdbc:postgresql://" + backend.host() + ":" + backend.port() + "/" + backend.database()
                + "?preferQueryMode=simple";
        // Minted per attempt: the retry loop calls copy again, and credentials from the last try may have expired.
        RedshiftRoleAccess.RoleSession loadCredentials = mintLoadCredentials(destination.getRoleArn());
        try {
            String sql = RedshiftCopySql.build(command.getDataTableName(), command.getDataTableColumns(), bucket, key,
                    command.getCopyOptions(), loadCredentials);
            runner.execute(jdbcUrl, credentials[0], credentials[1], sql);
        } catch (SQLException e) {
            throw new IllegalStateException("COPY into " + command.getDataTableName() + " on "
                    + backend.description() + " failed (SQLSTATE " + e.getSQLState() + "): "
                    + RedshiftCopySql.redact(e.getMessage(), loadCredentials), e);
        } finally {
            RedshiftRoleAccess.releaseRoleSession(loadCredentials, destination.getRoleArn(), iamService);
        }
        LOG.debugv("Firehose copied s3://{0}/{1} into {2} on {3}", bucket, key,
                command.getDataTableName(), backend.description());
    }

    /**
     * Temporary credentials of the destination's {@code RoleARN}, which is what Firehose loads with. The trust
     * and permissions were checked by {@link #requireLoadAccess}, so only the role's existence is left: with
     * enforcement off a stream may name a role IAM does not know, and the load then runs without credentials.
     */
    private RedshiftRoleAccess.RoleSession mintLoadCredentials(String roleArn) {
        return RedshiftRoleAccess.roleExists(roleArn, iamService)
                ? RedshiftRoleAccess.resolveRoleSession(roleArn, iamService, null, null, LOAD_CREDENTIALS_TTL)
                : null;
    }

    private String[] credentials(RedshiftDestination destination, Backend backend, String region) {
        RedshiftDestination.SecretsManagerConfiguration secrets = destination.getSecretsManagerConfiguration();
        if (secrets != null && Boolean.TRUE.equals(secrets.getEnabled())) {
            DatabaseSecretCredentials credentials = jdbcTargets.credentialsFromSecret(secrets.getSecretArn(), region);
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
