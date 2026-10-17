package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Base64Url;
import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactDomain;
import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactPackageVersion;
import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactRepository;
import io.github.hectorvent.floci.services.codeartifact.model.ExternalConnection;
import io.github.hectorvent.floci.services.codeartifact.model.PackageAsset;
import io.github.hectorvent.floci.services.codeartifact.model.PackageOriginConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@ApplicationScoped
public class CodeArtifactService implements Resettable {

    /** name -> packageFormat, the fixed real set of AWS-hosted public upstream connections. */
    private static final Map<String, String> EXTERNAL_CONNECTIONS = Map.ofEntries(
            Map.entry("public:maven-clojars", "maven"),
            Map.entry("public:maven-commonsware", "maven"),
            Map.entry("public:maven-googleandroid", "maven"),
            Map.entry("public:maven-gradleplugins", "maven"),
            Map.entry("public:maven-central", "maven"),
            Map.entry("public:npmjs", "npm"),
            Map.entry("public:nuget-org", "nuget"),
            Map.entry("public:pypi", "pypi"),
            Map.entry("public:ruby-gems-org", "ruby"),
            Map.entry("public:crates-io", "cargo"));

    private static final Set<String> PACKAGE_FORMATS =
            Set.of("npm", "pypi", "maven", "nuget", "generic", "ruby", "swift", "cargo");
    private static final Set<String> ENDPOINT_TYPES = Set.of("dualstack", "ipv4");
    /**
     * Formats served by a real package-manager-protocol proxy backed by a per-format sidecar, and
     * so need an {@link #ensureFormatContainerId} entry assigned at creation. Adding the next
     * format's proxy only needs a new entry here; {@link CodeArtifactRepository#getSidecarContainerIds()}
     * and the backfill in {@link #ensureFormatContainerId} already handle it, including for
     * repositories created before that format's proxy existed.
     */
    private static final List<String> CONTAINER_BACKED_FORMATS = List.of("maven", "npm", "pypi", "nuget");
    private static final Set<String> ORIGIN_RESTRICTION_VALUES = Set.of("ALLOW", "BLOCK");
    /**
     * Formats AWS documents as valid for {@code GetPackageVersionReadme} specifically: the
     * action's own request parameter doc gives "Valid Values: npm | pypi | nuget", narrower than
     * the shared eight-value {@code PackageFormat} wire enum every other package-version action
     * here accepts. Maven, Swift, generic, Ruby, and Cargo package versions carry no readme at
     * all in real CodeArtifact, so a request for one of those formats is rejected outright rather
     * than silently 404ing the way an unimplemented capability elsewhere here might.
     */
    private static final Set<String> README_FORMATS = Set.of("npm", "pypi", "nuget");

    private static final Logger LOG = Logger.getLogger(CodeArtifactService.class);

