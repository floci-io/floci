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
    static final int BATCH_FILE_LIMIT = 100;

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
            while (runAutomaticBatch(connection, job)) {
                // Commit each bounded batch before starting the next batch.
            }
        }
    }

    private static boolean runAutomaticBatch(Connection connection, String job) throws SQLException {
        connection.setAutoCommit(false);
        try (PreparedStatement lock = connection.prepareStatement(
                "SELECT job_id, (SELECT count(*) FROM floci_internal.copy_job_detail d WHERE d.job_id = j.job_id) "
                        + "FROM floci_internal.copy_job j WHERE j.job_name = ? AND j.is_auto FOR UPDATE")) {
            lock.setQueryTimeout(5);
            lock.setString(1, job);
            long jobId = -1;
            long before = 0;
            try (ResultSet row = lock.executeQuery()) {
                if (row.next()) {
                    jobId = row.getLong(1);
                    before = row.getLong(2);
                    try (Statement statement = connection.createStatement()) {
                        statement.setQueryTimeout(60);
                        statement.execute("COPY JOB RUN \"" + job.replace("\"", "\"\"") + "\"");
                    }
                }
            }
            long added = 0;
            if (jobId >= 0) {
                try (PreparedStatement afterQuery = connection.prepareStatement(
                        "SELECT count(*) FROM floci_internal.copy_job_detail WHERE job_id = ?")) {
                    if (afterQuery != null) {
                        afterQuery.setLong(1, jobId);
                        try (ResultSet afterRow = afterQuery.executeQuery()) {
                            if (afterRow != null && afterRow.next()) {
                                added = afterRow.getLong(1) - before;
                            }
                        }
                    }
                }
            }
            connection.commit();
            return added >= BATCH_FILE_LIMIT;
        } catch (SQLException exception) {
            connection.rollback();
            LOG.warnv(exception, "Automatic COPY job {0} failed; its files will be retried", job);
            return false;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private RedshiftDataResourceResolver.DatabaseTarget target(Integration integration, boolean disable) {
        if (RedshiftZeroEtlEndpoints.isNamespaceArn(integration.getTargetArn())) {
            WorkgroupTarget workgroup;
            try {
                workgroup = endpoints.targetWorkgroup(integration.getAccountId(), integration.getTargetArn());
            } catch (AwsException exception) {
                if ("ResourceNotFoundException".equals(exception.getErrorCode())
                        || "ValidationException".equals(exception.getErrorCode())) {
                    LOG.debugv("Workgroup target unavailable for S3 integration {0}: {1}",
                            integration.getIntegrationArn(), exception.getMessage());
                    return null;
                }
                throw exception;
            }
            if (workgroup == null || workgroup.host() == null || workgroup.port() <= 0
                    || workgroup.endpoint() == null || workgroup.endpoint().getPort() <= 0) {
                return null;
            }
            return new RedshiftDataResourceResolver.DatabaseTarget(workgroup.arn(), "127.0.0.1",
                    workgroup.endpoint().getPort(), workgroup.database(), workgroup.masterUsername(), workgroup.masterPassword());
        }
        List<Cluster> clusters;
        try {
            clusters = service.describeClustersForAccount(integration.getAccountId(),
                    integration.getTargetClusterIdentifier());
        } catch (AwsException exception) {
            if ("ClusterNotFound".equals(exception.getErrorCode())) {
                LOG.debugv("Target cluster not found for S3 integration {0}", integration.getIntegrationArn());
                return null;
            }
            throw exception;
        }
        if (clusters.isEmpty()) {
            return null;
        }
        Cluster cluster = clusters.getFirst();
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
