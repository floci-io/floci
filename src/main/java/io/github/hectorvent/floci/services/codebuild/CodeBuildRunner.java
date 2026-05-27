package io.github.hectorvent.floci.services.codebuild;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.services.codebuild.BuildspecParser.ParsedArtifacts;
import io.github.hectorvent.floci.services.codebuild.BuildspecParser.ParsedBuildspec;
import io.github.hectorvent.floci.services.codebuild.model.Build;
import io.github.hectorvent.floci.services.codebuild.model.BuildPhase;
import io.github.hectorvent.floci.services.codebuild.model.Project;
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
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@ApplicationScoped
public class CodeBuildRunner {

    private static final Logger LOG = Logger.getLogger(CodeBuildRunner.class);

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

    public void startBuild(String region, Build build, Project project, String buildspecOverride) {
        AtomicBoolean stopFlag = new AtomicBoolean(false);
        stopFlags.put(build.getId(), stopFlag);
        Thread.ofVirtual().start(() -> runBuild(region, build, project, buildspecOverride, stopFlag));
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
        String buildId = build.getId();
        Path workspace = null;
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

            String image = build.getEnvironment() != null && build.getEnvironment().getImage() != null
                    ? build.getEnvironment().getImage()
                    : project.getEnvironment().getImage();

            boolean privileged = (project.getEnvironment() != null
                    && Boolean.TRUE.equals(project.getEnvironment().getPrivilegedMode()))
                    || (build.getEnvironment() != null
                    && Boolean.TRUE.equals(build.getEnvironment().getPrivilegedMode()));

            Map<String, Object> logsMap = new java.util.HashMap<>();
            logsMap.put("groupName", logGroup);
            logsMap.put("streamName", logStream);
            logsMap.put("cloudWatchLogsArn", AwsArnUtils.Arn.of("logs", region, regionResolver.getAccountId(), "log-group:" + logGroup + ":log-stream:" + logStream).toString());
            build.setLogs(logsMap);

            List<String> envList = buildEnvList(region, build, project, buildspec, logStream);

            // Create the working directory inside the container as part of startup,
            // then keep the container alive. No bind mount needed — source and
            // artifacts are transferred with docker cp.
            ContainerSpec spec = containerBuilder.newContainer(image)
                    .withCmd(List.of("sh", "-c",
                            "mkdir -p /codebuild/output/src/src && tail -f /dev/null"))
                    .withEnv(envList)
                    .withDockerNetwork(config.services().codebuild().dockerNetwork())
                    .withEmbeddedDns()
                    .withHostDockerInternalOnLinux()
                    .withPrivileged(privileged)
                    .withLogRotation()
                    .build();

            ContainerLifecycleManager.ContainerInfo info = lifecycleManager.createAndStart(spec);
            containerId = info.containerId();
            runningContainers.put(buildId, containerId);

            logHandle = logStreamer.attach(containerId, logGroup, logStream, region, "codebuild:" + buildId);

            // Copy downloaded source files into the container (no-op for NO_SOURCE builds)
            copySourceToContainer(containerId, workspace, "/codebuild/output/src/src");

            String containerSrcDir = "/codebuild/output/src/src";
            int timeoutMinutes = build.getTimeoutInMinutes() != null ? build.getTimeoutInMinutes() : 60;
            boolean buildFailed = false;

            // INSTALL
            if (stopFlag.get()) { finishStopped(build); return; }
            beginPhase(build, "INSTALL");
            build.setCurrentPhase("INSTALL");
            PhaseResult installResult = runPhase(containerId, containerSrcDir, envList,
                    buildspec.installCommands(), timeoutMinutes, stopFlag);
            if (installResult.stopped()) { finishStopped(build); return; }
            if (installResult.failed()) {
                completePhaseWithError(build, "INSTALL", "FAILED", installResult.errorMessage());
                buildFailed = true;
            } else {
                completePhase(build, "INSTALL", "SUCCEEDED");
            }

            // PRE_BUILD
            if (!buildFailed) {
                if (stopFlag.get()) { finishStopped(build); return; }
                beginPhase(build, "PRE_BUILD");
                build.setCurrentPhase("PRE_BUILD");
                PhaseResult preBuildResult = runPhase(containerId, containerSrcDir, envList,
                        buildspec.preBuildCommands(), timeoutMinutes, stopFlag);
                if (preBuildResult.stopped()) { finishStopped(build); return; }
                if (preBuildResult.failed()) {
                    completePhaseWithError(build, "PRE_BUILD", "FAILED", preBuildResult.errorMessage());
                    buildFailed = true;
                } else {
                    completePhase(build, "PRE_BUILD", "SUCCEEDED");
                }
            } else {
                skipPhase(build, "PRE_BUILD");
            }

            // BUILD
            if (!buildFailed) {
                if (stopFlag.get()) { finishStopped(build); return; }
                beginPhase(build, "BUILD");
                build.setCurrentPhase("BUILD");
                PhaseResult buildResult = runPhase(containerId, containerSrcDir, envList,
                        buildspec.buildCommands(), timeoutMinutes, stopFlag);
                if (buildResult.stopped()) { finishStopped(build); return; }
                if (buildResult.failed()) {
                    completePhaseWithError(build, "BUILD", "FAILED", buildResult.errorMessage());
                    buildFailed = true;
                } else {
                    completePhase(build, "BUILD", "SUCCEEDED");
                }
            } else {
                skipPhase(build, "BUILD");
            }

            // POST_BUILD — always runs unless container was killed
            if (stopFlag.get()) { finishStopped(build); return; }
            beginPhase(build, "POST_BUILD");
            build.setCurrentPhase("POST_BUILD");
            PhaseResult postBuildResult = runPhase(containerId, containerSrcDir, envList,
                    buildspec.postBuildCommands(), timeoutMinutes, stopFlag);
            if (postBuildResult.stopped()) { finishStopped(build); return; }
            if (postBuildResult.failed()) {
                completePhaseWithError(build, "POST_BUILD", "FAILED", postBuildResult.errorMessage());
                buildFailed = true;
            } else {
                completePhase(build, "POST_BUILD", "SUCCEEDED");
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
            if (containerId != null) {
                runningContainers.remove(buildId);
                if (logHandle != null) {
                    try { logHandle.close(); } catch (Exception ignored) {}
                }
                lifecycleManager.stopAndRemove(containerId, null);
            }
            if (workspace != null) {
                deleteDirectory(workspace);
            }
        }
    }

