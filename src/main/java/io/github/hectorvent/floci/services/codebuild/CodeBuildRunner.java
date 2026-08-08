package io.github.hectorvent.floci.services.codebuild;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.TlsConfigSource;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.services.codebuild.BuildspecParser.ParsedArtifacts;
import io.github.hectorvent.floci.services.codebuild.BuildspecParser.ParsedBuildspec;
import io.github.hectorvent.floci.services.codebuild.model.Build;
import io.github.hectorvent.floci.services.codebuild.model.BuildPhase;
import io.github.hectorvent.floci.services.codebuild.model.Project;
import io.github.hectorvent.floci.services.codebuild.model.ProjectSource;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.secretsmanager.model.SecretVersion;
import io.github.hectorvent.floci.services.ssm.SsmService;
import io.github.hectorvent.floci.services.ssm.model.Parameter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.jboss.logging.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@ApplicationScoped
public class CodeBuildRunner implements ContainerTeardown {

    private static final Logger LOG = Logger.getLogger(CodeBuildRunner.class);

    private static final String PHASE_START_SENTINEL = "___FLOCI_PHASE_START___";
    private static final String PHASE_END_SENTINEL = "___FLOCI_PHASE_END___";

    // Unix file-type mask and S_IFLNK marker: a zip stores a symlink with these bits
    // in its unix mode and the link target as the entry's content. LZA's installer
    // zips with `zip -y`, so all of node_modules/.bin/* arrive as symlink entries.
    private static final int S_IFMT = 0xF000;
    private static final int S_IFLNK = 0xA000;
    private static final List<String> SHELL_PHASES = List.of("INSTALL", "PRE_BUILD", "BUILD", "POST_BUILD");
    static final String CONTAINER_CA_CERT_PATH = "/tmp/floci-ca.pem";

    // Wrapper for the bash driver: each command entry runs in its own child shell
    // that first restores the variable and cwd snapshot of the previous entry, sources
    // the entry, then snapshots again. declare -p re-declares variables with their
    // export flags intact; readonly variables (the r flag) are filtered out so the
    // restore never errors. The snapshot only runs if the entry's shell survives it,
    // so a failing entry keeps the last successful entry's state — like the real
    // CodeBuild agent, which never leaks set -e/-u/-x or exit across entries while
    // unexported variables and the working directory do persist.
    private static final String BASH_DRIVER_PRELUDE = """
            export ___FLOCI_DIR="/tmp/.floci-session-$$"
            mkdir -p "$___FLOCI_DIR"
            cat > "$___FLOCI_DIR/wrapper" <<'___FLOCI_WRAPPER_EOF___'
            [ -f "$___FLOCI_DIR/state" ] && . "$___FLOCI_DIR/state" >/dev/null 2>&1
            [ -f "$___FLOCI_DIR/cwd" ] && cd "$(cat "$___FLOCI_DIR/cwd")" 2>/dev/null
            :
            . "$___FLOCI_DIR/cmd"
            ___floci_entry_rc=$?
            set +e +u +x 2>/dev/null
            { declare -p | grep -Ev '^declare -[a-zA-Z]*r'; } > "$___FLOCI_DIR/state" 2>/dev/null
            pwd > "$___FLOCI_DIR/cwd"
            exit "$___floci_entry_rc"
            ___FLOCI_WRAPPER_EOF___
            """;

    // Trust prelude for transparent AWS endpoints: seeds a combined CA bundle with
    // Floci's staged certificate, appends the files referenced by any pre-existing
    // NODE_EXTRA_CA_CERTS / AWS_CA_BUNDLE (images that ship their own egress CAs
    // keep working — combined, never replaced), then exports both variables at the
    // bundle for the whole session. POSIX sh compatible so the bash driver and the
    // sh fallback share it; a no-op when the certificate was not staged.
    static String caBundlePrelude(String caCertPath) {
        return """
                if [ -r "%1$s" ]; then
                ___FLOCI_CA_BUNDLE="%1$s.bundle"
                cat "%1$s" > "$___FLOCI_CA_BUNDLE"
                for ___floci_extra_ca in "${NODE_EXTRA_CA_CERTS:-}" "${AWS_CA_BUNDLE:-}"; do
                if [ -n "$___floci_extra_ca" ] && [ -r "$___floci_extra_ca" ]; then
                cat "$___floci_extra_ca" >> "$___FLOCI_CA_BUNDLE"
                fi
                done
                export NODE_EXTRA_CA_CERTS="$___FLOCI_CA_BUNDLE"
                export AWS_CA_BUNDLE="$___FLOCI_CA_BUNDLE"
                fi
                """.formatted(caCertPath);
    }

    private final DockerClient dockerClient;
    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final S3Service s3Service;
    private final SsmService ssmService;
    private final SecretsManagerService secretsManagerService;
    private final EmulatorConfig config;
    private final ContainerDetector containerDetector;
    private final RegionResolver regionResolver;

    private final ConcurrentHashMap<String, String> runningContainers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> stopFlags = new ConcurrentHashMap<>();

    // Single shared gate bounding how many builds hold a staged workspace on disk at
    // once, sized from maxConcurrentBuilds. Null when unbounded — no permit is taken and
    // behaviour matches an unconstrained host. Resolved lazily and once so the config is
    // only read when a build actually runs.
    private Semaphore buildSlots;
    private boolean buildSlotsResolved;

    @Inject
    public CodeBuildRunner(DockerClient dockerClient,
                           ContainerBuilder containerBuilder,
                           ContainerLifecycleManager lifecycleManager,
                           ContainerLogStreamer logStreamer,
                           S3Service s3Service,
                           SsmService ssmService,
                           SecretsManagerService secretsManagerService,
                           EmulatorConfig config,
                           ContainerDetector containerDetector,
                           RegionResolver regionResolver) {
        this.dockerClient = dockerClient;
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.s3Service = s3Service;
        this.ssmService = ssmService;
        this.secretsManagerService = secretsManagerService;
        this.config = config;
        this.containerDetector = containerDetector;
        this.regionResolver = regionResolver;
    }

    /**
     * Stops the containers of all in-flight builds on emulator shutdown; without this
     * they outlive the process as orphans. Build state is transient, so there is no
     * persisted status to update.
     */
    @Override
    public void stopManagedContainers() {
        for (Map.Entry<String, String> entry : new LinkedHashMap<>(runningContainers).entrySet()) {
            if (runningContainers.remove(entry.getKey(), entry.getValue())) {
                try {
                    lifecycleManager.stopAndRemove(entry.getValue(), null);
                } catch (Exception e) {
                    LOG.warnv("Failed to stop CodeBuild container for build {0} on shutdown: {1}",
                            entry.getKey(), e.getMessage());
                }
            }
        }
    }

    public void startBuild(String region, Build build, Project project, String buildspecOverride) {
        AtomicBoolean stopFlag = new AtomicBoolean(false);
        stopFlags.put(build.getId(), stopFlag);
        Thread.ofVirtual().start(() -> {
            try {
                runBuild(region, build, project, buildspecOverride, stopFlag);
            } catch (Throwable t) {
                failBuildOnUncaughtError(build, t);
            }
        });
    }

