package io.github.hectorvent.floci.services.codebuild;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.codebuild.model.Build;
import io.github.hectorvent.floci.services.codebuild.model.Project;
import io.github.hectorvent.floci.services.codebuild.model.ProjectArtifacts;
import io.github.hectorvent.floci.services.codebuild.model.ProjectEnvironment;
import io.github.hectorvent.floci.services.codebuild.model.ProjectSource;
import io.github.hectorvent.floci.services.codebuild.model.ReportGroup;
import io.github.hectorvent.floci.services.codebuild.model.SourceCredential;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class CodeBuildJsonHandler {

    private final CodeBuildService service;
    private final ObjectMapper mapper;

    @Inject
    public CodeBuildJsonHandler(CodeBuildService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    public Response handle(String action, JsonNode request, String region, String account) throws Exception {
        return switch (action) {
            case "CreateProject" -> createProject(request, region, account);
            case "UpdateProject" -> updateProject(request, region);
            case "DeleteProject" -> deleteProject(request, region);
            case "BatchGetProjects" -> batchGetProjects(request, region);
            case "ListProjects" -> listProjects(region);
            case "CreateReportGroup" -> createReportGroup(request, region, account);
            case "UpdateReportGroup" -> updateReportGroup(request, region);
            case "DeleteReportGroup" -> deleteReportGroup(request, region);
            case "BatchGetReportGroups" -> batchGetReportGroups(request, region);
            case "ListReportGroups" -> listReportGroups(region);
            case "ImportSourceCredentials" -> importSourceCredentials(request, region, account);
            case "ListSourceCredentials" -> listSourceCredentials(region);
            case "DeleteSourceCredentials" -> deleteSourceCredentials(request, region);
            case "ListCuratedEnvironmentImages" -> listCuratedEnvironmentImages();
            case "StartBuild" -> startBuild(request, region, account);
            case "BatchGetBuilds" -> batchGetBuilds(request, region);
            case "ListBuilds" -> listBuilds(region);
            case "ListBuildsForProject" -> listBuildsForProject(request, region);
            case "StopBuild" -> stopBuild(request, region);
            case "RetryBuild" -> retryBuild(request, region, account);
            default -> throw new AwsException("InvalidAction", "Action " + action + " is not supported", 400);
        };
    }

    private Response createProject(JsonNode req, String region, String account) throws Exception {
        String name = req.path("name").asText(null);
        String description = req.has("description") ? req.path("description").asText() : null;
        ProjectSource source = req.has("source") ? mapper.treeToValue(req.get("source"), ProjectSource.class) : null;
        List<ProjectSource> secondarySources = parseList(req, "secondarySources", ProjectSource.class);
        String sourceVersion = req.has("sourceVersion") ? req.path("sourceVersion").asText() : null;
        ProjectArtifacts artifacts = req.has("artifacts") ? mapper.treeToValue(req.get("artifacts"), ProjectArtifacts.class) : null;
        List<ProjectArtifacts> secondaryArtifacts = parseList(req, "secondaryArtifacts", ProjectArtifacts.class);
        ProjectEnvironment environment = req.has("environment") ? mapper.treeToValue(req.get("environment"), ProjectEnvironment.class) : null;
        String serviceRole = req.has("serviceRole") ? req.path("serviceRole").asText() : null;
        Integer timeout = req.has("timeoutInMinutes") ? req.path("timeoutInMinutes").asInt() : null;
        Integer queuedTimeout = req.has("queuedTimeoutInMinutes") ? req.path("queuedTimeoutInMinutes").asInt() : null;
        String encryptionKey = req.has("encryptionKey") ? req.path("encryptionKey").asText() : null;
        List<Map<String, String>> tags = parseTags(req);
        Map<String, Object> logsConfig = req.has("logsConfig") ? mapper.treeToValue(req.get("logsConfig"), Map.class) : null;
        Map<String, Object> vpcConfig = req.has("vpcConfig") ? mapper.treeToValue(req.get("vpcConfig"), Map.class) : null;
        Integer concurrentBuildLimit = req.has("concurrentBuildLimit") ? req.path("concurrentBuildLimit").asInt() : null;

        Project project = service.createProject(region, account, name, description,
                source, secondarySources, sourceVersion,
                artifacts, secondaryArtifacts, environment,
                serviceRole, timeout, queuedTimeout, encryptionKey,
                tags, logsConfig, vpcConfig, concurrentBuildLimit);

        return Response.ok(Map.of("project", project)).build();
    }

    private Response updateProject(JsonNode req, String region) throws Exception {
        String name = req.path("name").asText(null);
        String description = req.has("description") ? req.path("description").asText() : null;
        ProjectSource source = req.has("source") ? mapper.treeToValue(req.get("source"), ProjectSource.class) : null;
        List<ProjectSource> secondarySources = parseList(req, "secondarySources", ProjectSource.class);
        String sourceVersion = req.has("sourceVersion") ? req.path("sourceVersion").asText() : null;
        ProjectArtifacts artifacts = req.has("artifacts") ? mapper.treeToValue(req.get("artifacts"), ProjectArtifacts.class) : null;
        List<ProjectArtifacts> secondaryArtifacts = parseList(req, "secondaryArtifacts", ProjectArtifacts.class);
        ProjectEnvironment environment = req.has("environment") ? mapper.treeToValue(req.get("environment"), ProjectEnvironment.class) : null;
        String serviceRole = req.has("serviceRole") ? req.path("serviceRole").asText() : null;
        Integer timeout = req.has("timeoutInMinutes") ? req.path("timeoutInMinutes").asInt() : null;
        Integer queuedTimeout = req.has("queuedTimeoutInMinutes") ? req.path("queuedTimeoutInMinutes").asInt() : null;
        String encryptionKey = req.has("encryptionKey") ? req.path("encryptionKey").asText() : null;
        List<Map<String, String>> tags = parseTags(req);
        Map<String, Object> logsConfig = req.has("logsConfig") ? mapper.treeToValue(req.get("logsConfig"), Map.class) : null;
        Map<String, Object> vpcConfig = req.has("vpcConfig") ? mapper.treeToValue(req.get("vpcConfig"), Map.class) : null;
        Integer concurrentBuildLimit = req.has("concurrentBuildLimit") ? req.path("concurrentBuildLimit").asInt() : null;

        Project project = service.updateProject(region, name, description,
                source, secondarySources, sourceVersion,
                artifacts, secondaryArtifacts, environment,
                serviceRole, timeout, queuedTimeout, encryptionKey,
                tags, logsConfig, vpcConfig, concurrentBuildLimit);

        return Response.ok(Map.of("project", project)).build();
    }

    private Response deleteProject(JsonNode req, String region) {
        String name = req.path("name").asText(null);
        service.deleteProject(region, name);
        return Response.ok(Map.of()).build();
    }

    private Response batchGetProjects(JsonNode req, String region) {
        List<String> names = new ArrayList<>();
        req.path("names").forEach(n -> names.add(n.asText()));
        List<Project> found = service.batchGetProjects(region, names);
        List<String> foundNames = found.stream().map(Project::getName).toList();
        List<String> notFound = names.stream().filter(n -> !foundNames.contains(n)).toList();
        return Response.ok(Map.of("projects", found, "projectsNotFound", notFound)).build();
    }

    private Response listProjects(String region) {
        List<String> names = service.listProjects(region);
        return Response.ok(Map.of("projects", names)).build();
    }

    private Response createReportGroup(JsonNode req, String region, String account) throws Exception {
        String name = req.path("name").asText(null);
        String type = req.path("type").asText(null);
        Map<String, Object> exportConfig = req.has("exportConfig") ? mapper.treeToValue(req.get("exportConfig"), Map.class) : null;
        List<Map<String, String>> tags = parseTags(req);
        ReportGroup rg = service.createReportGroup(region, account, name, type, exportConfig, tags);
        return Response.ok(Map.of("reportGroup", rg)).build();
    }

    private Response updateReportGroup(JsonNode req, String region) throws Exception {
        String arn = req.path("arn").asText(null);
        Map<String, Object> exportConfig = req.has("exportConfig") ? mapper.treeToValue(req.get("exportConfig"), Map.class) : null;
        List<Map<String, String>> tags = parseTags(req);
        ReportGroup rg = service.updateReportGroup(region, arn, exportConfig, tags);
        return Response.ok(Map.of("reportGroup", rg)).build();
    }

    private Response deleteReportGroup(JsonNode req, String region) {
        String arn = req.path("arn").asText(null);
        service.deleteReportGroup(region, arn);
        return Response.ok(Map.of()).build();
    }

    private Response batchGetReportGroups(JsonNode req, String region) {
        List<String> arns = new ArrayList<>();
        req.path("reportGroupArns").forEach(a -> arns.add(a.asText()));
        List<ReportGroup> found = service.batchGetReportGroups(region, arns);
        List<String> foundArns = found.stream().map(ReportGroup::getArn).toList();
        List<String> notFound = arns.stream().filter(a -> !foundArns.contains(a)).toList();
        return Response.ok(Map.of("reportGroups", found, "reportGroupsNotFound", notFound)).build();
    }

    private Response listReportGroups(String region) {
        return Response.ok(Map.of("reportGroups", service.listReportGroups(region))).build();
    }

    private Response importSourceCredentials(JsonNode req, String region, String account) {
        String token = req.path("token").asText(null);
        String serverType = req.path("serverType").asText(null);
        String authType = req.path("authType").asText(null);
        Boolean shouldOverwrite = req.has("shouldOverwrite") ? req.path("shouldOverwrite").asBoolean() : null;
        SourceCredential cred = service.importSourceCredentials(region, account, token, serverType, authType, shouldOverwrite);
        return Response.ok(Map.of("arn", cred.getArn())).build();
    }

    private Response listSourceCredentials(String region) {
        List<SourceCredential> creds = service.listSourceCredentials(region);
        return Response.ok(Map.of("sourceCredentialsInfos", creds)).build();
    }

    private Response deleteSourceCredentials(JsonNode req, String region) {
        String arn = req.path("arn").asText(null);
        service.deleteSourceCredentials(region, arn);
        return Response.ok(Map.of("arn", arn)).build();
    }

    private Response listCuratedEnvironmentImages() {
        return Response.ok(Map.of("platforms", service.listCuratedEnvironmentImages())).build();
    }

    private Response startBuild(JsonNode req, String region, String account) throws Exception {
        String projectName = req.path("projectName").asText(null);
        String buildspecOverride = req.has("buildspecOverride") ? req.path("buildspecOverride").asText(null) : null;
        ProjectEnvironment envOverride = req.has("privilegedModeOverride") || req.has("environmentTypeOverride")
                ? buildEnvOverride(req) : null;
        List<Map<String, String>> environmentVariablesOverride = parseEnvironmentVariablesOverride(req);
        ProjectArtifacts artifactsOverride = req.has("artifactsOverride")
                ? mapper.treeToValue(req.get("artifactsOverride"), ProjectArtifacts.class) : null;
        String sourceVersion = req.has("sourceVersion") ? req.path("sourceVersion").asText(null) : null;
        String sourceTypeOverride = req.has("sourceTypeOverride") ? req.path("sourceTypeOverride").asText(null) : null;
        String sourceLocationOverride = req.has("sourceLocationOverride") ? req.path("sourceLocationOverride").asText(null) : null;
        List<ProjectSource> secondarySourcesOverride = parseList(req, "secondarySourcesOverride", ProjectSource.class);
        Integer timeout = req.has("timeoutInMinutes") ? req.path("timeoutInMinutes").asInt() : null;
        String imageOverride = req.has("imageOverride") ? req.path("imageOverride").asText(null) : null;
        String computeTypeOverride = req.has("computeTypeOverride") ? req.path("computeTypeOverride").asText(null) : null;

        // Every override below is copied onto the stored Build and read back by BatchGetBuilds,
        // so the enum shapes are checked here rather than accepted verbatim.
        requireEnum(sourceTypeOverride, SOURCE_TYPES, "sourceTypeOverride");
        requireEnum(computeTypeOverride, COMPUTE_TYPES, "computeTypeOverride");
        requireEnum(envOverride == null ? null : envOverride.getType(),
                ENVIRONMENT_TYPES, "environmentTypeOverride");
        if (environmentVariablesOverride != null) {
            for (Map<String, String> variable : environmentVariablesOverride) {
                requireEnum(variable.get("type"), ENVIRONMENT_VARIABLE_TYPES,
                        "environmentVariablesOverride.type");
            }
        }
        if (secondarySourcesOverride != null) {
            if (secondarySourcesOverride.size() > MAX_SECONDARY_SOURCES) {
                throw new AwsException("InvalidInputException",
                        "secondarySourcesOverride accepts at most " + MAX_SECONDARY_SOURCES + " sources", 400);
            }
            for (ProjectSource source : secondarySourcesOverride) {
                requireEnum(source == null ? null : source.getType(), SOURCE_TYPES,
                        "secondarySourcesOverride.type");
            }
        }

        Build build = service.startBuild(region, account, projectName, buildspecOverride,
                envOverride, environmentVariablesOverride, artifactsOverride, sourceVersion,
                sourceTypeOverride, sourceLocationOverride, secondarySourcesOverride,
                timeout, imageOverride, computeTypeOverride);
        return Response.ok(Map.of("build", build)).build();
    }

    // Enum shapes from the CodeBuild model (codebuild/2016-10-06/service-2.json).
    private static final Set<String> SOURCE_TYPES = Set.of(
            "CODECOMMIT", "CODEPIPELINE", "GITHUB", "GITLAB", "GITLAB_SELF_MANAGED",
            "S3", "BITBUCKET", "GITHUB_ENTERPRISE", "NO_SOURCE");
    private static final Set<String> COMPUTE_TYPES = Set.of(
            "BUILD_GENERAL1_SMALL", "BUILD_GENERAL1_MEDIUM", "BUILD_GENERAL1_LARGE",
            "BUILD_GENERAL1_XLARGE", "BUILD_GENERAL1_2XLARGE", "BUILD_LAMBDA_1GB",
            "BUILD_LAMBDA_2GB", "BUILD_LAMBDA_4GB", "BUILD_LAMBDA_8GB", "BUILD_LAMBDA_10GB",
            "ATTRIBUTE_BASED_COMPUTE", "CUSTOM_INSTANCE_TYPE");
    private static final Set<String> ENVIRONMENT_TYPES = Set.of(
            "WINDOWS_CONTAINER", "LINUX_CONTAINER", "LINUX_GPU_CONTAINER", "ARM_CONTAINER",
            "WINDOWS_SERVER_2019_CONTAINER", "WINDOWS_SERVER_2022_CONTAINER",
            "LINUX_LAMBDA_CONTAINER", "ARM_LAMBDA_CONTAINER", "LINUX_EC2", "ARM_EC2",
            "WINDOWS_EC2", "MAC_ARM");
    private static final Set<String> ENVIRONMENT_VARIABLE_TYPES =
            Set.of("PLAINTEXT", "PARAMETER_STORE", "SECRETS_MANAGER");
    /** {@code ProjectSources} is {@code max: 12}. */
    private static final int MAX_SECONDARY_SOURCES = 12;

    /** Rejects a present value outside the shape's enum; a null value is left alone. */
    private static void requireEnum(String value, Set<String> allowed, String memberName) {
        if (value == null || allowed.contains(value)) {
            return;
        }
        throw new AwsException("InvalidInputException",
                "Value '" + value + "' at '" + memberName + "' failed to satisfy constraint: "
                        + "Member must satisfy enum value set: ["
                        + allowed.stream().sorted().collect(Collectors.joining(", ")) + "]", 400);
    }

    private List<Map<String, String>> parseEnvironmentVariablesOverride(JsonNode req) {
        if (!req.has("environmentVariablesOverride") || req.get("environmentVariablesOverride").isNull()) {
            return null;
        }
        List<Map<String, String>> vars = new ArrayList<>();
        for (JsonNode v : req.get("environmentVariablesOverride")) {
            Map<String, String> m = new HashMap<>();
            m.put("name", v.path("name").asText());
            m.put("value", v.path("value").asText());
            m.put("type", v.path("type").asText("PLAINTEXT"));
            vars.add(m);
        }
        return vars;
    }

    private ProjectEnvironment buildEnvOverride(JsonNode req) {
        ProjectEnvironment env = new ProjectEnvironment();
        if (req.has("imageOverride")) {
            env.setImage(req.path("imageOverride").asText(null));
        }
        if (req.has("computeTypeOverride")) {
            env.setComputeType(req.path("computeTypeOverride").asText(null));
        }
        if (req.has("privilegedModeOverride")) {
            env.setPrivilegedMode(req.path("privilegedModeOverride").asBoolean());
        }
        if (req.has("environmentTypeOverride")) {
            env.setType(req.path("environmentTypeOverride").asText(null));
        }
        return env;
    }

    private Response batchGetBuilds(JsonNode req, String region) {
        List<String> ids = new ArrayList<>();
        req.path("ids").forEach(n -> ids.add(n.asText()));
        List<Build> found = service.batchGetBuilds(region, ids);
        List<String> foundIds = found.stream().map(Build::getId).toList();
        List<String> notFound = ids.stream().filter(id -> !foundIds.contains(id)).toList();
        return Response.ok(Map.of("builds", found, "buildsNotFound", notFound)).build();
    }

    private Response listBuilds(String region) {
        return Response.ok(Map.of("ids", service.listBuilds(region))).build();
    }

    private Response listBuildsForProject(JsonNode req, String region) {
        String projectName = req.path("projectName").asText(null);
        return Response.ok(Map.of("ids", service.listBuildsForProject(region, projectName))).build();
    }

    private Response stopBuild(JsonNode req, String region) {
        String id = req.path("id").asText(null);
        service.stopBuild(region, id);
        Build build = service.getBuild(region, id);
        return Response.ok(Map.of("build", build)).build();
    }

    private Response retryBuild(JsonNode req, String region, String account) {
        String id = req.path("id").asText(null);
        Build build = service.retryBuild(region, account, id);
        return Response.ok(Map.of("build", build)).build();
    }

    private <T> List<T> parseList(JsonNode req, String field, Class<T> type) throws Exception {
        if (!req.has(field) || req.get(field).isNull()) {
            return null;
        }
        List<T> result = new ArrayList<>();
        for (JsonNode node : req.get(field)) {
            result.add(mapper.treeToValue(node, type));
        }
        return result;
    }

    private List<Map<String, String>> parseTags(JsonNode req) {
        if (!req.has("tags") || req.get("tags").isNull()) {
            return null;
        }
        List<Map<String, String>> tags = new ArrayList<>();
        for (JsonNode tag : req.get("tags")) {
            Map<String, String> t = new HashMap<>();
            t.put("key", tag.path("key").asText());
            t.put("value", tag.path("value").asText());
            tags.add(t);
        }
        return tags;
    }
}
