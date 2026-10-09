package io.github.hectorvent.floci.services.elasticache;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

public class ServerlessTestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("floci.services.rds.mock", "true", "floci.services.docdb.mock", "true",
                "floci.docker.resource-namespace", "issue4650-serverless");
    }
}
