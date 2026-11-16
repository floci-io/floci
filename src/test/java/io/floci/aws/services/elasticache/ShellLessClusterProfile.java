package io.floci.aws.services.elasticache;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * The shell-less backend path with the default Valkey image, for cluster mode. KiviDB, the image
 * {@link ShellLessBackendProfile} uses, has no {@code --cluster-announce-client-ipv4} flag, which
 * Floci passes to every cluster-mode node, so it cannot form a Floci cluster yet; Valkey takes the
 * same container arguments and shows that the cluster-mode flags arrive and the cluster forms.
 */
public class ShellLessClusterProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("floci.services.elasticache.shell-less-backend", "true");
    }
}
