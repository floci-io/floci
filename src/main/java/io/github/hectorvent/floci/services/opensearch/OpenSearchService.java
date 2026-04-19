package io.github.hectorvent.floci.services.opensearch;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.opensearch.model.ClusterConfig;
import io.github.hectorvent.floci.services.opensearch.model.Domain;
import io.github.hectorvent.floci.services.opensearch.model.EbsOptions;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class OpenSearchService {

    private static final Logger LOG = Logger.getLogger(OpenSearchService.class);

    private static final String DEFAULT_ENGINE_VERSION = "OpenSearch_2.11";

    private final StorageBackend<String, Domain> domainStore;
    private final EmulatorConfig config;
    private final OpenSearchDomainManager domainManager;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();

    @Inject
    public OpenSearchService(StorageFactory storageFactory, EmulatorConfig config,
                             OpenSearchDomainManager domainManager) {
        this.domainStore = storageFactory.create("opensearch", "opensearch-domains.json",
                new TypeReference<Map<String, Domain>>() {});
        this.config = config;
        this.domainManager = domainManager;
    }

    OpenSearchService(StorageBackend<String, Domain> domainStore, EmulatorConfig config,
                      OpenSearchDomainManager domainManager) {
        this.domainStore = domainStore;
        this.config = config;
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
            for (Domain domain : domainStore.scan(k -> true)) {
                domainManager.stopDomain(domain);
            }
        }
    }

    public Domain createDomain(String domainName, String engineVersion, ClusterConfig clusterConfig,
                                EbsOptions ebsOptions, Map<String, String> tags, String region) {
        validateDomainName(domainName);

        if (domainStore.get(domainName).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsException",
                    "Domain with name " + domainName + " already exists.", 409);
        }

        String accountId = config.defaultAccountId();
        Domain domain = new Domain();
        domain.setDomainName(domainName);
        domain.setDomainId(accountId + "/" + domainName);
        domain.setArn("arn:aws:es:" + region + ":" + accountId + ":domain/" + domainName);
        domain.setEngineVersion(engineVersion != null ? engineVersion : DEFAULT_ENGINE_VERSION);
        domain.setProcessing(false);
        domain.setDeleted(false);
        domain.setEndpoint("");
        domain.setCreatedAt(Instant.now());

        if (clusterConfig != null) {
            domain.setClusterConfig(clusterConfig);
        }
        if (ebsOptions != null) {
            domain.setEbsOptions(ebsOptions);
        }
        if (tags != null) {
            domain.setTags(tags);
        }

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
        Domain domain = describeDomain(domainName);

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

        domainStore.put(domainName, domain);
        return domain;
    }

    public Domain deleteDomain(String domainName) {
        Domain domain = describeDomain(domainName);
        domain.setDeleted(true);
        if (!config.services().opensearch().mock()) {
            domainManager.stopDomain(domain);
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
        if (targetVersion != null && !targetVersion.isBlank()) {
            domain.setEngineVersion(targetVersion);
            domainStore.put(domainName, domain);
        }
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

    private boolean matchesEngineType(String engineVersion, String engineType) {
        if ("Elasticsearch".equalsIgnoreCase(engineType)) {
            return engineVersion != null && engineVersion.startsWith("Elasticsearch");
        }
        return engineVersion == null || engineVersion.startsWith("OpenSearch");
    }

    private void startReadinessPoller() {
        poller.scheduleWithFixedDelay(() -> {
            for (Domain domain : domainStore.scan(k -> true)) {
                if (domain.isProcessing() && domainManager.isReady(domain)) {
                    domain.setProcessing(false);
                    domainStore.put(domain.getDomainName(), domain);
                    LOG.infov("OpenSearch domain {0} is ready at {1}",
                            domain.getDomainName(), domain.getEndpoint());
                }
            }
        }, 3, 3, TimeUnit.SECONDS);
    }
}
