package io.github.hectorvent.floci.services.appconfig;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.appconfig.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;

@ApplicationScoped
public class AppConfigService {
    private static final Logger LOG = Logger.getLogger(AppConfigService.class);

    private final StorageBackend<String, Application> applicationStore;
    private final StorageBackend<String, Environment> environmentStore;
    private final StorageBackend<String, ConfigurationProfile> profileStore;
    private final StorageBackend<String, DeploymentStrategy> strategyStore;
    private final StorageBackend<String, HostedConfigurationVersion> versionStore;
    private final StorageBackend<String, Deployment> deploymentStore;
    private final StorageBackend<String, String> activeConfigStore; // envId::profileId -> versionNumber

    @Inject
    public AppConfigService(StorageFactory storageFactory, EmulatorConfig config) {
        this.applicationStore = storageFactory.create("appconfig", "appconfig-applications.json", new TypeReference<>() {});
        this.environmentStore = storageFactory.create("appconfig", "appconfig-environments.json", new TypeReference<>() {});
        this.profileStore = storageFactory.create("appconfig", "appconfig-profiles.json", new TypeReference<>() {});
        this.strategyStore = storageFactory.create("appconfig", "appconfig-strategies.json", new TypeReference<>() {});
        this.versionStore = storageFactory.create("appconfig", "appconfig-versions.json", new TypeReference<>() {});
        this.deploymentStore = storageFactory.create("appconfig", "appconfig-deployments.json", new TypeReference<>() {});
        this.activeConfigStore = storageFactory.create("appconfig", "appconfig-active-configs.json", new TypeReference<>() {});
    }

    // ──────────────────────────── Application ────────────────────────────

    public Application createApplication(Map<String, Object> request) {
        Application app = new Application();
        app.setId(shortId(7));
        app.setName((String) request.get("Name"));
        app.setDescription((String) request.get("Description"));
        applicationStore.put(app.getId(), app);
        return app;
    }

    public Application getApplication(String id) {
        return applicationStore.get(id).orElseThrow(() -> new AwsException("ResourceNotFoundException", "Application not found", 404));
    }

    public List<Application> listApplications() {
        return applicationStore.scan(k -> true);
    }

    public void deleteApplication(String id) {
        applicationStore.delete(id);
    }

    // ──────────────────────────── Environment ────────────────────────────

    public Environment createEnvironment(String appId, Map<String, Object> request) {
        getApplication(appId);
        Environment env = new Environment();
        env.setId(shortId(7));
        env.setApplicationId(appId);
        env.setName((String) request.get("Name"));
        env.setDescription((String) request.get("Description"));
        env.setState("READY");
        environmentStore.put(env.getId(), env);
        return env;
    }

    public Environment getEnvironment(String appId, String envId) {
        Environment env = environmentStore.get(envId).orElseThrow(() -> new AwsException("ResourceNotFoundException", "Environment not found", 404));
        if (!env.getApplicationId().equals(appId)) throw new AwsException("ResourceNotFoundException", "Environment not found in this application", 404);
        return env;
    }

    public List<Environment> listEnvironments(String appId) {
        return environmentStore.scan(k -> true).stream()
                .filter(e -> e.getApplicationId().equals(appId))
                .toList();
    }

    // ──────────────────────────── Configuration Profile ────────────────────────────

    public ConfigurationProfile createConfigurationProfile(String appId, Map<String, Object> request) {
        getApplication(appId);
        ConfigurationProfile profile = new ConfigurationProfile();
        profile.setId(shortId(7));
        profile.setApplicationId(appId);
        profile.setName((String) request.get("Name"));
        profile.setDescription((String) request.get("Description"));
        profile.setLocationUri((String) request.get("LocationUri"));
        profile.setType((String) request.get("Type"));
        profileStore.put(profile.getId(), profile);
        return profile;
    }

    public ConfigurationProfile getConfigurationProfile(String appId, String profileId) {
        ConfigurationProfile profile = profileStore.get(profileId).orElseThrow(() -> new AwsException("ResourceNotFoundException", "Configuration profile not found", 404));
        if (!profile.getApplicationId().equals(appId)) throw new AwsException("ResourceNotFoundException", "Profile not found in this application", 404);
        return profile;
    }

    public List<ConfigurationProfile> listConfigurationProfiles(String appId) {
        return profileStore.scan(k -> true).stream()
                .filter(p -> p.getApplicationId().equals(appId))
                .toList();
    }

    // ──────────────────────────── Hosted Configuration Version ────────────────────────────

    public HostedConfigurationVersion createHostedConfigurationVersion(String appId, String profileId, byte[] content, String contentType, String description) {
        getConfigurationProfile(appId, profileId);
        String prefix = appId + "::" + profileId + "::";
        int nextVersion = versionStore.scan(k -> k.startsWith(prefix))
                .stream().mapToInt(HostedConfigurationVersion::getVersionNumber).max().orElse(0) + 1;

        HostedConfigurationVersion version = new HostedConfigurationVersion();
        version.setApplicationId(appId);
        version.setConfigurationProfileId(profileId);
        version.setVersionNumber(nextVersion);
        version.setContent(content);
        version.setContentType(contentType);
        version.setDescription(description);

        versionStore.put(prefix + nextVersion, version);
        return version;
    }

