package io.floci.aws.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.ContainerTeardown;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.Volume;
import io.floci.aws.services.ec2.model.VolumeAttachment;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;
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
public class Ec2VolumeBlockDeviceManager implements ContainerTeardown, Resettable {

    private static final Logger LOG = Logger.getLogger(Ec2VolumeBlockDeviceManager.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private final DockerClient dockerClient;
    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerDetector containerDetector;
    private final EmulatorConfig config;

    private final Map<String, String> activeLoopDevices = new ConcurrentHashMap<>();

    private volatile Boolean dockerAvailableCached;
    private volatile long dockerAvailableCheckTime;

    @Inject
    public Ec2VolumeBlockDeviceManager(DockerClient dockerClient,
                                       ContainerBuilder containerBuilder,
                                       ContainerLifecycleManager lifecycleManager,
                                       ContainerDetector containerDetector,
                                       EmulatorConfig config) {
        this.dockerClient = dockerClient;
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.containerDetector = containerDetector;
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
        ContainerExec.Result result = execInContainer(helperId, new String[]{"sh", "-c", cmd}, DEFAULT_TIMEOUT_SECONDS);
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
        if (!isAvailable() || volumeId == null) {
            return false;
        }
        return withVolumeLock(volumeId, () -> {
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
            ContainerExec.Result result = execInContainer(helperId,
                    new String[]{"sh", "-c", script, "delete", rawFile}, DEFAULT_TIMEOUT_SECONDS);
            activeLoopDevices.remove(volumeId);
            return result.exitCode() == 0;
        });
    }

    private static final class RefCountedLock {
        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicInteger refCount = new AtomicInteger(1);
    }

    private final ConcurrentHashMap<String, RefCountedLock> volumeLocks = new ConcurrentHashMap<>();

    private <T> T withVolumeLock(String volumeId, Supplier<T> action) {
        if (volumeId == null) {
            return action.get();
        }
        RefCountedLock refLock = volumeLocks.compute(volumeId, (k, v) -> {
            if (v == null) {
                return new RefCountedLock();
            }
            v.refCount.incrementAndGet();
            return v;
        });
        refLock.lock.lock();
        try {
            return action.get();
        } finally {
            refLock.lock.unlock();
            volumeLocks.computeIfPresent(volumeId, (k, v) -> {
                if (v.refCount.decrementAndGet() <= 0) {
                    return null;
                }
                return v;
            });
        }
    }

    /**
     * Resizes the volume's raw backing file and refreshes any associated loop device.
     */
    public boolean resizeVolume(String volumeId, int sizeGib) {
        if (!isAvailable() || volumeId == null) {
            return false;
        }
        return withVolumeLock(volumeId, () -> {
            String helperId = ensureHelperContainer();
            if (helperId == null) {
                LOG.warnv("EC2 volume helper container unavailable; backing file for volume {0} could not be resized",
                        volumeId);
                return false;
            }
            int effectiveSize = sizeGib > 0 ? sizeGib : 8;
            String rawFile = "/volumes/" + volumeId + ".raw";
            String script = SHELL_LOOP_FINDER
                    + "raw=\"$1\"\n"
                    + "size=\"$2\"\n"
                    + "cur_bytes=$(stat -c %s \"$raw\" 2>/dev/null || echo 0)\n"
                    + "target_bytes=$((size * 1024 * 1024 * 1024))\n"
                    + "truncated=0\n"
                    + "if [ \"$cur_bytes\" -lt \"$target_bytes\" ]; then\n"
                    + "  truncate -s \"${size}G\" \"$raw\" || exit 1\n"
                    + "  truncated=1\n"
                    + "fi\n"
                    + "for loop in $(find_loops \"$raw\"); do\n"
                    + "  minor=$(echo \"$loop\" | sed 's/[^0-9]*//g')\n"
                    + "  if [ -n \"$minor\" ] && [ ! -b \"$loop\" ]; then\n"
                    + "    mknod \"$loop\" b 7 \"$minor\" 2>/dev/null || true\n"
                    + "  fi\n"
                    + "  if ! losetup -c \"$loop\"; then\n"
                    + "    if [ \"$truncated\" -eq 1 ]; then\n"
                    + "      if [ \"$cur_bytes\" -gt 0 ]; then\n"
                    + "        truncate -s \"$cur_bytes\" \"$raw\" 2>/dev/null || true\n"
                    + "      else\n"
                    + "        rm -f \"$raw\" 2>/dev/null || true\n"
                    + "      fi\n"
                    + "      for rloop in $(find_loops \"$raw\"); do\n"
                    + "        losetup -c \"$rloop\" 2>/dev/null || true\n"
                    + "      done\n"
                    + "    fi\n"
                    + "    exit 2\n"
                    + "  fi\n"
                    + "done";
            ContainerExec.Result result = execInContainer(helperId,
                    new String[]{"sh", "-c", script, "resize", rawFile, String.valueOf(effectiveSize)},
                    DEFAULT_TIMEOUT_SECONDS);
            if (result.exitCode() == 1) {
                LOG.warnv("Failed to resize backing file for volume {0}: {1}", volumeId, result.summary());
                return false;
            }
            if (result.exitCode() == 2) {
                LOG.warnv("Failed to refresh loop device for volume {0}: {1}", volumeId, result.summary());
                return false;
            }
            if (result.exitCode() != 0) {
                LOG.warnv("Failed to resize volume {0}: {1}", volumeId, result.summary());
                return false;
            }
            return true;
        });
    }

    /**
     * Attaches a volume to a target instance container as a block device.
     *
     * @return the device path inside the container if block device creation succeeded,
     *         or empty for intentional metadata-only tracking.
     * @throws AwsException if physical attachment is attempted but fails
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

        if (instance.getState() != null && "stopped".equals(instance.getState().getName())) {
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
            throw attachmentFailure(volume, instance, null, null, null, "Volume helper container unavailable");
        }

        int effectiveSize = volume.getSize() > 0 ? volume.getSize() : 8;
        String rawFile = "/volumes/" + volume.getVolumeId() + ".raw";
        String helperScript = SHELL_LOOP_FINDER
                + "file=\"$1\"\n"
                + "size=\"$2\"\n"
                + "[ -f \"$file\" ] || truncate -s \"${size}G\" \"$file\"\n"
                + "allocated=0\n"
                + "loop=$(find_loops \"$file\" | head -n1)\n"
                + "if [ -z \"$loop\" ]; then\n"
                + "  loop=$(losetup -f 2>/dev/null)\n"
                + "  if [ -n \"$loop\" ]; then\n"
                + "    minor=$(echo \"$loop\" | sed 's/[^0-9]*//g')\n"
                + "    if [ -n \"$minor\" ] && [ ! -b \"$loop\" ]; then\n"
                + "      mknod \"$loop\" b 7 \"$minor\" 2>/dev/null || true\n"
                + "    fi\n"
                + "    losetup \"$loop\" \"$file\" || exit 1\n"
                + "    allocated=1\n"
                + "  else\n"
                + "    exit 1\n"
                + "  fi\n"
                + "fi\n"
                + "echo \"$allocated $loop\"";

        ContainerExec.Result helperResult;
        synchronized (loopAllocationLock) {
            helperResult = execInContainer(helperId,
                    new String[]{"sh", "-c", helperScript, "helper", rawFile, String.valueOf(effectiveSize)},
                    DEFAULT_TIMEOUT_SECONDS);
        }
        if (helperResult.exitCode() != 0) {
            throw attachmentFailure(volume, instance, null, null, null, helperResult.summary());
        }

        String[] allocation = helperResult.summary().split("\\s+");
        if (allocation.length != 2 || !("0".equals(allocation[0]) || "1".equals(allocation[0]))
                || !allocation[1].matches("/dev/loop[0-9]+")) {
            throw attachmentFailure(volume, instance, null, null, null,
                    "Unexpected loop allocation: " + helperResult.summary());
        }
        String loopDev = allocation[1];
        String allocatedLoop = "1".equals(allocation[0]) ? loopDev : null;
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
                + "[ -b \"$dev\" ]";

        ContainerExec.Result targetResult = execInContainer(targetContainerId,
                new String[]{"sh", "-c", targetScript, "target", normalizedDevice, loopDev, minorStr},
                DEFAULT_TIMEOUT_SECONDS);
        if (targetResult.exitCode() != 0) {
            throw attachmentFailure(volume, instance, helperId, allocatedLoop, normalizedDevice, targetResult.summary());
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
            releaseVolumeLoops(helperId, volume);
        }
    }