    // A build thread must never die leaving its Build IN_PROGRESS: CodePipeline
    // actions poll buildComplete and would wedge forever. Errors (e.g. OutOfMemory)
    // bypass runBuild's own Exception handling, so this outer net records the
    // failure and releases whatever the aborted cleanup left behind.
    void failBuildOnUncaughtError(Build build, Throwable t) {
        LOG.error("Build thread for " + build.getId() + " died unexpectedly", t);
        stopFlags.remove(build.getId());
        String containerId = runningContainers.remove(build.getId());
        if (containerId != null) {
            try {
                lifecycleManager.stopAndRemove(containerId, null);
            } catch (Exception e) {
                LOG.warnv("Could not stop container {0} of failed build {1}: {2}",
                        containerId, build.getId(), e.getMessage());
            }
        }
        if (Boolean.TRUE.equals(build.getBuildComplete())) {
            return;
        }
        double now = System.currentTimeMillis() / 1000.0;
        if (build.getPhases() == null) {
            build.setPhases(new ArrayList<>());
        }
        BuildPhase phase = build.getPhases().stream()
                .filter(p -> "IN_PROGRESS".equals(p.getPhaseStatus()))
                .reduce((first, second) -> second)
                .orElse(null);
        if (phase == null) {
            phase = new BuildPhase();
            phase.setPhaseType("COMPLETED");
            phase.setStartTime(now);
            build.getPhases().add(phase);
        }
        phase.setPhaseStatus("FAILED");
        phase.setEndTime(now);
        phase.setDurationInSeconds(Math.round(now - (phase.getStartTime() != null ? phase.getStartTime() : now)));
        phase.setContexts(List.of(Map.of("statusCode", "FAULT_ERROR", "message", t.toString())));
        build.setEndTime(now);
        build.setBuildComplete(true);
        build.setBuildStatus("FAILED");
        build.setCurrentPhase("COMPLETED");
    }

    public void stopBuild(String buildId) {
        AtomicBoolean flag = stopFlags.get(buildId);
        if (flag != null) {
            flag.set(true);
        }
        String containerId = runningContainers.get(buildId);
        if (containerId != null) {
            try {
                dockerClient.stopContainerCmd(containerId).withTimeout(5).exec();
            } catch (Exception e) {
                LOG.debugv("Error stopping build container {0}: {1}", containerId, e.getMessage());
            }
        }
    }

    private void runBuild(String region, Build build, Project project,
                          String buildspecOverride, AtomicBoolean stopFlag) {
        try {
            withBuildSlot(() -> runBuildBody(region, build, project, buildspecOverride, stopFlag));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finishStopped(build);
        }
    }

    // Runs the build body while holding a concurrency permit when a cap is configured,
    // so no more than maxConcurrentBuilds workspaces are staged on disk at once. The
    // permit is acquired before the body starts (before its workspace is created) and
    // released once the body returns (after its workspace is deleted). Unbounded runs
    // the body directly with no permit — zero behaviour change from an unbounded host.
    // Interruption while waiting for a permit aborts before the body runs; the caller
    // stops the build.
    void withBuildSlot(Runnable body) throws InterruptedException {
        Semaphore slots = buildSlots();
        if (slots == null) {
            body.run();
            return;
        }
        slots.acquire();
        try {
            body.run();
        } finally {
            slots.release();
        }
    }

    synchronized Semaphore buildSlots() {
        if (!buildSlotsResolved) {
            buildSlots = config == null
                    ? null
                    : config.services().codebuild().maxConcurrentBuilds()
                            .filter(max -> max > 0)
                            .map(Semaphore::new)
                            .orElse(null);
            buildSlotsResolved = true;
        }
        return buildSlots;
    }

