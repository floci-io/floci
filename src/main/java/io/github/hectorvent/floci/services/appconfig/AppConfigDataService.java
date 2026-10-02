package io.github.hectorvent.floci.services.appconfig;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.appconfig.model.Application;
import io.github.hectorvent.floci.services.appconfig.model.ConfigurationProfile;
import io.github.hectorvent.floci.services.appconfig.model.ConfigurationSession;
import io.github.hectorvent.floci.services.appconfig.model.Environment;
import io.github.hectorvent.floci.services.appconfig.model.HostedConfigurationVersion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class AppConfigDataService {
    private static final Logger LOG = Logger.getLogger(AppConfigDataService.class);

    private final StorageBackend<String, ConfigurationSession> sessionStore;
    private final AppConfigService appConfigService;
    private final ObjectMapper objectMapper;

    @Inject
    public AppConfigDataService(StorageFactory storageFactory, AppConfigService appConfigService,
                                ObjectMapper objectMapper) {
        this.sessionStore = storageFactory.create("appconfigdata", "appconfigdata-sessions.json", new TypeReference<>() {});
        this.appConfigService = appConfigService;
        this.objectMapper = objectMapper;
    }

    public String startConfigurationSession(Map<String, Object> request) {
        String appId = (String) request.get("ApplicationIdentifier");
        String envId = (String) request.get("EnvironmentIdentifier");
        String profileId = (String) request.get("ConfigurationProfileIdentifier");

        requireIdentifier(appId, "ApplicationIdentifier");
        requireIdentifier(envId, "EnvironmentIdentifier");
        requireIdentifier(profileId, "ConfigurationProfileIdentifier");

        // Like AWS, each identifier is either the resource's ID or its name.
        Application application = appConfigService.resolveApplication(appId);
        Environment environment = appConfigService.resolveEnvironment(application.getId(), envId);
        ConfigurationProfile profile = appConfigService.resolveConfigurationProfile(application.getId(), profileId);
        appId = application.getId();
        envId = environment.getId();
        profileId = profile.getId();

        ConfigurationSession session = new ConfigurationSession();
        session.setId(UUID.randomUUID().toString());
        session.setApplicationId(appId);
        session.setEnvironmentId(envId);
        session.setConfigurationProfileId(profileId);
        int pollInterval = parsePollInterval(request.getOrDefault("RequiredMinimumPollIntervalInSeconds", 15));
        session.setRequiredMinimumPollIntervalInSeconds(pollInterval);
        session.setCurrentToken(UUID.randomUUID().toString());

        sessionStore.put(session.getCurrentToken(), session);
        LOG.infov("Started AppConfigData session {0} for app {1}, env {2}, profile {3}", session.getId(), appId, envId, profileId);
        return session.getCurrentToken();
    }

    private static int parsePollInterval(Object value) {
        if (!(value instanceof Number)) {
            throw invalidPollInterval();
        }
        try {
            long interval = new BigDecimal(value.toString()).longValueExact();
            if (interval < 15 || interval > 86400) {
                throw invalidPollInterval();
            }
            return (int) interval;
        } catch (NumberFormatException | ArithmeticException e) {
            throw invalidPollInterval();
        }
    }

    private static void requireIdentifier(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new AwsException("BadRequestException", name + " is required", 400);
        }
    }

    private static AwsException invalidPollInterval() {
        return new AwsException("BadRequestException",
                "RequiredMinimumPollIntervalInSeconds must be an integer between 15 and 86400", 400);
    }

    public ConfigurationData getLatestConfiguration(String token) {
        return getLatestConfiguration(token, null);
    }

    /** {@code accept} is the caller's Accept header: the AppConfig Agent asks for Ion to evaluate variant flags itself. */
    public ConfigurationData getLatestConfiguration(String token, String accept) {
        ConfigurationSession session = sessionStore.get(token)
                .orElseThrow(() -> new AwsException("BadRequestException", "Invalid configuration token", 400));

        int pollInterval = normalizePollInterval(session.getRequiredMinimumPollIntervalInSeconds());
        session.setRequiredMinimumPollIntervalInSeconds(pollInterval);

        String activeVersion = appConfigService.getActiveVersion(session.getEnvironmentId(), session.getConfigurationProfileId());
        
        HostedConfigurationVersion version = null;
        if (activeVersion != null && !activeVersion.equals(session.getLastConfigurationVersion())) {
            try {
                version = appConfigService.getHostedConfigurationVersion(session.getApplicationId(), session.getConfigurationProfileId(), Integer.parseInt(activeVersion));
                session.setLastConfigurationVersion(activeVersion);
            } catch (Exception e) {
                LOG.warnv("Active version {0} not found for session {1}", activeVersion, session.getId());
            }
        }

        // Generate next token
        String nextToken = UUID.randomUUID().toString();
        session.setCurrentToken(nextToken);
        sessionStore.delete(token); // Old token is invalid
        sessionStore.put(nextToken, session);

        byte[] content = new byte[0];
        String contentType = "application/octet-stream";
        if (version != null) {
            ResolvedContent resolved = resolveContent(session, version, accept);
            content = resolved.bytes();
            contentType = resolved.contentType();
        }
        String versionLabel = (version != null) ? String.valueOf(version.getVersionNumber()) : "";

        return new ConfigurationData(content, contentType, versionLabel, nextToken,
                pollInterval);
    }

    private ResolvedContent resolveContent(ConfigurationSession session, HostedConfigurationVersion version,
                                           String accept) {
        ConfigurationProfile profile = appConfigService.getConfigurationProfile(
                session.getApplicationId(), session.getConfigurationProfileId());
        if (!"AWS.AppConfig.FeatureFlags".equals(profile.getType())) {
            return new ResolvedContent(version.getContent(), version.getContentType());
        }
        // As AWS does: a caller that accepts Ion gets variant flags whole, to evaluate against its own context.
        if (acceptsFeatureFlagIon(accept) && FeatureFlagIonEncoder.hasVariants(version.getContent(), objectMapper)) {
            byte[] ion = FeatureFlagIonEncoder.encode(version.getContent(), objectMapper);
            if (ion != null) {
                return new ResolvedContent(ion, FeatureFlagIonEncoder.CONTENT_TYPE);
            }
            LOG.warnv("Feature flag profile {0} has variants Floci cannot encode as Ion; returning the stored JSON",
                    profile.getId());
        }
        return new ResolvedContent(transformFeatureFlags(version.getContent(), objectMapper),
                version.getContentType());
    }

    static boolean acceptsFeatureFlagIon(String accept) {
        if (accept == null) {
            return false;
        }
        for (String range : accept.split(",")) {
            String[] parts = range.split(";");
            if (!"application/ion".equalsIgnoreCase(parts[0].trim())) {
                continue;
            }
            boolean featureFlags = false;
            boolean acceptable = true;
            for (int i = 1; i < parts.length; i++) {
                String[] parameter = parts[i].trim().split("=", 2);
                if (parameter.length != 2) {
                    continue;
                }
                String name = parameter[0].trim();
                String value = parameter[1].trim();
                if (name.equalsIgnoreCase("type")) {
                    featureFlags = "AWS.AppConfig.FeatureFlags".equalsIgnoreCase(value);
                } else if (name.equalsIgnoreCase("q")) {
                    acceptable = !value.matches("0(\\.0+)?");
                }
            }
            if (featureFlags && acceptable) {
                return true;
            }
        }
        return false;
    }

    static byte[] transformFeatureFlags(byte[] content, ObjectMapper objectMapper) {
        if (content == null || content.length == 0) {
            return content;
        }
        try {
            JsonNode document = objectMapper.readTree(content);
            if (document == null || !document.isObject()) {
                return content;
            }
            JsonNode values = document.get("values");
            if (values == null || !values.isObject()) {
                return content;
            }

            ObjectNode retrieval = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = values.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                // Multi-variant feature flags require context evaluation and Amazon Ion output, which only a
                // caller that accepts Ion gets; preserve the stored bytes for any other.
                ObjectNode flag = retrievalFlag(entry.getValue());
                if (flag == null) {
                    return content;
                }
                retrieval.set(entry.getKey(), flag);
            }
            return objectMapper.writeValueAsBytes(retrieval);
        } catch (IOException e) {
            LOG.debugv(e, "Could not convert AppConfig feature flags to retrieval format");
            return content;
        }
    }

    /**
     * A basic flag's value in the retrieval-time format: only {@code enabled} if it is off, else its attributes too.
     * {@code null} for a flag with variants or one that is not a valid basic flag.
     */
    static ObjectNode retrievalFlag(JsonNode definition) {
        if (!definition.isObject() || definition.has("_variants")) {
            return null;
        }
        JsonNode enabled = definition.get("enabled");
        if (enabled == null || !enabled.isBoolean()) {
            return null;
        }
        if (!enabled.booleanValue()) {
            return JsonNodeFactory.instance.objectNode().put("enabled", false);
        }
        ObjectNode flag = definition.deepCopy();
        flag.remove("_createdAt");
        flag.remove("_updatedAt");
        return flag;
    }

    static int normalizePollInterval(int interval) {
        return interval >= 15 && interval <= 86400 ? interval : 15;
    }

    private record ResolvedContent(byte[] bytes, String contentType) {}

    public record ConfigurationData(byte[] content, String contentType, String configurationVersion,
                                    String nextPollConfigurationToken, int nextPollIntervalInSeconds) {}
}
