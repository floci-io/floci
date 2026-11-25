package io.floci.aws.services.mwaa;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Base64Url;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.StripedLocks;
import io.floci.aws.core.common.TagHandler;
import io.floci.aws.core.common.docker.PortAllocator;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ec2.VpcDependencyProvider;
import io.floci.aws.services.mwaa.model.CreateEnvironmentRequest;
import io.floci.aws.services.mwaa.model.Environment;
import io.floci.aws.services.mwaa.model.EnvironmentRuntime;
import io.floci.aws.services.mwaa.model.EnvironmentStatus;
import io.floci.aws.services.mwaa.model.NetworkConfiguration;
import io.floci.aws.services.mwaa.model.UpdateEnvironmentRequest;
import io.floci.aws.services.mwaa.proxy.MwaaProxyManager;
import io.floci.aws.services.mwaa.proxy.MwaaWebProxy;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ApplicationScoped
public class MwaaService implements TagHandler, VpcDependencyProvider {

    private static final Logger LOG = Logger.getLogger(MwaaService.class);

    /**
     * Shape {@code MwaaEnvironmentManager.pythonTagFor} requires: at least a numeric
     * {@code major.minor}, an optional {@code .patch}. {@code supported-versions} is
     * operator-configurable, so a malformed entry (e.g. a stray {@code latest}) must be rejected
     * here as a clean {@code ValidationException} rather than reaching {@code pythonTagFor} and
     * surfacing as an internal {@code NumberFormatException} well after Postgres has already been
     * created for the environment.
     */
    private static final Pattern AIRFLOW_VERSION_PATTERN = Pattern.compile("\\d+\\.\\d+(\\.\\d+)?");

    /** S3 errors that mean the requirements object is not there (yet), rather than a fault. */
    private static final Set<String> MISSING_OBJECT_ERRORS = Set.of("NoSuchKey", "NoSuchBucket");
    private static final int REQUIREMENTS_INSTALL_THREADS = 2;

    /** Amazon MWAA's web login token "expires after 60 seconds" (Apache Airflow REST API guide). */
    static final Duration WEB_LOGIN_TOKEN_LIFETIME = Duration.ofSeconds(60);

    /** The states of an environment whose containers the startup restore brings back. */
    private static final Set<EnvironmentStatus> RESTORED_STATUSES = Set.of(EnvironmentStatus.CREATING,
            EnvironmentStatus.AVAILABLE, EnvironmentStatus.MAINTENANCE, EnvironmentStatus.UNAVAILABLE);

    private final StorageBackend<String, Environment> storage;
    /** The secrets each environment's metadata database was set up with, for its restart. */
    private final AccountAwareStorageBackend<EnvironmentRuntime> runtimeStorage;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final MwaaEnvironmentManager environmentManager;
    private final MwaaProxyManager proxyManager;
    private final PortAllocator portAllocator;
    private final S3Service s3Service;
    /** Runs DAG-sync reinstalls, which can take minutes, off the poller that syncs every environment. */
    private final Executor requirementsInstaller;
    private final Clock clock;

    private final ScheduledExecutorService readinessPoller = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService dagSyncPoller = Executors.newSingleThreadScheduledExecutor();

    /** Opaque CLI tokens minted by CreateCliToken, per environment. In-memory only — not persisted. */
    private final Map<String, Set<String>> cliTokensByEnvironment = new ConcurrentHashMap<>();

    /** CreateWebLoginToken tokens and the instant each one expires, per environment. In-memory only. */
    private final Map<String, Map<String, Instant>> webTokensByEnvironment = new ConcurrentHashMap<>();

    /** Last-synced DAG file state (S3 key relative to DagS3Path -> ETag), per environment. */
    private final Map<String, Map<String, String>> dagSyncState = new ConcurrentHashMap<>();

    /** Last-installed requirements.txt ETag, per environment. */
    private final Map<String, String> requirementsEtagByEnvironment = new ConcurrentHashMap<>();

    /** Environments whose bootstrap install the first DAG-sync pass still has to check. */
    private final Set<String> bootstrapInstallsToCheck = ConcurrentHashMap.newKeySet();

    /** Environments with a reinstall running, so a slow one is never started twice. */
    private final Set<String> requirementsInstallsInFlight = ConcurrentHashMap.newKeySet();

    /**
     * Environments the startup restore is bringing back, by account and storage key: the readiness poller leaves them
     * alone, and DeleteEnvironment leaves their removal to the restore.
     */
    private final Set<String> restoringEnvironments = ConcurrentHashMap.newKeySet();

    /** Orders the restore's reads and writes of an environment with DeleteEnvironment's. */
    private final StripedLocks storedEnvironmentLocks = new StripedLocks(256);

    @Inject
    public MwaaService(StorageFactory storageFactory, EmulatorConfig config, RegionResolver regionResolver,
                       MwaaEnvironmentManager environmentManager, MwaaProxyManager proxyManager,
                       PortAllocator portAllocator, S3Service s3Service) {
        this(storageFactory, config, regionResolver, environmentManager, proxyManager, portAllocator, s3Service,
                Executors.newFixedThreadPool(REQUIREMENTS_INSTALL_THREADS, runnable -> {
                    Thread thread = new Thread(runnable, "mwaa-requirements-install");
                    thread.setDaemon(true);
                    return thread;
                }),
                Clock.systemUTC());
    }

    MwaaService(StorageFactory storageFactory, EmulatorConfig config, RegionResolver regionResolver,
                MwaaEnvironmentManager environmentManager, MwaaProxyManager proxyManager,
                PortAllocator portAllocator, S3Service s3Service, Executor requirementsInstaller,
                Clock clock) {
        this.storage = storageFactory.create("mwaa", "mwaa-environments.json",
                new TypeReference<Map<String, Environment>>() {
                });
        this.runtimeStorage = storageFactory.create("mwaa", "mwaa-environment-runtime.json",
                new TypeReference<Map<String, EnvironmentRuntime>>() {
                });
        this.config = config;
        this.regionResolver = regionResolver;
        this.environmentManager = environmentManager;
        this.proxyManager = proxyManager;
        this.portAllocator = portAllocator;
        this.s3Service = s3Service;
        this.requirementsInstaller = requirementsInstaller;
        this.clock = clock;
    }

    @PostConstruct
    public void init() {
        if (!config.services().mwaa().mock()) {
            startReadinessPoller();
            startDagSyncPoller();
            for (Environment environment : allEnvironments()) {
                environmentManager.reclaimVpcAddress(environment);
            }
        }
    }

    /**
     * Amazon MWAA keeps network interfaces in each subnet an environment names, so DeleteSubnet refuses one an
     * environment still uses, as it does here. A plain read of the caller's environments and of the addresses live
     * Airflow containers hold, which takes no lock of this service.
     */
    @Override
    public boolean hasSubnetDependency(String region, String subnetId) {
        String accountId = regionResolver.getAccountId();
        return holdsLiveAddresses() && environmentManager.holdsSubnet(accountId, region, subnetId)
                || environmentsOf(accountId, region).stream()
                        .filter(environment -> environment.getStatus() != EnvironmentStatus.DELETED)
                        .map(Environment::getNetworkConfiguration)
                        .anyMatch(network -> network != null && network.getSubnetIds() != null
                                && network.getSubnetIds().contains(subnetId));
    }

