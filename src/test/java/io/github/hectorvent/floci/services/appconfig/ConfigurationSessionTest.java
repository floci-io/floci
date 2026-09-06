package io.github.hectorvent.floci.services.appconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.appconfig.model.ConfigurationSession;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigurationSessionTest {
    @Test
    void legacySessionWithoutPollIntervalUsesAwsDefault() throws Exception {
        ConfigurationSession session = new ObjectMapper().readValue(
                "{\"id\":\"legacy\",\"applicationId\":\"app\",\"environmentId\":\"env\",\"configurationProfileId\":\"profile\"}",
                ConfigurationSession.class);

        assertEquals(15, session.getRequiredMinimumPollIntervalInSeconds());
    }

    @Test
    void invalidPersistedPollIntervalUsesAwsDefault() {
        assertEquals(15, AppConfigDataService.normalizePollInterval(0));
        assertEquals(15, AppConfigDataService.normalizePollInterval(86401));
    }
}
