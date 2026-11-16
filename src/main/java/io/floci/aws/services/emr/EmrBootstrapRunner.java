package io.floci.aws.services.emr;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Image;
import io.floci.aws.config.ContainerCaBundle;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.ContainerTeardown;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.S3Uri;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.core.common.docker.ContainerLaunches;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.LaunchedContainerAwsEnv;
import io.floci.aws.core.common.docker.RetryingTarCopier;
import io.floci.aws.services.emrserverless.EmrServerlessSparkRunner;
import io.floci.aws.services.iam.model.SessionCreds;
import io.floci.aws.services.lambda.launcher.LambdaExecutionRoleCredentials;
import io.floci.aws.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPOutputStream;

/**
 * Runs a cluster's bootstrap actions on one of its nodes in a container of the release's EMR Serverless image, the one
 * its steps run in, then commits that container as the image that node runs from: the primary node's is the one the
 * cluster's steps run in from then on, so what the actions installed or wrote is there for every step, and a core or
 * task node's is its own, the container named, and its node's files written, for that node.
 * "Bootstrap actions execute as the Hadoop user by
 * default. You can execute a bootstrap action with root privileges by using sudo", but the image has no
 * {@code sudo}: an action runs as root instead, in the {@code hadoop} user's home, with a {@code sudo} that
 * runs its command as it is, and what it leaves in that home is the {@code hadoop} user's. Each action's
 * script is read from Floci's S3 as the cluster's account and runs through {@code docker exec}, with the
 * credentials of the cluster's EC2 instance profile in its environment alone, so that they never become
 * part of the image. With TLS on, the JVMs an action starts trust Floci's CA, through the Java trust store
 * copied in once the container is up, which goes before the image is kept.
 */
@ApplicationScoped
public class EmrBootstrapRunner implements BootstrapRunner, ContainerTeardown {

    private static final Logger LOG = Logger.getLogger(EmrBootstrapRunner.class);
    private static final String SERVICE = "emr";
    private static final String COMPONENT = "emr-bootstrap";
    private static final int STOP_TIMEOUT_SECONDS = 0;
    // No source gives a bootstrap action a time limit: it runs until it ends, or its cluster does.
    private static final int NO_TIMEOUT = Integer.MAX_VALUE;
    private static final int NODE_INFO_TIMEOUT_SECONDS = 60;
    // What each action runs as root, with the sudo a step has too (see EmrServerlessSparkRunner.SUDO), whose options
    // the action, already root, has no need of: $1 is its script, and the rest that script's arguments. The JVMs it
    // starts trust Floci's CA once the container has its trust store.
    private static final String ACTION_COMMAND = String.join("\n",
            ContainerCaBundle.JAVA_TRUST_SHELL_LINE,
            "mkdir -p /tmp/floci-bin",
            EmrServerlessSparkRunner.SUDO_FILE,
            "chmod 755 /tmp/floci-bin/sudo",
            "export PATH=\"/tmp/floci-bin:$PATH\" HOME=/home/hadoop",
            "cd /home/hadoop",
            "script=$1",
            "shift",
            "chmod u+x \"$script\"",
            "\"$script\" \"$@\"",
            "code=$?",
            "chown -R hadoop:hadoop /home/hadoop",
            "exit $code");
    // Run once every action has: what only the actions needed goes before the image is kept, the trust store
    // included, which each step's container gets afresh.
    // The primary node's files, which say which node it is, go too, so a node started from the image writes its own.
    private static final String CLEANUP_COMMAND = "rm -f /tmp/floci-bin/sudo /tmp/floci-bootstrap-action-* "
            + ContainerCaBundle.JAVA_TRUST_STORE_PATH + " " + EmrServerlessSparkRunner.INSTANCE_FILE + " "
            + EmrServerlessSparkRunner.EXTRA_INSTANCE_DATA_FILE + "; rmdir /tmp/floci-bin;"
            + " rmdir /mnt/var/lib/info /mnt/var/lib/instance-controller 2>/dev/null";

    // The bootstraps inside run(); a stop for one not here comes after its container has gone, and is dropped.
    private final ContainerLaunches launches = new ContainerLaunches();

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final LaunchedContainerAwsEnv awsEnv;
    private final LambdaExecutionRoleCredentials roleCredentials;
    private final S3Service s3Service;
    private final EmulatorConfig config;

