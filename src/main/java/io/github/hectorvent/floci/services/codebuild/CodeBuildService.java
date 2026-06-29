package io.github.hectorvent.floci.services.codebuild;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackedMap;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.codebuild.model.Build;
import io.github.hectorvent.floci.services.codebuild.model.BuildPhase;
import io.github.hectorvent.floci.services.codebuild.model.Project;
import io.github.hectorvent.floci.services.codebuild.model.ProjectArtifacts;
import io.github.hectorvent.floci.services.codebuild.model.ProjectEnvironment;
import io.github.hectorvent.floci.services.codebuild.model.ProjectSource;
import io.github.hectorvent.floci.services.codebuild.model.ReportGroup;
import io.github.hectorvent.floci.services.codebuild.model.SourceCredential;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@ApplicationScoped
public class CodeBuildService {

    // key: region -> name -> project
    private Map<String, Map<String, Project>> projects = new ConcurrentHashMap<>();
    // key: region -> arn -> report group
    private Map<String, Map<String, ReportGroup>> reportGroups = new ConcurrentHashMap<>();
    // key: region -> arn -> source credential (token is stored but never returned)
    private Map<String, Map<String, SourceCredential>> sourceCredentials = new ConcurrentHashMap<>();
    // key: region -> buildId -> build (transient: builds are runtime state)
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Build>> builds = new ConcurrentHashMap<>();
    // key: region:projectName -> build counter (transient)
    private final ConcurrentHashMap<String, AtomicLong> buildCounters = new ConcurrentHashMap<>();

    private final CodeBuildRunner runner;
    private final EmulatorConfig config;
    private final StorageFactory storageFactory;

    @Inject
    public CodeBuildService(CodeBuildRunner runner, EmulatorConfig config, StorageFactory storageFactory) {
        this.runner = runner;
        this.config = config;
        this.storageFactory = storageFactory;
    }

    @PostConstruct
    void initializeStorage() {
        if (storageFactory == null) {
            return; // keeps non-CDI unit tests working
        }
        this.projects = storageBacked("codebuild-projects.json",
                new TypeReference<Map<String, Map<String, Project>>>() {});
        this.reportGroups = storageBacked("codebuild-report-groups.json",
                new TypeReference<Map<String, Map<String, ReportGroup>>>() {});
        this.sourceCredentials = storageBacked("codebuild-source-credentials.json",
                new TypeReference<Map<String, Map<String, SourceCredential>>>() {});
        normalizeRegionMaps(projects);
        normalizeRegionMaps(reportGroups);
        normalizeRegionMaps(sourceCredentials);
    }

    private <V> Map<String, V> storageBacked(String fileName, TypeReference<Map<String, V>> typeReference) {
        return new StorageBackedMap<>(storageFactory.create("codebuild", fileName, typeReference));
    }

    /** After load, re-wrap the persisted inner maps as {@link ConcurrentHashMap} so per-region
     *  mutation stays thread-safe (Jackson deserializes them as plain LinkedHashMaps). */
    private <V> void normalizeRegionMaps(Map<String, Map<String, V>> resources) {
        for (Map.Entry<String, Map<String, V>> entry : new ArrayList<>(resources.entrySet())) {
            if (!(entry.getValue() instanceof ConcurrentHashMap)) {
                resources.put(entry.getKey(), new ConcurrentHashMap<>(entry.getValue()));
            }
        }
    }

    /** {@link StorageBackedMap} only flushes on a top-level put, so an in-place mutation of a
     *  region's inner map must be written back by re-putting the region entry. */
    private <V> void persistRegion(Map<String, Map<String, V>> resources, String region) {
        Map<String, V> regionResources = resources.get(region);
        if (regionResources != null) {
            resources.put(region, regionResources);
        }
    }

    private Map<String, Project> projectsFor(String region) {
        return projects.computeIfAbsent(region, r -> new ConcurrentHashMap<>());
    }

    private Map<String, ReportGroup> reportGroupsFor(String region) {
        return reportGroups.computeIfAbsent(region, r -> new ConcurrentHashMap<>());
    }

    private Map<String, SourceCredential> sourceCredentialsFor(String region) {
        return sourceCredentials.computeIfAbsent(region, r -> new ConcurrentHashMap<>());
    }

