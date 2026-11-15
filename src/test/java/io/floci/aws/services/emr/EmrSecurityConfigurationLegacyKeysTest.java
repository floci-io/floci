package io.floci.aws.services.emr;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.emr.model.EmrCluster;
import io.floci.aws.services.emr.model.SecurityConfiguration;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.floci.aws.services.emr.EmrServiceFixture.ACCOUNT;
import static io.floci.aws.services.emr.EmrServiceFixture.REGION;
import static io.floci.aws.services.emr.EmrServiceFixture.cluster;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Security configurations stored before they were keyed by Region, under their bare name, move at startup to the
 * default Region, and every other Region where one of the account's clusters names one gets a copy of it, so the
 * cluster's configuration is still described there.
 */
class EmrSecurityConfigurationLegacyKeysTest {

    private static final String CLUSTER_REGION = "eu-central-1";

    @Test
    void aLegacyConfigurationMovesToTheDefaultRegionAndIsCopiedToTheRegionsOfTheClustersNamingIt() {
        EmrServiceFixture fixture = new EmrServiceFixture();
        AccountAwareStorageBackend<SecurityConfiguration> configurations = fixture.storage.create("emr",
                "emr-security-configs.json", new TypeReference<Map<String, SecurityConfiguration>>() {});
        configurations.put("used", configuration("used"));
        configurations.put("unused", configuration("unused"));
        // A name may hold the separator itself: it is still a key stored before.
        configurations.put("team::prod", configuration("team::prod"));
        String clusterId = fixture.service.runJobFlow(cluster("secured", true), CLUSTER_REGION).getId();
        AccountAwareStorageBackend<EmrCluster> clusters = fixture.storage.create("emr", "emr-clusters.json",
                new TypeReference<Map<String, EmrCluster>>() {});
        EmrCluster stored = clusters.get(clusterId).orElseThrow();
        stored.setSecurityConfiguration("used");
        clusters.put(clusterId, stored);

        EmrService restarted = fixture.restartImmediate().service;

        assertEquals(Set.of(REGION + "::used", REGION + "::unused", REGION + "::team::prod", CLUSTER_REGION + "::used"),
                configurations.keysForAccount(ACCOUNT));
        assertEquals(Set.of("used", "unused", "team::prod"), names(restarted.listSecurityConfigurations(REGION)));
        assertEquals(Set.of("used"), names(restarted.listSecurityConfigurations(CLUSTER_REGION)));
        assertEquals(Set.of(), names(restarted.listSecurityConfigurations("eu-west-1")));
        assertEquals("{\"EncryptionConfiguration\":{}}",
                restarted.describeSecurityConfiguration("used", CLUSTER_REGION).getSecurityConfiguration());
    }

    private static SecurityConfiguration configuration(String name) {
        SecurityConfiguration configuration = new SecurityConfiguration();
        configuration.setName(name);
        configuration.setSecurityConfiguration("{\"EncryptionConfiguration\":{}}");
        configuration.setCreationDateTime(Instant.parse("2026-11-01T00:00:00Z"));
        return configuration;
    }

    private static Set<String> names(List<SecurityConfiguration> configurations) {
        return Set.copyOf(configurations.stream().map(SecurityConfiguration::getName).toList());
    }
}