    /** A VPC is only deleted once its subnets are, which {@link #hasSubnetDependency} guards; this adds live leases. */
    @Override
    public boolean hasVpcDependency(String region, String vpcId) {
        return holdsLiveAddresses() && environmentManager.holdsVpc(regionResolver.getAccountId(), region, vpcId);
    }

    /** Mock mode runs no containers, so no Airflow container holds a VPC address there. */
    private boolean holdsLiveAddresses() {
        return !config.services().mwaa().mock();
    }

    private List<Environment> environmentsOf(String accountId, String region) {
        List<Environment> environments = storage instanceof AccountAwareStorageBackend<Environment> aware
                ? aware.scanForAccount(accountId, k -> true)
                : storage.scan(k -> true);
        return environments.stream().filter(environment -> region.equals(environmentRegion(environment))).toList();
    }

    @PreDestroy
    public void shutdown() {
        readinessPoller.shutdownNow();
        dagSyncPoller.shutdownNow();
        if (requirementsInstaller instanceof ExecutorService installer) {
            installer.shutdownNow();
        }
        if (config.services().mwaa().mock()) {
            return;
        }
        // First, so a restore still starting containers stops, and a start it overtakes removes its own.
        environmentManager.markShuttingDown();
        if (config.services().mwaa().keepRunningOnShutdown()) {
            return;
        }
        boolean keepVolumes = keepsEnvironmentsAcrossRestarts();
        for (Environment environment : allEnvironments()) {
            proxyManager.stopProxy(environmentIdentity(environment));
            if (keepVolumes) {
                // The next start brings the environment back on them, with its DAG runs, connections and variables.
                // By name too: an environment the startup restore has not reached yet holds no container IDs.
                environmentManager.stopContainers(environment);
            } else {
                environmentManager.stopEnvironment(environment);
            }
        }
    }

    /** Whether the environments are stored to come back after Floci restarts, rather than kept in memory alone. */
    private boolean keepsEnvironmentsAcrossRestarts() {
        return !"memory".equals(config.storage().mode());
    }

    /**
     * Brings the persisted environments' containers back after Floci restarts. Shutdown removes them, unless
     * {@code keep-running-on-shutdown} keeps them, so an environment read back from storage reported itself AVAILABLE
     * with nothing behind its webserver URL. Each environment reports {@code MAINTENANCE} at once, one still being
     * created {@code CREATING}, and its containers come back in the background, so Floci does not wait for them to
     * report itself ready; the readiness poller leaves an environment alone while its restore runs, and reports it
     * AVAILABLE once Airflow answers.
     *
     * <p>Containers that outlived Floci, kept running or left by a Floci that was killed, are taken over. Otherwise the
     * environment gets new ones on the volumes shutdown kept, with the secrets its metadata database was set up with,
     * so its DAG runs, connections and variables are back. One stored before Floci kept those secrets gets them back
     * from a container left under its name; with none left, its volumes went at shutdown and it gets new ones and
     * starts on empty volumes, or, when they are still there, is left as it is, unable to open its database. When
     * Docker cannot say which containers or volumes are left, the environment's restore fails, removing nothing. Its
     * web proxy comes back on the port its webserver URL names while that port is free. One whose containers do not
     * start is {@code UNAVAILABLE}, or {@code CREATE_FAILED} while it was being created, keeping its volumes; an
     * {@code UNAVAILABLE} one is tried again at the next start.
     * One deleted during its restore is {@code DELETING} until its containers have started and been removed, and is
     * gone then; one still {@code DELETING} at the next start is removed then. Every environment is tried, and the
     * ones that could not be restored fail the returned future together. A shutdown during the restore leaves the
     * environments not started yet as they are, for the next start.
     */
    public CompletableFuture<Void> restorePersistedRuntime() {
        if (config.services().mwaa().mock() || !(storage instanceof AccountAwareStorageBackend<Environment> aware)) {
            return CompletableFuture.completedFuture(null);
        }
        List<RestoringEnvironment> pending = new ArrayList<>();
        for (StoredEnvironment stored : storedEnvironments(aware)) {
            RestoringEnvironment restoring = prepareRestore(stored);
            if (restoring != null) {
                pending.add(restoring);
            }
        }
        return CompletableFuture.runAsync(() -> {
            RuntimeException failure = null;
            for (RestoringEnvironment restoring : pending) {
                if (environmentManager.isShuttingDown()) {
                    // Environments not reached yet stay as they are, which the next start restores.
                    break;
                }
                Environment environment = restoring.environment();
                try {
                    // Its startup script and requirements are read from its own account's S3.
                    RequestScopes.runAs(restoring.accountId(), restoring.region(), () -> restoreEnvironment(restoring));
                } catch (MwaaEnvironmentManager.ShuttingDownException e) {
                    LOG.debugv("MWAA environment {0} not restored: {1}", environment.getName(), e.getMessage());
                } catch (RuntimeException e) {
                    RuntimeException restoreFailure = new IllegalStateException("Could not restore MWAA environment "
                            + environment.getName() + " of account " + restoring.accountId(), e);
                    if (failure == null) {
                        failure = restoreFailure;
                    } else {
                        failure.addSuppressed(restoreFailure);
                    }
                } finally {
                    endRestore(restoring);
                }
            }
            if (failure != null) {
                throw failure;
            }
        });
    }

    /** Every stored environment once, by the account, Region and name it belongs to, legacy keys included. */
    private Set<StoredEnvironment> storedEnvironments(AccountAwareStorageBackend<Environment> aware) {
        Set<StoredEnvironment> stored = new LinkedHashSet<>();
        for (Environment environment : aware.scanAllAccounts()) {
            stored.add(new StoredEnvironment(environmentAccount(environment), environmentRegion(environment),
                    environment.getName()));
        }
        return stored;
    }

    /**
     * Marks an environment the restore brings back, under its lock, and holds its proxy port for it; null for one it
     * leaves as it is.
     */
    private RestoringEnvironment prepareRestore(StoredEnvironment stored) {
        String key = environmentKey(stored.region(), stored.name());
        synchronized (storedEnvironmentLock(stored.accountId(), key)) {
            // Read as DescribeEnvironment reads it, which moves a record stored under a legacy key onto its key.
            Environment environment = getStoredEnvironment(stored.accountId(), stored.region(), stored.name())
                    .orElse(null);
            if (environment == null) {
                return null;
            }
            EnvironmentStatus status = environment.getStatus();
            boolean deleting = status == EnvironmentStatus.DELETING;
            if (!deleting && !RESTORED_STATUSES.contains(status)) {
                return null;
            }
            environment.setAccountId(stored.accountId());
            RestoringEnvironment restoring = new RestoringEnvironment(stored.accountId(), key, stored.region(),
                    environment, status == EnvironmentStatus.CREATING);
            restoringEnvironments.add(restoring.lockKey());
            // One still DELETING was deleted during a restore Floci did not live to finish, or during its shutdown:
            // the delete stands.
            if (!deleting) {
                runtimeStorage.getForAccount(stored.accountId(), key)
                        .filter(runtime -> Objects.equals(runtime.getEnvironmentCreatedAt(),
                                environment.getCreatedAt()))
                        .ifPresent(runtime -> {
                            environment.setDbPassword(runtime.getDbPassword());
                            environment.setFernetKey(runtime.getFernetKey());
                            environment.setAirflowAdminPassword(runtime.getAirflowAdminPassword());
                        });
                int proxyPort = recordedProxyPort(environment);
                if (proxyPort > 0) {
                    // Held from now on, so an environment created during the restore does not take it.
                    portAllocator.markReserved(proxyPort);
                }
                environment.setProxyPort(proxyPort);
                if (!restoring.creating()) {
                    environment.setStatus(EnvironmentStatus.MAINTENANCE);
                }
                // Its first DAG sync once it is back compares the DAG volume with S3, as for a new environment.
                dagSyncState.remove(environmentIdentity(environment));
                putEnvironment(environment);
            }
            return restoring;
        }
    }

