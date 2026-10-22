package io.github.hectorvent.floci.services.opensearch;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;
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
import io.github.hectorvent.floci.services.opensearch.model.VolumeOwnership;
import io.github.hectorvent.floci.services.opensearch.model.VpcOptions;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Predicate;

@ApplicationScoped
public class OpenSearchService implements ResourceProvider, Resettable {

    private static final Logger LOG = Logger.getLogger(OpenSearchService.class);

    private static final String DEFAULT_ENGINE_VERSION = OpenSearchVersions.DEFAULT_VERSION;

    private final StorageBackend<String, Domain> domainStore;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final OpenSearchDomainManager domainManager;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();
    /** Readiness writes hold the read lock; a reset takes the write lock to wait out the one in progress. */
    private final ReentrantReadWriteLock quiesceLock = new ReentrantReadWriteLock();
    private volatile boolean quiesced;
    /** Completed resets: a poll writes only while none is in progress and none completed since the poll began. */
    private volatile long resets;

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
        if (!config.services().opensearch().mock() && !config.services().opensearch().keepRunningOnShutdown()) {
            for (Domain domain : allDomains()) {
                domainManager.stopDomain(domain);
            }
        }
    }

    /**
     * The reset wipes every account's domain records, so their containers are stopped and their
     * host ports and storage released first, whatever keep-running-on-shutdown says: nothing would
     * be left to stop them by, or to give the ports back.
     */
    @Override
    public void beforeReset() {
        quiesceLock.writeLock().lock();
        try {
            quiesced = true;
        } finally {
            quiesceLock.writeLock().unlock();
        }
        if (config.services().opensearch().mock()) {
            return;
        }
        for (Domain domain : allDomains()) {
            try {
                domainManager.stopDomain(domain);
                domainManager.removeDomainStorage(domain);
            } catch (RuntimeException e) {
                LOG.warnv("Could not stop OpenSearch domain {0} for the reset: {1}",
                        domain.getDomainName(), e.getMessage());
            }
        }
    }

    @Override
    public void clear() {
        // Nothing to wipe: the domain records live in storage, which the reset has already cleared.
    }

    @Override
    public void afterReset() {
        quiesceLock.writeLock().lock();
        try {
            resets++;
            quiesced = false;
        } finally {
            quiesceLock.writeLock().unlock();
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
        return createDomain(domainName, engineVersion, clusterConfig, ebsOptions, tags, null,
                DomainOptions.EMPTY, region);
    }

    public Domain createDomain(String domainName, String engineVersion, ClusterConfig clusterConfig,
                                EbsOptions ebsOptions, Map<String, String> tags, String accessPolicies,
                                DomainOptions options, String region) {
        validateDomainName(domainName);
        OpenSearchVersions.validate(engineVersion);
        validateOptions(options);

        if (findDomain(domainName, region).isPresent()) {
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
        domain.setAccessPolicies(accessPolicies);
        domain.setProcessing(false);
        domain.setDeleted(false);
        domain.setEndpoint("");
        domain.setCreatedAt(Instant.now());
        domain.setVolumeId(String.format("%06x", new SecureRandom().nextInt(0xFFFFFF)));
        // Stamp the volume name now, with the current prefix, so it is persisted rather than
        // recomputed later. Only records predating this field fall back to the legacy name.
        domain.setDockerVolumeName(ContainerStorageHelper.resourceName(
                config, "opensearch", domain.getVolumeId(), domain.getDomainName()));
        // The ownership marker travels with the name, so deletion removes what was persisted
        // instead of recomputing ownership against whatever configuration holds at delete time.
        // A configured data volume overwrites both when startDomain mounts operator storage.
        domain.setVolumeOwnership(VolumeOwnership.FLOCI_MANAGED);

        applyClusterConfig(domain.getClusterConfig(), clusterConfig);
        applyEbsOptions(domain.getEbsOptions(), ebsOptions);
        if (tags != null) {
            domain.setTags(tags);
        }
        applyDomainOptions(domain, options);

        if (config.services().opensearch().mock()) {
            domain.setProcessing(false);
            domain.setEndpoint(synthesizedEndpoint(domainName, region));
        } else {
            domain.setProcessing(true);
            if (!domainManager.tryStartDomain(domain)) {
                domain.setProcessing(false);
                domain.setEndpoint(synthesizedEndpoint(domainName, region));
            }
        }

        putDomain(domain);
        LOG.infov("Created OpenSearch domain: {0}", domainName);
        return domain;
    }

    /**
     * The endpoint reported for a domain with no container behind it. Two cases reach this: the
     * service is mocked, and no Docker daemon is reachable, which is the only condition under which
     * {@link OpenSearchDomainManager#tryStartDomain} returns false rather than rethrowing.
     *
     * <p>It reports an AWS-shaped endpoint in place of a blank one,
     * {@code search-<domain>-<suffix>.<region>.es.<dnsSuffix>}, taking the DNS suffix from the
     * region's partition rather than assuming the commercial one, with the name suffix derived from the
     * domain name so it survives a restart. A domain reporting {@code Processing false} alongside
     * {@code Endpoint ""} describes a state AWS never returns, and a client that reads the endpoint
     * to address the domain gets an empty string to connect to.
     */
    private static String synthesizedEndpoint(String domainName, String region) {
        String suffix = Integer.toHexString(domainName.hashCode() & 0x7fffffff);
        return "search-" + domainName + "-" + suffix + "." + region + ".es."
                + AwsRegions.dnsSuffixFor(region);
    }

    public Domain describeDomain(String domainName, String region) {
        return findDomain(domainName, region)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Domain not found: " + domainName, 409));
    }

    public List<Domain> describeDomains(List<String> domainNames, String region) {
        return domainNames.stream()
                .map(name -> describeDomain(name, region))
                .toList();
    }

    public List<Domain> listDomainNames(String engineType, String region) {
        return domainStore.scan(k -> true).stream()
                .filter(d -> !d.isDeleted())
                .filter(d -> region.equals(regionOf(d)))
                .filter(d -> engineType == null || engineType.isBlank()
                        || matchesEngineType(d.getEngineVersion(), engineType))
                .toList();
    }

    public Domain updateDomainConfig(String domainName, String engineVersion,
                                      ClusterConfig clusterConfig, EbsOptions ebsOptions, String region) {
        return updateDomainConfig(domainName, engineVersion, clusterConfig, ebsOptions, null,
                DomainOptions.EMPTY, region);
    }

    public Domain updateDomainConfig(String domainName, String engineVersion,
                                      ClusterConfig clusterConfig, EbsOptions ebsOptions,
                                      String accessPolicies, DomainOptions options, String region) {
        Domain domain = describeDomain(domainName, region);
        OpenSearchVersions.validate(engineVersion);
        validateOptions(options);

        if (engineVersion != null && !engineVersion.isBlank()) {
            domain.setEngineVersion(engineVersion);
        }
        if (accessPolicies != null) {
            domain.setAccessPolicies(accessPolicies);
        }
        applyClusterConfig(domain.getClusterConfig(), clusterConfig);
        applyEbsOptions(domain.getEbsOptions(), ebsOptions);
        applyDomainOptions(domain, options);

        putDomain(domain);
        return domain;
    }

    /** Copies only the fields the request supplied (non-null) onto {@code existing}. */
    private static void applyClusterConfig(ClusterConfig existing, ClusterConfig supplied) {
        if (supplied == null) {
            return;
        }
        if (supplied.getInstanceType() != null) {
            existing.setInstanceType(supplied.getInstanceType());
        }
        if (supplied.getInstanceCount() != null && supplied.getInstanceCount() > 0) {
            existing.setInstanceCount(supplied.getInstanceCount());
        }
        if (supplied.isDedicatedMasterEnabled() != null) {
            existing.setDedicatedMasterEnabled(supplied.isDedicatedMasterEnabled());
        }
        if (supplied.isZoneAwarenessEnabled() != null) {
            existing.setZoneAwarenessEnabled(supplied.isZoneAwarenessEnabled());
        }
    }

    /** Copies only the fields the request supplied (non-null) onto {@code existing}. */
    private static void applyEbsOptions(EbsOptions existing, EbsOptions supplied) {
        if (supplied == null) {
            return;
        }
        if (supplied.isEbsEnabled() != null) {
            existing.setEbsEnabled(supplied.isEbsEnabled());
        }
        if (supplied.getVolumeType() != null) {
            existing.setVolumeType(supplied.getVolumeType());
        }
        if (supplied.getVolumeSize() != null && supplied.getVolumeSize() > 0) {
            existing.setVolumeSize(supplied.getVolumeSize());
        }
    }

    public Domain deleteDomain(String domainName, String region) {
        Domain domain = describeDomain(domainName, region);
        domain.setDeleted(true);
        if (!config.services().opensearch().mock()) {
            domainManager.stopDomain(domain);
            domainManager.removeDomainStorage(domain);
        }
        deleteStoredDomain(domain);
        LOG.infov("Deleted OpenSearch domain: {0}", domainName);
        return domain;
    }

    public void addTags(String arn, Map<String, String> tags) {
        Domain domain = findByArn(arn);
        domain.getTags().putAll(tags);
        putDomain(domain);
    }

    public Map<String, String> listTags(String arn) {
        return findByArn(arn).getTags();
    }

    public void removeTags(String arn, List<String> tagKeys) {
        Domain domain = findByArn(arn);
        tagKeys.forEach(domain.getTags()::remove);
        putDomain(domain);
    }

    public Domain upgradeDomain(String domainName, String targetVersion, String region) {
        Domain domain = describeDomain(domainName, region);
        if (targetVersion == null || targetVersion.isBlank()) {
            throw new AwsException("ValidationException",
                    "TargetVersion is required for UpgradeDomain.", 400);
        }
        OpenSearchVersions.validateUpgrade(domain.getEngineVersion(), targetVersion);
        domain.setEngineVersion(targetVersion);
        putDomain(domain);
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
        poller.scheduleWithFixedDelay(this::pollReadiness, 3, 3, TimeUnit.SECONDS);
    }

    void pollReadiness() {
        long resetsAtStart = resets;
        try {
            for (Domain domain : allDomains()) {
                if (domain.isProcessing() && domainManager.isReady(domain)) {
                    markReady(domain, resetsAtStart);
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("OpenSearch readiness poll failed; will retry", e);
        }
    }

    /** Skipped once a reset has begun since the poll read the domain, or it would write a wiped record back. */
    private void markReady(Domain domain, long resetsAtStart) {
        quiesceLock.readLock().lock();
        try {
            if (quiesced || resets != resetsAtStart) {
                return;
            }
            domain.setProcessing(false);
            putDomain(domain);
        } finally {
            quiesceLock.readLock().unlock();
        }
        LOG.infov("OpenSearch domain {0} is ready at {1}", domain.getDomainName(), domain.getEndpoint());
    }

    private List<Domain> allDomains() {
        if (domainStore instanceof AccountAwareStorageBackend<Domain> aware) {
            return aware.scanAllAccounts();
        }
        return domainStore.scan(k -> true);
    }

    /**
     * Domains are stored under {@code region::name}, since a domain name is unique only within an
     * account and Region. Records written before the key carried the Region sit under the bare name;
     * a lookup moves such a record to its regional key when its ARN names this account and Region.
     */
    private Optional<Domain> findDomain(String domainName, String region) {
        String key = domainKey(region, domainName);
        if (domainStore instanceof AccountAwareStorageBackend<Domain> aware) {
            String accountId = aware.accountId();
            return aware.getForAccountMigratingLegacyKeys(accountId, key, List.of(domainName),
                    ownedBy(accountId, region, domainName));
        }
        return domainStore.get(key);
    }

    /** Also drops the legacy name-only key when it still holds this domain, so a scan never sees it twice. */
    private void putDomain(Domain domain) {
        String region = regionOf(domain);
        String key = domainKey(region, domain.getDomainName());
        if (domainStore instanceof AccountAwareStorageBackend<Domain> aware) {
            String accountId = accountOf(domain);
            aware.getForAccountMigratingLegacyKeys(accountId, key, List.of(domain.getDomainName()),
                    ownedBy(accountId, region, domain.getDomainName()));
            aware.putForAccount(accountId, key, domain);
        } else {
            domainStore.put(key, domain);
        }
    }

    private void deleteStoredDomain(Domain domain) {
        String key = domainKey(regionOf(domain), domain.getDomainName());
        if (domainStore instanceof AccountAwareStorageBackend<Domain> aware) {
            aware.deleteForAccount(accountOf(domain), key);
        } else {
            domainStore.delete(key);
        }
    }

    private static String domainKey(String region, String domainName) {
        return region + "::" + domainName;
    }

    private Predicate<Domain> ownedBy(String accountId, String region, String domainName) {
        return domain -> domainName.equals(domain.getDomainName())
                && accountId.equals(accountOf(domain))
                && region.equals(regionOf(domain));
    }

    private String regionOf(Domain domain) {
        return AwsArnUtils.regionOrDefault(domain.getArn(), regionResolver.getDefaultRegion());
    }

    private String accountOf(Domain domain) {
        return AwsArnUtils.accountOrDefault(domain.getArn(), regionResolver.getDefaultAccountId());
    }

    @Override
    public List<ExplorerResource> getResources() {
        List<ExplorerResource> resources = new ArrayList<>();
        for (Domain domain : domainStore.scan(k -> true)) {
            if (domain.isDeleted() || domain.getArn() == null) {
                continue;
            }
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(domain.getArn());
            resources.add(new ExplorerResource(
                    domain.getArn(), "es:domain", "es",
                    parsed.region(), parsed.accountId(),
                    domain.getCreatedAt() != null ? domain.getCreatedAt() : Instant.now(),
                    domain.getTags() != null ? domain.getTags() : Map.of()));
        }
        return resources;
    }

    @Override
    public Set<SupportedResourceType> getSupportedResourceTypes() {
        return Set.of(new SupportedResourceType("es:domain", "es", true));
    }
}
