package io.github.hectorvent.floci.services.lambda.launcher;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.exception.InternalServerErrorException;
import com.github.dockerjava.api.model.AuthConfig;
import com.github.dockerjava.api.model.Info;
import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Ensures each Docker image is pulled only once per platform.
 * Thread-safe using ConcurrentHashMap for double-checked locking per image.
 */
@ApplicationScoped
public class ImageCacheService {

    private static final Logger LOG = Logger.getLogger(ImageCacheService.class);

    static final int MAX_PULL_ATTEMPTS = 3;
    static final long INITIAL_BACKOFF_MS = 500L;

    private final DockerClient dockerClient;
    private final List<EmulatorConfig.DockerConfig.RegistryCredential> registryCredentials;
    private final Map<ImageKey, String> resolvedImages = new ConcurrentHashMap<>();
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
                resolvedImage = resolvedImageReference(imageUri, requestedPlatform, localImage);
                resolvedImages.put(imageKey, resolvedImage);
                LOG.infov("Image already present locally, skipping pull: {0}", imageUri);
                return resolvedImage;
            }
            LOG.infov("Pulling image: {0}", imageUri);
            try {
                runWithRetry(imageUri, MAX_PULL_ATTEMPTS, INITIAL_BACKOFF_MS, () -> {
                    PullImageCmd pullImage = dockerClient.pullImageCmd(imageUri)
                            .withAuthConfig(resolveAuth(imageUri));
                    if (explicitPlatform) {
                        pullImage.withPlatform(requestedPlatform);
                    }
                    pullImage.exec(new PullImageResultCallback())
                            .awaitCompletion(5, TimeUnit.MINUTES);
                });
                resolvedImage = resolvedImageReference(imageUri, requestedPlatform,
                        inspectLocalImage(imageUri));
                resolvedImages.put(imageKey, resolvedImage);
                LOG.infov("Image pulled successfully: {0}", imageUri);
                return resolvedImage;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while pulling image: " + imageUri, e);
            }
        }
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

    private static String resolvedImageReference(String imageUri, String platform,
                                                  InspectImageResponse image) {
        if (!matchesPlatform(image, platform)) {
            throw new DockerClientException(
                    "Docker image does not match requested platform " + platform + ": " + imageUri);
        }
        String imageId = image.getId();
        if (imageId == null || imageId.isBlank()) {
            throw new DockerClientException("Docker did not report an image ID for: " + imageUri);
        }
        return imageId;
    }

    private record ImageKey(String imageUri, String platform) {}

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