    private void runBuildBody(String region, Build build, Project project,
                          String buildspecOverride, AtomicBoolean stopFlag) {
        String buildId = build.getId();
        Path workspace = null;
        Path secondaryRoot = null;
        String containerId = null;
        Closeable logHandle = null;

        try {
            // SUBMITTED
            beginPhase(build, "SUBMITTED");
            completePhase(build, "SUBMITTED", "SUCCEEDED");

            if (stopFlag.get()) { finishStopped(build); return; }

            // QUEUED
            beginPhase(build, "QUEUED");
            completePhase(build, "QUEUED", "SUCCEEDED");

            if (stopFlag.get()) { finishStopped(build); return; }

            // PROVISIONING
            beginPhase(build, "PROVISIONING");
            build.setCurrentPhase("PROVISIONING");
            workspace = Files.createTempDirectory("floci-codebuild-");
            completePhase(build, "PROVISIONING", "SUCCEEDED");

            if (stopFlag.get()) { finishStopped(build); return; }

            // DOWNLOAD_SOURCE
            beginPhase(build, "DOWNLOAD_SOURCE");
            build.setCurrentPhase("DOWNLOAD_SOURCE");

            String buildspecContent;
            try {
                buildspecContent = resolveAndAcquireSource(region, build, project, buildspecOverride, workspace);
            } catch (AwsException e) {
                completePhaseWithError(build, "DOWNLOAD_SOURCE", "FAILED", e.getMessage());
                finishFailed(build);
                return;
            }

            Map<String, Path> secondarySources = Map.of();
            if (build.getSecondarySources() != null && !build.getSecondarySources().isEmpty()) {
                secondaryRoot = Files.createTempDirectory("floci-codebuild-secondary-");
                secondarySources = acquireSecondarySources(build, secondaryRoot);
            }

            ParsedBuildspec buildspec;
            try {
                buildspec = BuildspecParser.parse(buildspecContent);
            } catch (AwsException e) {
                completePhaseWithError(build, "DOWNLOAD_SOURCE", "FAILED", e.getMessage());
                finishFailed(build);
                return;
            }

            completePhase(build, "DOWNLOAD_SOURCE", "SUCCEEDED");

            if (stopFlag.get()) { finishStopped(build); return; }

            String logGroup = "/aws/codebuild/" + project.getName();
            String logStream = logStreamer.generateLogStreamName(buildId.replace(":", "/"));

            String image = resolveCuratedImage(
                    build.getEnvironment() != null && build.getEnvironment().getImage() != null
                            ? build.getEnvironment().getImage()
                            : project.getEnvironment().getImage());

            boolean privileged = (project.getEnvironment() != null
                    && Boolean.TRUE.equals(project.getEnvironment().getPrivilegedMode()))
                    || (build.getEnvironment() != null
                    && Boolean.TRUE.equals(build.getEnvironment().getPrivilegedMode()));

            Map<String, Object> logsMap = new java.util.HashMap<>();
            logsMap.put("groupName", logGroup);
            logsMap.put("streamName", logStream);
            logsMap.put("cloudWatchLogsArn", AwsArnUtils.Arn.of("logs", region, regionResolver.getAccountId(), "log-group:" + logGroup + ":log-stream:" + logStream).toString());
            build.setLogs(logsMap);

            List<String> envList;
            try {
                envList = buildEnvList(region, build, project, buildspec, logStream);
            } catch (AwsException e) {
                completePhaseWithError(build, "PROVISIONING", "FAILED", e.getMessage());
                finishFailed(build);
                return;
            }

            // Keep the container alive so each phase can be run with docker exec.
            // No bind mount needed — source and artifacts are transferred with docker cp.
            // The keep-alive must be the ENTRYPOINT, not the Cmd: real CodeBuild overrides
            // a custom image's ENTRYPOINT, and the curated images' dockerd-entrypoint.sh
            // exits non-zero when dockerd cannot start (e.g. nested in a rootless runtime),
            // which would kill the container and every in-flight phase exec.
            ContainerSpec spec = containerBuilder.newContainer(image)
                    .withEntrypoint(List.of("sh", "-c", "tail -f /dev/null"))
                    .withEnv(envList)
                    .withDockerNetwork(config.services().codebuild().dockerNetwork())
                    .withEmbeddedDns()
                    .withHostDockerInternalOnLinux()
                    .withPrivileged(privileged)
                    .withLogRotation()
                    .withLabels(ContainerStorageHelper.resourceIdentityLabels(
                            "codebuild", buildId, regionResolver.getAccountId(), region))
                    .build();

            ContainerLifecycleManager.ContainerInfo info = lifecycleManager.createAndStart(spec);
            containerId = info.containerId();
            runningContainers.put(buildId, containerId);

            logHandle = logStreamer.attach(containerId, logGroup, logStream, region, "codebuild:" + buildId);

            String containerSrcDir = "/codebuild/output/src/src";
            int timeoutMinutes = build.getTimeoutInMinutes() != null ? build.getTimeoutInMinutes() : 60;
            boolean buildFailed = false;

            // createAndStart returns as soon as the container's entrypoint process is
            // running, which can be before any startup command would finish. Create the
            // working directory with an explicit, awaited exec (run from "/", which always
            // exists) so the source copy, the phase execs that chdir into it, and the final
            // artifact copy can never race against container startup.
            StringBuilder workDirs = new StringBuilder("mkdir -p " + containerSrcDir);
            for (String identifier : secondarySources.keySet()) {
                workDirs.append(' ').append(secondarySourceDir(identifier));
            }
            PhaseResult workDirResult = runPhase(containerId, "/", envList,
                    List.of(workDirs.toString()), timeoutMinutes, stopFlag);
            if (workDirResult.stopped()) { finishStopped(build); return; }
            if (workDirResult.failed()) {
                throw new IllegalStateException("Could not create build working directory "
                        + containerSrcDir + ": " + workDirResult.errorMessage());
            }

            // Copy downloaded source files into the container (no-op for NO_SOURCE builds)
            copySourceToContainer(containerId, workspace, containerSrcDir);
            for (Map.Entry<String, Path> secondary : secondarySources.entrySet()) {
                copySourceToContainer(containerId, secondary.getValue(),
                        secondarySourceDir(secondary.getKey()));
            }

            if (spoofedEndpointTrustEnabled()) {
                stageCaCertificate(containerId);
            }

            PhaseResult bashProbe = runPhase(containerId, "/", envList,
                    List.of("command -v bash >/dev/null 2>&1"), timeoutMinutes, stopFlag);
            if (bashProbe.stopped()) { finishStopped(build); return; }
            boolean bashAvailable = !bashProbe.failed();

            // INSTALL through POST_BUILD run in one docker exec so shell variables
            // (exported or not) and the working directory carry across phases like on
            // real CodeBuild, while shell options set by one command entry never leak
            // into the next. Per-phase status, timing, contexts and skip decisions
            // come from sentinel lines the generated script emits.
            if (stopFlag.get()) { finishStopped(build); return; }
            PhaseResult phasesResult = runPhaseSession(containerId, containerSrcDir, envList,
                    buildspec, build, bashAvailable, timeoutMinutes, stopFlag);
            if (phasesResult.stopped()) { finishStopped(build); return; }
            if (phasesResult.failed()) {
                buildFailed = true;
            }

            // UPLOAD_ARTIFACTS
            beginPhase(build, "UPLOAD_ARTIFACTS");
            build.setCurrentPhase("UPLOAD_ARTIFACTS");
            try {
                // Pull the working directory out of the container into the local workspace,
                // then upload matching files to S3. This works regardless of whether Floci
                // itself is running inside a container.
                copyArtifactsFromContainer(containerId, containerSrcDir, workspace);
                uploadArtifacts(region, build, project, buildspec.artifacts(), workspace);
                completePhase(build, "UPLOAD_ARTIFACTS", "SUCCEEDED");
            } catch (Exception e) {
                LOG.warnv("Artifact upload failed for build {0}: {1}", buildId, e.getMessage());
                completePhaseWithError(build, "UPLOAD_ARTIFACTS", "FAILED", e.getMessage());
            }

            // FINALIZING
            beginPhase(build, "FINALIZING");
            build.setCurrentPhase("FINALIZING");
            completePhase(build, "FINALIZING", "SUCCEEDED");

            // COMPLETED
            beginPhase(build, "COMPLETED");
            build.setCurrentPhase("COMPLETED");
            completePhase(build, "COMPLETED", buildFailed ? "FAILED" : "SUCCEEDED");

            build.setEndTime(System.currentTimeMillis() / 1000.0);
            build.setBuildComplete(true);
            build.setBuildStatus(buildFailed ? "FAILED" : "SUCCEEDED");

        } catch (Exception e) {
            LOG.error("Unexpected error in build " + build.getId(), e);
            build.setEndTime(System.currentTimeMillis() / 1000.0);
            build.setBuildComplete(true);
            build.setBuildStatus("FAULT");
            build.setCurrentPhase("COMPLETED");
            if (build.getPhases() == null) {
                build.setPhases(new ArrayList<>());
            }
            boolean hasCompleted = build.getPhases().stream()
                    .anyMatch(p -> "COMPLETED".equals(p.getPhaseType()));
            if (!hasCompleted) {
                BuildPhase completedPhase = new BuildPhase();
                completedPhase.setPhaseType("COMPLETED");
                completedPhase.setPhaseStatus("FAILED");
                completedPhase.setStartTime(System.currentTimeMillis() / 1000.0);
                completedPhase.setEndTime(System.currentTimeMillis() / 1000.0);
                completedPhase.setDurationInSeconds(0L);
                if (e.getMessage() != null) {
                    completedPhase.setContexts(List.of(Map.of(
                            "statusCode", "FAULT_ERROR",
                            "message", e.getMessage()
                    )));
                }
                build.getPhases().add(completedPhase);
            }
        } finally {
            stopFlags.remove(buildId);
            if (containerId != null && runningContainers.remove(buildId, containerId)) {
                lifecycleManager.stopAndRemove(containerId, logHandle);
            } else if (logHandle != null) {
                try { logHandle.close(); } catch (Exception ignored) {}
            }
            if (workspace != null) {
                deleteDirectory(workspace);
            }
            if (secondaryRoot != null) {
                deleteDirectory(secondaryRoot);
            }
        }
    }

    private String resolveAndAcquireSource(String region, Build build, Project project,
                                           String buildspecOverride, Path workspace) throws IOException {
        ProjectSource effectiveSource = build.getSource() != null ? build.getSource() : project.getSource();
        String sourceType = effectiveSource != null ? effectiveSource.getType() : "NO_SOURCE";

        if ("S3".equals(sourceType) && effectiveSource.getLocation() != null) {
            String location = effectiveSource.getLocation();
            int slash = location.indexOf('/');
            if (slash > 0) {
                String bucket = location.substring(0, slash);
                String key = location.substring(slash + 1);
                try {
                    S3Object obj = s3Service.getObject(bucket, key);
                    if (obj != null && obj.getData() != null) {
                        extractZip(obj.getData(), workspace);
                    }
                } catch (Exception e) {
                    LOG.warnv("Could not acquire S3 source {0}: {1}", location, e.getMessage());
                }
            }
        }

        if (buildspecOverride != null && !buildspecOverride.isBlank()) {
            return buildspecOverride;
        }
        if (project.getSource() != null && project.getSource().getBuildspec() != null
                && !project.getSource().getBuildspec().isBlank()) {
            return project.getSource().getBuildspec();
        }
        Path yml = workspace.resolve("buildspec.yml");
        if (Files.exists(yml)) {
            return Files.readString(yml);
        }
        Path yaml = workspace.resolve("buildspec.yaml");
        if (Files.exists(yaml)) {
            return Files.readString(yaml);
        }
        throw new AwsException("InvalidInputException", "No buildspec found in source or request", 400);
    }

