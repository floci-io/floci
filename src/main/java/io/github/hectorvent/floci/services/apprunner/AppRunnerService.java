package io.github.hectorvent.floci.services.apprunner;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.apprunner.model.AppRunnerAutoScalingConfiguration;
import io.github.hectorvent.floci.services.apprunner.model.AppRunnerAutoScalingConfigurationSummary;
import io.github.hectorvent.floci.services.apprunner.model.AppRunnerConnection;
import io.github.hectorvent.floci.services.apprunner.model.AppRunnerObservabilityConfiguration;
import io.github.hectorvent.floci.services.apprunner.model.AppRunnerObservabilityConfigurationSummary;
import io.github.hectorvent.floci.services.apprunner.model.AppRunnerOperation;
import io.github.hectorvent.floci.services.apprunner.model.AppRunnerServiceModel;
import io.github.hectorvent.floci.services.apprunner.model.AppRunnerVpcConnector;
import io.github.hectorvent.floci.services.apprunner.model.AppRunnerVpcIngressConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * AWS App Runner management plane.
 *
 * <p>Every resource reports its terminal state as soon as a create returns — services are
 * {@code RUNNING}, auto scaling configurations and VPC connectors are {@code ACTIVE}, and
 * connections are {@code AVAILABLE} — so provider waiters complete on their first poll.
 *
 * <p>Deleting a resource moves it to the deleted state its own status enum defines
 * ({@code INACTIVE} for auto scaling configurations and VPC connectors, {@code DELETED} for
 * connections and services) and drops it from the list operations, which AWS documents as
 * returning only running services and active configurations. That is what App Runner does, and
 * it is also what keeps a provider's delete waiter from polling for a state that never arrives.
 *
 * <p>No container is built, pushed or run: the service URL is a plausible App Runner subdomain
 * that resolves nowhere.
 */
@ApplicationScoped
public class AppRunnerService {