    private AwsException attachmentFailure(Volume volume, Instance instance, String helperId,
                                           String allocatedLoop, String targetDevice, String reason) {
        LOG.warnv("Failed to attach volume {0} to instance {1}: {2}",
                volume.getVolumeId(), instance.getInstanceId(), reason);
        if (targetDevice != null) {
            ContainerExec.Result removed = execInContainer(instance.getDockerContainerId(),
                    new String[]{"rm", "-f", targetDevice}, DEFAULT_TIMEOUT_SECONDS);
            if (removed.exitCode() != 0) {
                LOG.warnv("Could not clean up device {0} for volume {1}: {2}",
                        targetDevice, volume.getVolumeId(), removed.summary());
            }
        }
        if (helperId != null && allocatedLoop != null) {
            ContainerExec.Result released = execInContainer(helperId,
                    new String[]{"losetup", "-d", allocatedLoop}, DEFAULT_TIMEOUT_SECONDS);
            if (released.exitCode() != 0) {
                LOG.warnv("Could not release newly allocated loop {0} for volume {1}: {2}",
                        allocatedLoop, volume.getVolumeId(), released.summary());
            } else {
                activeLoopDevices.remove(volume.getVolumeId(), allocatedLoop);
            }
        }
        return new AwsException("InternalError", "Could not attach volume '" + volume.getVolumeId()
                + "' to instance '" + instance.getInstanceId() + "'", 500);
    }

