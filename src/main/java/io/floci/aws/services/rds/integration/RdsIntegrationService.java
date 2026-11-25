package io.floci.aws.services.rds.integration;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.rds.RdsService;
import io.floci.aws.services.rds.model.DatabaseEngine;
import io.floci.aws.services.rds.model.DbCluster;
import io.floci.aws.services.rds.model.DbInstance;
import io.floci.aws.services.rds.model.Integration;
import io.floci.aws.services.redshift.RedshiftService;
import io.floci.aws.services.redshift.RedshiftZeroEtlEndpoints;
import io.floci.aws.services.redshiftserverless.RedshiftServerlessService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * RDS zero-ETL integrations into Amazon Redshift: {@code CreateIntegration},
 * {@code DescribeIntegrations}, {@code ModifyIntegration} and {@code DeleteIntegration}, from an
 * Aurora PostgreSQL cluster or an RDS for PostgreSQL instance into a Redshift Serverless namespace
 * or a provisioned Redshift cluster.
 *
 * <p>The record, its validation and its lifecycle are kept here. A created integration reports
 * {@code creating} and is {@code active} on the next read, and a modified one reports
 * {@code modifying} and is {@code active} again on the next read. Each database created on the
 * target with {@code CREATE DATABASE ... FROM INTEGRATION} is a destination of the integration,
 * which is {@code syncing} while {@link RdsIntegrationLoader} copies its source database in, and then
 * {@code Synced} while {@link RdsIntegrationReplicator} applies the source database's changes to it.
 * Deviations are documented in {@code docs/services/rds.md}.
 */
@ApplicationScoped
public class RdsIntegrationService implements Resettable {

    private static final Logger LOG = Logger.getLogger(RdsIntegrationService.class);

    private static final String KEY_PREFIX = "integration::";
    static final String INITIAL_LOAD_FAILED = "InitialLoadFailed";
    static final String REPLICATION_FAILED = "ReplicationFailed";
    // The IntegrationName shape's modelled pattern and length.
    private static final Pattern NAME = Pattern.compile("[a-zA-Z](?:-?[a-zA-Z0-9]+)*");
    private static final int NAME_MAX_LENGTH = 63;
    private static final int DESCRIPTION_MAX_LENGTH = 1000;
    private static final String AURORA_POSTGRESQL = "aurora-postgresql";
    private static final String POSTGRES = "postgres";
    private static final Set<String> FILTER_NAMES =
            Set.of("integration-identifier", "integration-name", "source-arn", "target-arn", "status");

    private final StorageBackend<String, Integration> integrations;
    private final RdsService rdsService;
    private final RedshiftService redshiftService;
    private final RedshiftServerlessService redshiftServerlessService;
    private final RegionResolver regionResolver;
    private final Clock clock;
    private final Object lock = new Object();

    @Inject
    public RdsIntegrationService(StorageFactory storageFactory, RdsService rdsService,
                                 RedshiftService redshiftService, RedshiftServerlessService redshiftServerlessService,
                                 RegionResolver regionResolver, Clock clock) {
        this(storageFactory.create("rds", "rds-integrations.json",
                        new TypeReference<Map<String, Integration>>() {}),
                rdsService, redshiftService, redshiftServerlessService, regionResolver, clock);
    }

    RdsIntegrationService(StorageBackend<String, Integration> integrations, RdsService rdsService,
                          RedshiftService redshiftService, RedshiftServerlessService redshiftServerlessService,
                          RegionResolver regionResolver, Clock clock) {
        this.integrations = integrations;
        this.rdsService = rdsService;
        this.redshiftService = redshiftService;
        this.redshiftServerlessService = redshiftServerlessService;
        this.regionResolver = regionResolver;
        this.clock = clock;
    }

    public record CreateRequest(String integrationName, String sourceArn, String targetArn, String kmsKeyId,
                                Map<String, String> additionalEncryptionContext, Map<String, String> tags,
                                String dataFilter, String description) {}

