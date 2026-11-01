package io.github.hectorvent.floci.services.emr;

import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.command.ListVolumesResponse;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.services.emrserverless.SparkJobRunner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps each EMR cluster's storage in two Docker volumes, one for its primary node's {@code /home/hadoop} and one
 * for its {@code /mnt}, where its HDFS is. Both paths belong to the image's {@code hadoop} user, and Docker gives
 * an empty volume what the image has at the path it is first mounted on, owner included, so the steps, which
 * run as that user, can write to both, and what the cluster's bootstrap left there comes with them.
 */
@ApplicationScoped
public class EmrClusterStorage implements ClusterStorage {

    private static final Logger LOG = Logger.getLogger(EmrClusterStorage.class);
    private static final String SERVICE = "emr";
    private static final String COMPONENT = "emr-storage";

    private final ContainerLifecycleManager lifecycleManager;
    private final EmulatorConfig config;

    @Inject
    public EmrClusterStorage(ContainerLifecycleManager lifecycleManager, EmulatorConfig config) {
        this.lifecycleManager = lifecycleManager;
        this.config = config;
    }

    @Override
    public SparkJobRunner.Storage volumes(String clusterId) {
        return new SparkJobRunner.Storage(
                ContainerStorageHelper.dockerName(config, SERVICE + "-" + clusterId + "-home"),
                ContainerStorageHelper.dockerName(config, SERVICE + "-" + clusterId + "-mnt"));
    }

    @Override
    public void create(String clusterId, String accountId, String region) {
        Map<String, String> labels = new LinkedHashMap<>(ContainerStorageHelper.resourceIdentityLabels(SERVICE,
                clusterId, accountId, region));
        labels.put(ContainerStorageHelper.COMPONENT_LABEL, COMPONENT);
        labels.put(ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config));
        SparkJobRunner.Storage storage = volumes(clusterId);
        lifecycleManager.ensureVolume(storage.home(), labels);
        lifecycleManager.ensureVolume(storage.mnt(), labels);
    }

    @Override
    public boolean remove(String clusterId) {
        SparkJobRunner.Storage storage = volumes(clusterId);
        // Both are tried, so one Docker refused does not keep the other.
        boolean homeGone = lifecycleManager.removeVolume(storage.home());
        return lifecycleManager.removeVolume(storage.mnt()) && homeGone;
    }

    @Override
    public void removeAllBut(Set<String> kept) {
        try {
            ListVolumesResponse response = lifecycleManager.getDockerClient().listVolumesCmd()
                    .withFilter("label", List.of(ContainerStorageHelper.COMPONENT_LABEL + "=" + COMPONENT,
                            ContainerStorageHelper.OWNER_LABEL + "=" + ContainerStorageHelper.ownerIdentity(config)))
                    .exec();
            List<InspectVolumeResponse> volumes = response != null && response.getVolumes() != null
                    ? response.getVolumes() : List.of();
            for (InspectVolumeResponse volume : volumes) {
                Map<String, String> labels = volume.getLabels() != null ? volume.getLabels() : Map.of();
                if (!kept.contains(labels.get(ContainerStorageHelper.RESOURCE_ID_LABEL))) {
                    lifecycleManager.removeVolume(volume.getName());
                }
            }
        } catch (RuntimeException e) {
            LOG.warnv("Could not remove the storage of EMR clusters that have ended: {0}", e.getMessage());
        }
    }
}