    private void releaseVolumeLoops(String helperId, Volume volume) {
        String script = SHELL_LOOP_FINDER
                + "raw=\"$1\"\n"
                + "failed=0\n"
                + "for loop in $(find_loops \"$raw\"); do\n"
                + "  losetup -d \"$loop\" || failed=1\n"
                + "done\n"
                + "exit $failed";
        ContainerExec.Result released = execInContainer(helperId,
                new String[]{"sh", "-c", script, "detach", "/volumes/" + volume.getVolumeId() + ".raw"},
                DEFAULT_TIMEOUT_SECONDS);
        if (released.exitCode() != 0) {
            LOG.warnv("Could not release loop devices for volume {0}: {1}", volume.getVolumeId(), released.summary());
        } else {
            activeLoopDevices.remove(volume.getVolumeId());
        }
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
                try {
                    attachVolume(volumeOpt.get(), instance, att.getDevice());
                } catch (AwsException e) {
                    LOG.warnv("Could not restore volume {0} on instance {1}: {2}",
                            att.getVolumeId(), instance.getInstanceId(), e.getMessage());
                }
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
                String hostPath = ContainerStorageHelper.hostResourcePath(config, "ec2", "volumes")
                        .toAbsolutePath().toString();
                ContainerStorageHelper.ensureHostDir(containerDetector, hostPath);
                builder.withBind(hostPath, "/volumes");
            }

            ContainerSpec spec = builder.build();
            return lifecycleManager.createAndStart(spec).containerId();
        } catch (Exception e) {
            LOG.warnv("Failed to create and start EC2 volume helper container {0}: {1}", helperName, e.getMessage());
            return null;
        }
    }

    /**
     * Removes the helper container on shutdown and on a state reset. The helper is looked up by
     * name and never created here, so one left behind by an earlier process is collected too.
     * Backing files stay in storage, and the next volume operation recreates the helper.
     */
    @Override
    public synchronized void stopManagedContainers() {
        if (!isAvailable()) {
            return;
        }
        String helperName = ContainerStorageHelper.resourceName(config, "ec2", null, "volume-helper");
        InspectContainerResponse helper;
        try {
            helper = dockerClient.inspectContainerCmd(helperName).exec();
        } catch (NotFoundException e) {
            LOG.debugv("No EC2 volume helper container {0} to remove", helperName);
            return;
        } catch (Exception e) {
            LOG.warnv("Failed to inspect EC2 volume helper container {0}: {1}", helperName, e.getMessage());
            return;
        }
        if (Boolean.TRUE.equals(helper.getState().getRunning())) {
            // One pass per file: a loop device still held open by an instance container is
            // detached lazily and stays listed, so retrying it until it disappears would spin.
            // Every loop is tried, and one that would not detach fails the script so it is reported.
            String script = SHELL_LOOP_FINDER
                    + "failed=0\n"
                    + "for f in /volumes/*.raw; do\n"
                    + "  [ -f \"$f\" ] || continue\n"
                    + "  for loop in $(find_loops \"$f\"); do\n"
                    + "    losetup -d \"$loop\" 2>/dev/null || { echo \"could not detach $loop from $f\"; failed=1; }\n"
                    + "  done\n"
                    + "done\n"
                    + "exit $failed";
            ContainerExec.Result released = execInContainer(helper.getId(),
                    new String[]{"sh", "-c", script}, DEFAULT_TIMEOUT_SECONDS);
            if (released.exitCode() != 0) {
                LOG.warnv("Failed to release loop devices before removing EC2 volume helper container {0}: {1}",
                        helperName, released.summary());
            }
        }
        // The helper only sleeps and would ignore SIGTERM as PID 1, so do not wait on a graceful stop.
        lifecycleManager.stopAndRemove(helper.getId(), null, 0);
        activeLoopDevices.clear();
    }

    private ContainerExec.Result execInContainer(String containerId, String[] cmd, int timeoutSeconds) {
        try {
            return ContainerExec.runMerged(dockerClient, containerId, cmd, timeoutSeconds);
        } catch (RuntimeException e) {
            return new ContainerExec.Result(-1, "", Objects.requireNonNullElse(e.getMessage(), e.toString()), false);
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
            // The helper was only needed to delete the files, so a reset ends as a shutdown does:
            // without one.
            lifecycleManager.stopAndRemove(helperId, null, 0);
        }
        activeLoopDevices.clear();
    }
}
