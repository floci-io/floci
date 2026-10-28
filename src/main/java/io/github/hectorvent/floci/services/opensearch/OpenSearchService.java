package io.github.hectorvent.floci.services.opensearch;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
public class OpenSearchService implements ResourceProvider {

    private static final Logger LOG = Logger.getLogger(OpenSearchService.class);

    private static final String DEFAULT_ENGINE_VERSION = OpenSearchVersions.DEFAULT_VERSION;

    private final AccountAwareStorageBackend<Domain> domainStore;
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
        if (!config.services().opensearch().mock() && !config.services().opensearch().keepRunningOnShutdown()) {
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
        if (findDomain(accountId, region, domainName).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsException",
                    "Domain with name " + domainName + " already exists.", 409);
        }

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

        domainStore.putForAccount(accountId, storageKey(region, domainName), domain);
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
        return findDomain(regionResolver.getAccountId(), region, domainName)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Domain not found: " + domainName, 409));
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
        domainStore.deleteForAccount(regionResolver.getAccountId(), storageKey(region, domainName));
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
                if (domain.isProcessing() && domainManager.isReady(domain)) {
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
        Set<String> accounts = new LinkedHashSet<>();
        for (String rawKey : domainStore.scanAllAccountsWithRawKeys().keySet()) {
            accounts.add(AccountAwareStorageBackend.hasAccountPrefix(rawKey)
                    ? rawKey.substring(0, 12)
                    : regionResolver.getDefaultAccountId());
        }
        accounts.forEach(this::migrateLegacyDomainKeys);
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
