package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class BedrockAgentCoreCredentialProviderService {

    private final StorageBackend<String, ObjectNode> storage;
    private final RegionResolver regionResolver;

    @Inject
    public BedrockAgentCoreCredentialProviderService(StorageFactory storageFactory,
                                                     RegionResolver regionResolver) {
        this(storageFactory.create("bedrockagentcore", "bedrock-agentcore-credential-providers.json",
                new TypeReference<Map<String, ObjectNode>>() {}), regionResolver);
    }

    BedrockAgentCoreCredentialProviderService(StorageBackend<String, ObjectNode> storage,
                                              RegionResolver regionResolver) {
        this.storage = storage;
        this.regionResolver = regionResolver;
    }

    public ObjectNode createApiKey(ObjectNode request, String region) {
        String name = requiredName(request);
        String key = key("apikey", region, name);
        if (storage.get(key).isPresent()) {
            throw new AwsException("ConflictException", "API key credential provider already exists: " + name, 409);
        }
        String source = text(request, "apiKeySecretSource");
        if (source == null) {
            source = "MANAGED";
        }
        if (!source.equals("MANAGED") && !source.equals("EXTERNAL")) {
            throw new AwsException("ValidationException", "apiKeySecretSource must be MANAGED or EXTERNAL", 400);
        }
        String apiKey = text(request, "apiKey");
        if (apiKey != null && apiKey.length() > 65536) {
            throw new AwsException("ValidationException", "apiKey exceeds maximum length of 65536", 400);
        }
        JsonNode secretConfig = request.get("apiKeySecretConfig");
        if (source.equals("EXTERNAL")) {
            if (secretConfig == null || !secretConfig.isObject()
                    || !secretConfig.hasNonNull("secretId") || !secretConfig.hasNonNull("jsonKey")) {
                throw new AwsException("ValidationException",
                        "apiKeySecretConfig with secretId and jsonKey is required for EXTERNAL source", 400);
            }
            String secretId = secretConfig.path("secretId").asText();
            String jsonKey = secretConfig.path("jsonKey").asText();
            if (secretId.length() < 1 || secretId.length() > 2048) {
                throw new AwsException("ValidationException", "secretId must be between 1 and 2048 characters", 400);
            }
            if (jsonKey.length() < 1 || jsonKey.length() > 128) {
                throw new AwsException("ValidationException", "jsonKey must be between 1 and 128 characters", 400);
            }
        }
        BedrockAgentCoreTagValidation.validateTags(request.get("tags"));
        Instant now = Instant.now();
        ObjectNode item = JsonNodeFactory.instance.objectNode();
        item.put("name", name);
        item.put("credentialProviderArn", credentialProviderArn(region, name));
        item.put("apiKeySecretSource", source);
        if (source.equals("EXTERNAL")) {
            String secretId = secretConfig.path("secretId").asText();
            String jsonKey = secretConfig.path("jsonKey").asText();
            item.putObject("apiKeySecretArn").put("secretArn", secretId);
            item.put("apiKeySecretJsonKey", jsonKey);
        } else {
            item.putObject("apiKeySecretArn").put("secretArn", managedSecretArn(region, name));
            item.put("apiKeySecretJsonKey", "apiKey");
        }
        item.put("createdTime", now.getEpochSecond());
        item.put("lastUpdatedTime", now.getEpochSecond());
        if (request.has("tags")) {
            item.set("tags", request.get("tags").deepCopy());
        }
        storage.put(key, item);
        return item.deepCopy();
    }

    public ObjectNode getApiKey(String name, String region) {
        validateName(name);
        return storage.get(key("apikey", region, name))
                .map(ObjectNode::deepCopy)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "API key credential provider not found: " + name, 404));
    }

    public PaginatedResult<ObjectNode> listApiKeys(Integer maxResults, String nextToken, String region) {
        List<ObjectNode> items = storage.scan(k -> k.startsWith(prefix("apikey", region))).stream()
                .map(ObjectNode::deepCopy)
                .toList();
        return Pagination.paginate(items, node -> node.path("name").asText(),
                maxResults, nextToken, 100, 100, "ValidationException");
    }

    public ObjectNode updateApiKey(ObjectNode request, String region) {
        String name = requiredName(request);
        ObjectNode item = getApiKey(name, region);
        String apiKey = text(request, "apiKey");
        if (apiKey != null && apiKey.length() > 65536) {
            throw new AwsException("ValidationException", "apiKey exceeds maximum length of 65536", 400);
        }
        String source = text(request, "apiKeySecretSource");
        JsonNode secretConfig = request.get("apiKeySecretConfig");
        if (source != null) {
            if (!source.equals("MANAGED") && !source.equals("EXTERNAL")) {
                throw new AwsException("ValidationException", "apiKeySecretSource must be MANAGED or EXTERNAL", 400);
            }
            item.put("apiKeySecretSource", source);
        } else {
            source = item.path("apiKeySecretSource").asText("MANAGED");
        }
        if (source.equals("EXTERNAL")) {
            if (secretConfig == null || !secretConfig.isObject()
                    || !secretConfig.hasNonNull("secretId") || !secretConfig.hasNonNull("jsonKey")) {
                throw new AwsException("ValidationException",
                        "apiKeySecretConfig with secretId and jsonKey is required for EXTERNAL source", 400);
            }
        }
        if (secretConfig != null && secretConfig.isObject()) {
            String secretId = secretConfig.path("secretId").asText();
            String jsonKey = secretConfig.path("jsonKey").asText();
            if (secretId.length() < 1 || secretId.length() > 2048) {
                throw new AwsException("ValidationException", "secretId must be between 1 and 2048 characters", 400);
            }
            if (jsonKey.length() < 1 || jsonKey.length() > 128) {
                throw new AwsException("ValidationException", "jsonKey must be between 1 and 128 characters", 400);
            }
            item.putObject("apiKeySecretArn").put("secretArn", secretId);
            item.put("apiKeySecretJsonKey", jsonKey);
        } else if (source.equals("MANAGED")) {
            item.putObject("apiKeySecretArn").put("secretArn", managedSecretArn(region, name));
            item.put("apiKeySecretJsonKey", "apiKey");
        }
        item.put("lastUpdatedTime", Instant.now().getEpochSecond());
        storage.put(key("apikey", region, name), item);
        return item.deepCopy();
    }

    public void deleteApiKey(String name, String region) {
        getApiKey(name, region);
        storage.delete(key("apikey", region, name));
    }

    public ObjectNode createOauth2(ObjectNode request, String region) {
        String name = requiredName(request);
        String key = key("oauth2", region, name);
        if (storage.get(key).isPresent()) {
            throw new AwsException("ConflictException", "OAuth2 credential provider already exists: " + name, 409);
        }
        String vendor = text(request, "credentialProviderVendor");
        validateOauthVendor(vendor);
        JsonNode configInput = request.get("oauth2ProviderConfigInput");
        if (configInput == null || !configInput.isObject() || configInput.size() != 1) {
            throw new AwsException("ValidationException",
                    "oauth2ProviderConfigInput must contain exactly one provider configuration", 400);
        }
        Map.Entry<String, JsonNode> configEntry = configInput.fields().next();
        if (!configEntry.getKey().equals(expectedOauthConfigKey(vendor))) {
            throw new AwsException("ValidationException",
                    "oauth2ProviderConfigInput does not match credentialProviderVendor", 400);
        }
        if (!configEntry.getValue().isObject()) {
            throw new AwsException("ValidationException", "OAuth2 provider configuration must be an object", 400);
        }
        ObjectNode config = ((ObjectNode) configEntry.getValue()).deepCopy();
        validateOauthProviderConfig(vendor, config);
        String source = text(config, "clientSecretSource");
        if (source == null) {
            source = "MANAGED";
        }
        if (!source.equals("MANAGED") && !source.equals("EXTERNAL")) {
            throw new AwsException("ValidationException", "clientSecretSource must be MANAGED or EXTERNAL", 400);
        }
        JsonNode secretConfig = config.get("clientSecretConfig");
        if (source.equals("EXTERNAL")) {
            validateSecretReference(secretConfig, "clientSecretConfig");
        }
        String clientSecret = text(config, "clientSecret");
        if (clientSecret != null && clientSecret.length() > 2048) {
            throw new AwsException("ValidationException", "clientSecret exceeds maximum length of 2048", 400);
        }
        BedrockAgentCoreTagValidation.validateTags(request.get("tags"));

        Instant now = Instant.now();
        ObjectNode item = JsonNodeFactory.instance.objectNode();
        item.put("name", name);
        item.put("credentialProviderArn", oauthCredentialProviderArn(region, name));
        item.put("credentialProviderVendor", vendor);
        item.put("callbackUrl", "https://bedrock-agentcore." + region + ".amazonaws.com/oauth2/callback");
        item.put("clientSecretSource", source);
        if (source.equals("EXTERNAL")) {
            item.putObject("clientSecretArn").put("secretArn", secretConfig.path("secretId").asText());
            item.put("clientSecretJsonKey", secretConfig.path("jsonKey").asText());
        } else {
            item.putObject("clientSecretArn").put("secretArn", managedOauthSecretArn(region, name));
            item.put("clientSecretJsonKey", "clientSecret");
        }
        ObjectNode outputUnion = item.putObject("oauth2ProviderConfigOutput");
        outputUnion.set(configEntry.getKey(), oauthOutputConfig(vendor, config));
        item.put("status", "READY");
        item.put("createdTime", now.getEpochSecond());
        item.put("lastUpdatedTime", now.getEpochSecond());
        if (request.has("tags")) {
            item.set("tags", request.get("tags").deepCopy());
        }
        storage.put(key, item);
        return item.deepCopy();
    }

    public ObjectNode getOauth2(String name, String region) {
        validateName(name);
        return storage.get(key("oauth2", region, name))
                .map(ObjectNode::deepCopy)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "OAuth2 credential provider not found: " + name, 404));
    }

    public PaginatedResult<ObjectNode> listOauth2(Integer maxResults, String nextToken, String region) {
        List<ObjectNode> items = storage.scan(k -> k.startsWith(prefix("oauth2", region))).stream()
                .map(ObjectNode::deepCopy)
                .toList();
        return Pagination.paginate(items, node -> node.path("name").asText(),
                maxResults, nextToken, 20, 20, "ValidationException");
    }

    public ObjectNode updateOauth2(ObjectNode request, String region) {
        String name = requiredName(request);
        ObjectNode item = getOauth2(name, region);
        String vendor = text(request, "credentialProviderVendor");
        validateOauthVendor(vendor);
        JsonNode configInput = request.get("oauth2ProviderConfigInput");
        if (configInput == null || !configInput.isObject() || configInput.size() != 1) {
            throw new AwsException("ValidationException",
                    "oauth2ProviderConfigInput must contain exactly one provider configuration", 400);
        }
        Map.Entry<String, JsonNode> configEntry = configInput.fields().next();
        if (!configEntry.getKey().equals(expectedOauthConfigKey(vendor)) || !configEntry.getValue().isObject()) {
            throw new AwsException("ValidationException",
                    "oauth2ProviderConfigInput does not match credentialProviderVendor", 400);
        }
        ObjectNode config = ((ObjectNode) configEntry.getValue()).deepCopy();
        validateOauthProviderConfig(vendor, config);
        String source = text(config, "clientSecretSource");
        if (source == null) {
            source = item.path("clientSecretSource").asText("MANAGED");
        }
        if (!source.equals("MANAGED") && !source.equals("EXTERNAL")) {
            throw new AwsException("ValidationException", "clientSecretSource must be MANAGED or EXTERNAL", 400);
        }
        JsonNode secretConfig = config.get("clientSecretConfig");
        if (source.equals("EXTERNAL")) {
            validateSecretReference(secretConfig, "clientSecretConfig");
        }
        String clientSecret = text(config, "clientSecret");
        if (clientSecret != null && clientSecret.length() > 2048) {
            throw new AwsException("ValidationException", "clientSecret exceeds maximum length of 2048", 400);
        }
        item.put("credentialProviderVendor", vendor);
        item.put("clientSecretSource", source);
        if (source.equals("EXTERNAL")) {
            item.putObject("clientSecretArn").put("secretArn", secretConfig.path("secretId").asText());
            item.put("clientSecretJsonKey", secretConfig.path("jsonKey").asText());
        } else {
            item.putObject("clientSecretArn").put("secretArn", managedOauthSecretArn(region, name));
            item.put("clientSecretJsonKey", "clientSecret");
        }
        ObjectNode outputUnion = JsonNodeFactory.instance.objectNode();
        outputUnion.set(configEntry.getKey(), oauthOutputConfig(vendor, config));
        item.set("oauth2ProviderConfigOutput", outputUnion);
        item.put("status", "READY");
        item.put("lastUpdatedTime", Instant.now().getEpochSecond());
        storage.put(key("oauth2", region, name), item);
        return item.deepCopy();
    }

    public void deleteOauth2(String name, String region) {
        getOauth2(name, region);
        storage.delete(key("oauth2", region, name));
    }

    private String credentialProviderArn(String region, String name) {
        return AwsArnUtils.Arn.of("acps", region, regionResolver.getAccountId(),
                "token-vault/default/apikeycredentialprovider/" + name).toString();
    }

    private String managedSecretArn(String region, String name) {
        return AwsArnUtils.Arn.of("secretsmanager", region, regionResolver.getAccountId(),
                "secret:agentcore-" + name).toString();
    }

    private String oauthCredentialProviderArn(String region, String name) {
        return AwsArnUtils.Arn.of("acps", region, regionResolver.getAccountId(),
                "token-vault/default/oauth2credentialprovider/" + name).toString();
    }

    private String managedOauthSecretArn(String region, String name) {
        return AwsArnUtils.Arn.of("secretsmanager", region, regionResolver.getAccountId(),
                "secret:agentcore-oauth2-" + name).toString();
    }

    private static ObjectNode oauthOutputConfig(String vendor, ObjectNode config) {
        ObjectNode output = JsonNodeFactory.instance.objectNode();
        if (config.hasNonNull("clientId")) {
            output.put("clientId", config.get("clientId").asText());
        }
        if ("CustomOauth2".equals(vendor)) {
            if (config.has("oauthDiscovery")) {
                output.set("oauthDiscovery", config.get("oauthDiscovery").deepCopy());
            }
            for (String field : List.of("clientAuthenticationMethod", "onBehalfOfTokenExchangeConfig",
                    "privateEndpoint", "privateEndpointOverrides", "privateKeyJwtConfig")) {
                if (config.has(field)) {
                    output.set(field, config.get(field).deepCopy());
                }
            }
        } else {
            output.set("oauthDiscovery", syntheticOauthDiscovery(vendor));
        }
        return output;
    }

    private static ObjectNode syntheticOauthDiscovery(String vendor) {
        ObjectNode discovery = JsonNodeFactory.instance.objectNode();
        ObjectNode metadata = discovery.putObject("authorizationServerMetadata");
        String slug = vendor.replace("Oauth2", "").toLowerCase();
        metadata.put("issuer", "https://" + slug + ".oauth.local");
        metadata.put("authorizationEndpoint", "https://" + slug + ".oauth.local/authorize");
        metadata.put("tokenEndpoint", "https://" + slug + ".oauth.local/token");
        return discovery;
    }

    private static void validateOauthVendor(String vendor) {
        if (vendor == null || !java.util.Set.of(
                "GoogleOauth2", "GithubOauth2", "SlackOauth2", "SalesforceOauth2", "MicrosoftOauth2",
                "CustomOauth2", "AtlassianOauth2", "LinkedinOauth2", "XOauth2", "OktaOauth2",
                "OneLoginOauth2", "PingOneOauth2", "FacebookOauth2", "YandexOauth2", "RedditOauth2",
                "ZoomOauth2", "TwitchOauth2", "SpotifyOauth2", "DropboxOauth2", "NotionOauth2",
                "HubspotOauth2", "CyberArkOauth2", "FusionAuthOauth2", "Auth0Oauth2", "CognitoOauth2")
                .contains(vendor)) {
            throw new AwsException("ValidationException", "credentialProviderVendor is invalid", 400);
        }
    }

    private static String expectedOauthConfigKey(String vendor) {
        return switch (vendor) {
            case "GoogleOauth2" -> "googleOauth2ProviderConfig";
            case "GithubOauth2" -> "githubOauth2ProviderConfig";
            case "SlackOauth2" -> "slackOauth2ProviderConfig";
            case "SalesforceOauth2" -> "salesforceOauth2ProviderConfig";
            case "MicrosoftOauth2" -> "microsoftOauth2ProviderConfig";
            case "CustomOauth2" -> "customOauth2ProviderConfig";
            case "AtlassianOauth2" -> "atlassianOauth2ProviderConfig";
            case "LinkedinOauth2" -> "linkedinOauth2ProviderConfig";
            default -> "includedOauth2ProviderConfig";
        };
    }

    private static void validateOauthProviderConfig(String vendor, ObjectNode config) {
        if ("CustomOauth2".equals(vendor)) {
            JsonNode discovery = config.get("oauthDiscovery");
            if (discovery == null || !discovery.isObject() || discovery.size() != 1) {
                throw new AwsException("ValidationException",
                        "custom OAuth2 configuration requires exactly one oauthDiscovery member", 400);
            }
            if (discovery.has("discoveryUrl")) {
                String discoveryUrl = discovery.path("discoveryUrl").asText();
                if (!discoveryUrl.matches(".+/\\.well-known/(openid-configuration|oauth-authorization-server)")) {
                    throw new AwsException("ValidationException", "oauthDiscovery.discoveryUrl is invalid", 400);
                }
            } else if (discovery.has("authorizationServerMetadata")) {
                JsonNode metadata = discovery.get("authorizationServerMetadata");
                if (metadata == null || !metadata.isObject()
                        || !metadata.hasNonNull("issuer")
                        || !metadata.hasNonNull("authorizationEndpoint")
                        || !metadata.hasNonNull("tokenEndpoint")) {
                    throw new AwsException("ValidationException",
                            "authorizationServerMetadata requires issuer, authorizationEndpoint, and tokenEndpoint", 400);
                }
            } else {
                throw new AwsException("ValidationException", "oauthDiscovery union member is invalid", 400);
            }
        } else {
            String clientId = text(config, "clientId");
            if (clientId == null || clientId.length() < 1 || clientId.length() > 256) {
                throw new AwsException("ValidationException", "clientId must be between 1 and 256 characters", 400);
            }
        }
        String clientId = text(config, "clientId");
        if (clientId != null && clientId.length() > 256) {
            throw new AwsException("ValidationException", "clientId must not exceed 256 characters", 400);
        }
    }

    private static void validateSecretReference(JsonNode secretConfig, String fieldName) {
        if (secretConfig == null || !secretConfig.isObject()
                || !secretConfig.hasNonNull("secretId") || !secretConfig.hasNonNull("jsonKey")) {
            throw new AwsException("ValidationException",
                    fieldName + " with secretId and jsonKey is required", 400);
        }
        String secretId = secretConfig.path("secretId").asText();
        String jsonKey = secretConfig.path("jsonKey").asText();
        if (secretId.length() < 1 || secretId.length() > 2048) {
            throw new AwsException("ValidationException", "secretId must be between 1 and 2048 characters", 400);
        }
        if (jsonKey.length() < 1 || jsonKey.length() > 128) {
            throw new AwsException("ValidationException", "jsonKey must be between 1 and 128 characters", 400);
        }
    }

    private static String requiredName(ObjectNode request) {
        String name = text(request, "name");
        validateName(name);
        return name;
    }

    private static void validateName(String name) {
        if (name == null || name.length() < 1 || name.length() > 128 || !name.matches("[a-zA-Z0-9\\-_]+")) {
            throw new AwsException("ValidationException", "name must match [a-zA-Z0-9\\-_]+ and be 1-128 characters", 400);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String key(String type, String region, String name) {
        return prefix(type, region) + name;
    }

    private static String prefix(String type, String region) {
        return "credential-provider:" + type + ":" + region + ":";
    }
}
