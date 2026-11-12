package io.floci.aws.services.redshiftdata;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.redshift.RedshiftService;
import io.floci.aws.services.redshift.RedshiftZeroEtlEndpoints;
import io.floci.aws.services.redshift.model.Cluster;
import io.floci.aws.services.redshift.model.Integration;
import io.floci.aws.services.redshift.proxy.S3CopySimulator.S3TransferException;
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

    private record TargetResolution(RedshiftDataResourceResolver.DatabaseTarget database, boolean unavailable) {
        private static final TargetResolution MISSING = new TargetResolution(null, false);
        private static final TargetResolution UNAVAILABLE = new TargetResolution(null, true);
    }

    @Inject
    RedshiftCopyJobExecutor(RedshiftService service, RedshiftZeroEtlEndpoints endpoints,
                           RedshiftDataConnectionFactory connections) {
        this.service = service;
        this.endpoints = endpoints;
        this.connections = connections;
    }

    /** Returns whether the bucket needs another sweep. */
    public boolean runAutomatic(Integration integration) {
        return apply(integration, false);
    }

    public void disable(Integration integration) {
        apply(integration, true);
    }

    private boolean apply(Integration integration, boolean disable) {
        TargetResolution resolution = target(integration, disable);
        RedshiftDataResourceResolver.DatabaseTarget target = resolution.database();
        if (target == null) {
            return !disable && resolution.unavailable();
        }
        boolean retry = false;
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
                    retry |= applyToDatabase(databaseConnection, integration, disable);
                } catch (SQLException exception) {
                    if (!"42P01".equals(exception.getSQLState()) && !"3F000".equals(exception.getSQLState())) {
                        if (disable) {
                            throw exception;
                        }
                        retry = true;
                        LOG.warnv(exception, "S3 auto-copy failed in database {0} for integration {1}; will retry",
                                database, integration.getIntegrationArn());
                    }
                }
            }
        } catch (SQLException exception) {
            throw new AwsException("InternalFailure", "Could not process Redshift S3 copy jobs: "
                    + exception.getMessage(), 500);
        }
        return retry;
    }

    private static boolean applyToDatabase(Connection connection, Integration integration, boolean disable)
            throws SQLException {
        String bucket = AwsArnUtils.parse(integration.getSourceArn()).resource();
        if (disable) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE floci_internal.copy_job SET is_auto = false WHERE bucket = ?")) {
                statement.setString(1, bucket);
                statement.executeUpdate();
            }
            return false;
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
        boolean retry = false;
        for (String job : jobs) {
            retry |= runAutomaticJob(connection, job);
        }
        return retry;
    }

    private static boolean runAutomaticJob(Connection connection, String job) throws SQLException {
        connection.setAutoCommit(false);
        boolean retry = false;
        try {
            try (PreparedStatement lock = connection.prepareStatement(
                    "SELECT 1 FROM floci_internal.copy_job WHERE job_name = ? AND is_auto FOR UPDATE")) {
                lock.setQueryTimeout(5);
                lock.setString(1, job);
                try (ResultSet row = lock.executeQuery()) {
                    if (row.next()) {
                        try (Statement statement = connection.createStatement()) {
                            statement.execute("COPY JOB RUN \"" + job.replace("\"", "\"\"") + "\"");
                        }
                    }
                }
            }
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            if (S3TransferException.isJobFailure(exception.getSQLState())) {
                LOG.warnv(exception, "Automatic COPY job {0} failed and will run again on the next upload", job);
            } else {
                LOG.warnv(exception, "Automatic COPY job {0} failed; its files will be retried", job);
                retry = true;
            }
        }
        connection.setAutoCommit(true);
        return retry;
    }

    private TargetResolution target(Integration integration, boolean disable) {
        if (RedshiftZeroEtlEndpoints.isNamespaceArn(integration.getTargetArn())) {
            WorkgroupTarget workgroup;
            try {
                workgroup = endpoints.targetWorkgroup(integration.getAccountId(), integration.getTargetArn());
            } catch (AwsException exception) {
                if ("ResourceNotFoundException".equals(exception.getErrorCode())) {
                    LOG.debugv("Target namespace not found for S3 integration {0}", integration.getIntegrationArn());
                    return TargetResolution.MISSING;
                }
                if ("ValidationException".equals(exception.getErrorCode())) {
                    LOG.debugv("Workgroup target unavailable for S3 integration {0}: {1}",
                            integration.getIntegrationArn(), exception.getMessage());
                    return TargetResolution.UNAVAILABLE;
                }
                throw exception;
            }
            if (workgroup == null || workgroup.host() == null || workgroup.port() <= 0
                    || workgroup.endpoint() == null || workgroup.endpoint().getPort() <= 0) {
                return TargetResolution.UNAVAILABLE;
            }
            return new TargetResolution(new RedshiftDataResourceResolver.DatabaseTarget(workgroup.arn(), "127.0.0.1",
                    workgroup.endpoint().getPort(), workgroup.database(), workgroup.masterUsername(), workgroup.masterPassword()),
                    false);
        }
        List<Cluster> clusters;
        try {
            clusters = service.describeClustersForAccount(integration.getAccountId(),
                    integration.getTargetClusterIdentifier());
        } catch (AwsException exception) {
            if ("ClusterNotFound".equals(exception.getErrorCode())) {
                LOG.debugv("Target cluster not found for S3 integration {0}", integration.getIntegrationArn());
                return TargetResolution.MISSING;
            }
            throw exception;
        }
        if (clusters.isEmpty()) {
            return TargetResolution.MISSING;
        }
        Cluster cluster = clusters.getFirst();
        if (!"available".equals(cluster.getClusterStatus()) || cluster.getProxyPort() <= 0) {
            if (disable) {
                throw new AwsException("InvalidClusterState", "Resume the cluster before deleting its S3 integration.", 400);
            }
            return TargetResolution.UNAVAILABLE;
        }
        return new TargetResolution(new RedshiftDataResourceResolver.DatabaseTarget(integration.getTargetArn(),
                "127.0.0.1", cluster.getProxyPort(), "dev", cluster.getMasterUsername(), cluster.getMasterPassword()), false);
    }
}