    private Map<String, Build> buildsFor(String region) {
        return builds.computeIfAbsent(region, r -> new ConcurrentHashMap<>());
    }

    // ---- Projects ----

    public Project createProject(String region, String account,
                                 String name, String description,
                                 ProjectSource source, List<ProjectSource> secondarySources,
                                 String sourceVersion,
                                 ProjectArtifacts artifacts, List<ProjectArtifacts> secondaryArtifacts,
                                 ProjectEnvironment environment,
                                 String serviceRole,
                                 Integer timeoutInMinutes, Integer queuedTimeoutInMinutes,
                                 String encryptionKey,
                                 List<Map<String, String>> tags,
                                 Map<String, Object> logsConfig,
                                 Map<String, Object> vpcConfig,
                                 Integer concurrentBuildLimit) {
        Map<String, Project> store = projectsFor(region);
        if (store.containsKey(name)) {
            throw new AwsException("ResourceAlreadyExistsException",
                    "Project already exists: " + name, 400);
        }
        validateProjectName(name);
        if (source == null || source.getType() == null) {
            throw new AwsException("InvalidInputException", "source.type is required", 400);
        }
        if (environment == null) {
            throw new AwsException("InvalidInputException", "environment is required", 400);
        }
        if (serviceRole == null || serviceRole.isBlank()) {
            throw new AwsException("InvalidInputException", "serviceRole is required", 400);
        }
        if (artifacts == null || artifacts.getType() == null) {
            throw new AwsException("InvalidInputException", "artifacts.type is required", 400);
        }

        double now = Instant.now().toEpochMilli() / 1000.0;
        Project project = new Project();
        project.setName(name);
        project.setArn(AwsArnUtils.Arn.of("codebuild", region, account, "project/" + name).toString());
        project.setDescription(description);
        project.setSource(source);
        project.setSecondarySources(secondarySources);
        project.setSourceVersion(sourceVersion);
        project.setArtifacts(artifacts);
        project.setSecondaryArtifacts(secondaryArtifacts);
        project.setEnvironment(environment);
        project.setServiceRole(serviceRole);
        project.setTimeoutInMinutes(timeoutInMinutes != null ? timeoutInMinutes : 60);
        project.setQueuedTimeoutInMinutes(queuedTimeoutInMinutes != null ? queuedTimeoutInMinutes : 480);
        project.setEncryptionKey(encryptionKey);
        project.setTags(tags);
        project.setCreated(now);
        project.setLastModified(now);
        project.setLogsConfig(logsConfig);
        project.setVpcConfig(vpcConfig);
        project.setConcurrentBuildLimit(concurrentBuildLimit);
        project.setProjectVisibility("PRIVATE");

        store.put(name, project);
        persistRegion(projects, region);
        return project;
    }

    public Project updateProject(String region, String name,
                                 String description,
                                 ProjectSource source, List<ProjectSource> secondarySources,
                                 String sourceVersion,
                                 ProjectArtifacts artifacts, List<ProjectArtifacts> secondaryArtifacts,
                                 ProjectEnvironment environment,
                                 String serviceRole,
                                 Integer timeoutInMinutes, Integer queuedTimeoutInMinutes,
                                 String encryptionKey,
                                 List<Map<String, String>> tags,
                                 Map<String, Object> logsConfig,
                                 Map<String, Object> vpcConfig,
                                 Integer concurrentBuildLimit) {
        Map<String, Project> store = projectsFor(region);
        Project project = store.get(name);
        if (project == null) {
            throw new AwsException("ResourceNotFoundException", "Project not found: " + name, 400);
        }

        if (description != null) { project.setDescription(description); }
        if (source != null) { project.setSource(source); }
        if (secondarySources != null) { project.setSecondarySources(secondarySources); }
        if (sourceVersion != null) { project.setSourceVersion(sourceVersion); }
        if (artifacts != null) { project.setArtifacts(artifacts); }
        if (secondaryArtifacts != null) { project.setSecondaryArtifacts(secondaryArtifacts); }
        if (environment != null) { project.setEnvironment(environment); }
        if (serviceRole != null) { project.setServiceRole(serviceRole); }
        if (timeoutInMinutes != null) { project.setTimeoutInMinutes(timeoutInMinutes); }
        if (queuedTimeoutInMinutes != null) { project.setQueuedTimeoutInMinutes(queuedTimeoutInMinutes); }
        if (encryptionKey != null) { project.setEncryptionKey(encryptionKey); }
        if (tags != null) { project.setTags(tags); }
        if (logsConfig != null) { project.setLogsConfig(logsConfig); }
        if (vpcConfig != null) { project.setVpcConfig(vpcConfig); }
        if (concurrentBuildLimit != null) { project.setConcurrentBuildLimit(concurrentBuildLimit); }
        project.setLastModified(Instant.now().toEpochMilli() / 1000.0);

        persistRegion(projects, region);
        return project;
    }

