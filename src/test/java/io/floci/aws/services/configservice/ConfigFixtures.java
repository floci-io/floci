package io.floci.aws.services.configservice;

import io.floci.aws.services.configservice.model.ConfigRule;
import io.floci.aws.services.configservice.model.ConfigRuleSource;
import io.floci.aws.services.configservice.model.ConfigurationRecorder;

import java.util.List;

/**
 * An AWS Config holding the resources whose ARNs carry an id it mints: the config rule {@code versioning}, the
 * configuration recorder {@code default} and the conformance pack {@code pack}.
 */
public final class ConfigFixtures {

    /** The ARNs Config stored the rule, the recorder and the conformance pack under. */
    public record Stored(String rule, String recorder, String pack) {
    }

    private ConfigFixtures() {
    }

    /** Puts the rule, the recorder and the conformance pack in {@code region} and returns the ARNs they are under. */
    public static Stored stored(AwsConfigService config, String region) {
        String rule = config.putConfigRule(region, new ConfigRule("versioning", null, null, null, null,
                new ConfigRuleSource("AWS", "S3_BUCKET_VERSIONING_ENABLED", null, null), null, null, null, null, null))
                .configRuleArn();
        config.putConfigurationRecorder(region, new ConfigurationRecorder(null, "default", "role", null, null),
                List.of());
        String recorder = config.describeConfigurationRecorders(region, null, null).getFirst().arn();
        String pack = config.putConformancePack(region, "pack", "s3://bucket/template.yaml", null).conformancePackArn();
        return new Stored(rule, recorder, pack);
    }
}