    public Integration createIntegration(CreateRequest request, String region) {
        String effectiveRegion = effectiveRegion(region);
        String accountId = regionResolver.getAccountId();
        requireValidName(request.integrationName());
        requireValidDescription(request.description());
        requireSource(request.sourceArn(), effectiveRegion, accountId);
        requireTarget(request.targetArn(), effectiveRegion, accountId);
        IntegrationDataFilter.parsePostgres(request.dataFilter());
        Map<String, String> encryptionContext = request.additionalEncryptionContext() != null
                ? request.additionalEncryptionContext() : Map.of();
        if (!encryptionContext.isEmpty() && (request.kmsKeyId() == null || request.kmsKeyId().isBlank())) {
            throw new AwsException("InvalidParameterValue",
                    "AdditionalEncryptionContext can only be specified together with KMSKeyId.", 400);
        }

        Integration integration = new Integration();
        integration.setIntegrationArn(regionResolver.buildArn("rds", effectiveRegion, accountId,
                "integration:" + UUID.randomUUID()));
        integration.setIntegrationName(request.integrationName());
        integration.setSourceArn(request.sourceArn());
        integration.setTargetArn(request.targetArn());
        integration.setKmsKeyId(blankToNull(request.kmsKeyId()));
        integration.setAdditionalEncryptionContext(encryptionContext);
        integration.setTags(request.tags());
        integration.setDataFilter(request.dataFilter());
        integration.setDescription(request.description());
        integration.setCreateTime(Instant.now(clock));
        integration.setRegion(effectiveRegion);
        integration.setAccountId(accountId);
        // There is no setup to wait for: the record is active, ready for a database to be created from
        // it, and only the create response reports the creating status AWS returns.
        integration.setStatus(Integration.STATUS_ACTIVE);

        synchronized (lock) {
            if (findByName(effectiveRegion, request.integrationName()).isPresent()) {
                throw alreadyExists(request.integrationName());
            }
            if (findBySourceAndTarget(effectiveRegion, request.sourceArn(), request.targetArn()).isPresent()) {
                throw new AwsException("IntegrationAlreadyExistsFault",
                        "An integration between source " + request.sourceArn() + " and target "
                                + request.targetArn() + " already exists.", 400);
            }
            integrations.put(key(effectiveRegion, integration.integrationId()), integration);
        }
        LOG.infov("Created RDS integration {0} from {1} to {2}", integration.getIntegrationArn(),
                integration.getSourceArn(), integration.getTargetArn());
        return withStatus(integration, Integration.STATUS_CREATING);
    }

    /** The Region's integrations, oldest first, narrowed by an identifier and the filters. */
    public List<Integration> describeIntegrations(String integrationIdentifier, Map<String, List<String>> filters,
                                                  String region) {
        String effectiveRegion = effectiveRegion(region);
        for (String name : filters.keySet()) {
            if (!FILTER_NAMES.contains(name)) {
                throw new AwsException("InvalidParameterValue", "Unrecognized filter name: " + name, 400);
            }
        }
        if (integrationIdentifier != null && !integrationIdentifier.isBlank()) {
            Integration found = requireIntegration(effectiveRegion, integrationIdentifier);
            return matchesFilters(found, filters) ? List.of(new Integration(found)) : List.of();
        }
        return regionIntegrations(effectiveRegion).stream()
                .filter(integration -> matchesFilters(integration, filters))
                .map(Integration::new)
                .toList();
    }

    public Integration modifyIntegration(String integrationIdentifier, String integrationName, String dataFilter,
                                         String description, String region) {
        String effectiveRegion = effectiveRegion(region);
        if (integrationName != null) {
            requireValidName(integrationName);
        }
        requireValidDescription(description);
        if (dataFilter != null) {
            IntegrationDataFilter.parsePostgres(dataFilter);
        }
        synchronized (lock) {
            Integration integration = requireIntegration(effectiveRegion, integrationIdentifier);
            if (!Integration.STATUS_ACTIVE.equals(integration.getStatus())) {
                throw new AwsException("InvalidIntegrationStateFault", "The integration "
                        + integration.getIntegrationName() + " can't be modified because its status is "
                        + integration.getStatus() + ".", 400);
            }
            Integration updated = new Integration(integration);
            if (integrationName != null && !integrationName.equals(updated.getIntegrationName())) {
                if (findByName(effectiveRegion, integrationName).isPresent()) {
                    throw alreadyExists(integrationName);
                }
                updated.setIntegrationName(integrationName);
            }
            if (dataFilter != null) {
                updated.setDataFilter(dataFilter);
            }
            if (description != null) {
                updated.setDescription(description);
            }
            integrations.put(key(effectiveRegion, updated.integrationId()), updated);
            LOG.infov("Modified RDS integration {0}", updated.getIntegrationArn());
            return withStatus(updated, Integration.STATUS_MODIFYING);
        }
    }