    /** Downloads and extracts each S3 secondary source into its own local directory,
     *  keyed by source identifier, mirroring the primary source's lenient S3 handling. */
    private Map<String, Path> acquireSecondarySources(Build build, Path root) throws IOException {
        Map<String, Path> dirs = new LinkedHashMap<>();
        for (ProjectSource secondary : build.getSecondarySources()) {
            String identifier = secondary.getSourceIdentifier();
            if (identifier == null || identifier.isBlank()) {
                continue;
            }
            Path dir = Files.createDirectories(root.resolve(identifier));
            dirs.put(identifier, dir);
            String location = secondary.getLocation();
            int slash = location != null ? location.indexOf('/') : -1;
            if (!"S3".equals(secondary.getType()) || slash <= 0) {
                LOG.warnv("Secondary source {0} of build {1} has no usable S3 location: {2}",
                        identifier, build.getId(), location);
                continue;
            }
            try {
                S3Object obj = s3Service.getObject(location.substring(0, slash), location.substring(slash + 1));
                if (obj != null && obj.getData() != null) {
                    extractZip(obj.getData(), dir);
                }
            } catch (Exception e) {
                LOG.warnv("Could not acquire secondary source {0} from {1}: {2}",
                        identifier, location, e.getMessage());
            }
        }
        return dirs;
    }

    static String secondarySourceDir(String sourceIdentifier) {
        return "/codebuild/output/src-" + sourceIdentifier + "/src";
    }

    List<String> buildEnvList(String region, Build build, Project project,
                              ParsedBuildspec buildspec, String logStream) {
        Map<String, String> env = new LinkedHashMap<>();

        env.put("CODEBUILD_BUILD_ID", build.getId());
        env.put("CODEBUILD_BUILD_ARN", build.getArn());
        env.put("CODEBUILD_BUILD_NUMBER", String.valueOf(build.getBuildNumber()));
        env.put("CODEBUILD_BUILD_IMAGE", build.getEnvironment() != null && build.getEnvironment().getImage() != null
                ? build.getEnvironment().getImage() : project.getEnvironment().getImage());
        env.put("CODEBUILD_BUILD_SUCCEEDING", "1");
        env.put("CODEBUILD_INITIATOR", "user");
        env.put("CODEBUILD_SRC_DIR", "/codebuild/output/src/src");
        if (build.getSecondarySources() != null) {
            for (ProjectSource secondary : build.getSecondarySources()) {
                String identifier = secondary.getSourceIdentifier();
                if (identifier != null && !identifier.isBlank()) {
                    env.put("CODEBUILD_SRC_DIR_" + identifier, secondarySourceDir(identifier));
                }
            }
        }
        env.put("CODEBUILD_LOG_PATH", logStream);
        env.put("AWS_DEFAULT_REGION", region);
        env.put("AWS_REGION", region);
        env.put("AWS_ACCESS_KEY_ID", "test");
        env.put("AWS_SECRET_ACCESS_KEY", "test");
        env.put("AWS_ENDPOINT_URL", resolveEndpointUrl());

        env.putAll(buildspec.envVariables());

        for (Map.Entry<String, String> e : buildspec.parameterStoreVars().entrySet()) {
            try {
                Parameter p = ssmService.getParameter(e.getValue(), region);
                env.put(e.getKey(), p.getValue());
            } catch (Exception ex) {
                LOG.debugv("Could not resolve SSM parameter {0}: {1}", e.getValue(), ex.getMessage());
            }
        }

        for (Map.Entry<String, String> e : buildspec.secretsManagerVars().entrySet()) {
            try {
                SecretVersion sv = secretsManagerService.getSecretValue(e.getValue(), null, null, region);
                env.put(e.getKey(), sv.getSecretString() != null ? sv.getSecretString() : "");
            } catch (Exception ex) {
                LOG.debugv("Could not resolve secret {0}: {1}", e.getValue(), ex.getMessage());
            }
        }

        if (project.getEnvironment() != null) {
            applyEnvironmentVariables(env, project.getEnvironment().getEnvironmentVariables(), region);
        }

        if (build.getEnvironment() != null) {
            applyEnvironmentVariables(env, build.getEnvironment().getEnvironmentVariables(), region);
        }

        List<String> result = new ArrayList<>();
        env.forEach((k, v) -> result.add(k + "=" + (v != null ? v : "")));
        return result;
    }

    /** Entries typed PARAMETER_STORE carry an SSM parameter name as their value; an
     *  unresolvable parameter fails the build like real CodeBuild's provisioning error. */
    private void applyEnvironmentVariables(Map<String, String> env,
                                           List<Map<String, String>> variables, String region) {
        if (variables == null) {
            return;
        }
        for (Map<String, String> v : variables) {
            String name = v.get("name");
            if (name == null) {
                continue;
            }
            String value = v.get("value") != null ? v.get("value") : "";
            if ("PARAMETER_STORE".equals(v.get("type"))) {
                try {
                    value = ssmService.getParameter(value, region).getValue();
                } catch (Exception e) {
                    throw new AwsException("InvalidInputException",
                            "Could not resolve environment variable " + name
                                    + " from SSM parameter " + value + ": " + e.getMessage(), 400);
                }
            }
            env.put(name, value);
        }
    }

    // With spoofed endpoints and TLS both enabled, the injected endpoint switches to
    // https on the same host and port: clients like the CDK toolkit pass a shared
    // https.Agent into their SDK httpOptions and Node rejects http: URLs on an
    // https.Agent. The TLS proxy serves both protocols on the public port, and the
    // staged CA bundle makes Node (NODE_EXTRA_CA_CERTS) and the AWS CLI
    // (AWS_CA_BUNDLE) trust the self-signed certificate.
    private String resolveEndpointUrl() {
        String scheme = spoofedEndpointTrustEnabled() ? "https://" : "http://";
        if (containerDetector.isRunningInContainer()) {
            String suffix = config.hostname().orElse(EmbeddedDnsServer.DEFAULT_SUFFIX);
            return scheme + suffix + ":" + config.port();
        } else {
            return scheme + "host.docker.internal:" + config.port();
        }
    }

    /**
     * Curated {@code aws/codebuild/*} names are not directly pullable: the Amazon Linux
     * family has public.ecr.aws mirrors, while the Ubuntu {@code standard} family is not
     * published to any public registry and runs the configured substitute — by default
     * the newest public Amazon Linux standard image for the host architecture.
     */
    String resolveCuratedImage(String image) {
        if (image == null || !image.startsWith("aws/codebuild/")) {
            return image;
        }
        String name = image.substring("aws/codebuild/".length());
        if (name.startsWith("amazonlinux")) {
            return "public.ecr.aws/codebuild/" + name;
        }
        String substitute = config.services().codebuild().curatedImageSubstitute()
                .orElseGet(() -> defaultCuratedSubstitute(System.getProperty("os.arch", "")));
        LOG.infov("Curated image {0} is not publicly distributed; running {1} instead",
                image, substitute);
        return substitute;
    }

    /** The x86_64 and aarch64 Amazon Linux standard lines version independently. */
    static String defaultCuratedSubstitute(String osArch) {
        boolean arm = osArch.contains("aarch64") || osArch.contains("arm");
        return arm
                ? "public.ecr.aws/codebuild/amazonlinux-aarch64-standard:4.0"
                : "public.ecr.aws/codebuild/amazonlinux-x86_64-standard:6.0";
    }

