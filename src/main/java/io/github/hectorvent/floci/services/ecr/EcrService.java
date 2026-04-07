package io.github.hectorvent.floci.services.ecr;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ecr.model.AuthorizationData;
import io.github.hectorvent.floci.services.ecr.model.ImageDetail;
import io.github.hectorvent.floci.services.ecr.model.ImageFailure;
import io.github.hectorvent.floci.services.ecr.model.ImageIdentifier;
import io.github.hectorvent.floci.services.ecr.model.ImageMetadata;
import io.github.hectorvent.floci.services.ecr.model.Image;
import io.github.hectorvent.floci.services.ecr.model.Repository;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.ecr.registry.RegistryHttpClient;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@ApplicationScoped
public class EcrService {

    private static final Logger LOG = Logger.getLogger(EcrService.class);
    private static final Pattern REPO_NAME = Pattern.compile(
            "(?:[a-z0-9]+(?:[._-][a-z0-9]+)*/)*[a-z0-9]+(?:[._-][a-z0-9]+)*");
    private static final int MAX_REPO_NAME_LENGTH = 256;

    private final StorageBackend<String, Repository> repoStore;
    private final StorageBackend<String, ImageMetadata> imageMetaStore;
    private final EcrRegistryManager registryManager;
    private final EmulatorConfig config;

    @Inject
    public EcrService(StorageFactory factory,
                      EcrRegistryManager registryManager,
                      EmulatorConfig config) {
        this(factory.create("ecr", "repositories.json",
                        new TypeReference<Map<String, Repository>>() {}),
                factory.create("ecr", "image-metadata.json",
                        new TypeReference<Map<String, ImageMetadata>>() {}),
                registryManager, config);
    }

    EcrService(StorageBackend<String, Repository> repoStore,
               StorageBackend<String, ImageMetadata> imageMetaStore,
               EcrRegistryManager registryManager,
               EmulatorConfig config) {
        this.repoStore = repoStore;
        this.imageMetaStore = imageMetaStore;
        this.registryManager = registryManager;
        this.config = config;
        this.registryManager.setReconcileHook(this::reconcileFromCatalog);
    }

    /**
     * Recreates {@link Repository} metadata entries for any internal-namespaced
     * repos found in the registry catalog that are missing from local storage.
     * Internal names are of the form {@code <account>/<region>/<repoName>}.
     */
    void reconcileFromCatalog(List<String> catalog) {
        if (catalog == null || catalog.isEmpty()) {
            return;
        }
        int recreated = 0;
        for (String internal : catalog) {
            String[] parts = internal.split("/", 3);
            if (parts.length < 3) {
                continue;
            }
            String account = parts[0];
            String region = parts[1];
            String repoName = parts[2];
            String key = key(region, account, repoName);
            if (repoStore.get(key).isPresent()) {
                continue;
            }
            Repository repo = new Repository();
            repo.setRepositoryName(repoName);
            repo.setRegistryId(account);
            repo.setRepositoryArn("arn:aws:ecr:" + region + ":" + account + ":repository/" + repoName);
            repo.setRepositoryUri(registryManager.getRepositoryUri(account, region, repoName));
            repo.setCreatedAt(Instant.now());
            repoStore.put(key, repo);
            recreated++;
        }
        if (recreated > 0) {
            LOG.infov("Reconciled {0} ECR repository metadata entries from registry catalog", recreated);
        }
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
        registryManager.ensureStarted();
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
        repo.setRepositoryArn("arn:aws:ecr:" + region + ":" + account + ":repository/" + repositoryName);
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
            return repoStore.scan(k -> k.startsWith(prefix));
        }

        List<Repository> out = new ArrayList<>();
        for (String name : repositoryNames) {
            String key = key(region, account, name);
            Repository repo = repoStore.get(key).orElseThrow(() -> notFound(name, account));
            out.add(repo);
        }
        return out;
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

