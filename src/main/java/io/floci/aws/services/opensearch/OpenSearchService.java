package io.floci.aws.services.opensearch;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.StoredResourceArns;
import io.floci.aws.core.common.StripedLocks;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.resource.ExplorerResource;
import io.floci.aws.core.resource.ResourceProvider;
import io.floci.aws.core.resource.SupportedResourceType;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.opensearch.model.AdvancedSecurityOptions;
import io.floci.aws.services.opensearch.model.ClusterConfig;
import io.floci.aws.services.opensearch.model.Domain;
import io.floci.aws.services.opensearch.model.DomainEndpointOptions;
import io.floci.aws.services.opensearch.model.EbsOptions;
import io.floci.aws.services.opensearch.model.EncryptionAtRestOptions;
import io.floci.aws.services.opensearch.model.NodeToNodeEncryptionOptions;
import io.floci.aws.services.opensearch.model.OffPeakWindowOptions;
import io.floci.aws.services.opensearch.model.SoftwareUpdateOptions;
import io.floci.aws.services.opensearch.model.VolumeOwnership;
import io.floci.aws.services.opensearch.model.VpcOptions;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Domain metadata, scoped to an account and a region the way AWS scopes it: a domain name is
 * unique within one account and region, and every lookup (describe, update, delete, list)
 * resolves inside the caller's region only. The storage key carries the region next to the
 * name; records persisted before the region joined the key are migrated onto it, taking the
 * region from the domain's ARN, which has always carried it.
 */
@ApplicationScoped
public class OpenSearchService implements ResourceProvider, StoredResourceArns {

    private static final Logger LOG = Logger.getLogger(OpenSearchService.class);

    private static final String DEFAULT_ENGINE_VERSION = OpenSearchVersions.DEFAULT_VERSION;

    private final AccountAwareStorageBackend<Domain> domainStore;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final OpenSearchDomainManager domainManager;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();
    // The account and storage key of each domain a createDomain is creating, from its existence
    // check to its save, so a concurrent create of the same name is refused and starts no container.
    private final Set<String> creating = ConcurrentHashMap.newKeySet();
    /**
     * The domains the startup restore is bringing back, by account and storage key, which the readiness poller leaves
     * alone: their records still name the endpoint they had before Floci restarted until the restore keeps their
     * container or starts a new one.
     */
    private final Set<String> restoringDomains = ConcurrentHashMap.newKeySet();
    /**
     * Monitors over one stored domain, by account and storage key. The restore reads a domain again, and ends its
     * restore, under its lock, under which {@code DeleteDomain} finds whether a restore is starting the domain's
     * container. If one is, the delete leaves the domain {@code Deleted} for the restore to finish once that start is
     * through, so the restore never writes a deleted domain back or leaves its container running.
     */
    private final StripedLocks storedDomainLocks = new StripedLocks(256);

    @Inject
    public OpenSearchService(StorageFactory storageFactory, EmulatorConfig config,
                             RegionResolver regionResolver, OpenSearchDomainManager domainManager) {
        this.domainStore = storageFactory.create("opensearch", "opensearch-domains.json",
                new TypeReference<Map<String, Domain>>() {});
        this.config = config;
        this.regionResolver = regionResolver;
        this.domainManager = domainManager;
    }

    OpenSearchService(AccountAwareStorageBackend<Domain> domainStore, EmulatorConfig config,
                      RegionResolver regionResolver, OpenSearchDomainManager domainManager) {
        this.domainStore = domainStore;
        this.config = config;
        this.regionResolver = regionResolver;
        this.domainManager = domainManager;
    }

    @PostConstruct
    public void init() {
        migrateLegacyDomainKeys();
        if (!config.services().opensearch().mock()) {
            startReadinessPoller();
        }
    }

    @PreDestroy
    public void shutdown() {
        poller.shutdownNow();
        if (config.services().opensearch().mock()) {
            return;
        }
        // First, so a restore still starting containers stops, and a start it overtakes removes its own.
        domainManager.markShuttingDown();
        if (!config.services().opensearch().keepRunningOnShutdown()) {
            for (Domain domain : allDomains()) {
                domainManager.stopDomain(domain);
            }
        }
    }

