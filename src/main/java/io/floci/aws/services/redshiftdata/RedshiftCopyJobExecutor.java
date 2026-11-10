package io.floci.aws.services.redshiftdata;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.redshift.RedshiftService;
import io.floci.aws.services.redshift.RedshiftZeroEtlEndpoints;
import io.floci.aws.services.redshift.model.Cluster;
import io.floci.aws.services.redshift.model.Integration;
import io.floci.aws.services.redshiftserverless.RedshiftServerlessService.WorkgroupTarget;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class RedshiftCopyJobExecutor {
    private static final Logger LOG = Logger.getLogger(RedshiftCopyJobExecutor.class);

    private final RedshiftService service;
    private final RedshiftZeroEtlEndpoints endpoints;
    private final RedshiftDataConnectionFactory connections;

    @Inject
    RedshiftCopyJobExecutor(RedshiftService service, RedshiftZeroEtlEndpoints endpoints,
                           RedshiftDataConnectionFactory connections) {
        this.service = service;
        this.endpoints = endpoints;
        this.connections = connections;
    }

    public void runAutomatic(Integration integration) {
        apply(integration, false);
    }

    public void disable(Integration integration) {
        apply(integration, true);
    }

    private void apply(Integration integration, boolean disable) {
        RedshiftDataResourceResolver.DatabaseTarget target = target(integration, disable);
        if (target == null) {
            return;
        }
        try (Connection connection = connections.open(target)) {
            List<String> databases = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                    "SELECT datname FROM pg_database WHERE datallowconn AND NOT datistemplate AND datname <> 'postgres'")) {
                while (rows.next()) {
                    databases.add(rows.getString(1));
                }
            }
            for (String database : databases) {
                RedshiftDataResourceResolver.DatabaseTarget databaseTarget = new RedshiftDataResourceResolver.DatabaseTarget(
                        target.arn(), target.host(), target.port(), database, target.user(), target.password());
                try (Connection databaseConnection = connections.open(databaseTarget)) {
                    applyToDatabase(databaseConnection, integration, disable);
                } catch (SQLException exception) {
                    if (!"42P01".equals(exception.getSQLState()) && !"3F000".equals(exception.getSQLState())) {
                        if (disable) {
                            throw exception;
                        }
                        LOG.warnv(exception, "S3 auto-copy failed in database {0} for integration {1}",
                                database, integration.getIntegrationArn());
                    }
                }
            }
        } catch (SQLException exception) {
            throw new AwsException("InternalFailure", "Could not process Redshift S3 copy jobs: "
                    + exception.getMessage(), 500);
        }
    }

    private static void applyToDatabase(Connection connection, Integration integration, boolean disable) throws SQLException {
        String bucket = AwsArnUtils.parse(integration.getSourceArn()).resource();
        if (disable) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE floci_internal.copy_job SET is_auto = false WHERE bucket = ?")) {
                statement.setString(1, bucket);
                statement.executeUpdate();
            }
            return;
        }
        List<String> jobs = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT job_name FROM floci_internal.copy_job WHERE is_auto AND bucket = ?")) {
            statement.setString(1, bucket);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    jobs.add(rows.getString(1));
                }
            }
        }
        for (String job : jobs) {
            connection.setAutoCommit(false);
            try (PreparedStatement lock = connection.prepareStatement(
                    "SELECT job_id FROM floci_internal.copy_job WHERE job_name = ? AND is_auto FOR UPDATE")) {
                lock.setQueryTimeout(5);
                lock.setString(1, job);
                try (ResultSet row = lock.executeQuery()) {
                    if (row.next()) {
                        try (Statement statement = connection.createStatement()) {
                            statement.setQueryTimeout(60);
                            statement.execute("COPY JOB RUN \"" + job.replace("\"", "\"\"") + "\"");
                        }
                    }
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                LOG.warnv(exception, "Automatic COPY job {0} failed; its files will be retried", job);
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private RedshiftDataResourceResolver.DatabaseTarget target(Integration integration, boolean disable) {
        if (RedshiftZeroEtlEndpoints.isNamespaceArn(integration.getTargetArn())) {
            WorkgroupTarget workgroup;
            try {
                workgroup = endpoints.targetWorkgroup(integration.getAccountId(), integration.getTargetArn());
            } catch (AwsException exception) {
                if (disable && "ResourceNotFoundException".equals(exception.getErrorCode())
                        || "ValidationException".equals(exception.getErrorCode())
                        && exception.getMessage().endsWith("has no workgroup.")) {
                    LOG.debugv("No workgroup for S3 integration {0}", integration.getIntegrationArn());
                    return null;
                }
                throw exception;
            }
            if (workgroup.endpoint() == null || workgroup.endpoint().getPort() <= 0) {
                return null;
            }
            return new RedshiftDataResourceResolver.DatabaseTarget(workgroup.arn(), "127.0.0.1",
                    workgroup.endpoint().getPort(), workgroup.database(), workgroup.masterUsername(), workgroup.masterPassword());
        }
        Cluster cluster;
        try {
            cluster = service.describeClustersForAccount(integration.getAccountId(),
                    integration.getTargetClusterIdentifier()).getFirst();
        } catch (AwsException exception) {
            if (disable && "ClusterNotFound".equals(exception.getErrorCode())) {
                LOG.debugv("Target cluster already deleted for S3 integration {0}", integration.getIntegrationArn());
                return null;
            }
            throw exception;
        }
        if (!"available".equals(cluster.getClusterStatus()) || cluster.getProxyPort() <= 0) {
            if (disable) {
                throw new AwsException("InvalidClusterState", "Resume the cluster before deleting its S3 integration.", 400);
            }
            return null;
        }
        return new RedshiftDataResourceResolver.DatabaseTarget(integration.getTargetArn(), "127.0.0.1",
                cluster.getProxyPort(), "dev", cluster.getMasterUsername(), cluster.getMasterPassword());
    }
}