    private boolean spoofedEndpointTrustEnabled() {
        return config.dns().spoofAwsEndpoints() && config.tls().enabled();
    }

    // Stages Floci's TLS certificate PEM into the container so the phase-session
    // prelude can build a combined CA bundle before any buildspec phase runs —
    // builds then trust the spoofed https://*.amazonaws.com endpoints Floci serves.
    private void stageCaCertificate(String containerId) {
        Path certPath = config.tls().certPath()
                .filter(p -> !p.isBlank())
                .map(Path::of)
                .orElseGet(() -> TlsConfigSource.selfSignedCertPath(config.storage().persistentPath()));
        try {
            if (!Files.isReadable(certPath)) {
                LOG.warnv("Floci TLS certificate {0} is not readable; build containers will not trust spoofed AWS endpoints",
                        certPath);
                return;
            }
            byte[] pem = Files.readAllBytes(certPath);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (TarArchiveOutputStream tar = newTarStream(bos)) {
                TarArchiveEntry entry = new TarArchiveEntry(
                        CONTAINER_CA_CERT_PATH.substring(CONTAINER_CA_CERT_PATH.lastIndexOf('/') + 1));
                entry.setSize(pem.length);
                entry.setMode(0644);
                tar.putArchiveEntry(entry);
                tar.write(pem);
                tar.closeArchiveEntry();
            }
            dockerClient.copyArchiveToContainerCmd(containerId)
                    .withRemotePath("/tmp")
                    .withTarInputStream(new ByteArrayInputStream(bos.toByteArray()))
                    .exec();
        } catch (Exception e) {
            LOG.warnv("Could not stage Floci CA certificate into container {0}: {1}",
                    containerId, e.getMessage());
        }
    }