    public Integration deleteIntegration(String integrationIdentifier, String region) {
        String effectiveRegion = effectiveRegion(region);
        synchronized (lock) {
            Integration integration = requireIntegration(effectiveRegion, integrationIdentifier);
            integrations.delete(key(effectiveRegion, integration.integrationId()));
            LOG.infov("Deleted RDS integration {0}", integration.getIntegrationArn());
            return withStatus(integration, Integration.STATUS_DELETING);
        }
    }

    /** The caller's integration whose ARN ends in {@code integrationId}, in any Region. */
    public Optional<Integration> findById(String integrationId) {
        if (integrationId == null || integrationId.isBlank()) {
            return Optional.empty();
        }
        return integrations.scan(key -> key.startsWith(KEY_PREFIX) && key.endsWith("::" + integrationId)).stream()
                .filter(integration -> integrationId.equals(integration.integrationId()))
                .findFirst()
                .map(Integration::new);
    }

    /**
     * Records that {@code databaseName} is being created on the target from {@code sourceDatabase}, which
     * puts the integration in {@code syncing} until its initial load ends. An {@code active}, {@code syncing}
     * or {@code failed} integration takes a new destination, one for each source database. The destination
     * already recorded for that source database is replaced when its load failed, or when the caller found
     * that its database no longer exists ({@code previousDatabaseGone}); otherwise it is refused.
     *
     * @return the integration as it now is
     */
    public Integration addDestination(Integration integration, String databaseName, String sourceDatabase,
                                      boolean previousDatabaseGone) {
        return registerDestination(integration, databaseName, sourceDatabase,
                Integration.Destination.STATE_SYNCING, previousDatabaseGone, null, null);
    }

    /**
     * Reserves a destination in {@code Creating} while {@code CREATE DATABASE} runs on the target, so a
     * concurrent request for the same source database cannot treat the not-yet-created database as dropped and
     * overwrite this registration.
     */
    public Integration reserveDestination(Integration integration, String databaseName, String sourceDatabase,
                                          String droppedDatabaseName, String droppedLoadId) {
        return registerDestination(integration, databaseName, sourceDatabase,
                Integration.Destination.STATE_CREATING, droppedDatabaseName != null, droppedDatabaseName,
                droppedLoadId);
    }

    /**
     * Promotes a reserved destination from {@code Creating} to {@code Syncing} once its database exists on
     * the target. No-op when the integration or reservation is no longer present under {@code loadId}.
     */
    public Integration startDestinationSync(Integration integration, String sourceDatabase, String loadId) {
        synchronized (lock) {
            Integration current = findCurrent(integration).orElseThrow(() -> new AwsException(
                    "IntegrationNotFoundFault", "Integration " + integration.integrationId() + " not found.", 404));
            Integration.Destination destination = current.destinationFor(sourceDatabase)
                    .filter(candidate -> Objects.equals(loadId, candidate.getLoadId()))
                    .orElseThrow(() -> new AwsException("InvalidIntegrationStateFault",
                            "Destination for source database " + sourceDatabase + " of integration "
                                    + current.integrationId() + " is no longer active.", 400));
            destination.setState(Integration.Destination.STATE_SYNCING);
            refreshStatus(current);
            integrations.put(key(current.getRegion(), current.integrationId()), current);
            return new Integration(current);
        }
    }