    private static final Logger LOG = Logger.getLogger(AppRunnerService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String HEX = "0123456789abcdef";
    private static final String SUBDOMAIN_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_INACTIVE = "INACTIVE";
    private static final String STATUS_AVAILABLE = "AVAILABLE";
    private static final String STATUS_DELETED = "DELETED";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_PAUSED = "PAUSED";
    private static final String OPERATION_SUCCEEDED = "SUCCEEDED";

    private static final String DEFAULT_CONFIGURATION_NAME = "DefaultConfiguration";
    private static final String DEFAULT_CONFIGURATION_ID = "00000000000000000000000000000001";
    private static final String AUTO_SCALING_PREFIX = "autoscalingconfiguration/";
    private static final String OBSERVABILITY_PREFIX = "observabilityconfiguration/";

    private final StorageBackend<String, AppRunnerAutoScalingConfiguration> autoScalingConfigurations;
    private final StorageBackend<String, AppRunnerVpcConnector> vpcConnectors;
    private final StorageBackend<String, AppRunnerConnection> connections;
    private final StorageBackend<String, AppRunnerServiceModel> services;
    private final StorageBackend<String, AppRunnerOperation> operations;
    private final StorageBackend<String, AppRunnerObservabilityConfiguration> observabilityConfigurations;
    private final StorageBackend<String, AppRunnerVpcIngressConnection> vpcIngressConnections;
    private final StorageBackend<String, Map<String, String>> tags;
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;

    @Inject
    public AppRunnerService(StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper mapper) {
        this.autoScalingConfigurations = storageFactory.create("apprunner",
                "apprunner-auto-scaling-configurations.json",
                new TypeReference<Map<String, AppRunnerAutoScalingConfiguration>>() {});
        this.vpcConnectors = storageFactory.create("apprunner", "apprunner-vpc-connectors.json",
                new TypeReference<Map<String, AppRunnerVpcConnector>>() {});
        this.connections = storageFactory.create("apprunner", "apprunner-connections.json",
                new TypeReference<Map<String, AppRunnerConnection>>() {});
        this.services = storageFactory.create("apprunner", "apprunner-services.json",
                new TypeReference<Map<String, AppRunnerServiceModel>>() {});
        this.operations = storageFactory.create("apprunner", "apprunner-operations.json",
                new TypeReference<Map<String, AppRunnerOperation>>() {});
        this.observabilityConfigurations = storageFactory.create("apprunner",
                "apprunner-observability-configurations.json",
                new TypeReference<Map<String, AppRunnerObservabilityConfiguration>>() {});
        this.vpcIngressConnections = storageFactory.create("apprunner", "apprunner-vpc-ingress-connections.json",
                new TypeReference<Map<String, AppRunnerVpcIngressConnection>>() {});
        this.tags = storageFactory.create("apprunner", "apprunner-tags.json",
                new TypeReference<Map<String, Map<String, String>>>() {});
        this.regionResolver = regionResolver;
        this.mapper = mapper;
    }

    // ─────────────────── Auto scaling configurations ───────────────────

    public AppRunnerAutoScalingConfiguration createAutoScalingConfiguration(
            String name, Integer maxConcurrency, Integer minSize, Integer maxSize,
            Map<String, String> requestTags, String region) {
        requireArgument(name, "AutoScalingConfigurationName");
        int revision = nextRevision(name, region);

        autoScalingConfigurationsInRegion(region).stream()
                .filter(configuration -> name.equals(configuration.getAutoScalingConfigurationName()))
                .forEach(configuration -> {
                    configuration.setLatest(false);
                    autoScalingConfigurations.put(configuration.getAutoScalingConfigurationArn(), configuration);
                });

        AppRunnerAutoScalingConfiguration configuration = new AppRunnerAutoScalingConfiguration();
        configuration.setAutoScalingConfigurationArn(regionResolver.buildArn("apprunner", region,
                AUTO_SCALING_PREFIX + name + "/" + revision + "/" + randomHex(32)));
        configuration.setAutoScalingConfigurationName(name);
        configuration.setAutoScalingConfigurationRevision(revision);
        configuration.setLatest(true);
        configuration.setStatus(STATUS_ACTIVE);
        configuration.setMaxConcurrency(maxConcurrency != null ? maxConcurrency : 100);
        configuration.setMinSize(minSize != null ? minSize : 1);
        configuration.setMaxSize(maxSize != null ? maxSize : 25);
        configuration.setCreatedAt(Instant.now().getEpochSecond());
        configuration.setHasAssociatedService(false);
        configuration.setIsDefault(false);

        autoScalingConfigurations.put(configuration.getAutoScalingConfigurationArn(), configuration);
        putTags(configuration.getAutoScalingConfigurationArn(), requestTags);
        LOG.infov("Created App Runner auto scaling configuration: {0}",
                configuration.getAutoScalingConfigurationArn());
        return configuration;
    }

    public AppRunnerAutoScalingConfiguration describeAutoScalingConfiguration(String arn, String region) {
        AppRunnerAutoScalingConfiguration configuration = resolveAutoScalingConfiguration(arn, region);
        configuration.setHasAssociatedService(hasAssociatedService(
                configuration.getAutoScalingConfigurationArn(), region));
        return configuration;
    }

    public AppRunnerAutoScalingConfiguration deleteAutoScalingConfiguration(String arn, boolean deleteAllRevisions,
                                                                           String region) {
        AppRunnerAutoScalingConfiguration configuration = resolveAutoScalingConfiguration(arn, region);
        if (Boolean.TRUE.equals(configuration.getIsDefault())) {
            throw new AwsException("InvalidRequestException",
                    "The default auto scaling configuration can't be deleted.", 400);
        }
        List<AppRunnerAutoScalingConfiguration> doomed = deleteAllRevisions
                ? autoScalingConfigurationsInRegion(region).stream()
                        .filter(candidate -> configuration.getAutoScalingConfigurationName()
                                .equals(candidate.getAutoScalingConfigurationName()))
                        .toList()
                : List.of(configuration);

        for (AppRunnerAutoScalingConfiguration candidate : doomed) {
            if (hasAssociatedService(candidate.getAutoScalingConfigurationArn(), region)) {
                throw new AwsException("InvalidRequestException",
                        "The auto scaling configuration " + candidate.getAutoScalingConfigurationArn()
                                + " is used by one or more App Runner services and can't be deleted.", 400);
            }
        }

        long now = Instant.now().getEpochSecond();
        for (AppRunnerAutoScalingConfiguration candidate : doomed) {
            candidate.setStatus(STATUS_INACTIVE);
            candidate.setLatest(false);
            candidate.setDeletedAt(now);
            candidate.setHasAssociatedService(false);
            autoScalingConfigurations.put(candidate.getAutoScalingConfigurationArn(), candidate);
        }
        LOG.infov("Deleted App Runner auto scaling configuration: {0}",
                configuration.getAutoScalingConfigurationArn());
        return autoScalingConfigurations.get(configuration.getAutoScalingConfigurationArn())
                .orElse(configuration);
    }

    public List<AppRunnerAutoScalingConfigurationSummary> listAutoScalingConfigurations(
            String name, Boolean latestOnly, String region) {
        ensureDefaultAutoScalingConfiguration(region);
        return autoScalingConfigurationsInRegion(region).stream()
                .filter(configuration -> STATUS_ACTIVE.equals(configuration.getStatus()))
                .filter(configuration -> name == null
                        || name.equals(configuration.getAutoScalingConfigurationName()))
                .filter(configuration -> !Boolean.TRUE.equals(latestOnly)
                        || Boolean.TRUE.equals(configuration.getLatest()))
                .sorted(Comparator.comparing(AppRunnerAutoScalingConfiguration::getAutoScalingConfigurationName)
                        .thenComparing(AppRunnerAutoScalingConfiguration::getAutoScalingConfigurationRevision))
                .map(configuration -> {
                    configuration.setHasAssociatedService(
                            hasAssociatedService(configuration.getAutoScalingConfigurationArn(), region));
                    return AppRunnerAutoScalingConfigurationSummary.of(configuration);
                })
                .toList();
    }

    // ──────────────────── Observability configurations ────────────────────

    public AppRunnerObservabilityConfiguration createObservabilityConfiguration(
            String name, JsonNode traceConfiguration, Map<String, String> requestTags, String region) {
        requireArgument(name, "ObservabilityConfigurationName");
        int revision = nextObservabilityRevision(name, region);

        observabilityConfigurationsInRegion(region).stream()
                .filter(configuration -> name.equals(configuration.getObservabilityConfigurationName()))
                .forEach(configuration -> {
                    configuration.setLatest(false);
                    observabilityConfigurations.put(
                            configuration.getObservabilityConfigurationArn(), configuration);
                });

        AppRunnerObservabilityConfiguration configuration = new AppRunnerObservabilityConfiguration();
        configuration.setObservabilityConfigurationArn(regionResolver.buildArn("apprunner", region,
                OBSERVABILITY_PREFIX + name + "/" + revision + "/" + randomHex(32)));
        configuration.setObservabilityConfigurationName(name);
        configuration.setObservabilityConfigurationRevision(revision);
        configuration.setLatest(true);
        configuration.setStatus(STATUS_ACTIVE);
        configuration.setTraceConfiguration(normalizeTraceConfiguration(traceConfiguration));
        configuration.setCreatedAt(Instant.now().getEpochSecond());

        observabilityConfigurations.put(configuration.getObservabilityConfigurationArn(), configuration);
        putTags(configuration.getObservabilityConfigurationArn(), requestTags);
        LOG.infov("Created App Runner observability configuration: {0}",
                configuration.getObservabilityConfigurationArn());
        return configuration;
    }

    public AppRunnerObservabilityConfiguration describeObservabilityConfiguration(String arn, String region) {
        return resolveObservabilityConfiguration(arn, region);
    }

    public AppRunnerObservabilityConfiguration deleteObservabilityConfiguration(String arn, String region) {
        AppRunnerObservabilityConfiguration configuration = resolveObservabilityConfiguration(arn, region);
        configuration.setStatus(STATUS_INACTIVE);
        configuration.setLatest(false);
        configuration.setDeletedAt(Instant.now().getEpochSecond());
        observabilityConfigurations.put(configuration.getObservabilityConfigurationArn(), configuration);
        LOG.infov("Deleted App Runner observability configuration: {0}",
                configuration.getObservabilityConfigurationArn());
        return configuration;
    }

    public List<AppRunnerObservabilityConfigurationSummary> listObservabilityConfigurations(
            String name, Boolean latestOnly, String region) {
        return observabilityConfigurationsInRegion(region).stream()
                .filter(configuration -> STATUS_ACTIVE.equals(configuration.getStatus()))
                .filter(configuration -> name == null
                        || name.equals(configuration.getObservabilityConfigurationName()))
                .filter(configuration -> !Boolean.TRUE.equals(latestOnly)
                        || Boolean.TRUE.equals(configuration.getLatest()))
                .sorted(Comparator.comparing(
                                AppRunnerObservabilityConfiguration::getObservabilityConfigurationName)
                        .thenComparing(AppRunnerObservabilityConfiguration::getObservabilityConfigurationRevision))
                .map(AppRunnerObservabilityConfigurationSummary::of)
                .toList();
    }

    // ──────────────────────── VPC connectors ────────────────────────

    public AppRunnerVpcConnector createVpcConnector(String name, List<String> subnets, List<String> securityGroups,
                                                    Map<String, String> requestTags, String region) {
        requireArgument(name, "VpcConnectorName");
        if (subnets == null || subnets.isEmpty()) {
            throw new AwsException("InvalidRequestException", "Subnets is required.", 400);
        }
        int revision = (int) vpcConnectorsInRegion(region).stream()
                .filter(connector -> name.equals(connector.getVpcConnectorName()))
                .count() + 1;

        AppRunnerVpcConnector connector = new AppRunnerVpcConnector();
        connector.setVpcConnectorArn(regionResolver.buildArn("apprunner", region,
                "vpcconnector/" + name + "/" + revision + "/" + randomHex(32)));
        connector.setVpcConnectorName(name);
        connector.setVpcConnectorRevision(revision);
        connector.setSubnets(subnets);
        connector.setSecurityGroups(securityGroups != null ? securityGroups : new ArrayList<>());
        connector.setStatus(STATUS_ACTIVE);
        connector.setCreatedAt(Instant.now().getEpochSecond());

        vpcConnectors.put(connector.getVpcConnectorArn(), connector);
        putTags(connector.getVpcConnectorArn(), requestTags);
        LOG.infov("Created App Runner VPC connector: {0}", connector.getVpcConnectorArn());
        return connector;
    }

    public AppRunnerVpcConnector describeVpcConnector(String arn) {
        requireArgument(arn, "VpcConnectorArn");
        return vpcConnectors.get(arn)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "VPC connector " + arn + " does not exist.", 400));
    }

    public AppRunnerVpcConnector deleteVpcConnector(String arn) {
        AppRunnerVpcConnector connector = describeVpcConnector(arn);
        connector.setStatus(STATUS_INACTIVE);
        connector.setDeletedAt(Instant.now().getEpochSecond());
        vpcConnectors.put(arn, connector);
        LOG.infov("Deleted App Runner VPC connector: {0}", arn);
        return connector;
    }

    public List<AppRunnerVpcConnector> listVpcConnectors(String region) {
        return vpcConnectorsInRegion(region).stream()
                .filter(connector -> STATUS_ACTIVE.equals(connector.getStatus()))
                .toList();
    }

    // ──────────────────── VPC ingress connections ────────────────────

    public AppRunnerVpcIngressConnection createVpcIngressConnection(String name, String serviceArn,
            JsonNode ingressVpcConfiguration, Map<String, String> requestTags, String region) {
        requireArgument(name, "VpcIngressConnectionName");
        requireArgument(serviceArn, "ServiceArn");
        if (ingressVpcConfiguration == null || !ingressVpcConfiguration.isObject()) {
            throw new AwsException("InvalidRequestException", "IngressVpcConfiguration is required.", 400);
        }

        AppRunnerVpcIngressConnection connection = new AppRunnerVpcIngressConnection();
        connection.setVpcIngressConnectionArn(regionResolver.buildArn("apprunner", region,
                "vpcingressconnection/" + name + "/" + randomHex(32)));
        connection.setVpcIngressConnectionName(name);
        connection.setServiceArn(serviceArn);
        connection.setAccountId(regionResolver.getAccountId());
        connection.setStatus(STATUS_AVAILABLE);
        connection.setDomainName(randomSubdomain() + "." + region + ".prod.web.vpc-ingress." + "awsapprunner.com");
        connection.setIngressVpcConfiguration(ingressVpcConfiguration.deepCopy());
        connection.setCreatedAt(Instant.now().getEpochSecond());

        vpcIngressConnections.put(connection.getVpcIngressConnectionArn(), connection);
        putTags(connection.getVpcIngressConnectionArn(), requestTags);
        LOG.infov("Created App Runner VPC ingress connection: {0}", connection.getVpcIngressConnectionArn());
        return connection;
    }

    public AppRunnerVpcIngressConnection describeVpcIngressConnection(String arn) {
        requireArgument(arn, "VpcIngressConnectionArn");
        return vpcIngressConnections.get(arn)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "VPC ingress connection " + arn + " does not exist.", 400));
    }

    public AppRunnerVpcIngressConnection deleteVpcIngressConnection(String arn) {
        AppRunnerVpcIngressConnection connection = describeVpcIngressConnection(arn);
        connection.setStatus(STATUS_DELETED);
        connection.setDeletedAt(Instant.now().getEpochSecond());
        vpcIngressConnections.put(arn, connection);
        LOG.infov("Deleted App Runner VPC ingress connection: {0}", arn);
        return connection;
    }

    public List<AppRunnerVpcIngressConnection> listVpcIngressConnections(String serviceArn, String region) {
        return vpcIngressConnectionsInRegion(region).stream()
                .filter(connection -> !STATUS_DELETED.equals(connection.getStatus()))
                .filter(connection -> serviceArn == null || serviceArn.equals(connection.getServiceArn()))
                .toList();
    }

    // ────────────────────────── Connections ──────────────────────────

    public AppRunnerConnection createConnection(String name, String providerType,
                                                Map<String, String> requestTags, String region) {
        requireArgument(name, "ConnectionName");
        requireArgument(providerType, "ProviderType");
        if (!"GITHUB".equals(providerType) && !"BITBUCKET".equals(providerType)) {
            throw new AwsException("InvalidRequestException", "ProviderType must be GITHUB or BITBUCKET.", 400);
        }
        boolean nameTaken = connectionsInRegion(region).stream()
                .anyMatch(connection -> name.equals(connection.getConnectionName())
                        && !STATUS_DELETED.equals(connection.getStatus()));
        if (nameTaken) {
            throw new AwsException("InvalidRequestException",
                    "A connection named " + name + " already exists.", 400);
        }

        AppRunnerConnection connection = new AppRunnerConnection();
        connection.setConnectionArn(regionResolver.buildArn("apprunner", region,
                "connection/" + name + "/" + randomHex(32)));
        connection.setConnectionName(name);
        connection.setProviderType(providerType);
        connection.setStatus(STATUS_AVAILABLE);
        connection.setCreatedAt(Instant.now().getEpochSecond());

        connections.put(connection.getConnectionArn(), connection);
        putTags(connection.getConnectionArn(), requestTags);
        LOG.infov("Created App Runner connection: {0}", connection.getConnectionArn());
        return connection;
    }

    public AppRunnerConnection deleteConnection(String arn) {
        requireArgument(arn, "ConnectionArn");
        AppRunnerConnection connection = connections.get(arn)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Connection " + arn + " does not exist.", 400));
        connection.setStatus(STATUS_DELETED);
        connections.put(arn, connection);
        LOG.infov("Deleted App Runner connection: {0}", arn);
        return connection;
    }

    public List<AppRunnerConnection> listConnections(String name, String region) {
        return connectionsInRegion(region).stream()
                .filter(connection -> !STATUS_DELETED.equals(connection.getStatus()))
                .filter(connection -> name == null || name.equals(connection.getConnectionName()))
                .toList();
    }

    // ──────────────────────────── Services ────────────────────────────

    public AppRunnerServiceModel createService(String serviceName, JsonNode sourceConfiguration,
                                               JsonNode instanceConfiguration, JsonNode encryptionConfiguration,
                                               JsonNode healthCheckConfiguration,
                                               String autoScalingConfigurationArn, JsonNode networkConfiguration,
                                               JsonNode observabilityConfiguration,
                                               Map<String, String> requestTags, String region) {
        requireArgument(serviceName, "ServiceName");
        if (sourceConfiguration == null || !sourceConfiguration.isObject()) {
            throw new AwsException("InvalidRequestException", "SourceConfiguration is required.", 400);
        }
        boolean nameTaken = servicesInRegion(region).stream()
                .anyMatch(service -> serviceName.equals(service.getServiceName())
                        && !STATUS_DELETED.equals(service.getStatus()));
        if (nameTaken) {
            throw new AwsException("InvalidRequestException",
                    "A service named " + serviceName + " already exists.", 400);
        }

        String serviceId = randomHex(32);
        long now = Instant.now().getEpochSecond();

        AppRunnerServiceModel service = new AppRunnerServiceModel();
        service.setServiceName(serviceName);
        service.setServiceId(serviceId);
        service.setServiceArn(regionResolver.buildArn("apprunner", region,
                "service/" + serviceName + "/" + serviceId));
        service.setServiceUrl(randomSubdomain() + "." + region + ".awsapprunner.com");
        service.setCreatedAt(now);
        service.setUpdatedAt(now);
        service.setStatus(STATUS_RUNNING);
        service.setSourceConfiguration(normalizeSourceConfiguration(sourceConfiguration));
        service.setInstanceConfiguration(normalizeInstanceConfiguration(instanceConfiguration));
        service.setEncryptionConfiguration(encryptionConfiguration);
        service.setHealthCheckConfiguration(normalizeHealthCheckConfiguration(healthCheckConfiguration));
        service.setNetworkConfiguration(normalizeNetworkConfiguration(networkConfiguration));
        service.setObservabilityConfiguration(normalizeObservabilityConfiguration(observabilityConfiguration));
        service.setAutoScalingConfigurationSummary(
                resolveAutoScalingSummary(autoScalingConfigurationArn, region));

        services.put(service.getServiceArn(), service);
        putTags(service.getServiceArn(), requestTags);
        LOG.infov("Created App Runner service: {0}", service.getServiceArn());
        return service;
    }

    public AppRunnerServiceModel describeService(String arn) {
        requireArgument(arn, "ServiceArn");
        return services.get(arn)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Service " + arn + " does not exist.", 400));
    }

    public AppRunnerServiceModel updateService(String arn, JsonNode sourceConfiguration,
                                               JsonNode instanceConfiguration, String autoScalingConfigurationArn,
                                               JsonNode healthCheckConfiguration, JsonNode networkConfiguration,
                                               JsonNode observabilityConfiguration, String region) {
        AppRunnerServiceModel service = describeService(arn);
        requireUpdatable(service);
        if (sourceConfiguration != null && sourceConfiguration.isObject()) {
            service.setSourceConfiguration(normalizeSourceConfiguration(sourceConfiguration));
        }
        if (instanceConfiguration != null && instanceConfiguration.isObject()) {
            service.setInstanceConfiguration(normalizeInstanceConfiguration(instanceConfiguration));
        }
        if (healthCheckConfiguration != null && healthCheckConfiguration.isObject()) {
            service.setHealthCheckConfiguration(normalizeHealthCheckConfiguration(healthCheckConfiguration));
        }
        if (networkConfiguration != null && networkConfiguration.isObject()) {
            service.setNetworkConfiguration(normalizeNetworkConfiguration(networkConfiguration));
        }
        if (observabilityConfiguration != null && observabilityConfiguration.isObject()) {
            service.setObservabilityConfiguration(normalizeObservabilityConfiguration(observabilityConfiguration));
        }
        if (autoScalingConfigurationArn != null) {
            service.setAutoScalingConfigurationSummary(
                    resolveAutoScalingSummary(autoScalingConfigurationArn, region));
        }
        service.setUpdatedAt(Instant.now().getEpochSecond());
        services.put(arn, service);
        return service;
    }

    public AppRunnerServiceModel deleteService(String arn) {
        AppRunnerServiceModel service = describeService(arn);
        if (STATUS_DELETED.equals(service.getStatus())) {
            throw new AwsException("InvalidStateException",
                    "Service " + arn + " is already deleted.", 400);
        }
        long now = Instant.now().getEpochSecond();
        service.setStatus(STATUS_DELETED);
        service.setDeletedAt(now);
        service.setUpdatedAt(now);
        services.put(arn, service);
        LOG.infov("Deleted App Runner service: {0}", arn);
        return service;
    }

    public AppRunnerServiceModel pauseService(String arn) {
        AppRunnerServiceModel service = describeService(arn);
        requireUpdatable(service);
        service.setStatus(STATUS_PAUSED);
        service.setUpdatedAt(Instant.now().getEpochSecond());
        services.put(arn, service);
        return service;
    }

    public AppRunnerServiceModel resumeService(String arn) {
        AppRunnerServiceModel service = describeService(arn);
        if (STATUS_DELETED.equals(service.getStatus())) {
            throw new AwsException("InvalidStateException",
                    "Service " + arn + " is deleted and can't be resumed.", 400);
        }
        service.setStatus(STATUS_RUNNING);
        service.setUpdatedAt(Instant.now().getEpochSecond());
        services.put(arn, service);
        return service;
    }

    public AppRunnerServiceModel startDeployment(String arn) {
        AppRunnerServiceModel service = describeService(arn);
        requireUpdatable(service);
        service.setUpdatedAt(Instant.now().getEpochSecond());
        services.put(arn, service);
        return service;
    }

    public List<AppRunnerServiceModel> listServices(String region) {
        return servicesInRegion(region).stream()
                .filter(service -> !STATUS_DELETED.equals(service.getStatus()))
                .toList();
    }

    // ──────────────────────────── Operations ────────────────────────────

    public AppRunnerOperation recordOperation(String type, String targetArn) {
        long now = Instant.now().getEpochSecond();
        AppRunnerOperation operation = new AppRunnerOperation();
        operation.setId(UUID.randomUUID().toString());
        operation.setType(type);
        operation.setStatus(OPERATION_SUCCEEDED);
        operation.setTargetArn(targetArn);
        operation.setStartedAt(now);
        operation.setEndedAt(now);
        operation.setUpdatedAt(now);
        operations.put(operation.getId(), operation);
        return operation;
    }

    public List<AppRunnerOperation> listOperations(String serviceArn) {
        describeService(serviceArn);
        return operations.scan(k -> true).stream()
                .filter(operation -> serviceArn.equals(operation.getTargetArn()))
                .sorted(Comparator.comparing(AppRunnerOperation::getStartedAt).reversed())
                .toList();
    }

    // ──────────────────────────── Tags ────────────────────────────

    public Map<String, String> listTagsForResource(String resourceArn) {
        requireResourceExists(resourceArn);
        return tags.get(resourceArn).orElseGet(LinkedHashMap::new);
    }

    public void tagResource(String resourceArn, Map<String, String> newTags) {
        requireResourceExists(resourceArn);
        Map<String, String> existing = new LinkedHashMap<>(tags.get(resourceArn).orElseGet(LinkedHashMap::new));
        if (newTags != null) {
            existing.putAll(newTags);
        }
        tags.put(resourceArn, existing);
    }

    public void untagResource(String resourceArn, List<String> tagKeys) {
        requireResourceExists(resourceArn);
        Map<String, String> existing = new LinkedHashMap<>(tags.get(resourceArn).orElseGet(LinkedHashMap::new));
        if (tagKeys != null) {
            tagKeys.forEach(existing::remove);
        }
        tags.put(resourceArn, existing);
    }

    // ─────────────────────────── Helpers ───────────────────────────

    private void requireResourceExists(String resourceArn) {
        requireArgument(resourceArn, "ResourceArn");
        boolean known = services.get(resourceArn).isPresent()
                || vpcConnectors.get(resourceArn).isPresent()
                || connections.get(resourceArn).isPresent()
                || autoScalingConfigurations.get(resourceArn).isPresent()
                || observabilityConfigurations.get(resourceArn).isPresent()
                || vpcIngressConnections.get(resourceArn).isPresent();
        if (!known) {
            throw new AwsException("ResourceNotFoundException",
                    "Resource " + resourceArn + " does not exist.", 400);
        }
    }

    private static void requireUpdatable(AppRunnerServiceModel service) {
        if (STATUS_DELETED.equals(service.getStatus())) {
            throw new AwsException("InvalidStateException",
                    "Service " + service.getServiceArn() + " is deleted and can't be updated.", 400);
        }
    }

    private AppRunnerAutoScalingConfigurationSummary resolveAutoScalingSummary(String arn, String region) {
        AppRunnerAutoScalingConfiguration configuration = arn != null && !arn.isBlank()
                ? resolveAutoScalingConfiguration(arn, region)
                : ensureDefaultAutoScalingConfiguration(region);
        if (STATUS_INACTIVE.equals(configuration.getStatus())) {
            throw new AwsException("InvalidRequestException",
                    "The auto scaling configuration " + configuration.getAutoScalingConfigurationArn()
                            + " is inactive and can't be used.", 400);
        }
        configuration.setHasAssociatedService(true);
        autoScalingConfigurations.put(configuration.getAutoScalingConfigurationArn(), configuration);
        return AppRunnerAutoScalingConfigurationSummary.of(configuration);
    }

    private AppRunnerAutoScalingConfiguration ensureDefaultAutoScalingConfiguration(String region) {
        String arn = regionResolver.buildArn("apprunner", region,
                AUTO_SCALING_PREFIX + DEFAULT_CONFIGURATION_NAME + "/1/" + DEFAULT_CONFIGURATION_ID);
        return autoScalingConfigurations.get(arn).orElseGet(() -> {
            AppRunnerAutoScalingConfiguration configuration = new AppRunnerAutoScalingConfiguration();
            configuration.setAutoScalingConfigurationArn(arn);
            configuration.setAutoScalingConfigurationName(DEFAULT_CONFIGURATION_NAME);
            configuration.setAutoScalingConfigurationRevision(1);
            configuration.setLatest(true);
            configuration.setStatus(STATUS_ACTIVE);
            configuration.setMaxConcurrency(100);
            configuration.setMinSize(1);
            configuration.setMaxSize(25);
            configuration.setCreatedAt(Instant.now().getEpochSecond());
            configuration.setHasAssociatedService(false);
            configuration.setIsDefault(true);
            autoScalingConfigurations.put(arn, configuration);
            return configuration;
        });
    }

    /**
     * Resolves the three ARN forms App Runner accepts for an auto scaling configuration: the
     * full {@code name/revision/id} ARN a create returns, a {@code name/revision} ARN, and a
     * bare {@code name} ARN, which both select the highest active revision that matches.
     */
    private AppRunnerAutoScalingConfiguration resolveAutoScalingConfiguration(String arn, String region) {
        requireArgument(arn, "AutoScalingConfigurationArn");
        Optional<AppRunnerAutoScalingConfiguration> exact = autoScalingConfigurations.get(arn);
        if (exact.isPresent()) {
            return exact.get();
        }
        int prefixIndex = arn.indexOf(":" + AUTO_SCALING_PREFIX);
        if (prefixIndex < 0) {
            throw autoScalingNotFound(arn);
        }
        String[] segments = arn.substring(prefixIndex + AUTO_SCALING_PREFIX.length() + 1).split("/");
        String name = segments[0];
        Integer revision = null;
        if (segments.length > 1) {
            try {
                revision = Integer.valueOf(segments[1]);
            } catch (NumberFormatException e) {
                throw autoScalingNotFound(arn);
            }
        }
        Integer requestedRevision = revision;
        return autoScalingConfigurationsInRegion(region).stream()
                .filter(configuration -> name.equals(configuration.getAutoScalingConfigurationName()))
                .filter(configuration -> requestedRevision == null
                        || requestedRevision.equals(configuration.getAutoScalingConfigurationRevision()))
                .filter(configuration -> STATUS_ACTIVE.equals(configuration.getStatus()))
                .max(Comparator.comparing(AppRunnerAutoScalingConfiguration::getAutoScalingConfigurationRevision))
                .orElseThrow(() -> autoScalingNotFound(arn));
    }

    private static AwsException autoScalingNotFound(String arn) {
        return new AwsException("ResourceNotFoundException",
                "Auto scaling configuration " + arn + " does not exist.", 400);
    }

    /**
     * Resolves the same three ARN forms {@link #resolveAutoScalingConfiguration} does, applied
     * to observability configurations: the full {@code name/revision/id} ARN a create returns,
     * a {@code name/revision} ARN, and a bare {@code name} ARN, which both select the highest
     * active revision that matches.
     */
    private AppRunnerObservabilityConfiguration resolveObservabilityConfiguration(String arn, String region) {
        requireArgument(arn, "ObservabilityConfigurationArn");
        Optional<AppRunnerObservabilityConfiguration> exact = observabilityConfigurations.get(arn);
        if (exact.isPresent()) {
            return exact.get();
        }
        int prefixIndex = arn.indexOf(":" + OBSERVABILITY_PREFIX);
        if (prefixIndex < 0) {
            throw observabilityNotFound(arn);
        }
        String[] segments = arn.substring(prefixIndex + OBSERVABILITY_PREFIX.length() + 1).split("/");
        String name = segments[0];
        Integer revision = null;
        if (segments.length > 1) {
            try {
                revision = Integer.valueOf(segments[1]);
            } catch (NumberFormatException e) {
                throw observabilityNotFound(arn);
            }
        }
        Integer requestedRevision = revision;
        return observabilityConfigurationsInRegion(region).stream()
                .filter(configuration -> name.equals(configuration.getObservabilityConfigurationName()))
                .filter(configuration -> requestedRevision == null
                        || requestedRevision.equals(configuration.getObservabilityConfigurationRevision()))
                .filter(configuration -> STATUS_ACTIVE.equals(configuration.getStatus()))
                .max(Comparator.comparing(
                        AppRunnerObservabilityConfiguration::getObservabilityConfigurationRevision))
                .orElseThrow(() -> observabilityNotFound(arn));
    }

    private static AwsException observabilityNotFound(String arn) {
        return new AwsException("ResourceNotFoundException",
                "Observability configuration " + arn + " does not exist.", 400);
    }

    private int nextObservabilityRevision(String name, String region) {
        return observabilityConfigurationsInRegion(region).stream()
                .filter(configuration -> name.equals(configuration.getObservabilityConfigurationName()))
                .map(AppRunnerObservabilityConfiguration::getObservabilityConfigurationRevision)
                .max(Comparator.naturalOrder())
                .orElse(0) + 1;
    }

    private JsonNode normalizeTraceConfiguration(JsonNode requested) {
        if (requested == null || !requested.isObject()) {
            return null;
        }
        return requested.deepCopy();
    }

    private int nextRevision(String name, String region) {
        return autoScalingConfigurationsInRegion(region).stream()
                .filter(configuration -> name.equals(configuration.getAutoScalingConfigurationName()))
                .map(AppRunnerAutoScalingConfiguration::getAutoScalingConfigurationRevision)
                .max(Comparator.naturalOrder())
                .orElse(0) + 1;
    }

    private boolean hasAssociatedService(String autoScalingConfigurationArn, String region) {
        return servicesInRegion(region).stream()
                .filter(service -> !STATUS_DELETED.equals(service.getStatus()))
                .anyMatch(service -> service.getAutoScalingConfigurationSummary() != null
                        && autoScalingConfigurationArn.equals(
                                service.getAutoScalingConfigurationSummary().getAutoScalingConfigurationArn()));
    }

    private JsonNode normalizeSourceConfiguration(JsonNode requested) {
        ObjectNode source = requested.deepCopy();
        if (!source.has("AutoDeploymentsEnabled")) {
            String imageRepositoryType = source.path("ImageRepository").path("ImageRepositoryType").asText("");
            source.put("AutoDeploymentsEnabled", !"ECR_PUBLIC".equals(imageRepositoryType));
        }
        return source;
    }

    private JsonNode normalizeInstanceConfiguration(JsonNode requested) {
        ObjectNode instance = requested != null && requested.isObject()
                ? requested.deepCopy()
                : mapper.createObjectNode();
        if (!instance.has("Cpu")) {
            instance.put("Cpu", "1024");
        }
        if (!instance.has("Memory")) {
            instance.put("Memory", "2048");
        }
        return instance;
    }

    private JsonNode normalizeHealthCheckConfiguration(JsonNode requested) {
        ObjectNode healthCheck = requested != null && requested.isObject()
                ? requested.deepCopy()
                : mapper.createObjectNode();
        if (!healthCheck.has("Protocol")) {
            healthCheck.put("Protocol", "TCP");
        }
        if (!healthCheck.has("Path")) {
            healthCheck.put("Path", "/");
        }
        if (!healthCheck.has("Interval")) {
            healthCheck.put("Interval", 5);
        }
        if (!healthCheck.has("Timeout")) {
            healthCheck.put("Timeout", 2);
        }
        if (!healthCheck.has("HealthyThreshold")) {
            healthCheck.put("HealthyThreshold", 1);
        }
        if (!healthCheck.has("UnhealthyThreshold")) {
            healthCheck.put("UnhealthyThreshold", 5);
        }
        return healthCheck;
    }

    private JsonNode normalizeNetworkConfiguration(JsonNode requested) {
        ObjectNode network = requested != null && requested.isObject()
                ? requested.deepCopy()
                : mapper.createObjectNode();
        if (!network.has("EgressConfiguration")) {
            network.putObject("EgressConfiguration").put("EgressType", "DEFAULT");
        } else if (!network.path("EgressConfiguration").has("EgressType")) {
            ((ObjectNode) network.get("EgressConfiguration")).put("EgressType",
                    network.path("EgressConfiguration").has("VpcConnectorArn") ? "VPC" : "DEFAULT");
        }
        if (!network.has("IngressConfiguration")) {
            network.putObject("IngressConfiguration").put("IsPubliclyAccessible", true);
        }
        if (!network.has("IpAddressType")) {
            network.put("IpAddressType", "IPV4");
        }
        return network;
    }

    private JsonNode normalizeObservabilityConfiguration(JsonNode requested) {
        ObjectNode observability = requested != null && requested.isObject()
                ? requested.deepCopy()
                : mapper.createObjectNode();
        if (!observability.has("ObservabilityEnabled")) {
            observability.put("ObservabilityEnabled", false);
        }
        return observability;
    }

    private void putTags(String resourceArn, Map<String, String> requestTags) {
        tags.put(resourceArn, requestTags != null ? new LinkedHashMap<>(requestTags) : new LinkedHashMap<>());
    }

    private List<AppRunnerAutoScalingConfiguration> autoScalingConfigurationsInRegion(String region) {
        return autoScalingConfigurations.scan(k -> k.startsWith(arnPrefix(region)));
    }

    private List<AppRunnerVpcConnector> vpcConnectorsInRegion(String region) {
        return vpcConnectors.scan(k -> k.startsWith(arnPrefix(region)));
    }

    private List<AppRunnerConnection> connectionsInRegion(String region) {
        return connections.scan(k -> k.startsWith(arnPrefix(region)));
    }

    private List<AppRunnerServiceModel> servicesInRegion(String region) {
        return services.scan(k -> k.startsWith(arnPrefix(region)));
    }

    private List<AppRunnerObservabilityConfiguration> observabilityConfigurationsInRegion(String region) {
        return observabilityConfigurations.scan(k -> k.startsWith(arnPrefix(region)));
    }

    private List<AppRunnerVpcIngressConnection> vpcIngressConnectionsInRegion(String region) {
        return vpcIngressConnections.scan(k -> k.startsWith(arnPrefix(region)));
    }

    private static String arnPrefix(String region) {
        return "arn:aws:apprunner:" + region + ":";
    }

    private static void requireArgument(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new AwsException("InvalidRequestException", field + " is required.", 400);
        }
    }

    private static String randomHex(int length) {
        StringBuilder hex = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            hex.append(HEX.charAt(RANDOM.nextInt(HEX.length())));
        }
        return hex.toString().toLowerCase(Locale.ROOT);
    }

    private static String randomSubdomain() {
        StringBuilder subdomain = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            subdomain.append(SUBDOMAIN_ALPHABET.charAt(RANDOM.nextInt(SUBDOMAIN_ALPHABET.length())));
        }
        return subdomain.toString();
    }
}
