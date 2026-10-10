package io.github.hectorvent.floci.services.appconfig;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.appconfig.model.Application;
import io.github.hectorvent.floci.services.appconfig.model.ConfigurationProfile;
import io.github.hectorvent.floci.services.appconfig.model.Deployment;
import io.github.hectorvent.floci.services.appconfig.model.DeploymentStrategy;
import io.github.hectorvent.floci.services.appconfig.model.DeploymentSummary;
import io.github.hectorvent.floci.services.appconfig.model.Environment;
import io.github.hectorvent.floci.services.appconfig.model.HostedConfigurationVersion;
import io.github.hectorvent.floci.services.appconfig.model.HostedConfigurationVersionSummary;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    private final Map<String, DeploymentPageToken> deploymentPageTokens = new ConcurrentHashMap<>();
    private static final int MAX_DEPLOYMENT_PAGE_TOKENS = 1000;

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

    public Application getApplication(String idOrName) {
        return resolveApplication(idOrName);
    }

    /** The application whose ID or name is {@code idOrName}. */
    public Application resolveApplication(String idOrName) {
        return applicationStore.get(idOrName)
                .or(() -> listApplications().stream().filter(a -> idOrName.equals(a.getName())).findFirst())
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Application not found", 404));
    }

    public List<Application> listApplications() {
        return applicationStore.scan(k -> true);
    }

    public void deleteApplication(String idOrName) {
        applicationStore.get(idOrName)
                .or(() -> listApplications().stream().filter(a -> idOrName.equals(a.getName())).findFirst())
                .ifPresent(application -> applicationStore.delete(application.getId()));
    }

    // ──────────────────────────── Environment ────────────────────────────

    public Environment createEnvironment(String applicationIdentifier, Map<String, Object> request) {
        String appId = resolveApplication(applicationIdentifier).getId();
        Environment env = new Environment();
        env.setId(shortId(7));
        env.setApplicationId(appId);
        env.setName((String) request.get("Name"));
        env.setDescription((String) request.get("Description"));
        env.setState("READY");
        environmentStore.put(env.getId(), env);
        return env;
    }

    public Environment getEnvironment(String applicationIdentifier, String environmentIdentifier) {
        String appId = resolveApplication(applicationIdentifier).getId();
        return resolveEnvironment(appId, environmentIdentifier);
    }

    /** The environment of {@code appId} whose ID or name is {@code idOrName}. */
    public Environment resolveEnvironment(String appId, String idOrName) {
        // An exact ID always wins over another environment's name, and a direct lookup skips the scan.
        return environmentStore.get(idOrName)
                .filter(e -> appId.equals(e.getApplicationId()))
                .or(() -> listEnvironments(appId).stream().filter(e -> idOrName.equals(e.getName())).findFirst())
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Environment not found", 404));
    }

    public List<Environment> listEnvironments(String applicationIdentifier) {
        String appId = resolveApplication(applicationIdentifier).getId();
        return environmentStore.scan(k -> true).stream()
                .filter(e -> e.getApplicationId().equals(appId))
                .toList();
    }

    // ──────────────────────────── Configuration Profile ────────────────────────────

    public ConfigurationProfile createConfigurationProfile(String applicationIdentifier, Map<String, Object> request) {
        String appId = resolveApplication(applicationIdentifier).getId();
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

    public ConfigurationProfile getConfigurationProfile(String applicationIdentifier, String profileIdentifier) {
        String appId = resolveApplication(applicationIdentifier).getId();
        return resolveConfigurationProfile(appId, profileIdentifier);
    }

    /** The configuration profile of {@code appId} whose ID or name is {@code idOrName}. */
    public ConfigurationProfile resolveConfigurationProfile(String appId, String idOrName) {
        return profileStore.get(idOrName)
                .filter(p -> appId.equals(p.getApplicationId()))
                .or(() -> listConfigurationProfiles(appId).stream().filter(p -> idOrName.equals(p.getName())).findFirst())
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Configuration profile not found", 404));
    }

    public List<ConfigurationProfile> listConfigurationProfiles(String applicationIdentifier) {
        String appId = resolveApplication(applicationIdentifier).getId();
        return profileStore.scan(k -> true).stream()
                .filter(p -> p.getApplicationId().equals(appId))
                .toList();
    }

    public void deleteConfigurationProfile(String applicationIdentifier, String profileIdentifier) {
        String appId = resolveApplication(applicationIdentifier).getId();
        // Unlike deleteApplication (a single, unscoped ID), a profile is nested under an
        // application - a mismatched appId must not be able to delete another application's
        // profile just because its bare profileId is guessed/known. A profileId that doesn't
        // exist at all is still an idempotent no-op, matching deleteApplication's convention;
        // only an existing-but-wrongly-scoped one is rejected.
        ConfigurationProfile profile = profileStore.get(profileIdentifier).orElse(null);
        if (profile != null) {
            if (!profile.getApplicationId().equals(appId)) {
                throw new AwsException("ResourceNotFoundException", "Configuration profile not found in this application", 404);
            }
        } else {
            profile = listConfigurationProfiles(appId).stream()
                    .filter(candidate -> profileIdentifier.equals(candidate.getName()))
                    .findFirst()
                    .orElse(null);
        }
        if (profile == null) {
            return;
        }
        String profileId = profile.getId();
        // A hosted configuration version isn't independently addressable outside its profile's
        // lifecycle - cascade the delete so a caller can't still fetch versions for a profile
        // that's supposedly gone.
        String versionPrefix = appId + "::" + profileId + "::";
        versionStore.keys().stream()
                .filter(k -> k.startsWith(versionPrefix))
                .toList()
                .forEach(versionStore::delete);
        profileStore.delete(profileId);
    }

    // ──────────────────────────── Hosted Configuration Version ────────────────────────────

    public HostedConfigurationVersion createHostedConfigurationVersion(String applicationIdentifier,
                                                                         String profileIdentifier,
                                                                         byte[] content,
                                                                         String contentType,
                                                                         String description) {
        String appId = resolveApplication(applicationIdentifier).getId();
        String profileId = resolveConfigurationProfile(appId, profileIdentifier).getId();
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

    public HostedConfigurationVersion getHostedConfigurationVersion(String applicationIdentifier,
                                                                      String profileIdentifier,
                                                                      int versionNumber) {
        String appId = resolveApplication(applicationIdentifier).getId();
        String profileId = resolveConfigurationProfile(appId, profileIdentifier).getId();
        return versionStore.get(appId + "::" + profileId + "::" + versionNumber)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Hosted configuration version not found", 404));
    }

    public List<HostedConfigurationVersionSummary> listHostedConfigurationVersions(String applicationIdentifier,
                                                                                     String profileIdentifier) {
        String appId = resolveApplication(applicationIdentifier).getId();
        String profileId = resolveConfigurationProfile(appId, profileIdentifier).getId();
        String prefix = appId + "::" + profileId + "::";
        return versionStore.scan(k -> k.startsWith(prefix))
                .stream()
                .sorted(Comparator.comparingInt(HostedConfigurationVersion::getVersionNumber))
                .map(v -> {
                    HostedConfigurationVersionSummary s = new HostedConfigurationVersionSummary();
                    s.setApplicationId(v.getApplicationId());
                    s.setConfigurationProfileId(v.getConfigurationProfileId());
                    s.setVersionNumber(v.getVersionNumber());
                    s.setDescription(v.getDescription());
                    s.setContentType(v.getContentType());
                    return s;
                })
                .toList();
    }

    public void deleteHostedConfigurationVersion(String applicationIdentifier,
                                                  String profileIdentifier,
                                                  int versionNumber) {
        String appId = resolveApplication(applicationIdentifier).getId();
        String profileId = resolveConfigurationProfile(appId, profileIdentifier).getId();
        versionStore.delete(appId + "::" + profileId + "::" + versionNumber);
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

    public List<DeploymentStrategy> listDeploymentStrategies() {
        // Real AWS's ListDeploymentStrategies includes the predefined strategies alongside
        // custom ones (confirmed via the API reference's own sample response) - the predefined
        // ones aren't in strategyStore at all (see getDeploymentStrategy's builtinStrategy check
        // above), so they need to be added explicitly here too.
        List<DeploymentStrategy> result = new ArrayList<>(List.of(
                builtinStrategy("AppConfig.AllAtOnce"),
                builtinStrategy("AppConfig.Linear50PercentEvery30Seconds"),
                builtinStrategy("AppConfig.Canary10Percent20Minutes")));
        result.addAll(strategyStore.scan(k -> true));
        return result;
    }

    public void deleteDeploymentStrategy(String id) {
        // Predefined strategies aren't real stored resources (see builtinStrategy above) -
        // silently no-op'ing here would report success for a delete that did nothing, and the
        // "deleted" strategy would still show up in every subsequent Get/List call.
        if (builtinStrategy(id) != null) {
            throw new AwsException("BadRequestException",
                    "Predefined deployment strategy " + id + " cannot be deleted.", 400);
        }
        strategyStore.delete(id);
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

    public Deployment startDeployment(String applicationIdentifier,
                                      String environmentIdentifier,
                                      Map<String, Object> request) {
        String appId = resolveApplication(applicationIdentifier).getId();
        String envId = resolveEnvironment(appId, environmentIdentifier).getId();
        String profileId = resolveConfigurationProfile(
                appId, (String) request.get("ConfigurationProfileId")).getId();
        String version = (String) request.get("ConfigurationVersion");
        String strategyId = (String) request.get("DeploymentStrategyId");

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

    public Deployment getDeployment(String applicationIdentifier,
                                    String environmentIdentifier,
                                    int deploymentNumber) {
        String appId = resolveApplication(applicationIdentifier).getId();
        String envId = resolveEnvironment(appId, environmentIdentifier).getId();
        return deploymentStore.get(appId + "::" + envId + "::" + deploymentNumber)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Deployment not found", 404));
    }

    public DeploymentPage listDeployments(String applicationIdentifier,
                                          String environmentIdentifier,
                                          Integer maxResults,
                                          String nextToken) {
        String appId = resolveApplication(applicationIdentifier).getId();
        String envId = resolveEnvironment(appId, environmentIdentifier).getId();
        int pageSize = maxResults == null ? 50 : maxResults;
        if (pageSize < 1 || pageSize > 50) {
            throw new AwsException("BadRequestException", "max_results must be between 1 and 50", 400);
        }

        String scope = appId + "::" + envId;
        Integer afterDeploymentNumber = null;
        if (nextToken != null) {
            DeploymentPageToken token = deploymentPageTokens.get(nextToken);
            if (token == null || !token.scope().equals(scope)) {
                throw new AwsException("BadRequestException", "Invalid next_token", 400);
            }
            afterDeploymentNumber = token.lastDeploymentNumber();
        }

        List<Deployment> deployments = deploymentStore.scan(k -> true).stream()
                .filter(deployment -> appId.equals(deployment.getApplicationId()))
                .filter(deployment -> envId.equals(deployment.getEnvironmentId()))
                .sorted(Comparator.comparingInt(Deployment::getDeploymentNumber).reversed())
                .toList();

        int start = 0;
        if (afterDeploymentNumber != null) {
            while (start < deployments.size()
                    && deployments.get(start).getDeploymentNumber() >= afterDeploymentNumber) {
                start++;
            }
        }

        int end = Math.min(start + pageSize, deployments.size());
        List<DeploymentSummary> items = deployments.subList(start, end).stream()
                .map(this::toDeploymentSummary)
                .toList();
        String resultToken = null;
        if (end < deployments.size()) {
            resultToken = createDeploymentPageToken(scope,
                    items.get(items.size() - 1).getDeploymentNumber());
        } else if (nextToken != null) {
            deploymentPageTokens.remove(nextToken);
        }
        return new DeploymentPage(items, resultToken);
    }

    private DeploymentSummary toDeploymentSummary(Deployment deployment) {
        DeploymentSummary summary = new DeploymentSummary();
        summary.setConfigurationProfileId(deployment.getConfigurationProfileId());
        summary.setConfigurationVersion(deployment.getConfigurationVersion());
        summary.setDeploymentNumber(deployment.getDeploymentNumber());
        summary.setState(deployment.getState());
        summary.setConfigurationName(deployment.getConfigurationName());
        ConfigurationProfile profile = profileStore.get(deployment.getConfigurationProfileId()).orElse(null);
        if (profile != null) {
            summary.setConfigurationName(profile.getName());
            summary.setType(profile.getType());
        }
        DeploymentStrategy strategy = getDeploymentStrategy(deployment.getDeploymentStrategyId());
        summary.setDeploymentDurationInMinutes(strategy.getDeploymentDurationInMinutes());
        summary.setFinalBakeTimeInMinutes(strategy.getFinalBakeTimeInMinutes());
        summary.setGrowthFactor(strategy.getGrowthFactor());
        summary.setGrowthType(strategy.getGrowthType());
        summary.setPercentageComplete("COMPLETE".equals(deployment.getState()) ? 100.0f : 0.0f);
        return summary;
    }

    private String createDeploymentPageToken(String scope, int lastDeploymentNumber) {
        while (deploymentPageTokens.size() >= MAX_DEPLOYMENT_PAGE_TOKENS) {
            deploymentPageTokens.keySet().stream().findFirst().ifPresent(deploymentPageTokens::remove);
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        deploymentPageTokens.put(token, new DeploymentPageToken(scope, lastDeploymentNumber));
        return token;
    }

    private record DeploymentPageToken(String scope, int lastDeploymentNumber) {
    }

    public record DeploymentPage(List<DeploymentSummary> items, String nextToken) {
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
        applicationStore.put(app.getId(), app);
    }

    public void untagApplication(String appId, List<String> tagKeys) {
        Application app = getApplication(appId);
        tagKeys.forEach(app.getTags()::remove);
        applicationStore.put(app.getId(), app);
    }

    private static String shortId(int length) {
        return UUID.randomUUID().toString().replace("-", "").substring(0, length);
    }
}
