package io.floci.aws.config;

import io.floci.aws.testing.ParallelLocks;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ResourceLock(ParallelLocks.TLS_CONFIG)
class AlbZoneRuleConfigTest {
    @ConfigMapping(prefix = "floci.services.elbv2")
    interface Settings extends EmulatorConfig.ElbV2ServiceConfig {}

    @Test
    void defaultsToAwsWithNoRegionalOverrides() {
        Settings settings = settings(Map.of());
        assertEquals(EmulatorConfig.AlbZoneRule.AWS, settings.albZoneRule());
        assertEquals(Map.of(), settings.albZoneRuleByRegion());
    }

    @Test
    void mapsGlobalAndRegionalRulesIndependently() {
        Settings settings = settings(Map.of(
                "floci.services.elbv2.alb-zone-rule", "allow-single-zone",
                "floci.services.elbv2.alb-zone-rule-by-region.ap-northeast-3", "aws",
                "floci.services.elbv2.alb-zone-rule-by-region.us-east-1", "allow-single-zone"));
        assertEquals(EmulatorConfig.AlbZoneRule.ALLOW_SINGLE_ZONE, settings.albZoneRule());
        assertEquals(Map.of("ap-northeast-3", EmulatorConfig.AlbZoneRule.AWS,
                "us-east-1", EmulatorConfig.AlbZoneRule.ALLOW_SINGLE_ZONE), settings.albZoneRuleByRegion());
    }

    @Test
    void rejectsAnUnknownRule() {
        assertThrows(RuntimeException.class,
                () -> settings(Map.of("floci.services.elbv2.alb-zone-rule", "anything")));
    }

    private static Settings settings(Map<String, String> properties) {
        return new SmallRyeConfigBuilder().addDefaultInterceptors().withMapping(Settings.class)
                .withSources(new PropertiesConfigSource(properties, "zone-rule-test", 400))
                .build().getConfigMapping(Settings.class);
    }
}
