package io.floci.aws.services.rds.integration;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PostgresWire;
import io.floci.aws.services.rds.model.Integration;
import io.floci.aws.services.redshift.RedshiftService;
import io.floci.aws.services.redshift.RedshiftZeroEtlEndpoints;
import io.floci.aws.services.redshift.spectrum.BackendSql;
import io.floci.aws.services.redshift.spectrum.ExternalStatement;
import io.floci.aws.services.redshift.spectrum.IntegrationDatabaseCreator;
import io.floci.aws.services.redshift.spectrum.SpectrumSession;
import io.floci.aws.services.redshift.spectrum.SpectrumSqlException;
import io.floci.aws.services.redshiftserverless.RedshiftServerlessRuntime;
import io.floci.aws.services.redshiftserverless.RedshiftServerlessService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code CREATE DATABASE <name> FROM INTEGRATION '<id>' DATABASE <source>} on the Redshift cluster or
 * workgroup an RDS zero-ETL integration replicates into: creates the database and starts the initial
 * load of the source database into it.
 */
@ApplicationScoped
public class RdsIntegrationDatabases implements IntegrationDatabaseCreator {

    private static final String UNDEFINED_OBJECT = "42704";
    private static final String SYNTAX_ERROR = "42601";
    private static final String INVALID_PARAMETER_VALUE = "22023";
    private static final String DUPLICATE_DATABASE = "42P04";
    private static final String NOT_IN_PREREQUISITE_STATE = "55000";
    private static final String FEATURE_NOT_SUPPORTED = "0A000";

    private final RdsIntegrationService integrations;
    private final RdsIntegrationLoader loader;
    private final RedshiftService redshiftService;
    private final RedshiftServerlessService serverlessService;

    @Inject
    public RdsIntegrationDatabases(RdsIntegrationService integrations, RdsIntegrationLoader loader,
                                   RedshiftService redshiftService, RedshiftServerlessService serverlessService) {
        this.integrations = integrations;
        this.loader = loader;
        this.redshiftService = redshiftService;
        this.serverlessService = serverlessService;
    }

    @Override
    public void create(ExternalStatement.CreateIntegrationDatabase statement, SpectrumSession session,
                       BackendSql backend) {
        String integrationId = statement.integrationId();
        Integration integration = integrations.findById(integrationId)
                .filter(found -> replicatesInto(found, session))
                .orElseThrow(() -> notFound(integrationId, session));
        String sourceDatabase = statement.sourceDatabase();
        if (sourceDatabase == null || sourceDatabase.isBlank()) {
            throw new SpectrumSqlException(SYNTAX_ERROR, "Integration " + integrationId
                    + " replicates a PostgreSQL source, so CREATE DATABASE must name the source database with"
                    + " DATABASE '<source_database>'");
        }
        if (!IntegrationDataFilter.parsePostgres(integration.getDataFilter()).includesDatabase(sourceDatabase)) {
            throw new SpectrumSqlException(INVALID_PARAMETER_VALUE, "Source database \"" + sourceDatabase
                    + "\" is not included in the data filter of integration " + integrationId);
        }
        Optional<Integration.Destination> existing = integration.destinationFor(sourceDatabase);
        boolean previousGone = existing
                .filter(destination -> !Integration.Destination.STATE_CREATING.equals(destination.getState()))
                .map(destination -> previousDatabaseGone(destination, backend))
                .orElse(false);
        boolean reuseFailedDatabase = existing.isPresent()
                && Integration.Destination.STATE_FAILED.equals(existing.get().getState())
                && statement.databaseName().equals(existing.get().getDatabaseName())
                && !previousGone;
        List<Integration.IntegrationError> previousErrors = integration.getErrors().stream()
                .filter(error -> RdsIntegrationService.isErrorForSourceDatabase(error, sourceDatabase))
                .toList();
        if (reuseFailedDatabase) {
            Integration syncing = runDestinationRegistration(
                    () -> integrations.addDestination(integration, statement.databaseName(), sourceDatabase, false));
            String loadId = syncing.destinationFor(sourceDatabase)
                    .map(Integration.Destination::getLoadId)
                    .orElse(null);
            loader.submit(syncing, sourceDatabase, statement.databaseName(), loadId);
            return;
        }
        String droppedDatabaseName = previousGone ? existing.get().getDatabaseName() : null;
        String droppedLoadId = previousGone ? existing.get().getLoadId() : null;
        Integration reserved = runDestinationRegistration(
                () -> integrations.reserveDestination(integration, statement.databaseName(), sourceDatabase,
                        droppedDatabaseName, droppedLoadId));
        String loadId = reserved.destinationFor(sourceDatabase)
                .map(Integration.Destination::getLoadId)
                .orElse(null);
        try {
            backend.execute("CREATE DATABASE " + PostgresWire.quoteIdentifier(statement.databaseName()));
        } catch (RuntimeException e) {
            if (existing.isPresent()) {
                integrations.restoreDestination(integration, sourceDatabase, loadId, existing.get(), previousErrors);
            } else {
                integrations.removeDestination(integration, sourceDatabase, loadId);
            }
            throw e;
        }
        Integration syncing = runDestinationRegistration(
                () -> integrations.startDestinationSync(reserved, sourceDatabase, loadId));
        loader.submit(syncing, sourceDatabase, statement.databaseName(), loadId);
    }

