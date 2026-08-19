package io.github.hectorvent.floci.services.emrserverless.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.Map;

@RegisterForReflection
public class CreateApplicationRequest {
    private String architecture;
    private AutoStartConfiguration autoStartConfiguration;
    private AutoStopConfiguration autoStopConfiguration;
    private String clientToken;
    private ImageConfiguration imageConfiguration;
    private Map<String, InitialCapacityConfig> initialCapacity;
    private MaximumCapacity maximumCapacity;
    private String name;
    private NetworkConfiguration networkConfiguration;
    private String releaseLabel;
    private Map<String, String> tags;
    private String type;
    private Map<String, WorkerTypeSpecification> workerTypeSpecifications;

    public String getArchitecture() { return architecture; }
    public void setArchitecture(String architecture) { this.architecture = architecture; }
    public AutoStartConfiguration getAutoStartConfiguration() { return autoStartConfiguration; }
    public void setAutoStartConfiguration(AutoStartConfiguration autoStartConfiguration) { this.autoStartConfiguration = autoStartConfiguration; }
    public AutoStopConfiguration getAutoStopConfiguration() { return autoStopConfiguration; }
    public void setAutoStopConfiguration(AutoStopConfiguration autoStopConfiguration) { this.autoStopConfiguration = autoStopConfiguration; }
    public String getClientToken() { return clientToken; }
    public void setClientToken(String clientToken) { this.clientToken = clientToken; }
    public ImageConfiguration getImageConfiguration() { return imageConfiguration; }
    public void setImageConfiguration(ImageConfiguration imageConfiguration) { this.imageConfiguration = imageConfiguration; }
    public Map<String, InitialCapacityConfig> getInitialCapacity() { return initialCapacity; }
    public void setInitialCapacity(Map<String, InitialCapacityConfig> initialCapacity) { this.initialCapacity = initialCapacity; }
    public MaximumCapacity getMaximumCapacity() { return maximumCapacity; }
    public void setMaximumCapacity(MaximumCapacity maximumCapacity) { this.maximumCapacity = maximumCapacity; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public NetworkConfiguration getNetworkConfiguration() { return networkConfiguration; }
    public void setNetworkConfiguration(NetworkConfiguration networkConfiguration) { this.networkConfiguration = networkConfiguration; }
    public String getReleaseLabel() { return releaseLabel; }
    public void setReleaseLabel(String releaseLabel) { this.releaseLabel = releaseLabel; }
    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Map<String, WorkerTypeSpecification> getWorkerTypeSpecifications() { return workerTypeSpecifications; }
    public void setWorkerTypeSpecifications(Map<String, WorkerTypeSpecification> workerTypeSpecifications) { this.workerTypeSpecifications = workerTypeSpecifications; }
}
