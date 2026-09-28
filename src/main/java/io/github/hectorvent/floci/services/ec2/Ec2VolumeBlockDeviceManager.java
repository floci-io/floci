package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Frame;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.Volume;
import io.github.hectorvent.floci.services.ec2.model.VolumeAttachment;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Manages backing files and block device attachments for EC2 EBS volumes.
 *
 * <p>EBS volumes are backed by raw files on persistent storage. When attached to a running
 * instance container (which runs privileged), a Linux kernel loop device is bound to the
 * backing file via a lightweight helper container, and the corresponding device node is
 * created inside the target container at the requested path.
 */
@ApplicationScoped
public class Ec2VolumeBlockDeviceManager implements Resettable {

    private static final Logger LOG = Logger.getLogger(Ec2VolumeBlockDeviceManager.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private final DockerClient dockerClient;
    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final EmulatorConfig config;

    private final Map<String, String> activeLoopDevices = new ConcurrentHashMap<>();

    private volatile Boolean dockerAvailableCached;
    private volatile long dockerAvailableCheckTime;

    @Inject
    public Ec2VolumeBlockDeviceManager(DockerClient dockerClient,
                                       ContainerBuilder containerBuilder,
                                       ContainerLifecycleManager lifecycleManager,
                                       EmulatorConfig config) {
        this.dockerClient = dockerClient;
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.config = config;
    }

    /**
     * Checks if volume block device support is configured and Docker is reachable.
     */
    public boolean isAvailable() {
        if (config == null || config.services() == null || config.services().ec2() == null) {
            return false;
        }
        if (!config.services().ec2().volumeBlockDevices() || config.services().ec2().mock()) {
            return false;
        }
        if (dockerClient == null || lifecycleManager == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (dockerAvailableCached != null && (now - dockerAvailableCheckTime) < 5000) {
            return dockerAvailableCached;
        }
        try {
            dockerClient.pingCmd().exec();
            dockerAvailableCached = true;
        } catch (Exception e) {
            dockerAvailableCached = false;
        }
        dockerAvailableCheckTime = now;
        return dockerAvailableCached;
    }

    /**
     * Creates a raw backing file for the given volume.
     */
    public void createVolume(String volumeId, int sizeGib) {
        if (!isAvailable()) {
            return;
        }
        String helperId = ensureHelperContainer();
        if (helperId == null) {
            LOG.warnv("EC2 volume helper container unavailable; backing file for volume {0} will be created on attach",
                    volumeId);
            return;
        }
        int effectiveSize = sizeGib > 0 ? sizeGib : 8;
        String rawFile = "/volumes/" + volumeId + ".raw";
        String cmd = "truncate -s " + effectiveSize + "G " + rawFile;
        ContainerExecResult result = execInContainer(helperId, new String[]{"sh", "-c", cmd}, DEFAULT_TIMEOUT_SECONDS);
        if (result.exitCode() != 0) {
            LOG.warnv("Failed to create backing file for volume {0}: {1}", volumeId, result.summary());
        }
    }

    private static final Pattern VALID_DEVICE_PATTERN =
            Pattern.compile("^(/dev/)?[a-zA-Z0-9/_-]+$");

    /**
     * Shell helper function that finds loop devices backing a file across both BusyBox
     * losetup (which outputs `/dev/loopX: 0 /path/file`) and util-linux losetup
     * (which supports {@code losetup -j} and formats {@code losetup -a} as {@code /dev/loopX: [dev]:ino (/path/file)}).
     */
    private static final String SHELL_LOOP_FINDER =
            "find_loops() {\n"
            + "  f=\"$1\"\n"
            + "  if losetup -j \"$f\" >/dev/null 2>&1; then\n"
            + "    losetup -j \"$f\" 2>/dev/null | cut -d: -f1\n"
            + "  else\n"
            + "    losetup -a 2>/dev/null | grep -E \"[ (]$f[) ]?$\" | cut -d: -f1\n"
            + "  fi\n"
            + "}\n";

    private final Object loopAllocationLock = new Object();

    /**
     * Deletes the volume's backing file and releases any associated loop device.
     */
    public boolean deleteVolume(String volumeId) {
        if (!isAvailable()) {
            return false;
        }
        String helperId = ensureHelperContainer();
        if (helperId == null) {
            LOG.warnv("EC2 volume helper container unavailable; backing file for volume {0} could not be deleted",
                    volumeId);
            return false;
        }
        String rawFile = "/volumes/" + volumeId + ".raw";
        String script = SHELL_LOOP_FINDER
                + "raw=\"$1\"\n"
                + "while true; do\n"
                + "  loop=$(find_loops \"$raw\" | head -n1)\n"
                + "  [ -n \"$loop\" ] || break\n"
                + "  losetup -d \"$loop\" 2>/dev/null || break\n"
                + "done\n"
                + "rm -f \"$raw\"";
        ContainerExecResult result = execInContainer(helperId,
                new String[]{"sh", "-c", script, "delete", rawFile}, DEFAULT_TIMEOUT_SECONDS);
        activeLoopDevices.remove(volumeId);
        return result.exitCode() == 0;
    }

    /**
     * Attaches a volume to a target instance container as a block device.
     *
     * @return the device path inside the container if block device creation succeeded,
     *         or empty if degraded or container was not running.
     */
    public Optional<String> attachVolume(Volume volume, Instance instance, String requestedDevice) {
        if (!isAvailable()) {
            LOG.debugv("Volume block devices disabled or unavailable; skipping device creation for volume {0}",
                    volume.getVolumeId());
            return Optional.empty();
        }
        if (instance == null || requestedDevice == null || requestedDevice.isBlank()) {
            return Optional.empty();
        }
        if (!VALID_DEVICE_PATTERN.matcher(requestedDevice).matches() || requestedDevice.contains("..")) {
            LOG.warnv("Rejecting invalid device path {0} for volume {1}", requestedDevice, volume.getVolumeId());
            return Optional.empty();
        }

        String targetContainerId = instance.getDockerContainerId();
        if (targetContainerId == null || targetContainerId.isBlank()) {
            LOG.warnv("Instance {0} has no Docker container ID; volume {1} attached as metadata only",
                    instance.getInstanceId(), volume.getVolumeId());
            return Optional.empty();
        }
        if (!lifecycleManager.isContainerRunning(targetContainerId)) {
            LOG.warnv("Container {0} for instance {1} is not running; volume {2} attached as metadata only",
                    targetContainerId, instance.getInstanceId(), volume.getVolumeId());
            return Optional.empty();
        }

        String helperId = ensureHelperContainer();
        if (helperId == null) {
            LOG.warnv("EC2 volume helper container unavailable; volume {0} attached as metadata only",
                    volume.getVolumeId());
            return Optional.empty();
        }

        int effectiveSize = volume.getSize() > 0 ? volume.getSize() : 8;
        String rawFile = "/volumes/" + volume.getVolumeId() + ".raw";
        String helperScript = SHELL_LOOP_FINDER
                + "file=\"$1\"\n"
                + "size=\"$2\"\n"
                + "[ -f \"$file\" ] || truncate -s \"${size}G\" \"$file\"\n"
                + "loop=$(find_loops \"$file\" | head -n1)\n"
                + "if [ -z \"$loop\" ]; then\n"
                + "  loop=$(losetup -f 2>/dev/null)\n"
                + "  if [ -n \"$loop\" ]; then\n"
                + "    minor=$(echo \"$loop\" | sed 's/[^0-9]*//g')\n"
                + "    if [ -n \"$minor\" ] && [ ! -b \"$loop\" ]; then\n"
                + "      mknod \"$loop\" b 7 \"$minor\" 2>/dev/null || true\n"
                + "    fi\n"
                + "    losetup \"$loop\" \"$file\" || exit 1\n"
                + "  else\n"
                + "    exit 1\n"
                + "  fi\n"
                + "fi\n"
                + "echo \"$loop\"";

        ContainerExecResult helperResult;
        synchronized (loopAllocationLock) {
            helperResult = execInContainer(helperId,
                    new String[]{"sh", "-c", helperScript, "helper", rawFile, String.valueOf(effectiveSize)},
                    DEFAULT_TIMEOUT_SECONDS);
        }
        if (helperResult.exitCode() != 0) {
            LOG.warnv("Failed to allocate loop device for volume {0} in helper: {1}",
                    volume.getVolumeId(), helperResult.summary());
            return Optional.empty();
        }

        String loopDev = helperResult.summary();
        if (loopDev == null || !loopDev.startsWith("/dev/loop")) {
            LOG.warnv("Unexpected loop device format for volume {0}: {1}", volume.getVolumeId(), loopDev);
            return Optional.empty();
        }
        activeLoopDevices.put(volume.getVolumeId(), loopDev);

        String minorStr = loopDev.replaceAll("[^0-9]", "");
        String normalizedDevice = requestedDevice.startsWith("/") ? requestedDevice : "/dev/" + requestedDevice;

        String targetScript = "dev=\"$1\"\n"
                + "loop=\"$2\"\n"
                + "minor=\"$3\"\n"
                + "mkdir -p $(dirname \"$dev\")\n"
                + "if [ -e \"$dev\" ] || [ -L \"$dev\" ]; then\n"
                + "  rm -f \"$dev\"\n"
                + "fi\n"
                + "if [ -n \"$minor\" ]; then\n"
                + "  mknod \"$dev\" b 7 \"$minor\" 2>/dev/null || ln -sf \"$loop\" \"$dev\"\n"
                + "else\n"
                + "  ln -sf \"$loop\" \"$dev\"\n"
                + "fi\n"
                + "[ -b \"$dev\" ] || [ -L \"$dev\" ]";

        ContainerExecResult targetResult = execInContainer(targetContainerId,
                new String[]{"sh", "-c", targetScript, "target", normalizedDevice, loopDev, minorStr},
                DEFAULT_TIMEOUT_SECONDS);
        if (targetResult.exitCode() != 0) {
            LOG.warnv("Failed to create block device {0} in container {1} for volume {2}: {3}",
                    normalizedDevice, targetContainerId, volume.getVolumeId(), targetResult.summary());
            return Optional.empty();
        }

        LOG.infov("Attached volume {0} as block device {1} (loop device {2}) to instance {3}",
                volume.getVolumeId(), normalizedDevice, loopDev, instance.getInstanceId());
        return Optional.of(normalizedDevice);
    }

    /**
     * Detaches a volume block device from the target container and releases the loop device.
     */
    public void detachVolume(Volume volume, Instance instance, String requestedDevice) {
        if (!isAvailable()) {
            return;
        }
        if (instance != null && requestedDevice != null && !requestedDevice.isBlank()
                && VALID_DEVICE_PATTERN.matcher(requestedDevice).matches() && !requestedDevice.contains("..")) {
            String targetContainerId = instance.getDockerContainerId();
            if (targetContainerId != null && !targetContainerId.isBlank()
                    && lifecycleManager.isContainerRunning(targetContainerId)) {
                String normalizedDevice = requestedDevice.startsWith("/") ? requestedDevice : "/dev/" + requestedDevice;
                execInContainer(targetContainerId, new String[]{"rm", "-f", normalizedDevice}, DEFAULT_TIMEOUT_SECONDS);
            }
        }

        String helperId = ensureHelperContainer();
        if (helperId != null) {
            String rawFile = "/volumes/" + volume.getVolumeId() + ".raw";
            String script = SHELL_LOOP_FINDER
                    + "raw=\"$1\"\n"
                    + "while true; do\n"
                    + "  loop=$(find_loops \"$raw\" | head -n1)\n"
                    + "  [ -n \"$loop\" ] || break\n"
                    + "  losetup -d \"$loop\" 2>/dev/null || break\n"
                    + "done";
            execInContainer(helperId,
                    new String[]{"sh", "-c", script, "detach", rawFile}, DEFAULT_TIMEOUT_SECONDS);
        }
        activeLoopDevices.remove(volume.getVolumeId());
    }

    /**
     * Restores block device nodes inside an instance container after it is started or restarted.
     */
    public void restoreInstanceVolumeDevices(Instance instance,
                                             List<VolumeAttachment> attachments,
                                             Function<String, Optional<Volume>> volumeLookup) {
        if (!isAvailable() || instance == null || attachments == null || attachments.isEmpty()) {
            return;
        }
        for (VolumeAttachment att : attachments) {
            Optional<Volume> volumeOpt = volumeLookup.apply(att.getVolumeId());
            if (volumeOpt.isPresent()) {
                attachVolume(volumeOpt.get(), instance, att.getDevice());
            }
        }
    }

    private synchronized String ensureHelperContainer() {
        String helperName = ContainerStorageHelper.resourceName(config, "ec2", null, "volume-helper");
        try {
            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(helperName).exec();
            if (Boolean.TRUE.equals(inspect.getState().getRunning())) {
                return inspect.getId();
            }
            dockerClient.startContainerCmd(helperName).exec();
            return inspect.getId();
        } catch (NotFoundException e) {
            // Container does not exist yet; proceed to create it
        } catch (Exception e) {
            LOG.warnv("Failed to inspect EC2 volume helper container {0}: {1}", helperName, e.getMessage());
            return null;
        }

        try {
            String helperImage = config.services().ec2().volumeHelperImage();
            ContainerBuilder.Builder builder = containerBuilder.newContainer(helperImage)
                    .withName(helperName)
                    .withPrivileged(true)
                    .withEntrypoint(List.of("sleep", "2147483647"))
                    .withLabels(ContainerStorageHelper.defaultLabels(config))
                    .withLabels(ContainerStorageHelper.resourceIdentityLabels("ec2", "volume-helper", "default", "global"));

            if (ContainerStorageHelper.isNamedVolumeMode(config)) {
                String volumeName = ContainerStorageHelper.dockerName(config, "ec2-volumes");
                ContainerStorageHelper.applyNamedVolume(builder, lifecycleManager, volumeName, "/volumes");
            } else {
                Path hostPath = ContainerStorageHelper.hostResourcePath(config, "ec2", "volumes");
                Files.createDirectories(hostPath);
                builder.withBind(hostPath.toAbsolutePath().toString(), "/volumes");
            }

            ContainerSpec spec = builder.build();
            return lifecycleManager.createAndStart(spec).containerId();
        } catch (Exception e) {
            LOG.warnv("Failed to create and start EC2 volume helper container {0}: {1}", helperName, e.getMessage());
            return null;
        }
    }

    private ContainerExecResult execInContainer(String containerId, String[] cmd, int timeoutSeconds) {
        try {
            ExecCreateCmdResponse exec = dockerClient
                    .execCreateCmd(containerId)
                    .withCmd(cmd)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec();

            StringBuilder output = new StringBuilder();
            boolean completed = dockerClient.execStartCmd(exec.getId())
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            if (frame != null && frame.getPayload() != null) {
                                output.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                            }
                        }
                    })
                    .awaitCompletion(timeoutSeconds, TimeUnit.SECONDS);

            if (!completed) {
                return new ContainerExecResult(-1, "Timed out after " + timeoutSeconds + "s");
            }
            Long exitCode = dockerClient.inspectExecCmd(exec.getId()).exec().getExitCodeLong();
            return new ContainerExecResult(exitCode != null ? exitCode : -1, output.toString());
        } catch (Exception e) {
            return new ContainerExecResult(-1, e.getMessage());
        }
    }

    @Override
    public void clear() {
        if (!isAvailable()) {
            activeLoopDevices.clear();
            return;
        }
        String helperId = ensureHelperContainer();
        if (helperId != null) {
            String script = SHELL_LOOP_FINDER
                    + "for f in /volumes/*.raw; do\n"
                    + "  [ -f \"$f\" ] || continue\n"
                    + "  while true; do\n"
                    + "    loop=$(find_loops \"$f\" | head -n1)\n"
                    + "    [ -n \"$loop\" ] || break\n"
                    + "    losetup -d \"$loop\" 2>/dev/null || break\n"
                    + "  done\n"
                    + "  rm -f \"$f\"\n"
                    + "done";
            execInContainer(helperId, new String[]{"sh", "-c", script}, DEFAULT_TIMEOUT_SECONDS);
        }
        activeLoopDevices.clear();
    }

    public record ContainerExecResult(long exitCode, String output) {
        public String summary() {
            return output == null || output.isBlank() ? "(no output)" : output.trim();
        }
    }
}
