package io.github.hectorvent.floci.services.lambda.launcher;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.exception.InternalServerErrorException;
import com.github.dockerjava.api.model.AuthConfig;
import com.github.dockerjava.api.model.Info;
import com.github.dockerjava.api.model.PullResponseItem;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.EmulatorConfig.EcsServiceConfig.ImagePullBehavior;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Ensures each Docker image is pulled only once per platform, except for launches that ask
 * for the ECS agent's pull behaviour through {@link #resolveForLaunch}.
 * Thread-safe using ConcurrentHashMap for double-checked locking per image.
 */
@ApplicationScoped
public class ImageCacheService {

    private static final Logger LOG = Logger.getLogger(ImageCacheService.class);

    static final int MAX_PULL_ATTEMPTS = 3;
    static final long INITIAL_BACKOFF_MS = 500L;
    private static final List<String> DOCKER_HUB_PREFIXES = List.of("docker.io/", "index.docker.io/", "library/");
    private static final Pattern IMAGE_ID = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final String DIGEST_STATUS = "Digest: ";

    private final DockerClient dockerClient;
    private final List<EmulatorConfig.DockerConfig.RegistryCredential> registryCredentials;
    private final Map<ImageKey, String> resolvedImages = new ConcurrentHashMap<>();
    private final Set<ImageKey> pulledImages = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private volatile String daemonPlatform;

    @Inject
    public ImageCacheService(DockerClient dockerClient, EmulatorConfig config) {
        this.dockerClient = dockerClient;
        this.registryCredentials = config.docker().registryCredentials();
    }

    public String ensureImageExists(String imageUri) {
        return ensureImageExists(imageUri, null);
    }

    public String ensureImageExists(String imageUri, String platform) {
        if (isImageId(imageUri)) {
            return existingImageId(imageUri);
        }
        boolean explicitPlatform = platform != null && !platform.isBlank();
        String requestedPlatform = explicitPlatform ? platform.trim() : daemonPlatform();
        ImageKey imageKey = new ImageKey(imageUri, requestedPlatform);
        String resolvedImage = validResolvedImage(imageKey);
        if (resolvedImage != null) {
            return resolvedImage;
        }
        Object lock = locks.computeIfAbsent(imageUri, k -> new Object());
        synchronized (lock) {
            resolvedImage = validResolvedImage(imageKey);
            if (resolvedImage != null) {
                return resolvedImage;
            }
            InspectImageResponse localImage = inspectLocalImage(imageUri);
            if (matchesPlatform(localImage, requestedPlatform)) {
                resolvedImage = resolvedImageReference(imageUri, localImage);
                resolvedImages.put(imageKey, resolvedImage);
                LOG.infov("Image already present locally, skipping pull: {0}", imageUri);
                return resolvedImage;
            }
            resolvedImage = resolvedImageReference(imageUri,
                    pull(imageKey, explicitPlatform ? requestedPlatform : null).image());
            resolvedImages.put(imageKey, resolvedImage);
            return resolvedImage;
        }
    }

    /**
     * Resolves the image a container launch runs, pulling it as the ECS agent does under
     * {@code ECS_IMAGE_PULL_BEHAVIOR}.
     *
     * <p>Unlike {@link #ensureImageExists}, this never reuses the id a reference named the first
     * time it was seen: the local tag is read again on every call, so a tag moved by a pull (this
     * one or anyone else's) is what the next launch runs. The launch creates its container from the
     * returned image id rather than from the reference, so a pull by an overlapping launch that
     * moves the tag again cannot change what this one runs.
     */
    public LaunchImage resolveForLaunch(String imageUri, ImagePullBehavior behavior) {
        if (isImageId(imageUri)) {
            return new LaunchImage(existingImageId(imageUri), null);
        }
        ImageKey imageKey = new ImageKey(imageUri, daemonPlatform());
        Object lock = locks.computeIfAbsent(imageUri, k -> new Object());
        synchronized (lock) {
            LocalImage image = switch (behavior) {
                case DEFAULT -> pullOrUseCached(imageKey);
                case ALWAYS -> pull(imageKey, null);
                case ONCE -> pulledImages.contains(imageKey) ? cachedOrPull(imageKey) : pull(imageKey, null);
                case PREFER_CACHED -> cachedOrPull(imageKey);
            };
            String imageId = resolvedImageReference(imageUri, image.image());
            resolvedImages.put(imageKey, imageId);
            String digest = image.pulledDigest() != null && imageUri.indexOf('@') < 0
                    ? image.pulledDigest()
                    : manifestDigest(imageUri, image.image()).orElse(null);
            return new LaunchImage(imageId, digest);
        }
    }

