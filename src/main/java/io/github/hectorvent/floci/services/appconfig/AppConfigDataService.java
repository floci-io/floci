package io.github.hectorvent.floci.services.appconfig;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.appconfig.model.ConfigurationSession;
import io.github.hectorvent.floci.services.appconfig.model.HostedConfigurationVersion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class AppConfigDataService {
    private static final Logger LOG = Logger.getLogger(AppConfigDataService.class);

    private final StorageBackend<String, ConfigurationSession> sessionStore;
    private final AppConfigService appConfigService;

    @Inject
    public AppConfigDataService(StorageFactory storageFactory, AppConfigService appConfigService) {
        this.sessionStore = storageFactory.create("appconfigdata", "appconfigdata-sessions.json", new TypeReference<>() {});
        this.appConfigService = appConfigService;
    }

    public String startConfigurationSession(Map<String, Object> request) {
        String appId = (String) request.get("ApplicationIdentifier");
        String envId = (String) request.get("EnvironmentIdentifier");
        String profileId = (String) request.get("ConfigurationProfileIdentifier");

        // Validate resources exist
        appConfigService.getEnvironment(appId, envId);
        appConfigService.getConfigurationProfile(appId, profileId);

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

    private static AwsException invalidPollInterval() {
        return new AwsException("BadRequestException",
                "RequiredMinimumPollIntervalInSeconds must be an integer between 15 and 86400", 400);
    }

    public ConfigurationData getLatestConfiguration(String token) {
        ConfigurationSession session = sessionStore.get(token)
                .orElseThrow(() -> new AwsException("BadRequestException", "Invalid configuration token", 400));

        int pollInterval = normalizePollInterval(session.getRequiredMinimumPollIntervalInSeconds());
        session.setRequiredMinimumPollIntervalInSeconds(pollInterval);

        String activeVersion = appConfigService.getActiveVersion(session.getEnvironmentId(), session.getConfigurationProfileId());
        
        HostedConfigurationVersion version = null;
        if (activeVersion != null) {
            try {
                version = appConfigService.getHostedConfigurationVersion(session.getApplicationId(), session.getConfigurationProfileId(), Integer.parseInt(activeVersion));
            } catch (Exception e) {
                LOG.warnv("Active version {0} not found for session {1}", activeVersion, session.getId());
            }
        }

        // Generate next token
        String nextToken = UUID.randomUUID().toString();
        session.setCurrentToken(nextToken);
        sessionStore.delete(token); // Old token is invalid
        sessionStore.put(nextToken, session);

        byte[] content = (version != null) ? version.getContent() : new byte[0];
        String contentType = (version != null) ? version.getContentType() : "application/octet-stream";
        String versionLabel = (version != null) ? String.valueOf(version.getVersionNumber()) : "";

        return new ConfigurationData(content, contentType, versionLabel, nextToken,
                pollInterval);
    }

    static int normalizePollInterval(int interval) {
        return interval >= 15 && interval <= 86400 ? interval : 15;
    }

    public record ConfigurationData(byte[] content, String contentType, String configurationVersion,
                                    String nextPollConfigurationToken, int nextPollIntervalInSeconds) {}
}
