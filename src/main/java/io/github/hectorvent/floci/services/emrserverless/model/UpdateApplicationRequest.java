package io.github.hectorvent.floci.services.emrserverless.model;

import java.util.List;
import java.util.Map;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class UpdateApplicationRequest {

    private String applicationId;
    private String architecture;
    private AutoStartConfiguration autoStartConfiguration;
    private AutoStopConfiguration autoStopConfiguration;
    private String clientToken;
    private DiskEncryptionConfiguration diskEncryptionConfiguration;
    private IdentityCenterConfiguration identityCenterConfiguration;
    private ImageConfiguration imageConfiguration;
    private Map<String, InitialCapacityConfig> initialCapacity;
    private InteractiveConfiguration interactiveConfiguration;
    private JobLevelCostAllocationConfiguration jobLevelCostAllocationConfiguration;
    private MaximumCapacity maximumCapacity;
    private MonitoringConfiguration monitoringConfiguration;
    private NetworkConfiguration networkConfiguration;
    private String releaseLabel;
    private List<RuntimeConfiguration> runtimeConfiguration;
    private SchedulerConfiguration schedulerConfiguration;
    private Map<String, WorkerTypeSpecification> workerTypeSpecifications;

    public UpdateApplicationRequest() {}

    public String getApplicationId() {
        return applicationId;
    }

    public void setApplicationId(String applicationId) {
        this.applicationId = applicationId;
    }

    public String getArchitecture() {
        return architecture;
    }

    public void setArchitecture(String architecture) {
        this.architecture = architecture;
    }

    public AutoStartConfiguration getAutoStartConfiguration() {
        return autoStartConfiguration;
    }

    public void setAutoStartConfiguration(AutoStartConfiguration autoStartConfiguration) {
        this.autoStartConfiguration = autoStartConfiguration;
    }

    public AutoStopConfiguration getAutoStopConfiguration() {
        return autoStopConfiguration;
    }

    public void setAutoStopConfiguration(AutoStopConfiguration autoStopConfiguration) {
        this.autoStopConfiguration = autoStopConfiguration;
    }

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }

    public DiskEncryptionConfiguration getDiskEncryptionConfiguration() {
        return diskEncryptionConfiguration;
    }

    public void setDiskEncryptionConfiguration(DiskEncryptionConfiguration diskEncryptionConfiguration) {
        this.diskEncryptionConfiguration = diskEncryptionConfiguration;
    }

    public IdentityCenterConfiguration getIdentityCenterConfiguration() {
        return identityCenterConfiguration;
    }

    public void setIdentityCenterConfiguration(IdentityCenterConfiguration identityCenterConfiguration) {
        this.identityCenterConfiguration = identityCenterConfiguration;
    }

    public ImageConfiguration getImageConfiguration() {
        return imageConfiguration;
    }

    public void setImageConfiguration(ImageConfiguration imageConfiguration) {
        this.imageConfiguration = imageConfiguration;
    }

    public Map<String, InitialCapacityConfig> getInitialCapacity() {
        return initialCapacity;
    }

    public void setInitialCapacity(Map<String, InitialCapacityConfig> initialCapacity) {
        this.initialCapacity = initialCapacity;
    }

    public InteractiveConfiguration getInteractiveConfiguration() {
        return interactiveConfiguration;
    }

    public void setInteractiveConfiguration(InteractiveConfiguration interactiveConfiguration) {
        this.interactiveConfiguration = interactiveConfiguration;
    }

    public JobLevelCostAllocationConfiguration getJobLevelCostAllocationConfiguration() {
        return jobLevelCostAllocationConfiguration;
    }

    public void setJobLevelCostAllocationConfiguration(JobLevelCostAllocationConfiguration jobLevelCostAllocationConfiguration) {
        this.jobLevelCostAllocationConfiguration = jobLevelCostAllocationConfiguration;
    }

    public MaximumCapacity getMaximumCapacity() {
        return maximumCapacity;
    }

    public void setMaximumCapacity(MaximumCapacity maximumCapacity) {
        this.maximumCapacity = maximumCapacity;
    }

    public MonitoringConfiguration getMonitoringConfiguration() {
        return monitoringConfiguration;
    }

    public void setMonitoringConfiguration(MonitoringConfiguration monitoringConfiguration) {
        this.monitoringConfiguration = monitoringConfiguration;
    }

    public NetworkConfiguration getNetworkConfiguration() {
        return networkConfiguration;
    }

    public void setNetworkConfiguration(NetworkConfiguration networkConfiguration) {
        this.networkConfiguration = networkConfiguration;
    }

    public String getReleaseLabel() {
        return releaseLabel;
    }

    public void setReleaseLabel(String releaseLabel) {
        this.releaseLabel = releaseLabel;
    }

    public List<RuntimeConfiguration> getRuntimeConfiguration() {
        return runtimeConfiguration;
    }

    public void setRuntimeConfiguration(List<RuntimeConfiguration> runtimeConfiguration) {
        this.runtimeConfiguration = runtimeConfiguration;
    }

    public SchedulerConfiguration getSchedulerConfiguration() {
        return schedulerConfiguration;
    }

    public void setSchedulerConfiguration(SchedulerConfiguration schedulerConfiguration) {
        this.schedulerConfiguration = schedulerConfiguration;
    }

    public Map<String, WorkerTypeSpecification> getWorkerTypeSpecifications() {
        return workerTypeSpecifications;
    }

    public void setWorkerTypeSpecifications(Map<String, WorkerTypeSpecification> workerTypeSpecifications) {
        this.workerTypeSpecifications = workerTypeSpecifications;
    }
}
