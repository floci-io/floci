package io.github.hectorvent.floci.services.ecr;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsPartitions;
import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ecr.model.AuthorizationData;
import io.github.hectorvent.floci.services.ecr.model.Image;
import io.github.hectorvent.floci.services.ecr.model.ImageDetail;
import io.github.hectorvent.floci.services.ecr.model.ImageFailure;
import io.github.hectorvent.floci.services.ecr.model.ImageIdentifier;
import io.github.hectorvent.floci.services.ecr.model.ImageMetadata;
import io.github.hectorvent.floci.services.ecr.model.PullThroughCacheRule;
import io.github.hectorvent.floci.services.ecr.model.RegistrySettings;
import io.github.hectorvent.floci.services.ecr.model.Repository;
import io.github.hectorvent.floci.services.ecr.model.RepositoryCreationTemplate;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.ecr.registry.RegistryHttpClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;

@ApplicationScoped
public class EcrService implements ResourceProvider {

    /**
     * A private ECR registry host in any partition, {@code <account>.dkr.ecr.<region>.<dnsSuffix>},
     * matched the same way {@code EcrRegistryManager} recognises an ECR image URI.
     */
    private static final Pattern ECR_UPSTREAM_HOST = Pattern.compile(
            "[0-9]{12}\\.dkr\\.ecr\\.[a-z0-9-]+\\." + AwsPartitions.dnsSuffixRegex());

