package io.floci.aws.services.configservice;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.configservice.model.ConfigRule;
import io.floci.aws.services.configservice.model.ConfigRuleSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * PutConfigRule updates the rule its request names: the PutConfigRule reference lets an update name its rule by
 * ConfigRuleName, ConfigRuleId or ConfigRuleArn and requires the name only of a new rule.
 */
class AwsConfigServicePutConfigRuleTest {

    private static final String REGION = "us-east-1";
    private static final ConfigRuleSource SOURCE = new ConfigRuleSource("AWS", "S3_BUCKET_VERSIONING_ENABLED", null,
            null);

    private final AwsConfigService config = new AwsConfigService(new RegionResolver(REGION, "000000000000"), null);

    @Test
    void anUpdateNamingItsRuleByIdOrArnUpdatesThatRuleWhichKeepsItsName() {
        ConfigRule first = put("first", null, null, "one");
        ConfigRule second = put("second", null, null, "two");

        ConfigRule updated = put("second", first.configRuleId(), null, "by id");
        assertEquals("first", updated.configRuleName());
        assertEquals(first.configRuleArn(), updated.configRuleArn());
        assertEquals(List.of("by id", "two"), descriptions());

        put(null, null, second.configRuleArn(), "by arn");
        assertEquals(List.of("by id", "by arn"), descriptions());
    }

    @Test
    void anIdOrArnNamingNoRuleIsRefused() {
        put("first", null, null, "one");
        assertEquals("InvalidParameterValueException",
                assertThrows(AwsException.class, () -> put("first", "config-rule-missing", null, "x")).getErrorCode());
        assertEquals("InvalidParameterValueException", assertThrows(AwsException.class,
                () -> put("first", null, "arn:aws:config:us-east-1:000000000000:config-rule/config-rule-missing", "x"))
                .getErrorCode());
        assertEquals(List.of("one"), descriptions());
    }

    private ConfigRule put(String name, String id, String arn, String description) {
        return config.putConfigRule(REGION, new ConfigRule(name, arn, id, description, null, SOURCE, null, null, null,
                null, null));
    }

    private List<String> descriptions() {
        return config.describeConfigRules(REGION, null).stream().map(ConfigRule::description).toList();
    }
}