    public EmrBootstrapRunner(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                              LaunchedContainerAwsEnv awsEnv, LambdaExecutionRoleCredentials roleCredentials,
                              S3Service s3Service, EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.awsEnv = awsEnv;
        this.roleCredentials = roleCredentials;
        this.s3Service = s3Service;
        this.config = config;
    }

    @Override
    public Outcome run(Bootstrap bootstrap, BooleanSupplier cancelled) {
        ContainerLaunches.Launch launch = launches.begin(launchId(bootstrap));
        Optional<SessionCreds> credentials = Optional.empty();
        try {
            if (cancelled.getAsBoolean()) {
                return Outcome.halted();
            }
            credentials = roleCredentials.forRole(bootstrap.role(), bootstrap.accountId(),
                    bootstrap.name() + "-bootstrap");
            String image = EmrServerlessSparkRunner.image(bootstrap.imageTemplate(), bootstrap.releaseLabel());
            Map<String, String> labels = labels(bootstrap);
            ContainerBuilder.Builder container = containerBuilder.newContainer(image)
                    .withName(ContainerStorageHelper.dockerName(config, SERVICE + "-" + bootstrap.clusterId()
                            + "-bootstrap" + (bootstrap.node() == null ? "" : "-" + bootstrap.node())))
                    .withUser("root")
                    .withEntrypoint(List.of("sleep", "infinity"))
                    .withHostDockerInternalOnLinux()
                    .withEmbeddedDns()
                    .withLabels(labels);
            if (bootstrap.metadataNetwork() != null) {
                // On its cluster's network, where the relay answers its address as the primary node's metadata, and on
                // its steps' too, where it reaches Floci.
                container.withDockerNetwork(Optional.of(bootstrap.metadataNetwork()))
                        .withLinkLocalIp(bootstrap.metadataAddress())
                        .withJoinedNetwork(containerBuilder.resolveDockerNetwork(
                                Optional.ofNullable(bootstrap.dockerNetwork())).orElse(null));
            } else {
                container.withDockerNetwork(Optional.ofNullable(bootstrap.dockerNetwork()));
            }
            ContainerSpec spec = container.build();
            String containerId = lifecycleManager.createAndStart(spec).containerId();
            launch.started(containerId);
            // In before the first action, for the actions' JVMs to find it. Not through the spec: the container is
            // kept as an image, which would keep a JAVA_TOOL_OPTIONS given at its creation, naming a trust store
            // the cleanup removes.
            lifecycleManager.copyJavaTrustStore(containerId);
            // A stop, a reset or a shutdown while the image was pulled or the trust store copied is read now.
            if (launch.stopRequested() || cancelled.getAsBoolean()) {
                return Outcome.halted();
            }
            // The actions run on the primary node, whose files say so, as the Management Guide's own action reads them.
            writeNodeInfo(containerId, bootstrap.instanceRole());
            List<String> env = awsEnv.sdkBaselineEnv(bootstrap.region(), Optional.empty(), credentials,
                    bootstrap.accountId());
            for (int number = 1; number <= bootstrap.actions().size(); number++) {
                Outcome failed = runAction(bootstrap, number, containerId, env);
                if (launch.stopRequested() || cancelled.getAsBoolean()) {
                    return Outcome.halted();
                }
                if (failed != null) {
                    return failed;
                }
            }
            DockerClient docker = lifecycleManager.getDockerClient();
            ContainerExec.run(docker, containerId, new String[] {"sh", "-c", CLEANUP_COMMAND}, 60);
            lifecycleManager.stop(containerId, STOP_TIMEOUT_SECONDS);
            return Outcome.succeeded(commit(docker, containerId, labels, bootstrap));
        } catch (RuntimeException e) {
            if (launch.stopRequested() || Thread.currentThread().isInterrupted()) {
                // A stop, a reset or a shutdown removed the container while an action ran.
                return Outcome.halted();
            }
            LOG.warnv(e, "The bootstrap actions of EMR cluster {0} could not run", bootstrap.clusterId());
            return Outcome.couldNotRun(e.getMessage());
        } finally {
            String unclaimed = launches.end(launchId(bootstrap), launch);
            if (unclaimed != null) {
                lifecycleManager.stopAndRemove(unclaimed, null, STOP_TIMEOUT_SECONDS);
            }
            credentials.ifPresent(session -> roleCredentials.unregister(bootstrap.accountId(),
                    session.accessKeyId()));
        }
    }