        // Check whether the registry has any tagged images for this repo. If
        // ensureStarted() can't talk to docker (no daemon), assume the repo is
        // empty — this allows control-plane unit tests to delete without docker.
        List<String> tags = listTagsBestEffort(account, region, repositoryName);
        if (!tags.isEmpty() && !force) {
            throw new AwsException("RepositoryNotEmptyException",
                    "The repository with name '" + repositoryName
                            + "' in registry with id '" + account + "' cannot be deleted because it still contains images",
                    400);
        }

        if (force && !tags.isEmpty()) {
            // Phase 5 will issue real DELETE /v2/<name>/manifests/<digest> calls.
            LOG.infov("Force-deleting ECR repository {0} containing {1} tag(s) (manifest deletion deferred)",
                    repositoryName, tags.size());
        }

        repoStore.delete(key);
        // Drop cached image metadata for this repo.
        String metaPrefix = key + "::";
        for (ImageMetadata meta : imageMetaStore.scan(k -> k.startsWith(metaPrefix))) {
            imageMetaStore.delete(metaPrefix + meta.getDigest());
        }
        LOG.infov("Deleted ECR repository {0}/{1}/{2}", region, account, repositoryName);
        return repo;
    }

    // ============================================================
    // GetAuthorizationToken
    // ============================================================

    public AuthorizationData getAuthorizationToken() {
        registryManager.ensureStarted();
        String token = Base64.getEncoder()
                .encodeToString("AWS:floci".getBytes(StandardCharsets.UTF_8));
        Instant expires = Instant.now().plusSeconds(12 * 60 * 60);
        String proxy = registryManager.getProxyEndpoint();
        return new AuthorizationData(token, expires, proxy);
    }

    // ============================================================
    // ListImages / DescribeImages / BatchGetImage / BatchDeleteImage
    // ============================================================

    public List<ImageIdentifier> listImages(String repositoryName, String registryId, String region) {
        Repository repo = requireRepo(repositoryName, registryId, region);
        registryManager.ensureStarted();
        String internal = registryManager.internalRepoName(repo.getRegistryId(), region, repositoryName);
        try {
            RegistryHttpClient http = registryManager.httpClient();
            List<String> tags = http.listTags(internal);
            List<ImageIdentifier> out = new ArrayList<>();
            for (String tag : tags) {
                String digest = http.headManifestDigest(internal, tag, null);
                out.add(new ImageIdentifier(tag, digest));
            }
            return out;
        } catch (Exception e) {
            LOG.warnv("ListImages registry query failed for {0}: {1}", repositoryName, e.getMessage());
            return List.of();
        }
    }

    public DescribeImagesResult describeImages(String repositoryName,
                                                List<ImageIdentifier> requested,
                                                String registryId,
                                                String region) {
        Repository repo = requireRepo(repositoryName, registryId, region);
        registryManager.ensureStarted();
        String internal = registryManager.internalRepoName(repo.getRegistryId(), region, repositoryName);
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
        registryManager.ensureStarted();
        String internal = registryManager.internalRepoName(repo.getRegistryId(), region, repositoryName);
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
        registryManager.ensureStarted();
        String internal = registryManager.internalRepoName(repo.getRegistryId(), region, repositoryName);
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
    // Tag mutability + resource tags + policies (metadata round-trip)
    // ============================================================

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
        return repoStore.get(key(region, account, name)).orElseThrow(() -> notFound(name, account));
    }

    private static String imageMetaKey(String region, String account, String repoName, String digest) {
        return key(region, account, repoName) + "::" + digest;
    }


    private List<String> listTagsBestEffort(String account, String region, String repoName) {
        try {
            return registryManager.httpClient()
                    .listTags(registryManager.internalRepoName(account, region, repoName));
        } catch (Exception e) {
            LOG.debugv("Could not list tags for {0} (registry not available): {1}", repoName, e.getMessage());
            return List.of();
        }
    }

    private static String key(String region, String account, String repoName) {
        return region + "::" + account + "::" + repoName;
    }

    private String effectiveAccount(String registryId) {
        if (registryId != null && !registryId.isBlank()) {
            return registryId;
        }
        return config.defaultAccountId();
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