    private static Integration runDestinationRegistration(Supplier<Integration> action) {
        try {
            return action.get();
        } catch (AwsException e) {
            String state = switch (e.getErrorCode()) {
                case "IntegrationAlreadyExistsFault" -> DUPLICATE_DATABASE;
                case "IntegrationNotFoundFault" -> UNDEFINED_OBJECT;
                default -> NOT_IN_PREREQUISITE_STATE;
            };
            throw new SpectrumSqlException(state, e.getMessage());
        }
    }

    /**
     * Whether a database was created from {@code sourceDatabase} before and has since been dropped on the target,
     * so that it can be created and loaded again.
     */
    private static boolean previousDatabaseGone(Integration.Destination destination, BackendSql backend) {
        return backend.queryString("SELECT 1 FROM pg_catalog.pg_database WHERE datname = '"
                + destination.getDatabaseName().replace("'", "''") + "'").isEmpty();
    }

    /** Whether the integration's target is the provisioned cluster or the workgroup the session runs on. */
    private boolean replicatesInto(Integration integration, SpectrumSession session) {
        String sessionTarget = session.clusterIdentifier();
        if (sessionTarget == null || !session.accountId().equals(integration.getAccountId())) {
            return false;
        }
        String targetArn = integration.getTargetArn();
        if (RedshiftZeroEtlEndpoints.isNamespaceArn(targetArn)) {
            try {
                RedshiftServerlessService.WorkgroupTarget workgroup =
                        serverlessService.getNamespaceWorkgroupTarget(targetArn, integration.getRegion());
                return sessionTarget.equals(
                        RedshiftServerlessRuntime.backendId(integration.getRegion(), workgroup.workgroupName()));
            } catch (AwsException expected) {
                // A namespace without a workgroup serves no session, so no session's statement can name it.
                return false;
            }
        }
        String clusterIdentifier = AwsArnUtils.parse(targetArn).resource().substring("cluster:".length());
        return sessionTarget.equals(clusterIdentifier)
                && redshiftService.clusterRegion(session.accountId(), clusterIdentifier)
                        .map(integration.getRegion()::equals).orElse(false);
    }

    private SpectrumSqlException notFound(String integrationId, SpectrumSession session) {
        if (redshiftService.integrationExists(session.accountId(), integrationId)) {
            return new SpectrumSqlException(FEATURE_NOT_SUPPORTED, "CREATE DATABASE ... FROM INTEGRATION is only "
                    + "supported for integrations from RDS and Aurora PostgreSQL; integration " + integrationId
                    + " replicates another source");
        }
        return new SpectrumSqlException(UNDEFINED_OBJECT, "Integration " + integrationId
                + " does not exist or does not replicate into this database");
    }
}