    private Integration registerDestination(Integration integration, String databaseName, String sourceDatabase,
                                            String initialState, boolean previousDatabaseGone,
                                            String droppedDatabaseName, String droppedLoadId) {
        synchronized (lock) {
            Integration current = findCurrent(integration).orElseThrow(() -> new AwsException(
                    "IntegrationNotFoundFault", "Integration " + integration.integrationId() + " not found.", 404));
            if (!Set.of(Integration.STATUS_ACTIVE, Integration.STATUS_SYNCING, Integration.STATUS_FAILED)
                    .contains(current.getStatus())) {
                throw new AwsException("InvalidIntegrationStateFault", "Integration " + current.integrationId()
                        + " can't take a database because its status is " + current.getStatus() + ".", 400);
            }
            Optional<Integration.Destination> previous = current.destinationFor(sourceDatabase);
            if (previous.isPresent()) {
                Integration.Destination existing = previous.get();
                // A destination currently being created is never replaceable: its database does not exist yet.
                boolean beingCreated = Integration.Destination.STATE_CREATING.equals(existing.getState());
                boolean droppedMatches = previousDatabaseGone
                        && (droppedDatabaseName == null
                                || (droppedDatabaseName.equals(existing.getDatabaseName())
                                        && Objects.equals(droppedLoadId, existing.getLoadId())));
                boolean replaceable = !beingCreated
                        && (droppedMatches || Integration.Destination.STATE_FAILED.equals(existing.getState()));
                if (!replaceable) {
                    throw new AwsException("IntegrationAlreadyExistsFault", "Database "
                            + existing.getDatabaseName() + " was already created from source database "
                            + sourceDatabase + " of integration " + current.integrationId() + ".", 400);
                }
                current.getDestinations().remove(existing);
            }
            String loadId = UUID.randomUUID().toString();
            current.getDestinations().add(new Integration.Destination(databaseName, sourceDatabase,
                    initialState, Instant.now(clock), loadId));
            current.getErrors().removeIf(error -> isErrorForSourceDatabase(error, sourceDatabase));
            refreshStatus(current);
            integrations.put(key(current.getRegion(), current.integrationId()), current);
            LOG.infov("Integration {0} syncs source database {1} into Redshift database {2}",
                    current.getIntegrationArn(), sourceDatabase, databaseName);
            return new Integration(current);
        }
    }

    /**
     * Forgets a destination owned by {@code expectedLoadId} whose database could not be created, as if it had
     * never been asked for.
     */
    public void removeDestination(Integration integration, String sourceDatabase, String expectedLoadId) {
        restoreDestination(integration, sourceDatabase, expectedLoadId, null, List.of());
    }

    /**
     * Reverts a destination registration owned by {@code expectedLoadId} when creating its database on the target
     * failed, restoring any replaced destination and its errors rather than clearing a prior failure. Another
     * caller's registration is left untouched.
     */
    public void restoreDestination(Integration integration, String sourceDatabase, String expectedLoadId,
                                   Integration.Destination previousDestination,
                                   List<Integration.IntegrationError> previousErrors) {
        synchronized (lock) {
            findCurrent(integration).ifPresent(current -> {
                Optional<Integration.Destination> existing = current.destinationFor(sourceDatabase);
                if (existing.filter(destination -> Objects.equals(expectedLoadId, destination.getLoadId()))
                        .isEmpty()) {
                    return;
                }
                current.getDestinations().removeIf(
                        destination -> sourceDatabase.equals(destination.getSourceDatabase()));
                if (previousDestination != null) {
                    current.getDestinations().add(new Integration.Destination(previousDestination));
                }
                if (previousErrors != null && !previousErrors.isEmpty()) {
                    current.getErrors().removeIf(error -> isErrorForSourceDatabase(error, sourceDatabase));
                    for (Integration.IntegrationError error : previousErrors) {
                        current.getErrors().add(new Integration.IntegrationError(
                                error.getErrorCode(), error.getErrorMessage()));
                    }
                }
                refreshStatus(current);
                integrations.put(key(current.getRegion(), current.integrationId()), current);
            });
        }
    }

    static boolean isErrorForSourceDatabase(Integration.IntegrationError error, String sourceDatabase) {
        return error != null && error.getErrorMessage() != null
                && (error.getErrorMessage().startsWith("The initial load of source database " + sourceDatabase + " ")
                        || error.getErrorMessage().startsWith(replicationErrorPrefix(sourceDatabase)));
    }

    private static String replicationErrorPrefix(String sourceDatabase) {
        return "Replication of source database " + sourceDatabase + " ";
    }

