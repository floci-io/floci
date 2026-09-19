package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

@RegisterForReflection
public class ContainerDefinition {

    private String name;
    private String image;
    private Integer cpu;
    private Integer memory;
    private Integer memoryReservation;
    private boolean essential = true;
    private List<PortMapping> portMappings;
    private List<KeyValuePair> environment;
    private List<EnvironmentFile> environmentFiles;
    private List<Secret> secrets;
    private List<String> command;
    private List<String> entryPoint;
    private List<MountPoint> mountPoints;
    private List<VolumeFrom> volumesFrom;
    private List<ContainerDependency> dependsOn;
    private LogConfiguration logConfiguration;
    private FirelensConfiguration firelensConfiguration;
    private HealthCheck healthCheck;
    private Integer startTimeout;
    private Integer stopTimeout;
    private String user;
    private String workingDirectory;
    private String hostname;
    private Boolean readonlyRootFilesystem;
    private Boolean privileged;
    private Boolean disableNetworking;
    private Boolean interactive;
    private Boolean pseudoTerminal;
    private List<String> links;
    private List<String> dnsServers;
    private List<String> dnsSearchDomains;
    private List<String> dockerSecurityOptions;
    private Map<String, String> dockerLabels;
    private String repositoryCredentialsParameter;
    private Map<String, Object> unparsed;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }

    public Integer getCpu() { return cpu; }
    public void setCpu(Integer cpu) { this.cpu = cpu; }

    public Integer getMemory() { return memory; }
    public void setMemory(Integer memory) { this.memory = memory; }

    public Integer getMemoryReservation() { return memoryReservation; }
    public void setMemoryReservation(Integer memoryReservation) { this.memoryReservation = memoryReservation; }

    public boolean isEssential() { return essential; }
    public void setEssential(boolean essential) { this.essential = essential; }

    public List<PortMapping> getPortMappings() { return portMappings; }
    public void setPortMappings(List<PortMapping> portMappings) { this.portMappings = portMappings; }

    public List<KeyValuePair> getEnvironment() { return environment; }
    public void setEnvironment(List<KeyValuePair> environment) { this.environment = environment; }

    public List<Secret> getSecrets() { return secrets; }
    public void setSecrets(List<Secret> secrets) { this.secrets = secrets; }

    public List<String> getCommand() { return command; }
    public void setCommand(List<String> command) { this.command = command; }

    public List<String> getEntryPoint() { return entryPoint; }
    public void setEntryPoint(List<String> entryPoint) { this.entryPoint = entryPoint; }

    public List<MountPoint> getMountPoints() { return mountPoints; }
    public void setMountPoints(List<MountPoint> mountPoints) { this.mountPoints = mountPoints; }

    public List<VolumeFrom> getVolumesFrom() { return volumesFrom; }
    public void setVolumesFrom(List<VolumeFrom> volumesFrom) { this.volumesFrom = volumesFrom; }

    public LogConfiguration getLogConfiguration() { return logConfiguration; }
    public void setLogConfiguration(LogConfiguration logConfiguration) { this.logConfiguration = logConfiguration; }

    public FirelensConfiguration getFirelensConfiguration() { return firelensConfiguration; }
    public void setFirelensConfiguration(FirelensConfiguration firelensConfiguration) {
        this.firelensConfiguration = firelensConfiguration;
    }
    public HealthCheck getHealthCheck() { return healthCheck; }
    public void setHealthCheck(HealthCheck healthCheck) { this.healthCheck = healthCheck; }

    public List<EnvironmentFile> getEnvironmentFiles() { return environmentFiles; }
    public void setEnvironmentFiles(List<EnvironmentFile> environmentFiles) {
        this.environmentFiles = environmentFiles;
    }

    public List<ContainerDependency> getDependsOn() { return dependsOn; }
    public void setDependsOn(List<ContainerDependency> dependsOn) { this.dependsOn = dependsOn; }

    public Integer getStartTimeout() { return startTimeout; }
    public void setStartTimeout(Integer startTimeout) { this.startTimeout = startTimeout; }

    public Integer getStopTimeout() { return stopTimeout; }
    public void setStopTimeout(Integer stopTimeout) { this.stopTimeout = stopTimeout; }

    public String getUser() { return user; }
    public void setUser(String user) { this.user = user; }

    public String getWorkingDirectory() { return workingDirectory; }
    public void setWorkingDirectory(String workingDirectory) { this.workingDirectory = workingDirectory; }

    public String getHostname() { return hostname; }
    public void setHostname(String hostname) { this.hostname = hostname; }

    public Boolean getReadonlyRootFilesystem() { return readonlyRootFilesystem; }
    public void setReadonlyRootFilesystem(Boolean readonlyRootFilesystem) {
        this.readonlyRootFilesystem = readonlyRootFilesystem;
    }

    public Boolean getPrivileged() { return privileged; }
    public void setPrivileged(Boolean privileged) { this.privileged = privileged; }

    public Boolean getDisableNetworking() { return disableNetworking; }
    public void setDisableNetworking(Boolean disableNetworking) { this.disableNetworking = disableNetworking; }

    public Boolean getInteractive() { return interactive; }
    public void setInteractive(Boolean interactive) { this.interactive = interactive; }

    public Boolean getPseudoTerminal() { return pseudoTerminal; }
    public void setPseudoTerminal(Boolean pseudoTerminal) { this.pseudoTerminal = pseudoTerminal; }

    public List<String> getLinks() { return links; }
    public void setLinks(List<String> links) { this.links = links; }

    public List<String> getDnsServers() { return dnsServers; }
    public void setDnsServers(List<String> dnsServers) { this.dnsServers = dnsServers; }

    public List<String> getDnsSearchDomains() { return dnsSearchDomains; }
    public void setDnsSearchDomains(List<String> dnsSearchDomains) { this.dnsSearchDomains = dnsSearchDomains; }

    public List<String> getDockerSecurityOptions() { return dockerSecurityOptions; }
    public void setDockerSecurityOptions(List<String> dockerSecurityOptions) {
        this.dockerSecurityOptions = dockerSecurityOptions;
    }

    public Map<String, String> getDockerLabels() { return dockerLabels; }
    public void setDockerLabels(Map<String, String> dockerLabels) { this.dockerLabels = dockerLabels; }

    public String getRepositoryCredentialsParameter() { return repositoryCredentialsParameter; }
    public void setRepositoryCredentialsParameter(String repositoryCredentialsParameter) {
        this.repositoryCredentialsParameter = repositoryCredentialsParameter;
    }

    public Map<String, Object> getUnparsed() { return unparsed; }
    public void setUnparsed(Map<String, Object> unparsed) { this.unparsed = unparsed; }
}
