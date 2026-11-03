package io.github.hectorvent.floci.services.route53;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Route53NameserverConfigTest {
    @Test
    void explicitDefaultLiteralIsDistinctFromAnUnsetNameserver() {
        assertEquals(Optional.empty(), config(Map.of()).services().route53().defaultNameserver1());
        assertEquals(Optional.of("ns-1.awsdns-01.org"), config(Map.of(
                "FLOCI_SERVICES_ROUTE53_DEFAULT_NAMESERVER1", "ns-1.awsdns-01.org"))
                .services().route53().defaultNameserver1());
    }

    private static EmulatorConfig config(Map<String, String> environment) {
        return new SmallRyeConfigBuilder()
                .addDefaultInterceptors()
                .withSources(new EnvConfigSource(environment, 300))
                .withMapping(EmulatorConfig.class)
                .build().getConfigMapping(EmulatorConfig.class);
    }
}