    private String resolveAndAcquireSource(String region, Build build, Project project,
                                           String buildspecOverride, Path workspace) throws IOException {
        String sourceType = project.getSource() != null ? project.getSource().getType() : "NO_SOURCE";

        if ("S3".equals(sourceType) && project.getSource().getLocation() != null) {
            String location = project.getSource().getLocation();
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

    private List<String> buildEnvList(String region, Build build, Project project,
                                      ParsedBuildspec buildspec, String logStream) {
        Map<String, String> env = new LinkedHashMap<>();

        env.put("CODEBUILD_BUILD_ID", build.getId());
        env.put("CODEBUILD_BUILD_ARN", build.getArn());
        env.put("CODEBUILD_BUILD_NUMBER", String.valueOf(build.getBuildNumber()));
        env.put("CODEBUILD_BUILD_IMAGE", build.getEnvironment() != null && build.getEnvironment().getImage() != null
                ? build.getEnvironment().getImage() : project.getEnvironment().getImage());
        env.put("CODEBUILD_INITIATOR", "user");
        env.put("CODEBUILD_SRC_DIR", "/codebuild/output/src/src");
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

        if (project.getEnvironment() != null && project.getEnvironment().getEnvironmentVariables() != null) {
            for (Map<String, String> v : project.getEnvironment().getEnvironmentVariables()) {
                String name = v.get("name");
                String value = v.get("value");
                if (name != null) { env.put(name, value != null ? value : ""); }
            }
        }

        if (build.getEnvironment() != null && build.getEnvironment().getEnvironmentVariables() != null) {
            for (Map<String, String> v : build.getEnvironment().getEnvironmentVariables()) {
                String name = v.get("name");
                String value = v.get("value");
                if (name != null) { env.put(name, value != null ? value : ""); }
            }
        }

        List<String> result = new ArrayList<>();
        env.forEach((k, v) -> result.add(k + "=" + (v != null ? v : "")));
        return result;
    }

    private String resolveEndpointUrl() {
        if (containerDetector.isRunningInContainer()) {
            String suffix = config.hostname().orElse(EmbeddedDnsServer.DEFAULT_SUFFIX);
            return "http://" + suffix + ":" + config.port();
        } else {
            return "http://host.docker.internal:" + config.port();
        }
    }

    // Copies files from the local workspace into the container's working directory.
    // Skips silently when the workspace is empty (e.g. NO_SOURCE builds).
    private void copySourceToContainer(String containerId, Path sourceDir, String remotePath) {
        try {
            if (!Files.exists(sourceDir)) return;
            boolean hasFiles;
            try (var ls = Files.list(sourceDir)) {
                hasFiles = ls.findAny().isPresent();
            }
            if (!hasFiles) return;

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            createTarFromDir(sourceDir, bos);
            dockerClient.copyArchiveToContainerCmd(containerId)
                    .withRemotePath(remotePath)
                    .withTarInputStream(new ByteArrayInputStream(bos.toByteArray()))
                    .exec();
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

                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.write(target, tar.readAllBytes());
                }
            }
        } catch (Exception e) {
            LOG.warnv("Could not copy artifacts from container {0}: {1}", containerId, e.getMessage());
        }
    }

    private void createTarFromDir(Path dir, ByteArrayOutputStream out) throws IOException {
        try (TarArchiveOutputStream tar = newTarStream(out);
             var stream = Files.walk(dir)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                if (path.equals(dir)) continue;
                String entryName = dir.relativize(path).toString();
                if (Files.isDirectory(path)) {
                    TarArchiveEntry entry = new TarArchiveEntry(entryName + "/");
                    tar.putArchiveEntry(entry);
                    tar.closeArchiveEntry();
                } else {
                    TarArchiveEntry entry = new TarArchiveEntry(entryName);
                    entry.setSize(Files.size(path));
                    entry.setMode(0644);
                    tar.putArchiveEntry(entry);
                    try (var fis = Files.newInputStream(path)) {
                        fis.transferTo(tar);
                    }
                    tar.closeArchiveEntry();
                }
            }
        }
    }

    private static TarArchiveOutputStream newTarStream(ByteArrayOutputStream out) {
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

    private void extractZip(byte[] data, Path dest) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path target = dest.resolve(entry.getName()).normalize();
                if (!target.startsWith(dest)) {
                    continue; // zip slip protection
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.write(target, zis.readAllBytes());
                }
                zis.closeEntry();
            }
        }
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