    /** Whether the integration still exists under the same ARN. */
    public boolean exists(Integration integration) {
        return findCurrent(integration).isPresent();
    }

    /**
     * Whether the integration still exists and its destination for {@code sourceDatabase} is {@code databaseName},
     * loaded, so that the changes of its source database are replicated into it.
     */
    public boolean isReplicating(Integration integration, String sourceDatabase, String databaseName) {
        return findCurrent(integration)
                .flatMap(current -> current.destinationFor(sourceDatabase))
                .filter(destination -> databaseName.equals(destination.getDatabaseName()))
                .filter(destination -> Integration.Destination.STATE_SYNCED.equals(destination.getState()))
                .isPresent();
    }

    /**
     * Fails a loaded destination whose replication stopped, with {@code failure} as the integration's error, which
     * puts the integration in {@code failed}. A destination no longer replicating is left alone.
     */
    public void failReplication(Integration integration, String sourceDatabase, String databaseName,
                                String failure) {
        synchronized (lock) {
            Optional<Integration> found = findCurrent(integration);
            Optional<Integration.Destination> destination = found
                    .flatMap(current -> current.destinationFor(sourceDatabase))
                    .filter(candidate -> databaseName.equals(candidate.getDatabaseName()))
                    .filter(candidate -> Integration.Destination.STATE_SYNCED.equals(candidate.getState()));
            if (destination.isEmpty()) {
                return;
            }
            Integration current = found.get();
            destination.get().setState(Integration.Destination.STATE_FAILED);
            current.getErrors().add(new Integration.IntegrationError(REPLICATION_FAILED,
                    replicationErrorPrefix(sourceDatabase) + "into " + databaseName + " failed: " + failure));
            refreshStatus(current);
            integrations.put(key(current.getRegion(), current.integrationId()), current);
            LOG.warnv("Integration {0} stopped replicating {1} into {2}: {3}", current.getIntegrationArn(),
                    sourceDatabase, databaseName, failure);
        }
    }

    /**
     * Whether the integration still exists and is still running the specific load identified by {@code loadId}
     * for {@code sourceDatabase} into {@code databaseName}.
     */
    public boolean isLoading(Integration integration, String sourceDatabase, String databaseName, String loadId) {
        return findCurrent(integration)
                .flatMap(current -> current.destinationFor(sourceDatabase))
                .filter(destination -> databaseName.equals(destination.getDatabaseName()))
                .filter(destination -> Objects.equals(loadId, destination.getLoadId()))
                .filter(destination -> Integration.Destination.STATE_SYNCING.equals(destination.getState()))
                .isPresent();
    }

    /**
     * Ends the initial load identified by {@code loadId}: {@code Synced}, or {@code Failed} with {@code failure}
     * as the integration's error. The integration is {@code active} again once no destination is syncing, or
     * {@code failed} when a load failed. A stale completion, whose load was replaced or already ended or whose
     * integration was deleted meanwhile, is ignored.
     */
    public void finishDestination(Integration integration, String sourceDatabase, String databaseName,
                                  String loadId, String failure) {
        synchronized (lock) {
            Optional<Integration> found = findCurrent(integration);
            Optional<Integration.Destination> destination = found
                    .flatMap(current -> current.destinationFor(sourceDatabase))
                    .filter(candidate -> databaseName.equals(candidate.getDatabaseName()))
                    .filter(candidate -> Objects.equals(loadId, candidate.getLoadId()))
                    .filter(candidate -> Integration.Destination.STATE_SYNCING.equals(candidate.getState()));
            if (destination.isEmpty()) {
                return;
            }
            Integration current = found.get();
            destination.get().setState(failure == null
                    ? Integration.Destination.STATE_SYNCED : Integration.Destination.STATE_FAILED);
            if (failure != null) {
                current.getErrors().add(new Integration.IntegrationError(INITIAL_LOAD_FAILED,
                        "The initial load of source database " + sourceDatabase + " into " + databaseName
                                + " failed: " + failure));
            }
            refreshStatus(current);
            integrations.put(key(current.getRegion(), current.integrationId()), current);
            LOG.infov("Integration {0} finished loading {1} into {2}: {3}", current.getIntegrationArn(),
                    sourceDatabase, databaseName, destination.get().getState());
        }
    }

