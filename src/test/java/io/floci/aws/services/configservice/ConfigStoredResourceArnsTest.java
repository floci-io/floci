package io.floci.aws.services.configservice;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.configservice.model.ConfigurationRecorder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What AWS Config gives the request-resources table of its own resources: the ARN a config rule, a configuration
 * recorder or a conformance pack of a name is stored under, the id Config minted for it included.
 */
class ConfigStoredResourceArnsTest {

    private static final String REGION = "us-east-1";

    private final AwsConfigService config = new AwsConfigService(new RegionResolver(REGION, "000000000000"), null);
    private final ConfigStoredResourceArns stored = new ConfigStoredResourceArns(config);

    @Test
    void aNameFindsTheArnItsResourceIsStoredUnderItsMintedIdIncluded() {
        ConfigFixtures.Stored arns = ConfigFixtures.stored(config, REGION);

        assertEquals(Optional.of(arns.rule()), stored.find("ConfigRule", List.of("versioning"), REGION));
        assertEquals(Optional.of(arns.recorder()), stored.find("ConfigurationRecorder", List.of("default"), REGION));
        assertEquals(Optional.of(arns.pack()), stored.find("ConformancePack", List.of("pack"), REGION));
    }

    @Test
    void aNameItHoldsNoneOfFindsNothing() {
        config.putConfigurationRecorder(REGION, new ConfigurationRecorder(null, "default", "role", null, null),
                List.of());
        assertEquals(Optional.empty(), stored.find("ConfigRule", List.of("missing"), REGION));
        assertEquals(Optional.empty(), stored.find("ConfigurationRecorder", List.of("other"), REGION));
        assertEquals(Optional.empty(), stored.find("ConformancePack", List.of("pack"), REGION));
        assertEquals(Optional.empty(), stored.find("ConfigurationRecorder", List.of("default"), "eu-west-1"));
        assertEquals(Optional.empty(), stored.find("AggregationAuthorization", List.of("default"), REGION));
    }
}