    /** The proxy port the environment's webserver URL names, or 0 for a URL that names none of the proxy range. */
    private int recordedProxyPort(Environment environment) {
        if (environment.getWebserverUrl() == null) {
            return 0;
        }
        int port;
        try {
            port = URI.create(environment.getWebserverUrl()).getPort();
        } catch (IllegalArgumentException e) {
            return 0;
        }
        return port >= config.services().mwaa().proxyBasePort() && port <= config.services().mwaa().proxyMaxPort()
                ? port : 0;
    }

    private void restoreEnvironment(RestoringEnvironment restoring) {
        Environment environment = restoring.environment();
        Object lock = storedEnvironmentLock(restoring.accountId(), restoring.key());
        EnvironmentStatus stored;
        synchronized (lock) {
            stored = storedStatus(restoring);
        }
        if (stored == null) {
            portAllocator.release(environment.getProxyPort());
            return;
        }
        if (stored == EnvironmentStatus.DELETING) {
            finishDelete(restoring);
            return;
        }
        try {
            startContainers(restoring);
        } catch (MwaaEnvironmentManager.ShuttingDownException e) {
            throw e;
        } catch (RuntimeException e) {
            failRestore(restoring);
            throw e;
        }
        RuntimeException proxyFailure = null;
        EnvironmentStatus storedNow;
        synchronized (lock) {
            storedNow = storedStatus(restoring);
            if (storedNow != null && storedNow != EnvironmentStatus.DELETING) {
                try {
                    startRestoredProxy(environment);
                    // Its restore ends here: from now on the poller reports it AVAILABLE, and a delete removes it.
                    restoringEnvironments.remove(restoring.lockKey());
                    putEnvironment(environment);
                    return;
                } catch (RuntimeException e) {
                    proxyFailure = e;
                }
            }
        }
        if (proxyFailure != null) {
            failRestore(restoring);
            throw proxyFailure;
        }
        if (storedNow == null) {
            // Its record went, or another environment took its name, with no delete of its own: only the containers
            // this restore kept or started go, by their IDs, leaving the name and the volumes to whoever has them.
            environmentManager.stopOwnContainers(environment);
            portAllocator.release(environment.getProxyPort());
            return;
        }
        // Deleted while its containers started: the delete finishes now they are there to remove.
        finishDelete(restoring);
    }

    /**
     * Takes over the environment's running containers, or starts new ones on the volumes and with the secrets it kept,
     * as CreateEnvironment starts them: the startup script runs and requirements.txt is installed before Airflow.
     */
    private void startContainers(RestoringEnvironment restoring) {
        Environment environment = restoring.environment();
        if (environmentManager.adoptRunningEnvironment(environment)) {
            keepSecrets(restoring);
            return;
        }
        if (!MwaaEnvironmentManager.hasSecrets(environment)) {
            // Stored before Floci kept the secrets: a container left under its name has them, and with none left
            // its volumes went at the shutdown then, so it gets new ones. Either way they are kept before anything
            // starts, so a start that fails part way, with that container gone, is tried again with them.
            if (!environmentManager.recoverSecrets(environment)) {
                environmentManager.giveNewSecrets(environment);
            }
            keepSecrets(restoring);
        }
        byte[] startupScriptContent = fetchStartupScript(environment);
        S3Object requirements = fetchRequirements(environment);
        if (requirements != null) {
            requirementsEtagByEnvironment.put(environmentIdentity(environment), requirements.getETag());
            bootstrapInstallsToCheck.add(environmentIdentity(environment));
        }
        byte[] requirementsContent = requirements != null ? requirements.getData() : null;
        environmentManager.restartEnvironment(environment, environment.getAirflowVersion(), startupScriptContent,
                requirementsContent);
    }

    /** Keeps the secrets the restore found or gave the environment, while its record is still the one it restores. */
    private void keepSecrets(RestoringEnvironment restoring) {
        synchronized (storedEnvironmentLock(restoring.accountId(), restoring.key())) {
            if (storedStatus(restoring) != null) {
                putRuntime(restoring.environment());
            }
        }
    }

    /**
     * Starts the restored environment's web proxy on the port its webserver URL names, or, when that port is taken or
     * the URL names none, on a new one its URL then names.
     */
    private void startRestoredProxy(Environment environment) {
        int recorded = environment.getProxyPort();
        if (recorded > 0) {
            try {
                environment.setWebserverUrl(buildWebserverUrl(recorded));
                startWebProxy(environment);
                return;
            } catch (RuntimeException e) {
                LOG.warnv("MWAA environment {0} gets a new webserver URL: its web proxy cannot listen on port {1} "
                        + "again: {2}", environment.getName(), String.valueOf(recorded), e.getMessage());
                portAllocator.release(recorded);
                environment.setProxyPort(0);
            }
        }
        int proxyPort = portAllocator.allocateForInProcessListener(
                config.services().mwaa().proxyBasePort(), config.services().mwaa().proxyMaxPort());
        environment.setProxyPort(proxyPort);
        environment.setWebserverUrl(buildWebserverUrl(proxyPort));
        try {
            startWebProxy(environment);
        } catch (RuntimeException e) {
            portAllocator.release(proxyPort);
            environment.setProxyPort(0);
            throw e;
        }
    }

    /**
     * Ends a restore whose containers did not start: the ones that did go, the volumes stay, and the environment is
     * {@code UNAVAILABLE}, for the next start to try again, or {@code CREATE_FAILED} while it was being created. One
     * deleted meanwhile is removed.
     */
    private void failRestore(RestoringEnvironment restoring) {
        Environment environment = restoring.environment();
        environmentManager.stopOwnContainers(environment);
        EnvironmentStatus storedNow;
        synchronized (storedEnvironmentLock(restoring.accountId(), restoring.key())) {
            storedNow = storedStatus(restoring);
            if (storedNow != null && storedNow != EnvironmentStatus.DELETING) {
                restoringEnvironments.remove(restoring.lockKey());
                environment.setStatus(restoring.creating()
                        ? EnvironmentStatus.CREATE_FAILED : EnvironmentStatus.UNAVAILABLE);
                putEnvironment(environment);
            }
        }
        if (storedNow == EnvironmentStatus.DELETING) {
            finishDelete(restoring);
        }
    }

    /** Finishes the delete of an environment deleted during its restore, or before it: as DeleteEnvironment does. */
    private void finishDelete(RestoringEnvironment restoring) {
        Environment environment = restoring.environment();
        removeRuntime(environment);
        synchronized (storedEnvironmentLock(restoring.accountId(), restoring.key())) {
            restoringEnvironments.remove(restoring.lockKey());
            if (storedStatus(restoring) == EnvironmentStatus.DELETING) {
                deleteEnvironment(environment);
            }
        }
        LOG.infov("Deleted MWAA environment {0}", environment.getName());
    }