    // Copies files from the local workspace into the container's working directory.
    // Skips silently when the workspace is empty (e.g. NO_SOURCE builds). The tar is
    // staged on disk instead of in memory: a source tree can be several GB (LZA hands
    // its whole built monorepo, node_modules included, to the toolkit build) and an
    // in-memory tar of it exhausts the heap.
    private void copySourceToContainer(String containerId, Path sourceDir, String remotePath) {
        try {
            if (!Files.exists(sourceDir)) return;
            boolean hasFiles;
            try (var ls = Files.list(sourceDir)) {
                hasFiles = ls.findAny().isPresent();
            }
            if (!hasFiles) return;

            Path tarFile = Files.createTempFile("floci-codebuild-src-", ".tar");
            try {
                try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(tarFile))) {
                    createTarFromDir(sourceDir, out);
                }
                try (InputStream in = new BufferedInputStream(Files.newInputStream(tarFile))) {
                    dockerClient.copyArchiveToContainerCmd(containerId)
                            .withRemotePath(remotePath)
                            .withTarInputStream(in)
                            .exec();
                }
            } finally {
                Files.deleteIfExists(tarFile);
            }
        } catch (Exception e) {
            LOG.warnv("Could not copy source to container {0}: {1}", containerId, e.getMessage());
        }
    }

    // Pulls the container's working directory back into the local workspace so
    // uploadArtifacts can read the build outputs. Docker cp adds the last path
    // component as a top-level directory in the tar; we strip it on extraction.
    private void copyArtifactsFromContainer(String containerId, String containerPath, Path destDir)
            throws IOException {
        try (InputStream tarStream = dockerClient.copyArchiveFromContainerCmd(containerId, containerPath).exec();
             TarArchiveInputStream tar = new TarArchiveInputStream(tarStream)) {

            String stripPrefix = null;
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (!tar.canReadEntryData(entry)) continue;

                String name = entry.getName();

                if (stripPrefix == null) {
                    if (entry.isDirectory()) {
                        stripPrefix = name.endsWith("/") ? name : name + "/";
                        continue;
                    } else {
                        stripPrefix = "";
                    }
                }

                if (!stripPrefix.isEmpty() && name.startsWith(stripPrefix)) {
                    name = name.substring(stripPrefix.length());
                }
                if (name.isEmpty()) continue;

                Path target = destDir.resolve(name).normalize();
                if (!target.startsWith(destDir)) continue; // path traversal

                if (entry.isSymbolicLink()) {
                    String linkTarget = entry.getLinkName();
                    Path resolved = target.getParent().resolve(linkTarget).normalize();
                    if (!resolved.startsWith(destDir)) continue; // symlink escape
                    Files.createDirectories(target.getParent());
                    Files.deleteIfExists(target);
                    try {
                        Files.createSymbolicLink(target, Path.of(linkTarget));
                    } catch (UnsupportedOperationException e) {
                        LOG.debugv("Filesystem does not support symlinks; writing target of {0} as a regular file: {1}",
                                name, e.getMessage());
                        Files.write(target, linkTarget.getBytes(StandardCharsets.UTF_8));
                    }
                } else if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(tar, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (Exception e) {
            LOG.warnv("Could not copy artifacts from container {0}: {1}", containerId, e.getMessage());
        }
    }

    void createTarFromDir(Path dir, OutputStream out) throws IOException {
        try (TarArchiveOutputStream tar = newTarStream(out);
             var stream = Files.walk(dir)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                if (path.equals(dir)) continue;
                String entryName = dir.relativize(path).toString();
                if (Files.isSymbolicLink(path)) {
                    // Check symlinks before directories: isDirectory follows links and
                    // would dereference a link to a directory. Files.walk itself does not
                    // follow links, so it never recurses through one.
                    TarArchiveEntry entry = new TarArchiveEntry(entryName, TarConstants.LF_SYMLINK);
                    entry.setLinkName(Files.readSymbolicLink(path).toString());
                    tar.putArchiveEntry(entry);
                    tar.closeArchiveEntry();
                } else if (Files.isDirectory(path)) {
                    TarArchiveEntry entry = new TarArchiveEntry(entryName + "/");
                    tar.putArchiveEntry(entry);
                    tar.closeArchiveEntry();
                } else {
                    TarArchiveEntry entry = new TarArchiveEntry(entryName);
                    entry.setSize(Files.size(path));
                    int mode;
                    try {
                        mode = posixMode(Files.getPosixFilePermissions(path));
                    } catch (UnsupportedOperationException e) {
                        LOG.debugv("Filesystem does not support POSIX permissions for {0}: {1}",
                                path, e.getMessage());
                        mode = 0644;
                    }
                    entry.setMode(mode);
                    tar.putArchiveEntry(entry);
                    try (var fis = Files.newInputStream(path)) {
                        fis.transferTo(tar);
                    }
                    tar.closeArchiveEntry();
                }
            }
        }
    }

    private static TarArchiveOutputStream newTarStream(OutputStream out) {
        TarArchiveOutputStream tar = new TarArchiveOutputStream(out);
        tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
        tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_STAR);
        return tar;
    }

    private PhaseResult runPhase(String containerId, String workDir, List<String> env,
                                 List<String> commands, int timeoutMinutes, AtomicBoolean stopFlag) {
        if (commands.isEmpty()) {
            return PhaseResult.ofSuccess();
        }
        if (stopFlag.get()) {
            return PhaseResult.ofStopped();
        }

        String script = String.join("\n", commands);
        String[] cmd = {"sh", "-e", "-c", script};

        try {
            String execId = dockerClient.execCreateCmd(containerId)
                    .withCmd(cmd)
                    .withWorkingDir(workDir)
                    .withEnv(env)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec()
                    .getId();

            CountDownLatch latch = new CountDownLatch(1);
            ByteArrayOutputStream outputCapture = new ByteArrayOutputStream();

            dockerClient.execStartCmd(execId).exec(new ResultCallback.Adapter<Frame>() {
                @Override
                public void onNext(Frame frame) {
                    if (frame.getPayload() != null) {
                        try { outputCapture.write(frame.getPayload()); } catch (IOException ignored) {}
                    }
                }
                @Override
                public void onComplete() { latch.countDown(); }
                @Override
                public void onError(Throwable t) { latch.countDown(); }
            });

            boolean completed = latch.await(timeoutMinutes, TimeUnit.MINUTES);
            if (!completed) {
                return PhaseResult.ofFailure("Phase timed out after " + timeoutMinutes + " minutes");
            }
            if (stopFlag.get()) {
                return PhaseResult.ofStopped();
            }

            Long exitCode = dockerClient.inspectExecCmd(execId).exec().getExitCodeLong();
            if (exitCode != null && exitCode != 0) {
                String output = outputCapture.toString(StandardCharsets.UTF_8);
                String msg = "Exit code " + exitCode;
                if (!output.isBlank()) {
                    int start = Math.max(0, output.length() - 512);
                    msg += ": " + output.stripTrailing().substring(start);
                }
                return PhaseResult.ofFailure(msg);
            }
            return PhaseResult.ofSuccess();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PhaseResult.ofStopped();
        } catch (Exception e) {
            if (stopFlag.get()) {
                return PhaseResult.ofStopped();
            }
            return PhaseResult.ofFailure(e.getMessage());
        }
    }

    // One driver script for all buildspec phases, run in a single docker exec so
    // state persists across phases. With bash available (all curated CodeBuild
    // images), each command entry runs in its own child shell with a variable+cwd
    // snapshot restored between entries — shell options (set -e/-u/-x) and exit
    // never leak from one entry into the next, matching the real CodeBuild agent.
    // Without bash the entries run inline in the single driver shell (sh fallback).
    // A phase stops at its first failing entry; PRE_BUILD and BUILD are skipped once
    // an earlier phase failed while INSTALL and POST_BUILD always run, mirroring the
    // previous Java-side decisions. Sentinel lines report each phase's outcome.
    static String phaseSessionScript(List<String> installCommands, List<String> preBuildCommands,
                                     List<String> buildCommands, List<String> postBuildCommands,
                                     boolean bashAvailable, String caCertPath) {
        StringBuilder script = new StringBuilder();
        if (caCertPath != null && !caCertPath.isBlank()) {
            script.append(caBundlePrelude(caCertPath));
        }
        if (bashAvailable) {
            script.append(BASH_DRIVER_PRELUDE);
        }
        script.append("___floci_failed=0\n");
        appendPhaseScript(script, "INSTALL", installCommands, false, bashAvailable);
        appendPhaseScript(script, "PRE_BUILD", preBuildCommands, true, bashAvailable);
        appendPhaseScript(script, "BUILD", buildCommands, true, bashAvailable);
        appendPhaseScript(script, "POST_BUILD", postBuildCommands, false, bashAvailable);
        if (bashAvailable) {
            script.append("rm -rf \"$___FLOCI_DIR\"\n");
        }
        return script.toString();
    }

    private static void appendPhaseScript(StringBuilder script, String phase,
                                          List<String> commands, boolean skipAfterFailure,
                                          boolean isolatedEntries) {
        if (skipAfterFailure) {
            script.append("if [ \"$___floci_failed\" -eq 0 ]; then\n");
        }
        script.append("echo \"").append(PHASE_START_SENTINEL).append(' ').append(phase).append("\"\n");
        script.append("___floci_rc=0\n");
        for (String command : commands) {
            script.append("if [ \"$___floci_rc\" -eq 0 ]; then\n");
            if (isolatedEntries) {
                script.append("cat > \"$___FLOCI_DIR/cmd\" <<'___FLOCI_CMD_EOF___'\n")
                        .append(command).append('\n')
                        .append("___FLOCI_CMD_EOF___\n")
                        .append("bash \"$___FLOCI_DIR/wrapper\"\n");
            } else {
                script.append(command).append('\n');
            }
            script.append("___floci_rc=$?\n")
                    .append("fi\n");
        }
        script.append("echo \"").append(PHASE_END_SENTINEL).append(' ').append(phase)
                .append(" $___floci_rc\"\n");
        // Real CodeBuild flips CODEBUILD_BUILD_SUCCEEDING to 0 for every phase after
        // the first failure. The append outlives later snapshots because each failed
        // phase re-appends after the last entry's state rewrite, and successful
        // entries snapshot the restored 0 back out themselves.
        script.append("if [ \"$___floci_rc\" -ne 0 ]; then\n");
        script.append("___floci_failed=1\n");
        if (isolatedEntries) {
            script.append("echo 'declare -x CODEBUILD_BUILD_SUCCEEDING=\"0\"' >> \"$___FLOCI_DIR/state\"\n");
        } else {
            script.append("export CODEBUILD_BUILD_SUCCEEDING=0\n");
        }
        script.append("fi\n");
        if (skipAfterFailure) {
            script.append("else\n");
            script.append("echo \"").append(PHASE_END_SENTINEL).append(' ').append(phase)
                    .append(" SKIPPED\"\n");
            script.append("fi\n");
        }
    }

    private PhaseResult runPhaseSession(String containerId, String workDir, List<String> env,
                                        ParsedBuildspec buildspec, Build build, boolean bashAvailable,
                                        int timeoutMinutes, AtomicBoolean stopFlag) {
        String script = phaseSessionScript(buildspec.installCommands(), buildspec.preBuildCommands(),
                buildspec.buildCommands(), buildspec.postBuildCommands(), bashAvailable,
                spoofedEndpointTrustEnabled() ? CONTAINER_CA_CERT_PATH : null);
        String[] cmd = {bashAvailable ? "bash" : "sh", "-c", script};
        PhaseSession session = new PhaseSession(build);

        try {
            String execId = dockerClient.execCreateCmd(containerId)
                    .withCmd(cmd)
                    .withWorkingDir(workDir)
                    .withEnv(env)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec()
                    .getId();

            CountDownLatch latch = new CountDownLatch(1);

            dockerClient.execStartCmd(execId).exec(new ResultCallback.Adapter<Frame>() {
                @Override
                public void onNext(Frame frame) {
                    if (frame.getPayload() != null) {
                        session.accept(frame.getPayload());
                    }
                }
                @Override
                public void onComplete() { latch.countDown(); }
                @Override
                public void onError(Throwable t) { latch.countDown(); }
            });

            boolean completed = latch.await(timeoutMinutes, TimeUnit.MINUTES);
            if (stopFlag.get()) {
                return PhaseResult.ofStopped();
            }
            if (!completed) {
                String message = "Phase timed out after " + timeoutMinutes + " minutes";
                session.finish(null, message);
                return PhaseResult.ofFailure(message);
            }

            Long exitCode = dockerClient.inspectExecCmd(execId).exec().getExitCodeLong();
            boolean anyPhaseFailed = session.finish(exitCode, null);
            return anyPhaseFailed ? PhaseResult.ofFailure(null) : PhaseResult.ofSuccess();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PhaseResult.ofStopped();
        } catch (Exception e) {
            if (stopFlag.get()) {
                return PhaseResult.ofStopped();
            }
            session.finish(null, e.getMessage());
            return PhaseResult.ofFailure(e.getMessage());
        }
    }

    private void uploadArtifacts(String region, Build build, Project project,
                                 ParsedArtifacts artifacts, Path workspace) throws IOException {
        String type = artifacts.type();
        if ("NO_ARTIFACTS".equals(type) && project.getArtifacts() != null) {
            type = project.getArtifacts().getType();
        }
        if (type == null || "NO_ARTIFACTS".equals(type) || "CODEPIPELINE".equals(type)) {
            return;
        }
        if (!"S3".equals(type)) {
            return;
        }

        String packaging = artifacts.packaging();
        if ("ZIP".equals(packaging) && project.getArtifacts() != null
                && project.getArtifacts().getPackaging() != null) {
            packaging = project.getArtifacts().getPackaging();
        }

        String location = project.getArtifacts() != null ? project.getArtifacts().getLocation() : null;
        if (location == null || location.isBlank()) {
            return;
        }

        List<String> filePatterns = artifacts.files();
        if (filePatterns.isEmpty()) {
            return;
        }

        Path baseDir = workspace;
        if (artifacts.baseDirectory() != null) {
            baseDir = workspace.resolve(artifacts.baseDirectory());
        }

        List<Path> matchedFiles = collectFiles(baseDir, filePatterns);
        if (matchedFiles.isEmpty()) {
            LOG.warnv("No artifact files matched patterns {0} in {1} for build {2}",
                    filePatterns, baseDir, build.getId());
            return;
        }

        int slash = location.indexOf('/');
        String bucket = slash > 0 ? location.substring(0, slash) : location;
        String prefix = slash > 0 ? location.substring(slash + 1) : "";
        String artifactName = artifacts.name() != null ? artifacts.name()
                : project.getName() + "-" + build.getBuildNumber();

        boolean isNone = "NONE".equalsIgnoreCase(packaging);
        if (isNone) {
            for (Path file : matchedFiles) {
                String relative = artifacts.discardPaths()
                        ? file.getFileName().toString()
                        : baseDir.relativize(file).toString();
                String key = prefix.isBlank() ? relative : prefix + "/" + relative;
                byte[] data = Files.readAllBytes(file);
                String contentType = guessContentType(file.getFileName().toString());
                s3Service.putObject(bucket, key, data, contentType, Map.of());
            }
        } else {
            String key = prefix.isBlank() ? artifactName + ".zip" : prefix + "/" + artifactName + ".zip";
            byte[] zipBytes = zipFiles(baseDir, matchedFiles, artifacts.discardPaths());
            s3Service.putObject(bucket, key, zipBytes, "application/zip", Map.of());
        }
    }

    private List<Path> collectFiles(Path baseDir, List<String> patterns) throws IOException {
        List<Path> result = new ArrayList<>();
        if (!Files.exists(baseDir)) {
            LOG.warnv("Artifact base directory does not exist: {0}", baseDir);
            return result;
        }
        for (String pattern : patterns) {
            if ("**/*".equals(pattern) || "**".equals(pattern)) {
                try (var stream = Files.walk(baseDir)) {
                    stream.filter(Files::isRegularFile).forEach(result::add);
                }
            } else if (!pattern.contains("*") && !pattern.contains("?")
                    && !pattern.contains("{") && !pattern.contains("[")) {
                // Plain filename — resolve directly instead of using PathMatcher
                Path direct = baseDir.resolve(pattern);
                if (Files.isRegularFile(direct)) {
                    result.add(direct);
                } else {
                    LOG.warnv("Artifact file not found: {0}", direct);
                }
            } else {
                var matcher = baseDir.getFileSystem().getPathMatcher("glob:" + pattern);
                try (var stream = Files.walk(baseDir)) {
                    stream.filter(Files::isRegularFile)
                            .filter(p -> matcher.matches(baseDir.relativize(p)))
                            .forEach(result::add);
                }
            }
        }
        return result;
    }

    private byte[] zipFiles(Path baseDir, List<Path> files, boolean discardPaths) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Path file : files) {
                String entryName = discardPaths ? file.getFileName().toString()
                        : baseDir.relativize(file).toString();
                zos.putNextEntry(new ZipEntry(entryName));
                zos.write(Files.readAllBytes(file));
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    void extractZip(byte[] data, Path dest) throws IOException {
        try (ZipFile zipFile = ZipFile.builder()
                .setSeekableByteChannel(new SeekableInMemoryByteChannel(data)).get()) {
            var entries = zipFile.getEntriesInPhysicalOrder();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                Path target = dest.resolve(entry.getName()).normalize();
                if (!target.startsWith(dest)) {
                    continue; // zip slip protection
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else if ((entry.getUnixMode() & S_IFMT) == S_IFLNK) {
                    extractSymlink(zipFile, entry, target, dest);
                } else {
                    Files.createDirectories(target.getParent());
                    try (InputStream in = zipFile.getInputStream(entry)) {
                        Files.write(target, in.readAllBytes());
                    }
                    if (entry.getUnixMode() != 0) {
                        try {
                            Files.setPosixFilePermissions(target, posixPermissions(entry.getUnixMode()));
                        } catch (UnsupportedOperationException e) {
                            LOG.debugv("Filesystem does not support POSIX permissions for {0}: {1}",
                                    target, e.getMessage());
                        }
                    }
                }
            }
        }
    }

    // Recreates a symlink zip entry as a real symlink. The entry content is the link
    // target. Relative intra-tree targets (e.g. node_modules/.bin/ts-node ->
    // ../ts-node/dist/bin.js) are the common, legitimate case and must be created;
    // only targets that resolve outside the extraction root are skipped. On a
    // filesystem without symlink support, fall back to the previous behaviour of
    // writing the target string as a regular file.
    private void extractSymlink(ZipFile zipFile, ZipArchiveEntry entry, Path target, Path dest)
            throws IOException {
        String linkTarget;
        try (InputStream in = zipFile.getInputStream(entry)) {
            linkTarget = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Path resolved = target.getParent().resolve(linkTarget).normalize();
        if (!resolved.startsWith(dest)) {
            LOG.debugv("Skipping symlink {0} whose target {1} escapes the source root",
                    entry.getName(), linkTarget);
            return;
        }
        Files.createDirectories(target.getParent());
        Files.deleteIfExists(target);
        try {
            Files.createSymbolicLink(target, Path.of(linkTarget));
        } catch (UnsupportedOperationException e) {
            LOG.debugv("Filesystem does not support symlinks; writing target of {0} as a regular file: {1}",
                    entry.getName(), e.getMessage());
            Files.write(target, linkTarget.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static Set<PosixFilePermission> posixPermissions(int mode) {
        PosixFilePermission[] bits = PosixFilePermission.values();
        Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
        for (int i = 0; i < bits.length; i++) {
            if ((mode & (0400 >> i)) != 0) {
                permissions.add(bits[i]);
            }
        }
        return permissions;
    }

    private static int posixMode(Set<PosixFilePermission> permissions) {
        PosixFilePermission[] bits = PosixFilePermission.values();
        int mode = 0;
        for (int i = 0; i < bits.length; i++) {
            if (permissions.contains(bits[i])) {
                mode |= 0400 >> i;
            }
        }
        return mode;
    }

    private void beginPhase(Build build, String phaseType) {
        BuildPhase phase = new BuildPhase();
        phase.setPhaseType(phaseType);
        phase.setPhaseStatus("IN_PROGRESS");
        phase.setStartTime(System.currentTimeMillis() / 1000.0);
        build.getPhases().add(phase);
        build.setCurrentPhase(phaseType);
    }

    private void completePhase(Build build, String phaseType, String status) {
        findPhase(build, phaseType).ifPresent(p -> {
            double end = System.currentTimeMillis() / 1000.0;
            p.setPhaseStatus(status);
            p.setEndTime(end);
            p.setDurationInSeconds(Math.round(end - p.getStartTime()));
        });
    }

    private void completePhaseWithError(Build build, String phaseType, String status, String message) {
        findPhase(build, phaseType).ifPresent(p -> {
            double end = System.currentTimeMillis() / 1000.0;
            p.setPhaseStatus(status);
            p.setEndTime(end);
            p.setDurationInSeconds(Math.round(end - p.getStartTime()));
            if (message != null) {
                String truncated = message.substring(0, Math.min(message.length(), 1024));
                p.setContexts(List.of(Map.of("statusCode", "COMMAND_EXECUTION_ERROR", "message", truncated)));
            }
        });
    }

    private void skipPhase(Build build, String phaseType) {
        BuildPhase phase = new BuildPhase();
        phase.setPhaseType(phaseType);
        phase.setPhaseStatus("SUCCEEDED");
        double now = System.currentTimeMillis() / 1000.0;
        phase.setStartTime(now);
        phase.setEndTime(now);
        phase.setDurationInSeconds(0L);
        build.getPhases().add(phase);
    }

    private void finishStopped(Build build) {
        build.setEndTime(System.currentTimeMillis() / 1000.0);
        build.setBuildComplete(true);
        build.setBuildStatus("STOPPED");
        build.setCurrentPhase("COMPLETED");
    }

    private void finishFailed(Build build) {
        build.setEndTime(System.currentTimeMillis() / 1000.0);
        build.setBuildComplete(true);
        build.setBuildStatus("FAILED");
        build.setCurrentPhase("COMPLETED");
    }

    private Optional<BuildPhase> findPhase(Build build, String phaseType) {
        return build.getPhases().stream()
                .filter(p -> phaseType.equals(p.getPhaseType()))
                .findFirst();
    }

    private void deleteDirectory(Path path) {
        try {
            if (!Files.exists(path)) { return; }
            Files.walk(path).sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (Exception ignored) {}
            });
        } catch (Exception e) {
            LOG.debugv("Could not delete workspace {0}: {1}", path, e.getMessage());
        }
    }

    private static String guessContentType(String filename) {
        if (filename.endsWith(".json")) { return "application/json"; }
        if (filename.endsWith(".xml")) { return "application/xml"; }
        if (filename.endsWith(".html")) { return "text/html"; }
        return "text/plain";
    }

    // Parses the streamed session output line by line: sentinel lines drive phase
    // begin/complete/skip bookkeeping on the Build, everything else is buffered as
    // the running phase's log tail for the FAILED context message. finish() settles
    // phases the shell never reported on (premature exit, timeout, exec error).
    private class PhaseSession {

        private final Build build;
        private final ByteArrayOutputStream pendingLine = new ByteArrayOutputStream();
        private final StringBuilder phaseOutput = new StringBuilder();
        private final Set<String> endedPhases = new HashSet<>();
        private String runningPhase;
        private boolean anyPhaseFailed;
        private boolean finished;

        PhaseSession(Build build) {
            this.build = build;
        }

        synchronized void accept(byte[] payload) {
            if (finished) {
                return;
            }
            for (byte b : payload) {
                if (b == '\n') {
                    handleLine(pendingLine.toString(StandardCharsets.UTF_8));
                    pendingLine.reset();
                } else {
                    pendingLine.write(b);
                }
            }
        }

        private void handleLine(String line) {
            String stripped = line.stripTrailing();
            if (stripped.startsWith(PHASE_START_SENTINEL + " ")) {
                runningPhase = stripped.substring(PHASE_START_SENTINEL.length() + 1).trim();
                phaseOutput.setLength(0);
                beginPhase(build, runningPhase);
                return;
            }
            if (stripped.startsWith(PHASE_END_SENTINEL + " ")) {
                String[] parts = stripped.substring(PHASE_END_SENTINEL.length() + 1).trim().split("\\s+");
                if (parts.length >= 2) {
                    endPhase(parts[0], parts[1]);
                }
                return;
            }
            phaseOutput.append(line).append('\n');
            if (phaseOutput.length() > 8192) {
                phaseOutput.delete(0, phaseOutput.length() - 4096);
            }
        }

        private void endPhase(String phase, String result) {
            endedPhases.add(phase);
            if (phase.equals(runningPhase)) {
                runningPhase = null;
            }
            if ("SKIPPED".equals(result)) {
                skipPhase(build, phase);
                return;
            }
            long exitCode;
            try {
                exitCode = Long.parseLong(result);
            } catch (NumberFormatException e) {
                LOG.warnv("Unparseable exit code {0} for phase {1} of build {2}", result, phase, build.getId());
                exitCode = -1;
            }
            if (exitCode == 0) {
                completePhase(build, phase, "SUCCEEDED");
            } else {
                completePhaseWithError(build, phase, "FAILED", failureMessage(exitCode));
                anyPhaseFailed = true;
            }
            phaseOutput.setLength(0);
        }

        private String failureMessage(long exitCode) {
            String output = phaseOutput.toString();
            String message = "Exit code " + exitCode;
            if (!output.isBlank()) {
                String trimmed = output.stripTrailing();
                message += ": " + trimmed.substring(Math.max(0, trimmed.length() - 512));
            }
            return message;
        }

        synchronized boolean finish(Long execExitCode, String sessionErrorMessage) {
            if (pendingLine.size() > 0) {
                handleLine(pendingLine.toString(StandardCharsets.UTF_8));
                pendingLine.reset();
            }
            finished = true;
            boolean unattributedError = sessionErrorMessage != null
                    || execExitCode == null || execExitCode != 0;
            for (String phase : SHELL_PHASES) {
                if (endedPhases.contains(phase)) {
                    continue;
                }
                endedPhases.add(phase);
                boolean started = phase.equals(runningPhase);
                if (!started && !unattributedError) {
                    skipPhase(build, phase);
                    continue;
                }
                if (!started) {
                    beginPhase(build, phase);
                }
                if (unattributedError) {
                    String message = sessionErrorMessage != null
                            ? sessionErrorMessage
                            : failureMessage(execExitCode != null ? execExitCode : -1);
                    completePhaseWithError(build, phase, "FAILED", message);
                    anyPhaseFailed = true;
                    unattributedError = false;
                } else {
                    completePhase(build, phase, "SUCCEEDED");
                }
                if (started) {
                    runningPhase = null;
                }
            }
            return anyPhaseFailed;
        }
    }

    private enum PhaseStatus { SUCCEEDED, FAILED, STOPPED }

    private record PhaseResult(PhaseStatus status, String errorMessage) {
        boolean succeeded() { return status == PhaseStatus.SUCCEEDED; }
        boolean failed() { return status == PhaseStatus.FAILED; }
        boolean stopped() { return status == PhaseStatus.STOPPED; }
        static PhaseResult ofSuccess() { return new PhaseResult(PhaseStatus.SUCCEEDED, null); }
        static PhaseResult ofFailure(String msg) { return new PhaseResult(PhaseStatus.FAILED, msg); }
        static PhaseResult ofStopped() { return new PhaseResult(PhaseStatus.STOPPED, null); }
    }
}
