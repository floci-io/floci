package io.floci.aws.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/** Mocks the RDS container layer and reports the requested Port instead of the proxy port. */
public class RdsAdvertisePortProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("floci.services.rds.mock", "true",
                "floci.services.rds.advertise-requested-port", "true");
    }
}