    private static final Pattern DOMAIN_NAME = Pattern.compile("[a-z][a-z0-9\\-]{0,48}[a-z0-9]");
    private static final Pattern REPOSITORY_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._\\-]{1,99}");
    private static final Pattern ACCOUNT_ID = Pattern.compile("[0-9]{12}");
    private static final Pattern PACKAGE_TOKEN = Pattern.compile("[^#/\\s]+");
    // Matches the wire model's AssetName pattern exactly: any non-empty string with no
    // control/format/unassigned/surrogate/private-use characters. Without this, an asset name
    // containing '\r'/'\n' would be echoed back verbatim in the X-AssetName response header.
    private static final Pattern ASSET_NAME = Pattern.compile("\\P{C}+");
    private static final List<String> ASSET_HASH_ALGORITHMS = List.of("MD5", "SHA-1", "SHA-256", "SHA-512");
    // Package-private: reused by CodeArtifactMavenController so the Maven proxy enforces the same
    // documented 5 GB AWS quota rather than duplicating the constant.
    static final long MAX_ASSET_FILE_SIZE_BYTES = 5L * 1024 * 1024 * 1024;
    private static final int MAX_ASSETS_PER_PACKAGE_VERSION = 350;
    private static final int MAX_DOMAINS_PER_ACCOUNT = 10;
    private static final int MAX_REPOSITORIES_PER_DOMAIN = 1000;
    private static final String ASSET_STORAGE_DIR = "codeartifact-assets";
    private static final long MIN_TOKEN_DURATION_SECONDS = 900;
    private static final long MAX_TOKEN_DURATION_SECONDS = 43200;
    private static final long DEFAULT_TOKEN_DURATION_SECONDS = 43200;
    private static final int TOKEN_RANDOM_BYTES = 32;

    public record DomainView(CodeArtifactDomain domain, int repositoryCount, long assetSizeBytes) {}
    public record ResourcePolicy(String resourceArn, String revision, String document) {}
    public record PublishPackageVersionResult(CodeArtifactPackageVersion packageVersion, PackageAsset asset) {}
    public record PackageVersionAssetResult(PackageAsset asset, String packageVersionRevision) {}
    public record PackageVersionReadmeResult(String format, String namespace, String packageName, String version,
            String versionRevision, String readme) {}
    public record AuthorizationToken(String token, long expirationEpochSeconds) {}
    public record AuthorizationTokenScope(String owner, String region) {}
    private record AuthorizationTokenRecord(String owner, String region, String domain, Instant expiration) {}

    private final AccountAwareStorageBackend<CodeArtifactDomain> domains;
    private final AccountAwareStorageBackend<CodeArtifactRepository> repositories;
    private final AccountAwareStorageBackend<CodeArtifactPackageVersion> packageVersions;
    private final AccountAwareStorageBackend<PackageOriginConfig> packageOriginConfigs;
    private final RegionResolver regionResolver;
    private final EmulatorConfig config;
    private final CodeArtifactSidecarRegistry sidecarRegistry;
    // Asset bytes never go through the JSON/WAL-backed `packageVersions` store (see PackageAsset's
    // @JsonIgnore content field): they're kept here instead, on disk in persistent/hybrid/wal mode
    // or in this map in memory mode, so a publish only ever rewrites metadata.
    private final boolean inMemory;
    private final Path assetRoot;
    private final ConcurrentHashMap<String, byte[]> memoryAssetStore = new ConcurrentHashMap<>();
    // Bearer tokens are ephemeral security material, not durable emulator state: kept in memory
    // only, the same way IamService keeps STS session credentials, regardless of storage mode.
    private final ConcurrentHashMap<String, AuthorizationTokenRecord> authorizationTokens = new ConcurrentHashMap<>();
    // A race guard, not emulator state a caller can ever observe directly: never persisted, and
    // never needs to survive a restart (an in-flight race can't span one either). See
    // currentContainerBackedPackageFence's own javadoc for what this protects against.
    private final ConcurrentHashMap<String, Long> containerBackedPackageDeletionFence = new ConcurrentHashMap<>();
    private final AtomicLong containerBackedPackageDeletionCounter = new AtomicLong();
    // Maven, npm, pypi, and nuget publishes go straight to their own sidecar and never create a
    // packageVersions record (see CONTAINER_BACKED_FORMATS), so DescribeDomain's assetSizeBytes
    // can't see their bytes by summing packageVersions the way it does for generic. Each format's
    // own proxy reports a successful, non-idempotent publish's size here instead, keyed exactly
    // like packageOriginConfigs (region/domain/repository/format/namespace/package, no version).
    // In memory only, like the fence above, not through StorageFactory: the Maven/npm/pypi/nuget
    // content these bytes count lives in the sidecar containers themselves, which do not survive
    // a Floci restart either (no volume attached, removed on shutdown; see this service's own
    // Known limitations doc). Persisting the count but not the content it describes would leave
    // a stale total surviving a restart for bytes that no longer exist anywhere, with nothing to
    // ever naturally clean it up; kept in memory instead, so a restart loses both together.
    private final ConcurrentHashMap<String, Long> containerBackedAssetBytes = new ConcurrentHashMap<>();
    // Keyed the same way as packageVersions (packageVersionKey): npm's per-version readme (from
    // the publish envelope, see recordPublishedPackageVersionReadme) and pypi's upload
    // "description" form field both land here, since neither sidecar can answer for either after
    // the fact (see getPackageVersionReadme's own javadoc). In memory only, like the fence and
    // containerBackedAssetBytes above and for the exact same reason: the Verdaccio/pypiserver
    // content a readme describes does not survive a Floci restart either (no volume attached,
    // removed on shutdown), so persisting the readme text but not the package version it
    // describes would leave it answering for a version that, after a restart, no longer exists
    // anywhere real.
    private final ConcurrentHashMap<String, String> packageVersionReadmes = new ConcurrentHashMap<>();

    @Inject
    public CodeArtifactService(StorageFactory storageFactory, RegionResolver regionResolver, EmulatorConfig config,
                                ServiceConfigAccess serviceConfigAccess, CodeArtifactSidecarRegistry sidecarRegistry) {
        this(
                storageFactory.create("codeartifact", "codeartifact-domains.json",
                        new TypeReference<Map<String, CodeArtifactDomain>>() {}),
                storageFactory.create("codeartifact", "codeartifact-repositories.json",
                        new TypeReference<Map<String, CodeArtifactRepository>>() {}),
                storageFactory.create("codeartifact", "codeartifact-package-versions.json",
                        new TypeReference<Map<String, CodeArtifactPackageVersion>>() {}),
                storageFactory.create("codeartifact", "codeartifact-package-origin-configs.json",
                        new TypeReference<Map<String, PackageOriginConfig>>() {}),
                regionResolver, config,
                "memory".equals(serviceConfigAccess.storageMode("codeartifact")),
                Path.of(config.storage().persistentPath()).resolve(ASSET_STORAGE_DIR),
                sidecarRegistry);
    }

    /** Package-private constructor for testing. */
    CodeArtifactService(AccountAwareStorageBackend<CodeArtifactDomain> domains,
                         AccountAwareStorageBackend<CodeArtifactRepository> repositories,
                         AccountAwareStorageBackend<CodeArtifactPackageVersion> packageVersions,
                         AccountAwareStorageBackend<PackageOriginConfig> packageOriginConfigs,
                         RegionResolver regionResolver, EmulatorConfig config,
                         boolean inMemory, Path assetRoot, CodeArtifactSidecarRegistry sidecarRegistry) {
        this.domains = domains;
        this.repositories = repositories;
        this.packageVersions = packageVersions;
        this.packageOriginConfigs = packageOriginConfigs;
        this.regionResolver = regionResolver;
        this.config = config;
        this.sidecarRegistry = sidecarRegistry;
        this.inMemory = inMemory;
        this.assetRoot = assetRoot;
        if (!inMemory) {
            try {
                Files.createDirectories(assetRoot);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to create CodeArtifact asset data directory: " + assetRoot, e);
            }
        }
    }

    // ---------------------------------------------------------------- domains

    public synchronized DomainView createDomain(String region, String domain, String encryptionKey,
                                                 Map<String, String> tags) {
        validateDomainName(domain);
        String owner = regionResolver.getAccountId();
        String key = domainKey(region, domain);
        if (domains.getForAccount(owner, key).isPresent()) {
            throw conflict("Domain with name '" + domain + "' already exists.", domain, "domain");
        }
        if (domainCountForAccount(owner, region) >= MAX_DOMAINS_PER_ACCOUNT) {
            throw new AwsException("ServiceQuotaExceededException",
                    "An AWS account can have a maximum of " + MAX_DOMAINS_PER_ACCOUNT + " domains.", 402,
                    resourceFields(domain, "domain"));
        }
        CodeArtifactDomain d = new CodeArtifactDomain();
        d.setName(domain);
        d.setOwner(owner);
        d.setRegion(region);
        d.setArn(regionResolver.buildArn("codeartifact", region, "domain/" + domain));
        d.setEncryptionKey(encryptionKey != null ? encryptionKey
                : regionResolver.buildArn("kms", region, "alias/aws/codeartifact"));
        d.setCreatedTime(Instant.now().getEpochSecond());
        d.setTags(validateTags(tags, Map.of(), domain, "domain"));
        domains.putForAccount(owner, key, d);
        return new DomainView(d, 0, 0L);
    }

    /**
     * A missing domain fails with {@code ResourceNotFoundException} (404), which is what AWS
     * returns even though the API reference leaves it out of DeleteDomain's error list.
     */
    public synchronized DomainView deleteDomain(String region, String domain, String domainOwner) {
        String owner = effectiveOwner(domainOwner);
        String key = domainKey(region, domain);
        CodeArtifactDomain d = requireDomain(owner, key, domain);
        int repoCount = repositoryCountForDomain(owner, region, domain);
        if (repoCount > 0) {
            throw conflict("Domain '" + domain + "' contains repositories and cannot be deleted "
                    + "until they are deleted.", domain, "domain");
        }
        domains.deleteForAccount(owner, key);
        return new DomainView(d, 0, 0L);
    }

    public DomainView describeDomain(String region, String domain, String domainOwner) {
        String owner = effectiveOwner(domainOwner);
        CodeArtifactDomain d = requireDomain(owner, domainKey(region, domain), domain);
        return new DomainView(d, repositoryCountForDomain(owner, region, domain),
                assetSizeBytesForDomain(owner, region, domain));
    }

    public PaginatedResult<DomainView> listDomains(String region, Integer maxResults, String nextToken) {
        String owner = regionResolver.getAccountId();
        List<CodeArtifactDomain> matching = domains.scanForAccount(owner, k -> k.startsWith(region + "::"));
        PaginatedResult<CodeArtifactDomain> page = Pagination.paginate(matching, CodeArtifactDomain::getName,
                maxResults, nextToken, 100, 1000, "ValidationException");
        // DomainSummary (what ListDomains actually returns) has no assetSizeBytes field at all on
        // real AWS, unlike DomainDescription; 0 here is never observed by a caller, same as
        // repositoryCount would be if DomainSummary carried one.
        List<DomainView> views = page.items().stream()
                .map(d -> new DomainView(d, repositoryCountForDomain(owner, region, d.getName()), 0L))
                .toList();
        return new PaginatedResult<>(views, page.nextToken());
    }

    public synchronized ResourcePolicy putDomainPermissionsPolicy(String region, String domain, String domainOwner,
                                                                   String policyDocument, String policyRevision) {
        String owner = effectiveOwner(domainOwner);
        String key = domainKey(region, domain);
        CodeArtifactDomain d = requireDomain(owner, key, domain);
        checkRevision(d.getPolicyRevision(), policyRevision, domain, "domain");
        validatePolicyDocument(policyDocument);
        d.setPolicyDocument(policyDocument);
        d.setPolicyRevision(newRevision());
        domains.putForAccount(owner, key, d);
        return new ResourcePolicy(d.getArn(), d.getPolicyRevision(), d.getPolicyDocument());
    }

    public ResourcePolicy getDomainPermissionsPolicy(String region, String domain, String domainOwner) {
        String owner = effectiveOwner(domainOwner);
        CodeArtifactDomain d = requireDomain(owner, domainKey(region, domain), domain);
        if (d.getPolicyDocument() == null) {
            throw notFound("No resource policy is associated with domain '" + domain + "'.", domain, "domain");
        }
        return new ResourcePolicy(d.getArn(), d.getPolicyRevision(), d.getPolicyDocument());
    }

    public synchronized ResourcePolicy deleteDomainPermissionsPolicy(String region, String domain, String domainOwner,
                                                                      String policyRevision) {
        String owner = effectiveOwner(domainOwner);
        String key = domainKey(region, domain);
        CodeArtifactDomain d = requireDomain(owner, key, domain);
        if (d.getPolicyDocument() == null) {
            throw notFound("No resource policy is associated with domain '" + domain + "'.", domain, "domain");
        }
        checkRevision(d.getPolicyRevision(), policyRevision, domain, "domain");
        ResourcePolicy removed = new ResourcePolicy(d.getArn(), d.getPolicyRevision(), d.getPolicyDocument());
        d.setPolicyDocument(null);
        d.setPolicyRevision(null);
        domains.putForAccount(owner, key, d);
        return removed;
    }

    // ------------------------------------------------------------ repositories

    public synchronized CodeArtifactRepository createRepository(String region, String domain, String domainOwner,
                                                                  String repository, String description,
                                                                  List<String> upstreams, Map<String, String> tags) {
        validateRepositoryName(repository);
        String owner = effectiveOwner(domainOwner);
        requireDomain(owner, domainKey(region, domain), domain);
        String key = repositoryKey(region, domain, repository);
        if (repositories.getForAccount(owner, key).isPresent()) {
            throw conflict("Repository with name '" + repository + "' already exists in domain '" + domain + "'.",
                    repository, "repository");
        }
        if (repositoryCountForDomain(owner, region, domain) >= MAX_REPOSITORIES_PER_DOMAIN) {
            throw new AwsException("ServiceQuotaExceededException",
                    "A domain can have a maximum of " + MAX_REPOSITORIES_PER_DOMAIN + " repositories.", 402,
                    resourceFields(repository, "repository"));
        }
        validateUpstreams(owner, region, domain, repository, upstreams);
        validateDescription(description);

        CodeArtifactRepository r = new CodeArtifactRepository();
        r.setName(repository);
        r.setDomainName(domain);
        r.setDomainOwner(owner);
        r.setAdministratorAccount(regionResolver.getAccountId());
        r.setRegion(region);
        r.setArn(regionResolver.buildArn("codeartifact", region, "repository/" + domain + "/" + repository));
        r.setDescription(description);
        r.setUpstreams(upstreams != null ? new ArrayList<>(upstreams) : new ArrayList<>());
        r.setCreatedTime(Instant.now().getEpochSecond());
        r.setTags(validateTags(tags, Map.of(), repository, "repository"));
        // A bare UUID per container-backed format, not a domain/repository-derived name:
        // Reposilite rejects any repository id over 64 characters, and CodeArtifact domain (max
        // 50) and repository (max 100) names can easily exceed that combined. Each id is purely
        // internal and never surfaced by the public API, so readability doesn't matter here, only
        // fitting the limit and staying unique per creation, so a repository deleted and recreated
        // under the same name never inherits the previous one's sidecar-side content.
        Map<String, String> sidecarContainerIds = new LinkedHashMap<>();
        for (String format : CONTAINER_BACKED_FORMATS) {
            sidecarContainerIds.put(format, newRevision());
        }
        r.setSidecarContainerIds(sidecarContainerIds);
        repositories.putForAccount(owner, key, r);
        return r;
    }

    public synchronized CodeArtifactRepository deleteRepository(String region, String domain, String domainOwner,
                                                                  String repository) {
        requireNonBlank(domain, "domain");
        String owner = effectiveOwner(domainOwner);
        String key = repositoryKey(region, domain, repository);
        CodeArtifactRepository r = requireRepository(owner, key, repository);
        repositories.deleteForAccount(owner, key);
        // Best-effort: the metadata delete above already committed, matching real CodeArtifact's
        // own DeleteRepository (synchronous and always succeeds once the repository exists; actual
        // content cleanup is an internal detail never exposed to the caller). A sidecar that is
        // unreachable when this runs must not turn an already-successful delete into a 500, and
        // must not leave the repository stuck: a retry would just 404, since the record above is
        // already gone.
        releaseSidecarStorage(r, domain, repository);
        // packageOriginConfigs is keyed by name (region/domain/repository/format/namespace/package),
        // unlike sidecarContainerIds, which createRepository deliberately assigns a fresh id for on
        // every creation so a repository recreated under the same name never inherits the previous
        // one's sidecar content. Without this, the same recreated-under-the-same-name repository
        // would still inherit any package's stored publish/upstream restrictions, including a
        // publish:BLOCK that would reject a publish with no way for the caller to know why.
        String originPrefix = region + "::" + domain + "::" + repository + "::";
        packageOriginConfigs.keysForAccount(owner).stream()
                .filter(k -> k.startsWith(originPrefix))
                .forEach(k -> packageOriginConfigs.deleteForAccount(owner, k));
        // Keyed the same way: without this, a repository recreated under the same name would
        // inherit the previous one's Maven/npm/pypi/nuget byte counts into DescribeDomain's total.
        String ownerScopedOriginPrefix = ownerScopedKey(owner, originPrefix);
        containerBackedAssetBytes.keySet().stream()
                .filter(k -> k.startsWith(ownerScopedOriginPrefix))
                .toList()
                .forEach(containerBackedAssetBytes::remove);
        // packageVersions (generic-format metadata and asset bytes) is keyed the same way too, and
        // was never cleaned up here before assetSizeBytesForDomain existed to notice: without
        // this, a repository recreated under this same name would inherit every generic package
        // version the previous one ever had, resurfacing through DescribePackageVersion,
        // GetPackageVersionAsset, and DescribeDomain's own total alike. Deletes each version's own
        // asset content too, the same way deletePackage already does for one package at a time.
        for (CodeArtifactPackageVersion pv : packageVersions.scanForAccount(owner, k -> k.startsWith(originPrefix))) {
            String versionKey = packageVersionKey(region, domain, repository, pv.getFormat(), pv.getNamespace(),
                    pv.getPackageName(), pv.getVersion());
            packageVersions.deleteForAccount(owner, versionKey);
            for (PackageAsset asset : pv.getAssets().values()) {
                try {
                    deleteAssetContent(owner, versionKey, asset.getName());
                } catch (UncheckedIOException e) {
                    LOG.warnv(e, "Could not delete CodeArtifact asset file for {0}/{1}: {2}", versionKey,
                            asset.getName(), e.getMessage());
                }
            }
        }
        // Same reasoning, same prefix: packageVersionReadmes is also keyed by name
        // (region/domain/repository/format/namespace/package/version), not a freshly minted id, so
        // a pypi/npm package republished to a repository recreated under this same name must not
        // inherit a readme captured for a version with the same coordinates under the deleted
        // repository.
        packageVersionReadmes.keySet().stream()
                .filter(k -> k.startsWith(ownerScopedOriginPrefix))
                .toList()
                .forEach(packageVersionReadmes::remove);
        return r;
    }

    /**
     * Generic over whatever formats a repository actually has sidecar ids for: adding the next
     * format's sidecar needs no change here, only a new {@link RepositorySidecarManager}
     * implementation that {@link CodeArtifactSidecarRegistry} discovers.
     */
    private void releaseSidecarStorage(CodeArtifactRepository r, String domain, String repository) {
        r.getSidecarContainerIds().forEach((format, containerId) ->
                sidecarRegistry.forFormat(format).ifPresent(manager -> {
                    try {
                        manager.release(containerId);
                    } catch (RuntimeException e) {
                        LOG.warnv(e, "Failed to release {0} sidecar storage for repository {1}/{2}: {3}",
                                format, domain, repository, e.getMessage());
                    }
                }));
    }

    public CodeArtifactRepository describeRepository(String region, String domain, String domainOwner,
                                                       String repository) {
        requireNonBlank(domain, "domain");
        String owner = effectiveOwner(domainOwner);
        return requireRepository(owner, repositoryKey(region, domain, repository), repository);
    }

    /**
     * A sidecar container id for one format is assigned at {@link #createRepository}, but a
     * repository persisted before that format's proxy existed has none; backfills it lazily on
     * first use of that format rather than leaving every caller handle a missing entry it can
     * otherwise never see. {@code format} is expected to be one of
     * {@link #CONTAINER_BACKED_FORMATS}, though nothing here enforces that: an unlisted format
     * simply gets its own id assigned the same way.
     */
    public synchronized String ensureFormatContainerId(String format, String region, String domain,
                                                         String domainOwner, String repository) {
        String owner = effectiveOwner(domainOwner);
        String key = repositoryKey(region, domain, repository);
        CodeArtifactRepository r = requireRepository(owner, key, repository);
        String id = r.getSidecarContainerIds().get(format);
        if (id == null) {
            id = newRevision();
            Map<String, String> sidecarContainerIds = new LinkedHashMap<>(r.getSidecarContainerIds());
            sidecarContainerIds.put(format, id);
            r.setSidecarContainerIds(sidecarContainerIds);
            repositories.putForAccount(owner, key, r);
        }
        return id;
    }

    public synchronized CodeArtifactRepository updateRepository(String region, String domain, String domainOwner,
                                                                  String repository, String description,
                                                                  List<String> upstreams) {
        requireNonBlank(domain, "domain");
        String owner = effectiveOwner(domainOwner);
        String key = repositoryKey(region, domain, repository);
        CodeArtifactRepository r = requireRepository(owner, key, repository);
        if (upstreams != null) {
            if (!upstreams.isEmpty() && !r.getExternalConnections().isEmpty()) {
                throw conflict("Repository '" + repository + "' has an external connection; "
                        + "a repository cannot have both an external connection and upstream repositories.",
                        repository, "repository");
            }
            validateUpstreams(owner, region, domain, repository, upstreams);
            r.setUpstreams(new ArrayList<>(upstreams));
        }
        if (description != null) {
            validateDescription(description);
            r.setDescription(description);
        }
        repositories.putForAccount(owner, key, r);
        return r;
    }

    public PaginatedResult<CodeArtifactRepository> listRepositories(String region, String repositoryPrefix,
                                                                      Integer maxResults, String nextToken) {
        String owner = regionResolver.getAccountId();
        List<CodeArtifactRepository> matching = repositories.scanForAccount(owner, k -> k.startsWith(region + "::"))
                .stream()
                .filter(r -> repositoryPrefix == null || r.getName().startsWith(repositoryPrefix))
                .toList();
        return Pagination.paginate(matching, r -> r.getDomainName() + "::" + r.getName(),
                maxResults, nextToken, 100, 1000, "ValidationException");
    }

    public PaginatedResult<CodeArtifactRepository> listRepositoriesInDomain(String region, String domain,
                                                                             String domainOwner,
                                                                             String administratorAccount,
                                                                             String repositoryPrefix,
                                                                             Integer maxResults, String nextToken) {
        String owner = effectiveOwner(domainOwner);
        requireDomain(owner, domainKey(region, domain), domain);
        List<CodeArtifactRepository> matching = repositories
                .scanForAccount(owner, k -> k.startsWith(region + "::" + domain + "::"))
                .stream()
                .filter(r -> repositoryPrefix == null || r.getName().startsWith(repositoryPrefix))
                .filter(r -> administratorAccount == null || administratorAccount.equals(r.getAdministratorAccount()))
                .toList();
        return Pagination.paginate(matching, CodeArtifactRepository::getName,
                maxResults, nextToken, 100, 1000, "ValidationException");
    }

    public String getRepositoryEndpoint(String region, String domain, String domainOwner, String repository,
                                         String format, String endpointType) {
        if (format == null || !PACKAGE_FORMATS.contains(format)) {
            throw validation("format must be one of " + PACKAGE_FORMATS + ".");
        }
        if (endpointType != null && !ENDPOINT_TYPES.contains(endpointType)) {
            throw validation("endpointType must be one of " + ENDPOINT_TYPES + ".");
        }
        requireNonBlank(domain, "domain");
        String owner = effectiveOwner(domainOwner);
        requireRepository(owner, repositoryKey(region, domain, repository), repository);
        return config.effectiveBaseUrl() + "/codeartifact/" + format + "/" + domain + "/" + repository + "/";
    }

    public synchronized ResourcePolicy putRepositoryPermissionsPolicy(String region, String domain,
                                                                       String domainOwner, String repository,
                                                                       String policyDocument, String policyRevision) {
        requireNonBlank(domain, "domain");
        String owner = effectiveOwner(domainOwner);
        String key = repositoryKey(region, domain, repository);
        CodeArtifactRepository r = requireRepository(owner, key, repository);
        checkRevision(r.getPolicyRevision(), policyRevision, repository, "repository");
        validatePolicyDocument(policyDocument);
        r.setPolicyDocument(policyDocument);
        r.setPolicyRevision(newRevision());
        repositories.putForAccount(owner, key, r);
        return new ResourcePolicy(r.getArn(), r.getPolicyRevision(), r.getPolicyDocument());
    }

    public ResourcePolicy getRepositoryPermissionsPolicy(String region, String domain, String domainOwner,
                                                          String repository) {
        requireNonBlank(domain, "domain");
        String owner = effectiveOwner(domainOwner);
        CodeArtifactRepository r = requireRepository(owner, repositoryKey(region, domain, repository), repository);
        if (r.getPolicyDocument() == null) {
            throw notFound("No resource policy is associated with repository '" + repository + "'.",
                    repository, "repository");
        }
        return new ResourcePolicy(r.getArn(), r.getPolicyRevision(), r.getPolicyDocument());
    }

    public synchronized ResourcePolicy deleteRepositoryPermissionsPolicy(String region, String domain,
                                                                          String domainOwner, String repository,
                                                                          String policyRevision) {
        requireNonBlank(domain, "domain");
        String owner = effectiveOwner(domainOwner);
        String key = repositoryKey(region, domain, repository);
        CodeArtifactRepository r = requireRepository(owner, key, repository);
        if (r.getPolicyDocument() == null) {
            throw notFound("No resource policy is associated with repository '" + repository + "'.",
                    repository, "repository");
        }
        checkRevision(r.getPolicyRevision(), policyRevision, repository, "repository");
        ResourcePolicy removed = new ResourcePolicy(r.getArn(), r.getPolicyRevision(), r.getPolicyDocument());
        r.setPolicyDocument(null);
        r.setPolicyRevision(null);
        repositories.putForAccount(owner, key, r);
        return removed;
    }

    public synchronized CodeArtifactRepository associateExternalConnection(String region, String domain,
                                                                            String domainOwner, String repository,
                                                                            String externalConnection) {
        String format = EXTERNAL_CONNECTIONS.get(externalConnection);
        if (format == null) {
            throw validation("externalConnection must be one of " + EXTERNAL_CONNECTIONS.keySet() + ".");
        }
        requireNonBlank(domain, "domain");
        String owner = effectiveOwner(domainOwner);
        String key = repositoryKey(region, domain, repository);
        CodeArtifactRepository r = requireRepository(owner, key, repository);
        if (!r.getExternalConnections().isEmpty()) {
            throw conflict("Repository '" + repository + "' already has an external connection; "
                    + "a repository can only have one.", repository, "repository");
        }
        if (!r.getUpstreams().isEmpty()) {
            throw conflict("Repository '" + repository + "' has upstream repositories; "
                    + "a repository cannot have both an external connection and upstream repositories.",
                    repository, "repository");
        }
        List<ExternalConnection> connections = new ArrayList<>(r.getExternalConnections());
        connections.add(new ExternalConnection(externalConnection, format, "Available"));
        r.setExternalConnections(connections);
        repositories.putForAccount(owner, key, r);
        return r;
    }

    public synchronized CodeArtifactRepository disassociateExternalConnection(String region, String domain,
                                                                               String domainOwner, String repository,
                                                                               String externalConnection) {
        requireNonBlank(domain, "domain");
        String owner = effectiveOwner(domainOwner);
        String key = repositoryKey(region, domain, repository);
        CodeArtifactRepository r = requireRepository(owner, key, repository);
        List<ExternalConnection> connections = new ArrayList<>(r.getExternalConnections());
        boolean removed = connections.removeIf(ec -> ec.getExternalConnectionName().equals(externalConnection));
        if (!removed) {
            throw notFound("Repository '" + repository + "' has no external connection named '"
                    + externalConnection + "'.", repository, "repository");
        }
        r.setExternalConnections(connections);
        repositories.putForAccount(owner, key, r);
        return r;
    }

    // ------------------------------------------------------- package versions

    public synchronized PublishPackageVersionResult publishPackageVersion(String region, String domain,
            String domainOwner, String repository, String format, String namespace, String packageName,
            String version, String assetName, String assetSha256, String unfinishedParam, byte[] content) {
        if (!"generic".equals(format)) {
            throw validation("format must be 'generic'; PublishPackageVersion only supports the generic "
                    + "package format.");
        }
        boolean unfinished = parseBoolean(unfinishedParam);
        validatePackageToken("package", packageName);
        validatePackageToken("packageVersion", version);
        if (namespace != null) {
            validatePackageToken("namespace", namespace);
        }
        validateAssetName(assetName);
        if (assetSha256 == null || !SigV4RequestValidator.isSha256Hex(assetSha256)) {
            throw validation("assetSHA256 is required and must be a 64-character SHA-256 hex digest.");
        }
        byte[] assetContent = content != null ? content : new byte[0];
        if (assetContent.length > MAX_ASSET_FILE_SIZE_BYTES) {
            throw new AwsException("ServiceQuotaExceededException",
                    "The maximum asset file size is 5 Gigabytes.", 402,
                    resourceFields(assetName, "asset"));
        }
        String actualSha256 = Digests.sha256Hex(assetContent);
        if (!assetSha256.equalsIgnoreCase(actualSha256)) {
            throw validation("assetSHA256 does not match the SHA-256 hash of the uploaded content.");
        }

        requireNonBlank(domain, "domain");
        String owner = effectiveOwner(domainOwner);
        requireRepository(owner, repositoryKey(region, domain, repository), repository);
        // A caller-configured publish:BLOCK (see PutPackageOriginConfiguration) gates the whole
        // operation before any idempotency check below, the same way a real prerequisite failure
        // would: a retry of already-published bytes is still a PublishPackageVersion call, and
        // AWS's own documented behavior ("version 1.2 cannot be published" once Publish is set to
        // Block) draws no bytes-already-match exception to it.
        String originKey = packageKey(region, domain, repository, format, namespace, packageName);
        String publishRestriction = packageOriginConfigs.getForAccount(owner, originKey)
                .map(PackageOriginConfig::getPublishRestriction)
                .orElse("ALLOW");
        if ("BLOCK".equals(publishRestriction)) {
            throw conflict("Package '" + packageName + "' cannot be published because its origin configuration "
                    + "blocks direct publish.", packageName, "package");
        }
        String key = packageVersionKey(region, domain, repository, format, namespace, packageName, version);

        CodeArtifactPackageVersion pv = packageVersions.getForAccount(owner, key).orElse(null);
        if (pv != null && "Published".equals(pv.getStatus())) {
            // AWS's idempotency rule ("Overwriting package assets" in CodeArtifact's packages
            // overview) is not scoped to Unfinished versions, and the terminal publish call (the
            // one that finishes a version, or a single-shot generic publish that's Published
            // immediately) is the likeliest one a client retries after a dropped response. A brand
            // new asset name is still rejected outright: once Published, only a byte-identical
            // replay of an asset that's already there is idempotent, nothing new can be added.
            PackageAsset existingAsset = pv.getAssets().get(assetName);
            if (existingAsset == null) {
                throw conflict("Package version '" + version + "' of package '" + packageName + "' is already "
                        + "Published; no additional assets can be uploaded to it.", version, "package-version");
            }
            // Throws on a genuine mismatch; a Published version can never transition to anything
            // else via republish, so the match/no-stored-hash distinction contentMatchesExisting
            // returns doesn't matter here the way it does for the Unfinished path below.
            contentMatchesExisting(existingAsset, assetName, version, packageName, actualSha256);
            // Only rewrites when the actual disk bytes don't check out: an intact retry (the
            // overwhelmingly common case) must stay a true no-op that needs no write capacity at
            // all, not fail just because the asset store happens to be low on free space. Repairs
            // (missing file, or bytes corrupted independently of Floci) still self-heal here, since
            // existingFileMatches actually reads the file back rather than trusting it's fine.
            if (!existingFileMatches(owner, key, assetName, actualSha256)) {
                writeAssetContent(owner, key, assetName, assetContent);
            }
            return new PublishPackageVersionResult(pv, existingAsset);
        }
        if (pv == null) {
            pv = new CodeArtifactPackageVersion();
            pv.setDomainName(domain);
            pv.setDomainOwner(owner);
            pv.setRegion(region);
            pv.setRepositoryName(repository);
            pv.setFormat(format);
            pv.setNamespace(namespace);
            pv.setPackageName(packageName);
            pv.setVersion(version);
        }

        Map<String, PackageAsset> assets = new LinkedHashMap<>(pv.getAssets());
        PackageAsset existingAsset = assets.get(assetName);
        if (existingAsset != null) {
            // Throws on a genuine mismatch. A confirmed exact match is a true no-op only when this
            // call also isn't trying to finalize the version (unfinished=false): that's a real
            // status transition to Published even when the one asset it happens to touch is
            // unchanged, and must not be skipped just because that asset's bytes already matched.
            boolean matches = contentMatchesExisting(existingAsset, assetName, version, packageName, actualSha256);
            if (matches && unfinished) {
                // Only rewrites when the actual disk bytes don't check out: see the identical
                // reasoning on the Published branch above.
                if (!existingFileMatches(owner, key, assetName, actualSha256)) {
                    writeAssetContent(owner, key, assetName, assetContent);
                }
                return new PublishPackageVersionResult(pv, existingAsset);
            }
        }
        if (existingAsset == null && assets.size() >= MAX_ASSETS_PER_PACKAGE_VERSION) {
            throw new AwsException("ServiceQuotaExceededException",
                    "A package version can have a maximum of " + MAX_ASSETS_PER_PACKAGE_VERSION + " assets.", 402,
                    resourceFields(version, "package-version"));
        }

        PackageAsset asset = new PackageAsset();
        asset.setName(assetName);
        asset.setSize(assetContent.length);
        asset.setContent(assetContent);
        asset.setHashes(computeHashes(assetContent));
        writeAssetContent(owner, key, assetName, assetContent);
        assets.put(assetName, asset);
        pv.setAssets(assets);

        pv.setStatus(unfinished ? "Unfinished" : "Published");
        if (!unfinished && pv.getPublishedTime() == null) {
            pv.setPublishedTime(Instant.now().getEpochSecond());
        }
        pv.setRevision(newRevision());

        packageVersions.putForAccount(owner, key, pv);
        return new PublishPackageVersionResult(pv, asset);
    }

    public CodeArtifactPackageVersion describePackageVersion(String region, String domain, String domainOwner,
            String repository, String format, String namespace, String packageName, String version) {
        if (format == null || !PACKAGE_FORMATS.contains(format)) {
            throw validation("format must be one of " + PACKAGE_FORMATS + ".");
        }
        requireNonBlank(domain, "domain");
        requireNonBlank(repository, "repository");
        requireNonBlank(packageName, "package");
        requireNonBlank(version, "packageVersion");
        validatePackageToken("package", packageName);
        validatePackageToken("packageVersion", version);
        if (namespace != null) {
            validatePackageToken("namespace", namespace);
        }
        String owner = effectiveOwner(domainOwner);
        // Maven, npm, and pypi are metadata-free passthroughs straight to their own sidecar, the
        // same reason getPackageVersionAsset bridges to them (see
        // getContainerBackedPackageVersionAsset): publish for these formats never goes through
        // publishPackageVersion, so packageVersions can never hold a record for them.
        if (CONTAINER_BACKED_FORMATS.contains(format)) {
            return describeContainerBackedPackageVersion(region, domain, owner, repository, format, namespace,
                    packageName, version);
        }
        String key = packageVersionKey(region, domain, repository, format, namespace, packageName, version);
        return packageVersions.getForAccount(owner, key)
                .orElseThrow(() -> notFound("Package version '" + version + "' of package '" + packageName
                        + "' was not found.", version, "package-version"));
    }

    /**
     * Unlike {@code GetPackageVersionAsset}, real CodeArtifact's {@code DescribePackageVersion}
     * response has no asset listing at all (confirmed against the wire model: {@code
     * PackageVersionDescription} has no such member; enumerating a version's files is {@code
     * ListPackageVersionAssets}'s job, a separate, still-unimplemented action). All this needs
     * from a sidecar is a yes/no answer to "does this exact version exist," which is cheap for
     * every one of the three: Verdaccio's packument already lists every version it holds,
     * Reposilite's own details API answers directory existence the same way {@link
     * #ensureFormatContainerId}'s readiness probe already relies on, and pypiserver's per-project
     * JSON listing already groups files by version the same way {@code deletePackage} already
     * reads it.
     *
     * <p>A sidecar-backed version is always reported {@code Published}: nothing here ever leaves
     * one in {@code Unfinished}, the way a generic publish deliberately can, since a real {@code
     * mvn deploy}/{@code npm publish}/{@code twine upload} only ever lands a version by completing
     * it in one shot.
     *
     * <p>Namespace is validated through the same shared {@link #validatePackageCoordinates} every
     * other package-level operation already uses, rather than a one-off maven-only check: a pypi
     * version genuinely has no namespace, so a caller-supplied one must be rejected outright, not
     * silently ignored and echoed back in the response as though it belonged to the package.
     */
    private CodeArtifactPackageVersion describeContainerBackedPackageVersion(String region, String domain,
            String owner, String repository, String format, String namespace, String packageName, String version) {
        validatePackageCoordinates(format, namespace, packageName, "describing a version of");
        String repoId = ensureFormatContainerId(format, region, domain, owner, repository);
        RepositorySidecarManager manager = sidecarRegistry.forFormat(format)
                .orElseThrow(() -> notFound("Package version '" + version + "' of package '" + packageName
                        + "' was not found.", version, "package-version"));
        if (!manager.packageVersionExists(repoId, domain, repository, namespace, packageName, version)) {
            throw notFound("Package version '" + version + "' of package '" + packageName
                    + "' was not found.", version, "package-version");
        }
        CodeArtifactPackageVersion pv = new CodeArtifactPackageVersion();
        pv.setDomainName(domain);
        pv.setDomainOwner(owner);
        pv.setRegion(region);
        pv.setRepositoryName(repository);
        pv.setFormat(format);
        pv.setNamespace(namespace);
        pv.setPackageName(packageName);
        pv.setVersion(version);
        pv.setStatus("Published");
        pv.setRevision(synthesizeContainerBackedRevision(repoId, namespace, packageName, version));
        return pv;
    }

    /**
     * Floci never ingests packages from an upstream source, so every package it holds was first
     * published directly. That is the one case where CodeArtifact's default origin controls are
     * publish ALLOW and upstream BLOCK; a package a caller has configured via
     * {@link #putPackageOriginConfiguration} carries whatever restrictions were set there instead.
     */
    public record PackageDescription(String format, String namespace, String packageName,
                                     String publishRestriction, String upstreamRestriction) {}

    public PackageDescription describePackage(String region, String domain, String domainOwner, String repository,
                                              String format, String namespace, String packageName) {
        validatePackageCoordinates(format, namespace, packageName, "describing");
        requireNonBlank(domain, "domain");
        requireNonBlank(repository, "repository");
        String owner = effectiveOwner(domainOwner);
        requireRepository(owner, repositoryKey(region, domain, repository), repository);
        String originKey = packageKey(region, domain, repository, format, namespace, packageName);
        Optional<PackageOriginConfig> originConfig = packageOriginConfigs.getForAccount(owner, originKey);
        // PutPackageOriginConfiguration can be called on a package with no versions yet, creating
        // it with no versions (its own documented behavior): a stored config, even with zero
        // versions behind it, already proves the package exists, and the && below must short-circuit
        // on originConfig.isEmpty() before packageExistsViaVersionsOrSidecar ever runs. For a
        // container-backed format that call would otherwise start the real Maven/npm/pypi/nuget sidecar
        // container (its own ensureReady lazily provisions one) just to answer a DescribePackage for
        // a package nothing was ever actually published to.
        if (originConfig.isEmpty()
                && !packageExistsViaVersionsOrSidecar(region, domain, owner, repository, format, namespace,
                        packageName)) {
            throw notFound("Package '" + packageName + "' was not found.", packageName, "package");
        }
        String publishRestriction = originConfig.map(PackageOriginConfig::getPublishRestriction).orElse("ALLOW");
        String upstreamRestriction = originConfig.map(PackageOriginConfig::getUpstreamRestriction).orElse("BLOCK");
        return new PackageDescription(format, namespace, packageName, publishRestriction, upstreamRestriction);
    }

    /**
     * Sets the publish/upstream origin-control restrictions for a package. Unlike every other
     * package-level operation here, this one does not require the package to already exist:
     * AWS documents that calling it on a package with no versions yet creates the package (with
     * no versions) and applies the requested restrictions, specifically so a caller can lock down
     * a package (block direct publish, block upstream ingestion, or both) before anything is ever
     * published to it or connected to the repository.
     *
     * <p>{@code synchronized} for the same reason every other package-mutating method here is:
     * {@link #publishPackageVersion} reads this same record to decide whether to allow a publish,
     * and must never see a half-applied write.
     */
    public synchronized PackageDescription putPackageOriginConfiguration(String region, String domain,
            String domainOwner, String repository, String format, String namespace, String packageName,
            String publishRestriction, String upstreamRestriction) {
        validatePackageCoordinates(format, namespace, packageName, "setting the origin configuration of");
        // Both restrictions are required (the API reference: "You must include both the desired
        // upstream and publish restrictions"), and package groups' extra ALLOW_SPECIFIC_REPOSITORIES
        // / INHERIT values do not apply to a plain package's own restrictions. Set.of's own
        // contains(null) throws rather than returning false, so a missing value needs its own
        // null check first rather than falling into the membership test.
        if (publishRestriction == null || !ORIGIN_RESTRICTION_VALUES.contains(publishRestriction)) {
            throw validation("restrictions.publish must be one of " + ORIGIN_RESTRICTION_VALUES + ".");
        }
        if (upstreamRestriction == null || !ORIGIN_RESTRICTION_VALUES.contains(upstreamRestriction)) {
            throw validation("restrictions.upstream must be one of " + ORIGIN_RESTRICTION_VALUES + ".");
        }
        requireNonBlank(domain, "domain");
        requireNonBlank(repository, "repository");
        String owner = effectiveOwner(domainOwner);
        requireRepository(owner, repositoryKey(region, domain, repository), repository);
        String key = packageKey(region, domain, repository, format, namespace, packageName);
        packageOriginConfigs.putForAccount(owner, key, new PackageOriginConfig(publishRestriction,
                upstreamRestriction));
        return new PackageDescription(format, namespace, packageName, publishRestriction, upstreamRestriction);
    }

    /**
     * Whether a package has any real version behind it: a stored {@code packageVersions} record
     * for generic, or the repository's own sidecar for a container-backed format. Deliberately
     * does not know about {@code packageOriginConfigs}; {@link #describePackage} checks that
     * separately and short-circuits before ever calling this, so an origin-config-only package
     * (no versions) never pays for a sidecar round trip just to be found.
     */
    private boolean packageExistsViaVersionsOrSidecar(String region, String domain, String owner, String repository,
            String format, String namespace, String packageName) {
        return CONTAINER_BACKED_FORMATS.contains(format)
                ? containerBackedPackageExists(region, domain, owner, repository, format, namespace, packageName)
                : genericPackageExists(region, domain, owner, repository, format, namespace, packageName);
    }

    private boolean containerBackedPackageExists(String region, String domain, String owner, String repository,
            String format, String namespace, String packageName) {
        String repoId = ensureFormatContainerId(format, region, domain, owner, repository);
        return sidecarRegistry.forFormat(format)
                .map(manager -> manager.packageExists(repoId, domain, repository, namespace, packageName))
                .orElse(false);
    }

    private boolean genericPackageExists(String region, String domain, String owner, String repository,
            String format, String namespace, String packageName) {
        return !genericPackageVersions(region, domain, owner, repository, format, namespace, packageName).isEmpty();
    }

    private List<CodeArtifactPackageVersion> genericPackageVersions(String region, String domain, String owner,
            String repository, String format, String namespace, String packageName) {
        String prefix = packageVersionKey(region, domain, repository, format, namespace, packageName, "");
        String wantedNamespace = namespace == null ? "" : namespace;
        return packageVersions.scanForAccount(owner, key -> key.startsWith(prefix)).stream()
                .filter(pv -> format.equals(pv.getFormat())
                        && packageName.equals(pv.getPackageName())
                        && wantedNamespace.equals(pv.getNamespace() == null ? "" : pv.getNamespace()))
                .toList();
    }

    /**
     * Deletes a package and every one of its versions. A deleted package cannot be restored,
     * matching the real API's own documented behavior. Existence is checked where the package
     * actually lives, the same way {@link #describePackage} checks it, so a nonexistent package
     * answers {@code ResourceNotFoundException} rather than silently succeeding.
     *
     * <p>{@code synchronized} for the same reason {@link #publishPackageVersion} is: without a
     * shared lock, a publish and a delete racing the same generic package could interleave (a
     * version restored after its asset bytes are already gone, or a version published mid-scan
     * silently surviving or being dropped).
     */
    public synchronized PackageDescription deletePackage(String region, String domain, String domainOwner,
            String repository, String format, String namespace, String packageName) {
        validatePackageCoordinates(format, namespace, packageName, "deleting");
        requireNonBlank(domain, "domain");
        requireNonBlank(repository, "repository");
        String owner = effectiveOwner(domainOwner);
        requireRepository(owner, repositoryKey(region, domain, repository), repository);
        // Looked up before any deletion below: an origin-config-only package (no version ever
        // published, see putPackageOriginConfiguration) is the one case where neither branch below
        // finds anything real to delete, yet the package still exists, the same way describePackage
        // already recognizes it. hadRealContent carries whether the branch actually found something.
        String originKey = packageKey(region, domain, repository, format, namespace, packageName);
        Optional<PackageOriginConfig> originConfig = packageOriginConfigs.getForAccount(owner, originKey);
        boolean hadRealContent;
        String accountingKey = packageKey(region, domain, repository, format, namespace,
                accountingPackageName(format, packageName));
        if (CONTAINER_BACKED_FORMATS.contains(format)) {
            hadRealContent = deleteContainerBackedPackageIfPresent(region, domain, owner, repository, format,
                    namespace, packageName);
            if (hadRealContent) {
                // Bumped only on an actual deletion, never a no-op (nothing there to begin with):
                // see currentContainerBackedPackageFence's own javadoc for what this protects
                // against, and why it can replace a sidecar round trip entirely rather than just
                // relocate one.
                containerBackedPackageDeletionFence.put(ownerScopedKey(owner, accountingKey),
                        containerBackedPackageDeletionCounter.incrementAndGet());
            }
            // Harmless to do unconditionally: without this, a package republished under the same
            // namespace/packageName/version after being deleted would inherit the deleted
            // publish's readme text instead of whatever (or nothing) the new publish itself sent.
            // Built from accountingKey, not the raw originKey above: readme capture/lookup key off
            // accountingPackageName the same way the fence and byte accounting just above do (see
            // accountingPackageName's own javadoc), so a pypi package deleted under one case/
            // punctuation spelling still finds the readme captured under another.
            String ownerScopedReadmePrefix = ownerScopedKey(owner, accountingKey) + "::";
            packageVersionReadmes.keySet().stream()
                    .filter(k -> k.startsWith(ownerScopedReadmePrefix))
                    .toList()
                    .forEach(packageVersionReadmes::remove);
        } else {
            List<CodeArtifactPackageVersion> versions = genericPackageVersions(region, domain, owner, repository,
                    format, namespace, packageName);
            hadRealContent = !versions.isEmpty();
            for (CodeArtifactPackageVersion pv : versions) {
                String versionKey = packageVersionKey(region, domain, repository, format, namespace, packageName,
                        pv.getVersion());
                // Metadata goes first: once the version record is gone, no caller can reach a
                // deleted asset's bytes through it regardless of whether the cleanup below
                // succeeds. Deleting bytes first and metadata last, if a later asset in the same
                // version failed to delete, would leave a surviving record pointing at bytes
                // already gone.
                packageVersions.deleteForAccount(owner, versionKey);
                for (PackageAsset asset : pv.getAssets().values()) {
                    try {
                        deleteAssetContent(owner, versionKey, asset.getName());
                    } catch (UncheckedIOException e) {
                        LOG.warnv(e, "Could not delete CodeArtifact asset file for {0}/{1}: {2}", versionKey,
                                asset.getName(), e.getMessage());
                    }
                }
            }
        }
        if (!hadRealContent && originConfig.isEmpty()) {
            throw notFound("Package '" + packageName + "' was not found.", packageName, "package");
        }
        // DeletePackage removes the package entity itself, origin configuration included (AWS's
        // own doc: "Deletes a package and all associated package versions"), and the real
        // DeletePackage response's deletedPackage.originConfiguration reports whatever restrictions
        // were actually in effect, not CodeArtifact's bare defaults. A later DescribePackage on the
        // same coordinates must 404 rather than finding a zero-version package the deleted config
        // left behind.
        packageOriginConfigs.deleteForAccount(owner, originKey);
        // Harmless no-op for generic (nothing is ever recorded there under this key; its bytes
        // live in packageVersions instead, already gone via the version-deleting branch above).
        // Uses accountingKey, not originKey: for pypi the two can differ (see
        // accountingPackageName's own javadoc), and the byte count was written under whichever
        // one actually matches what pypiserver itself treats as this package's identity.
        containerBackedAssetBytes.remove(ownerScopedKey(owner, accountingKey));
        String publishRestriction = originConfig.map(PackageOriginConfig::getPublishRestriction).orElse("ALLOW");
        String upstreamRestriction = originConfig.map(PackageOriginConfig::getUpstreamRestriction).orElse("BLOCK");
        return new PackageDescription(format, namespace, packageName, publishRestriction, upstreamRestriction);
    }

    /**
     * Each sidecar's delete capability differs enough that there is no honest shared implementation:
     * Verdaccio and Reposilite remove a whole package in one call, while pypiserver's and BaGetter's
     * own management endpoints only delete one exact name/version pair per call, so {@code
     * PypiserverSidecarClient} and {@code BagetterSidecarClient} each list every file or version first
     * and remove each one in turn; all four are confirmed against live containers.
     * {@link RepositorySidecarManager#deletePackage}'s default still throws
     * {@link UnsupportedOperationException} for a sidecar that genuinely cannot do this, rather than
     * silently leaving the package in place or claiming a success it cannot back up; that becomes a
     * real, if deliberately blunt, AWS error here instead of an unhandled 500.
     *
     * <p>Returns whether the sidecar actually had the package, rather than throwing when it does
     * not: the caller still has to decide between success (an origin-config-only package with
     * nothing real behind it yet) and a genuine 404, which needs the origin-config lookup this
     * method deliberately knows nothing about.
     *
     * <p>Always asks the sidecar, even for a package deletePackage otherwise knows only through a
     * stored origin configuration: unlike describePackage's short-circuit, a package set up this
     * way is a real target for a later native-protocol publish (PublishPackageVersion only ever
     * accepts generic, but {@code mvn deploy}/{@code npm publish}/{@code twine upload} go straight
     * to the sidecar and leave no record here), so skipping this call could silently leave real
     * content behind. The cost is a lazily-started container on every container-backed delete,
     * origin-config-only or not; that cost already existed for every real package before this
     * method's origin-config awareness, this just stops it from being skipped by accident instead
     * of by choice.
     */
    private boolean deleteContainerBackedPackageIfPresent(String region, String domain, String owner,
            String repository, String format, String namespace, String packageName) {
        String repoId = ensureFormatContainerId(format, region, domain, owner, repository);
        RepositorySidecarManager manager = sidecarRegistry.forFormat(format)
                .orElseThrow(() -> notFound("Package '" + packageName + "' was not found.", packageName, "package"));
        if (!manager.packageExists(repoId, domain, repository, namespace, packageName)) {
            return false;
        }
        try {
            manager.deletePackage(repoId, domain, repository, namespace, packageName);
        } catch (UnsupportedOperationException e) {
            throw new AwsException("InternalServerException",
                    "Deleting a " + format + " package is not yet supported by this emulator.", 500);
        }
        return true;
    }

    private void deleteAssetContent(String owner, String packageVersionKey, String assetName) {
        if (inMemory) {
            memoryAssetStore.remove(assetStoreKey(owner, packageVersionKey, assetName));
            return;
        }
        Path filePath = resolveAssetPath(owner, packageVersionKey, assetName);
        try {
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete CodeArtifact asset file: " + filePath, e);
        }
    }

    public PackageVersionAssetResult getPackageVersionAsset(String region, String domain, String domainOwner,
            String repository, String format, String namespace, String packageName, String version,
            String assetName, String packageVersionRevision) {
        if (format == null || !PACKAGE_FORMATS.contains(format)) {
            throw validation("format must be one of " + PACKAGE_FORMATS + ".");
        }
        requireNonBlank(domain, "domain");
        requireNonBlank(repository, "repository");
        requireNonBlank(packageName, "package");
        requireNonBlank(version, "packageVersion");
        requireNonBlank(assetName, "asset");
        validatePackageToken("package", packageName);
        validatePackageToken("packageVersion", version);
        if (namespace != null) {
            validatePackageToken("namespace", namespace);
        }
        String owner = effectiveOwner(domainOwner);

        // Maven, npm, pypi, and nuget are metadata-free passthroughs straight to their own sidecar
        // (ReposiliteSidecarClient/VerdaccioSidecarClient/PypiserverSidecarClient/BagetterSidecarClient): publish for
        // these formats never goes through publishPackageVersion (it only ever accepts "generic"),
        // so packageVersions can never hold a record for them. Bridge straight to the sidecar
        // that actually has the asset instead of always 404ing here.
        if (CONTAINER_BACKED_FORMATS.contains(format)) {
            return getContainerBackedPackageVersionAsset(region, domain, owner, repository, format, namespace,
                    packageName, version, assetName, packageVersionRevision);
        }

        String key = packageVersionKey(region, domain, repository, format, namespace, packageName, version);
        CodeArtifactPackageVersion pv = packageVersions.getForAccount(owner, key)
                .orElseThrow(() -> notFound("Package version '" + version + "' of package '" + packageName
                        + "' was not found.", version, "package-version"));
        if (packageVersionRevision != null && !packageVersionRevision.equals(pv.getRevision())) {
            throw notFound("Package version '" + version + "' was not found at revision '"
                    + packageVersionRevision + "'.", version, "package-version");
        }
        PackageAsset asset = pv.getAssets().get(assetName);
        if (asset == null) {
            throw notFound("Asset '" + assetName + "' was not found on package version '" + version + "'.",
                    assetName, "asset");
        }
        // The live object's content may already be populated (same JVM as the publish that set
        // it), but it isn't guaranteed to be: a reload from the persisted store never restores it,
        // since it's excluded from that JSON/WAL. Always re-read from the asset store itself
        // rather than trusting whatever happens to already be on the object.
        asset.setContent(readAssetContent(owner, key, assetName));
        return new PackageVersionAssetResult(asset, pv.getRevision());
    }

    private PackageVersionAssetResult getContainerBackedPackageVersionAsset(String region, String domain,
            String owner, String repository, String format, String namespace, String packageName, String version,
            String assetName, String packageVersionRevision) {
        if ("maven".equals(format) && (namespace == null || namespace.isBlank())) {
            throw validation("namespace is required when requesting an asset from a maven package version.");
        }
        String repoId = ensureFormatContainerId(format, region, domain, owner, repository);
        String revision = synthesizeContainerBackedRevision(repoId, namespace, packageName, version);
        if (packageVersionRevision != null && !packageVersionRevision.equals(revision)) {
            throw notFound("Package version '" + version + "' was not found at revision '"
                    + packageVersionRevision + "'.", version, "package-version");
        }
        RepositorySidecarManager manager = sidecarRegistry.forFormat(format)
                .orElseThrow(() -> notFound("Package version '" + version + "' of package '" + packageName
                        + "' was not found.", version, "package-version"));
        byte[] content = manager.fetchPackageVersionAsset(repoId, domain, repository, namespace, packageName,
                        version, assetName)
                .orElseThrow(() -> notFound("Asset '" + assetName + "' was not found on package version '"
                        + version + "'.", assetName, "asset"));
        PackageAsset asset = new PackageAsset();
        asset.setName(assetName);
        asset.setSize(content.length);
        asset.setContent(content);
        asset.setHashes(computeHashes(content));
        return new PackageVersionAssetResult(asset, revision);
    }

    /**
     * There's no stored revision for a sidecar-backed format to compare against, so one is
     * synthesized from the version coordinate itself (this repository's own sidecar container id,
     * plus namespace/package/version), not the fetched asset's own bytes: a real revision is
     * shared by every asset of one package version (a Maven version's JAR and POM alike), so
     * hashing per-asset content would give a client that described the version once, then tried
     * to fetch a second asset at that same revision, a spurious not-found on the second call even
     * though both assets genuinely exist. Keying on the sidecar container id, not just the bare
     * namespace/package/version strings, keeps two different repositories (even two with the
     * identical coordinates) from ever synthesizing the same revision.
     *
     * <p>Shared by {@link #getContainerBackedPackageVersionAsset}, {@link
     * #describeContainerBackedPackageVersion}, and {@link #getPackageVersionReadme}'s npm/pypi/nuget
     * answers, so a client calling any two of them for the same version coordinate always sees one
     * consistent revision rather than independently-computed stand-ins that happen to differ.
     * Doesn't change when a new asset is published to the version afterward, unlike a real stored
     * revision would: nothing before this helper existed ever let a client obtain a revision for
     * these formats in the first place (not even a stale one), so this isn't a regression against
     * any previously working staleness check.
     */
    private static String synthesizeContainerBackedRevision(String repoId, String namespace, String packageName,
            String version) {
        String revisionKey = repoId + "/" + (namespace == null ? "" : namespace) + "/" + packageName + "/" + version;
        return Digests.sha256Hex(revisionKey.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Gets the readme text for one package version. AWS documents this action for npm, pypi, and
     * nuget only (see {@link #README_FORMATS}), unlike every other package-version action here,
     * which accepts the full eight-format set: Maven, Swift, generic, Ruby, and Cargo package
     * versions carry no readme in real CodeArtifact, and this rejects them outright with
     * {@code ValidationException} rather than quietly answering not-found.
     *
     * <p>Neither npm nor pypi can be answered live from the sidecar itself, confirmed directly
     * rather than assumed for either one. npm: a real npm client's publish envelope does carry a
     * per-version {@code readme} (confirmed against a real captured publish fixture), but
     * Verdaccio's own packument response does not echo it back — checked against a live Verdaccio
     * container, which hoists {@code readme} to the top level only, for the single
     * most-recently-published version, truncated to 64K, exactly like real npmjs.org's own
     * documented response shape; the per-version field in a GET response simply does not exist.
     * pypi: pypiserver parses only a distribution's filename, never its embedded metadata (also
     * confirmed directly: its own {@code PkgFile} model has no description-shaped field anywhere,
     * and its {@code /json} route's own code comment says outright "we do not presently have any
     * description/summary"). For both, the one place the text exists at all is the original
     * publish/upload itself (npm's per-version {@code readme} field; pypi's twine upload's
     * {@code description} form field, the real PyPI legacy upload API's field for a distribution's
     * long description, in Markdown or reStructuredText per AWS's own doc wording for this very
     * action); see {@link #recordPublishedPackageVersionReadme}, the only place either is ever
     * captured.
     *
     * <p>nuget differs from both: BaGetter keeps every version's own readme (and nuspec {@code
     * description} fallback) addressable by exact version for as long as that version exists
     * (confirmed directly against its package content controller), so this answers nuget live
     * against the sidecar, through {@link RepositorySidecarManager#fetchPackageVersionReadme},
     * rather than from a record captured at publish time the way npm and pypi need to.
     */
    public PackageVersionReadmeResult getPackageVersionReadme(String region, String domain, String domainOwner,
            String repository, String format, String namespace, String packageName, String version) {
        if (format == null || !README_FORMATS.contains(format)) {
            throw validation("format must be one of " + README_FORMATS + ".");
        }
        requireNonBlank(domain, "domain");
        requireNonBlank(repository, "repository");
        requireNonBlank(packageName, "package");
        requireNonBlank(version, "packageVersion");
        validatePackageToken("packageVersion", version);
        // Found by Qodo review: this used to only check namespace's token syntax, never whether
        // pypi or nuget (neither of which has one) was even given one; a caller-supplied
        // namespace for an existing pypi/nuget version could resolve a readme under a coordinate
        // that format can't actually have, instead of a ValidationException.
        validatePackageCoordinates(format, namespace, packageName, "reading the readme of");
        String owner = effectiveOwner(domainOwner);
        requireRepository(owner, repositoryKey(region, domain, repository), repository);
        String repoId = ensureFormatContainerId(format, region, domain, owner, repository);
        String revision = synthesizeContainerBackedRevision(repoId, namespace, packageName, version);
        if ("nuget".equals(format)) {
            String readme = sidecarRegistry.forFormat(format)
                    .flatMap(manager -> manager.fetchPackageVersionReadme(repoId, domain, repository, namespace,
                            packageName, version))
                    .orElseThrow(() -> notFound("Package version '" + version + "' of package '" + packageName
                            + "' was not found.", version, "package-version"));
            return new PackageVersionReadmeResult(format, namespace, packageName, version, revision, readme);
        }
        // npm and pypi: neither can be answered live (see this method's own javadoc), so a
        // version that was never captured here means it was never published.
        String key = packageVersionKey(region, domain, repository, format, namespace,
                accountingPackageName(format, packageName), version);
        String readme = Optional.ofNullable(packageVersionReadmes.get(ownerScopedKey(owner, key)))
                .orElseThrow(() -> notFound("Package version '" + version + "' of package '" + packageName
                        + "' was not found.", version, "package-version"));
        return new PackageVersionReadmeResult(format, namespace, packageName, version, revision, readme);
    }

    /**
     * Captures the readme text for one published npm or pypi package version at the one moment it
     * is ever available: right after the real upload/publish to the sidecar actually succeeded.
     * See {@link #getPackageVersionReadme}'s own javadoc for why both formats need this; neither
     * sidecar can answer for a specific version after the fact.
     *
     * <p>{@code namespace}/{@code packageName}/{@code version} arrive straight from the wire
     * protocol here (an npm publish envelope's own per-version field, or a twine upload's {@code
     * name}/{@code version} form fields), never through {@link #validatePackageToken} the way
     * every JSON-API package operation's own coordinates already are. {@link #escapeKeySegment}'s
     * whole collision-safety argument rests on no namespace/package/version that reaches it ever
     * containing a raw {@code '#'}; silently skipping one that does (rather than escaping or
     * rejecting outright) keeps that invariant true for this new path into {@link #packageVersionKey}
     * without turning a client's publish, which already succeeded against the real sidecar, into a
     * thrown exception over what is only a best-effort readme capture.
     *
     * <p>{@code synchronized}, and re-checks the repository still exists, for the same reason
     * {@code recordContainerBackedAssetBytes}-style accounting calls elsewhere in this codebase
     * do: the caller's own "does this repository still exist" check ran in a separate,
     * already-released {@code synchronized} call ({@link #ensureFormatContainerId}, inside each
     * proxy's own {@code requireRepository} path), before the real HTTP round trip to the sidecar
     * that this call reports the result of. A {@code DeleteRepository} landing in that window
     * would otherwise resurrect a key {@link #deleteRepository} already cleaned up. Re-checking
     * existence here, inside the same {@code synchronized} call that does the write, closes that
     * window the same way every other mutating method here already does.
     *
     * <p>{@code packageFenceSnapshot} is whatever {@link #currentContainerBackedPackageFence}
     * returned when the proxy called it, right before issuing the real write this call reports the
     * readme of — the exact same snapshot the proxy already takes to call
     * {@link #recordContainerBackedAssetBytes}. Without comparing it here too, a publish's readme
     * capture that runs on a delayed callback (npm's own, off the event loop) after a concurrent
     * {@link #deletePackage} or native unpublish has already removed this package would resurrect
     * a readme for a package {@link #getPackageVersionReadme} should now 404 on, the same window
     * {@code recordContainerBackedAssetBytes} already closes for byte accounting.
     */
    public synchronized void recordPublishedPackageVersionReadme(String region, String domain, String domainOwner,
            String repository, String format, String namespace, String packageName, String version,
            String readme, long packageFenceSnapshot) {
        if (!isValidPackageToken(packageName) || !isValidPackageToken(version)
                || (namespace != null && !isValidPackageToken(namespace))) {
            LOG.warnv("Skipping readme capture for {0}/{1} package ''{2}'' version ''{3}'': the namespace, name, "
                    + "or version contains a character ('#', '/', or whitespace) a real CodeArtifact package "
                    + "coordinate cannot have.", domain, repository, packageName, version);
            return;
        }
        String owner = effectiveOwner(domainOwner);
        if (repositories.getForAccount(owner, repositoryKey(region, domain, repository)).isEmpty()) {
            LOG.warnv("Skipping readme capture for {0}/{1}: the repository was deleted while this publish was in "
                    + "flight.", domain, repository);
            return;
        }
        String accountingName = accountingPackageName(format, packageName);
        String fenceKey = packageKey(region, domain, repository, format, namespace, accountingName);
        long currentFence = containerBackedPackageDeletionFence.getOrDefault(ownerScopedKey(owner, fenceKey), 0L);
        if (currentFence != packageFenceSnapshot) {
            LOG.warnv("Skipping readme capture for {0}/{1} package ''{2}'': a DeletePackage removed it from the "
                    + "{3} sidecar (possibly replaced by a different publish) while this publish was in flight.",
                    domain, repository, packageName, format);
            return;
        }
        String key = packageVersionKey(region, domain, repository, format, namespace, accountingName, version);
        packageVersionReadmes.put(ownerScopedKey(owner, key), readme == null ? "" : readme);
    }

    // ------------------------------------------------------------ authorization

    public AuthorizationToken getAuthorizationToken(String region, String domain, String domainOwner,
                                                      Long durationSeconds) {
        String owner = effectiveOwner(domainOwner);
        requireDomain(owner, domainKey(region, domain), domain);
        long duration = validateTokenDuration(durationSeconds);
        Instant expiration = Instant.now().plusSeconds(duration);
        String token = generateAuthorizationToken();
        authorizationTokens.put(token, new AuthorizationTokenRecord(owner, region, domain, expiration));
        return new AuthorizationToken(token, expiration.getEpochSecond());
    }

    /**
     * Resolves a live token issued for exactly this domain, returning the account and Region it
     * was issued under. A Maven request carries no SigV4 header, so there is nothing else to
     * derive the caller's Region or account from: the token itself is both the credential and, on
     * this path, the only source of that scope, the same way a real CodeArtifact authorization
     * token is.
     */
    public Optional<AuthorizationTokenScope> resolveAuthorizationToken(String token, String domain) {
        if (token == null) {
            return Optional.empty();
        }
        AuthorizationTokenRecord record = authorizationTokens.get(token);
        if (record == null) {
            return Optional.empty();
        }
        if (Instant.now().isAfter(record.expiration())) {
            authorizationTokens.remove(token);
            return Optional.empty();
        }
        if (!record.domain().equals(domain)) {
            return Optional.empty();
        }
        return Optional.of(new AuthorizationTokenScope(record.owner(), record.region()));
    }

    private static long validateTokenDuration(Long durationSeconds) {
        if (durationSeconds == null || durationSeconds == 0) {
            return DEFAULT_TOKEN_DURATION_SECONDS;
        }
        if (durationSeconds < MIN_TOKEN_DURATION_SECONDS || durationSeconds > MAX_TOKEN_DURATION_SECONDS) {
            throw validation("durationSeconds must be 0, or between " + MIN_TOKEN_DURATION_SECONDS + " and "
                    + MAX_TOKEN_DURATION_SECONDS + ".");
        }
        return durationSeconds;
    }

    private static String generateAuthorizationToken() {
        byte[] bytes = new byte[TOKEN_RANDOM_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base64Url.encode(bytes);
    }

    // -------------------------------------------------------------------- tags

    public synchronized void tagResource(String resourceArn, Map<String, String> newTags) {
        ResourceRef ref = parseResourceArn(resourceArn);
        if (ref.repository() == null) {
            String key = domainKey(ref.region(), ref.domain());
            CodeArtifactDomain d = requireDomain(ref.owner(), key, ref.domain());
            d.setTags(validateTags(newTags, d.getTags(), ref.domain(), "domain"));
            domains.putForAccount(ref.owner(), key, d);
        } else {
            String key = repositoryKey(ref.region(), ref.domain(), ref.repository());
            CodeArtifactRepository r = requireRepository(ref.owner(), key, ref.repository());
            r.setTags(validateTags(newTags, r.getTags(), ref.repository(), "repository"));
            repositories.putForAccount(ref.owner(), key, r);
        }
    }

    public synchronized void untagResource(String resourceArn, List<String> tagKeys) {
        ResourceRef ref = parseResourceArn(resourceArn);
        if (ref.repository() == null) {
            String key = domainKey(ref.region(), ref.domain());
            CodeArtifactDomain d = requireDomain(ref.owner(), key, ref.domain());
            Map<String, String> tags = new LinkedHashMap<>(d.getTags());
            tagKeys.forEach(tags::remove);
            d.setTags(tags);
            domains.putForAccount(ref.owner(), key, d);
        } else {
            String key = repositoryKey(ref.region(), ref.domain(), ref.repository());
            CodeArtifactRepository r = requireRepository(ref.owner(), key, ref.repository());
            Map<String, String> tags = new LinkedHashMap<>(r.getTags());
            tagKeys.forEach(tags::remove);
            r.setTags(tags);
            repositories.putForAccount(ref.owner(), key, r);
        }
    }

    public Map<String, String> listTagsForResource(String resourceArn) {
        ResourceRef ref = parseResourceArn(resourceArn);
        if (ref.repository() == null) {
            return requireDomain(ref.owner(), domainKey(ref.region(), ref.domain()), ref.domain()).getTags();
        }
        return requireRepository(ref.owner(), repositoryKey(ref.region(), ref.domain(), ref.repository()),
                ref.repository()).getTags();
    }

    @Override
    public void clear() {
        domains.clear();
        repositories.clear();
        packageVersions.clear();
        packageOriginConfigs.clear();
        containerBackedAssetBytes.clear();
        containerBackedPackageDeletionFence.clear();
        packageVersionReadmes.clear();
        authorizationTokens.clear();
        if (inMemory) {
            memoryAssetStore.clear();
        } else {
            deleteAssetRoot();
        }
    }

    // ----------------------------------------------------------------- helpers

    private record ResourceRef(String type, String region, String owner, String domain, String repository) {}

    private ResourceRef parseResourceArn(String resourceArn) {
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(resourceArn);
        } catch (IllegalArgumentException e) {
            throw validation("resourceArn is not a valid ARN.");
        }
        if (!"codeartifact".equals(arn.service())) {
            throw validation("resourceArn must be a CodeArtifact ARN.");
        }
        String[] parts = arn.resource().split("/");
        if (parts.length == 2 && "domain".equals(parts[0])) {
            return new ResourceRef("domain", arn.region(), arn.accountId(), parts[1], null);
        }
        if (parts.length == 3 && "repository".equals(parts[0])) {
            return new ResourceRef("repository", arn.region(), arn.accountId(), parts[1], parts[2]);
        }
        throw validation("resourceArn must reference a domain or a repository.");
    }

    private String effectiveOwner(String domainOwner) {
        if (domainOwner == null || domainOwner.isBlank()) {
            return regionResolver.getAccountId();
        }
        if (!ACCOUNT_ID.matcher(domainOwner).matches()) {
            throw validation("domainOwner must be a 12-digit account ID.");
        }
        return domainOwner;
    }

    private int repositoryCountForDomain(String owner, String region, String domain) {
        return repositories.scanForAccount(owner, k -> k.startsWith(region + "::" + domain + "::")).size();
    }

    /**
     * Sums every stored {@link PackageAsset}'s size across the domain's repositories, plus
     * whatever each repository's Maven/npm/pypi/nuget proxy has reported through
     * {@link #recordContainerBackedAssetBytes}, for {@code DescribeDomain}'s {@code
     * assetSizeBytes}.
     *
     * <p>Scoped per-repository (via each live repository's own key prefix) rather than one scan
     * over the whole domain: a defense-in-depth match for the per-repository prefix every read
     * path here already uses, not a load-bearing one by itself. {@link #deleteRepository} deletes
     * a repository's {@code packageVersions} records (and their asset content) and
     * {@code containerBackedAssetBytes} outright, by that same prefix, specifically so a
     * repository recreated under the same name can never inherit either; this method's own
     * per-repository scoping means that even a future gap in that cleanup would still only risk
     * double-scanning live data, never resurrecting a deleted repository's orphaned bytes into a
     * same-named replacement's total.
     */
    private long assetSizeBytesForDomain(String owner, String region, String domain) {
        long total = 0L;
        for (CodeArtifactRepository r : repositories.scanForAccount(owner, k -> k.startsWith(region + "::" + domain
                + "::"))) {
            String repositoryPrefix = region + "::" + domain + "::" + r.getName() + "::";
            for (CodeArtifactPackageVersion pv : packageVersions.scanForAccount(owner,
                    k -> k.startsWith(repositoryPrefix))) {
                for (PackageAsset asset : pv.getAssets().values()) {
                    total += asset.getSize();
                }
            }
            String ownerScopedRepositoryPrefix = ownerScopedKey(owner, repositoryPrefix);
            for (Map.Entry<String, Long> entry : containerBackedAssetBytes.entrySet()) {
                if (entry.getKey().startsWith(ownerScopedRepositoryPrefix)) {
                    total += entry.getValue();
                }
            }
        }
        return total;
    }

    /**
     * A snapshot each format proxy takes right before issuing its real write to the sidecar
     * (Reposilite/Verdaccio/pypiserver), to pass into {@link #recordContainerBackedAssetBytes}
     * once that write (and the proxy's own decision that it actually wrote something new)
     * completes. Comparing the two there detects not just that the package existed when the
     * write was issued, but that no {@link #deletePackage} has completed in between: a package
     * deleted and republished under the same name while an npm publish's accounting runs on a
     * delayed callback (its own Vert.x response arriving well after this snapshot was taken, long
     * after Maven's or pypi's near-immediate inline accounting would have run) must not have the
     * first publish's bytes misattributed to the second. {@code deletePackage}'s container-backed
     * branch bumps the counter behind this snapshot on an actual deletion; so does
     * {@link #forgetContainerBackedPackage}, for the one other way a container-backed package's
     * real content can disappear: a real npm client's native {@code npm unpublish}, which the
     * pinned Verdaccio image (configured with {@code unpublish: $all}) honors on its own wire
     * protocol without ever calling {@code deletePackage} (confirmed: {@code
     * CodeArtifactNpmDataPlane} forwards it straight through, detecting only enough of its shape
     * to call {@code forgetContainerBackedPackage} afterward). Maven and pypi have no such native
     * delete of their own to worry about. A snapshot that still matches at record time proves
     * neither of those two paths has removed whatever now answers to this name. Cheap and
     * lock-free (a {@code ConcurrentHashMap} read), unlike the sidecar round trip this replaces,
     * so it never costs a proxy anything to call this inline, even on a Vert.x event-loop thread.
     */
    public long currentContainerBackedPackageFence(String region, String domain, String domainOwner,
            String repository, String format, String namespace, String packageName) {
        String owner = effectiveOwner(domainOwner);
        String key = packageKey(region, domain, repository, format, namespace, accountingPackageName(format,
                packageName));
        return containerBackedPackageDeletionFence.getOrDefault(ownerScopedKey(owner, key), 0L);
    }

    /**
     * Adds {@code bytes} to the running total CodeArtifact's own storage tracks for one
     * Maven/npm/pypi/nuget package, independent of whatever that sidecar could report back later:
     * Maven, npm, and pypi expose no enumeration cheap enough for {@link #assetSizeBytesForDomain}
     * to query live (the same reason {@link #describePackageVersion} can't bridge to any of the
     * three either). nuget's BaGetter sidecar actually could answer a per-package byte total cheaply
     * through its own flat container listing, but this stays the uniform mechanism across every
     * container-backed format rather than special-casing the one sidecar that happens to support
     * it, the same reasoning {@link #deleteContainerBackedPackageIfPresent} already applies by
     * always asking the sidecar instead of trusting a stored shortcut.
     * Called by each format's own proxy only once it has already decided, in its own terms, that a
     * real write just happened: never for an idempotent short-circuit (nothing new was written) or
     * a rejected conflict (Reposilite's {@code redeployment: false}, Verdaccio's native reject, or
     * this emulator's own pypi preflight), since neither actually adds any bytes.
     * {@code packageFenceSnapshot} is whatever {@link #currentContainerBackedPackageFence} returned
     * when the proxy called it, right before issuing that write.
     *
     * <p>{@code namespace}/{@code packageName} here come straight from the wire protocol (a Maven
     * GAV path segment, an npm scope/name, a twine form field), never through
     * {@link #validatePackageCoordinates}/{@link #validatePackageToken} the way every JSON-API
     * package operation's own namespace/packageName already is. {@link #escapeKeySegment}'s whole
     * collision-safety argument rests on no namespace/package/version that reaches it ever
     * containing a raw {@code '#'}; silently skipping one that does (rather than escaping or
     * rejecting outright) keeps that invariant true for this one new path into
     * {@link #packageKey} without turning a client's publish, which already succeeded against the
     * real sidecar, into a thrown exception over what is only a best-effort accounting side
     * channel.
     *
     * <p>Unlike every other mutating method here, the caller's own "does this repository still
     * exist" check ({@code ensureFormatContainerId}, inside each proxy's own {@code requireScope}/
     * {@code requireRepository} path) ran in a separate, already-released {@code synchronized}
     * call, before the real HTTP round trip to the sidecar that this call reports the result of.
     * A {@code DeleteRepository} landing in that window, after the sidecar write already
     * succeeded but before this call runs, would otherwise resurrect a key {@code deleteRepository}
     * already cleaned up: invisible to {@code DescribeDomain} while the repository stays deleted
     * (its domain-sum loop only walks live repositories), but inherited the moment a repository is
     * recreated under the same name, since this key is name-based with no fresh id the way {@code
     * sidecarContainerIds} deliberately gets one. Re-checking existence here, inside the same
     * {@code synchronized} call that does the write, closes that window the same way every other
     * method's single-call check-then-act already does.
     *
     * <p>The repository check above does not catch the same window opening for
     * {@link #deletePackage} instead of {@link #deleteRepository}: the repository stays alive,
     * only the one package underneath it is gone, and {@link #assetSizeBytesForDomain}'s
     * per-repository scan would then count a phantom entry for a package that no longer exists, or
     * misattribute it to whatever was republished under the same name. The fence comparison below
     * closes that window precisely, with no sidecar round trip (and so no lock-held network call,
     * and no event-loop-blocking call from an async proxy like npm's) needed to do it.
     */
    public synchronized void recordContainerBackedAssetBytes(String region, String domain, String domainOwner,
            String repository, String format, String namespace, String packageName, long bytes,
            long packageFenceSnapshot) {
        if (!isValidPackageToken(packageName) || (namespace != null && !isValidPackageToken(namespace))) {
            LOG.warnv("Skipping container-backed asset byte accounting for {0}/{1} package ''{2}'': namespace or "
                    + "package name contains a character ('#', '/', or whitespace) a real CodeArtifact package "
                    + "coordinate cannot have.", domain, repository, packageName);
            return;
        }
        String owner = effectiveOwner(domainOwner);
        if (repositories.getForAccount(owner, repositoryKey(region, domain, repository)).isEmpty()) {
            LOG.warnv("Skipping container-backed asset byte accounting for {0}/{1}: the repository was deleted "
                    + "while this publish was in flight.", domain, repository);
            return;
        }
        String key = packageKey(region, domain, repository, format, namespace, accountingPackageName(format,
                packageName));
        long currentFence = containerBackedPackageDeletionFence.getOrDefault(ownerScopedKey(owner, key), 0L);
        if (currentFence != packageFenceSnapshot) {
            LOG.warnv("Skipping container-backed asset byte accounting for {0}/{1} package ''{2}'': a "
                    + "DeletePackage removed it from the {3} sidecar (possibly replaced by a different publish) "
                    + "while this publish was in flight.", domain, repository, packageName, format);
            return;
        }
        String ownerScopedKey = ownerScopedKey(owner, key);
        long existing = containerBackedAssetBytes.getOrDefault(ownerScopedKey, 0L);
        containerBackedAssetBytes.put(ownerScopedKey, existing + bytes);
    }

    /**
     * Cleans up this service's own byte accounting, deletion fence, and captured readmes for one
     * container-backed package after something other than {@link #deletePackage} removed it
     * directly from the sidecar: specifically, a real npm client's native {@code npm unpublish},
     * which the pinned Verdaccio image (configured with {@code unpublish: $all}) honors on its own
     * wire protocol, bypassing {@link #deletePackage} entirely (confirmed: {@code
     * CodeArtifactNpmDataPlane} forwards any request that is not a publish straight through).
     * {@link #deletePackage}'s own container-backed branch already does all three of these same
     * things as part of deleting the real sidecar content too; this is that same bookkeeping on
     * its own, for when the sidecar content is already gone by a path {@link #deletePackage} never
     * saw. Without the readme cleanup here, {@link #getPackageVersionReadme} would keep answering
     * for a package a real npm registry (and real CodeArtifact) now 404s on, forever, since nothing
     * else ever revisits that store once it's written.
     *
     * <p>{@code namespace}/{@code packageName} come straight from the wire protocol here too (the
     * unpublish request's own path), so this guards the same escape-collision invariant
     * {@link #recordContainerBackedAssetBytes} does, the same way and for the same reason.
     */
    public synchronized void forgetContainerBackedPackage(String region, String domain, String domainOwner,
            String repository, String format, String namespace, String packageName) {
        if (!isValidPackageToken(packageName) || (namespace != null && !isValidPackageToken(namespace))) {
            LOG.warnv("Ignoring a native unpublish of {0}/{1} package ''{2}'': namespace or package name contains "
                    + "a character ('#', '/', or whitespace) a real CodeArtifact package coordinate cannot have.",
                    domain, repository, packageName);
            return;
        }
        String owner = effectiveOwner(domainOwner);
        String key = packageKey(region, domain, repository, format, namespace, accountingPackageName(format,
                packageName));
        String ownerScopedKey = ownerScopedKey(owner, key);
        containerBackedAssetBytes.remove(ownerScopedKey);
        containerBackedPackageDeletionFence.put(ownerScopedKey, containerBackedPackageDeletionCounter.incrementAndGet());
        // Same key as the accounting/fence entries above: recordPublishedPackageVersionReadme now
        // normalizes through accountingPackageName the same way (see its own javadoc), so this
        // prefix matches whatever it wrote regardless of format.
        String readmePrefix = ownerScopedKey + "::";
        packageVersionReadmes.keySet().stream()
                .filter(k -> k.startsWith(readmePrefix))
                .toList()
                .forEach(packageVersionReadmes::remove);
    }

    /**
     * The fence and {@code containerBackedAssetBytes} are both process-wide plain
     * {@code ConcurrentHashMap}s, not account-scoped storage the way every durable store here is,
     * so the account has to be folded into the key explicitly rather than relying on an
     * {@code AccountAwareStorageBackend} to keep two accounts' identical-looking package keys
     * apart.
     */
    private static String ownerScopedKey(String owner, String packageKey) {
        return owner + "::" + packageKey;
    }

    /**
     * The same PEP 503 project-name normalization {@code PypiserverSidecarClient} applies before
     * every lookup against the real pypiserver sidecar (lowercase, runs of {@code -_.} collapsed
     * to one {@code -}), applied here too, only for pypi's own accounting/fence key: pypiserver
     * treats case- and punctuation-equivalent spellings as the same package, so an upload spelled
     * one way and a later {@code DeletePackage} spelled another must still address the same byte
     * count and the same fence entry, the way they already address the same sidecar package.
     * {@code packageOriginConfigs} deliberately keeps using the caller's raw spelling unchanged;
     * that mismatch is pre-existing and unrelated to the byte accounting this method guards.
     */
    private static String accountingPackageName(String format, String packageName) {
        if ("pypi".equals(format)) {
            return packageName.toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-");
        }
        // AWS's own NuGet normalization doc: "CodeArtifact normalizes NuGet package names by
        // converting all letters to lowercase," with no further collapsing the way pypi's PEP 503
        // normalization does, so this never needs anything beyond a case change.
        if ("nuget".equals(format)) {
            return packageName.toLowerCase(Locale.ROOT);
        }
        return packageName;
    }

    private int domainCountForAccount(String owner, String region) {
        return domains.scanForAccount(owner, k -> k.startsWith(region + "::")).size();
    }

    private void validateUpstreams(String owner, String region, String domain, String repository,
                                    List<String> upstreams) {
        if (upstreams == null) {
            return;
        }
        if (upstreams.size() > 10) {
            throw new AwsException("ServiceQuotaExceededException",
                    "A repository can have a maximum of 10 direct upstream repositories.", 402,
                    resourceFields(repository, "repository"));
        }
        for (String upstream : upstreams) {
            if (upstream.equals(repository)) {
                throw validation("A repository cannot be its own upstream.");
            }
            if (repositories.getForAccount(owner, repositoryKey(region, domain, upstream)).isEmpty()) {
                throw notFound("Upstream repository '" + upstream + "' was not found in domain '" + domain + "'.",
                        upstream, "repository");
            }
        }
    }

    // Used by DeleteDomain too: AWS returns ResourceNotFoundException for a missing domain there,
    // although the API reference does not declare it on that operation.
    private CodeArtifactDomain requireDomain(String owner, String key, String domainName) {
        if (domainName == null || domainName.isBlank()) {
            throw validation("domain is required.");
        }
        return domains.getForAccount(owner, key)
                .orElseThrow(() -> notFound("Domain not found.", domainName, "domain"));
    }

    private CodeArtifactRepository requireRepository(String owner, String key, String repositoryName) {
        if (repositoryName == null || repositoryName.isBlank()) {
            throw validation("repository is required.");
        }
        return repositories.getForAccount(owner, key)
                .orElseThrow(() -> notFound("Repository not found.", repositoryName, "repository"));
    }

    private void checkRevision(String currentRevision, String requestedRevision, String resourceId,
                                String resourceType) {
        if (requestedRevision != null && !requestedRevision.equals(currentRevision)) {
            throw conflict("The policy revision does not match the current policy revision.", resourceId,
                    resourceType);
        }
    }

    private static String newRevision() {
        return UUID.randomUUID().toString();
    }

    private static void validatePolicyDocument(String policyDocument) {
        if (policyDocument == null || policyDocument.isBlank() || policyDocument.length() > 7168) {
            throw validation("policyDocument must be 1-7168 characters.");
        }
    }

    private static void validateDescription(String description) {
        if (description != null && description.length() > 1000) {
            throw validation("description must be at most 1000 characters.");
        }
    }

    /**
     * {@code domain} is required on every operation that takes it, but a repository-scoped
     * lookup only guards its own {@code repository} argument via {@link #requireRepository}; a
     * missing domain would otherwise get silently baked into the composite key as the literal
     * string "null" and surface as a misleading "Repository not found" instead of a proper
     * validation error.
     */
    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw validation(fieldName + " is required.");
        }
    }

    private static void validateDomainName(String domain) {
        if (domain == null || !DOMAIN_NAME.matcher(domain).matches()) {
            throw validation("domain must be 2-50 characters and match [a-z][a-z0-9-]*[a-z0-9].");
        }
    }

    private static void validateRepositoryName(String repository) {
        if (repository == null || !REPOSITORY_NAME.matcher(repository).matches()) {
            throw validation("repository must be 2-100 characters and match [A-Za-z0-9][A-Za-z0-9._-]*.");
        }
    }

    private static Map<String, String> validateTags(Map<String, String> newTags, Map<String, String> existing,
                                                      String resourceId, String resourceType) {
        Map<String, String> merged = new LinkedHashMap<>(existing);
        if (newTags == null) {
            return merged;
        }
        newTags.forEach((k, v) -> {
            if (k == null || k.isBlank() || k.length() > 128 || k.startsWith("aws:")) {
                throw validation("Tag key '" + k + "' is invalid.");
            }
            if (v == null || v.length() > 256) {
                throw validation("Tag value for key '" + k + "' is invalid.");
            }
            merged.put(k, v);
        });
        if (merged.size() > 200) {
            throw new AwsException("ServiceQuotaExceededException",
                    "The maximum number of tags (200) for this resource has been exceeded.", 402,
                    resourceFields(resourceId, resourceType));
        }
        return merged;
    }

    private static void validatePackageToken(String field, String value) {
        if (!isValidPackageToken(value)) {
            throw validation(field + " must be 1-255 characters with no '#', '/', or whitespace.");
        }
    }

    /**
     * The non-throwing form of {@link #validatePackageToken}, for a caller like
     * {@link #recordContainerBackedAssetBytes} or {@link #recordPublishedPackageVersionReadme}
     * that takes a namespace/package/version straight from a wire protocol rather than the JSON
     * API's own validated request fields, and must skip a value that fails this rather than throw:
     * the real write it guards already succeeded against a live sidecar by the time it runs, so
     * rejecting it here would turn that success into a client-visible error over what is only
     * best-effort bookkeeping.
     */
    private static boolean isValidPackageToken(String value) {
        return value != null && !value.isEmpty() && value.length() <= 255 && PACKAGE_TOKEN.matcher(value).matches();
    }

    /**
     * The format/package/namespace coordinate validation shared by every package-level operation
     * that takes them. {@code action} is a present-participle phrase ("describing", "setting the
     * origin configuration of") substituted into the one message that otherwise differs per caller.
     *
     * <p>The namespace rule is the API reference's own namespace parameter doc for these actions:
     * "The namespace is required when requesting packages of the following formats: Maven, Swift,
     * generic." pypi, nuget, ruby, and cargo packages have no namespace at all, so a supplied one
     * can never be real; echoing it back in a response would misrepresent a package that way.
     */
    private static void validatePackageCoordinates(String format, String namespace, String packageName,
                                                     String action) {
        if (format == null || !PACKAGE_FORMATS.contains(format)) {
            throw validation("format must be one of " + PACKAGE_FORMATS + ".");
        }
        validatePackageToken("package", packageName);
        if (namespace != null) {
            validatePackageToken("namespace", namespace);
        }
        if (("maven".equals(format) || "generic".equals(format)) && (namespace == null || namespace.isBlank())) {
            throw validation("namespace is required when " + action + " a " + format + " package.");
        }
        if (("pypi".equals(format) || "nuget".equals(format)) && namespace != null) {
            throw validation(format + " packages do not have a namespace.");
        }
    }

    private static void validateAssetName(String assetName) {
        if (assetName == null || assetName.isEmpty() || assetName.length() > 255
                || !ASSET_NAME.matcher(assetName).matches()) {
            throw validation("asset must be 1-255 characters with no control characters.");
        }
    }

    // ----------------------------------------------------------- asset bytes

    /**
     * {@code true} when the existing backing bytes actually hash to {@code expectedSha256}, so an
     * idempotent retry can safely skip rewriting them; {@code false} when the file is missing,
     * unreadable, or its real content doesn't match, meaning a repair write is still needed. For
     * disk-backed storage this streams the file in fixed-size chunks rather than loading it whole:
     * the caller's own upload is already fully resident in memory, so verifying a large existing
     * asset this way costs no second full copy the way loading it to compare would. Deliberately
     * separate from {@link #contentMatchesExisting}, which only compares persisted metadata and
     * never touches disk: that is enough to reject a genuinely different republish outright, but
     * not enough to know the disk file backing a *matching* one hasn't been corrupted or gone
     * missing independently of Floci, which only actually reading it back can confirm.
     */
    private boolean existingFileMatches(String owner, String packageVersionKey, String assetName,
                                         String expectedSha256) {
        if (inMemory) {
            byte[] existing = memoryAssetStore.get(assetStoreKey(owner, packageVersionKey, assetName));
            return existing != null && expectedSha256.equalsIgnoreCase(Digests.sha256Hex(existing));
        }
        Path filePath = resolveAssetPath(owner, packageVersionKey, assetName);
        try (InputStream in = Files.newInputStream(filePath)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return expectedSha256.equalsIgnoreCase(SigV4RequestValidator.hexEncode(digest.digest()));
        } catch (NoSuchFileException expected) {
            // Routine, not diagnostically interesting: the metadata already confirmed this asset
            // exists (the only way this method gets called), so a missing file here means something
            // touched the asset store directly without going through Floci, e.g. a partial restore
            // that skipped it. That is exactly the case this method's caller repairs, not a real
            // failure worth a log line.
            return false;
        } catch (IOException e) {
            // Unlike a missing file, any other I/O error reading an existing file is unexpected and
            // worth keeping a record of, even though the safe response is still the same: a repair
            // write, not a thrown exception that would turn an otherwise-successful idempotent
            // retry into one.
            LOG.warnv(e, "Could not verify existing CodeArtifact asset file {0}: {1}", filePath, e.getMessage());
            return false;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM does not support SHA-256", e);
        }
    }

    private void writeAssetContent(String owner, String packageVersionKey, String assetName, byte[] content) {
        if (inMemory) {
            memoryAssetStore.put(assetStoreKey(owner, packageVersionKey, assetName), content);
            return;
        }
        Path filePath = resolveAssetPath(owner, packageVersionKey, assetName);
        try {
            Files.createDirectories(filePath.getParent());
            Path tmp = filePath.resolveSibling(filePath.getFileName() + ".tmp-" + UUID.randomUUID());
            try {
                Files.write(tmp, content);
                try {
                    Files.move(tmp, filePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write CodeArtifact asset file: " + filePath, e);
        }
    }

    private byte[] readAssetContent(String owner, String packageVersionKey, String assetName) {
        if (inMemory) {
            byte[] content = memoryAssetStore.get(assetStoreKey(owner, packageVersionKey, assetName));
            return content != null ? content : new byte[0];
        }
        Path filePath = resolveAssetPath(owner, packageVersionKey, assetName);
        try {
            return Files.readAllBytes(filePath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read CodeArtifact asset file: " + filePath, e);
        }
    }

    private static String assetStoreKey(String owner, String packageVersionKey, String assetName) {
        return owner + "::" + packageVersionKey + "::" + assetName;
    }

    /**
     * Both path segments are SHA-256 hex digests, never the raw names: an asset name may contain
     * '/', '.', '..' or run long, and the version key can exceed a filesystem's filename limit, so
     * using either directly would let distinct legal names collide or fail. Digests are fixed
     * length, path-safe, and cannot escape the directory.
     */
    private Path resolveAssetPath(String owner, String packageVersionKey, String assetName) {
        return assetRoot.resolve(owner)
                .resolve(Digests.sha256Hex(packageVersionKey.getBytes(StandardCharsets.UTF_8)))
                .resolve(Digests.sha256Hex(assetName.getBytes(StandardCharsets.UTF_8)));
    }

    private void deleteAssetRoot() {
        if (!Files.isDirectory(assetRoot)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(assetRoot)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    LOG.errorv(e, "Failed to delete CodeArtifact asset file: {0}", p);
                }
            });
        } catch (IOException e) {
            LOG.errorv(e, "Failed to reset CodeArtifact asset storage under {0}", assetRoot);
        }
    }

    private static boolean parseBoolean(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return Boolean.parseBoolean(value);
        }
        throw validation("unfinished must be 'true' or 'false'.");
    }

    /**
     * Rejects with {@code ConflictException} when {@code existingAsset}'s stored SHA-256 differs
     * from the new upload's. Compares against the asset's own persisted {@code hashes} map rather
     * than its bytes: unlike {@code PackageAsset.content} (which is {@code @JsonIgnore} and empty
     * after a reload from persisted storage), the hash survives normally, so this needs no disk
     * read, no in-memory-store lookup, and no missing-backing-file special case at all, since a
     * hash never "goes missing" the way a file can.
     *
     * <p>Returns {@code true} only on a confirmed exact match, letting a caller treat that case as
     * a genuine no-op; {@code false} when there's no stored hash to compare against (defensive:
     * every asset gets one computed at publish time, so not a path any real request reaches).
     */
    private static boolean contentMatchesExisting(PackageAsset existingAsset, String assetName, String version,
                                                   String packageName, String actualSha256) {
        String existingSha256 = existingAsset.getHashes().get("SHA-256");
        if (existingSha256 == null) {
            return false;
        }
        if (!existingSha256.equalsIgnoreCase(actualSha256)) {
            throw conflict("Asset '" + assetName + "' already exists for package version '" + version
                    + "' of package '" + packageName + "' with different content.", assetName, "asset");
        }
        return true;
    }

    private static Map<String, String> computeHashes(byte[] content) {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String algorithm : ASSET_HASH_ALGORITHMS) {
            hashes.put(algorithm, SigV4RequestValidator.hexEncode(digest(algorithm, content)));
        }
        return hashes;
    }

    private static byte[] digest(String algorithm, byte[] content) {
        try {
            return MessageDigest.getInstance(algorithm).digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM does not support " + algorithm, e);
        }
    }

    private static String domainKey(String region, String domain) {
        return region + "::" + domain;
    }

    private static String repositoryKey(String region, String domain, String repository) {
        return region + "::" + domain + "::" + repository;
    }

    private static String packageVersionKey(String region, String domain, String repository, String format,
                                             String namespace, String packageName, String version) {
        return region + "::" + domain + "::" + repository + "::" + format + "::"
                + escapeKeySegment(namespace) + "::" + escapeKeySegment(packageName) + "::"
                + escapeKeySegment(version);
    }

    private static String packageKey(String region, String domain, String repository, String format,
                                      String namespace, String packageName) {
        return region + "::" + domain + "::" + repository + "::" + format + "::"
                + escapeKeySegment(namespace) + "::" + escapeKeySegment(packageName);
    }

    /**
     * Escapes a namespace/package/version segment before it goes into a {@code ::}-joined storage
     * key. {@code region}/{@code domain}/{@code repository}/{@code format} never need this: they're
     * each validated against a pattern that already excludes {@code :}. Namespace, package name, and
     * version are different, {@link #PACKAGE_TOKEN} only excludes {@code #}, {@code /}, and
     * whitespace, so {@code ::} itself is a legal character sequence in any of them. Without
     * escaping, namespace {@code "a::b"} + package {@code "c"} and namespace {@code "a"} + package
     * {@code "b::c"} join to the identical key, so one package's stored origin configuration (or
     * published version) silently answers for the other.
     *
     * <p>{@code #} is the escape introducer, not {@code \}: every {@code \} becomes {@code #b} and
     * every {@code :} becomes {@code #c}. {@code #} is itself one of the three characters
     * {@link #PACKAGE_TOKEN} (and AWS's own {@code PackageName}/{@code PackageNamespace} wire
     * pattern) forbids, so no namespace/package/version ever validated through this service, before
     * or after this method existed, can contain one. An escaped segment therefore never collides
     * with a pre-existing stored key built from a segment that happened to contain a literal
     * backslash: that old raw key cannot contain {@code #}, and this method's output only does when
     * it actually escaped something. For the overwhelming majority of packages, whose
     * namespace/name/version never contain a backslash or colon, this returns the input unchanged,
     * so the key built from those segments is byte-identical to the one built before this method
     * existed.
     */
    private static String escapeKeySegment(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(':') < 0 && value.indexOf('\\') < 0) {
            return value;
        }
        StringBuilder escaped = new StringBuilder(value.length() + 4);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') {
                escaped.append("#b");
            } else if (c == ':') {
                escaped.append("#c");
            } else {
                escaped.append(c);
            }
        }
        return escaped.toString();
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException conflict(String message, String resourceId, String resourceType) {
        return new AwsException("ConflictException", message, 409, resourceFields(resourceId, resourceType));
    }

    private static AwsException notFound(String message, String resourceId, String resourceType) {
        return new AwsException("ResourceNotFoundException", message, 404, resourceFields(resourceId, resourceType));
    }

    /**
     * {@link Map#of} rejects null values outright, but a resourceId can legitimately be
     * unknown at the point an error is raised; omit it rather than let that turn into an NPE
     * that replaces a clean 4xx with a 500.
     */
    private static Map<String, Object> resourceFields(String resourceId, String resourceType) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("resourceType", resourceType);
        if (resourceId != null) {
            fields.put("resourceId", resourceId);
        }
        return fields;
    }
}