    /**
     * Ends an environment's restore, if it has not ended already: the poller and DeleteEnvironment treat the
     * environment as any other again.
     */
    private void endRestore(RestoringEnvironment restoring) {
        synchronized (storedEnvironmentLock(restoring.accountId(), restoring.key())) {
            restoringEnvironments.remove(restoring.lockKey());
        }
    }

    /** The stored environment's status, or null when it is gone or another environment was created under its name. */
    private EnvironmentStatus storedStatus(RestoringEnvironment restoring) {
        return storage instanceof AccountAwareStorageBackend<Environment> aware
                ? aware.getForAccount(restoring.accountId(), restoring.key())
                        .filter(current -> Objects.equals(current.getCreatedAt(),
                                restoring.environment().getCreatedAt()))
                        .map(Environment::getStatus)
                        .orElse(null)
                : null;
    }

    private Object storedEnvironmentLock(String accountId, String key) {
        return storedEnvironmentLocks.lockFor(restoringKey(accountId, key));
    }

    private String restoringKey(Environment environment) {
        return restoringKey(environmentAccount(environment),
                environmentKey(environmentRegion(environment), environment.getName()));
    }

    private static String restoringKey(String accountId, String key) {
        return accountId + "/" + key;
    }

    /** A stored environment, by the account, Region and name it belongs to. */
    private record StoredEnvironment(String accountId, String region, String name) {
    }

    /**
     * An environment the restore brings back, with the account, storage key and Region it is stored under, and
     * whether it was still being created.
     */
    private record RestoringEnvironment(String accountId, String key, String region, Environment environment,
                                        boolean creating) {
        String lockKey() {
            return restoringKey(accountId, key);
        }
    }

    public Environment createEnvironment(String name, CreateEnvironmentRequest request) {
        if (name == null || name.isBlank()) {
            throw new AwsException("ValidationException", "Environment name is required", 400);
        }
        String accountId = regionResolver.getAccountId();
        String region = regionResolver.getRegion();
        if (getStoredEnvironment(accountId, region, name).isPresent()) {
            // CreateEnvironment's botocore model declares only ServiceUnavailableException,
            // ValidationException, and InternalServerException — no "already exists" shape — so an
            // SDK client can't map a ResourceAlreadyExistsException here.
            throw new AwsException("ValidationException",
                    "Environment already exists: " + name, 400);
        }

        String version = resolveAirflowVersion(request.getAirflowVersion());
        validateAirflowConfigurationOptions(request.getAirflowConfigurationOptions());

        String arn = AwsArnUtils.Arn.of("airflow", region, accountId, "environment/" + name).toString();

        Environment environment = new Environment();
        environment.setName(name);
        environment.setArn(arn);
        environment.setAccountId(accountId);
        environment.setCreatedAt(Instant.now());
        environment.setStatus(EnvironmentStatus.CREATING);
        environment.setExecutionRoleArn(request.getExecutionRoleArn());
        environment.setAirflowVersion(version);
        environment.setSourceBucketArn(request.getSourceBucketArn());
        environment.setDagS3Path(request.getDagS3Path());
        environment.setPluginsS3Path(request.getPluginsS3Path());
        environment.setRequirementsS3Path(request.getRequirementsS3Path());
        environment.setStartupScriptS3Path(request.getStartupScriptS3Path());
        environment.setNetworkConfiguration(request.getNetworkConfiguration());
        environment.setLoggingConfiguration(request.getLoggingConfiguration());
        environment.setAirflowConfigurationOptions(request.getAirflowConfigurationOptions() != null
                ? new HashMap<>(request.getAirflowConfigurationOptions()) : new HashMap<>());
        environment.setEnvironmentClass(request.getEnvironmentClass() != null ? request.getEnvironmentClass() : "mw1.small");
        environment.setWebserverAccessMode(request.getWebserverAccessMode() != null
                ? request.getWebserverAccessMode() : "PUBLIC_ONLY");
        environment.setMaxWorkers(request.getMaxWorkers() != null ? request.getMaxWorkers() : 10);
        environment.setMinWorkers(request.getMinWorkers() != null ? request.getMinWorkers() : 1);
        environment.setSchedulers(request.getSchedulers() != null ? request.getSchedulers() : 2);
        environment.setTags(request.getTags() != null ? new HashMap<>(request.getTags()) : new HashMap<>());

        if (config.services().mwaa().mock()) {
            environment.setStatus(EnvironmentStatus.AVAILABLE);
            environment.setWebserverUrl(buildWebserverUrl(config.services().mwaa().proxyBasePort()));
        } else {
            // proxyPort/provisioned tracked outside the try so the finally block below knows exactly
            // how far setup got, mirroring NeptuneService.createDbCluster's rollback shape: a partial
            // failure must never leak the reserved port or leave orphaned Postgres/Airflow containers
            // behind — regardless of which of the three steps below actually failed.
            int proxyPort = -1;
            boolean provisioned = false;
            try {
                byte[] startupScriptContent = fetchStartupScript(environment);
                S3Object requirements = fetchRequirements(environment);
                if (requirements != null) {
                    // The container's bootstrap installs this file, so the DAG-sync pass must not
                    // install it again unless that install failed. Recorded before the start, so no
                    // sync can run in between.
                    requirementsEtagByEnvironment.put(environmentIdentity(environment), requirements.getETag());
                    bootstrapInstallsToCheck.add(environmentIdentity(environment));
                }
                environmentManager.startEnvironment(environment, version, startupScriptContent,
                        requirements != null ? requirements.getData() : null);
                proxyPort = portAllocator.allocateForInProcessListener(
                        config.services().mwaa().proxyBasePort(), config.services().mwaa().proxyMaxPort());
                environment.setProxyPort(proxyPort);
                environment.setWebserverUrl(buildWebserverUrl(proxyPort));
                startWebProxy(environment);
                putRuntime(environment);
                provisioned = true;
            } catch (Exception e) {
                LOG.errorv(e, "Failed to start MWAA environment {0}", name);
                environment.setStatus(EnvironmentStatus.CREATE_FAILED);
            } finally {
                if (!provisioned) {
                    rollbackFailedCreate(environment, proxyPort);
                }
            }
        }

        putEnvironment(environment);
        return environment;
    }

    /** Starts the web proxy that serves the environment's webserver URL, on the port the environment holds. */
    private void startWebProxy(Environment environment) {
        String airflowContainerId = environment.getAirflowContainerId();
        String version = environment.getAirflowVersion();
        // The proxy reads this environment's own token map, not whatever the name maps to later: a token
        // issued while an earlier environment of this name was being deleted lands in that one's map, not
        // in this.
        Map<String, Instant> webTokens = new ConcurrentHashMap<>();
        webTokensByEnvironment.put(environmentIdentity(environment), webTokens);
        proxyManager.startProxy(environmentIdentity(environment), environment.getProxyPort(),
                environment.getAirflowInternalHost(), environment.getAirflowInternalPort(),
                this::isValidCliToken,
                cliCommand -> environmentManager.runAirflowCli(airflowContainerId, version, cliCommand),
                (proxyIdentity, token) -> isUnexpired(webTokens, token),
                new MwaaWebProxy.AirflowAccount(MwaaEnvironmentManager.AIRFLOW_ADMIN_USER,
                        environment.getAirflowAdminPassword()));
    }