    /**
     * Brings the persisted domains' containers back after Floci restarts. Shutdown removes them, unless
     * {@code keep-running-on-shutdown} keeps them, so a domain read back from storage named an endpoint nothing
     * answered on. Each domain goes back to {@code Processing} at once, and the containers come back in the
     * background, so Floci does not wait for them to report itself ready; the readiness poller leaves a domain alone
     * while its restore runs, and reports it ready once its container answers.
     *
     * <p>A container that outlived Floci, kept running or left by a Floci that was killed, is kept, found by the ID
     * the record names. Otherwise the domain gets a new container, as {@code CreateDomain} starts one, on the data it
     * kept: its Docker volume or data directory, so its indices are back. A domain with no reachable Docker daemon is
     * not processing and reports an AWS-shaped endpoint, as one created then does. A domain deleted during its restore
     * is {@code Deleted} until its container has started and been removed, as AWS reports a domain being deleted, and
     * is gone then; one still {@code Deleted} at the next start has its container and data removed then. Every domain
     * is tried, and the ones that could not be restored fail the returned future together. A shutdown during the
     * restore leaves the domains not started yet as they are, for the next start.
     */
    public CompletableFuture<Void> restorePersistedRuntime() {
        if (config.services().opensearch().mock()) {
            return CompletableFuture.completedFuture(null);
        }
        // Records stored before domains were keyed by Region move onto their keys first, as at startup.
        migrateLegacyDomainKeys();
        List<RestoringDomain> pending = new ArrayList<>();
        for (AccountAwareStorageBackend.AccountEntry<Domain> entry
                : domainStore.scanAllAccountEntries(key -> key.contains("::"))) {
            RestoringDomain restoring = prepareRestore(entry.accountId(), entry.key());
            if (restoring != null) {
                pending.add(restoring);
            }
        }
        return CompletableFuture.runAsync(() -> {
            RuntimeException failure = null;
            for (RestoringDomain restoring : pending) {
                if (domainManager.isShuttingDown()) {
                    // Domains not reached yet stay as they are, which the next start restores.
                    break;
                }
                Domain domain = restoring.domain();
                try {
                    restoreDomain(restoring);
                } catch (OpenSearchDomainManager.ShuttingDownException e) {
                    LOG.debugv("OpenSearch domain {0} not restored: {1}", domain.getDomainName(), e.getMessage());
                } catch (RuntimeException e) {
                    RuntimeException restoreFailure = new IllegalStateException("Could not restore OpenSearch domain "
                            + domain.getDomainName() + " of account " + restoring.accountId(), e);
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

    /** Marks a domain the restore brings back, under its lock; null for one it leaves as it is. */
    private RestoringDomain prepareRestore(String accountId, String key) {
        synchronized (storedDomainLock(accountId, key)) {
            Domain domain = domainStore.getForAccount(accountId, key).orElse(null);
            if (domain == null) {
                return null;
            }
            String region;
            try {
                region = AwsArnUtils.parse(domain.getArn()).region();
            } catch (IllegalArgumentException e) {
                LOG.warnv("Not restoring OpenSearch domain {0}: its ARN {1} cannot be parsed",
                        domain.getDomainName(), domain.getArn());
                return null;
            }
            RestoringDomain restoring = new RestoringDomain(accountId, key, region, domain);
            restoringDomains.add(restoring.lockKey());
            // A domain still Deleted was deleted during a restore Floci did not live to finish: the delete stands.
            if (!domain.isDeleted()) {
                domain.setProcessing(true);
                domainStore.putForAccount(accountId, key, domain);
            }
            return restoring;
        }
    }

    private void restoreDomain(RestoringDomain restoring) {
        Domain domain = restoring.domain();
        Object lock = storedDomainLock(restoring.accountId(), restoring.key());
        Boolean deleted;
        synchronized (lock) {
            deleted = storedDeleted(restoring);
        }
        if (deleted == null) {
            return;
        }
        if (deleted) {
            finishDelete(restoring);
            return;
        }
        boolean running;
        try {
            running = domainManager.adoptRunningDomain(domain) || domainManager.tryRestartDomain(domain);
        } catch (OpenSearchDomainManager.ShuttingDownException e) {
            throw e;
        } catch (RuntimeException e) {
            // A container Docker would not start: the domain has none behind it, as when no daemon is reachable.
            Boolean deletedNow;
            synchronized (lock) {
                deletedNow = storedDeleted(restoring);
                if (Boolean.FALSE.equals(deletedNow)) {
                    restoringDomains.remove(restoring.lockKey());
                    reportNoContainer(domain, restoring.region());
                    domainStore.putForAccount(restoring.accountId(), restoring.key(), domain);
                }
            }
            if (Boolean.TRUE.equals(deletedNow)) {
                finishDelete(restoring);
            }
            throw e;
        }
        Boolean deletedNow;
        synchronized (lock) {
            deletedNow = storedDeleted(restoring);
            if (Boolean.FALSE.equals(deletedNow)) {
                // Its restore ends here: from now on the poller reports it ready, and a delete removes the container.
                restoringDomains.remove(restoring.lockKey());
                if (!running) {
                    reportNoContainer(domain, restoring.region());
                }
                domainStore.putForAccount(restoring.accountId(), restoring.key(), domain);
                return;
            }
        }
        if (deletedNow == null) {
            // Its record went, or another domain took its name, with no delete of its own: only the container this
            // restore kept or started goes, by its ID, leaving the name, the record and the data to whoever has them.
            domainManager.stopDomain(domain);
            return;
        }
        // Deleted while its container started: the delete finishes now the container is there to remove.
        finishDelete(restoring);
    }

    /** A domain with no container behind it, as {@link #createDomain} reports one with no Docker daemon reachable. */
    private static void reportNoContainer(Domain domain, String region) {
        domain.setProcessing(false);
        domain.setEndpoint(synthesizedEndpoint(domain, region));
    }

    /**
     * Finishes the delete of a domain deleted during its restore: its container and data go, then its record. While
     * Docker keeps the container this throws, and the record stays {@code Deleted}, naming it, for the next start.
     */
    private void finishDelete(RestoringDomain restoring) {
        Domain domain = restoring.domain();
        domainManager.removeDomainContainers(domain);
        domainManager.removeDomainStorage(domain);
        synchronized (storedDomainLock(restoring.accountId(), restoring.key())) {
            restoringDomains.remove(restoring.lockKey());
            if (Boolean.TRUE.equals(storedDeleted(restoring))) {
                domainStore.deleteForAccount(restoring.accountId(), restoring.key());
            }
        }
        LOG.infov("Deleted OpenSearch domain: {0}", domain.getDomainName());
    }

    /**
     * Ends a domain's restore, if it has not ended already: the poller and {@code DeleteDomain} treat the domain as any
     * other again.
     */
    private void endRestore(RestoringDomain restoring) {
        synchronized (storedDomainLock(restoring.accountId(), restoring.key())) {
            restoringDomains.remove(restoring.lockKey());
        }
    }

    /**
     * Whether the stored domain is deleted, or null when it is gone or another domain was created under its name.
     */
    private Boolean storedDeleted(RestoringDomain restoring) {
        return domainStore.getForAccount(restoring.accountId(), restoring.key())
                .filter(current -> Objects.equals(current.getCreatedAt(), restoring.domain().getCreatedAt()))
                .map(Domain::isDeleted)
                .orElse(null);
    }

    private Object storedDomainLock(String accountId, String key) {
        return storedDomainLocks.lockFor(accountId + "/" + key);
    }

    /** A domain the restore brings back, with the account, storage key and Region it is stored under. */
    private record RestoringDomain(String accountId, String key, String region, Domain domain) {
        String lockKey() {
            return accountId + "/" + key;
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
            DomainEndpointOptions domainEndpointOptions,
            Map<String, String> advancedOptions,
            String ipAddressType,
            OffPeakWindowOptions offPeakWindowOptions,
            SoftwareUpdateOptions softwareUpdateOptions) {

        public static final DomainOptions EMPTY = new DomainOptions(null, null, null, null, null);

        public DomainOptions(VpcOptions vpcOptions, AdvancedSecurityOptions advancedSecurityOptions,
                             EncryptionAtRestOptions encryptionAtRestOptions,
                             NodeToNodeEncryptionOptions nodeToNodeEncryptionOptions,
                             DomainEndpointOptions domainEndpointOptions) {
            this(vpcOptions, advancedSecurityOptions, encryptionAtRestOptions, nodeToNodeEncryptionOptions,
                    domainEndpointOptions, null, null, null, null);
        }
    }

    /** AWS's address type for a domain whose request names none. */
    static final String DEFAULT_IP_ADDRESS_TYPE = "ipv4";
    /**
     * The start AWS gives an off-peak window the request leaves unset: 10:00 P.M., which AWS
     * reads as the Region's local time. Floci keeps no time zone per Region and reports it in UTC.
     */
    static final long DEFAULT_OFF_PEAK_START_HOURS = 22;

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

        String accountId = regionResolver.getAccountId();
        Domain domain = new Domain();
        domain.setDomainName(domainName);
        domain.setDomainId(accountId + "/" + domainName);
        domain.setAccountId(accountId);
        domain.setRegion(region);
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
        applyCreateDefaults(domain);

        String creatingKey = accountId + "/" + storageKey(region, domainName);
        if (!creating.add(creatingKey)) {
            throw domainAlreadyExists(domainName);
        }
        try {
            if (findDomain(accountId, region, domainName).isPresent()) {
                throw domainAlreadyExists(domainName);
            }
            if (config.services().opensearch().mock()) {
                domain.setProcessing(false);
                domain.setEndpoint(synthesizedEndpoint(domain, region));
            } else {
                domain.setProcessing(true);
                if (!domainManager.tryStartDomain(domain)) {
                    domain.setProcessing(false);
                    domain.setEndpoint(synthesizedEndpoint(domain, region));
                }
            }
            domainStore.putForAccount(accountId, storageKey(region, domainName), domain);
        } finally {
            creating.remove(creatingKey);
        }
        LOG.infov("Created OpenSearch domain: {0}", domainName);
        return domain;
    }

    /**
     * The endpoint reported for a domain with no container behind it. Two cases reach this: the
     * service is mocked, and no Docker daemon is reachable, which is the only condition under which
     * {@link OpenSearchDomainManager#tryStartDomain} returns false rather than rethrowing.
     *
     * <p>It reports an AWS-shaped endpoint in place of a blank one,
     * {@code search-<domain>-<suffix>.<region>.es.<dnsSuffix>}, or {@code vpc-<domain>-...} for a domain in a
     * VPC, taking the DNS suffix from the region's partition rather than assuming the commercial one, with the
     * name suffix derived from the domain name so it survives a restart. A domain reporting {@code Processing false} alongside
     * {@code Endpoint ""} describes a state AWS never returns, and a client that reads the endpoint
     * to address the domain gets an empty string to connect to.
     */
    private static String synthesizedEndpoint(Domain domain, String region) {
        String domainName = domain.getDomainName();
        String suffix = Integer.toHexString(domainName.hashCode() & 0x7fffffff);
        String prefix = domain.getVpcOptions() != null ? "vpc-" : "search-";
        return prefix + domainName + "-" + suffix + "." + region + ".es."
                + AwsRegions.dnsSuffixFor(region);
    }

    public Domain describeDomain(String domainName, String region) {
        return findDomain(regionResolver.getAccountId(), region, domainName)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Domain not found: " + domainName, 409));
    }

    /** The domain called {@code domainName} in the caller's account and {@code region}, empty when there is none. */
    public Optional<Domain> findDomain(String domainName, String region) {
        return findDomain(regionResolver.getAccountId(), region, domainName);
    }

    /** The domain an ARN names in the caller's account, empty when there is none. */
    public Optional<Domain> findDomainByArn(String arn) {
        try {
            return Optional.of(findByArn(arn));
        } catch (AwsException e) {
            return Optional.empty();
        }
    }

    public List<Domain> describeDomains(List<String> domainNames, String region) {
        return domainNames.stream()
                .map(name -> describeDomain(name, region))
                .toList();
    }

    public List<Domain> listDomainNames(String engineType, String region) {
        String accountId = regionResolver.getAccountId();
        migrateLegacyDomainKeys(accountId);
        return domainStore.scanForAccount(accountId, key -> key.startsWith(region + "::")).stream()
                .filter(d -> !d.isDeleted())
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
        if (supplied.getAvailabilityZoneCount() != null) {
            existing.setAvailabilityZoneCount(supplied.getAvailabilityZoneCount());
        }
        if (supplied.getDedicatedMasterType() != null) {
            existing.setDedicatedMasterType(supplied.getDedicatedMasterType());
        }
        if (supplied.getDedicatedMasterCount() != null) {
            existing.setDedicatedMasterCount(supplied.getDedicatedMasterCount());
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
        if (supplied.getIops() != null) {
            existing.setIops(supplied.getIops());
        }
        if (supplied.getThroughput() != null) {
            existing.setThroughput(supplied.getThroughput());
        }
    }

    public Domain deleteDomain(String domainName, String region) {
        Domain domain = describeDomain(domainName, region);
        String accountId = regionResolver.getAccountId();
        String key = storageKey(region, domainName);
        boolean restoring;
        boolean deletedBefore;
        synchronized (storedDomainLock(accountId, key)) {
            deletedBefore = domain.isDeleted();
            domain.setDeleted(true);
            restoring = restoringDomains.contains(accountId + "/" + key);
            if (restoring) {
                // The startup restore is starting its container, and finishes the delete once that start is through.
                // Until then the domain reads Deleted, as AWS reports a domain being deleted, and its name stays taken.
                domainStore.putForAccount(accountId, key, domain);
            }
        }
        if (restoring) {
            LOG.infov("Deleting OpenSearch domain {0} once its restore is through", domainName);
            return domain;
        }
        if (deletedBefore && !config.services().opensearch().mock()) {
            return retryDelete(accountId, key, domain);
        }
        if (!config.services().opensearch().mock()) {
            domainManager.stopDomain(domain);
            domainManager.removeDomainStorage(domain);
        }
        domainStore.deleteForAccount(accountId, key);
        LOG.infov("Deleted OpenSearch domain: {0}", domainName);
        return domain;
    }

    /**
     * Retries a delete the restore could not finish, Docker keeping the container: removed the same confirmed way, or
     * left {@code Deleted}, naming the container, for another delete or the next start. One retry runs at a time, under
     * the domain's lock, on the record still stored: a retry that waited finds it gone, so it never removes, by name,
     * the container of a domain created again since.
     */
    private Domain retryDelete(String accountId, String key, Domain domain) {
        synchronized (storedDomainLock(accountId, key)) {
            Domain current = domainStore.getForAccount(accountId, key).orElse(null);
            if (current == null || !Objects.equals(current.getCreatedAt(), domain.getCreatedAt())) {
                return domain;
            }
            try {
                domainManager.removeDomainContainers(current);
            } catch (RuntimeException e) {
                LOG.warnv("OpenSearch domain {0} is still being deleted: {1}", current.getDomainName(), e.getMessage());
                return current;
            }
            domainManager.removeDomainStorage(current);
            domainStore.deleteForAccount(accountId, key);
            LOG.infov("Deleted OpenSearch domain: {0}", current.getDomainName());
            return current;
        }
    }

    /** Gives the domain a new map, its tags with {@code tags} added, one tag call at a time. */
    public synchronized void addTags(String arn, Map<String, String> tags) {
        Domain domain = findByArn(arn);
        Map<String, String> merged = new HashMap<>(domain.getTags());
        merged.putAll(tags);
        domain.setTags(merged);
        putDomain(domain);
    }

    public Map<String, String> listTags(String arn) {
        return findByArn(arn).getTags();
    }

    /** Gives the domain a new map, its tags without {@code tagKeys}, one tag call at a time. */
    public synchronized void removeTags(String arn, List<String> tagKeys) {
        Domain domain = findByArn(arn);
        Map<String, String> kept = new HashMap<>(domain.getTags());
        tagKeys.forEach(kept::remove);
        domain.setTags(kept);
        putDomain(domain);
    }

    @Override
    public String namespace() {
        return "es";
    }

    /** A request names a domain by its name, which its ARN carries whole, so there is nothing to find. */
    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        return Optional.empty();
    }

    /**
     * The tags of the domain whose ARN is {@code arn}, in the request's account, where the tag handlers read: an ARN of
     * another account names none. Read in place under the key the ARN gives, or the name a domain was stored under
     * before its key named the Region, a record from before storage was account-aware included, as {@link #findByArn}
     * finds it, and matched on the whole ARN. A tag call stores a new map, so the read is of one whole set.
     */
    @Override
    public Map<String, String> storedTags(String arn) {
        if (!AwsArnUtils.isArnFor(arn, "es")) {
            return Map.of();
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String account = parsed.accountId();
        if (!account.equals(regionResolver.getAccountId()) || !parsed.resource().startsWith("domain/")) {
            return Map.of();
        }
        String domainName = parsed.resource().substring("domain/".length());
        Map<String, String> tags = new LinkedHashMap<>();
        AccountAwareStorageBackend.readInAccount(domainStore, account, storageKey(parsed.region(), domainName))
                .or(() -> AccountAwareStorageBackend.readInAccount(domainStore, account, domainName))
                .filter(domain -> arn.equals(domain.getArn()))
                .ifPresent(domain -> domain.getTags().forEach((key, value) -> {
                    if (key != null && value != null) {
                        tags.put(key, value);
                    }
                }));
        return tags;
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

    /**
     * Resolves a domain inside one account and region, migrating a pre-region-scoping record
     * stored under the bare name onto the region-scoped key when the domain's own ARN proves
     * it belongs to this account and region.
     */
    private Optional<Domain> findDomain(String accountId, String region, String domainName) {
        return domainStore.getForAccountMigratingLegacyKeys(accountId, storageKey(region, domainName),
                List.of(domainName), domain -> ownsDomain(domain, accountId, region));
    }

    /**
     * Resolves the domain an ARN names, the way the tag operations address domains. The ARN
     * carries the account, region and name, so it names exactly one key; a stored record
     * matches only when its own ARN is the one asked for.
     */
    private Domain findByArn(String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw domainNotFoundForArn(arn);
        }
        if (!"es".equals(parsed.service()) || !parsed.resource().startsWith("domain/")) {
            throw domainNotFoundForArn(arn);
        }
        if (AwsArnUtils.isForeignAccount(parsed, regionResolver.getAccountId())) {
            throw domainNotFoundForArn(arn);
        }
        String domainName = parsed.resource().substring("domain/".length());
        return domainStore.getForAccountMigratingLegacyKeys(
                        parsed.accountId(), storageKey(parsed.region(), domainName),
                        List.of(domainName), domain -> arn.equals(domain.getArn()))
                .orElseThrow(() -> domainNotFoundForArn(arn));
    }

    private static AwsException domainNotFoundForArn(String arn) {
        return new AwsException("ResourceNotFoundException",
                "Domain not found for ARN: " + arn, 409);
    }

    /**
     * Whether a stored record is the domain the account and region name: its ARN must be an
     * OpenSearch ARN carrying exactly that account and region.
     */
    private static boolean ownsDomain(Domain domain, String accountId, String region) {
        try {
            AwsArnUtils.Arn arn = AwsArnUtils.parse(domain.getArn());
            return "es".equals(arn.service()) && accountId.equals(arn.accountId())
                    && region.equals(arn.region());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Whether a stored record's ARN is an OpenSearch ARN naming exactly this account. The
     * migration's ownership proof: the destination region comes from the same ARN, so proving
     * the account is enough there.
     */
    private static boolean ownsAccount(Domain domain, String accountId) {
        try {
            AwsArnUtils.Arn arn = AwsArnUtils.parse(domain.getArn());
            return "es".equals(arn.service()) && accountId.equals(arn.accountId());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static AwsException domainAlreadyExists(String domainName) {
        return new AwsException("ResourceAlreadyExistsException",
                "Domain with name " + domainName + " already exists.", 409);
    }

    private static String storageKey(String region, String domainName) {
        return region + "::" + domainName;
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
        if (options.advancedOptions() != null) {
            domain.setAdvancedOptions(new HashMap<>(options.advancedOptions()));
        }
        if (options.ipAddressType() != null) {
            domain.setIpAddressType(options.ipAddressType());
        }
        if (options.offPeakWindowOptions() != null) {
            OffPeakWindowOptions supplied = options.offPeakWindowOptions();
            OffPeakWindowOptions current = domain.getOffPeakWindowOptions() != null
                    ? domain.getOffPeakWindowOptions() : new OffPeakWindowOptions();
            if (supplied.getEnabled() != null) {
                current.setEnabled(supplied.getEnabled());
            }
            if (supplied.getStartHours() != null) {
                current.setStartHours(supplied.getStartHours());
                current.setStartMinutes(supplied.getStartMinutes() != null ? supplied.getStartMinutes() : 0L);
            }
            domain.setOffPeakWindowOptions(current);
        }
        if (options.softwareUpdateOptions() != null
                && options.softwareUpdateOptions().getAutoSoftwareUpdateEnabled() != null) {
            domain.setSoftwareUpdateOptions(options.softwareUpdateOptions());
        }
    }

    /**
     * The settings a new domain reports when its request leaves them out, as AWS fills them: an
     * {@code ipv4} address type, an enabled off-peak window starting at 10:00 P.M., and automatic
     * software updates off.
     */
    private static void applyCreateDefaults(Domain domain) {
        if (domain.getIpAddressType() == null) {
            domain.setIpAddressType(DEFAULT_IP_ADDRESS_TYPE);
        }
        OffPeakWindowOptions offPeak = domain.getOffPeakWindowOptions();
        if (offPeak == null) {
            offPeak = new OffPeakWindowOptions(true, null, null);
            domain.setOffPeakWindowOptions(offPeak);
        }
        if (offPeak.getEnabled() == null) {
            offPeak.setEnabled(true);
        }
        if (offPeak.getStartHours() == null) {
            offPeak.setStartHours(DEFAULT_OFF_PEAK_START_HOURS);
            offPeak.setStartMinutes(0L);
        }
        if (domain.getSoftwareUpdateOptions() == null) {
            domain.setSoftwareUpdateOptions(new SoftwareUpdateOptions(false));
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
        try {
            for (Domain domain : allDomains()) {
                if (domain.isProcessing() && !restoringDomains.contains(restoringKey(domain))
                        && domainManager.isReady(domain)) {
                    domain.setProcessing(false);
                    putDomain(domain);
                    LOG.infov("OpenSearch domain {0} is ready at {1}",
                            domain.getDomainName(), domain.getEndpoint());
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("OpenSearch readiness poll failed; will retry", e);
        }
    }

    private List<Domain> allDomains() {
        return domainStore.scanAllAccounts();
    }

    /** The account and storage key the domain's ARN names, as its restore is known by; empty for an unparsable ARN. */
    private static String restoringKey(Domain domain) {
        try {
            AwsArnUtils.Arn arn = AwsArnUtils.parse(domain.getArn());
            return arn.accountId() + "/" + storageKey(arn.region(), domain.getDomainName());
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /**
     * Persists a domain under the key its ARN names, so the poller writes a record back to the
     * account and region that own it rather than to whatever account the background thread
     * happens to run under. A record whose ARN cannot be parsed is left un-persisted, with a
     * warning, rather than guessed at.
     */
    private void putDomain(Domain domain) {
        try {
            AwsArnUtils.Arn arn = AwsArnUtils.parse(domain.getArn());
            domainStore.putForAccount(arn.accountId(), storageKey(arn.region(), domain.getDomainName()), domain);
        } catch (IllegalArgumentException e) {
            LOG.warnv("Not persisting OpenSearch domain {0}: its ARN {1} cannot be parsed",
                    domain.getDomainName(), domain.getArn());
        }
    }

    /**
     * Moves records persisted before domains were keyed by region, one account partition at a
     * time, from the bare domain name onto the region-scoped key. The destination region comes
     * from the domain's ARN, which has always carried it, so a pre-scoping domain stays in its
     * own region; a record whose ARN cannot be parsed is left where it is.
     */
    private void migrateLegacyDomainKeys() {
        domainStore.migrateLegacyEntriesInAllAccounts(key -> !key.contains("::"),
                this::legacyDomainDestinationKey, (accountId, domain) -> ownsAccount(domain, accountId));
    }

    private void migrateLegacyDomainKeys(String accountId) {
        domainStore.migrateLegacyEntries(accountId, key -> !key.contains("::"),
                this::legacyDomainDestinationKey, domain -> ownsAccount(domain, accountId));
    }

    /**
     * The region-scoped key for a legacy record, or null to leave it untouched when its ARN
     * cannot be parsed.
     */
    private String legacyDomainDestinationKey(Domain domain) {
        try {
            return storageKey(AwsArnUtils.parse(domain.getArn()).region(), domain.getDomainName());
        } catch (IllegalArgumentException e) {
            LOG.warnv("Leaving OpenSearch domain {0} under its pre-region-scoping key: "
                    + "its ARN {1} cannot be parsed", domain.getDomainName(), domain.getArn());
            return null;
        }
    }

    @Override
    public List<ExplorerResource> getResources() {
        List<ExplorerResource> resources = new ArrayList<>();
        for (Domain domain : domainStore.scan(key -> true)) {
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
