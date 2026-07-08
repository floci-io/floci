package io.github.hectorvent.floci.services.elasticbeanstalk;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.StorageBackedMap;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.elasticbeanstalk.model.BeanstalkApplication;
import io.github.hectorvent.floci.services.elasticbeanstalk.model.BeanstalkApplicationVersion;
import io.github.hectorvent.floci.services.elasticbeanstalk.model.BeanstalkEnvironment;
import io.github.hectorvent.floci.services.elasticbeanstalk.model.ConfigurationOptionSetting;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ElasticBeanstalkService implements Resettable {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String LOWER_ALNUM = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final String DEFAULT_SOLUTION_STACK =
            "64bit Amazon Linux 2023 v4.3.0 running Docker";

    private final RegionResolver regionResolver;
    private final StorageFactory storageFactory;
    private Map<String, BeanstalkApplication> applications = new ConcurrentHashMap<>();
    private Map<String, BeanstalkApplicationVersion> versions = new ConcurrentHashMap<>();
    private Map<String, BeanstalkEnvironment> environments = new ConcurrentHashMap<>();

    @Inject
    ElasticBeanstalkService(RegionResolver regionResolver, StorageFactory storageFactory) {
        this.regionResolver = regionResolver;
        this.storageFactory = storageFactory;
    }

    @PostConstruct
    void initializeStorage() {
        if (storageFactory == null) {
            return; // keeps non-CDI unit tests working
        }
        this.applications = new StorageBackedMap<>(storageFactory.create("elasticbeanstalk",
                "elasticbeanstalk-applications.json",
                new TypeReference<Map<String, BeanstalkApplication>>() {}));
        this.versions = new StorageBackedMap<>(storageFactory.create("elasticbeanstalk",
                "elasticbeanstalk-versions.json",
                new TypeReference<Map<String, BeanstalkApplicationVersion>>() {}));
        this.environments = new StorageBackedMap<>(storageFactory.create("elasticbeanstalk",
                "elasticbeanstalk-environments.json",
                new TypeReference<Map<String, BeanstalkEnvironment>>() {}));
    }

    @Override
    public void clear() {
        applications.clear();
        versions.clear();
        environments.clear();
    }

    // Mutators are synchronized: StorageBackedMap has no atomic check-then-put, and
    // persistent backends return fresh copies on get, so every in-place mutation must be
    // re-put and the get-mutate-put sequences must not interleave.
    public synchronized BeanstalkApplication createApplication(String region, String name, String description,
                                                  Map<String, String> tags) {
        requireName(name, "ApplicationName");
        String key = appKey(region, name);
        if (applications.containsKey(key)) {
            throw new AwsException("InvalidParameterValue",
                    "Application " + name + " already exists.", 400);
        }
        Instant now = Instant.now();
        BeanstalkApplication app = new BeanstalkApplication();
        app.setApplicationName(name);
        app.setApplicationArn(regionResolver.buildArn("elasticbeanstalk", region, "application/" + name));
        app.setDescription(description);
        app.setDateCreated(now);
        app.setDateUpdated(now);
        app.getConfigurationTemplates().add("default");
        app.getTags().putAll(tags);
        applications.put(key, app);
        return app;
    }

    public Collection<BeanstalkApplication> describeApplications(String region, List<String> names) {
        if (names != null && !names.isEmpty()) {
            List<BeanstalkApplication> result = new ArrayList<>();
            for (String name : names) {
                BeanstalkApplication app = applications.get(appKey(region, name));
                if (app != null) {
                    result.add(app);
                }
            }
            return result;
        }
        return applications.entrySet().stream()
                .filter(e -> e.getKey().startsWith(region + "::"))
                .map(Map.Entry::getValue)
                .sorted(Comparator.comparing(BeanstalkApplication::getApplicationName))
                .toList();
    }

    public synchronized BeanstalkApplication updateApplication(String region, String name, String description) {
        BeanstalkApplication app = requireApplication(region, name);
        if (description != null) {
            app.setDescription(description);
        }
        app.setDateUpdated(Instant.now());
        applications.put(appKey(region, name), app);
        return app;
    }

    public synchronized void deleteApplication(String region, String name, boolean terminateEnvByForce) {
        requireApplication(region, name);
        List<BeanstalkEnvironment> activeEnvironments = environments.entrySet().stream()
                .filter(e -> e.getKey().startsWith(region + "::"))
                .map(Map.Entry::getValue)
                .filter(env -> name.equals(env.getApplicationName()))
                .filter(env -> !"Terminated".equals(env.getStatus()))
                .toList();
        if (!activeEnvironments.isEmpty() && !terminateEnvByForce) {
            throw new AwsException("InvalidParameterValue",
                    "Application " + name + " has active environments.", 400);
        }
        for (BeanstalkEnvironment env : activeEnvironments) {
            terminateEnvironment(region, env.getEnvironmentName(), env.getEnvironmentId());
        }
        applications.remove(appKey(region, name));
        versions.entrySet().removeIf(e -> e.getKey().startsWith(versionPrefix(region, name)));
    }

    public synchronized BeanstalkApplicationVersion createApplicationVersion(String region, String applicationName,
                                                               String versionLabel, String description,
                                                               boolean autoCreateApplication,
                                                               String sourceBundleBucket,
                                                               String sourceBundleKey,
                                                               Map<String, String> tags) {
        requireName(applicationName, "ApplicationName");
        requireName(versionLabel, "VersionLabel");
        BeanstalkApplication app = applications.get(appKey(region, applicationName));
        if (app == null && autoCreateApplication) {
            app = createApplication(region, applicationName, null, Map.of());
        }
        if (app == null) {
            throw new AwsException("InvalidParameterValue",
                    "No Application named " + applicationName + " found.", 400);
        }
        String key = versionKey(region, applicationName, versionLabel);
        if (versions.containsKey(key)) {
            throw new AwsException("InvalidParameterValue",
                    "Application Version " + versionLabel + " already exists.", 400);
        }
        Instant now = Instant.now();
        BeanstalkApplicationVersion version = new BeanstalkApplicationVersion();
        version.setApplicationName(applicationName);
        version.setVersionLabel(versionLabel);
        version.setDescription(description);
        version.setSourceBundleBucket(sourceBundleBucket);
        version.setSourceBundleKey(sourceBundleKey);
        version.setDateCreated(now);
        version.setDateUpdated(now);
        version.setStatus("Processed");
        version.getTags().putAll(tags);
        versions.put(key, version);
        app.getVersions().add(versionLabel);
        app.setDateUpdated(now);
        applications.put(appKey(region, applicationName), app);
        return version;
    }

    public Collection<BeanstalkApplicationVersion> describeApplicationVersions(String region,
                                                                              String applicationName,
                                                                              List<String> labels) {
        return versions.entrySet().stream()
                .filter(e -> e.getKey().startsWith(region + "::"))
                .map(Map.Entry::getValue)
                .filter(version -> applicationName == null || applicationName.equals(version.getApplicationName()))
                .filter(version -> labels == null || labels.isEmpty() || labels.contains(version.getVersionLabel()))
                .sorted(Comparator.comparing(BeanstalkApplicationVersion::getApplicationName)
                        .thenComparing(BeanstalkApplicationVersion::getVersionLabel))
                .toList();
    }

    public synchronized void deleteApplicationVersion(String region, String applicationName, String versionLabel) {
        requireName(applicationName, "ApplicationName");
        requireName(versionLabel, "VersionLabel");
        BeanstalkApplicationVersion removed = versions.remove(versionKey(region, applicationName, versionLabel));
        if (removed == null) {
            throw new AwsException("InvalidParameterValue",
                    "No Application Version named " + versionLabel + " found.", 400);
        }
        BeanstalkApplication app = applications.get(appKey(region, applicationName));
        if (app != null) {
            app.getVersions().remove(versionLabel);
            app.setDateUpdated(Instant.now());
            applications.put(appKey(region, applicationName), app);
        }
    }

    public synchronized BeanstalkEnvironment createEnvironment(String region, String applicationName, String environmentName,
                                                  String description, String cnamePrefix, String solutionStackName,
                                                  String platformArn, String templateName, String versionLabel,
                                                  List<ConfigurationOptionSetting> optionSettings,
                                                  Map<String, String> tags) {
        requireApplication(region, applicationName);
        if (environmentName == null || environmentName.isBlank()) {
            environmentName = generatedEnvironmentName(applicationName);
        }
        validateEnvironmentName(environmentName);
        if (environments.containsKey(envKey(region, environmentName))) {
            throw new AwsException("InvalidParameterValue",
                    "Environment " + environmentName + " already exists.", 400);
        }
        if (versionLabel != null && !versions.containsKey(versionKey(region, applicationName, versionLabel))) {
            throw new AwsException("InvalidParameterValue",
                    "No Application Version named " + versionLabel + " found.", 400);
        }

        Instant now = Instant.now();
        String envId = "e-" + randomLower(10);
        BeanstalkEnvironment env = new BeanstalkEnvironment();
        env.setApplicationName(applicationName);
        env.setEnvironmentId(envId);
        env.setEnvironmentName(environmentName);
        env.setEnvironmentArn(regionResolver.buildArn("elasticbeanstalk", region,
                "environment/" + applicationName + "/" + environmentName));
        env.setDescription(description);
        env.setCname(cname(cnamePrefix, environmentName));
        env.setEndpointUrl("awseb-" + envId + "." + region + ".elasticbeanstalk.local");
        env.setSolutionStackName(firstNonBlank(solutionStackName, DEFAULT_SOLUTION_STACK));
        env.setPlatformArn(platformArn);
        env.setTemplateName(templateName);
        env.setVersionLabel(versionLabel);
        env.setStatus("Ready");
        env.setHealth("Green");
        env.setHealthStatus("Ok");
        env.setDateCreated(now);
        env.setDateUpdated(now);
        env.getOptionSettings().addAll(optionSettings);
        env.getTags().putAll(tags);
        environments.put(envKey(region, environmentName), env);
        return env;
    }

    public Collection<BeanstalkEnvironment> describeEnvironments(String region, String applicationName,
                                                                 List<String> environmentNames,
                                                                 List<String> environmentIds,
                                                                 String versionLabel,
                                                                 boolean includeDeleted) {
        Set<String> names = environmentNames == null ? Set.of() : Set.copyOf(environmentNames);
        Set<String> ids = environmentIds == null ? Set.of() : Set.copyOf(environmentIds);
        return environments.entrySet().stream()
                .filter(e -> e.getKey().startsWith(region + "::"))
                .map(Map.Entry::getValue)
                .filter(env -> applicationName == null || applicationName.equals(env.getApplicationName()))
                .filter(env -> names.isEmpty() || names.contains(env.getEnvironmentName()))
                .filter(env -> ids.isEmpty() || ids.contains(env.getEnvironmentId()))
                .filter(env -> versionLabel == null || versionLabel.equals(env.getVersionLabel()))
                .filter(env -> includeDeleted || !"Terminated".equals(env.getStatus()))
                .sorted(Comparator.comparing(BeanstalkEnvironment::getEnvironmentName))
                .toList();
    }

    public synchronized BeanstalkEnvironment updateEnvironment(String region, String environmentName, String environmentId,
                                                  String description, String versionLabel,
                                                  String solutionStackName, String platformArn,
                                                  List<ConfigurationOptionSetting> optionSettings) {
        BeanstalkEnvironment env = requireEnvironment(region, environmentName, environmentId);
        if ("Terminated".equals(env.getStatus())) {
            throw new AwsException("InvalidParameterValue",
                    "Environment " + env.getEnvironmentName() + " is terminated.", 400);
        }
        if (versionLabel != null && !versions.containsKey(versionKey(region, env.getApplicationName(), versionLabel))) {
            throw new AwsException("InvalidParameterValue",
                    "No Application Version named " + versionLabel + " found.", 400);
        }
        if (description != null) {
            env.setDescription(description);
        }
        if (versionLabel != null) {
            env.setVersionLabel(versionLabel);
        }
        if (solutionStackName != null) {
            env.setSolutionStackName(solutionStackName);
        }
        if (platformArn != null) {
            env.setPlatformArn(platformArn);
        }
        if (!optionSettings.isEmpty()) {
            mergeOptionSettings(env.getOptionSettings(), optionSettings);
        }
        env.setStatus("Ready");
        env.setHealth("Green");
        env.setHealthStatus("Ok");
        env.setDateUpdated(Instant.now());
        environments.put(envKey(region, env.getEnvironmentName()), env);
        return env;
    }

    public synchronized BeanstalkEnvironment terminateEnvironment(String region, String environmentName, String environmentId) {
        BeanstalkEnvironment env = requireEnvironment(region, environmentName, environmentId);
        env.setStatus("Terminated");
        env.setHealth("Grey");
        env.setHealthStatus("NoData");
        env.setDateUpdated(Instant.now());
        environments.put(envKey(region, env.getEnvironmentName()), env);
        return env;
    }

    public List<ConfigurationOptionSetting> describeConfigurationSettings(String region, String applicationName,
                                                                          String environmentName,
                                                                          String templateName) {
        requireApplication(region, applicationName);
        if ((environmentName == null || environmentName.isBlank()) && (templateName == null || templateName.isBlank())) {
            throw new AwsException("MissingParameter",
                    "Either EnvironmentName or TemplateName must be specified.", 400);
        }
        if (environmentName != null && templateName != null) {
            throw new AwsException("InvalidParameterCombination",
                    "EnvironmentName and TemplateName cannot both be specified.", 400);
        }
        if (environmentName != null) {
            return List.copyOf(requireEnvironment(region, environmentName, null).getOptionSettings());
        }
        return List.of();
    }

    public boolean isCnameAvailable(String cnamePrefix, String region) {
        requireName(cnamePrefix, "CNAMEPrefix");
        String candidate = cname(cnamePrefix, cnamePrefix);
        return environments.entrySet().stream()
                .filter(e -> e.getKey().startsWith(region + "::"))
                .map(Map.Entry::getValue)
                .noneMatch(env -> candidate.equals(env.getCname()));
    }

    public List<String> listAvailableSolutionStacks() {
        return List.of(
                DEFAULT_SOLUTION_STACK,
                "64bit Amazon Linux 2023 v4.4.0 running Corretto 21",
                "64bit Amazon Linux 2023 v4.4.0 running Python 3.13",
                "64bit Amazon Linux 2023 v6.5.0 running Node.js 22"
        );
    }

    private BeanstalkApplication requireApplication(String region, String name) {
        requireName(name, "ApplicationName");
        BeanstalkApplication app = applications.get(appKey(region, name));
        if (app == null) {
            throw new AwsException("InvalidParameterValue",
                    "No Application named " + name + " found.", 400);
        }
        return app;
    }

    private BeanstalkEnvironment requireEnvironment(String region, String environmentName, String environmentId) {
        if ((environmentName == null || environmentName.isBlank()) && (environmentId == null || environmentId.isBlank())) {
            throw new AwsException("MissingParameter",
                    "Either EnvironmentName or EnvironmentId must be specified.", 400);
        }
        if (environmentName != null && !environmentName.isBlank()) {
            BeanstalkEnvironment env = environments.get(envKey(region, environmentName));
            if (env != null && (environmentId == null || environmentId.equals(env.getEnvironmentId()))) {
                return env;
            }
        }
        if (environmentId != null && !environmentId.isBlank()) {
            return environments.entrySet().stream()
                    .filter(e -> e.getKey().startsWith(region + "::"))
                    .map(Map.Entry::getValue)
                    .filter(env -> environmentId.equals(env.getEnvironmentId()))
                    .findFirst()
                    .orElseThrow(() -> new AwsException("InvalidParameterValue",
                            "No Environment found for id " + environmentId + ".", 400));
        }
        throw new AwsException("InvalidParameterValue",
                "No Environment named " + environmentName + " found.", 400);
    }

    private void mergeOptionSettings(List<ConfigurationOptionSetting> existing,
                                     List<ConfigurationOptionSetting> incoming) {
        for (ConfigurationOptionSetting next : incoming) {
            existing.removeIf(setting -> Objects.equals(setting.getNamespace(), next.getNamespace())
                    && Objects.equals(setting.getOptionName(), next.getOptionName())
                    && Objects.equals(setting.getResourceName(), next.getResourceName()));
            existing.add(next);
        }
    }

    private static void requireName(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new AwsException("MissingParameter", field + " is required.", 400);
        }
    }

    private static void validateEnvironmentName(String name) {
        if (name.length() < 4 || name.length() > 40 || name.startsWith("-") || name.endsWith("-")
                || !name.matches("[A-Za-z0-9-]+")) {
            throw new AwsException("InvalidParameterValue",
                    "EnvironmentName must be 4 to 40 characters and contain only letters, numbers, and hyphens.", 400);
        }
    }

    private static String generatedEnvironmentName(String applicationName) {
        String base = applicationName.replaceAll("[^A-Za-z0-9-]", "-");
        if (base.length() > 27) {
            base = base.substring(0, 27);
        }
        return base + "-" + randomLower(8);
    }

    private static String cname(String cnamePrefix, String environmentName) {
        String prefix = firstNonBlank(cnamePrefix, environmentName);
        return prefix + ".elasticbeanstalk.local";
    }

    private static String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private static String randomLower(int length) {
        StringBuilder result = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            result.append(LOWER_ALNUM.charAt(RANDOM.nextInt(LOWER_ALNUM.length())));
        }
        return result.toString();
    }

    private static String appKey(String region, String name) {
        return region + "::" + name;
    }

    private static String versionPrefix(String region, String applicationName) {
        return region + "::" + applicationName + "::";
    }

    private static String versionKey(String region, String applicationName, String versionLabel) {
        return versionPrefix(region, applicationName) + versionLabel;
    }

    private static String envKey(String region, String environmentName) {
        return region + "::" + environmentName;
    }

}