    /**
     * Best-effort teardown for a create that didn't fully provision — stops the proxy (a no-op if it
     * never started, since {@link MwaaProxyManager#stopProxy} just finds nothing registered),
     * stops any containers {@code environmentManager.startEnvironment} did manage to start before
     * failing ({@link MwaaEnvironmentManager#stopEnvironment} null-checks each container id, so it's
     * safe even when only Postgres — or nothing — started), and always releases the reserved proxy
     * port. Mirrors {@code NeptuneService.rollbackDbCluster}.
     */
    private void rollbackFailedCreate(Environment environment, int proxyPort) {
        try {
            proxyManager.stopProxy(environmentIdentity(environment));
        } catch (Exception e) {
            LOG.warnv("Error stopping proxy while rolling back failed MWAA environment {0}: {1}",
                    environment.getName(), e.getMessage());
        }
        try {
            environmentManager.stopEnvironment(environment);
        } catch (Exception e) {
            LOG.warnv("Error stopping containers while rolling back failed MWAA environment {0}: {1}",
                    environment.getName(), e.getMessage());
        } finally {
            if (proxyPort >= 0) {
                portAllocator.release(proxyPort);
            }
        }
    }

    public Environment getEnvironment(String name) {
        return getStoredEnvironment(regionResolver.getAccountId(), regionResolver.getRegion(), name)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No environment found for name: " + name, 404));
    }

    public List<String> listEnvironments() {
        String accountId = regionResolver.getAccountId();
        String region = regionResolver.getRegion();
        if (storage instanceof AccountAwareStorageBackend<Environment> aware) {
            migrateLegacyEnvironments(aware, accountId, region);
        }
        List<Environment> environments = storage instanceof AccountAwareStorageBackend<Environment> aware
                ? aware.scanForAccount(accountId, k -> true)
                : storage.scan(k -> true);
        return environments.stream()
                .filter(environment -> region.equals(environmentRegion(environment)))
                .map(Environment::getName)
                .collect(Collectors.toList());
    }

    public Environment updateEnvironment(String name, UpdateEnvironmentRequest request) {
        Environment environment = getEnvironment(name);

        if (request.getAirflowVersion() != null && !request.getAirflowVersion().equals(environment.getAirflowVersion())) {
            throw new AwsException("ValidationException",
                    "Updating AirflowVersion is not supported; it would require recreating the "
                            + "environment's Airflow container. Delete and recreate the environment instead.", 400);
        }
        if (request.getAirflowConfigurationOptions() != null) {
            throw new AwsException("ValidationException",
                    "Updating AirflowConfigurationOptions is not supported; it would require "
                            + "recreating the environment's Airflow container.", 400);
        }
        if (request.getRequirementsS3Path() != null
                && !request.getRequirementsS3Path().equals(environment.getRequirementsS3Path())) {
            throw new AwsException("ValidationException",
                    "Changing RequirementsS3Path via UpdateEnvironment is not supported; requirements "
                            + "are re-installed automatically on the next DAG-sync pass when the file's "
                            + "content changes at its existing path.", 400);
        }
        if (request.getStartupScriptS3Path() != null
                && !request.getStartupScriptS3Path().equals(environment.getStartupScriptS3Path())) {
            throw new AwsException("ValidationException",
                    "Updating StartupScriptS3Path is not supported; it only runs once, at container "
                            + "creation, so it would require recreating the environment's Airflow "
                            + "container. Delete and recreate the environment instead.", 400);
        }

        if (request.getExecutionRoleArn() != null) {
            environment.setExecutionRoleArn(request.getExecutionRoleArn());
        }
        if (request.getSourceBucketArn() != null) {
            environment.setSourceBucketArn(request.getSourceBucketArn());
        }
        if (request.getDagS3Path() != null) {
            environment.setDagS3Path(request.getDagS3Path());
        }
        if (request.getPluginsS3Path() != null) {
            environment.setPluginsS3Path(request.getPluginsS3Path());
        }
        if (request.getNetworkConfiguration() != null) {
            // UpdateEnvironment's NetworkConfiguration holds only SecurityGroupIds: an environment's subnets, and so
            // its VPC, are fixed at creation, as Amazon MWAA has them.
            NetworkConfiguration network = new NetworkConfiguration();
            network.setSubnetIds(environment.getNetworkConfiguration() != null
                    ? environment.getNetworkConfiguration().getSubnetIds() : null);
            network.setSecurityGroupIds(request.getNetworkConfiguration().getSecurityGroupIds());
            environment.setNetworkConfiguration(network);
        }
        if (request.getLoggingConfiguration() != null) {
            environment.setLoggingConfiguration(request.getLoggingConfiguration());
        }
        if (request.getEnvironmentClass() != null) {
            environment.setEnvironmentClass(request.getEnvironmentClass());
        }
        if (request.getWebserverAccessMode() != null) {
            environment.setWebserverAccessMode(request.getWebserverAccessMode());
        }
        if (request.getMaxWorkers() != null) {
            environment.setMaxWorkers(request.getMaxWorkers());
        }
        if (request.getMinWorkers() != null) {
            environment.setMinWorkers(request.getMinWorkers());
        }
        if (request.getSchedulers() != null) {
            environment.setSchedulers(request.getSchedulers());
        }

        environment.setLastUpdate(new Environment.LastUpdate("SUCCESS", Instant.now()));
        putEnvironment(environment);
        return environment;
    }

    public Environment deleteEnvironment(String name) {
        Environment environment = getEnvironment(name);
        String accountId = regionResolver.getAccountId();
        String key = environmentKey(regionResolver.getRegion(), name);
        synchronized (storedEnvironmentLock(accountId, key)) {
            environment.setStatus(EnvironmentStatus.DELETING);
            if (restoringEnvironments.contains(restoringKey(accountId, key))) {
                // The startup restore is starting its containers, and finishes the delete once that start is through.
                // Until then the environment reads DELETING, as AWS reports one being deleted, and its name stays
                // taken.
                putEnvironment(environment);
                LOG.infov("Deleting MWAA environment {0} once its restore is through", name);
                return environment;
            }
        }
        removeRuntime(environment);
        deleteEnvironment(environment);
        return environment;
    }

    /** Removes what runs for the environment: its web proxy, containers and volumes, and what Floci holds for them. */
    private void removeRuntime(Environment environment) {
        if (!config.services().mwaa().mock()) {
            proxyManager.stopProxy(environmentIdentity(environment));
            environmentManager.stopEnvironment(environment);
            // Mirrors how Lambda/MSK/EC2/ECR release their allocated ports on cleanup — otherwise
            // repeated create/delete cycles exhaust the configured proxy port range even though no
            // MWAA proxy is actually running anymore.
            portAllocator.release(environment.getProxyPort());
        }
        String environmentIdentity = environmentIdentity(environment);
        cliTokensByEnvironment.remove(environmentIdentity);
        webTokensByEnvironment.remove(environmentIdentity);
        dagSyncState.remove(environmentIdentity);
        requirementsEtagByEnvironment.remove(environmentIdentity);
        bootstrapInstallsToCheck.remove(environmentIdentity);
    }

