package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactDomain;
import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactRepository;
import io.github.hectorvent.floci.services.codeartifact.model.ExternalConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

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

    private static final Pattern DOMAIN_NAME = Pattern.compile("[a-z][a-z0-9\\-]{0,48}[a-z0-9]");
    private static final Pattern REPOSITORY_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._\\-]{1,99}");
    private static final Pattern ACCOUNT_ID = Pattern.compile("[0-9]{12}");

    public record DomainView(CodeArtifactDomain domain, int repositoryCount) {}
    public record ResourcePolicy(String resourceArn, String revision, String document) {}

    private final AccountAwareStorageBackend<CodeArtifactDomain> domains;
    private final AccountAwareStorageBackend<CodeArtifactRepository> repositories;
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

    // ---------------------------------------------------------------- domains

    public synchronized DomainView createDomain(String region, String domain, String encryptionKey,
                                                 Map<String, String> tags) {
        validateDomainName(domain);
        String owner = regionResolver.getAccountId();
        String key = domainKey(region, domain);
        if (domains.getForAccount(owner, key).isPresent()) {
            throw conflict("Domain with name '" + domain + "' already exists.");
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

    private static String domainKey(String region, String domain) {
        return region + "::" + domain;
    }

    private static String repositoryKey(String region, String domain, String repository) {
        return region + "::" + domain + "::" + repository;
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