    /**
     * The image a launch resolved a reference to.
     *
     * @param imageId the immutable id of the image the launch runs
     * @param manifestDigest the image's manifest digest in the repository it was named by, null when
     *        the image was never pulled from or pushed to that repository (a locally built image)
     */
    public record LaunchImage(String imageId, String manifestDigest) {}

    /** Whether a reference is an image id, which names one immutable image and never needs a pull. */
    public static boolean isImageId(String reference) {
        return reference != null && IMAGE_ID.matcher(reference).matches();
    }

    private String existingImageId(String imageId) {
        InspectImageResponse image = inspectLocalImage(imageId);
        if (image == null) {
            throw new DockerClientException("Image no longer exists: " + imageId);
        }
        return resolvedImageReference(imageId, image);
    }

    private LocalImage pullOrUseCached(ImageKey imageKey) {
        try {
            return pull(imageKey, null);
        } catch (RuntimeException e) {
            InspectImageResponse cached = Thread.currentThread().isInterrupted() ? null : cachedImage(imageKey);
            if (cached == null) {
                throw e;
            }
            LOG.warnv("Could not pull image {0}, using the cached image {1}: {2}",
                    imageKey.imageUri(), cached.getId(), e.getMessage());
            return new LocalImage(cached, null);
        }
    }

    private LocalImage cachedOrPull(ImageKey imageKey) {
        InspectImageResponse cached = cachedImage(imageKey);
        return cached != null ? new LocalImage(cached, null) : pull(imageKey, null);
    }

    private InspectImageResponse cachedImage(ImageKey imageKey) {
        InspectImageResponse local = inspectLocalImage(imageKey.imageUri());
        return matchesPlatform(local, imageKey.platform()) ? local : null;
    }

