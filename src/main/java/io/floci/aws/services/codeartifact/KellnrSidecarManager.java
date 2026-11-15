package io.floci.aws.services.codeartifact;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.ContainerTeardown;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.PerKeyContainerPool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Starts and stops one Kellnr container per CodeArtifact repository backing the {@code cargo}
 * format, the same per-repository shape as Verdaccio (npm), pypiserver (pypi), and BaGetter
 * (nuget) rather than Reposilite's one shared instance. The HTTP protocol lives in {@link
 * KellnrSidecarClient}. Implements {@link ContainerTeardown} so reset, nuke, and shutdown stop
 * every container this manager started.
 *
 * <p>Unlike BaGetter, Kellnr has no "leave it fully open" configuration: confirmed directly
 * against a live container, {@code KELLNR_REGISTRY__AUTH_REQUIRED=false} (already the image's own
 * documented default) only relaxes anonymous download, never publish, which 401s without a valid
 * token regardless of that setting. So this container is left at its real default auth posture,
 * and {@link KellnrSidecarClient} itself holds the one internal admin credential every publish
 * needs (see its own javadoc); real CodeArtifact authorization still happens exactly once, at
 * Floci's own proxy layer, before a request ever reaches this container, the same reasoning every
 * other container-backed format's own manager already documents.
 *
 * <p>The admin account's own password is a fresh, random value minted per container at creation
 * (via {@code KELLNR_SETUP__ADMIN_PWD}, confirmed live to override Kellnr's own shipped
 * {@code admin}/{@code admin} default), not the shipped default itself: every Kellnr image shares
 * that same default, so anything else already able to reach this container on the configured
 * Docker network (the same cross-container reachability every sidecar-backed format's own manager
 * documents, see {@code docs/services/codeartifact.md}) could otherwise log in as this container's
 * own admin using nothing but public knowledge of Kellnr's defaults, without ever needing the
 * internal token {@link KellnrSidecarClient} mints under it.
 */
@ApplicationScoped
public class KellnrSidecarManager implements ContainerTeardown {

    private static final Logger LOG = Logger.getLogger(KellnrSidecarManager.class);
    // Kellnr's own shipped default (confirmed directly against a live container: it listens on
    // 8000 with no port configuration set at all).
    private static final int KELLNR_PORT = 8000;
    // Kellnr's static SPA shell answers 200 on "/" even before its backend API is actually ready
    // to serve requests, so it is not a safe readiness signal; this config endpoint only answers
    // once the real API is up, confirmed live.
    private static final String HEALTH_PATH = "/api/v1/crates/config.json";
    private static final SecureRandom ADMIN_PASSWORD_RANDOM = new SecureRandom();

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final EmulatorConfig config;
    private final PerKeyContainerPool pool;
    private final SidecarRepositoryLease lease;
    // One random admin password per repository's own container, regenerated every time
    // startContainer() runs for that key (including an internal pool restart of an unhealthy
    // container under the same key): KellnrSidecarClient reads this fresh on every admin login
    // rather than caching it, so it always matches whichever container is actually running now.
    private final ConcurrentHashMap<String, String> adminPasswords = new ConcurrentHashMap<>();

    @Inject
    public KellnrSidecarManager(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                                EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.config = config;
        this.pool = new PerKeyContainerPool(lifecycleManager, HEALTH_PATH);
        this.lease = new SidecarRepositoryLease("Kellnr", pool);
    }

    /**
     * Base URL of a ready Kellnr instance for this cargo repository, starting its container if
     * this is the first use. {@code publicUrl} is unused: Kellnr's own self-reported {@code
     * config.json} (and anything else that could embed a self-referential URL) is never proxied
     * verbatim by a future controller, which is expected to author that response itself, the same
     * way {@code CodeArtifactNuGetController} already authors its own service index rather than
     * proxying BaGetter's.
     */
    public String ensureReady(String cargoRepositoryId, String publicUrl) {
        return pool.ensureReady(cargoRepositoryId, () -> startContainer(cargoRepositoryId));
    }

    /** Stops and removes the container for one cargo repository, if one was ever started. */
    public void release(String cargoRepositoryId) {
        lease.release(cargoRepositoryId);
        adminPasswords.remove(cargoRepositoryId);
    }

