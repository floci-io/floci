package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    private static final long MAX_ASSET_FILE_SIZE_BYTES = 5L * 1024 * 1024 * 1024;
    private static final int MAX_ASSETS_PER_PACKAGE_VERSION = 350;
    private static final int MAX_DOMAINS_PER_ACCOUNT = 10;
    private static final int MAX_REPOSITORIES_PER_DOMAIN = 1000;
    private static final String ASSET_STORAGE_DIR = "codeartifact-assets";

    public record DomainView(CodeArtifactDomain domain, int repositoryCount) {}
    public record ResourcePolicy(String resourceArn, String revision, String document) {}
    public record PublishPackageVersionResult(CodeArtifactPackageVersion packageVersion, PackageAsset asset) {}
    public record PackageVersionAssetResult(PackageAsset asset, String packageVersionRevision) {}

    private final AccountAwareStorageBackend<CodeArtifactDomain> domains;
    private final AccountAwareStorageBackend<CodeArtifactRepository> repositories;
    private final AccountAwareStorageBackend<CodeArtifactPackageVersion> packageVersions;
    private final RegionResolver regionResolver;
    private final EmulatorConfig config;
    // Asset bytes never go through the JSON/WAL-backed `packageVersions` store (see PackageAsset's
    // @JsonIgnore content field): they're kept here instead, on disk in persistent/hybrid/wal mode
    // or in this map in memory mode, so a publish only ever rewrites metadata.
    private final boolean inMemory;
    private final Path assetRoot;
    private final ConcurrentHashMap<String, byte[]> memoryAssetStore = new ConcurrentHashMap<>();

    @Inject
    public CodeArtifactService(StorageFactory storageFactory, RegionResolver regionResolver, EmulatorConfig config,
                                ServiceConfigAccess serviceConfigAccess) {
        this(
                storageFactory.create("codeartifact", "codeartifact-domains.json",
                        new TypeReference<Map<String, CodeArtifactDomain>>() {}),
                storageFactory.create("codeartifact", "codeartifact-repositories.json",
                        new TypeReference<Map<String, CodeArtifactRepository>>() {}),
                storageFactory.create("codeartifact", "codeartifact-package-versions.json",
                        new TypeReference<Map<String, CodeArtifactPackageVersion>>() {}),
                regionResolver, config,
                "memory".equals(serviceConfigAccess.storageMode("codeartifact")),
                Path.of(config.storage().persistentPath()).resolve(ASSET_STORAGE_DIR));
    }

    /** Package-private constructor for testing. */
    CodeArtifactService(AccountAwareStorageBackend<CodeArtifactDomain> domains,
                         AccountAwareStorageBackend<CodeArtifactRepository> repositories,
                         AccountAwareStorageBackend<CodeArtifactPackageVersion> packageVersions,
                         RegionResolver regionResolver, EmulatorConfig config,
                         boolean inMemory, Path assetRoot) {
        this.domains = domains;
        this.repositories = repositories;
        this.packageVersions = packageVersions;
        this.regionResolver = regionResolver;
        this.config = config;
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
            throw conflict("Domain with name '" + domain + "' already exists.");
        }
        if (domainCountForAccount(owner, region) >= MAX_DOMAINS_PER_ACCOUNT) {
            throw new AwsException("ServiceQuotaExceededException",
                    "An AWS account can have a maximum of " + MAX_DOMAINS_PER_ACCOUNT + " domains.", 402,
                    Map.of("resourceId", domain, "resourceType", "domain"));
        }
        CodeArtifactDomain d = new CodeArtifactDomain();
        d.setName(domain);
        d.setOwner(owner);
        d.setRegion(region);
        d.setArn(regionResolver.buildArn("codeartifact", region, "domain/" + domain));
        d.setEncryptionKey(encryptionKey != null ? encryptionKey
                : regionResolver.buildArn("kms", region, "alias/aws/codeartifact"));
        d.setCreatedTime(Instant.now().getEpochSecond());
        d.setTags(validateTags(tags, Map.of()));
        domains.putForAccount(owner, key, d);
        return new DomainView(d, 0);
    }

    public synchronized DomainView deleteDomain(String region, String domain, String domainOwner) {
        String owner = effectiveOwner(domainOwner);
        String key = domainKey(region, domain);
        CodeArtifactDomain d = requireDomain(owner, key);
        int repoCount = repositoryCountForDomain(owner, region, domain);
        if (repoCount > 0) {
            throw conflict("Domain '" + domain + "' contains repositories and cannot be deleted "
                    + "until they are deleted.");
        }
        domains.deleteForAccount(owner, key);
        return new DomainView(d, 0);
    }

    public DomainView describeDomain(String region, String domain, String domainOwner) {
        String owner = effectiveOwner(domainOwner);
        CodeArtifactDomain d = requireDomain(owner, domainKey(region, domain));
        return new DomainView(d, repositoryCountForDomain(owner, region, domain));
    }

    public PaginatedResult<DomainView> listDomains(String region, Integer maxResults, String nextToken) {
        String owner = regionResolver.getAccountId();
        List<CodeArtifactDomain> matching = domains.scanForAccount(owner, k -> k.startsWith(region + "::"));
        PaginatedResult<CodeArtifactDomain> page = Pagination.paginate(matching, CodeArtifactDomain::getName,
                maxResults, nextToken, 100, 1000, "ValidationException");
        List<DomainView> views = page.items().stream()
                .map(d -> new DomainView(d, repositoryCountForDomain(owner, region, d.getName())))
                .toList();
        return new PaginatedResult<>(views, page.nextToken());
    }

    public synchronized ResourcePolicy putDomainPermissionsPolicy(String region, String domain, String domainOwner,
                                                                   String policyDocument, String policyRevision) {
        String owner = effectiveOwner(domainOwner);
        String key = domainKey(region, domain);
        CodeArtifactDomain d = requireDomain(owner, key);
        checkRevision(d.getPolicyRevision(), policyRevision);
        validatePolicyDocument(policyDocument);
        d.setPolicyDocument(policyDocument);
        d.setPolicyRevision(newRevision());
        domains.putForAccount(owner, key, d);
        return new ResourcePolicy(d.getArn(), d.getPolicyRevision(), d.getPolicyDocument());
    }

    public ResourcePolicy getDomainPermissionsPolicy(String region, String domain, String domainOwner) {
        String owner = effectiveOwner(domainOwner);
        CodeArtifactDomain d = requireDomain(owner, domainKey(region, domain));
        if (d.getPolicyDocument() == null) {
            throw notFound("No resource policy is associated with domain '" + domain + "'.");
        }
        return new ResourcePolicy(d.getArn(), d.getPolicyRevision(), d.getPolicyDocument());
    }

    public synchronized ResourcePolicy deleteDomainPermissionsPolicy(String region, String domain, String domainOwner,
                                                                      String policyRevision) {
        String owner = effectiveOwner(domainOwner);
        String key = domainKey(region, domain);
        CodeArtifactDomain d = requireDomain(owner, key);
        if (d.getPolicyDocument() == null) {
            throw notFound("No resource policy is associated with domain '" + domain + "'.");
        }
        checkRevision(d.getPolicyRevision(), policyRevision);
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
        requireDomain(owner, domainKey(region, domain));
        String key = repositoryKey(region, domain, repository);
        if (repositories.getForAccount(owner, key).isPresent()) {
            throw conflict("Repository with name '" + repository + "' already exists in domain '" + domain + "'.");
        }
        if (repositoryCountForDomain(owner, region, domain) >= MAX_REPOSITORIES_PER_DOMAIN) {
            throw new AwsException("ServiceQuotaExceededException",
                    "A domain can have a maximum of " + MAX_REPOSITORIES_PER_DOMAIN + " repositories.", 402,
                    Map.of("resourceId", repository, "resourceType", "repository"));
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
        r.setTags(validateTags(tags, Map.of()));
        repositories.putForAccount(owner, key, r);
        return r;
    }

    public synchronized CodeArtifactRepository deleteRepository(String region, String domain, String domainOwner,
                                                                  String repository) {
        String owner = effectiveOwner(domainOwner);
        String key = repositoryKey(region, domain, repository);
        CodeArtifactRepository r = requireRepository(owner, key);
        repositories.deleteForAccount(owner, key);
        return r;
    }

    public CodeArtifactRepository describeRepository(String region, String domain, String domainOwner,
                                                       String repository) {
        String owner = effectiveOwner(domainOwner);
        return requireRepository(owner, repositoryKey(region, domain, repository));
    }

    public synchronized CodeArtifactRepository updateRepository(String region, String domain, String domainOwner,
                                                                  String repository, String description,
                                                                  List<String> upstreams) {
        String owner = effectiveOwner(domainOwner);
        String key = repositoryKey(region, domain, repository);
        CodeArtifactRepository r = requireRepository(owner, key);
        if (upstreams != null) {
            if (!upstreams.isEmpty() && !r.getExternalConnections().isEmpty()) {
                throw conflict("Repository '" + repository + "' has an external connection; "
                        + "a repository cannot have both an external connection and upstream repositories.");
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
        requireDomain(owner, domainKey(region, domain));
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
        String owner = effectiveOwner(domainOwner);
        requireRepository(owner, repositoryKey(region, domain, repository));
        return config.effectiveBaseUrl() + "/codeartifact/" + format + "/" + domain + "/" + repository + "/";
    }

    public synchronized ResourcePolicy putRepositoryPermissionsPolicy(String region, String domain,
                                                                       String domainOwner, String repository,
                                                                       String policyDocument, String policyRevision) {
        String owner = effectiveOwner(domainOwner);
        String key = repositoryKey(region, domain, repository);
        CodeArtifactRepository r = requireRepository(owner, key);
        checkRevision(r.getPolicyRevision(), policyRevision);
        validatePolicyDocument(policyDocument);
        r.setPolicyDocument(policyDocument);
        r.setPolicyRevision(newRevision());
        repositories.putForAccount(owner, key, r);
        return new ResourcePolicy(r.getArn(), r.getPolicyRevision(), r.getPolicyDocument());
    }

    public ResourcePolicy getRepositoryPermissionsPolicy(String region, String domain, String domainOwner,
                                                          String repository) {
        String owner = effectiveOwner(domainOwner);
        CodeArtifactRepository r = requireRepository(owner, repositoryKey(region, domain, repository));
        if (r.getPolicyDocument() == null) {
            throw notFound("No resource policy is associated with repository '" + repository + "'.");
        }
        return new ResourcePolicy(r.getArn(), r.getPolicyRevision(), r.getPolicyDocument());
    }

    public synchronized ResourcePolicy deleteRepositoryPermissionsPolicy(String region, String domain,
                                                                          String domainOwner, String repository,
                                                                          String policyRevision) {
        String owner = effectiveOwner(domainOwner);
        String key = repositoryKey(region, domain, repository);
        CodeArtifactRepository r = requireRepository(owner, key);
        if (r.getPolicyDocument() == null) {
            throw notFound("No resource policy is associated with repository '" + repository + "'.");
        }
        checkRevision(r.getPolicyRevision(), policyRevision);
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
        String owner = effectiveOwner(domainOwner);
        String key = repositoryKey(region, domain, repository);
        CodeArtifactRepository r = requireRepository(owner, key);
        if (!r.getExternalConnections().isEmpty()) {
            throw conflict("Repository '" + repository + "' already has an external connection; "
                    + "a repository can only have one.");
        }
        if (!r.getUpstreams().isEmpty()) {
            throw conflict("Repository '" + repository + "' has upstream repositories; "
                    + "a repository cannot have both an external connection and upstream repositories.");
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
        String owner = effectiveOwner(domainOwner);
        String key = repositoryKey(region, domain, repository);
        CodeArtifactRepository r = requireRepository(owner, key);
        List<ExternalConnection> connections = new ArrayList<>(r.getExternalConnections());
        boolean removed = connections.removeIf(ec -> ec.getExternalConnectionName().equals(externalConnection));
        if (!removed) {
            throw notFound("Repository '" + repository + "' has no external connection named '"
                    + externalConnection + "'.");
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
                    "The maximum asset file size is 5 Gigabytes.", 402);
        }
        String actualSha256 = sha256Hex(assetContent);
        if (!assetSha256.equalsIgnoreCase(actualSha256)) {
            throw validation("assetSHA256 does not match the SHA-256 hash of the uploaded content.");
        }

        String owner = effectiveOwner(domainOwner);
        requireRepository(owner, repositoryKey(region, domain, repository));
        String key = packageVersionKey(region, domain, repository, format, namespace, packageName, version);

        CodeArtifactPackageVersion pv = packageVersions.getForAccount(owner, key).orElse(null);
        if (pv != null && "Published".equals(pv.getStatus())) {
            throw conflict("Package version '" + version + "' of package '" + packageName + "' is already "
                    + "Published; no additional assets can be uploaded to it.");
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
        if (!assets.containsKey(assetName) && assets.size() >= MAX_ASSETS_PER_PACKAGE_VERSION) {
            throw new AwsException("ServiceQuotaExceededException",
                    "A package version can have a maximum of " + MAX_ASSETS_PER_PACKAGE_VERSION + " assets.", 402);
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
        String owner = effectiveOwner(domainOwner);
        String key = packageVersionKey(region, domain, repository, format, namespace, packageName, version);
        return packageVersions.getForAccount(owner, key)
                .orElseThrow(() -> notFound("Package version '" + version + "' of package '" + packageName
                        + "' was not found."));
    }

    public PackageVersionAssetResult getPackageVersionAsset(String region, String domain, String domainOwner,
            String repository, String format, String namespace, String packageName, String version,
            String assetName, String packageVersionRevision) {
        if (format == null || !PACKAGE_FORMATS.contains(format)) {
            throw validation("format must be one of " + PACKAGE_FORMATS + ".");
        }
        String owner = effectiveOwner(domainOwner);
        String key = packageVersionKey(region, domain, repository, format, namespace, packageName, version);
        CodeArtifactPackageVersion pv = packageVersions.getForAccount(owner, key)
                .orElseThrow(() -> notFound("Package version '" + version + "' of package '" + packageName
                        + "' was not found."));
        if (packageVersionRevision != null && !packageVersionRevision.equals(pv.getRevision())) {
            throw notFound("Package version '" + version + "' was not found at revision '"
                    + packageVersionRevision + "'.");
        }
        PackageAsset asset = pv.getAssets().get(assetName);
        if (asset == null) {
            throw notFound("Asset '" + assetName + "' was not found on package version '" + version + "'.");
        }
        // The live object's content may already be populated (same JVM as the publish that set
        // it), but it isn't guaranteed to be: a reload from the persisted store never restores it,
        // since it's excluded from that JSON/WAL. Always re-read from the asset store itself
        // rather than trusting whatever happens to already be on the object.
        asset.setContent(readAssetContent(owner, key, assetName));
        return new PackageVersionAssetResult(asset, pv.getRevision());
    }

    // -------------------------------------------------------------------- tags

    public synchronized void tagResource(String resourceArn, Map<String, String> newTags) {
        ResourceRef ref = parseResourceArn(resourceArn);
        if (ref.repository() == null) {
            String key = domainKey(ref.region(), ref.domain());
            CodeArtifactDomain d = requireDomain(ref.owner(), key);
            d.setTags(validateTags(newTags, d.getTags()));
            domains.putForAccount(ref.owner(), key, d);
        } else {
            String key = repositoryKey(ref.region(), ref.domain(), ref.repository());
            CodeArtifactRepository r = requireRepository(ref.owner(), key);
            r.setTags(validateTags(newTags, r.getTags()));
            repositories.putForAccount(ref.owner(), key, r);
        }
    }

    public synchronized void untagResource(String resourceArn, List<String> tagKeys) {
        ResourceRef ref = parseResourceArn(resourceArn);
        if (ref.repository() == null) {
            String key = domainKey(ref.region(), ref.domain());
            CodeArtifactDomain d = requireDomain(ref.owner(), key);
            Map<String, String> tags = new LinkedHashMap<>(d.getTags());
            tagKeys.forEach(tags::remove);
            d.setTags(tags);
            domains.putForAccount(ref.owner(), key, d);
        } else {
            String key = repositoryKey(ref.region(), ref.domain(), ref.repository());
            CodeArtifactRepository r = requireRepository(ref.owner(), key);
            Map<String, String> tags = new LinkedHashMap<>(r.getTags());
            tagKeys.forEach(tags::remove);
            r.setTags(tags);
            repositories.putForAccount(ref.owner(), key, r);
        }
    }

    public Map<String, String> listTagsForResource(String resourceArn) {
        ResourceRef ref = parseResourceArn(resourceArn);
        if (ref.repository() == null) {
            return requireDomain(ref.owner(), domainKey(ref.region(), ref.domain())).getTags();
        }
        return requireRepository(ref.owner(), repositoryKey(ref.region(), ref.domain(), ref.repository())).getTags();
    }

    @Override
    public void clear() {
        domains.clear();
        repositories.clear();
        packageVersions.clear();
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
                    "A repository can have a maximum of 10 direct upstream repositories.", 402);
        }
        for (String upstream : upstreams) {
            if (upstream.equals(repository)) {
                throw validation("A repository cannot be its own upstream.");
            }
            if (repositories.getForAccount(owner, repositoryKey(region, domain, upstream)).isEmpty()) {
                throw notFound("Upstream repository '" + upstream + "' was not found in domain '" + domain + "'.");
            }
        }
    }

    private CodeArtifactDomain requireDomain(String owner, String key) {
        return domains.getForAccount(owner, key)
                .orElseThrow(() -> notFound("Domain not found."));
    }

    private CodeArtifactRepository requireRepository(String owner, String key) {
        return repositories.getForAccount(owner, key)
                .orElseThrow(() -> notFound("Repository not found."));
    }

    private void checkRevision(String currentRevision, String requestedRevision) {
        if (requestedRevision != null && !requestedRevision.equals(currentRevision)) {
            throw conflict("The policy revision does not match the current policy revision.");
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

    private static Map<String, String> validateTags(Map<String, String> newTags, Map<String, String> existing) {
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
                    "The maximum number of tags (200) for this resource has been exceeded.", 402);
        }
        return merged;
    }

    private static void validatePackageToken(String field, String value) {
        if (value == null || value.isEmpty() || value.length() > 255 || !PACKAGE_TOKEN.matcher(value).matches()) {
            throw validation(field + " must be 1-255 characters with no '#', '/', or whitespace.");
        }
    }

    private static void validateAssetName(String assetName) {
        if (assetName == null || assetName.isEmpty() || assetName.length() > 255
                || !ASSET_NAME.matcher(assetName).matches()) {
            throw validation("asset must be 1-255 characters with no control characters.");
        }
    }

    // ----------------------------------------------------------- asset bytes

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
                .resolve(sha256Hex(packageVersionKey.getBytes(StandardCharsets.UTF_8)))
                .resolve(sha256Hex(assetName.getBytes(StandardCharsets.UTF_8)));
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

    private static Map<String, String> computeHashes(byte[] content) {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String algorithm : ASSET_HASH_ALGORITHMS) {
            hashes.put(algorithm, SigV4RequestValidator.hexEncode(digest(algorithm, content)));
        }
        return hashes;
    }

    private static String sha256Hex(byte[] content) {
        try {
            return SigV4RequestValidator.sha256Hex(content);
        } catch (Exception e) {
            throw new IllegalStateException("JVM does not support SHA-256", e);
        }
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
                + (namespace == null ? "" : namespace) + "::" + packageName + "::" + version;
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }
}