    public Map<String, Object> createWebLoginToken(String name) {
        Environment environment = getEnvironment(name);
        String token = generateToken();
        Map<String, Instant> tokens = webTokensByEnvironment.computeIfAbsent(environmentIdentity(environment),
                k -> new ConcurrentHashMap<>());
        Instant now = clock.instant();
        tokens.values().removeIf(expiresAt -> !now.isBefore(expiresAt));
        tokens.put(token, now.plus(WEB_LOGIN_TOKEN_LIFETIME));
        return Map.of(
                "WebToken", token,
                "WebServerHostname", hostnameFromUrl(environment.getWebserverUrl()),
                "IamIdentity", "assumed-role/floci-local/user",
                "AirflowIdentity", MwaaEnvironmentManager.AIRFLOW_ADMIN_USER);
    }

    boolean isValidWebToken(String environmentIdentity, String token) {
        Map<String, Instant> tokens = webTokensByEnvironment.get(environmentIdentity);
        return tokens != null && isUnexpired(tokens, token);
    }

    private boolean isUnexpired(Map<String, Instant> tokens, String token) {
        Instant expiresAt = tokens.get(token);
        return expiresAt != null && clock.instant().isBefore(expiresAt);
    }

    public Map<String, Object> createCliToken(String name) {
        Environment environment = getEnvironment(name);
        String token = generateToken();
        cliTokensByEnvironment.computeIfAbsent(environmentIdentity(environment), k -> ConcurrentHashMap.newKeySet()).add(token);
        return Map.of(
                "CliToken", token,
                "WebServerHostname", hostnameFromUrl(environment.getWebserverUrl()));
    }

    boolean isValidCliToken(String environmentIdentity, String token) {
        Set<String> tokens = cliTokensByEnvironment.get(environmentIdentity);
        return tokens != null && tokens.contains(token);
    }

    private String resolveAirflowVersion(String requested) {
        List<String> supported = config.services().mwaa().supportedVersions();
        String version = requested != null && !requested.isBlank() ? requested : config.services().mwaa().defaultVersion();
        if (!AIRFLOW_VERSION_PATTERN.matcher(version).matches() || !supported.contains(version)) {
            throw new AwsException("ValidationException",
                    "Unsupported AirflowVersion '" + version + "'. Supported versions: " + supported, 400);
        }
        return version;
    }

    // CreateEnvironment's botocore model caps AirflowConfigurationOptions to these key/value
    // shapes, matched below.
    private static final Pattern AIRFLOW_CONFIGURATION_OPTION_KEY_PATTERN =
            Pattern.compile("[a-z]+([a-z0-9._]*[a-z0-9_]+)?");
    private static final Pattern AIRFLOW_CONFIGURATION_OPTION_VALUE_PATTERN = Pattern.compile("[ -~]+");
    private static final int MAX_AIRFLOW_CONFIGURATION_OPTION_KEY_LENGTH = 64;
    private static final int MAX_AIRFLOW_CONFIGURATION_OPTION_VALUE_LENGTH = 65536;

