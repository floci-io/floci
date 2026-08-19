package io.github.hectorvent.floci.services.emrserverless.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.Date;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class Application {
    private String applicationId;
    private String arn;
    private String name;
    private String releaseLabel;
    private String type;
    private String state;
    private String stateDetails;
    private String clientToken;
    private Long createdAt;
    private Long updatedAt;
    private Map<String, String> tags;
    private String architecture;
    private Map<String, InitialCapacityConfig> initialCapacity;
    private MaximumCapacity maximumCapacity;
    private AutoStartConfiguration autoStartConfiguration;
    private AutoStopConfiguration autoStopConfiguration;
    private NetworkConfiguration networkConfiguration;
    private ImageConfiguration imageConfiguration;
    private Map<String, WorkerTypeSpecification> workerTypeSpecifications;
    private MonitoringConfiguration monitoringConfiguration;
    private List<RuntimeConfiguration> runtimeConfiguration;
    private SchedulerConfiguration schedulerConfiguration;
    private DiskEncryptionConfiguration diskEncryptionConfiguration;
    private InteractiveConfiguration interactiveConfiguration;
    private IdentityCenterConfiguration identityCenterConfiguration;
    private JobLevelCostAllocationConfiguration jobLevelCostAllocationConfiguration;

    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }
    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getReleaseLabel() { return releaseLabel; }
    public void setReleaseLabel(String releaseLabel) { this.releaseLabel = releaseLabel; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getStateDetails() { return stateDetails; }
    public void setStateDetails(String stateDetails) { this.stateDetails = stateDetails; }
    public String getClientToken() { return clientToken; }
    public void setClientToken(String clientToken) { this.clientToken = clientToken; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
    public String getArchitecture() { return architecture; }
    public void setArchitecture(String architecture) { this.architecture = architecture; }
    public Map<String, InitialCapacityConfig> getInitialCapacity() { return initialCapacity; }
    public void setInitialCapacity(Map<String, InitialCapacityConfig> initialCapacity) { this.initialCapacity = initialCapacity; }
    public MaximumCapacity getMaximumCapacity() { return maximumCapacity; }
    public void setMaximumCapacity(MaximumCapacity maximumCapacity) { this.maximumCapacity = maximumCapacity; }
    public AutoStartConfiguration getAutoStartConfiguration() { return autoStartConfiguration; }
    public void setAutoStartConfiguration(AutoStartConfiguration autoStartConfiguration) { this.autoStartConfiguration = autoStartConfiguration; }
    public AutoStopConfiguration getAutoStopConfiguration() { return autoStopConfiguration; }
    public void setAutoStopConfiguration(AutoStopConfiguration autoStopConfiguration) { this.autoStopConfiguration = autoStopConfiguration; }
    public NetworkConfiguration getNetworkConfiguration() { return networkConfiguration; }
    public void setNetworkConfiguration(NetworkConfiguration networkConfiguration) { this.networkConfiguration = networkConfiguration; }
    public ImageConfiguration getImageConfiguration() { return imageConfiguration; }
    public void setImageConfiguration(ImageConfiguration imageConfiguration) { this.imageConfiguration = imageConfiguration; }
    public Map<String, WorkerTypeSpecification> getWorkerTypeSpecifications() { return workerTypeSpecifications; }
    public void setWorkerTypeSpecifications(Map<String, WorkerTypeSpecification> workerTypeSpecifications) { this.workerTypeSpecifications = workerTypeSpecifications; }
    public MonitoringConfiguration getMonitoringConfiguration() { return monitoringConfiguration; }
    public void setMonitoringConfiguration(MonitoringConfiguration monitoringConfiguration) { this.monitoringConfiguration = monitoringConfiguration; }
    public List<RuntimeConfiguration> getRuntimeConfiguration() { return runtimeConfiguration; }
    public void setRuntimeConfiguration(List<RuntimeConfiguration> runtimeConfiguration) { this.runtimeConfiguration = runtimeConfiguration; }
    public SchedulerConfiguration getSchedulerConfiguration() { return schedulerConfiguration; }
    public void setSchedulerConfiguration(SchedulerConfiguration schedulerConfiguration) { this.schedulerConfiguration = schedulerConfiguration; }
    public DiskEncryptionConfiguration getDiskEncryptionConfiguration() { return diskEncryptionConfiguration; }
    public void setDiskEncryptionConfiguration(DiskEncryptionConfiguration diskEncryptionConfiguration) { this.diskEncryptionConfiguration = diskEncryptionConfiguration; }
    public InteractiveConfiguration getInteractiveConfiguration() { return interactiveConfiguration; }
    public void setInteractiveConfiguration(InteractiveConfiguration interactiveConfiguration) { this.interactiveConfiguration = interactiveConfiguration; }
    public IdentityCenterConfiguration getIdentityCenterConfiguration() { return identityCenterConfiguration; }
    public void setIdentityCenterConfiguration(IdentityCenterConfiguration identityCenterConfiguration) { this.identityCenterConfiguration = identityCenterConfiguration; }
    public JobLevelCostAllocationConfiguration getJobLevelCostAllocationConfiguration() { return jobLevelCostAllocationConfiguration; }
    public void setJobLevelCostAllocationConfiguration(JobLevelCostAllocationConfiguration jobLevelCostAllocationConfiguration) { this.jobLevelCostAllocationConfiguration = jobLevelCostAllocationConfiguration; }
}
