package io.github.hectorvent.floci.config;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import java.util.LinkedHashMap;
import java.util.Map;

@QuarkusTest
@TestProfile(EcrTlsPathUriIntegrationTest.Profile.class)
class EcrTlsPathUriIntegrationTest extends EcrTlsUriIntegrationTest {

    @Override
    String expectedRepositoryUri(String repository) {
        return "localhost.floci.io:" + ADVERTISED_PORT + "/" + ACCOUNT + "/" + REGION + "/" + repository;
    }

    public static final class Profile extends EcrTlsUriIntegrationTest.Profile {

        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new LinkedHashMap<>(super.getConfigOverrides());
            overrides.put("floci.services.ecr.uri-style", "path");
            return overrides;
        }
    }
}