    public void deleteProject(String region, String name) {
        Map<String, Project> store = projectsFor(region);
        if (store.remove(name) == null) {
            throw new AwsException("ResourceNotFoundException", "Project not found: " + name, 400);
        }
        persistRegion(projects, region);
    }

    public List<Project> batchGetProjects(String region, List<String> names) {
        Map<String, Project> store = projectsFor(region);
        return names.stream()
                .map(store::get)
                .filter(p -> p != null)
                .collect(Collectors.toList());
    }

    public List<String> listProjects(String region) {
        return new ArrayList<>(projectsFor(region).keySet());
    }

    // ---- Report Groups ----

    public ReportGroup createReportGroup(String region, String account,
                                         String name, String type,
                                         Map<String, Object> exportConfig,
                                         List<Map<String, String>> tags) {
        Map<String, ReportGroup> store = reportGroupsFor(region);
        String arn = AwsArnUtils.Arn.of("codebuild", region, account, "report-group/" + name).toString();
        if (store.containsKey(arn)) {
            throw new AwsException("ResourceAlreadyExistsException",
                    "Report group already exists: " + name, 400);
        }
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidInputException", "name is required", 400);
        }
        if (type == null) {
            throw new AwsException("InvalidInputException", "type is required", 400);
        }

        double now = Instant.now().toEpochMilli() / 1000.0;
        ReportGroup rg = new ReportGroup();
        rg.setArn(arn);
        rg.setName(name);
        rg.setType(type);
        rg.setExportConfig(exportConfig);
        rg.setCreated(now);
        rg.setLastModified(now);
        rg.setTags(tags);
        rg.setStatus("ACTIVE");