    /**
     * Pulls the image and inspects what the reference names afterwards.
     *
     * @param platform the platform to ask the registry for, or null for the daemon's own
     */
    private LocalImage pull(ImageKey imageKey, String platform) {
        String imageUri = imageKey.imageUri();
        LOG.infov("Pulling image: {0}", imageUri);
        AtomicReference<ManifestDigestCallback> lastAttempt = new AtomicReference<>();
        try {
            runWithRetry(imageUri, MAX_PULL_ATTEMPTS, INITIAL_BACKOFF_MS, () -> {
                PullImageCmd pullImage = dockerClient.pullImageCmd(imageUri)
                        .withAuthConfig(resolveAuth(imageUri));
                if (platform != null) {
                    pullImage.withPlatform(platform);
                }
                ManifestDigestCallback callback = new ManifestDigestCallback();
                lastAttempt.set(callback);
                pullImage.<PullImageResultCallback>exec(callback)
                        .awaitCompletion(5, TimeUnit.MINUTES);
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while pulling image: " + imageUri, e);
        }
        InspectImageResponse pulled = inspectLocalImage(imageUri);
        pulledImages.add(imageKey);
        LOG.infov("Image pulled successfully: {0}", imageUri);
        return new LocalImage(pulled, lastAttempt.get().digest);
    }

    /**
     * The digest of the manifest the reference resolved to, from the repository it names. A
     * reference pinned by digest is its own answer; otherwise it is the image's repo digest for
     * that repository, which Docker records when it pulls or pushes the image. An image can hold
     * several digests for one repository: on the classic image store, manifests that share one
     * image config; on the containerd store, an index and its platform manifest. The containerd
     * store's image id is the digest of the manifest the tag names, so a digest equal to the id
     * is the answer there. Otherwise nothing local says which one the tag names, and a launch that
     * did not pull reports none rather than a digest the tag may no longer name.
     */
    static Optional<String> manifestDigest(String imageUri, InspectImageResponse image) {
        int at = imageUri.indexOf('@');
        if (at >= 0) {
            return Optional.of(imageUri.substring(at + 1));
        }
        List<String> repoDigests = image.getRepoDigests();
        if (repoDigests == null) {
            return Optional.empty();
        }
        String repository = normalizedRepository(imageUri);
        Set<String> digests = new LinkedHashSet<>();
        for (String repoDigest : repoDigests) {
            int separator = repoDigest.indexOf('@');
            if (separator > 0 && normalizedRepository(repoDigest.substring(0, separator)).equals(repository)) {
                digests.add(repoDigest.substring(separator + 1));
            }
        }
        if (digests.size() > 1) {
            if (digests.contains(image.getId())) {
                return Optional.of(image.getId());
            }
            LOG.debugv("Image {0} holds several digests for {1}, reporting none: {2}",
                    image.getId(), imageUri, digests);
            return Optional.empty();
        }
        return digests.stream().findFirst();
    }

    /** The repository a reference names, without its tag and with Docker Hub's implied prefixes. */
    private static String normalizedRepository(String reference) {
        String repository = reference;
        int lastSlash = repository.lastIndexOf('/');
        int tagSeparator = repository.lastIndexOf(':');
        if (tagSeparator > lastSlash) {
            repository = repository.substring(0, tagSeparator);
        }
        for (String prefix : DOCKER_HUB_PREFIXES) {
            if (repository.startsWith(prefix)) {
                repository = repository.substring(prefix.length());
            }
        }
        return repository;
    }

    /**
     * Runs the given pull attempt, retrying on transient registry failures with exponential
     * backoff. A failure is considered transient when either:
     * <ul>
     *   <li>the docker daemon throws {@link InternalServerErrorException} directly (HTTP 500,
     *       e.g. ECR Public's {@code "toomanyrequests: Rate exceeded"}), or</li>
     *   <li>{@link PullImageResultCallback#awaitCompletion} rewraps a daemon error as
     *       {@link DockerClientException} with a message starting with
     *       {@code "Could not pull image: "} (the async-callback path; same root cause, just
     *       a different exception class).</li>
     * </ul>
     * Permanent failures (auth, missing image, malformed request, or any other
     * {@code DockerClientException} not coming from the pull wrapper) keep surfacing their
     * original docker-java exception subclass on the first attempt and are not retried.
     */
    static void runWithRetry(String imageUri, int maxAttempts, long initialBackoffMs,
                             PullAttempt attempt) throws InterruptedException {
        long backoffMs = initialBackoffMs;
        for (int i = 1; i <= maxAttempts; i++) {
            try {
                attempt.run();
                return;
            } catch (RuntimeException e) {
                if (!isTransientPullFailure(e) || i == maxAttempts) {
                    throw e;
                }
                LOG.warnv(e, "Transient image pull failure for {0} (attempt {1}/{2}). "
                        + "Retrying in {3}ms.", imageUri, i, maxAttempts, backoffMs);
                Thread.sleep(backoffMs);
                backoffMs *= 2;
            }
        }
    }

    private static boolean isTransientPullFailure(RuntimeException e) {
        if (e instanceof InternalServerErrorException) {
            return true;
        }
        if (e instanceof DockerClientException && e.getMessage() != null
                && e.getMessage().startsWith("Could not pull image: ")) {
            return true;
        }
        return false;
    }

    @FunctionalInterface
    interface PullAttempt {
        void run() throws InterruptedException;
    }

    private InspectImageResponse inspectLocalImage(String imageUri) {
        try {
            return dockerClient.inspectImageCmd(imageUri).exec();
        } catch (com.github.dockerjava.api.exception.NotFoundException e) {
            return null;
        }
    }

    private String validResolvedImage(ImageKey imageKey) {
        String resolvedImage = resolvedImages.get(imageKey);
        if (resolvedImage == null) {
            return null;
        }
        if (inspectLocalImage(resolvedImage) != null) {
            return resolvedImage;
        }
        resolvedImages.remove(imageKey, resolvedImage);
        return null;
    }

    private String daemonPlatform() {
        String resolvedPlatform = daemonPlatform;
        if (resolvedPlatform != null) {
            return resolvedPlatform;
        }
        Info info = dockerClient.infoCmd().exec();
        String architecture = info.getArchitecture();
        if (architecture == null || architecture.isBlank()) {
            throw new DockerClientException("Docker did not report its architecture");
        }
        String os = info.getOsType();
        if (os == null || os.isBlank()) {
            os = "linux";
        }
        String normalizedArchitecture = switch (architecture.toLowerCase(Locale.ROOT)) {
            case "aarch64" -> "arm64";
            case "x86_64" -> "amd64";
            default -> architecture.toLowerCase(Locale.ROOT);
        };
        resolvedPlatform = os.toLowerCase(Locale.ROOT) + "/" + normalizedArchitecture;
        daemonPlatform = resolvedPlatform;
        return resolvedPlatform;
    }

    private static boolean matchesPlatform(InspectImageResponse image, String platform) {
        if (image == null) {
            return false;
        }
        if (platform == null) {
            return true;
        }
        String[] parts = platform.split("/", 2);
        return parts.length == 2
                && parts[0].equals(image.getOs())
                && parts[1].equals(image.getArch());
    }

    /**
     * Resolves the reference the container is created from. Inspecting the image cannot confirm
     * the platform on Docker 29, whose containerd image store is the default: for an image whose
     * platform is not the host's it answers with an empty Os and Architecture until another
     * variant of the same tag is present, and with the host's variant once one is, because the
     * tag and its id both name the index rather than the variant that will run. Neither answer
     * says anything about the variant the daemon selected. The platform is enforced by the daemon
     * itself at both points that matter, choosing the variant to pull and choosing the variant to
     * create the container from, so this only has to hand back an id.
     */
    private static String resolvedImageReference(String imageUri, InspectImageResponse image) {
        if (image == null) {
            throw new DockerClientException("Docker did not report the image: " + imageUri);
        }
        String imageId = image.getId();
        if (imageId == null || imageId.isBlank()) {
            throw new DockerClientException("Docker did not report an image ID for: " + imageUri);
        }
        return imageId;
    }

    private record ImageKey(String imageUri, String platform) {}

    /**
     * The image a reference names locally.
     *
     * @param pulledDigest the manifest digest the registry reported when this launch pulled the
     *        image, null when the image came from the cache
     */
    private record LocalImage(InspectImageResponse image, String pulledDigest) {}

    /** Records the manifest digest the daemon reports once a pull has resolved the reference. */
    private static final class ManifestDigestCallback extends PullImageResultCallback {
        private volatile String digest;

        @Override
        public void onNext(PullResponseItem item) {
            String status = item.getStatus();
            if (status != null && status.startsWith(DIGEST_STATUS)) {
                digest = status.substring(DIGEST_STATUS.length()).trim();
            }
            super.onNext(item);
        }
    }

    private AuthConfig resolveAuth(String imageUri) {
        String host = extractRegistryHost(imageUri);
        for (EmulatorConfig.DockerConfig.RegistryCredential cred : registryCredentials) {
            if (cred.server().equals(host)) {
                LOG.debugv("Using configured credentials for registry: {0}", host);
                return new AuthConfig()
                        .withUsername(cred.username())
                        .withPassword(cred.password())
                        .withRegistryAddress(cred.server());
            }
        }
        return new AuthConfig();
    }

    static String extractRegistryHost(String imageUri) {
        String firstSegment = imageUri.split("/")[0];
        return (firstSegment.contains(".") || firstSegment.contains(":")) ? firstSegment : "";
    }
}