    /**
     * This cargo repository's own container's current admin password, read fresh on every call
     * (never cached by the caller) so it always matches whichever container is actually running,
     * including one silently restarted under the same key by {@link PerKeyContainerPool
     * #ensureReady}. Only meaningful after {@link #ensureReady} has started this repository's
     * container at least once.
     */
    String adminPassword(String cargoRepositoryId) {
        String password = adminPasswords.get(cargoRepositoryId);
        if (password == null) {
            throw new IllegalStateException("No Kellnr admin password minted yet for cargo repository "
                    + cargoRepositoryId);
        }
        return password;
    }

    /**
     * For a test that seeds a container directly into the pool without going through this class's
     * own {@link #ensureReady}/{@link #startContainer}: those real paths always mint and store a
     * password themselves, so a test standing in for them needs its own way to make one visible to
     * {@link #adminPassword} too, without reaching for reflection the way its own seeding of
     * {@code pool}'s containers map already does for that unrelated, pre-existing convention.
     */
    void seedAdminPasswordForTest(String cargoRepositoryId, String password) {
        adminPasswords.put(cargoRepositoryId, password);
    }

    public void registerRepository(String cargoRepositoryId) {
        lease.registerRepository(cargoRepositoryId);
    }

    public void beginUse(String cargoRepositoryId) {
        lease.beginUse(cargoRepositoryId);
    }

    public void endUse(String cargoRepositoryId) {
        lease.endUse(cargoRepositoryId);
    }

    /**
     * Whether this cargo repository's own Kellnr container is already running, without starting
     * one for a repository that has never used cargo at all. Every CodeArtifact repository gets a
     * cargo sidecar id at creation regardless of whether it is ever actually used through cargo,
     * so a caller that only wants to ask an already-running container what it holds must check
     * this first rather than provision a brand-new container just to find it has nothing, the same
     * reasoning {@code BagetterSidecarManager#isRunning} already documents.
     */
    public boolean isRunning(String cargoRepositoryId) {
        return pool.isTracked(cargoRepositoryId);
    }

    private PerKeyContainerPool.StartedContainer startContainer(String cargoRepositoryId) {
        String image = config.services().codeartifact().cargoImage();
        String containerName = ContainerStorageHelper.dockerName(config, "floci-kellnr-" + cargoRepositoryId);
        lifecycleManager.removeIfExists(containerName);

        // Set before the container is created, not after: KellnrSidecarClient's own adminLogin
        // reads this map directly, so the new password must already be visible to it the moment
        // this container could first be reached, never a window where the map still holds a
        // previous container's own now-wrong password.
        String adminPassword = generateAdminPassword();
        adminPasswords.put(cargoRepositoryId, adminPassword);

        // Loopback-only: nothing inside this container should ever be reachable except through
        // Floci's own proxy layer, the same reasoning Verdaccio/pypiserver/BaGetter's own managers
        // already document. KELLNR_ORIGIN__HOSTNAME is required for Kellnr's own self-reported
        // base URLs (confirmed live); storage is left at the image's own SQLite/local-filesystem
        // default, matching every other sidecar's own "keep it simple, ephemeral" choice.
        // KELLNR_SETUP__ADMIN_PWD overrides Kellnr's own shipped admin/admin default (confirmed
        // live), see this class's own javadoc for why that default is never left in place.
        ContainerSpec spec = containerBuilder.newContainer(image)
                .withName(containerName)
                .withEnv("KELLNR_ORIGIN__HOSTNAME", "localhost")
                .withEnv("KELLNR_SETUP__ADMIN_PWD", adminPassword)
                .withLoopbackPortBinding(KELLNR_PORT, 0)
                .withDockerNetwork(config.services().dockerNetwork())
                .withEmbeddedDns()
                .withLogRotation()
                .build();
        String containerId = lifecycleManager.create(spec);
        try {
            ContainerInfo info = lifecycleManager.startCreated(containerId, spec);
            EndpointInfo endpoint = info.getEndpoint(KELLNR_PORT);
            String url = "http://" + endpoint;
            LOG.infov("Kellnr sidecar for cargo repository {0} is ready at {1}", cargoRepositoryId, url);
            return new PerKeyContainerPool.StartedContainer(containerId, url);
        } catch (RuntimeException e) {
            lifecycleManager.stopAndRemove(containerId, null);
            throw e;
        }
    }

    /** A fresh 256-bit random value, hex-encoded so it is always safe as a Kellnr config env var
     * and a JSON login body's own string value with no escaping concerns either way. */
    private static String generateAdminPassword() {
        byte[] bytes = new byte[32];
        ADMIN_PASSWORD_RANDOM.nextBytes(bytes);
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    @Override
    public void stopManagedContainers() {
        lease.stopManagedContainers();
    }
}