    /**
     * Marks any destination left in {@code Creating} by a restart as {@code Failed} so the integration is
     * not stuck syncing and a subsequent {@code CREATE DATABASE ... FROM INTEGRATION} can retry or reuse
     * the database if it was created before the restart.
     */
    public void reconcileInterruptedCreations() {
        synchronized (lock) {
            for (Integration integration : integrationsWithState(Integration.Destination.STATE_CREATING)) {
                RequestScopes.runAs(integration.getAccountId(), integration.getRegion(), () ->
                        findCurrent(integration).ifPresent(current -> {
                            boolean changed = false;
                            for (Integration.Destination destination : current.getDestinations()) {
                                if (Integration.Destination.STATE_CREATING.equals(destination.getState())) {
                                    destination.setState(Integration.Destination.STATE_FAILED);
                                    current.getErrors().add(new Integration.IntegrationError(INITIAL_LOAD_FAILED,
                                            "The initial load of source database " + destination.getSourceDatabase()
                                                    + " into " + destination.getDatabaseName()
                                                    + " failed: interrupted while creating the destination database."));
                                    changed = true;
                                }
                            }
                            if (changed) {
                                refreshStatus(current);
                                integrations.put(key(current.getRegion(), current.integrationId()), current);
                                LOG.warnv("Marked interrupted Creating destination(s) of integration {0} as Failed",
                                        current.getIntegrationArn());
                            }
                        }));
            }
        }
    }

    /**
     * The integrations of every account with a destination still syncing, as a restart left them. Each
     * carries its account id, since no request scope is active at startup.
     */
    public List<Integration> integrationsWithLoadsInProgress() {
        return integrationsWithState(Integration.Destination.STATE_SYNCING);
    }

    /**
     * The integrations of every account with a loaded destination, whose changes a restart stopped replicating.
     * Each carries its account id, since no request scope is active at startup.
     */
    public List<Integration> integrationsWithLoadedDestinations() {
        return integrationsWithState(Integration.Destination.STATE_SYNCED);
    }

    private List<Integration> integrationsWithState(String state) {
        if (!(integrations instanceof AccountAwareStorageBackend<Integration> accountIntegrations)) {
            return integrations.scan(key -> key.startsWith(KEY_PREFIX)).stream()
                    .filter(integration -> hasDestinationInState(integration, state))
                    .map(Integration::new)
                    .toList();
        }
        return accountIntegrations.scanAllAccountEntries(key -> key.startsWith(KEY_PREFIX)).stream()
                .filter(entry -> hasDestinationInState(entry.value(), state))
                .map(entry -> {
                    Integration integration = new Integration(entry.value());
                    integration.setAccountId(entry.accountId());
                    return integration;
                })
                .toList();
    }

    private static boolean hasDestinationInState(Integration integration, String state) {
        return integration.getDestinations().stream()
                .anyMatch(destination -> state.equals(destination.getState()));
    }

    /** The stored copy of the integration, when it still exists under the same ARN. */
    private Optional<Integration> findCurrent(Integration integration) {
        return integrations.get(key(integration.getRegion(), integration.integrationId()))
                .filter(current -> integration.getIntegrationArn().equals(current.getIntegrationArn()))
                .map(Integration::new);
    }

    private static void refreshStatus(Integration integration) {
        List<Integration.Destination> destinations = integration.getDestinations();
        if (destinations.stream().anyMatch(destination ->
                Integration.Destination.STATE_SYNCING.equals(destination.getState())
                        || Integration.Destination.STATE_CREATING.equals(destination.getState()))) {
            integration.setStatus(Integration.STATUS_SYNCING);
        } else if (destinations.stream().anyMatch(destination ->
                Integration.Destination.STATE_FAILED.equals(destination.getState()))) {
            integration.setStatus(Integration.STATUS_FAILED);
        } else {
            integration.setStatus(Integration.STATUS_ACTIVE);
            integration.setErrors(List.of());
        }
    }

