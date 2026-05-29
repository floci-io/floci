package io.github.hectorvent.floci.services.opensearch;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.opensearch.model.AdvancedSecurityOptions;
import io.github.hectorvent.floci.services.opensearch.model.ClusterConfig;
import io.github.hectorvent.floci.services.opensearch.model.Domain;
import io.github.hectorvent.floci.services.opensearch.model.DomainEndpointOptions;
import io.github.hectorvent.floci.services.opensearch.model.EbsOptions;
import io.github.hectorvent.floci.services.opensearch.model.EncryptionAtRestOptions;
import io.github.hectorvent.floci.services.opensearch.model.NodeToNodeEncryptionOptions;
import io.github.hectorvent.floci.services.opensearch.model.VpcOptions;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class OpenSearchService {

    private static final Logger LOG = Logger.getLogger(OpenSearchService.class);

    private static final String DEFAULT_ENGINE_VERSION = OpenSearchVersions.DEFAULT_VERSION;

    private final StorageBackend<String, Domain> domainStore;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final OpenSearchDomainManager domainManager;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();

    @Inject
    public OpenSearchService(StorageFactory storageFactory, EmulatorConfig config,
                             RegionResolver regionResolver, OpenSearchDomainManager domainManager) {
        this.domainStore = storageFactory.create("opensearch", "opensearch-domains.json",
                new TypeReference<Map<String, Domain>>() {});
        this.config = config;
        this.regionResolver = regionResolver;
        this.domainManager = domainManager;
    }

    OpenSearchService(StorageBackend<String, Domain> domainStore, EmulatorConfig config,
                      RegionResolver regionResolver, OpenSearchDomainManager domainManager) {
        this.domainStore = domainStore;
        this.config = config;
        this.regionResolver = regionResolver;
        this.domainManager = domainManager;
    }

    @PostConstruct
    public void init() {
        if (!config.services().opensearch().mock()) {
            startReadinessPoller();
        }
    }

    @PreDestroy
    public void shutdown() {
        poller.shutdownNow();
        if (!config.services().opensearch().mock()) {
            for (Domain domain : allDomains()) {
                domainManager.stopDomain(domain);
            }
        }
    }

    /**
     * Bag of optional configuration blocks parsed by {@link OpenSearchController}
     * and round-tripped on Describe. Any field can be null when the request
     * omitted the corresponding block — the service treats null as "leave the
     * existing value untouched" on update and "feature unset" on create.
     */
    public record DomainOptions(
            VpcOptions vpcOptions,
            AdvancedSecurityOptions advancedSecurityOptions,
            EncryptionAtRestOptions encryptionAtRestOptions,
            NodeToNodeEncryptionOptions nodeToNodeEncryptionOptions,
            DomainEndpointOptions domainEndpointOptions) {

        public static final DomainOptions EMPTY = new DomainOptions(null, null, null, null, null);
    }

    public Domain createDomain(String domainName, String engineVersion, ClusterConfig clusterConfig,
                                EbsOptions ebsOptions, Map<String, String> tags, String region) {
        return createDomain(domainName, engineVersion, clusterConfig, ebsOptions, tags,
                DomainOptions.EMPTY, region);
    }

    public Domain createDomain(String domainName, String engineVersion, ClusterConfig clusterConfig,
                                EbsOptions ebsOptions, Map<String, String> tags,
                                DomainOptions options, String region) {
        validateDomainName(domainName);
        OpenSearchVersions.validate(engineVersion);
        validateOptions(options);

        if (domainStore.get(domainName).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsException",
                    "Domain with name " + domainName + " already exists.", 409);
        }

        String accountId = regionResolver.getAccountId();
        Domain domain = new Domain();
        domain.setDomainName(domainName);
        domain.setDomainId(accountId + "/" + domainName);
        domain.setAccountId(accountId);
        domain.setArn(AwsArnUtils.Arn.of("es", region, accountId, "domain/" + domainName).toString());
        domain.setEngineVersion(engineVersion != null ? engineVersion : DEFAULT_ENGINE_VERSION);
        domain.setProcessing(false);
        domain.setDeleted(false);
        domain.setEndpoint("");
        domain.setCreatedAt(Instant.now());
        domain.setVolumeId(String.format("%06x", new SecureRandom().nextInt(0xFFFFFF)));

        if (clusterConfig != null) {
            domain.setClusterConfig(clusterConfig);
        }
        if (ebsOptions != null) {
            domain.setEbsOptions(ebsOptions);
        }
        if (tags != null) {
            domain.setTags(tags);
        }
        applyDomainOptions(domain, options);

        if (config.services().opensearch().mock()) {
            domain.setProcessing(false);
        } else {
            domain.setProcessing(true);
            domainManager.startDomain(domain);
        }

        domainStore.put(domainName, domain);
        LOG.infov("Created OpenSearch domain: {0}", domainName);
        return domain;
    }

    public Domain describeDomain(String domainName) {
        return domainStore.get(domainName)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Domain not found: " + domainName, 409));
    }

    public List<Domain> describeDomains(List<String> domainNames) {
        return domainNames.stream()
                .map(name -> domainStore.get(name)
                        .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                                "Domain not found: " + name, 409)))
                .toList();
    }

    public List<Domain> listDomainNames(String engineType) {
        return domainStore.scan(k -> true).stream()
                .filter(d -> !d.isDeleted())
                .filter(d -> engineType == null || engineType.isBlank()
                        || matchesEngineType(d.getEngineVersion(), engineType))
                .toList();
    }

    public Domain updateDomainConfig(String domainName, String engineVersion,
                                      ClusterConfig clusterConfig, EbsOptions ebsOptions,
                                      String region) {
        return updateDomainConfig(domainName, engineVersion, clusterConfig, ebsOptions,
                DomainOptions.EMPTY, region);
    }

    public Domain updateDomainConfig(String domainName, String engineVersion,
                                      ClusterConfig clusterConfig, EbsOptions ebsOptions,
                                      DomainOptions options, String region) {
        Domain domain = describeDomain(domainName);
        OpenSearchVersions.validate(engineVersion);
        validateOptions(options);

        if (engineVersion != null && !engineVersion.isBlank()) {
            domain.setEngineVersion(engineVersion);
        }
        if (clusterConfig != null) {
            ClusterConfig existing = domain.getClusterConfig();
            if (clusterConfig.getInstanceType() != null) {
                existing.setInstanceType(clusterConfig.getInstanceType());
            }
            if (clusterConfig.getInstanceCount() > 0) {
                existing.setInstanceCount(clusterConfig.getInstanceCount());
            }
            existing.setDedicatedMasterEnabled(clusterConfig.isDedicatedMasterEnabled());
            existing.setZoneAwarenessEnabled(clusterConfig.isZoneAwarenessEnabled());
        }
        if (ebsOptions != null) {
            EbsOptions existing = domain.getEbsOptions();
            existing.setEbsEnabled(ebsOptions.isEbsEnabled());
            if (ebsOptions.getVolumeType() != null) {
                existing.setVolumeType(ebsOptions.getVolumeType());
            }
            if (ebsOptions.getVolumeSize() > 0) {
                existing.setVolumeSize(ebsOptions.getVolumeSize());
            }
        }
        applyDomainOptions(domain, options);

        domainStore.put(domainName, domain);
        return domain;
    }

    public Domain deleteDomain(String domainName) {
        Domain domain = describeDomain(domainName);
        domain.setDeleted(true);
        if (!config.services().opensearch().mock()) {
            domainManager.stopDomain(domain);
            domainManager.removeDomainStorage(domain);
        }
        domainStore.delete(domainName);
        LOG.infov("Deleted OpenSearch domain: {0}", domainName);
        return domain;
    }

    public void addTags(String arn, Map<String, String> tags) {
        Domain domain = findByArn(arn);
        domain.getTags().putAll(tags);
        domainStore.put(domain.getDomainName(), domain);
    }

    public Map<String, String> listTags(String arn) {
        return findByArn(arn).getTags();
    }

    public void removeTags(String arn, List<String> tagKeys) {
        Domain domain = findByArn(arn);
        tagKeys.forEach(domain.getTags()::remove);
        domainStore.put(domain.getDomainName(), domain);
    }

    public Domain upgradeDomain(String domainName, String targetVersion) {
        Domain domain = describeDomain(domainName);
        if (targetVersion == null || targetVersion.isBlank()) {
            throw new AwsException("ValidationException",
                    "TargetVersion is required for UpgradeDomain.", 400);
        }
        OpenSearchVersions.validateUpgrade(domain.getEngineVersion(), targetVersion);
        domain.setEngineVersion(targetVersion);
        domainStore.put(domainName, domain);
        return domain;
    }

    private Domain findByArn(String arn) {
        return domainStore.scan(k -> true).stream()
                .filter(d -> arn.equals(d.getArn()))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Domain not found for ARN: " + arn, 409));
    }

    private void validateDomainName(String name) {
        if (name == null || name.length() < 3 || name.length() > 28) {
            throw new AwsException("ValidationException",
                    "Domain name must be between 3 and 28 characters.", 400);
        }
        if (!name.matches("[a-z][a-z0-9\\-]*")) {
            throw new AwsException("ValidationException",
                    "Domain name must start with a lowercase letter and contain only lowercase letters, numbers, and hyphens.", 400);
        }
    }

    /**
     * Cross-block validation: only the cases where a wrong combination is
     * deterministically rejected by AWS land here. Per-field syntax checks
     * stay in the controller's parsers.
     */
    private void validateOptions(DomainOptions options) {
        if (options == null) {
            return;
        }
        VpcOptions vpc = options.vpcOptions();
        if (vpc != null && !vpc.getSubnetIds().isEmpty() && vpc.getSubnetIds().stream().anyMatch(String::isBlank)) {
            throw new AwsException("ValidationException",
                    "VPCOptions.SubnetIds may not contain blank entries.", 400);
        }
        AdvancedSecurityOptions adv = options.advancedSecurityOptions();
        if (adv != null && adv.isEnabled() && adv.isInternalUserDatabaseEnabled()) {
            // AWS rejects internal user db without a master user — keep
            // emulator-side parity so Terraform plans surface the same error.
            if (adv.getMasterUserOptions() == null
                    || adv.getMasterUserOptions().getMasterUserName() == null
                    || adv.getMasterUserOptions().getMasterUserName().isBlank()) {
                throw new AwsException("ValidationException",
                        "AdvancedSecurityOptions.MasterUserOptions.MasterUserName is required "
                                + "when InternalUserDatabaseEnabled=true.", 400);
            }
        }
        DomainEndpointOptions deo = options.domainEndpointOptions();
        if (deo != null && deo.isCustomEndpointEnabled()
                && (deo.getCustomEndpoint() == null || deo.getCustomEndpoint().isBlank())) {
            throw new AwsException("ValidationException",
                    "DomainEndpointOptions.CustomEndpoint is required when CustomEndpointEnabled=true.", 400);
        }
    }

    /** Copy non-null fields from {@code options} onto {@code domain}. Null leaves the field untouched. */
    private void applyDomainOptions(Domain domain, DomainOptions options) {
        if (options == null) {
            return;
        }
        if (options.vpcOptions() != null) {
            domain.setVpcOptions(options.vpcOptions());
        }
        if (options.advancedSecurityOptions() != null) {
            domain.setAdvancedSecurityOptions(options.advancedSecurityOptions());
        }
        if (options.encryptionAtRestOptions() != null) {
            domain.setEncryptionAtRestOptions(options.encryptionAtRestOptions());
        }
        if (options.nodeToNodeEncryptionOptions() != null) {
            domain.setNodeToNodeEncryptionOptions(options.nodeToNodeEncryptionOptions());
        }
        if (options.domainEndpointOptions() != null) {
            domain.setDomainEndpointOptions(options.domainEndpointOptions());
        }
    }

    private boolean matchesEngineType(String engineVersion, String engineType) {
        if ("Elasticsearch".equalsIgnoreCase(engineType)) {
            return engineVersion != null && engineVersion.startsWith("Elasticsearch");
        }
        return engineVersion == null || engineVersion.startsWith("OpenSearch");
    }

    private void startReadinessPoller() {
        poller.scheduleWithFixedDelay(() -> {
            for (Domain domain : allDomains()) {
                if (domain.isProcessing() && domainManager.isReady(domain)) {
                    domain.setProcessing(false);
                    putDomain(domain);
                    LOG.infov("OpenSearch domain {0} is ready at {1}",
                            domain.getDomainName(), domain.getEndpoint());
                }
            }
        }, 3, 3, TimeUnit.SECONDS);
    }

    private List<Domain> allDomains() {
        if (domainStore instanceof AccountAwareStorageBackend<Domain> aware) {
            return aware.scanAllAccounts();
        }
        return domainStore.scan(k -> true);
    }

    private void putDomain(Domain domain) {
        if (domain.getAccountId() != null && domainStore instanceof AccountAwareStorageBackend<Domain> aware) {
            aware.putForAccount(domain.getAccountId(), domain.getDomainName(), domain);
        } else {
            domainStore.put(domain.getDomainName(), domain);
        }
    }
}