    public HostedConfigurationVersion getHostedConfigurationVersion(String appId, String profileId, int versionNumber) {
        return versionStore.get(appId + "::" + profileId + "::" + versionNumber)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Hosted configuration version not found", 404));
    }

    // ──────────────────────────── Deployment Strategy ────────────────────────────

    public DeploymentStrategy createDeploymentStrategy(Map<String, Object> request) {
        DeploymentStrategy strategy = new DeploymentStrategy();
        strategy.setId(shortId(7));
        strategy.setName((String) request.get("Name"));
        strategy.setDescription((String) request.get("Description"));
        strategy.setDeploymentDurationInMinutes((Integer) request.getOrDefault("DeploymentDurationInMinutes", 0));
        strategy.setGrowthFactor(((Number) request.getOrDefault("GrowthFactor", 100.0f)).floatValue());
        strategy.setFinalBakeTimeInMinutes((Integer) request.getOrDefault("FinalBakeTimeInMinutes", 0));
        strategy.setGrowthType((String) request.getOrDefault("GrowthType", "LINEAR"));
        strategy.setReplicateTo((String) request.getOrDefault("ReplicateTo", "NONE"));
        strategyStore.put(strategy.getId(), strategy);
        return strategy;
    }

    public DeploymentStrategy getDeploymentStrategy(String id) {
        // AWS predefined built-in strategies
        DeploymentStrategy builtin = builtinStrategy(id);
        if (builtin != null) return builtin;
        return strategyStore.get(id).orElseThrow(() -> new AwsException("ResourceNotFoundException", "Deployment strategy not found", 404));
    }

    private static DeploymentStrategy builtinStrategy(String id) {
        return switch (id) {
            case "AppConfig.AllAtOnce" -> {
                DeploymentStrategy s = new DeploymentStrategy();
                s.setId(id); s.setName(id);
                s.setDescription("Quick");
                s.setDeploymentDurationInMinutes(0); s.setGrowthFactor(100f);
                s.setFinalBakeTimeInMinutes(10); s.setGrowthType("LINEAR");
                s.setReplicateTo("NONE");
                yield s;
            }
            case "AppConfig.Linear50PercentEvery30Seconds" -> {
                DeploymentStrategy s = new DeploymentStrategy();
                s.setId(id); s.setName(id);
                s.setDescription("Test/Demo");
                s.setDeploymentDurationInMinutes(1); s.setGrowthFactor(50f);
                s.setFinalBakeTimeInMinutes(1); s.setGrowthType("LINEAR");
                s.setReplicateTo("NONE");
                yield s;
            }
            case "AppConfig.Canary10Percent20Minutes" -> {
                DeploymentStrategy s = new DeploymentStrategy();
                s.setId(id); s.setName(id);
                s.setDescription("AWS Recommended");
                s.setDeploymentDurationInMinutes(20); s.setGrowthFactor(10f);
                s.setFinalBakeTimeInMinutes(10); s.setGrowthType("EXPONENTIAL");
                s.setReplicateTo("NONE");
                yield s;
            }
            default -> null;
        };
    }

    // ──────────────────────────── Deployment ────────────────────────────

    public Deployment startDeployment(String appId, String envId, Map<String, Object> request) {
        getEnvironment(appId, envId);
        String profileId = (String) request.get("ConfigurationProfileId");
        String version = (String) request.get("ConfigurationVersion");
        String strategyId = (String) request.get("DeploymentStrategyId");

        getConfigurationProfile(appId, profileId);
        getDeploymentStrategy(strategyId);

        Deployment deployment = new Deployment();
        deployment.setApplicationId(appId);
        deployment.setEnvironmentId(envId);
        deployment.setConfigurationProfileId(profileId);
        deployment.setConfigurationVersion(version);
        deployment.setDeploymentStrategyId(strategyId);
        deployment.setDeploymentNumber(deploymentStore.keys().size() + 1);
        deployment.setState("COMPLETE"); // Synchronous immediate deployment
        deployment.setDescription((String) request.get("Description"));

        deploymentStore.put(appId + "::" + envId + "::" + deployment.getDeploymentNumber(), deployment);

        // Update active configuration
        activeConfigStore.put(envId + "::" + profileId, version);

        LOG.infov("Started deployment for app {0}, env {1}, profile {2}, version {3}. State: COMPLETE", appId, envId, profileId, version);
        return deployment;
    }

    public Deployment getDeployment(String appId, String envId, int deploymentNumber) {
        return deploymentStore.get(appId + "::" + envId + "::" + deploymentNumber)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Deployment not found", 404));
    }

    public String getActiveVersion(String envId, String profileId) {
        return activeConfigStore.get(envId + "::" + profileId).orElse(null);
    }

    // ──────────────────────────── Tags ────────────────────────────

    public Map<String, String> getApplicationTags(String appId) {
        return getApplication(appId).getTags();
    }

    public void tagApplication(String appId, Map<String, String> tags) {
        Application app = getApplication(appId);
        app.getTags().putAll(tags);
        applicationStore.put(appId, app);
    }

    public void untagApplication(String appId, List<String> tagKeys) {
        Application app = getApplication(appId);
        tagKeys.forEach(app.getTags()::remove);
        applicationStore.put(appId, app);
    }

    private static String shortId(int length) {
        return UUID.randomUUID().toString().replace("-", "").substring(0, length);
    }
}