    private void requireSource(String sourceArn, String region, String accountId) {
        AwsArnUtils.Arn arn = parseArn(sourceArn, "SourceArn");
        String resource = arn.resource();
        int separator = resource.indexOf(':');
        if (!"rds".equals(arn.service()) || separator < 0) {
            throw invalidSource();
        }
        requireInScope(arn, region, accountId, "SourceArn");
        String type = resource.substring(0, separator);
        String identifier = resource.substring(separator + 1);
        if (identifier.isBlank()) {
            throw invalidSource();
        }
        switch (type) {
            case "cluster" -> requireClusterSource(rdsService.getDbCluster(identifier, region));
            case "db" -> requireInstanceSource(rdsService.getDbInstance(identifier, region));
            default -> throw invalidSource();
        }
    }

    private static AwsException invalidSource() {
        return new AwsException("InvalidParameterValue",
                "SourceArn must identify an RDS DB instance or an Aurora DB cluster.", 400);
    }

    private static void requireClusterSource(DbCluster cluster) {
        String engine = engineName(cluster.getEngineIdentifier(), cluster.getEngine(), AURORA_POSTGRESQL);
        if (!AURORA_POSTGRESQL.equals(engine)) {
            throw unsupportedSource("DB cluster " + cluster.getDbClusterIdentifier(), engine);
        }
    }

    private static void requireInstanceSource(DbInstance instance) {
        if (instance.getDbClusterIdentifier() != null && !instance.getDbClusterIdentifier().isBlank()) {
            throw new AwsException("InvalidParameterValue", "DB instance " + instance.getDbInstanceIdentifier()
                    + " is a member of DB cluster " + instance.getDbClusterIdentifier()
                    + ": use the DB cluster ARN as the SourceArn.", 400);
        }
        String engine = engineName(instance.getEngineIdentifier(), instance.getEngine(), POSTGRES);
        if (!POSTGRES.equals(engine)) {
            throw unsupportedSource("DB instance " + instance.getDbInstanceIdentifier(), engine);
        }
    }

    /**
     * The engine name the resource was created with. A record persisted before the name was kept
     * only has the enum, and a PostgreSQL one is taken to be the engine this source type allows.
     */
    private static String engineName(String engineIdentifier, DatabaseEngine engine, String postgresName) {
        if (engineIdentifier != null && !engineIdentifier.isBlank()) {
            return engineIdentifier.toLowerCase(Locale.ROOT);
        }
        if (engine == DatabaseEngine.POSTGRES) {
            return postgresName;
        }
        return engine != null ? engine.name().toLowerCase(Locale.ROOT) : "";
    }

    private static AwsException unsupportedSource(String source, String engine) {
        return new AwsException("InvalidParameterValue", source + " uses engine " + engine
                + ", which is not a supported zero-ETL source: use an aurora-postgresql DB cluster"
                + " or a postgres DB instance.", 400);
    }

    private void requireTarget(String targetArn, String region, String accountId) {
        AwsArnUtils.Arn arn = parseArn(targetArn, "TargetArn");
        boolean namespace = RedshiftZeroEtlEndpoints.isNamespaceArn(targetArn);
        boolean cluster = "redshift".equals(arn.service()) && arn.resource().startsWith("cluster:");
        if (!namespace && !cluster) {
            throw new AwsException("InvalidParameterValue",
                    "TargetArn must identify a Redshift Serverless namespace or a provisioned Redshift cluster.", 400);
        }
        requireInScope(arn, region, accountId, "TargetArn");
        boolean found = namespace
                ? redshiftServerlessService.findNamespaceByArn(targetArn, region).isPresent()
                : redshiftService.findClusterByArn(targetArn, region).isPresent();
        if (!found) {
            throw new AwsException("InvalidParameterValue",
                    "The integration target " + targetArn + " can't be found.", 400);
        }
    }

    private void requireInScope(AwsArnUtils.Arn arn, String region, String accountId, String parameter) {
        if (!region.equals(arn.region()) || !accountId.equals(arn.accountId())
                || !regionResolver.partitionForRegion(region).equals(arn.partition())) {
            throw new AwsException("InvalidParameterValue", parameter + " " + arn
                    + " must be in the integration's account and Region.", 400);
        }
    }

