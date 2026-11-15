package io.floci.aws.services.mwaa;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Base64Url;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.TagHandler;
import io.floci.aws.core.common.docker.PortAllocator;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ec2.VpcDependencyProvider;
import io.floci.aws.services.mwaa.model.CreateEnvironmentRequest;
import io.floci.aws.services.mwaa.model.Environment;
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

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    private final StorageBackend<String, Environment> storage;
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
        if (!config.services().mwaa().mock() && !config.services().mwaa().keepRunningOnShutdown()) {
            for (Environment environment : allEnvironments()) {
                proxyManager.stopProxy(environmentIdentity(environment));
                environmentManager.stopEnvironment(environment);
            }
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
                String airflowContainerId = environment.getAirflowContainerId();
                // The proxy reads this environment's own token map, not whatever the name maps to later: a token
                // issued while an earlier environment of this name was being deleted lands in that one's map, not
                // in this.
                Map<String, Instant> webTokens = new ConcurrentHashMap<>();
                webTokensByEnvironment.put(environmentIdentity(environment), webTokens);
                proxyManager.startProxy(environmentIdentity(environment), proxyPort,
                        environment.getAirflowInternalHost(), environment.getAirflowInternalPort(),
                        this::isValidCliToken,
                        cliCommand -> environmentManager.runAirflowCli(airflowContainerId, version, cliCommand),
                        (proxyIdentity, token) -> isUnexpired(webTokens, token),
                        new MwaaWebProxy.AirflowAccount(MwaaEnvironmentManager.AIRFLOW_ADMIN_USER,
                                environment.getAirflowAdminPassword()));
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
        environment.setStatus(EnvironmentStatus.DELETING);
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
        deleteEnvironment(environment);
        return environment;
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
        if (environment.getStatus() != EnvironmentStatus.CREATING) {
            return;
        }
        boolean ready = environmentManager.isReady(environment);
        boolean exited = !ready && environmentManager.hasAnyContainerExited(environment);

        // isReady()/hasAnyContainerExited() are blocking Docker/HTTP calls, long enough for a
        // concurrent DeleteEnvironment (which sets DELETING on this same Environment instance
        // before tearing down its containers) to have moved this environment past CREATING in the
        // meantime. Re-checking right before writing avoids resurrecting a just-deleted environment
        // into storage with a status decided from stale, pre-delete information.
        if (environment.getStatus() != EnvironmentStatus.CREATING) {
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
            // of the CREATE_FAILED a real failure should be.
            LOG.errorv("MWAA environment {0}''s Postgres or Airflow container exited before "
                    + "becoming ready; marking CREATE_FAILED", environment.getName());
            environment.setStatus(EnvironmentStatus.CREATE_FAILED);
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

        List<S3Object> objects = s3Service.listObjects(bucket, prefix, null, 1000);
        Map<String, String> currentByKey = new HashMap<>();
        for (S3Object obj : objects) {
            String relative = obj.getKey().substring(prefix.length());
            if (relative.isBlank()) {
                continue;
            }
            currentByKey.put(relative, obj.getETag());
        }

        String environmentIdentity = environmentIdentity(environment);
        Map<String, String> previous = dagSyncState.computeIfAbsent(environmentIdentity, k -> new HashMap<>());
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
        return MwaaEnvironmentManager.environmentRegion(environment, regionResolver.getDefaultRegion());
    }

}
