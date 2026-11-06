package io.floci.aws.services.codebuild;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.floci.aws.core.common.AwsException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stateless YAML/JSON buildspec parser. Supports version 0.2.
 */
class BuildspecParser {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    record ParsedBuildspec(
            Map<String, String> envVariables,
            Map<String, String> parameterStoreVars,
            Map<String, String> secretsManagerVars,
            List<String> installCommands,
            List<String> preBuildCommands,
            List<String> buildCommands,
            List<String> postBuildCommands,
            ParsedArtifacts artifacts
    ) {}

    record ParsedArtifacts(
            String type,
            List<String> files,
            String baseDirectory,
            boolean discardPaths,
            String name,
            String packaging
    ) {}

    static ParsedBuildspec parse(String content) {
        if (content == null || content.isBlank()) {
            throw new AwsException("InvalidInputException", "Buildspec content is empty", 400);
        }
        try {
            JsonNode root = YAML.readTree(content);

            JsonNode envNode = root.path("env");
            Map<String, String> envVars = parseStringMap(envNode.path("variables"));
            Map<String, String> paramStore = parseStringMap(envNode.path("parameter-store"));
            Map<String, String> secretsMgr = parseStringMap(envNode.path("secrets-manager"));

            JsonNode phases = root.path("phases");
            List<String> install = parseCommands(phases.path("install"));
            List<String> preBuild = parseCommands(phases.path("pre_build"));
            List<String> build = parseCommands(phases.path("build"));
            List<String> postBuild = parseCommands(phases.path("post_build"));

            ParsedArtifacts artifacts = parseArtifacts(root.path("artifacts"));

            return new ParsedBuildspec(envVars, paramStore, secretsMgr,
                    install, preBuild, build, postBuild, artifacts);
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InvalidInputException", "Failed to parse buildspec: " + e.getMessage(), 400);
        }
    }

    private static List<String> parseCommands(JsonNode phaseNode) {
        if (phaseNode.isMissingNode() || phaseNode.isNull()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode cmd : phaseNode.path("commands")) {
            result.add(cmd.asText());
        }
        return result;
    }

    private static Map<String, String> parseStringMap(JsonNode node) {
        Map<String, String> result = new LinkedHashMap<>();
        if (node.isMissingNode() || node.isNull()) {
            return result;
        }
        node.fields().forEachRemaining(e -> result.put(e.getKey(), e.getValue().asText()));
        return result;
    }

    private static ParsedArtifacts parseArtifacts(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return new ParsedArtifacts("NO_ARTIFACTS", List.of(), null, false, null, "ZIP");
        }
        String type = node.path("type").asText("NO_ARTIFACTS");
        List<String> files = new ArrayList<>();
        for (JsonNode f : node.path("files")) {
            files.add(f.asText());
        }
        String baseDir = node.has("base-directory") ? node.path("base-directory").asText(null) : null;
        boolean discardPaths = node.path("discard-paths").asBoolean(false);
        String name = node.has("name") ? node.path("name").asText(null) : null;
        String packaging = node.path("packaging").asText("ZIP");
        return new ParsedArtifacts(type, files, baseDir, discardPaths, name, packaging);
    }
}