    private static AwsArnUtils.Arn parseArn(String value, String parameter) {
        if (value == null || value.isBlank()) {
            throw new AwsException("InvalidParameterValue", parameter + " is required.", 400);
        }
        try {
            return AwsArnUtils.parse(value);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidParameterValue", parameter + " " + value + " is not a valid ARN.", 400);
        }
    }

    private static void requireValidName(String integrationName) {
        if (integrationName == null || integrationName.isBlank()) {
            throw new AwsException("InvalidParameterValue", "IntegrationName is required.", 400);
        }
        if (integrationName.length() > NAME_MAX_LENGTH || !NAME.matcher(integrationName).matches()) {
            throw new AwsException("InvalidParameterValue", "The integration name " + integrationName
                    + " is not valid: use 1 to 63 letters, digits or single hyphens, starting with a letter"
                    + " and not ending with a hyphen.", 400);
        }
    }

    private static void requireValidDescription(String description) {
        if (description != null && description.length() > DESCRIPTION_MAX_LENGTH) {
            throw new AwsException("InvalidParameterValue",
                    "Description can be at most " + DESCRIPTION_MAX_LENGTH + " characters.", 400);
        }
    }

    /** An integration by its ARN, the id at the end of its ARN, or its name. */
    private Integration requireIntegration(String region, String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new AwsException("InvalidParameterValue", "IntegrationIdentifier is required.", 400);
        }
        List<Integration> all = regionIntegrations(region);
        return all.stream().filter(integration -> identifier.equals(integration.getIntegrationArn())).findFirst()
                .or(() -> all.stream().filter(integration -> identifier.equals(integration.integrationId())).findFirst())
                .or(() -> all.stream()
                        .filter(integration -> identifier.equals(integration.getIntegrationName())).findFirst())
                .orElseThrow(() -> new AwsException("IntegrationNotFoundFault",
                        "Integration " + identifier + " not found.", 404));
    }

    private Optional<Integration> findByName(String region, String integrationName) {
        return regionIntegrations(region).stream()
                .filter(integration -> integrationName.equals(integration.getIntegrationName()))
                .findFirst();
    }

    private Optional<Integration> findBySourceAndTarget(String region, String sourceArn, String targetArn) {
        return regionIntegrations(region).stream()
                .filter(integration -> sourceArn.equals(integration.getSourceArn())
                        && targetArn.equals(integration.getTargetArn()))
                .findFirst();
    }

    private List<Integration> regionIntegrations(String region) {
        return integrations.scan(key -> key.startsWith(KEY_PREFIX + region + "::")).stream()
                .sorted(Comparator.comparing(Integration::getCreateTime)
                        .thenComparing(Integration::getIntegrationArn))
                .toList();
    }

    private static boolean matchesFilters(Integration integration, Map<String, List<String>> filters) {
        for (Map.Entry<String, List<String>> filter : filters.entrySet()) {
            List<String> values = filter.getValue();
            if (values.isEmpty()) {
                continue;
            }
            boolean matched = switch (filter.getKey()) {
                case "integration-identifier" -> values.contains(integration.getIntegrationArn())
                        || values.contains(integration.integrationId());
                case "integration-name" -> values.contains(integration.getIntegrationName());
                case "source-arn" -> values.contains(integration.getSourceArn());
                case "target-arn" -> values.contains(integration.getTargetArn());
                case "status" -> values.contains(integration.getStatus());
                default -> throw new IllegalStateException("Unvalidated filter name: " + filter.getKey());
            };
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private static Integration withStatus(Integration integration, String status) {
        Integration reported = new Integration(integration);
        reported.setStatus(status);
        return reported;
    }

    private static AwsException alreadyExists(String integrationName) {
        return new AwsException("IntegrationAlreadyExistsFault",
                "An integration named " + integrationName + " already exists.", 400);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String effectiveRegion(String region) {
        return region != null && !region.isBlank() ? region : regionResolver.getDefaultRegion();
    }

    private static String key(String region, String integrationId) {
        return KEY_PREFIX + region + "::" + integrationId;
    }

    @Override
    public void clear() {
        integrations.clear();
        LOG.info("Cleared all RDS integrations");
    }
}