    private void writeNodeInfo(String containerId, String instanceRole) {
        ContainerExec.Result written = ContainerExec.run(lifecycleManager.getDockerClient(), containerId,
                new String[] {"env", EmrServerlessSparkRunner.INSTANCE_ROLE_VARIABLE + "=" + instanceRole, "bash",
                        "-c", EmrServerlessSparkRunner.NODE_INFO}, NODE_INFO_TIMEOUT_SECONDS);
        if (written.exitCode() != 0) {
            String why = written.stderr().strip();
            String node = "MASTER".equals(instanceRole) ? "primary" : instanceRole.toLowerCase(Locale.ROOT);
            throw new IllegalStateException("Could not write the " + node + " node's "
                    + EmrServerlessSparkRunner.INSTANCE_FILE + (why.isEmpty() ? "" : ": " + why));
        }
    }

    /**
     * Runs action {@code number} in the container, its output going to Floci's log and to the action's log
     * folder; null when it succeeded, or how it failed.
     */
    private Outcome runAction(Bootstrap bootstrap, int number, String containerId, List<String> env) {
        Action action = bootstrap.actions().get(number - 1);
        String script;
        try {
            script = placeScript(bootstrap, action, number, containerId);
        } catch (AwsException | IllegalArgumentException e) {
            return Outcome.notDownloaded(number, e.getMessage());
        }
        List<String> command = new ArrayList<>(List.of("bash", "-c", ACTION_COMMAND, "bootstrap-action", script));
        command.addAll(action.args());
        String prefix = SERVICE + ":" + bootstrap.clusterId() + ":bootstrap-action-" + number;
        boolean logged = bootstrap.logFolder() != null;
        ContainerExec.Result result;
        try (ActionOutput stdout = new ActionOutput(prefix, logged);
             ActionOutput stderr = new ActionOutput(prefix, logged)) {
            result = ContainerExec.run(lifecycleManager.getDockerClient(), containerId,
                    command.toArray(String[]::new), env, NO_TIMEOUT, stdout, stderr);
            if (logged) {
                writeLogs(bootstrap, number, stdout, stderr);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result.exitCode() == 0 ? null : Outcome.actionFailed(number, (int) result.exitCode());
    }

    /**
     * Where in the container action {@code number}'s script is: one in S3 is read as the cluster's account
     * and copied in, and a path that is not in S3 is the image's own.
     */
    private String placeScript(Bootstrap bootstrap, Action action, int number, String containerId) {
        if (!S3Uri.isS3(action.path())) {
            return action.path().startsWith("file:") ? action.path().substring("file:".length()) : action.path();
        }
        S3Uri location = S3Uri.parseAnyScheme(action.path());
        if (location.key().isEmpty()) {
            throw new IllegalArgumentException("The path " + action.path() + " names no object.");
        }
        byte[] content = RequestScopes.callAs(bootstrap.accountId(), bootstrap.region(),
                () -> s3Service.getObject(location.bucket(), location.key()).getData());
        String name = "floci-bootstrap-action-" + number + "-"
                + location.key().substring(location.key().lastIndexOf('/') + 1);
        RetryingTarCopier.copyBytes(lifecycleManager.getDockerClient(), containerId, "/tmp", name, content, 0755);
        return "/tmp/" + name;
    }

    // An action's output goes to its own folder under the cluster's: bootstrap-actions/<number>/.
    private void writeLogs(Bootstrap bootstrap, int number, ActionOutput stdout, ActionOutput stderr) {
        String folder = bootstrap.logFolder() + number + "/";
        try (InputStream stdoutGzip = stdout.finish(); InputStream stderrGzip = stderr.finish()) {
            EmrServerlessSparkRunner.putLogs(s3Service, bootstrap.accountId(), bootstrap.region(), folder,
                    stdoutGzip, stderrGzip);
        } catch (IOException | RuntimeException e) {
            LOG.warnv("Could not write the logs of bootstrap action {0} of EMR cluster {1} to {2}: {3}", number,
                    bootstrap.clusterId(), folder, e.getMessage());
        }
    }

    /**
     * One of an action's output streams, as it arrives: each line to Floci's log and, when the cluster has a
     * log folder, gzipped into a file of its own, so that however much an action writes, Floci keeps no more
     * than a line of it in memory. Closing it deletes the file.
     */
    private static final class ActionOutput extends OutputStream {

        private final OutputStream console;
        private final Path file;
        private final GZIPOutputStream gzip;

        ActionOutput(String logPrefix, boolean kept) throws IOException {
            console = ContainerLogStreamer.consoleLines(logPrefix);
            file = kept ? Files.createTempFile("floci-emr-bootstrap-", ".gz") : null;
            try {
                gzip = kept ? new GZIPOutputStream(Files.newOutputStream(file)) : null;
            } catch (IOException e) {
                Files.delete(file);
                throw e;
            }
        }

        @Override
        public synchronized void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
            console.write(bytes, offset, length);
            if (gzip != null) {
                gzip.write(bytes, offset, length);
            }
        }

        /** The output has ended: its last line is logged, and its gzipped file is read from the start. */
        synchronized InputStream finish() throws IOException {
            console.close();
            gzip.close();
            return Files.newInputStream(file);
        }

        @Override
        public synchronized void close() throws IOException {
            try {
                console.close();
                if (gzip != null) {
                    gzip.close();
                }
            } finally {
                if (file != null) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    /**
     * Commits the stopped container as the image the cluster's steps run in, and returns its id. The image
     * takes the container's configuration where the commit sets none, so its user, which the actions changed
     * to root, is set back to that of the image the container ran, the one the steps run as. The steps get
     * the id, which a container runs as it is, so the image-registry-base Floci prefixes image names with
     * does not send them to a registry for an image only this daemon has.
     */
    private String commit(DockerClient docker, String containerId, Map<String, String> labels, Bootstrap bootstrap) {
        String released = docker.inspectContainerCmd(containerId).exec().getImageId();
        String user = docker.inspectImageCmd(released).exec().getConfig().getUser();
        String repository = ContainerStorageHelper.dockerName(config, COMPONENT);
        lifecycleManager.commit(containerId, repository, bootstrap.name(),
                user == null || user.isBlank() ? "root" : user, labels);
        String tag = repository + ":" + bootstrap.name();
        String committed = docker.inspectImageCmd(tag).exec().getId();
        LOG.infov("Kept the bootstrap of EMR cluster {0} as image {1} ({2})", bootstrap.clusterId(), tag, committed);
        return committed;
    }

    // Removing a container ends the action running in it, which run() then reads as stopped: the primary node's and
    // each core and task node's.
    @Override
    public void stop(String clusterId) {
        launches.stopEach(id -> id.equals(clusterId) || id.startsWith(clusterId + "/"), lifecycleManager,
                STOP_TIMEOUT_SECONDS);
    }

    // A cluster's primary node's bootstrap by the cluster's id, a core or task node's after it.
    private static String launchId(Bootstrap bootstrap) {
        return bootstrap.node() == null ? bootstrap.clusterId() : bootstrap.clusterId() + "/" + bootstrap.node();
    }

    /** Stops every bootstrap inside {@link #run}, so a reset or a shutdown orphans none. */
    @Override
    public void stopManagedContainers() {
        launches.stopAll(lifecycleManager, STOP_TIMEOUT_SECONDS);
    }

    @Override
    public boolean removeImage(String image) {
        return lifecycleManager.removeImage(image);
    }

    @Override
    public void removeImages(Set<String> kept) {
        try {
            List<Image> images = lifecycleManager.getDockerClient().listImagesCmd()
                    .withLabelFilter(Map.of(ContainerStorageHelper.COMPONENT_LABEL, COMPONENT,
                            ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config)))
                    .exec();
            for (Image image : images) {
                if (!kept.contains(image.getId())) {
                    removeImage(image.getId());
                }
            }
        } catch (RuntimeException e) {
            LOG.warnv("Could not remove the bootstrap images of EMR clusters that have ended: {0}", e.getMessage());
        }
    }

    // The container's and the image's labels: the cluster's, and the deployment's that runs it.
    private Map<String, String> labels(Bootstrap bootstrap) {
        Map<String, String> labels = new LinkedHashMap<>(ContainerStorageHelper.resourceIdentityLabels(SERVICE,
                bootstrap.clusterId(), bootstrap.accountId(), bootstrap.region()));
        labels.put(ContainerStorageHelper.COMPONENT_LABEL, COMPONENT);
        labels.put(ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config));
        return labels;
    }

}