    /**
     * The AWS SDKs enforce {@code AirflowConfigurationOptions}' key/value shape client-side, but
     * Floci is also reachable via raw HTTP or CLI {@code --cli-input-json}, which bypasses that,
     * and this map now genuinely flows into the Airflow container's environment
     * ({@link MwaaEnvironmentManager#airflowConfigurationOptionsEnv}) rather than being inert, so
     * an oversized or malformed entry is worth rejecting here rather than passing through to
     * Docker container creation.
     */
    private void validateAirflowConfigurationOptions(Map<String, String> options) {
        if (options == null) {
            return;
        }
        for (Map.Entry<String, String> entry : options.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.length() > MAX_AIRFLOW_CONFIGURATION_OPTION_KEY_LENGTH
                    || !AIRFLOW_CONFIGURATION_OPTION_KEY_PATTERN.matcher(key).matches()) {
                throw new AwsException("ValidationException",
                        "Invalid AirflowConfigurationOptions key '" + key + "': must be 1-"
                                + MAX_AIRFLOW_CONFIGURATION_OPTION_KEY_LENGTH
                                + " characters matching " + AIRFLOW_CONFIGURATION_OPTION_KEY_PATTERN.pattern(),
                        400);
            }
            String value = entry.getValue();
            if (value == null || value.length() > MAX_AIRFLOW_CONFIGURATION_OPTION_VALUE_LENGTH
                    || !AIRFLOW_CONFIGURATION_OPTION_VALUE_PATTERN.matcher(value).matches()) {
                throw new AwsException("ValidationException",
                        "Invalid AirflowConfigurationOptions value for key '" + key + "': must be 1-"
                                + MAX_AIRFLOW_CONFIGURATION_OPTION_VALUE_LENGTH + " printable ASCII characters",
                        400);
            }
        }
    }

    private String buildWebserverUrl(int proxyPort) {
        String scheme = config.tls().enabled() ? "https" : "http";
        String host = config.hostname().orElse("localhost");
        return scheme + "://" + host + ":" + proxyPort;
    }

    private static String hostnameFromUrl(String url) {
        if (url == null) {
            return "localhost";
        }
        String withoutScheme = url.replaceFirst("^[a-zA-Z]+://", "");
        int slash = withoutScheme.indexOf('/');
        return slash >= 0 ? withoutScheme.substring(0, slash) : withoutScheme;
    }

    private static String generateToken() {
        byte[] buf = new byte[24];
        new SecureRandom().nextBytes(buf);
        return Base64Url.encode(buf);
    }

    @Override
    public String serviceKey() {
        return "airflow";
    }

    @Override
    public String tagsBodyKey() {
        return "Tags";
    }

    @Override
    public Map<String, String> listTags(String region, String resourceArn) {
        Environment environment = findByArn(resourceArn);
        return environment.getTags() != null ? environment.getTags() : Map.of();
    }

    @Override
    public void tagResource(String region, String resourceArn, Map<String, String> tags) {
        Environment environment = findByArn(resourceArn);
        if (environment.getTags() == null) {
            environment.setTags(new HashMap<>());
        }
        environment.getTags().putAll(tags);
        putEnvironment(environment);
    }

    @Override
    public void untagResource(String region, String resourceArn, List<String> tagKeys) {
        Environment environment = findByArn(resourceArn);
        if (environment.getTags() != null && tagKeys != null) {
            tagKeys.forEach(environment.getTags()::remove);
        }
        putEnvironment(environment);
    }

    private Environment findByArn(String resourceArn) {
        if (resourceArn == null) {
            throw new AwsException("ValidationException", "Invalid resource ARN: " + resourceArn, 400);
        }
        try {
            AwsArnUtils.Arn arn = AwsArnUtils.parse(resourceArn);
            String name = arn.resource().startsWith("environment/")
                    ? arn.resource().substring("environment/".length())
                    : "";
            if (name.isBlank() || arn.region().isBlank() || arn.accountId().isBlank()
                    || !"airflow".equals(arn.service())) {
                throw new IllegalArgumentException("not an MWAA environment ARN");
            }
            if (!arn.accountId().equals(regionResolver.getAccountId())
                    || !arn.region().equals(regionResolver.getRegion())) {
                throw new AwsException("ResourceNotFoundException",
                        "No environment found for name: " + name, 404);
            }
            return getStoredEnvironment(arn.accountId(), arn.region(), name)
                    .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                            "No environment found for name: " + name, 404));
        } catch (IllegalArgumentException e) {
            throw new AwsException("ValidationException", "Invalid resource ARN: " + resourceArn, 400);
        }
    }

    private void startReadinessPoller() {
        readinessPoller.scheduleAtFixedRate(() -> {
            try {
                for (Environment environment : allEnvironments()) {
                    checkReadiness(environment);
                }
            } catch (Exception e) {
                LOG.error("Error in MWAA readiness poller", e);
            }
        }, 2, 3, TimeUnit.SECONDS);
    }

    // Package-private (not private) so MwaaServiceTest can exercise a single poll pass directly,
    // without waiting on the scheduled executor.
    void checkReadiness(Environment environment) {
        EnvironmentStatus status = environment.getStatus();
        if (status != EnvironmentStatus.CREATING && status != EnvironmentStatus.MAINTENANCE
                || restoringEnvironments.contains(restoringKey(environment))) {
            return;
        }
        boolean ready = environmentManager.isReady(environment);
        boolean exited = !ready && environmentManager.hasAnyContainerExited(environment);

        // isReady()/hasAnyContainerExited() are blocking Docker/HTTP calls, long enough for a
        // concurrent DeleteEnvironment (which sets DELETING on this same Environment instance
        // before tearing down its containers) to have moved this environment past CREATING in the
        // meantime. Re-checking right before writing avoids resurrecting a just-deleted environment
        // into storage with a status decided from stale, pre-delete information.
        if (environment.getStatus() != status) {
            return;
        }
        if (ready) {
            LOG.infov("MWAA environment {0} is now AVAILABLE", environment.getName());
            environment.setStatus(EnvironmentStatus.AVAILABLE);
            putEnvironment(environment);
        } else if (exited) {
            // docker start returns as soon as a container's entrypoint launches, so its Postgres or
            // Airflow container dying partway through never surfaces here on its own; without this
            // check the environment would poll dead containers and report CREATING forever instead
            // of the CREATE_FAILED a real failure should be. One whose containers came back after a
            // restart is UNAVAILABLE, as AWS reports an environment that did not return to its state.
            EnvironmentStatus failed = status == EnvironmentStatus.CREATING
                    ? EnvironmentStatus.CREATE_FAILED : EnvironmentStatus.UNAVAILABLE;
            LOG.errorv("MWAA environment {0}''s Postgres or Airflow container exited before "
                    + "becoming ready; marking {1}", environment.getName(), failed);
            environment.setStatus(failed);
            putEnvironment(environment);
        }
    }

    private void startDagSyncPoller() {
        int intervalSeconds = Math.max(1, config.services().mwaa().dagSyncIntervalSeconds());
        dagSyncPoller.scheduleAtFixedRate(() -> {
            try {
                for (Environment environment : allEnvironments()) {
                    if (environment.getStatus() != EnvironmentStatus.AVAILABLE) {
                        continue;
                    }
                    try {
                        syncDags(environment);
                    } catch (Exception e) {
                        // A bad/unsyncable DAG or S3 error must never fail the environment itself —
                        // only this poller pass for this one environment is affected.
                        LOG.warnv("DAG sync failed for MWAA environment {0}: {1}",
                                environment.getName(), e.getMessage());
                    }
                }
            } catch (Exception e) {
                LOG.error("Error in MWAA DAG-sync poller", e);
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    // Package-private (not private) so MwaaServiceTest can run a single sync pass directly.
    void syncDags(Environment environment) {
        if (environment.getSourceBucketArn() == null || environment.getDagS3Path() == null) {
            return;
        }
        String bucket = bucketNameFromArn(environment.getSourceBucketArn());
        String prefix = environment.getDagS3Path().endsWith("/")
                ? environment.getDagS3Path() : environment.getDagS3Path() + "/";

        List<S3Object> objects = listDagObjects(bucket, prefix);
        Map<String, String> currentByKey = new HashMap<>();
        for (S3Object obj : objects) {
            String relative = obj.getKey().substring(prefix.length());
            if (relative.isBlank()) {
                continue;
            }
            currentByKey.put(relative, obj.getETag());
        }

        String environmentIdentity = environmentIdentity(environment);
        Map<String, String> previous = dagSyncState.get(environmentIdentity);
        if (previous == null) {
            // The first pass since the environment's containers started, or came back after a restart on a DAG volume
            // that may hold files the S3 prefix no longer has, which no earlier pass of this Floci recorded. Each file
            // there counts as synced with an ETag S3 never gives, so one S3 still has is copied again and one it has
            // not is removed.
            previous = new HashMap<>();
            for (String relative : environmentManager.listDagFiles(environment)) {
                previous.put(relative, "");
            }
            dagSyncState.put(environmentIdentity, previous);
        }
        for (Map.Entry<String, String> entry : currentByKey.entrySet()) {
            String relative = entry.getKey();
            String etag = entry.getValue();
            if (!etag.equals(previous.get(relative))) {
                try {
                    S3Object obj = s3Service.getObject(bucket, prefix + relative);
                    environmentManager.copyDagFile(environment, relative, obj.getData());
                } catch (Exception e) {
                    LOG.warnv("Skipping unsyncable DAG {0} for environment {1}: {2}",
                            relative, environment.getName(), e.getMessage());
                }
            }
        }
        for (String relative : new HashSet<>(previous.keySet())) {
            if (!currentByKey.containsKey(relative)) {
                environmentManager.removeDagFile(environment, relative);
            }
        }
        previous.clear();
        previous.putAll(currentByKey);

        syncRequirementsIfNeeded(environment, bucket);
    }

    /**
     * Every object under the DAG prefix, page by page: the sync removes from the container what this does not name,
     * so a DAG past the first page must not read as gone.
     */
    private List<S3Object> listDagObjects(String bucket, String prefix) {
        List<S3Object> objects = new ArrayList<>();
        String continuationToken = null;
        do {
            S3Service.ListObjectsResult page = s3Service.listObjectsWithPrefixes(bucket, prefix, null, 1000,
                    continuationToken, null);
            objects.addAll(page.objects());
            continuationToken = page.isTruncated() ? page.nextContinuationToken() : null;
        } while (continuationToken != null);
        return objects;
    }

    // installRequirements re-checks on every DAG-sync pass (rather than only at CreateEnvironment
    // time) so that updating requirements.txt in S3 after creation takes effect automatically,
    // matching how DAG file changes are already picked up. The install itself runs on
    // requirementsInstaller, one at a time per environment, so a slow pip never holds up the DAG
    // sync of every other environment.
    private void syncRequirementsIfNeeded(Environment environment, String bucket) {
        if (!config.services().mwaa().installRequirements() || environment.getRequirementsS3Path() == null) {
            return;
        }
        String environmentIdentity = environmentIdentity(environment);
        if (bootstrapInstallsToCheck.remove(environmentIdentity)) {
            checkBootstrapInstall(environment, environmentIdentity);
        }
        try {
            S3Object requirements = s3Service.getObject(bucket, environment.getRequirementsS3Path());
            if (requirements.getETag().equals(requirementsEtagByEnvironment.get(environmentIdentity))
                    || !requirementsInstallsInFlight.add(environmentIdentity)) {
                return;
            }
            requirementsInstaller.execute(() -> reinstallRequirements(environment, environmentIdentity, requirements));
        } catch (Exception e) {
            LOG.warnv("Could not sync requirements.txt for environment {0}: {1}",
                    environment.getName(), e.getMessage());
        }
    }

    // A failed bootstrap install forgets the recorded ETag, so the same sync pass installs the file again.
    private void checkBootstrapInstall(Environment environment, String environmentIdentity) {
        Integer exitCode = environmentManager.lastRequirementsInstallExitCode(environment);
        if (exitCode != null && exitCode != 0) {
            LOG.warnv("pip install -r requirements.txt exited {0} while MWAA environment {1} started; "
                    + "installing it again", String.valueOf(exitCode), environment.getName());
            requirementsEtagByEnvironment.remove(environmentIdentity);
        }
    }

    private void reinstallRequirements(Environment environment, String environmentIdentity, S3Object requirements) {
        try {
            environmentManager.installRequirements(environment, requirements.getData());
            if (environment.getStatus() == EnvironmentStatus.AVAILABLE) {
                requirementsEtagByEnvironment.put(environmentIdentity, requirements.getETag());
            }
        } finally {
            requirementsInstallsInFlight.remove(environmentIdentity);
        }
    }

    // Fetched at creation so the container installs requirements.txt before Airflow starts, the
    // order real MWAA uses. A missing object does not fail the environment, since the DAG-sync pass
    // installs the file once it appears; any other S3 error fails CreateEnvironment, as it does for
    // the startup script.
    private S3Object fetchRequirements(Environment environment) {
        if (!config.services().mwaa().installRequirements() || environment.getRequirementsS3Path() == null
                || environment.getSourceBucketArn() == null) {
            return null;
        }
        try {
            return s3Service.getObject(bucketNameFromArn(environment.getSourceBucketArn()),
                    environment.getRequirementsS3Path());
        } catch (AwsException e) {
            if (!MISSING_OBJECT_ERRORS.contains(e.getErrorCode())) {
                throw e;
            }
            LOG.warnv("requirements.txt for environment {0} is not in S3 yet ({1}); the DAG-sync pass installs it "
                    + "once it is", environment.getName(), e.getErrorCode());
            return null;
        }
    }

    // Unlike DAG/requirements sync (which run periodically post-creation and must never fail the
    // environment over one bad object), the startup script only ever runs once, at container
    // creation — a missing/unreadable object here is a genuine configuration error, so it
    // propagates and fails CreateEnvironment, matching real MWAA gating environment creation on
    // a startup-script problem.
    private byte[] fetchStartupScript(Environment environment) {
        if (environment.getStartupScriptS3Path() == null) {
            return null;
        }
        if (environment.getSourceBucketArn() == null) {
            throw new AwsException("ValidationException",
                    "StartupScriptS3Path requires SourceBucketArn to be set", 400);
        }
        String bucket = bucketNameFromArn(environment.getSourceBucketArn());
        return s3Service.getObject(bucket, environment.getStartupScriptS3Path()).getData();
    }

    private static String bucketNameFromArn(String sourceBucketArn) {
        int idx = sourceBucketArn.lastIndexOf(':');
        return idx >= 0 ? sourceBucketArn.substring(idx + 1) : sourceBucketArn;
    }

    private List<Environment> allEnvironments() {
        if (storage instanceof AccountAwareStorageBackend<Environment> aware) {
            return aware.scanAllAccounts();
        }
        return storage.scan(k -> true);
    }

    void putEnvironment(Environment environment) {
        String key = environmentKey(environmentRegion(environment), environment.getName());
        String accountId = environmentAccount(environment);
        if (accountId != null && storage instanceof AccountAwareStorageBackend<Environment> aware) {
            aware.getForAccountMigratingLegacyKeys(accountId, key, List.of(environment.getName()),
                    candidate -> accountId.equals(environmentAccount(candidate))
                            && environmentRegion(environment).equals(environmentRegion(candidate)),
                    isDefaultScope(accountId, environmentRegion(environment)));
            aware.putForAccount(accountId, key, environment);
        } else {
            storage.put(key, environment);
        }
    }

    private void deleteEnvironment(Environment environment) {
        String key = environmentKey(environmentRegion(environment), environment.getName());
        String accountId = environmentAccount(environment);
        if (accountId != null && storage instanceof AccountAwareStorageBackend<Environment> aware) {
            aware.deleteForAccount(accountId, key);
        } else {
            storage.delete(key);
        }
        runtimeStorage.deleteForAccount(accountId, key);
    }

    private void putRuntime(Environment environment) {
        runtimeStorage.putForAccount(environmentAccount(environment),
                environmentKey(environmentRegion(environment), environment.getName()),
                EnvironmentRuntime.of(environment));
    }

    private Optional<Environment> getStoredEnvironment(String accountId, String region, String name) {
        String key = environmentKey(region, name);
        if (storage instanceof AccountAwareStorageBackend<Environment> aware) {
            return aware.getForAccountMigratingLegacyKeys(accountId, key, List.of(name),
                    environment -> accountId.equals(environmentAccount(environment))
                            && region.equals(environmentRegion(environment)),
                    isDefaultScope(accountId, region));
        }
        return storage.get(key);
    }

    private void migrateLegacyEnvironments(AccountAwareStorageBackend<Environment> aware,
                                           String accountId, String region) {
        for (Environment legacy : aware.scanUnscopedLegacy(environment ->
                accountId.equals(environmentAccount(environment))
                        && region.equals(environmentRegion(environment)))) {
            migrateLegacyEnvironment(aware, accountId, region, legacy);
        }
        for (String legacyKey : aware.keysForAccount(accountId)) {
            if (legacyKey.contains("/")) {
                continue;
            }
            aware.getForAccount(accountId, legacyKey)
                    .filter(environment -> accountId.equals(environmentAccount(environment))
                            && region.equals(environmentRegion(environment)))
                    .ifPresent(environment -> migrateLegacyEnvironment(aware, accountId, region, environment));
        }
    }

    private void migrateLegacyEnvironment(AccountAwareStorageBackend<Environment> aware,
                                          String accountId, String region, Environment environment) {
        String key = environmentKey(region, environment.getName());
        aware.getForAccountMigratingLegacyKeys(accountId, key, List.of(environment.getName()),
                candidate -> accountId.equals(environmentAccount(candidate))
                        && region.equals(environmentRegion(candidate)),
                isDefaultScope(accountId, region)).ifPresent(value -> aware.putForAccount(accountId, key, value));
    }

    private boolean isDefaultScope(String accountId, String region) {
        return accountId.equals(regionResolver.getDefaultAccountId())
                && region.equals(regionResolver.getDefaultRegion());
    }

    private static String environmentKey(String region, String name) {
        return region + "/" + name;
    }

    String environmentIdentity(Environment environment) {
        return environmentAccount(environment) + "/" + environmentRegion(environment) + "/" + environment.getName();
    }

    private static String environmentAccount(Environment environment) {
        return MwaaEnvironmentManager.environmentAccount(environment);
    }

    private String environmentRegion(Environment environment) {
        return regionResolver.regionOf(environment.getArn());
    }

}
