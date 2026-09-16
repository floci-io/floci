package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

@ApplicationScoped
public class BedrockAgentCoreToolsService {

    private static final Pattern TOOL_NAME = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{0,47}");
    private static final String ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private final StorageBackend<String, ObjectNode> storage;
    private final RegionResolver regionResolver;

    @Inject
    public BedrockAgentCoreToolsService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create("bedrockagentcore", "bedrock-agentcore-tools.json",
                new TypeReference<Map<String, ObjectNode>>() {}), regionResolver);
    }

    BedrockAgentCoreToolsService(StorageBackend<String, ObjectNode> storage, RegionResolver regionResolver) {
        this.storage = storage;
        this.regionResolver = regionResolver;
    }

    public ObjectNode createBrowser(ObjectNode request, String region) {
        String name = requiredText(request, "name");
        if (!TOOL_NAME.matcher(name).matches()) {
            throw new AwsException("ValidationException",
                    "name must match [a-zA-Z][a-zA-Z0-9_]{0,47}", 400);
        }
        JsonNode networkConfiguration = request.get("networkConfiguration");
        if (networkConfiguration == null || !networkConfiguration.isObject()) {
            throw new AwsException("ValidationException", "networkConfiguration is required", 400);
        }
        validateNetworkConfiguration(networkConfiguration, false);
        validateCommonCreateFields(request, true);
        String clientToken = optionalText(request, "clientToken");
        if (clientToken != null) {
            if (clientToken.length() < 33 || clientToken.length() > 256
                    || !clientToken.matches("[a-zA-Z0-9](-*[a-zA-Z0-9]){0,256}")) {
                throw new AwsException("ValidationException", "clientToken does not satisfy length or pattern constraints", 400);
            }
            ObjectNode existing = findByClientToken("browser", region, clientToken);
            if (existing != null) {
                return existing.deepCopy();
            }
        }
        if (findByName("browser", region, name) != null) {
            throw new AwsException("ConflictException", "Browser already exists: " + name, 409);
        }

        String id = name + "-" + random(10);
        Instant now = Instant.now();
        ObjectNode browser = request.deepCopy();
        browser.put("browserId", id);
        browser.put("browserArn", regionResolver.buildArn("bedrock-agentcore", region, "browser-custom/" + id));
        browser.put("status", "READY");
        browser.put("createdAt", now.toString());
        browser.put("lastUpdatedAt", now.toString());
        storage.put(key("browser", region, id), browser);
        return browser.deepCopy();
    }

    public ObjectNode getBrowser(String browserId, String region) {
        if ("aws.browser.v1".equals(browserId)) {
            ObjectNode browser = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            browser.put("browserId", browserId);
            browser.put("browserArn", regionResolver.buildArn("bedrock-agentcore", region, "browser/" + browserId));
            browser.put("name", browserId);
            browser.put("status", "READY");
            browser.put("createdAt", "1970-01-01T00:00:00Z");
            browser.put("lastUpdatedAt", "1970-01-01T00:00:00Z");
            browser.putObject("networkConfiguration").put("networkMode", "PUBLIC");
            return browser;
        }
        if (browserId == null || !browserId.matches("[a-zA-Z][a-zA-Z0-9_]{0,47}-[a-zA-Z0-9]{10}")) {
            throw new AwsException("ValidationException", "browserId does not satisfy the required pattern", 400);
        }
        return storage.get(key("browser", region, browserId))
                .map(ObjectNode::deepCopy)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Browser not found: " + browserId, 404));
    }

    public ObjectNode createBrowserProfile(ObjectNode request, String region) {
        String name = requiredText(request, "name");
        if (!TOOL_NAME.matcher(name).matches()) {
            throw new AwsException("ValidationException",
                    "name must match [a-zA-Z][a-zA-Z0-9_]{0,47}", 400);
        }
        validateDescription(request.get("description"));
        BedrockAgentCoreTagValidation.validateTags(request.get("tags"));
        String clientToken = optionalText(request, "clientToken");
        if (clientToken != null) {
            if (clientToken.length() < 33 || clientToken.length() > 256
                    || !clientToken.matches("[a-zA-Z0-9](-*[a-zA-Z0-9]){0,256}")) {
                throw new AwsException("ValidationException", "clientToken does not satisfy length or pattern constraints", 400);
            }
            ObjectNode existing = findByClientToken("browser-profile", region, clientToken);
            if (existing != null) {
                return existing.deepCopy();
            }
        }
        if (findByName("browser-profile", region, name) != null) {
            throw new AwsException("ConflictException", "Browser profile already exists: " + name, 409);
        }
        String id = name + "-" + random(10);
        Instant now = Instant.now();
        ObjectNode profile = request.deepCopy();
        profile.put("profileId", id);
        profile.put("profileArn", regionResolver.buildArn("bedrock-agentcore", region, "browser-profile/" + id));
        profile.put("status", "READY");
        profile.put("createdAt", now.toString());
        profile.put("lastUpdatedAt", now.toString());
        storage.put(key("browser-profile", region, id), profile);
        return profile.deepCopy();
    }

    public ObjectNode createCodeInterpreter(ObjectNode request, String region) {
        String name = requiredText(request, "name");
        if (!TOOL_NAME.matcher(name).matches()) {
            throw new AwsException("ValidationException",
                    "name must match [a-zA-Z][a-zA-Z0-9_]{0,47}", 400);
        }
        JsonNode networkConfiguration = request.get("networkConfiguration");
        if (networkConfiguration == null || !networkConfiguration.isObject()) {
            throw new AwsException("ValidationException", "networkConfiguration is required", 400);
        }
        validateNetworkConfiguration(networkConfiguration, true);
        validateCommonCreateFields(request, false);
        String clientToken = optionalText(request, "clientToken");
        if (clientToken != null) {
            if (clientToken.length() < 33 || clientToken.length() > 256
                    || !clientToken.matches("[a-zA-Z0-9](-*[a-zA-Z0-9]){0,256}")) {
                throw new AwsException("ValidationException", "clientToken does not satisfy length or pattern constraints", 400);
            }
            ObjectNode existing = findByClientToken("code-interpreter", region, clientToken);
            if (existing != null) {
                return existing.deepCopy();
            }
        }
        if (findByName("code-interpreter", region, name) != null) {
            throw new AwsException("ConflictException", "Code interpreter already exists: " + name, 409);
        }
        String id = name + "-" + random(10);
        Instant now = Instant.now();
        ObjectNode interpreter = request.deepCopy();
        interpreter.put("codeInterpreterId", id);
        interpreter.put("codeInterpreterArn", regionResolver.buildArn(
                "bedrock-agentcore", region, "code-interpreter-custom/" + id));
        interpreter.put("status", "READY");
        interpreter.put("createdAt", now.toString());
        interpreter.put("lastUpdatedAt", now.toString());
        storage.put(key("code-interpreter", region, id), interpreter);
        return interpreter.deepCopy();
    }

    public ObjectNode getCodeInterpreter(String codeInterpreterId, String region) {
        if ("aws.codeinterpreter.v1".equals(codeInterpreterId)) {
            ObjectNode interpreter = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            interpreter.put("codeInterpreterId", codeInterpreterId);
            interpreter.put("codeInterpreterArn", "arn:aws:bedrock-agentcore:" + region
                    + ":aws:code-interpreter/aws.codeinterpreter.v1");
            interpreter.put("name", codeInterpreterId);
            interpreter.put("status", "READY");
            interpreter.put("createdAt", "1970-01-01T00:00:00Z");
            interpreter.put("lastUpdatedAt", "1970-01-01T00:00:00Z");
            interpreter.putObject("networkConfiguration").put("networkMode", "SANDBOX");
            return interpreter;
        }
        if (codeInterpreterId == null
                || !codeInterpreterId.matches("[a-zA-Z][a-zA-Z0-9_]{0,47}-[a-zA-Z0-9]{10}")) {
            throw new AwsException("ValidationException",
                    "codeInterpreterId does not satisfy the required pattern", 400);
        }
        return storage.get(key("code-interpreter", region, codeInterpreterId))
                .map(ObjectNode::deepCopy)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Code interpreter not found: " + codeInterpreterId, 404));
    }

    public PaginatedResult<ObjectNode> listCodeInterpreters(Integer maxResults, String nextToken,
                                                             String type, String region) {
        if (type != null && !type.isBlank() && !"SYSTEM".equals(type) && !"CUSTOM".equals(type)) {
            throw new AwsException("ValidationException", "type must be SYSTEM or CUSTOM", 400);
        }
        List<ObjectNode> interpreters = new ArrayList<>();
        if (type == null || type.isBlank() || "SYSTEM".equals(type)) {
            interpreters.add(getCodeInterpreter("aws.codeinterpreter.v1", region));
        }
        if (type == null || type.isBlank() || "CUSTOM".equals(type)) {
            storage.scan(k -> k.startsWith(prefix("code-interpreter", region))).stream()
                    .map(ObjectNode::deepCopy)
                    .forEach(interpreters::add);
        }
        return Pagination.paginate(interpreters,
                node -> node.path("codeInterpreterId").asText(), maxResults, nextToken,
                100, 100, "ValidationException");
    }

    public ObjectNode deleteCodeInterpreter(String codeInterpreterId, String clientToken, String region) {
        if ("aws.codeinterpreter.v1".equals(codeInterpreterId)) {
            throw new AwsException("ValidationException", "System code interpreter cannot be deleted", 400);
        }
        if (clientToken != null && !clientToken.isBlank()
                && (clientToken.length() < 33 || clientToken.length() > 256
                || !clientToken.matches("[a-zA-Z0-9](-*[a-zA-Z0-9]){0,256}"))) {
            throw new AwsException("ValidationException", "clientToken does not satisfy length or pattern constraints", 400);
        }
        String replayKey = deleteReplayKey("code-interpreter", region, codeInterpreterId, clientToken);
        ObjectNode replay = replayKey == null ? null : storage.get(replayKey).map(ObjectNode::deepCopy).orElse(null);
        if (replay != null) {
            return replay.deepCopy();
        }
        ObjectNode interpreter = getCodeInterpreter(codeInterpreterId, region);
        storage.delete(key("code-interpreter", region, codeInterpreterId));
        interpreter.put("status", "DELETING");
        interpreter.put("lastUpdatedAt", Instant.now().toString());
        if (replayKey != null) {
            storage.put(replayKey, interpreter.deepCopy());
        }
        return interpreter;
    }

    public ObjectNode getBrowserProfile(String profileId, String region) {
        if (profileId == null || !profileId.matches("[a-zA-Z][a-zA-Z0-9_]{0,47}-[a-zA-Z0-9]{10}")) {
            throw new AwsException("ValidationException", "profileId does not satisfy the required pattern", 400);
        }
        return storage.get(key("browser-profile", region, profileId))
                .map(ObjectNode::deepCopy)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Browser profile not found: " + profileId, 404));
    }

    public PaginatedResult<ObjectNode> listBrowserProfiles(Integer maxResults, String nextToken,
                                                            String name, String region) {
        if (name != null && !name.isBlank() && !TOOL_NAME.matcher(name).matches()) {
            throw new AwsException("ValidationException",
                    "name must match [a-zA-Z][a-zA-Z0-9_]{0,47}", 400);
        }
        List<ObjectNode> profiles = storage.scan(k -> k.startsWith(prefix("browser-profile", region))).stream()
                .map(ObjectNode::deepCopy)
                .filter(node -> name == null || name.isBlank() || name.equals(optionalText(node, "name")))
                .toList();
        return Pagination.paginate(profiles,
                node -> node.path("profileId").asText(), maxResults, nextToken,
                100, 100, "ValidationException");
    }

    public ObjectNode deleteBrowserProfile(String profileId, String clientToken, String region) {
        if (clientToken != null && !clientToken.isBlank()
                && (clientToken.length() < 33 || clientToken.length() > 256
                || !clientToken.matches("[a-zA-Z0-9](-*[a-zA-Z0-9]){0,256}"))) {
            throw new AwsException("ValidationException", "clientToken does not satisfy length or pattern constraints", 400);
        }
        String replayKey = deleteReplayKey("browser-profile", region, profileId, clientToken);
        ObjectNode replay = replayKey == null ? null : storage.get(replayKey).map(ObjectNode::deepCopy).orElse(null);
        if (replay != null) {
            return replay.deepCopy();
        }
        ObjectNode profile = getBrowserProfile(profileId, region);
        storage.delete(key("browser-profile", region, profileId));
        profile.put("status", "DELETING");
        profile.put("lastUpdatedAt", Instant.now().toString());
        if (replayKey != null) {
            storage.put(replayKey, profile.deepCopy());
        }
        return profile;
    }

    public ObjectNode deleteBrowser(String browserId, String clientToken, String region) {
        if ("aws.browser.v1".equals(browserId)) {
            throw new AwsException("ValidationException", "System browser cannot be deleted", 400);
        }
        if (clientToken != null && !clientToken.isBlank()
                && (clientToken.length() < 33 || clientToken.length() > 256
                || !clientToken.matches("[a-zA-Z0-9](-*[a-zA-Z0-9]){0,256}"))) {
            throw new AwsException("ValidationException", "clientToken does not satisfy length or pattern constraints", 400);
        }
        String replayKey = deleteReplayKey("browser", region, browserId, clientToken);
        ObjectNode replay = replayKey == null ? null : storage.get(replayKey).map(ObjectNode::deepCopy).orElse(null);
        if (replay != null) {
            return replay.deepCopy();
        }
        ObjectNode browser = getBrowser(browserId, region);
        storage.delete(key("browser", region, browserId));
        browser.put("status", "DELETING");
        browser.put("lastUpdatedAt", Instant.now().toString());
        if (replayKey != null) {
            storage.put(replayKey, browser.deepCopy());
        }
        return browser;
    }

    public PaginatedResult<ObjectNode> listBrowsers(Integer maxResults, String nextToken, String type, String region) {
        if (type != null && !type.isBlank() && !"SYSTEM".equals(type) && !"CUSTOM".equals(type)) {
            throw new AwsException("ValidationException", "type must be SYSTEM or CUSTOM", 400);
        }
        List<ObjectNode> browsers = new ArrayList<>();
        if (type == null || type.isBlank() || "SYSTEM".equals(type)) {
            browsers.add(getBrowser("aws.browser.v1", region));
        }
        if (type == null || type.isBlank() || "CUSTOM".equals(type)) {
            storage.scan(k -> k.startsWith(prefix("browser", region))).stream()
                    .map(ObjectNode::deepCopy)
                    .forEach(browsers::add);
        }
        return Pagination.paginate(browsers,
                node -> node.path("browserId").asText(), maxResults, nextToken,
                100, 100, "ValidationException");
    }

    private ObjectNode findByClientToken(String family, String region, String clientToken) {
        return storage.scan(k -> k.startsWith(prefix(family, region))).stream()
                .filter(node -> clientToken.equals(optionalText(node, "clientToken")))
                .findFirst()
                .map(ObjectNode::deepCopy)
                .orElse(null);
    }

    private ObjectNode findByName(String family, String region, String name) {
        return storage.scan(k -> k.startsWith(prefix(family, region))).stream()
                .filter(node -> name.equals(optionalText(node, "name")))
                .findFirst()
                .map(ObjectNode::deepCopy)
                .orElse(null);
    }

    private static void validateCommonCreateFields(ObjectNode request, boolean browser) {
        validateDescription(request.get("description"));
        BedrockAgentCoreTagValidation.validateTags(request.get("tags"));
        JsonNode executionRoleArn = request.get("executionRoleArn");
        if (executionRoleArn != null && !executionRoleArn.isNull()) {
            if (!executionRoleArn.isTextual()) {
                throw new AwsException("ValidationException", "executionRoleArn must be a string", 400);
            }
            String arn = executionRoleArn.asText();
            if (arn.length() < 1 || arn.length() > 2048
                    || !arn.matches("arn:aws(-[^:]+)?:iam::([0-9]{12})?:role/.+")) {
                throw new AwsException("ValidationException", "executionRoleArn is invalid", 400);
            }
        }
        validateArray(request.get("certificates"), "certificates", 1, 200);
        validateArray(request.get("filesystemConfigurations"), "filesystemConfigurations", 0, 10);
        if (browser) {
            validateArray(request.get("enterprisePolicies"), "enterprisePolicies", 0, 100);
        }
    }

    private static void validateDescription(JsonNode description) {
        if (description == null || description.isNull()) {
            return;
        }
        if (!description.isTextual() || description.asText().length() < 1
                || description.asText().length() > 4096) {
            throw new AwsException("ValidationException", "description must be between 1 and 4096 characters", 400);
        }
    }

    private static void validateArray(JsonNode value, String field, int min, int max) {
        if (value == null || value.isNull()) {
            return;
        }
        if (!value.isArray() || value.size() < min || value.size() > max) {
            throw new AwsException("ValidationException",
                    field + " must contain between " + min + " and " + max + " items", 400);
        }
    }

    private static void validateNetworkConfiguration(JsonNode networkConfiguration, boolean codeInterpreter) {
        JsonNode modeNode = networkConfiguration.get("networkMode");
        if (modeNode == null || !modeNode.isTextual() || modeNode.asText().isBlank()) {
            throw new AwsException("ValidationException", "networkConfiguration.networkMode is required", 400);
        }
        String mode = modeNode.asText();
        boolean valid = codeInterpreter
                ? "PUBLIC".equals(mode) || "SANDBOX".equals(mode) || "VPC".equals(mode)
                : "PUBLIC".equals(mode) || "VPC".equals(mode);
        if (!valid) {
            throw new AwsException("ValidationException", codeInterpreter
                    ? "networkMode must be PUBLIC, SANDBOX, or VPC"
                    : "networkMode must be PUBLIC or VPC", 400);
        }
        JsonNode vpcConfig = networkConfiguration.get("vpcConfig");
        if ("VPC".equals(mode) && (vpcConfig == null || !vpcConfig.isObject())) {
            throw new AwsException("ValidationException", "vpcConfig is required when networkMode is VPC", 400);
        }
        if (vpcConfig == null || vpcConfig.isNull()) {
            return;
        }
        if (!vpcConfig.isObject()) {
            throw new AwsException("ValidationException", "vpcConfig must be an object", 400);
        }
        validateVpcIds(vpcConfig.get("securityGroups"), "securityGroups", "sg-[0-9a-zA-Z]{8,17}");
        validateVpcIds(vpcConfig.get("subnets"), "subnets", "subnet-[0-9a-zA-Z]{8,17}");
    }

    private static void validateVpcIds(JsonNode value, String field, String pattern) {
        if (value == null || !value.isArray() || value.isEmpty() || value.size() > 16) {
            throw new AwsException("ValidationException", field + " must contain between 1 and 16 items", 400);
        }
        for (JsonNode item : value) {
            if (!item.isTextual() || !item.asText().matches(pattern)) {
                throw new AwsException("ValidationException", field + " contains an invalid identifier", 400);
            }
        }
    }

    private String deleteReplayKey(String family, String region, String id, String clientToken) {
        if (clientToken == null || clientToken.isBlank()) {
            return null;
        }
        return "delete-replay:" + family + ":" + regionResolver.getAccountId() + ":"
                + region + ":" + id + ":" + clientToken;
    }

    private static String requiredText(ObjectNode request, String field) {
        String value = optionalText(request, field);
        if (value == null || value.isBlank()) {
            throw new AwsException("ValidationException", field + " is required", 400);
        }
        return value;
    }

    private static String optionalText(JsonNode request, String field) {
        JsonNode value = request.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String random(int length) {
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            value.append(ALNUM.charAt(ThreadLocalRandom.current().nextInt(ALNUM.length())));
        }
        return value.toString();
    }

    private static String key(String family, String region, String id) {
        return prefix(family, region) + id;
    }

    private static String prefix(String family, String region) {
        return family + ":" + region + ":";
    }
}