    private static final Logger LOG = Logger.getLogger(EcrService.class);
    private static final Pattern REPO_NAME = Pattern.compile(
            "(?:[a-z0-9]+(?:[._-][a-z0-9]+)*/)*[a-z0-9]+(?:[._-][a-z0-9]+)*");
    private static final Pattern PULL_THROUGH_CACHE_PREFIX = Pattern.compile(
            "(?:[a-z0-9]+(?:(?:\\.|_|__|-+)[a-z0-9]+)*(?:/[a-z0-9]+"
                    + "(?:(?:\\.|_|__|-+)[a-z0-9]+)*)*/?|ROOT)");
    private static final Pattern REGISTRY_ID = Pattern.compile("[0-9]{12}");
    private static final Pattern LAYER_DIGEST = Pattern.compile("[a-zA-Z0-9-_+.]+:[a-fA-F0-9]+");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** ECR's imageTag rule, which the service model bounds at 300 characters. */
    private static final Pattern ECR_IMAGE_TAG = Pattern.compile("[a-zA-Z0-9_.-]+");
    /** The OCI distribution spec's tag grammar, which the backing registry's manifest URL takes. */
    private static final Pattern REGISTRY_IMAGE_TAG = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]{0,127}");
    private static final Pattern CREDENTIAL_ARN = Pattern.compile(
            "arn:" + AwsArnUtils.PARTITION_REGEX + ":secretsmanager:[a-zA-Z0-9-:]+:secret:ecr-pullthroughcache/"
                    + "[a-zA-Z0-9/_+=.@-]+");
    private static final Set<String> UPSTREAM_REGISTRIES = Set.of(
            "ecr", "ecr-public", "quay", "k8s", "docker-hub",
            "github-container-registry", "azure-container-registry",
            "gitlab-container-registry", "chainguard");
    private static final ObjectReader SINGLE_JSON_VALUE =
            MAPPER.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final int MAX_REPO_NAME_LENGTH = 256;
    private static final int MAX_PULL_THROUGH_CACHE_PREFIX_LENGTH = 30;

    private final StorageBackend<String, Repository> repoStore;
    private final StorageBackend<String, ImageMetadata> imageMetaStore;
    private final StorageBackend<String, PullThroughCacheRule> pullThroughCacheRuleStore;
    private final StorageBackend<String, RegistrySettings> registrySettingsStore;
    private final Map<String, Object> registryLocks = new ConcurrentHashMap<>();
    private final StorageBackend<String, RepositoryCreationTemplate> templateStore;
    private final EcrRegistryManager registryManager;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final EcrLayerUploads layerUploads = new EcrLayerUploads(this::discardUpload);
    private final Set<String> inFlightImmutableTags = ConcurrentHashMap.newKeySet();

    @Inject
    public EcrService(StorageFactory factory,
                      EcrRegistryManager registryManager,
                      EmulatorConfig config,
                      RegionResolver regionResolver) {
        this(factory.create("ecr", "repositories.json",
                        new TypeReference<Map<String, Repository>>() {}),
                factory.create("ecr", "image-metadata.json",
                        new TypeReference<Map<String, ImageMetadata>>() {}),
                factory.create("ecr", "pull-through-cache-rules.json",
                        new TypeReference<Map<String, PullThroughCacheRule>>() {}),
                factory.create("ecr", "registry-settings.json",
                        new TypeReference<Map<String, RegistrySettings>>() {}),
                factory.create("ecr", "repository-creation-templates.json",
                        new TypeReference<Map<String, RepositoryCreationTemplate>>() {}),
                registryManager, config, regionResolver);
    }

    EcrService(StorageBackend<String, Repository> repoStore,
               StorageBackend<String, ImageMetadata> imageMetaStore,
               StorageBackend<String, PullThroughCacheRule> pullThroughCacheRuleStore,
               EcrRegistryManager registryManager,
               EmulatorConfig config,
               RegionResolver regionResolver) {
        this(repoStore, imageMetaStore, pullThroughCacheRuleStore, new InMemoryStorage<>(), new InMemoryStorage<>(),
                registryManager, config, regionResolver);
    }

    EcrService(StorageBackend<String, Repository> repoStore,
               StorageBackend<String, ImageMetadata> imageMetaStore,
               StorageBackend<String, PullThroughCacheRule> pullThroughCacheRuleStore,
               StorageBackend<String, RegistrySettings> registrySettingsStore,
               StorageBackend<String, RepositoryCreationTemplate> templateStore,
               EcrRegistryManager registryManager,
               EmulatorConfig config,
               RegionResolver regionResolver) {
        this.repoStore = repoStore;
        this.imageMetaStore = imageMetaStore;
        this.pullThroughCacheRuleStore = pullThroughCacheRuleStore;
        this.registrySettingsStore = registrySettingsStore;
        this.templateStore = templateStore;
        this.registryManager = registryManager;
        this.config = config;
        this.regionResolver = regionResolver;
        this.registryManager.setReconcileHook(this::reconcileFromCatalog);
    }

    public boolean isDisabled() {
        return !config.services().ecr().enabled();
    }

    // ============================================================
    // Pull through cache rules
    // ============================================================

    public PullThroughCacheRule createPullThroughCacheRule(String ecrRepositoryPrefix,
                                                           String upstreamRegistryUrl,
                                                           String registryId,
                                                           String upstreamRegistry,
                                                           String credentialArn,
                                                           String customRoleArn,
                                                           String upstreamRepositoryPrefix,
                                                           String region) {
        String prefix = normalizePullThroughCachePrefix(ecrRepositoryPrefix);
        validatePullThroughCachePrefix(prefix, "ecrRepositoryPrefix");
        validateUpstreamRegistryUrl(upstreamRegistryUrl);
        validateCredentialArn(credentialArn);
        validateCustomRoleArn(customRoleArn);
        String upstreamPrefix = upstreamRepositoryPrefix == null || upstreamRepositoryPrefix.isBlank()
                ? "ROOT"
                : normalizePullThroughCachePrefix(upstreamRepositoryPrefix);
        validatePullThroughCachePrefix(upstreamPrefix, "upstreamRepositoryPrefix");
        if ("ROOT".equals(prefix) && !"ROOT".equals(upstreamPrefix)) {
            throw new AwsException("InvalidParameterException",
                    "upstreamRepositoryPrefix must be ROOT when ecrRepositoryPrefix is ROOT", 400);
        }
        String account = effectiveAccount(registryId);
        validateRegistryId(account);
        String resolvedUpstreamRegistry = resolveUpstreamRegistry(upstreamRegistry, upstreamRegistryUrl);
        String key = pullThroughCacheRuleKey(region, account, prefix);
        if (pullThroughCacheRuleStore.get(key).isPresent()) {
            throw new AwsException("PullThroughCacheRuleAlreadyExistsException",
                    "A pull through cache rule with repository prefix '" + prefix
                            + "' already exists in registry '" + account + "'", 400);
        }

        Instant now = Instant.now();
        PullThroughCacheRule rule = new PullThroughCacheRule();
        rule.setRegistryId(account);
        rule.setEcrRepositoryPrefix(prefix);
        rule.setUpstreamRegistryUrl(upstreamRegistryUrl);
        rule.setUpstreamRegistry(resolvedUpstreamRegistry);
        rule.setCredentialArn(credentialArn);
        rule.setCustomRoleArn(customRoleArn);
        rule.setUpstreamRepositoryPrefix(upstreamPrefix);
        rule.setCreatedAt(now);
        rule.setUpdatedAt(now);
        pullThroughCacheRuleStore.put(key, rule);
        return rule;
    }

    public PaginatedResult<PullThroughCacheRule> describePullThroughCacheRules(
            String registryId,
            List<String> ecrRepositoryPrefixes,
            Integer maxResults,
            String nextToken,
            String region) {
        String account = effectiveAccount(registryId);
        validateRegistryId(account);
        List<PullThroughCacheRule> rules;
        if (ecrRepositoryPrefixes == null) {
            String keyPrefix = region + "::" + account + "::";
            rules = pullThroughCacheRuleStore.scan(key -> key.startsWith(keyPrefix));
        } else {
            if (ecrRepositoryPrefixes.isEmpty() || ecrRepositoryPrefixes.size() > 100) {
                throw new AwsException("InvalidParameterException",
                        "ecrRepositoryPrefixes must contain between 1 and 100 items", 400);
            }
            rules = new ArrayList<>();
            for (String requestedPrefix : ecrRepositoryPrefixes) {
                String prefix = normalizePullThroughCachePrefix(requestedPrefix);
                validatePullThroughCachePrefix(prefix, "ecrRepositoryPrefixes");
                rules.add(pullThroughCacheRuleStore.get(pullThroughCacheRuleKey(region, account, prefix))
                        .orElseThrow(() -> pullThroughCacheRuleNotFound(prefix, account)));
            }
        }
        return Pagination.paginate(rules, PullThroughCacheRule::getEcrRepositoryPrefix,
                maxResults, nextToken, 100, 1000, "InvalidParameterException");
    }

    public PullThroughCacheRule deletePullThroughCacheRule(String ecrRepositoryPrefix,
                                                           String registryId,
                                                           String region) {
        String prefix = normalizePullThroughCachePrefix(ecrRepositoryPrefix);
        validatePullThroughCachePrefix(prefix, "ecrRepositoryPrefix");
        String account = effectiveAccount(registryId);
        validateRegistryId(account);
        String key = pullThroughCacheRuleKey(region, account, prefix);
        PullThroughCacheRule rule = pullThroughCacheRuleStore.get(key)
                .orElseThrow(() -> pullThroughCacheRuleNotFound(prefix, account));
        pullThroughCacheRuleStore.delete(key);
        return rule;
    }

    public PullThroughCacheRule updatePullThroughCacheRule(String ecrRepositoryPrefix,
                                                           String registryId,
                                                           String credentialArn,
                                                           String customRoleArn,
                                                           String region) {
        String prefix = normalizePullThroughCachePrefix(ecrRepositoryPrefix);
        validatePullThroughCachePrefix(prefix, "ecrRepositoryPrefix");
        String account = effectiveAccount(registryId);
        validateRegistryId(account);
        validateCredentialArn(credentialArn);
        validateCustomRoleArn(customRoleArn);

        String key = pullThroughCacheRuleKey(region, account, prefix);
        PullThroughCacheRule rule = pullThroughCacheRuleStore.get(key)
                .orElseThrow(() -> pullThroughCacheRuleNotFound(prefix, account));
        if (credentialArn != null) {
            rule.setCredentialArn(credentialArn);
        }
        if (customRoleArn != null) {
            rule.setCustomRoleArn(customRoleArn);
        }
        rule.setUpdatedAt(Instant.now());
        pullThroughCacheRuleStore.put(key, rule);
        return rule;
    }

    public PullThroughCacheRule validatePullThroughCacheRule(String ecrRepositoryPrefix,
                                                             String registryId,
                                                             String region) {
        String prefix = normalizePullThroughCachePrefix(ecrRepositoryPrefix);
        validatePullThroughCachePrefix(prefix, "ecrRepositoryPrefix");
        String account = effectiveAccount(registryId);
        validateRegistryId(account);
        return pullThroughCacheRuleStore.get(pullThroughCacheRuleKey(region, account, prefix))
                .orElseThrow(() -> pullThroughCacheRuleNotFound(prefix, account));
    }

    /**
     * Recreates {@link Repository} metadata entries for registry namespaces found in the catalog.
     * Namespaces created by Floci are {@code <account>/<region>/<repoName>}; pre-proxy registry
     * namespaces are recovered for the default account and region without moving image storage.
     */
    void reconcileFromCatalog(List<String> catalog) {
        if (catalog == null || catalog.isEmpty()) {
            return;
        }
        int recreated = 0;
        for (String registryRepositoryName : catalog) {
            String defaultAccount = regionResolver.getAccountId();
            String defaultRegion = regionResolver.getDefaultRegion();
            String legacyKey = key(defaultRegion, defaultAccount, registryRepositoryName);
            Optional<Repository> legacyRepository = repoStore.get(legacyKey);
            if (legacyRepository.isPresent()) {
                Repository repo = legacyRepository.get();
                repo.setRepositoryUri(registryManager.getRepositoryUri(
                        defaultAccount, defaultRegion, repo.getRepositoryName()));
                repo.setRegistryRepositoryName(registryRepositoryName);
                repoStore.put(legacyKey, repo);
                continue;
            }
            String[] parts = registryRepositoryName.split("/", 3);
            boolean namespaced = parts.length == 3 && parts[0].matches("[0-9]{12}")
                    && parts[1].matches("[a-z0-9-]+");
            String account = namespaced ? parts[0] : defaultAccount;
            String region = namespaced ? parts[1] : defaultRegion;
            String repoName = namespaced ? parts[2] : registryRepositoryName;
            String key = key(region, account, repoName);
            Optional<Repository> existing = repoStore.get(key);
            if (existing.isPresent()) {
                Repository repo = existing.get();
                repo.setRepositoryUri(registryManager.getRepositoryUri(account, region, repoName));
                if (!namespaced) {
                    repo.setRegistryRepositoryName(registryRepositoryName);
                }
                repoStore.put(key, repo);
                continue;
            }
            Repository repo = new Repository();
            repo.setRepositoryName(repoName);
            repo.setRegistryId(account);
            repo.setRepositoryArn(AwsArnUtils.Arn.of("ecr", region, account, "repository/" + repoName).toString());
            repo.setRepositoryUri(registryManager.getRepositoryUri(account, region, repoName));
            if (!namespaced) {
                repo.setRegistryRepositoryName(registryRepositoryName);
            }
            repo.setCreatedAt(Instant.now());
            repoStore.put(key, repo);
            recreated++;
        }
        if (recreated > 0) {
            LOG.infov("Reconciled {0} ECR repository metadata entries from registry catalog", recreated);
        }
    }

    // ============================================================
    // Repository creation templates
    // ============================================================

    /**
     * Stores a template under its prefix. Fields left null take ECR's defaults: AES256 encryption
     * and mutable tags.
     */
    public RepositoryCreationTemplate createRepositoryCreationTemplate(RepositoryCreationTemplate requested,
                                                                       String region) {
        validateTemplateFields(requested);
        String account = regionResolver.getAccountId();
        String key = templateKey(region, account, requested.getPrefix());
        synchronized (registryLock(region)) {
            if (templateStore.get(key).isPresent()) {
                throw new AwsException("TemplateAlreadyExistsException",
                        "The repository creation template with prefix '" + requested.getPrefix()
                                + "' already exists in the registry with id '" + account + "'", 400);
            }
            RepositoryCreationTemplate template = new RepositoryCreationTemplate();
            template.setPrefix(requested.getPrefix());
            template.setEncryptionConfiguration(new RepositoryCreationTemplate.EncryptionConfiguration("AES256", null));
            template.setImageTagMutability("MUTABLE");
            applyTemplateFields(template, requested);
            Instant now = Instant.now();
            template.setCreatedAt(now);
            template.setUpdatedAt(now);
            templateStore.put(key, template);
            return template;
        }
    }

    /**
     * Every template in the account and Region, or those named in {@code prefixes}; a prefix with
     * no template is left out, as the model lists no not-found error for this action.
     */
    public PaginatedResult<RepositoryCreationTemplate> describeRepositoryCreationTemplates(
            List<String> prefixes, Integer maxResults, String nextToken, String region) {
        String account = regionResolver.getAccountId();
        List<RepositoryCreationTemplate> templates;
        if (prefixes == null || prefixes.isEmpty()) {
            String keyPrefix = region + "::" + account + "::";
            templates = templateStore.scan(key -> key.startsWith(keyPrefix));
        } else {
            Set<String> keys = new LinkedHashSet<>();
            for (String prefix : prefixes) {
                keys.add(templateKey(region, account, prefix));
            }
            templates = new ArrayList<>();
            for (String key : keys) {
                templateStore.get(key).ifPresent(templates::add);
            }
        }
        return Pagination.paginate(templates, RepositoryCreationTemplate::getPrefix,
                maxResults, nextToken, 100, 1000, "InvalidParameterException");
    }

    /** Replaces each field the request sets; an empty string or list clears it. */
    public RepositoryCreationTemplate updateRepositoryCreationTemplate(RepositoryCreationTemplate requested,
                                                                       String region) {
        validateTemplateFields(requested);
        String key = templateKey(region, regionResolver.getAccountId(), requested.getPrefix());
        synchronized (registryLock(region)) {
            RepositoryCreationTemplate template = templateStore.get(key)
                    .orElseThrow(() -> templateNotFound(requested.getPrefix()));
            applyTemplateFields(template, requested);
            // Exclusion filters mean nothing outside a *_WITH_EXCLUSION mode. Terraform leaves the
            // member out when the filters are removed, so they go when the mode no longer takes them.
            if (!template.getImageTagMutability().endsWith("_WITH_EXCLUSION")) {
                template.setImageTagMutabilityExclusionFilters(null);
            }
            template.setUpdatedAt(Instant.now());
            templateStore.put(key, template);
            return template;
        }
    }

    public RepositoryCreationTemplate deleteRepositoryCreationTemplate(String prefix, String region) {
        String key = templateKey(region, regionResolver.getAccountId(), prefix);
        synchronized (registryLock(region)) {
            RepositoryCreationTemplate template = templateStore.get(key).orElseThrow(() -> templateNotFound(prefix));
            templateStore.delete(key);
            return template;
        }
    }

    /** The registry the templates belong to: the caller's account. */
    public String templateRegistryId() {
        return regionResolver.getAccountId();
    }

    /**
     * The template rules the ECR model can't state; {@code EcrRequestShapes} has already checked
     * the request against the model's types, lengths, patterns and enums.
     */
    private static void validateTemplateFields(RepositoryCreationTemplate t) {
        if (t.getAppliedFor() != null && t.getAppliedFor().isEmpty()) {
            throw invalidTemplate("appliedFor must name at least one of REPLICATION, PULL_THROUGH_CACHE "
                    + "or CREATE_ON_PUSH");
        }
        requireJsonObject(t.getRepositoryPolicy(), "repositoryPolicy");
        requireJsonObject(t.getLifecyclePolicy(), "lifecyclePolicy");
    }

    private static void requireJsonObject(String text, String field) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (!isJsonObject(text)) {
            throw invalidTemplate(field + " must be a JSON object");
        }
    }

    /** Whether {@code text} is one JSON object with nothing after it, so {@code {} x} is not. */
    private static boolean isJsonObject(String text) {
        try {
            return SINGLE_JSON_VALUE.readTree(text).isObject();
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    /** Copies the fields the request sets; an empty string or list clears the stored value. */
    private static void applyTemplateFields(RepositoryCreationTemplate template, RepositoryCreationTemplate requested) {
        if (requested.getDescription() != null) {
            template.setDescription(emptyToNull(requested.getDescription()));
        }
        if (requested.getEncryptionConfiguration() != null) {
            RepositoryCreationTemplate.EncryptionConfiguration e = requested.getEncryptionConfiguration();
            template.setEncryptionConfiguration(new RepositoryCreationTemplate.EncryptionConfiguration(
                    e.encryptionType(), emptyToNull(e.kmsKey())));
        }
        if (requested.getResourceTags() != null) {
            template.setResourceTags(List.copyOf(requested.getResourceTags()));
        }
        if (requested.getImageTagMutability() != null) {
            template.setImageTagMutability(requested.getImageTagMutability());
        }
        if (requested.getImageTagMutabilityExclusionFilters() != null) {
            template.setImageTagMutabilityExclusionFilters(List.copyOf(requested.getImageTagMutabilityExclusionFilters()));
        }
        if (requested.getRepositoryPolicy() != null) {
            template.setRepositoryPolicy(emptyToNull(requested.getRepositoryPolicy()));
        }
        if (requested.getLifecyclePolicy() != null) {
            template.setLifecyclePolicy(emptyToNull(requested.getLifecyclePolicy()));
        }
        if (requested.getAppliedFor() != null) {
            template.setAppliedFor(List.copyOf(new LinkedHashSet<>(requested.getAppliedFor())));
        }
        if (requested.getCustomRoleArn() != null) {
            template.setCustomRoleArn(emptyToNull(requested.getCustomRoleArn()));
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** ECR assumes a {@code /} after every prefix, so {@code prod} and {@code prod/} are one template. */
    private static String templateKey(String region, String account, String prefix) {
        String namespace = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        return region + "::" + account + "::" + namespace;
    }

    private static AwsException invalidTemplate(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }

    private AwsException templateNotFound(String prefix) {
        return new AwsException("TemplateNotFoundException",
                "The repository creation template with prefix '" + prefix
                        + "' does not exist in the registry with id '" + regionResolver.getAccountId() + "'", 400);
    }

    // ============================================================
    // CreateRepository
    // ============================================================

    public Repository createRepository(String repositoryName,
                                       String registryId,
                                       String imageTagMutability,
                                       Boolean scanOnPush,
                                       String encryptionType,
                                       String kmsKey,
                                       Map<String, String> tags,
                                       String region) {
        validateRepoName(repositoryName);
        // Repository records are pure metadata: the ARN and the URI are derived from the
        // configured account, region and registry port, none of which need Docker. Start
        // the backing registry opportunistically so the URI reflects an already-adopted
        // container's published port, but never fail CreateRepository when no Docker
        // daemon is reachable, the registry is retried on the next image operation.
        registryManager.tryEnsureStarted();
        String account = effectiveAccount(registryId);
        String key = key(region, account, repositoryName);
        if (repoStore.get(key).isPresent()) {
            throw new AwsException("RepositoryAlreadyExistsException",
                    "The repository with name '" + repositoryName + "' already exists in the registry with id '"
                            + account + "'", 400);
        }

        Repository repo = new Repository();
        repo.setRepositoryName(repositoryName);
        repo.setRegistryId(account);
        repo.setRepositoryArn(AwsArnUtils.Arn.of("ecr", region, account, "repository/" + repositoryName).toString());
        repo.setRepositoryUri(registryManager.getRepositoryUri(account, region, repositoryName));
        repo.setCreatedAt(Instant.now());
        if (imageTagMutability != null && !imageTagMutability.isBlank()) {
            repo.setImageTagMutability(imageTagMutability);
        }
        if (scanOnPush != null) {
            repo.setScanOnPush(scanOnPush);
        }
        if (encryptionType != null && !encryptionType.isBlank()) {
            repo.setEncryptionType(encryptionType);
        }
        repo.setKmsKey(kmsKey);
        if (tags != null) {
            repo.getTags().putAll(tags);
        }

        repoStore.put(key, repo);
        LOG.infov("Created ECR repository {0}/{1}/{2}", region, account, repositoryName);
        return repo;
    }

    // ============================================================
    // DescribeRepositories
    // ============================================================

    public List<Repository> describeRepositories(List<String> repositoryNames,
                                                 String registryId,
                                                 String region) {
        String account = effectiveAccount(registryId);
        String prefix = region + "::" + account + "::";

        if (repositoryNames == null || repositoryNames.isEmpty()) {
            return repoStore.scan(k -> k.startsWith(prefix)).stream()
                    .map(repo -> refreshRepositoryUri(repo, region))
                    .toList();
        }

        List<Repository> out = new ArrayList<>();
        for (String name : repositoryNames) {
            String key = key(region, account, name);
            Repository repo = repoStore.get(key).orElseThrow(() -> notFound(name, account));
            out.add(refreshRepositoryUri(repo, region));
        }
        return out;
    }

    public record ScanningConfigurationFailure(String repositoryName, String failureCode, String failureReason) {}

    public record BatchScanningConfigurationResult(List<Repository> repositories,
                                                    List<ScanningConfigurationFailure> failures) {}

    public BatchScanningConfigurationResult batchGetRepositoryScanningConfiguration(
            List<String> repositoryNames, String registryId, String region) {
        String account = effectiveAccount(registryId);
        List<Repository> repositories = new ArrayList<>();
        List<ScanningConfigurationFailure> failures = new ArrayList<>();
        for (String name : repositoryNames) {
            Optional<Repository> repository = repoStore.get(key(region, account, name));
            if (repository.isPresent()) {
                repositories.add(refreshRepositoryUri(repository.get(), region));
            } else {
                failures.add(new ScanningConfigurationFailure(name, "REPOSITORY_NOT_FOUND",
                        notFound(name, account).getMessage()));
            }
        }
        return new BatchScanningConfigurationResult(repositories, failures);
    }

    // ============================================================
    // DeleteRepository
    // ============================================================

    public Repository deleteRepository(String repositoryName,
                                       String registryId,
                                       boolean force,
                                       String region) {
        String account = effectiveAccount(registryId);
        String key = key(region, account, repositoryName);
        Repository repo = repoStore.get(key).orElseThrow(() -> notFound(repositoryName, account));

        // Check whether the registry has any tagged images for this repo.
        // A test double with no HTTP client means no backing registry exists,
        // so there is nothing to clean up. Any failure against a real client
        // aborts the delete: an unreachable registry cannot prove the repo
        // empty (non-force must not bypass RepositoryNotEmptyException) and
        // force must not orphan pullable images behind deleted metadata.
        List<String> tags;
        boolean registryAvailable = true;
        if (registryManager.httpClient() == null) {
            // Test double without an HTTP layer (Mockito default): no backing
            // registry exists, so there is nothing to clean up.
            tags = List.of();
            registryAvailable = false;
        } else try {
            tags = listTagsOrThrow(account, region, repositoryName);
        } catch (Exception e) {
            if (isRegistryUnreachable(e)) {
                throw new AwsException("ServerException",
                        "Backing registry unreachable for repository '" + repositoryName
                                + "': retry after the registry recovers (" + e.getMessage() + ")",
                        500);
            }
            throw new AwsException("ServerException",
                    "Failed to clean up the backing registry for repository '" + repositoryName + "': "
                            + e.getMessage(),
                    500);
        }
        if (!tags.isEmpty() && !force) {
            throw new AwsException("RepositoryNotEmptyException",
                    "The repository with name '" + repositoryName
                            + "' in registry with id '" + account + "' cannot be deleted because it still contains images",
                    400);
        }
        if (force && registryAvailable) {
            // Single cleanup mechanism (#2982): remove the repository storage
            // directories inside the registry container. Routed through
            // resolveRegistryRepoName so content pushed under the bare name
            // via hostname-style URIs is removed too (issue #2444).
            deleteRepositoryStorageResolved(account, region, repositoryName);
        }

        repoStore.delete(key);
        // Drop cached image metadata for this repo.
        String metaPrefix = key + "::";
        for (ImageMetadata meta : imageMetaStore.scan(k -> k.startsWith(metaPrefix))) {
            imageMetaStore.delete(metaPrefix + meta.getDigest());
        }
        if (!hasRepositories()) {
            registryManager.pruneStorage();
        }
        LOG.infov("Deleted ECR repository {0}/{1}/{2}", region, account, repositoryName);
        repo.setRepositoryUri(registryManager.getRepositoryUri(account, region, repositoryName));
        return repo;
    }

    // ============================================================
    // GetAuthorizationToken
    // ============================================================

    public AuthorizationData getAuthorizationToken() {
        // Deliberately not gated on the backing registry: AWS answers this call from the
        // control plane, and both values here are computed without Docker. Gating it made
        // every SDK client retry a 500 before its first repository call.
        String token = Base64.getEncoder()
                .encodeToString("AWS:floci".getBytes(StandardCharsets.UTF_8));
        Instant expires = Instant.now().plusSeconds(12 * 60 * 60);
        String proxy = registryManager.getProxyEndpoint();
        return new AuthorizationData(token, expires, proxy);
    }

    // ============================================================
    // ListImages / DescribeImages / BatchGetImage / BatchDeleteImage
    // ============================================================

    public PaginatedResult<ImageIdentifier> listImages(String repositoryName, String registryId, String region,
                                                       Integer maxResults, String nextToken) {
        int limit = maxResults != null ? maxResults : 100;
        if (limit < 1 || limit > 1000) {
            throw new AwsException("InvalidParameterException", "maxResults must be between 1 and 1000", 400);
        }
        String namespace = "ListImages:" + region + ":" + effectiveAccount(registryId) + ":" + repositoryName;
        Repository repo = requireRepo(repositoryName, registryId, region);
        requireRegistry();
        String internal = resolveRegistryRepoName(repo.getRegistryId(), region, repositoryName);
        try {
            RegistryHttpClient http = registryManager.httpClient();
            PaginatedResult<String> page = Pagination.paginate(http.listTags(internal), Function.identity(),
                    limit, nextToken, namespace,
                    token -> new AwsException("InvalidParameterException", "Invalid nextToken", 400));
            return new PaginatedResult<>(imageIdentifiers(http, internal, page.items()), page.nextToken());
        } catch (IOException | InterruptedException e) {
            LOG.warnv("ListImages registry query failed for {0}: {1}", repositoryName, e.getMessage());
            return new PaginatedResult<>(List.of(), null);
        }
    }

    private static List<ImageIdentifier> imageIdentifiers(RegistryHttpClient http, String internal, List<String> tags)
            throws IOException, InterruptedException {
        List<ImageIdentifier> identifiers = new ArrayList<>();
        for (String tag : tags) {
            identifiers.add(new ImageIdentifier(tag, http.headManifestDigest(internal, tag, null)));
        }
        return identifiers;
    }

    public DescribeImagesResult describeImages(String repositoryName,
                                                List<ImageIdentifier> requested,
                                                String registryId,
                                                String region) {
        Repository repo = requireRepo(repositoryName, registryId, region);
        requireRegistry();
        String internal = resolveRegistryRepoName(repo.getRegistryId(), region, repositoryName);
        RegistryHttpClient http = registryManager.httpClient();

        List<String> refs = new ArrayList<>();
        if (requested == null || requested.isEmpty()) {
            try {
                refs.addAll(http.listTags(internal));
            } catch (Exception e) {
                LOG.warnv("DescribeImages tag enumeration failed for {0}: {1}", repositoryName, e.getMessage());
            }
        } else {
            for (ImageIdentifier id : requested) {
                if (id.getImageTag() != null) refs.add(id.getImageTag());
                else if (id.getImageDigest() != null) refs.add(id.getImageDigest());
            }
        }

        boolean explicitRequest = requested != null && !requested.isEmpty();
        List<ImageDetail> details = new ArrayList<>();
        List<ImageFailure> failures = new ArrayList<>();
        for (String ref : refs) {
            try {
                RegistryHttpClient.ManifestResult m = http.getManifest(internal, ref, null);
                if (m == null) {
                    failures.add(new ImageFailure(
                            new ImageIdentifier(ref.startsWith("sha256:") ? null : ref,
                                    ref.startsWith("sha256:") ? ref : null),
                            "ImageNotFound", "Image not found"));
                    continue;
                }
                ImageDetail d = new ImageDetail();
                d.setRegistryId(repo.getRegistryId());
                d.setRepositoryName(repositoryName);
                d.setImageDigest(m.digest());
                if (!ref.startsWith("sha256:")) {
                    d.setImageTags(new ArrayList<>(List.of(ref)));
                }
                d.setImageSizeInBytes(RegistryHttpClient.sizeFromManifest(m.body()));
                d.setImageManifestMediaType(m.mediaType());
                d.setArtifactMediaType(RegistryHttpClient.artifactMediaTypeFromManifest(m.body()));

                String metaKey = imageMetaKey(region, repo.getRegistryId(), repositoryName, m.digest());
                ImageMetadata meta = imageMetaStore.get(metaKey).orElseGet(() -> {
                    ImageMetadata fresh = new ImageMetadata(m.digest(), Instant.now());
                    imageMetaStore.put(metaKey, fresh);
                    return fresh;
                });
                d.setImagePushedAt(meta.getPushedAt());
                details.add(d);
            } catch (Exception e) {
                LOG.warnv("DescribeImages registry call failed for {0}/{1}: {2}", repositoryName, ref, e.getMessage());
                failures.add(new ImageFailure(
                        new ImageIdentifier(ref.startsWith("sha256:") ? null : ref,
                                ref.startsWith("sha256:") ? ref : null),
                        "ImageNotFound", "Image not found"));
            }
        }
        // Real AWS throws ImageNotFoundException when explicit imageIds were passed
        // and NONE of them resolved to an actual image. cdk-assets relies on this
        // exception to decide whether an asset needs to be published.
        if (explicitRequest && details.isEmpty()) {
            throw new AwsException("ImageNotFoundException",
                    "The image with imageId(s) " + requested + " does not exist within the repository with name '"
                            + repositoryName + "' in the registry with id '" + repo.getRegistryId() + "'", 400);
        }
        return new DescribeImagesResult(details, failures);
    }

    public BatchGetImageResult batchGetImage(String repositoryName,
                                              List<ImageIdentifier> imageIds,
                                              List<String> acceptedMediaTypes,
                                              String registryId,
                                              String region) {
        Repository repo = requireRepo(repositoryName, registryId, region);
        requireRegistry();
        String internal = resolveRegistryRepoName(repo.getRegistryId(), region, repositoryName);
        RegistryHttpClient http = registryManager.httpClient();

        List<Image> images = new ArrayList<>();
        List<ImageFailure> failures = new ArrayList<>();
        if (imageIds == null) imageIds = List.of();
        for (ImageIdentifier id : imageIds) {
            String ref = id.getImageTag() != null ? id.getImageTag() : id.getImageDigest();
            if (ref == null) {
                failures.add(new ImageFailure(id, "MissingDigestAndTag", "Both imageTag and imageDigest are missing"));
                continue;
            }
            try {
                RegistryHttpClient.ManifestResult m = http.getManifest(internal, ref, acceptedMediaTypes);
                if (m == null) {
                    failures.add(new ImageFailure(id, "ImageNotFound", "Image not found"));
                    continue;
                }
                Image img = new Image();
                img.setRegistryId(repo.getRegistryId());
                img.setRepositoryName(repositoryName);
                img.setImageId(new ImageIdentifier(
                        id.getImageTag(),
                        m.digest() != null ? m.digest() : id.getImageDigest()));
                img.setImageManifest(m.body());
                img.setImageManifestMediaType(m.mediaType());
                images.add(img);
            } catch (Exception e) {
                failures.add(new ImageFailure(id, "ImageNotFound", e.getMessage()));
            }
        }
        return new BatchGetImageResult(images, failures);
    }

    public BatchDeleteImageResult batchDeleteImage(String repositoryName,
                                                    List<ImageIdentifier> imageIds,
                                                    String registryId,
                                                    String region) {
        Repository repo = requireRepo(repositoryName, registryId, region);
        requireRegistry();
        String internal = resolveRegistryRepoName(repo.getRegistryId(), region, repositoryName);
        RegistryHttpClient http = registryManager.httpClient();

        List<ImageIdentifier> deleted = new ArrayList<>();
        List<ImageFailure> failures = new ArrayList<>();
        if (imageIds == null) imageIds = List.of();
        for (ImageIdentifier id : imageIds) {
            try {
                String digest = id.getImageDigest();
                if (digest == null && id.getImageTag() != null) {
                    digest = http.headManifestDigest(internal, id.getImageTag(), null);
                }
                if (digest == null) {
                    failures.add(new ImageFailure(id, "ImageNotFound", "Image not found"));
                    continue;
                }
                boolean ok = http.deleteManifest(internal, digest);
                if (!ok) {
                    failures.add(new ImageFailure(id, "ImageNotFound", "Image not found"));
                    continue;
                }
                deleted.add(new ImageIdentifier(id.getImageTag(), digest));
                imageMetaStore.delete(imageMetaKey(region, repo.getRegistryId(), repositoryName, digest));
            } catch (Exception e) {
                failures.add(new ImageFailure(id, "ImageNotFound", e.getMessage()));
            }
        }
        return new BatchDeleteImageResult(deleted, failures);
    }

    // ============================================================
    // Image push through the API (PutImage and the layer API)
    // ============================================================

    public record LayerAvailability(String layerDigest, String availability, Long layerSize) {}
    public record LayerFailure(String layerDigest, String failureCode, String failureReason) {}
    public record BatchCheckLayerAvailabilityResult(List<LayerAvailability> layers, List<LayerFailure> failures) {}
    public record LayerUpload(String registryId, String repositoryName, String uploadId) {}

    /**
     * Writes a manifest to the registry under its tag, or under its digest when no tag is given, so
     * {@code docker pull} and {@code BatchGetImage} see it as they see a pushed image.
     */
    public Image putImage(String repositoryName, String imageManifest, String imageManifestMediaType,
                          String imageTag, String imageDigest, String registryId, String region) {
        Repository repo = requireRepo(repositoryName, registryId, region);
        if (imageManifest == null || imageManifest.isEmpty()) {
            throw new AwsException("InvalidParameterException", "imageManifest must not be empty", 400);
        }
        if (imageTag != null) {
            requireImageTag(imageTag);
        }
        String mediaType = manifestMediaType(imageManifest, imageManifestMediaType);
        String digest = "sha256:" + Digests.sha256Hex(imageManifest.getBytes(StandardCharsets.UTF_8));
        if (imageDigest != null && !imageDigest.isEmpty() && !imageDigest.equals(digest)) {
            throw new AwsException("ImageDigestDoesNotMatchException",
                    "Invalid parameter at 'imageDigest' failed to satisfy constraint: 'specified digest "
                            + imageDigest + " does not match the calculated digest " + digest + "'", 400);
        }
        requireRegistry();
        String storage = registryRepositoryName(repo, region);
        RegistryHttpClient http = registryManager.httpClient();

        String guarded = imageTag != null && "IMMUTABLE".equals(repo.getImageTagMutability())
                ? immutableTagKey(repo.getRegistryId(), region, repositoryName, imageTag) : null;
        if (guarded != null && !claimImmutableTag(guarded)) {
            throw imageTagAlreadyExists(imageTag, repositoryName);
        }
        try {
            return callRegistry(() -> writeManifest(repo, repositoryName, storage, http, imageManifest, mediaType,
                    imageTag, digest, guarded, region), EcrService::putImageError);
        } finally {
            if (guarded != null) {
                releaseImmutableTag(guarded);
            }
        }
    }

    private Image writeManifest(Repository repo, String repositoryName, String storage, RegistryHttpClient http,
                                String imageManifest, String mediaType, String imageTag, String digest,
                                String guarded, String region) throws Exception {
        String existing = http.headManifestDigestStrict(storage, imageTag != null ? imageTag : digest, null);
        if (digest.equals(existing)) {
            throw new AwsException("ImageAlreadyExistsException",
                    "Image with digest '" + digest + "'" + (imageTag != null ? " and tag '" + imageTag + "'" : "")
                            + " already exists in the repository with name '" + repositoryName
                            + "' in registry with id '" + repo.getRegistryId() + "'", 400);
        }
        if (existing != null && guarded != null) {
            throw imageTagAlreadyExists(imageTag, repositoryName);
        }
        String stored = http.putManifest(storage, imageTag != null ? imageTag : digest, mediaType, imageManifest);
        String finalDigest = stored != null ? stored : digest;
        // A retag keeps the image's first push time, as DescribeImages records it.
        String metaKey = imageMetaKey(region, repo.getRegistryId(), repositoryName, finalDigest);
        if (imageMetaStore.get(metaKey).isEmpty()) {
            imageMetaStore.put(metaKey, new ImageMetadata(finalDigest, Instant.now()));
        }

        Image image = new Image();
        image.setRegistryId(repo.getRegistryId());
        image.setRepositoryName(repositoryName);
        image.setImageId(new ImageIdentifier(imageTag, finalDigest));
        image.setImageManifest(imageManifest);
        image.setImageManifestMediaType(mediaType);
        return image;
    }

    /**
     * Refuses a tag ECR refuses, with ECR's message, then a tag ECR takes but the backing registry
     * cannot hold: one starting with '.' or '-', or longer than 128 characters.
     */
    private static void requireImageTag(String imageTag) {
        if (imageTag.length() > 300) {
            throw new AwsException("InvalidParameterException", "Invalid parameter at 'imageTag' failed to satisfy"
                    + " constraint: 'Member must have length less than or equal to 300'", 400);
        }
        if (!ECR_IMAGE_TAG.matcher(imageTag).matches()) {
            throw new AwsException("InvalidParameterException", "Invalid parameter at 'imageTag' failed to satisfy"
                    + " constraint: 'must satisfy regular expression '[a-zA-Z0-9-_.]+''", 400);
        }
        if (!REGISTRY_IMAGE_TAG.matcher(imageTag).matches()) {
            throw new AwsException("InvalidParameterException", "Floci's backing registry cannot hold the tag '"
                    + imageTag + "': it must start with a letter, digit or underscore and be at most 128"
                    + " characters", 400);
        }
    }

    /** Reports which of the given layers the repository holds, with their sizes. */
    public BatchCheckLayerAvailabilityResult batchCheckLayerAvailability(String repositoryName,
                                                                         List<String> layerDigests,
                                                                         String registryId,
                                                                         String region) {
        Repository repo = requireRepo(repositoryName, registryId, region);
        if (layerDigests == null || layerDigests.isEmpty() || layerDigests.size() > 100) {
            throw new AwsException("InvalidParameterException", "layerDigests must hold 1 to 100 digests", 400);
        }
        requireRegistry();
        String storage = registryRepositoryName(repo, region);
        RegistryHttpClient http = registryManager.httpClient();
        List<LayerAvailability> layers = new ArrayList<>();
        List<LayerFailure> failures = new ArrayList<>();
        for (String digest : layerDigests) {
            if (digest == null || !LAYER_DIGEST.matcher(digest).matches()) {
                failures.add(new LayerFailure(digest, "InvalidLayerDigest", "Invalid layer digest"));
                continue;
            }
            Long size = headBlob(http, storage, digest);
            if (size == null) {
                failures.add(new LayerFailure(digest, "MissingLayerDigest", "Could not find layer"));
            } else {
                layers.add(new LayerAvailability(digest, "AVAILABLE", size));
            }
        }
        return new BatchCheckLayerAvailabilityResult(layers, failures);
    }

    /** The address a layer can be fetched from: Floci's registry endpoint, which needs no credentials. */
    public String getDownloadUrlForLayer(String repositoryName, String layerDigest, String registryId, String region) {
        Repository repo = requireRepo(repositoryName, registryId, region);
        requireLayerDigest(layerDigest);
        requireRegistry();
        Long size = headBlob(registryManager.httpClient(), registryRepositoryName(repo, region), layerDigest);
        if (size == null) {
            throw layersNotFound(layerDigest, repositoryName, repo.getRegistryId());
        }
        return registryManager.blobUrl(repo.getRepositoryUri(), layerDigest);
    }

    /** Cancels the registry upload session of a layer upload left idle for a day. */
    private void discardUpload(String location) {
        registryManager.httpClient().cancelUpload(location);
    }

    /** Opens a registry upload session for one layer. */
    public LayerUpload initiateLayerUpload(String repositoryName, String registryId, String region) {
        Repository repo = requireRepo(repositoryName, registryId, region);
        requireRegistry();
        String location = callRegistry(
                () -> registryManager.httpClient().startUpload(registryRepositoryName(repo, region)),
                EcrService::registryUnavailable);
        String uploadId = layerUploads.open(key(region, repo.getRegistryId(), repositoryName), location);
        return new LayerUpload(repo.getRegistryId(), repositoryName, uploadId);
    }

    /** Sends one part of a layer, returning the last byte received. */
    public long uploadLayerPart(String repositoryName, String uploadId, Long partFirstByte, Long partLastByte,
                                byte[] layerPartBlob, String registryId, String region) {
        Repository repo = requireRepo(repositoryName, registryId, region);
        if (partFirstByte == null || partLastByte == null || layerPartBlob == null
                || partFirstByte < 0 || partLastByte < 0) {
            throw new AwsException("InvalidParameterException",
                    "partFirstByte, partLastByte and layerPartBlob are required and must not be negative", 400);
        }
        EcrLayerUploads.Session session = layerUploads.require(
                key(region, repo.getRegistryId(), repositoryName), uploadId, repositoryName);
        synchronized (session) {
            EcrLayerUploads.checkPart(session, partFirstByte, partLastByte, layerPartBlob.length,
                    repo.getRegistryId(), repositoryName, uploadId);
            requireRegistry();
            String next = callRegistry(
                    () -> registryManager.httpClient().patchUpload(session.location(), layerPartBlob, partFirstByte),
                    e -> uploadError(e, uploadId, repositoryName));
            session.accept(layerPartBlob.length, next, layerUploads.now());
            return partLastByte;
        }
    }

    /** Closes an upload, storing its bytes under the digest given, and returns that digest. */
    public String completeLayerUpload(String repositoryName, String uploadId, List<String> layerDigests,
                                      String registryId, String region) {
        Repository repo = requireRepo(repositoryName, registryId, region);
        if (layerDigests == null || layerDigests.isEmpty() || layerDigests.size() > 100) {
            throw new AwsException("InvalidParameterException", "layerDigests must hold 1 to 100 digests", 400);
        }
        // An upload holds one layer, so every digest given must name it.
        String digest = layerDigests.get(0);
        for (String d : layerDigests) {
            requireLayerDigest(d);
            if (!digest.equals(d)) {
                throw new AwsException("InvalidParameterException",
                        "layerDigests must all name the one layer the upload holds", 400);
            }
        }
        EcrLayerUploads.Session session = layerUploads.require(
                key(region, repo.getRegistryId(), repositoryName), uploadId, repositoryName);
        synchronized (session) {
            EcrLayerUploads.checkComplete(session);
            requireRegistry();
            RegistryHttpClient http = registryManager.httpClient();
            String storage = registryRepositoryName(repo, region);
            if (headBlob(http, storage, digest) != null) {
                http.cancelUpload(session.location());
                layerUploads.close(uploadId);
                throw new AwsException("LayerAlreadyExistsException",
                        "Layer " + digest + " already exists in the repository with name '" + repositoryName
                                + "' in registry with id '" + repo.getRegistryId() + "'", 400);
            }
            callRegistry(() -> {
                http.finishUpload(session.location(), digest);
                return null;
            }, e -> {
                layerUploads.close(uploadId);
                if ("DIGEST_INVALID".equals(e.code())) {
                    return new AwsException("InvalidLayerException",
                            "The layer digest calculated for the upload does not match " + digest, 400);
                }
                return uploadError(e, uploadId, repositoryName);
            });
            layerUploads.close(uploadId);
            return digest;
        }
    }

    /** The registry ID a request names, or the caller's account when it names none. */
    public String registryIdFor(String registryId) {
        return effectiveAccount(registryId);
    }

    /** The key both the registry data plane and {@code PutImage} hold while writing an immutable tag. */
    public static String immutableTagKey(String account, String region, String repositoryName, String tag) {
        return account + "/" + region + "/" + repositoryName + ":" + tag;
    }

    /** Claims an immutable tag for one write; false if another write holds it. */
    public boolean claimImmutableTag(String key) {
        return inFlightImmutableTags.add(key);
    }

    public void releaseImmutableTag(String key) {
        inFlightImmutableTags.remove(key);
    }

    /** The media type sent, else the manifest's own {@code mediaType}; with neither, the request is refused. */
    private static String manifestMediaType(String manifest, String given) {
        JsonNode root;
        try {
            root = MAPPER.readTree(manifest);
        } catch (Exception e) {
            root = null;
        }
        if (root == null || !root.isObject()) {
            throw new AwsException("InvalidParameterException", "imageManifest is not a valid JSON manifest", 400);
        }
        if (given != null && !given.isEmpty()) {
            return given;
        }
        String own = root.path("mediaType").asText("");
        if (own.isEmpty()) {
            throw new AwsException("InvalidParameterException",
                    "imageManifestMediaType is required when the manifest has no mediaType", 400);
        }
        return own;
    }

    private Long headBlob(RegistryHttpClient http, String storage, String digest) {
        return callRegistry(() -> http.headBlob(storage, digest), EcrService::registryUnavailable);
    }

    /** One call to the backing registry. */
    @FunctionalInterface
    private interface RegistryCall<T> {
        T call() throws Exception;
    }

    /**
     * Runs a call to the backing registry, answering a refusal the registry gives with what
     * {@code refused} maps it to and any other failure with {@code ServerException}.
     */
    private static <T> T callRegistry(RegistryCall<T> call,
                                      Function<RegistryHttpClient.RegistryErrorException, AwsException> refused) {
        try {
            return call.call();
        } catch (AwsException e) {
            throw e;
        } catch (RegistryHttpClient.RegistryErrorException e) {
            throw refused.apply(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw registryUnavailable(e);
        } catch (Exception e) {
            throw registryUnavailable(e);
        }
    }

    private static void requireLayerDigest(String digest) {
        if (digest == null || !LAYER_DIGEST.matcher(digest).matches()) {
            throw new AwsException("InvalidParameterException", "Invalid layer digest: " + digest, 400);
        }
    }

    private static AwsException putImageError(RegistryHttpClient.RegistryErrorException e) {
        String code = e.code() == null ? "" : e.code();
        return switch (code) {
            case "MANIFEST_BLOB_UNKNOWN", "BLOB_UNKNOWN" -> new AwsException("LayersNotFoundException",
                    "The image references layers that are not in the repository: " + e.getMessage(), 400);
            case "MANIFEST_INVALID", "MANIFEST_UNVERIFIED", "DIGEST_INVALID", "TAG_INVALID", "NAME_INVALID" ->
                    new AwsException("InvalidParameterException", "The registry refused the manifest: " + e.getMessage(), 400);
            default -> registryUnavailable(e);
        };
    }

    private static AwsException uploadError(RegistryHttpClient.RegistryErrorException e, String uploadId,
                                            String repositoryName) {
        if ("BLOB_UPLOAD_UNKNOWN".equals(e.code()) || e.status() == 404) {
            return new AwsException("UploadNotFoundException",
                    "Upload with id '" + uploadId + "' is not found for repository '" + repositoryName + "'", 400);
        }
        if ("BLOB_UPLOAD_INVALID".equals(e.code()) || e.status() == 416) {
            return new AwsException("InvalidLayerPartException", "The registry refused the part: " + e.getMessage(), 400);
        }
        return registryUnavailable(e);
    }

    private static AwsException imageTagAlreadyExists(String tag, String repositoryName) {
        return new AwsException("ImageTagAlreadyExistsException",
                "The image tag '" + tag + "' already exists in the '" + repositoryName
                        + "' repository and cannot be overwritten because the repository is immutable.", 400);
    }

    private static AwsException layersNotFound(String digest, String repositoryName, String registryId) {
        return new AwsException("LayersNotFoundException",
                "The specified layer " + digest + " could not be found in the repository with name '"
                        + repositoryName + "' in the registry with id '" + registryId + "'", 400);
    }

    private static AwsException registryUnavailable(Exception e) {
        return new AwsException("ServerException", "The ECR backing registry failed: " + e.getMessage(), 500);
    }

    // ============================================================
    // Registry settings (policy, replication, scanning)
    // ============================================================

    /**
     * Stores the registry policy. Its length is checked against the model before this is reached;
     * text that is not a JSON object is refused here, as the model cannot say so.
     */
    public String putRegistryPolicy(String policyText, String region) {
        if (policyText == null || !isJsonObject(policyText)) {
            throw new AwsException("InvalidParameterException", "Invalid parameter at 'PolicyText' failed to satisfy "
                    + "constraint: 'Invalid registry policy provided'", 400);
        }
        updateRegistrySettings(region, settings -> {
            settings.setPolicyText(policyText);
            return null;
        });
        return policyText;
    }

    public String getRegistryPolicy(String region) {
        String policy = registrySettings(region).getPolicyText();
        if (policy == null) {
            throw registryPolicyNotFound();
        }
        return policy;
    }

    /** Removes the registry policy and returns what it was. */
    public String deleteRegistryPolicy(String region) {
        return updateRegistrySettings(region, settings -> {
            String policy = settings.getPolicyText();
            if (policy == null) {
                throw registryPolicyNotFound();
            }
            settings.setPolicyText(null);
            return policy;
        });
    }

    /**
     * Stores the replication rules; an empty list clears them. The rules are checked against the
     * model before this is reached. Floci does not replicate images.
     */
    public List<RegistrySettings.ReplicationRule> putReplicationConfiguration(
            List<RegistrySettings.ReplicationRule> rules, String region) {
        updateRegistrySettings(region, settings -> {
            settings.setReplicationRules(rules.isEmpty() ? null : List.copyOf(rules));
            return null;
        });
        return replicationRules(region);
    }

    /** The stored replication rules, empty when none are set. */
    public List<RegistrySettings.ReplicationRule> replicationRules(String region) {
        List<RegistrySettings.ReplicationRule> rules = registrySettings(region).getReplicationRules();
        return rules == null ? List.of() : rules;
    }

    /**
     * Stores the scanning configuration. The model's constraints are checked before this is
     * reached; {@code CONTINUOUS_SCAN} is refused here with {@code BASIC}, as the API reference
     * offers it only with enhanced scanning and the model cannot say so. Floci does not scan images.
     */
    public RegistrySettings putRegistryScanningConfiguration(String scanType, List<RegistrySettings.ScanningRule> rules,
                                                             String region) {
        String type = scanType == null ? "BASIC" : scanType;
        List<RegistrySettings.ScanningRule> given = rules == null ? List.of() : rules;
        for (RegistrySettings.ScanningRule rule : given) {
            if ("BASIC".equals(type) && "CONTINUOUS_SCAN".equals(rule.scanFrequency())) {
                throw new AwsException("InvalidParameterException",
                        "CONTINUOUS_SCAN is supported only with the ENHANCED scan type", 400);
            }
        }
        updateRegistrySettings(region, settings -> {
            settings.setScanType(type);
            settings.setScanningRules(List.copyOf(given));
            return null;
        });
        return registryScanningConfiguration(region);
    }

    /** The scanning configuration, {@code BASIC} with no rules until one is put. */
    public RegistrySettings registryScanningConfiguration(String region) {
        RegistrySettings stored = registrySettings(region);
        RegistrySettings view = new RegistrySettings();
        view.setScanType(stored.getScanType() == null ? "BASIC" : stored.getScanType());
        view.setScanningRules(stored.getScanningRules() == null ? List.of() : stored.getScanningRules());
        return view;
    }

    /** The registry ID of the caller's account. */
    public String registryId() {
        return regionResolver.getAccountId();
    }

    private RegistrySettings registrySettings(String region) {
        return registrySettingsStore.get(registrySettingsKey(region)).orElseGet(RegistrySettings::new);
    }

    /**
     * Reads the registry's settings, changes them and writes them back under the registry's lock.
     * The settings are one record, and Terraform puts the policy, replication and scanning
     * configuration in parallel: unlocked, two writers could start from the same record and the
     * later write would drop the other's field.
     */
    private <T> T updateRegistrySettings(String region, Function<RegistrySettings, T> change) {
        String key = registrySettingsKey(region);
        synchronized (registryLock(region)) {
            RegistrySettings settings = registrySettings(region);
            T result = change.apply(settings);
            registrySettingsStore.put(key, settings);
            return result;
        }
    }

    /**
     * One lock per registry for every read and write back of its settings and creation templates:
     * unlocked, two creates of one template prefix could both succeed, and an update could write
     * back a template a delete had just removed.
     */
    private Object registryLock(String region) {
        return registryLocks.computeIfAbsent(registrySettingsKey(region), k -> new Object());
    }

    private String registrySettingsKey(String region) {
        return region + "::" + regionResolver.getAccountId();
    }

    private AwsException registryPolicyNotFound() {
        return new AwsException("RegistryPolicyNotFoundException",
                "Registry policy does not exist in the registry with id '" + regionResolver.getAccountId() + "'", 400);
    }

    // ============================================================
    // Tag mutability + resource tags + policies (metadata round-trip)
    // ============================================================

    public Repository putImageScanningConfiguration(String repositoryName, String registryId,
                                                    boolean scanOnPush, String region) {
        Repository repo = requireRepo(repositoryName, registryId, region);
        repo.setScanOnPush(scanOnPush);
        repoStore.put(key(region, repo.getRegistryId(), repositoryName), repo);
        return repo;
    }

    public Repository putImageTagMutability(String repositoryName, String registryId,
                                            String mutability, String region) {
        if (mutability == null
                || (!"MUTABLE".equals(mutability) && !"IMMUTABLE".equals(mutability))) {
            throw new AwsException("InvalidParameterException",
                    "imageTagMutability must be MUTABLE or IMMUTABLE", 400);
        }
        Repository repo = requireRepo(repositoryName, registryId, region);
        repo.setImageTagMutability(mutability);
        repoStore.put(key(region, repo.getRegistryId(), repositoryName), repo);
        return repo;
    }

    /** Returns whether the repository rejects a second manifest write for an existing tag. */
    public boolean isImageTagImmutable(String repositoryName, String registryId, String region) {
        String account = effectiveAccount(registryId);
        return repoStore.get(key(region, account, repositoryName))
                .map(Repository::getImageTagMutability)
                .filter("IMMUTABLE"::equals)
                .isPresent();
    }

    /** Returns the physical registry namespace selected for an ECR repository. */
    public String registryRepositoryName(String repositoryName, String registryId, String region) {
        String account = effectiveAccount(registryId);
        return repoStore.get(key(region, account, repositoryName))
                .map(repo -> registryRepositoryName(repo, region))
                .orElseGet(() -> registryManager.internalRepoName(account, region, repositoryName));
    }

    public void tagResource(String repoName, String registryId, Map<String, String> tags, String region) {
        Repository repo = requireRepo(repoName, registryId, region);
        if (tags != null) {
            repo.getTags().putAll(tags);
        }
        repoStore.put(key(region, repo.getRegistryId(), repoName), repo);
    }

    public void untagResource(String repoName, String registryId, List<String> tagKeys, String region) {
        Repository repo = requireRepo(repoName, registryId, region);
        if (tagKeys != null) {
            for (String k : tagKeys) {
                repo.getTags().remove(k);
            }
        }
        repoStore.put(key(region, repo.getRegistryId(), repoName), repo);
    }

    public Map<String, String> listTagsForResource(String repoName, String registryId, String region) {
        Repository repo = requireRepo(repoName, registryId, region);
        return repo.getTags();
    }

    public Repository putLifecyclePolicy(String repoName, String registryId, String policyText, String region) {
        Repository repo = requireRepo(repoName, registryId, region);
        repo.setLifecyclePolicyText(policyText);
        repoStore.put(key(region, repo.getRegistryId(), repoName), repo);
        return repo;
    }

    public Repository getLifecyclePolicy(String repoName, String registryId, String region) {
        Repository repo = requireRepo(repoName, registryId, region);
        if (repo.getLifecyclePolicyText() == null) {
            throw new AwsException("LifecyclePolicyNotFoundException",
                    "No lifecycle policy associated with repository " + repoName, 400);
        }
        return repo;
    }

    public Repository deleteLifecyclePolicy(String repoName, String registryId, String region) {
        Repository repo = getLifecyclePolicy(repoName, registryId, region);
        repo.setLifecyclePolicyText(null);
        repoStore.put(key(region, repo.getRegistryId(), repoName), repo);
        return repo;
    }

    public Repository setRepositoryPolicy(String repoName, String registryId, String policyText, String region) {
        Repository repo = requireRepo(repoName, registryId, region);
        repo.setRepositoryPolicyText(policyText);
        repoStore.put(key(region, repo.getRegistryId(), repoName), repo);
        return repo;
    }

    public Repository getRepositoryPolicy(String repoName, String registryId, String region) {
        Repository repo = requireRepo(repoName, registryId, region);
        if (repo.getRepositoryPolicyText() == null) {
            throw new AwsException("RepositoryPolicyNotFoundException",
                    "Repository policy does not exist for the repository with name '" + repoName + "'", 400);
        }
        return repo;
    }

    public Repository deleteRepositoryPolicy(String repoName, String registryId, String region) {
        Repository repo = getRepositoryPolicy(repoName, registryId, region);
        repo.setRepositoryPolicyText(null);
        repoStore.put(key(region, repo.getRegistryId(), repoName), repo);
        return repo;
    }

    @Override
    public List<ExplorerResource> getResources() {
        List<ExplorerResource> resources = new ArrayList<>();
        for (Repository repo : repoStore.scan(k -> true)) {
            String arn = repo.getRepositoryArn();
            if (arn == null) {
                continue;
            }
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            resources.add(new ExplorerResource(
                    arn, "ecr:repository", "ecr",
                    parsed.region(), parsed.accountId(),
                    repo.getCreatedAt() != null ? repo.getCreatedAt() : Instant.now(),
                    repo.getTags() != null ? repo.getTags() : Map.of()));
        }
        return resources;
    }

    @Override
    public Set<SupportedResourceType> getSupportedResourceTypes() {
        return Set.of(new SupportedResourceType("ecr:repository", "ecr", true));
    }

    // ============================================================
    // Result records
    // ============================================================

    public record DescribeImagesResult(List<ImageDetail> imageDetails, List<ImageFailure> failures) {}
    public record BatchGetImageResult(List<Image> images, List<ImageFailure> failures) {}
    public record BatchDeleteImageResult(List<ImageIdentifier> imageIds, List<ImageFailure> failures) {}

    // ============================================================
    // Helpers
    // ============================================================

    private Repository requireRepo(String name, String registryId, String region) {
        String account = effectiveAccount(registryId);
        Repository repo = repoStore.get(key(region, account, name)).orElseThrow(() -> notFound(name, account));
        return refreshRepositoryUri(repo, region);
    }

    private static String imageMetaKey(String region, String account, String repoName, String digest) {
        return key(region, account, repoName) + "::" + digest;
    }

    private static String pullThroughCacheRuleKey(String region, String account, String prefix) {
        return region + "::" + account + "::" + prefix;
    }


    /**
     * Gate for the ECR data plane, which cannot be emulated without the backing
     * registry container. Surfaces ECR's modelled {@code ServerException} instead of
     * letting the Docker client's {@code SocketException} escape as an InternalFailure.
     */
    private void requireRegistry() {
        if (!registryManager.tryEnsureStarted()) {
            throw new AwsException("ServerException",
                    "The ECR backing registry is unavailable because no Docker daemon is reachable "
                            + "from Floci. Repository metadata operations are supported; image push, "
                            + "pull and image queries require Docker.", 500);
        }
    }

    private Repository refreshRepositoryUri(Repository repo, String region) {
        repo.setRepositoryUri(registryManager.getRepositoryUri(repo.getRegistryId(), region, repo.getRepositoryName()));
        repoStore.put(key(region, repo.getRegistryId(), repo.getRepositoryName()), repo);
        return repo;
    }

    private String registryRepositoryName(Repository repo, String region) {
        String registryRepositoryName = repo.getRegistryRepositoryName();
        if (registryRepositoryName != null && !registryRepositoryName.isBlank()) {
            return registryRepositoryName;
        }
        return registryManager.internalRepoName(repo.getRegistryId(), region, repo.getRepositoryName());
    }

    private List<String> listTagsBestEffort(Repository repo, String region) {
        try {
            return registryManager.httpClient()
                    .listTags(resolveRegistryRepoName(repo.getRegistryId(), region, repo.getRepositoryName()));
        } catch (Exception e) {
            LOG.debugv("Could not list tags for {0} (registry not available): {1}",
                    repo.getRepositoryName(), e.getMessage());
            return List.of();
        }
    }

    /**
     * Deletes registry storage for the repository under the name it was
     * actually pushed as. Hostname-style pushes land under the bare name,
     * which {@code internalRepoName} alone never addresses (issue #2444).
     */
    private void deleteRepositoryStorageResolved(String account, String region, String repositoryName) {
        String internal = resolveRegistryRepoName(account, region, repositoryName);
        try {
            registryManager.deleteRepositoryStorageByInternalName(internal);
        } catch (Exception e) {
            throw registryFailure(repositoryName, e);
        }
    }

    private boolean hasRepositories() {
        if (repoStore instanceof AccountAwareStorageBackend<?> accountAware) {
            return !accountAware.scanAllAccounts().isEmpty();
        }
        return !repoStore.scan(k -> true).isEmpty();
    }

    private AwsException registryFailure(String repositoryName, Exception cause) {
        LOG.warnv("Could not delete ECR repository {0} from the backing registry: {1}",
                repositoryName, cause.getMessage());
        return new AwsException("ServerException",
                "Could not delete images from repository '" + repositoryName + "'", 500);
    }

    /**
     * Lists tags and propagates registry failures. Used by force-delete to
     * distinguish "registry unreachable" (skip cleanup) from "registry
     * available but listing failed" (abort deletion).
     */
    private List<String> listTagsOrThrow(String account, String region, String repoName) throws Exception {
        return registryManager.httpClient()
                .listTagsStrict(resolveRegistryRepoNameStrict(account, region, repoName));
    }

    /**
     * Resolves the repository name as stored in the backing registry.
     * Hostname-style URIs ({@code <account>.dkr.ecr.<region>.localhost:<port>/<repo>})
     * reach the raw registry, so docker pushes land under the bare repo name,
     * while path-style URIs land under {@code <account>/<region>/<repo>}.
     * Resolution uses catalog membership (not tag listing) so untagged,
     * digest-only repositories resolve correctly. The namespaced form wins
     * when both exist.
     *
     * <p>Ambiguity guard: a bare-name entry can only be claimed when no
     * <em>other</em> account/region has metadata for the same repository name.
     * Otherwise the bare entry's owning scope is unknown and resolving to it
     * would let one scope read or delete another scope's images, so the call
     * falls back to the (empty) namespaced form.
     */
    private String resolveRegistryRepoName(String account, String region, String repoName) {
        try {
            return resolveRegistryRepoNameStrict(account, region, repoName);
        } catch (Exception e) {
            LOG.debugv("Registry lookup failed while resolving {0}: {1}", repoName, e.getMessage());
            return registryManager.internalRepoName(account, region, repoName);
        }
    }

    private String resolveRegistryRepoNameStrict(String account, String region, String repoName) throws Exception {
        String internal = registryManager.internalRepoName(account, region, repoName);
        List<String> catalog = registryManager.httpClient().catalogStrict();
        if (catalog.contains(internal)) {
            return internal;
        }
        if (catalog.contains(repoName)) {
            String currentKey = region + "::" + account + "::" + repoName;
            boolean otherScopeClaimsIt = hasOtherScopeClaim(repoName, currentKey);
            if (!otherScopeClaimsIt) {
                return repoName;
            }
            LOG.warnv("Bare registry entry {0} claimed by another account/region; "
                    + "resolving {0} to its namespaced form", repoName, internal);
        }
        return internal;
    }

    private boolean hasOtherScopeClaim(String repoName, String currentKey) {
        String suffix = "::" + repoName;
        if (repoStore instanceof AccountAwareStorageBackend<?> aware) {
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<Repository> typed = (AccountAwareStorageBackend<Repository>) aware;
            return typed.scanAllAccountEntries(k -> k.endsWith(suffix) && !k.equals(currentKey))
                    .stream().findAny().isPresent();
        }
        return repoStore.keys().stream()
                .anyMatch(k -> k.endsWith(suffix) && !k.equals(currentKey));
    }

    private static boolean isRegistryUnreachable(Throwable e) {
        // Only genuine connectivity failures qualify. An NPE or an "is null"
        // message means a bug or an answered error body, those must abort
        // the delete, never masquerade as an outage.
        Throwable t = e;
        while (t != null) {
            if (t instanceof java.net.ConnectException || t instanceof java.net.UnknownHostException
                    || t instanceof java.net.SocketTimeoutException) {
                return true;
            }
            String msg = t.getMessage();
            if (msg != null && (msg.contains("Connection refused") || msg.contains("Connection reset")
                    || msg.contains("Failed to connect") || msg.contains("Connection timed out"))) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private static String key(String region, String account, String repoName) {
        return region + "::" + account + "::" + repoName;
    }

    private String effectiveAccount(String registryId) {
        if (registryId != null && !registryId.isBlank()) {
            return registryId;
        }
        return regionResolver.getAccountId();
    }

    private static String normalizePullThroughCachePrefix(String prefix) {
        if (prefix == null || prefix.isBlank() || "ROOT".equals(prefix)) {
            return prefix;
        }
        return prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
    }

    private static void validatePullThroughCachePrefix(String prefix, String fieldName) {
        if (prefix == null || prefix.length() < 2 || prefix.length() > MAX_PULL_THROUGH_CACHE_PREFIX_LENGTH
                || !PULL_THROUGH_CACHE_PREFIX.matcher(prefix).matches()) {
            throw new AwsException("InvalidParameterException",
                    fieldName + " must match the ECR pull through cache repository prefix pattern", 400);
        }
    }

    private static void validateRegistryId(String registryId) {
        if (!REGISTRY_ID.matcher(registryId).matches()) {
            throw new AwsException("InvalidParameterException",
                    "registryId must be a 12 digit account ID", 400);
        }
    }

    private static void validateUpstreamRegistryUrl(String upstreamRegistryUrl) {
        if (upstreamRegistryUrl == null || upstreamRegistryUrl.isBlank()) {
            throw new AwsException("InvalidParameterException",
                    "upstreamRegistryUrl must not be empty", 400);
        }
    }

    private static void validateCredentialArn(String credentialArn) {
        if (credentialArn == null) {
            return;
        }
        if (credentialArn.length() < 50 || credentialArn.length() > 612
                || !CREDENTIAL_ARN.matcher(credentialArn).matches()) {
            throw new AwsException("InvalidParameterException",
                    "credentialArn does not match the ECR pull through cache secret ARN pattern", 400);
        }
    }

    private static void validateCustomRoleArn(String customRoleArn) {
        if (customRoleArn != null && customRoleArn.length() > 2048) {
            throw new AwsException("InvalidParameterException",
                    "customRoleArn exceeds 2048 characters", 400);
        }
    }

    private static String resolveUpstreamRegistry(String upstreamRegistry, String upstreamRegistryUrl) {
        if (upstreamRegistry != null && !upstreamRegistry.isBlank()) {
            if (!UPSTREAM_REGISTRIES.contains(upstreamRegistry)) {
                throw unsupportedUpstreamRegistry(upstreamRegistry);
            }
            return upstreamRegistry;
        }
        String host = upstreamRegistryUrl.toLowerCase();
        if (host.startsWith("https://")) {
            host = host.substring("https://".length());
        } else if (host.startsWith("http://")) {
            host = host.substring("http://".length());
        }
        while (host.endsWith("/")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.equals("registry-1.docker.io")) {
            return "docker-hub";
        }
        if (host.equals("public.ecr.aws")) { // partition-literal: ECR Public's fixed registry host; the service exists only in the commercial partition
            return "ecr-public";
        }
        if (host.equals("quay.io")) {
            return "quay";
        }
        if (host.equals("registry.k8s.io")) {
            return "k8s";
        }
        if (host.equals("ghcr.io")) {
            return "github-container-registry";
        }
        if (host.equals("registry.gitlab.com")) {
            return "gitlab-container-registry";
        }
        if (host.endsWith(".azurecr.io")) {
            return "azure-container-registry";
        }
        if (host.equals("cgr.dev")) {
            return "chainguard";
        }
        if (ECR_UPSTREAM_HOST.matcher(host).matches()) {
            return "ecr";
        }
        throw unsupportedUpstreamRegistry(upstreamRegistryUrl);
    }

    private static AwsException unsupportedUpstreamRegistry(String value) {
        return new AwsException("UnsupportedUpstreamRegistryException",
                "The upstream registry '" + value + "' is not supported", 400);
    }

    private static AwsException pullThroughCacheRuleNotFound(String prefix, String account) {
        return new AwsException("PullThroughCacheRuleNotFoundException",
                "The pull through cache rule with repository prefix '" + prefix
                        + "' does not exist in registry '" + account + "'", 400);
    }

    private static void validateRepoName(String name) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterException",
                    "Repository name must not be empty", 400);
        }
        if (name.length() > MAX_REPO_NAME_LENGTH) {
            throw new AwsException("InvalidParameterException",
                    "Repository name exceeds " + MAX_REPO_NAME_LENGTH + " characters", 400);
        }
        if (!REPO_NAME.matcher(name).matches()) {
            throw new AwsException("InvalidParameterException",
                    "Repository name '" + name + "' does not match the required pattern", 400);
        }
    }

    private static AwsException notFound(String name, String account) {
        return new AwsException("RepositoryNotFoundException",
                "The repository with name '" + name + "' does not exist in the registry with id '"
                        + account + "'", 400);
    }
}