        store.put(arn, rg);
        persistRegion(reportGroups, region);
        return rg;
    }

    public ReportGroup updateReportGroup(String region, String arn,
                                          Map<String, Object> exportConfig,
                                          List<Map<String, String>> tags) {
        Map<String, ReportGroup> store = reportGroupsFor(region);
        ReportGroup rg = store.get(arn);
        if (rg == null) {
            throw new AwsException("ResourceNotFoundException", "Report group not found: " + arn, 400);
        }
        if (exportConfig != null) { rg.setExportConfig(exportConfig); }
        if (tags != null) { rg.setTags(tags); }
        rg.setLastModified(Instant.now().toEpochMilli() / 1000.0);
        persistRegion(reportGroups, region);
        return rg;
    }

    public void deleteReportGroup(String region, String arn) {
        Map<String, ReportGroup> store = reportGroupsFor(region);
        if (store.remove(arn) == null) {
            throw new AwsException("ResourceNotFoundException", "Report group not found: " + arn, 400);
        }
        persistRegion(reportGroups, region);
    }

    public List<ReportGroup> batchGetReportGroups(String region, List<String> arns) {
        Map<String, ReportGroup> store = reportGroupsFor(region);
        return arns.stream()
                .map(store::get)
                .filter(rg -> rg != null)
                .collect(Collectors.toList());
    }

    public List<String> listReportGroups(String region) {
        return new ArrayList<>(reportGroupsFor(region).keySet());
    }

    // ---- Source Credentials ----

    public SourceCredential importSourceCredentials(String region, String account,
                                                     String token, String serverType, String authType,
                                                     Boolean shouldOverwrite) {
        Map<String, SourceCredential> store = sourceCredentialsFor(region);
        // One credential per serverType+authType combo — overwrite existing by default
        String key = serverType + "/" + authType;
        SourceCredential existing = store.values().stream()
                .filter(c -> c.getServerType().equals(serverType) && c.getAuthType().equals(authType))
                .findFirst().orElse(null);
        if (existing != null && Boolean.FALSE.equals(shouldOverwrite)) {
            throw new AwsException("ResourceAlreadyExistsException",
                    "Source credentials already exist for " + serverType + "/" + authType, 400);
        }

        String arn = AwsArnUtils.Arn.of("codebuild", region, account, "token/" + serverType.toLowerCase() + "-" + UUID.randomUUID()).toString();
        if (existing != null) {
            arn = existing.getArn();
            store.remove(existing.getArn());
        }

        SourceCredential cred = new SourceCredential();
        cred.setArn(arn);
        cred.setServerType(serverType);
        cred.setAuthType(authType);
        // Token is accepted but not stored in plaintext in a returned field
        store.put(arn, cred);
        persistRegion(sourceCredentials, region);
        return cred;
    }

    public List<SourceCredential> listSourceCredentials(String region) {
        return new ArrayList<>(sourceCredentialsFor(region).values());
    }

    public void deleteSourceCredentials(String region, String arn) {
        Map<String, SourceCredential> store = sourceCredentialsFor(region);
        if (store.remove(arn) == null) {
            throw new AwsException("ResourceNotFoundException",
                    "Source credentials not found: " + arn, 400);
        }
        persistRegion(sourceCredentials, region);
    }

    // ---- Curated Environment Images ----

    public List<Map<String, Object>> listCuratedEnvironmentImages() {
        // Return the standard CodeBuild curated platform/language/image list
        return List.of(
                Map.of("platform", "AMAZON_LINUX_2",
                        "languages", List.of(
                                Map.of("language", "JAVA",
                                        "images", List.of(
                                                Map.of("name", "aws/codebuild/amazonlinux2-x86_64-standard:5.0",
                                                        "description", "AWS CodeBuild - amazonlinux2 - 5.0",
                                                        "versions", List.of("aws/codebuild/amazonlinux2-x86_64-standard:5.0")))),
                                Map.of("language", "PYTHON",
                                        "images", List.of(
                                                Map.of("name", "aws/codebuild/amazonlinux2-x86_64-standard:5.0",
                                                        "description", "AWS CodeBuild - amazonlinux2 - 5.0",
                                                        "versions", List.of("aws/codebuild/amazonlinux2-x86_64-standard:5.0")))),
                                Map.of("language", "NODE_JS",
                                        "images", List.of(
                                                Map.of("name", "aws/codebuild/amazonlinux2-x86_64-standard:5.0",
                                                        "description", "AWS CodeBuild - amazonlinux2 - 5.0",
                                                        "versions", List.of("aws/codebuild/amazonlinux2-x86_64-standard:5.0")))))),
                Map.of("platform", "UBUNTU",
                        "languages", List.of(
                                Map.of("language", "JAVA",
                                        "images", List.of(
                                                Map.of("name", "aws/codebuild/standard:7.0",
                                                        "description", "AWS CodeBuild - Ubuntu - 7.0",
                                                        "versions", List.of("aws/codebuild/standard:7.0")))),
                                Map.of("language", "PYTHON",
                                        "images", List.of(
                                                Map.of("name", "aws/codebuild/standard:7.0",
                                                        "description", "AWS CodeBuild - Ubuntu - 7.0",
                                                        "versions", List.of("aws/codebuild/standard:7.0")))),
                                Map.of("language", "NODE_JS",
                                        "images", List.of(
                                                Map.of("name", "aws/codebuild/standard:7.0",
                                                        "description", "AWS CodeBuild - Ubuntu - 7.0",
                                                        "versions", List.of("aws/codebuild/standard:7.0")))))));
    }

    private void validateProjectName(String name) {
        if (name == null || name.length() < 2 || name.length() > 150) {
            throw new AwsException("InvalidInputException",
                    "Project name must be between 2 and 150 characters", 400);
        }
    }

    // ---- Builds ----

    public Build startBuild(String region, String account, String projectName,
                            String buildspecOverride,
                            ProjectEnvironment environmentOverride,
                            ProjectArtifacts artifactsOverride,
                            String sourceVersion,
                            Integer timeoutOverride,
                            String imageOverride,
                            String computeTypeOverride) {
        Project project = projectsFor(region).get(projectName);
        if (project == null) {
            throw new AwsException("ResourceNotFoundException", "Project not found: " + projectName, 400);
        }

        String counterKey = region + ":" + projectName;
        long buildNumber = buildCounters
                .computeIfAbsent(counterKey, k -> new AtomicLong(0))
                .incrementAndGet();

        String buildId = projectName + ":" + buildNumber;
        String arn = AwsArnUtils.Arn.of("codebuild", region, account, "build/" + buildId).toString();

        Build build = new Build();
        build.setId(buildId);
        build.setArn(arn);
        build.setBuildNumber(buildNumber);
        build.setBuildStatus("IN_PROGRESS");
        build.setBuildComplete(false);
        build.setCurrentPhase("SUBMITTED");
        build.setProjectName(projectName);
        build.setInitiator("user");
        build.setStartTime(Instant.now().toEpochMilli() / 1000.0);
        build.setSource(project.getSource());
        build.setArtifacts(artifactsOverride != null ? artifactsOverride : project.getArtifacts());
        build.setTimeoutInMinutes(timeoutOverride != null ? timeoutOverride : project.getTimeoutInMinutes());
        build.setQueuedTimeoutInMinutes(project.getQueuedTimeoutInMinutes());
        build.setEncryptionKey(project.getEncryptionKey());

        ProjectEnvironment env = environmentOverride != null ? environmentOverride : project.getEnvironment();
        if (imageOverride != null || computeTypeOverride != null) {
            ProjectEnvironment merged = new ProjectEnvironment();
            merged.setType(env != null ? env.getType() : null);
            merged.setImage(imageOverride != null ? imageOverride : (env != null ? env.getImage() : null));
            merged.setComputeType(computeTypeOverride != null ? computeTypeOverride : (env != null ? env.getComputeType() : null));
            merged.setEnvironmentVariables(env != null ? env.getEnvironmentVariables() : null);
            merged.setPrivilegedMode(env != null ? env.getPrivilegedMode() : null);
            build.setEnvironment(merged);
        } else {
            build.setEnvironment(env);
        }

        build.setPhases(new CopyOnWriteArrayList<>());

        buildsFor(region).put(buildId, build);

        runner.startBuild(region, build, project, buildspecOverride);

        return build;
    }

    public Build getBuild(String region, String buildId) {
        Build build = buildsFor(region).get(buildId);
        if (build == null) {
            throw new AwsException("ResourceNotFoundException", "Build not found: " + buildId, 400);
        }
        return build;
    }

    public List<Build> batchGetBuilds(String region, List<String> buildIds) {
        Map<String, Build> store = buildsFor(region);
        return buildIds.stream()
                .map(store::get)
                .filter(b -> b != null)
                .collect(Collectors.toList());
    }

    public List<String> listBuilds(String region) {
        return buildsFor(region).values().stream()
                .sorted((a, b) -> Double.compare(
                        b.getStartTime() != null ? b.getStartTime() : 0,
                        a.getStartTime() != null ? a.getStartTime() : 0))
                .map(Build::getId)
                .collect(Collectors.toList());
    }

    public List<String> listBuildsForProject(String region, String projectName) {
        return buildsFor(region).values().stream()
                .filter(b -> projectName.equals(b.getProjectName()))
                .sorted((a, b) -> Double.compare(
                        b.getStartTime() != null ? b.getStartTime() : 0,
                        a.getStartTime() != null ? a.getStartTime() : 0))
                .map(Build::getId)
                .collect(Collectors.toList());
    }

    public void stopBuild(String region, String buildId) {
        Build build = buildsFor(region).get(buildId);
        if (build == null) {
            throw new AwsException("ResourceNotFoundException", "Build not found: " + buildId, 400);
        }
        runner.stopBuild(buildId);
    }

    public Build retryBuild(String region, String account, String buildId) {
        Build original = getBuild(region, buildId);
        return startBuild(region, account, original.getProjectName(),
                null, original.getEnvironment(), original.getArtifacts(),
                null, original.getTimeoutInMinutes(), null, null);
    }
}
