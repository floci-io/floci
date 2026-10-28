package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Base64Url;
import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.core.common.FileTrees;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
    /** The real {@code PackageVersionStatus} wire enum, confirmed against {@code service-2.json}. */
    private static final Set<String> PACKAGE_VERSION_STATUSES =
            Set.of("Published", "Unfinished", "Unlisted", "Archived", "Disposed", "Deleted");
    /** The real {@code PackageVersionOriginType} wire enum. Floci never ingests from an external
     * connection, so every version {@link #listPackageVersions} ever reports is {@code INTERNAL}. */
    private static final Set<String> ORIGIN_TYPES = Set.of("INTERNAL", "EXTERNAL", "UNKNOWN");
    /** {@code UpdatePackageVersionsStatusRequest.targetStatus}'s own documented valid values:
     * {@code Unfinished} is deliberately excluded (see {@link #updatePackageVersionsStatus}'s own
     * javadoc), and {@code Disposed}/{@code Deleted} each have their own separate action instead. */
    private static final Set<String> UPDATE_TARGET_STATUSES = Set.of("Published", "Unlisted", "Archived");
    /** {@code UpdatePackageVersionsStatusRequest.expectedStatus}'s own documented valid values. */
    private static final Set<String> UPDATE_EXPECTED_STATUSES =
            Set.of("Published", "Unlisted", "Archived", "Unfinished");
    /** {@code DisposePackageVersionsRequest.expectedStatus}'s own documented valid values: wider
     * than {@link #UPDATE_EXPECTED_STATUSES} by exactly {@code Disposed} itself. */
    private static final Set<String> DISPOSE_EXPECTED_STATUSES =
            Set.of("Published", "Unlisted", "Archived", "Unfinished", "Disposed");

    private static final Logger LOG = Logger.getLogger(CodeArtifactService.class);

    private static final Pattern DOMAIN_NAME = Pattern.compile("[a-z][a-z0-9\\-]{0,48}[a-z0-9]");
    private static final Pattern REPOSITORY_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._\\-]{1,99}");
    private static final Pattern ACCOUNT_ID = Pattern.compile("[0-9]{12}");
    private static final Pattern PACKAGE_TOKEN = Pattern.compile("[^#/\\s]+");
    // Matches the wire model's AssetName pattern exactly: any non-empty string with no
    // control/format/unassigned/surrogate/private-use characters. Without this, an asset name
    // containing '\r'/'\n' would be echoed back verbatim in the X-AssetName response header.
    private static final Pattern ASSET_NAME = Pattern.compile("\\P{C}+");
    // CopyPackageVersionsRequest.versionRevisions' map value pattern: "\S+", no whitespace
    // anywhere in the string (not merely non-blank; a value containing an interior space is
    // just as malformed as an all-whitespace one).
    private static final Pattern VERSION_REVISION_VALUE = Pattern.compile("\\S+");
    private static final List<String> ASSET_HASH_ALGORITHMS = List.of("MD5", "SHA-1", "SHA-256", "SHA-512");
    // Package-private: reused by CodeArtifactMavenController so the Maven proxy enforces the same
    // documented 5 GB AWS quota rather than duplicating the constant.
    static final long MAX_ASSET_FILE_SIZE_BYTES = 5L * 1024 * 1024 * 1024;
    private static final int MAX_ASSETS_PER_PACKAGE_VERSION = 350;
    // AWS's own wire model: every batch package-version action's own PackageVersionList shape
    // (CopyPackageVersionsRequest.versions, UpdatePackageVersionsStatusRequest.versions,
    // DisposePackageVersionsRequest.versions) has "Array Members: Maximum number of 100 items."
    // Applied the same way to versionRevisions, the alternate way of naming the same set of
    // versions.
    private static final int MAX_PACKAGE_VERSIONS_PER_BATCH_CALL = 100;
    // Each batch action's own versionRevisions map value: "Maximum length of 50."
    private static final int MAX_REVISION_LENGTH = 50;
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
    public record SuccessfulPackageVersionInfo(String revision, String status) {}
    public record PackageVersionError(String errorCode, String errorMessage) {}
    public record CopyPackageVersionsResult(Map<String, SuccessfulPackageVersionInfo> successfulVersions,
                                              Map<String, PackageVersionError> failedVersions) {}
    public record AssetSummary(String name, long size, Map<String, String> hashes) {}
    public record PackageVersionAssetsListResult(String format, String namespace, String packageName,
            String version, String versionRevision, List<AssetSummary> assets, String nextToken) {}
    public record PackageVersionSummary(String version, String revision, String status) {}
    public record ListPackageVersionsResult(String format, String namespace, String packageName,
            String defaultDisplayVersion, List<PackageVersionSummary> versions, String nextToken) {}
    public record PackageSummaryEntry(String format, String namespace, String packageName,
            String publishRestriction, String upstreamRestriction) {}
    public record ListPackagesResult(List<PackageSummaryEntry> packages, String nextToken) {}
    /** Every package a repository holds, regardless of format, before origin-restriction filtering
     * and pagination are applied in {@link #listPackages}. */
    private record PackageCoordinate(String format, String namespace, String packageName) {}

    /**
     * {@link #allPackagesInRepository}'s own two outputs, kept together so {@link #listPackages}
     * never has to re-derive one from the other: {@code coordinates} is already deduplicated and
     * normalized, and {@code originConfigsByCoordinate} is keyed by that exact same normalized
     * coordinate, resolved from the one scan of {@code packageOriginConfigs} that method already
     * does for its own union.
     */
    private record PackagesWithOriginConfigs(List<PackageCoordinate> coordinates,
            Map<PackageCoordinate, PackageOriginConfig> originConfigsByCoordinate) {}
    /** Internal working shape shared by the generic and container-backed branches of {@link
     * #listPackageVersions} before status/origin filtering and pagination are applied.
     * {@code publishedEpochSeconds} is {@code null} when nothing is known to order by. */
    private record PackageVersionEntry(String version, String revision, String status,
            Long publishedEpochSeconds) {}
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
    // Same race guard, scoped to one version instead of the whole package: see
    // currentContainerBackedVersionFence's own javadoc. Shares containerBackedPackageDeletionCounter
    // rather than a counter of its own, so a snapshot that compares equal at write time still proves
    // neither this version nor the whole package was removed in between, with a single scalar.
    private final ConcurrentHashMap<String, Long> containerBackedVersionDeletionFence = new ConcurrentHashMap<>();
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
    // Keyed the same way as packageVersionReadmes (packageVersionKey), and in memory only for the
    // identical reason: a container-backed version's status/revision override describes content
    // that lives in the sidecar container itself (or, once Disposed, used to), which does not
    // survive a Floci restart either. generic needs no equivalent of this at all; its status and
    // revision already live directly on its own packageVersions record. Absent here means
    // "Published" (every container-backed version's implicit starting status; see
    // describeContainerBackedPackageVersion's own javadoc for why nothing ever lands one in
    // Unfinished the way a generic publish deliberately can) with its usual synthesized revision,
    // the same default {@link #listPackageVersions} and {@link #describeContainerBackedPackageVersion}
    // already assumed before this existed.
    private final ConcurrentHashMap<String, PackageVersionStatusOverride>
            containerBackedPackageVersionStatusOverrides = new ConcurrentHashMap<>();
    // containerBackedPackageVersionStatusOverrides' own keys are each one version's full escaped
    // packageVersionKey, which namespace/package/version segments containing ':' or '\' make
    // lossy to split back apart into "does this key belong to package X" (see escapeKeySegment's
    // own javadoc on why that escaping exists at all). This side index answers that question
    // directly instead: which raw version strings have an override, kept under the package's own
    // (version-free) key, so listPackageVersions can still report a version whose real sidecar
    // content Disposed already deleted (so the sidecar itself no longer lists it at all) without
    // ever parsing a stored key back into parts.
    private final ConcurrentHashMap<String, Set<String>> containerBackedOverriddenVersionsByPackage =
            new ConcurrentHashMap<>();
    // Raised by Greptile's review of this PR: a namespace-less format's own caller can have no
    // reliable package-version coordinate at all to look an override up by directly (a PyPI twine
    // upload's version form field is optional, and its name field is no more trustworthy without
    // one, since pypiserver indexes by the uploaded filename, never the form). This answers "which
    // package names in this one repository+format currently have any override at all", kept under
    // a (version- and package-free) repository+format key, so a caller with only a filename can
    // test it against every override that actually exists instead of trusting an unverified
    // coordinate. Only ever written for a namespace-less format: Maven's GAV path always supplies a
    // reliable version on its own, so it never needs this, and folding its own real namespace in
    // here too would risk two different namespaces' same-spelled package name colliding into one
    // entry for no caller that would ever need it.
    private final ConcurrentHashMap<String, Set<String>> containerBackedOverriddenPackagesByRepository =
            new ConcurrentHashMap<>();

    /**
     * {@code publishedEpochSeconds} is carried forward from whichever value was already true for
     * this version the first time it ever got an override (read from the sidecar's own {@link
     * RepositorySidecarManager#listPackageVersions} before anything happened to change its
     * status), not re-derived on every later transition: once {@code Disposed}, the sidecar no
     * longer has the version at all to ask, and even for {@code Archived}/{@code Unlisted} (whose
     * assets are not deleted), AWS's own definition of "package version revision" as covering a
     * version's metadata, not just its bytes, means its original publish time should not appear to
     * change just because a later status change happened to re-derive it from a sidecar that
     * coincidentally still has the same files.
     */
    /**
     * {@code pendingAssetNames} is the container-backed dispose counterpart to {@link
     * #applyGenericStatusChange}'s own per-asset {@code assets} map bookkeeping: every asset
     * still known to need real deletion for this version, updated right after each one's own
     * delete succeeds (see {@link #deleteContainerBackedVersionAssets}'s own javadoc). Always
     * {@code List.of()} for a non-disposing override (Archived/Unlisted/Published) or the
     * implicit default {@link #currentContainerBackedVersionStatus} synthesizes, never {@code
     * null}: there is nothing pending to track outside an actual dispose.
     *
     * <p>Raised by Greptile's review: {@code VerdaccioSidecarClient#deletePackageVersionAsset}'s
     * own real npm unpublish protocol (confirmed live, see its own javadoc) removes a version
     * from the packument <em>before</em> deleting its tarball, since Verdaccio's tarball-delete
     * endpoint requires the fresh revision token only the packument update produces; that
     * ordering cannot be reversed. Once that update lands, {@link
     * RepositorySidecarManager#listPackageVersionAssetNames} can no longer answer "what is left
     * to delete" for npm at all, even moments later, so a sidecar failure on the tarball delete
     * itself used to leave a retry seeing nothing pending and reporting success with the real
     * tarball still on disk. This field is what a retry now consults instead.
     */
    private record PackageVersionStatusOverride(String status, String revision, Long publishedEpochSeconds,
            List<String> pendingAssetNames) {}

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
        // Same reasoning, same prefix, for the status overrides updatePackageVersionsStatus/
        // disposePackageVersions write: a repository recreated under this same name must not
        // inherit a previous Disposed/Archived/Unlisted override for a version with the same
        // coordinates, and the per-package side index that names which versions have one must be
        // cleaned up right alongside it so the two never drift apart (see
        // writeContainerBackedStatusOverride's own javadoc).
        containerBackedPackageVersionStatusOverrides.keySet().stream()
                .filter(k -> k.startsWith(ownerScopedOriginPrefix))
                .toList()
                .forEach(containerBackedPackageVersionStatusOverrides::remove);
        containerBackedOverriddenVersionsByPackage.keySet().stream()
                .filter(k -> k.startsWith(ownerScopedOriginPrefix))
                .toList()
                .forEach(containerBackedOverriddenVersionsByPackage::remove);
        containerBackedOverriddenPackagesByRepository.keySet().stream()
                .filter(k -> k.startsWith(ownerScopedOriginPrefix))
                .toList()
                .forEach(containerBackedOverriddenPackagesByRepository::remove);
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

    /**
     * {@link #ensureFormatContainerId} plus {@link RepositorySidecarManager#beginUse}, both under
     * this same {@code synchronized} monitor {@link #deleteRepository} also holds for its own
     * sidecar cleanup, so a delete can never run its cleanup in the gap between resolving
     * {@code repoId} and registering use of it. Used by every caller of {@link
     * #ensureFormatContainerId} whose sidecar call is bracketed by {@code beginUse}/{@code
     * endUse} at all: {@link #listPackageVersions} and {@link #listPackages}.
     */
    private synchronized String resolveContainerIdAndBeginUse(String format, String region, String domain,
            String domainOwner, String repository, RepositorySidecarManager manager) {
        String id = ensureFormatContainerId(format, region, domain, domainOwner, repository);
        manager.beginUse(id);
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
        // The User Guide draws an explicit, asymmetric line here: Archived ("you cannot use a
        // package manager or build tool to re-publish an Archived package version because it is
        // still present in the repository") and Disposed ("the package version can no longer be
        // used because the assets have been deleted") are both unconditionally blocked, with no
        // byte-identical exception stated for either, unlike DeletePackageVersions, whose own doc
        // explicitly says the freed coordinate can be "freely re-published." Without this check,
        // the fallthrough below would treat either status exactly like a brand new version and
        // let a publish silently resurrect it, undoing updatePackageVersionsStatus/
        // disposePackageVersions.
        if (pv != null && ("Archived".equals(pv.getStatus()) || "Disposed".equals(pv.getStatus()))) {
            throw conflict("Package version '" + version + "' of package '" + packageName + "' is "
                    + pv.getStatus() + " and cannot be republished.", version, "package-version");
        }
        // Unlisted only hides a version from listings; its assets stay downloadable and the User
        // Guide never says republishing it is blocked the way it does for Archived/Disposed. Folds
        // it into the same idempotent-replay rule as Published (reject new content, allow an exact
        // byte-identical retry) rather than the Unfinished fallthrough below, which would otherwise
        // let it be rebuilt as though no version existed there at all.
        if (pv != null && ("Published".equals(pv.getStatus()) || "Unlisted".equals(pv.getStatus()))) {
            // AWS's idempotency rule ("Overwriting package assets" in CodeArtifact's packages
            // overview) is not scoped to Unfinished versions, and the terminal publish call (the
            // one that finishes a version, or a single-shot generic publish that's Published
            // immediately) is the likeliest one a client retries after a dropped response. A brand
            // new asset name is still rejected outright: once Published, only a byte-identical
            // replay of an asset that's already there is idempotent, nothing new can be added.
            PackageAsset existingAsset = pv.getAssets().get(assetName);
            if (existingAsset == null) {
                throw conflict("Package version '" + version + "' of package '" + packageName + "' is already "
                        + pv.getStatus() + "; no additional assets can be uploaded to it.", version,
                        "package-version");
            }
            // Throws on a genuine mismatch; neither Published nor Unlisted can transition to
            // anything else via republish, so the match/no-stored-hash distinction
            // contentMatchesExisting returns doesn't matter here the way it does for the
            // Unfinished path below.
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
     * Lists every asset of one package version. For {@code generic}, reads straight from the
     * version's own stored {@link PackageAsset} map, the same source {@link
     * #getPackageVersionAsset} and {@link #copyGenericPackageVersionAssets} already trust.
     * Container-backed formats have no such record (same reason {@link #describePackageVersion}
     * bridges them), so this reuses {@link RepositorySidecarManager#listPackageVersionAssetNames}
     * ({@code CopyPackageVersions}' own enumeration primitive) and fetches each one to compute its
     * size and hash, the same way {@link #getContainerBackedPackageVersionAsset} already computes
     * a single asset's hash on the fly rather than trusting a stored value that doesn't exist.
     *
     * <p>{@code npm}/{@code nuget} genuinely have exactly one asset per version. {@code pypi} does
     * not: a single release can hold both a source distribution and a wheel, and {@link
     * PypiserverSidecarClient#listPackageVersionAssetNames} already lists every filename
     * pypiserver's own per-project JSON reports for that version, not just one.
     *
     * <p>For a container-backed format, only the names are enumerated up front; {@code
     * maxResults}/{@code nextToken} are applied to that name list before any byte is fetched, so
     * a page request only ever downloads and hashes the assets it actually returns, not every
     * asset the version has.
     */
    public PackageVersionAssetsListResult listPackageVersionAssets(String region, String domain, String domainOwner,
            String repository, String format, String namespace, String packageName, String version,
            Integer maxResults, String nextToken) {
        validatePackageCoordinates(format, namespace, packageName, "listing the assets of");
        requireNonBlank(domain, "domain");
        requireNonBlank(repository, "repository");
        requireNonBlank(version, "packageVersion");
        validatePackageToken("packageVersion", version);
        String owner = effectiveOwner(domainOwner);
        requireRepository(owner, repositoryKey(region, domain, repository), repository);

        PaginatedResult<AssetSummary> page;
        String revision;
        if (CONTAINER_BACKED_FORMATS.contains(format)) {
            String repoId = ensureFormatContainerId(format, region, domain, owner, repository);
            RepositorySidecarManager manager = sidecarRegistry.forFormat(format)
                    .orElseThrow(() -> notFound("Package version '" + version + "' of package '" + packageName
                            + "' was not found.", version, "package-version"));
            if (!manager.packageVersionExists(repoId, domain, repository, namespace, packageName, version)) {
                throw notFound("Package version '" + version + "' of package '" + packageName
                        + "' was not found.", version, "package-version");
            }
            List<String> assetNames;
            try {
                assetNames = manager.listPackageVersionAssetNames(repoId, domain, repository, namespace,
                        packageName, version);
            } catch (UnsupportedOperationException e) {
                throw new AwsException("InternalServerException", "Listing a " + format + " package version's "
                        + "assets is not yet supported by this emulator.", 500);
            }
            PaginatedResult<String> namePage = Pagination.paginate(assetNames, Function.identity(), maxResults,
                    nextToken, 100, 1000, "ValidationException");
            List<AssetSummary> pageAssets = new ArrayList<>();
            for (String assetName : namePage.items()) {
                byte[] content = manager.fetchPackageVersionAsset(repoId, domain, repository, namespace,
                                packageName, version, assetName)
                        .orElseThrow(() -> new AwsException("InternalServerException", "Could not list version '"
                                + version + "' of package '" + packageName + "': its asset '" + assetName
                                + "' disappeared between listing and fetching it.", 500));
                pageAssets.add(new AssetSummary(assetName, content.length, computeHashes(content)));
            }
            page = new PaginatedResult<>(pageAssets, namePage.nextToken());
            revision = currentContainerBackedVersionStatus(owner, region, domain, repository, format, namespace,
                    packageName, version, repoId).revision();
        } else {
            String key = packageVersionKey(region, domain, repository, format, namespace, packageName, version);
            CodeArtifactPackageVersion pv = packageVersions.getForAccount(owner, key)
                    .orElseThrow(() -> notFound("Package version '" + version + "' of package '" + packageName
                            + "' was not found.", version, "package-version"));
            List<AssetSummary> allAssets = pv.getAssets().values().stream()
                    .map(a -> new AssetSummary(a.getName(), a.getSize(), a.getHashes()))
                    .toList();
            page = Pagination.paginate(allAssets, AssetSummary::name, maxResults, nextToken, 100, 1000,
                    "ValidationException");
            revision = pv.getRevision();
        }
        return new PackageVersionAssetsListResult(format, namespace, packageName, version, revision, page.items(),
                page.nextToken());
    }

    /**
     * Lists every version of one package. For {@code generic}, reads straight from the stored
     * {@link CodeArtifactPackageVersion} records {@link #genericPackageVersions} already groups by
     * package; container-backed formats have no such record (same reason {@link
     * #describePackageVersion} bridges them), so this reuses each sidecar's own new {@link
     * RepositorySidecarManager#listPackageVersions}.
     *
     * <p>Existence follows the exact same rule {@link #describePackage} already uses (a version
     * published somewhere, or an origin configuration set with {@link
     * #putPackageOriginConfiguration} even with zero versions behind it), rather than
     * {@code genericPackageVersions}/the sidecar's own list being empty on its own: a package
     * origin-config'd but never published to genuinely exists with zero versions, which is an
     * empty {@code versions} list here, not a 404, the same distinction {@code describePackage}
     * already draws.
     *
     * <p>Real AWS returns every status by default ("Package versions of all statuses will be
     * returned by default," the operation's own doc), but two of its own sibling actions'
     * documentation names this exact operation as the one place {@code Archived} and {@code
     * Disposed} versions are hidden unless the caller filters by that exact status
     * (DeletePackageVersions: "Archived packages... don't show up with list package APIs (for
     * example, ListPackageVersions)"; DisposePackageVersions: pass {@code status=Disposed} "to
     * view all disposed package versions"). {@code Unlisted} stays included by default: its own
     * status description only talks about exclusion from each format's own native package-manager
     * listing (a different mechanism, never this management API). Resolved this way rather than
     * guessed, since the two sibling pages directly contradict the general doc's "all statuses"
     * line for exactly these two statuses.
     *
     * <p>{@code sortBy=PUBLISHED_TIME}, the only value {@code PackageVersionSortType} has, is
     * always applied (most recently published first) regardless of whether the caller passes it:
     * there is no other sort mode for an omitted value to mean instead. {@code originType} other
     * than {@code INTERNAL} always answers empty: Floci has no external-connection ingestion for
     * any format, so nothing it ever lists is {@code EXTERNAL} or {@code UNKNOWN}.
     *
     * <p>{@code defaultDisplayVersion} is computed over every {@code Published} version
     * regardless of the {@code status}/{@code originType} filters applied to the returned {@code
     * versions} page, the same way {@code describePackage}'s own fields aren't scoped by anything
     * caller-supplied either: it represents the package's one real default, not a property of
     * this particular request's filters.
     */
    public ListPackageVersionsResult listPackageVersions(String region, String domain, String domainOwner,
            String repository, String format, String namespace, String packageName, String status, String sortBy,
            Integer maxResults, String nextToken, String originType) {
        validatePackageCoordinates(format, namespace, packageName, "listing the versions of");
        requireNonBlank(domain, "domain");
        requireNonBlank(repository, "repository");
        if (status != null && !PACKAGE_VERSION_STATUSES.contains(status)) {
            throw validation("status must be one of " + PACKAGE_VERSION_STATUSES + ".");
        }
        if (sortBy != null && !"PUBLISHED_TIME".equals(sortBy)) {
            throw validation("sortBy must be PUBLISHED_TIME.");
        }
        if (originType != null && !ORIGIN_TYPES.contains(originType)) {
            throw validation("originType must be one of " + ORIGIN_TYPES + ".");
        }
        String owner = effectiveOwner(domainOwner);
        requireRepository(owner, repositoryKey(region, domain, repository), repository);

        String originKey = packageKey(region, domain, repository, format, namespace, packageName);
        boolean hasOriginConfig = packageOriginConfigs.getForAccount(owner, originKey).isPresent();
        List<PackageVersionEntry> entries;
        String preferredDisplayVersion = null;
        if (CONTAINER_BACKED_FORMATS.contains(format)) {
            RepositorySidecarManager manager = sidecarRegistry.forFormat(format)
                    .orElseThrow(() -> notFound("Package '" + packageName + "' was not found.", packageName,
                            "package"));
            // Resolves repoId and registers use of it atomically (see
            // resolveContainerIdAndBeginUse's own javadoc): a repository delete racing in between
            // those two steps must not be able to run a sidecar's own cleanup to completion before
            // this call gets a chance to register itself as using repoId.
            String repoId = resolveContainerIdAndBeginUse(format, region, domain, owner, repository, manager);
            List<RepositorySidecarManager.PackageVersionTimestamp> raw;
            try {
                raw = manager.listPackageVersions(repoId, domain, repository, namespace, packageName);
            } catch (UnsupportedOperationException e) {
                throw new AwsException("InternalServerException", "Listing " + format + " package versions is not "
                        + "yet supported by this emulator.", 500);
            } finally {
                manager.endUse(repoId);
            }
            // Disposed deletes a version's real sidecar content, so the sidecar's own listing
            // (raw, above) stops naming it at all; overridesByVersion is this emulator's only
            // remaining record that the version ever existed, and AWS's own documented behavior
            // is that ListPackageVersions still reports a disposed version (status=Disposed) with
            // status=Disposed, not silently drop it the moment its assets are gone.
            Map<String, PackageVersionStatusOverride> overridesByVersion = containerBackedOverridesForPackage(owner,
                    region, domain, repository, format, namespace, packageName);
            if (raw.isEmpty() && overridesByVersion.isEmpty() && !hasOriginConfig) {
                throw notFound("Package '" + packageName + "' was not found.", packageName, "package");
            }
            Set<String> coveredVersions = raw.stream().map(RepositorySidecarManager.PackageVersionTimestamp::version)
                    .collect(Collectors.toSet());
            Stream<PackageVersionEntry> fromSidecar = raw.stream().map(v -> {
                PackageVersionStatusOverride override = overridesByVersion.get(v.version());
                if (override != null) {
                    return new PackageVersionEntry(v.version(), override.revision(), override.status(),
                            override.publishedEpochSeconds());
                }
                return new PackageVersionEntry(v.version(),
                        synthesizeContainerBackedRevision(format, repoId, namespace, packageName, v.version()),
                        "Published", v.publishedTime() == null ? null : v.publishedTime().getEpochSecond());
            });
            Stream<PackageVersionEntry> disposedOnly = overridesByVersion.entrySet().stream()
                    .filter(e -> !coveredVersions.contains(e.getKey()))
                    .map(e -> new PackageVersionEntry(e.getKey(), e.getValue().revision(), e.getValue().status(),
                            e.getValue().publishedEpochSeconds()));
            entries = Stream.concat(fromSidecar, disposedOnly).toList();
            preferredDisplayVersion = manager.preferredDisplayVersion(repoId, domain, repository, namespace,
                    packageName).orElse(null);
        } else {
            List<CodeArtifactPackageVersion> versions = genericPackageVersions(region, domain, owner, repository,
                    format, namespace, packageName);
            if (versions.isEmpty() && !hasOriginConfig) {
                throw notFound("Package '" + packageName + "' was not found.", packageName, "package");
            }
            entries = versions.stream()
                    .map(pv -> new PackageVersionEntry(pv.getVersion(), pv.getRevision(), pv.getStatus(),
                            pv.getPublishedTime()))
                    .toList();
        }

        String preferredVersion = preferredDisplayVersion;
        String defaultDisplayVersion = preferredVersion != null
                && entries.stream().anyMatch(e -> e.version().equals(preferredVersion)
                        && "Published".equals(e.status()))
                ? preferredVersion
                : mostRecentlyPublishedVersion(entries);

        List<PackageVersionEntry> matchingOrigin = originType != null && !"INTERNAL".equals(originType)
                ? List.of()
                : entries;
        List<PackageVersionEntry> filtered = status != null
                ? matchingOrigin.stream().filter(e -> status.equals(e.status())).toList()
                : matchingOrigin.stream()
                        .filter(e -> !"Archived".equals(e.status()) && !"Disposed".equals(e.status()))
                        .toList();

        PaginatedResult<PackageVersionEntry> page = Pagination.paginate(filtered,
                CodeArtifactService::packageVersionSortCursor, maxResults, nextToken, 50, 1000,
                "ValidationException");
        List<PackageVersionSummary> summaries = page.items().stream()
                .map(e -> new PackageVersionSummary(e.version(), e.revision(), e.status()))
                .toList();
        return new ListPackageVersionsResult(format, namespace, packageName, defaultDisplayVersion, summaries,
                page.nextToken());
    }

    /**
     * {@link Pagination#paginate} always sorts ascending by this cursor, but {@code
     * ListPackageVersions} is always most-recently-published-first (see {@link
     * #listPackageVersions}'s own javadoc): the publish time is inverted into a fixed-width,
     * zero-padded decimal so its own ascending string order is the descending time order this
     * needs, with the version itself as a deterministic tie-break for two versions published in
     * the same second (or, for a {@code generic} {@code Unfinished} version, two with no publish
     * time at all). Fine as an opaque cursor: nothing ever decodes this value back into its parts,
     * the same way every other paginated action here only ever compares whole tokens.
     */
    private static String packageVersionSortCursor(PackageVersionEntry entry) {
        long effectiveSeconds = entry.publishedEpochSeconds() == null ? 0 : entry.publishedEpochSeconds();
        return String.format("%020d:%s", Long.MAX_VALUE - effectiveSeconds, entry.version());
    }

    /**
     * AWS documents Maven and PyPI's {@code defaultDisplayVersion} as simply "the most recently
     * published package version," and this codebase's own research found no stated rule at all
     * for {@code generic} or {@code nuget}: applying the same fallback to those two is the
     * best-justified inference available, not an AWS-confirmed rule for them specifically. Only
     * {@code Published} versions are eligible: an {@code Unfinished} one was never actually
     * completed, so it can never be "the" default to show. {@code null} when nothing qualifies
     * (every version is {@code Unfinished}).
     *
     * <p>Picks the first {@code Published} entry under {@link #packageVersionSortCursor}'s own
     * ordering, rather than independently re-deriving "most recent" from the raw timestamp: a
     * second tie-break computed here that disagreed with the cursor's could list one version
     * first while naming a different one the default. Reusing the cursor also means a sidecar
     * that could not supply a timestamp for any version (so every {@code publishedEpochSeconds}
     * is {@code null}) still gets a deterministic default, whichever {@code Published} version
     * the cursor's own tie-break puts first, instead of reporting none at all.
     */
    private static String mostRecentlyPublishedVersion(List<PackageVersionEntry> entries) {
        return entries.stream()
                .filter(e -> "Published".equals(e.status()))
                .min(Comparator.comparing(CodeArtifactService::packageVersionSortCursor))
                .map(PackageVersionEntry::version)
                .orElse(null);
    }

    public record PackageVersionStatusChangeResult(Map<String, SuccessfulPackageVersionInfo> successfulVersions,
            Map<String, PackageVersionError> failedVersions) {}

    /**
     * Updates one or more versions of a package to {@code Published}, {@code Unlisted}, or {@code
     * Archived} (confirmed against the API reference's own restriction on {@code targetStatus}:
     * {@code Unfinished} is deliberately excluded, matching the User Guide's own note that "once
     * published, a package version cannot be moved back to the Unfinished state"). To reach {@code
     * Disposed}, use {@link #disposePackageVersions} instead; this and that one share every other
     * rule through {@link #applyPackageVersionStatusChange}, confirmed against the User Guide's own
     * worked examples ("Update package version status", "Disposing of package versions") rather
     * than assumed from the shared {@code PackageVersionError} error-code enum alone, since that
     * enum is reused across several batch package-version actions and not every code it lists
     * applies to these two ({@code ALREADY_EXISTS} is {@code CopyPackageVersions}' own "destination
     * already has this version" outcome, which neither of these actions has an equivalent of):
     * <ul>
     * <li>A requested version that does not exist fails {@code NOT_FOUND}.
     * <li>{@code versionRevisions}, if given, fails a version whose current revision does not
     * match with {@code MISMATCHED_REVISION}.
     * <li>{@code expectedStatus}, if given, fails a version whose current status does not match
     * with {@code MISMATCHED_STATUS}.
     * <li>A version whose current status is already {@code Disposed} fails {@code NOT_ALLOWED}:
     * the User Guide is explicit that "you cannot move a package version from Disposed to
     * Archived, Unlisted, or Published, ... the only action that can be taken on a package version
     * that has been disposed is for it to be deleted."
     * <li>The whole call is all-or-nothing, documented as the current (but not contractually
     * guaranteed) behavior: "if one or more versions cannot have their status changed, all other
     * versions will be skipped. ... either all versions are updated successfully or no versions
     * are updated." Every version that would otherwise have succeeded is reported {@code SKIPPED}
     * rather than partially applied.
     * </ul>
     *
     * <p>{@code synchronized} for the same reason every other package-mutating method here is: a
     * concurrent publish, dispose, or another status update racing the same version must never
     * interleave with this method's own read-then-write.
     */
    public synchronized PackageVersionStatusChangeResult updatePackageVersionsStatus(String region, String domain,
            String domainOwner, String repository, String format, String namespace, String packageName,
            List<String> versions, Map<String, String> versionRevisions, String expectedStatus,
            String targetStatus) {
        if (targetStatus == null || !UPDATE_TARGET_STATUSES.contains(targetStatus)) {
            throw validation("targetStatus must be one of " + UPDATE_TARGET_STATUSES + ".");
        }
        if (expectedStatus != null && !UPDATE_EXPECTED_STATUSES.contains(expectedStatus)) {
            throw validation("expectedStatus must be one of " + UPDATE_EXPECTED_STATUSES + ".");
        }
        return applyPackageVersionStatusChange(region, domain, domainOwner, repository, format, namespace,
                packageName, "updating the status of", versions, versionRevisions, expectedStatus, targetStatus,
                false);
    }

    /**
     * Deletes the real assets of one or more versions of a package and sets their status to
     * {@code Disposed}, sharing every other rule with {@link #updatePackageVersionsStatus} through
     * {@link #applyPackageVersionStatusChange} (see that method's own javadoc for the full list).
     * {@code Disposed} has its own, wider valid set for {@code expectedStatus} than {@code
     * UpdatePackageVersionsStatus} allows (confirmed against the API reference: {@code Archived |
     * Disposed | Published | Unfinished | Unlisted}, where {@code UpdatePackageVersionsStatusRequest}
     * has no {@code Disposed} at all), unlike that action, which can never encounter a version
     * already at its own target status, disposing one that is already {@code Disposed} is a
     * meaningful (if pointless) case for a caller to probe for with {@code expectedStatus=Disposed}
     * before trying again with a different one, even though the attempt itself still fails {@code
     * NOT_ALLOWED} (disposing something already disposed changes nothing a second deletion could
     * ever improve on).
     *
     * <p>{@code synchronized} for the same reason {@link #updatePackageVersionsStatus} is.
     */
    public synchronized PackageVersionStatusChangeResult disposePackageVersions(String region, String domain,
            String domainOwner, String repository, String format, String namespace, String packageName,
            List<String> versions, Map<String, String> versionRevisions, String expectedStatus) {
        if (expectedStatus != null && !DISPOSE_EXPECTED_STATUSES.contains(expectedStatus)) {
            throw validation("expectedStatus must be one of " + DISPOSE_EXPECTED_STATUSES + ".");
        }
        return applyPackageVersionStatusChange(region, domain, domainOwner, repository, format, namespace,
                packageName, "disposing", versions, versionRevisions, expectedStatus, "Disposed", true);
    }

    /**
     * Permanently removes one or more versions of a package, every real asset included. Confirmed
     * against the wire model and the User Guide rather than assumed from the shared {@code
     * PackageVersionError} enum alone, the same discipline {@link #disposePackageVersions}'s own
     * javadoc used:
     * <ul>
     * <li>{@code DeletePackageVersionsRequest} has no {@code versionRevisions} field at all
     * (confirmed directly against {@code service-2.json}'s member list), only a single {@code
     * expectedStatus} applied to the whole batch. Unlike {@link #updatePackageVersionsStatus}/
     * {@link #disposePackageVersions}, this can never fail {@code MISMATCHED_REVISION}.
     * <li>{@code expectedStatus} reuses the full, unrestricted {@code PackageVersionStatus} shape
     * (confirmed against the wire model: unlike {@code targetStatus}, it is not narrowed to its
     * own request-specific enum the way {@link #UPDATE_EXPECTED_STATUSES}/{@link
     * #DISPOSE_EXPECTED_STATUSES} are), so every one of the six real status values is accepted.
     * <li>Deleting is explicitly documented as allowed from every status, {@code Disposed}
     * included: "the only action that can be taken on a package version that has been disposed is
     * for it to be deleted." This never reuses {@link #checkStatusTransitionAllowed}, whose
     * Disposed-blocks-further-change rule is specific to {@link #updatePackageVersionsStatus}/
     * {@link #disposePackageVersions} and would otherwise wrongly reject exactly the one case AWS
     * says this action must accept.
     * <li>Unlike {@link #disposePackageVersions}, which keeps a tombstone record so {@link
     * #describePackageVersion} can keep answering {@code Disposed}, a deleted version is
     * documented as "no longer in the repository" at all, the same as one that was never
     * published. {@link #describePackageVersion}/{@link #getPackageVersionAsset} must 404 on it
     * afterward, and the freed coordinate can be freely republished (unlike Archived/Disposed,
     * which {@link #publishPackageVersion}'s own check blocks).
     * <li>The whole call is still all-or-nothing, the same documented behavior {@link
     * #disposePackageVersions} shares: every version that would otherwise have succeeded is
     * reported {@code SKIPPED} rather than partially applied.
     * </ul>
     *
     * <p>{@code synchronized} for the same reason every other package-mutating method here is.
     */
    public synchronized PackageVersionStatusChangeResult deletePackageVersions(String region, String domain,
            String domainOwner, String repository, String format, String namespace, String packageName,
            List<String> versions, String expectedStatus) {
        validatePackageCoordinates(format, namespace, packageName, "deleting versions of");
        requireNonBlank(domain, "domain");
        requireNonBlank(repository, "repository");
        if (expectedStatus != null && !PACKAGE_VERSION_STATUSES.contains(expectedStatus)) {
            throw validation("expectedStatus must be one of " + PACKAGE_VERSION_STATUSES + ".");
        }
        if (versions == null || versions.isEmpty()) {
            throw validation("versions is required and must specify at least one version.");
        }
        if (versions.size() > MAX_PACKAGE_VERSIONS_PER_BATCH_CALL) {
            throw validation("A single call can process at most " + MAX_PACKAGE_VERSIONS_PER_BATCH_CALL
                    + " versions.");
        }
        List<String> requestedVersions = List.copyOf(new LinkedHashSet<>(versions));
        for (String version : requestedVersions) {
            validatePackageToken("packageVersion", version);
        }
        String owner = effectiveOwner(domainOwner);
        requireRepository(owner, repositoryKey(region, domain, repository), repository);

        if (CONTAINER_BACKED_FORMATS.contains(format)) {
            return deleteContainerBackedPackageVersions(region, domain, owner, repository, format, namespace,
                    packageName, requestedVersions, expectedStatus);
        }
        return deleteGenericPackageVersions(region, domain, owner, repository, format, namespace, packageName,
                requestedVersions, expectedStatus);
    }

    /**
     * The one check shared by every requested version in {@link #deletePackageVersions}, for
     * either storage branch: {@code null} means the version is eligible to actually be deleted; a
     * non-null result is the exact failure to report. Deliberately does not reuse {@link
     * #checkStatusTransitionAllowed}: that method's Disposed-blocks-further-change rule does not
     * apply here (see {@link #deletePackageVersions}'s own javadoc), and there is no revision to
     * check against at all, since the real request shape has no {@code versionRevisions} field.
     */
    private static PackageVersionError checkDeletionAllowed(String currentStatus, String expectedStatus) {
        if (expectedStatus != null && !expectedStatus.equals(currentStatus)) {
            return new PackageVersionError("MISMATCHED_STATUS", "current status: " + currentStatus
                    + ", expected status: " + expectedStatus);
        }
        return null;
    }

    /**
     * The {@code generic} branch of {@link #deletePackageVersions}: removes the version's record
     * from {@code packageVersions} first, exactly the way {@link #deletePackage} already does for
     * a whole package's worth of versions, and for the same reason: once the record is gone, no
     * caller can reach its bytes through it regardless of whether the best-effort cleanup below
     * succeeds, so a cleanup failure (logged, not thrown) can never leave a surviving record
     * pointing at bytes that are already gone. This differs from {@link #disposePackageVersions}'s
     * own stricter ordering deliberately: that action keeps a tombstone a failed cleanup could
     * misrepresent, this one does not.
     */
    private PackageVersionStatusChangeResult deleteGenericPackageVersions(String region, String domain, String owner,
            String repository, String format, String namespace, String packageName, List<String> requestedVersions,
            String expectedStatus) {
        Map<String, PackageVersionError> failures = new LinkedHashMap<>();
        List<String> pending = new ArrayList<>();
        Map<String, CodeArtifactPackageVersion> byVersion = new LinkedHashMap<>();
        for (String version : requestedVersions) {
            String key = packageVersionKey(region, domain, repository, format, namespace, packageName, version);
            CodeArtifactPackageVersion pv = packageVersions.getForAccount(owner, key).orElse(null);
            if (pv == null) {
                failures.put(version, new PackageVersionError("NOT_FOUND", "Could not find version " + version));
                continue;
            }
            PackageVersionError failure = checkDeletionAllowed(pv.getStatus(), expectedStatus);
            if (failure != null) {
                failures.put(version, failure);
                continue;
            }
            byVersion.put(version, pv);
            pending.add(version);
        }
        if (!failures.isEmpty()) {
            for (String version : pending) {
                failures.put(version, new PackageVersionError("SKIPPED", "Version " + version + " is skipped"));
            }
            return new PackageVersionStatusChangeResult(Map.of(), failures);
        }

        Map<String, SuccessfulPackageVersionInfo> successes = new LinkedHashMap<>();
        for (String version : pending) {
            CodeArtifactPackageVersion pv = byVersion.get(version);
            String key = packageVersionKey(region, domain, repository, format, namespace, packageName, version);
            packageVersions.deleteForAccount(owner, key);
            for (PackageAsset asset : pv.getAssets().values()) {
                try {
                    deleteAssetContent(owner, key, asset.getName());
                } catch (UncheckedIOException e) {
                    LOG.warnv(e, "Could not delete CodeArtifact asset file for {0}/{1}: {2}", key,
                            asset.getName(), e.getMessage());
                }
            }
            successes.put(version, new SuccessfulPackageVersionInfo(pv.getRevision(), "Deleted"));
        }
        return new PackageVersionStatusChangeResult(successes, Map.of());
    }

    /**
     * The container-backed branch of {@link #deletePackageVersions}: deletes every one of the
     * version's real assets first, through the exact same {@link #deleteContainerBackedVersionAssets}
     * {@link #disposePackageVersions} already uses (real deletion, byte accounting, and Maven
     * metadata cleanup are identical regardless of which action triggered them), propagating any
     * sidecar failure rather than swallowing it, the same ordering {@link #deletePackage}'s own
     * container-backed branch already uses for a whole package. The status override is only
     * cleared afterward, with {@link #clearContainerBackedStatusOverride} rather than {@link
     * #writeContainerBackedStatusOverride}: unlike {@link #disposePackageVersions}, this leaves no
     * tombstone, so {@link #containerBackedVersionExists} reports the version gone entirely once
     * both steps finish, matching the User Guide's "no longer in the repository" wording.
     */
    private PackageVersionStatusChangeResult deleteContainerBackedPackageVersions(String region, String domain,
            String owner, String repository, String format, String namespace, String packageName,
            List<String> requestedVersions, String expectedStatus) {
        String repoId = ensureFormatContainerId(format, region, domain, owner, repository);
        RepositorySidecarManager manager = sidecarRegistry.forFormat(format)
                .orElseThrow(() -> notFound("Package '" + packageName + "' was not found.", packageName, "package"));
        List<RepositorySidecarManager.PackageVersionTimestamp> raw;
        try {
            raw = manager.listPackageVersions(repoId, domain, repository, namespace, packageName);
        } catch (UnsupportedOperationException e) {
            throw new AwsException("InternalServerException", "Deleting " + format + " package versions is not "
                    + "yet supported by this emulator.", 500);
        }
        // Keyed by normalized version (see normalizedContainerBackedVersion), the same as
        // applyContainerBackedStatusChange's own realPublishedTimes. Raised by Greptile's review:
        // BaGetter's own listing can spell a NuGet version differently than a caller's own
        // request does, and the override side index already normalizes on write, so comparing
        // both against the same normalized form here is what keeps existence checks, override
        // reads, and the deletion gate below consistent with every other container-backed lookup.
        Map<String, Instant> realPublishedTimes = new LinkedHashMap<>();
        for (RepositorySidecarManager.PackageVersionTimestamp v : raw) {
            realPublishedTimes.put(normalizedContainerBackedVersion(format, v.version()), v.publishedTime());
        }
        Map<String, PackageVersionStatusOverride> overridesByVersion = containerBackedOverridesForPackage(owner,
                region, domain, repository, format, namespace, packageName);

        Map<String, PackageVersionError> failures = new LinkedHashMap<>();
        List<String> pending = new ArrayList<>();
        Map<String, String> finalRevisionByVersion = new LinkedHashMap<>();
        for (String version : requestedVersions) {
            String matchVersion = normalizedContainerBackedVersion(format, version);
            boolean existsNow = realPublishedTimes.containsKey(matchVersion)
                    || overridesByVersion.containsKey(matchVersion);
            if (!existsNow) {
                failures.put(version, new PackageVersionError("NOT_FOUND", "Could not find version " + version));
                continue;
            }
            PackageVersionStatusOverride override = overridesByVersion.get(matchVersion);
            String currentStatus = override != null ? override.status() : "Published";
            String currentRevision = override != null ? override.revision()
                    : synthesizeContainerBackedRevision(format, repoId, namespace, packageName, version);
            PackageVersionError failure = checkDeletionAllowed(currentStatus, expectedStatus);
            if (failure != null) {
                failures.put(version, failure);
                continue;
            }
            finalRevisionByVersion.put(version, currentRevision);
            pending.add(version);
        }
        if (!failures.isEmpty()) {
            for (String version : pending) {
                failures.put(version, new PackageVersionError("SKIPPED", "Version " + version + " is skipped"));
            }
            return new PackageVersionStatusChangeResult(Map.of(), failures);
        }

        Map<String, SuccessfulPackageVersionInfo> successes = new LinkedHashMap<>();
        for (String version : pending) {
            String matchVersion = normalizedContainerBackedVersion(format, version);
            PackageVersionStatusOverride override = overridesByVersion.get(matchVersion);
            boolean retryingPendingAssets = override != null && !override.pendingAssetNames().isEmpty();
            boolean sidecarStillListsIt = realPublishedTimes.containsKey(matchVersion);
            if (retryingPendingAssets || sidecarStillListsIt) {
                List<String> assetNames;
                if (retryingPendingAssets) {
                    // A retry of a previously partially-failed dispose, or of this same delete
                    // call (see the write below): reuse exactly what is still known to need
                    // cleanup rather than asking the sidecar again, since npm's own listing can no
                    // longer answer this once a prior attempt's own per-asset delete already
                    // updated the packument (see PackageVersionStatusOverride's own javadoc).
                    // Raised by Greptile's review: this case used to fall through to the sidecar
                    // listing below, which for npm no longer named the version at all once its
                    // packument update had landed, so these assets were silently skipped and the
                    // override cleared anyway, orphaning the tarball on disk.
                    assetNames = override.pendingAssetNames();
                } else {
                    try {
                        assetNames = manager.listPackageVersionAssetNames(repoId, domain, repository, namespace,
                                packageName, version);
                    } catch (UnsupportedOperationException e) {
                        throw new AwsException("InternalServerException", "Deleting a package version of this "
                                + "format is not yet supported by this emulator.", 500);
                    }
                }
                // Written before deleting, exactly like disposePackageVersions's own ordering: a
                // sidecar failing partway through this version's own asset deletions must leave
                // pendingAssetNames naming only what genuinely remains, so a retry (another
                // DeletePackageVersions call on the same coordinate) can still find it afterward.
                Instant publishedTime = realPublishedTimes.get(matchVersion);
                writeContainerBackedStatusOverride(owner, region, domain, repository, format, namespace,
                        packageName, version,
                        new PackageVersionStatusOverride(override != null ? override.status() : "Published",
                                override != null ? override.revision() : finalRevisionByVersion.get(version),
                                override != null ? override.publishedEpochSeconds()
                                        : (publishedTime == null ? null : publishedTime.getEpochSecond()),
                                assetNames));
                // Called even when assetNames ends up empty here (every real asset already
                // deleted by an earlier attempt): raised by Greptile's review, {@code
                // deleteContainerBackedVersionAssets}'s own trailing {@code recordVersionDisposed}
                // call is what retries Maven's metadata.xml/checksum cleanup, and gating this call
                // on a non-empty list, as an earlier version of this fix did, meant a retry whose
                // only remaining failure was that metadata update (sidecarStillListsIt true,
                // nothing left in a fresh asset listing) skipped retrying it and cleared the
                // override anyway.
                deleteContainerBackedVersionAssets(manager, repoId, owner, region, domain, repository, format,
                        namespace, packageName, version, assetNames);
            }
            clearContainerBackedStatusOverride(owner, region, domain, repository, format, namespace, packageName,
                    version);
            // npm/pypi readmes are captured separately at publish time (see
            // recordPublishedPackageVersionReadme's own javadoc) and never answered live from the
            // sidecar the way nuget's readme is, so deleting the version here would otherwise leave
            // getPackageVersionReadme still serving it forever.
            String readmeKey = packageVersionKey(region, domain, repository, format, namespace,
                    accountingPackageName(format, packageName), version);
            packageVersionReadmes.remove(ownerScopedKey(owner, readmeKey));
            // Bumps this version's own fence (see currentContainerBackedVersionFence's own
            // javadoc): raised by Greptile's review, without this a publish's readme capture
            // already in flight for this exact version, delayed past this delete, would resurrect
            // the readme just removed above, since nothing short of this would ever invalidate its
            // own snapshot.
            containerBackedVersionDeletionFence.put(ownerScopedKey(owner, readmeKey),
                    containerBackedPackageDeletionCounter.incrementAndGet());
            successes.put(version, new SuccessfulPackageVersionInfo(finalRevisionByVersion.get(version), "Deleted"));
        }
        return new PackageVersionStatusChangeResult(successes, Map.of());
    }

    private PackageVersionStatusChangeResult applyPackageVersionStatusChange(String region, String domain,
            String domainOwner, String repository, String format, String namespace, String packageName,
            String action, List<String> versions, Map<String, String> versionRevisions, String expectedStatus,
            String targetStatus, boolean disposing) {
        validatePackageCoordinates(format, namespace, packageName, action);
        requireNonBlank(domain, "domain");
        requireNonBlank(repository, "repository");
        if (versions == null || versions.isEmpty()) {
            throw validation("versions is required and must specify at least one version.");
        }
        if (versions.size() > MAX_PACKAGE_VERSIONS_PER_BATCH_CALL) {
            throw validation("A single call can process at most " + MAX_PACKAGE_VERSIONS_PER_BATCH_CALL
                    + " versions.");
        }
        List<String> requestedVersions = List.copyOf(new LinkedHashSet<>(versions));
        for (String version : requestedVersions) {
            validatePackageToken("packageVersion", version);
        }
        Map<String, String> revisions = versionRevisions == null ? Map.of() : versionRevisions;
        for (Map.Entry<String, String> entry : revisions.entrySet()) {
            String expectedRevision = entry.getValue();
            if (expectedRevision == null || expectedRevision.length() > MAX_REVISION_LENGTH
                    || !VERSION_REVISION_VALUE.matcher(expectedRevision).matches()) {
                throw validation("versionRevisions['" + entry.getKey() + "'] must be 1-" + MAX_REVISION_LENGTH
                        + " characters with no whitespace.");
            }
        }
        String owner = effectiveOwner(domainOwner);
        requireRepository(owner, repositoryKey(region, domain, repository), repository);

        if (CONTAINER_BACKED_FORMATS.contains(format)) {
            return applyContainerBackedStatusChange(region, domain, owner, repository, format, namespace,
                    packageName, requestedVersions, revisions, expectedStatus, targetStatus, disposing);
        }
        return applyGenericStatusChange(region, domain, owner, repository, format, namespace, packageName,
                requestedVersions, revisions, expectedStatus, targetStatus, disposing);
    }

    /**
     * The one check shared by every requested version in both {@link #updatePackageVersionsStatus}
     * and {@link #disposePackageVersions}, for either storage branch: {@code null} means the
     * version is eligible to actually change; a non-null result is the exact failure to report
     * (and, per the all-or-nothing rule both actions document, the reason every other
     * otherwise-eligible version in the same call gets downgraded to {@code SKIPPED} instead).
     *
     * <p>Raised by Greptile's review: an already-{@code Disposed} version used to fail {@code
     * NOT_ALLOWED} unconditionally, including a retry of {@code DisposePackageVersions} itself.
     * For the container-backed branch (see {@link #applyContainerBackedStatusChange}'s own
     * javadoc), the override is written {@code Disposed} before its real assets are deleted, so a
     * sidecar failing partway through left no way to ever finish that cleanup or repair a stale
     * {@code maven-metadata.xml}: every retry hit this exact same unconditional block first.
     * {@code targetStatus} is {@code "Disposed"} if and only if this check is running inside
     * {@link #disposePackageVersions} (confirmed against {@link #UPDATE_TARGET_STATUSES}, which
     * never includes it), so re-disposing an already-disposed version is let through as the
     * idempotent cleanup retry it actually is; {@link #updatePackageVersionsStatus} keeps the
     * unconditional block exactly as the User Guide describes {@code Disposed} ("the package
     * version can no longer be used") for every other target status.
     */
    private static PackageVersionError checkStatusTransitionAllowed(String currentStatus, String currentRevision,
            String version, Map<String, String> revisions, String expectedStatus, String targetStatus) {
        if ("Disposed".equals(currentStatus) && !"Disposed".equals(targetStatus)) {
            return new PackageVersionError("NOT_ALLOWED", "Package version " + version + " has already been "
                    + "disposed and its status can no longer be changed");
        }
        String expectedRevision = revisions.get(version);
        if (expectedRevision != null && !expectedRevision.equals(currentRevision)) {
            return new PackageVersionError("MISMATCHED_REVISION", "current revision: " + currentRevision
                    + ", expected revision: " + expectedRevision);
        }
        if (expectedStatus != null && !expectedStatus.equals(currentStatus)) {
            return new PackageVersionError("MISMATCHED_STATUS", "current status: " + currentStatus
                    + ", expected status: " + expectedStatus);
        }
        return null;
    }

    /**
     * The {@code generic} branch of {@link #applyPackageVersionStatusChange}: reads and writes
     * {@code packageVersions} directly, the same store {@link #publishPackageVersion} and {@link
     * #copyPackageVersions} already use. For {@link #disposePackageVersions}, every asset's bytes
     * are deleted the same way {@link #deletePackage} already deletes a whole package's worth, and
     * the version's own {@code assets} map is cleared, before its status and revision are updated,
     * matching the User Guide's own description of {@code Disposed} ("the assets of the package
     * version will be permanently deleted") rather than leaving the bytes in place with only the
     * status changed.
     *
     * <p>Raised by Greptile's review: the status/revision write used to run only after every
     * asset's delete succeeded, so a failure partway through left the record reading
     * {@code Published} (or whatever it was before) with its full original asset list, even
     * though some of those assets' real bytes were already gone. {@code Disposed} is now written
     * first, the same ordering {@link #applyContainerBackedStatusChange} already uses and for the
     * identical reason (see that method's own javadoc): downloads are blocked immediately, and
     * each asset is removed from the record's own {@code assets} map right after its own delete
     * succeeds, not batched at the end, so a later failure leaves this record naming only the
     * assets genuinely still there. A retry (see {@link #checkStatusTransitionAllowed}'s own
     * javadoc for why a dispose retry is let through at all) then only has those left to finish.
     */
    private PackageVersionStatusChangeResult applyGenericStatusChange(String region, String domain, String owner,
            String repository, String format, String namespace, String packageName, List<String> requestedVersions,
            Map<String, String> revisions, String expectedStatus, String targetStatus, boolean disposing) {
        Map<String, PackageVersionError> failures = new LinkedHashMap<>();
        List<String> pending = new ArrayList<>();
        Map<String, CodeArtifactPackageVersion> byVersion = new LinkedHashMap<>();
        for (String version : requestedVersions) {
            String key = packageVersionKey(region, domain, repository, format, namespace, packageName, version);
            CodeArtifactPackageVersion pv = packageVersions.getForAccount(owner, key).orElse(null);
            if (pv == null) {
                failures.put(version, new PackageVersionError("NOT_FOUND", "Could not find version " + version));
                continue;
            }
            PackageVersionError failure = checkStatusTransitionAllowed(pv.getStatus(), pv.getRevision(), version,
                    revisions, expectedStatus, targetStatus);
            if (failure != null) {
                failures.put(version, failure);
                continue;
            }
            byVersion.put(version, pv);
            pending.add(version);
        }
        if (!failures.isEmpty()) {
            for (String version : pending) {
                failures.put(version, new PackageVersionError("SKIPPED", "Version " + version + " is skipped"));
            }
            return new PackageVersionStatusChangeResult(Map.of(), failures);
        }

        Map<String, SuccessfulPackageVersionInfo> successes = new LinkedHashMap<>();
        for (String version : pending) {
            CodeArtifactPackageVersion pv = byVersion.get(version);
            String key = packageVersionKey(region, domain, repository, format, namespace, packageName, version);
            if (disposing) {
                // Written before deleting below, not after (see this method's own javadoc for
                // why): a failure partway through the loop below must never leave this record
                // reading as more intact (its old status, every asset still listed) than it now
                // really is.
                pv.setStatus(targetStatus);
                pv.setRevision(newRevision());
                packageVersions.putForAccount(owner, key, pv);
                // Unlike deletePackage's own best-effort cleanup, this record is not removed:
                // disposePackageVersions keeps it around with a Disposed status for
                // DescribePackageVersion to keep answering, so a failed delete here must not be
                // swallowed the way deletePackage's already-unreachable leftover bytes can be:
                // reporting success while clearing the asset map would permanently orphan bytes
                // this version's own record can no longer point back to.
                Map<String, PackageAsset> remaining = new LinkedHashMap<>(pv.getAssets());
                for (PackageAsset asset : pv.getAssets().values()) {
                    deleteAssetContent(owner, key, asset.getName());
                    // Removed one at a time, and re-saved after each, so a later asset's delete
                    // failing leaves this record naming only the assets genuinely still there,
                    // letting a retry pick up exactly where this one stopped.
                    remaining.remove(asset.getName());
                    pv.setAssets(Map.copyOf(remaining));
                    packageVersions.putForAccount(owner, key, pv);
                }
            } else {
                pv.setStatus(targetStatus);
                // Raised by Greptile's review: UPDATE_TARGET_STATUSES allows targeting Published
                // from Unfinished (nothing here or in checkStatusTransitionAllowed blocks it), the
                // real way a version with no publishedTime at all finalizes through this action
                // rather than through publishPackageVersion's own normal path, which already sets
                // it on the same condition. Left unset, the version sorts as though never
                // published (behind every real release in packageVersionSortCursor's own
                // ordering) and may never become defaultDisplayVersion.
                if ("Published".equals(targetStatus) && pv.getPublishedTime() == null) {
                    pv.setPublishedTime(Instant.now().getEpochSecond());
                }
                pv.setRevision(newRevision());
                packageVersions.putForAccount(owner, key, pv);
            }
            successes.put(version, new SuccessfulPackageVersionInfo(pv.getRevision(), pv.getStatus()));
        }
        return new PackageVersionStatusChangeResult(successes, Map.of());
    }

    /**
     * The container-backed branch of {@link #applyPackageVersionStatusChange}: {@link
     * RepositorySidecarManager#listPackageVersions} is fetched exactly once up front (rather than
     * once per requested version) both to check which requested versions are real and to capture
     * each one's publish time, reused by {@link #currentContainerBackedVersionStatus}'s own default
     * once this call writes an override for it. For {@link #disposePackageVersions}, every one of
     * the version's real assets is deleted through {@link RepositorySidecarManager#listPackageVersionAssetNames}/
     * {@link RepositorySidecarManager#deletePackageVersionAsset}, the only primitives this sidecar
     * interface exposes for it; no client needed a new method of its own.
     */
    private PackageVersionStatusChangeResult applyContainerBackedStatusChange(String region, String domain,
            String owner, String repository, String format, String namespace, String packageName,
            List<String> requestedVersions, Map<String, String> revisions, String expectedStatus,
            String targetStatus, boolean disposing) {
        String repoId = ensureFormatContainerId(format, region, domain, owner, repository);
        RepositorySidecarManager manager = sidecarRegistry.forFormat(format)
                .orElseThrow(() -> notFound("Package '" + packageName + "' was not found.", packageName, "package"));
        List<RepositorySidecarManager.PackageVersionTimestamp> raw;
        try {
            raw = manager.listPackageVersions(repoId, domain, repository, namespace, packageName);
        } catch (UnsupportedOperationException e) {
            throw new AwsException("InternalServerException", "Changing " + format + " package version status is "
                    + "not yet supported by this emulator.", 500);
        }
        // Built with a plain loop, not Collectors.toMap: a sidecar that could not supply a
        // timestamp for one of its versions (see listPackageVersions's own handling of this same
        // possibility) reports a null publishedTime, and Map.merge, which toMap uses internally,
        // throws NullPointerException on a null value. Keyed by the normalized version (see
        // normalizedContainerBackedVersion, raised again by Greptile's review): BaGetter's own
        // listing can spell a NuGet version differently than a caller's own request does, and the
        // override side index below already normalizes on write, so comparing both against the
        // same normalized form below is what actually keeps existence/override matching
        // consistent with GetPackageVersionAsset's own normalized lookup.
        Map<String, Instant> realPublishedTimes = new LinkedHashMap<>();
        for (RepositorySidecarManager.PackageVersionTimestamp v : raw) {
            realPublishedTimes.put(normalizedContainerBackedVersion(format, v.version()), v.publishedTime());
        }
        Map<String, PackageVersionStatusOverride> overridesByVersion = containerBackedOverridesForPackage(owner,
                region, domain, repository, format, namespace, packageName);

        Map<String, PackageVersionError> failures = new LinkedHashMap<>();
        List<String> pending = new ArrayList<>();
        Map<String, Long> pendingPublishedEpochSeconds = new LinkedHashMap<>();
        for (String version : requestedVersions) {
            String matchVersion = normalizedContainerBackedVersion(format, version);
            boolean existsNow = realPublishedTimes.containsKey(matchVersion)
                    || overridesByVersion.containsKey(matchVersion);
            if (!existsNow) {
                failures.put(version, new PackageVersionError("NOT_FOUND", "Could not find version " + version));
                continue;
            }
            PackageVersionStatusOverride override = overridesByVersion.get(matchVersion);
            String currentStatus = override != null ? override.status() : "Published";
            String currentRevision = override != null ? override.revision()
                    : synthesizeContainerBackedRevision(format, repoId, namespace, packageName, version);
            PackageVersionError failure = checkStatusTransitionAllowed(currentStatus, currentRevision, version,
                    revisions, expectedStatus, targetStatus);
            if (failure != null) {
                failures.put(version, failure);
                continue;
            }
            Long publishedEpochSeconds;
            if (override != null) {
                publishedEpochSeconds = override.publishedEpochSeconds();
            } else {
                Instant publishedTime = realPublishedTimes.get(matchVersion);
                publishedEpochSeconds = publishedTime == null ? null : publishedTime.getEpochSecond();
            }
            pendingPublishedEpochSeconds.put(version, publishedEpochSeconds);
            pending.add(version);
        }
        if (!failures.isEmpty()) {
            for (String version : pending) {
                failures.put(version, new PackageVersionError("SKIPPED", "Version " + version + " is skipped"));
            }
            return new PackageVersionStatusChangeResult(Map.of(), failures);
        }

        Map<String, SuccessfulPackageVersionInfo> successes = new LinkedHashMap<>();
        for (String version : pending) {
            List<String> assetNames = List.of();
            if (disposing) {
                String matchVersion = normalizedContainerBackedVersion(format, version);
                PackageVersionStatusOverride existing = overridesByVersion.get(matchVersion);
                if (existing != null && "Disposed".equals(existing.status())) {
                    // A retry of a previously partially-failed dispose (see
                    // PackageVersionStatusOverride's own javadoc for why the sidecar's own
                    // listing can no longer answer this for npm specifically): reuse exactly
                    // what is still known to need cleanup rather than asking the sidecar again.
                    assetNames = existing.pendingAssetNames();
                } else {
                    try {
                        assetNames = manager.listPackageVersionAssetNames(repoId, domain, repository, namespace,
                                packageName, version);
                    } catch (UnsupportedOperationException e) {
                        throw new AwsException("InternalServerException", "Disposing a package version of this "
                                + "format is not yet supported by this emulator.", 500);
                    }
                }
            }
            PackageVersionStatusOverride updated = new PackageVersionStatusOverride(targetStatus, newRevision(),
                    pendingPublishedEpochSeconds.get(version), assetNames);
            // Written before deleting below, not after: a sidecar failing partway through this
            // version's own asset deletions (the one real way this loop can throw) would otherwise
            // leave it marked Published with some assets already gone. There is no cheap way to
            // roll a partial sidecar delete back (unlike copyContainerBackedPackageVersions's own
            // write path, which can restore a destination's original bytes because it never
            // deletes more than it is about to replace), so this can't promise every earlier
            // version in the same batch rolls back too, only that this exact version never reads
            // as more intact than it now really is.
            writeContainerBackedStatusOverride(owner, region, domain, repository, format, namespace, packageName,
                    version, updated);
            if (disposing) {
                deleteContainerBackedVersionAssets(manager, repoId, owner, region, domain, repository, format,
                        namespace, packageName, version, assetNames);
            }
            successes.put(version, new SuccessfulPackageVersionInfo(updated.revision(), updated.status()));
        }
        return new PackageVersionStatusChangeResult(successes, Map.of());
    }

    /**
     * {@link #disposePackageVersions}'s real deletion for a container-backed version: every asset
     * name {@link RepositorySidecarManager#listPackageVersionAssetNames} still reports (the version
     * has not been disposed yet at the point this runs, so these are genuinely all still there) is
     * fetched (to measure its real size) and then deleted one at a time through {@link
     * RepositorySidecarManager#deletePackageVersionAsset}, the same primitive {@code
     * DeletePackageVersionAsset} itself already uses for one asset at a time. The bytes freed are
     * subtracted from {@link #containerBackedAssetBytes}'s own package-level total, floored at 0:
     * without this, {@code DescribeDomain}'s {@code assetSizeBytes} would keep counting a disposed
     * version's bytes forever, the same way {@link #recordContainerBackedAssetBytes} already
     * accounts for every container-backed publish adding to that same total. This runs inside
     * {@link #disposePackageVersions}'s own {@code synchronized} call, unlike that method's own
     * fence-checked accounting, so no separate race guard is needed here. {@link
     * RepositorySidecarManager#recordVersionDisposed} runs last, once every asset is confirmed
     * gone, for a sidecar (Maven) whose own native listing can otherwise keep naming a version
     * whose assets no longer exist.
     *
     * <p>Raised by Greptile's review: every asset's size used to be measured immediately before
     * deleting that exact asset, interleaved one at a time. {@code PypiserverSidecarClient}'s own
     * {@code deletePackageVersionAsset} removes every file sharing the same raw package name and
     * version in one call (confirmed live, the same quirk {@link #copyContainerBackedPackageVersions}'s
     * own write path already works around), so deleting a release's first file could silently
     * delete a sibling too; that sibling's own turn in this same loop then measured 0 bytes for
     * content already gone, undercounting what {@link #containerBackedAssetBytes} subtracts below.
     * Every asset is now measured in its own pass, before any of them are deleted, exactly like
     * that same write path's own two-phase ordering.
     *
     * <p>Raised again by Greptile's review, once a dispose retry (see {@link
     * #checkStatusTransitionAllowed}'s own javadoc) became possible: subtracting the whole
     * batch's bytes only after every delete (and the metadata update after that) succeeded meant a
     * failure partway through lost the freed bytes of every asset already genuinely deleted before
     * it, since a retry's own fresh {@link RepositorySidecarManager#listPackageVersionAssetNames}
     * call never lists them again to measure a second time. Each asset's own measured size is now
     * subtracted right after that exact asset's own delete call returns, so a later failure (in a
     * sibling's delete, or in {@link RepositorySidecarManager#recordVersionDisposed} itself) never
     * loses bytes this method already knows for certain are gone.
     *
     * <p>Raised a third time by Greptile's review: {@code assetNames} is now the caller's own
     * (the one real way to still know, for npm specifically, see {@code
     * PackageVersionStatusOverride}'s own javadoc) rather than this method re-listing it fresh,
     * and the override's {@code pendingAssetNames} is updated right after each asset's own
     * delete succeeds, not just {@link #containerBackedAssetBytes}: a later asset's delete
     * failing must leave that field naming only what is genuinely still real, the same per-item
     * bookkeeping {@link #applyGenericStatusChange} already does for its own in-memory
     * {@code assets} map.
     */
    private void deleteContainerBackedVersionAssets(RepositorySidecarManager manager, String repoId, String owner,
            String region, String domain, String repository, String format, String namespace, String packageName,
            String version, List<String> assetNames) {
        Map<String, Long> sizeByAssetName = new LinkedHashMap<>();
        for (String assetName : assetNames) {
            sizeByAssetName.put(assetName, manager.fetchPackageVersionAsset(repoId, domain, repository, namespace,
                    packageName, version, assetName).map(bytes -> (long) bytes.length).orElse(0L));
        }
        String key = ownerScopedKey(owner, packageKey(region, domain, repository, format, namespace,
                accountingPackageName(format, packageName)));
        List<String> remaining = new ArrayList<>(assetNames);
        for (String assetName : assetNames) {
            manager.deletePackageVersionAsset(repoId, domain, repository, namespace, packageName, version,
                    assetName);
            remaining.remove(assetName);
            recordPendingAssetNames(owner, region, domain, repository, format, namespace, packageName, version,
                    List.copyOf(remaining));
            long freedBytes = sizeByAssetName.get(assetName);
            if (freedBytes > 0) {
                containerBackedAssetBytes.computeIfPresent(key, (k, existing) -> Math.max(0, existing - freedBytes));
            }
        }
        manager.recordVersionDisposed(repoId, namespace, packageName, version);
    }

    /**
     * {@link #deleteContainerBackedVersionAssets}'s own per-asset update of {@code
     * pendingAssetNames} alone, leaving the override's {@code status}/{@code revision}/{@code
     * publishedEpochSeconds} exactly as they already are. A no-op if no override exists for this
     * version at all, which should never happen for either of this method's own callers (both
     * write one, with {@code disposing} true, immediately before calling it).
     */
    private void recordPendingAssetNames(String owner, String region, String domain, String repository,
            String format, String namespace, String packageName, String version, List<String> remaining) {
        String key = packageVersionKey(region, domain, repository, format, namespace,
                accountingPackageName(format, packageName), normalizedContainerBackedVersion(format, version));
        PackageVersionStatusOverride current =
                containerBackedPackageVersionStatusOverrides.get(ownerScopedKey(owner, key));
        if (current == null) {
            return;
        }
        writeContainerBackedStatusOverride(owner, region, domain, repository, format, namespace, packageName,
                version, new PackageVersionStatusOverride(current.status(), current.revision(),
                        current.publishedEpochSeconds(), remaining));
    }

    /**
     * {@code GetPackageVersionAsset} for a version with a status of {@code Archived} or {@code
     * Disposed} must 404 even though, for {@code Archived}, the bytes are still physically
     * present: confirmed against the User Guide's own worked example of an upstream fetch hitting
     * a package version with one of these two statuses in the upstream repository ("the response
     * will be 404 (Not Found) because the assets of package versions in those two statuses are
     * not downloadable"), which states the restriction as a property of the status itself, not
     * something specific to the upstream path the example happens to illustrate it with. {@code
     * Unlisted} is deliberately not checked here: the User Guide draws it out explicitly as the
     * one status that still allows a normal download ("if the Unlisted package version is already
     * referenced in an npm package-lock.json file, it can still be downloaded and installed").
     *
     * <p><b>Known gap, not fixed here (flagged by Qodo review on the PR that added {@code
     * DisposePackageVersions}):</b> this only guards {@code GetPackageVersionAsset} itself. The
     * native per-format data planes ({@code CodeArtifactMavenController}'s GAV path, {@code
     * CodeArtifactNpmDataPlane}'s tarball route, {@code CodeArtifactPypiController}'s raw
     * filename-only download route, {@code CodeArtifactNuGetController}'s content route) proxy
     * straight to each sidecar and never consult this override at all, for any purpose, so a
     * plain {@code pip install}/{@code npm install}/{@code mvn} fetch against an Archived version
     * can still download it. Fixing this means threading status awareness into four independently
     * shaped native protocols (pypi's own download route does not even carry a version in its
     * URL, only a filename, unlike the other three), a feature-family-sized gap closer to Package
     * Groups than a quick patch, tracked in the private design memo, not silently dropped.
     */
    private static void requireAssetDownloadable(String status, String version) {
        if ("Archived".equals(status) || "Disposed".equals(status)) {
            throw notFound("Package version '" + version + "' was not found.", version, "package-version");
        }
    }

    /**
     * A container-backed version "exists" if either the sidecar itself still has it, or Floci
     * holds a status override for it: once {@code DisposePackageVersions} deletes a version's
     * real sidecar content, {@link RepositorySidecarManager#packageVersionExists} alone would
     * start reporting it gone, even though AWS's own documented behavior is that a disposed
     * version keeps answering {@code DescribePackageVersion} (just with a {@code Disposed}
     * status and no assets) rather than ever reverting to a plain 404 as if it had never existed.
     *
     * <p>The override lookup normalizes through {@link #accountingPackageName} and {@link
     * #normalizedContainerBackedVersion}, the sidecar call does not: this mirrors how {@link
     * #recordPublishedPackageVersionReadme} already keys its own per-version, container-backed-only
     * bookkeeping, so a pypi/nuget package (or NuGet version) addressed under two case-,
     * punctuation-, or numeric-equivalent spellings (which the real sidecar itself treats as the
     * same package/version) still finds the one override either spelling wrote, while the sidecar
     * call itself is left to each client's own normalization against the real container.
     */
    private boolean containerBackedVersionExists(RepositorySidecarManager manager, String owner, String region,
            String domain, String repository, String format, String namespace, String packageName, String version,
            String repoId) {
        String key = packageVersionKey(region, domain, repository, format, namespace,
                accountingPackageName(format, packageName), normalizedContainerBackedVersion(format, version));
        return containerBackedPackageVersionStatusOverrides.containsKey(ownerScopedKey(owner, key))
                || manager.packageVersionExists(repoId, domain, repository, namespace, packageName, version);
    }

    /**
     * A container-backed version's current status and revision: whatever {@link
     * #updatePackageVersionsStatus}/{@link #disposePackageVersions} last recorded for it, or, for
     * a version neither has ever touched, the implicit {@code Published} status every
     * container-backed version starts in (nothing here ever lands one in {@code Unfinished} the
     * way a generic publish deliberately can, since a real {@code mvn deploy}/{@code npm
     * publish}/{@code twine upload} only ever lands a version by completing it in one shot) with
     * its usual coordinate-derived {@link #synthesizeContainerBackedRevision}. Callers that also
     * need to know whether the version exists at all check that separately with {@link
     * #containerBackedVersionExists} first; this always returns a value either way.
     */
    private PackageVersionStatusOverride currentContainerBackedVersionStatus(String owner, String region,
            String domain, String repository, String format, String namespace, String packageName, String version,
            String repoId) {
        String key = packageVersionKey(region, domain, repository, format, namespace,
                accountingPackageName(format, packageName), normalizedContainerBackedVersion(format, version));
        PackageVersionStatusOverride override =
                containerBackedPackageVersionStatusOverrides.get(ownerScopedKey(owner, key));
        if (override != null) {
            return override;
        }
        return new PackageVersionStatusOverride("Published",
                synthesizeContainerBackedRevision(format, repoId, namespace, packageName, version), null, List.of());
    }

    /**
     * Writes one version's status override, keeping {@link #containerBackedPackageVersionStatusOverrides}
     * and its {@link #containerBackedOverriddenVersionsByPackage} side index in sync: every writer
     * goes through this rather than touching either map directly, so the two can never drift apart.
     */
    private void writeContainerBackedStatusOverride(String owner, String region, String domain, String repository,
            String format, String namespace, String packageName, String version,
            PackageVersionStatusOverride override) {
        String accountingName = accountingPackageName(format, packageName);
        String normalizedVersion = normalizedContainerBackedVersion(format, version);
        String versionKey = ownerScopedKey(owner,
                packageVersionKey(region, domain, repository, format, namespace, accountingName,
                        normalizedVersion));
        containerBackedPackageVersionStatusOverrides.put(versionKey, override);
        String packageKey = ownerScopedKey(owner, packageKey(region, domain, repository, format, namespace,
                accountingName));
        containerBackedOverriddenVersionsByPackage.computeIfAbsent(packageKey, k -> ConcurrentHashMap.newKeySet())
                .add(normalizedVersion);
        if (namespace == null) {
            String repoFormatKey = ownerScopedKey(owner, repositoryFormatKey(region, domain, repository, format));
            containerBackedOverriddenPackagesByRepository.computeIfAbsent(repoFormatKey,
                    k -> ConcurrentHashMap.newKeySet()).add(accountingName);
        }
    }

    /**
     * Removes one version's status override, the counterpart to {@link
     * #writeContainerBackedStatusOverride}: {@link #copyContainerBackedPackageVersions} calls this
     * after successfully overwriting a destination version's real content, and {@link
     * #forgetContainerBackedStatusOverride} exposes it to every native publish path, so a status
     * an earlier {@link #updatePackageVersionsStatus}/{@link #disposePackageVersions} call
     * recorded for whatever used to be at that coordinate doesn't keep describing (and, through
     * {@link #requireAssetDownloadable}, blocking downloads of) the content that just replaced it.
     * A version with no override to begin with is a no-op, matching every other removal here.
     */
    private void clearContainerBackedStatusOverride(String owner, String region, String domain, String repository,
            String format, String namespace, String packageName, String version) {
        String accountingName = accountingPackageName(format, packageName);
        String normalizedVersion = normalizedContainerBackedVersion(format, version);
        String versionKey = ownerScopedKey(owner,
                packageVersionKey(region, domain, repository, format, namespace, accountingName,
                        normalizedVersion));
        containerBackedPackageVersionStatusOverrides.remove(versionKey);
        String packageKey = ownerScopedKey(owner, packageKey(region, domain, repository, format, namespace,
                accountingName));
        Set<String> versions = containerBackedOverriddenVersionsByPackage.get(packageKey);
        if (versions != null) {
            versions.remove(normalizedVersion);
        }
    }

    /**
     * Found by Qodo review, as a remaining half of the same finding its own copy-side fix (see
     * {@link #clearContainerBackedStatusOverride}'s javadoc) only partly closed: {@code
     * copyContainerBackedPackageVersions} clears a stale override on its own overwrite path, but
     * nothing cleared one for a <em>native</em> publish, a real {@code mvn deploy}/{@code npm
     * publish}/twine upload/{@code dotnet nuget push} that successfully republishes a coordinate
     * an earlier {@link #updatePackageVersionsStatus}/{@link #disposePackageVersions} call left
     * Archived or Disposed. Each native proxy controller (Maven, npm, pypi, nuget) calls this
     * right alongside its own existing {@link #recordContainerBackedAssetBytes} call, for the
     * exact same "a real write just happened" signal, whenever it has a specific version to name;
     * {@code CodeArtifactMavenController}'s own bare {@code maven-metadata.xml} PUT has none
     * (see its own GAV-parsing javadoc) and skips this entirely, since that path never represents
     * one specific version's content changing.
     */
    public synchronized void forgetContainerBackedStatusOverride(String region, String domain, String domainOwner,
            String repository, String format, String namespace, String packageName, String version) {
        String owner = effectiveOwner(domainOwner);
        clearContainerBackedStatusOverride(owner, region, domain, repository, format, namespace, packageName,
                version);
    }

    /**
     * Raised by hectorvent's review of this same PR: {@link #forgetContainerBackedStatusOverride}
     * only ever ran <em>after</em> each native proxy controller's real sidecar write already
     * succeeded, which cleared an Archived/Disposed override once Reposilite/Verdaccio/pypiserver/
     * BaGetter had already accepted (and written) the republished content, exactly the outcome
     * {@link #publishPackageVersion}'s own Archived/Disposed block exists to prevent for the
     * generic format. The User Guide draws no byte-identical exception for either status the way
     * it does for a plain idempotent retry of a {@code Published}/{@code Unlisted} version (see
     * {@link #publishPackageVersion}'s own javadoc), so this is unconditional, independent of
     * whether the incoming content happens to match what is already there.
     *
     * <p>Each native proxy controller calls this first, before issuing its own real write, so a
     * blocked coordinate is refused outright rather than accepted and then silently un-blocked;
     * {@code CodeArtifactMavenController}'s bare {@code maven-metadata.xml} PUT has no specific
     * version to check against (see its own GAV-parsing javadoc) and skips this entirely, the same
     * case {@link #forgetContainerBackedStatusOverride} already skips it for.
     *
     * <p>Returns the override's own revision if one exists at this exact moment, or {@code null}
     * if none does; a caller whose own real write later succeeds passes whichever came back here
     * straight into {@link #forgetContainerBackedStatusOverrideIfUnchanged}, not this class's own
     * unconditional {@link #forgetContainerBackedStatusOverride} (see that method's own javadoc for
     * why the distinction matters).
     */
    public synchronized String requireContainerBackedVersionNotArchivedOrDisposed(String region, String domain,
            String domainOwner, String repository, String format, String namespace, String packageName,
            String version) {
        String owner = effectiveOwner(domainOwner);
        String key = packageVersionKey(region, domain, repository, format, namespace,
                accountingPackageName(format, packageName), normalizedContainerBackedVersion(format, version));
        PackageVersionStatusOverride override =
                containerBackedPackageVersionStatusOverrides.get(ownerScopedKey(owner, key));
        if (override != null && ("Archived".equals(override.status()) || "Disposed".equals(override.status()))) {
            throw conflict("Package version '" + version + "' of package '" + packageName + "' is "
                    + override.status() + " and cannot be republished.", version, "package-version");
        }
        return override == null ? null : override.revision();
    }

    /**
     * Raised by Greptile's review of this same PR: a native publish's own check, real sidecar
     * write, and override clear are three separate calls (necessarily, since the real write sits
     * in between and this class's own lock cannot be held across it), so a concurrent {@link
     * #updatePackageVersionsStatus}/{@link #disposePackageVersions} call that lands strictly
     * between the check and the clear writes a brand new override this publish never saw, only for
     * the unconditional {@link #forgetContainerBackedStatusOverride} to erase it afterward,
     * silently undoing a status change its own caller had already been told succeeded.
     *
     * <p>{@code expectedRevisionOrNull} is whatever {@link
     * #requireContainerBackedVersionNotArchivedOrDisposed} returned when the caller's own guard
     * check ran, right before its real write: a revision changes on every single status write
     * ({@link #applyContainerBackedStatusChange} always calls {@link #newRevision} for it, even
     * when the target status repeats one already in effect), so comparing the override's current
     * revision against that snapshot, inside this one {@code synchronized} call, with no other
     * synchronized method able to interleave in between the comparison and the clear, tells
     * apart "nothing touched it since my guard ran" (safe to clear the stale pre-existing entry,
     * restoring the version to its real freshly-written content) from "something else just wrote a
     * newer one" (leave it alone, whatever it now says is more current than what this publish knew
     * about). Lands as a no-op either way once nothing is left to compare: a caller with
     * {@code expectedRevisionOrNull == null} whose own version still has no override at all
     * finds nothing changed and nothing to clear.
     *
     * <p>Raised by Greptile's review: leaving a newer override alone is correct for the override
     * itself, but a concurrent {@link #disposePackageVersions} landing in that same gap already
     * deleted this version's real sidecar content before the caller's own write restored it; the
     * override staying {@code Disposed} is no longer enough on its own to keep that promise, since
     * a native per-format download route (the documented gap in {@code requireAssetDownloadable}'s
     * own javadoc) consults real sidecar content directly, never this override. When the override
     * changed to specifically {@code Disposed} (not just any newer value), this now also re-runs
     * {@link #deleteContainerBackedVersionAssets} for the exact same version, undoing what the
     * caller's own write just restored and leaving the version exactly as disposal's own caller
     * was already told it would be: real, byte-accounted content included.
     */
    public synchronized void forgetContainerBackedStatusOverrideIfUnchanged(String region, String domain,
            String domainOwner, String repository, String format, String namespace, String packageName,
            String version, String expectedRevisionOrNull) {
        String owner = effectiveOwner(domainOwner);
        String key = packageVersionKey(region, domain, repository, format, namespace,
                accountingPackageName(format, packageName), normalizedContainerBackedVersion(format, version));
        PackageVersionStatusOverride current =
                containerBackedPackageVersionStatusOverrides.get(ownerScopedKey(owner, key));
        String currentRevision = current == null ? null : current.revision();
        if (Objects.equals(currentRevision, expectedRevisionOrNull)) {
            clearContainerBackedStatusOverride(owner, region, domain, repository, format, namespace, packageName,
                    version);
            return;
        }
        if (current != null && "Disposed".equals(current.status())) {
            String repoId = ensureFormatContainerId(format, region, domain, owner, repository);
            RepositorySidecarManager manager = sidecarRegistry.forFormat(format)
                    .orElseThrow(() -> notFound("Package '" + packageName + "' was not found.", packageName,
                            "package"));
            // Fresh, since the content to undo here is whatever the caller's own write just
            // restored, which the disposal that wrote this override could never itself have
            // known to list.
            List<String> freshAssetNames;
            try {
                freshAssetNames = manager.listPackageVersionAssetNames(repoId, domain, repository, namespace,
                        packageName, version);
            } catch (UnsupportedOperationException e) {
                throw new AwsException("InternalServerException", "Disposing a package version of this format "
                        + "is not yet supported by this emulator.", 500);
            }
            // Raised by Greptile's review: this fresh listing is not reliable on its own either.
            // A disposal running concurrently with this exact publish can itself be mid-cleanup
            // right now: for npm specifically, its own packument-removal half (see
            // VerdaccioSidecarClient#deletePackageVersionAsset's own javadoc) can already have
            // landed while its tarball-delete half is still failing, which makes this same fresh
            // listing go blind to a tarball that disposal's own pendingAssetNames still correctly
            // names as real. Overwriting pendingAssetNames with only this fresh list would lose
            // that name outright; the union of both is what a cleanup here, or whatever
            // DisposePackageVersions retry comes after it, actually needs to finish.
            Set<String> assetNamesToClean = new LinkedHashSet<>(current.pendingAssetNames());
            assetNamesToClean.addAll(freshAssetNames);
            List<String> combinedAssetNames = List.copyOf(assetNamesToClean);
            recordPendingAssetNames(owner, region, domain, repository, format, namespace, packageName, version,
                    combinedAssetNames);
            deleteContainerBackedVersionAssets(manager, repoId, owner, region, domain, repository, format,
                    namespace, packageName, version, combinedAssetNames);
        }
    }

    /**
     * Every version of one package that has a status override, by normalized version string (see
     * {@link #normalizedContainerBackedVersion}): the set
     * {@link #containerBackedOverriddenVersionsByPackage} tracks names the versions, and each is
     * then looked up individually in {@link #containerBackedPackageVersionStatusOverrides} itself.
     * Empty for a package nothing has ever called {@link #updatePackageVersionsStatus} or {@link
     * #disposePackageVersions} on, which is every container-backed package before this existed.
     */
    private Map<String, PackageVersionStatusOverride> containerBackedOverridesForPackage(String owner, String region,
            String domain, String repository, String format, String namespace, String packageName) {
        String accountingName = accountingPackageName(format, packageName);
        Set<String> versions = containerBackedOverriddenVersionsByPackage.get(ownerScopedKey(owner,
                packageKey(region, domain, repository, format, namespace, accountingName)));
        if (versions == null || versions.isEmpty()) {
            return Map.of();
        }
        Map<String, PackageVersionStatusOverride> result = new LinkedHashMap<>();
        for (String version : versions) {
            String versionKey = ownerScopedKey(owner,
                    packageVersionKey(region, domain, repository, format, namespace, accountingName, version));
            PackageVersionStatusOverride override = containerBackedPackageVersionStatusOverrides.get(versionKey);
            if (override != null) {
                result.put(version, override);
            }
        }
        return result;
    }

    /**
     * Every package in this one repository+format that currently carries a status override on any
     * version at all, each by its own version-to-status map, for a caller with no reliable
     * package-version coordinate of its own to look an override up by directly. Raised by
     * Greptile's follow-up review of this PR: a real twine upload's {@code version} form field is
     * optional, and its {@code name} field is no more trustworthy without one, since pypiserver
     * indexes a publish by the uploaded filename itself, never the form ({@code
     * PypiserverSidecarClient#filenameEmbedsVersion}'s own javadoc); a wrong {@code name} field
     * would otherwise have {@code CodeArtifactPypiController#upload} search the wrong package's
     * overrides entirely and never find a real Archived/Disposed one the filename actually names.
     * Only ever answers for a namespace-less format ({@link
     * #containerBackedOverriddenPackagesByRepository}'s own javadoc explains why): the caller
     * matches {@code filename} against whichever of these (package, version) pairs it actually
     * names, trusting this store's own recorded package name over whatever the caller's own
     * request happened to claim. Empty for the overwhelming common case, a repository no one has
     * ever called {@code UpdatePackageVersionsStatus}/{@code DisposePackageVersions} against for
     * this format.
     */
    public synchronized Map<String, Map<String, String>> containerBackedOverriddenVersionStatusesByRepository(
            String region, String domain, String domainOwner, String repository, String format) {
        String owner = effectiveOwner(domainOwner);
        Set<String> packageNames = containerBackedOverriddenPackagesByRepository.get(
                ownerScopedKey(owner, repositoryFormatKey(region, domain, repository, format)));
        if (packageNames == null || packageNames.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (String packageName : packageNames) {
            Map<String, PackageVersionStatusOverride> overrides = containerBackedOverridesForPackage(owner, region,
                    domain, repository, format, null, packageName);
            if (!overrides.isEmpty()) {
                Map<String, String> statuses = new LinkedHashMap<>();
                overrides.forEach((version, override) -> statuses.put(version, override.status()));
                result.put(packageName, statuses);
            }
        }
        return result;
    }

    /**
     * Unlike {@code GetPackageVersionAsset}, real CodeArtifact's {@code DescribePackageVersion}
     * response has no asset listing at all (confirmed against the wire model: {@code
     * PackageVersionDescription} has no such member; enumerating a version's files is {@link
     * #listPackageVersionAssets}'s job, a separate action). All this needs
     * from a sidecar (beyond the status override {@link #currentContainerBackedVersionStatus}
     * already resolves) is a yes/no answer to "does this exact version exist," which is cheap for
     * every one of the three: Verdaccio's packument already lists every version it holds,
     * Reposilite's own details API answers directory existence the same way {@link
     * #ensureFormatContainerId}'s readiness probe already relies on, and pypiserver's per-project
     * JSON listing already groups files by version the same way {@code deletePackage} already
     * reads it.
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
        if (!containerBackedVersionExists(manager, owner, region, domain, repository, format, namespace, packageName,
                version, repoId)) {
            throw notFound("Package version '" + version + "' of package '" + packageName
                    + "' was not found.", version, "package-version");
        }
        PackageVersionStatusOverride state = currentContainerBackedVersionStatus(owner, region, domain, repository,
                format, namespace, packageName, version, repoId);
        CodeArtifactPackageVersion pv = new CodeArtifactPackageVersion();
        pv.setDomainName(domain);
        pv.setDomainOwner(owner);
        pv.setRegion(region);
        pv.setRepositoryName(repository);
        pv.setFormat(format);
        pv.setNamespace(namespace);
        pv.setPackageName(packageName);
        pv.setVersion(version);
        pv.setStatus(state.status());
        pv.setRevision(state.revision());
        return pv;
    }

    /**
     * Every package a repository holds, for {@code ListPackages}: every format unless
     * {@code requestedFormat} narrows it to one, in which case only that one format's own source
     * is ever touched, container-backed sidecar included: without this, an unfiltered-by-format
     * caller's own lazily-provisioned sidecar containers would stay the real cost of answering
     * "all formats", but a caller who already named one format would otherwise still pay to start
     * every other format's container for nothing, just to filter its packages back out a moment
     * later.
     *
     * <p>{@code generic} is read straight from the stored {@link CodeArtifactPackageVersion}
     * records' own {@code format}/{@code namespace}/{@code packageName} fields (the same technique
     * {@link #genericPackageVersions} already uses for one known package, here scanned
     * repository-wide instead of by one package's own prefix), plus any origin-config-only
     * package's own matching fields on {@link PackageOriginConfig}, never by parsing a scanned key
     * back into parts, which {@link #escapeKeySegment}'s own javadoc explains is lossy. Every
     * container-backed format's own new {@link RepositorySidecarManager#listPackages} answers the
     * same for real sidecar content, plus the same origin-config-only union. {@code domainOwner}
     * is threaded through (rather than just the already-resolved {@code owner}) only because
     * {@link #resolveContainerIdAndBeginUse} re-resolves it itself, the same double-resolution
     * {@link #listPackageVersions} already does at its own call site.
     *
     * <p>Raised by Greptile's review: every coordinate here, from every source, is now normalized
     * through {@link #accountingPackageName} before it is ever added to the result set or used as
     * an origin-config lookup key. Without it, a pypi/nuget package whose {@link
     * PackageOriginConfig} was set under its as-typed spelling (case, or pypi's {@code -}/{@code _}/
     * {@code .} variants) would union into a second, spelling-distinct entry alongside the same
     * package's real, sidecar-normalized one, and the config lookup keyed by the normalized
     * spelling would never find a config stored under the raw one, silently falling back to the
     * ALLOW/BLOCK defaults instead of what was actually configured. Resolving every origin config
     * once here, into a map keyed by this same normalized coordinate, is what lets {@link
     * #listPackages} read it correctly regardless of which spelling the config was originally set
     * under.
     */
    private PackagesWithOriginConfigs allPackagesInRepository(String region, String domain, String domainOwner,
            String owner, String repository, String requestedFormat) {
        Set<PackageCoordinate> coordinates = new LinkedHashSet<>();
        Map<PackageCoordinate, PackageOriginConfig> originConfigsByCoordinate = new LinkedHashMap<>();
        String originPrefix = region + "::" + domain + "::" + repository + "::";
        if (requestedFormat == null || "generic".equals(requestedFormat)) {
            for (CodeArtifactPackageVersion pv : packageVersions.scanForAccount(owner,
                    k -> k.startsWith(originPrefix))) {
                coordinates.add(new PackageCoordinate(pv.getFormat(), pv.getNamespace(),
                        accountingPackageName(pv.getFormat(), pv.getPackageName())));
            }
        }
        for (PackageOriginConfig config : packageOriginConfigs.scanForAccount(owner,
                k -> k.startsWith(originPrefix))) {
            if (config.getFormat() != null
                    && (requestedFormat == null || requestedFormat.equals(config.getFormat()))) {
                PackageCoordinate coordinate = new PackageCoordinate(config.getFormat(), config.getNamespace(),
                        accountingPackageName(config.getFormat(), config.getPackageName()));
                coordinates.add(coordinate);
                originConfigsByCoordinate.put(coordinate, config);
            }
        }
        for (String format : CONTAINER_BACKED_FORMATS) {
            if (requestedFormat != null && !requestedFormat.equals(format)) {
                continue;
            }
            RepositorySidecarManager manager = sidecarRegistry.forFormat(format).orElse(null);
            if (manager == null) {
                continue;
            }
            String repoId = resolveContainerIdAndBeginUse(format, region, domain, domainOwner, repository, manager);
            try {
                for (RepositorySidecarManager.PackageIdentifier id : manager.listPackages(repoId, domain,
                        repository)) {
                    coordinates.add(new PackageCoordinate(format, id.namespace(),
                            accountingPackageName(format, id.packageName())));
                }
            } catch (UnsupportedOperationException e) {
                throw new AwsException("InternalServerException", "Listing " + format + " packages is not yet "
                        + "supported by this emulator.", 500);
            } finally {
                manager.endUse(repoId);
            }
            // Raised by Greptile's review: disposing a container-backed package's last version
            // deletes its real sidecar content, so the sidecar's own listing above no longer names
            // it at all, but updatePackageVersionsStatus/disposePackageVersions leave a status
            // override behind specifically so ListPackageVersions/DescribePackageVersion can keep
            // answering Disposed/Archived for it. Without this, a package with no origin
            // configuration either (nothing else to keep it in the union) would silently disappear
            // from ListPackages the moment its last version was disposed. Only ever adds something
            // for npm/pypi/nuget (see containerBackedOverriddenVersionStatusesByRepository's own
            // javadoc for why its index is namespace-less only); the same gap for maven or a scoped
            // npm package, both of which always carry a namespace, is not closed here.
            for (String packageName : containerBackedOverriddenVersionStatusesByRepository(region, domain,
                    domainOwner, repository, format).keySet()) {
                coordinates.add(new PackageCoordinate(format, null, packageName));
            }
        }
        return new PackagesWithOriginConfigs(new ArrayList<>(coordinates), originConfigsByCoordinate);
    }

    /**
     * Sorts the same way {@link #packageVersionSortCursor} does for its own action, as an opaque
     * cursor: {@code ListPackages} has no {@code sortBy} parameter at all in the wire model (unlike
     * {@code ListPackageVersions}' {@code PUBLISHED_TIME}), so alphabetical by format, then
     * namespace, then package name is the best-justified deterministic order available, not an
     * AWS-confirmed one.
     *
     * <p>Raised by Greptile's review: joining the three fields with a plain {@code ::} the way an
     * earlier version of this method did collides for two different coordinates whose namespace
     * and package name happen to straddle that same separator differently, e.g. namespace
     * {@code a::b} with package {@code c} against namespace {@code a} with package {@code b::c};
     * {@code Pagination}'s own resume logic (strictly-greater-cursor) would then skip whichever of
     * the two sorts second. Each field is now escaped through {@link #escapeKeySegment}, the same
     * helper this service's own storage keys already use for exactly this collision, before being
     * joined.
     */
    private static String packageSortCursor(PackageSummaryEntry entry) {
        return escapeKeySegment(entry.format()) + "::" + escapeKeySegment(entry.namespace()) + "::"
                + escapeKeySegment(entry.packageName());
    }

    /**
     * Lists every package a repository holds, across every format at once unless {@code format}
     * filters to one. {@code namespace} is a prefix filter exactly like {@code packagePrefix} (its
     * own doc: "although this option is called --namespace and not --namespace-prefix, it has
     * prefix-matching behavior"), and real AWS rejects it when {@code format} is omitted ("Using
     * --namespace by itself results in an error," the User Guide's own documented search-option
     * combination rule): {@code format} combined with a namespace-less format (pypi, nuget) is
     * still accepted, since nothing in that same rule singles it out, it just can never match
     * anything, the same way a {@code packagePrefix} nothing matches returns empty rather than an
     * error.
     *
     * <p>{@code publish}/{@code upstream} filter against each package's own resolved restriction
     * (its {@link PackageOriginConfig} if one was ever set, else CodeArtifact's documented default
     * of publish {@code ALLOW}, upstream {@code BLOCK}), the same resolution {@link
     * #describePackage} already does for one known package.
     *
     * <p>Default page size is 100 (the User Guide's own stated default), not the 50 {@code
     * ListPackageVersions} uses: a real, AWS-documented difference between the two actions, not
     * an oversight copying one convention over the other.
     */
    public ListPackagesResult listPackages(String region, String domain, String domainOwner, String repository,
            String format, String namespace, String packagePrefix, Integer maxResults, String nextToken,
            String publish, String upstream) {
        requireNonBlank(domain, "domain");
        requireNonBlank(repository, "repository");
        if (format != null && !PACKAGE_FORMATS.contains(format)) {
            throw validation("format must be one of " + PACKAGE_FORMATS + ".");
        }
        if (namespace != null) {
            if (format == null) {
                throw validation("format must be specified when namespace is provided.");
            }
            validatePackageToken("namespace", namespace);
        }
        if (packagePrefix != null) {
            validatePackageToken("packagePrefix", packagePrefix);
        }
        if (publish != null && !ORIGIN_RESTRICTION_VALUES.contains(publish)) {
            throw validation("publish must be one of " + ORIGIN_RESTRICTION_VALUES + ".");
        }
        if (upstream != null && !ORIGIN_RESTRICTION_VALUES.contains(upstream)) {
            throw validation("upstream must be one of " + ORIGIN_RESTRICTION_VALUES + ".");
        }
        String owner = effectiveOwner(domainOwner);
        requireRepository(owner, repositoryKey(region, domain, repository), repository);

        List<PackageSummaryEntry> entries = new ArrayList<>();
        PackagesWithOriginConfigs packages = allPackagesInRepository(region, domain, domainOwner, owner, repository,
                format);
        for (PackageCoordinate coordinate : packages.coordinates()) {
            if (namespace != null
                    && (coordinate.namespace() == null || !coordinate.namespace().startsWith(namespace))) {
                continue;
            }
            if (packagePrefix != null && !coordinate.packageName().startsWith(packagePrefix)) {
                continue;
            }
            // Resolved from the same normalized map allPackagesInRepository already built, rather
            // than a fresh key lookup here: see that method's own javadoc for why a direct lookup
            // keyed by this (already normalized) coordinate could otherwise miss a config stored
            // under its caller's original, un-normalized spelling.
            Optional<PackageOriginConfig> config = Optional.ofNullable(
                    packages.originConfigsByCoordinate().get(coordinate));
            // Raised by Greptile's review: a PackageOriginConfig persisted before format/namespace/
            // packageName existed on it (see that model's own javadoc) has none of the three, so
            // allPackagesInRepository's own scan can never resolve it into this map at all, even
            // though the package it configures is still found here through its real version record
            // or sidecar listing. Falling back to the original key-based lookup, exactly like
            // describePackage's own, is what keeps that package's real restriction (not the ALLOW/
            // BLOCK default) resolving for it too.
            if (config.isEmpty()) {
                config = packageOriginConfigs.getForAccount(owner, packageKey(region, domain, repository,
                        coordinate.format(), coordinate.namespace(), coordinate.packageName()));
            }
            String publishRestriction = config.map(PackageOriginConfig::getPublishRestriction).orElse("ALLOW");
            String upstreamRestriction = config.map(PackageOriginConfig::getUpstreamRestriction).orElse("BLOCK");
            if (publish != null && !publish.equals(publishRestriction)) {
                continue;
            }
            if (upstream != null && !upstream.equals(upstreamRestriction)) {
                continue;
            }
            entries.add(new PackageSummaryEntry(coordinate.format(), coordinate.namespace(),
                    coordinate.packageName(), publishRestriction, upstreamRestriction));
        }
        PaginatedResult<PackageSummaryEntry> page = Pagination.paginate(entries,
                CodeArtifactService::packageSortCursor, maxResults, nextToken, 100, 1000, "ValidationException");
        return new ListPackagesResult(page.items(), page.nextToken());
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
                upstreamRestriction, format, namespace, packageName));
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
            // Same prefix, same reasoning, for updatePackageVersionsStatus/disposePackageVersions'
            // own per-version overrides; the side index is removed outright rather than
            // prefix-scanned since its own key is exactly ownerScopedKey(owner, accountingKey).
            containerBackedPackageVersionStatusOverrides.keySet().stream()
                    .filter(k -> k.startsWith(ownerScopedReadmePrefix))
                    .toList()
                    .forEach(containerBackedPackageVersionStatusOverrides::remove);
            containerBackedOverriddenVersionsByPackage.remove(ownerScopedKey(owner, accountingKey));
            if (namespace == null) {
                Set<String> packageNames = containerBackedOverriddenPackagesByRepository.get(
                        ownerScopedKey(owner, repositoryFormatKey(region, domain, repository, format)));
                if (packageNames != null) {
                    packageNames.remove(accountingPackageName(format, packageName));
                }
            }
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
        requireAssetDownloadable(pv.getStatus(), version);
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
        RepositorySidecarManager manager = sidecarRegistry.forFormat(format)
                .orElseThrow(() -> notFound("Package version '" + version + "' of package '" + packageName
                        + "' was not found.", version, "package-version"));
        // No separate existence check needed here the way describeContainerBackedPackageVersion
        // needs one: fetchPackageVersionAsset's own empty result already 404s a version that never
        // existed at all (see below), and requireAssetDownloadable below already blocks a version
        // this emulator knows is Archived/Disposed purely from the override map, with no sidecar
        // round trip required to tell the two cases apart.
        PackageVersionStatusOverride state = currentContainerBackedVersionStatus(owner, region, domain, repository,
                format, namespace, packageName, version, repoId);
        String revision = state.revision();
        if (packageVersionRevision != null && !packageVersionRevision.equals(revision)) {
            throw notFound("Package version '" + version + "' was not found at revision '"
                    + packageVersionRevision + "'.", version, "package-version");
        }
        requireAssetDownloadable(state.status(), version);
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
     *
     * <p>Truncated to {@link #MAX_REVISION_LENGTH} (50) hex characters of the full SHA-256 digest,
     * not the full 64: {@code CopyPackageVersions}' own {@code versionRevisions} value is
     * wire-model-capped at 50 characters, so a revision this helper hands out through {@code
     * DescribePackageVersion}/{@code GetPackageVersionAsset} must itself fit back into that field
     * or a caller could never round-trip it through a revision-pinned copy. 50 hex characters
     * (200 bits) is still astronomically collision-resistant for distinguishing the handful of
     * live repository/namespace/package/version coordinates this emulator ever holds at once.
     *
     * <p>{@code nuget} normalizes {@code version} first ({@link NuGetVersions#normalize}, the
     * same normalization {@link BagetterSidecarClient}'s own version matching already applies
     * before comparing a requested version against what BaGetter holds): without it, two
     * equivalent spellings of the same NuGet version (AWS's own worked example, {@code 1.00} and
     * {@code 1.0.0}) resolved to the identical stored version but hashed to two different
     * revisions, so a revision obtained with one spelling never matched a later request using the
     * other. Maven, npm, and pypi need no such normalization: only BaGetter's client normalizes
     * the requested version before matching it; Reposilite and Verdaccio match the literal string.
     */
    private static String synthesizeContainerBackedRevision(String format, String repoId, String namespace,
            String packageName, String version) {
        String revisionVersion = "nuget".equals(format) ? NuGetVersions.normalize(version) : version;
        String revisionKey = repoId + "/" + (namespace == null ? "" : namespace) + "/" + packageName + "/"
                + revisionVersion;
        return Digests.sha256Hex(revisionKey.getBytes(StandardCharsets.UTF_8)).substring(0, MAX_REVISION_LENGTH);
    }

    /**
     * Copies one or more versions of a package from one repository to another in the same
     * domain, for every format CodeArtifact supports: {@code generic} through its own {@code
     * packageVersions} store, and {@code maven}/{@code npm}/{@code pypi}/{@code nuget} bridged to
     * their own sidecar (see {@link #copyContainerBackedPackageVersions}). Matches real AWS,
     * which does not restrict this action's {@code format} the way it restricts {@link
     * #publishPackageVersion}'s to {@code generic} alone (confirmed against the wire model:
     * {@code CopyPackageVersionsRequest.format}'s own doc carries no such restriction): every
     * container-backed format publishes straight to its own sidecar and keeps no {@code
     * packageVersions} record this could otherwise read from or write to, so each needed the
     * same per-sidecar fetch-then-republish shape {@code GetPackageVersionAsset}'s own bridge
     * already uses, built on each sidecar's own real wire protocol and confirmed live against a
     * real container for every one of the four: {@code ReposiliteSidecarClient} reuses the write
     * path {@link CodeArtifactMavenController}'s own {@code mvn deploy} passthrough already
     * proved; {@code VerdaccioSidecarClient}, {@code PypiserverSidecarClient}, and {@code
     * BagetterSidecarClient} each speak the real {@code npm publish}/{@code twine upload}/{@code
     * nuget push} wire shape directly, the same one their own data-plane proxies already forward
     * unmodified for a native client.
     *
     * <p>Confirmed against the CodeArtifact User Guide's own worked examples ("Copy package
     * versions"), not assumed from the shared {@code PackageVersionError} error-code enum alone,
     * since that enum is reused across several batch actions and not every code it lists applies
     * to this one:
     * <ul>
     * <li>Exactly one of {@code versions}/{@code versionRevisions} must be given.
     * <li>A requested version absent from the source repository fails {@code NOT_FOUND}.
     * <li>A {@code versionRevisions} entry whose expected revision doesn't match the source's
     * actual one fails {@code MISMATCHED_REVISION}.
     * <li>A version already present in the destination, byte- and metadata-identical to the
     * source, is treated as an already-successful no-op: not copied, and not listed in either
     * response map at all.
     * <li>A version already present in the destination with different content fails {@code
     * ALREADY_EXISTS} unless {@code allowOverwrite} is set, in which case the destination's
     * asset set is replaced outright with the source's.
     * <li>The whole call is all-or-nothing: one requested version failing any of the above
     * aborts every other version that would otherwise have copied cleanly, each reported {@code
     * SKIPPED} rather than performing a partial copy.
     * </ul>
     * {@code NOT_ALLOWED}, {@code MISMATCHED_STATUS}, and the top-level {@code ConflictException}
     * are all part of this action's documented error surface but have no documented trigger
     * specific to {@code CopyPackageVersions} (unlike, say, {@code UpdatePackageVersionsStatus}'s
     * own {@code expectedStatus} parameter, which {@code CopyPackageVersionsRequest} has no
     * equivalent of, or {@code PublishPackageVersion}'s origin-configuration check, which the User
     * Guide's own definition of the "Publish" origin control scopes to direct publish "using
     * package managers or similar tools", a mechanism {@code CopyPackageVersions} is not), this
     * never produces any of the three, rather than guessing at a condition AWS never actually
     * documents for this action.
     *
     * <p>{@code includeFromUpstream} is accepted for wire compatibility but has no effect: Floci
     * has no upstream-ingestion path for any format (see {@code docs/services/codeartifact.md}'s
     * Known limitations), so there is never a separate upstream repository to also search.
     *
     * <p>For {@code generic}, a freshly copied version gets a new revision of its own ({@link
     * #newRevision()}, never the source's): the User Guide's own definition of "package version
     * revision" is explicit that "each time a package version is updated, a new package version
     * revision is created," and landing a version in a repository it previously had no record in
     * is such an update at the destination, the same reason {@link #publishPackageVersion}
     * always mints its own rather than ever accepting a caller-supplied one. An identical-content
     * no-op (see above) is correctly the opposite case: nothing was updated at the destination, so
     * its existing revision, whatever it already was, is left untouched. {@code maven}'s
     * destination revision is instead {@link #synthesizeContainerBackedRevision}'s own stable,
     * coordinate-derived value, the same one {@link #describeContainerBackedPackageVersion} and
     * {@link #getContainerBackedPackageVersionAsset} already return for it: there is no
     * "revision changes on update" concept for a container-backed format at all, an existing,
     * documented simplification this does not invent a new exception to.
     *
     * <p>{@code synchronized} for the same reason every other package-mutating method here is: a
     * concurrent publish or delete on either repository must never interleave with this method's
     * own read-then-write of the destination's {@code packageVersions} record, or, for {@code
     * maven}, its sidecar's own backing storage.
     */
    public synchronized CopyPackageVersionsResult copyPackageVersions(String region, String domain,
            String domainOwner, String sourceRepository, String destinationRepository, String format,
            String namespace, String packageName, List<String> versions, Map<String, String> versionRevisions,
            boolean allowOverwrite, boolean includeFromUpstream) {
        if (!"generic".equals(format) && !CONTAINER_BACKED_FORMATS.contains(format)) {
            throw validation("format must be 'generic', 'maven', 'npm', 'pypi', or 'nuget'.");
        }
        boolean hasVersions = versions != null && !versions.isEmpty();
        boolean hasRevisions = versionRevisions != null && !versionRevisions.isEmpty();
        if (hasVersions == hasRevisions) {
            throw validation("Exactly one of versions or versionRevisions must be specified.");
        }
        // AWS's own wire model caps the versions array itself (and, by the same limit applied to
        // the alternate way of naming the same set, versionRevisions) at 100 members; checked
        // against the raw caller-supplied count, before any deduplication below, so a request
        // that only looks small after collapsing repeats still gets rejected.
        int rawRequestedCount = hasVersions ? versions.size() : versionRevisions.size();
        if (rawRequestedCount > MAX_PACKAGE_VERSIONS_PER_BATCH_CALL) {
            throw validation("A single CopyPackageVersions call can copy at most " + MAX_PACKAGE_VERSIONS_PER_BATCH_CALL
                    + " versions.");
        }
        // A duplicate requested version (whether from a caller-supplied repeat in versions, or
        // simply because a Set was never guaranteed here) must not be processed twice: besides
        // the wasted work, double-copying would also waste the per-call quota check below.
        List<String> requestedVersions = List.copyOf(
                new LinkedHashSet<>(hasVersions ? versions : versionRevisions.keySet()));
        validatePackageCoordinates(format, namespace, packageName, "copying");
        for (String requestedVersion : requestedVersions) {
            validatePackageToken("packageVersion", requestedVersion);
            // AWS's own wire model: versionRevisions' map value has "Length Constraints: Minimum
            // length of 1. Maximum length of 50" and "Value Pattern: \S+". A value outside that
            // shape is a malformed request, not a per-version MISMATCHED_REVISION outcome the
            // batch should otherwise report.
            if (hasRevisions) {
                String expectedRevision = versionRevisions.get(requestedVersion);
                if (expectedRevision == null || expectedRevision.length() > MAX_REVISION_LENGTH
                        || !VERSION_REVISION_VALUE.matcher(expectedRevision).matches()) {
                    throw validation("versionRevisions['" + requestedVersion + "'] must be 1-" + MAX_REVISION_LENGTH
                            + " characters with no whitespace.");
                }
            }
        }
        requireNonBlank(domain, "domain");
        String owner = effectiveOwner(domainOwner);
        requireRepository(owner, repositoryKey(region, domain, sourceRepository), sourceRepository);
        requireRepository(owner, repositoryKey(region, domain, destinationRepository), destinationRepository);
        // Deliberately does NOT check the destination package's origin configuration (see
        // putPackageOriginConfiguration): the User Guide's own definition of the "Publish" origin
        // control scopes it exactly to "publish directly to the repository using package managers
        // or similar tools," and "Upstream" to ingestion from an external connection or a
        // configured upstream repository. CopyPackageVersions is a distinct, explicit API action
        // reached through neither mechanism, and nothing in the User Guide's "Editing package
        // origin controls" chapter or the API reference ties the two together. ConflictException
        // is part of this action's documented error list (so it's a real possible response), but
        // this codebase has no confirmed trigger for it on this action and does not guess one.

        if (CONTAINER_BACKED_FORMATS.contains(format)) {
            return copyContainerBackedPackageVersions(region, domain, owner, sourceRepository,
                    destinationRepository, format, namespace, packageName, requestedVersions, versionRevisions,
                    hasRevisions, allowOverwrite);
        }

        Map<String, PackageVersionError> failures = new LinkedHashMap<>();
        List<String> pendingCopies = new ArrayList<>();
        Map<String, CodeArtifactPackageVersion> sourceByVersion = new LinkedHashMap<>();
        for (String version : requestedVersions) {
            String sourceKey = packageVersionKey(region, domain, sourceRepository, format, namespace, packageName,
                    version);
            CodeArtifactPackageVersion sourcePv = packageVersions.getForAccount(owner, sourceKey).orElse(null);
            if (sourcePv == null) {
                failures.put(version, new PackageVersionError("NOT_FOUND", "Could not find version "
                        + version));
                continue;
            }
            // Raised by Greptile's review: disposePackageVersions clears a generic version's
            // assets but keeps its record around (so DescribePackageVersion keeps answering, the
            // same reason the Disposed-destination check below exists), so a disposed source still
            // passes the null check above with an empty asset map. Copying it, with
            // allowOverwrite=true against a healthy destination, would otherwise overwrite that
            // destination's real content and status with the disposed source's empty one while
            // reporting success. Checked before the revision/content comparisons below, the same
            // order checkStatusTransitionAllowed already checks Disposed in.
            if ("Disposed".equals(sourcePv.getStatus())) {
                failures.put(version, new PackageVersionError("NOT_ALLOWED", "Package version " + version
                        + " has already been disposed at the source and cannot be copied"));
                continue;
            }
            if (hasRevisions) {
                String expectedRevision = versionRevisions.get(version);
                if (!sourcePv.getRevision().equals(expectedRevision)) {
                    failures.put(version, new PackageVersionError("MISMATCHED_REVISION",
                            "current revision: " + sourcePv.getRevision() + ", expected revision: "
                                    + expectedRevision));
                    continue;
                }
            }
            String destKey = packageVersionKey(region, domain, destinationRepository, format, namespace,
                    packageName, version);
            CodeArtifactPackageVersion destPv = packageVersions.getForAccount(owner, destKey).orElse(null);
            if (destPv != null) {
                // Raised by hectorvent's review, matching copyContainerBackedPackageVersions's own
                // Disposed-destination guard: applyGenericStatusChange clears a disposed version's
                // assets but keeps its record around (so DescribePackageVersion keeps answering),
                // which means packageVersionContentMatches below can never see a match against an
                // emptied asset map, and without this check the plain-overwrite branch would
                // resurrect a destination the User Guide says "can no longer be used."
                if ("Disposed".equals(destPv.getStatus())) {
                    failures.put(version, new PackageVersionError("NOT_ALLOWED", "Package version " + version
                            + " has already been disposed at the destination and cannot be republished"));
                    continue;
                }
                if (packageVersionContentMatches(sourcePv, destPv)) {
                    // Identical in both repositories already: AWS's own documented behavior is to
                    // treat this as an already-successful no-op, not copied and not listed in
                    // either response map at all, so this requested version is simply dropped
                    // here rather than added to either failures or pendingCopies.
                    continue;
                }
                if (!allowOverwrite) {
                    failures.put(version, new PackageVersionError("ALREADY_EXISTS",
                            "Version " + version + " already exists"));
                    continue;
                }
            }
            sourceByVersion.put(version, sourcePv);
            pendingCopies.add(version);
        }

        if (!failures.isEmpty()) {
            for (String version : pendingCopies) {
                failures.put(version, new PackageVersionError("SKIPPED", "Version " + version
                        + " was skipped"));
            }
            return new CopyPackageVersionsResult(Map.of(), failures);
        }

        // Every pending version's source assets are read and verified against their persisted
        // hashes before any version in this same batch has anything written to the destination
        // or committed: a missing or corrupted source asset for version 3 of 5 must never leave
        // versions 1 and 2 committed while the batch as a whole fails, and must never be
        // discovered only after this method already started mutating the destination.
        Map<String, Map<String, byte[]>> verifiedContentByVersion = new LinkedHashMap<>();
        for (String version : pendingCopies) {
            verifiedContentByVersion.put(version, verifyAndReadSourceAssets(owner, sourceByVersion.get(version)));
        }

        Map<String, SuccessfulPackageVersionInfo> successes = new LinkedHashMap<>();
        for (String version : pendingCopies) {
            CodeArtifactPackageVersion copied = copyGenericPackageVersionAssets(owner, region, domain,
                    destinationRepository, format, namespace, packageName, version, sourceByVersion.get(version),
                    verifiedContentByVersion.get(version));
            successes.put(version, new SuccessfulPackageVersionInfo(copied.getRevision(), copied.getStatus()));
        }
        return new CopyPackageVersionsResult(successes, Map.of());
    }

    /**
     * {@link #copyPackageVersions}'s bridge for a container-backed format: the same NOT_FOUND /
     * MISMATCHED_REVISION / identical-content-no-op / ALREADY_EXISTS / all-or-nothing-SKIPPED
     * semantics as the {@code generic} path, built on the sidecar primitives {@link
     * RepositorySidecarManager#listPackageVersionAssetNames}, {@link
     * RepositorySidecarManager#fetchPackageVersionAsset}, {@link
     * RepositorySidecarManager#storePackageVersionAsset}, and {@link
     * RepositorySidecarManager#deletePackageVersionAsset} instead of the generic path's own {@code
     * packageVersions} store. {@code requestedVersions} and {@code hasRevisions} are the caller's
     * own already-validated, already-deduplicated values, not recomputed here.
     *
     * <p>Every container-backed format's {@link RepositorySidecarManager} overrides all four of
     * these today. A future format whose sidecar manager does not would have its default throw
     * {@link UnsupportedOperationException} from the first one reached, caught here and turned
     * into a real, if deliberately blunt, {@code InternalServerException}: the same convention
     * {@link #deleteContainerBackedPackageIfPresent} already uses for a sidecar capability not
     * yet built, rather than a silent no-op or an unhandled 500.
     *
     * <p>There is no persisted hash to re-verify a container-backed asset's bytes against the way
     * {@link #verifyAndReadSourceAssets} does for generic, so this only confirms an asset {@link
     * RepositorySidecarManager#listPackageVersionAssetNames} just reported is still fetchable
     * immediately afterward, failing the whole batch with {@code InternalServerException} if not
     * (the same storage-integrity failure {@link #verifyAndReadSourceAssets} reports, just without
     * a hash to compare).
     *
     * <p>{@code hasRevisions} can succeed here the same as for {@code generic}: {@link
     * #synthesizeContainerBackedRevision} is truncated to {@link #MAX_REVISION_LENGTH} (50)
     * characters specifically so a revision a caller obtained from {@code
     * DescribePackageVersion}/{@code GetPackageVersionAsset} always fits back into {@code
     * versionRevisions}' own 1-50 character value constraint and compares equal when it is in
     * fact the current one.
     */
    private CopyPackageVersionsResult copyContainerBackedPackageVersions(String region, String domain, String owner,
            String sourceRepository, String destinationRepository, String format, String namespace,
            String packageName, List<String> requestedVersions, Map<String, String> versionRevisions,
            boolean hasRevisions, boolean allowOverwrite) {
        RepositorySidecarManager manager = sidecarRegistry.forFormat(format)
                .orElseThrow(() -> new AwsException("InternalServerException", "Copying a " + format
                        + " package version is not yet supported by this emulator.", 500));
        String sourceRepoId = ensureFormatContainerId(format, region, domain, owner, sourceRepository);
        String destRepoId = ensureFormatContainerId(format, region, domain, owner, destinationRepository);

        Map<String, PackageVersionError> failures = new LinkedHashMap<>();
        List<String> pendingCopies = new ArrayList<>();
        Map<String, List<String>> sourceAssetNamesByVersion = new LinkedHashMap<>();
        Map<String, List<String>> destAssetNamesByVersion = new LinkedHashMap<>();
        try {
            for (String version : requestedVersions) {
                if (!manager.packageVersionExists(sourceRepoId, domain, sourceRepository, namespace, packageName,
                        version)) {
                    failures.put(version, new PackageVersionError("NOT_FOUND", "Could not find version "
                            + version));
                    continue;
                }
                // Raised by Greptile's review: a Maven dispose failing partway through its own
                // asset-deletion loop (see deleteContainerBackedVersionAssets's own javadoc)
                // leaves the override reading Disposed while real files genuinely still remain
                // in the version's own directory, so packageVersionExists above alone cannot
                // tell a healthy source apart from a partly-disposed one. Checked against the
                // override directly, the same way the generic path's own source-side guard
                // already does, before this version's real files are ever listed or copied.
                PackageVersionStatusOverride sourceOverride = currentContainerBackedVersionStatus(owner, region,
                        domain, sourceRepository, format, namespace, packageName, version, sourceRepoId);
                if ("Disposed".equals(sourceOverride.status())) {
                    failures.put(version, new PackageVersionError("NOT_ALLOWED", "Package version " + version
                            + " has already been disposed at the source and cannot be copied"));
                    continue;
                }
                if (hasRevisions) {
                    String expectedRevision = versionRevisions.get(version);
                    String actualRevision = sourceOverride.revision();
                    if (!actualRevision.equals(expectedRevision)) {
                        failures.put(version, new PackageVersionError("MISMATCHED_REVISION",
                                "current revision: " + actualRevision + ", expected revision: "
                                        + expectedRevision));
                        continue;
                    }
                }
                // Checked regardless of destExists below: disposing a version deletes its real
                // sidecar content, so a disposed destination coordinate almost always has
                // destExists == false by the time anything copies into it again, even though the
                // User Guide is explicit that Disposed "can no longer be used" and can never move
                // to Published (the status this copy's own response would otherwise claim for it).
                PackageVersionStatusOverride destOverride = currentContainerBackedVersionStatus(owner, region,
                        domain, destinationRepository, format, namespace, packageName, version, destRepoId);
                if ("Disposed".equals(destOverride.status())) {
                    failures.put(version, new PackageVersionError("NOT_ALLOWED", "Package version " + version
                            + " has already been disposed at the destination and cannot be republished"));
                    continue;
                }
                List<String> sourceAssetNames = manager.listPackageVersionAssetNames(sourceRepoId, domain,
                        sourceRepository, namespace, packageName, version);
                boolean destExists = manager.packageVersionExists(destRepoId, domain, destinationRepository,
                        namespace, packageName, version);
                if (destExists) {
                    List<String> destAssetNames = manager.listPackageVersionAssetNames(destRepoId, domain,
                            destinationRepository, namespace, packageName, version);
                    if (containerBackedVersionContentMatches(manager, sourceRepoId, sourceRepository, destRepoId,
                            destinationRepository, domain, namespace, packageName, version, sourceAssetNames,
                            destAssetNames)) {
                        // Identical in both repositories already: the same already-successful,
                        // not-listed-anywhere no-op the generic path applies (see
                        // #copyPackageVersions's own javadoc).
                        continue;
                    }
                    if (!allowOverwrite) {
                        failures.put(version, new PackageVersionError("ALREADY_EXISTS",
                                "Version " + version + " already exists"));
                        continue;
                    }
                    destAssetNamesByVersion.put(version, destAssetNames);
                }
                sourceAssetNamesByVersion.put(version, sourceAssetNames);
                pendingCopies.add(version);
            }
        } catch (UnsupportedOperationException e) {
            throw new AwsException("InternalServerException", "Copying a " + format + " package version is not "
                    + "yet supported by this emulator.", 500);
        }

        if (!failures.isEmpty()) {
            for (String version : pendingCopies) {
                failures.put(version, new PackageVersionError("SKIPPED", "Version " + version
                        + " was skipped"));
            }
            return new CopyPackageVersionsResult(Map.of(), failures);
        }

        // Same verify-before-write ordering as the generic path's own two-pass split: every
        // pending version's source assets are fetched and confirmed present before any version in
        // this batch has anything written to the destination.
        Map<String, Map<String, byte[]>> verifiedContentByVersion = new LinkedHashMap<>();
        for (String version : pendingCopies) {
            Map<String, byte[]> contentByAsset = new LinkedHashMap<>();
            for (String assetName : sourceAssetNamesByVersion.get(version)) {
                byte[] content = manager.fetchPackageVersionAsset(sourceRepoId, domain, sourceRepository, namespace,
                                packageName, version, assetName)
                        .orElseThrow(() -> new AwsException("InternalServerException", "Could not copy version '"
                                + version + "' of package '" + packageName + "': its asset '" + assetName
                                + "' disappeared from the source repository between listing and fetching it.",
                                500));
                contentByAsset.put(assetName, content);
            }
            verifiedContentByVersion.put(version, contentByAsset);
        }

        Map<String, SuccessfulPackageVersionInfo> successes = new LinkedHashMap<>();
        for (String version : pendingCopies) {
            List<String> destAssetNames = destAssetNamesByVersion.getOrDefault(version, List.of());
            Map<String, byte[]> originalContent = new LinkedHashMap<>();
            for (String destAssetName : destAssetNames) {
                manager.fetchPackageVersionAsset(destRepoId, domain, destinationRepository, namespace, packageName,
                                version, destAssetName)
                        .ifPresent(content -> originalContent.put(destAssetName, content));
            }
            List<String> touchedAssetNames = new ArrayList<>();
            // Snapshotted immediately before this version's own writes, the same way every other
            // container-backed write path already takes its fence snapshot right before issuing
            // the real write it will later report: recording this version's bytes must still
            // reflect the latest live state even if an earlier version in this same batch call
            // already changed it (there is no shared snapshot spanning the whole batch to go
            // stale as later versions are processed).
            long packageFenceSnapshot = currentContainerBackedPackageFence(region, domain, owner,
                    destinationRepository, format, namespace, packageName);
            long bytesWritten = 0L;
            try {
                // Every asset this version already held at the destination is deleted first, in
                // its own phase, before anything new is written: pypiserver's own delete call
                // (confirmed live) removes every file sharing that exact package name and
                // version, not just the one asset name passed to it, so deleting a same-named
                // asset in the middle of a per-asset write loop would silently wipe a sibling
                // asset (e.g. the wheel of a release also carrying an sdist) already rewritten
                // earlier in that same loop. Clearing everything up front, before any of this
                // version's new content exists to be collaterally wiped, is correct for every
                // format: Reposilite/Verdaccio/BaGetter's own deletes are already scoped to
                // exactly the asset (or, for npm/nuget, the single asset their formats ever hold
                // per version) being removed, so doing them first changes nothing for them, and
                // still satisfies Reposilite's own redeployment:false rejection of a plain PUT
                // over differing existing content the same way the previous interleaved ordering
                // did.
                for (String destAssetName : destAssetNames) {
                    manager.deletePackageVersionAsset(destRepoId, domain, destinationRepository, namespace,
                            packageName, version, destAssetName);
                    touchedAssetNames.add(destAssetName);
                }
                for (Map.Entry<String, byte[]> entry : verifiedContentByVersion.get(version).entrySet()) {
                    String assetName = entry.getKey();
                    manager.copyPackageVersionAsset(sourceRepoId, domain, sourceRepository, destRepoId,
                            destinationRepository, namespace, packageName, version, assetName, entry.getValue());
                    if (!touchedAssetNames.contains(assetName)) {
                        touchedAssetNames.add(assetName);
                    }
                    bytesWritten += entry.getValue().length;
                }
                manager.recordVersionAvailable(destRepoId, domain, destinationRepository, namespace, packageName,
                        version);
            } catch (RuntimeException e) {
                rollbackContainerBackedVersionWrite(manager, destRepoId, domain, destinationRepository, namespace,
                        packageName, version, originalContent, touchedAssetNames);
                throw e;
            }
            if (bytesWritten > 0) {
                recordContainerBackedAssetBytes(region, domain, owner, destinationRepository, format, namespace,
                        packageName, bytesWritten, packageFenceSnapshot);
            }
            copyCapturedReadmeIfApplicable(region, domain, owner, sourceRepository, destinationRepository, format,
                    namespace, packageName, version);
            // Drops any status override left over from whatever used to be at this destination
            // coordinate (an Archived version this copy's allowOverwrite just replaced, most
            // concretely): the response below reports Published, and a stale override would
            // otherwise keep describing (and keep requireAssetDownloadable blocking downloads of)
            // content that no longer matches what is now really there. A destination that
            // never had an override is an inexpensive no-op (see that method's own javadoc).
            clearContainerBackedStatusOverride(owner, region, domain, destinationRepository, format, namespace,
                    packageName, version);
            String revision = synthesizeContainerBackedRevision(format, destRepoId, namespace, packageName, version);
            successes.put(version, new SuccessfulPackageVersionInfo(revision, "Published"));
        }
        return new CopyPackageVersionsResult(successes, Map.of());
    }

    /**
     * Propagates the source version's captured readme to the destination for {@code npm} and
     * {@code pypi}, the only two formats {@link #getPackageVersionReadme} answers from a record
     * captured at publish time rather than live from the sidecar (see that method's own javadoc
     * for why neither sidecar can answer for a specific version after the fact). Without this, a
     * copied npm or pypi version's assets would land correctly at the destination while {@code
     * GetPackageVersionReadme} 404s on it there, even though the source genuinely has one: this
     * bridge's own asset copy has no other way to reach that text, since it lives only in this
     * service's own {@code packageVersionReadmes} map, never in the sidecar content itself.
     *
     * <p>{@code nuget} needs no equivalent call: {@link #getPackageVersionReadme} already answers
     * it live against the sidecar, and the {@code .nupkg} this bridge already copied carries its
     * own embedded readme/nuspec description with it. {@code maven} has no readme concept in
     * Floci at all (absent from {@link #README_FORMATS}).
     *
     * <p>A missing source entry (no capture ever ran for that version, only possible for data
     * from before this call existed) is silently skipped, not treated as a failure: there is
     * nothing to propagate, the same way a version genuinely published with no readme field
     * already records an empty string rather than nothing (see {@link
     * #recordPublishedPackageVersionReadme}'s own callers). Any other failure here is logged and
     * swallowed rather than thrown: this is the same best-effort side channel {@link
     * #recordPublishedPackageVersionReadme}'s own javadoc already describes it as, so it must
     * never turn a copy that otherwise fully succeeded against the real sidecar into a thrown
     * exception over this alone.
     */
    private void copyCapturedReadmeIfApplicable(String region, String domain, String owner, String sourceRepository,
            String destinationRepository, String format, String namespace, String packageName, String version) {
        if (!"npm".equals(format) && !"pypi".equals(format)) {
            return;
        }
        try {
            String sourceKey = packageVersionKey(region, domain, sourceRepository, format, namespace,
                    accountingPackageName(format, packageName), version);
            String readme = packageVersionReadmes.get(ownerScopedKey(owner, sourceKey));
            if (readme == null) {
                return;
            }
            long readmeFenceSnapshot = currentContainerBackedVersionFence(region, domain, owner,
                    destinationRepository, format, namespace, packageName, version);
            recordPublishedPackageVersionReadme(region, domain, owner, destinationRepository, format, namespace,
                    packageName, version, readme, readmeFenceSnapshot);
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not copy {0}/{1} package ''{2}'' version ''{3}''''s readme to {4}; the copied "
                    + "version itself still succeeded.", domain, sourceRepository, packageName, version,
                    destinationRepository);
        }
    }

    /**
     * Best-effort compensating cleanup for one version's partially-applied destination writes
     * after {@link #copyContainerBackedPackageVersions} hits a failure partway through them:
     * every asset this call itself touched is restored to whatever it held before (if it
     * pre-existed, from {@code originalContent}) or deleted outright (if it was new). Does not
     * attempt to re-create a stale asset it had not yet gotten to deleting, or undo a successful
     * {@link RepositorySidecarManager#recordVersionAvailable} call, since neither leaves the
     * destination in a worse state than before this version's write began. A failure during the
     * rollback itself is logged and swallowed, never thrown over the original failure that
     * triggered it: a best-effort cleanup that cannot fully complete is still strictly no worse
     * than not attempting one, and must never hide the real error behind a cleanup error instead.
     */
    private void rollbackContainerBackedVersionWrite(RepositorySidecarManager manager, String destRepoId,
            String domain, String destinationRepository, String namespace, String packageName, String version,
            Map<String, byte[]> originalContent, List<String> touchedAssetNames) {
        for (String assetName : touchedAssetNames) {
            try {
                byte[] original = originalContent.get(assetName);
                if (original != null) {
                    manager.storePackageVersionAsset(destRepoId, domain, destinationRepository, namespace,
                            packageName, version, assetName, original);
                } else {
                    manager.deletePackageVersionAsset(destRepoId, domain, destinationRepository, namespace,
                            packageName, version, assetName);
                }
            } catch (RuntimeException rollbackFailure) {
                LOG.warnv(rollbackFailure, "Best-effort rollback could not restore {0}/{1} package ''{2}'' "
                        + "version ''{3}'' asset ''{4}'' after a failed copy; the destination may still be "
                        + "left in a partially-copied state for this version.", domain, destinationRepository,
                        packageName, version, assetName);
            }
        }
    }

    /**
     * {@code true} only when {@code source} and {@code dest} hold the exact same asset names for
     * this version, each fetching to byte-identical content: the container-backed equivalent of
     * {@link #packageVersionContentMatches}, which compares persisted hashes instead since generic
     * assets have one and these don't.
     */
    private static boolean containerBackedVersionContentMatches(RepositorySidecarManager manager,
            String sourceRepoId, String sourceRepository, String destRepoId, String destinationRepository,
            String domain, String namespace, String packageName, String version, List<String> sourceAssetNames,
            List<String> destAssetNames) {
        if (!new LinkedHashSet<>(sourceAssetNames).equals(new LinkedHashSet<>(destAssetNames))) {
            return false;
        }
        for (String assetName : sourceAssetNames) {
            byte[] sourceContent = manager.fetchPackageVersionAsset(sourceRepoId, domain, sourceRepository,
                    namespace, packageName, version, assetName).orElse(null);
            byte[] destContent = manager.fetchPackageVersionAsset(destRepoId, domain, destinationRepository,
                    namespace, packageName, version, assetName).orElse(null);
            if (!Arrays.equals(sourceContent, destContent)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reads every one of {@code source}'s assets and confirms each one's actual bytes still hash
     * to what its own persisted metadata claims, returning the verified bytes keyed by asset name.
     * Guards against two distinct ways {@link #readAssetContent} can otherwise hand back the
     * wrong bytes silently: a missing in-memory entry, which it answers with an empty array
     * rather than an error, and disk corruption independent of Floci, which it never checks at
     * all. Thrown as {@code InternalServerException} rather than folded into a per-version
     * {@code CopyPackageVersionsResult} failure: this is this emulator's own storage integrity
     * failing, not a caller-facing outcome any AWS-documented error code for this action
     * describes.
     */
    private Map<String, byte[]> verifyAndReadSourceAssets(String owner, CodeArtifactPackageVersion source) {
        String sourceKey = packageVersionKey(source.getRegion(), source.getDomainName(), source.getRepositoryName(),
                source.getFormat(), source.getNamespace(), source.getPackageName(), source.getVersion());
        Map<String, byte[]> verified = new LinkedHashMap<>();
        for (PackageAsset sourceAsset : source.getAssets().values()) {
            byte[] content = readAssetContent(owner, sourceKey, sourceAsset.getName());
            String actualSha256 = computeHashes(content).get("SHA-256");
            String expectedSha256 = sourceAsset.getHashes().get("SHA-256");
            if (expectedSha256 == null || !expectedSha256.equalsIgnoreCase(actualSha256)) {
                throw new AwsException("InternalServerException", "Could not copy version '" + source.getVersion()
                        + "' of package '" + source.getPackageName() + "': its asset '" + sourceAsset.getName()
                        + "' is missing from the source repository's storage or no longer matches its recorded "
                        + "hash.", 500);
            }
            verified.put(sourceAsset.getName(), content);
        }
        return verified;
    }

    /**
     * {@code true} only when {@code source} and {@code dest} would look identical to a client
     * describing either one: same status, and the exact same set of asset names each hashing to
     * the same SHA-256. Compared against the assets' own persisted hashes, the same way {@link
     * #contentMatchesExisting} does for a single asset, rather than re-reading every asset's
     * bytes from both repositories' asset stores.
     */
    private static boolean packageVersionContentMatches(CodeArtifactPackageVersion source,
                                                          CodeArtifactPackageVersion dest) {
        if (!source.getStatus().equals(dest.getStatus())) {
            return false;
        }
        Map<String, PackageAsset> sourceAssets = source.getAssets();
        Map<String, PackageAsset> destAssets = dest.getAssets();
        if (!sourceAssets.keySet().equals(destAssets.keySet())) {
            return false;
        }
        for (Map.Entry<String, PackageAsset> entry : sourceAssets.entrySet()) {
            String sourceSha256 = entry.getValue().getHashes().get("SHA-256");
            String destSha256 = destAssets.get(entry.getKey()).getHashes().get("SHA-256");
            if (sourceSha256 == null || !sourceSha256.equalsIgnoreCase(destSha256)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Actually performs one version's copy: every asset's bytes are read from the source
     * repository's asset store and written into the destination's under the destination's own
     * key, and the destination gets a package-version record of its own with a freshly minted
     * revision (see {@link #copyPackageVersions}'s own javadoc for why it is never the source's).
     *
     * <p>{@code verifiedContent} is every one of {@code source}'s assets, already confirmed by
     * {@link #verifyAndReadSourceAssets} to hash correctly, before this method or any other
     * version in the same batch call had anything written to a destination; this method only
     * ever writes bytes it did not itself have to read or validate.
     *
     * <p>The only way this is reached with an existing destination record already there is the
     * {@code allowOverwrite} path, and the destination ends up with exactly the source's asset
     * set afterward: any asset the old destination version had that the source version does not
     * is deleted, not left behind as an orphan the new record no longer references. That cleanup
     * runs last, after the new assets are written and the new record is committed, not first: a
     * failure partway through writing the new assets must leave the old record and the old assets
     * it still points to both intact and consistent, not a surviving old record pointing at bytes
     * this method already deleted out from under it.
     */
    private CodeArtifactPackageVersion copyGenericPackageVersionAssets(String owner, String region, String domain,
            String destinationRepository, String format, String namespace, String packageName, String version,
            CodeArtifactPackageVersion source, Map<String, byte[]> verifiedContent) {
        String destKey = packageVersionKey(region, domain, destinationRepository, format, namespace, packageName,
                version);
        CodeArtifactPackageVersion existingDest = packageVersions.getForAccount(owner, destKey).orElse(null);
        Map<String, PackageAsset> copiedAssets = new LinkedHashMap<>();
        for (PackageAsset sourceAsset : source.getAssets().values()) {
            writeAssetContent(owner, destKey, sourceAsset.getName(), verifiedContent.get(sourceAsset.getName()));
            PackageAsset copy = new PackageAsset();
            copy.setName(sourceAsset.getName());
            copy.setSize(sourceAsset.getSize());
            copy.setHashes(sourceAsset.getHashes());
            copiedAssets.put(sourceAsset.getName(), copy);
        }
        CodeArtifactPackageVersion dest = new CodeArtifactPackageVersion();
        dest.setDomainName(domain);
        dest.setDomainOwner(owner);
        dest.setRegion(region);
        dest.setRepositoryName(destinationRepository);
        dest.setFormat(format);
        dest.setNamespace(namespace);
        dest.setPackageName(packageName);
        dest.setVersion(version);
        dest.setStatus(source.getStatus());
        // publishedTime reflects when this version entered THIS repository, not the source
        // repository's own publish time; an Unfinished version carries no publishedTime yet,
        // matching publishPackageVersion's own rule for a version that hasn't been finalized.
        dest.setPublishedTime("Unfinished".equals(source.getStatus()) ? null : Instant.now().getEpochSecond());
        dest.setRevision(newRevision());
        dest.setAssets(copiedAssets);
        packageVersions.putForAccount(owner, destKey, dest);
        if (existingDest != null) {
            for (String staleAssetName : existingDest.getAssets().keySet()) {
                if (!source.getAssets().containsKey(staleAssetName)) {
                    deleteAssetContent(owner, destKey, staleAssetName);
                }
            }
        }
        return dest;
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
     * Verdaccio's own packument response does not echo it back, checked against a live Verdaccio
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
        String revision = currentContainerBackedVersionStatus(owner, region, domain, repository, format, namespace,
                packageName, version, repoId).revision();
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
     * <p>{@code fenceSnapshot} is whatever {@link #currentContainerBackedVersionFence} returned
     * when the proxy called it, right before issuing the real write this call reports the readme
     * of. Without comparing it here too, a publish's readme capture that runs on a delayed callback
     * (npm's own, off the event loop) after a concurrent {@link #deletePackage}, native unpublish,
     * or {@link #deletePackageVersions} call for this exact version has already removed it would
     * resurrect a readme {@link #getPackageVersionReadme} should now 404 on. Raised by Greptile's
     * review: this used to compare against {@link #currentContainerBackedPackageFence} alone, which
     * only ever changes on a whole-package removal, so a single version's own deletion never
     * invalidated a readme capture already in flight for it.
     */
    public synchronized void recordPublishedPackageVersionReadme(String region, String domain, String domainOwner,
            String repository, String format, String namespace, String packageName, String version,
            String readme, long fenceSnapshot) {
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
        String key = packageVersionKey(region, domain, repository, format, namespace, accountingName, version);
        long currentFence = Math.max(containerBackedPackageDeletionFence.getOrDefault(ownerScopedKey(owner,
                fenceKey), 0L), containerBackedVersionDeletionFence.getOrDefault(ownerScopedKey(owner, key), 0L));
        if (currentFence != fenceSnapshot) {
            LOG.warnv("Skipping readme capture for {0}/{1} package ''{2}'' version ''{3}'': a DeletePackage or "
                    + "DeletePackageVersions removed it from the {4} sidecar (possibly replaced by a different "
                    + "publish) while this publish was in flight.", domain, repository, packageName, version,
                    format);
            return;
        }
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
        containerBackedVersionDeletionFence.clear();
        packageVersionReadmes.clear();
        containerBackedPackageVersionStatusOverrides.clear();
        containerBackedOverriddenVersionsByPackage.clear();
        containerBackedOverriddenPackagesByRepository.clear();
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
     * Same purpose as {@link #currentContainerBackedPackageFence}, narrowed to one version: a
     * snapshot a format proxy takes right before issuing a real per-version write (an npm publish's
     * own readme, or pypi's twine upload description), for {@link
     * #recordPublishedPackageVersionReadme} to compare against once that write completes. Unlike
     * byte accounting, which only ever runs for a whole package at a time (see {@link
     * #recordContainerBackedAssetBytes}'s own javadoc) and so has no per-version snapshot of its
     * own, a captured readme is keyed per version, and {@link #deletePackageVersions} can remove
     * just one version's readme without touching its siblings'. Raised by Greptile's review:
     * {@link #deletePackageVersions} cleared a deleted version's own captured readme without
     * bumping any fence at all, so a publish callback already in flight for that exact version,
     * delayed past the delete (Verdaccio's own response arriving well after this emulator's side of
     * the delete already finished), would resurrect the readme it just removed. Returns whichever
     * of the package-wide fence or this version's own is higher, so a snapshot taken here still
     * catches a whole-package removal ({@link #deletePackage}, or a native {@code npm unpublish}
     * via {@link #forgetContainerBackedPackage}) exactly the way {@link
     * #currentContainerBackedPackageFence} already does on its own.
     */
    public long currentContainerBackedVersionFence(String region, String domain, String domainOwner,
            String repository, String format, String namespace, String packageName, String version) {
        String owner = effectiveOwner(domainOwner);
        String accountingName = accountingPackageName(format, packageName);
        String packageFenceKey = ownerScopedKey(owner, packageKey(region, domain, repository, format, namespace,
                accountingName));
        String versionFenceKey = ownerScopedKey(owner, packageVersionKey(region, domain, repository, format,
                namespace, accountingName, version));
        return Math.max(containerBackedPackageDeletionFence.getOrDefault(packageFenceKey, 0L),
                containerBackedVersionDeletionFence.getOrDefault(versionFenceKey, 0L));
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
        // Same key/prefix, same reasoning: a native unpublish removed this package's real sidecar
        // content directly, so any status override updatePackageVersionsStatus/
        // disposePackageVersions previously recorded for one of its versions would otherwise keep
        // describing a package that, as far as the sidecar is concerned, no longer exists at all.
        containerBackedPackageVersionStatusOverrides.keySet().stream()
                .filter(k -> k.startsWith(readmePrefix))
                .toList()
                .forEach(containerBackedPackageVersionStatusOverrides::remove);
        containerBackedOverriddenVersionsByPackage.remove(ownerScopedKey);
        if (namespace == null) {
            Set<String> packageNames = containerBackedOverriddenPackagesByRepository.get(
                    ownerScopedKey(owner, repositoryFormatKey(region, domain, repository, format)));
            if (packageNames != null) {
                packageNames.remove(accountingPackageName(format, packageName));
            }
        }
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

    /**
     * Raised by Greptile's review of this PR: the override map's own key building left {@code
     * version} exactly as the caller spelled it, while {@link BagetterSidecarClient}'s own version
     * matching (see {@link #synthesizeContainerBackedRevision}'s own javadoc) already treats two
     * equivalent NuGet spellings (AWS's own worked example, {@code 1.00} and {@code 1.0.0}) as the
     * same stored version. Without this, archiving one spelling left the other still resolving to
     * the implicit {@code Published} default everywhere an override is read, exactly like
     * {@link #accountingPackageName} already normalizes the package name half of the same key for
     * pypi/nuget; this is its version-string counterpart, applied only for {@code nuget} since
     * Maven/npm/pypi match the literal version string with no such aliasing to account for.
     */
    private static String normalizedContainerBackedVersion(String format, String version) {
        return "nuget".equals(format) ? NuGetVersions.normalize(version) : version;
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
        try {
            FileTrees.delete(assetRoot);
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

    private static String repositoryFormatKey(String region, String domain, String repository, String format) {
        return repositoryKey(region, domain, repository) + "::" + format;
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
