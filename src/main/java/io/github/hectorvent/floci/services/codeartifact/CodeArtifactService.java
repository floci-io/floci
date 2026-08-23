package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactDomain;
import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactRepository;
import io.github.hectorvent.floci.services.codeartifact.model.RepositoryExternalConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * AWS CodeArtifact domain and repository control plane.
 *
 * <p>Domains are {@code Active} from the moment {@code CreateDomain} returns, so a describe
 * never reports a transitional status. The package data plane (publish, copy, dispose,
 * versions, assets, readme, package groups) is not emulated.
 */
@ApplicationScoped
public class CodeArtifactService {

    private static final Logger LOG = Logger.getLogger(CodeArtifactService.class);

    private static final Pattern DOMAIN_NAME = Pattern.compile("[a-z][a-z0-9\\-]{0,48}[a-z0-9]");
    private static final Pattern REPOSITORY_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._\\-]{1,99}");
    private static final Set<String> PACKAGE_FORMATS =
            Set.of("npm", "pypi", "maven", "nuget", "generic", "ruby", "swift", "cargo");
    private static final Set<String> ENDPOINT_TYPES = Set.of("dualstack", "ipv4");
    private static final String ACTIVE = "Active";
    private static final String CONNECTION_AVAILABLE = "Available";

    private final StorageBackend<String, CodeArtifactDomain> domains;
    private final StorageBackend<String, CodeArtifactRepository> repositories;
    private final RegionResolver regionResolver;
    private final EmulatorConfig config;

    @Inject
    public CodeArtifactService(StorageFactory storageFactory, RegionResolver regionResolver, EmulatorConfig config) {
        this.domains = storageFactory.create("codeartifact", "codeartifact-domains.json",
                new TypeReference<Map<String, CodeArtifactDomain>>() {});
        this.repositories = storageFactory.create("codeartifact", "codeartifact-repositories.json",
                new TypeReference<Map<String, CodeArtifactRepository>>() {});
        this.regionResolver = regionResolver;
        this.config = config;
    }

    // ──────────────────────────── Domains ────────────────────────────

    public CodeArtifactDomain createDomain(String domainName, String encryptionKey,
                                           Map<String, String> tags, String region) {
        validateDomainName(domainName);
        String key = domainKey(region, domainName);
        if (domains.get(key).isPresent()) {
            throw new AwsException("ConflictException",
                    "Domain " + domainName + " already exists.", 409);
        }

        String accountId = regionResolver.getAccountId();
        CodeArtifactDomain domain = new CodeArtifactDomain();
        domain.setName(domainName);
        domain.setOwner(accountId);
        domain.setArn(regionResolver.buildArn("codeartifact", region, "domain/" + domainName));
        domain.setStatus(ACTIVE);
        domain.setCreatedTime(Instant.now());
        domain.setEncryptionKey(encryptionKey != null && !encryptionKey.isBlank()
                ? encryptionKey
                : "arn:aws:kms:" + region + ":" + accountId + ":key/" + UUID.randomUUID());
        domain.setS3BucketArn("arn:aws:s3:::assets-" + accountId + "-" + region + "-"
                + UUID.randomUUID().toString().substring(0, 8));
        domain.setTags(tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>());

        domains.put(key, domain);
        LOG.infov("Created CodeArtifact domain: {0}", domainName);
        return domain;
    }

    public CodeArtifactDomain describeDomain(String domainName, String domainOwner, String region) {
        if (domainName == null || domainName.isBlank()) {
            throw new AwsException("ValidationException", "domain is required.", 400);
        }
        CodeArtifactDomain domain = domains.get(domainKey(region, domainName))
                .orElseThrow(() -> domainNotFound(domainName));
        if (domainOwner != null && !domainOwner.isBlank() && !domainOwner.equals(domain.getOwner())) {
            throw domainNotFound(domainName);
        }
        return domain;
    }

    /** AWS refuses to delete a domain that still contains repositories. */
    public CodeArtifactDomain deleteDomain(String domainName, String domainOwner, String region) {
        CodeArtifactDomain domain = describeDomain(domainName, domainOwner, region);
        if (!listRepositoriesInDomain(domainName, domainOwner, null, region).isEmpty()) {
            throw new AwsException("ConflictException",
                    "Domain " + domainName + " cannot be deleted because it contains repositories.", 409);
        }
        domains.delete(domainKey(region, domainName));
        LOG.infov("Deleted CodeArtifact domain: {0}", domainName);
        return domain;
    }

    public List<CodeArtifactDomain> listDomains(String region) {
        String regionPrefix = region + "::";
        return domains.scan(key -> key.startsWith(regionPrefix)).stream()
                .sorted(Comparator.comparing(CodeArtifactDomain::getName))
                .toList();
    }

    public int repositoryCount(String domainName, String region) {
        return listRepositoriesInDomain(domainName, null, null, region).size();
    }

    // ──────────────────── Domain permissions policies ────────────────────

    public CodeArtifactDomain putDomainPermissionsPolicy(String domainName, String domainOwner,
                                                         String policyRevision, String policyDocument,
                                                         String region) {
        if (policyDocument == null || policyDocument.isBlank()) {
            throw new AwsException("ValidationException", "policyDocument is required.", 400);
        }
        CodeArtifactDomain domain = describeDomain(domainName, domainOwner, region);
        checkRevision(policyRevision, domain.getPolicyRevision(), "domain", domainName);
        domain.setPolicyDocument(policyDocument);
        domain.setPolicyRevision(newPolicyRevision());
        domains.put(domainKey(region, domainName), domain);
        return domain;
    }

    public CodeArtifactDomain getDomainPermissionsPolicy(String domainName, String domainOwner, String region) {
        CodeArtifactDomain domain = describeDomain(domainName, domainOwner, region);
        if (domain.getPolicyDocument() == null) {
            throw new AwsException("ResourceNotFoundException",
                    "Domain " + domainName + " has no permissions policy.", 404);
        }
        return domain;
    }

    public CodeArtifactDomain deleteDomainPermissionsPolicy(String domainName, String domainOwner,
                                                            String policyRevision, String region) {
        CodeArtifactDomain domain = getDomainPermissionsPolicy(domainName, domainOwner, region);
        checkRevision(policyRevision, domain.getPolicyRevision(), "domain", domainName);
        CodeArtifactDomain deleted = new CodeArtifactDomain();
        deleted.setArn(domain.getArn());
        deleted.setPolicyDocument(domain.getPolicyDocument());
        deleted.setPolicyRevision(domain.getPolicyRevision());

        domain.setPolicyDocument(null);
        domain.setPolicyRevision(null);
        domains.put(domainKey(region, domainName), domain);
        return deleted;
    }

    // ──────────────────────────── Repositories ────────────────────────────

    public CodeArtifactRepository createRepository(String domainName, String domainOwner, String repositoryName,
                                                   String description, List<String> upstreams,
                                                   Map<String, String> tags, String region) {
        CodeArtifactDomain domain = describeDomain(domainName, domainOwner, region);
        validateRepositoryName(repositoryName);
        String key = repositoryKey(region, domainName, repositoryName);
        if (repositories.get(key).isPresent()) {
            throw new AwsException("ConflictException",
                    "Repository " + repositoryName + " already exists in domain " + domainName + ".", 409);
        }

        CodeArtifactRepository repository = new CodeArtifactRepository();
        repository.setName(repositoryName);
        repository.setAdministratorAccount(domain.getOwner());
        repository.setDomainName(domainName);
        repository.setDomainOwner(domain.getOwner());
        repository.setArn(regionResolver.buildArn("codeartifact", region,
                "repository/" + domainName + "/" + repositoryName));
        repository.setDescription(description);
        repository.setUpstreams(resolveUpstreams(domainName, domainOwner, upstreams, region));
        repository.setCreatedTime(Instant.now());
        repository.setTags(tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>());

        repositories.put(key, repository);
        LOG.infov("Created CodeArtifact repository: {0}/{1}", domainName, repositoryName);
        return repository;
    }

    public CodeArtifactRepository describeRepository(String domainName, String domainOwner, String repositoryName,
                                                     String region) {
        describeDomain(domainName, domainOwner, region);
        if (repositoryName == null || repositoryName.isBlank()) {
            throw new AwsException("ValidationException", "repository is required.", 400);
        }
        return repositories.get(repositoryKey(region, domainName, repositoryName))
                .orElseThrow(() -> repositoryNotFound(domainName, repositoryName));
    }

    public CodeArtifactRepository updateRepository(String domainName, String domainOwner, String repositoryName,
                                                   String description, List<String> upstreams, boolean upstreamsSent,
                                                   String region) {
        CodeArtifactRepository repository = describeRepository(domainName, domainOwner, repositoryName, region);
        if (description != null) {
            repository.setDescription(description);
        }
        if (upstreamsSent) {
            repository.setUpstreams(resolveUpstreams(domainName, domainOwner, upstreams, region));
        }
        repositories.put(repositoryKey(region, domainName, repositoryName), repository);
        return repository;
    }

    public CodeArtifactRepository deleteRepository(String domainName, String domainOwner, String repositoryName,
                                                   String region) {
        CodeArtifactRepository repository = describeRepository(domainName, domainOwner, repositoryName, region);
        repositories.delete(repositoryKey(region, domainName, repositoryName));
        LOG.infov("Deleted CodeArtifact repository: {0}/{1}", domainName, repositoryName);
        return repository;
    }

    public List<CodeArtifactRepository> listRepositories(String repositoryPrefix, String region) {
        String regionPrefix = region + "::";
        return repositories.scan(key -> key.startsWith(regionPrefix)).stream()
                .filter(repository -> repositoryPrefix == null || repositoryPrefix.isBlank()
                        || repository.getName().startsWith(repositoryPrefix))
                .sorted(Comparator.comparing(CodeArtifactRepository::getDomainName)
                        .thenComparing(CodeArtifactRepository::getName))
                .toList();
    }

    public List<CodeArtifactRepository> listRepositoriesInDomain(String domainName, String domainOwner,
                                                                 String repositoryPrefix, String region) {
        if (domainName == null || domainName.isBlank()) {
            throw new AwsException("ValidationException", "domain is required.", 400);
        }
        if (domainOwner != null && !domainOwner.isBlank()) {
            describeDomain(domainName, domainOwner, region);
        }
        String prefix = region + "::" + domainName + "::";
        return repositories.scan(key -> key.startsWith(prefix)).stream()
                .filter(repository -> repositoryPrefix == null || repositoryPrefix.isBlank()
                        || repository.getName().startsWith(repositoryPrefix))
                .sorted(Comparator.comparing(CodeArtifactRepository::getName))
                .toList();
    }

    // ────────────────── Repository permissions policies ──────────────────

    public CodeArtifactRepository putRepositoryPermissionsPolicy(String domainName, String domainOwner,
                                                                 String repositoryName, String policyRevision,
                                                                 String policyDocument, String region) {
        if (policyDocument == null || policyDocument.isBlank()) {
            throw new AwsException("ValidationException", "policyDocument is required.", 400);
        }
        CodeArtifactRepository repository = describeRepository(domainName, domainOwner, repositoryName, region);
        checkRevision(policyRevision, repository.getPolicyRevision(), "repository", repositoryName);
        repository.setPolicyDocument(policyDocument);
        repository.setPolicyRevision(newPolicyRevision());
        repositories.put(repositoryKey(region, domainName, repositoryName), repository);
        return repository;
    }

    public CodeArtifactRepository getRepositoryPermissionsPolicy(String domainName, String domainOwner,
                                                                 String repositoryName, String region) {
        CodeArtifactRepository repository = describeRepository(domainName, domainOwner, repositoryName, region);
        if (repository.getPolicyDocument() == null) {
            throw new AwsException("ResourceNotFoundException",
                    "Repository " + repositoryName + " has no permissions policy.", 404);
        }
        return repository;
    }

    public CodeArtifactRepository deleteRepositoryPermissionsPolicy(String domainName, String domainOwner,
                                                                    String repositoryName, String policyRevision,
                                                                    String region) {
        CodeArtifactRepository repository =
                getRepositoryPermissionsPolicy(domainName, domainOwner, repositoryName, region);
        checkRevision(policyRevision, repository.getPolicyRevision(), "repository", repositoryName);
        CodeArtifactRepository deleted = new CodeArtifactRepository();
        deleted.setArn(repository.getArn());
        deleted.setPolicyDocument(repository.getPolicyDocument());
        deleted.setPolicyRevision(repository.getPolicyRevision());

        repository.setPolicyDocument(null);
        repository.setPolicyRevision(null);
        repositories.put(repositoryKey(region, domainName, repositoryName), repository);
        return deleted;
    }

    // ────────────────────── External connections ──────────────────────

    public CodeArtifactRepository associateExternalConnection(String domainName, String domainOwner,
                                                              String repositoryName, String externalConnection,
                                                              String region) {
        CodeArtifactRepository repository = describeRepository(domainName, domainOwner, repositoryName, region);
        if (!repository.getExternalConnections().isEmpty()) {
            throw new AwsException("ConflictException",
                    "Repository " + repositoryName + " already has an external connection.", 409);
        }
        RepositoryExternalConnection connection = new RepositoryExternalConnection();
        connection.setExternalConnectionName(externalConnection);
        connection.setPackageFormat(packageFormatOf(externalConnection));
        connection.setStatus(CONNECTION_AVAILABLE);
        repository.getExternalConnections().add(connection);
        repositories.put(repositoryKey(region, domainName, repositoryName), repository);
        return repository;
    }

    public CodeArtifactRepository disassociateExternalConnection(String domainName, String domainOwner,
                                                                 String repositoryName, String externalConnection,
                                                                 String region) {
        CodeArtifactRepository repository = describeRepository(domainName, domainOwner, repositoryName, region);
        boolean removed = repository.getExternalConnections()
                .removeIf(connection -> connection.getExternalConnectionName().equals(externalConnection));
        if (!removed) {
            throw new AwsException("ResourceNotFoundException",
                    "Repository " + repositoryName + " has no external connection " + externalConnection + ".", 404);
        }
        repositories.put(repositoryKey(region, domainName, repositoryName), repository);
        return repository;
    }

    /**
     * Returns the emulator-hosted stand-in for the per-domain CodeArtifact endpoint. The path
     * matches the AWS shape ({@code /<format>/<repository>/}); the host is floci's, because
     * the package data plane is not emulated.
     */
    public String repositoryEndpoint(String domainName, String domainOwner, String repositoryName,
                                     String format, String endpointType, String region) {
        describeRepository(domainName, domainOwner, repositoryName, region);
        if (format == null || !PACKAGE_FORMATS.contains(format)) {
            throw new AwsException("ValidationException",
                    "format must be one of " + PACKAGE_FORMATS.stream().sorted().toList() + ".", 400);
        }
        if (endpointType != null && !endpointType.isBlank() && !ENDPOINT_TYPES.contains(endpointType)) {
            throw new AwsException("ValidationException",
                    "endpointType must be one of " + ENDPOINT_TYPES.stream().sorted().toList() + ".", 400);
        }
        return config.effectiveBaseUrl() + "/" + format + "/" + repositoryName + "/";
    }

    // ──────────────────────────── Tags ────────────────────────────

    public Map<String, String> listTagsForResource(String resourceArn, String region) {
        Object resource = findByArn(resourceArn, region);
        return resource instanceof CodeArtifactDomain domain ? domain.getTags()
                : ((CodeArtifactRepository) resource).getTags();
    }

    public void tagResource(String resourceArn, Map<String, String> tags, String region) {
        Object resource = findByArn(resourceArn, region);
        if (resource instanceof CodeArtifactDomain domain) {
            domain.getTags().putAll(tags);
            domains.put(domainKey(region, domain.getName()), domain);
        } else {
            CodeArtifactRepository repository = (CodeArtifactRepository) resource;
            repository.getTags().putAll(tags);
            repositories.put(repositoryKey(region, repository.getDomainName(), repository.getName()), repository);
        }
    }

    public void untagResource(String resourceArn, List<String> tagKeys, String region) {
        Object resource = findByArn(resourceArn, region);
        if (resource instanceof CodeArtifactDomain domain) {
            tagKeys.forEach(domain.getTags()::remove);
            domains.put(domainKey(region, domain.getName()), domain);
        } else {
            CodeArtifactRepository repository = (CodeArtifactRepository) resource;
            tagKeys.forEach(repository.getTags()::remove);
            repositories.put(repositoryKey(region, repository.getDomainName(), repository.getName()), repository);
        }
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private List<String> resolveUpstreams(String domainName, String domainOwner, List<String> upstreams,
                                          String region) {
        if (upstreams == null || upstreams.isEmpty()) {
            return List.of();
        }
        for (String upstream : upstreams) {
            describeRepository(domainName, domainOwner, upstream, region);
        }
        return List.copyOf(upstreams);
    }

    /**
     * Derives the package format from the external connection name. AWS names public
     * upstreams {@code public:<ecosystem>[-<flavour>]}, and the format follows from the
     * ecosystem segment.
     */
    private String packageFormatOf(String externalConnection) {
        if (externalConnection == null || !externalConnection.startsWith("public:")) {
            throw new AwsException("ValidationException",
                    "externalConnection must name a public upstream repository, e.g. public:npmjs.", 400);
        }
        String ecosystem = externalConnection.substring("public:".length());
        if (ecosystem.startsWith("maven")) {
            return "maven";
        }
        if (ecosystem.startsWith("npm")) {
            return "npm";
        }
        if (ecosystem.startsWith("pypi")) {
            return "pypi";
        }
        if (ecosystem.startsWith("nuget")) {
            return "nuget";
        }
        if (ecosystem.startsWith("crates")) {
            return "cargo";
        }
        if (ecosystem.startsWith("ruby")) {
            return "ruby";
        }
        if (ecosystem.startsWith("swift")) {
            return "swift";
        }
        if (ecosystem.startsWith("generic")) {
            return "generic";
        }
        throw new AwsException("ValidationException",
                "Unsupported external connection " + externalConnection + ".", 400);
    }

    private Object findByArn(String resourceArn, String region) {
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(resourceArn);
        } catch (IllegalArgumentException e) {
            throw new AwsException("ValidationException", "Invalid resourceArn: " + resourceArn, 400);
        }
        if (!"codeartifact".equals(arn.service())) {
            throw new AwsException("ValidationException", "Invalid resourceArn: " + resourceArn, 400);
        }
        String arnRegion = arn.region().isEmpty() ? region : arn.region();
        String[] parts = arn.resource().split("/");
        if (parts.length == 2 && "domain".equals(parts[0])) {
            return domains.get(domainKey(arnRegion, parts[1])).orElseThrow(() -> domainNotFound(parts[1]));
        }
        if (parts.length == 3 && "repository".equals(parts[0])) {
            return repositories.get(repositoryKey(arnRegion, parts[1], parts[2]))
                    .orElseThrow(() -> repositoryNotFound(parts[1], parts[2]));
        }
        throw new AwsException("ValidationException", "Invalid resourceArn: " + resourceArn, 400);
    }

    private void checkRevision(String suppliedRevision, String currentRevision, String resourceType, String name) {
        if (suppliedRevision != null && !suppliedRevision.isBlank()
                && !suppliedRevision.equals(currentRevision)) {
            throw new AwsException("ConflictException",
                    "The policyRevision does not match the current revision of " + resourceType + " " + name + ".",
                    409);
        }
    }

    private String newPolicyRevision() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private void validateDomainName(String domainName) {
        if (domainName == null || !DOMAIN_NAME.matcher(domainName).matches()) {
            throw new AwsException("ValidationException",
                    "domain must be 2-50 characters matching [a-z][a-z0-9\\-]{0,48}[a-z0-9].", 400);
        }
    }

    private void validateRepositoryName(String repositoryName) {
        if (repositoryName == null || !REPOSITORY_NAME.matcher(repositoryName).matches()) {
            throw new AwsException("ValidationException",
                    "repository must be 2-100 characters matching [A-Za-z0-9][A-Za-z0-9._\\-]{1,99}.", 400);
        }
    }

    private AwsException domainNotFound(String domainName) {
        return new AwsException("ResourceNotFoundException", "Domain " + domainName + " does not exist.", 404);
    }

    private AwsException repositoryNotFound(String domainName, String repositoryName) {
        return new AwsException("ResourceNotFoundException",
                "Repository " + repositoryName + " does not exist in domain " + domainName + ".", 404);
    }

    private String domainKey(String region, String domainName) {
        return region + "::" + domainName;
    }

    private String repositoryKey(String region, String domainName, String repositoryName) {
        return region + "::" + domainName + "::" + repositoryName;
    }
}
